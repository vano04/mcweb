/*
 * MC-Web builder patch: real Wasm atomics for the WasmLM backend.
 *
 * Upstream lowers every atomic operation for a single thread:
 *
 *   - `WasmLMSingleThreadedAtomicsPhase.processCAS` replaces compare-and-swap with a
 *     foreign call to `SingleThreadedAtomics`, which does a plain read and write;
 *   - `SingleThreadedAtomicsPhase` rewrites `LoweredAtomicReadAndWrite/ReadAndAdd`
 *     into a `ReadNode` + `WriteNode` pair;
 *   - `WebImageWasmLMNodeLowerer` ignores `MemoryOrderMode`, so a `volatile` field
 *     access is an ordinary load or store.
 *
 * All three are correct for one thread and silently wrong for several: without them
 * `AtomicInteger`, `ConcurrentHashMap`, `CompletableFuture` and the `ForkJoinPool`
 * lose updates, and a plain load in a spin loop can be hoisted forever.
 *
 * The patch disables those rewrites and lowers the surviving nodes here, to threads
 * -proposal instructions. Seq-cst is the only ordering wasm atomics have, which is
 * stronger than Java needs and therefore always safe.
 */
package com.oracle.svm.hosted.webimage.wasm.codegen;

import com.oracle.svm.hosted.webimage.wasm.ast.Instruction;
import com.oracle.svm.hosted.webimage.wasm.ast.McWebAtomicOp;
import com.oracle.svm.hosted.webimage.wasm.ast.id.WasmId;
import com.oracle.svm.webimage.wasm.types.WasmLMUtil;
import com.oracle.svm.webimage.wasm.types.WasmPrimitiveType;

import jdk.graal.compiler.core.common.memory.MemoryOrderMode;
import jdk.graal.compiler.nodes.NodeView;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.MembarNode;
import jdk.graal.compiler.nodes.java.AbstractCompareAndSwapNode;
import jdk.graal.compiler.nodes.java.LogicCompareAndSwapNode;
import jdk.graal.compiler.nodes.java.LoweredAtomicReadAndAddNode;
import jdk.graal.compiler.nodes.java.LoweredAtomicReadAndWriteNode;
import jdk.graal.compiler.nodes.memory.ReadNode;
import jdk.graal.compiler.nodes.memory.WriteNode;
import jdk.vm.ci.meta.JavaKind;

public final class McWebLMAtomics {

    private McWebLMAtomics() {
    }

    /**
     * Prologue hook for {@code WebImageWasmLMNodeLowerer.dispatch}. Returns null for
     * every node this class does not handle, leaving the upstream dispatch untouched.
     */
    public static Instruction tryLower(WebImageWasmLMNodeLowerer lowerer, ValueNode node) {
        return switch (node) {
            case AbstractCompareAndSwapNode cas -> lowerCompareAndSwap(lowerer, cas);
            case LoweredAtomicReadAndWriteNode xchg -> lowerReadModifyWrite(lowerer, xchg, "xchg", xchg.getNewValue());
            case LoweredAtomicReadAndAddNode add -> lowerReadModifyWrite(lowerer, add, "add", add.delta());
            case MembarNode ignored -> new McWebAtomicOp("atomic.fence", null);
            case ReadNode read -> lowerOrderedRead(lowerer, read);
            case WriteNode write -> lowerOrderedWrite(lowerer, write);
            default -> null;
        };
    }

    /*
     * MembarNode is the JMM's final-field freeze, and the single-threaded backend
     * lowered it to nothing.
     *
     * Graal ends every constructor that writes a final field with a barrier so a
     * thread obtaining the reference - even through a plain data race, with no
     * synchronisation at all - is guaranteed to see initialised fields (JLS 17.5).
     * Dropping it is sound on one thread and catastrophic on several: WebAssembly
     * plain stores carry no ordering, so the store publishing an object can become
     * visible before the stores that fill it in.
     *
     * Kept on correctness grounds, NOT because it fixed an observed fault.
     * tools/wasmlm-probes/LmPublication (5 readers, 9.6M observations of records
     * published through a plain static with no synchronisation) reports zero null final
     * fields both with and without this fence, so no reordering is demonstrable here on
     * V8 today. It is emitted anyway because JLS 17.5 requires the freeze and this lane
     * is genuinely multithreaded; a missing barrier is a latent fault, not a free win.
     * Do NOT cite this as the cause of the multi-worker worldgen NPE - that remains
     * open, and an earlier claim that it was came from a broken probe.
     *
     * atomic.fence is sequentially consistent, stronger than the StoreStore the
     * freeze strictly requires, and is emitted only where Graal already placed a
     * barrier, so paths that never had one are unaffected.
     */
    private static Instruction lowerCompareAndSwap(WebImageWasmLMNodeLowerer lowerer, AbstractCompareAndSwapNode cas) {
        ValueNode expectedNode = cas.getExpectedValue();
        int bits = accessBits(expectedNode);
        WasmPrimitiveType type = wasmType(expectedNode);
        Instruction address = lowerer.lowerExpression(cas.getAddress());
        Instruction newValue = lowerer.lowerExpression(cas.getNewValue());

        if (!(cas instanceof LogicCompareAndSwapNode)) {
            // Value CAS: the result is the previous value, which is exactly what
            // cmpxchg leaves on the stack.
            return new McWebAtomicOp(mnemonic(type, bits, "cmpxchg"), type, address, lowerer.lowerExpression(expectedNode), newValue);
        }

        /*
         * Logic CAS returns whether the swap happened, so the expected value is needed
         * twice. Lowering it twice could duplicate a side effect, so tee it through a
         * temporary local: `local.tee` stores it and leaves it on the stack for
         * cmpxchg, and the comparison then reads the local.
         */
        WasmId.Local expectedLocal = lowerer.masm().idFactory.newTemporaryVariable(type);
        Instruction expectedTee = new Instruction.LocalTee(expectedLocal, lowerer.lowerExpression(expectedNode));
        Instruction previous = new McWebAtomicOp(mnemonic(type, bits, "cmpxchg"), type, address, expectedTee, newValue);
        Instruction.Binary.Op equality = type == WasmPrimitiveType.i64 ? Instruction.Binary.Op.I64Eq : Instruction.Binary.Op.I32Eq;
        return equality.create(previous, new Instruction.LocalGet(expectedLocal));
    }

