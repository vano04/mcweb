/*
 * MC-Web builder patch: make the WasmLM allocator safe for more than one agent.
 *
 * `WasmAllocation` keeps one global free list and one bump pointer with no lock at all,
 * so two agents allocating at the same time corrupt the block headers. This class adds
 * a reentrant spin lock around the allocator's entry points (see the WasmAllocation
 * rewrite in tools/webimage-patch/McWebImagePatcher.java) built on the real
 * compare-and-swap the atomics patch now emits.
 *
 * It also gates collection. `WasmLMGC` scans exactly one shadow stack - the collecting
 * agent's - so collecting while another agent runs Java would free objects that are
 * live in that agent's frames. Until a stop-the-world protocol publishes every agent's
 * stack (see docs/WASMLM-THREADS.md), collection is only allowed when no other agent is
 * inside Java. Skipping a collection is safe: the allocator falls back to growing the
 * heap and reports out of memory if it cannot.
 *
 * State lives in raw linear memory below `MemoryLayout.HEAP_BASE` (page 0, which the
 * image heap never uses) rather than in Java statics, so every access is a plain word
 * read or write: no allocation, no GC interaction, usable from @Uninterruptible code.
 *
 *   offset 128  lock word (0 = free, else owner agent id + 1)
 *   offset 132  reentrancy depth
 *   offset 136  number of agents currently executing Java (excluding the primary)
 *   offset 140  registered agent count
 *   offset 144  agent table: {stackLow, stackHigh} pairs, 8 bytes each
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.hosted.webimage.wasm.nodes.WasmTrapNode;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMHeapLock {

    private static final int LOCK_OFFSET = 128;
    private static final int DEPTH_OFFSET = 132;
    private static final int RUNNING_AGENTS_OFFSET = 136;
    private static final int AGENT_COUNT_OFFSET = 140;
    private static final int AGENT_TABLE_OFFSET = 144;
    /** Reserved low control-page words for the primary's fast identity range. */
    private static final int PRIMARY_STACK_LOW_OFFSET = 80;
    private static final int PRIMARY_STACK_HIGH_OFFSET = 84;
    /** Must match the browser and probe hosts' aligned carrier stack reservation. */
    private static final long AGENT_STACK_BYTES = 1L << 20;
    /** Marker at an aligned agent stack low address; the following word is its id. */
    private static final int AGENT_STACK_MARKER = 0x4d434147; // "MCAG"
    /** Safepoint statistics use 300; keep this flag outside that block. */
    private static final int PRIMARY_WAITING_OFFSET = 704;
    private static final int MAX_AGENTS = 16;
    private static final long INITIAL_ALLOCATOR_BYTES = 512L * 1024L * 1024L;
    /** Failed acquisitions an agent spins through before it starts backing off. */
    private static final int AGENT_SPINS_BEFORE_BACKOFF = 8;
    /** Upper bound on the read-only delay, so backoff cannot become a stall. */
    private static final int MAX_BACKOFF_SPINS = 4096;

    private McWebLMHeapLock() {
    }

    /**
     * Starts full images with a useful allocator arena. Upstream starts with one 64 KiB
     * page, then runs a whole-heap collection before every subsequent growth. That is
     * reasonable for tiny images, but Minecraft's bootstrap has more than 256 MiB of
     * transient allocation and over one million image-heap objects, so its first
     * collection takes minutes.
     *
     * <p>512 MiB, inside the threaded host's 4 GiB memory maximum
     * ({@link com.oracle.svm.hosted.webimage.wasm.ast.visitors.McWebAtomicVisitors#MAX_MEMORY_PAGES}).
     * WebAssembly commits pages on first use, so this costs address space, not memory.
     * A revision that cut it to 64 MiB - on the theory that {@link McWebLMHeapPolicy}
     * had removed the need - stalled the unshared boot inside the Minecraft constructor,
     * before the resource reload emitted a single task: a small arena means the first
     * fit misses constantly, and the growth that follows is not free.
     *
     * <p>The previous 1.5 GiB reserve left only 512 MiB of headroom under the ceiling,
     * and threaded runs reached 2004-2031 MiB with a live set of only 659 MiB. Reaching
     * the wall made a skipped collection fatal because the fallback had nowhere to grow.
     * Starting at 512 MiB leaves room for collection and lets the policy decide when
     * another bounded chunk is actually needed.
     *
     * <p>The reserve is halved until it fits rather than falling straight back to one
     * page: a probe that deliberately caps linear memory low (the GC stress probe runs
     * in 16 MiB) still gets the largest arena its ceiling allows, and a ceiling that is
     * merely *smaller* than the reserve can no longer silently reintroduce upstream's
     * collect-before-every-growth behaviour. A failed {@code memory.grow} is side-effect
     * free, so trying and losing costs nothing.
     */
    @Uninterruptible(reason = "Runs while the module is constructed", callerMustBe = true)
    public static void initializeAllocator() {
        McWebLMHeapPolicy.initializeDiagnostics();
        Pointer base = MemoryLayout.getAllocatorBase();
        Pointer end = MemoryLayout.getAllocatorTop();
        VMError.guarantee(base == end, "Allocator must start out completely empty");
        publishPrimaryStackRange();
        long reserve = INITIAL_ALLOCATOR_BYTES;
        long pageSize = MemoryLayout.pageSize().rawValue();
        while (reserve >= pageSize) {
            if (WasmAllocation.growAllocatorRegion(Word.unsigned(reserve)).isNonNull()) {
                return;
            }
            reserve /= 2;
        }
        WasmTrapNode.trap();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer control(int offset) {
        return Word.pointer(offset);
    }

    /**
     * Publish a cheap identity range for the primary instance. The primary's Wasm
     * stack is not in the agent table, so this avoids making every primary allocation
     * scan all attached carrier ranges merely to discover that it is the primary.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void publishPrimaryStackRange() {
        long sp = KnownIntrinsics.readStackPointer().rawValue();
        long low = sp & ~(AGENT_STACK_BYTES - 1L);
        control(PRIMARY_STACK_LOW_OFFSET).writeInt(0, (int) low);
        control(PRIMARY_STACK_HIGH_OFFSET).writeInt(0, (int) (low + AGENT_STACK_BYTES));
        Pointer marker = Word.pointer(low);
        marker.writeInt(0, AGENT_STACK_MARKER);
        marker.writeInt(4, 0);
        McWebLMThreadLocals.publishPrimaryMarkerHolders(marker);
    }

    /** Returns an identity from an aligned stack marker, or -1 for the slow path. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int fastAgentId(long sp) {
        long low = sp & ~(AGENT_STACK_BYTES - 1L);
        Pointer marker = Word.pointer(low);
        if (marker.readInt(0) == AGENT_STACK_MARKER) {
            int id = marker.readInt(4);
            if (id > 0 && id <= MAX_AGENTS) {
                return id;
            }
        }
        long primaryLow = control(PRIMARY_STACK_LOW_OFFSET).readInt(0) & 0xffff_ffffL;
        long primaryHigh = control(PRIMARY_STACK_HIGH_OFFSET).readInt(0) & 0xffff_ffffL;
        if (primaryLow != 0 && sp > primaryLow && sp <= primaryHigh) {
            return 0;
        }
        return -1;
    }

    /**
     * Registers an agent's shadow-stack region. The host calls this once per agent, on
     * that agent's own thread, right after it has set its stack pointer.
     */
    @WasmExport(value = "mcweb.agent.attach", comment = "Register an agent's shadow stack region")
    public static int attachAgent(long stackLow, long stackHigh) {
        // Agents attach from their own threads, concurrently, so the slot has to be
        // reserved atomically: two agents sharing an id would share a lock owner and
        // defeat the whole lock.
        Pointer count = control(AGENT_COUNT_OFFSET);
        int slot;
        do {
            slot = count.readInt(0);
            if (slot >= MAX_AGENTS) {
                return -1;
            }
        } while (count.compareAndSwapInt(0, slot, slot + 1, org.graalvm.word.LocationIdentity.ANY_LOCATION) != slot);
        control(AGENT_TABLE_OFFSET + 8 * slot).writeInt(0, (int) stackLow);
        control(AGENT_TABLE_OFFSET + 8 * slot).writeInt(4, (int) stackHigh);
        if ((stackLow & (AGENT_STACK_BYTES - 1L)) == 0 && stackHigh - stackLow == AGENT_STACK_BYTES) {
            Pointer marker = Word.pointer(stackLow);
            marker.writeInt(0, AGENT_STACK_MARKER);
            marker.writeInt(4, slot + 1);
        }
        // From here on this agent has an identity, so give it its own VM thread-locals.
        McWebLMThreadLocals.attach(slot + 1, stackLow);
        return slot + 1;
    }

    /**
     * {@link #agentId()} without the stack-pointer read and table scan while no agent has
     * attached. Every VM thread-local access goes through this (see
     * {@link McWebLMThreadLocals#objectHolder}), which on a booting Minecraft image is
     * hot enough to be measurable in a CPU profile, and the unshared image never has an
     * agent at all.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("allocator lock ownership is on every lock and unlock slow path")
    public static int agentIdOrPrimary() {
        if (control(AGENT_COUNT_OFFSET).readInt(0) == 0) {
            return 0;
        }
        int fast = fastAgentId(KnownIntrinsics.readStackPointer().rawValue());
        return fast >= 0 ? fast : agentIdSlow();
    }

    /**
     * Identity of the agent running this code: the index of the registered stack region
     * containing the current stack pointer, or 0 for the primary instance (whose stack
     * is the image's own and is never registered).
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static int agentId() {
        long sp = KnownIntrinsics.readStackPointer().rawValue();
        int fast = fastAgentId(sp);
        if (fast >= 0) {
            return fast;
        }
        return agentIdSlow(sp);
    }

    /**
     * Identity for the TLAB allocation path. The marker is the common case after carrier
     * attach, so keep that case in the caller rather than paying a direct-call frame for
     * every small object. The table scan remains the correctness fallback for probe hosts
     * that attach an unaligned or otherwise non-marker carrier range.
     */
    @AlwaysInline("TLAB allocation must not call the carrier identity slow path")
    @Uninterruptible(reason = "Called from the TLAB allocation fast path", mayBeInlined = true)
    public static int agentIdForTlab() {
        if (agentCount() == 0) {
            return 0;
        }
        long sp = KnownIntrinsics.readStackPointer().rawValue();
        int fast = fastAgentId(sp);
        return fast >= 0 ? fast : agentIdSlow(sp);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int agentIdSlow() {
        return agentIdSlow(KnownIntrinsics.readStackPointer().rawValue());
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int agentIdSlow(long sp) {
        int count = control(AGENT_COUNT_OFFSET).readInt(0);
        for (int i = 0; i < count; i++) {
            Pointer entry = control(AGENT_TABLE_OFFSET + 8 * i);
            long low = entry.readInt(0) & 0xffffffffL;
            long high = entry.readInt(4) & 0xffffffffL;
            if (sp > low && sp <= high) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Reentrant acquire with primary-thread priority.
     *
     * <p>Test-and-test-and-set, not a bare compare-and-swap loop. Every Java allocation
     * on every thread passes through here, and a dozen agents hammering one cache line
     * with atomic read-modify-writes starves whoever is unlucky — measured as 74.9% of
     * the browser thread's samples sitting in this method while the agents drained
     * Minecraft's reload queue. Reading the word until it looks free keeps the line
     * shared, so only a genuine hand-off costs an exclusive acquire.
     *
     * <p>Agents additionally back off aggressively whenever the primary thread is
     * waiting for the lock, so main-thread UI rendering and click handling stay responsive.
     */
    @Uninterruptible(reason = "Guards allocator state", calleeMustBe = false)
    public static void lock() {
        int me = agentIdOrPrimary() + 1;
        Pointer word = control(LOCK_OFFSET);
        if (word.readInt(0) == me) {
            control(DEPTH_OFFSET).writeInt(0, control(DEPTH_OFFSET).readInt(0) + 1);
            return;
        }
        /*
         * Allocation is the safepoint. This poll is the one that makes the
         * stop-the-world rendezvous reachable at all.
         *
         * Every Java `new` passes through here, and until this call existed the *only*
         * polls on this path were inside the retry loop below — reached exclusively when
         * an acquisition **fails**. A thread that wins the lock on its first attempt,
         * which is the overwhelmingly common case at one or two threads, therefore
         * allocated indefinitely without ever offering to park. The old comment
         * ("spinning for the allocator is a safepoint") was true and insufficient: a
         * thread that never spins never polls.
         *
         * Measured consequence before the fix, one agent (the Server thread) against a
         * collecting primary: `gcSkipped 843` versus `gcStopped 76`, the heap pinned at
         * the `GROWTH_BUDGET_MIB` cap, every later allocation taking a full free-list
         * walk, and in-world frames at 2.03 FPS with `javaMsPerFrame` 486. It also
         * explains the otherwise backwards observation that runs with *more* agents
         * collected more successfully: more contention meant more failed acquisitions,
         * and so more polls.
         *
         * Placement matters. It is after the reentrancy check, so a thread that already
         * owns the allocator lock cannot park inside it and strand the collector, and
         * before acquisition, so the caller holds nothing when it parks. This mirrors a
         * real VM, where the allocation slow path is a safepoint and a thread that
         * allocates can never miss a rendezvous.
         */
        McWebLMSafepoint.poll();
        // Everything from here to acquisition is time this thread is not doing work.
        // Only the SLOW path is timed: a reentrant re-acquire returned above without
        // reading the clock, and the TLAB carve fast path never reaches this method.
        long waitStarted = McWebLMTiming.start();
        boolean primary = me == 1;
        if (primary) {
            control(PRIMARY_WAITING_OFFSET).writeInt(0, 1);
        }
        int failures = 0;
        for (;;) {
            if (!primary && control(PRIMARY_WAITING_OFFSET).readInt(0) == 1) {
                McWebLMSafepoint.poll();
                failures++;
                pause(failures > 64 ? failures : 64);
                continue;
            }
            if (word.readInt(0) == 0
                            && word.compareAndSwapInt(0, 0, me, org.graalvm.word.LocationIdentity.ANY_LOCATION) == 0) {
                if (primary) {
                    control(PRIMARY_WAITING_OFFSET).writeInt(0, 0);
                }
                McWebLMTiming.account(McWebLMTiming.CAT_LOCK_WAIT, waitStarted);
                break;
            }
            // Spinning for the allocator is a safepoint: the primary may be waiting for
            // this agent to park so it can collect.
            McWebLMSafepoint.poll();
            if (!primary && ++failures > AGENT_SPINS_BEFORE_BACKOFF) {
                pause(failures);
            }
        }
        control(DEPTH_OFFSET).writeInt(0, 1);
    }

    /**
     * Read-only delay, growing with the number of failed acquisitions and capped. Reads
     * the lock word so the compiler cannot elide the loop, and touches nothing else.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void pause(int failures) {
        int spins = failures < MAX_BACKOFF_SPINS ? failures : MAX_BACKOFF_SPINS;
        Pointer word = control(LOCK_OFFSET);
        int observed = 0;
        for (int i = 0; i < spins; i++) {
            observed += word.readInt(0);
        }
        if (observed == Integer.MIN_VALUE) {
            // Never true; keeps the accumulator live so the delay is not optimised away.
            control(DEPTH_OFFSET).writeInt(4, observed);
        }
    }

    @Uninterruptible(reason = "Guards allocator state", calleeMustBe = false)
    public static void unlock() {
        Pointer depth = control(DEPTH_OFFSET);
        int remaining = depth.readInt(0) - 1;
        depth.writeInt(0, remaining);
        if (remaining <= 0) {
            if (agentIdOrPrimary() == 0) {
                control(PRIMARY_WAITING_OFFSET).writeInt(0, 0);
            }
            control(LOCK_OFFSET).compareAndSwapInt(0, control(LOCK_OFFSET).readInt(0), 0, org.graalvm.word.LocationIdentity.ANY_LOCATION);
        }
    }


    /** Marks entry into Java on an agent thread; see {@link #collectionAllowed}. */
    @Uninterruptible(reason = "Called at the agent entry point", calleeMustBe = false)
    public static void enterJava() {
        Pointer running = control(RUNNING_AGENTS_OFFSET);
        int observed;
        do {
            observed = running.readInt(0);
        } while (running.compareAndSwapInt(0, observed, observed + 1, org.graalvm.word.LocationIdentity.ANY_LOCATION) != observed);
    }

    @Uninterruptible(reason = "Called at the agent entry point", calleeMustBe = false)
    public static void exitJava() {
        Pointer running = control(RUNNING_AGENTS_OFFSET);
        int observed;
        do {
            observed = running.readInt(0);
        } while (running.compareAndSwapInt(0, observed, observed - 1, org.graalvm.word.LocationIdentity.ANY_LOCATION) != observed);
    }

    /**
     * Collection is only safe when the primary instance is the one collecting and no
     * agent is inside Java.
     *
     * The collector scans exactly one shadow stack - the caller's. An agent that
     * collects while the primary sits in `Thread.join()` would miss every root in the
     * primary's frames and free live objects; that was observed as an
     * ArrayStoreException and a trap before this guard existed. The primary is only
     * quiescent-safe when no agent is running Java either.
     */
    @Uninterruptible(reason = "Queried from the collector", calleeMustBe = false)
    public static boolean collectionAllowed() {
        int running = control(RUNNING_AGENTS_OFFSET).readInt(0);
        return agentIdOrPrimary() == 0 && running == 0;
    }

    /** Exposed for diagnostics: agents currently parked at a safepoint. */
    @WasmExport(value = "mcweb.heap.parkedAgents", comment = "Agents currently parked at a safepoint")
    public static int parkedAgentsExport() {
        return McWebLMSafepoint.parkedAgentsCount();
    }

    @WasmExport(value = "mcweb.gc.stopped", comment = "Collections that stopped running agents")
    public static int stoppedCollectionsExport() {
        return McWebLMSafepoint.stoppedCollections();
    }

    @WasmExport(value = "mcweb.gc.uncontended", comment = "Collections that needed no stop")
    public static int uncontendedCollectionsExport() {
        return McWebLMSafepoint.uncontendedCollections();
    }

    @WasmExport(value = "mcweb.gc.skipped", comment = "Collections skipped: an agent missed the safepoint")
    public static int skippedCollectionsExport() {
        return McWebLMSafepoint.skippedCollections();
    }

    @WasmExport(value = "mcweb.gc.agentRefused", comment = "Collections refused: an agent asked, not the primary")
    public static int agentRefusedCollectionsExport() {
        return McWebLMSafepoint.agentRefusedCollections();
    }

    @WasmExport(value = "mcweb.gc.requested", comment = "Agent allocation pressure awaiting the primary collector")
    public static int collectionRequestedExport() {
        return McWebLMSafepoint.collectionRequested() ? 1 : 0;
    }


    @WasmExport(value = "mcweb.gc.maxParked", comment = "Maximum agents parked during collection rendezvous")
    public static int maxParkedAgentsExport() {
        return McWebLMSafepoint.maxParkedAgents();
    }

    @WasmExport(value = "mcweb.gc.latchWaits", comment = "Resumes the collector's latch held back")
    public static int latchWaitsExport() {
        return McWebLMSafepoint.latchWaits();
    }


    @WasmExport(value = "mcweb.heap.runningAgents", comment = "Agents currently executing Java")
    public static int runningAgents() {
        return control(RUNNING_AGENTS_OFFSET).readInt(0);
    }

    /** Number of registered agents; slot ids run 1..count. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static int agentCount() {
        return control(AGENT_COUNT_OFFSET).readInt(0);
    }

    /** Stack base (high address) of a registered agent, for bounding a stack walk. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    static int agentStackHigh(int agent) {
        return control(AGENT_TABLE_OFFSET + 8 * (agent - 1)).readInt(4);
    }
}
