/*
 * MC-Web builder patch: real monitors (`synchronized`) for the WasmLM backend.
 *
 * Upstream deletes them. `WebImageWasmLMHighTier` appends `RemoveMonitorPhase`, which
 * strips every `MonitorEnterNode`/`MonitorExitNode` from the graph, and
 * `WebImageSingleThreadedMonitorSupport` implements `MonitorSupport` with empty methods.
 * SVM's own lowering (`MonitorFeature` -> `MonitorSnippets`) is
 * `@Platforms(InternalPlatform.NATIVE_ONLY.class)`, so it never runs here: on Web Image
 * `synchronized` simply does not exist.
 *
 * This class supplies both halves:
 *
 *   - `rewriteMonitors` replaces the monitor nodes with foreign calls to the two targets
 *     below, exactly the way `WasmLMSingleThreadedAtomicsPhase` replaced a CAS with a
 *     call to `SingleThreadedAtomics`;
 *   - `enter`/`exit`/`heldByCurrent`/`waitOn` implement the lock itself, over a fixed
 *     open-addressed table in unmanaged memory keyed by object address.
 *
 * Keying on the address is sound because `WasmLMGC` is a non-moving mark-sweep
 * collector: an object never changes address.
 *
 * Ownership is the shared-heap address of the logical Java Thread object. A carrier is
 * reused by many Thread objects, so a physical agent id would incorrectly transfer
 * reentrant ownership from one logical thread to the next.
 *
 * ## Entries are reclaimed, and why that is the whole point
 *
 * The first version of this table claimed an entry per locked address and never released
 * it. A long-lived image therefore walked into a permanently full table, and everything
 * after that point fell through to one global lock. That is not a throughput footnote,
 * it is two correctness failures:
 *
 *   - the fallback was not depth-counted, so `synchronized (a) { synchronized (b) {} }`
 *     released the lock at the *inner* exit and left the outer block unprotected; and
 *   - `waitOn` found no entry and returned without releasing anything, so a thread
 *     inside `Object.wait` held the one lock the whole image needed.
 *
 * The second one is a hard deadlock, and every `Thread.join()` in the JDK has its exact
 * shape - `synchronized (thread) { while (isAlive()) wait(...) }` on a freshly allocated
 * `Thread`, which is precisely the object a full table has no room for. Measured with
 * `tools/wasmlm-probes/monitor-saturation-harness.mjs`: after 4,096 entries are claimed,
 * a join wedges with `monitorFallbackOwner=1` (the primary) and every agent spinning.
 *
 * Minecraft reaches that state early and unavoidably: `ConcurrentHashMap.putVal`
 * synchronizes on the *bin head node*, a distinct object per colliding bin, so the table
 * fills with objects nothing will ever lock again.
 *
 * So an entry now lives exactly as long as the monitor is held. Steady-state occupancy is
 * the number of monitors held *at this instant* - threads times nesting depth, tens - not
 * the number ever locked. `mcweb.monitor.peakEntries` reports the high-water mark so that
 * claim is measured rather than assumed.
 *
 * ## Why the structural changes take a lock
 *
 * Reclaiming makes open addressing subtle: a probe that stops at a free slot can miss an
 * entry placed beyond it, and two threads claiming concurrently can create *two* entries
 * for one address - which silently unmakes mutual exclusion, the one thing this class
 * exists to provide. Claiming and freeing therefore happen under a per-bucket spin lock,
 * while the common path (an entry that already exists) stays a lock-free scan plus one
 * compare-and-swap on the owner word. Probing never leaves its bucket, so the bucket lock
 * covers exactly the slots a probe can see.
 *
 * There is deliberately no global fallback. A bucket overflow is a bounded runtime
 * failure, surfaced by `mcweb.monitor.fallbacks`, rather than an ownership violation
 * hidden behind a lock that cannot distinguish objects.
 *
 * Layout, in unmanaged memory:
 *   bucket = {lock:4, live:4, peak:4, watermark:4} followed by SLOTS entries of
 *   {address:4, owner:4, depth:4, -:4}
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import java.util.List;

import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.snippets.SnippetRuntime.SubstrateForeignCallDescriptor;
import com.oracle.svm.core.snippets.SubstrateForeignCallTarget;
import com.oracle.svm.hosted.webimage.options.WebImageOptions;
import com.oracle.svm.webimage.threads.McWebLMThreads;

import jdk.graal.compiler.core.common.spi.ForeignCallDescriptor.CallSideEffect;
import jdk.graal.compiler.core.common.type.StampFactory;
import jdk.graal.compiler.nodes.StructuredGraph;
import jdk.graal.compiler.nodes.ValueNode;
import jdk.graal.compiler.nodes.extended.ForeignCallNode;
import jdk.graal.compiler.nodes.java.MonitorEnterNode;
import jdk.graal.compiler.nodes.java.MonitorExitNode;
import jdk.graal.compiler.word.Word;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.UnsignedWord;

public final class McWebLMMonitors {

    public static final SubstrateForeignCallDescriptor MONITOR_ENTER = SnippetRuntime.findForeignCall(
                    McWebLMMonitors.class, "monitorEnterTarget", CallSideEffect.HAS_SIDE_EFFECT, LocationIdentity.any());
    public static final SubstrateForeignCallDescriptor MONITOR_EXIT = SnippetRuntime.findForeignCall(
                    McWebLMMonitors.class, "monitorExitTarget", CallSideEffect.HAS_SIDE_EFFECT, LocationIdentity.any());
    public static final SubstrateForeignCallDescriptor[] FOREIGN_CALLS = {MONITOR_ENTER, MONITOR_EXIT};

    /** Table pointer, in the control block below MemoryLayout.HEAP_BASE. */
    private static final int TABLE_POINTER_OFFSET = 272;
    /**
     * Diagnostics: monitor enters that found no room in their bucket and fell back to the
     * single global lock, and the peak number of entries held at once. Plain loads and
     * stores, not atomics - both answer "is the table under pressure", where an
     * approximate count is enough and every Java {@code synchronized} pays for it.
     */
    private static final int FALLBACK_ENTERS_OFFSET = 320;

    /**
     * 512 buckets of 32 slots. Probing stays inside a bucket, so a bucket's lock covers
     * every slot a probe can reach; and a bucket only overflows if 32 monitors that hash
     * together are held *simultaneously*, which reclamation makes a pathological case
     * rather than the steady state it used to be.
     */
    private static final int BUCKETS = 512;
    private static final int SLOTS = 32;
    private static final int ENTRY_BYTES = 16;
    private static final int BUCKET_HEADER_BYTES = 16;
    private static final int BUCKET_BYTES = BUCKET_HEADER_BYTES + SLOTS * ENTRY_BYTES;

    /*
     * Waiters are part of the same unmanaged allocation as the monitor table. Keeping
     * them here makes Object.wait/notify real without allocating a Java queue from the
     * monitor path, and gives the bounded runtime an explicit failure when the image has
     * more simultaneous waits than it can represent.
     */
    private static final int WAIT_LOCK_BYTES = 4;
    private static final int MAX_WAITERS = 128;
    private static final int WAITER_BYTES = 24;
    private static final int WAITER_OBJECT = 0;
    private static final int WAITER_THREAD = 4;
    private static final int WAITER_STATE = 8;
    private static final int WAITER_DEPTH = 12;
    private static final int WAITER_RESERVED = 16;
    private static final int WAITER_WAITING = 1;
    private static final int WAITER_NOTIFIED = 2;
    private static final int WAITER_TIMED_OUT = 3;
    private static final int WAITER_INTERRUPTED = 4;
    /**
     * Per-agent `synchronized` entry counter, appended to the table's own allocation.
     *
     * <p>Exists to attribute the threaded lane's per-chunk cost. WasmGC deletes every
     * monitor (`RemoveMonitorPhase`), so `synchronized` is free there and a real
     * bucket scan + CAS here; that asymmetry is a candidate explanation for the
     * measured 16x per-chunk gap, and this counter is what decides whether it is
     * millions of enters per chunk or thousands.
     *
     * <p>One 8-byte slot per agent, written only by its owner. Deliberately NOT one
     * shared counter: the bucket header javadoc below records that a global pair of
     * plain read-modify-writes lost so many updates across four threads that the count
     * went negative, and {@code Statistics.objectSize} was found drifting negative the
     * same way. A diagnostic that can print a negative number cannot be used to argue
     * anything.
     */
    private static final int ENTER_BYTES_PER_AGENT = 8;
    private static final int ENTER_AGENTS = 16;
    /** One cached logical-thread address per carrier; zero means no published thread. */
    private static final int CURRENT_THREAD_BYTES_PER_AGENT = 4;
    private static final int TABLE_BYTES = BUCKETS * BUCKET_BYTES
                    + WAIT_LOCK_BYTES + MAX_WAITERS * WAITER_BYTES
                    + ENTER_AGENTS * ENTER_BYTES_PER_AGENT
                    + ENTER_AGENTS * CURRENT_THREAD_BYTES_PER_AGENT;
    /**
     * Bucket header: the lock, then this bucket's live and peak entry counts.
     *
     * <p>Counted per bucket rather than globally because both updates already happen
     * under the bucket lock, so they are exact for free. A global pair of plain
     * read-modify-writes lost so many updates across four threads that the live count
     * went negative, and a diagnostic that can print -5505 cannot be used to argue the
     * table has headroom.
     */
    private static final int BUCKET_LOCK = 0;
    private static final int BUCKET_LIVE = 4;
    private static final int BUCKET_PEAK = 8;
    /**
     * One past the highest slot index this bucket has ever claimed.
     *
     * <p>A scan cannot stop at the first free slot - that is what would hand out a second
     * entry for an address whose entry sits beyond a hole - so without a bound every
     * monitor enter would read all {@link #SLOTS} slots. Entries only ever live below
     * this mark, it never decreases, and a reader that races a claim raising it simply
     * misses the new entry and retries under the bucket lock. Steady-state occupancy is
     * one, so the common enter reads one slot.
     */
    private static final int BUCKET_WATERMARK = 12;

    private static final int OFFSET_ADDRESS = 0;
    private static final int OFFSET_OWNER = 4;
    private static final int OFFSET_DEPTH = 8;

    private McWebLMMonitors() {
    }

    /** `Word.pointer` is generic in PointerBase; this pins it to Pointer. */
    private static Pointer at(int address) {
        Pointer pointer = Word.pointer(address);
        return pointer;
    }

    // ---------------------------------------------------------------- compiler side

    /**
     * Replacement body for {@code RemoveMonitorPhase.run}: on WasmLM the monitor nodes
     * become calls instead of being deleted. Other backends keep the upstream behaviour,
     * because their `MonitorSupport` really is a no-op.
     */
    public static void rewriteMonitors(StructuredGraph graph) {
        boolean lm = WebImageOptions.getBackend() == WebImageOptions.CompilerBackend.WASM;
        for (MonitorExitNode exit : graph.getNodes(MonitorExitNode.TYPE).snapshot()) {
            if (lm) {
                replace(graph, exit, MONITOR_EXIT, exit.object());
            } else {
                graph.removeFixed(exit);
            }
        }
        for (MonitorEnterNode enter : graph.getNodes(MonitorEnterNode.TYPE).snapshot()) {
            if (lm) {
                replace(graph, enter, MONITOR_ENTER, enter.object());
            } else {
                graph.removeFixed(enter);
            }
        }
    }

    private static void replace(StructuredGraph graph, jdk.graal.compiler.nodes.java.AccessMonitorNode node,
                    SubstrateForeignCallDescriptor descriptor, ValueNode object) {
        ForeignCallNode call = graph.add(new ForeignCallNode(descriptor, StampFactory.forVoid(), List.of(object)));
        call.setStateAfter(node.stateAfter());
        graph.replaceFixedWithFixed(node, call);
    }

    // ---------------------------------------------------------------- runtime side

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    private static void monitorEnterTarget(Object obj) {
        enter(obj);
    }

    @SubstrateForeignCallTarget(stubCallingConvention = false)
    private static void monitorExitTarget(Object obj) {
        exit(obj);
    }

    /**
     * Lazily reserves the table. Unmanaged memory, so the collector neither scans nor
     * moves it, and the address stays valid for the life of the image.
     */
    private static int tableBase() {
        Pointer slot = at(TABLE_POINTER_OFFSET);
        int existing = slot.readInt(0);
        if (existing != 0) {
            return existing;
        }
        UnsignedWord bytes = Word.unsigned(TABLE_BYTES);
        Pointer allocated = WasmAllocation.malloc(bytes);
        if (allocated.isNull()) {
            return 0;
        }
        for (int offset = 0; offset < TABLE_BYTES; offset += 4) {
            allocated.writeInt(offset, 0);
        }
        int base = (int) allocated.rawValue();
        if (slot.compareAndSwapInt(0, 0, base, LocationIdentity.ANY_LOCATION) == 0) {
            return base;
        }
        // Another agent won the race; give ours back and use theirs.
        WasmAllocation.free(allocated);
        return slot.readInt(0);
    }

    private static int addressOf(Object obj) {
        return (int) Word.objectToUntrackedPointer(obj).rawValue();
    }

    /**
     * Monitor ownership is a logical Java-thread property, not a carrier property. A
     * carrier is reused for many Thread objects, so using agentId here lets the new
     * logical thread inherit a previous thread's reentrant ownership. The shared-heap
     * address of Thread.currentThread is stable and is exactly what the host start queue
     * already uses as the logical identity.
     */
    private static int ownerOfCurrentThread() {
        int base = at(TABLE_POINTER_OFFSET).readInt(0);
        int agent = McWebLMHeapLock.agentIdOrPrimary();
        if (base != 0 && agent >= 0 && agent < ENTER_AGENTS) {
            int cached = currentThreadAt(base, agent).readInt(0);
            if (cached != 0) {
                return cached;
            }
        }
        int owner = addressOf(Thread.currentThread());
        if (owner == 0) {
            throw new IllegalStateException("current logical thread has no heap address");
        }
        if (base != 0 && agent >= 0 && agent < ENTER_AGENTS) {
            currentThreadAt(base, agent).writeInt(0, owner);
        }
        return owner;
    }

    private static int waitLock(int base) {
        return base + BUCKETS * BUCKET_BYTES;
    }

    private static Pointer waiterAt(int base, int index) {
        return at(waitLock(base) + WAIT_LOCK_BYTES + WAITER_BYTES * index);
    }

    private static Pointer enterCountAt(int base, int agent) {
        return at(waitLock(base) + WAIT_LOCK_BYTES + WAITER_BYTES * MAX_WAITERS
                        + agent * ENTER_BYTES_PER_AGENT);
    }

    private static Pointer currentThreadAt(int base, int agent) {
        return at(waitLock(base) + WAIT_LOCK_BYTES + WAITER_BYTES * MAX_WAITERS
                        + ENTER_AGENTS * ENTER_BYTES_PER_AGENT
                        + agent * CURRENT_THREAD_BYTES_PER_AGENT);
    }

    /**
     * Publishes the logical thread currently running on this carrier.
     *
     * <p>{@code Thread.currentThread()} is backed by the per-agent VM-thread-local
     * holder. Monitor entry/exit is already a foreign-call boundary on WasmLM, and
     * resolving that holder again for the ownership key made every synchronized block
     * pay the holder lookup in addition to the monitor-table lookup. The carrier is
     * reused only after {@code McWebLMThreads.run} clears the holder, so this cache is
     * valid for the lifetime of one logical run. A zero publication is the idle state
     * and deliberately disables the fast path.</p>
     *
     * <p>This method never allocates the table. It is called at thread hand-off, where
     * a missing table is normal; the first monitor operation populates the cache through
     * {@link #ownerOfCurrentThread()}.</p>
     */
    public static void publishCurrentThread(Thread thread) {
        int base = at(TABLE_POINTER_OFFSET).readInt(0);
        if (base == 0) {
            return;
        }
        int agent = McWebLMHeapLock.agentIdOrPrimary();
        if (agent < 0 || agent >= ENTER_AGENTS) {
            return;
        }
        currentThreadAt(base, agent).writeInt(0, thread == null ? 0 : addressOf(thread));
    }

    /** Total `synchronized` entries across all agents; see {@link #ENTER_BYTES_PER_AGENT}. */
    private static long enterCount() {
        int base = tableBase();
        if (base == 0) {
            return 0;
        }
        long total = 0;
        for (int agent = 0; agent < ENTER_AGENTS; agent++) {
            total += enterCountAt(base, agent).readLong(0);
        }
        return total;
    }

    /** Base address of the bucket an object's monitor lives in. */
    private static int bucketOf(int base, int address) {
        int h = address * 0x9E3779B1;
        return base + BUCKET_BYTES * ((h >>> 15) & (BUCKETS - 1));
    }

    private static Pointer slotAt(int bucket, int index) {
        return at(bucket + BUCKET_HEADER_BYTES + ENTRY_BYTES * index);
    }

    /**
     * Lock-free scan for an existing entry. Returns 0 when the bucket holds none for this
     * address; the caller then takes the bucket lock and claims one.
     *
     * <p>Every claimed slot is scanned rather than stopping at the first free one. With
     * reclamation a free slot can sit *before* a live entry for the same address, and
     * stopping there would hand out a second entry for one object - two threads each
     * holding "the" monitor. {@link #BUCKET_WATERMARK} is what keeps that from costing a
     * full {@link #SLOTS} scan per enter.
     */
    private static int findEntry(int bucket, int address) {
        int used = at(bucket).readInt(BUCKET_WATERMARK);
        for (int index = 0; index < used; index++) {
            Pointer entry = slotAt(bucket, index);
            if (entry.readInt(OFFSET_ADDRESS) == address) {
                return (int) entry.rawValue();
            }
        }
        return 0;
    }

    private static void lockBucket(int bucket) {
        Pointer lock = at(bucket + BUCKET_LOCK);
        int me = McWebLMHeapLock.agentIdOrPrimary() + 1;
        while (lock.compareAndSwapInt(0, 0, me, LocationIdentity.ANY_LOCATION) != 0) {
            // Waiting here is a safepoint: the primary may be waiting for this agent to
            // park so it can collect. Nothing inside the bucket lock allocates, so the
            // collector never needs this lock itself.
            McWebLMSafepoint.poll();
        }
    }

    private static void unlockBucket(int bucket) {
        int me = McWebLMHeapLock.agentIdOrPrimary() + 1;
        at(bucket + BUCKET_LOCK).compareAndSwapInt(0, me, 0, LocationIdentity.ANY_LOCATION);
    }

    /**
     * Returns the entry for {@code address}, claiming a free slot if the bucket has one.
     * Zero means the bucket is full, which is surfaced as a bounded runtime failure.
     */
    private static int claimEntry(int bucket, int address) {
        lockBucket(bucket);
        int found = findEntry(bucket, address);
        if (found == 0) {
            for (int index = 0; index < SLOTS; index++) {
                Pointer entry = slotAt(bucket, index);
                if (entry.readInt(OFFSET_ADDRESS) == 0 && entry.readInt(OFFSET_OWNER) == 0) {
                    /*
                     * Deliberately does NOT touch the depth. The bucket lock excludes other
                     * claims and releases, but NOT `acquireEntry`, which is lock-free: a
                     * thread holding a stale pointer to this slot can win the owner CAS
                     * between the two reads above and the address store below. If it wanted
                     * this same address it then legitimately owns the slot at depth 1, and a
                     * depth store from here would clobber a held monitor's depth back to 0 --
                     * the next exit drives it negative and frees an entry someone else still
                     * holds, so the real owner's outer exit finds no entry at all. Only the
                     * owner writes the depth; `releaseEntry` leaves every free slot at 0.
                     */
                    entry.writeInt(OFFSET_ADDRESS, address);
                    found = (int) entry.rawValue();
                    Pointer header = at(bucket);
                    if (index >= header.readInt(BUCKET_WATERMARK)) {
                        // Publish the slot's contents before the mark that makes a
                        // lock-free scan look at it.
                        header.writeInt(BUCKET_WATERMARK, index + 1);
                    }
                    countEntryClaimed(bucket);
                    break;
                }
            }
        }
        unlockBucket(bucket);
        return found;
    }

    /** Gives a slot back once nobody holds the monitor. */
    private static void releaseEntry(int bucket, Pointer entry, int me) {
        lockBucket(bucket);
        /*
         * Address first, owner second. A scanner that already resolved this entry for the
         * old address re-validates the address after taking ownership and backs out, and a
         * thread that claims the slot for a different object cannot take ownership until
         * the owner word is cleared here.
         */
        entry.writeInt(OFFSET_DEPTH, 0);
        entry.writeInt(OFFSET_ADDRESS, 0);
        entry.compareAndSwapInt(OFFSET_OWNER, me, 0, LocationIdentity.ANY_LOCATION);
        Pointer header = at(bucket);
        header.writeInt(BUCKET_LIVE, header.readInt(BUCKET_LIVE) - 1);
        unlockBucket(bucket);
    }

    /** Called with the bucket locked, so the counts are exact. */
    private static void countEntryClaimed(int bucket) {
        Pointer header = at(bucket);
        int now = header.readInt(BUCKET_LIVE) + 1;
        header.writeInt(BUCKET_LIVE, now);
        if (now > header.readInt(BUCKET_PEAK)) {
            header.writeInt(BUCKET_PEAK, now);
        }
    }

    public static void enter(Object obj) {
        int me = ownerOfCurrentThread();
        int base = tableBase();
        if (base == 0) {
            throw new IllegalStateException("WasmLM monitor table allocation failed");
        }
        int counterAgent = McWebLMHeapLock.agentIdOrPrimary();
        if (counterAgent >= 0 && counterAgent < ENTER_AGENTS) {
            Pointer enters = enterCountAt(base, counterAgent);
            enters.writeLong(0, enters.readLong(0) + 1);
        }
        int address = addressOf(obj);
        int bucket = bucketOf(base, address);
        for (;;) {
            int found = findEntry(bucket, address);
            if (found == 0) {
                found = claimEntry(bucket, address);
            }
            if (found == 0) {
                Pointer fallbacks = at(FALLBACK_ENTERS_OFFSET);
                fallbacks.writeInt(0, fallbacks.readInt(0) + 1);
                throw new IllegalStateException("WasmLM monitor table bucket exhausted");
            }
            Pointer entry = at(found);
            if (entry.readInt(OFFSET_OWNER) == me && entry.readInt(OFFSET_ADDRESS) == address) {
                entry.writeInt(OFFSET_DEPTH, entry.readInt(OFFSET_DEPTH) + 1);
                return;
            }
            if (acquireEntry(entry, address, me)) {
                return;
            }
            // The slot was released and re-used for another object while we waited for
            // it; find where this object's monitor lives now.
        }
    }

    /**
     * Takes ownership of {@code entry}, or reports that the entry stopped representing
     * {@code address} and the caller must start over.
     */
    private static boolean acquireEntry(Pointer entry, int address, int me) {
        for (;;) {
            if (entry.compareAndSwapInt(OFFSET_OWNER, 0, me, LocationIdentity.ANY_LOCATION) == 0) {
                if (entry.readInt(OFFSET_ADDRESS) == address) {
                    entry.writeInt(OFFSET_DEPTH, 1);
                    return true;
                }
                entry.compareAndSwapInt(OFFSET_OWNER, me, 0, LocationIdentity.ANY_LOCATION);
                return false;
            }
            if (entry.readInt(OFFSET_ADDRESS) != address) {
                return false;
            }
            // Waiting for a monitor is a safepoint; otherwise a collection could not
            // proceed while an agent blocks on a lock held by another agent.
            McWebLMSafepoint.poll();
        }
    }

    public static void exit(Object obj) {
        int me = ownerOfCurrentThread();
        int base = tableBase();
        if (base == 0) {
            throw new IllegalMonitorStateException("WasmLM monitor table is unavailable");
        }
        int address = addressOf(obj);
        int bucket = bucketOf(base, address);
        int found = findEntry(bucket, address);
        if (found == 0) {
            throw new IllegalMonitorStateException("current thread does not own monitor");
        }
        Pointer entry = at(found);
        if (entry.readInt(OFFSET_OWNER) != me) {
            throw new IllegalMonitorStateException("current thread does not own monitor");
        }
        int depth = entry.readInt(OFFSET_DEPTH) - 1;
        entry.writeInt(OFFSET_DEPTH, depth);
        if (depth <= 0) {
            releaseEntry(bucket, entry, me);
        }
    }

    public static boolean heldByCurrent(Object obj) {
        int me = ownerOfCurrentThread();
        int base = tableBase();
        if (base != 0) {
            int address = addressOf(obj);
            int found = findEntry(bucketOf(base, address), address);
            if (found != 0) {
                return at(found).readInt(OFFSET_OWNER) == me;
            }
        }
        return false;
    }

    public static boolean heldByAny(Object obj) {
        int base = tableBase();
        if (base != 0) {
            int address = addressOf(obj);
            int found = findEntry(bucketOf(base, address), address);
            if (found != 0) {
                return at(found).readInt(OFFSET_OWNER) != 0;
            }
        }
        return false;
    }

    private static void lockWaitTable(int base) {
        Pointer lock = at(waitLock(base));
        int me = McWebLMHeapLock.agentIdOrPrimary() + 1;
        while (lock.compareAndSwapInt(0, 0, me, LocationIdentity.ANY_LOCATION) != 0) {
            McWebLMSafepoint.poll();
        }
    }

    private static void unlockWaitTable(int base) {
        int me = McWebLMHeapLock.agentIdOrPrimary() + 1;
        at(waitLock(base)).compareAndSwapInt(0, me, 0, LocationIdentity.ANY_LOCATION);
    }

    /** Reserve a logical waiter before releasing its monitor. */
    private static int reserveWaiter(int base, int object, int thread, int depth) {
        lockWaitTable(base);
        int result = -1;
        for (int index = 0; index < MAX_WAITERS; index++) {
            Pointer waiter = waiterAt(base, index);
            if (waiter.readInt(WAITER_THREAD) == 0) {
                waiter.writeInt(WAITER_OBJECT, object);
                waiter.writeInt(WAITER_DEPTH, depth);
                waiter.writeInt(WAITER_RESERVED, 0);
                waiter.writeInt(WAITER_THREAD, thread);
                // State is the publication word: notify never observes a half-written
                // waiter because it takes the same table lock.
                waiter.writeInt(WAITER_STATE, WAITER_WAITING);
                result = index;
                break;
            }
        }
        unlockWaitTable(base);
        if (result < 0) {
            throw new IllegalStateException("WasmLM wait table exhausted");
        }
        return result;
    }

    private static int waiterState(int base, int index) {
        return waiterAt(base, index).readInt(WAITER_STATE);
    }

    private static void finishWaiter(int base, int index, int state) {
        lockWaitTable(base);
        Pointer waiter = waiterAt(base, index);
        waiter.writeInt(WAITER_STATE, state);
        // Clear the key only after the state transition. A notifier that already found
        // this waiter may still issue an unpark; the host permit is deliberately sticky.
        waiter.writeInt(WAITER_OBJECT, 0);
        waiter.writeInt(WAITER_DEPTH, 0);
        waiter.writeInt(WAITER_THREAD, 0);
        unlockWaitTable(base);
    }

    private static long waitNanos(long timeoutMillis) {
        if (timeoutMillis <= 0L) {
            return 0L;
        }
        if (timeoutMillis >= Long.MAX_VALUE / 1_000_000L) {
            return Long.MAX_VALUE;
        }
        return timeoutMillis * 1_000_000L;
    }

    /**
     * Real Object.wait: register the logical thread, release all recursive monitor depth,
     * park until notification/timeout/interrupt, then reacquire the exact depth before
     * returning or throwing InterruptedException. The primary's host park is an allowed
     * spurious return, so its timed path uses the same deadline loop and its untimed path
     * waits for the shared waiter state to become notified.
     */
    public static void waitOn(Object obj, long timeoutMillis) throws InterruptedException {
        int me = ownerOfCurrentThread();
        int base = tableBase();
        if (base == 0) {
            throw new IllegalStateException("WasmLM monitor table allocation failed");
        }
        int address = addressOf(obj);
        int bucket = bucketOf(base, address);
        int found = findEntry(bucket, address);
        if (found == 0) {
            throw new IllegalMonitorStateException("current thread does not own monitor");
        }
        Pointer entry = at(found);
        if (entry.readInt(OFFSET_OWNER) != me) {
            throw new IllegalMonitorStateException("current thread does not own monitor");
        }
        int depth = entry.readInt(OFFSET_DEPTH);
        int waiter = reserveWaiter(base, address, me, depth);
        entry.writeInt(OFFSET_DEPTH, 0);
        releaseEntry(bucket, entry, me);

        int result = WAITER_WAITING;
        boolean interrupted = false;
        long duration = waitNanos(timeoutMillis);
        long deadline = duration == 0L ? 0L : saturatingDeadline(duration);
        try {
            for (;;) {
                int state = waiterState(base, waiter);
                if (state != WAITER_WAITING) {
                    result = state;
                    break;
                }
                if (McWebLMThreads.takeCurrentInterrupt()) {
                    interrupted = true;
                    result = WAITER_INTERRUPTED;
                    break;
                }
                long remaining = duration == 0L ? 0L : deadline - System.nanoTime();
                if (duration != 0L && remaining <= 0L) {
                    result = WAITER_TIMED_OUT;
                    break;
                }
                McWebLMThreads.park(false, remaining);
            }
            // An interrupt can race with a notification or timeout. Java gives the
            // interrupt precedence once the flag is observed, but always reacquires the
            // monitor first so the caller's synchronized block remains valid.
            if (!interrupted && McWebLMThreads.takeCurrentInterrupt()) {
                interrupted = true;
                result = WAITER_INTERRUPTED;
            }
        } finally {
            finishWaiter(base, waiter, result);
            for (int level = 0; level < depth; level++) {
                enter(obj);
            }
        }
        if (interrupted) {
            throw new InterruptedException();
        }
    }

    private static long saturatingDeadline(long duration) {
        long now = System.nanoTime();
        long deadline = now + duration;
        return deadline < now ? Long.MAX_VALUE : deadline;
    }

    /** Notify one waiter, or all waiters, after proving the caller owns the monitor. */
    public static void notifyWaiters(Object obj, boolean all) {
        if (!heldByCurrent(obj)) {
            throw new IllegalMonitorStateException("current thread does not own monitor");
        }
        int base = tableBase();
        int object = addressOf(obj);
        int[] threads = new int[all ? MAX_WAITERS : 1];
        int count = 0;
        lockWaitTable(base);
        for (int index = 0; index < MAX_WAITERS; index++) {
            Pointer waiter = waiterAt(base, index);
            if (waiter.readInt(WAITER_OBJECT) != object
                            || waiter.readInt(WAITER_STATE) != WAITER_WAITING) {
                continue;
            }
            waiter.writeInt(WAITER_STATE, WAITER_NOTIFIED);
            threads[count++] = waiter.readInt(WAITER_THREAD);
            if (!all) {
                break;
            }
        }
        unlockWaitTable(base);
        for (int index = 0; index < count; index++) {
            McWebLMThreads.unparkAddress(threads[index] & 0xffff_ffffL);
        }
    }

    /** Diagnostics: monitor enters that could not claim a table entry. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.monitor.fallbacks", comment = "Monitor table overflows")
    public static int fallbackEntersExport() {
        return at(FALLBACK_ENTERS_OFFSET).readInt(0);
    }

    /** Thousands of `synchronized` entries; see {@link #ENTER_BYTES_PER_AGENT}. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(
                    value = "mcweb.monitor.enterThousands", comment = "Monitor enters, in thousands")
    public static int enterThousandsExport() {
        long total = enterCount() / 1000L;
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * Diagnostics: the deepest any one bucket ever got, and the total held now.
     *
     * <p>The peak is per bucket on purpose - {@link #SLOTS} is a per-bucket bound, so the
     * number that says whether the table is close to falling back is the fullest bucket,
     * not the sum.
     */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.monitor.peakEntries", comment = "Deepest any single bucket has been")
    public static int peakEntriesExport() {
        int base = at(TABLE_POINTER_OFFSET).readInt(0);
        if (base == 0) {
            return 0;
        }
        int peak = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            int seen = at(base + BUCKET_BYTES * bucket).readInt(BUCKET_PEAK);
            if (seen > peak) {
                peak = seen;
            }
        }
        return peak;
    }

    /** Diagnostics: monitors held right now, across every bucket. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.monitor.liveEntries", comment = "Monitors held right now")
    public static int liveEntriesExport() {
        int base = at(TABLE_POINTER_OFFSET).readInt(0);
        if (base == 0) {
            return 0;
        }
        int live = 0;
        for (int bucket = 0; bucket < BUCKETS; bucket++) {
            live += at(base + BUCKET_BYTES * bucket).readInt(BUCKET_LIVE);
        }
        return live;
    }

    /** Diagnostics: how many simultaneous monitors one bucket can represent. */
    @com.oracle.svm.webimage.wasmgc.annotation.WasmExport(value = "mcweb.monitor.slotsPerBucket", comment = "Monitor slots per bucket")
    public static int slotsPerBucketExport() {
        return SLOTS;
    }

}
