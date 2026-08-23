package dev.mcweb.graal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import net.minecraft.server.MinecraftServer;


import org.graalvm.webimage.api.JS;
import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

/**
 * Real worker pool for Minecraft's background executors, backed by WasmLM thread
 * agents over one shared Java heap.
 *
 * <p>{@link InlineExecutorService} runs every submitted task on the caller because the
 * WasmGC image has no threads at all. The WasmLM image does: each agent is another
 * instance of the same module on its own OS thread, importing the same linear memory,
 * so an ordinary {@code new Thread(...).start()} really runs Java elsewhere and shares
 * objects by address. This pool is that capability applied to the executors Mojang
 * intends to be parallel — {@code Util.backgroundExecutor()} and the IO executor —
 * while Minecraft's *main* executor stays on the browser thread, where every GL and
 * GUI same-thread assertion requires it.
 *
 * <p>An agent runs one Java thread to completion, so a long-lived executor worker owns
 * one host slot. The configured agent count is capacity, not an instruction to create
 * that many browser Workers: this executor creates a Java worker only after Mojang
 * submits background work, and the host creates the corresponding Worker only when
 * that Java {@link Thread#start()} occurs. One slot is left for vanilla's dedicated
 * integrated-server thread. {@link #agentCount()} reads capacity from the host, and
 * zero agents keeps the WasmGC inline executor unchanged.
 */
public final class AgentExecutorService extends AbstractExecutorService {

    /**
     * Workers that may run normal background work concurrently.
     *
     * <p>The randomly varying victims were read as shared-heap corruption under
     * concurrent allocation. They were not: the cause was that {@code synchronized}
     * had stopped excluding. {@code McWebLMMonitors} keyed monitor state to a fixed
     * table of object addresses and never released an entry, so the table filled
     * permanently — {@code ConcurrentHashMap.putVal} synchronizes on the bin head
     * node, a fresh object per colliding bin — after which every monitor on a new
     * object fell through to one global lock that was not depth-counted. A nested
     * pair released it at the inner exit and the outer block then ran unprotected.
     * Entries are now reclaimed on release; see {@code docs/STATUS.md} and
     * {@code tools/wasmlm-probes/monitor-saturation-harness.mjs}.
     *
     * <p>Watch {@code monitorFallbacks} in {@code mcWebThreadRuntime.info()}: it must
     * stay zero. A non-zero value means the table is filling again and this bound is
     * back on the wrong side of that fix.
     * Workers are prestarted at the world-creation boundary. This value is the normal
     * demand target the adapter may expose before terrain entry; bounded compensation
     * remains separate and is only claimed after a real blocking observation.
     */
    /**
     * Hard implementation ceiling; the configured host capacity is normally lower.
     * The page currently supports sixteen agent slots, one of which is reserved for
     * Minecraft's real {@code Server thread}.
     */
    private static final int MAX_WORKERS = 15;
    /** No completed task for this long is evidence of a blocked pool, not normal load. */
    private static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static volatile java.util.concurrent.ExecutorService backgroundPool;
    private static volatile java.util.concurrent.ExecutorService ioPool;
    private static volatile boolean resolved;

    /** Stable ownership/liveness counters for the real integrated-server lane. */
    private static final AtomicLong serverBound = new AtomicLong();
    private static final AtomicLong serverRunEnters = new AtomicLong();
    private static final AtomicLong serverRunExits = new AtomicLong();
    private static final AtomicLong serverReady = new AtomicLong();
    private static final AtomicLong serverOwnershipViolations = new AtomicLong();
    /** Agent allocation-pressure requests serviced by the browser/primary thread. */
    private static final AtomicLong primaryGcServices = new AtomicLong();
    private static final AtomicLong workerStartFailures = new AtomicLong();
    /** One physical carrier may be borrowed by whichever lane first blocks. */
    private static final AtomicInteger compensationLeases = new AtomicInteger();
    private static volatile int compensationLimit;
    /** Enable the spare only once Mojang has constructed the threaded Server lane. */
    private static volatile boolean compensationEnabled;
    private static volatile String boundServerThreadName = "";
    private static volatile long boundServerThreadId = -1L;
    private static volatile MinecraftServer boundServer;
    /** Fast, allocation-free gate for the managed-block probe hot path. */
    private static volatile boolean serverThreadActive;

    /**
     * A bounded real ForkJoinPool adapter for the threaded mode. The pool owns
     * its normal work-stealing and managed-block compensation semantics; the
     * adapter only counts submissions and gives each worker an explicit host
     * role before its patched Thread.start dispatches.
     */
    private static final class ForkJoinExecutorService extends AbstractExecutorService {
        private final String lane;
        private final ForkJoinPool pool;
        private final AtomicLong submitted = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicInteger failed = new AtomicInteger();
        private volatile boolean stopping;

        ForkJoinExecutorService(String lane, int parallelism, int role, int spare) {
            this.lane = lane;
            int boundedParallelism = Math.max(1, Math.min(MAX_WORKERS, parallelism));
            int maximumPoolSize = Math.max(
                    boundedParallelism,
                    Math.min(MAX_WORKERS, boundedParallelism + Math.max(0, spare)));
            pool = new ForkJoinPool(
                    boundedParallelism,
                    forkJoinWorkerFactory(role),
                    null,
                    true,
                    boundedParallelism,
                    maximumPoolSize,
                    1,
                    unused -> true,
                    60L,
                    TimeUnit.SECONDS
            );
        }

        private static ForkJoinPool.ForkJoinWorkerThreadFactory forkJoinWorkerFactory(int role) {
            return current -> new RoleForkJoinWorker(current, role);
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            if (stopping) {
                throw new RejectedExecutionException("WasmLM " + lane + " executor is shut down");
            }
            submitted.incrementAndGet();
            try {
                pool.execute(() -> {
                    try {
                        command.run();
                    } catch (Throwable failure) {
                        failed.incrementAndGet();
                        throw failure;
                    } finally {
                        completed.incrementAndGet();
                    }
                });
            } catch (RuntimeException | Error failure) {
                submitted.decrementAndGet();
                throw failure;
            }
        }

        @Override
        public void shutdown() {
            stopping = true;
            pool.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            stopping = true;
            return pool.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return stopping || pool.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return pool.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit)
                throws InterruptedException {
            return pool.awaitTermination(timeout, unit);
        }

        String stats() {
            return "executor=" + lane + "-forkjoin parallelism=" + pool.getParallelism()
                    + " size=" + pool.getPoolSize()
                    + " active=" + pool.getActiveThreadCount()
                    + " queued=" + pool.getQueuedSubmissionCount()
                    + " running=" + pool.getRunningThreadCount()
                    + " steals=" + pool.getStealCount()
                    + " submitted=" + submitted.get()
                    + " completed=" + completed.get()
                    + " failed=" + failed.get();
        }

        String compactState() {
            return "q=" + pool.getQueuedSubmissionCount()
                    + " b=" + pool.getActiveThreadCount() + '/' + pool.getPoolSize()
                    + " x=0 [forkjoin lane=" + lane + "]";
        }
    }

    /** FJP worker whose exact start call carries the Background/IO role. */
    private static final class RoleForkJoinWorker extends ForkJoinWorkerThread {
        private final int role;

        RoleForkJoinWorker(ForkJoinPool pool, int role) {
            super(pool);
            this.role = role;
            setName((role == McWebThreadRole.IO ? "mcweb-io-" : "mcweb-background-")
                    + "forkjoin-worker");
            setDaemon(true);
        }

        @Override
        public void start() {
            McWebThreadRole.setNext(role);
            try {
                super.start();
            } finally {
                McWebThreadRole.clear();
            }
        }
    }

    private final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
    /** Constant-time demand count; ConcurrentLinkedQueue.size() walks the whole queue. */
    private final AtomicInteger pendingTasks = new AtomicInteger();
    private final List<Thread> workers = new CopyOnWriteArrayList<>();
    private final String lane;
    private final String workerPrefix;
    /** Normal demand target; compensation workers are never created by queue depth alone. */
    private final int baseWorkers;
    private final int maxWorkers;
    private final AtomicInteger size = new AtomicInteger();
    private final AtomicInteger busy = new AtomicInteger();
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicInteger completed = new AtomicInteger();
    private final AtomicReferenceArray<String> inFlight = new AtomicReferenceArray<>(MAX_WORKERS);
    /** Fine-grained phase for a task whose class is only CompletableFuture$AsyncSupply. */
    private final AtomicReferenceArray<String> inFlightPhase = new AtomicReferenceArray<>(MAX_WORKERS);
    /** Host-carrier state for an in-flight task: 1 means it is inside a blocking park. */
    private final AtomicIntegerArray inFlightBlocked = new AtomicIntegerArray(MAX_WORKERS);
    /** Stable task identity and physical-carrier attribution for watchdog diagnostics. */
    private final AtomicIntegerArray inFlightHash = new AtomicIntegerArray(MAX_WORKERS);
    private final AtomicLongArray inFlightThread = new AtomicLongArray(MAX_WORKERS);
    private final AtomicLongArray inFlightStartedNanos = new AtomicLongArray(MAX_WORKERS);
    private final AtomicInteger failed = new AtomicInteger();
    /** Queue/park counters make a lost wake distinguishable from an idle lane. */
    private final AtomicLong pollCount = new AtomicLong();
    private final AtomicLong emptyPollCount = new AtomicLong();
    private final AtomicLong idleParkCount = new AtomicLong();
    private final AtomicLong wakeRequestCount = new AtomicLong();
    private final AtomicInteger blockedWorkers = new AtomicInteger();
    private final AtomicLong compensationStarts = new AtomicLong();
    private volatile String lastWorkerStartFailure = "";
    private final AtomicLong lastCompletionNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastGrowthNanos = new AtomicLong(System.nanoTime());
    private final AtomicLong lastCompensationNanos = new AtomicLong();
    private volatile boolean stopping;
    /**
     * False through bootstrap and the initial menu reload. This deliberately preserves the
     * shipping WasmGC scheduling contract: Mojang's client background executor runs
     * cooperatively on its caller until the JAR reaches its world-creation load. At that
     * boundary the normal Background lane is made persistent before the first future is
     * submitted, so a following Server.start has a deterministic second carrier.
     */
    private volatile boolean workersEnabled;

    private AgentExecutorService(
            String lane,
            String workerPrefix,
            int availableAgents,
            int compensationAgents
    ) {
        this.lane = lane;
        this.workerPrefix = workerPrefix;
        baseWorkers = Math.min(MAX_WORKERS, Math.max(0, availableAgents));
        maxWorkers = Math.min(MAX_WORKERS, baseWorkers + Math.max(0, compensationAgents));
    }

    /**
     * Starts one more worker, each on its own agent.
     *
     * <p>The pool has to be elastic, not fixed. A worker only leaves {@code task.run()}
     * when its task returns, and Minecraft's background executors receive tasks that
     * block on other tasks - and some that never return at all. A fixed pool is then
     * consumed one wedged worker at a time until the queue stops draining entirely,
     * which is exactly what stalled the threaded boot on the loading screen with every
     * worker occupied and tasks still queued.
     */
    private synchronized boolean addWorker(boolean allowCompensation) {
        if (stopping) {
            return false;
        }
        int index = size.get();
        if (index >= maxWorkers) {
            return false;
        }
        boolean compensation = index >= baseWorkers;
        if (compensation && (!allowCompensation || !claimCompensationLease())) {
            return false;
        }
        size.incrementAndGet();
        Thread worker = new Thread(this::workerLoop, workerPrefix + index);
        worker.setDaemon(true);
        workers.add(worker);
        try {
            McWebThreadRole.start(worker,
                    "io".equals(lane)
                            ? McWebThreadRole.IO
                            : McWebThreadRole.BACKGROUND);
            return true;
        } catch (Throwable failure) {
            workers.remove(worker);
            size.decrementAndGet();
            if (compensation) {
                releaseCompensationLease();
            }
            workerStartFailures.incrementAndGet();
            lastWorkerStartFailure = failure.getClass().getName() + ":" + failure.getMessage();
            report("executor:" + lane + ":worker-start-failed index=" + index
                    + " failure=" + lastWorkerStartFailure);
            return false;
        }
    }