    private static Instruction lowerReadModifyWrite(WebImageWasmLMNodeLowerer lowerer, ValueNode node, String op, ValueNode operand) {
        WasmPrimitiveType type = wasmType(node);
        int bits = accessBits(node);
        Instruction address = lowerer.lowerExpression(((jdk.graal.compiler.nodes.memory.FixedAccessNode) node).getAddress());
        return new McWebAtomicOp(mnemonic(type, bits, op), type, address, lowerer.lowerExpression(operand));
    }

    /**
     * A `volatile` (or otherwise ordered) read becomes an atomic load. Narrow and
     * floating-point accesses fall through to the plain lowering: atomic loads have no
     * sign-extending or floating-point forms, and synthesising them is not worth it
     * until something needs a volatile `byte`, `short`, `char`, `float` or `double`
     * field across agents.
     */
    private static Instruction lowerOrderedRead(WebImageWasmLMNodeLowerer lowerer, ReadNode read) {
        if (!isOrdered(read.getMemoryOrder()) || !isFullWidthIntegral(read)) {
            return null;
        }
        WasmPrimitiveType type = wasmType(read);
        return new McWebAtomicOp(type.toString() + ".atomic.load", type, lowerer.lowerExpression(read.getAddress()));
    }

    private static Instruction lowerOrderedWrite(WebImageWasmLMNodeLowerer lowerer, WriteNode write) {
        if (!isOrdered(write.getMemoryOrder()) || !isFullWidthIntegral(write.value())) {
            return null;
        }
        WasmPrimitiveType type = wasmType(write.value());
        return new McWebAtomicOp(type.toString() + ".atomic.store", null,
                        lowerer.lowerExpression(write.getAddress()), lowerer.lowerExpression(write.value()));
    }

    /**
     * Replacement for {@code WasmAssembler.Binaryen.getExtraFlags}: wasm-as rejects
     * every atomic instruction and a shared memory without --enable-threads.
     */
    public static java.util.List<String> binaryenFlags() {
        return java.util.List.of("--enable-exception-handling", "--enable-nontrapping-float-to-int", "--enable-bulk-memory",
                        "--enable-reference-types", "--enable-gc", "--enable-threads");
    }

    private static boolean isOrdered(MemoryOrderMode order) {
        return order != null && MemoryOrderMode.ordersMemoryAccesses(order);
    }

    private static boolean isFullWidthIntegral(ValueNode node) {
        JavaKind kind = node.getStackKind();
        if (kind == JavaKind.Object) {
            return true;
        }
        if (kind != JavaKind.Int && kind != JavaKind.Long) {
            return false;
        }
        return accessBits(node) == kind.getBitCount();
    }

    private static WasmPrimitiveType wasmType(ValueNode node) {
        JavaKind kind = node.getStackKind();
        return kind == JavaKind.Object || kind == JavaKind.Int ? WasmPrimitiveType.i32
                        : kind == JavaKind.Long ? WasmPrimitiveType.i64 : null;
    }

    /**
     * Width of the memory access in bits. Object references are pointer-sized; integers
     * carry their width in the stamp (a CAS on a `byte` field is 8 bits wide).
     */
    private static int accessBits(ValueNode node) {
        JavaKind kind = node.getStackKind();
        if (kind == JavaKind.Object) {
            return WasmLMUtil.POINTER_KIND.getBitCount();
        }
        if (node.stamp(NodeView.DEFAULT) instanceof jdk.graal.compiler.core.common.type.IntegerStamp stamp) {
            return stamp.getBits();
        }
        return kind.getBitCount();
    }

    /**
     * `i32.atomic.rmw.add`, `i32.atomic.rmw8.add_u`, `i64.atomic.rmw32.cmpxchg_u`, ...
     * Narrow forms are always zero-extending, which is what the JDK's narrow CAS
     * helpers expect.
     */
    private static String mnemonic(WasmPrimitiveType type, int bits, String op) {
        String prefix = type.toString() + ".atomic.rmw";
        if (bits == type.getBitCount()) {
            return prefix + "." + op;
        }
        return prefix + bits + "." + op + "_u";
    }
}
