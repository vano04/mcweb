/*
 * MC-Web builder patch: allocation policy for a heap several threads share.
 *
 * Upstream `WasmAllocation.doMalloc` is: first-fit the free list, and if that finds
 * nothing, collect the whole heap, first-fit again, then grow. That is right when a miss
 * means "the heap is full". It is catastrophically wrong once the first-fit search is
 * *bounded* - which it has to be, because the search is linear in the number of GC
 * fragments and an unbounded walk monopolises the browser thread - because then a miss
 * usually means only "the first N free blocks were too small", and paying a whole-heap
 * mark and sweep for that, per allocation, is unbounded work for nothing.
 *
 * With agents it is worse still. A collection needs every agent inside Java to reach a
 * safepoint poll, and an agent in a long allocation-free loop (inflating a resource, say)
 * reaches none, so the collector spins out its budget and skips. Measured on a two-agent
 * Minecraft boot before this class existed: 397 skipped collections against 1 that ran,
 * each skip having burned 20,000,000 spin iterations.
 *
 * So separate the two cases the upstream policy conflates:
 *
 *   - the bounded search missed, and the region can still grow -> grow, in chunks large
 *     enough that the remainder lands at the head of the free list and satisfies the next
 *     thousands of allocations on their first probe;
 *   - the heap is genuinely full, or the live set has grown past the collection threshold
 *     -> collect, then search once with the bound lifted so every reclaimed block is
 *     reachable, and only then grow.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.heap.RestrictHeapAccess;
import com.oracle.svm.core.heap.RestrictHeapAccess.Access;
import com.oracle.svm.core.util.UnsignedUtils;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMHeapPolicy {

    /**
     * Bytes the region grows by when the bounded search misses. Large enough that the
     * remainder becomes a head block thousands of allocations can be split out of, so
     * growth is rare; small enough that a burst of misses cannot walk the region to the
     * memory ceiling in a few steps.
     */
    private static final long GROWTH_CHUNK_BYTES = 64L * 1024L * 1024L;

    /**
     * Occupancy above which reclaiming dead objects is preferable to another grow.
     *
     * <p>An absolute live-byte threshold was the wrong signal for a non-moving heap.
     * The browser run had only 360-460 MiB live in a 1,561 MiB allocator region, but
     * more than a gigabyte of small free fragments. Once the old lifetime growth
     * budget expired, every larger temporary allocation collected that sparse heap and
     * still needed fresh contiguous space. Those collections were the repeatable
     * 100-ms menu stalls. Occupancy distinguishes a genuinely crowded arena from that
     * fragmentation case.</p>
     */
    private static final int COLLECT_ABOVE_PERCENT = 75;

    /**
     * Chunked region growth permitted during one image run, in MiB.
     *
     * <p>The live-set threshold alone is not a bound on the region. A threaded run
     * measured a 659 MiB live set - comfortably below the occupancy threshold -
     * against a region already at 2004 MiB of the
     * 2048 MiB ceiling: every miss grew, nothing ever collected, and the arena walked
     * to the wall. That is the state in which skipping a collection stops being safe.
     * The whole justification for {@code McWebLMSafepoint}'s small park budget is that
     * a skipped collection simply grows instead, and at the ceiling there is nothing
     * left to grow into: the allocator then does a full free-list walk per allocation
     * with no reclamation, and the browser thread stalls for minutes.
     *
     * <p>So bound the growth as well as the live set. Once this much has been added,
     * a miss collects first and only grows the exact unsatisfied request if collecting
     * did not produce a block. Do not reset this counter after GC: the Wasm memory
     * commitment cannot shrink, so resetting a "since collection" budget allowed four
     * more 64 MiB chunks after every successful collection and walked a 597 MiB live
     * set to a 3,955 MiB commitment in the measured browser run.
     */
    /*
     * The size-class repair makes a post-budget deep walk productive: the rebuilt
     * browser reached Accessibility with 1,216 MiB of chunk growth, then every later
     * menu miss found reusable space (1,305/1,307 deep searches hit).  Letting chunked
     * growth continue to 3,072 MiB merely committed another 1.8 GiB while the live set
     * stayed near 400 MiB, and the first Worker instantiation pushed the renderer over
     * its process limit.  One final 64 MiB chunk leaves bootstrap variance without
     * allowing an idle menu to walk to the wasm32 ceiling.
     */
    /*
     * Raised 1280 -> 2048 on 2026-08-03. Reaching this budget does not merely stop
     * growth, it switches `doMalloc` into its most expensive mode for *every*
     * subsequent miss: `pressureFallback` and `collectFirst` both become permanently
     * true, so the cheap 64 MiB chunked grow is never taken again, each miss pays a
     * full free-list walk, and each one then attempts a stop-the-world that is being
     * skipped ~97% of the time. Exact-size growth is all that remains, which leaves no
     * remainder headroom and fragments further, making the next miss more likely.
     *
     * Measured in that state during a four-agent world load: `gcStopped 55` against
     * `gcSkipped 1830`, `allocationFullScans 2036`, and ~480 MiB live in a 2.1 GiB heap
     * with 1.67 GiB free but `allocationLargestFreeKiB` repeatedly 0-44 against a 24 MiB
     * request. World load took 356 s and the client stalled at 25 of 329 chunks.
     *
     * 2048 keeps ~1 GiB of the wasm32 4 GiB ceiling in reserve: the historical renderer
     * process crash was at 3,699 MiB committed, and 1 GiB reserve + 2048 MiB growth
     * lands near 3.1 GiB. This is headroom to stop the spiral while the rendezvous is
     * diagnosed (see McWebLMSafepoint's STAT_SKIP_BLOCKERS_*), not a fix for it —
     * a collector that ran would keep the heap near its ~480 MiB live set and never
     * approach either number.
     */
    /*
     * Raised again 2048 -> 3072 once the collector actually worked.
     *
     * The cap's whole justification was that a skipped collection is safe because the
     * allocator grows instead — which made a *low* cap essential while collections were
     * being skipped ~92% of the time, because runaway growth was the only alternative to
     * reclamation. With the rendezvous repaired (`McWebLMHeapLock.lock` polls on the
     * uncontended path, `McWebLMSafepoint.park` actually waits) a four-agent world load
     * measures `gcSkipped 0` against 71 completed collections, so growth is now bounded
     * by reclamation rather than by this constant.
     *
     * What the same run then hit is the *other* failure at the cap: `noProgress 16` —
     * collections running, and the following deep search still finding no fit for the
     * 24 MiB (`allocationMaxRequestKiB`) terrain buffers, because a non-moving collector
     * leaves the arena fragmented. That is a contiguity problem, and the only cure
     * available to a non-moving heap is fresh address space.
     *
     * 3072 against the 512 MiB initial reserve lands near 3,584 MiB of the wasm32
     * 4 GiB ceiling. That is close to the 3,699 MiB at which the renderer process was
     * historically lost, so this is the last raise available: beyond it the fix has to be
     * fewer/smaller large allocations, not more room.
     */
    /*
     * Lowering this was TRIED AND REFUTED, 2026-08-04. Do not retry it without new
     * information.
     *
     * The first honest measurement of the live set (511 MiB in a 3,671 MiB arena, 13%
     * occupancy) made 3072 look absurdly generous, and a full-heap mark-sweep over 3.8
     * GiB is exactly what costs the frames. So 3072 -> 1024 (arena capped near 1,536
     * MiB, still 3x the live set). Measured at 4 agents against the 3072 baseline:
     *
     *              budget 3072      budget 1024
     *   world entry      128 s        601 s
     *   chunks           129          11
     *   gcStopped        54           501
     *   growAttempts     539          4029
     *   fullScans        297          4407
     *   memoryMiB        3842         2173
     *   frameMs p50      33 ms        1267 ms
     *
     * The arena size is load-bearing: growth is what ABSORBS the fragmentation. Capping
     * it does not reduce the fragmentation, it converts it into constant collection and
     * full free-list walks. `LmFragmentSurvivors` explains why -- the arena is pinned by
     * uniformly interleaved lifetimes, so reclamation cannot produce contiguity no
     * matter how often it runs.
     *
     * This is the same lever, from the other side, as correcting Statistics.objectSize
     * (eee13e4, reverted in 4fb4d1a): both make the port "collect more, grow less", and
     * both are large regressions. The remaining routes are a compacting collector
     * (blocked: McWebLMMonitors keys ownership on object addresses and the TLAB carves
     * by address) or segregating large allocations from the small-object arena.
     */
    private static final int GROWTH_BUDGET_MIB = 3072;

    /**
     * Page-0 override for {@link #GROWTH_BUDGET_MIB} (`?mcweb_growth_budget=N`, MiB).
     * Zero keeps the default.
     *
     * <p>The refutation above was measured while **worldgen ran on this heap**, and its
     * 24 MiB contiguous requests were what needed the growth budget. Worldgen now runs
     * in a private server Worker, so that premise no longer holds --
     * docs/OFF-HEAP-GPU-BUFFERS.md explicitly says to re-test this lever afterwards
     * because "their whole justification changes". This is that re-test.</p>
     *
     * <p>It is a knob rather than a constant because sweeping it is the measurement and
     * a rebuild of this image costs about eight minutes; baking a value in makes a
     * three-point sweep three builds.</p>
     */
    private static final int GROWTH_BUDGET_OFFSET = 108;

    /** Requests this large use exact growth once chunked growth is under pressure. */
    private static final int DIRECT_GROW_REQUEST_KIB = 1024;

    /** Chunked region bytes added during this image run, in MiB. */
    private static final int GROWN_MIB_OFFSET = 316;

    /**
     * MiB of chunked growth added since the last collection that actually ran.
     *
     * <p>Retained as telemetry only. It was briefly used to force a collection every
     * {@code 384} MiB of growth, on the theory that sizing the heap to the live set
     * would cut the 1,836 ms `frameMs` p99 caused by sweeping a 3.7 GiB heap. **That
     * made things worse and was reverted.** Two reasons, both worth recording:
     *
     * <ul>
     * <li>Wasm linear memory never shrinks, so forcing collections cannot give committed
     * memory back — the run still reached 3,775 MiB, identical to before.</li>
     * <li>Most growth happens during boot and the resource reload, where the live set is
     * genuinely growing. Collecting every 384 MiB there is pure overhead: the collection
     * frees almost nothing and growth resumes immediately. Measured cost: the
     * Create-New-World click moved from 41.2 s to 104.7 s.</li>
     * </ul>
     *
     * <p>{@link #COLLECT_ABOVE_PERCENT} is the principled version of the same idea — it
     * collects on *occupancy*, so it stays quiet while the live set is legitimately
     * growing. The p99 stalls remain, and the real cure for them is an incremental or
     * generational collector rather than a heap-sizing constant.
     */
    private static final int GROWN_SINCE_COLLECTION_OFFSET =
                    /* first word past the size-class cache; see GROWTH_STATS_BASE */
                    2704 + 20 * 16 * 4;
    /** {@code McWebLMSafepoint}'s STAT_STOPPED and STAT_UNCONTENDED, read directly. */
    private static final int STAT_STOPPED_OFFSET = 284;
    private static final int STAT_UNCONTENDED_OFFSET = 288;
    /**
     * Free-list probes a bounded search may make. A 4096-entry walk was visible as
     * 100-200 ms menu frames once the threaded reload had fragmented the arena.
     */
    private static final int BOUNDED_SEARCH_LIMIT = 4096;

    /**
     * Number of constant-time free-block size classes. Classes 0..18 cover powers of
     * two from 32 bytes through 8 MiB; the last class also covers larger blocks.
     *
     * <p>The upstream allocator has one doubly-linked free list. After a Minecraft
     * collection that list contains millions of small holes, so even a 256-node
     * bounded first-fit costs visible frame time and regularly misses a suitable tail
     * block. Sixteen maximum block pointers per size class preserve the upstream list as
     * the source of truth while giving the common allocation path a bounded lookup and
     * retaining replacements when a split remainder drops into the next smaller class.
     * Every list add/remove updates this cache under {@link McWebLMHeapLock}.</p>
     */
    private static final int SIZE_CLASS_COUNT = 20;
    private static final int SIZE_CLASS_SLOTS = 16;
    private static final long SIZE_CLASS_MIN_BYTES = 32L;
    private static final long ADDRESS_MASK = 0xffff_ffffL;

    /* Primitive statics live in the shared WasmLM data area; no Java array access or
     * allocation is permitted on this uninterruptible allocator path. */
    private static long freeClass0;
    private static long freeClass1;
    private static long freeClass2;
    private static long freeClass3;
    private static long freeClass4;
    private static long freeClass5;
    private static long freeClass6;
    private static long freeClass7;
    private static long freeClass8;
    private static long freeClass9;
    private static long freeClass10;
    private static long freeClass11;
    private static long freeClass12;
    private static long freeClass13;
    private static long freeClass14;
    private static long freeClass15;
    private static long freeClass16;
    private static long freeClass17;
    private static long freeClass18;
    private static long freeClass19;
    private static long freeClassBackup0;
    private static long freeClassBackup1;
    private static long freeClassBackup2;
    private static long freeClassBackup3;
    private static long freeClassBackup4;
    private static long freeClassBackup5;
    private static long freeClassBackup6;
    private static long freeClassBackup7;
    private static long freeClassBackup8;
    private static long freeClassBackup9;
    private static long freeClassBackup10;
    private static long freeClassBackup11;
    private static long freeClassBackup12;
    private static long freeClassBackup13;
    private static long freeClassBackup14;
    private static long freeClassBackup15;
    private static long freeClassBackup16;
    private static long freeClassBackup17;
    private static long freeClassBackup18;
    private static long freeClassBackup19;
    private static int sizeClassHits;
    private static int sizeClassBackupHits;
    private static int sizeClassMisses;
    /**
     * The largest free block is a proof of fit, not merely a hint.  Every insertion
     * point in the patched FreeList calls {@link #registerFreeBlock}, and every
     * removal invalidates the entry before changing the header or links.  Keeping this
     * one pointer means a request that cannot fit can be rejected in O(1) time; the
     * old fallback walked the entire fragmented list twice before growing the exact
     * request, which is the world-load allocator stall seen in the threaded trace.
    */
    private static long largestFreeAddress;

    /** Global largest-free candidates, packed as two 32-bit WasmLM addresses per word. */
    private static final int GLOBAL_CACHE_SLOTS = 480;
    /** Shared control-page cache; Java statics are private to each Wasm instance. */
    private static final int GLOBAL_CACHE_OFFSET = 768;
    private static final int GLOBAL_REFILL_CURSOR_OFFSET = GLOBAL_CACHE_OFFSET + GLOBAL_CACHE_SLOTS * 4;
    /** Negative-fit memo, kept after the shared cache and its scan cursor. */
    private static final int NO_FIT_TOPOLOGY_OFFSET = GLOBAL_REFILL_CURSOR_OFFSET + 4;
    private static final int NO_FIT_COLLECTION_OFFSET = NO_FIT_TOPOLOGY_OFFSET + 4;
    private static final int NO_FIT_MAX_KIB_OFFSET = NO_FIT_COLLECTION_OFFSET + 4;
    /** Shared size-class slots follow the negative-fit memo. */
    private static final int SIZE_CLASS_CACHE_OFFSET = NO_FIT_MAX_KIB_OFFSET + 4;
    /**
     * First free control word after the size-class cache, asserted against the derived
     * layout below. Offsets in this control block are dense and hand-assigned across
     * McWebLMHeapLock/Safepoint/Monitors/HeapPolicy — 764, the obvious-looking gap, is
     * already {@code STAT_SKIP_BLOCKERS_LAST}, so new slots go here.
     */
    private static final int GROWTH_STATS_BASE =
                    SIZE_CLASS_CACHE_OFFSET + SIZE_CLASS_COUNT * SIZE_CLASS_SLOTS * 4;

    static {
        // The literal in GROWN_SINCE_COLLECTION_OFFSET cannot forward-reference the
        // derived constant, so pin the two together at class init instead of letting
        // them drift if the cache geometry ever changes.
        if (GROWN_SINCE_COLLECTION_OFFSET != GROWTH_STATS_BASE) {
            throw new IllegalStateException("growth stats offset drifted from the layout");
        }
    }
    /** Diagnostic toggle; the production path uses the proof after probe validation. */
    private static final boolean ENABLE_LARGEST_FREE_PROOF = true;
    private static long globalFree0;
    private static long globalFree1;
    private static long globalFree2;
    private static long globalFree3;
    private static long globalFree4;
    private static long globalFree5;
    private static long globalFree6;
    private static long globalFree7;
    private static long globalFree8;
    private static long globalFree9;
    private static long globalFree10;
    private static long globalFree11;
    private static long globalFree12;
    private static long globalFree13;
    private static long globalFree14;
    private static long globalFree15;
    private static long globalFree16;
    private static long globalFree17;
    private static long globalFree18;
    private static long globalFree19;
    private static long globalFree20;
    private static long globalFree21;
    private static long globalFree22;
    private static long globalFree23;
    private static long globalFree24;
    private static long globalFree25;
    private static long globalFree26;
    private static long globalFree27;
    private static long globalFree28;
    private static long globalFree29;
    private static long globalFree30;
    private static long globalFree31;
    /** Cursor for incremental cache repair; zero means start at the current list head. */
    private static long globalRefillCursor;
    private static final int GLOBAL_REFILL_BUDGET = 4096;

    /**
     * Control word holding the current search limit, in raw memory below
     * {@code MemoryLayout.HEAP_BASE} alongside {@link McWebLMHeapLock}'s state, so the
     * injected bound in {@code allocateInExistingBlocks} can read it with a plain load
     * from uninterruptible, non-allocating code.
     */
    private static final int SEARCH_LIMIT_OFFSET = 312;
    /** Actual deep/free-list walks; kept separate from the bounded probe counter. */
    private static final int FULL_SCANS_OFFSET = 304;
    private static final int PROBE_COUNT_OFFSET = 340;

    /*
     * Allocator diagnostics live in the same raw control page as the existing
     * safepoint counters.  They are deliberately plain integers: allocation is
     * already serialized by McWebLMHeapLock, and keeping these counters out of
     * the Java heap makes them readable even when the primary is wedged at the
     * memory ceiling.
     */
    private static final int BOUNDED_MISSES_OFFSET = 344;
    private static final int DEEP_SEARCHES_OFFSET = 348;
    private static final int DEEP_HITS_OFFSET = 352;
    private static final int GROW_ATTEMPTS_OFFSET = 356;
    private static final int GROW_FAILURES_OFFSET = 360;
    private static final int NO_PROGRESS_OFFSET = 364;
    private static final int TOPOLOGY_EPOCH_OFFSET = 368;
    private static final int MAX_REQUEST_KIB_OFFSET = 372;
    private static final int LAST_HEAD_KIB_OFFSET = 376;
    private static final int LAST_CANDIDATE_KIB_OFFSET = 380;
    private static final int LAST_FAILED_REQUEST_OFFSET = 384;
    private static final int LAST_FAILED_COLLECTION_OFFSET = 388;
    private static final int LAST_FAILED_TOPOLOGY_OFFSET = 392;
    /**
     * State for the current unsatisfied request.  The allocator can be called many
     * times while a large world task is waiting on another agent.  Repeating the
     * same deep walk in that interval is pure browser-thread work, so remember which
     * phase has already run until a collection epoch or a fit-relevant free-list
     * change occurs.
     *
     * <p>Values: 0 = no failed request, 1 = pre-GC deep miss, 2 = collection tried,
     * 3 = post-GC deep miss (one final growth fallback remains), 4 = fallback growth
     * failed and the request must fail deterministically.</p>
     */
    private static final int FAILED_SEARCH_STATE_OFFSET = 396;

    /*
     * Allocation failure diagnostics live after the safepoint state table
     * (400..671) and the primary-waiting word (704).  They are written on the
     * no-allocation path, so the watchdog can distinguish a real null returned
     * by the allocator from a Java future/queue failure while the page thread
     * is still blocked in the synchronous world-load call.
     */
    private static final int OOM_COUNT_OFFSET = 708;
    private static final int OOM_REQUEST_KIB_OFFSET = 712;
    private static final int OOM_AGENT_OFFSET = 716;
    private static final int OOM_SEARCH_STATE_OFFSET = 720;
    private static final int OOM_GROWN_MIB_OFFSET = 724;
    /**
     * Snapshot taken at every no-progress event (a collection ran, yet the deep
     * search still found no fit): the free/heap MiB and request/proof sizes at the
     * moment of the refusal. Readable from the watchdog without entering Wasm, which
     * is the only way to see them while the primary is wedged. freeMiB >> requestKiB
     * with proofKiB small means free space exists but only as fragments the proof
     * correctly reports as too small (a coalescing problem); freeMiB ~ 0 means the
     * heap is genuinely full (a root-retention problem).
     */
    private static final int NO_PROGRESS_FREE_MIB_OFFSET = 736;
    private static final int NO_PROGRESS_HEAP_MIB_OFFSET = 740;
    private static final int NO_PROGRESS_REQUEST_KIB_OFFSET = 744;
    private static final int NO_PROGRESS_PROOF_KIB_OFFSET = 748;
    /** Size the largest-free proof claimed on the most recent deep-search decision. */
    private static final int LAST_PROOF_KIB_OFFSET = 752;
    /**
     * Diagnostic full-list walk taken at each no-progress event, under the heap lock.
     * truthKiB == proofKiB means the refusal is honest fragmentation; truthKiB far
     * above proofKiB means the cache lost a block; a small block count with a large
     * freeMiB means the bookkeeping and the list disagree (lost space).
     */
    private static final int NO_PROGRESS_TRUTH_KIB_OFFSET = 756;
    private static final int NO_PROGRESS_FREE_BLOCKS_OFFSET = 760;

    private McWebLMHeapPolicy() {
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer control(int offset) {
        return Word.pointer(offset);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void increment(int offset) {
        Pointer word = control(offset);
        int value = word.readInt(0);
        if (value != Integer.MAX_VALUE) {
            word.writeInt(0, value + 1);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int requestKey(UnsignedWord padded) {
        long kib = padded.rawValue() >>> 10;
        return kib >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) kib;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void recordRequest(UnsignedWord padded) {
        Pointer max = control(MAX_REQUEST_KIB_OFFSET);
        int request = requestKey(padded);
        if (request > max.readInt(0)) {
            max.writeInt(0, request);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void recordBoundedMiss() {
        increment(BOUNDED_MISSES_OFFSET);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void recordTopologyChange() {
        /*
         * Kept as a compatibility hook for the generated FreeList bridge.  A raw
         * link insertion is not enough to invalidate a failed search: inserting a
         * smaller fragment cannot make the same request succeed.  The precise,
         * size-aware invalidation happens in registerFreeBlock below, after the
         * block header is available.
         */
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void recordPotentialFit(long candidateBytes) {
        int failedRequest = control(LAST_FAILED_REQUEST_OFFSET).readInt(0);
        int negativeFitLargest = control(NO_FIT_MAX_KIB_OFFSET).readInt(0);
        /*
         * LAST_FAILED_REQUEST is the request size rounded down to KiB.  This is
         * intentionally conservative: a block whose KiB floor reaches that value
         * may still be a few header bytes short, but a genuinely fitting block can
         * never have a smaller floor.  False positives merely permit one extra
         * search; false negatives would strand a fitting block behind the memo.
         */
        boolean fitsFailedRequest = failedRequest >= 0
                && (candidateBytes >>> 10) >= (long) failedRequest;
        boolean invalidatesNegativeFit = negativeFitLargest >= 0
                && (candidateBytes >>> 10) >= (long) negativeFitLargest;
        if (fitsFailedRequest || invalidatesNegativeFit) {
            increment(TOPOLOGY_EPOCH_OFFSET);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean sameFailedRequest(UnsignedWord padded) {
        int state = control(FAILED_SEARCH_STATE_OFFSET).readInt(0);
        if (state >= 2 && negativeFitKnown(padded)) {
            return true;
        }
        return state >= 2
                && control(LAST_FAILED_REQUEST_OFFSET).readInt(0) == requestKey(padded)
                && control(LAST_FAILED_COLLECTION_OFFSET).readInt(0) == collectionsRun()
                && control(LAST_FAILED_TOPOLOGY_OFFSET).readInt(0)
                        == control(TOPOLOGY_EPOCH_OFFSET).readInt(0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean sameDeepSearch(UnsignedWord padded) {
        int state = control(FAILED_SEARCH_STATE_OFFSET).readInt(0);
        return (state == 1 || state == 3 || state == 4)
                && control(LAST_FAILED_REQUEST_OFFSET).readInt(0) == requestKey(padded)
                && control(LAST_FAILED_COLLECTION_OFFSET).readInt(0) == collectionsRun()
                && control(LAST_FAILED_TOPOLOGY_OFFSET).readInt(0)
                        == control(TOPOLOGY_EPOCH_OFFSET).readInt(0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void rememberFailedRequest(UnsignedWord padded) {
        control(LAST_FAILED_REQUEST_OFFSET).writeInt(0, requestKey(padded));
        control(LAST_FAILED_COLLECTION_OFFSET).writeInt(0, collectionsRun());
        control(LAST_FAILED_TOPOLOGY_OFFSET).writeInt(0, control(TOPOLOGY_EPOCH_OFFSET).readInt(0));
        control(FAILED_SEARCH_STATE_OFFSET).writeInt(0, 2);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void rememberDeepSearchMiss(UnsignedWord padded) {
        control(LAST_FAILED_REQUEST_OFFSET).writeInt(0, requestKey(padded));
        control(LAST_FAILED_COLLECTION_OFFSET).writeInt(0, collectionsRun());
        control(LAST_FAILED_TOPOLOGY_OFFSET).writeInt(0, control(TOPOLOGY_EPOCH_OFFSET).readInt(0));
        int prior = control(FAILED_SEARCH_STATE_OFFSET).readInt(0);
        control(FAILED_SEARCH_STATE_OFFSET).writeInt(0, prior == 2 ? 3 : 1);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void clearFailedRequest() {
        control(LAST_FAILED_REQUEST_OFFSET).writeInt(0, -1);
        control(FAILED_SEARCH_STATE_OFFSET).writeInt(0, 0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean negativeFitKnown(UnsignedWord padded) {
        int largestKiB = control(NO_FIT_MAX_KIB_OFFSET).readInt(0);
        return largestKiB >= 0
                && requestKey(padded) > largestKiB
                && control(NO_FIT_COLLECTION_OFFSET).readInt(0) == collectionsRun()
                && control(NO_FIT_TOPOLOGY_OFFSET).readInt(0)
                        == control(TOPOLOGY_EPOCH_OFFSET).readInt(0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean deepSearchAlreadyKnown(UnsignedWord padded) {
        return sameDeepSearch(padded) || negativeFitKnown(padded);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void initializeDiagnostics() {
        control(LAST_FAILED_REQUEST_OFFSET).writeInt(0, -1);
        control(LAST_FAILED_COLLECTION_OFFSET).writeInt(0, -1);
        control(LAST_FAILED_TOPOLOGY_OFFSET).writeInt(0, -1);
        control(FAILED_SEARCH_STATE_OFFSET).writeInt(0, 0);
        for (int sizeClass = 0; sizeClass < SIZE_CLASS_COUNT; sizeClass++) {
            for (int slot = 0; slot < SIZE_CLASS_SLOTS; slot++) {
                setCachedAddress(sizeClass, slot, 0L);
            }
        }
        sizeClassHits = 0;
        sizeClassBackupHits = 0;
        sizeClassMisses = 0;
        for (int slot = 0; slot < GLOBAL_CACHE_SLOTS; slot++) {
            setGlobalCachedAddress(slot, 0L);
        }
        setGlobalRefillCursor(0L);
        control(NO_FIT_TOPOLOGY_OFFSET).writeInt(0, -1);
        control(NO_FIT_COLLECTION_OFFSET).writeInt(0, -1);
        control(NO_FIT_MAX_KIB_OFFSET).writeInt(0, -1);
        largestFreeAddress = 0L;
    }

    /**
     * Forget the cached view of the free list before the collector rebuilds it from the
     * linear block chain. The sweep deliberately does not call the normal per-object
     * {@code FreeList.add/remove} hooks: doing so performs the same cache bookkeeping for
     * every white object, even though the final coalesced run is the only node that will
     * remain. The list is empty while this method's caller reconstructs those run heads.
     *
     * <p>The topology epoch is advanced even when the chain contains no reclaimable
     * object. A failed allocation memo from before the collection must never treat the
     * rebuilt cache as the same free-list state.</p>
     */
    @Uninterruptible(reason = "Called while the collector owns the allocator lock.")
    public static void resetFreeBlockIndex() {
        for (int sizeClass = 0; sizeClass < SIZE_CLASS_COUNT; sizeClass++) {
            for (int slot = 0; slot < SIZE_CLASS_SLOTS; slot++) {
                setCachedAddress(sizeClass, slot, 0L);
            }
        }
        for (int slot = 0; slot < GLOBAL_CACHE_SLOTS; slot++) {
            setGlobalCachedAddress(slot, 0L);
        }
        setGlobalRefillCursor(0L);
        largestFreeAddress = 0L;
        control(NO_FIT_TOPOLOGY_OFFSET).writeInt(0, -1);
        control(NO_FIT_COLLECTION_OFFSET).writeInt(0, -1);
        control(NO_FIT_MAX_KIB_OFFSET).writeInt(0, -1);
        increment(TOPOLOGY_EPOCH_OFFSET);
        clearFailedRequest();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int kib(long bytes) {
        long value = bytes >>> 10;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    /**
     * Probe budget for the next first-fit walk. Read by the bound the patcher injects
     * into {@code WasmAllocation.allocateInExistingBlocks}. A zero control word means
     * the normal bounded search. Complete recovery walks use the pointer-only helpers
     * in {@link #deepSearch(UnsignedWord)} so they can choose the largest fitting block
     * instead of repeating upstream first-fit behavior.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int searchLimit() {
        Pointer probes = control(PROBE_COUNT_OFFSET);
        probes.writeInt(0, probes.readInt(0) + 1);
        int limit = control(SEARCH_LIMIT_OFFSET).readInt(0);
        return limit == 0 ? BOUNDED_SEARCH_LIMIT : limit;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.probes", comment = "Bounded allocator probe calls")
    public static int allocationProbesExport() {
        return control(PROBE_COUNT_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.fullScans", comment = "Actual deep free-list scans")
    public static int fullScansExport() {
        return control(FULL_SCANS_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.boundedMisses", comment = "Bounded free-list search misses")
    public static int boundedMissesExport() {
        return control(BOUNDED_MISSES_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.deepSearches", comment = "Post-collection deep free-list searches")
    public static int deepSearchesExport() {
        return control(DEEP_SEARCHES_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.deepHits", comment = "Deep free-list searches that found a block")
    public static int deepHitsExport() {
        return control(DEEP_HITS_OFFSET).readInt(0);
    }

    /**
     * Diagnostic oracle: the true largest free block, in KiB, from a complete list
     * walk. This is the O(n) cost the O(1) proof exists to avoid; it must never run
     * on the allocation path. Call it only when no thread is inside the allocator -
     * between probe phases, or from the host while the primary owns the browser
     * thread - because it reads list links without {@link McWebLMHeapLock}.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.truthLargestKiB", comment = "True largest free block from a full list walk")
    public static int truthLargestKiB() {
        long largestBytes = 0L;
        Pointer candidate = WasmAllocation.firstFreeBlock();
        int steps = 0;
        while (candidate.isNonNull()) {
            if ((steps++ & 63) == 0) {
                McWebLMSafepoint.poll();
            }
            long candidateBytes = blockBytes(candidate);
            if (candidateBytes > largestBytes) {
                largestBytes = candidateBytes;
            }
            candidate = WasmAllocation.nextFreeBlock(candidate);
        }
        return kib(largestBytes);
    }

    /**
     * Diagnostic: the size the cached largest-free *proof* currently claims, in KiB.
     * Compare against {@link #truthLargestKiB}: a proof below the truth means a free
     * block was lost from the cache and the O(1) fit rejection can refuse space the
     * heap actually has.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.largestFreeKiB", comment = "Size claimed by the cached largest-free proof")
    public static int largestFreeKiB() {
        Pointer candidate = largestFreeBlock();
        if (candidate.isNull()) {
            return 0;
        }
        return kib(blockBytes(candidate));
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.growAttempts", comment = "Allocator region growth attempts")
    public static int growAttemptsExport() {
        return control(GROW_ATTEMPTS_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.growFailures", comment = "Allocator region growth failures")
    public static int growFailuresExport() {
        return control(GROW_FAILURES_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgress", comment = "Collections followed by an empty deep search")
    public static int noProgressExport() {
        return control(NO_PROGRESS_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressFreeMiB", comment = "Free MiB at the last no-progress refusal")
    public static int noProgressFreeMiBExport() {
        return control(NO_PROGRESS_FREE_MIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressHeapMiB", comment = "Region MiB at the last no-progress refusal")
    public static int noProgressHeapMiBExport() {
        return control(NO_PROGRESS_HEAP_MIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressRequestKiB", comment = "Refused request at the last no-progress event")
    public static int noProgressRequestKiBExport() {
        return control(NO_PROGRESS_REQUEST_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressProofKiB", comment = "Proof claim at the last no-progress refusal")
    public static int noProgressProofKiBExport() {
        return control(NO_PROGRESS_PROOF_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.lastProofKiB", comment = "Proof claim on the most recent deep-search decision")
    public static int lastProofKiBExport() {
        return control(LAST_PROOF_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressTruthKiB", comment = "Full-walk largest free block at the last no-progress event")
    public static int noProgressTruthKiBExport() {
        return control(NO_PROGRESS_TRUTH_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.noProgressFreeBlocks", comment = "Free-list block count at the last no-progress event")
    public static int noProgressFreeBlocksExport() {
        return control(NO_PROGRESS_FREE_BLOCKS_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.topologyEpoch", comment = "Fit-relevant free-list changes")
    public static int topologyEpochExport() {
        return control(TOPOLOGY_EPOCH_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.maxRequestKiB", comment = "Largest requested allocation in KiB")
    public static int maxRequestKiBExport() {
        return control(MAX_REQUEST_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.lastHeadKiB", comment = "Last observed free-list head size in KiB")
    public static int lastHeadKiBExport() {
        return control(LAST_HEAD_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.lastCandidateKiB", comment = "Last coalesced free block size in KiB")
    public static int lastCandidateKiBExport() {
        return control(LAST_CANDIDATE_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.failedSearchState", comment = "Allocator failed-request search phase")
    public static int failedSearchStateExport() {
        return control(FAILED_SEARCH_STATE_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.oomCount", comment = "Allocator calls that returned null")
    public static int oomCountExport() {
        return control(OOM_COUNT_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.oomRequestKiB", comment = "Last null allocation request in KiB")
    public static int oomRequestKiBExport() {
        return control(OOM_REQUEST_KIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.oomAgent", comment = "Agent that saw the last null allocation")
    public static int oomAgentExport() {
        return control(OOM_AGENT_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.oomSearchState", comment = "Failed-request phase at last null allocation")
    public static int oomSearchStateExport() {
        return control(OOM_SEARCH_STATE_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.oomGrownMiB", comment = "Chunked growth at last null allocation")
    public static int oomGrownMiBExport() {
        return control(OOM_GROWN_MIB_OFFSET).readInt(0);
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.sizeClassHits", comment = "Allocations served by the free-block size cache")
    public static int sizeClassHitsExport() {
        return sizeClassHits;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.sizeClassBackupHits", comment = "Allocations served by retained size-class replacements")
    public static int sizeClassBackupHitsExport() {
        return sizeClassBackupHits;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.sizeClassMisses", comment = "Allocations that fell back to the linked free list")
    public static int sizeClassMissesExport() {
        return sizeClassMisses;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.objectMiB", comment = "Currently allocated object bytes in MiB")
    public static int objectMiBExport() {
        return kib(WasmAllocation.getObjectSize()) >>> 10;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.freeMiB", comment = "Total free allocator bytes in MiB")
    public static int freeMiBExport() {
        return kib(WasmAllocation.getFreeSize()) >>> 10;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.heapMiB", comment = "Allocator-region bytes in MiB")
    public static int heapMiBExport() {
        return kib(WasmAllocation.getHeapSize()) >>> 10;
    }

    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.alloc.objectPercent", comment = "Allocated percentage of the allocator region")
    public static int objectPercentExport() {
        return WasmAllocation.getObjectPercentage();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int requestSizeClass(long bytes) {
        int result = 0;
        long upperBound = SIZE_CLASS_MIN_BYTES;
        while (result < SIZE_CLASS_COUNT - 1 && bytes > upperBound) {
            upperBound <<= 1;
            result++;
        }
        return result;
    }

    /**
     * Put free blocks in the same ceiling bucket used by requests.
     *
     * <p>Using the block's lower power-of-two bound looked attractive because every
     * block in the request's bucket would then be guaranteed to fit.  It was also
     * wrong: a 76 KiB block landed in the 64 KiB floor bucket while a 76 KiB request
     * started in the 128 KiB ceiling bucket, so the cache skipped an exact fit and the
     * allocator collected the whole Minecraft heap.  A settled Accessibility screen
     * measured 38 such collections in ten seconds.</p>
     *
     * <p>The cache already retains four candidates per bucket, keeps them
     * largest-first, and validates the header before returning one.  Sharing the
     * request's ceiling mapping therefore keeps all potentially fitting blocks
     * reachable without weakening correctness.</p>
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int freeSizeClass(long bytes) {
        return requestSizeClass(bytes);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long primaryCacheWord(int sizeClass) {
        switch (sizeClass) {
            case 0: return freeClass0;
            case 1: return freeClass1;
            case 2: return freeClass2;
            case 3: return freeClass3;
            case 4: return freeClass4;
            case 5: return freeClass5;
            case 6: return freeClass6;
            case 7: return freeClass7;
            case 8: return freeClass8;
            case 9: return freeClass9;
            case 10: return freeClass10;
            case 11: return freeClass11;
            case 12: return freeClass12;
            case 13: return freeClass13;
            case 14: return freeClass14;
            case 15: return freeClass15;
            case 16: return freeClass16;
            case 17: return freeClass17;
            case 18: return freeClass18;
            case 19: return freeClass19;
            default: return 0L;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void setPrimaryCacheWord(int sizeClass, long value) {
        switch (sizeClass) {
            case 0: freeClass0 = value; break;
            case 1: freeClass1 = value; break;
            case 2: freeClass2 = value; break;
            case 3: freeClass3 = value; break;
            case 4: freeClass4 = value; break;
            case 5: freeClass5 = value; break;
            case 6: freeClass6 = value; break;
            case 7: freeClass7 = value; break;
            case 8: freeClass8 = value; break;
            case 9: freeClass9 = value; break;
            case 10: freeClass10 = value; break;
            case 11: freeClass11 = value; break;
            case 12: freeClass12 = value; break;
            case 13: freeClass13 = value; break;
            case 14: freeClass14 = value; break;
            case 15: freeClass15 = value; break;
            case 16: freeClass16 = value; break;
            case 17: freeClass17 = value; break;
            case 18: freeClass18 = value; break;
            case 19: freeClass19 = value; break;
            default: break;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long backupCacheWord(int sizeClass) {
        switch (sizeClass) {
            case 0: return freeClassBackup0;
            case 1: return freeClassBackup1;
            case 2: return freeClassBackup2;
            case 3: return freeClassBackup3;
            case 4: return freeClassBackup4;
            case 5: return freeClassBackup5;
            case 6: return freeClassBackup6;
            case 7: return freeClassBackup7;
            case 8: return freeClassBackup8;
            case 9: return freeClassBackup9;
            case 10: return freeClassBackup10;
            case 11: return freeClassBackup11;
            case 12: return freeClassBackup12;
            case 13: return freeClassBackup13;
            case 14: return freeClassBackup14;
            case 15: return freeClassBackup15;
            case 16: return freeClassBackup16;
            case 17: return freeClassBackup17;
            case 18: return freeClassBackup18;
            case 19: return freeClassBackup19;
            default: return 0L;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void setBackupCacheWord(int sizeClass, long value) {
        switch (sizeClass) {
            case 0: freeClassBackup0 = value; break;
            case 1: freeClassBackup1 = value; break;
            case 2: freeClassBackup2 = value; break;
            case 3: freeClassBackup3 = value; break;
            case 4: freeClassBackup4 = value; break;
            case 5: freeClassBackup5 = value; break;
            case 6: freeClassBackup6 = value; break;
            case 7: freeClassBackup7 = value; break;
            case 8: freeClassBackup8 = value; break;
            case 9: freeClassBackup9 = value; break;
            case 10: freeClassBackup10 = value; break;
            case 11: freeClassBackup11 = value; break;
            case 12: freeClassBackup12 = value; break;
            case 13: freeClassBackup13 = value; break;
            case 14: freeClassBackup14 = value; break;
            case 15: freeClassBackup15 = value; break;
            case 16: freeClassBackup16 = value; break;
            case 17: freeClassBackup17 = value; break;
            case 18: freeClassBackup18 = value; break;
            case 19: freeClassBackup19 = value; break;
            default: break;
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long cachedAddress(int sizeClass, int slot) {
        int offset = SIZE_CLASS_CACHE_OFFSET
                + (sizeClass * SIZE_CLASS_SLOTS + slot) * 4;
        return control(offset).readInt(0) & ADDRESS_MASK;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void setCachedAddress(int sizeClass, int slot, long address) {
        int offset = SIZE_CLASS_CACHE_OFFSET
                + (sizeClass * SIZE_CLASS_SLOTS + slot) * 4;
        control(offset).writeInt(0, (int) (address & ADDRESS_MASK));
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static long globalCacheWord(int word) {
        switch (word) {
            case 0: return globalFree0;
            case 1: return globalFree1;
            case 2: return globalFree2;
            case 3: return globalFree3;
            case 4: return globalFree4;
            case 5: return globalFree5;
            case 6: return globalFree6;
            case 7: return globalFree7;
            case 8: return globalFree8;
            case 9: return globalFree9;
            case 10: return globalFree10;
            case 11: return globalFree11;
            case 12: return globalFree12;
            case 13: return globalFree13;
            case 14: return globalFree14;
            case 15: return globalFree15;
            case 16: return globalFree16;
            case 17: return globalFree17;
            case 18: return globalFree18;
            case 19: return globalFree19;
            case 20: return globalFree20;
            case 21: return globalFree21;
            case 22: return globalFree22;
            case 23: return globalFree23;
            case 24: return globalFree24;
            case 25: return globalFree25;
            case 26: return globalFree26;
            case 27: return globalFree27;
            case 28: return globalFree28;
            case 29: return globalFree29;
            case 30: return globalFree30;
            case 31: return globalFree31;
            default: return 0L;
        }
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void setGlobalCacheWord(int word, long value) {
        switch (word) {
            case 0: globalFree0 = value; break;
            case 1: globalFree1 = value; break;
            case 2: globalFree2 = value; break;
            case 3: globalFree3 = value; break;
            case 4: globalFree4 = value; break;
            case 5: globalFree5 = value; break;
            case 6: globalFree6 = value; break;
            case 7: globalFree7 = value; break;
            case 8: globalFree8 = value; break;
            case 9: globalFree9 = value; break;
            case 10: globalFree10 = value; break;
            case 11: globalFree11 = value; break;
            case 12: globalFree12 = value; break;
            case 13: globalFree13 = value; break;
            case 14: globalFree14 = value; break;
            case 15: globalFree15 = value; break;
            case 16: globalFree16 = value; break;
            case 17: globalFree17 = value; break;
            case 18: globalFree18 = value; break;
            case 19: globalFree19 = value; break;
            case 20: globalFree20 = value; break;
            case 21: globalFree21 = value; break;
            case 22: globalFree22 = value; break;
            case 23: globalFree23 = value; break;
            case 24: globalFree24 = value; break;
            case 25: globalFree25 = value; break;
            case 26: globalFree26 = value; break;
            case 27: globalFree27 = value; break;
            case 28: globalFree28 = value; break;
            case 29: globalFree29 = value; break;
            case 30: globalFree30 = value; break;
            case 31: globalFree31 = value; break;
            default: break;
        }
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static long globalCachedAddress(int slot) {
        return control(GLOBAL_CACHE_OFFSET + slot * 4).readInt(0) & ADDRESS_MASK;
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void setGlobalCachedAddress(int slot, long address) {
        control(GLOBAL_CACHE_OFFSET + slot * 4).writeInt(0, (int) (address & ADDRESS_MASK));
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static long globalRefillCursor() {
        return control(GLOBAL_REFILL_CURSOR_OFFSET).readInt(0) & ADDRESS_MASK;
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void setGlobalRefillCursor(long address) {
        control(GLOBAL_REFILL_CURSOR_OFFSET).writeInt(0, (int) (address & ADDRESS_MASK));
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void removeGlobalSlot(int slot) {
        for (int next = slot; next < GLOBAL_CACHE_SLOTS - 1; next++) {
            setGlobalCachedAddress(next, globalCachedAddress(next + 1));
        }
        setGlobalCachedAddress(GLOBAL_CACHE_SLOTS - 1, 0L);
    }

    /** Keep the global candidates largest-first; this is a bounded O(8) update. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void registerGlobalFreeBlock(long candidateAddress, long candidateBytes) {
        int insertion = GLOBAL_CACHE_SLOTS;
        int slot = 0;
        while (slot < GLOBAL_CACHE_SLOTS) {
            long cachedAddress = globalCachedAddress(slot);
            if (cachedAddress == candidateAddress) {
                return;
            }
            if (cachedAddress == 0L) {
                insertion = slot;
                break;
            }
            long cachedHeader = blockHeader(Word.pointer(cachedAddress));
            if ((cachedHeader & 1L) != 0L) {
                removeGlobalSlot(slot);
                continue;
            }
            if (candidateBytes > (cachedHeader & ~7L)) {
                insertion = slot;
                break;
            }
            slot++;
        }
        if (insertion < GLOBAL_CACHE_SLOTS) {
            for (int destination = GLOBAL_CACHE_SLOTS - 1; destination > insertion; destination--) {
                setGlobalCachedAddress(destination, globalCachedAddress(destination - 1));
            }
            setGlobalCachedAddress(insertion, candidateAddress);
        }
    }

    /**
     * Avoid the O(cache-size) insertion walk for the overwhelmingly common case in
     * which a newly observed block is below the retained largest candidates.  A full
     * registration is still used when the candidate can displace the cache floor.
     */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void considerGlobalFreeBlock(long candidateAddress, long candidateBytes) {
        long floorAddress = globalCachedAddress(GLOBAL_CACHE_SLOTS - 1);
        if (floorAddress == 0L) {
            registerGlobalFreeBlock(candidateAddress, candidateBytes);
            return;
        }
        long floorHeader = blockHeader(Word.pointer(floorAddress));
        if ((floorHeader & 1L) != 0L || candidateBytes > (floorHeader & ~7L)) {
            registerGlobalFreeBlock(candidateAddress, candidateBytes);
        }
    }

    /**
     * Replenish the largest-free reservoir from a bounded slice of the live list.
     * Allocating a cached candidate necessarily evicts one of the retained entries,
     * but the next-largest block was not retained.  A rotating cursor repairs that
     * omission incrementally on a later miss instead of walking the entire list for
     * every allocation burst.
     */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void refillGlobalCache(long requiredOuter) {
        long cursor = globalRefillCursor();
        Pointer candidate = cursor == 0L
                ? WasmAllocation.firstFreeBlock()
                : Word.pointer(cursor);
        if (candidate.isNonNull() && (blockHeader(candidate) & 1L) != 0L) {
            candidate = WasmAllocation.firstFreeBlock();
        }
        int steps = 0;
        while (candidate.isNonNull() && steps++ < GLOBAL_REFILL_BUDGET) {
            Pointer next = WasmAllocation.nextFreeBlock(candidate);
            long candidateAddress = candidate.rawValue() & ADDRESS_MASK;
            long candidateHeader = blockHeader(candidate);
            if ((candidateHeader & 1L) == 0L) {
                long candidateBytes = candidateHeader & ~7L;
                considerGlobalFreeBlock(candidateAddress, candidateBytes);
            }
            candidate = next;
        }
        setGlobalRefillCursor(candidate.isNull()
                ? 0L
                : (candidate.rawValue() & ADDRESS_MASK));
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void unregisterGlobalFreeBlock(long address) {
        for (int slot = 0; slot < GLOBAL_CACHE_SLOTS; slot++) {
            if (globalCachedAddress(slot) == address) {
                removeGlobalSlot(slot);
                return;
            }
        }
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static Pointer largestFreeBlock() {
        if (!ENABLE_LARGEST_FREE_PROOF) {
            return Word.nullPointer();
        }
        long address = globalCachedAddress(0);
        largestFreeAddress = address;
        if (address == 0L) {
            return Word.nullPointer();
        }
        Pointer candidate = Word.pointer(address);
        if ((blockHeader(candidate) & 1L) != 0L) {
            // A stale entry is never used for allocation.  The normal remove hook
            // should have cleared it; dropping it here keeps a damaged cache from
            // turning into an unsafe pointer dereference.
            unregisterGlobalFreeBlock(address);
            largestFreeAddress = globalCachedAddress(0);
            return Word.nullPointer();
        }
        return candidate;
    }

    /**
     * Size of the largest free block the O(1) proof can vouch for, in bytes, or 0 when
     * it knows of none.
     *
     * <p>Callers must hold {@link McWebLMHeapLock}. This is deliberately the *proof*,
     * not the truth: the reservoir can under-report (see {@link #deepSearch}), and every
     * caller must treat a small answer as "ask for less", never as "the heap is full".
     * Its purpose is to let {@link McWebLMTlab#refill} size its region request to
     * something the arena can actually satisfy, instead of discovering the shortfall by
     * paying a failed {@code doMalloc} — which drags in a deep search, a collection
     * attempt, and the failed-request memo.
     *
     * <p>Returning a *byte count* rather than a yes/no fit test matters and was measured.
     * A variant that instead asked {@link #cachedFit} whether a candidate size was
     * servable, halving 64 KiB down a power-of-two ladder until it said yes, is strictly
     * coarser: where this reports a usable 9 KiB and gets a 9 KiB region, the ladder
     * rounds down to 4 KiB. Over a four-agent world load that took refills from 702,523
     * to 1,035,518 and shrunk regions on 69% of them instead of 56%. Do not replace this
     * with a fit test.
     */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    public static long largestFreeBytesHint() {
        Pointer candidate = largestFreeBlock();
        return candidate.isNull() ? 0L : blockBytes(candidate);
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void recomputeLargestFreeBlock() {
        for (int slot = 0; slot < GLOBAL_CACHE_SLOTS; slot++) {
            long address = globalCachedAddress(slot);
            if (address == 0L) {
                break;
            }
            if ((blockHeader(Word.pointer(address)) & 1L) != 0L) {
                removeGlobalSlot(slot--);
            }
        }
        largestFreeAddress = globalCachedAddress(0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void removeCachedSlot(int sizeClass, int slot) {
        for (int next = slot; next < SIZE_CLASS_SLOTS - 1; next++) {
            setCachedAddress(sizeClass, next, cachedAddress(sizeClass, next + 1));
        }
        setCachedAddress(sizeClass, SIZE_CLASS_SLOTS - 1, 0L);
    }

    /** Publish a free block as the largest known candidate in its size class. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    public static void registerFreeBlock(Pointer block) {
        registerFreeBlockInternal(block, true);
    }

    /** Re-index an existing block during a diagnostic/recovery walk without making it a topology change. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void registerScannedFreeBlock(Pointer block) {
        registerFreeBlockInternal(block, false);
    }

    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static void registerFreeBlockInternal(Pointer block, boolean fitRelevant) {
        if (block.isNull()) {
            return;
        }
        long candidateHeader = blockHeader(block);
        long candidateBytes = candidateHeader & ~7L;
        if (fitRelevant) {
            recordPotentialFit(candidateBytes);
        }
        int candidateClass = freeSizeClass(candidateBytes);
        long candidateAddress = block.rawValue() & ADDRESS_MASK;
        considerGlobalFreeBlock(candidateAddress, candidateBytes);
        largestFreeAddress = globalCachedAddress(0);

        /*
         * Normal FreeList.add calls publish a newly free block: it cannot already
         * occupy this class cache. Once the retained floor is valid, a block no
         * larger than that floor cannot improve any of the sixteen candidates, so
         * scanning all slots just to discover "no insertion" is pure allocator
         * overhead. Deep-search repair calls this method with fitRelevant=false and
         * retain the duplicate/stale-entry scan below, because those candidates may
         * already be present in the cache.
         */
        if (fitRelevant) {
            long floor = cachedAddress(candidateClass, SIZE_CLASS_SLOTS - 1);
            if (floor != 0L) {
                long floorHeader = blockHeader(Word.pointer(floor));
                if ((floorHeader & 1L) != 0L) {
                    removeCachedSlot(candidateClass, SIZE_CLASS_SLOTS - 1);
                } else if (candidateBytes <= (floorHeader & ~7L)) {
                    return;
                }
            }
        }

        int insertion = SIZE_CLASS_SLOTS;
        int slot = 0;
        while (slot < SIZE_CLASS_SLOTS) {
            long cached = cachedAddress(candidateClass, slot);
            if (cached == candidateAddress) {
                return;
            }
            if (cached == 0L) {
                insertion = slot;
                break;
            }
            long cachedHeader = blockHeader(Word.pointer(cached));
            if ((cachedHeader & 1L) != 0L) {
                removeCachedSlot(candidateClass, slot);
                continue;
            }
            if (candidateBytes > (cachedHeader & ~7L)) {
                insertion = slot;
                break;
            }
            slot++;
        }
        if (insertion < SIZE_CLASS_SLOTS) {
            for (int destination = SIZE_CLASS_SLOTS - 1; destination > insertion; destination--) {
                setCachedAddress(candidateClass, destination,
                                cachedAddress(candidateClass, destination - 1));
            }
            setCachedAddress(candidateClass, insertion, candidateAddress);
        }
    }

    /** Remove a block from its size class before its header or list links change. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    public static void unregisterFreeBlock(Pointer block) {
        if (block.isNull()) {
            return;
        }
        int currentClass = freeSizeClass(blockBytes(block));
        long address = block.rawValue() & ADDRESS_MASK;
        if (globalRefillCursor() == address) {
            Pointer next = WasmAllocation.nextFreeBlock(block);
            setGlobalRefillCursor(next.isNull()
                    ? 0L
                    : (next.rawValue() & ADDRESS_MASK));
        }
        unregisterGlobalFreeBlock(address);
        for (int slot = 0; slot < SIZE_CLASS_SLOTS; slot++) {
            if (cachedAddress(currentClass, slot) == address) {
                removeCachedSlot(currentClass, slot);
                break;
            }
        }
        largestFreeAddress = globalCachedAddress(0);
    }

    /** Return the smallest cached block from the first size class that can satisfy it. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static Pointer cachedSizeClassFit(long requiredOuter) {
        int firstClass = requestSizeClass(requiredOuter);
        for (int candidateClass = firstClass; candidateClass < SIZE_CLASS_COUNT; candidateClass++) {
            Pointer best = Word.nullPointer();
            long bestBytes = Long.MAX_VALUE;
            int bestSlot = -1;
            int slot = 0;
            while (slot < SIZE_CLASS_SLOTS) {
                long address = cachedAddress(candidateClass, slot);
                if (address == 0L) {
                    break;
                }
                Pointer candidate = Word.pointer(address);
                long header = blockHeader(candidate);
                if ((header & 1L) != 0L) {
                    removeCachedSlot(candidateClass, slot);
                    continue;
                }
                if ((header & ~7L) >= requiredOuter) {
                    long candidateBytes = header & ~7L;
                    if (candidateBytes < bestBytes) {
                        best = candidate;
                        bestBytes = candidateBytes;
                        bestSlot = slot;
                    }
                    slot++;
                    continue;
                }
                // Entries are largest-first; no later entry in this class can fit.
                break;
            }
            if (best.isNonNull()) {
                if (bestSlot > 0 && sizeClassBackupHits != Integer.MAX_VALUE) {
                    sizeClassBackupHits++;
                }
                return best;
            }
        }
        return Word.nullPointer();
    }

    /** Prefer a small fitting class entry; use the largest proof only as a fallback. */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    private static Pointer cachedFit(UnsignedWord padded) {
        long requiredOuter = padded.rawValue() + WasmAllocation.headerSize().rawValue();
        Pointer sizeClass = cachedSizeClassFit(requiredOuter);
        if (sizeClass.isNonNull()) {
            return sizeClass;
        }
        Pointer largest = largestFreeBlock();
        if (largest.isNonNull() && blockBytes(largest) >= requiredOuter) {
            return largest;
        }
        return Word.nullPointer();
    }

    /**
     * Search the complete free list and allocate from its largest fitting block.
     *
     * <p>An unbounded first-fit found a block, but often one only a few bytes larger
     * than the request. Its tiny split remainder then became the list head and the
     * very next allocation repeated the complete walk. A browser trace measured this
     * exact cycle 205,000 times before the title screen and continuously afterwards.
     * Selecting the largest fitting block makes one complete walk publish a large
     * active remainder which serves subsequent allocations through the normal bounded
     * path. The allocator lock already serializes this pointer walk.</p>
     *
     * <p>The largest-free pointer is a proof of *fit*, never a proof of *miss*. The
     * size-class cache holds four candidates per class; when those four are allocated
     * away, the class's remaining blocks stay in the linked list but drop out of the
     * cache, because an unregistration cannot promote an uncached block without a
     * walk. At probe scale the cache stays warm and the distinction never mattered.
     * At Minecraft's world-creation scale it is decisive: a wedged run measured
     * 884,582 free blocks whose true largest was 7,884 KiB while the cached proof
     * claimed 6 KiB, so 7 KiB registry allocations refused a fitting heap, paid a
     * stop-the-world collection each, and grew anyway. The cached block therefore
     * answers the hit path in O(1), but a refusal falls through to the walk - which
     * also re-registers what it sees, repairing the cache for the requests that
     * follow.</p>
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer deepSearch(UnsignedWord padded) {
        long requiredOuter = padded.rawValue() + WasmAllocation.headerSize().rawValue();

        /*
         * Once the largest cached block is known, its size answers both questions a
         * full walk was trying to answer: whether any block can fit and, if so, which
         * block leaves the largest useful remainder.  A bounded refill advances the
         * live-list cursor when cached candidates have been consumed.  Keep the
         * complete linked-list walk only as the final recovery path after that refill.
         */
        Pointer largestKnown = largestFreeBlock();
        if (largestKnown.isNull() || blockBytes(largestKnown) < requiredOuter) {
            refillGlobalCache(requiredOuter);
            largestKnown = largestFreeBlock();
        }
        if (largestKnown.isNonNull()) {
            long largestBytes = blockBytes(largestKnown);
            control(LAST_PROOF_KIB_OFFSET).writeInt(0, kib(largestBytes));
            if (largestBytes >= requiredOuter) {
                Pointer knownResult = WasmAllocation.allocateInKnownBlock(largestKnown, padded);
                if (knownResult.isNonNull()) {
                    increment(DEEP_HITS_OFFSET);
                    control(LAST_CANDIDATE_KIB_OFFSET).writeInt(0, kib(largestBytes));
                }
                return knownResult;
            }
            // The bounded refill found no candidate that raises the proof above the
            // request; only now is a complete list walk justified.
        }

        increment(DEEP_SEARCHES_OFFSET);
        increment(FULL_SCANS_OFFSET);
        long largestBytes = 0L;
        long largestObservedBytes = 0L;
        Pointer largest = Word.nullPointer();
        Pointer candidate = WasmAllocation.firstFreeBlock();
        int scanSteps = 0;
        while (candidate.isNonNull()) {
            // A fragmented heap can make this otherwise uninterruptible walk
            // traverse a large number of free blocks.  Keep the collector's
            // rendezvous reachable without polling every pointer operation.
            if ((scanSteps++ & 63) == 0) {
                McWebLMSafepoint.poll();
            }
            Pointer current = candidate;
            Pointer next = WasmAllocation.nextFreeBlock(current);
            long candidateBytes = blockBytes(current);
            if (candidateBytes > largestObservedBytes) {
                largestObservedBytes = candidateBytes;
            }
            registerScannedFreeBlock(current);
            if (candidateBytes >= requiredOuter && candidateBytes > largestBytes) {
                largest = current;
                largestBytes = candidateBytes;
            }
            candidate = next;
        }
        // Preserve the incremental cursor. The complete walk repairs the reservoir,
        // but the next refill must continue past the slice already inspected rather
        // than restarting at the same fragmented-list prefix.
        Pointer result = largest.isNull()
                ? Word.nullPointer()
                : WasmAllocation.allocateInKnownBlock(largest, padded);
        if (result.isNull() && largestObservedBytes >>> 10 < requestKey(padded)) {
            control(NO_FIT_MAX_KIB_OFFSET).writeInt(0, kib(largestObservedBytes));
            control(NO_FIT_COLLECTION_OFFSET).writeInt(0, collectionsRun());
            control(NO_FIT_TOPOLOGY_OFFSET).writeInt(0, control(TOPOLOGY_EPOCH_OFFSET).readInt(0));
        }
        if (result.isNonNull()) {
            increment(DEEP_HITS_OFFSET);
            control(LAST_CANDIDATE_KIB_OFFSET).writeInt(0, kib(largestBytes));
        }
        return result;
    }

    /** Chunked region bytes added during this image run, in MiB. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int grownMiB() {
        return control(GROWN_MIB_OFFSET).readInt(0);
    }

    /** Growth budget in MiB: the page-0 override if the host set one, else the default. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int growthBudgetMib() {
        int override = control(GROWTH_BUDGET_OFFSET).readInt(0);
        return override > 0 ? override : GROWTH_BUDGET_MIB;
    }

    /** Chunked region MiB added since the last collection that actually ran. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int grownSinceCollectionMiB() {
        return control(GROWN_SINCE_COLLECTION_OFFSET).readInt(0);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void addGrownMiB(int megabytes) {
        control(GROWN_SINCE_COLLECTION_OFFSET).writeInt(0, grownSinceCollectionMiB() + megabytes);
        control(GROWN_MIB_OFFSET).writeInt(0, grownMiB() + megabytes);
    }

    /** Grow exactly one request before paying an O(n) fragmented-list walk. */
    @Uninterruptible(reason = "Called from uninterruptible allocator paths.")
    private static Pointer tryDirectGrowth(UnsignedWord padded) {
        UnsignedWord chunk = padded.add(WasmAllocation.headerSize());
        increment(GROW_ATTEMPTS_OFFSET);
        Pointer grown = WasmAllocation.growAllocatorRegion(chunk);
        if (grown.isNull()) {
            increment(GROW_FAILURES_OFFSET);
            return Word.nullPointer();
        }
        /*
         * This is deliberately outside the chunk-growth budget. The budget is a
         * guard against committing another multi-gigabyte arena; exact fallback
         * grows only the request (normally one or a few Wasm pages) after that
         * budget is spent. Counting each sub-MiB request as a full MiB would make
         * the diagnostic budget lie and would disable this escape hatch after a
         * handful of ordinary worker allocations.
         */
        return WasmAllocation.allocateInKnownBlock(grown, padded);
    }

    /**
     * Run a collection on the primary, or publish one coalesced request when this
     * allocation is executing on an agent.  An agent must never enter WasmLM's
     * primary-stack collector itself.
     */
    @Uninterruptible(reason = "Allocator collection hand-off", calleeMustBe = false)
    private static boolean collectOrRequest() {
        if (McWebLMSafepoint.requestCollectionIfAgent()) {
            return false;
        }
        int ranBefore = collectionsRun();
        WasmLMGC.getGC().collect(WasmGCCause.OnAllocation);
        boolean ran = collectionsRun() != ranBefore;
        if (ran) {
            // Only a collection that really happened re-permits growth. Resetting this
            // on a *refused* collection is what previously let a 597 MiB live set walk
            // the region to a 3,955 MiB commitment.
            control(GROWN_SINCE_COLLECTION_OFFSET).writeInt(0, 0);
        }
        return ran;
    }

    /**
     * Collections that have actually run, from {@code McWebLMSafepoint}'s counters.
     *
     * <p>Read straight out of the shared control block rather than through the
     * safepoint's accessors, which are not uninterruptible and cannot be called from
     * the allocator.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int collectionsRun() {
        return control(STAT_STOPPED_OFFSET).readInt(0) + control(STAT_UNCONTENDED_OFFSET).readInt(0);
    }

    /** Used by older patched images; retained as a narrow diagnostic helper. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean shouldPrependReclaimed(Pointer candidate, Pointer head) {
        /*
         * A block header is its aligned outer size with the low three flag bits.
         * Keeping the largest free block first makes the common allocation a one-probe
         * split; that split remainder stays first until a still-larger block is swept.
         */
        long candidateBytes = blockBytes(candidate);
        long headBytes = blockBytes(head);
        control(LAST_HEAD_KIB_OFFSET).writeInt(0, kib(headBytes));
        control(LAST_CANDIDATE_KIB_OFFSET).writeInt(0, kib(candidateBytes));
        return candidateBytes > headBytes;
    }

    /**
     * Find where a reclaimed block belongs relative to the active split remainder.
     * The size-class cache owns fast selection; free-list insertion itself stays O(1).
     */
    @Uninterruptible(reason = "Called while the allocator owns the free-list lock.")
    public static Pointer rankedInsertionPredecessor(Pointer candidate, Pointer head) {
        long candidateBytes = blockBytes(candidate);
        long headBytes = blockBytes(head);
        control(LAST_HEAD_KIB_OFFSET).writeInt(0, kib(headBytes));
        control(LAST_CANDIDATE_KIB_OFFSET).writeInt(0, kib(candidateBytes));
        if (candidateBytes > headBytes) {
            return Word.nullPointer();
        }

        return head;
    }

    /**
     * Coalescing changes a block's size after it has already been linked into the
     * free list. Reinsert it into the ranked prefix so the bounded first-fit walk
     * cannot miss the newly-created contiguous space.
     */
    @Uninterruptible(reason = "Maintains allocator free-list invariants without allocation.")
    public static void repromoteCoalesced(Pointer block, Pointer head) {
        if (block.isNull() || head.isNull()) {
            return;
        }
        long blockBytes = blockBytes(block);
        long headBytes = blockBytes(head);
        control(LAST_HEAD_KIB_OFFSET).writeInt(0, kib(headBytes));
        control(LAST_CANDIDATE_KIB_OFFSET).writeInt(0, kib(blockBytes));
        if (!block.equal(head)) {
            WasmAllocation.repromoteFreeBlock(block);
        } else {
            registerFreeBlock(block);
        }
    }

    /**
     * Publish the no-progress context to the control page. Runs under the heap lock
     * at the exact refusal, so the free/heap sizes describe the moment the allocator
     * claimed nothing fits.
     */
    @Uninterruptible(reason = "Only reads allocator state and writes the raw control page", calleeMustBe = false)
    private static void recordNoProgressEvidence(UnsignedWord padded) {
        control(NO_PROGRESS_FREE_MIB_OFFSET).writeInt(0, kib(WasmAllocation.getFreeSize()) >>> 10);
        control(NO_PROGRESS_HEAP_MIB_OFFSET).writeInt(0, kib(WasmAllocation.getHeapSize()) >>> 10);
        control(NO_PROGRESS_REQUEST_KIB_OFFSET).writeInt(0, requestKey(padded));
        control(NO_PROGRESS_PROOF_KIB_OFFSET).writeInt(0, control(LAST_PROOF_KIB_OFFSET).readInt(0));
        long largestBytes = 0L;
        int blocks = 0;
        Pointer candidate = WasmAllocation.firstFreeBlock();
        int steps = 0;
        while (candidate.isNonNull()) {
            if ((steps++ & 63) == 0) {
                McWebLMSafepoint.poll();
            }
            blocks++;
            long candidateBytes = blockBytes(candidate);
            if (candidateBytes > largestBytes) {
                largestBytes = candidateBytes;
            }
            candidate = WasmAllocation.nextFreeBlock(candidate);
        }
        control(NO_PROGRESS_TRUTH_KIB_OFFSET).writeInt(0, kib(largestBytes));
        control(NO_PROGRESS_FREE_BLOCKS_OFFSET).writeInt(0, blocks);
    }

    @Uninterruptible(reason = "Called from uninterruptible allocator paths.")
    private static long blockHeader(Pointer block) {
        return ((UnsignedWord) block.readWord(0)).rawValue();
    }

    @Uninterruptible(reason = "Called from uninterruptible allocator paths.")
    private static long blockBytes(Pointer block) {
        return blockHeader(block) & ~7L;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void recordAllocationFailure(UnsignedWord padded) {
        increment(OOM_COUNT_OFFSET);
        control(OOM_REQUEST_KIB_OFFSET).writeInt(0, requestKey(padded));
        control(OOM_AGENT_OFFSET).writeInt(0, McWebLMHeapLock.agentIdOrPrimary());
        control(OOM_SEARCH_STATE_OFFSET).writeInt(0, control(FAILED_SEARCH_STATE_OFFSET).readInt(0));
        control(OOM_GROWN_MIB_OFFSET).writeInt(0, grownMiB());
    }

    /**
     * Replacement body for {@code WasmAllocation.doMallocUnlocked}. Runs under
     * {@link McWebLMHeapLock}, which the patcher wraps around {@code doMalloc}.
     *
     * <p>A bounded first-fit is a latency guarantee, not a hint that the allocator may
     * start an unbounded search on a browser frame. A miss therefore proceeds directly
     * to the bounded growth/collection policy. The free-list insertion patch keeps the
     * largest reclaimed block near the head so normal allocations still succeed in a
     * small number of probes.
     */
    @Uninterruptible(reason = "Modifies allocator state", calleeMustBe = false)
    @RestrictHeapAccess(access = Access.NO_ALLOCATION, reason = "Must not allocate in the implementation of allocation.")
    public static Pointer doMalloc(UnsignedWord numBytes) {
        if (numBytes.equal(0)) {
            return Word.nullPointer();
        }
        UnsignedWord padded = UnsignedUtils.max(WasmAllocation.minInnerSize(), numBytes);
        recordRequest(padded);

        Pointer cached = cachedFit(padded);
        if (cached.isNonNull()) {
            Pointer hit = WasmAllocation.allocateInKnownBlock(cached, padded);
            if (hit.isNonNull()) {
                if (sizeClassHits != Integer.MAX_VALUE) {
                    sizeClassHits++;
                }
                clearFailedRequest();
                return hit;
            }
        }
        if (sizeClassMisses != Integer.MAX_VALUE) {
            sizeClassMisses++;
        }

        Pointer ptr = WasmAllocation.allocateInExistingBlocks(padded);
        if (ptr.isNonNull()) {
            clearFailedRequest();
            return ptr;
        }

        recordBoundedMiss();

        boolean growthBudgetReached = grownMiB() >= growthBudgetMib();
        boolean pressureFallback = growthBudgetReached
                || WasmAllocation.getObjectPercentage() >= COLLECT_ABOVE_PERCENT;
        boolean largeRequest = requestKey(padded) >= DIRECT_GROW_REQUEST_KIB;

        /*
         * A request can be smaller than total free space yet larger than every
         * block in the bounded prefix. Under high occupancy or after the chunk
         * budget, growing a large request exactly is cheaper and more useful than
         * walking hundreds of thousands of free-list nodes. If memory.grow is
         * unavailable, continue through the existing deep-search/collection fallback.
         */
        if (pressureFallback && largeRequest) {
            ptr = tryDirectGrowth(padded);
            if (ptr.isNonNull()) {
                clearFailedRequest();
                return ptr;
            }
        }

        /*
         * A complete walk is a last-resort reuse operation, not part of normal
         * allocation. While the lifetime growth budget has headroom, publish a fresh
         * active remainder instead. Once the budget is exhausted, search the tail once
         * before asking the collector to stop every Java thread.
         */
        if (growthBudgetReached && !deepSearchAlreadyKnown(padded)) {
            ptr = deepSearch(padded);
            if (ptr.isNonNull()) {
                clearFailedRequest();
                return ptr;
            }
            rememberDeepSearchMiss(padded);
        }

        // No block was found in the bounded prefix. Grow while there is headroom,
        // unless the live set is past the threshold or this run has already consumed its
        // chunk-growth budget. Near the ceiling the order is important: do one
        // deep free-list search before collecting. A fitting block may be thousands of
        // nodes behind the bounded prefix; collecting first turns that harmless miss
        // into a stop-the-world pause for every large worldgen allocation.
        boolean collectFirst = WasmAllocation.getObjectPercentage() >= COLLECT_ABOVE_PERCENT
                        || growthBudgetReached
                        || (McWebLMSafepoint.collectionRequested()
                                && McWebLMHeapLock.agentIdOrPrimary() != 0);
        if (!collectFirst) {
            increment(GROW_ATTEMPTS_OFFSET);
            UnsignedWord chunk = UnsignedUtils.max(
                            Word.unsigned(GROWTH_CHUNK_BYTES), padded.add(WasmAllocation.headerSize()));
            Pointer grown = WasmAllocation.growAllocatorRegion(chunk);
            if (grown.isNonNull()) {
                addGrownMiB((int) (chunk.rawValue() >>> 20));
                // The just-grown block is a known fitting block. Allocate from it
                // directly instead of putting it through the bounded free-list walk
                // (which can otherwise miss it after another thread publishes a
                // fragment and immediately trigger a deep scan for the same request).
                ptr = WasmAllocation.allocateInKnownBlock(grown, padded);
                if (ptr.isNonNull()) {
                    clearFailedRequest();
                    return ptr;
                }
            } else {
                increment(GROW_FAILURES_OFFSET);
                // A failed grow is primary work waiting to happen, not a reason for
                // every agent allocation to repeat the same impossible memory.grow.
                McWebLMSafepoint.requestCollectionIfAgent();
            }
        }

        // A bounded miss near the memory ceiling gets exactly one complete free-list
        // search before GC when only the live-set threshold forced the decision. This
        // is the liveness-critical ordering: current terrain loads have fitting blocks
        // in the tail of the list, and the old order ran a full collection even when
        // reclamation was unnecessary.
        if (!deepSearchAlreadyKnown(padded)) {
            ptr = deepSearch(padded);
            if (ptr.isNonNull()) {
                clearFailedRequest();
                return ptr;
            }
            rememberDeepSearchMiss(padded);
        }

        /*
         * A refused collection at the ceiling used to be retried for every
         * allocation, which is the GC storm seen during Loading Terrain. One
         * request gets one collection attempt until either the collection epoch
         * or the free-list topology changes. We still perform the deep search
         * below on every miss, so a coalesced block can satisfy the request.
         */
        if (!sameFailedRequest(padded)) {
            boolean collectionRan = collectOrRequest();
            rememberFailedRequest(padded);
            if (collectionRan) {
                if (!deepSearchAlreadyKnown(padded)) {
                    ptr = deepSearch(padded);
                    if (ptr.isNonNull()) {
                        clearFailedRequest();
                        return ptr;
                    }
                    // This is the meaningful no-progress event: a collection
                    // actually ran, but the largest-free proof still found no fit.
                    increment(NO_PROGRESS_OFFSET);
                    recordNoProgressEvidence(padded);
                    rememberDeepSearchMiss(padded);
                }
            } else {
                // An agent only publishes a request for the primary.  Repeating the
                // same full-list search before that request is serviced is pure
                // browser-thread work; mark the phase complete and grow/fail once.
                rememberDeepSearchMiss(padded);
            }
        }

        /*
         * The post-GC deep miss is the last useful search while the collection
         * epoch and topology are unchanged.  Permit one growth fallback, then
         * return null on subsequent identical requests so callers receive a
         * deterministic out-of-memory failure instead of spinning forever.
         */
        if (sameFailedRequest(padded)
                        && control(FAILED_SEARCH_STATE_OFFSET).readInt(0) == 4) {
            recordAllocationFailure(padded);
            return Word.nullPointer();
        }
        increment(GROW_ATTEMPTS_OFFSET);
        ptr = WasmAllocation.growMalloc(padded);
        if (ptr.isNull()) {
            increment(GROW_FAILURES_OFFSET);
            if (sameFailedRequest(padded)) {
                control(FAILED_SEARCH_STATE_OFFSET).writeInt(0, 4);
            }
            recordAllocationFailure(padded);
        } else {
            clearFailedRequest();
        }
        return ptr;
    }
}