    private static boolean claimCompensationLease() {
        for (;;) {
            int current = compensationLeases.get();
            if (current >= compensationLimit) {
                return false;
            }
            if (compensationLeases.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private static void releaseCompensationLease() {
        compensationLeases.updateAndGet(current -> Math.max(0, current - 1));
    }

    /**
     * The executor Minecraft's background work goes to: an agent-backed pool when the
     * host reports agents, the inline executor otherwise. Resolved once, because
     * {@code Util.makeExecutor} is called several times during bootstrap and they must
     * all share one set of workers.
     */
    public static java.util.concurrent.ExecutorService pool() {
        return backgroundPool();
    }

    /** Mojang's shared async-mode Background domain. */
    /**
     * Vanilla's background executor is an async-mode {@link java.util.concurrent.ForkJoinPool},
     * and on the threaded lane it is worth having the real one.
     *
     * <p>Chunk generation is a deep dependency graph: `biomes` needs
     * `STRUCTURE_STARTS` at radius 8, and {@code ChunkGenerationTask} advances a chunk
     * layer by layer over the chunk *and every required neighbour*
     * (docs/Minecraft/world/chunks.md). A plain queue+worker pool resolves that
     * breadth-first, and a worker that blocks on a dependency cannot run it — which is
     * why this lane measured 0.04 chunks/s on one pooled worker against 0.19 inline and
     * 7.15 on WasmGC. ForkJoinPool resolves it depth-first by *stealing*, and supplies
     * the managed-block compensation the custom pool never had.
     *
     * <p>The historical objection — "constructing a ForkJoinPool here is fatal in Web
     * Image, its workers call Unsafe.park/unpark which the backend substitutes to
     * throw" — is WasmGC-specific. The WasmLM builder patch routes those to
     * {@code McWebLMThreads}, and {@code tools/wasmlm-probes/LmForkJoin} runs a real
     * pool at parallelism 3 with 32 steals and no failures.
     *
     * <p>{@code maximumPoolSize} is bounded by the carriers actually reserved: FJP will
     * otherwise spawn compensation threads on managed blocks, and a thread without a
     * carrier cannot run. {@code saturate} returns true so a managed block degrades to
     * running with fewer threads rather than throwing.
     */
    private static java.util.concurrent.ExecutorService backgroundExecutorFor(
            int workers, int spare) {
        if (!McWebRuntimeMode.isThreaded() || forkJoinDisabled()) {
            return new AgentExecutorService("background", "mcweb-agent-", workers, spare);
        }
        try {
            ForkJoinExecutorService pool = new ForkJoinExecutorService(
                    "background", workers, McWebThreadRole.BACKGROUND, spare);
            report("executor:forkjoin parallelism=" + pool.pool.getParallelism()
                    + " max=" + Math.min(MAX_WORKERS, workers + spare)
                    + " role=background");
            return pool;
        } catch (Throwable failure) {
            report("executor:forkjoin-failed " + failure.getClass().getName()
                    + " falling back to the custom pool");
            return new AgentExecutorService("background", "mcweb-agent-", workers, spare);
        }
    }

    /** `?mcweb_forkjoin=0` restores the custom pool for a bisect. */
    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "return new URLSearchParams("
            + "globalThis.location ? globalThis.location.search : '')"
            + ".get('mcweb_forkjoin') === '0';", args = {})
    private static native boolean forkJoinDisabled();

    public static java.util.concurrent.ExecutorService backgroundPool() {
        if (resolved) {
            return backgroundPool;
        }
        synchronized (AgentExecutorService.class) {
            if (!resolved) {
                int agents = 0;
                if (McWebRuntimeMode.usesBackgroundAgents()) {
                    try {
                        agents = agentCount();
                    } catch (Throwable ignored) {
                        // A threaded mode without attached capacity remains a bounded
                        // inline Background lane; Server semantics stay threaded.
                    }
                }
                // Reserve one physical agent for Minecraft's real Server thread and,
                // from the four-carrier profile upward, one bounded spare. The spare
                // is not part of either lane's ordinary demand target: a worker that
                // blocks in a CompletableFuture can activate it without stealing a
                // task on the blocked caller. The four-carrier profile keeps one
                // normal Background worker and runs IO inline; the spare brings
                // worldgen back to the two-worker shape that passed registry load
                // without exposing the terrain graph to a third concurrent worker.
                // The six-carrier profile has enough room for three normal
                // Background workers and one normal IO carrier.
                //
                int spareAgents = agents >= 4 ? 1 : 0;
                int ioAgents = agents >= 6 ? 1 : 0;
                int backgroundAgents = agents >= 4
                        ? agents == 4
                                ? 1
                                : agents - 1 - spareAgents - ioAgents
                        : agents > 2
                                ? agents - 2
                                : Math.max(0, agents - 1);
                compensationLimit = spareAgents;
                java.util.concurrent.ExecutorService selected =
                        backgroundAgents > 0
                                ? backgroundExecutorFor(backgroundAgents, spareAgents)
                                : InlineExecutorService.INSTANCE;
                java.util.concurrent.ExecutorService selectedIo =
                        ioAgents > 0
                                ? new AgentExecutorService("io", "mcweb-io-", ioAgents, spareAgents)
                                : InlineExecutorService.INSTANCE;
                report(agents > 0
                        ? "executor:agent-pool workers=prestart capacity="
                                + Math.min(MAX_WORKERS, backgroundAgents)
                                + " ioCapacity=" + ioAgents
                                + " compensation=" + spareAgents
                                + " hostAgents=" + agents
                        : "executor:inline mode=" + McWebRuntimeMode.name());
                backgroundPool = selected;
                ioPool = selectedIo;
                resolved = true;
            }
        }
        return backgroundPool;
    }

    /** Mojang's independent blocking IO domain. */
    public static java.util.concurrent.ExecutorService ioPool() {
        backgroundPool();
        return ioPool;
    }

    /**
     * Executor installed in {@code MinecraftServer.executor}.
     *
     * <p>Vanilla captures {@code Util.backgroundExecutor()} here; it does not run
     * worldgen on the Server thread. Keep that ownership: the server loop/ticks and
     * chunk-source polling stay on the one Mojang Server thread, while the futures
     * Mojang explicitly submits to its shared background executor run here. The
     * bytecode seam retains the server parameter only so the transform stays exact.
     */
    public static java.util.concurrent.ExecutorService serverExecutor(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        activateTerrainParallelism();
        return serverWorldgenPool();
    }

    /**
     * The server's worldgen/light executor: the shared background pool when it has
     * enough parallelism to resolve the dependency graph, inline otherwise.
     *
     * <p>Vanilla backs {@code worldgenTaskDispatcher}/{@code lightTaskDispatcher} with
     * {@code Util.backgroundExecutor()} — one ForkJoinPool of
     * {@code availableProcessors()-1} threads shared with client reload and meshing.
     * Generation is "ordered per-chunk, parallel across chunks"
     * (docs/Minecraft/world/chunks.md), and {@code ChunkGenerationTask} advances a chunk
     * layer by layer over the chunk *and every required neighbour* — so a worker that
     * blocks on a neighbour needs other workers to steal from.
     *
     * <p>Two reasons were originally given for making this unconditionally inline. One
     * of them is gone and one of them turned out to be the real constraint:
     *
     * <ul>
     *   <li>"three concurrent workers hit a standing NPE in {@code DensityFunctions$Ap2}"
     *       — gone. That was the cross-agent class-initialisation race: every agent got
     *       the same {@code CurrentIsolate.getCurrentThread()} constant, so a racer took
     *       the false "reentrant" branch out of {@code slowPath} and read statics the
     *       winner had not written yet. Fixed in the builder patch; {@code LmClassInit}
     *       is 30/30 green at 3, 5 and 8 threads, and a 4-agent run logs zero
     *       {@code worldgen:step-failure} markers.</li>
     *   <li>"a pool starves on the generation dependency graph" — <b>confirmed, and it
     *       is not about the NPE at all.</b> Measured 2026-08-04 on one image, same
     *       6 agents, only this switch changing: pooled at parallelism 3 reached
     *       <b>5 chunks</b> at t+75 s and 25 at t+194 s, while inline reached 64 by
     *       t+103 s. 5 is the same number the original inline decision recorded. The
     *       generation graph is resolved depth-first by an inline caller and
     *       breadth-first by a pool, and Mojang's worldgen/light dispatchers are
     *       {@code ConsecutiveExecutor}s that serialise anyway, so the pool adds queueing
     *       latency without adding parallelism.</li>
     * </ul>
     *
     * <p>So inline is the default, and it is a real cap: it serialises worldgen onto
     * Mojang's one Server thread. But the threaded lane's deficit is <b>not</b> a
     * parallelism deficit — it is per-chunk cost. WasmGC inline does 140 ms/chunk;
     * this lane does 2,295 ms/chunk with the same algorithm on one thread. Sixteen
     * perfect workers would only draw level. Spend effort on the per-chunk cost
     * (allocator, monitors, GC pauses), not on worker count.
     *
     * <p>{@code ?mcweb_server_pool=1} opts into the pool for further experiments and
     * {@code ?mcweb_server_pool=0} pins inline explicitly.
     */
    public static java.util.concurrent.ExecutorService serverWorldgenPool() {
        // The override is a wasm->JS crossing and this method is polled per submission,
        // so read the query string once.
        int forced = serverPoolForced;
        if (forced == Integer.MIN_VALUE) {
            forced = serverPoolOverride();
            serverPoolForced = forced;
        }
        if (forced != 1) {
            return InlineExecutorService.INSTANCE;
        }
        java.util.concurrent.ExecutorService pool = backgroundPool();
        int parallelism = parallelismOf(pool);
        boolean inline = parallelism < 2;
        // This is polled, not called once (see the caller's note on not caching an
        // unresolved pool), so only report a decision that differs from the last one.
        String decision = (inline ? "inline" : "pool") + " parallelism=" + parallelism;
        if (!decision.equals(lastServerWorldgenDecision)) {
            lastServerWorldgenDecision = decision;
            report("executor:server-worldgen mode=" + decision + (forced == 1 ? " forced=1" : ""));
        }
        return inline ? InlineExecutorService.INSTANCE : pool;
    }

    private static volatile int serverPoolForced = Integer.MIN_VALUE;
    private static volatile String lastServerWorldgenDecision;

    /** Worker count of whichever pool implementation {@link #backgroundPool()} chose. */
    private static int parallelismOf(java.util.concurrent.ExecutorService pool) {
        if (pool instanceof java.util.concurrent.ForkJoinPool forkJoin) {
            return forkJoin.getParallelism();
        }
        if (pool instanceof ForkJoinExecutorService forkJoin) {
            return forkJoin.pool.getParallelism();
        }
        if (pool instanceof AgentExecutorService agents) {
            return agents.maxWorkers;
        }
        return 0;
    }

    /** -1 = default policy, 0 = force inline, 1 = force the pool. */
    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value = "var v = new URLSearchParams("
            + "globalThis.location ? globalThis.location.search : '')"
            + ".get('mcweb_server_pool');"
            + "return v === null ? -1 : (v === '0' ? 0 : 1);", args = {})
    private static native int serverPoolOverride();

    /** True only while the current Java thread is the bound Mojang Server thread. */
    public static boolean isServerThread() {
        return ServerOwnedExecutorService.currentFor(Thread.currentThread()) != null;
    }

    /**
     * Service allocator pressure handed off by a WasmLM agent.
     *
     * <p>The LM collector must run on the primary instance so its own stack and every
     * latched agent stack are marked together.  Agent-side {@code System.gc()} calls
     * therefore publish one coalesced control-word request.  Mojang's client wait loop,
     * managed-block loop, and browser frame boundary call this method; none of them
     * execute server tasks or poll a server chunk source.</p>
     */
    public static void servicePrimaryCollectionRequest(String phase) {
        if (!primaryCollectionRequested()) {
            return;
        }
        long service = primaryGcServices.incrementAndGet();
        System.gc();
        if (service <= 8L || (service & 0xFL) == 0L) {
            report("gc:agent-request-serviced n=" + service + " phase=" + phase);
        }
    }

    public static void reportServerBound(MinecraftServer server, Thread thread) {
        boundServer = server;
        boundServerThreadName = thread.getName();
        boundServerThreadId = thread.getId();
        serverBound.incrementAndGet();
        report("integrated-server:thread-bound agent=" + thread.getId()
                + " name=" + thread.getName());
    }

    public static void reportServerRunEnter(MinecraftServer server) {
        ServerOwnedExecutorService.enter(server);
        boundServer = server;
        boundServerThreadName = Thread.currentThread().getName();
        boundServerThreadId = Thread.currentThread().getId();
        serverThreadActive = true;
        serverRunEnters.incrementAndGet();
        report("integrated-server:run-enter thread=" + Thread.currentThread().getName()
                + " id=" + boundServerThreadId);
    }

    public static void reportServerReady(MinecraftServer server) {
        boundServer = server;
        serverReady.incrementAndGet();
        report("integrated-server:ready thread=" + Thread.currentThread().getName());
    }

    public static void reportServerRunExit(MinecraftServer server) {
        serverRunExits.incrementAndGet();
        report("integrated-server:run-exit thread=" + Thread.currentThread().getName());
        serverThreadActive = false;
        ServerOwnedExecutorService.leave(server);
        ServerOwnedExecutorService.unregister(server);
    }

    public static void reportServerExecutorExit(
            MinecraftServer server,
            ServerOwnedExecutorService owner
    ) {
        report("integrated-server:executor-exit submitted=" + owner.submittedCount()
                + " inline=" + owner.inlineCount()
                + " queued=" + owner.queuedCount()
                + " completed=" + owner.completedCount()
                + " rejected=" + owner.rejectedCount()
                + " maxQueue=" + owner.maxQueueDepth());
    }

    /** Record a bounded diagnostic if server-owned work runs from the wrong lane. */
    public static void serverOwnershipViolation(String operation) {
        long count = serverOwnershipViolations.incrementAndGet();
        if (count <= 1 || (count & 0x3FF) == 0) {
            report("integrated-server:ownership-violation #" + count
                    + " op=" + operation + " thread=" + Thread.currentThread().getName());
        }
    }

    public static String serverDiagnostics() {
        ServerOwnedExecutorService owner = boundServer == null
                ? null
                : ServerOwnedExecutorService.forServer(boundServer);
        return "serverBound=" + serverBound.get()
                + " runEnter=" + serverRunEnters.get()
                + " ready=" + serverReady.get()
                + " runExit=" + serverRunExits.get()
                + " ownershipViolations=" + serverOwnershipViolations.get()
                + " threadId=" + boundServerThreadId
                + " threadName=" + boundServerThreadName
                + (owner == null ? "" : " serverQueue=" + owner.queueDepth());
    }

    private static ServerOwnedExecutorService boundOwner() {
        return boundServer == null ? null : ServerOwnedExecutorService.forServer(boundServer);
    }

    @WasmExport(value = "mcweb.server.bound", comment = "Integrated server bindings")
    public static long serverBoundExport() { return serverBound.get(); }

    @WasmExport(value = "mcweb.server.runEnter", comment = "Integrated server run entries")
    public static long serverRunEnterExport() { return serverRunEnters.get(); }

    @WasmExport(value = "mcweb.server.ready", comment = "Integrated server ready markers")
    public static long serverReadyExport() { return serverReady.get(); }

    @WasmExport(value = "mcweb.server.runExit", comment = "Integrated server run exits")
    public static long serverRunExitExport() { return serverRunExits.get(); }

    @WasmExport(value = "mcweb.server.ownershipViolations", comment = "Server ownership violations")
    public static long serverOwnershipViolationsExport() {
        return serverOwnershipViolations.get();
    }

    @WasmExport(value = "mcweb.server.threadId", comment = "Bound server thread id")
    public static long serverThreadIdExport() { return boundServerThreadId; }

    @WasmExport(value = "mcweb.gc.primaryServices", comment = "Agent GC requests serviced by the primary")
    public static long primaryGcServicesExport() { return primaryGcServices.get(); }

    @WasmExport(value = "mcweb.server.submitted", comment = "Server executor submissions")
    public static long serverSubmittedExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.submittedCount();
    }

