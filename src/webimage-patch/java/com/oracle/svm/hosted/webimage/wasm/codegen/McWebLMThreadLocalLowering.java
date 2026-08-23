/*
 * MC-Web builder patch: lower the VM thread-local holder to a per-agent lookup.
 *
 * Upstream emits a constant relocation to the primary holder (see
 * `WebImageWasmLMNodeLowerer.lowerThreadLocalHolder`). This emits an inline marker load
 * with that relocation as the bootstrap fallback, so attached carriers read their own
 * holder without a helper call on every VM-thread-local access. The returned value is
 * still the array object pointer, so the element offsets the feature assigned keep
 * working unchanged.
 */
package com.oracle.svm.hosted.webimage.wasm.codegen;

import com.oracle.svm.hosted.webimage.wasm.WebImageWasmVMThreadLocalSTSupport;
import com.oracle.svm.hosted.webimage.wasm.ast.Instruction;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMThreadLocals;
import com.oracle.svm.hosted.webimage.wasm.nodes.WebImageWasmVMThreadLocalSTHolderNode;
import com.oracle.svm.webimage.wasm.types.WasmPrimitiveType;

import org.graalvm.nativeimage.ImageSingletons;


public final class McWebLMThreadLocalLowering {

    /** Must match the marker layout in {@link McWebLMThreadLocals}. */
    private static final int AGENT_STACK_BYTES = 1 << 20;
    private static final int AGENT_STACK_MARKER = 0x4d434147;
    private static final int OBJECT_HOLDER_OFFSET = 8;
    private static final int PRIMITIVE_HOLDER_OFFSET = 12;

    private McWebLMThreadLocalLowering() {
    }

    public static Instruction lowerHolder(WebImageWasmLMNodeLowerer lowerer, WebImageWasmVMThreadLocalSTHolderNode node) {
        boolean object = node.getThreadLocalInfo().isObject;
        WebImageWasmVMThreadLocalSTSupport support = ImageSingletons.lookup(WebImageWasmVMThreadLocalSTSupport.class);
        Object primaryHolder = object ? support.objectThreadLocals : support.primitiveThreadLocals;

        /*
         * The ordinary lowering is a constant relocation to primaryHolder. A Java helper
         * call fixes correctness for agents, but the custom backend emits that call below
         * Graal's normal inliner, so it remains on every VM-thread-local access. Keep the
         * fallback as the same constant relocation and put only the marker test in the
         * generated expression. That makes the invalid/early-bootstrap path safe without
         * paying a fallback call on the hot path.
         */
        Instruction primary = lowerer.masm().getConstantRelocation(
                        lowerer.masm().getProviders().getSnippetReflection().forObject(primaryHolder));
        int holderOffset = object ? OBJECT_HOLDER_OFFSET : PRIMITIVE_HOLDER_OFFSET;
        Instruction marker = alignedStackPointer(lowerer);
        Instruction markerWord = new Instruction.Load(WasmPrimitiveType.i32, 0, marker, 0, false);
        Instruction holderAddress = new Instruction.Load(WasmPrimitiveType.i32, holderOffset, alignedStackPointer(lowerer), 0, false);
        Instruction markerReady = Instruction.Binary.Op.I32Eq.create(markerWord, Instruction.Const.forInt(AGENT_STACK_MARKER));
        Instruction holderReady = Instruction.Binary.Op.I32Ne.create(holderAddress, Instruction.Const.forInt(0));
        Instruction ready = Instruction.Binary.Op.I32And.create(markerReady, holderReady);
        Instruction liveHolder = new Instruction.Load(WasmPrimitiveType.i32, holderOffset, alignedStackPointer(lowerer), 0, false);
        return new Instruction.Select(liveHolder, primary, ready, WasmPrimitiveType.i32)
                        .setComment("inline per-agent thread-local holder");
    }

    private static Instruction alignedStackPointer(WebImageWasmLMNodeLowerer lowerer) {
        return Instruction.Binary.Op.I32And.create(
                        lowerer.masm().getKnownIds().stackPointer.getter(),
                        Instruction.Const.forInt(-AGENT_STACK_BYTES));
    }
}
