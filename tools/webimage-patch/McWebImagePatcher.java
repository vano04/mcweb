/*
 * Rewrites a small, exactly-counted set of GraalVM Web Image builder methods so the
 * WasmLM backend can host `@JS` (and, later, real threads). The rewritten classes are
 * handed to the image builder with
 *
 *     -J--patch-module=org.graalvm.extraimage.builder=<outputDir>
 *
 * which shadows the originals inside the builder module without editing the GraalVM
 * install. Every patch asserts the exact shape it expects (class present, method
 * present, descriptor match, not already patched); a GraalVM update that moves any of
 * them fails the patch instead of silently producing a stock image.
 *
 *     -J--patch-module=org.graalvm.extraimage.builder=<outputDir>
 *
 * With a third and fourth argument (`svm.jar` and a second output dir) it additionally
 * rewrites the runtime class-initialization publication in `svm.jar` (module
 * `org.graalvm.nativeimage.builder`), handed to the builder with
 *
 *     -J--patch-module=org.graalvm.nativeimage.builder=<svmPatchDir>
 */
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

public final class McWebImagePatcher {

    private static final String LOWERER = "com/oracle/svm/hosted/webimage/wasm/codegen/WebImageWasmLMNodeLowerer";
    private static final String COUNTERPARTS = "com/oracle/svm/hosted/webimage/wasm/WasmJSCounterparts";
    private static final String LM_CODEGEN = "com/oracle/svm/hosted/webimage/wasm/codegen/WebImageWasmLMCodeGen";
    private static final String WASM_CODEGEN = "com/oracle/svm/hosted/webimage/wasm/codegen/WebImageWasmCodeGen";
    private static final String WEBIMAGE_CODEGEN = "com/oracle/svm/hosted/webimage/codegen/WebImageCodeGen";
    private static final String HELPER = "com/oracle/svm/hosted/webimage/wasm/codegen/McWebLMJSBody";
    private static final String JS_STUB = "com/oracle/svm/hosted/webimage/js/JSStubMethod";
    private static final String THREAD_TARGET = "com/oracle/svm/webimage/substitute/system/Target_java_lang_Thread_Web";
    private static final String FJP_POOL_TARGET = "com/oracle/svm/webimage/substitute/system/Target_java_util_concurrent_ForkJoinPool_Web";
    private static final String FJP_TASK_TARGET = "com/oracle/svm/webimage/substitute/system/Target_java_util_concurrent_ForkJoinTask_Web";
    private static final String THREADS = "com/oracle/svm/webimage/threads/McWebLMThreads";
    private static final String UNSAFE_TARGET = "com/oracle/svm/webimage/substitute/system/Target_sun_misc_Unsafe_Web";
    private static final String GENERATOR = "com/oracle/svm/hosted/webimage/WebImageGenerator";
    private static final String THREAD_EXPORTS = "com/oracle/svm/hosted/webimage/wasm/codegen/McWebLMThreadExports";
    private static final String VISITOR = "com/oracle/svm/hosted/webimage/wasm/ast/visitors/WasmVisitor";
    private static final String PRINTER = "com/oracle/svm/hosted/webimage/wasm/ast/visitors/WasmPrinter";
    private static final String VALIDATOR = "com/oracle/svm/hosted/webimage/wasm/ast/visitors/WasmValidator";
    private static final String ATOMIC_VISITORS = "com/oracle/svm/hosted/webimage/wasm/ast/visitors/McWebAtomicVisitors";
    private static final String ATOMICS = "com/oracle/svm/hosted/webimage/wasm/codegen/McWebLMAtomics";
    private static final String CAS_PHASE = "com/oracle/svm/hosted/webimage/wasm/phases/WasmLMSingleThreadedAtomicsPhase";
    private static final String ATOMICS_PHASE = "com/oracle/svm/hosted/webimage/wasm/phases/SingleThreadedAtomicsPhase";
    private static final String BINARYEN = "com/oracle/svm/hosted/webimage/wasm/codegen/WasmAssembler$Binaryen";
    private static final String ALLOCATION = "com/oracle/svm/hosted/webimage/wasm/gc/WasmAllocation";
    private static final String WASM_HEAP = "com/oracle/svm/hosted/webimage/wasm/gc/WasmHeap";
    private static final String COLLECTOR = "com/oracle/svm/hosted/webimage/wasm/gc/WasmLMGC";
    private static final String HEAP_LOCK = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMHeapLock";
    private static final String HEAP_POLICY = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMHeapPolicy";
    private static final String CLOCK = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMClock";
    private static final String TLAB = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMTlab";
    private static final String SYSTEM_WEB = "com/oracle/svm/webimage/substitute/system/Target_java_lang_System_Web";
    private static final String MONITORS = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMMonitors";
    private static final String MARK = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMMark";
    private static final String SWEEP = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMSweep";
    private static final String GRAY_VISITOR = "com/oracle/svm/hosted/webimage/wasm/gc/GrayToBlackObjectVisitor";
    private static final String MARK_STACK = "com/oracle/svm/hosted/webimage/wasm/gc/SizedObjectStack";
    private static final String SAFEPOINT = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMSafepoint";
    private static final String THREAD_LOCALS = "com/oracle/svm/hosted/webimage/wasm/gc/McWebLMThreadLocals";
    private static final String THREAD_LOCAL_LOWERING = "com/oracle/svm/hosted/webimage/wasm/codegen/McWebLMThreadLocalLowering";
    private static final String ST_FEATURE = "com/oracle/svm/hosted/webimage/wasm/WebImageWasmLMVMThreadSTFeature";
    private static final String ST_SUPPORT = "com/oracle/svm/hosted/webimage/wasm/WebImageWasmVMThreadLocalSTSupport";
    private static final String MONITOR_PHASE = "com/oracle/svm/hosted/webimage/codegen/phase/RemoveMonitorPhase";
    private static final String MONITOR_SUPPORT = "com/oracle/svm/webimage/threads/WebImageSingleThreadedMonitorSupport";
    private static final String ATOMICS_FEATURE = "com/oracle/svm/hosted/webimage/wasm/snippets/SingleThreadedAtomicsFeature";
    private static final String CLASS_INIT_INFO = "com/oracle/svm/core/classinitialization/ClassInitializationInfo";
    private static final String UNSAFE_INTERNAL = "jdk/internal/misc/Unsafe";

    private static final String INSTRUCTION = "Lcom/oracle/svm/hosted/webimage/wasm/ast/Instruction;";
    private static final String JSBODY = "Lcom/oracle/svm/hosted/webimage/js/JSBody;";
    private static final String JAVA_KIND = "Ljdk/vm/ci/meta/JavaKind;";
    private static final String WASM_UTIL = "Lcom/oracle/svm/webimage/wasm/types/WasmUtil;";
    private static final String WASM_VAL_TYPE = "Lcom/oracle/svm/webimage/wasm/types/WasmValType;";
    private static final String PROVIDERS = "Lcom/oracle/svm/hosted/webimage/wasm/codegen/WebImageWasmProviders;";
    private static final String CODE_GEN_TOOL = "Lcom/oracle/svm/hosted/webimage/codegen/JSCodeGenTool;";

