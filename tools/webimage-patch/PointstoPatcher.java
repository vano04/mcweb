import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
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
 * Patches GraalVM's points-to analysis so a Windows image build can finish.
 *
 * On Windows -- and only on Windows; macOS and Linux build the same source with
 * the same GraalVM cleanly -- the Minecraft closure trips a hard check in
 * StrengthenGraphs while building the universe:
 *
 *   Error: @Delete methods should have a single callee.
 *
 * The code is, in effect:
 *
 *   if (isAnnotationPresent(targetMethod, Delete.class)) {
 *       AnalysisError.guarantee(callees.size() == 1, "@Delete methods should have a single callee.");
 *       devirtualizeInvoke(callees.iterator().next(), invoke);
 *   }
 *
 * `@Delete` is SVM's own marker for JDK methods that are unsupported in an image;
 * nothing in MC-Web uses it. Devirtualizing such a call is only an optimisation:
 * the method throws if it is ever reached, so leaving the invoke polymorphic is
 * semantically the same. This rewrites the hard failure into "report it and skip
 * the devirtualization", which both names the offending method and lets the build
 * continue.
 *
 * It is a guarantee, not an assert, so -da cannot disable it -- patching the
 * class is the only lever short of forking GraalVM.
 *
 * Output: a patch-module directory for org.graalvm.nativeimage.pointsto, passed
 * to native-image as -J--patch-module=org.graalvm.nativeimage.pointsto=<dir>.
 *
 *   java -cp <asm> PointstoPatcher <pointsto.jar> <outDir>
 */
public final class PointstoPatcher {

    private static final String TARGET =
            "com/oracle/graal/pointsto/results/StrengthenGraphs$StrengthenSimplifier";
    private static final String MESSAGE = "@Delete methods should have a single callee.";

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("usage: PointstoPatcher <pointsto.jar> <outDir>");
            System.exit(1);
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
        // Assert the exact count: if a GraalVM upgrade moves or removes this
        // check, fail loudly here rather than silently shipping an unpatched
        // builder that dies mid-build on Windows.
        if (rewritten != 1) {
            throw new IllegalStateException("expected exactly 1 @Delete guarantee, rewrote " + rewritten);
        }

        // COMPUTE_MAXS only, exactly as McWebImagePatcher does: recomputing frames
        // needs the builder's type hierarchy, which is not on this tool's classpath,
        // and approximating it produces "VerifyError: Bad return type". The rewrite
        // below therefore introduces no new branch target -- it reuses the jump label
        // the original @Delete test already branches to, which already carries a
        // stack map frame.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    /** Find the guarantee, and turn it into a skip-with-report. */
    private static boolean patchMethod(MethodNode method) {
        for (AbstractInsnNode insn : method.instructions.toArray()) {
            if (!(insn instanceof LdcInsnNode ldc) || !MESSAGE.equals(ldc.cst)) {
                continue;
            }
            // The guarantee call follows the message: (Z, String, Object[]) -> void.
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

            // Walk back to the start of the condition: ALOAD <callees>, size(), 1, compare.
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

            // Where the non-@Delete path resumes: the IFEQ guarding the whole block.
            LabelNode skipTarget = null;
            for (AbstractInsnNode cursor = conditionStart; cursor != null; cursor = cursor.getPrevious()) {
                if (cursor instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.IFEQ) {
                    skipTarget = jump.label;
                    break;
                }
            }
            if (skipTarget == null) {
                continue;
            }

            InsnList replacement = new InsnList();

            // Report every @Delete invoke unconditionally. Straight-line: a
            // conditional print would need its own branch target, and that is what
            // breaks frame verification.
            replacement.add(new FieldInsnNode(Opcodes.GETSTATIC,
                    "java/lang/System", "err", "Ljava/io/PrintStream;"));
            replacement.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
            replacement.add(new InsnNode(Opcodes.DUP));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                    "java/lang/StringBuilder", "<init>", "()V", false));
            replacement.add(new LdcInsnNode("[mcweb] @Delete invoke, callees="));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                    "append", "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
            replacement.add(new VarInsnNode(Opcodes.ALOAD, calleesLoad.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder",
                    "append", "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));

            // if (callees.size() != 1) goto <the label the @Delete test already uses>;
            // Reusing that label is what keeps the existing stack map valid.
            replacement.add(new VarInsnNode(Opcodes.ALOAD, calleesLoad.var));
            replacement.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                    "java/util/Collection", "size", "()I", true));
            replacement.add(new InsnNode(Opcodes.ICONST_1));
            replacement.add(new JumpInsnNode(Opcodes.IF_ICMPNE, skipTarget));

            // Drop everything from the condition through the guarantee call.
            AbstractInsnNode cursor = conditionStart;
            while (cursor != null && cursor != guarantee) {
                AbstractInsnNode next = cursor.getNext();
                method.instructions.remove(cursor);
                cursor = next;
            }
            method.instructions.insertBefore(guarantee, replacement);
            method.instructions.remove(guarantee);
            System.out.println("patched " + method.name + ": @Delete guarantee -> report and skip");
            return true;
        }
        return false;
    }

    private PointstoPatcher() {
    }
}
