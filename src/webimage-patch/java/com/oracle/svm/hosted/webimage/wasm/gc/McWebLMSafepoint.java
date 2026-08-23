/*
 * MC-Web builder patch: a stop-the-world safepoint so the WasmLM collector can run while
 * other agents hold live Java frames.
 *
 * `WasmLMGC` scans exactly one shadow stack - the collecting agent's - because upstream
 * has one thread. With agents, collecting without stopping them frees objects that are
 * live in their frames (observed as `ArrayStoreException` and a wasm trap). The first
 * version of this patch therefore refused such collections, which is safe but useless:
 * the allocator grows the heap instead and a long threaded run ends in OOM.
 *
 * This class makes those collections possible:
 *
 *   - agents poll at the points where they are already spinning (the allocator lock, a
 *     contended monitor) and while blocked in a host call, and park with their stack
 *     pointer published;
 *   - the primary requests a stop-the-world, waits for every agent that is inside Java to
 *     park, collects, then resumes them;
 *   - during marking, each parked agent's stack is walked from its published SP to its
 *     registered stack base, with the collector's own frame visitor.
 *
 * A collection is skipped if some agent does not reach a poll in time - an agent in a long
 * allocation-free compute loop, since there is no safepoint on loop back-edges yet.
 * Skipping is always safe. What is *not* safe is collecting with an unparked agent, and
 * that cannot happen: the request is withdrawn before the collection starts.
 *
 * The rendezvous is a *latch*, not a count of a flag. Counting was a race - an agent could
 * be counted as parked and then resume into the collection it had just been counted for -
 * so the collector moves each parked agent `PARKED_FREE -> LATCHED` with a
 * compare-and-swap, counts only the ones it won, and marks only from latched stacks. The
 * agent's resume competes for the same word, so the two cannot both succeed.
 *
 * Control block (raw memory below `MemoryLayout.HEAP_BASE`, alongside McWebLMHeapLock's):
 *   offset 280            stop-the-world request
 *   offset 400 + 16*agent {state (running/parked/latched), published SP, -, -}
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.AlwaysInline;
import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.NeverInline;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.snippets.KnownIntrinsics;
import com.oracle.svm.hosted.webimage.wasm.stack.WebImageWasmStackFrameVisitor;
import com.oracle.svm.hosted.webimage.wasm.stack.WebImageWasmStackWalk;
import com.oracle.svm.hosted.webimage.wasm.stack.WebImageWasmStackWalker;
import com.oracle.svm.webimage.threads.McWebLMThreads;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.CurrentIsolate;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMSafepoint {

    private static final int REQUEST_OFFSET = 280;
    /** Page-0 diagnostic flag; non-zero suppresses collections for short B5/B6 runs. */
    private static final int GC_DISABLED_OFFSET = 88;
    /**
     * An agent cannot safely run the collector: its stack is not the primary stack and
     * the primary may still hold live roots.  Allocation pressure is therefore handed
     * back to the primary through this coalescing request word.  The browser/client
     * thread consumes it at Mojang-owned wait and frame boundaries.
     */
    private static final int PRIMARY_GC_REQUEST_OFFSET = 332;
    private static final int STATE_OFFSET = 400;
    private static final int ENTRY_GATE_OFFSET = 308;
    private static final int STATE_BYTES = 16;
    private static final int PARKED = 0;
    private static final int PUBLISHED_SP = 4;

    /**
     * Values of the {@link #PARKED} word.
     *
     * <p>{@link #LATCHED} exists because "parked" and "may resume" are different states,
     * and conflating them is a race: the old blocking exit path checked the stop request
     * and cleared its flag as two steps, so a collector could publish a request, count the
     * agent as parked, and start marking in between — after which the agent returned to
     * Java and ran against a live mark. Measured at 45 such windows in 377 collections by
     * the scheduler probe, so this is a real interleaving and not a theoretical one.
     *
     * <p>The collector now moves each parked agent {@code PARKED_FREE -> LATCHED} with a
     * compare-and-swap and only counts the ones it won. An agent leaving the parked state
     * uses the *same* word, so exactly one of the two succeeds: either the agent gets out
     * before the collector sees it (and is not counted), or it is latched and must wait.
     */
    private static final int RUNNING = 0;
    private static final int PARKED_FREE = 1;
    private static final int LATCHED = 2;

    /**
     * How long the primary waits for agents to park, in spin iterations.
     *
     * <p>Deliberately small. Skipping a collection is safe - {@link McWebLMHeapPolicy}
     * grows instead - but the *wait* is not free, and there is no safepoint on loop
     * back-edges yet, so an agent inside a long allocation-free loop (inflating a
     * resource, for instance) cannot rendezvous however long the collector waits. A
     * two-agent Minecraft boot measured 397 skipped collections against 1 that ran; at
     * the previous budget of 20,000,000 that was about eight billion spin iterations of
     * the browser thread spent achieving nothing. Retrying cheaply and often beats
     * waiting expensively and rarely.
     */
    private static final int PARK_SPIN_BUDGET = 100_000;

    /** Cap on the read-only backoff between rendezvous rounds, in reads. */
    private static final int RENDEZVOUS_MAX_PAUSE = 256;

    /** Diagnostics: collections that stopped agents, that needed no stop, and that were skipped. */
    private static final int STAT_STOPPED = 284;
    private static final int STAT_UNCONTENDED = 288;
    private static final int STAT_SKIPPED = 292;
    /** Collections refused because an agent, not the primary, asked for one. */
    private static final int STAT_AGENT_REFUSED = 296;
    private static final int STAT_MAX_PARKED = 300;
    /**
     * Times an agent's resume was held back because the collector had latched it.
     *
     * <p>This is the protocol working, not a fault: each one is an interleaving that
     * would previously have let the agent run Java against an in-flight mark. It stays
     * exported because a build where it drops to zero while collections still happen has
     * probably lost the latch.
     */
    private static final int STAT_LATCH_WAITS = 336;

    /**
     * Which agents were still {@link #RUNNING} when a rendezvous timed out, as a bitmask
     * with bit {@code agent - 1} per agent.
     *
     * <p>{@link #STAT_SKIPPED} says a collection was skipped but not by whom, and that
     * gap has cost two wrong diagnoses already: "skipped" was read first as the monitor
     * table and then as unrooted operand-stack values at the injected worldgen polls,
     * and both were disproved only after the fact. The blocker is directly observable at
     * the moment the budget runs out — record it there rather than inferring it later.
     *
     * <p>{@code ANY} accumulates every blocker ever seen; {@code LAST} keeps only the
     * most recent timeout. The pair separates "one agent never parks" from "a different
     * agent each time", which have unrelated causes: the first is a specific
     * allocation-free loop, the second is simply a budget that is too small.
     */
    private static final int STAT_SKIP_BLOCKERS_ANY = 328;
    private static final int STAT_SKIP_BLOCKERS_LAST = 764;

    private McWebLMSafepoint() {
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer at(int address) {
        Pointer pointer = Word.pointer(address);
        return pointer;
    }

    /**
     * Atomic read. A plain load in a spin loop can be kept in a register by the engine,
     * which would make an agent spin forever; a compare-and-swap against its own value
     * reads atomically and writes nothing.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static int atomicRead(Pointer pointer, int offset) {
        return pointer.compareAndSwapInt(offset, 0, 0, LocationIdentity.ANY_LOCATION);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer state(int agent) {
        return at(STATE_OFFSET + STATE_BYTES * agent);
    }

    /**
     * True while a stop-the-world is pending, read atomically. Used by the spin loops
     * that wait for the request to clear, where a load the engine may keep in a register
     * would spin forever.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean stopRequested() {
        return atomicRead(at(REQUEST_OFFSET), 0) != 0;
    }

    /** True while one or more agents need the primary to collect. */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean collectionRequested() {
        return atomicRead(at(PRIMARY_GC_REQUEST_OFFSET), 0) != 0;
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static boolean collectionDisabled() {
        return at(GC_DISABLED_OFFSET).readInt(0) != 0;
    }

    @WasmExport(value = "mcweb.gc.disabled", comment = "Diagnostic GC suppression flag")
    public static int gcDisabledExport() {
        return collectionDisabled() ? 1 : 0;
    }

    /**
     * Coalesce an agent-side collection attempt into one primary-side request.
     *
     * <p>Returns {@code true} when the caller is an agent, whether this call published
     * the request or found one already pending.  The refusal counter is incremented
     * only for the transition from no request to a pending request; counting every
     * allocator miss produced hundreds of thousands of identical diagnostics without
     * creating any additional opportunity for the primary to collect.</p>
     */
    @Uninterruptible(reason = "Only reads and writes the raw control block", calleeMustBe = false)
    public static boolean requestCollectionIfAgent() {
        if (McWebLMHeapLock.agentIdOrPrimary() == 0) {
            return false;
        }
        Pointer request = at(PRIMARY_GC_REQUEST_OFFSET);
        if (request.compareAndSwapInt(0, 0, 1, LocationIdentity.ANY_LOCATION) == 0) {
            bump(STAT_AGENT_REFUSED);
        }
        return true;
    }

    /**
     * Poll: called from places an agent is already spinning or blocking, and - through the
     * patched {@code WasmHeap.exitIfAllocationDisallowed} - before every allocation.
     *
     * <p>Both reads here are deliberately plain loads, not {@link #atomicRead}'s
     * compare-and-swap. A locked read-modify-write on every single allocation showed up
     * as ~12% of the browser thread in a CPU profile, and the ordering it buys is not
     * needed: this is not a spin loop, every call is a fresh one with allocator stores
     * either side, and missing one request only defers this agent's rendezvous to its
     * next allocation. The confirming read of the request, once a stop is seen, is the
     * atomic one inside {@link #park}.
     */
    @Uninterruptible(reason = "Poll from the allocator and monitor spin loops", calleeMustBe = false, mayBeInlined = true)
    @AlwaysInline("the no-request poll is on every Java allocation")
    @WasmExport(value = "mcweb.safepoint.poll", comment = "Poll the WasmLM stop-the-world rendezvous")
    public static void poll() {
        if (McWebLMHeapLock.agentCount() != 0 && at(REQUEST_OFFSET).readInt(0) != 0) {
            park();
        }
    }

    /**
     * Publishes this agent's stack pointer and waits for the collection to finish. The SP
     * is this frame's, not the caller's, so every frame that could hold a reference is
     * inside the walked range. This method remains active while it waits, so its frame
     * is a valid starting frame for the stack walker.
     */
    @NeverInline("publishes this frame's stack pointer")
    @Uninterruptible(reason = "Parked frames must not move", calleeMustBe = false)
    public static void park() {
        int agent = McWebLMHeapLock.agentIdOrPrimary();
        if (agent == 0) {
            // The primary is the only collector, so it never parks for itself.
            return;
        }
        // Everything from here to `resume` returning is time this agent is stopped for
        // somebody else's collection, i.e. the GC pause as the AGENT experiences it.
        long parkStarted = McWebLMTiming.start();
        Pointer self = state(agent);
        self.writeInt(PUBLISHED_SP, (int) KnownIntrinsics.readStackPointer().rawValue());
        self.compareAndSwapInt(PARKED, RUNNING, PARKED_FREE, LocationIdentity.ANY_LOCATION);
        /*
         * Actually wait here. This loop is what makes the rendezvous a rendezvous.
         *
         * Without it, `park` published PARKED_FREE and immediately called `resume`,
         * whose first compare-and-swap takes the agent straight back to RUNNING. The
         * agent was therefore parked for about three instructions, and the collector had
         * to win a compare-and-swap inside that window to count it. It almost never did:
         * a four-agent world load measured `maxParked=1` against `runningAgents=3` and
         * `gcSkipped 128 -> 211` while every agent was polling on every allocation. More
         * polling could not fix that — each poll only offered another microscopic window.
         *
         * Holding the parked state open while the request stands gives the collector a
         * window bounded by the collection instead of by three instructions. The two exit
         * conditions are the only ones that can occur: the request clears (the collection
         * was withdrawn, so resume immediately), or this agent is no longer PARKED_FREE
         * because the collector latched it (and then `resume` waits out the collection on
         * the same word). Neither can hang: `endCollection` unlatches before it withdraws
         * the request, which is why that order is load-bearing.
         */
        while (stopRequested() && atomicRead(self, PARKED) == PARKED_FREE) {
            // spin: stay visible to the collector's latch
        }
        resume(self);
        McWebLMTiming.account(McWebLMTiming.CAT_PARKED, parkStarted);
    }

    /**
     * Leaves the parked state, waiting out any collection that has latched this agent.
     *
     * <p>The compare-and-swap is the whole handshake: it competes with the collector's
     * {@code PARKED_FREE -> LATCHED} on the same word, so an agent either gets out before
     * the collector counts it or does not get out at all.
     */
    @Uninterruptible(reason = "Parked frames must not move", calleeMustBe = false)
    private static void resume(Pointer self) {
        boolean waited = false;
        for (;;) {
            int witness = self.compareAndSwapInt(PARKED, PARKED_FREE, RUNNING, LocationIdentity.ANY_LOCATION);
            if (witness == PARKED_FREE) {
                if (waited) {
                    bump(STAT_LATCH_WAITS);
                }
                return;
            }
            if (witness == RUNNING) {
                /*
                 * Nothing to leave. This should be unreachable - both callers set
                 * PARKED_FREE immediately beforehand - but the alternative to returning
                 * is spinning forever on a condition no other thread is going to change,
                 * and an unreachable state is a bad reason to hang an agent.
                 */
                return;
            }
            // Latched: the collector may be marking from this stack right now.
            waited = true;
            while (stopRequested() || atomicRead(self, PARKED) == LATCHED) {
                // spin: endCollection unlatches, then clears the request
            }
        }
    }

    /**
     * Marks this agent as safepoint-parked for the duration of a *host* block -
     * {@code LockSupport.park}, which suspends the agent inside {@code Atomics.wait}
     * where it can reach no poll of its own.
     *
     * <p>Without this, an idle thread is the worst possible state for the collector:
     * {@link McWebLMHeapLock#enterJava} still counts it as running Java, but it never
     * parks, so {@link #beginCollection} exhausts its whole spin budget and skips every
     * collection while the allocator grows the heap to the memory ceiling instead. A
     * pool of worker threads waiting for work - which is what Minecraft's background
     * executors are most of the time - made that the normal case.
     *
     * <p>Publishing the stack pointer is what makes this sound rather than merely
     * convenient: the blocked thread's frames stay live and reachable, and the
     * collector walks them from here to the agent's registered stack base exactly as it
     * walks an agent parked at a poll. The method below keeps the publishing frame
     * active around the host call, so the published pointer is never a popped frame.
     */
    @NeverInline("keeps the published stack frame live across the host block")
    public static void parkBlocking(long address, boolean absolute, long time) {
        int agent = McWebLMHeapLock.agentIdOrPrimary();
        if (agent == 0) {
            // The browser thread cannot block, so the primary never really parks.
            return;
        }
        Pointer self = state(agent);
        self.writeInt(PUBLISHED_SP, (int) KnownIntrinsics.readStackPointer().rawValue());
        self.compareAndSwapInt(PARKED, RUNNING, PARKED_FREE, LocationIdentity.ANY_LOCATION);
        try {
            McWebLMThreads.park0(address, absolute ? 1 : 0, time);
        } finally {
            // The frame published above is still active here. If the collector latched
            // it, resume waits until the mark has finished before Java continues.
            resume(self);
        }
    }

    /**
     * Enters Java without racing an uncontended collection. The gate closes the
     * interval between the collector observing zero running agents and setting the
     * request: either this agent is counted before that snapshot, or it waits until
     * the collection has cleared the request.
     */
    @Uninterruptible(reason = "Agent entry rendezvous", calleeMustBe = false)
    public static void enterJava() {
        Pointer gate = at(ENTRY_GATE_OFFSET);
        for (;;) {
            while (gate.compareAndSwapInt(0, 0, 1, LocationIdentity.ANY_LOCATION) != 0) {
                // another agent or the collector owns the entry gate
            }
            if (!stopRequested()) {
                McWebLMHeapLock.enterJava();
                gate.compareAndSwapInt(0, 1, 0, LocationIdentity.ANY_LOCATION);
                return;
            }
            gate.compareAndSwapInt(0, 1, 0, LocationIdentity.ANY_LOCATION);
            while (stopRequested()) {
                // The agent has no Java frames to publish yet.
            }
        }
    }

    /**
     * Called before a collection. Returns false when the collection must be skipped: the
     * caller is not the primary, or an agent failed to park in time.
     *
     * <p>The primary check was documented here but never written, and its absence is
     * expensive twice over. An agent that reaches a collection can never satisfy the
     * rendezvous — it is running Java by definition and cannot park itself — so it
     * publishes a stop request, spins the whole {@link #PARK_SPIN_BUDGET}, stops every
     * other agent for the duration, and then withdraws. During Minecraft's reload the
     * agent does nearly all of the allocating, which is why a run measured 140 skipped
     * collections against 2 that ran, and why the region walked to the memory ceiling:
     * the agent's collect always failed, so the allocator always grew instead.
     *
     * <p>Refusing immediately is also the only safe answer. {@code
     * McWebLMHeapLock.collectionAllowed} spells out why: the collector scans the
     * caller's shadow stack, so an agent collecting would miss every root in the
     * primary's frames. Leaving collection to the primary is not a throughput choice.
     */
    @Uninterruptible(reason = "Only reads and writes the raw control block; the agents it waits for are parked", calleeMustBe = false)
    public static boolean beginCollection() {
        if (collectionDisabled()) {
            // Do not leave an agent-to-primary pressure request latched while the
            // diagnostic arm is active. This arm is intentionally allowed to grow or
            // fail; it must not turn the normal request bit into a false hang signal.
            if (McWebLMHeapLock.agentIdOrPrimary() == 0) {
                at(PRIMARY_GC_REQUEST_OFFSET).compareAndSwapInt(
                                0, 1, 0, LocationIdentity.ANY_LOCATION);
            }
            return false;
        }
        if (requestCollectionIfAgent()) {
            return false;
        }
        Pointer gate = at(ENTRY_GATE_OFFSET);
        while (gate.compareAndSwapInt(0, 0, 1, LocationIdentity.ANY_LOCATION) != 0) {
            // close agent entry before publishing the request
        }
        at(REQUEST_OFFSET).compareAndSwapInt(0, 0, 1, LocationIdentity.ANY_LOCATION);
        int initiallyRunning = McWebLMHeapLock.runningAgents();
        gate.compareAndSwapInt(0, 1, 0, LocationIdentity.ANY_LOCATION);
        if (initiallyRunning <= 0) {
            // Latch even here. "No agent is running Java" is read from a counter an agent
            // only joins inside `run`, and an agent that is merely attaching allocates -
            // so it can be parked at a poll, with real frames, while uncounted.
            latchParkedAgents();
            bump(STAT_UNCONTENDED);
            McWebLMTiming.gcBegin();
            return true;
        }
        for (int spins = 0; spins < PARK_SPIN_BUDGET; spins++) {
            // Re-read the target. A worker can terminate between the request and
            // reaching its next poll (for example after an allocation failure).
            // Waiting for the stale entry count makes every later collection time
            // out even though all agents that still have live frames are parked.
            int running = McWebLMHeapLock.runningAgents();
            // Latching is the count: an agent this wins is one that cannot leave, so
            // unlike a plain read the number cannot be stale by the time it is used.
            int latched = latchParkedAgents();
            updateMax(STAT_MAX_PARKED, latched);
            if (latched >= running) {
                bump(STAT_STOPPED);
                McWebLMTiming.gcBegin();
                return true;
            }
            // Give the agents that still have to park uncontended access to their own
            // state words before sweeping them again.
            rendezvousPause(spins);
        }
        // Somebody is not polling; withdraw and let the allocator grow the heap instead.
        // Name them first: this is the only moment the blocking agent is identifiable.
        recordSkipBlockers();
        bump(STAT_SKIPPED);
        endCollection();
        return false;
    }

    /**
     * Releases the agents and withdraws the request, <em>in that order</em>. An agent
     * waiting to resume spins on "still requested, or still latched"; clearing the
     * request first would let it observe both as false only after a second pass, and an
     * agent that saw the request clear while still latched would spin on a condition
     * nobody was going to change.
     */
    @Uninterruptible(reason = "Only writes the raw control block", calleeMustBe = false)
    public static void endCollection() {
        // Pairs with gcBegin on the two successful returns of beginCollection. The
        // skip path calls this too, where gcStartedAt is 0 and account() drops it.
        McWebLMTiming.gcEnd();
        /*
         * Clear the hand-off while every participating agent is still latched.  If it
         * were cleared after releasing them, an agent could publish new pressure in
         * between and have that new request erased by this collection's cleanup.
         */
        at(PRIMARY_GC_REQUEST_OFFSET).compareAndSwapInt(
                        0, 1, 0, LocationIdentity.ANY_LOCATION);
        int count = McWebLMHeapLock.agentCount();
        for (int agent = 1; agent <= count; agent++) {
            state(agent).compareAndSwapInt(PARKED, LATCHED, PARKED_FREE, LocationIdentity.ANY_LOCATION);
        }
        at(REQUEST_OFFSET).compareAndSwapInt(0, 1, 0, LocationIdentity.ANY_LOCATION);
    }

    /**
     * Moves every free-parked agent into {@link #LATCHED} and returns how many are now
     * latched. Idempotent: the rendezvous calls it repeatedly while it waits.
     */
    /**
     * Records the agents that failed to reach a poll, at the instant the rendezvous gave
     * up. Bit {@code agent - 1} is set for every agent still {@link #RUNNING}.
     */
    @Uninterruptible(reason = "Only reads and writes the raw control block", calleeMustBe = false)
    private static void recordSkipBlockers() {
        int blockers = 0;
        int count = McWebLMHeapLock.agentCount();
        if (count > 31) {
            count = 31;
        }
        for (int agent = 1; agent <= count; agent++) {
            // Only registered agents can block a rendezvous. An agent slot that was
            // configured but never attached has an all-zero state word, and zero *is*
            // RUNNING — so the first version of this counter reported every unattached
            // slot as a blocker on every timeout. That artefact is not harmless: it made
            // the mask read as "a different subset each time, therefore livelock" when
            // the real signal was two specific agents. A registered agent has published
            // a stack high-water mark; an unattached slot has not.
            if (McWebLMHeapLock.agentStackHigh(agent) != 0
                            && atomicRead(state(agent), PARKED) == RUNNING) {
                blockers |= 1 << (agent - 1);
            }
        }
        at(STAT_SKIP_BLOCKERS_LAST).writeInt(0, blockers);
        Pointer any = at(STAT_SKIP_BLOCKERS_ANY);
        for (;;) {
            int observed = atomicRead(any, 0);
            int merged = observed | blockers;
            if (merged == observed
                            || any.compareAndSwapInt(0, observed, merged, LocationIdentity.ANY_LOCATION) == observed) {
                return;
            }
        }
    }

    /**
     * Test-and-test-and-set, not a bare compare-and-swap sweep.
     *
     * <p>The rendezvous calls this up to {@link #PARK_SPIN_BUDGET} times, and the old
     * version issued an unconditional read-modify-write against *every* agent's state
     * word on every one of those iterations. That is a cache line each agent must own to
     * park: {@link #park} compare-and-swaps the same word, so the collector's polling was
     * competing with — and starving — the very agents it was waiting for. {@link
     * McWebLMHeapLock#lock} already documents this exact effect on the allocator lock
     * ("a dozen agents hammering one cache line with atomic read-modify-writes starves
     * whoever is unlucky", measured at 74.9% of browser-thread samples), and the
     * rendezvous had the same shape.
     *
     * <p>The evidence that this, and not a stuck agent, is what fails: the blocker mask
     * recorded at timeout showed a *different* subset every time and eventually every
     * agent ({@code any=[1,2,3,4] last=[1,2,4]}). One agent in an allocation-free loop
     * cannot produce that; a livelock in which whoever loses the line race is left
     * unparked can.
     *
     * <p>A plain read keeps the line shared, so only a genuine {@code PARKED_FREE ->
     * LATCHED} transition costs an exclusive acquire. Reading {@link #LATCHED} without an
     * atomic is sound because the collector is the only writer of that value and it is
     * the caller here.
     */
    @Uninterruptible(reason = "Only reads and writes the raw control block", calleeMustBe = false)
    private static int latchParkedAgents() {
        int latched = 0;
        int count = McWebLMHeapLock.agentCount();
        for (int agent = 1; agent <= count; agent++) {
            Pointer self = state(agent);
            int observed = self.readInt(PARKED);
            if (observed == PARKED_FREE) {
                observed = self.compareAndSwapInt(PARKED, PARKED_FREE, LATCHED, LocationIdentity.ANY_LOCATION);
                observed = observed == PARKED_FREE ? LATCHED : self.readInt(PARKED);
            }
            if (observed == LATCHED) {
                latched++;
            }
        }
        return latched;
    }

    /**
     * Read-only delay between rendezvous rounds, so agents get uncontended access to the
     * state words they must write to park. Reads the request word so the loop cannot be
     * optimised away, and touches nothing an agent needs exclusively.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void rendezvousPause(int round) {
        int spins = round < RENDEZVOUS_MAX_PAUSE ? round : RENDEZVOUS_MAX_PAUSE;
        Pointer request = at(REQUEST_OFFSET);
        int observed = 0;
        for (int i = 0; i < spins; i++) {
            observed += request.readInt(0);
        }
        if (observed == Integer.MIN_VALUE) {
            // Never true; keeps the accumulator live so the delay is not elided.
            at(STAT_SKIP_BLOCKERS_LAST).writeInt(0, observed);
        }
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void bump(int offset) {
        Pointer counter = at(offset);
        int observed;
        do {
            observed = atomicRead(counter, 0);
        } while (counter.compareAndSwapInt(0, observed, observed + 1, LocationIdentity.ANY_LOCATION) != observed);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static void updateMax(int offset, int value) {
        Pointer counter = at(offset);
        int observed = atomicRead(counter, 0);
        while (value > observed) {
            int witness = counter.compareAndSwapInt(0, observed, value, LocationIdentity.ANY_LOCATION);
            if (witness == observed) {
                return;
            }
            observed = witness;
        }
    }

    /** Collections that stopped running agents. */
    public static int stoppedCollections() {
        return atomicRead(at(STAT_STOPPED), 0);
    }

    /** Collections that needed no stop, because no agent was inside Java. */
    public static int uncontendedCollections() {
        return atomicRead(at(STAT_UNCONTENDED), 0);
    }


    public static int maxParkedAgents() {
        return atomicRead(at(STAT_MAX_PARKED), 0);
    }
    /** Collections skipped because an agent did not reach a safepoint in time. */
    public static int skippedCollections() {
        return atomicRead(at(STAT_SKIPPED), 0);
    }

    /** Collections refused outright because an agent asked for one. */
    public static int agentRefusedCollections() {
        return atomicRead(at(STAT_AGENT_REFUSED), 0);
    }

    /** Resumes the collector's latch held back; see {@link #STAT_LATCH_WAITS}. */
    public static int latchWaits() {
        return atomicRead(at(STAT_LATCH_WAITS), 0);
    }

    @Uninterruptible(reason = "Only reads the raw control block", calleeMustBe = false)
    static int parkedAgentsCount() {
        return parkedAgents();
    }

    @Uninterruptible(reason = "Only reads the raw control block", calleeMustBe = false)
    private static int parkedAgents() {
        int parked = 0;
        int count = McWebLMHeapLock.agentCount();
        for (int agent = 1; agent <= count; agent++) {
            if (atomicRead(state(agent), PARKED) != RUNNING) {
                parked++;
            }
        }
        return parked;
    }

    /**
     * Marks the roots in every parked agent's frames. Called from `WasmLMGC` with its own
     * frame visitor, so the promotion logic is exactly the one used for the collector's
     * own stack.
     */
    public static void walkParkedAgents(WebImageWasmStackFrameVisitor visitor) {
        int count = McWebLMHeapLock.agentCount();
        for (int agent = 1; agent <= count; agent++) {
            Pointer self = state(agent);
            // Only latched agents. A free-parked one may leave at any moment, and its
            // frames would then be walked while it was changing them.
            if (atomicRead(self, PARKED) != LATCHED) {
                continue;
            }
            int sp = self.readInt(PUBLISHED_SP);
            int stackBase = McWebLMHeapLock.agentStackHigh(agent);
            if (sp == 0 || stackBase == 0) {
                continue;
            }
            walkFrames(Word.pointer(sp), Word.pointer(stackBase), visitor);
        }
    }

    /**
     * `WebImageWasmStackWalker.walkCurrentThread` hard-codes `MemoryLayout.getStackBase()`
     * as the end of the walk, which is the primary's stack. An agent's stack is a malloc'd
     * region elsewhere, so the walk is set up here with that agent's own bounds and then
     * driven with the public `continueWalk`.
     */
    private static void walkFrames(Pointer startSP, Pointer endSP, WebImageWasmStackFrameVisitor visitor) {
        WebImageWasmStackWalk walk = StackValue.get(WebImageWasmStackWalk.class);
        walk.setSP(startSP);
        walk.setIP(FrameAccess.singleton().readReturnAddress(CurrentIsolate.getCurrentThread(), startSP));
        walk.setEndSP(endSP);
        while (walk.getIP().isNonNull() && visitor.visitFrame(walk.getSP(), walk.getIP())) {
            if (!WebImageWasmStackWalker.continueWalk(walk, CurrentIsolate.getCurrentThread())) {
                break;
            }
        }
    }
}
