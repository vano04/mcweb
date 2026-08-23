/*
 * MC-Web builder patch: thread-local allocation buffers.
 *
 * Problem, measured (tools/wasmlm-probes/LmAllocStress, same work per thread):
 *   threads=1 0.17 s   threads=2 0.34 s   threads=3 0.46 s   threads=5 0.74 s
 * Wall time scales linearly with thread count, so total allocation throughput is
 * CONSTANT however many threads run: every Java `new` takes the one global
 * McWebLMHeapLock. Worldgen is allocation-saturated, so it cannot scale with workers,
 * and the render thread queues behind those workers -- which is why the lane currently
 * has to choose between terrain (3 workers) and frame time (1 worker).
 *
 * DESIGN -- dictated by the block format, which three earlier attempts ignored:
 *
 *   a block is HEADER_SIZE + inner, the header holds {size (total), allocated, isObject},
 *   and getNextBlock(p) = p.add(size). The heap is a LINEAR CHAIN and the sweep walks it.
 *
 * So a buffer may not sub-allocate inside a block's payload -- the walk steps over the
 * outer block entirely and never sees what is inside. A buffer must instead own a run of
 * real, chain-visible blocks:
 *
 *   1. Under the lock, doMalloc a large region. Its own header sits at the outer pointer.
 *   2. Allocate by turning the region into a sequence: rewrite the current head header to
 *      exactly cover one object, then write a fresh "tail" header immediately after it
 *      covering all remaining bytes. The chain is valid after every single step.
 *   3. The tail is always marked ALLOCATED, so the free list is never touched and no
 *      other thread will try to coalesce into the region.
 *   4. On exhaustion the tail is FREED at refill, so its bytes coalesce back into the
 *      dead region around it. Abandoning tails was measured to be fatal: every
 *      abandoned tail (up to MAX_TLAB_REQUEST bytes) permanently splits whatever dead
 *      region it sits in -- the sweep leaves allocated raw blocks alone and coalescing
 *      is forward-only -- so the free list degenerates into small fragments that can
 *      never rejoin (in-game: 3,650 MiB free across 1.16M blocks, largest 64 KiB,
 *      worldgen requests up to 24 MiB, integrated server dead on OOM).
 *
 * CONTROL BLOCK PLACEMENT -- the bug the first version had:
 *
 *   The committed TLAB parked its control words at fixed page-0 offsets 8192+.
 *   MemoryLayout.HEAP_BASE is 4096: the image heap starts there. Writing TLAB state
 *   at 8192+ therefore stamped on live image heap data (measured: image data from
 *   0x1000 up in both the probe image and the game image). That corruption is
 *   probabilistic image-heap damage -- the same class of fault that produced the
 *   in-game ExceptionInInitializerError and the probe traps.
 *
 *   The only verified-free fixed offset is below the control block's first user:
 *   every existing user (McWebLMHeapLock lock word 128, monitors 272, safepoint 280+,
 *   heap policy 300+) starts at 128, and the image heap starts at 4096, so 0..127 is
 *   the control page. One pointer slot at offset 120 holds the address of a malloc'd
 *   control block; all other TLAB words live there. This is exactly
 *   McWebLMMonitors.tableBase() (TABLE_POINTER_OFFSET 272) and thread-host.js's
 *   beacon ("allocating is the only way to be sure") applied to the TLAB.
 *
 * WHY THE FAST PATH IS SAFE WITHOUT A LOCK:
 *   - The sweep's chain walk only runs at a stop-the-world with every agent parked
 *     (McWebLMSafepoint), and this fast path takes no lock and so never polls a
 *     safepoint mid-carve. A partially written chain is never observable.
 *   - A neighbour coalescing forward under the lock reads the region head's size word.
 *     Writes here are aligned single-word stores, so it reads either the old or the new
 *     size; both address a real header (the old one addresses the block after the whole
 *     region, the new one addresses our tail). Neither can land on unheadered memory.
 *   - Blocks handed out are allocated-but-not-object until markAsObject, exactly as in
 *     the original allocateObject. McWebLMRawBlockProbe confirms the sweep leaves such
 *     blocks alone (128 of 128 intact across two collections).
 *   - The control block itself is a malloc'd block: allocated, raw (no object bit),
 *     never handed out, so the sweep leaves it alone too. It is zeroed completely
 *     before its address is published with a compare-and-swap, and the fast path reads
 *     the pointer slot with a compare-and-swap against itself (an atomic load), so the
 *     publication is ordered: no agent ever carves from a control block whose zeroing
 *     it cannot see.
 *
 * Slot 0 belongs to the primary and slots 1..15 belong to attached agents. The
 * threaded host has a pre-attach barrier: every carrier calls attachAgent before
 * the primary starts Java, and an agent does not execute a logical Java thread
 * before that attach. That makes the otherwise ambiguous zero returned for an
 * unattached carrier safe to treat as the primary slot here. Keeping a TLAB for
 * slot 0 matters: the browser thread renders and allocates continuously, and
 * sending only workers through the fast path leaves every primary allocation on
 * the global heap lock.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMTlab {

    /**
     * Fixed page-0 slot holding the address of the malloc'd control block.
     *
     * 0..127 is the only part of page 0 that is neither control-block state (from 128
     * up) nor image heap (from HEAP_BASE=4096 up). 120 is its last aligned word below
     * the first user. A zero here means the control block has not been allocated yet.
     */
    private static final int CONTROL_POINTER_OFFSET = 120;

    /** Layout of the malloc'd control block. One span covers TLAB, backoff and stats. */
    private static final int AGENTS = 16;
    private static final int BYTES_PER_AGENT = 8;
    private static final int BACKOFF_BYTES_PER_AGENT = 16;
    /** Owner-local allocation count used to sample the safepoint poll. */
    private static final int POLL_BYTES_PER_AGENT = 4;
    /** Keep a bounded rendezvous opportunity without calling into the poll on every new. */
    private static final int POLL_INTERVAL = 64;
    private static final int POLL_MASK = POLL_INTERVAL - 1;
    private static final int BACKOFF_LAST_COLLECTIONS = 0;
    /** 1 when the most recent refill returned no block; 0 otherwise. */
    private static final int BACKOFF_FAILED = 4;

    /**
     * Counters, shared by every agent, at the end of the control block.
     *
     * <p>Plain non-atomic increments. Allocation is the hottest path in the image and a
     * seq-cst read-modify-write on one shared cache line per refill is exactly the
     * cross-core traffic this class exists to remove; a lost count in a diagnostic is a
     * far cheaper error than a slower allocator. Read them as orders of magnitude.</p>
     */
    private static final int POLL_BASE = AGENTS * BYTES_PER_AGENT + AGENTS * BACKOFF_BYTES_PER_AGENT;
    private static final int STATS_BASE = POLL_BASE + AGENTS * POLL_BYTES_PER_AGENT;
    private static final int STAT_REFILLS = 0;
    private static final int STAT_REFILL_FAILURES = 4;
    private static final int STAT_BACKOFF_SKIPS = 8;
    private static final int STAT_LOCKED_FALLBACKS = 12;
    private static final int STAT_LAST_REGION_KIB = 16;
    private static final int STAT_MIN_REGION_KIB = 20;
    private static final int STAT_SHRINKS = 24;
    private static final int STATS_BYTES = 32;

    /** Opt-in request-size histogram: 1..64, then powers of two, then >16 KiB. */
    private static final int ALLOCATION_BUCKETS = 10;
    private static final int ALLOCATION_BUCKET_BYTES = ALLOCATION_BUCKETS * 4;
    private static final int ALLOCATION_BUCKET_BASE = STATS_BASE + STATS_BYTES;

    private static final int CONTROL_BLOCK_BYTES = ALLOCATION_BUCKET_BASE
                    + AGENTS * ALLOCATION_BUCKET_BYTES;

    /**
     * Region pulled per refill. The exact size is read back from the region's own
     * header (doMalloc may hand over a larger block), so this is a request floor,
     * not a region shape. Tails are freed at refill, so dead regions coalesce whole
     * again; the size is a refill cost vs. waste tradeoff, not a fragmentation bound.
     *
     * <p>This is the *preferred* size, not the only one — see {@link #refill}. It is
     * deliberately also the maximum: sweeping the region size in
     * {@code LmFragmentSurvivors} moved the pinned largest-free block to 105/107/73 KiB
     * at 8/16/64 KiB, i.e. larger regions are mildly *worse* for contiguity, so there is
     * nothing to win by asking for more.
     */
    private static final int REGION_BYTES = 64 * 1024;

    /**
     * Smallest region worth carving from, and the floor the adaptive request degrades to.
     *
     * <p>Must comfortably exceed {@link #MAX_TLAB_REQUEST} plus two block headers plus a
     * minimum tail, or a region could be taken that {@link #carve} can never use.
     * At 4 KiB a region still serves tens of small objects per heap-lock acquisition,
     * which is the whole point: the alternative is not "a bigger region", it is every
     * allocation on the global lock.</p>
     */
    private static final int MIN_REGION_BYTES = 4 * 1024;

    /** Requests above this always take the locked path. */
    private static final int MAX_TLAB_REQUEST = 2048;

    /** The allocator's packed block flags; bit 1 is the block's Java-object marker. */
    private static final long BLOCK_ALLOCATED_BIT = 1L;
    private static final long BLOCK_OBJECT_BIT = 2L;
    private static final long BLOCK_HEADER_FLAGS = 7L;

    private McWebLMTlab() {
    }

    /**
     * Read the control pointer slot on the hot path. Deliberately a PLAIN load, not an
     * atomic read-modify-write: a per-allocation seq-cst RMW on one cache line shared
     * by every agent would reintroduce exactly the cross-core contention the TLAB
     * exists to remove (the same trade McWebLMSafepoint.poll documents for the stop
     * request word).
     *
     * <p>The plain read is sound against every value in the slot's write history:
     * the slot is written exactly twice — zero at image start, then the control block
     * address, published by a single agent under the allocator lock with a
     * compare-and-swap after the block has been fully zeroed. A stale read therefore
     * observes either zero (the caller falls back to the locked path, correct) or the
     * published address (the caller proceeds). The caller's own per-agent words are
     * likewise either still the zero the publication preceded (carve bails on a zero
     * or misaligned tail and takes the locked refill, correct) or this agent's own
     * later writes (a valid carve) — no third value exists in their write history,
     * because only the owning agent writes them and only after the same publication.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long controlBlockPointer() {
        Pointer slot = Word.pointer(CONTROL_POINTER_OFFSET);
        return slot.readInt(0) & 0xffff_ffffL;
    }

    /**
     * Address of the malloc'd control block, allocating it lazily. Must run under
     * {@link McWebLMHeapLock}: the malloc below re-enters the (reentrant) lock, and
     * two agents racing the first allocation must see one block.
     *
     * Returns 0 when the block cannot be allocated; every caller treats that as
     * "no TLAB" and falls back to the locked per-object path, so a failed control
     * allocation degrades to the pre-TLAB behaviour instead of corrupting anything.
     */
    @Uninterruptible(reason = "Runs under the allocator lock", calleeMustBe = false)
    private static long controlBlock() {
        long base = controlBlockPointer();
        if (base != 0) {
            return base;
        }
        Pointer allocated = WasmAllocation.malloc(Word.unsigned(CONTROL_BLOCK_BYTES));
        if (allocated.isNull()) {
            return 0;
        }
        base = allocated.rawValue();
        // Zero the whole span: TLAB tails/sizes and backoff words all start at zero.
        Pointer block = Word.pointer(base);
        for (int off = 0; off < CONTROL_BLOCK_BYTES; off += 4) {
            block.writeInt(off, 0);
        }
        // Publish with a seq-cst store so the zeroing is visible to every agent that
        // observes this address. The slot is zero exactly once in the image's life,
        // and only one agent can be here (the caller holds the allocator lock), so
        // the compare-and-swap from zero always wins.
        Pointer slot = Word.pointer(CONTROL_POINTER_OFFSET);
        slot.compareAndSwapInt(0, 0, (int) base, LocationIdentity.ANY_LOCATION);
        return base;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer tailAt(long base, int agent) {
        return Word.pointer(base + agent * BYTES_PER_AGENT);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer sizeAt(long base, int agent) {
        return Word.pointer(base + agent * BYTES_PER_AGENT + 4);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer backoffAt(long base, int agent) {
        return Word.pointer(base + AGENTS * BYTES_PER_AGENT + agent * BACKOFF_BYTES_PER_AGENT);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer pollCountAt(long base, int agent) {
        return Word.pointer(base + POLL_BASE + agent * POLL_BYTES_PER_AGENT);
    }

    /**
     * Offer a safepoint at a bounded cadence on the TLAB fast path.
     *
     * <p>The old path called {@link McWebLMSafepoint#poll()} before every allocation,
     * including every carve from a worker-owned tail. That made the fast path a runtime
     * call in practice and defeated the point of the TLAB. The counter is written only
     * by the owning agent, so it needs no atomic operation. Refill and all locked paths
     * still poll unconditionally; a fast-path burst offers the collector a rendezvous
     * every {@link #POLL_INTERVAL} objects at most.</p>
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean samplePoll(long base, int agent) {
        Pointer count = pollCountAt(base, agent);
        int prior = count.readInt(0);
        count.writeInt(0, prior == Integer.MAX_VALUE ? 0 : prior + 1);
        return (prior & POLL_MASK) == 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void bumpStat(int stat) {
        long base = controlBlockPointer();
        if (base == 0) {
            return;
        }
        Pointer word = Word.pointer(base + STATS_BASE + stat);
        int value = word.readInt(0);
        if (value != Integer.MAX_VALUE) {
            word.writeInt(0, value + 1);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void setStat(int stat, int value) {
        long base = controlBlockPointer();
        if (base != 0) {
            Pointer word = Word.pointer(base + STATS_BASE + stat);
            word.writeInt(0, value);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int readStat(int stat) {
        long base = controlBlockPointer();
        if (base == 0) {
            return 0;
        }
        Pointer word = Word.pointer(base + STATS_BASE + stat);
        return word.readInt(0);
    }

    /** Maps an inner allocation request to a stable diagnostic bucket. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int allocationBucket(long bytes) {
        if (bytes <= 64) return 0;
        if (bytes <= 128) return 1;
        if (bytes <= 256) return 2;
        if (bytes <= 512) return 3;
        if (bytes <= 1024) return 4;
        if (bytes <= 2048) return 5;
        if (bytes <= 4096) return 6;
        if (bytes <= 8192) return 7;
        if (bytes <= 16384) return 8;
        return 9;
    }

    /** Records one request in the calling agent's private histogram row. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("diagnostic allocation buckets are local raw-memory increments")
    private static void recordAllocationBucket(long base, int agent, long bytes) {
        if (base == 0 || agent < 0 || agent >= AGENTS || bytes <= 0) {
            return;
        }
        Pointer word = Word.pointer(base + ALLOCATION_BUCKET_BASE
                        + agent * ALLOCATION_BUCKET_BYTES + allocationBucket(bytes) * 4L);
        int value = word.readInt(0);
        if (value != Integer.MAX_VALUE) {
            word.writeInt(0, value + 1);
        }
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.allocationBucket", comment = "Opt-in allocation request-size bucket")
    public static int allocationBucketExport(int agent, int bucket) {
        if (agent < 0 || agent >= AGENTS || bucket < 0 || bucket >= ALLOCATION_BUCKETS) {
            return 0;
        }
        long base = controlBlockPointer();
        if (base == 0) {
            return 0;
        }
        Pointer word = Word.pointer(base + ALLOCATION_BUCKET_BASE
                        + agent * ALLOCATION_BUCKET_BYTES + bucket * 4L);
        return word.readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.refills", comment = "TLAB regions successfully taken")
    public static int refillsExport() {
        return readStat(STAT_REFILLS);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.refillFailures", comment = "TLAB refills the allocator could not satisfy")
    public static int refillFailuresExport() {
        return readStat(STAT_REFILL_FAILURES);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.backoffSkips", comment = "Refills skipped because the last one failed")
    public static int backoffSkipsExport() {
        return readStat(STAT_BACKOFF_SKIPS);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.lockedFallbacks", comment = "TLAB-eligible allocations that took the global heap lock")
    public static int lockedFallbacksExport() {
        return readStat(STAT_LOCKED_FALLBACKS);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.lastRegionKiB", comment = "Size of the most recent TLAB region")
    public static int lastRegionKiBExport() {
        return readStat(STAT_LAST_REGION_KIB);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.minRegionKiB", comment = "Smallest TLAB region taken during this run")
    public static int minRegionKiBExport() {
        return readStat(STAT_MIN_REGION_KIB);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.tlab.shrinks", comment = "Refills that had to ask for less than the preferred region")
    public static int shrinksExport() {
        return readStat(STAT_SHRINKS);
    }

    /** Rounds up to the allocator's alignment using its own minimum-size contract. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long alignUp(long bytes) {
        long min = WasmAllocation.minInnerSize().rawValue();
        long value = bytes < min ? min : bytes;
        return (value + 7L) & ~7L;
    }

    /**
     * Fast replacement for {@code WasmAllocation.markAsObject}.
     *
     * <p>The TLAB has already carved an allocated block and the caller supplies its outer
     * header. Upstream decodes that one word into a StackValue-backed interface, reads the
     * flag, writes it back through three setters, and then verifies the whole allocator
     * when verification is enabled. That is useful as a general allocator operation but
     * is redundant for every TLAB allocation. Keep the same idempotence and object-size
     * accounting while updating the packed word directly.</p>
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void markAsObject(Pointer outer) {
        long packed = ((UnsignedWord) outer.readWord(0)).rawValue();
        if ((packed & BLOCK_OBJECT_BIT) == 0) {
            outer.writeWord(0, Word.unsigned(packed | BLOCK_OBJECT_BIT));
            WasmAllocation.addObjectSize(packed & ~BLOCK_HEADER_FLAGS);
        }
    }

    /**
     * Replacement body for {@code WasmAllocation.allocateObject}.
     *
     * <p>One straight-line call is injected: this patcher uses {@code COMPUTE_MAXS} only,
     * so an injected branch fails verification. All branching lives here.
     */
    @Uninterruptible(reason = "Replaces WasmAllocation.allocateObject", calleeMustBe = false)
    public static Pointer allocateObject(UnsignedWord size) {
        long want = size.rawValue();
        boolean histogram = false;
        int agent = -1;
        if (want > 0 && want <= MAX_TLAB_REQUEST) {
            agent = McWebLMHeapLock.agentIdForTlab();
        }
        if (want > 0 && want <= MAX_TLAB_REQUEST) {
            // agentCount() is tiny and inlines to one page-0 load. Keep the no-agent
            // path out of agentIdOrPrimary(): that method must read the Wasm stack
            // marker, but an inline image has no carrier identity to discover. This
            // branch is on every eligible allocation, so leaving it behind the call
            // made the nominally local TLAB pay a runtime-call frame for every object.
            // Slot 0 is reserved for the primary. See the class invariant above:
            // carriers are attached before primary Java starts, so no unattached
            // agent can race the primary's TLAB.
            if (agent >= 0 && agent < AGENTS) {
                long base = controlBlockPointer();
                boolean recorded = false;
                if (base != 0) {
                    histogram = McWebLMTiming.allocationHistogramEnabled();
                    if (histogram) {
                        recordAllocationBucket(base, agent, want);
                        recorded = true;
                    }
                    /*
                     * Do not poll between carve's two header writes: the collector must
                     * never walk a half-split chain. Sampling before the carve keeps the
                     * same safety property while removing the call from 63/64 fast-path
                     * allocations.
                     */
                    if (samplePoll(base, agent)) {
                        McWebLMSafepoint.poll();
                    }
                    Pointer fast = carve(base, agent, alignUp(want));
                    if (fast.isNonNull()) {
                        WasmAllocation.markAsObject(WasmAllocation.getOuterPointer(fast));
                        return fast;
                    }
                }
                McWebLMHeapLock.lock();
                long refillStarted = McWebLMTiming.start();
                try {
                    refill(agent);
                } finally {
                    McWebLMTiming.account(McWebLMTiming.CAT_REFILL, refillStarted);
                    McWebLMHeapLock.unlock();
                }
                base = controlBlockPointer();
                if (base != 0) {
                    if (!histogram) {
                        histogram = McWebLMTiming.allocationHistogramEnabled();
                    }
                    if (histogram && !recorded) {
                        recordAllocationBucket(base, agent, want);
                    }
                    Pointer fast = carve(base, agent, alignUp(want));
                    if (fast.isNonNull()) {
                        WasmAllocation.markAsObject(WasmAllocation.getOuterPointer(fast));
                        return fast;
                    }
                }
                // A request that FITS the TLAB and still ends on the global lock is the
                // failure mode worth counting: it means the buffer could not be refilled,
                // so this allocation - and every one after it until the next refill
                // succeeds - serializes every thread on McWebLMHeapLock.
                bumpStat(STAT_LOCKED_FALLBACKS);
            }
        }
        if (want > MAX_TLAB_REQUEST && McWebLMTiming.allocationHistogramEnabled()) {
            agent = McWebLMHeapLock.agentIdForTlab();
            recordAllocationBucket(controlBlockPointer(), agent, want);
        }
        McWebLMHeapLock.lock();
        long allocStarted = McWebLMTiming.start();
        try {
            return WasmAllocation.allocateObjectUnlocked(size);
        } finally {
            McWebLMTiming.account(McWebLMTiming.CAT_LOCKED_ALLOC, allocStarted);
            McWebLMHeapLock.unlock();
        }
    }

    /**
     * Splits one object off the front of this agent's tail block, leaving a valid tail.
     * Lock-free: only this agent reads or writes its own control words, and only this
     * agent addresses the bytes inside its tail.
     */
    @Uninterruptible(reason = "Splits a block this agent exclusively owns; the WasmAllocation"
                    + " header helpers it calls are themselves allocation-free and only touch"
                    + " bytes inside that region, so no safepoint or allocation can occur here",
                    calleeMustBe = false, mayBeInlined = true)
    @AlwaysInline("the TLAB carve is the common allocation path")
    private static Pointer carve(long base, int agent, long innerBytes) {
        long header = WasmAllocation.headerSize().rawValue();
        long tail = tailAt(base, agent).readInt(0) & 0xffff_ffffL;
        long tailSize = sizeAt(base, agent).readInt(0) & 0xffff_ffffL;
        /*
         * Bail to the locked path on anything unexpected rather than writing a header at
         * a bad address: writeBlockHeader's first act is guarantee(isAligned(pointer)),
         * an unrecoverable messageless VM error.
         */
        if (tail == 0 || (tail & 7L) != 0 || (tailSize & 7L) != 0) {
            return Word.nullPointer();
        }
        long need = header + innerBytes;
        // Leave room for a tail that can still hold the smallest legal block.
        long minTail = header + WasmAllocation.minInnerSize().rawValue();
        if (tailSize < need + minTail) {
            return Word.nullPointer();
        }
        Pointer outer = Word.pointer(tail);
        // Write the remainder header FIRST, so the chain never points at unheadered
        // bytes: until the head shrinks, this header is simply inside the head block.
        Pointer nextOuter = Word.pointer(tail + need);
        nextOuter.writeWord(0, Word.unsigned((tailSize - need) | BLOCK_ALLOCATED_BIT));
        // Now shrink the head to exactly this object. A concurrent coalescer reads either
        // size; both address a real header.
        outer.writeWord(0, Word.unsigned(need | BLOCK_ALLOCATED_BIT));
        tailAt(base, agent).writeInt(0, (int) (tail + need));
        sizeAt(base, agent).writeInt(0, (int) (tailSize - need));
        return WasmAllocation.getInnerPointer(outer);
    }

    /**
     * Takes a fresh region under the caller's lock.
     *
     * <p>The exhausted tail is FREED here, not abandoned (see the class javadoc for
     * the measured cost of abandoning). The free list stores its next/prev pointers
     * INSIDE a freed block's payload, so the tail must be at least
     * MIN_FREE_BLOCK_SIZE (= header + two pointer words = header + minInnerSize) or
     * the link scribbles past the block's own end. Carve's guard guarantees exactly
     * that minimum, but a region exhausted by allocation paths that bypass carve
     * (there are none today) could leave less, so the check stays. A tail below the
     * minimum is leaked instead -- the same bounded-by-one-allocation leak the old
     * code always had, now per exhaustion instead of per refill.
     */
    @Uninterruptible(reason = "Runs under the allocator lock", calleeMustBe = false)
    private static void refill(int agent) {
        long base = controlBlock();
        if (base == 0) {
            return;
        }
        // First refill on any thread is the earliest point that both holds the allocator
        // lock and is guaranteed to run, so it is where the timing block gets made.
        McWebLMTiming.ensureBlock();
        Pointer backoff = backoffAt(base, agent);
        Pointer stoppedWord = Word.pointer(284);
        Pointer uncontendedWord = Word.pointer(288);
        int collections = stoppedWord.readInt(0) + uncontendedWord.readInt(0);
        /*
         * Failed-refill backoff. A miss here means the free list holds no block the
         * request fits in; the fast path would otherwise retry the full locked doMalloc
         * machinery on EVERY small allocation. One attempt per completed collection is
         * enough: only the primary can collect, and a collection is the only event that
         * can turn unusable fragments into a fitting block. A refill that SUCCEEDED must
         * always be retried once its region exhausts, so the skip keys on the failed
         * flag rather than on "attempted at this epoch".
         */
        if (backoff.readInt(BACKOFF_FAILED) == 1
                        && backoff.readInt(BACKOFF_LAST_COLLECTIONS) == collections) {
            bumpStat(STAT_BACKOFF_SKIPS);
            return;
        }
        backoff.writeInt(BACKOFF_FAILED, 0);
        // Return the exhausted tail to the free list so its bytes coalesce with the
        // dead region around it. Only this agent carves its own tail, and it is here
        // in refill, so the free races nothing; the lock is reentrant, so the doFree
        // inside the free machinery stays on this agent. The control words are zeroed
        // BEFORE the free so no later carve can address the freed bytes.
        Pointer tailSlot = tailAt(base, agent);
        Pointer sizeSlot = sizeAt(base, agent);
        long oldTail = tailSlot.readInt(0) & 0xffff_ffffL;
        long oldSize = sizeSlot.readInt(0) & 0xffff_ffffL;
        tailSlot.writeInt(0, 0);
        sizeSlot.writeInt(0, 0);
        long header = WasmAllocation.headerSize().rawValue();
        long minFree = header + WasmAllocation.minInnerSize().rawValue();
        if (oldTail != 0 && (oldTail & 7L) == 0 && (oldSize & 7L) == 0
                        && oldSize >= minFree) {
            WasmAllocation.doFree(Word.pointer(oldTail + header));
        }
        /*
         * Size the region to what the arena can actually produce.
         *
         * A fixed REGION_BYTES request is what turns fragmentation into a *throughput*
         * collapse rather than merely a large-allocation problem. In-game the arena
         * settles with 87% free and a largest contiguous run of 93-135 KiB, i.e. the
         * same order as the 64 KiB region; the moment a refill misses, the backoff above
         * switches this whole buffer off until the next COMPLETED collection, of which a
         * four-agent run manages about 54. Every small allocation in between then takes
         * the global heap lock, which is the pre-TLAB regime whose measured property is
         * that total allocation throughput does not increase with thread count at all.
         *
         * Measured in tools/wasmlm-probes/LmTlabStarvation: a single refill failure was
         * followed by 1,500-2,400 TLAB-eligible allocations forced onto the lock.
         *
         * So ask the allocator's O(1) largest-free proof first and request the smaller of
         * the preferred region and what the proof can vouch for. The proof is a proof of
         * fit and never of miss, so a small answer only ever makes this ask for less --
         * never wrongly refuse. A 4 KiB floor region still serves tens of objects per
         * heap-lock acquisition; below that floor the alternative is not a bigger region,
         * it is no buffer at all.
         *
         * Take the proof's exact byte count, not a power-of-two ladder of fit tests --
         * see McWebLMHeapPolicy.largestFreeBytesHint for the measurement that settled it.
         */
        long headerBytes = WasmAllocation.headerSize().rawValue();
        long hint = McWebLMHeapPolicy.largestFreeBytesHint();
        long request = REGION_BYTES;
        if (hint > headerBytes) {
            long usable = (hint - headerBytes) & ~7L;
            if (usable < request) {
                request = usable;
            }
        }
        if (request < MIN_REGION_BYTES) {
            request = MIN_REGION_BYTES;
        }
        if (request < REGION_BYTES) {
            bumpStat(STAT_SHRINKS);
        }
        Pointer block = WasmAllocation.doMalloc(Word.unsigned(request));
        if (block.isNull() && request > MIN_REGION_BYTES) {
            // The proof under-reports whenever the reservoir's candidates have been
            // allocated away (see McWebLMHeapPolicy.deepSearch), so a miss above the floor is not
            // evidence that the heap is out. Spend exactly one retry at the floor before
            // accepting that and switching the buffer off.
            bumpStat(STAT_SHRINKS);
            request = MIN_REGION_BYTES;
            block = WasmAllocation.doMalloc(Word.unsigned(request));
        }
        if (block.isNull()) {
            bumpStat(STAT_REFILL_FAILURES);
            backoff.writeInt(BACKOFF_FAILED, 1);
            backoff.writeInt(BACKOFF_LAST_COLLECTIONS, collections);
            return;
        }
        bumpStat(STAT_REFILLS);
        Pointer outer = WasmAllocation.getOuterPointer(block);
        /*
         * The region's true size, read back from its own header -- NOT
         * headerSize + REGION_BYTES.
         *
         * doMalloc can hand back a block LARGER than asked for: allocateInBlock only
         * splits when the remainder is at least MIN_FREE_BLOCK_SIZE, and otherwise gives
         * away the whole block. Recording the requested size instead would leave the
         * surplus bytes past our final tail header with no header of their own, and the
         * sweep's chain walk would step straight into them.
         *
         * The low 3 bits are the allocated/isObject flags (MASK_HEADER_BITS == 7); the
         * size is 8-aligned, so masking them off recovers it.
         */
        long total = outer.readWord(0).rawValue() & ~7L;
        tailSlot.writeInt(0, (int) outer.rawValue());
        sizeSlot.writeInt(0, (int) total);
        int regionKiB = (int) (total >>> 10);
        setStat(STAT_LAST_REGION_KIB, regionKiB);
        int smallest = readStat(STAT_MIN_REGION_KIB);
        if (smallest == 0 || regionKiB < smallest) {
            setStat(STAT_MIN_REGION_KIB, regionKiB);
        }
    }
}
