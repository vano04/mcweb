/*
 * MC-Web builder patch: a Wasm threads-proposal instruction the upstream AST has no
 * node for.
 *
 * The WasmLM backend lowers every compare-and-swap to a non-atomic foreign call
 * (`WasmLMSingleThreadedAtomicsPhase` -> `SingleThreadedAtomics`), turns atomic
 * read-modify-writes into plain load/store pairs, and ignores `MemoryOrderMode`
 * entirely. That is sound for one thread and silently wrong for several, so
 * `java.util.concurrent` cannot be trusted across agents.
 *
 * Since the LM backend assembles through WAT text (`wasm-as`), a real atomic
 * instruction only needs a name, its operands and its result type; the printer emits
 * the text and Binaryen validates it (`--enable-threads`). Alignment is left implicit,
 * which in the text format means natural alignment - exactly what atomics require.
 */
package com.oracle.svm.hosted.webimage.wasm.ast;

import com.oracle.svm.webimage.wasm.types.WasmValType;

public final class McWebAtomicOp extends Instruction {

    /** Text-format mnemonic, for example {@code i32.atomic.rmw.cmpxchg}. */
    public final String opName;

    /** Result type pushed by this instruction, or {@code null} if it pushes nothing. */
    public final WasmValType resultType;

    /** Operands, in wasm stack order. */
    public final Instruction[] operands;

    public McWebAtomicOp(String opName, WasmValType resultType, Instruction... operands) {
        this.opName = opName;
        this.resultType = resultType;
        this.operands = operands;
    }

    @Override
    protected String toInnerString() {
        return opName;
    }
}