    @WasmExport(value = "mcweb.server.inline", comment = "Server executor inline submissions")
    public static long serverInlineExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.inlineCount();
    }

    @WasmExport(value = "mcweb.server.queued", comment = "Server executor queued submissions")
    public static long serverQueuedExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.queuedCount();
    }

    @WasmExport(value = "mcweb.server.completed", comment = "Server executor completions")
    public static long serverCompletedExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.completedCount();
    }

    @WasmExport(value = "mcweb.server.rejected", comment = "Server executor rejections")
    public static long serverRejectedExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.rejectedCount();
    }

    @WasmExport(value = "mcweb.server.maxQueue", comment = "Server executor maximum queue")
    public static long serverMaxQueueExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.maxQueueDepth();
    }

    @WasmExport(value = "mcweb.server.queueDepth", comment = "Server executor queue depth")
    public static long serverQueueDepthExport() {
        ServerOwnedExecutorService owner = boundOwner();
        return owner == null ? 0L : owner.queueDepth();
    }

    @WasmExport(value = "mcweb.client.submitted", comment = "Client executor submissions")
    public static long clientSubmittedExport() {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof AgentExecutorService agentPool) return agentPool.submitted.get();
        return current instanceof ForkJoinExecutorService forkJoin ? forkJoin.submitted.get() : 0L;
    }

    @WasmExport(value = "mcweb.client.completed", comment = "Client executor completions")
    public static long clientCompletedExport() {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof AgentExecutorService agentPool) return agentPool.completed.get();
        return current instanceof ForkJoinExecutorService forkJoin ? forkJoin.completed.get() : 0L;
    }

    @WasmExport(value = "mcweb.client.queued", comment = "Client executor queue depth")
    public static long clientQueuedExport() {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof AgentExecutorService agentPool) return agentPool.pendingTasks.get();
        return current instanceof ForkJoinExecutorService forkJoin
                ? forkJoin.pool.getQueuedSubmissionCount() : 0L;
    }

    /** Compatibility query for older staged probes; runtime code uses the explicit mode. */
    @Deprecated
    public static boolean usesAgents() {
        return backgroundPool instanceof AgentExecutorService
                || backgroundPool instanceof ForkJoinExecutorService;
    }

    /**
     * True when the page supplied WasmLM agents, even if Minecraft has not
     * resolved its background executor yet.
     */
    @Deprecated
    public static boolean threadingAvailable() {
        return McWebRuntimeMode.isThreaded();
    }

    /**
     * Ensures Mojang's shared background executor is enabled when the integrated
     * server is constructed. Idempotent because world creation normally enables it
     * earlier, before {@code WorldLoader.load} submits its first task.
     */
    public static void activateTerrainParallelism() {
        // Resource reload has a proven three-worker shape. The reserved carrier is
        // for the Server/worldgen dependency graph, where a blocked worker must be
        // compensated without increasing reload concurrency before its primitives
        // have completed their conformance gate.
        compensationEnabled = true;
        enableWorkers("integrated-server-start");
    }

    private static void enableWorkers(String phase) {
        backgroundPool();
        enablePool(backgroundPool, phase);
        enablePool(ioPool, phase);
    }

    /**
     * Make a lane eligible for demand-created workers without prestarting one.
     * World creation must first return from the synchronous executor accessor; the
     * IO lane is not needed for that accessor and its first carrier start can be
     * safely claimed by the normal execute() demand path.
     */
    private static void armPoolLazily(java.util.concurrent.ExecutorService current) {
        if (current instanceof AgentExecutorService agentPool) {
            agentPool.workersEnabled = true;
        }
    }

    private static void enablePool(
            java.util.concurrent.ExecutorService current,
            String phase
    ) {
        if (current instanceof ForkJoinExecutorService forkJoin) {
            report("executor:" + forkJoin.lane + "-enabled phase=" + phase
                    + " workers=lazy capacity=" + forkJoin.pool.getParallelism());
            return;
        }
        if (!(current instanceof AgentExecutorService agentPool)
                || agentPool.workersEnabled) {
            return;
        }
        agentPool.workersEnabled = true;
        if (!agentPool.startWorkersToCapacity()) {
            agentPool.workersEnabled = false;
            String failure = agentPool.lastWorkerStartFailure;
            report("executor:" + agentPool.lane + ":enable-failed phase=" + phase
                    + " workers=" + agentPool.size.get()
                    + " capacity=" + agentPool.baseWorkers
                    + " failure=" + failure);
            throw new RejectedExecutionException(
                    "WasmLM " + agentPool.lane + " worker start failed during " + phase
                            + (failure.isEmpty() ? "" : ": " + failure));
        }
        report("executor:" + agentPool.lane + "-enabled phase=" + phase
                + " workers=" + agentPool.size.get()
                + " capacity=" + agentPool.baseWorkers
                + " max=" + agentPool.maxWorkers);
    }

    /**
     * Enables background execution at Mojang's real world-creation boundary.
     * Initial boot, Accessibility, and Title retain the proven cooperative WasmGC
     * scheduling. Immediately before {@code WorldLoader.load} asks for
     * {@code Util.backgroundExecutor()}, the JAR is beginning work that vanilla runs
     * on Worker-Main threads. The normal Background carriers are started here rather
     * than on first demand: the two-carrier Phase 1 experiment must leave one
     * persistent Background worker parked while the next {@code Thread.start} claims
     * the second carrier for Server.
     */
    public static void activateWorldCreationParallelism() {
        backgroundPool();
        enablePool(backgroundPool, "world-creation");
        armPoolLazily(ioPool);
    }

    /** Diagnostics: tasks each pool has finished, and how many threw. */
    public static String stats() {
        java.util.concurrent.ExecutorService current = backgroundPool();
        if (current instanceof ForkJoinExecutorService forkJoin) {
            return forkJoin.stats() + " io=" + laneState(ioPool);
        }
        if (!(current instanceof AgentExecutorService agentPool)) {
            return "executor=inline";
        }
        return "executor=agents workers=" + agentPool.workers.size()
                + '/' + agentPool.maxWorkers
                + " base=" + agentPool.baseWorkers
                + " mode=" + (agentPool.workersEnabled
                        ? "vanilla-background" : "cooperative-menu")
                + " busy=" + agentPool.busy.get()
                + " queued=" + agentPool.pendingTasks.get()
                + " completed=" + agentPool.completed.get()
                + " failed=" + agentPool.failed.get()
                + " polls=" + agentPool.pollCount.get()
                + " emptyPolls=" + agentPool.emptyPollCount.get()
                + " parks=" + agentPool.idleParkCount.get()
                + " wakes=" + agentPool.wakeRequestCount.get()
                + " blocked=" + agentPool.blockedWorkers.get()
                + " compensation=" + agentPool.compensationStarts.get()
                + " workerStartFailures=" + workerStartFailures.get()
                + " lastWorkerStartFailure=" + agentPool.lastWorkerStartFailure
                + " inFlight=[" + agentPool.stuckWorkers() + ']';
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        submitted.incrementAndGet();
        if (stopping) {
            // Mirrors the inline executor's shutdown behaviour: never drop work on the
            // floor during teardown, run it on the caller instead.
            command.run();
            return;
        }
        if (!workersEnabled) {
            /*
             * This is intentionally the exact executor used by the WasmGC image,
             * including its main-loop drain after a task. CompletableFuture chains
             * therefore complete with the same ordering in Accessibility and Title,
             * and no Java Thread.start reaches the host before vanilla begins its
             * world-creation background load.
             */
            try {
                InlineExecutorService.INSTANCE.execute(command);
                completed.incrementAndGet();
            } catch (RuntimeException | Error failure) {
                failed.incrementAndGet();
                throw failure;
            } finally {
                lastCompletionNanos.set(System.nanoTime());
            }
            return;
        }
        pendingTasks.incrementAndGet();
        queue.add(command);
        if (!startWorkersForDemand() && queue.remove(command)) {
            pendingTasks.decrementAndGet();
            failed.incrementAndGet();
            throw new RejectedExecutionException(
                    "WasmLM " + lane + " worker start failed: " + lastWorkerStartFailure
            );
        }
        // A worker may already be parked inside a future wait while another task
        // publishes the dependency. Queue growth is the second observation point;
        // the park hook alone cannot see a submission that happens after it fires.
        if (blockedWorkers.get() > 0) {
            compensateBlockedWorker();
        }
        // One unpark per submission. The host permit is sticky, so a worker that is
        // between its poll and its park still consumes the permit and returns at once;
        // no wakeup can be lost, which is why the idle park may be indefinite.
        wakeRequestCount.addAndGet(workers.size());
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
        }
    }

    /**
     * Lazily claim host agents in response to queued Mojang work.
     *
     * <p>The host reserves stack addresses before Java enters, but creates the browser
     * Worker synchronously from the {@code Thread.start()} import. A pool worker is
     * created only after world launch begins and only when Mojang queues background
     * work. The configured agent count bounds demand; no Worker is preallocated.</p>
     */
    private boolean startWorkersForDemand() {
        if (stopping || !workersEnabled || pendingTasks.get() <= 0) {
            return true;
        }
        int desired = Math.min(baseWorkers, Math.max(1, busy.get() + pendingTasks.get()));
        while (size.get() < desired) {
            int before = size.get();
            if (!addWorker(false)) {
                return false;
            }
            if (size.get() == before) {
                return false;
            }
        }
        return true;
    }

    /**
     * Prestart the ordinary lane capacity at the explicit runtime boundary.
     *
     * <p>This is intentionally separate from demand growth. A lazy first start can
     * hide a second Worker bootstrap or lease failure behind a client future that is
     * already blocking; prestarting makes the carrier order observable and guarantees
     * that the first real Server start competes with a live Background worker, not with
     * an unmaterialized queue entry.</p>
     */
    private boolean startWorkersToCapacity() {
        if (stopping || !workersEnabled) {
            return true;
        }
        while (size.get() < baseWorkers) {
            int before = size.get();
            if (!addWorker(false)) {
                return false;
            }
            if (size.get() == before) {
                return false;
            }
        }
        return true;
    }

    /**
     * Frame-pump watchdog for genuinely blocked pools.
     *
     * <p>Growing from {@link #execute} mistook a normal burst of queued work for a
     * deadlock and reached six workers before the first task could finish. The browser
     * thread calls this once per frame instead. A rescue worker is added only when every
     * current worker is occupied, work is queued, and no task has returned for thirty
     * seconds.
     */
    public static void maintain() {
        // A real WasmLM server must never be rescued by the browser frame thread.
        // During client reload the bounded one-worker rescue remains useful; after
        // server construction all terrain work is owned by MinecraftServer itself.
        if (!BrowserReloadDiagnostics.isActive() || isServerThread()) {
            return;
        }
        growIfStalled(backgroundPool);
        growIfStalled(ioPool);
    }

    private static void growIfStalled(java.util.concurrent.ExecutorService current) {
        if (current instanceof AgentExecutorService agentPool) {
            agentPool.growIfStalled();
        }
    }

    /**
     * Runs queued background tasks on the calling thread, but only while the pool is
     * starved: every worker busy and work still waiting.
     *
     * <p>This is what breaks the world-load deadlock. Minecraft's background executor
     * legitimately receives tasks that block on *other* tasks — `CompletableFuture`
     * combinators are the whole idiom — so a pool of N workers can have all N parked
     * inside tasks whose completions are still in the queue behind them. Measured at
     * world load with the out-of-thread watchdog: three workers holding
     * `CompletableFuture$AsyncRun` / `$UniAccept`, 517 tasks queued, all three agent
     * stack pointers frozen for 132 seconds, and the primary stuck in
     * `levelload:focus minecraft:overworld [0, 0]` waiting for a chunk future.
     *
     * <p>{@link #growIfStalled} cannot rescue that, because it is driven from the frame
     * pump and the frame pump does not run while {@code doWorldLoad} is inside its
     * ready-wait — the escape hatch depends on the thing the deadlock stops. A waiter
     * that drains the queue itself does not: it needs nothing but its own thread.
     *
     * <p>Running background work on the browser thread is safe by construction here:
     * it is exactly what {@code InlineExecutorService} does for the whole WasmGC image,
     * which is the configuration that reaches gameplay today.
     *
     * @return how many tasks ran
     */
    public static int drainInlineIfStarved(int budget) {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (!(current instanceof AgentExecutorService agentPool)) {
            return 0;
        }
        return agentPool.drainInline(budget, true);
    }

    /**
     * Runs queued background tasks on the caller unconditionally.
     *
     * <p>For a thread that is *waiting* on background work rather than doing its own,
     * "is the pool starved" is the wrong question and an unreliable one: the gate reads
     * `busy` and `size` as two separate counters, so a pool that is deadlocked overall
     * still dips below the threshold whenever a worker is between tasks, and the drain
     * silently never fires. Measured that way — the world-load wait stalled at
     * `levelload:focus` with 394 tasks queued while the gate saw `busy=2 size=3`.
     *
     * <p>A waiter has nothing better to do than the work it is waiting for, which is
     * exactly what {@code InlineExecutorService} does for the whole WasmGC image.
     */
    public static int drainInlineWhileWaiting(int budget) {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (!(current instanceof AgentExecutorService agentPool)) {
            return 0;
        }
        return agentPool.drainInline(budget, false);
    }

    /**
     * Work-steal one pool task, called from the primary's {@code LockSupport.park}
     * via {@code mcwebThreads.parkDrain} (installed by {@link #installParkDrain}).
     *
     * <p>This is the cooperative-host answer to blocking. A task running inline on
     * the browser thread (via the drain) may call {@code CompletableFuture.join()},
     * which spins on {@code park()}. On a real thread that would suspend; here the
     * park is a no-op, so the spin would never run the dependency still queued
     * behind it and generation would deadlock (the threaded world-load wedge).
     * Draining one task per park lets the join-spin run its own dependency —
     * exactly what the WasmGC inline executor gets for free. The {@code RUNNING_TASK}
     * guard inside {@link #drainInline} is what keeps this safe: a task already
     * running (the one whose join is spinning) is not re-entered.
     */
    @WasmExport(value = "mcweb.client.parkDrain", comment = "Run agent-pool tasks from park")
    public static void parkDrain() {
        if (McWebRuntimeMode.isThreaded()) {
            // A real WasmLM thread must retain vanilla ownership. Workers park and
            // wake through the host; the primary's browser-safe park is a spurious
            // return. Cooperative draining is only for the no-thread WasmGC lane.
            return;
        }
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof AgentExecutorService agentPool) {
            // The primary can be parked inside Minecraft's synchronous bootstrap
            // future wait before BrowserFramePump.maintain() ever gets a turn. Let
            // the already-defined stall rescue activate an unused agent from that
            // wait, but never grow from inside a worker task.
            if (!Boolean.TRUE.equals(RUNNING_TASK.get())) {
                agentPool.growIfStalled();
            }
            // Run all available tasks, not just one. If we run only one and it
            // doesn't complete the future the caller is waiting on, the caller
            // blocks in park0() and never wakes up (lost wakeup). Running all
            // tasks ensures the future completes (or the queue empties) before
            // blocking.
            agentPool.drainInline(1024, false);
        }
    }

    /**
     * Wire the primary's park to {@link #parkDrain}. Called once, when the agent
     * pool is resolved. The hook is a JS indirection because the park lives in the
     * builder patch ({@code McWebLMThreads}), which cannot reference game classes;
     * it calls back into the game's exported {@code mcweb.client.parkDrain}.
     */
    @JSRawCall
    @JS("if(globalThis.mcWebThreads){globalThis.mcWebThreads.parkDrain="
            + "()=>getExport('mcweb.client.parkDrain')();}")
    private static native void installParkDrain();

    /** Enable the opt-in primary drain once world creation owns the synchronous wait. */
    @JSRawCall
    @JS("if(globalThis.mcWebEnablePrimaryParkDrain){globalThis.mcWebEnablePrimaryParkDrain();}")
    private static native void enablePrimaryParkDrain();

    /**
     * Called from the patched {@code BlockableEventLoop.waitForTasks}: run pool work
     * while a server thread has nothing of its own left to do.
     *
     * <p>`managedBlock` is `while (!isDone) { if (!pollTask()) waitForTasks(); }`, and it
     * is the blocking primitive behind every server-side wait — including the chunk load
     * inside spawn finding, which is where world load stops. Its queue and the agent
     * pool's are different queues, so a chunk whose continuation is stuck behind blocked
     * pool workers can never arrive no matter how long this loop spins.
     *
     * <p>Reaching `waitForTasks` means "I have nothing to do until someone else makes
     * progress". On a cooperative host that is precisely the moment to do that someone
     * else's work. The budget is small because this runs inside a tight wait loop and
     * will be called again immediately.
     */
    public static void drainInlineForBlockedWait() {
        if (McWebRuntimeMode.isThreaded()) {
            // Vanilla managedBlock already polls its owning event-loop queue. A real
            // WasmLM thread must not steal Mojang background tasks or server work.
            return;
        }
        /*
         * Never from inside a pool task. `waitForTasks` runs on whatever thread is
         * blocking, agents included, so without this guard a worker parked in
         * `managedBlock` starts running *other* pool tasks nested inside the task it is
         * already executing.
         *
         * That is not merely surprising, it is unsound: `McWebLMMonitors` keys lock
         * ownership to the agent id, on the stated assumption that an agent runs one
         * Java thread at a time, so a nested task inherits every monitor its host holds
         * and mutual exclusion silently disappears. Measured: the first build with an
         * ungated drain brought back `Registry Loading <- IllegalStateException: Failed
         * to load registries due to errors` — the exact failure this whole change set
         * started from.
         *
         * The browser thread is the one that wedges and the one that is not a pool
         * worker, so gating on "not already running a task" keeps the fix and drops the
         * hazard.
         */
        if (Boolean.TRUE.equals(RUNNING_TASK.get())) {
            return;
        }
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof AgentExecutorService agentPool) {
            // A synchronous client reload can occupy the primary inside
            // CompletableFuture.join() for the entire lifetime of the frame pump.
            // In that interval maintain() cannot run, so give the rescue path the
            // same observation point as the inline drain.
            agentPool.growIfStalled();
        }
        drainInlineWhileWaiting(8);
    }

    /**
     * Let the real Server owner request the reserved Background carrier while it
     * is inside Mojang's {@code BlockableEventLoop.managedBlock}.
     *
     * <p>This is deliberately separate from {@link #workerBlockedExport()}: a
     * managed-block loop can remain in Java without reaching {@code park()}, so a
     * pool worker's park hook cannot see the Server's dependency wait. The Server
     * still owns its event-loop queue and remains blocked on that queue; this
     * method only starts one bounded compensation worker through the normal
     * generation-tagged Thread.start path. No task is executed by the caller.</p>
     */
    public static void serverBlockingWait() {
        if (!isBoundServerThread()
                || !McWebRuntimeMode.isThreaded()
                || !(backgroundPool instanceof AgentExecutorService agentPool)) {
            return;
        }
        agentPool.compensateExternalBlock("server");
    }

    /**
     * The runServer marker is the lifecycle authority, while the current-thread
     * checks keep the global active bit from allowing a client or background
     * managedBlock to borrow the server's spare carrier.
     */
    private static boolean isBoundServerThread() {
        if (!serverThreadActive) {
            return false;
        }
        Thread current = Thread.currentThread();
        if (current.getId() == boundServerThreadId
                || boundServerThreadName.equals(current.getName())) {
            return true;
        }
        return ServerOwnedExecutorService.currentFor(current) != null;
    }

    /**
     * Called once per iteration of the patched {@code BlockableEventLoop.managedBlock}
     * loop: publish what the blocked thread and the pool are doing, to shared memory.
     *
     * <p>World load stops inside this loop, and until now every diagnostic went silent
     * with it — the loop never returns, so nothing that runs on the browser's event loop
     * can report. That left four mechanisms indistinguishable from each other, and four
     * successive guesses at which one it was were all wrong. This line separates them by
     * observation instead:</p>
     *
     * <ul>
     *   <li>counter climbing, {@code completed} climbing — the pool is working and the
     *       future simply never completes: a chunk-pipeline bug, not a threading one.</li>
     *   <li>counter climbing, {@code completed} frozen at {@code busy=N/N} — the workers
     *       are blocked, and on what they are blocked is the next question.</li>
     *   <li>counter climbing, {@code queued=0 busy=0} — nothing was ever scheduled; the
     *       dispatch into the pool is what is broken.</li>
     *   <li>counter frozen — this thread is not in the loop at all, and the wedge is
     *       deeper than {@code managedBlock} (a park that never returns).</li>
     * </ul>
     *
     * <p>The count goes in the text rather than being inferred from the marker changing,
     * because a marker that only changes on a state change cannot distinguish a slow
     * loop from a stopped one — this project has already paid for that lesson once.</p>
     *
     * <p>The loop can spin at megahertz, so diagnostics are sampled every 16,384
     * iterations and time-throttled to one shared-memory update every two seconds.
     * This channel deliberately does not call {@code reportProgress}: doing so flooded
     * the browser console roughly every 30 ms and displaced the actual chunk failure.</p>
     */
    public static void onBlockingWaitSpin(net.minecraft.util.thread.BlockableEventLoop<?> loop) {
        // A managedBlock loop is a long-lived Java frame, not a LockSupport park. It is
        // nevertheless a bounded safepoint site: an allocation-free Server wait must
        // eventually publish its stack when the primary requests a collection. Crossing
        // the JS import on *every* loop iteration made spawn preparation spend most of
        // its time in the probe itself (the timeout trace reached tens of millions of
        // iterations). Poll at a fixed cadence instead; the safepoint rendezvous budget
        // is much larger than this interval, so collection latency stays bounded without
        // turning the vanilla wait loop into a host-call benchmark.
        long spins = blockingWaitSpins.incrementAndGet();
        if ((spins & 0x3FFL) == 0L) {
            safepointPoll0();
            // The chunk-future callback observes only the first request. Later
            // CompletableFuture continuations can fill the Background queue after
            // that callback, so revisit the bounded server compensation decision
            // from the same managed-block cadence.
            if (McWebRuntimeMode.isThreaded()) {
                serverBlockingWait();
            }
        }
        /*
         * The threaded world-load wedge, and why this runs on every iteration.
         *
         * Spawn finding calls {@code ServerChunkCache.getChunk} on the browser
         * thread, which blocks in {@code MainThreadExecutor.managedBlock(isDone)}.
         * That loop's own {@code pollTask()} calls {@code runDistanceManagerUpdates()},
         * so the chunk pipeline *is* polled every iteration — but the generation
         * work it dispatches lands on this agent pool, whose workers are themselves
         * parked inside {@code CompletableFuture} combinators waiting on tasks still
         * queued behind them. {@code pollTask()} keeps returning {@code true} (the
         * distance manager always has bookkeeping), so {@code waitForTasks()} — the
         * only other place the pool is drained — is never reached. The spin busy-loops
         * forever while the pool starves. Measured: primary frozen at
         * {@code levelload:focus [0, 0]} for 200+ s, agents stuck in
         * {@code CompletableFuture$AsyncRun}/{@code $UniAccept}, tasks queued.
         *
         * Draining the pool here breaks the cycle: the queued completions run on the
         * browser thread (exactly what the inline WasmGC executor does), unblocking
         * the workers and letting the chunk future complete. The {@code RUNNING_TASK}
         * guard inside {@link #drainInlineForBlockedWait} keeps an agent that is itself
         * spinning in a {@code managedBlock} from nesting pool tasks, which would break
         * monitor exclusion.
         */
        if (McWebRuntimeMode.isCooperativeServer()) {
            try {
                drainInlineForBlockedWait();
            } catch (Throwable ignored) {
                // A drain failure must not break a cooperative wait it is servicing.
            }
        }
        if ((spins & 0xFF) == 0
                && !isServerThread()
                && !Boolean.TRUE.equals(RUNNING_TASK.get())) {
            servicePrimaryCollectionRequest("managed-block");
        }
        /*
         * ServerChunkCache.MainThreadExecutor.pollTask() is allowed to report
         * progress from its distance-manager bookkeeping even when its own
         * completion queue is empty.  In that case managedBlock never reaches
         * waitForTasks(), so the park-drain hook is not called and the chunk
         * future's main-thread continuation can sit forever.  Poll the chunk
         * source periodically from the loop head as well.  The helper only
         * touches the chunk source on this path (it does not recursively drain
         * the whole server task queue), so it is safe while the server thread is
         * inside managedBlock.
         */
        if (McWebRuntimeMode.isCooperativeServer() && (spins & 0xFF) == 0) {
            try {
                BrowserIntegratedServerCompat.pollChunkSourceFromBlockingWait();
            } catch (Throwable ignored) {
                // A probe must never break the wait it is servicing.
            }
        }
        if ((spins & 0x3FFF) != 0) {
            return;
        }
        long now = System.nanoTime();
        long priorReport = lastBlockingWaitReportNanos.get();
        if (now - priorReport < BLOCKING_WAIT_REPORT_NANOS
                || !lastBlockingWaitReportNanos.compareAndSet(priorReport, now)) {
            return;
        }
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportDiag("blockwait #" + spins
                    + " loop=" + loop.name()
                    + " own=" + loop.getPendingTasksCount()
                    + " active=" + compactBackgroundState()
                    + " " + stats());
        } catch (Throwable ignored) {
            // A probe must never be the reason a wait fails.
        }
    }

    private static final AtomicLong blockingWaitSpins = new AtomicLong();
    private static final AtomicLong lastBlockingWaitReportNanos = new AtomicLong();
    private static final long BLOCKING_WAIT_REPORT_NANOS = TimeUnit.SECONDS.toNanos(2);

    /** Bridge the game-side wait loop to the WasmLM safepoint without coupling game
     * classes to the builder patch module. The hook is a no-op on WasmGC and zero-agent
     * WasmLM images. */
    @JSRawCall
    @JS("if(globalThis.mcwebThreads&&typeof globalThis.mcwebThreads.safepointPoll==='function'){globalThis.mcwebThreads.safepointPoll();}")
    private static native void safepointPoll0();

    /**
     * Image-reachable bridge for exact Minecraft loop probes.  The browser JAR
     * transform cannot name builder-only hosted classes: those calls resolve in
     * every agent image only when they enter through an application-classpath
     * runtime seam.  Keep the actual JS boundary private so the transform has a
     * small, stable method to call.
     */
    public static void safepointPoll() {
        safepointPoll0();
    }

    @JS.Coerce
    @JS(value = "return !!(globalThis.mcWebThreadRuntime"
            + "&&globalThis.mcWebThreadRuntime.collectionRequested"
            + "&&globalThis.mcWebThreadRuntime.collectionRequested());", args = {})
    private static native boolean primaryCollectionRequested();

    /**
     * Independent liveness for the workers, on the same shared-memory ring.
     *
     * <p>{@link #onBlockingWaitSpin} reports the pool as seen by the blocked thread, so
     * it says nothing at all if that thread is the one that stopped. An agent that is
     * still turning over tasks says so here regardless of what the primary is doing,
     * which is the difference between "the pool is dead" and "the pool is fine and the
     * primary is waiting for something else".</p>
     */
    private static void reportTaskLiveness() {
        long tasks = taskStarts.incrementAndGet();
        if ((tasks & 0x3F) != 0) {
            return;
        }
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportDiag(
                    "pooltask #" + tasks + " " + queueState());
        } catch (Throwable ignored) {
            // Telemetry must never break task execution.
        }
    }

    private static final AtomicLong taskStarts = new AtomicLong();

    /**
     * True while this thread is inside a pool task, whether on a worker or run inline.
     *
     * <p>Guards {@link #drainInlineForBlockedWait} against reentrancy; see there for why
     * nesting pool tasks is unsound rather than just untidy.
     */
    private static final ThreadLocal<Boolean> RUNNING_TASK = new ThreadLocal<>();
    /** Pool lane owning the current task; used by the WasmLM park callback. */
    private static final ThreadLocal<AgentExecutorService> RUNNING_POOL = new ThreadLocal<>();
    /** Prevent repeated park calls in one wait loop from inflating blockedWorkers. */
    private static final ThreadLocal<Boolean> RUNNING_BLOCKED = new ThreadLocal<>();
    /** Physical worker slot used by the bounded in-flight phase diagnostic. */
    private static final ThreadLocal<Integer> RUNNING_WORKER_INDEX = new ThreadLocal<>();
    /** The active worldgen supplier phase, cleared on every normal/exceptional return. */
    private static final ThreadLocal<String> WORLDGEN_PHASE = new ThreadLocal<>();
    /** Last exact doCreateBiomes operation on the same callback thread as a failure. */
    private static final ThreadLocal<String> WORLDGEN_SUBPHASE = new ThreadLocal<>();

    /**
     * Whether the per-operation worldgen trace is armed (`?mcweb_worldgen_trace=1`).
     *
     * <p>These markers sit on the hottest path in the image: 25 ASM-injected call sites
     * fire inside {@code doCreateBiomes}/{@code doFill} per chunk column, and each one
     * built a String, wrote a ThreadLocal and crossed the JS boundary through {@code
     * reportDiag} — unconditionally, on every threaded run. Measured in a four-agent
     * world load, the diag ring reached sequence 61,235 and {@code chunk:get-owner}
     * alone reached n=187,392, all of it while worldgen was the thing being waited on.
     * The allocation it generates also feeds the very allocator pressure this lane is
     * trying to escape.
     *
     * <p>Read once and cached: the value cannot change during a run, and a URL parse per
     * marker would cost more than the marker it is meant to suppress. The failure
     * reporter ({@link #reportGenerationStepFailure}) is deliberately *not* gated — it
     * is already sampled and it is the marker that names a real fault.
     */
    private static volatile int worldgenTraceState;

    /**
     * Chunks currently inside a generation step, keyed by packed position.
     *
     * <p>docs/Minecraft/world/chunks.md states the invariant this tests: "Generation is
     * ordered per-chunk, parallel across chunks. A port can serialize on one worker as
     * long as future-completion order per chunk is preserved." Vanilla enforces it with
     * per-queue `ConsecutiveExecutor`s, and it is load-bearing because each step builds a
     * `NoiseChunk` whose `Cache2D`/`FlatCache`/`CacheOnce` wrappers are mutable and
     * single-threaded by design.
     *
     * <p>If the port ever runs two steps of the *same* chunk concurrently they share
     * those caches, which would produce a null deep in the density graph — exactly the
     * standing `Ap2` NPE that appears only at three or more workers and has survived
     * every other explanation (allocator, safepoint roots, publication, the pool
     * implementation). This records the overlap directly instead of inferring it.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Long, String> ACTIVE_CHUNK_STEPS =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong chunkStepOverlaps =
            new java.util.concurrent.atomic.AtomicLong();

    /** Drops whatever entry this thread owns; the exit markers carry no ChunkAccess. */
    private static void exitChunkStepsForThread() {
        String self = Thread.currentThread().getName() + ":";
        ACTIVE_CHUNK_STEPS.entrySet().removeIf(e -> e.getValue().startsWith(self));
    }

    /**
     * Serialises the first worldgen steps so class initialisation cannot race.
     *
     * <p>`tools/wasmlm-probes/LmClassInit` shows this backend does **not** hold the JLS
     * class-initialisation lock: with 3 threads racing to first-touch 8 classes, 13 of 24
     * static reads came back null (22 of 40 with 5 threads), while no value was ever
     * torn — the exact signature of "the initialiser has not assigned yet". The JLS
     * requires the first thread into a class to run {@code <clinit>} under a lock with
     * every other thread blocked.
     *
     * <p>That is the standing >2-worker worldgen NPE. Mojang's density graph is built
     * from classes dense in static finals ({@code DensityFunctions} and friends); with
     * three or more workers they are first touched concurrently, and a losing thread
     * reads a null static deep inside the graph. It fires at the first observation, on
     * the same chunk, deterministically — and it survived eight other fixes (allocator
     * pressure, two safepoint-root theories, cross-agent publication of instance finals,
     * the pool implementation, same-chunk step overlap, and this port's own density
     * hooks) because none of them touched class init.
     *
     * <p>Serialising only the opening steps is enough: once a class is initialised the
     * race is over for the lifetime of the image, and worldgen touches its whole class
     * graph within the first few chunks. Full parallelism resumes afterwards, so this
     * costs a bounded warm-up rather than throughput. It is a targeted workaround for a
     * missing VM guarantee, not a fix for it — the backend should hold a real
     * per-class init lock.
     *
     * <p><b>DISABLED 2026-08-04 — the missing guarantee is now supplied.</b> The backend
     * does hold the init lock; what it lacked was a per-agent thread identity to decide
     * who owns an in-progress {@code <clinit>}. {@code CurrentIsolate.getCurrentThread()}
     * constant-folds to one sentinel on Web Image, so every racer looked "reentrant",
     * skipped the wait and returned uninitialised.
     * {@code McWebImagePatcher.patchClassInitPublication} now rewrites that call site to
     * {@code Thread.threadId()}, and {@code LmClassInit} is 30/30 green at 3, 5 and 8
     * threads (was 12/24 and 22/40 null statics).
     *
     * <p>The mechanism is kept, not deleted: set the step count back above zero to
     * restore the workaround if a multi-worker run regresses to worldgen NPEs.
     */
    private static final int SERIALIZED_WARMUP_STEPS = 0;
    private static final java.util.concurrent.locks.ReentrantLock WARMUP_LOCK =
            new java.util.concurrent.locks.ReentrantLock();
    private static final java.util.concurrent.atomic.AtomicInteger warmupSteps =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final ThreadLocal<Boolean> HOLDS_WARMUP = new ThreadLocal<>();

    private static void beginWarmupStep() {
        if (!McWebRuntimeMode.isThreaded()
                || warmupSteps.get() >= SERIALIZED_WARMUP_STEPS
                || Boolean.TRUE.equals(HOLDS_WARMUP.get())) {
            return;
        }
        WARMUP_LOCK.lock();
        HOLDS_WARMUP.set(Boolean.TRUE);
    }

    private static void endWarmupStep() {
        if (!Boolean.TRUE.equals(HOLDS_WARMUP.get())) {
            return;
        }
        HOLDS_WARMUP.remove();
        int done = warmupSteps.incrementAndGet();
        WARMUP_LOCK.unlock();
        if (done == SERIALIZED_WARMUP_STEPS) {
            report("worldgen:warmup-complete steps=" + done + " parallelism=unrestricted");
        }
    }

    /** Marks a chunk as being generated; reports if another thread is already in it. */
    private static void enterChunkStep(net.minecraft.world.level.chunk.ChunkAccess chunk, String step) {
        if (chunk == null || chunk.getPos() == null) {
            return;
        }
        long packed = chunk.getPos().pack();
        String self = Thread.currentThread().getName();
        String prior = ACTIVE_CHUNK_STEPS.putIfAbsent(packed, self + ":" + step);
        if (prior != null && !prior.startsWith(self + ":")) {
            long n = chunkStepOverlaps.incrementAndGet();
            if (n <= 32) {
                reportDiagnostic("worldgen:CHUNK-STEP-OVERLAP n=" + n
                        + " chunk=" + chunk.getPos().x() + "," + chunk.getPos().z()
                        + " holder=" + prior
                        + " intruder=" + self + ":" + step);
            }
        }
    }

    /** Overlapping same-chunk generation steps observed; 0 means the invariant holds. */
    public static long chunkStepOverlaps() {
        return chunkStepOverlaps.get();
    }

    /** Null `PureTransformer.input()` observations, caught on the generating thread. */
    private static final java.util.concurrent.atomic.AtomicLong nullTransformerInputs =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * Resolve the trace flag on the browser thread, before any agent can ask.
     *
     * <p>An agent runs in a Worker whose {@code globalThis.location} is the *worker
     * script* URL and carries none of the page's query string, so whichever thread
     * happened to resolve the flag first could cache "off" for the whole image. A run
     * launched with `?mcweb_worldgen_trace=1` produced no trace markers at all because
     * of exactly that. The state word lives in shared linear memory, so priming it from
     * the page makes every agent observe the page's answer.
     */
    public static void primeWorldgenTrace() {
        worldgenTraceEnabled();
    }

    private static boolean worldgenTraceEnabled() {
        int state = worldgenTraceState;
        if (state == 0) {
            try {
                state = dev.mcweb.graal.webgpu.BrowserGpu.worldgenTraceEnabled() != 0 ? 2 : 1;
            } catch (Throwable ignored) {
                state = 1;
            }
            worldgenTraceState = state;
        }
        return state == 2;
    }

    /**
     * Bounded observations at the two points where the generation graph can lose
     * visibility: a task handing its next pending layer back to the scheduler and a
     * holder publishing the result of one status future.  These are deliberately
     * counters rather than per-task state; the watchdog ring is small and must not
     * become another source of heap pressure while the world is loading.
     */
    private static final AtomicLong generationTaskWaitObservations = new AtomicLong();
    private static final AtomicLong generationFutureCompleteObservations = new AtomicLong();
    private static final AtomicLong generationStepFailureObservations = new AtomicLong();

    /**
     * Entry marker injected into Mojang's NoiseBasedChunkGenerator supplier.
     *
     * <p>All active pool tasks otherwise have the same JDK class name
     * ({@code CompletableFuture$AsyncSupply}).  This bounded marker names the actual
     * worldgen body and chunk position in the external watchdog without relying on Java
     * stack traces, which are empty in Web Image.</p>
     */
    public static void reportWorldgenEnter(
            net.minecraft.world.level.chunk.ChunkAccess chunk
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        String position;
        try {
            net.minecraft.world.level.ChunkPos pos = chunk.getPos();
            position = pos.x() + "," + pos.z();
        } catch (Throwable ignored) {
            position = "unknown";
        }
        String phase = "noise chunk=" + position;
        WORLDGEN_PHASE.set(phase);
        setRunningPhase(phase);
        boolean trace = worldgenTraceEnabled();
        if (trace) {
            enterChunkStep(chunk, "noise");
        }
        beginWarmupStep();
        if (!trace) {
            return;
        }
        reportDiagnostic("worldgen:noise-enter " + phase
                + " thread=" + Thread.currentThread().getName());
    }

    /** Exit marker paired with {@link #reportWorldgenEnter}; safe on exceptional exit. */
    public static void reportWorldgenExit() {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        boolean trace = worldgenTraceEnabled();
        if (trace) {
            exitChunkStepsForThread();
        }
        endWarmupStep();
        String phase = WORLDGEN_PHASE.get();
        if (trace) {
            reportDiagnostic("worldgen:noise-exit " + (phase == null ? "unknown" : phase)
                    + " thread=" + Thread.currentThread().getName());
        }
        WORLDGEN_PHASE.remove();
        setRunningPhase(null);
    }

    /**
     * Entry marker for the separate asynchronous biome-generation supplier.
     * Keeping this phase distinct from noise filling makes a missing exit marker
     * identify the exact supplier body that raised the first CompletionException.
     */
    public static void reportWorldgenBiomesEnter(
            net.minecraft.world.level.chunk.ChunkAccess chunk
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        String position;
        try {
            net.minecraft.world.level.ChunkPos pos = chunk.getPos();
            position = pos.x() + "," + pos.z();
        } catch (Throwable ignored) {
            position = "unknown";
        }
        String phase = "biomes chunk=" + position;
        WORLDGEN_PHASE.set(phase);
        WORLDGEN_SUBPHASE.set("enter");
        setRunningPhase(phase);
        boolean trace = worldgenTraceEnabled();
        if (trace) {
            enterChunkStep(chunk, "biomes");
        }
        beginWarmupStep();
        if (!trace) {
            return;
        }
        reportDiagnostic("worldgen:biomes-enter " + phase
                + " thread=" + Thread.currentThread().getName());
    }

    /** Exit marker for the asynchronous biome-generation supplier. */
    public static void reportWorldgenBiomesExit() {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        boolean trace = worldgenTraceEnabled();
        if (trace) {
            exitChunkStepsForThread();
        }
        endWarmupStep();
        String phase = WORLDGEN_PHASE.get();
        WORLDGEN_SUBPHASE.set("complete");
        if (trace) {
            reportDiagnostic("worldgen:biomes-exit " + (phase == null ? "unknown" : phase)
                    + " thread=" + Thread.currentThread().getName());
        }
        WORLDGEN_PHASE.remove();
        WORLDGEN_SUBPHASE.remove();
        setRunningPhase(null);
    }

    /**
     * Names the next exact operation in NoiseBasedChunkGenerator.doCreateBiomes.
     * The marker is emitted immediately before Mojang's call, so a missing later
     * phase identifies the call that raised the asynchronous NPE.
     */
    public static void reportWorldgenBiomesPhase(int phase) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        // The phase label is only useful for an explicitly traced run. Keeping it out of
        // the ordinary path avoids a String build and ThreadLocal write at every injected
        // phase site. A null receiver below still reconstructs the exact fault label.
        if (!worldgenTraceEnabled()) {
            return;
        }
        String name = describeWorldgenBiomesPhase(phase);
        WORLDGEN_SUBPHASE.set(name);
        reportDiagnostic("worldgen:biomes-phase " + name
                + " " + (WORLDGEN_PHASE.get() == null ? "unknown" : WORLDGEN_PHASE.get())
                + " thread=" + Thread.currentThread().getName());
    }

    /**
     * Records the receiver/context classes for the preliminary density call.
     * This is deliberately silent on the success path: preliminary surface
     * sampling is invoked many times per chunk.  The current value is included
     * only if the surrounding generation future later reports a failure.
     */
    public static void reportWorldgenBiomesObjects(
            int phase,
            Object receiver,
            Object context
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        // Successful calls are silent in the normal path. The receiver/context label is
        // only needed to diagnose the null-input fault, so retain it for that rare case
        // and for explicitly traced runs. This removes a String build, class lookup and
        // ThreadLocal write from every injected density call while preserving the
        // evidence that made the original hook useful.
        boolean trace = worldgenTraceEnabled();
        if (!trace && receiver != null) {
            return;
        }
        WORLDGEN_SUBPHASE.set(describeWorldgenBiomesPhase(phase)
                + ":receiver=" + describeWorldgenObject(receiver)
                + ":context=" + describeWorldgenObject(context));
        // Catch the fault at the throw site, on the throwing thread.
        //
        // `reportGenerationStepFailure` reads WORLDGEN_SUBPHASE on whichever thread
        // *reports* the future's completion, which is routinely not the thread that
        // threw — so the `p=` field it prints is a stale label and cannot attribute the
        // NPE. Two rebuilds were spent learning that. This hook, by contrast, runs
        // inline on the generating thread immediately before the failing
        // `DensityFunction.compute` call, so a null here is the fault itself rather
        // than a label near it.
        if (receiver == null) {
            long observation = nullTransformerInputs.incrementAndGet();
            if (observation <= 16) {
                reportDiagnostic("worldgen:null-transformer-input n=" + observation
                        + " phase=" + describeWorldgenBiomesPhase(phase)
                        + " context=" + describeWorldgenObject(context)
                        + " chunkPhase=" + (WORLDGEN_PHASE.get() == null
                                ? "unknown" : WORLDGEN_PHASE.get())
                        + " thr=" + Thread.currentThread().getName()
                        + '/' + Thread.currentThread().getId());
            }
        }
    }

    private static String describeWorldgenObject(Object value) {
        if (value == null) {
            return "null";
        }
        String name = value.getClass().getName();
        int last = name.lastIndexOf('$');
        return last >= 0 ? name.substring(last + 1) : name.substring(name.lastIndexOf('.') + 1);
    }

    private static String describeWorldgenBiomesPhase(int phase) {
        switch (phase) {
            case 1:
                return "before-noise-chunk";
            case 2:
                return "before-blender-resolver";
            case 3:
                return "before-retrogen-resolver";
            case 4:
                return "before-climate-sampler";
            case 5:
                return "before-fill-biomes";
            case 6:
                return "complete";
            case 21:
                return "noise-settings-noiseSettings";
            case 22:
                return "noise-chunk-pos";
            case 23:
                return "noise-settings-cellWidth";
            case 24:
                return "noise-before-constructor";
            case 31:
                return "constructor-cellWidth";
            case 32:
                return "constructor-cellHeight";
            case 33:
                return "constructor-height";
            case 34:
                return "constructor-minY";
            case 35:
                return "constructor-blender-empty";
            case 36:
                return "constructor-router";
            case 37:
                return "constructor-router-mapAll";
            case 38:
                return "constructor-aquifers-enabled";
            case 39:
                return "constructor-aquifer-disabled";
            case 40:
                return "constructor-aquifer-create";
            case 41:
                return "aquifer-before-noise-based-constructor";
            case 42:
                return "aquifer-before-disabled-constructor";
            case 43:
                return "noise-aquifer-enter";
            case 44:
                return "noise-aquifer-barrier";
            case 45:
                return "noise-aquifer-floodedness";
            case 46:
                return "noise-aquifer-spread";
            case 47:
                return "noise-aquifer-lava";
            case 48:
                return "noise-aquifer-erosion";
            case 49:
                return "noise-aquifer-depth";
            case 50:
                return "noise-aquifer-surface";
            case 51:
                return "noise-aquifer-cache-fill";
            case 52:
                return "noise-surface-enter";
            case 53:
                return "noise-surface-before-column";
            case 54:
                return "noise-surface-cache-call";
            case 55:
                return "noise-surface-compute-enter";
            case 56:
                return "noise-surface-before-density";
            case 57:
                return "noise-surface-receivers";
            case 59:
                return "find-top-upper-bound";
            case 60:
                return "find-top-density";
            case 61:
                return "pure-transformer-input";
            case 11:
                return "noise-before-structure-lookup";
            case 12:
                return "noise-before-settings";
            case 13:
                return "noise-before-fluid-picker";
            case 14:
                return "noise-before-create";
            default:
                return "unknown";
        }
    }

    /**
     * Observe the future returned by Mojang's generation task before its caller
     * waits or re-drives it.  This is intentionally observation-only: the first
     * repair must distinguish a lost completion from a task that was never
     * scheduled without changing the generation graph underneath the JAR.
     */
    public static void reportGenerationTaskWait(
            net.minecraft.server.level.ChunkGenerationTask task,
            java.util.concurrent.CompletableFuture<?> future
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        long observation = generationTaskWaitObservations.incrementAndGet();
        if (!sampleGenerationObservation(observation)) {
            return;
        }
        try {
            net.minecraft.server.level.GenerationChunkHolder center = task.getCenter();
            String position = center == null || center.getPos() == null
                    ? "unknown"
                    : center.getPos().x() + "," + center.getPos().z();
            String holderState = describeGenerationHolder(center);
            Thread current = Thread.currentThread();
            reportDiagnostic("worldgen:task-wait n=" + observation
                    + " center=" + position
                    + " target=" + task.targetStatus
                    + " future=" + describeGenerationFuture(future)
                    + " holder=" + holderState
                    + " thread=" + current.getName() + '/' + current.getId()
                    + " pool=" + queueState());
        } catch (Throwable ignored) {
            // Diagnostics must not alter ChunkGenerationTask's return value.
        }
    }

    /**
     * Observe the boolean result of GenerationChunkHolder's actual future
     * publication.  A false result is as important as a true one: it means the
     * graph attempted to publish a status that had already been completed or
     * failed, rather than merely making the operation look successful.
     */
    public static void reportGenerationFutureComplete(
            net.minecraft.server.level.GenerationChunkHolder holder,
            net.minecraft.world.level.chunk.status.ChunkStatus status,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            boolean completed
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        long observation = generationFutureCompleteObservations.incrementAndGet();
        if (!sampleGenerationObservation(observation)) {
            return;
        }
        try {
            String holderPosition = holder == null || holder.getPos() == null
                    ? "unknown"
                    : holder.getPos().x() + "," + holder.getPos().z();
            String chunkPosition = chunk == null || chunk.getPos() == null
                    ? "null"
                    : chunk.getPos().x() + "," + chunk.getPos().z();
            Thread current = Thread.currentThread();
            reportDiagnostic("worldgen:future-complete n=" + observation
                    + " holder=" + holderPosition
                    + " status=" + status
                    + " chunk=" + chunkPosition
                    + " completed=" + completed
                    + " latest=" + (holder == null ? "null" : holder.getLatestStatus())
                    + " persisted=" + (holder == null ? "null" : holder.getPersistedStatus())
                    + " futures=" + describeGenerationFutures(holder)
                    + " thread=" + current.getName() + '/' + current.getId()
                    + " pool=" + queueState());
        } catch (Throwable ignored) {
            // The generation result has already been published; keep this best effort.
        }
    }

    /**
     * Records the exceptional branch that bypasses GenerationChunkHolder's future
     * publication.  Vanilla deliberately relays this failure asynchronously and
     * returns a null ChunkResult, so without this marker the owner only sees a
     * permanently pending FULL future and cannot distinguish it from a lost wake.
     */
    public static void reportGenerationStepFailure(
            net.minecraft.server.level.GenerationChunkHolder holder,
            net.minecraft.world.level.chunk.status.ChunkStep step,
            net.minecraft.world.level.chunk.ChunkAccess chunk,
            Throwable failure
    ) {
        if (!McWebRuntimeMode.isThreaded() || failure == null) {
            return;
        }
        long observation = generationStepFailureObservations.incrementAndGet();
        if (!sampleGenerationObservation(observation)) {
            return;
        }
        try {
            String holderPosition = holder == null || holder.getPos() == null
                    ? "unknown"
                    : holder.getPos().x() + "," + holder.getPos().z();
            String chunkPosition = chunk == null || chunk.getPos() == null
                    ? "null"
                    : chunk.getPos().x() + "," + chunk.getPos().z();
            String cause = describeGenerationFailure(failure);
            Thread current = Thread.currentThread();
            // Keep this marker compact enough for both the normal beacon ring and
            // the sticky external-watchdog record. The cause chain is the useful
            // discriminator; the pool summary follows as a separate marker.
            reportDiagnostic("worldgen:step-failure n=" + observation
                    + " h=" + holderPosition
                    + " t=" + (step == null ? "null" : step.targetStatus())
                    + " p=" + (WORLDGEN_SUBPHASE.get() == null
                    ? "unknown" : WORLDGEN_SUBPHASE.get())
                    + " x=" + (WORLDGEN_PHASE.get() == null
                    ? "unknown" : WORLDGEN_PHASE.get())
                    + " c=" + cause
                    + " chunk=" + chunkPosition
                    + " thr=" + current.getName() + '/' + current.getId());
            reportDiagnostic("worldgen:step-failure-pool n=" + observation
                    + ' ' + queueState());
        } catch (Throwable ignored) {
            // The failure is still relayed by Mojang's original handler.
        }
    }

    private static boolean sampleGenerationObservation(long observation) {
        // The first observations describe bootstrap, while periodic samples retain
        // evidence after the ring has moved on to the large terrain workload.
        return observation <= 64 || (observation & 0x1FF) == 0;
    }

    private static String describeGenerationFailure(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 4) {
            if (result.length() != 0) {
                result.append('/');
            }
            String name = current.getClass().getName();
            int separator = name.lastIndexOf('.');
            result.append(separator < 0 ? name : name.substring(separator + 1));
            if (depth == 1) {
                String message = current.getMessage();
                if (message != null && !message.isEmpty()) {
                    result.append(':');
                    for (int i = 0; i < message.length() && i < 80; i++) {
                        char c = message.charAt(i);
                        result.append(c < 32 || c > 126 ? ' ' : c);
                    }
                }
            }
            current = current.getCause();
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }

    private static String describeGenerationHolder(
            net.minecraft.server.level.GenerationChunkHolder holder
    ) {
        if (holder == null) {
            return "null";
        }
        return "latest=" + holder.getLatestStatus()
                + "/persisted=" + holder.getPersistedStatus()
                + "/futures=" + describeGenerationFutures(holder);
    }

    private static String describeGenerationFutures(
            net.minecraft.server.level.GenerationChunkHolder holder
    ) {
        if (holder == null) {
            return "null";
        }
        try {
            StringBuilder states = new StringBuilder();
            for (com.mojang.datafixers.util.Pair<
                    net.minecraft.world.level.chunk.status.ChunkStatus,
                    java.util.concurrent.CompletableFuture<
                            net.minecraft.server.level.ChunkResult<
                                    net.minecraft.world.level.chunk.ChunkAccess>>> entry
                    : holder.getAllFutures()) {
                if (states.length() != 0) {
                    states.append(',');
                }
                states.append(entry.getFirst()).append('=').append(
                        describeGenerationFuture(entry.getSecond()));
            }
            return states.length() == 0 ? "empty" : states.toString();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    private static String describeGenerationFuture(
            java.util.concurrent.CompletableFuture<?> future
    ) {
        if (future == null) {
            return "null";
        }
        String state = future.isCancelled()
                ? "cancelled"
                : future.isCompletedExceptionally()
                        ? "failed"
                        : future.isDone() ? "done" : "pending";
        return state + "/deps=" + future.getNumberOfDependents();
    }

    /**
     * Records the first missing dependency behind Mojang's worldgen failure.
     *
     * <p>This is deliberately called at the vanilla throw site, not from a
     * replacement scheduler. A WorldGenRegion is built around a bounded cache of
     * generation holders; when a task asks for a chunk whose direct dependency is
     * absent, the resulting CrashReport is otherwise only visible after the Server
     * owner has already blocked on the FULL future.</p>
     */
    public static void reportWorldgenRegionMissing(
            net.minecraft.server.level.WorldGenRegion region,
            int x,
            int z,
            net.minecraft.world.level.chunk.status.ChunkStatus requested,
            net.minecraft.world.level.chunk.status.ChunkStep step,
            net.minecraft.server.level.GenerationChunkHolder holder
    ) {
        if (!McWebRuntimeMode.isThreaded()) {
            return;
        }
        try {
            net.minecraft.world.level.ChunkPos center = region.getCenter();
            int distance = center.getChessboardDistance(x, z);
            net.minecraft.world.level.chunk.status.ChunkStatus required = null;
            if (step != null && distance < step.directDependencies().size()) {
                required = step.directDependencies().get(distance);
            }
            String holderState = holder == null
                    ? "none"
                    : String.valueOf(holder.getPos())
                            + "/latest=" + holder.getLatestStatus()
                            + "/persisted=" + holder.getPersistedStatus()
                            + "/has=" + (required != null
                            && holder.getChunkIfPresentUnchecked(required) != null);
            String marker = "worldgen:missing center=" + center.x() + ',' + center.z()
                    + " request=" + x + ',' + z
                    + " distance=" + distance
                    + " need=" + required
                    + " requested=" + requested
                    + " target=" + (step == null ? "null" : step.targetStatus())
                    + " holder=" + holderState
                    + " thread=" + Thread.currentThread().getName()
                    + '/' + Thread.currentThread().getId();
            reportDiagnostic(marker);
            // This is a bounded failure marker, so retain it in the normal beacon
            // too; the test-side stage dump can correlate it with the owning task.
            report(marker);
            reportDiagnostic("worldgen:missing-pool " + queueState());
        } catch (Throwable ignored) {
            // The failure itself must remain Mojang's failure; diagnostics are best
            // effort and cannot be allowed to replace its ChunkResult.
        }
    }

    private static void setRunningPhase(String phase) {
        AgentExecutorService pool = RUNNING_POOL.get();
        Integer index = RUNNING_WORKER_INDEX.get();
        if (pool != null && index != null && index >= 0 && index < MAX_WORKERS) {
            pool.inFlightPhase.set(index, phase);
        }
    }

    private static void reportDiagnostic(String message) {
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportDiag(message);
        } catch (Throwable ignored) {
            // Diagnostics must never change worldgen semantics.
        }
    }

    @WasmExport(value = "mcweb.executor.workerBlocked", comment = "Current pool worker entered a blocking park")
    public static void workerBlockedExport() {
        AgentExecutorService pool = RUNNING_POOL.get();
        if (pool == null || Boolean.TRUE.equals(RUNNING_BLOCKED.get())) {
            return;
        }
        RUNNING_BLOCKED.set(Boolean.TRUE);
        Integer index = RUNNING_WORKER_INDEX.get();
        if (index != null && index >= 0 && index < MAX_WORKERS) {
            pool.inFlightBlocked.set(index, 1);
        }
        pool.blockedWorkers.incrementAndGet();
        pool.compensateBlockedWorker();
    }

    @WasmExport(value = "mcweb.executor.workerUnblocked", comment = "Current pool worker left a blocking park")
    public static void workerUnblockedExport() {
        AgentExecutorService pool = RUNNING_POOL.get();
        if (pool == null || !Boolean.TRUE.equals(RUNNING_BLOCKED.get())) {
            return;
        }
        RUNNING_BLOCKED.set(Boolean.FALSE);
        Integer index = RUNNING_WORKER_INDEX.get();
        if (index != null && index >= 0 && index < MAX_WORKERS) {
            pool.inFlightBlocked.set(index, 0);
        }
        pool.blockedWorkers.updateAndGet(current -> Math.max(0, current - 1));
    }

    /** Runs one task with the reentrancy guard set. */
    private void runGuarded(Runnable task) {
        reportTaskLiveness();
        Boolean previous = RUNNING_TASK.get();
        AgentExecutorService previousPool = RUNNING_POOL.get();
        Boolean previousBlocked = RUNNING_BLOCKED.get();
        RUNNING_TASK.set(Boolean.TRUE);
        RUNNING_POOL.set(this);
        RUNNING_BLOCKED.set(Boolean.FALSE);
        try {
            task.run();
        } finally {
            RUNNING_TASK.set(previous);
            if (previousPool == null) {
                RUNNING_POOL.remove();
            } else {
                RUNNING_POOL.set(previousPool);
            }
            if (previousBlocked == null) {
                RUNNING_BLOCKED.remove();
            } else {
                RUNNING_BLOCKED.set(previousBlocked);
            }
        }
    }

    /** Diagnostics for the world-load wait: what the pool looks like right now. */
    public static String queueState() {
        java.util.concurrent.ExecutorService current = backgroundPool;
        return "background=" + laneState(current)
                + " io=" + laneState(ioPool);
    }

    /**
     * Compact attribution for the external watchdog's fixed-width diagnostic ring.
     *
     * <p>{@link #queueState()} is intentionally detailed for page-side markers, but
     * the watchdog keeps only a small prefix of each diagnostic string. The old prefix
     * ended at {@code AsyncSupply}, which could not distinguish a running supplier from
     * a worker blocked on a dependency. This form names the worker slot, task identity,
     * physical Java thread, blocked/running state, age, and the exact worldgen phase
     * when one has been published.</p>
     */
    private static String compactBackgroundState() {
        java.util.concurrent.ExecutorService current = backgroundPool;
        if (current instanceof ForkJoinExecutorService forkJoin) {
            return forkJoin.compactState();
        }
        if (!(current instanceof AgentExecutorService agentPool)) {
            return "inline";
        }
        return agentPool.compactState();
    }

    private String compactState() {
        StringBuilder out = new StringBuilder(112);
        out.append("q=").append(pendingTasks.get())
                .append(" b=").append(busy.get()).append('/').append(size.get())
                .append(" x=").append(blockedWorkers.get())
                .append(" [");
        long now = System.nanoTime();
        boolean first = true;
        for (int i = 0; i < MAX_WORKERS; i++) {
            String task = inFlight.get(i);
            if (task == null) {
                continue;
            }
            if (!first) {
                out.append(',');
            }
            first = false;
            int threadMarker = task.indexOf(" thread=");
            String taskClass = threadMarker < 0 ? task : task.substring(0, threadMarker);
            int separator = Math.max(taskClass.lastIndexOf('.'), taskClass.lastIndexOf('$'));
            if (separator >= 0 && separator + 1 < taskClass.length()) {
                taskClass = taskClass.substring(separator + 1);
            }
            if (taskClass.length() > 18) {
                taskClass = taskClass.substring(0, 18);
            }
            out.append(i).append('=').append(taskClass)
                    .append('#').append(Integer.toHexString(inFlightHash.get(i)))
                    .append(inFlightBlocked.get(i) != 0 ? 'B' : 'R')
                    .append('@').append(inFlightThread.get(i));
            long started = inFlightStartedNanos.get(i);
            if (started != 0L) {
                out.append('+').append(Math.max(0L, (now - started) / 1_000_000_000L)).append('s');
            }
            String phase = inFlightPhase.get(i);
            if (phase != null) {
                out.append(':');
                for (int j = 0; j < phase.length() && j < 20; j++) {
                    char c = phase.charAt(j);
                    out.append(c == ' ' ? '_' : c);
                }
            }
            if (out.length() >= 104) {
                out.append("...");
                break;
            }
        }
        return out.append(']').toString();
    }

    private static String laneState(java.util.concurrent.ExecutorService current) {
        if (current instanceof ForkJoinExecutorService forkJoin) {
            return "queued=" + forkJoin.pool.getQueuedSubmissionCount()
                    + " busy=" + forkJoin.pool.getActiveThreadCount()
                    + "/" + forkJoin.pool.getPoolSize()
                    + " active=" + forkJoin.pool.getRunningThreadCount()
                    + " completed=" + forkJoin.completed.get()
                    + " failed=" + forkJoin.failed.get()
                    + " steals=" + forkJoin.pool.getStealCount();
        }
        if (!(current instanceof AgentExecutorService agentPool)) {
            return "inline";
        }
        return "queued=" + agentPool.pendingTasks.get()
                + " busy=" + agentPool.busy.get()
                + "/" + agentPool.size.get()
                + " active=[" + agentPool.stuckWorkers() + ']'
                + " completed=" + agentPool.completed.get()
                + " failed=" + agentPool.failed.get()
                + " polls=" + agentPool.pollCount.get()
                + " emptyPolls=" + agentPool.emptyPollCount.get()
                + " parks=" + agentPool.idleParkCount.get()
                + " wakes=" + agentPool.wakeRequestCount.get()
                + " blocked=" + agentPool.blockedWorkers.get()
                + " compensation=" + agentPool.compensationStarts.get()
                + " startsFailed=" + workerStartFailures.get()
                + (agentPool.lastWorkerStartFailure.isEmpty()
                        ? "" : " lastStart=" + agentPool.lastWorkerStartFailure);
    }

    private int drainInline(int budget, boolean onlyWhenStarved) {
        /*
         * A pool task may reach LockSupport.park while it waits for another
         * CompletableFuture stage. Never run a second pool task on that same Java
         * thread: the monitor table keys ownership to the agent id and assumes one
         * task at a time per agent. The primary is subject to the same rule when a
         * task was already drained inline onto it.
         */
        if (Boolean.TRUE.equals(RUNNING_TASK.get())
                || stopping || pendingTasks.get() <= 0
                || (onlyWhenStarved && busy.get() < size.get())) {
            return 0;
        }
        int ran = 0;
        while (ran < budget) {
            Runnable task = queue.poll();
            if (task == null) {
                break;
            }
            pendingTasks.decrementAndGet();
            try {
                runGuarded(task);
                completed.incrementAndGet();
            } catch (Throwable failure) {
                failed.incrementAndGet();
                report("executor:inline-task-failed " + failure.getClass().getSimpleName());
            }
            lastCompletionNanos.set(System.nanoTime());
            ran++;
        }
        if (ran > 0) {
            inlineRuns.addAndGet(ran);
        }
        return ran;
    }

    /** Tasks the starved-pool fallback has had to run on the caller. */
    private final AtomicInteger inlineRuns = new AtomicInteger();

    private void growIfStalled() {
        int currentSize = size.get();
        int growthLimit = maxWorkers;
        if (stopping || !workersEnabled || !compensationEnabled
                || currentSize >= growthLimit || pendingTasks.get() <= 0
                || busy.get() < currentSize) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastCompletionNanos.get() < STALL_NANOS
                || now - lastGrowthNanos.get() < STALL_NANOS
                || !lastGrowthNanos.compareAndSet(lastGrowthNanos.get(), now)) {
            return;
        }
        if (addWorker(true)) {
            compensationStarts.incrementAndGet();
            report("executor:stall-rescue workers=" + size.get()
                    + " queued=" + pendingTasks.get());
        }
    }

    /**
     * Activate the one reserved physical carrier when a real worker blocks on a
     * dependency and queued work can make progress. The blocked Java thread keeps
     * its monitor/stack ownership; the new worker receives an ordinary executor
     * task through the same shared start queue. No task is run on the waiter.
     */
    private void compensateBlockedWorker() {
        compensateExternalBlock("worker");
    }

    private void compensateExternalBlock(String blocker) {
        if (stopping || !workersEnabled || !compensationEnabled || baseWorkers <= 0
                || pendingTasks.get() <= 0
                || size.get() < baseWorkers
                || size.get() >= maxWorkers
                || busy.get() < size.get()) {
            return;
        }
        long now = System.nanoTime();
        long previous = lastCompensationNanos.get();
        if (now - previous < TimeUnit.MILLISECONDS.toNanos(1)
                || !lastCompensationNanos.compareAndSet(previous, now)) {
            return;
        }
        if (addWorker(true)) {
            compensationStarts.incrementAndGet();
            report("executor:" + lane + ":compensation-start workers=" + size.get()
                    + " base=" + baseWorkers
                    + " blocker=" + blocker
                    + " blocked=" + blockedWorkers.get()
                    + " queued=" + pendingTasks.get());
        }
    }

    private void workerLoop() {
        String name = Thread.currentThread().getName();
        int index = Math.max(0, size.get() - 1);
        for (int i = 0; i < workers.size(); i++) {
            if (workers.get(i) == Thread.currentThread()) {
                index = i;
                break;
            }
        }
        int ran = 0;
        while (!stopping) {
            pollCount.incrementAndGet();
            Runnable task = queue.poll();
            if (task == null) {
                emptyPollCount.incrementAndGet();
                /*
                 * McWebLMThreads.park publishes this worker's stack and marks it parked
                 * before entering Atomics.wait. It is therefore already a valid GC
                 * safepoint and does not need to wake every 10 ms. The old polling park
                 * kept six idle WebAssembly instances hot forever and measurably stole
                 * frame time from Minecraft's browser thread.
                 */
                idleParkCount.incrementAndGet();
                LockSupport.park();
                continue;
            }
            pendingTasks.decrementAndGet();
            // Published so a worker that never returns names the task holding it. A
            // single such task is fatal, not merely slow: Mojang's reload barrier waits
            // for *every* listener to finish preparing, so one wedged worker leaves the
            // client on its loading screen for good.
            boolean probe = BrowserReloadDiagnostics.isActive();
            String taskName = probe
                    ? BrowserReloadDiagnostics.agentTaskStarted(task)
                    : task.getClass().getName();
            inFlight.set(index, taskName + " thread=" + Thread.currentThread().getId());
            inFlightPhase.set(index, null);
            inFlightBlocked.set(index, 0);
            inFlightHash.set(index, System.identityHashCode(task));
            inFlightThread.set(index, Thread.currentThread().getId());
            inFlightStartedNanos.set(index, System.nanoTime());
            RUNNING_WORKER_INDEX.set(index);
            busy.incrementAndGet();
            Throwable taskFailure = null;
            try {
                runGuarded(task);
                completed.incrementAndGet();
            } catch (Throwable failure) {
                taskFailure = failure;
                failed.incrementAndGet();
                report("executor:task-failed " + rootFirstFailure(failure));
            } finally {
                busy.decrementAndGet();
                inFlight.set(index, null);
                inFlightPhase.set(index, null);
                inFlightBlocked.set(index, 0);
                inFlightHash.set(index, 0);
                inFlightThread.set(index, 0L);
                inFlightStartedNanos.set(index, 0L);
                RUNNING_WORKER_INDEX.remove();
                lastCompletionNanos.set(System.nanoTime());
                if (probe) {
                    BrowserReloadDiagnostics.agentTaskFinished(task, taskFailure);
                }
            }
            if (ran < 2) {
                report("executor:" + name + " first" + ran + " " + taskName);
            }
            // Per-worker, and named: this is the evidence that Java really ran on
            // another OS thread rather than inline on the browser thread. The name is
            // read from Thread.currentThread(), so a marker naming this worker also
            // proves the per-agent VM thread-locals resolved to the right thread.
            if (++ran % 256 == 1) {
                report("executor:" + lane + ":" + name + " ran=" + ran + " queued=" + pendingTasks.get()
                        + " workers=" + size.get() + " busy=" + busy.get()
                        + " stuck=" + stuckWorkers());
            }
        }
        report("executor:" + name + " stopped ran=" + ran);
    }

    /** Put the actionable root message before the bounded agent beacon truncates it. */
    private static String rootFirstFailure(Throwable failure) {
        Throwable root = failure;
        int depth = 0;
        while (root.getCause() != null && root.getCause() != root && depth++ < 8) {
            root = root.getCause();
        }
        StringBuilder detail = new StringBuilder("root=")
                .append(root.getClass().getName());
        String message = root.getMessage();
        if (message != null && !message.isEmpty()) {
            detail.append(": ").append(message);
        }
        if (root != failure) {
            detail.append(" <- outer=").append(failure.getClass().getName());
            String outerMessage = failure.getMessage();
            if (outerMessage != null && !outerMessage.isEmpty()) {
                detail.append(": ").append(outerMessage);
            }
        }
        // A failed executor command otherwise leaves only the message in the shared
        // beacon.  Keep the first few frames as bounded attribution: the worldgen
        // pipeline has several distinct futures that can surface the same exception,
        // and changing scheduling before identifying the owning call site hides the
        // first real invariant violation.  This is only reached on a task failure, so
        // the diagnostic allocation is not part of the normal executor path.
        StackTraceElement[] trace = root.getStackTrace();
        int frames = Math.min(6, trace == null ? 0 : trace.length);
        for (int i = 0; i < frames; i++) {
            detail.append(" @").append(trace[i]);
        }
        return detail.toString();
    }

    /** Tasks currently in flight, by worker index; the wedged ones never change. */
    private String stuckWorkers() {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < MAX_WORKERS; i++) {
            String task = inFlight.get(i);
            if (task != null) {
                out.append(i).append('=').append(task);
                String phase = inFlightPhase.get(i);
                if (phase != null) {
                    out.append(" phase=").append(phase);
                }
                out.append(' ');
            }
        }
        return out.toString();
    }

    private static void report(String message) {
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(message);
        } catch (Throwable ignored) {
            // Telemetry must never break task execution.
        }
    }

    @Override
    public void shutdown() {
        stopping = true;
        for (Thread worker : workers) {
            LockSupport.unpark(worker);
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown();
        List<Runnable> pending = new ArrayList<>();
        for (Runnable task = queue.poll(); task != null; task = queue.poll()) {
            pendingTasks.decrementAndGet();
            pending.add(task);
        }
        return Collections.unmodifiableList(pending);
    }

    @Override
    public boolean isShutdown() {
        return stopping;
    }

    @Override
    public boolean isTerminated() {
        return stopping && pendingTasks.get() <= 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isTerminated() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Math.min(
                    TimeUnit.MILLISECONDS.toNanos(50),
                    Math.max(1L, deadline - System.nanoTime())
            ));
        }
        return isTerminated();
    }

    /**
     * Agents the host has available. Zero on any page without the WasmLM thread host,
     * including the unshared image, because {@code web/thread-host.js} installs a
     * zero-agent host unconditionally.
     */
    @JS.Coerce
    @JS(value = "return globalThis.mcwebThreads ? globalThis.mcwebThreads.agents() : 0;", args = {})
    private static native int agentCount();
}
