/*
 * MC-Web builder patch: sweep the block chain once per collection instead of twice.
 *
 * WHAT THIS IS FOR, measured.
 *
 * With McWebLMMark's worklist the mark phase costs literally 0 ms and the collection is
 * the sweep, all of it: 7 collections in a 125 s window of worldgen, 43,591 ms of sweep,
 * **6,227 ms each**, 36.6% of the primary's wall time and 36.6% of every agent's (they
 * are parked for it). Upstream's `WasmLMGC.releaseSpace` is:
 *
 *     WasmHeap.getHeapImpl().walkCollectedHeapObjects(o -> {   // pass 1: free the white
 *         if (isWhite(o)) { logicalFree(o); } else { markWhite(o); }
 *     });
 *     WasmAllocation.coalesce();                               // pass 2: merge free runs
 *
 * Two linear walks of the same ~3 GiB block chain with the same `getNextBlock` stride, one
 * immediately after the other. At these sizes the walk is a cache-miss per block header
 * and nothing else, so the second pass costs very nearly what the first one does.
 *
 * WHY IT CAN BE ONE PASS
 *
 * The reason upstream needs two is ordering: `coalesceAt` merges a free block with the
 * free blocks that FOLLOW it, and during pass 1 a following white object has not been
 * freed yet, so merging as you go would miss it. Tracking the current run of free blocks
 * fixes that without changing what is merged: walk in address order, free as you go, and
 * when an allocated block ends the current run, coalesce the run that just closed — every
 * block in it is free by then, exactly as it would have been at the start of pass 2.
 *
 * The first implementation kept those upstream primitives, but that still performed a
 * free-list insertion and cache update for every white object before coalescing removed
 * almost all of those nodes again. The collector already owns the allocator lock and
 * the entire chain is stopped, so the cheaper equivalent is to clear the list once,
 * write one free header per final run, and publish only those run heads. Statistics are
 * adjusted directly for reclaimed object blocks; existing free bytes are already in
 * the upstream counters. Nothing moves, so the address-keyed monitor table and the
 * address-carving TLAB are unaffected.
 *
 * The walk itself deliberately reads the packed block word directly. The general
 * allocator header interface has to decode the same word into a StackValue-backed
 * object and invoke setter methods for every block. That is a reasonable general
 * purpose helper, but this path touches hundreds of thousands of headers while the
 * world is stopped. The low three bits and the aligned total size are the allocator's
 * stable on-memory contract; use them for the visit/stride decision and only write the
 * final run header once. The object-header color is read and whitened through the same
 * pointer directly as well, avoiding a temporary Java object conversion and heap-header
 * dispatch for every live object.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMSweep {

    /**
     * Page-0 flag: non-zero restores upstream's two-pass sweep (`?mcweb_legacy_sweep=1`).
     *
     * <p>Same reason as {@code McWebLMMark}'s: an image is nine minutes, so both arms of
     * the comparison have to live in one binary. 0..111 of page 0 is scratch.
     */
    private static final int LEGACY_FLAG_OFFSET = 100;

    private McWebLMSweep() {
    }

    private static long blocks;
    private static long freed;
    private static long runs;

    /** Header low bits: allocated, object, and the three-bit flag mask. */
    private static final long ALLOCATED_BIT = 1L;
    private static final long OBJECT_BIT = 2L;
    private static final long HEADER_FLAGS = 7L;
    private static final long OBJECT_HEADER_FLAGS = 7L;

    /** False restores the upstream two-pass sweep; the patched `releaseSpace` branches on it. */
    public static boolean fused() {
        Pointer flag = Word.pointer(LEGACY_FLAG_OFFSET);
        return flag.readInt(0) == 0;
    }

    /**
     * One pass: free the white, whiten the black, and coalesce each free run as it closes.
     *
     * <p>Allocates nothing — it runs inside the collector's {@code NoAllocationVerifier}.
     * All headers and free-list links are written through pointer-only allocator bridges.
     */
    @Uninterruptible(reason = "Runs while the collector owns the allocator lock.", calleeMustBe = false)
    public static void sweep() {
        Pointer block = MemoryLayout.getAllocatorBase();
        Pointer runStart = Word.nullPointer();
        long headerBytes = WasmAllocation.headerSize().rawValue();
        long runBytes = 0;
        long visited = 0;
        long released = 0;
        long merged = 0;

        /*
         * The old nodes point into free or dead-object payloads that this walk is about
         * to overwrite. Rebuild both views before publishing the first final run, so a
         * direct add cannot accidentally splice a new node into the old topology.
         */
        McWebLMHeapPolicy.resetFreeBlockIndex();
        WasmAllocation.resetFreeListForSweep();

        while (block.belowThan(MemoryLayout.getAllocatorTop())) {
            long packed = ((org.graalvm.word.UnsignedWord) block.readWord(0)).rawValue();
            long blockBytes = packed & ~HEADER_FLAGS;
            // The stride has to be taken before the block is freed. Freeing only clears
            // the allocated and isObject bits, so the size is stable either way, but
            // reading it once is also one fewer touch of a cold header.
            Pointer next = block.add(Word.unsigned(blockBytes));
            boolean allocated = (packed & ALLOCATED_BIT) != 0;
            boolean free = !allocated;

            if (allocated && (packed & OBJECT_BIT) != 0) {
                Pointer inner = block.add(Word.unsigned(headerBytes));
                long objectHeaderWord = ((UnsignedWord) inner.readWord(0)).rawValue();
                if ((objectHeaderWord & OBJECT_HEADER_FLAGS) == 0) {
                    WasmAllocation.freeObjectForSweep(block, blockBytes);
                    free = true;
                    released++;
                } else {
                    // Black becomes white for the next cycle, exactly as upstream's
                    // release visitor does. A gray object here would be a mark bug.
                    inner.writeWord(0, Word.unsigned(objectHeaderWord & ~OBJECT_HEADER_FLAGS));
                }
            }

            if (free) {
                if (runStart.isNull()) {
                    runStart = block;
                    runBytes = 0;
                }
                runBytes += blockBytes;
            } else if (runStart.isNonNull()) {
                WasmAllocation.writeFreeBlockForSweep(runStart, runBytes);
                WasmAllocation.addFreeBlockForSweep(runStart);
                runStart = Word.nullPointer();
                runBytes = 0;
                merged++;
            }

            visited++;
            block = next;
        }
        // A run that reaches the top of the arena closes here rather than at an
        // allocated block, and is the one most worth merging: it is the tail.
        if (runStart.isNonNull()) {
            WasmAllocation.writeFreeBlockForSweep(runStart, runBytes);
            WasmAllocation.addFreeBlockForSweep(runStart);
            merged++;
        }

        blocks += visited;
        freed += released;
        runs += merged;
    }

    /* ------------------------------------------------------------------ exports */

    private static int clamp(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.sweep.blocks", comment = "Block headers visited by the fused sweep")
    public static int blocksExport() {
        return clamp(blocks);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.sweep.freed", comment = "White objects released by the fused sweep")
    public static int freedExport() {
        return clamp(freed);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.sweep.runs", comment = "Free runs coalesced by the fused sweep")
    public static int runsExport() {
        return clamp(runs);
    }
}
