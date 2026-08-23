/*
 * MC-Web builder patch: teach the Wasm AST visitors about McWebAtomicOp.
 *
 * `WasmVisitor.visitInstruction` is a `switch` over the known instruction classes whose
 * default branch reports an unknown node, so a new instruction needs one hook there.
 * This class is that hook. It lives in the visitors' own package so it can reach
 * `WasmPrinter.WriterWrapper` and `WasmPrinter.Indenter`; the two private members it
 * needs (`WasmPrinter.writer`, `WasmValidator.pushVal`) are widened to public by
 * tools/webimage-patch/McWebImagePatcher.java.
 */
package com.oracle.svm.hosted.webimage.wasm.ast.visitors;

import com.oracle.svm.hosted.webimage.wasm.ast.Instruction;
import com.oracle.svm.hosted.webimage.wasm.ast.McWebAtomicOp;
import com.oracle.svm.hosted.webimage.wasm.ast.Memory;

public final class McWebAtomicVisitors {

    /**
     * Pages the shared memory may grow to. A wasm {@code shared} memory must declare a
     * maximum, and every agent instance must agree on it because they all import the
     * same memory. 65536 pages = 4 GiB, matching the maximum the browser thread host
     * creates ({@code web/thread-host.js}) and the one
     * {@code tools/stage-wasmlm-browser.mjs} rewrites the shared pair with.
     *
     * <p>This ceiling is load-bearing, not a safety margin: the allocator reserves its
     * arena up front, and a ceiling below that reserve makes the reservation fail and
     * silently drops the image back to upstream's one-page arena, which collects the
     * whole heap before every growth. At 512 MiB that alone cost Minecraft its boot.
     */
    public static final int MAX_MEMORY_PAGES = Integer.getInteger("mcweb.wasm.maxMemoryPages", 65536);

    private McWebAtomicVisitors() {
    }

    /**
     * Handles {@link McWebAtomicOp} for any visitor. Returns false for every other
     * instruction so the upstream switch runs unchanged.
     */
    public static boolean visit(WasmVisitor visitor, Instruction instruction) {
        if (!(instruction instanceof McWebAtomicOp atomic)) {
            return false;
        }
        if (visitor instanceof WasmPrinter printer) {
            printer.writer.print(atomic.opName);
            for (Instruction operand : atomic.operands) {
                printer.visitInstruction(operand);
            }
        } else if (visitor instanceof WasmValidator validator) {
            // The operands are deliberately not walked: skipping them leaves the
            // validator's value stack balanced (nothing pushed, then our single
            // result), and wasm-as type-checks the emitted module anyway.
            if (atomic.resultType != null) {
                validator.pushVal(atomic.resultType);
            }
        } else {
            // Element creation, id resolution and relocation processing all just need
            // to reach the operands.
            for (Instruction operand : atomic.operands) {
                visitor.visitInstruction(operand);
            }
        }
        return true;
    }

    /**
     * Replacement for {@code WasmPrinter.visitMemory}: a memory that atomics may
     * operate on, and that several instances may import, has to be declared
     * {@code shared}, which in turn requires a maximum.
     */
    public static void printMemory(WasmPrinter printer, Memory memory) {
        WasmPrinter.WriterWrapper writer = printer.writer;
        writer.print("(memory ");
        writer.print("$" + memory.id.getName());
        writer.print(" " + memory.limit.getMin());
        int max = Math.max(MAX_MEMORY_PAGES, memory.limit.hasMax() ? memory.limit.getMax() : memory.limit.getMin());
        writer.print(" " + max);
        writer.print(" shared)");
    }
}