    private final List<String> log = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        if (args.length != 2 && args.length != 4) {
            throw new IllegalArgumentException(
                    "usage: McWebImagePatcher <svm-wasm.jar> <outputDir> [<svm.jar> <svmPatchDir>]");
        }
        Path jar = Path.of(args[0]);
        Path out = Path.of(args[1]);
        McWebImagePatcher patcher = new McWebImagePatcher();
        try (JarFile jarFile = new JarFile(jar.toFile())) {
            patcher.patchLowerer(jarFile, out);
            patcher.patchCoercedJSStub(jarFile, out);
            patcher.patchCounterparts(jarFile, out);
            patcher.patchLMCodeGen(jarFile, out);
            patcher.patchThreadStart(jarFile, out);
            patcher.patchForkJoinSubstitutions(jarFile, out);
            patcher.patchThreadParking(jarFile, out);
            patcher.patchEntryPoints(jarFile, out);
            patcher.patchAtomicVisitors(jarFile, out);
            patcher.patchAtomicLowering(jarFile, out);
            patcher.patchAtomicPhases(jarFile, out);
            patcher.patchAssemblerFlags(jarFile, out);
            patcher.patchAllocatorLock(jarFile, out);
            patcher.patchAllocationSafepoint(jarFile, out);
            patcher.patchSharedClock(jarFile, out);
            patcher.patchCollectorGuard(jarFile, out);
            patcher.patchMarkWorklist(jarFile, out);
            patcher.patchMonitors(jarFile, out);
            patcher.patchThreadLocals(jarFile, out);
        }
        if (args.length == 4) {
            Path svmPatchDir = Path.of(args[3]);
            try (JarFile svmJar = new JarFile(Path.of(args[2]).toFile())) {
                patcher.patchClassInitPublication(svmJar, svmPatchDir);
            }
        }
        patcher.log.forEach(System.out::println);
    }

    /**
     * `lowerJSBody` returns a stub on WasmLM. Route it to the real lowering.
     */
    private void patchLowerer(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, LOWERER);
        MethodNode method = uniqueMethod(node, "lowerJSBody", "(" + JSBODY + ")" + INSTRUCTION);
        require(containsStubCall(method), LOWERER + ".lowerJSBody no longer delegates to getStub; re-check the upstream implementation");

        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "lowerJSBody",
                        "(L" + LOWERER + ";" + JSBODY + ")" + INSTRUCTION, false));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, body, 2, 2);
        write(node, out);
        log.add("patched " + LOWERER + ".lowerJSBody -> McWebLMJSBody.lowerJSBody");
    }

    /**
     * The stock JS stub boxes every non-raw argument and routes @JS.Coerce through the
     * WasmGC externref conversion runtime. On WasmLM that runtime is unavailable. For
     * the primitive/String coercions supported by McWebLMJSBody, make the graph use the
     * original Wasm signature; the generated import wrapper performs the conversions.
     */
    private void patchCoercedJSStub(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, JS_STUB);
        String publicDescriptor =
                        "(Ljdk/graal/compiler/debug/DebugContext;" +
                        "Lcom/oracle/graal/pointsto/meta/AnalysisMethod;" +
                        "Lcom/oracle/graal/pointsto/meta/HostedProviders;" +
                        "Lcom/oracle/graal/pointsto/infrastructure/GraphProvider$Purpose;)" +
                        "Ljdk/graal/compiler/nodes/StructuredGraph;";
        String privateDescriptor =
                        "(Ljdk/graal/compiler/debug/DebugContext;" +
                        "Lcom/oracle/graal/pointsto/meta/AnalysisMethod;" +
                        "Lcom/oracle/graal/pointsto/meta/HostedProviders;" +
                        "Lcom/oracle/graal/pointsto/infrastructure/GraphProvider$Purpose;" +
                        "Lcom/oracle/svm/hosted/webimage/js/JSBody$JSCode;ZZ)" +
                        "Ljdk/graal/compiler/nodes/StructuredGraph;";
        MethodNode method = methodByDescriptor(node, "buildGraph", publicDescriptor);
        List<MethodInsnNode> calls = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call &&
                            call.getOpcode() == Opcodes.INVOKESTATIC &&
                            call.owner.equals(JS_STUB) &&
                            call.name.equals("buildGraph") &&
                            call.desc.equals(privateDescriptor)) {
                calls.add(call);
            }
        }
        require(calls.size() == 1, JS_STUB + ".buildGraph must invoke its private builder exactly once");

        AbstractInsnNode[] loads = new AbstractInsnNode[7];
        AbstractInsnNode cursor = calls.get(0);
        for (int i = loads.length - 1; i >= 0; i--) {
            do {
                cursor = cursor.getPrevious();
            } while (cursor != null && cursor.getOpcode() < 0);
            require(cursor != null, JS_STUB + ".buildGraph argument-load sequence is truncated");
            loads[i] = cursor;
        }
        int[] expectedOpcodes = {
                        Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD, Opcodes.ALOAD,
                        Opcodes.ALOAD, Opcodes.ILOAD, Opcodes.ILOAD
        };
        int[] expectedVariables = {1, 2, 3, 4, 7, 6, 5};
        for (int i = 0; i < loads.length; i++) {
            require(loads[i] instanceof VarInsnNode load &&
                            load.getOpcode() == expectedOpcodes[i] &&
                            load.var == expectedVariables[i],
                            JS_STUB + ".buildGraph private-call arguments changed at position " + i);
        }

        InsnList adjustment = new InsnList();
        adjustment.add(new VarInsnNode(Opcodes.ALOAD, 2));
        adjustment.add(new VarInsnNode(Opcodes.ILOAD, 5));
        adjustment.add(new VarInsnNode(Opcodes.ILOAD, 6));
        adjustment.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "effectiveRawCall",
                        "(Ljdk/vm/ci/meta/ResolvedJavaMethod;ZZ)Z", false));
        adjustment.add(new VarInsnNode(Opcodes.ISTORE, 5));
        adjustment.add(new VarInsnNode(Opcodes.ALOAD, 2));
        adjustment.add(new VarInsnNode(Opcodes.ILOAD, 6));
        adjustment.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "effectiveCoercion",
                        "(Ljdk/vm/ci/meta/ResolvedJavaMethod;Z)Z", false));
        adjustment.add(new VarInsnNode(Opcodes.ISTORE, 6));
        method.instructions.insertBefore(loads[0], adjustment);
        method.maxStack = Math.max(method.maxStack, 3);
        write(node, out);
        log.add("patched " + JS_STUB + ": primitive/String @JS.Coerce uses WasmLM import signatures");
    }

    /**
     * `getArgumentType` maps JavaKind.Object to externref on every backend. WasmLM has
     * no externref; references are i32 pointers.
     */
    private void patchCounterparts(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, COUNTERPARTS);
        MethodNode method = uniqueMethod(node, "getArgumentType", "(" + JAVA_KIND + WASM_UTIL + ")" + WASM_VAL_TYPE);

        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "argumentType",
                        "(" + JAVA_KIND + WASM_UTIL + ")" + WASM_VAL_TYPE, false));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, body, 2, 2);
        write(node, out);
        log.add("patched " + COUNTERPARTS + ".getArgumentType -> McWebLMJSBody.argumentType");
    }

    /**
     * The LM code generator never emits `wasmImports.jsbody`, so even a correctly
     * imported jsbody function would fail instantiation. Add the override the WasmGC
     * code generator has.
     */
    private void patchLMCodeGen(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, LM_CODEGEN);
        require(node.superName.equals(WASM_CODEGEN), LM_CODEGEN + " no longer extends " + WASM_CODEGEN);
        require(node.methods.stream().noneMatch(m -> m.name.equals("emitBootstrapDefinitions")),
                        LM_CODEGEN + " already declares emitBootstrapDefinitions; upstream changed");

        MethodNode method = new MethodNode(Opcodes.ACC_PROTECTED, "emitBootstrapDefinitions", "()V", null, null);
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, WASM_CODEGEN, "emitBootstrapDefinitions", "()V", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LM_CODEGEN, "getProviders", "()" + PROVIDERS, false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, WEBIMAGE_CODEGEN, "codeGenTool",
                        "Lcom/oracle/svm/hosted/webimage/codegen/JSCodeGenTool;"));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HELPER, "emitJSBodyImports",
                        "(" + PROVIDERS + CODE_GEN_TOOL + ")V", false));
        body.add(new InsnNode(Opcodes.RETURN));
        method.instructions = body;
        method.maxLocals = 1;
        method.maxStack = 3;
        node.methods.add(method);
        write(node, out);
        log.add("patched " + LM_CODEGEN + ": added emitBootstrapDefinitions -> McWebLMJSBody.emitJSBodyImports");
    }

    /**
     * `Target_java_lang_Thread_Web` substitutes `start0()` with an empty body and
     * `isAlive()` with `this == Thread.currentThread()`: a started thread never runs and
     * a join never waits. Route both to McWebLMThreads, which hands the Thread's heap
     * address to a host agent running on another OS thread over the shared heap.
     */
    private void patchThreadStart(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, THREAD_TARGET);

        MethodNode start0 = uniqueMethod(node, "start0", "()V");
        require(realInstructionCount(start0) == 1, THREAD_TARGET + ".start0 is no longer an empty substitution");
        InsnList startBody = new InsnList();
        startBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        startBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "start", "(Ljava/lang/Object;)V", false));
        startBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(start0, startBody, 1, 1);

        MethodNode isAlive = uniqueMethod(node, "isAlive", "()Z");
        InsnList aliveBody = new InsnList();
        aliveBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        aliveBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "isAlive", "(Ljava/lang/Object;)Z", false));
        aliveBody.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(isAlive, aliveBody, 1, 1);

        MethodNode getId = uniqueMethod(node, "getId", "()J");
        InsnList idBody = new InsnList();
        idBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        idBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "threadId",
                        "(Ljava/lang/Object;)J", false));
        idBody.add(new InsnNode(Opcodes.LRETURN));
        replaceBody(getId, idBody, 1, 1);

        MethodNode getState = uniqueMethod(node, "getState", "()Ljava/lang/Thread$State;");
        InsnList stateBody = new InsnList();
        stateBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        stateBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "threadState",
                        "(Ljava/lang/Object;)Ljava/lang/Thread$State;", false));
        stateBody.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(getState, stateBody, 1, 1);

        MethodNode interrupt = uniqueMethod(node, "interrupt", "()V");
        InsnList interruptBody = new InsnList();
        interruptBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        interruptBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "interrupt",
                        "(Ljava/lang/Object;)V", false));
        interruptBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(interrupt, interruptBody, 1, 1);

        MethodNode interrupted = uniqueMethod(node, "isInterrupted", "()Z");
        InsnList interruptedBody = new InsnList();
        interruptedBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        interruptedBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "isInterrupted",
                        "(Ljava/lang/Object;)Z", false));
        interruptedBody.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(interrupted, interruptedBody, 1, 1);

        MethodNode yield = uniqueMethod(node, "yield", "()V");
        InsnList yieldBody = new InsnList();
        yieldBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "yield", "()V", false));
        yieldBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(yield, yieldBody, 0, 0);

        MethodNode sleep = uniqueMethod(node, "sleep", "(J)V");
        InsnList sleepBody = new InsnList();
        sleepBody.add(new VarInsnNode(Opcodes.LLOAD, 0));
        sleepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "sleep", "(J)V", false));
        sleepBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(sleep, sleepBody, 2, 2);

        write(node, out);
        log.add("patched " + THREAD_TARGET + ".start0/.isAlive/id/state/interrupt/sleep -> McWebLMThreads");
    }

    /** Restore JDK ForkJoinPool/ForkJoinTask semantics for the shared-heap image. */
    private void patchForkJoinSubstitutions(JarFile jarFile, Path out) throws IOException {
        for (String target : new String[]{FJP_POOL_TARGET, FJP_TASK_TARGET}) {
            ClassNode node = read(jarFile, target);
            require(node.visibleAnnotations != null
                            && node.visibleAnnotations.stream().anyMatch(a ->
                                    "Lcom/oracle/svm/core/annotate/TargetClass;".equals(a.desc)),
                    target + " is missing its TargetClass annotation");
            node.visibleAnnotations.removeIf(a ->
                            "Lcom/oracle/svm/core/annotate/TargetClass;".equals(a.desc));
            write(node, out);
        }
        log.add("disabled WasmLM caller-runs ForkJoinPool/ForkJoinTask substitutions");
    }

    /**
     * WasmLM's Unsafe substitutions throw for park/unpark. Route them through the
     * shared host control block, where Atomics.wait/notify provide real permits.
     */
    private void patchThreadParking(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, UNSAFE_TARGET);

        MethodNode unpark = uniqueMethod(node, "unpark", "(Ljava/lang/Object;)V");
        InsnList unparkBody = new InsnList();
        unparkBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        unparkBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "unpark", "(Ljava/lang/Object;)V", false));
        unparkBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(unpark, unparkBody, 2, 1);

        MethodNode park = uniqueMethod(node, "park", "(ZJ)V");
        InsnList parkBody = new InsnList();
        parkBody.add(new VarInsnNode(Opcodes.ILOAD, 1));
        parkBody.add(new VarInsnNode(Opcodes.LLOAD, 2));
        parkBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREADS, "park", "(ZJ)V", false));
        parkBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(park, parkBody, 4, 3);

        write(node, out);
        log.add("patched " + UNSAFE_TARGET + ".park/.unpark -> McWebLMThreads");
    }

    /**
     * `@WasmExport` discovery walks the image classpath and the builder's module path,
     * neither of which contains a `--patch-module` directory. Register the thread agent
     * entry point explicitly at the top of `registerEntryPoints`.
     */
    private void patchEntryPoints(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, GENERATOR);
        MethodNode method = uniqueMethod(node, "registerEntryPoints", "(Ljava/util/Map;)V");

        InsnList prologue = new InsnList();
        prologue.add(new VarInsnNode(Opcodes.ALOAD, 1));
        prologue.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREAD_EXPORTS, "register", "(Ljava/util/Map;)V", false));
        method.instructions.insert(prologue);
        write(node, out);
        log.add("patched " + GENERATOR + ".registerEntryPoints -> McWebLMThreadExports.register");
    }

    /**
     * Route `McWebAtomicOp` through every AST visitor, and make the two private members
     * the hook needs reachable: the printer's output writer and the validator's value
     * stack push. Also make the memory declaration `shared`, which wasm requires before
     * any atomic instruction may touch it.
     */
    private void patchAtomicVisitors(JarFile jarFile, Path out) throws IOException {
        ClassNode visitor = read(jarFile, VISITOR);
        MethodNode visitInstruction = uniqueMethod(visitor, "visitInstruction", "(" + INSTRUCTION + ")V");
        InsnList hook = new InsnList();
        LabelNode notHandled = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ATOMIC_VISITORS, "visit",
                        "(L" + VISITOR + ";" + INSTRUCTION + ")Z", false));
        hook.add(new JumpInsnNode(Opcodes.IFEQ, notHandled));
        hook.add(new InsnNode(Opcodes.RETURN));
        hook.add(notHandled);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        visitInstruction.instructions.insert(hook);
        write(visitor, out);

        ClassNode printer = read(jarFile, PRINTER);
        widenField(printer, "writer");
        MethodNode visitMemory = uniqueMethod(printer, "visitMemory", "(Lcom/oracle/svm/hosted/webimage/wasm/ast/Memory;)V");
        InsnList memoryBody = new InsnList();
        memoryBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        memoryBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        memoryBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ATOMIC_VISITORS, "printMemory",
                        "(L" + PRINTER + ";Lcom/oracle/svm/hosted/webimage/wasm/ast/Memory;)V", false));
        memoryBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(visitMemory, memoryBody, 2, 2);
        write(printer, out);

        ClassNode validator = read(jarFile, VALIDATOR);
        MethodNode pushVal = uniqueMethod(validator, "pushVal", "(Lcom/oracle/svm/webimage/wasm/types/WasmValType;)V");
        pushVal.access = (pushVal.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
        write(validator, out);

        log.add("patched AST visitors for McWebAtomicOp (+ shared memory declaration)");
    }

    /**
     * Lower the atomic nodes the phases below now leave in the graph.
     */
    private void patchAtomicLowering(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, LOWERER);
        // patchLowerer already rewrote lowerJSBody in the copy under `out`; keep both.
        ClassNode staged = readStaged(out, LOWERER);
        ClassNode target = staged != null ? staged : node;

        MethodNode dispatch = uniqueMethod(target, "dispatch",
                        "(Ljdk/graal/compiler/nodes/ValueNode;Lcom/oracle/svm/hosted/webimage/wasm/codegen/WasmIRWalker$Requirements;)" + INSTRUCTION);
        InsnList hook = new InsnList();
        LabelNode notHandled = new LabelNode();
        hook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ATOMICS, "tryLower",
                        "(L" + LOWERER + ";Ljdk/graal/compiler/nodes/ValueNode;)" + INSTRUCTION, false));
        // A slot past everything the original body uses, so no local is clobbered.
        int slot = dispatch.maxLocals;
        hook.add(new VarInsnNode(Opcodes.ASTORE, slot));
        hook.add(new VarInsnNode(Opcodes.ALOAD, slot));
        hook.add(new JumpInsnNode(Opcodes.IFNULL, notHandled));
        hook.add(new VarInsnNode(Opcodes.ALOAD, slot));
        hook.add(new InsnNode(Opcodes.ARETURN));
        hook.add(notHandled);
        hook.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        dispatch.instructions.insert(hook);
        dispatch.maxLocals = slot + 1;
        write(target, out);
        log.add("patched " + LOWERER + ".dispatch -> McWebLMAtomics.tryLower");
    }

    /**
     * Stop rewriting atomics for a single thread: `processCAS` replaced every CAS with a
     * non-atomic foreign call, and the read-and-write/read-and-add handlers replaced
     * atomic read-modify-writes with a plain load/store pair. Emptying all three leaves
     * the nodes in the graph for McWebLMAtomics to lower.
     */
    private void patchAtomicPhases(JarFile jarFile, Path out) throws IOException {
        ClassNode casPhase = read(jarFile, CAS_PHASE);
        MethodNode processCAS = uniqueMethod(casPhase, "processCAS",
                        "(Ljdk/graal/compiler/nodes/spi/CoreProviders;Ljdk/graal/compiler/nodes/java/AbstractCompareAndSwapNode;)V");
        require(containsForeignCall(processCAS), CAS_PHASE + ".processCAS no longer emits a foreign call; upstream changed");
        InsnList empty = new InsnList();
        empty.add(new InsnNode(Opcodes.RETURN));
        replaceBody(processCAS, empty, 0, 3);
        write(casPhase, out);

        ClassNode phase = read(jarFile, ATOMICS_PHASE);
        for (String name : new String[]{"processReadAndWrite", "processReadAndAdd"}) {
            MethodNode method = node(phase, name);
            InsnList body = new InsnList();
            body.add(new InsnNode(Opcodes.RETURN));
            replaceBody(method, body, 0, 2);
        }
        write(phase, out);
        log.add("patched single-threaded atomics phases to no-ops");
    }

    /** Binaryen rejects atomic instructions without --enable-threads. */
    private void patchAssemblerFlags(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, BINARYEN);
        MethodNode method = uniqueMethod(node, "getExtraFlags", "()Ljava/util/List;");
        InsnList body = new InsnList();
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ATOMICS, "binaryenFlags", "()Ljava/util/List;", false));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, body, 1, 1);
        write(node, out);
        log.add("patched " + BINARYEN + ".getExtraFlags -> +--enable-threads");
    }

    /**
     * Serialise the allocator. `doMalloc`/`doFree` become locking wrappers around the
     * original bodies, which are renamed and keep being used for every call inside the
     * allocator and the collector - those already run under the lock.
     */
    private void patchAllocatorLock(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, ALLOCATION);
        // `allocateObject` is the path every Java `new` takes; doMalloc/doFree are the
        // exported unmanaged-memory entry points. The lock is reentrant per agent, so
        // nesting (allocateObject -> doMalloc, doRealloc -> doMalloc/doFree, and the
        // collector's sweep -> doFree from inside doMalloc) is safe and no call site
        // needs rewriting.
        MethodNode initialize = uniqueMethod(node, "initialize", "()V");
        require(realInstructionCount(initialize) > 1,
                        ALLOCATION + ".initialize is unexpectedly empty; re-check the allocator reserve patch");
        InsnList initializeBody = new InsnList();
        initializeBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_LOCK, "initializeAllocator", "()V", false));
        initializeBody.add(new InsnNode(Opcodes.RETURN));
        initialize.instructions = initializeBody;
        initialize.tryCatchBlocks.clear();
        initialize.localVariables = null;
        initialize.maxStack = 0;
        initialize.maxLocals = 0;
        // Thread-local allocation buffers. wrapWithLock runs first so the original
        // survives as allocateObjectUnlocked, the slow path the buffer falls back to.
        widenToPublic(node, "markAsObject", "(Lorg/graalvm/word/Pointer;)V");
        addObjectSizeAccessor(node);
        addSweepHelpers(node);
        replaceMarkAsObject(node);
        widenToPublic(node, "getOuterPointer", "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;");
        widenToPublic(node, "getInnerPointer", "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;");
        widenToPublic(node, "writeBlockHeader", "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/UnsignedWord;ZZ)V");
        wrapWithLock(node, "allocateObject", "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;", true);
        widenToPublic(node, "allocateObjectUnlocked", "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        replaceWithStaticCall(node, "allocateObject", "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;",
                        TLAB, "allocateObject");
        wrapWithLock(node, "doMalloc", "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;", true);
        installHeapPolicy(node);
        addBoundedFreeListSearch(node);
        wrapWithLock(node, "doFree", "(Lorg/graalvm/word/Pointer;)V", false);
        patchFreeListPolicy(jarFile, node, out);
        write(node, out);
        log.add("patched " + ALLOCATION + ".allocateObject/.doMalloc/.doFree with the McWebLMHeapLock spin lock");
        log.add("patched " + ALLOCATION + ".initialize -> 512 MiB allocator reserve, halving until it fits");
        log.add("patched " + ALLOCATION + ".doMalloc -> McWebLMHeapPolicy: 256-probe common path, grow on a miss, collect only when full");
        log.add("patched " + ALLOCATION + ".allocateInExistingBlocks: probe budget from McWebLMHeapPolicy.searchLimit");
        log.add("patched " + ALLOCATION
                        + "$FreeList: preserve the split remainder and maintain size-class candidates");
    }

    /** Exposes the existing allocator statistic to the direct TLAB object-header path. */
    private static void addObjectSizeAccessor(ClassNode node) {
        require(node.methods.stream().noneMatch(method -> method.name.equals("addObjectSize")
                        && method.desc.equals("(J)V")), ALLOCATION + ".addObjectSize already exists");
        MethodNode mark = methodByDescriptor(node, "markAsObject", "(Lorg/graalvm/word/Pointer;)V");
        MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "addObjectSize", "(J)V", null, null);
        method.visibleAnnotations = mark.visibleAnnotations;
        String statistics = ALLOCATION + "$Statistics";
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        statistics, "objectSize", "J"));
        method.instructions.add(new VarInsnNode(Opcodes.LLOAD, 0));
        method.instructions.add(new InsnNode(Opcodes.LADD));
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC,
                        statistics, "objectSize", "J"));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
        node.methods.add(method);
    }

    /**
     * Add the pointer-only primitives used by the stop-the-world sweep rebuild. They
     * live on {@code WasmAllocation} so the patch can call the allocator's private
     * header writer and nested free-list without widening either implementation detail
     * in the generated image.
     */
    private static void addSweepHelpers(ClassNode node) {
        MethodNode markFree = methodByDescriptor(node, "markFree", "(Lorg/graalvm/word/Pointer;)V");
        String pointer = "Lorg/graalvm/word/Pointer;";
        String unsigned = "Lorg/graalvm/word/UnsignedWord;";
        String freeList = ALLOCATION + "$FreeList";
        String statistics = ALLOCATION + "$Statistics";

        require(node.methods.stream().noneMatch(method -> method.name.equals("writeFreeBlockForSweep")
                        && method.desc.equals("(" + pointer + "J)V")),
                        ALLOCATION + ".writeFreeBlockForSweep already exists");
        MethodNode writeFree = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "writeFreeBlockForSweep", "(" + pointer + "J)V", null, null);
        writeFree.visibleAnnotations = markFree.visibleAnnotations;
        writeFree.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        writeFree.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        writeFree.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "jdk/graal/compiler/word/Word", "unsigned", "(J)" + unsigned, false));
        writeFree.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION,
                        "writeFreeBlockHeader", "(" + pointer + unsigned + ")V", false));
        writeFree.instructions.add(new InsnNode(Opcodes.RETURN));
        writeFree.maxStack = 3;
        writeFree.maxLocals = 3;
        node.methods.add(writeFree);

        require(node.methods.stream().noneMatch(method -> method.name.equals("freeObjectForSweep")
                        && method.desc.equals("(" + pointer + "J)V")),
                        ALLOCATION + ".freeObjectForSweep already exists");
        MethodNode freeObject = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "freeObjectForSweep", "(" + pointer + "J)V", null, null);
        freeObject.visibleAnnotations = markFree.visibleAnnotations;
        freeObject.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        statistics, "objectSize", "J"));
        freeObject.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        freeObject.instructions.add(new InsnNode(Opcodes.LSUB));
        freeObject.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC,
                        statistics, "objectSize", "J"));
        freeObject.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        statistics, "freeSize", "J"));
        freeObject.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        freeObject.instructions.add(new InsnNode(Opcodes.LADD));
        freeObject.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC,
                        statistics, "freeSize", "J"));
        freeObject.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        freeObject.instructions.add(new VarInsnNode(Opcodes.LLOAD, 1));
        freeObject.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION,
                        "writeFreeBlockForSweep", "(" + pointer + "J)V", false));
        freeObject.instructions.add(new InsnNode(Opcodes.RETURN));
        freeObject.maxStack = 4;
        freeObject.maxLocals = 3;
        node.methods.add(freeObject);

        require(node.methods.stream().noneMatch(method -> method.name.equals("resetFreeListForSweep")
                        && method.desc.equals("()V")),
                        ALLOCATION + ".resetFreeListForSweep already exists");
        MethodNode reset = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "resetFreeListForSweep", "()V", null, null);
        reset.visibleAnnotations = markFree.visibleAnnotations;
        reset.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "jdk/graal/compiler/word/Word", "nullPointer",
                        "()Lorg/graalvm/word/PointerBase;", false));
        reset.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,
                        "org/graalvm/word/Pointer"));
        reset.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.PUTSTATIC,
                        freeList, "firstFree", pointer));
        reset.instructions.add(new InsnNode(Opcodes.RETURN));
        reset.maxStack = 1;
        reset.maxLocals = 0;
        node.methods.add(reset);

        require(node.methods.stream().noneMatch(method -> method.name.equals("addFreeBlockForSweep")
                        && method.desc.equals("(" + pointer + ")V")),
                        ALLOCATION + ".addFreeBlockForSweep already exists");
        MethodNode add = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        "addFreeBlockForSweep", "(" + pointer + ")V", null, null);
        add.visibleAnnotations = markFree.visibleAnnotations;
        add.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        add.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, freeList, "add",
                        "(" + pointer + ")V", false));
        add.instructions.add(new InsnNode(Opcodes.RETURN));
        add.maxStack = 1;
        add.maxLocals = 1;
        node.methods.add(add);
    }

    /** Delegate every object-header promotion to the packed-word TLAB helper. */
    private static void replaceMarkAsObject(ClassNode node) {
        MethodNode method = methodByDescriptor(node, "markAsObject", "(Lorg/graalvm/word/Pointer;)V");
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TLAB, "markAsObject",
                        "(Lorg/graalvm/word/Pointer;)V", false));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        method.maxStack = 1;
        method.maxLocals = 1;
    }

    /**
     * Replaces the allocator's main allocation path with {@link McWebLMHeapPolicy}, which
     * separates "the bounded first-fit search missed" from "the heap is full" instead of
     * paying a whole-heap collection for either. See that class for why the upstream
     * policy collapses once the search is bounded and once agents can miss a safepoint.
     */
    private static void installHeapPolicy(ClassNode node) {
        // The whole body goes, so none of upstream's stack-map frames survive to be
        // patched around - which is why this replaced an earlier version that spliced a
        // fast path into the middle of the method and had to rewrite its frames by hand.
        MethodNode method = uniqueMethod(node, "doMallocUnlocked",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        require(realInstructionCount(method) > 1, ALLOCATION + ".doMalloc is unexpectedly empty");
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY, "doMalloc",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;", false));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(method, body, 1, 1);

        // The policy calls these directly instead of going through doMalloc, so they can
        // no longer be private. Widening is exact-counted like every other transform: a
        // rename upstream must fail the build rather than silently leave the policy
        // calling something that no longer exists.
        widenToPackagePrivate(node, "allocateInExistingBlocks",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        widenToPackagePrivate(node, "growMalloc",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        addConstantAccessor(node, "minInnerSize", "MIN_INNER_SIZE");
        addConstantAccessor(node, "headerSize", "HEADER_SIZE");
    }

    private static void widenToPackagePrivate(ClassNode node, String name, String descriptor) {
        MethodNode method = uniqueMethod(node, name, descriptor);
        method.access &= ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED);
    }

    /**
     * Exposes one of the allocator's private size constants as a static accessor, so the
     * policy can use the real value instead of recomputing it from ALIGNMENT and
     * POINTERS_SIZE and drifting the day either changes.
     */
    private static void addConstantAccessor(ClassNode node, String accessor, String field) {
        require(node.fields.stream().anyMatch(candidate -> candidate.name.equals(field)
                        && candidate.desc.equals("Lorg/graalvm/word/UnsignedWord;")),
                        ALLOCATION + "." + field + " is missing or no longer an UnsignedWord");
        require(node.methods.stream().noneMatch(candidate -> candidate.name.equals(accessor)),
                        ALLOCATION + "." + accessor + " already exists");
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, accessor,
                        "()Lorg/graalvm/word/UnsignedWord;", null, null);
        method.visibleAnnotations = methodByDescriptor(node, "getInnerSize",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/UnsignedWord;").visibleAnnotations;
        method.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        ALLOCATION, field, "Lorg/graalvm/word/UnsignedWord;"));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 1;
        method.maxLocals = 0;
        node.methods.add(method);
    }

    /**
     * First-fit is linear in the number of GC fragments. Bound each search so a miss grows
     * the heap instead of monopolising the browser thread; at the memory limit, doMalloc's
     * post-collection search reuses one of the most recently freed blocks.
     */
    private static void addBoundedFreeListSearch(ClassNode node) {
        MethodNode method = uniqueMethod(node, "allocateInExistingBlocks",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        require(method.maxLocals == 3, ALLOCATION + ".allocateInExistingBlocks local layout changed");

        List<FrameNode> frames = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof FrameNode frame) {
                frames.add(frame);
            }
        }
        require(frames.size() == 5
                        && frames.get(0).type == Opcodes.F_SAME
                        && frames.get(1).type == Opcodes.F_APPEND
                        && frames.get(2).type == Opcodes.F_APPEND
                        && frames.get(3).type == Opcodes.F_SAME
                        && frames.get(4).type == Opcodes.F_CHOP,
                        ALLOCATION + ".allocateInExistingBlocks stack-map layout changed");

        VarInsnNode currentStore = null;
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() == Opcodes.GETSTATIC
                            && instruction instanceof org.objectweb.asm.tree.FieldInsnNode field
                            && field.owner.equals(ALLOCATION + "$FreeList")
                            && field.name.equals("firstFree")) {
                AbstractInsnNode next = nextRealInstruction(instruction);
                require(next instanceof VarInsnNode store
                                && store.getOpcode() == Opcodes.ASTORE
                                && store.var == 1,
                                ALLOCATION + ".allocateInExistingBlocks current-pointer local changed");
                require(currentStore == null,
                                ALLOCATION + ".allocateInExistingBlocks has duplicate firstFree loads");
                currentStore = (VarInsnNode) next;
            }
        }
        require(currentStore != null, ALLOCATION + ".allocateInExistingBlocks firstFree load not found");

        InsnList initializeCounter = new InsnList();
        initializeCounter.add(new InsnNode(Opcodes.ICONST_0));
        initializeCounter.add(new VarInsnNode(Opcodes.ISTORE, 3));
        method.instructions.insert(currentStore, initializeCounter);

        setFullFrame(frames.get(1), "org/graalvm/word/UnsignedWord", "org/graalvm/word/Pointer",
                        Opcodes.TOP, Opcodes.INTEGER);
        setFullFrame(frames.get(2), "org/graalvm/word/UnsignedWord", "org/graalvm/word/Pointer",
                        ALLOCATION + "$BlockHeader", Opcodes.INTEGER);
        setFullFrame(frames.get(4), "org/graalvm/word/UnsignedWord", "org/graalvm/word/Pointer",
                        Opcodes.TOP, Opcodes.INTEGER);

        LabelNode searchLimit = new LabelNode();
        InsnList guard = new InsnList();
        guard.add(new org.objectweb.asm.tree.IincInsnNode(3, 1));
        guard.add(new VarInsnNode(Opcodes.ILOAD, 3));
        guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY, "searchLimit", "()I", false));
        guard.add(new JumpInsnNode(Opcodes.IF_ICMPGT, searchLimit));
        method.instructions.insert(frames.get(1), guard);

        InsnList bailout = new InsnList();
        bailout.add(searchLimit);
        bailout.add(new FrameNode(Opcodes.F_FULL, 4,
                        new Object[]{"org/graalvm/word/UnsignedWord", "org/graalvm/word/Pointer",
                                        Opcodes.TOP, Opcodes.INTEGER},
                        0, new Object[0]));
        bailout.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "jdk/graal/compiler/word/Word",
                        "nullPointer", "()Lorg/graalvm/word/PointerBase;", false));
        bailout.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST, "org/graalvm/word/Pointer"));
        bailout.add(new InsnNode(Opcodes.ARETURN));
        method.instructions.add(bailout);
        method.maxLocals = 4;
    }

    /**
     * Keep the largest available block at the front. During boot that is the active
     * split remainder; after a sweep it may be a larger reclaimed block. Normal frame
     * allocations then split it in one probe instead of walking random fragments.
     */
    private static void patchFreeListPolicy(JarFile jarFile, ClassNode allocation, Path out) throws IOException {
        MethodNode markFree = uniqueMethod(allocation, "markFree", "(Lorg/graalvm/word/Pointer;)V");
        int rewrittenCalls = 0;
        for (AbstractInsnNode instruction : markFree.instructions) {
            if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(ALLOCATION + "$FreeList")
                            && call.name.equals("add")
                            && call.desc.equals("(Lorg/graalvm/word/Pointer;)V")) {
                call.name = "addAfterFirst";
                rewrittenCalls++;
            }
        }
        require(rewrittenCalls == 1,
                        ALLOCATION + ".markFree expected exactly one FreeList.add call, found " + rewrittenCalls);

        MethodNode allocateInBlock = methodByDescriptor(allocation, "allocateInBlock",
                        "(Lorg/graalvm/word/Pointer;L" + ALLOCATION + "$BlockHeader;Lorg/graalvm/word/UnsignedWord;)V");
        int splitRemainderAdds = 0;
        for (AbstractInsnNode instruction : allocateInBlock.instructions) {
            if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(ALLOCATION + "$FreeList")
                            && call.name.equals("add")
                            && call.desc.equals("(Lorg/graalvm/word/Pointer;)V")) {
                splitRemainderAdds++;
            }
        }
        require(splitRemainderAdds == 1,
                        ALLOCATION + ".allocateInBlock expected exactly one split-remainder FreeList.add call, found "
                                        + splitRemainderAdds);

        /*
         * Keep the upstream FreeList.add call for split remainders. A block that
         * satisfied an allocation may have been beyond the bounded-search prefix;
         * its remainder is then the only block known to suit the next similar
         * request and must become the active head. Putting it after the old,
         * too-small head made the next allocation repeat the complete list walk.
         */

        /*
         * Region growth is another free-list insertion point.  A newly grown
         * chunk is often smaller than the existing active remainder; prepending
         * it would put a small block in front of the largest reusable block and
         * turn every bounded miss into a deep walk.  Keep the same invariant for
         * growth as for swept and split blocks.
         */
        MethodNode growRegion = uniqueMethod(allocation, "growAllocatorRegion",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;");
        int grownAdds = 0;
        for (AbstractInsnNode instruction : growRegion.instructions) {
            if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(ALLOCATION + "$FreeList")
                            && call.name.equals("add")
                            && call.desc.equals("(Lorg/graalvm/word/Pointer;)V")) {
                call.name = "addAfterFirst";
                grownAdds++;
            }
        }
        require(grownAdds == 1,
                        ALLOCATION + ".growAllocatorRegion expected exactly one FreeList.add call, found "
                                        + grownAdds);

        ClassNode freeList = read(jarFile, ALLOCATION + "$FreeList");

        /*
         * Keep McWebLMHeapPolicy's constant-time size-class candidates synchronized
         * with the upstream doubly-linked list. remove() runs before allocateInBlock
         * writes the allocated header, so the policy can still identify the old size
         * class. add() covers initial/grown blocks and every split remainder.
         */
        MethodNode removeFree = uniqueMethod(freeList, "remove",
                        "(Lorg/graalvm/word/Pointer;)V");
        int existingCacheInvalidations = 0;
        for (AbstractInsnNode instruction : removeFree.instructions) {
            if (instruction instanceof MethodInsnNode call
                            && call.owner.equals(HEAP_POLICY)
                            && call.name.equals("unregisterFreeBlock")) {
                existingCacheInvalidations++;
            }
        }
        require(existingCacheInvalidations == 0,
                        ALLOCATION + "$FreeList.remove cache invalidation already exists");
        InsnList unregister = new InsnList();
        unregister.add(new VarInsnNode(Opcodes.ALOAD, 0));
        unregister.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "unregisterFreeBlock", "(Lorg/graalvm/word/Pointer;)V", false));
        removeFree.instructions.insertBefore(removeFree.instructions.getFirst(), unregister);

        MethodNode addFree = uniqueMethod(freeList, "add",
                        "(Lorg/graalvm/word/Pointer;)V");
        List<AbstractInsnNode> addReturns = new ArrayList<>();
        for (AbstractInsnNode instruction : addFree.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN) {
                addReturns.add(instruction);
            }
        }
        require(addReturns.size() == 1,
                        ALLOCATION + "$FreeList.add expected one return, found " + addReturns.size());
        InsnList registerAdded = new InsnList();
        registerAdded.add(new VarInsnNode(Opcodes.ALOAD, 0));
        registerAdded.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "registerFreeBlock", "(Lorg/graalvm/word/Pointer;)V", false));
        addFree.instructions.insertBefore(addReturns.get(0), registerAdded);

        require(freeList.methods.stream().noneMatch(candidate -> candidate.name.equals("addAfterFirst")),
                        ALLOCATION + "$FreeList.addAfterFirst already exists");
        MethodNode method = new MethodNode(Opcodes.ACC_STATIC, "addAfterFirst",
                        "(Lorg/graalvm/word/Pointer;)V", null, null);
        method.visibleAnnotations = uniqueMethod(freeList, "add",
                        "(Lorg/graalvm/word/Pointer;)V").visibleAnnotations;
        LabelNode nonEmpty = new LabelNode();
        LabelNode havePredecessor = new LabelNode();
        LabelNode done = new LabelNode();
        InsnList body = method.instructions;
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "recordTopologyChange", "()V", false));
        body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        ALLOCATION + "$FreeList", "firstFree", "Lorg/graalvm/word/Pointer;"));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/graalvm/word/Pointer",
                        "isNonNull", "()Z", true));
        body.add(new JumpInsnNode(Opcodes.IFNE, nonEmpty));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "add",
                        "(Lorg/graalvm/word/Pointer;)V", false));
        body.add(new InsnNode(Opcodes.RETURN));
        body.add(nonEmpty);
        body.add(new FrameNode(Opcodes.F_FULL, 1,
                        new Object[]{"org/graalvm/word/Pointer"}, 0, new Object[0]));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        ALLOCATION + "$FreeList", "firstFree", "Lorg/graalvm/word/Pointer;"));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY, "rankedInsertionPredecessor",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;",
                        false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/graalvm/word/Pointer",
                        "isNonNull", "()Z", true));
        body.add(new JumpInsnNode(Opcodes.IFNE, havePredecessor));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "add",
                        "(Lorg/graalvm/word/Pointer;)V", false));
        body.add(new InsnNode(Opcodes.RETURN));
        body.add(havePredecessor);
        body.add(new FrameNode(Opcodes.F_FULL, 2,
                        new Object[]{"org/graalvm/word/Pointer", "org/graalvm/word/Pointer"},
                        0, new Object[0]));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "getNextFreeBlock",
                        "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;", false));
        body.add(new VarInsnNode(Opcodes.ASTORE, 2));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "setNext",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)V", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "setPrev",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)V", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "setNext",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)V", false));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/graalvm/word/Pointer",
                        "isNull", "()Z", true));
        body.add(new JumpInsnNode(Opcodes.IFNE, done));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION + "$FreeList", "setPrev",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)V", false));
        body.add(done);
        body.add(new FrameNode(Opcodes.F_FULL, 3,
                        new Object[]{"org/graalvm/word/Pointer", "org/graalvm/word/Pointer",
                                        "org/graalvm/word/Pointer"},
                        0, new Object[0]));
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "registerFreeBlock", "(Lorg/graalvm/word/Pointer;)V", false));
        body.add(new InsnNode(Opcodes.RETURN));
        freeList.methods.add(method);
        write(freeList, out);

        /*
         * McWebLMHeapPolicy runs in the same package but must not depend on private
         * nested-class layout. Expose the two pointer-only operations it needs to
         * select the largest reusable block under the allocator lock. Keeping these
         * accessors on WasmAllocation also makes an upstream FreeList change fail this
         * exact patch rather than silently corrupting the Java policy.
         */
        require(allocation.methods.stream().noneMatch(candidate -> candidate.name.equals("firstFreeBlock")),
                        ALLOCATION + ".firstFreeBlock already exists");
        MethodNode firstFreeBlock = new MethodNode(Opcodes.ACC_STATIC, "firstFreeBlock",
                        "()Lorg/graalvm/word/Pointer;", null, null);
        firstFreeBlock.visibleAnnotations = uniqueMethod(allocation, "growMalloc",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;").visibleAnnotations;
        firstFreeBlock.instructions.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        ALLOCATION + "$FreeList", "firstFree", "Lorg/graalvm/word/Pointer;"));
        firstFreeBlock.instructions.add(new InsnNode(Opcodes.ARETURN));
        firstFreeBlock.maxStack = 1;
        allocation.methods.add(firstFreeBlock);

        require(allocation.methods.stream().noneMatch(candidate -> candidate.name.equals("nextFreeBlock")),
                        ALLOCATION + ".nextFreeBlock already exists");
        MethodNode nextFreeBlock = new MethodNode(Opcodes.ACC_STATIC, "nextFreeBlock",
                        "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;", null, null);
        nextFreeBlock.visibleAnnotations = firstFreeBlock.visibleAnnotations;
        nextFreeBlock.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        nextFreeBlock.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        ALLOCATION + "$FreeList", "getNextFreeBlock",
                        "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;", false));
        nextFreeBlock.instructions.add(new InsnNode(Opcodes.ARETURN));
        nextFreeBlock.maxStack = 1;
        nextFreeBlock.maxLocals = 1;
        allocation.methods.add(nextFreeBlock);

        /*
         * coalesceAt() removes adjacent free blocks and then writes the enlarged
         * header back to the surviving block. The surviving node is already in
         * the free list, so the old addAfterFirst ordering no longer reflects its
         * new size. Re-promote that node after the header write when it became the
         * largest block. Keep this exact-counted: a changed upstream allocator
         * must fail the build instead of silently losing the invariant again.
         */
        MethodNode coalesceAt = uniqueMethod(allocation, "coalesceAt",
                        "(Lorg/graalvm/word/Pointer;L" + ALLOCATION + "$BlockHeader;)V");
        List<MethodInsnNode> headerWrites = new ArrayList<>();
        for (AbstractInsnNode instruction : coalesceAt.instructions) {
            if (instruction instanceof MethodInsnNode call
                            && call.getOpcode() == Opcodes.INVOKESTATIC
                            && call.owner.equals(ALLOCATION)
                            && call.name.equals("writeBlockHeader")
                            && call.desc.equals("(Lorg/graalvm/word/Pointer;L" + ALLOCATION
                                            + "$BlockHeader;)V")) {
                headerWrites.add(call);
            }
        }
        require(headerWrites.size() == 1,
                        ALLOCATION + ".coalesceAt expected one final writeBlockHeader, found "
                                        + headerWrites.size());
        MethodInsnNode headerWrite = headerWrites.get(0);
        /* The surviving block still has its old header in memory at this point. */
        InsnList uncacheOldCoalescedSize = new InsnList();
        uncacheOldCoalescedSize.add(new VarInsnNode(Opcodes.ALOAD, 0));
        uncacheOldCoalescedSize.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "unregisterFreeBlock", "(Lorg/graalvm/word/Pointer;)V", false));
        coalesceAt.instructions.insertBefore(headerWrite, uncacheOldCoalescedSize);
        InsnList promote = new InsnList();
        promote.add(new VarInsnNode(Opcodes.ALOAD, 0));
        promote.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC,
                        ALLOCATION + "$FreeList", "firstFree", "Lorg/graalvm/word/Pointer;"));
        promote.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_POLICY,
                        "repromoteCoalesced",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/Pointer;)V", false));
        coalesceAt.instructions.insert(headerWrite, promote);

        require(allocation.methods.stream().noneMatch(candidate -> candidate.name.equals("repromoteFreeBlock")),
                        ALLOCATION + ".repromoteFreeBlock already exists");
        MethodNode helper = new MethodNode(Opcodes.ACC_STATIC, "repromoteFreeBlock",
                        "(Lorg/graalvm/word/Pointer;)V", null, null);
        helper.visibleAnnotations = uniqueMethod(allocation, "markFree",
                        "(Lorg/graalvm/word/Pointer;)V").visibleAnnotations;
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        ALLOCATION + "$FreeList", "remove", "(Lorg/graalvm/word/Pointer;)V", false));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        helper.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        ALLOCATION + "$FreeList", "addAfterFirst",
                        "(Lorg/graalvm/word/Pointer;)V", false));
        helper.instructions.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 1;
        helper.maxLocals = 1;
        allocation.methods.add(helper);

        /*
         * A successful region growth returns a block whose size is known to be at
         * least the requested chunk.  Do not publish that block and then search the
         * (possibly fragmented) free list for it again: the bounded search can miss
         * the fresh block after another free-list mutation.  Keep this bridge inside
         * WasmAllocation so it can call the private allocator primitive directly.
         */
        require(allocation.methods.stream().noneMatch(candidate -> candidate.name.equals("allocateInKnownBlock")),
                        ALLOCATION + ".allocateInKnownBlock already exists");
        MethodNode grown = new MethodNode(Opcodes.ACC_STATIC, "allocateInKnownBlock",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;",
                        null, null);
        grown.visibleAnnotations = uniqueMethod(allocation, "growMalloc",
                        "(Lorg/graalvm/word/UnsignedWord;)Lorg/graalvm/word/Pointer;").visibleAnnotations;
        LabelNode nonNull = new LabelNode();
        grown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        grown.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "org/graalvm/word/Pointer",
                        "isNull", "()Z", true));
        grown.instructions.add(new JumpInsnNode(Opcodes.IFEQ, nonNull));
        grown.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "jdk/graal/compiler/word/Word",
                        "nullPointer", "()Lorg/graalvm/word/PointerBase;", false));
        grown.instructions.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.CHECKCAST,
                        "org/graalvm/word/Pointer"));
        grown.instructions.add(new InsnNode(Opcodes.ARETURN));
        grown.instructions.add(nonNull);
        grown.instructions.add(new FrameNode(Opcodes.F_FULL, 2,
                        new Object[]{"org/graalvm/word/Pointer", "org/graalvm/word/UnsignedWord"},
                        0, new Object[0]));
        grown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        grown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        grown.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION, "allocateInBlock",
                        "(Lorg/graalvm/word/Pointer;Lorg/graalvm/word/UnsignedWord;)V", false));
        grown.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        grown.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION, "getInnerPointer",
                        "(Lorg/graalvm/word/Pointer;)Lorg/graalvm/word/Pointer;", false));
        grown.instructions.add(new InsnNode(Opcodes.ARETURN));
        grown.maxStack = 2;
        grown.maxLocals = 2;
        allocation.methods.add(grown);
        write(allocation, out);
    }

    private static void setFullFrame(FrameNode frame, Object... locals) {
        frame.type = Opcodes.F_FULL;
        frame.local = new ArrayList<>(List.of(locals));
        frame.stack = new ArrayList<>();
    }


    /**
     * Renames a method to `<name>Unlocked` and puts a locking wrapper under the original
     * name, so every existing call site now goes through the lock.
     */
    private static void widenToPublic(ClassNode node, String name, String descriptor) {
        // methodByDescriptor, not uniqueMethod: uniqueMethod matches on name alone and
        // writeBlockHeader is overloaded.
        MethodNode method = methodByDescriptor(node, name, descriptor);
        method.access = (method.access & ~Opcodes.ACC_PRIVATE & ~Opcodes.ACC_PROTECTED) | Opcodes.ACC_PUBLIC;
    }

    /** Replaces a body with one straight-line static call; no branch, so frames stay valid. */
    private static void replaceWithStaticCall(ClassNode node, String name, String descriptor,
                    String owner, String target) {
        MethodNode method = methodByDescriptor(node, name, descriptor);
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, target, descriptor, false));
        method.instructions.add(new InsnNode(Opcodes.ARETURN));
        method.maxStack = 2;
        method.maxLocals = 2;
    }

    private static void wrapWithLock(ClassNode node, String name, String descriptor, boolean returnsReference) {
        MethodNode original = uniqueMethod(node, name, descriptor);
        int access = original.access;
        original.name = name + "Unlocked";
        MethodNode wrapper = new MethodNode(access, name, descriptor, null, null);
        InsnList body = new InsnList();
        LabelNode start = new LabelNode();
        LabelNode end = new LabelNode();
        LabelNode handler = new LabelNode();

        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_LOCK, "lock", "()V", false));
        body.add(start);
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, ALLOCATION, original.name, descriptor, false));
        if (returnsReference) {
            body.add(new VarInsnNode(Opcodes.ASTORE, 1));
        }
        body.add(end);
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_LOCK, "unlock", "()V", false));
        if (returnsReference) {
            body.add(new VarInsnNode(Opcodes.ALOAD, 1));
            body.add(new InsnNode(Opcodes.ARETURN));
        } else {
            body.add(new InsnNode(Opcodes.RETURN));
        }
        body.add(handler);
        body.add(new FrameNode(Opcodes.F_FULL, 1, new Object[]{"java/lang/Object"}, 1, new Object[]{"java/lang/Throwable"}));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, HEAP_LOCK, "unlock", "()V", false));
        body.add(new InsnNode(Opcodes.ATHROW));

        wrapper.instructions = body;
        wrapper.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(start, end, handler, null));
        wrapper.maxStack = 2;
        wrapper.maxLocals = returnsReference ? 2 : 1;
        wrapper.visibleAnnotations = original.visibleAnnotations;
        node.methods.add(wrapper);
    }

    /**
     * `System.nanoTime()` reads a shared-memory clock instead of crossing to JS.
     *
     * Web Image lowers nanoTime to JSFunctionIntrinsics.performanceNow(), a wasm->JS
     * call. Because McWebLMThreads.park returns immediately on the browser thread (the
     * primary cannot block), every `while (!done) park()` in j.u.c becomes a tight loop
     * recomputing its deadline: measured at 5.0-7.6 MILLION crossings per second,
     * 126k-158k per rendered frame, and 45.3% of the game thread's CPU profile -- which
     * is why a frame cost ~33 ms whether 5 or 329 chunks were drawn. A Worker now
     * publishes the clock into the shared control block (web/thread-host.js) and this
     * reads it through a seqlock. Measured after: 60-70 crossings per frame, frameMs p50
     * 33.09 -> 16.52 ms.
     *
     * Straight-line replacement, no branch: this patcher must use COMPUTE_MAXS only, so
     * an injected jump fails with "Expecting a stackmap frame at branch target". The
     * fallback test lives in McWebLMClock.nanoTime.
     */
    private void patchSharedClock(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, SYSTEM_WEB);
        MethodNode method = methodByDescriptor(node, "nanoTime", "()J");
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        method.localVariables = null;
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLOCK, "nanoTime", "()J", false));
        method.instructions.add(new InsnNode(Opcodes.LRETURN));
        method.maxStack = 2;
        method.maxLocals = 0;
        write(node, out);
        log.add("patched " + SYSTEM_WEB + ".nanoTime -> McWebLMClock shared-memory clock");
    }

    /**
     * The TLAB replacement samples the safepoint every 64 small allocations, and the
     * shared-lock/refill path polls before it enters the allocator. Do not inject a
     * second poll into {@code WasmHeap.exitIfAllocationDisallowed}: that method is on
     * the remaining allocation paths as well, so the old hook turned every Java
     * allocation back into a runtime call and erased the TLAB's bounded-poll win.
     * Keep the upstream method itself intact; its no-allocation verifier still runs.
     */
    private void patchAllocationSafepoint(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, WASM_HEAP);
        MethodNode method = methodByDescriptor(node, "exitIfAllocationDisallowed",
                        "(Ljava/lang/String;Ljava/lang/String;)V");
        require((method.access & Opcodes.ACC_STATIC) != 0,
                        WASM_HEAP + ".exitIfAllocationDisallowed is no longer static");
        write(node, out);
        log.add("retained " + WASM_HEAP + ".exitIfAllocationDisallowed; TLAB/refill paths provide bounded safepoint polls");
    }

    /**
     * Turn collection with running agents from "refused" into a real stop-the-world:
     * `collect(GCCause, boolean)` is wrapped in a request/park/resume handshake, and root
     * marking additionally walks every parked agent's stack.
     */
    private void patchCollectorGuard(JarFile jarFile, Path out) throws IOException {
        ClassNode node = read(jarFile, COLLECTOR);
        /*
         * `collectWithoutAllocating` is the choke point: both the explicit
         * `collect(GCCause)` and the allocator's `maybeCollectOnAllocation` reach a
         * collection through it, and guarding only `collect` left agents collecting.
         */
        MethodNode collect = methodByDescriptor(node, "collectWithoutAllocating", "(Lcom/oracle/svm/core/heap/GCCause;Z)Z");
        collect.name = "mcwebCollectAtSafepoint";
        collect.access = (collect.access & ~(Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PRIVATE;

        MethodNode wrapper = new MethodNode(Opcodes.ACC_FINAL, "collectWithoutAllocating", "(Lcom/oracle/svm/core/heap/GCCause;Z)Z", null, null);
        InsnList body = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode skip = new LabelNode();
        Object[] locals = {COLLECTOR, "com/oracle/svm/core/heap/GCCause", Opcodes.INTEGER};

        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFEPOINT, "beginCollection", "()Z", false));
        body.add(new JumpInsnNode(Opcodes.IFEQ, skip));
        body.add(tryStart);
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new VarInsnNode(Opcodes.ILOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, COLLECTOR, collect.name, "(Lcom/oracle/svm/core/heap/GCCause;Z)Z", false));
        body.add(new VarInsnNode(Opcodes.ISTORE, 3));
        body.add(tryEnd);
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFEPOINT, "endCollection", "()V", false));
        body.add(new VarInsnNode(Opcodes.ILOAD, 3));
        body.add(new InsnNode(Opcodes.IRETURN));
        body.add(handler);
        body.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 1, new Object[]{"java/lang/Throwable"}));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFEPOINT, "endCollection", "()V", false));
        body.add(new InsnNode(Opcodes.ATHROW));
        body.add(skip);
        body.add(new FrameNode(Opcodes.F_FULL, locals.length, locals, 0, new Object[]{}));
        body.add(new InsnNode(Opcodes.ICONST_0));
        body.add(new InsnNode(Opcodes.IRETURN));

        wrapper.instructions = body;
        wrapper.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(tryStart, tryEnd, handler, null));
        wrapper.maxStack = 3;
        wrapper.maxLocals = 4;
        // Carries the callee's contract: the allocator calls this from uninterruptible code.
        wrapper.visibleAnnotations = collect.visibleAnnotations;
        node.methods.add(wrapper);

        // Roots: also mark what the parked agents hold, with the collector's own visitor.
        MethodNode blackenStackRoots = uniqueMethod(node, "blackenStackRoots", "()V");
        InsnList rootHook = new InsnList();
        rootHook.add(new VarInsnNode(Opcodes.ALOAD, 0));
        rootHook.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, COLLECTOR, "blackenStackRootsVisitor",
                        "Lcom/oracle/svm/hosted/webimage/wasm/stack/WebImageWasmStackFrameVisitor;"));
        rootHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SAFEPOINT, "walkParkedAgents",
                        "(Lcom/oracle/svm/hosted/webimage/wasm/stack/WebImageWasmStackFrameVisitor;)V", false));
        blackenStackRoots.instructions.insert(rootHook);
        blackenStackRoots.maxStack = Math.max(blackenStackRoots.maxStack, 2);

        write(node, out);
        log.add("patched " + COLLECTOR + ": stop-the-world collection + parked-agent stack roots");
    }


    /**
     * Make the mark phase O(live) instead of O(heap x passes). See {@code McWebLMMark}
     * for the mechanism; in short, upstream's marking worklist holds 128 objects and
     * every overflow costs a full walk of the block chain, which on a ~3 GiB Minecraft
     * arena is measured at 7.1 seconds per collection.
     *
     * <p>Four rewrites:
     *
     * <ol>
     *   <li>{@code GrayToBlackObjectVisitor}'s worklist is built with
     *       {@code McWebLMMark.STACK_ENTRIES} slots instead of 128, and its
     *       {@code worklist} field loses ACC_PRIVATE so the drain can reach it;</li>
     *   <li>{@code SizedObjectStack.hasSpace} defers to {@code McWebLMMark.hasSpace},
     *       which counts overflows and can restore the 128 bound at runtime so both arms
     *       of the comparison live in one nine-minute image;</li>
     *   <li>{@code WasmLMGC.blackenCollectedHeap} drains the roots transitively before
     *       the upstream rescan loop, which then finds nothing to do;</li>
     *   <li>{@code blackenCollectedHeap}, {@code releaseSpace} and
     *       {@code WasmHeap.walkCollectedHeapObjects} are wrapped for phase timing, so
     *       mark and sweep are separately attributable.</li>
     * </ol>
     */
    private void patchMarkWorklist(JarFile jarFile, Path out) throws IOException {
        /*
         * 1. The worklist field has to be reachable from the drain. The 128-entry array
         * itself is left exactly as upstream built it: it is a build-time image-heap
         * object, and growing it there cost the probes a halving step of
         * `WasmAllocation.initialize`'s reserve (see McWebLMMark.MIN_ENTRIES). The real
         * worklist is raw linear memory sized from the arena instead.
         */
        ClassNode visitor = read(jarFile, GRAY_VISITOR);
        FieldNode worklistField = null;
        for (FieldNode field : visitor.fields) {
            if (field.name.equals("worklist")) {
                worklistField = field;
            }
        }
        require(worklistField != null, GRAY_VISITOR + ".worklist not found");
        worklistField.access &= ~Opcodes.ACC_PRIVATE;
        write(visitor, out);

        /*
         * 2. hasSpace/push/pop go through McWebLMMark, which owns the off-heap worklist
         * and falls back to this object's own 128-entry array when there is none. The
         * fields it reads have to lose ACC_PRIVATE for that.
         */
        ClassNode stack = read(jarFile, MARK_STACK);
        for (FieldNode field : stack.fields) {
            if (field.name.equals("currentSize") || field.name.equals("stack")) {
                field.access &= ~Opcodes.ACC_PRIVATE;
            }
        }
        MethodNode hasSpace = uniqueMethod(stack, "hasSpace", "()Z");
        InsnList hasSpaceBody = new InsnList();
        hasSpaceBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hasSpaceBody.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, MARK_STACK, "currentSize", "I"));
        hasSpaceBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        hasSpaceBody.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, MARK_STACK, "maxSize", "I"));
        hasSpaceBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "hasSpace", "(II)Z", false));
        hasSpaceBody.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(hasSpace, hasSpaceBody, 2, 1);

        MethodNode push = uniqueMethod(stack, "push", "(Ljava/lang/Object;)V");
        InsnList pushBody = new InsnList();
        pushBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        pushBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        pushBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "push", "(L" + MARK_STACK + ";Ljava/lang/Object;)V", false));
        pushBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(push, pushBody, 2, 2);

        MethodNode pop = uniqueMethod(stack, "pop", "()Ljava/lang/Object;");
        InsnList popBody = new InsnList();
        popBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        popBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "pop", "(L" + MARK_STACK + ";)Ljava/lang/Object;", false));
        popBody.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(pop, popBody, 1, 1);
        write(stack, out);

        /* 3. Count every full walk of the block chain. */
        ClassNode heapStaged = readStaged(out, WASM_HEAP);
        require(heapStaged != null, WASM_HEAP + " must be patched before the mark instrumentation");
        MethodNode walk = uniqueMethod(heapStaged, "walkCollectedHeapObjects", "(Lcom/oracle/svm/core/heap/ObjectVisitor;)V");
        InsnList walkHook = new InsnList();
        walkHook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "heapWalk", "()V", false));
        walk.instructions.insert(walkHook);
        walk.maxStack = Math.max(walk.maxStack, 1);
        write(heapStaged, out);

        /* 4. Drain the roots, and time the two phases. */
        ClassNode collector = readStaged(out, COLLECTOR);
        require(collector != null, COLLECTOR + " must be patched before the mark rewrite");

        /*
         * The worklist is allocated at the head of the collect wrapper — before
         * `beginCollection`, so outside the collector's NoAllocationVerifier, and where
         * taking the reentrant heap lock is legal.
         */
        MethodNode collectWrapper = methodByDescriptor(collector, "collectWithoutAllocating", "(Lcom/oracle/svm/core/heap/GCCause;Z)Z");
        InsnList ensure = new InsnList();
        ensure.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "ensureStack", "()V", false));
        collectWrapper.instructions.insert(ensure);
        collectWrapper.maxStack = Math.max(collectWrapper.maxStack, 1);

        MethodNode blacken = uniqueMethod(collector, "blackenCollectedHeap", "()V");
        blacken.name = "mcwebBlackenCollectedHeap";
        MethodNode blackenWrapper = new MethodNode(Opcodes.ACC_PRIVATE, "blackenCollectedHeap", "()V", null, null);
        InsnList markBody = new InsnList();
        markBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "markBegin", "()V", false));
        markBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        markBody.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, COLLECTOR, "grayToBlackObjectVisitor",
                        "L" + GRAY_VISITOR + ";"));
        markBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "drainRoots", "(L" + GRAY_VISITOR + ";)V", false));
        markBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        markBody.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, COLLECTOR, blacken.name, "()V", false));
        markBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "markEnd", "()V", false));
        markBody.add(new InsnNode(Opcodes.RETURN));
        blackenWrapper.instructions = markBody;
        blackenWrapper.maxStack = 1;
        blackenWrapper.maxLocals = 1;
        collector.methods.add(blackenWrapper);

        /*
         * releaseSpace() -> McWebLMSweep.sweep(), with upstream's two-pass version kept
         * behind the runtime flag. Measured: with the worklist fixed, the mark phase is
         * 0 ms and the collection *is* this method, at 6.2 s per collection.
         */
        MethodNode release = uniqueMethod(collector, "releaseSpace", "()V");
        require((release.access & Opcodes.ACC_STATIC) != 0, COLLECTOR + ".releaseSpace is no longer static");
        release.name = "mcwebReleaseSpace";
        MethodNode releaseWrapper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "releaseSpace", "()V", null, null);
        InsnList sweepBody = new InsnList();
        LabelNode legacySweep = new LabelNode();
        LabelNode sweepDone = new LabelNode();
        sweepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "sweepBegin", "()V", false));
        sweepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SWEEP, "fused", "()Z", false));
        sweepBody.add(new JumpInsnNode(Opcodes.IFEQ, legacySweep));
        sweepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SWEEP, "sweep", "()V", false));
        sweepBody.add(new JumpInsnNode(Opcodes.GOTO, sweepDone));
        sweepBody.add(legacySweep);
        sweepBody.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        sweepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, COLLECTOR, release.name, "()V", false));
        sweepBody.add(sweepDone);
        sweepBody.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
        sweepBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MARK, "sweepEnd", "()V", false));
        sweepBody.add(new InsnNode(Opcodes.RETURN));
        releaseWrapper.instructions = sweepBody;
        releaseWrapper.maxStack = 1;
        releaseWrapper.maxLocals = 0;
        collector.methods.add(releaseWrapper);

        write(collector, out);

        /*
         * 5. The TLAB and sweep helpers use pointer-only allocator bridges injected
         * above. Keep the exact-counted widening for the TLAB's inner-pointer helper;
         * the sweep no longer needs a StackValue BlockHeader or upstream coalescer.
         */
        ClassNode allocation = readStaged(out, ALLOCATION);
        require(allocation != null, ALLOCATION + " must be patched before the fused sweep");
        String[] exposed = {"getInnerPointer"};
        for (String name : exposed) {
            int widened = 0;
            for (MethodNode method : allocation.methods) {
                if (method.name.equals(name)) {
                    method.access &= ~Opcodes.ACC_PRIVATE;
                    widened++;
                }
            }
            require(widened >= 1, ALLOCATION + "." + name + " not found to widen for the fused sweep");
        }
        write(allocation, out);

        log.add("patched " + GRAY_VISITOR + "/" + MARK_STACK + "/" + COLLECTOR + "/" + ALLOCATION
                        + ": heap-sized off-heap marking worklist, explicit root drain, single-pass sweep/free-list rebuild, phase timing");
    }

    /**
     * Give `synchronized` a real implementation. Three rewrites:
     *
     *   - `RemoveMonitorPhase.run` stops deleting the monitor nodes and turns them into
     *     foreign calls (the same shape the atomics phase used for CAS);
     *   - `SingleThreadedAtomicsFeature` also registers those two foreign calls and their
     *     targets as analysis roots, since a patch cannot add a Feature to a named module;
     *   - `WebImageSingleThreadedMonitorSupport`'s empty methods delegate to the real
     *     lock, so `Object.wait` releases the monitor and `holdsLock` tells the truth.
     */
    private void patchMonitors(JarFile jarFile, Path out) throws IOException {
        ClassNode phase = read(jarFile, MONITOR_PHASE);
        // `run` exists twice: the real one and BasePhase's bridge.
        MethodNode run = methodByDescriptor(phase, "run",
                        "(Ljdk/graal/compiler/nodes/StructuredGraph;Ljdk/graal/compiler/nodes/spi/CoreProviders;)V");
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MONITORS, "rewriteMonitors",
                        "(Ljdk/graal/compiler/nodes/StructuredGraph;)V", false));
        body.add(new InsnNode(Opcodes.RETURN));
        replaceBody(run, body, 1, 3);
        write(phase, out);

        ClassNode feature = read(jarFile, ATOMICS_FEATURE);
        MethodNode registerForeignCalls = uniqueMethod(feature, "registerForeignCalls",
                        "(Lcom/oracle/svm/core/graal/meta/SubstrateForeignCallsProvider;)V");
        InsnList registerHook = new InsnList();
        registerHook.add(new VarInsnNode(Opcodes.ALOAD, 1));
        registerHook.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETSTATIC, MONITORS, "FOREIGN_CALLS",
                        "[Lcom/oracle/svm/core/snippets/SnippetRuntime$SubstrateForeignCallDescriptor;"));
        registerHook.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/oracle/svm/core/graal/meta/SubstrateForeignCallsProvider",
                        "register", "([Lcom/oracle/svm/core/snippets/SnippetRuntime$SubstrateForeignCallDescriptor;)V", false));
        registerForeignCalls.instructions.insert(registerHook);

        write(feature, out);

        ClassNode support = read(jarFile, MONITOR_SUPPORT);
        delegate(support, "monitorEnter", "(Ljava/lang/Object;Lcom/oracle/svm/core/monitor/MonitorInflationCause;)V",
                        MONITORS, "enter", "(Ljava/lang/Object;)V", Opcodes.RETURN);
        delegate(support, "monitorExit", "(Ljava/lang/Object;Lcom/oracle/svm/core/monitor/MonitorInflationCause;)V",
                        MONITORS, "exit", "(Ljava/lang/Object;)V", Opcodes.RETURN);
        delegate(support, "isLockedByCurrentThread", "(Ljava/lang/Object;)Z",
                        MONITORS, "heldByCurrent", "(Ljava/lang/Object;)Z", Opcodes.IRETURN);
        delegate(support, "isLockedByAnyThread", "(Ljava/lang/Object;)Z",
                        MONITORS, "heldByAny", "(Ljava/lang/Object;)Z", Opcodes.IRETURN);
        MethodNode notify = uniqueMethod(support, "notify", "(Ljava/lang/Object;Z)V");
        InsnList notifyBody = new InsnList();
        notifyBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        notifyBody.add(new VarInsnNode(Opcodes.ILOAD, 2));
        notifyBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MONITORS, "notifyWaiters",
                        "(Ljava/lang/Object;Z)V", false));
        notifyBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(notify, notifyBody, 3, 3);
        MethodNode doWait = uniqueMethod(support, "doWait", "(Ljava/lang/Object;J)V");
        InsnList waitBody = new InsnList();
        waitBody.add(new VarInsnNode(Opcodes.ALOAD, 1));
        waitBody.add(new VarInsnNode(Opcodes.LLOAD, 2));
        waitBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MONITORS, "waitOn", "(Ljava/lang/Object;J)V", false));
        waitBody.add(new InsnNode(Opcodes.RETURN));
        replaceBody(doWait, waitBody, 3, 4);
        write(support, out);

        // `Thread.holdsLock` is substituted to `return true`, which makes every assertion
        // about lock ownership vacuous.
        ClassNode thread = readStaged(out, THREAD_TARGET);
        require(thread != null, THREAD_TARGET + " must be patched before holdsLock");
        MethodNode holdsLock = uniqueMethod(thread, "holdsLock", "(Ljava/lang/Object;)Z");
        InsnList holdsBody = new InsnList();
        holdsBody.add(new VarInsnNode(Opcodes.ALOAD, 0));
        holdsBody.add(new MethodInsnNode(Opcodes.INVOKESTATIC, MONITORS, "heldByCurrent", "(Ljava/lang/Object;)Z", false));
        holdsBody.add(new InsnNode(Opcodes.IRETURN));
        replaceBody(holdsLock, holdsBody, 1, 1);
        write(thread, out);

        log.add("patched monitors: RemoveMonitorPhase -> foreign calls, MonitorSupport wait/notify + Thread.holdsLock -> McWebLMMonitors");
    }

    /** Replaces an instance method's body with a call to a static helper taking (arg1). */
    private static void delegate(ClassNode node, String name, String descriptor, String owner, String target,
                    String targetDescriptor, int returnOpcode) {
        MethodNode method = uniqueMethod(node, name, descriptor);
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, target, targetDescriptor, false));
        body.add(new InsnNode(returnOpcode));
        replaceBody(method, body, 2, 1 + descriptor.substring(1, descriptor.indexOf(')')).split(";").length);
    }

    /**
     * Per-agent VM thread-locals: the holder becomes a call instead of a constant, and the
     * build records the primary's holders plus the current-thread slot index.
     */
    private void patchThreadLocals(JarFile jarFile, Path out) throws IOException {
        ClassNode lowerer = readStaged(out, LOWERER);
        require(lowerer != null, LOWERER + " must be patched before the thread-local holder");
        MethodNode holder = uniqueMethod(lowerer, "lowerThreadLocalHolder",
                        "(Lcom/oracle/svm/hosted/webimage/wasm/nodes/WebImageWasmVMThreadLocalSTHolderNode;)" + INSTRUCTION);
        InsnList body = new InsnList();
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new VarInsnNode(Opcodes.ALOAD, 1));
        body.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREAD_LOCAL_LOWERING, "lowerHolder",
                        "(L" + LOWERER + ";Lcom/oracle/svm/hosted/webimage/wasm/nodes/WebImageWasmVMThreadLocalSTHolderNode;)" + INSTRUCTION, false));
        body.add(new InsnNode(Opcodes.ARETURN));
        replaceBody(holder, body, 2, 2);
        write(lowerer, out);

        ClassNode feature = read(jarFile, ST_FEATURE);
        MethodNode beforeCompilation = uniqueMethod(feature, "beforeCompilation",
                        "(Lorg/graalvm/nativeimage/hosted/Feature$BeforeCompilationAccess;)V");
        // Append: the offsets and the primary's holders only exist at the end of this method.
        InsnList tail = new InsnList();
        tail.add(new VarInsnNode(Opcodes.ALOAD, 0));
        tail.add(new org.objectweb.asm.tree.FieldInsnNode(Opcodes.GETFIELD, ST_FEATURE, "threadLocalCollector",
                        "Lcom/oracle/svm/hosted/thread/VMThreadLocalCollector;"));
        tail.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/oracle/svm/hosted/thread/VMThreadLocalCollector",
                        "getSortedThreadLocalInfos", "()Ljava/util/List;", false));
        tail.add(new MethodInsnNode(Opcodes.INVOKESTATIC, THREAD_LOCALS, "recordCurrentThreadIndex", "(Ljava/util/List;)V", false));
        AbstractInsnNode last = beforeCompilation.instructions.getLast();
        while (last != null && last.getOpcode() != Opcodes.RETURN) {
            last = last.getPrevious();
        }
        require(last != null, ST_FEATURE + ".beforeCompilation has no return to append to");
        beforeCompilation.instructions.insertBefore(last, tail);
        beforeCompilation.maxStack = Math.max(beforeCompilation.maxStack, 2);
        write(feature, out);

        // The per-agent state has to live on the singleton the image heap already knows
        // is assigned late; a static of our own class would not survive into the image.
        ClassNode support = read(jarFile, ST_SUPPORT);
        FieldNode template = support.fields.stream().filter(f -> f.name.equals("objectThreadLocals")).findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                        "webimage patch precondition failed: " + ST_SUPPORT + ".objectThreadLocals not found"));
        require(template.visibleAnnotations != null && !template.visibleAnnotations.isEmpty(),
                        ST_SUPPORT + ".objectThreadLocals lost its late-assignment annotation");
        addField(support, "mcwebObjectHolders", "[[Ljava/lang/Object;", template.visibleAnnotations);
        addField(support, "mcwebPrimitiveHolders", "[[B", template.visibleAnnotations);
        FieldNode index = addField(support, "mcwebCurrentThreadIndex", "I", null);
        AnnotationNode lateInt = new AnnotationNode("Lcom/oracle/svm/core/heap/UnknownPrimitiveField;");
        lateInt.visit("availability", Type.getType("Lcom/oracle/svm/core/BuildPhaseProvider$ReadyForCompilation;"));
        index.visibleAnnotations = List.of(lateInt);
        write(support, out);

        log.add("patched " + LOWERER + ".lowerThreadLocalHolder -> per-agent holders");
    }

    /**
     * JLS class-initialization exclusion across agents — ROOT CAUSE, fixed here.
     *
     * <p>The >2-worker worldgen NPE (racing `<clinit>` reads null statics) is
     * <em>not</em> a memory-ordering problem. It is a broken thread identity:
     *
     * <p>{@code slowPath} opens with {@code CurrentIsolate.getCurrentThread()} and uses
     * that word to decide, under {@code initLock}, whether a class already
     * {@code BeingInitialized} is being initialized by <em>this</em> thread
     * ({@code isReentrantInitialization} — {@code initThread.equal(current)}). A
     * reentrant caller must not wait on the condition, and must return immediately.
     *
     * <p>On the Web Image backend {@code CurrentIsolate.getCurrentThread()} is an
     * invocation plugin ({@code JSGraphBuilderPlugins$28}) that constant-folds to
     * {@code SINGLE_THREAD_SENTINEL = 0x150150150150777L} — the backend is
     * single-threaded upstream, so one sentinel is correct there. Under WasmLM agents
     * every agent gets the <em>same</em> constant. Verified in the emitted WAT of
     * {@code LmClassInit} (`slowPath`):
     *
     * <pre>
     *   if (initState == BeingInitialized)
     *       if (i64.load offset=0x28 (initThread) == i64.const 0x150150150150777) br $lb1
     * </pre>
     *
     * <p>So once agent A publishes {@code initThread = sentinel} and starts the
     * {@code <clinit>}, every other agent B evaluates "reentrant" as TRUE, skips the
     * condition wait, unlocks and <em>returns from slowPath without initializing</em>.
     * B's caller then reads the statics A has not written yet → null. That is the exact
     * ~50% null rate the probe measures (12/24 at 3 threads, 22/40 at 5), the
     * determinism, and why the earlier experiments all failed: forcing every class
     * through the locked path cannot help when the locked path itself hands out a false
     * reentrancy verdict, and it deadlocks because a "reentrant" winner also skips the
     * state machine.
     *
     * <p>Ruled out beforehand, with evidence, so nobody re-derives it:
     * {@code ReentrantLock}/{@code Condition} exclusion and wait/signal are sound across
     * agents including out-of-lock store publication ({@code tools/wasmlm-probes/LmInitLock},
     * 60 rounds at 3 and 5 threads, zero violations); the fast-path gate
     * ({@code slowPathRequired}, emitted as a plain {@code i32.load8_u offset=0xc}) is
     * not the hole; fences are unusable because {@code MembarNode} is in the lowerer's
     * {@code IGNORED_NODE_TYPES} (zero {@code atomic.fence} in the WAT) and narrow
     * {@code volatile} fields inject MembarNodes the backend rejects.
     *
     * <p>FIX, two parts:
     *
     * <ol>
     *   <li>Replace the single {@code CurrentIsolate.getCurrentThread()} call in
     *       {@code slowPath} with {@code Word.unsigned(Thread.currentThread().threadId())},
     *       cast to {@code IsolateThread}. Thread ids are unique and never 0, so they are
     *       a sound identity for a word field whose "unowned" value is
     *       {@code Word.nullPointer()} (which {@code setInitializationStateAndNotify}
     *       already stores). The word-typed {@code checkcast} is erased by SVM's word
     *       type rewriting — the same shape the class already uses for
     *       {@code Word.nullPointer() -> IsolateThread}. This is the only
     *       {@code getCurrentThread} call in the class, so no other behaviour moves.</li>
     *   <li>Make {@code initState} {@code volatile}. It is a REFERENCE field, so
     *       {@code McWebLMAtomics} lowers its accesses to real
     *       {@code i32.atomic.load}/{@code i32.atomic.store} (confirmed in the WAT)
     *       rather than a rejected Membar. That gives the release/acquire pair the
     *       dropped {@code Unsafe.storeFence} in {@code setInitializationStateAndNotify}
     *       compiles away to.</li>
     * </ol>
     */
    private void patchClassInitPublication(JarFile svmJar, Path out) throws IOException {
        ClassNode node = read(svmJar, CLASS_INIT_INFO);

        FieldNode initState = node.fields.stream()
                        .filter(f -> f.name.equals("initState")).findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                        "webimage patch precondition failed: " + CLASS_INIT_INFO + ".initState not found"));
        initState.access |= Opcodes.ACC_VOLATILE;

        MethodNode slowPath = uniqueMethod(node, "slowPath",
                        "(L" + CLASS_INIT_INFO + ";Lcom/oracle/svm/core/hub/DynamicHub;)V");
        int replaced = 0;
        for (AbstractInsnNode instruction : slowPath.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                            || call.getOpcode() != Opcodes.INVOKESTATIC
                            || !call.owner.equals("org/graalvm/nativeimage/CurrentIsolate")
                            || !call.name.equals("getCurrentThread")) {
                continue;
            }
            InsnList identity = new InsnList();
            identity.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread",
                            "currentThread", "()Ljava/lang/Thread;", false));
            identity.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Thread",
                            "threadId", "()J", false));
            identity.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "jdk/graal/compiler/word/Word",
                            "unsigned", "(J)Lorg/graalvm/word/UnsignedWord;", false));
            identity.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/graalvm/nativeimage/IsolateThread"));
            slowPath.instructions.insertBefore(call, identity);
            slowPath.instructions.remove(call);
            replaced++;
        }
        require(replaced == 1, CLASS_INIT_INFO
                        + ".slowPath must call CurrentIsolate.getCurrentThread exactly once, found " + replaced);
        slowPath.maxStack = Math.max(slowPath.maxStack, 2);

        write(node, out);
        log.add("patched " + CLASS_INIT_INFO
                        + ": slowPath thread identity -> Thread.threadId (was the single-thread sentinel),"
                        + " initState -> volatile");
    }

    private static FieldNode addField(ClassNode node, String name, String descriptor, List<AnnotationNode> annotations) {
        require(node.fields.stream().noneMatch(f -> f.name.equals(name)), node.name + " already declares " + name);
        FieldNode field = new FieldNode(Opcodes.ACC_PUBLIC, name, descriptor, null, null);
        field.visibleAnnotations = annotations;
        node.fields.add(field);
        return field;
    }

    private static boolean containsForeignCall(MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof org.objectweb.asm.tree.TypeInsnNode type
                            && type.desc.contains("ForeignCallNode")) {
                return true;
            }
        }
        return false;
    }

    private static MethodNode methodByDescriptor(ClassNode classNode, String name, String descriptor) {
        List<MethodNode> matches = classNode.methods.stream()
                        .filter(m -> m.name.equals(name) && m.desc.equals(descriptor)).toList();
        require(matches.size() == 1, classNode.name + "." + name + descriptor + ": expected exactly 1 declaration, found " + matches.size());
        return matches.get(0);
    }

    private static MethodNode node(ClassNode classNode, String name) {
        List<MethodNode> matches = classNode.methods.stream().filter(m -> m.name.equals(name)).toList();
        require(matches.size() == 1, classNode.name + "." + name + ": expected exactly 1 declaration, found " + matches.size());
        return matches.get(0);
    }

    private static void widenField(ClassNode classNode, String name) {
        List<org.objectweb.asm.tree.FieldNode> matches = classNode.fields.stream().filter(f -> f.name.equals(name)).toList();
        require(matches.size() == 1, classNode.name + "." + name + ": expected exactly 1 field, found " + matches.size());
        org.objectweb.asm.tree.FieldNode field = matches.get(0);
        field.access = (field.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC;
    }

    /** A class this run already rewrote, so several patches can stack on one class. */
    private static ClassNode readStaged(Path out, String internalName) throws IOException {
        Path staged = out.resolve(internalName + ".class");
        if (!Files.isRegularFile(staged)) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(Files.readAllBytes(staged)).accept(node, 0);
        return node;
    }

    /** Instruction count ignoring labels, line numbers and frames. */
    private static int realInstructionCount(MethodNode method) {
        int count = 0;
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i).getOpcode() >= 0) {
                count++;
            }
        }
        return count;
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode instruction) {
        AbstractInsnNode next = instruction.getNext();
        while (next != null && next.getOpcode() < 0) {
            next = next.getNext();
        }
        return next;
    }

    private static boolean containsStubCall(MethodNode method) {
        for (int i = 0; i < method.instructions.size(); i++) {
            if (method.instructions.get(i) instanceof MethodInsnNode call && call.name.equals("getStub")) {
                return true;
            }
        }
        return false;
    }

    private static void replaceBody(MethodNode method, InsnList body, int maxStack, int maxLocals) {
        method.instructions.clear();
        method.tryCatchBlocks.clear();
        if (method.localVariables != null) {
            method.localVariables.clear();
        }
        method.instructions.add(body);
        method.maxStack = maxStack;
        method.maxLocals = maxLocals;
    }

    private static MethodNode uniqueMethod(ClassNode node, String name, String descriptor) {
        List<MethodNode> matches = node.methods.stream().filter(m -> m.name.equals(name)).toList();
        require(matches.size() == 1, node.name + "." + name + ": expected exactly 1 declaration, found " + matches.size());
        MethodNode method = matches.get(0);
        require(method.desc.equals(descriptor),
                        node.name + "." + name + ": expected descriptor " + descriptor + ", found " + method.desc);
        return method;
    }

    private static ClassNode read(JarFile jarFile, String internalName) throws IOException {
        ZipEntry entry = jarFile.getEntry(internalName + ".class");
        require(entry != null, "class not found in jar: " + internalName);
        try (InputStream in = jarFile.getInputStream(entry)) {
            ClassNode node = new ClassNode();
            new ClassReader(in.readAllBytes()).accept(node, 0);
            return node;
        }
    }

    private static void write(ClassNode node, Path out) throws IOException {
        // COMPUTE_MAXS only: recomputing frames would need the builder's type
        // hierarchy, which is not on this tool's classpath. Every rewritten body is
        // straight-line code, so it carries no stack map entries and the untouched
        // methods keep their original frames verbatim.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        Path target = out.resolve(node.name + ".class");
        Files.createDirectories(target.getParent());
        Files.write(target, writer.toByteArray());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException("webimage patch precondition failed: " + message);
        }
    }
}
