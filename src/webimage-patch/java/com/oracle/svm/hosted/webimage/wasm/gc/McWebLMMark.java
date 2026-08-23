/*
 * MC-Web builder patch: make the WasmLM mark phase O(live) instead of O(heap x passes).
 *
 * WHAT UPSTREAM DOES, and why it costs seconds per collection.
 *
 * `WasmLMGC.blackenCollectedHeap` is, verbatim:
 *
 *     while (grayToBlackObjectVisitor.hasGray()) {
 *         WasmHeap.getHeapImpl().walkCollectedHeapObjects(grayToBlackObjectVisitor);
 *     }
 *
 * and the marker's worklist is a `SizedObjectStack` of **128 entries**. When a reference
 * is discovered and the stack is full, `GrayToBlackObjectVisitor$GrayHeapVisitor
 * .visitObjectReference` marks the target gray in its header and simply drops it:
 *
 *     if (promoteToGray(child) && worklist.hasSpace()) { worklist.push(child); }
 *
 * The only thing that ever finds a dropped gray object again is *another full walk of the
 * whole block chain*. So marking is O(heap x number of passes), where the pass count is
 * set by how often the frontier exceeds 128 -- which, on a Minecraft heap, is essentially
 * always. Measured in-game before this class existed: 14 collections in 125 s averaging
 * **7.1 seconds each** over a ~2.9 GiB arena, 44% of the primary's wall time and 44% of
 * every agent's (they are all parked for it). See docs/STATUS.md.
 *
 * This is also the concrete answer to "why is V8's WasmGC collector so much faster": it
 * is not that V8 emits better code for the same algorithm. V8 marks with a segmented,
 * growable worklist and drains it to a fixed point, so its mark cost is proportional to
 * the live set, once -- plus a generational nursery so most collections never look at the
 * old heap at all. Upstream WasmLMGC has neither.
 *
 * WHAT THIS DOES
 *
 * The nursery needs a write barrier the WasmLM backend does not have (`WasmLMHeapFeature`
 * installs `NoBarrierSet`), so that half is out of reach for now. The worklist half is
 * not: give the marker a stack large enough to hold the real frontier and drain it
 * explicitly after root marking, and the mark phase stops walking the heap at all --
 * `hasGray()` is already 0 by the time the upstream loop is reached, and the only
 * remaining full-chain pass in a collection is the sweep in `releaseSpace`.
 *
 * Three cooperating pieces, all installed by `McWebImagePatcher.patchMarkWorklist`:
 *
 *   1. `SizedObjectStack`'s `hasSpace`/`push`/`pop` are routed through this class, which
 *      keeps the real worklist in raw linear memory sized from the arena ({@link
 *      #ensureStack}, allocated between collections, never inside one -- the collection
 *      path runs inside a `NoAllocationVerifier`). With no off-heap worklist it falls
 *      back to the object's own 128-entry array, i.e. to upstream.
 *   2. That indirection is also what makes the legacy bound reproducible at runtime (see
 *      {@link #LEGACY_FLAG_OFFSET}), so both arms of the comparison live in one image
 *      rather than costing two nine-minute builds.
 *   3. `WasmLMGC.blackenCollectedHeap` calls {@link #drainRoots} before the upstream
 *      loop, so the roots pushed by `blackenRoots` are followed transitively without a
 *      heap walk. The upstream loop stays as the overflow fallback: if the frontier ever
 *      does exceed the stack, the gray bits are still set and the old algorithm still
 *      finds them, so this can only ever be faster, never wrong.
 *
 * The counters here are plain statics on purpose: only the primary ever collects (agents
 * are parked at a safepoint for the duration), WasmLM statics are private to each Wasm
 * instance, and a static read/write allocates nothing.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMMark {

    /**
     * Upper bound on the worklist, in objects. One million entries is 4 MiB on wasm32.
     *
     * <p>The frontier only has to hold the gray set at its widest, not the live set: an
     * object is pushed when it goes white->gray and popped when it is scanned, so this
     * bounds breadth. Measured in a four-agent Minecraft run, the real peak is **410,167**
     * — against upstream's bound of 128.
     */
    private static final int MAX_ENTRIES = 1024 * 1024;

    /**
     * Floor, and the reason the worklist is not simply a fixed array.
     *
     * <p>A fixed 1M-entry array was tried first, as a build-time image-heap `Word[]` in
     * `GrayToBlackObjectVisitor`. It works in the game and **breaks the probes**: 4 MiB of
     * extra image heap under a 64 MiB ceiling pushed `WasmAllocation.initialize`'s
     * halving reserve down a step, and `LmAllocatorFragmentation` went from a 57 MiB arena
     * with zero growth failures to a 34 MiB arena with 70. The worklist therefore scales
     * with the heap — one entry per 4 KiB of arena, i.e. 0.1% of it — and lives in raw
     * linear memory allocated *before* a collection, never inside one.
     */
    private static final int MIN_ENTRIES = 1024;

    /** Arena bytes per worklist entry. At 4 bytes an entry this is 0.1% of the heap. */
    private static final int HEAP_BYTES_PER_ENTRY = 4096;

    /** Upstream's capacity, used when the legacy flag is set. */
    private static final int LEGACY_ENTRIES = 128;

    /** Page-0 slots: base address of the off-heap worklist, and its capacity in entries. */
    private static final int STACK_POINTER_OFFSET = 96;
    private static final int STACK_CAPACITY_OFFSET = 92;

    /** wasm32: an untracked object pointer is four bytes. */
    private static final int ENTRY_BYTES = 4;

    /**
     * Page-0 flag: non-zero restores upstream's 128-entry worklist (`?mcweb_legacy_mark=1`).
     *
     * <p>An image is nine minutes, so the before/after arm has to live in the same
     * binary. 0..111 of page 0 is scratch; the lowest fixed user is McWebLMTiming's
     * control pointer at 112.
     */
    private static final int LEGACY_FLAG_OFFSET = 104;

    private McWebLMMark() {
    }

    /* ---------------------------------------------------------------- statistics */

    /** No initializers anywhere in this class: default values need no class init. */
    private static long collections;
    private static long heapWalks;
    private static long markNanos;
    private static long sweepNanos;
    private static long drainSeeds;
    private static long overflows;
    private static long peakDepth;
    private static boolean ensuring;
    private static long stackAllocations;
    private static long markStartedAt;
    private static long sweepStartedAt;

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean legacy() {
        Pointer flag = Word.pointer(LEGACY_FLAG_OFFSET);
        return flag.readInt(0) != 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long stackBase() {
        Pointer slot = Word.pointer(STACK_POINTER_OFFSET);
        return slot.readInt(0) & 0xffff_ffffL;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int stackCapacity() {
        Pointer slot = Word.pointer(STACK_CAPACITY_OFFSET);
        return slot.readInt(0);
    }

    /** Base of the worklist to use, or 0 for upstream's 128-entry in-object array. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long activeBase() {
        return legacy() ? 0L : stackBase();
    }

    /**
     * Sizes and allocates the worklist. Called from the collect wrapper **before** the
     * collection begins, so it is outside the collector's {@code NoAllocationVerifier}
     * and may take the (reentrant) heap lock.
     *
     * <p>Re-sizes when the arena has grown. Failure is not an error: with no off-heap
     * worklist the marker falls back to upstream's array and upstream's rescan, which is
     * slower but correct.
     */
    public static void ensureStack() {
        if (legacy() || ensuring) {
            return;
        }
        ensuring = true;
        try {
            allocateStack();
        } finally {
            ensuring = false;
        }
    }

    /**
     * Never {@code WasmAllocation.malloc} here.
     *
     * <p>`malloc` runs `maybeCollectOnAllocation`, so a worklist allocation that does not
     * fit immediately collects, and the collection calls this again: measured as
     * `RangeError: Maximum call stack size exceeded` in `LmAllocatorFragmentation`, with
     * `ensureStack -> malloc -> collect -> ensureStack` repeating in the trace. `doMalloc`
     * is the same allocation without the collection trigger — the same entry point
     * `McWebLMTlab.refill` uses, and for the same reason. The `ensuring` guard above is
     * belt and braces on top of that.
     */
    private static void allocateStack() {
        long want = WasmAllocation.getHeapSize() / HEAP_BYTES_PER_ENTRY;
        if (want < MIN_ENTRIES) {
            want = MIN_ENTRIES;
        } else if (want > MAX_ENTRIES) {
            want = MAX_ENTRIES;
        }
        if (stackCapacity() >= want && stackBase() != 0) {
            return;
        }
        Pointer allocated = WasmAllocation.doMalloc(Word.unsigned(want * ENTRY_BYTES));
        if (allocated.isNull()) {
            return;
        }
        long previous = stackBase();
        stackAllocations++;
        Pointer basePublish = Word.pointer(STACK_POINTER_OFFSET);
        basePublish.writeInt(0, (int) allocated.rawValue());
        Pointer capacityPublish = Word.pointer(STACK_CAPACITY_OFFSET);
        capacityPublish.writeInt(0, (int) want);
        if (previous != 0) {
            Pointer stale = Word.pointer(previous);
            WasmAllocation.doFree(stale);
        }
    }

    /**
     * Replaces `SizedObjectStack.hasSpace`'s `currentSize < maxSize`.
     *
     * <p>Also where overflow is counted: a false answer here is exactly the event that
     * costs upstream a whole extra pass over the block chain.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean hasSpace(int currentSize, int maxSize) {
        int capacity = legacy() ? LEGACY_ENTRIES : (stackBase() != 0 ? stackCapacity() : maxSize);
        if (currentSize > peakDepth) {
            peakDepth = currentSize;
        }
        if (currentSize < capacity) {
            return true;
        }
        overflows++;
        return false;
    }

    /**
     * Replaces `SizedObjectStack.push`.
     *
     * <p>Which store is used cannot change inside a collection: the legacy flag is set
     * before Java starts and {@link #ensureStack} only runs between collections, so the
     * stack a push writes is the stack the matching pop reads.
     */
    public static void push(SizedObjectStack self, Object object) {
        int index = self.currentSize;
        long base = activeBase();
        if (base != 0) {
            Pointer slot = Word.pointer(base + (long) index * ENTRY_BYTES);
            slot.writeInt(0, (int) Word.objectToUntrackedPointer(object).rawValue());
        } else {
            self.stack[index] = Word.objectToUntrackedPointer(object);
        }
        self.currentSize = index + 1;
    }

    /** Replaces `SizedObjectStack.pop`. */
    public static Object pop(SizedObjectStack self) {
        int index = self.currentSize - 1;
        self.currentSize = index;
        long base = activeBase();
        if (base != 0) {
            Pointer slot = Word.pointer(base + (long) index * ENTRY_BYTES);
            Pointer entry = Word.pointer(slot.readInt(0) & 0xffff_ffffL);
            return entry.toObjectNonNull();
        }
        return self.stack[index].toObjectNonNull();
    }

    /**
     * Follows everything root marking left on the worklist, without walking the heap.
     *
     * <p>`visitObject` pushes its argument and then drains the worklist transitively, so
     * one call per remaining seed empties the stack; the loop is here only because
     * `visitObject` returns early for an object that is no longer gray.
     */
    public static void drainRoots(GrayToBlackObjectVisitor visitor) {
        SizedObjectStack worklist = visitor.worklist;
        while (!worklist.isEmpty()) {
            Object seed = worklist.pop();
            drainSeeds++;
            visitor.visitObject(seed);
        }
    }

    /* ------------------------------------------------------------------- phases */

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void markBegin() {
        collections++;
        markStartedAt = McWebLMClock.sharedNanos();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void markEnd() {
        markNanos += elapsed(markStartedAt);
        markStartedAt = 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void sweepBegin() {
        sweepStartedAt = McWebLMClock.sharedNanos();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void sweepEnd() {
        sweepNanos += elapsed(sweepStartedAt);
        sweepStartedAt = 0;
    }

    /** Counts every full walk of the block chain, from either the mark loop or the sweep. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void heapWalk() {
        heapWalks++;
    }

    /**
     * Zero when the shared clock has no publisher yet, in which case the sample is
     * dropped rather than recorded as an enormous interval — the same rule as
     * {@link McWebLMTiming#account}.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long elapsed(long startedAt) {
        if (startedAt == 0) {
            return 0;
        }
        long now = McWebLMClock.sharedNanos();
        if (now == 0 || now < startedAt) {
            return 0;
        }
        return now - startedAt;
    }

    /* ------------------------------------------------------------------ exports */

    private static int clamp(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.collections", comment = "Collections whose mark phase was observed")
    public static int collectionsExport() {
        return clamp(collections);
    }

    /**
     * Full walks of the block chain. The sweep does exactly one per collection, so
     * {@code heapWalks - collections} is the number of *mark* passes — the quantity this
     * whole class exists to drive to zero.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.heapWalks", comment = "Full block-chain walks (mark passes + sweeps)")
    public static int heapWalksExport() {
        return clamp(heapWalks);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.markMs", comment = "Milliseconds in the mark phase")
    public static int markMsExport() {
        return clamp(markNanos / 1_000_000L);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.sweepMs", comment = "Milliseconds in the sweep phase")
    public static int sweepMsExport() {
        return clamp(sweepNanos / 1_000_000L);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.drainSeeds", comment = "Worklist seeds drained without a heap walk")
    public static int drainSeedsExport() {
        return clamp(drainSeeds);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.overflows", comment = "Times the marking worklist was full")
    public static int overflowsExport() {
        return clamp(overflows);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.peakDepth", comment = "Deepest observed marking worklist")
    public static int peakDepthExport() {
        return clamp(peakDepth);
    }

    /**
     * How many times the worklist itself was allocated from the arena.
     *
     * <p>Each one is an ordinary allocation and shows up in the allocator's own counters,
     * so a gate on those (`tools/wasmlm-probes/fragmentation-harness.mjs`) has to discount
     * it rather than read it as a regression.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.mark.stackAllocations", comment = "Arena allocations made for the marking worklist")
    public static int stackAllocationsExport() {
        return clamp(stackAllocations);
    }
}
