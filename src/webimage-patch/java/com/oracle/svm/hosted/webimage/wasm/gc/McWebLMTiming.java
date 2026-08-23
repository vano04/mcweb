/*
 * MC-Web builder patch: top-down wall-clock accounting, per agent.
 *
 * WHY THIS EXISTS, given that a CPU profile is the obvious instrument.
 *
 * On this lane a CPU profile is not obtainable cheaply and has never once looked at the
 * thread that matters:
 *
 *   - Worldgen runs on the integrated server thread, which on the threaded lane is an
 *     AGENT, i.e. a dedicated Worker. Every profiling harness in this repo attaches CDP
 *     to the *page*, so all of them sample the browser thread and none of them sample
 *     worldgen.
 *   - Playwright's `newCDPSession` only accepts a Page or Frame, and reaching worker
 *     targets through `--remote-debugging-port` did not survive contact: the image did
 *     not reach its first frame in 12 minutes against 7 seconds without the flag.
 *   - The image carries no wasm name section, so a profile is a list of
 *     `wasm-function[12345]` and needs `tools/wasm-symbols.mjs` to mean anything.
 *
 * Top-down accounting answers the actual question without any of that, on any thread, in
 * any lane. The question is not "which Java method is hot" -- it is:
 *
 *   of the wall time the server thread spends, how much is the RUNTIME SUBSTRATE
 *   (waiting for the heap lock, allocating under it, parked at a safepoint, collecting)
 *   and how much is real Java compute?
 *
 * Those two answers have opposite fixes, and the whole 16-37x per-chunk gap against the
 * WasmGC lane hangs on which one it is. A sampling profile would answer it too, less
 * reliably (sampling bias, no denominator) and at far greater cost.
 *
 * DESIGN
 *
 * Only SLOW paths are instrumented. `McWebLMClock.nanoTime` is a seqlock read of shared
 * memory -- cheap next to a lock acquisition or a park, ruinous on the TLAB carve fast
 * path. So the fast path is deliberately uninstrumented and shows up as the REMAINDER,
 * which is exactly the quantity of interest.
 *
 * Counters are per agent, so no two threads write the same cache line and no atomics are
 * needed; plain 64-bit adds. Slot 0 is the primary, 1..15 are agents, matching
 * McWebLMHeapLock.agentIdOrPrimary().
 *
 * The block is malloc'd and its address published at page-0 offset 112 -- the same
 * "allocating is the only way to be sure" pattern McWebLMTlab and McWebLMMonitors use.
 * 0..119 is free (the lowest fixed user is McWebLMTlab's control pointer at 120).
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMTiming {

    /** Page-0 slot holding the address of the malloc'd timing block; 0 = not yet made. */
    private static final int CONTROL_POINTER_OFFSET = 112;

    /**
     * Enable flag, written by the host before Java starts (`?mcweb_timing=1`).
     *
     * <p>Off by default, and that is not caution — it is a measured requirement. The
     * first version of this class had no flag and instrumented unconditionally, and the
     * image then failed to reach its first frame in 10 minutes against 7 seconds. The
     * cause is {@link McWebLMClock#nanoTime}: it falls back to
     * {@code JSFunctionIntrinsics.performanceNow()} whenever the shared clock publisher
     * is not running yet, which is exactly the case throughout boot — and a wasm->JS
     * crossing per lock acquisition is the very cost McWebLMClock exists to remove (it
     * measured 45.3% of the game thread before that patch).
     *
     * <p>So this class reads {@link McWebLMClock#sharedNanos} directly and never the
     * fallback, AND stays off unless asked for. With it off the cost is one load.
     */
    private static final int ENABLED_OFFSET = 116;

    private static final int AGENTS = 16;

    /** Categories. Each is one 64-bit nanosecond accumulator plus one 64-bit count. */
    public static final int CAT_LOCK_WAIT = 0;
    public static final int CAT_LOCKED_ALLOC = 1;
    public static final int CAT_REFILL = 2;
    public static final int CAT_PARKED = 3;
    public static final int CAT_GC = 4;
    private static final int CATEGORIES = 5;

    private static final int BYTES_PER_CATEGORY = 16;
    private static final int BYTES_PER_AGENT = CATEGORIES * BYTES_PER_CATEGORY;
    /** Per-agent wall-clock window: first-touch nanos, then last-touch nanos. */
    private static final int WALL_BASE = AGENTS * BYTES_PER_AGENT;
    private static final int WALL_BYTES_PER_AGENT = 16;
    private static final int CONTROL_BLOCK_BYTES = WALL_BASE + AGENTS * WALL_BYTES_PER_AGENT;

    private McWebLMTiming() {
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static long block() {
        Pointer slot = Word.pointer(CONTROL_POINTER_OFFSET);
        return slot.readInt(0) & 0xffff_ffffL;
    }

    /**
     * Allocates the timing block. Must run under {@link McWebLMHeapLock}: the malloc
     * re-enters the reentrant lock, and two agents racing must end up with one block.
     *
     * <p>Returns 0 when it cannot be allocated, and every caller treats that as "no
     * timing", so a failure degrades to no measurement rather than to a bad pointer.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean enabled() {
        Pointer flag = Word.pointer(ENABLED_OFFSET);
        return flag.readInt(0) != 0;
    }

    @Uninterruptible(reason = "Runs under the allocator lock", calleeMustBe = false)
    public static long ensureBlock() {
        if (!enabled()) {
            return 0;
        }
        long base = block();
        if (base != 0) {
            return base;
        }
        Pointer allocated = WasmAllocation.malloc(Word.unsigned(CONTROL_BLOCK_BYTES));
        if (allocated.isNull()) {
            return 0;
        }
        base = allocated.rawValue();
        Pointer zero = Word.pointer(base);
        for (int offset = 0; offset < CONTROL_BLOCK_BYTES; offset += 4) {
            zero.writeInt(offset, 0);
        }
        Pointer slot = Word.pointer(CONTROL_POINTER_OFFSET);
        slot.compareAndSwapInt(0, 0, (int) base, LocationIdentity.ANY_LOCATION);
        return base;
    }

    /**
     * Reads the shared clock. Returns 0 when the timing block does not exist yet, which
     * makes {@link #account} a no-op — so instrumentation costs one load before the
     * block is created and never allocates on its own.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("disabled timing must not add a call frame to allocation")
    public static long start() {
        if (block() == 0) {
            return 0L;
        }
        // sharedNanos, NOT nanoTime: nanoTime falls back to a wasm->JS performance.now()
        // crossing when no publisher is running, and putting one of those on the lock
        // path is what made the instrumented image unbootable. A zero here means the
        // shared clock is not up yet, and the sample is simply dropped.
        return McWebLMClock.sharedNanos();
    }

    /**
     * Whether the opt-in allocation-size histogram may record a sample. This is a
     * plain control-page read and remains false for normal launches, so the diagnostic
     * histogram does not add a counter update or a clock read to the production path.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("allocation-size diagnostics are opt-in")
    public static boolean allocationHistogramEnabled() {
        return enabled();
    }

    /**
     * Adds the elapsed time since {@code startedAt} to one category for this agent.
     *
     * <p>Plain 64-bit adds: the slot belongs to one agent, so nothing else writes it.
     * A zero {@code startedAt} means the block did not exist when the region began and
     * the sample is dropped rather than recorded as an enormous interval.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    @AlwaysInline("disabled timing must not add a call frame to allocation")
    public static void account(int category, long startedAt) {
        if (startedAt == 0) {
            return;
        }
        long base = block();
        if (base == 0) {
            return;
        }
        int agent = McWebLMHeapLock.agentIdOrPrimary();
        if (agent < 0 || agent >= AGENTS) {
            return;
        }
        long now = McWebLMClock.sharedNanos();
        if (now == 0) {
            return;
        }
        long elapsed = now - startedAt;
        if (elapsed < 0) {
            elapsed = 0;
        }
        Pointer entry = Word.pointer(base + agent * BYTES_PER_AGENT + category * BYTES_PER_CATEGORY);
        entry.writeLong(0, entry.readLong(0) + elapsed);
        entry.writeLong(8, entry.readLong(8) + 1);
        // Wall window: first touch fixes the start, every touch moves the end, so the
        // denominator is this agent's own observed lifetime rather than the page's.
        Pointer wall = Word.pointer(base + WALL_BASE + agent * WALL_BYTES_PER_AGENT);
        if (wall.readLong(0) == 0) {
            wall.writeLong(0, startedAt);
        }
        wall.writeLong(8, now);
    }

    /**
     * Start of the primary's current collection. A plain static is right here: WasmLM
     * statics are private to each Wasm instance, only the primary ever collects, and
     * {@code beginCollection}/{@code endCollection} run on that one instance.
     */
    private static long gcStartedAt;

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void gcBegin() {
        gcStartedAt = start();
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static void gcEnd() {
        account(CAT_GC, gcStartedAt);
        gcStartedAt = 0;
    }

    /* ------------------------------------------------------------------ exports */

    /** {@code byteOffset} is 0 for the nanosecond accumulator, 8 for the count. */
    private static long readCategory(int agent, int category, int byteOffset) {
        long base = block();
        if (base == 0 || agent < 0 || agent >= AGENTS) {
            return 0;
        }
        Pointer entry = Word.pointer(base + agent * BYTES_PER_AGENT + category * BYTES_PER_CATEGORY);
        return entry.readLong(byteOffset);
    }

    /** Milliseconds this agent spent in a category. Agent 0 is the primary. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.time.categoryMs", comment = "Milliseconds an agent spent in a timing category")
    public static int categoryMs(int agent, int category) {
        return (int) (readCategory(agent, category, 0) / 1_000_000L);
    }

    /** How many times this agent entered a category. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.time.categoryCount", comment = "Times an agent entered a timing category")
    public static int categoryCount(int agent, int category) {
        long count = readCategory(agent, category, 8);
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /**
     * Milliseconds between this agent's first and last instrumented event — the
     * denominator every category should be read against.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
            value = "mcweb.time.wallMs", comment = "Observed wall window for an agent, in ms")
    public static int wallMs(int agent) {
        long base = block();
        if (base == 0 || agent < 0 || agent >= AGENTS) {
            return 0;
        }
        Pointer wall = Word.pointer(base + WALL_BASE + agent * WALL_BYTES_PER_AGENT);
        long first = wall.readLong(0);
        long last = wall.readLong(8);
        return first == 0 || last <= first ? 0 : (int) ((last - first) / 1_000_000L);
    }
}
