/*
 * MC-Web builder patch: real `Thread.start()` for the Web Image WasmLM backend.
 *
 * Upstream substitutes `Thread.start0()` with an empty body and `Thread.isAlive()`
 * with `this == Thread.currentThread()`, so a started thread never runs and a join
 * returns immediately. Those two substitutions are rewritten (see
 * tools/webimage-patch/McWebImagePatcher.java) to call into this class.
 *
 * The execution context is a pre-created *agent*: another instance of this very Wasm
 * module, on another OS thread, importing the same shared linear memory - which on
 * WasmLM is the Java heap. Because the heap is shared, a Java object reference is
 * just an i32/i64 address that is equally valid in the agent, so a `Thread` object
 * needs no serialisation: the host is handed its address and the agent turns the
 * address back into the same object.
 *
 * Thread bookkeeping (which agent is idle, which thread is still running, blocking
 * waits) deliberately lives on the host side, in JavaScript, over a control
 * SharedArrayBuffer of its own. That keeps the Java side free of atomics the WasmLM
 * backend does not emit yet, and keeps every blocking wait in `Atomics.wait`, where
 * the platform's rules about which thread may block are the host's problem.
 *
 * Host contract (see tools/wasmlm-probes/thread-host.mjs):
 *   mcwebThreads.dispatch(threadAddress)  hand a Thread to an idle agent; must not block
 *   mcwebThreads.isAlive(threadAddress)   atomic read of that thread's run state
 *   mcwebThreads.await(threadAddress)     block until the thread terminates
 *   mcwebThreads.agents()                 number of agents available
 */
package com.oracle.svm.webimage.threads;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMHeapLock;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMMonitors;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMSafepoint;
import com.oracle.svm.hosted.webimage.wasm.gc.McWebLMThreadLocals;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

