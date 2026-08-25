import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches the single GraalVM points-to check that aborts this image on Windows.
 *
 * The check requires every {@code @Delete} invoke to have exactly one callee
 * before devirtualizing it. The Windows analysis can retain multiple callees;
 * those targets are unsupported methods that throw if reached, so leaving the
 * invoke polymorphic preserves runtime behavior. The rewrite reports the
 * callees and skips only that optimization.
 *
 * Output is passed to native-image as a patch-module directory for
 * {@code org.graalvm.nativeimage.pointsto}. The exact rewrite count is asserted
 * so a GraalVM update fails here instead of silently applying the wrong patch.
 */
public final class PointstoPatcher {
    private static final String TARGET =
            "com/oracle/graal/pointsto/results/TypeFlowSimplifier";
    private static final String MESSAGE = "@Delete methods should have a single callee.";

    public static void main(String[] args) throws IOException {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: PointstoPatcher <pointsto.jar> <outDir>");
        }
        Path jarPath = Path.of(args[0]);
        Path outDir = Path.of(args[1]);

        byte[] patched;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            JarEntry entry = jar.getJarEntry(TARGET + ".class");
            if (entry == null) {
                throw new IllegalStateException("not found in " + jarPath + ": " + TARGET);
            }
            try (InputStream in = jar.getInputStream(entry)) {
                patched = patch(in.readAllBytes());
            }
        }

        Path target = outDir.resolve(TARGET + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, patched);
        System.out.println("patched " + TARGET + " -> " + target);
    }

    private static byte[] patch(byte[] original) {
        ClassNode node = new ClassNode();
        new ClassReader(original).accept(node, 0);

        int rewritten = 0;
        for (MethodNode method : node.methods) {
            rewritten += patchMethod(method) ? 1 : 0;
        }
        if (rewritten != 1) {
            throw new IllegalStateException(
                    "expected exactly 1 @Delete guarantee, rewrote " + rewritten);
        }

        // Reuse an existing branch target, so existing stack-map frames remain valid.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static boolean patchMethod(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof LdcInsnNode ldc) || !MESSAGE.equals(ldc.cst)) {
                continue;
            }

            MethodInsnNode guarantee = null;
            for (AbstractInsnNode cursor = insn; cursor != null; cursor = cursor.getNext()) {
                if (cursor instanceof MethodInsnNode call
                        && "guarantee".equals(call.name)
                        && call.owner.endsWith("AnalysisError")) {
                    guarantee = call;
                    break;
                }
            }
            if (guarantee == null) {
                continue;
            }

            VarInsnNode calleesLoad = null;
            AbstractInsnNode conditionStart = null;
            for (AbstractInsnNode cursor = insn; cursor != null; cursor = cursor.getPrevious()) {
                if (cursor instanceof MethodInsnNode call && "size".equals(call.name)) {
                    AbstractInsnNode load = call.getPrevious();
                    if (load instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                        calleesLoad = var;
                        conditionStart = var;
                    }
                    break;
                }
            }
            if (calleesLoad == null) {
                continue;
            }

            LabelNode skipTarget = null;
            for (AbstractInsnNode cursor = conditionStart;
                    cursor != null; cursor = cursor.getPrevious()) {
                if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    skipTarget = jump.label;
                    break;
                }
            }
            if (skipTarget == null) {
                continue;
            }

            InsnList replacement = new InsnList();
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC,
                    "java/lang/System", "err", "Ljava/io/PrintStream;"));
            replacement.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
            replacement.add(new InsnNode(Opcodes.DUP));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/StringBuilder", "<init>", "()V", false));
            replacement.add(new LdcInsnNode("[mcweb] @Delete invoke, callees="));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, calleesLoad.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "append",
                    "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

            replacement.add(new VarInsnNode(Opcodes.ALOAD, calleesLoad.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/Collection", "size", "()I", true));
            replacement.add(new InsnNode(Opcodes.ICONST_1));
            replacement.add(new JumpInsnNode(Opcodes.IF_ICMPNE, skipTarget));

            AbstractInsnNode cursor = conditionStart;
            while (cursor != null && cursor != guarantee) {
                AbstractInsnNode next = cursor.getNext();
                method.instructions.remove(cursor);
                cursor = next;
            }
            method.instructions.insertBefore(guarantee, replacement);
            method.instructions.remove(guarantee);
            System.out.println("patched " + method.name
                    + ": @Delete guarantee -> report and skip");
            return true;
        }
        return false;
    }

    private PointstoPatcher() {
    }
}