import jdk.graal.compiler.word.Word;
import org.graalvm.word.Pointer;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.webimage.api.JS;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMThreads {

    /** Host dispatch roles; zero remains the compatibility/default role. */
    public static final int ROLE_GENERIC = 0;
    public static final int ROLE_SERVER = 1;
    public static final int ROLE_BACKGROUND = 2;
    public static final int ROLE_IO = 3;

    private McWebLMThreads() {
    }

    /** Starts rejected by the host because the shared thread-record queue is full. */
    private static int droppedStarts;

    /**
     * Replacement for the empty {@code Thread.start0()} substitution. Runs on the
     * starting thread and must not block: the host hands the thread to an agent that
     * is already instantiated and already parked in {@code Atomics.wait}.
     *
     * <p>The host owns a bounded start queue. A threaded image must never claim that a
     * Java thread started when no agent will run it: ForkJoinPool uses that lifecycle
     * state to decide whether compensation is required. Zero-agent images retain the
     * old Web Image no-op, while a configured threaded image fails explicitly when its
     * queue is exhausted.
     */
    public static void start(Object self) {
        Thread thread = SubstrateUtil.cast(self, Thread.class);
        long address = Word.objectToUntrackedPointer(thread).rawValue();
        if (address == 0) {
            throw VMError.shouldNotReachHere("Thread object has no heap address");
        }
        int role = roleFor(thread);
        int result = role == ROLE_GENERIC
                ? dispatch(address)
                : dispatchWithRole(address, role);
        if (result < 0) {
            throw new IllegalThreadStateException("Thread already started");
        }
        if (result == 0 && agentCount() > 0) {
            droppedStarts++;
            throw new IllegalStateException("WasmLM thread start queue is full");
        }
    }

    /**
     * Starts a platform thread with an explicit carrier role.  Minecraft's
     * {@code MinecraftServer.spin} calls this at its exact Thread.start seam so
     * a generic Background dispatch cannot consume the reserved Server slot.
     * Executor adapters use the same path for their Background/IO workers.
     */
    public static void startWithRole(Object self, int role) {
        Thread thread = SubstrateUtil.cast(self, Thread.class);
        long address = Word.objectToUntrackedPointer(thread).rawValue();
        if (address == 0) {
            throw VMError.shouldNotReachHere("Thread object has no heap address");
        }
        int result = dispatchWithRole(address, role);
        if (result < 0) {
            throw new IllegalThreadStateException("Thread already started");
        }
        if (result == 0 && agentCount() > 0) {
            droppedStarts++;
            throw new IllegalStateException("WasmLM " + roleName(role)
                    + " thread start has no attached carrier capacity");
        }
    }

    private static String roleName(int role) {
        return switch (role) {
            case ROLE_SERVER -> "Server";
            case ROLE_BACKGROUND -> "Background";
            case ROLE_IO -> "IO";
            default -> "generic";
        };
    }

    /**
     * ForkJoinPool creates and starts its worker through JDK code after the
     * application-side factory returns.  That path does not preserve the
     * short-lived Java-side role tag, so the factory gives the Thread a stable
     * role prefix and the patched start seam consumes it here.  Ordinary
     * application starts still use the per-instance JS tag through dispatch().
     */
    private static int roleFor(Thread thread) {
        String name = thread.getName();
        if (name != null) {
            if (name.startsWith("mcweb-server-")) {
                return ROLE_SERVER;
            }
            if (name.startsWith("mcweb-background-")) {
                return ROLE_BACKGROUND;
            }
            if (name.startsWith("mcweb-io-")) {
                return ROLE_IO;
            }
        }
        return ROLE_GENERIC;
    }

    @WasmExport(value = "mcweb.thread.dropped", comment = "Thread.start() calls no agent accepted")
    public static int droppedStartsExport() {
        return droppedStarts;
    }

    /**
     * Replacement for the {@code Thread.isAlive()} substitution. Reads the host's run
     * state atomically, so a `join()` spin observes the agent's termination instead of
     * a value the engine is free to keep in a register.
     */
    public static boolean isAlive(Object self) {
        // `join()` spins on this, so it is where a joining thread notices a pending
        // collection and parks with its frames published.
        McWebLMSafepoint.poll();
        return isAlive0(Word.objectToUntrackedPointer(self).rawValue()) != 0;
    }

    /**
     * Reachable runtime bridge for Mojang classes transformed by the browser JAR
     * repair.  The safepoint implementation itself is a Web Image runtime class;
     * Mojang bytecode must enter it through this already image-reachable thread
     * seam rather than naming the builder-owned hosted package directly.
     */
    public static void safepointPoll() {
        McWebLMSafepoint.poll();
    }

    /** Thread.getId() must be stable across the primary and agent realms. */
    public static long threadId(Object self) {
        return threadId0(Word.objectToUntrackedPointer(self).rawValue());
    }

    /** Map the host record to the JDK lifecycle enum. */
    public static Thread.State threadState(Object self) {
        int state = threadState0(Word.objectToUntrackedPointer(self).rawValue());
        return switch (state) {
            case 1 -> Thread.State.RUNNABLE;
            case 2 -> Thread.State.TERMINATED;
            default -> Thread.State.NEW;
        };
    }

    public static void interrupt(Object self) {
        interrupt0(Word.objectToUntrackedPointer(self).rawValue());
    }

    public static boolean isInterrupted(Object self) {
        return interrupted0(Word.objectToUntrackedPointer(self).rawValue(), 0) != 0;
    }

    /** Consume the current logical thread's interrupt flag for Object.wait/sleep. */
    public static boolean takeCurrentInterrupt() {
        Thread current = Thread.currentThread();
        return interrupted0(Word.objectToUntrackedPointer(current).rawValue(), 1) != 0;
    }

    public static void yield() {
        yield0();
    }

    public static void sleep(long millis) throws InterruptedException {
        if (millis < 0) {
            throw new IllegalArgumentException("timeout value is negative");
        }
        long address = Word.objectToUntrackedPointer(Thread.currentThread()).rawValue();
        if (interrupted0(address, 1) != 0) {
            throw new InterruptedException();
        }
        if (millis == 0) {
            McWebLMThreads.yield();
            return;
        }
        park(false, millis * 1_000_000L);
        if (interrupted0(address, 1) != 0) {
            throw new InterruptedException();
        }
    }

    /**
     * Agent entry point. The host calls this on the agent's own OS thread with the
     * address of a {@link Thread} allocated by another agent.
     */
    @WasmExport(value = "mcweb.thread.run", comment = "Run a Java Thread whose object lives in the shared heap")
    public static void run(long address) {
        Pointer pointer = Word.pointer(address);
        Thread thread = (Thread) pointer.toObjectNonNull();
        // Tells the collector that another agent has live frames; see McWebLMHeapLock.
        McWebLMSafepoint.enterJava();
        // Makes Thread.currentThread() inside this agent report the thread being run.
        McWebLMThreadLocals.setCurrentThread(thread);
        try {
            thread.run();
        } catch (Throwable t) {
            uncaught(thread, t);
        } finally {
            // Publish the host lifecycle before waking joiners. A joining thread may
            // observe the wake while still holding this Thread object's monitor; if the
            // host state were published afterwards, it could re-enter wait() after the
            // only notification and sleep forever.
            completed0(address);
            McWebLMMonitors.enter(thread);
            McWebLMMonitors.notifyWaiters(thread, true);
            McWebLMMonitors.exit(thread);
            // The carrier is reusable. Do not leave the previous logical Thread as a
            // root or let a later task observe it through Thread.currentThread().
            McWebLMThreadLocals.setCurrentThread(null);
            McWebLMHeapLock.exitJava();
        }
    }

    /** Address of the primary Java thread, used by the host's park permit table. */
    @WasmExport(value = "mcweb.thread.current", comment = "Address of the current Java Thread")
    public static long currentThreadAddress() {
        return Word.objectToUntrackedPointer(Thread.currentThread()).rawValue();
    }

    /**
     * Replacement for {@code Unsafe.park}: consume a host-side permit or block.
     *
     * <p>The block happens in the host's {@code Atomics.wait}, where this agent can
     * reach no safepoint poll, so the wait is bracketed as a published-stack park. An
     * idle thread would otherwise stall every collection; see
     * {@link McWebLMSafepoint#parkBlocking}.
     *
     * <p>The browser thread is different in kind: it may not block, so its park is
     * always an immediate spurious wakeup, and every {@code j.u.c} wait on it is a
     * {@code while (!done) park()} spin. Measured at ~680,000 calls per second, so
     * the primary returns here instead of paying a JS boundary crossing, an
     * untracked-pointer conversion and a published-stack park bracket per call —
     * none of which can change its outcome. It also must not mark itself parked:
     * the primary is the collector, and {@link McWebLMSafepoint#parkBlocking}
     * already refuses to park agent 0.</p>
     */
    public static void park(boolean absolute, long time) {
        if (McWebLMHeapLock.agentIdOrPrimary() == 0) {
            /*
             * Primary: immediate spurious wakeup, no blocking.
             *
             * Two attempts to cut the clock traffic this causes both REGRESSED, and the
             * reason is the same in each: this "spin" is not waste. Mojang's
             * managedBlock is `while (!done) { if (!pollTask()) waitForTasks(); }`, so
             * the loop is draining the caller's own task queue -- slowing it slows real
             * progress.
             *
             *  - Pausing here (512 read-only spins per park, inside Wasm) dropped world
             *    load to 2 client chunks in 7 minutes, from 56 in 4.
             *  - Coarsening System.nanoTime() itself (cache performance.now(), refresh
             *    every 16th call) gave `frameMs` p50 1132 ms against 33 ms, because
             *    deadline loops need the clock to advance to terminate at all.
             *
             * The cost is real and large -- 5.0-7.6M wasm->JS clock crossings per
             * second, 126k-158k per frame, 45.3% of the game thread in
             * `performance.now` -- and it is why the frame costs ~33 ms whether 5 or 329
             * chunks are drawn. But the fix has to remove the *crossing* while keeping
             * both the iteration rate and a real advancing clock: publish
             * performance.now() into shared memory from a Worker and have nanoTime read
             * that word with a plain load. Do not slow the loop, and do not freeze the
             * clock.
             */
            return;
        }
        long address = Word.objectToUntrackedPointer(Thread.currentThread()).rawValue();
        notifyWorkerBlocked();
        try {
            // parkBlocking keeps its own published frame live while the imported host
            // call is inside Atomics.wait. Publishing in a helper that returns first
            // would leave the collector with a pointer to a popped frame.
            McWebLMSafepoint.parkBlocking(address, absolute, time);
        } finally {
            notifyWorkerUnblocked();
        }
    }

    /** Replacement for {@code Unsafe.unpark}: publish one host-side permit. */
    public static void unpark(Object thread) {
        if (thread != null) {
            unpark0(Word.objectToUntrackedPointer(thread).rawValue());
        }
    }

    /** Host permit bridge for monitor wait sets that retain a shared-heap address. */
    public static void unparkAddress(long address) {
        if (address != 0L) {
            unpark0(address);
        }
    }

    private static void uncaught(Thread thread, Throwable t) {
        Thread.UncaughtExceptionHandler handler = thread.getUncaughtExceptionHandler();
        if (handler != null) {
            try {
                handler.uncaughtException(thread, t);
                return;
            } catch (Throwable ignored) {
                // fall through to the default report
            }
        }
        System.err.println("Exception in thread \"" + thread.getName() + "\" " + t);
    }

    @JSRawCall
    @JS(value = "return mcwebThreads.dispatch(address, globalThis.mcwebThreadRole | 0);",
            args = {"address"})
    private static native int dispatch(long address);

    @JSRawCall
    @JS(value = "return mcwebThreads.dispatch(address, role);", args = {"address", "role"})
    private static native int dispatchWithRole(long address, int role);

    @JSRawCall
    @JS(value = "mcwebThreads.completed(address);", args = {"address"})
    private static native void completed0(long address);
    @JSRawCall
    @JS(value = "return mcwebThreads.isAlive(address);", args = {"address"})
    private static native int isAlive0(long address);

    @JSRawCall
    @JS(value = "return mcwebThreads.threadId(address);", args = {"address"})
    private static native long threadId0(long address);

    @JSRawCall
    @JS(value = "return mcwebThreads.state(address);", args = {"address"})
    private static native int threadState0(long address);

    @JSRawCall
    @JS(value = "mcwebThreads.interrupt(address);", args = {"address"})
    private static native void interrupt0(long address);

    @JSRawCall
    @JS(value = "return mcwebThreads.interrupted(address, clear);", args = {"address", "clear"})
    private static native int interrupted0(long address, int clear);

    @JSRawCall
    @JS("if(globalThis.mcwebThreads&&typeof globalThis.mcwebThreads.yield==='function'){mcwebThreads.yield();}")
    private static native void yield0();

    /**
     * The game-side executor installs these callbacks in the shared host object.
     * Keeping the bridge here preserves the patch module's dependency direction:
     * the low-level Thread runtime does not link against Minecraft classes, while
     * an agent parked inside a CompletableFuture can still activate a reserved
     * executor carrier before it enters Atomics.wait.
     */
    @JSRawCall
    @JS("if(globalThis.mcwebThreads&&typeof globalThis.mcwebThreads.workerBlocked==='function'){mcwebThreads.workerBlocked();}")
    private static native void notifyWorkerBlocked();

    @JSRawCall
    @JS("if(globalThis.mcwebThreads&&typeof globalThis.mcwebThreads.workerUnblocked==='function'){mcwebThreads.workerUnblocked();}")
    private static native void notifyWorkerUnblocked();

    /**
     * Blocking join. Exposed for hosts and probes that want a real wait instead of the
     * JDK's spin over {@link #isAlive}; the host decides whether the calling thread is
     * allowed to block.
     */
    @JSRawCall
    @JS(value = "return mcwebThreads.await(address);", args = {"address"})
    public static native int awaitTermination(long address);

    @JSRawCall
    @JS(value = "return mcwebThreads.park(address, absolute, time);", args = {"address", "absolute", "time"})
    public static native int park0(long address, int absolute, long time);

    @JSRawCall
    @JS(value = "mcwebThreads.unpark(address);", args = {"address"})
    private static native void unpark0(long address);

    public static int agentCount() {
        return agents();
    }

    @JSRawCall
    @JS("return mcwebThreads.agents();")
    private static native int agents();
}
