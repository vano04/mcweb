package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.util.function.BooleanSupplier;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.WorldDataConfiguration;
import org.graalvm.webimage.api.JS;

/**
 * Cooperatively runs Mojang's integrated server on the browser's single
 * thread. The transformed {@code MinecraftServer.spin} registers the real
 * server instead of starting an unavailable OS thread; the browser frame pump
 * then performs the same protected initialization and tick entry points.
 */
public final class BrowserIntegratedServerCompat {
    /**
     * WorldLoader reaches this Mojang method through a method handle. Keeping a
     * concrete reference here prevents closed-world analysis from pruning it when
     * the browser spawn-finder seam removes the only unrelated transitive edge.
     */
    @FunctionalInterface
    private interface PackRepositoryConfigurer {
        WorldDataConfiguration apply(
                PackRepository repository,
                WorldDataConfiguration configuration,
                boolean safeMode,
                boolean allowVanilla
        );
    }

    private static final PackRepositoryConfigurer KEEP_CONFIGURE_PACK_REPOSITORY =
            MinecraftServer::configurePackRepository;

    /**
     * Vanilla passes {@code this::haveTime} into {@code tickServer}: every
     * poll loop inside it (chunk generation tasks, ChunkMap processing,
     * POI, light work, unloads) drains only while the current tick's 50 ms
     * deadline still holds. The original port hardcoded {@code () -> true},
     * so those queues drained unboundedly inside one tick — measured 200 to
     * 820 ms per server tick during worldgen on a default world (pump-gap
     * probe), which stretched the 50 ms tick cadence to ~2 Hz: mobs, hits,
     * damage and dig confirmations all landed a half second late, and late
     * chunk resyncs stomped client predictions between them. Bounding the
     * supplier restores vanilla pacing: a tick that overruns stops polling
     * and the remainder advances on the next tick's slice.
     */
    private static final BooleanSupplier HAS_TIME =
            BrowserIntegratedServerCompat::hasTickTime;

    private static boolean hasTickTime() {
        IntegratedServer current = server;
        // grantTaskDeadline sets nextTickTimeNanos and
        // delayedTasksMaxNextTickTimeNanos to the same value and clears
        // mayHaveDelayedTasks, so this reproduces Mojang's haveTime()
        // exactly (runningTask() never holds here: the port never runs the
        // superclass task-execution loop that would set it).
        return current != null && System.nanoTime() < current.nextTickTimeNanos;
    }
    private static final long TICK_INTERVAL_NANOS = 50_000_000L;
    /** Frame budget for server work while the client is still loading. */
    private static final long LOADING_BUDGET_NANOS = 25_000_000L;
    /**
     * Slice granted to each drain inside {@code initServer()}.
     *
     * <p>Bounded, not enormous. An enormous deadline makes {@code haveTime()}
     * permanently true, and Mojang's {@code waitUntilNextTick} waits for exactly the
     * opposite — {@code managedBlock(() -> !haveTime())} — so it never returns. The
     * budget is refreshed per iteration by {@link #waitUntilNextTickCompat} instead,
     * which is the only way to keep the drain alive across a synchronous init.
     */
    private static final long INIT_TASK_BUDGET_NANOS = 50_000_000L;
    /** Slice each patched {@code waitUntilNextTick} grants before draining. */
    private static final long WAIT_TICK_BUDGET_NANOS = 50_000_000L;
    /**
     * Budget for Mojang's normal between-tick task drain after world entry.
     *
     * <p>The desktop loop spends the wait before its next 20 TPS tick in
     * {@code waitUntilNextTick()}, which calls {@code runAllTasks()}. Returning
     * immediately from the browser pump during that same interval starves
     * chunk-send futures and serverbound interaction packets for 49 out of
     * every 50 milliseconds. A small per-frame slice preserves the real loop's
     * cooperative work without monopolizing the render thread.</p>
     */
    private static final long IDLE_TASK_BUDGET_NANOS = 2_000_000L;
    private static final long CHUNK_CATCHUP_BUDGET_NANOS = 8_000_000L;
    /** Fallback warmup only for the diagnostic arm with reconciliation disabled. */
    private static final long TERRAIN_WARMUP_DURATION_NANOS = 60_000_000_000L;
    private static final long TERRAIN_WARMUP_TASK_BUDGET_NANOS = 20_000_000L;
    private static final long TERRAIN_WARMUP_POST_TICK_BUDGET_NANOS = 12_000_000L;
    /**
     * Time granted to the drain that follows each server tick.
     *
     * <p>Everything in Mojang's chunk pipeline — generation tasks, holder
     * promotion, and the player-ticket release — is reachable only from
     * {@code ServerChunkCache.pollTask()}, which
     * {@code MinecraftServer.pollTaskInternal} calls only while
     * {@code haveTime()} holds. This is that time.</p>
     */
    private static final long POST_TICK_DRAIN_BUDGET_NANOS = 8_000_000L;
    /**
     * Vanilla advances its deadline by one 50 ms period rather than anchoring
     * the next tick on wall-clock completion. After an overrun it therefore
     * runs overdue gameplay ticks immediately, with {@code haveTime()} false so
     * optional chunk work cannot deepen the debt. Bound the cooperative catch-up
     * so one Worker task still yields promptly to MessagePort input.
     */
    private static final int MAX_CATCHUP_TICKS = 3;
    private static final long CATCHUP_BUDGET_NANOS = 30_000_000L;
    private static final long MAX_TICK_DEBT_NANOS = 500_000_000L;
    /** Slow-tick attribution counter (see serverTick). */
    private static int slowTicks;
    private static final int MAX_LOADING_TICKS_PER_FRAME = 10;
    /**
     * Accept ticks up to this much early. The Worker pump timer nominally
     * fires every 50 ms — the tick interval — so a period even a hair under
     * 50 ms (measured: 49.97 ms) made every other pump miss the deadline and
     * alias the server to 10 TPS. Ticking 2 ms early is inside the 50 ms
     * cycle and the deadline re-anchors from `now`, so the rate stays exact.
     */
    private static final long EARLY_TICK_TOLERANCE_NANOS = 2_000_000L;
    /** Interval between chunk-grid samples streamed to the loading screen. */
    private static final long GRID_SAMPLE_INTERVAL_NANOS = 250_000_000L;

    private static IntegratedServer server;
    private static boolean initialized;
    private static long nextTickNanos;
    private static int loadingTicks;
    private static int tickCount;
    private static int idleDrains;
    private static boolean reconcileChunks;
    private static boolean wasLoadingWorld;
    /**
     * Primary-side pool draining is only safe once world loading owns the wait.
     * The client also uses {@code managedBlock} while opening the Create World
     * screen; stealing registry-load tasks there makes the primary execute the
     * same reload graph concurrently with the agents.
     */
    private static long terrainWarmupUntilNanos;
    private static String serverPhase = "idle";
    /**
     * True while the player's tracking view still contains chunks the client
     * has not been sent. Replaces a fixed post-entry timer as the condition for
     * the larger task budgets: the work is finished when the view is complete,
     * not when a stopwatch expires.
     */
    private static boolean viewIncomplete = true;
    /** Guard re-armed once for each dimension's terrain-streaming debt. */
    private static boolean viewCompletionDebtReset;
    private static int viewTracked;
    private static int viewDelivered;
    /** Per-heartbeat window: how the frame's task budget is actually spent. */
    private static int drains;
    private static int drainsBudgetExpired;
    private static long drainNanos;
    /** Durable record of what browserPrepareSpawnPosition placed, reported on each heartbeat. */
    private static String spawnPlacement;
    private static int postTickBudgetExpired;
    private static long postTickNanos;
    private static long lastPumpNanos;
    private static long pumpPeriodNanos;
    private static int pumpPeriods;
    private static int admittedChunks;
    private static int caughtUpTicks;
    private static int tickDebtResets;
    private static int maxCatchupBurst;
    /**
     * Chunks known to have entered each player's send queue.
     *
     * <p>The initial 5x5 batch is delivered normally, but on WasmGC the
     * asynchronous ready-to-send callbacks for later chunks can complete
     * without repopulating {@code PlayerChunkSender}. This small reconciliation
     * set repairs that browser scheduling seam while leaving Mojang's packet,
     * batching, acknowledgement, and client chunk-loading paths untouched.</p>
     */
    private static final java.util.Map<java.util.UUID, java.util.Set<Long>> queuedChunks =
            new java.util.HashMap<>();
    /**
     * Dimension that owns each {@link #queuedChunks} entry.
     *
     * <p>{@code ChunkPos.pack} contains only X/Z. A portal keeps the same player
     * UUID and commonly lands in a view whose packed positions overlap the old
     * dimension, so retaining the old set makes Overworld chunks look delivered
     * in the Nether. Keep the browser-only reconciliation cache scoped the same
     * way Mojang scopes {@code PlayerChunkSender}: to the player's current
     * {@link net.minecraft.server.level.ServerLevel}.</p>
     */
    private static final java.util.Map<
            java.util.UUID,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
            > queuedChunkDimensions = new java.util.HashMap<>();
    /** Server instance for which the unified ready marker has already fired. */
    private static MinecraftServer readyReportedServer;
    private static final int INITIAL_BATCH_RADIUS = 2;
    /**
     * Upper bound on chunks admitted into the send queue per server tick.
     *
     * <p>This is only a queueing bound. Mojang's own {@code PlayerChunkSender}
     * throttle — {@code desiredChunksPerTick} from the client's
     * {@code ChunkBatchSizeCalculator}, plus the unacknowledged-batch window —
     * decides how many of these actually become packets, so a small value here
     * is a second throttle stacked on the real one and can only starve it.</p>
     */
    private static final int MAX_RECONCILED_CHUNKS_PER_TICK = 32;
    /**
     * Chunk radius scanned when hunting the parked-generation root. Covers the
     * view distance (8) plus the radius-8 generation halo that feeds its edge.
     */
    private static final int SCAN_RADIUS = 18;
    /** Exact owner-branch observations from ServerChunkCache.getChunk. */
    private static final java.util.concurrent.atomic.AtomicLong chunkOwnerObservations =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong chunkFutureObservations =
            new java.util.concurrent.atomic.AtomicLong();

    private BrowserIntegratedServerCompat() {
    }

    public static void register(MinecraftServer instance) {
        if (KEEP_CONFIGURE_PACK_REPOSITORY == null) {
            throw new IllegalStateException("MinecraftServer pack configurator unavailable");
        }
        server = (IntegratedServer) instance;
        initialized = false;
        nextTickNanos = 0L;
        loadingTicks = 0;
        tickCount = 0;
        clientWorldEntered = false;
        chunkGridEnabled = false;
        gridFirstSampleReported = false;
        idleDrains = 0;
        reconcileChunks = !chunkReconciliationDisabled();
        wasLoadingWorld = true;
        clientWorldEntered = false;
        chunkGridEnabled = false;
        serverPhase = "registered";
        // Only the reconciler measures view completeness, so the disabled
        // comparison path keeps the original elapsed-time warmup instead of
        // holding the large budget forever on a measurement that never runs.
        viewIncomplete = reconcileChunks;
        viewCompletionDebtReset = false;
        viewTracked = 0;
        viewDelivered = 0;
        lastPumpNanos = 0L;
        caughtUpTicks = 0;
        tickDebtResets = 0;
        maxCatchupBurst = 0;
        resetBudgetWindow();
        queuedChunks.clear();
        queuedChunkDimensions.clear();
        readyReportedServer = null;
        BrowserGpu.reportProgress("integrated-server:registered");
        if (reconcileChunks) {
            BrowserGpu.reportProgress("chunk-reconcile:enabled");
        }
    }

    /**
     * Entry marker for the real vanilla Server phases. This is called from the
     * transformed MinecraftServer methods, including the WasmLM Server thread.
     */
    public static void reportServerPhase(MinecraftServer current, String phase) {
        server = current instanceof IntegratedServer integrated ? integrated : server;
        serverPhase = phase;
        BrowserGpu.reportProgress("server:" + phase
                + " thread=" + Thread.currentThread().getName()
                + " id=" + Thread.currentThread().getId()
                + " sameThread=" + current.isSameThread()
                + " mode=" + McWebRuntimeMode.name());
    }

    /** Static setInitialSpawn has no server receiver; retain its owning thread marker. */
    public static void reportServerPhase(String phase) {
        serverPhase = phase;
        BrowserGpu.reportProgress("server:" + phase
                + " thread=" + Thread.currentThread().getName()
                + " id=" + Thread.currentThread().getId()
                + " mode=" + McWebRuntimeMode.name());
    }

    /**
     * Browser-only replacement for Mojang's synchronous initial spawn finder.
     *
     * <p>{@code setInitialSpawn()} first installs the climate sampler's candidate
     * before probing a square around it.  Returning {@code null} would skip that
     * probe but preserve the sampler candidate, which can be hundreds of blocks
     * from the origin and turns the first login into an unexpectedly large terrain
     * load.  Use the browser's deterministic, bounded spawn instead; the normal
     * player ticket still loads the real chunk and all gameplay terrain through
     * Mojang's pipeline.</p>
     */
    public static net.minecraft.core.BlockPos browserInitialSpawn(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.ChunkPos chunk
    ) {
        return net.minecraft.core.BlockPos.ZERO.above(80);
    }

    /**
     * Browser-only replacement for the serial respawn-radius search performed
     * while the configuration handshake is preparing the first player.
     *
     * <p>The position was selected and stored by {@code setInitialSpawn}; the
     * normal {@code PrepareSpawnTask} ticket still loads the configured spawn
     * square before the player is placed. Deferring collision probing here keeps
     * that real ticket/chunk path intact without making configuration wait on a
     * potentially hundreds-of-chunks sequential search.</p>
     */
    public static java.util.concurrent.CompletableFuture<net.minecraft.world.phys.Vec3>
            browserPrepareSpawnPosition(
                    net.minecraft.server.level.ServerLevel level,
                    net.minecraft.core.BlockPos spawn
            ) {
        // The initial-spawn BlockPos is a fixed high point (ZERO.above(80)),
        // chosen in setInitialSpawn before any chunk exists. Placing the
        // player there drops them ~140 blocks on superflat and ~17 blocks
        // onto normal terrain — both lethal for survival, which read as
        // "block breaking does not register" for the seconds between entry
        // and respawn (measured: hp 20 -> 0 in ~0.8 s, DeathScreen at entry).
        //
        // Full chunks are NOT loaded at this point (PrepareSpawnTask loads
        // the spawn square only after the position is resolved), so a
        // getChunkNow probe misses and free-fall happens anyway. The
        // chunk-independent answer is the chunk generator's heightmap:
        // getFirstFreeHeight computes the standing surface Y for any
        // generator (flat or noise) without loading a chunk, which is
        // exactly how vanilla derives spawn heights.
        try {
            net.minecraft.server.level.ServerChunkCache chunks = level.getChunkSource();
            int surfaceY = chunks.getGenerator().getFirstFreeHeight(
                    spawn.getX(),
                    spawn.getZ(),
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
                    level,
                    chunks.randomState()
            );
            if (surfaceY > level.getMinY()) {
                // getFirstFreeHeight returns the heightmap value, the first
                // free space above the highest motion-blocking block — the
                // exact standing position, no settle-fall.
                net.minecraft.world.phys.Vec3 placed = new net.minecraft.world.phys.Vec3(
                        spawn.getX() + 0.5, surfaceY, spawn.getZ() + 0.5);
                spawnPlacement = "surface y=" + surfaceY + " placed=" + placed;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "spawn:surface-found y=" + surfaceY + " placed=" + placed);
                return java.util.concurrent.CompletableFuture.completedFuture(placed);
            }
            spawnPlacement = "fallback heightmap-empty y=" + surfaceY + " spawn=" + spawn;
        } catch (Throwable probeFailure) {
            // A failed probe must not break login; the fallback below is
            // the previous behavior.
            spawnPlacement = "fallback probe-failed " + probeFailure.getClass().getName();
        }
        return java.util.concurrent.CompletableFuture.completedFuture(
                net.minecraft.world.phys.Vec3.atBottomCenterOf(spawn)
        );
    }

    /**
     * Called at the exact {@code currentThread == mainThread} comparison in
     * {@code ServerChunkCache.getChunk}. It is intentionally observational: the
     * original comparison immediately follows this call unchanged.
     */
    public static void reportChunkOwner(
            net.minecraft.server.level.ServerChunkCache chunks,
            int x,
            int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status,
            boolean loadOrGenerate,
            Thread current,
            Thread owner
    ) {
        long observation = chunkOwnerObservations.incrementAndGet();
        boolean same = current == owner;
        if (observation > 8L && (observation & 0xFFFFL) != 0L) {
            return;
        }
        String marker = "chunk:get-owner n=" + observation
                + " branch=" + (same ? "server" : "off-owner")
                + " pos=" + x + ',' + z
                + " status=" + status
                + " load=" + loadOrGenerate
                + " current=" + current.getName() + '/' + current.getId()
                + " owner=" + owner.getName() + '/' + owner.getId()
                + " serverSame=" + (server != null && server.isSameThread())
                + " runtimeServer=" + McWebRuntimeMode.server();
        BrowserGpu.reportProgress(marker);
        BrowserGpu.reportDiag(marker + " pool=" + AgentExecutorService.stats());
    }

    /**
     * Records the exact future that the owner branch waits on, including both
     * Minecraft's main-thread queue and the independent Background/IO lanes.
     */
    public static void reportChunkFuture(
            net.minecraft.server.level.ServerChunkCache chunks,
            int x,
            int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status,
            boolean loadOrGenerate,
            java.util.concurrent.CompletableFuture<?> future
    ) {
        // This hook runs only after the real Server has asked for a chunk future.
        // It is the first Server-owned observation after worldgen work is queued,
        // so it can lease the bounded spare without putting a hot compensation
        // check into every client registry managedBlock iteration.
        if (McWebRuntimeMode.isThreaded()) {
            AgentExecutorService.serverBlockingWait();
        }
        long observation = chunkFutureObservations.incrementAndGet();
        if (observation > 8L && (observation & 0xFFFFL) != 0L) {
            return;
        }
        if (observation <= 8L) {
            /*
             * A failed ChunkResult is normally observed later by the Server owner as
             * "Exception chunk generation/loading".  That wrapper discards the worker
             * and future context that distinguishes an executor/IO race from a bad
             * generation stage.  Keep this callback observational and attach it to the
             * exact future before the owner starts waiting; it reports the completion
             * carrier, cause chain, and the queue topology without changing the future
             * that Mojang consumes.
             */
            final long futureObservation = observation;
            future.whenComplete((ignored, failure) -> reportChunkFutureCompletion(
                    futureObservation, x, z, status, failure));
        }
        String marker = "chunk:future n=" + observation
                + " pos=" + x + ',' + z
                + " status=" + status
                + " load=" + loadOrGenerate
                + " done=" + future.isDone()
                + " exceptional=" + future.isCompletedExceptionally()
                + " cancelled=" + future.isCancelled()
                + " dependents=" + future.getNumberOfDependents()
                + " chunkQueue=" + chunks.getPendingTasksCount()
                + " chunkMapWork=" + chunks.chunkMap.hasWork()
                + " worldgenWork=" + chunks.chunkMap.worldgenTaskDispatcher.hasWork()
                + " worldgenSleeping=" + chunks.chunkMap.worldgenTaskDispatcher.sleeping
                + " lightWork=" + chunks.chunkMap.lightTaskDispatcher.hasWork()
                + " lightSleeping=" + chunks.chunkMap.lightTaskDispatcher.sleeping
                + " current=" + Thread.currentThread().getName() + '/'
                + Thread.currentThread().getId()
                + " serverSame=" + (server != null && server.isSameThread());
        BrowserGpu.reportProgress(marker);
        BrowserGpu.reportDiag(marker + " pool=" + AgentExecutorService.stats());
    }

    private static void reportChunkFutureCompletion(
            long observation,
            int x,
            int z,
            net.minecraft.world.level.chunk.status.ChunkStatus status,
            Throwable failure
    ) {
        try {
            BrowserGpu.reportProgress("chunk:future-complete n=" + observation
                    + " pos=" + x + ',' + z
                    + " status=" + status
                    + " success=" + (failure == null)
                    + " thread=" + Thread.currentThread().getName() + '/'
                    + Thread.currentThread().getId()
                    + (failure == null
                            ? ""
                            : " failure=" + BrowserMinecraftMain.describeFailure(failure)));
            BrowserGpu.reportDiag("chunk:future-complete n=" + observation
                    + " pos=" + x + ',' + z
                    + " status=" + status
                    + " success=" + (failure == null)
                    + " thread=" + Thread.currentThread().getName() + '/'
                    + Thread.currentThread().getId()
                    + " pool=" + AgentExecutorService.queueState());
        } catch (Throwable ignored) {
            // Future diagnostics must never replace the result Minecraft is awaiting.
        }
    }

    /** Whether a primary-thread wait is part of integrated-server world loading. */
    public static void pump() {
        // WasmLM runs Mojang's original runServer() on the dedicated Server thread.
        // The browser frame thread may continue rendering, but it must not tick the
        // server, drain its executor, or poll ServerChunkCache from here.
        if (!McWebRuntimeMode.isCooperativeServer()) {
            return;
        }
        IntegratedServer current = server;
        if (current == null) {
            return;
        }
        try {
            if (!initialized) {
                serverPhase = "initServer";
                /*
                 * `initServer` runs `prepareLevels`, which is a synchronous loop of
                 * `waitUntilNextTick()` -> `runAllTasks()` until enough spawn chunks are
                 * generated. `runAllTasks` only polls the chunk source while `haveTime()`
                 * holds, and `haveTime()` reads `nextTickTimeNanos`, which only
                 * `runServer()` ever sets — the fault documented at the top of
                 * docs/STATUS.md. Every other drain in this class grants the deadline
                 * first; this one could not, because it is inside one Wasm call that has
                 * to carry its own budget in.
                 *
                 * The WasmGC lane survives without it only by accident: its background
                 * executor is inline, so chunk work completes on the caller and progress
                 * does not depend on the drain. With real agents the continuations land
                 * on the server's own queue, `runAllTasks` returns in microseconds
                 * against an expired deadline, and `prepareLevels` spins forever.
                 * Measured: the beacon ring ends at `levelload:start PREPARE_GLOBAL_SPAWN`
                 * / `levelload:focus [0, 0]` with the pool idle (`queued=0 busy=0/3`),
                 * and `integrated-server:initialized` never arrives.
                 */
                grantTaskDeadline(current, System.nanoTime() + INIT_TASK_BUDGET_NANOS);
                BrowserGpu.reportProgress("integrated-server:init-begin threaded="
                        + McWebRuntimeMode.name());
                boolean started = current.initServer();
                if (!started) {
                    throw new IllegalStateException("Integrated server initialization returned false");
                }
                initialized = true;
                // Mojang sets isReady inside runServer()'s main loop, which this port
                // never runs. Without it, pumpAndIsReady loops forever checking
                // !isReady() and the client never leaves the ready-wait.
                current.isReady = true;
                serverPhase = "initialized";
                BrowserGpu.reportProgress("integrated-server:initialized");
            }

            long now = System.nanoTime();
            if (lastPumpNanos != 0L) {
                // Frame period as the server sees it. One pump runs per
                // animation frame, so this is the real interval between server
                // ticks and the ceiling on chunk-pipeline throughput.
                pumpPeriodNanos += now - lastPumpNanos;
                pumpPeriods++;
            }
            lastPumpNanos = now;
            boolean loading = loadingWorld();
            if (loading) {
                wasLoadingWorld = true;
                // World entry is gated on server ticks: spawn chunks, player
                // placement and the initial chunk send all advance one tick at
                // a time. Holding the server to 20 TPS while the client has
                // nothing to render but a progress bar makes loading take
                // dozens of seconds longer than the work actually needs. Spend
                // a bounded slice of each frame on accelerated catch-up ticks
                // and their task drains instead.
                long deadline = now + LOADING_BUDGET_NANOS;
                int ticks = 0;
                do {
                    serverTick(current);
                    ticks++;
                } while (ticks < MAX_LOADING_TICKS_PER_FRAME && System.nanoTime() < deadline);
                loadingTicks += ticks;
                nextTickNanos = System.nanoTime() + TICK_INTERVAL_NANOS;
                if (chunkGridEnabled) {
                    sampleChunkGrid(current, System.nanoTime());
                }
            } else {
                if (wasLoadingWorld) {
                    wasLoadingWorld = false;
                    terrainWarmupUntilNanos = now + TERRAIN_WARMUP_DURATION_NANOS;
                    BrowserGpu.reportProgress("terrain-warmup:started");
                }
                if (nextTickNanos != 0L
                        && now < nextTickNanos - EARLY_TICK_TOLERANCE_NANOS) {
                    drainBetweenTicks(current, now);
                    return;
                }
                // `nextTickNanos` is the start deadline which just became due.
                // Move to this tick's end exactly as MinecraftServer.runServer
                // does; anchoring it on `now` permanently discards every game
                // tick an unpreemptable chunk task overran.
                if (now - nextTickNanos > MAX_TICK_DEBT_NANOS) {
                    nextTickNanos = now;
                    tickDebtResets++;
                }
                nextTickNanos += TICK_INTERVAL_NANOS;
                serverTick(current, nextTickNanos);

                // Overdue ticks use their historical absolute deadline. When
                // that deadline is in the past HAS_TIME is false, so gameplay,
                // entities and dig progress advance while optional chunk polls
                // wait. This is the key distinction from granting every
                // catch-up tick a fresh 50 ms chunk-work slice.
                long catchupStarted = System.nanoTime();
                int ticks = 1;
                while (ticks < MAX_CATCHUP_TICKS
                        && System.nanoTime() >= nextTickNanos - EARLY_TICK_TOLERANCE_NANOS
                        && System.nanoTime() - catchupStarted < CATCHUP_BUDGET_NANOS) {
                    nextTickNanos += TICK_INTERVAL_NANOS;
                    serverTick(current, nextTickNanos);
                    caughtUpTicks++;
                    ticks++;
                }
                maxCatchupBurst = Math.max(maxCatchupBurst, ticks - 1);
            }
        } catch (Throwable failure) {
            server = null;
            BrowserGpu.reportJavaFailure(
                    "integrated-server",
                    failure.getClass().getName(),
                    "phase=" + serverPhase + " | "
                            + BrowserMinecraftMain.describeFailure(failure)
            );
        }
    }

    /**
     * True while chunk work should get the larger frame slices.
     *
     * <p>The supported reconciler starts with {@link #viewIncomplete} true, so
     * it needs no stopwatch before its first measurement. Once it observes a
     * complete view, normal play returns to the small idle slices immediately.
     * The fixed timer exists only for the comparison arm which deliberately
     * disables that measurement.</p>
     */
    private static boolean terrainCatchup(long now) {
        return reconcileChunks ? viewIncomplete : now < terrainWarmupUntilNanos;
    }

    /** Where each heartbeat window's frame time actually went. */
    private static String describeBudgetWindow() {
        long frameMicros = pumpPeriods == 0 ? 0L : pumpPeriodNanos / pumpPeriods / 1000L;
        long drainMicros = drains == 0 ? 0L : drainNanos / drains / 1000L;
        long postMicros = postTickNanos / 100L / 1000L;
        return "frameUs=" + frameMicros
                + " drains=" + drains
                + " drainUs=" + drainMicros
                + " drainExpired=" + drainsBudgetExpired
                + " postTickUs=" + postMicros
                + " postExpired=" + postTickBudgetExpired
                + " admitted=" + admittedChunks
                + " caughtUp=" + caughtUpTicks
                + " debtResets=" + tickDebtResets
                + " maxCatchupBurst=" + maxCatchupBurst
                + " catchup=" + terrainCatchup(System.nanoTime())
                + " viewDelivered=" + viewDelivered + '/' + viewTracked;
    }

    private static void resetBudgetWindow() {
        drains = 0;
        drainsBudgetExpired = 0;
        drainNanos = 0L;
        postTickBudgetExpired = 0;
        postTickNanos = 0L;
        pumpPeriodNanos = 0L;
        pumpPeriods = 0;
        admittedChunks = 0;
        caughtUpTicks = 0;
        tickDebtResets = 0;
        maxCatchupBurst = 0;
    }

    /**
     * Grants the server a task deadline the way {@code runServer} does.
     *
     * <p>Setting {@code nextTickTimeNanos} alone is not enough, and the reason
     * is not obvious. {@code MinecraftServer.pollTask} assigns
     * {@code mayHaveDelayedTasks} the result of every poll, and
     * {@code haveTime()} switches to {@code delayedTasksMaxNextTickTimeNanos}
     * as soon as that flag is set. Only {@code runServer} writes that second
     * field, so in this port it stayed 0: the first chunk task of every drain
     * set the flag, the next {@code haveTime()} compared the clock against 0,
     * and {@code pollTaskInternal} stopped polling the chunk source entirely.
     *
     * <p>The whole chunk pipeline therefore advanced exactly one task per
     * {@code runAllTasks()} — two per server tick — while the 8 ms drain
     * returned in 11 us with 7,687 tasks queued. {@code runServer} clears the
     * flag and sets both deadlines together before each drain; so does this.</p>
     */
    private static void grantTaskDeadline(IntegratedServer current, long deadline) {
        current.mayHaveDelayedTasks = false;
        current.nextTickTimeNanos = deadline;
        current.delayedTasksMaxNextTickTimeNanos = deadline;
    }

    /**
     * Replacement body for {@code MinecraftServer.waitUntilNextTick()}.
     *
     * <p>Mojang's is {@code runAllTasks(); managedBlock(() -> !haveTime())}: drain, then
     * wait for the tick budget to run out. Only {@code runServer()} calls it on desktop,
     * which this port never runs — but {@code initServer() -> prepareLevels()} calls it
     * too, in a loop, and that path very much runs here.
     *
     * <p>Both halves misbehave in the browser. The drain needs a live deadline, and
     * `initServer` is a single synchronous Wasm call, so nothing outside can grant one;
     * with an expired deadline {@code runAllTasks} returns in microseconds and
     * `prepareLevels` spins forever. Granting a *large* deadline instead makes
     * {@code haveTime()} permanently true, so {@code managedBlock}'s condition never
     * holds and it never returns — measured as the spawn focus advancing once and then
     * freezing harder than before.
     *
     * <p>So: grant a fresh slice, drain, return. The caller loops, which is what makes
     * the deadline refresh every iteration — the thing a one-shot grant cannot do. There
     * is nothing to "wait" for on a cooperative server; the budget exists to bound the
     * drain, not to pace a real-time tick.
     */
    public static void waitUntilNextTickCompat(MinecraftServer current) {
        if (!(current instanceof IntegratedServer integrated)) {
            return;
        }
        if (McWebRuntimeMode.server() == McWebRuntimeMode.Server.THREADED) {
            // WasmLM has a real server Thread. Preserve Mojang's deadline and
            // managed-block behavior there; only the WasmGC lane needs the
            // bounded cooperative replacement below.
            integrated.mcwebWaitUntilNextTickVanilla();
            return;
        }
        // Grant a fresh deadline so haveTime() holds and the chunk source is polled.
        grantTaskDeadline(integrated, System.nanoTime() + WAIT_TICK_BUDGET_NANOS);
        net.minecraft.server.level.ServerChunkCache chunkSource =
                integrated.overworld().getChunkSource();
        /*
         * Drive the chunk pipeline directly, bounded — and ONLY the chunk source.
         *
         * Two failure modes this avoids:
         *  - Mojang's runAllTasks() loops while the server's pollTask() returns true,
         *    but pollTaskInternal only polls the chunk source when the server's own
         *    main task queue is empty. The agent pool keeps refilling that queue, so
         *    an unbounded runAllTasks() livelocks on main-queue tasks and never
         *    advances generation (measured: no server:wait-tick marker, frozen at
         *    levelload:focus).
         *  - Draining the server's main queue here runs arbitrary server tasks, and
         *    some of them block: a CompletableFuture.join() spins on the no-op
         *    LockSupport.park outside any managedBlock, so nothing can report or
         *    unblock it (measured: the diag ring froze entirely). Those tasks belong
         *    in serverTick after init, not in the spawn-wait drain.
         *
         * chunkSource.pollTask() runs runDistanceManagerUpdates() (generation dispatch,
         * holder promotion, ticket release) plus the chunk source's own queue, which is
         * exactly what completes the spawn chunks' futures. The bounded count keeps the
         * browser thread returning to the frame pump; the caller loops for progress.
         */
        for (int i = 0; i < MAX_WAIT_TICK_POLLS; i++) {
            if (!chunkSource.pollTask()) {
                break;
            }
            if ((i & 0x7F) == 0x7F) {
                grantTaskDeadline(integrated, System.nanoTime() + WAIT_TICK_BUDGET_NANOS);
            }
        }
        if ((waitUntilNextTicks++ & 0xF) == 0) {
            BrowserGpu.reportDiag("server:wait-tick n=" + waitUntilNextTicks
                    + " pending=" + chunkSource.getPendingTasksCount()
                    + " " + AgentExecutorService.queueState());
        }
        // The chunk pipeline's continuations run on the agent pool; if every worker is
        // blocked on another task, draining here is what keeps prepareLevels advancing.
        // Budget is large: the reload barrier parks workers on SimpleReloadInstance
        // lambdas that wait on generation futures deep in the queue, and a small budget
        // never reaches them. Also trigger pool growth: growIfStalled is driven from the
        // frame pump, which does not run during initServer, so the pool cannot rescue
        // itself without this nudge.
        AgentExecutorService.maintain();
        AgentExecutorService.drainInlineWhileWaiting(512);
    }

    /** Iterations of the patched {@code waitUntilNextTick}; reported into the beacon. */
    private static int waitUntilNextTicks;
    /** Tasks polled per bounded {@code waitUntilNextTick} drain (see above). */
    private static final int MAX_WAIT_TICK_POLLS = 512;

    private static void drainBetweenTicks(IntegratedServer current, long now) {
        // Network delivery is threaded on desktop, so packets can make progress
        // between server ticks. The browser transport is cooperative; drain it
        // on each animation frame before advancing any tasks it unblocks.
        serverPhase = "between-tick-inbound";
        current.getConnection().drainInbound();
        // The Worker's PacketTransport normally flushes through setTimeout(0).
        // Post responses produced by player input now, before the task drain can
        // monopolize this Worker for another worldgen/save slice.
        BrowserWorkerTransport.flush();
        // getPendingTasksCount() only counts the mainThreadProcessor queue and
        // reads zero while the chunk map still has promotion work outstanding,
        // so ask the chunk map directly as well.
        net.minecraft.server.level.ServerChunkCache source =
                primaryPlayerLevel(current).getChunkSource();
        long budget;
        if (terrainCatchup(now) && source.getPendingTasksCount() > 0) {
            budget = TERRAIN_WARMUP_TASK_BUDGET_NANOS;
        } else {
            budget = source.getPendingTasksCount() > 0
                    ? CHUNK_CATCHUP_BUDGET_NANOS
                    : IDLE_TASK_BUDGET_NANOS;
        }
        // A *fresh* deadline, not min(nextTickNanos, …). `nextTickNanos` was set
        // to tickStart+50ms before a tick that can easily take longer than that
        // in the browser, so clamping to it hands runAllTasks an already-expired
        // deadline — and an expired deadline silently disables the entire chunk
        // pipeline (see serverTick).
        grantTaskDeadline(current, now + budget);
        serverPhase = "between-tick-tasks";
        long drainStart = System.nanoTime();
        current.runAllTasks();
        long drainEnd = System.nanoTime();
        serverPhase = "idle";
        idleDrains++;
        drains++;
        drainNanos += drainEnd - drainStart;
        if (drainEnd >= current.nextTickTimeNanos) {
            // The drain stopped because the deadline passed, not because the
            // queues emptied: the budget, not the work, is the limit.
            drainsBudgetExpired++;
        }
    }

    /** Loading-path tick with a fresh deadline and post-tick generation slice. */
    private static void serverTick(IntegratedServer current) {
        serverTick(current, System.nanoTime() + TICK_INTERVAL_NANOS, true);
    }

    /** Normal/catch-up tick bound to the absolute runServer-style deadline. */
    private static void serverTick(IntegratedServer current, long tickDeadlineNanos) {
        serverTick(current, tickDeadlineNanos, false);
    }

    private static void serverTick(
            IntegratedServer current,
            long tickDeadlineNanos,
            boolean loadingTick
    ) {
        // Grant the tick its absolute deadline, exactly as
        // MinecraftServer.runServer does. An overdue catch-up tick receives an
        // already-expired deadline, making HAS_TIME false inside optional
        // chunk/POI/light polls while the game tick itself still advances.
        // MinecraftServer.pollTaskInternal only polls ServerChunkCache while
        // haveTime() holds, and haveTime() compares against nextTickTimeNanos;
        // those queued tasks resume once the absolute schedule catches up.
        grantTaskDeadline(current, tickDeadlineNanos);

        // Serverbound packets are handled inline (the transformed spin passes
        // the browser main thread as the server thread, so
        // PacketProcessor.isSameThread() holds). Draining here rather than
        // inside ServerConnectionListener.tick keeps a packet's effects in the
        // same tick it arrived, instead of one tick behind.
        serverPhase = "tick-inbound";
        long inboundStart = System.nanoTime();
        current.getConnection().drainInbound();
        long inboundEnd = System.nanoTime();
        // Creative actions can produce their block update while inbound is
        // drained. Publish it before tickServer, whose chunk work may overrun
        // the Worker's 50 ms budget by hundreds of milliseconds.
        BrowserWorkerTransport.flush();
        serverPhase = "tickServer";
        current.tickServer(HAS_TIME);
        long tickEnd = System.nanoTime();
        // A survival break completes during tickServer. Preserve packet order
        // but cross the realm boundary before post-tick chunk work. postMessage
        // is non-blocking, so the server continues immediately after publishing.
        BrowserWorkerTransport.flush();

        // runServer() follows every tick with waitUntilNextTick(), whose
        // runAllTasks() is what actually advances chunk generation. The pump
        // replaces that loop, so it has to do this itself; without it the
        // spawn-chunk future never completes and world entry hangs on
        // "Loading terrain" forever.
        //
        // Loading uses a fresh post-tick slice because accelerated world entry
        // deliberately runs outside the normal 20 TPS schedule. Normal play
        // reuses the absolute tick boundary: if a task overran it, desktop
        // runServer catches up game ticks before polling more chunk work.
        // `MinecraftServer.pollTaskInternal` reaches
        // `ServerChunkCache.pollTask()` — the sole caller of
        // `runDistanceManagerUpdates()`, and therefore of `runGenerationTasks()`,
        // `promoteChunkMap()` and the player-ticket release inside
        // `DistanceManager.runAllUpdates` — only while `haveTime()` holds. A
        // browser loading tick regularly overruns the 50 ms granted above, so
        // that accelerated path must refresh the deadline here.
        //
        // That is what froze the chunk horizon: holders stopped being promoted,
        // so no chunk ever reached BLOCK_TICKING/ENTITY_TICKING, the four
        // ThrottlingChunkTaskDispatcher slots were never released, and no
        // further PLAYER_LOADING tickets were issued. It only bites after world
        // entry because the loading path runs cheap ticks in a tight loop, each
        // with its own fresh deadline.
        //
        // Bounded, so it cannot monopolise the render thread: runAllTasks
        // returns as soon as the queues are empty or this deadline passes.
        long postTickBudget = terrainCatchup(System.nanoTime())
                ? TERRAIN_WARMUP_POST_TICK_BUDGET_NANOS
                : POST_TICK_DRAIN_BUDGET_NANOS;
        long postTickStart = System.nanoTime();
        long postTickDeadline = loadingTick
                ? postTickStart + postTickBudget
                : Math.min(tickDeadlineNanos, postTickStart + postTickBudget);
        grantTaskDeadline(current, postTickDeadline);
        serverPhase = "post-tick-tasks";
        boolean postTickAttempted = postTickDeadline > postTickStart;
        if (postTickAttempted) {
            current.runAllTasks();
        }
        long postTickEnd = System.nanoTime();
        // Attribution for slow ticks: which phase ate the pump call.
        // Rate-limited so the stage ring is not flooded during sustained
        // worldgen (one marker per 20 slow ticks).
        long totalMs = (postTickEnd - inboundStart) / 1_000_000L;
        if (totalMs > 100L && ++slowTicks % 20 == 1) {
            net.minecraft.server.level.ServerLevel level = primaryPlayerLevel(current);
            BrowserGpu.reportProgress("pumpslow:total=" + totalMs + "ms"
                    + " inbound=" + (inboundEnd - inboundStart) / 1_000_000L + "ms"
                    + " tick=" + (tickEnd - inboundEnd) / 1_000_000L + "ms"
                    + " post=" + (postTickEnd - postTickStart) / 1_000_000L + "ms"
                    + " dimension=" + level.dimension().identifier()
                    + " loaded=" + level.getChunkSource().getLoadedChunksCount()
                    + " pending=" + level.getChunkSource().getPendingTasksCount());
        }
        postTickNanos += postTickEnd - postTickStart;
        if (postTickAttempted && postTickEnd >= current.nextTickTimeNanos) {
            postTickBudgetExpired++;
        }
        serverPhase = "post-tick-ready";
        if (!current.isReady) {
            current.isReady = true;
        }
        reportReadyIfNeeded(current);
        if (reconcileChunks && !loadingWorld()) {
            serverPhase = "chunk-reconcile";
            reconcileChunkSends(current);
        }
        // Heartbeat: distinguishes "the server stopped ticking" from "the
        // server is ticking but the handshake is stuck".
        if (++tickCount % 100 == 0) {
            serverPhase = "heartbeat";
            net.minecraft.server.level.ServerLevel level = primaryPlayerLevel(current);
            net.minecraft.server.level.ServerChunkCache chunks = level.getChunkSource();
            // A diagnostic must never be able to take down the game. An earlier
            // revision of this probe threw and killed a whole run.
            String chunkSendState;
            try {
                chunkSendState = describeChunkSendState(current, chunks);
            } catch (Throwable probeFailure) {
                chunkSendState = " chunkSendState-probe-failed=" + probeFailure.getClass().getName();
            }
            BrowserGpu.reportProgress(
                    "server:tick=" + tickCount
                            + " players=" + current.getPlayerCount()
                            + " dimension=" + level.dimension().identifier()
                            + " loadedChunks=" + chunks.getLoadedChunksCount()
                            + " pendingTasks=" + chunks.getPendingTasksCount()
                            + " loadingTicks=" + loadingTicks
                            + " idleDrains=" + idleDrains
                            + " " + describeBudgetWindow()
                            + chunkSendState
                            + " spawn=" + spawnPlacement
            );
            resetBudgetWindow();
        }
        serverPhase = "idle";
    }

    /** Actual completed Minecraft ticks, not host pump callbacks. */
    public static long completedTickCount() {
        return tickCount;
    }

    /** Allows focused comparisons while keeping the proven repair as default. */
    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_chunk_reconcile') === '0';", args = {})
    private static native boolean chunkReconciliationDisabled();

    /**
     * Reconciles ready chunks in the real tracking view with the real
     * {@link net.minecraft.server.network.PlayerChunkSender}.
     *
     * <p>Bounded per tick only so one server tick cannot queue an unbounded
     * batch; Mojang's {@code PlayerChunkSender} throttle still decides the
     * packet rate. It also measures how much of the tracking view the client
     * actually has, which is what selects the frame task budget.</p>
     */
    /**
     * Threaded lane: reconcile chunk sends from Mojang's own {@code Server thread}.
     *
     * <p>{@link #pump} — and with it {@link #reconcileChunkSends} — returns immediately
     * unless the server is cooperative, so on the threaded lane the reconciliation
     * simply never ran. Measured consequence: two four-agent runs both entered a world
     * and then held at <em>exactly</em> 25 client chunks of 329, which is Mojang's
     * initial 5x5 login batch and nothing afterwards. An exact repeat of 25 is a stall
     * in the send path, not slow generation — worldgen throughput would creep past it.
     *
     * <p>This is called from the tail of the transformed {@code IntegratedServer
     * .tickServer}, so it runs on the thread that owns {@code ServerChunkCache},
     * {@code ChunkMap} and {@code PlayerChunkSender}. Everything it touches is
     * server-owned; nothing here reads client state, which the cooperative path could
     * do safely only because it *was* the client thread.
     */
    public static void afterThreadedServerTick(IntegratedServer current) {
        if (current == null || !McWebRuntimeMode.isThreaded()) {
            return;
        }
        // Do NOT gate on `reconcileChunks`. It is only assigned in register(), and
        // register() never runs on the threaded lane — `integrated-server:registered`
        // is absent from a full threaded stage list, while `integrated-server:
        // thread-start` is present. The first version of this hook gated on it and so
        // returned on every tick without ever reconciling anything.
        if (threadedReconcileState == 0) {
            threadedReconcileState = chunkReconciliationDisabled() ? 1 : 2;
            BrowserGpu.reportProgress("chunk-reconcile:threaded-init enabled="
                    + (threadedReconcileState == 2));
        }
        if (threadedReconcileState != 2) {
            return;
        }
        // The threaded lane also never ran register(), so the server reference the
        // rest of this class keys off is unset; publish it from the owning thread.
        server = current;
        try {
            // Players are the real precondition: the reconciler seeds its per-player
            // known-set from the login batch and does nothing without one. This
            // deliberately replaces the cooperative path's LevelLoadingScreen check,
            // which would read client state from the Server thread.
            if (current.getPlayerList() == null || current.getPlayerList().getPlayers().isEmpty()) {
                return;
            }
            reconcileChunkSends(current);
            if (++threadedReconcileTicks % 100 == 0) {
                BrowserGpu.reportProgress("chunk-reconcile:threaded"
                        + " tracked=" + viewTracked
                        + " delivered=" + viewDelivered
                        + " admitted=" + admittedChunks
                        + " ticks=" + threadedReconcileTicks);
            }
        } catch (Throwable failure) {
            // A reconciliation fault must never take down Mojang's server tick.
            if (threadedReconcileFailures++ < 3) {
                BrowserGpu.reportProgress("chunk-reconcile:threaded-failed "
                        + failure.getClass().getName() + ":" + failure.getMessage());
            }
        }
    }

    private static int threadedReconcileTicks;
    private static int threadedReconcileFailures;
    /** 0 = undecided, 1 = disabled by query flag, 2 = armed. */
    private static int threadedReconcileState;

    /**
     * Destination level for the integrated player, with Overworld as the
     * pre-login fallback. The private integrated server normally has one local
     * player; Minecraft's task drain still iterates every level itself.
     */
    private static net.minecraft.server.level.ServerLevel primaryPlayerLevel(
            IntegratedServer current
    ) {
        if (current.getPlayerList() != null
                && !current.getPlayerList().getPlayers().isEmpty()) {
            return current.getPlayerList().getPlayers().get(0).level();
        }
        return current.overworld();
    }

    /** Marks the destination view incomplete once when a portal changes level. */
    private static void reportDimensionChange(
            net.minecraft.server.level.ServerPlayer player,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> previous,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> current
    ) {
        if (previous == null || java.util.Objects.equals(previous, current)) {
            return;
        }
        viewIncomplete = true;
        viewTracked = 0;
        viewDelivered = 0;
        // Destination generation can leave the same structural scheduling
        // debt as initial terrain. Re-arm exactly once for this dimension;
        // reportDimensionChange itself is one-shot because the cache's stored
        // key is updated before the next packet/reconcile pass.
        viewCompletionDebtReset = false;
        try {
            BrowserGpu.reportProgress("chunk-reconcile:dimension player=" + player.getUUID()
                    + " from=" + previous.identifier()
                    + " to=" + current.identifier());
        } catch (Throwable ignored) {
            // A transition marker must never interfere with the packet/send
            // reconciliation state it describes.
        }
    }

    private static void reconcileChunkSends(IntegratedServer current) {
        boolean viewWasIncomplete = viewIncomplete;
        java.util.Set<java.util.UUID> activePlayers = new java.util.HashSet<>();
        for (net.minecraft.server.level.ServerPlayer player : current.getPlayerList().getPlayers()) {
            activePlayers.add(player.getUUID());
            // Mojang's PlayerChunkSender always resolves pending X/Z against
            // player.level(). The browser reconciler must do the same: an
            // Overworld ChunkMap cannot say whether a Nether chunk is ready.
            net.minecraft.server.level.ServerChunkCache chunks =
                    player.level().getChunkSource();
            net.minecraft.server.level.ChunkMap chunkMap = chunks.chunkMap;
            net.minecraft.server.level.ChunkTrackingView view = player.getChunkTrackingView();
            java.util.UUID playerId = player.getUUID();
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension =
                    player.level().dimension();
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> previousDimension =
                    queuedChunkDimensions.get(playerId);
            java.util.Set<Long> known = queuedChunks.get(playerId);
            boolean firstPlayerView = known == null;
            boolean dimensionChanged = !firstPlayerView
                    && !java.util.Objects.equals(previousDimension, dimension);
            if (firstPlayerView || dimensionChanged) {
                known = new java.util.HashSet<>();
                queuedChunks.put(playerId, known);
                queuedChunkDimensions.put(playerId, dimension);
                reportDimensionChange(player, previousDimension, dimension);
            }
            if (firstPlayerView) {
                // Seed with what the client already holds, not with a
                // fixed radius. Vanilla closes the loading screen right after
                // its 5x5 initial batch, but the browser terrain gate holds
                // it until much more of the view is delivered. Assuming only
                // the 5x5 square here used to re-queue ~300 already-delivered
                // chunks as stale whole-chunk resends: those arrived after
                // live block updates and stomped them, and the resend flood
                // collapsed the pump. A chunk counts as delivered when it is
                // send-ready or already queued for sending; unready chunks
                // stay unseeded so they stream exactly once below.
                //
                // Do not apply this one-time login seed after a dimension
                // change. The destination's ready chunks are not yet known to
                // the client; the loop below must enqueue them (or observe
                // them pending) before they count as delivered.
                final java.util.Set<Long> seed = known;
                view.forEach(pos -> {
                    long packed = pos.pack();
                    if (chunkMap.getChunkToSend(packed) != null
                            || player.connection.chunkSender.isPending(packed)) {
                        seed.add(packed);
                    }
                });
            }

            // Forget chunks that left the view so returning to the area sends
            // them again after the client has dropped them.
            known.removeIf(packed -> !view.contains(
                    net.minecraft.world.level.ChunkPos.getX(packed),
                    net.minecraft.world.level.ChunkPos.getZ(packed)
            ));

            final java.util.Set<Long> playerKnown = known;
            final int[] admitted = {0};
            final int[] seen = {0};
            view.forEach(pos -> {
                seen[0]++;
                long packed = pos.pack();
                net.minecraft.world.level.chunk.LevelChunk ready = chunkMap.getChunkToSend(packed);
                if (ready == null) {
                    // Deliberately keep the entry. A chunk in view can be
                    // momentarily unready (send-sync dependency, ticket
                    // demotion) without the client having dropped it; forgetting
                    // it here would re-send it and, worse, makes `reconciled`
                    // decay to a mirror of `trackedReady` instead of counting
                    // what the client actually knows. Entries are forgotten
                    // only when they leave the tracking view, above.
                    return;
                }
                if (player.connection.chunkSender.isPending(packed)) {
                    playerKnown.add(packed);
                    return;
                }
                if (admitted[0] >= MAX_RECONCILED_CHUNKS_PER_TICK) {
                    return;
                }
                if (playerKnown.add(packed)) {
                    player.connection.chunkSender.markChunkPendingToSend(ready);
                    admitted[0]++;
                }
            });
            admittedChunks += admitted[0];
            viewTracked = seen[0];
            viewDelivered = playerKnown.size();
            viewIncomplete = viewDelivered < viewTracked;
        }
        queuedChunks.keySet().removeIf(uuid -> !activePlayers.contains(uuid));
        queuedChunkDimensions.keySet().removeIf(uuid -> !activePlayers.contains(uuid));
        if (viewWasIncomplete && !viewIncomplete && !viewCompletionDebtReset
                && !McWebRuntimeMode.isThreaded()) {
            /*
             * Terrain generation in a newly entered dimension can leave the absolute tick schedule
             * several seconds behind even though each individual overrun stays
             * below MAX_TICK_DEBT_NANOS. Carrying that structural debt into a
             * complete world fast-forwards gameplay at two or three ticks per
             * Worker pump: measured heartbeats after 329/329 delivery still had
             * caughtUp=50 per 100 ticks. Drop it exactly once at the objective
             * view-complete boundary. Later transient overruns continue through
             * the normal bounded catch-up path above, preserving Minecraft's
             * absolute-deadline semantics during play.
             */
            viewCompletionDebtReset = true;
            nextTickNanos = System.nanoTime() + TICK_INTERVAL_NANOS;
            tickDebtResets++;
            BrowserGpu.reportProgress("server:tick-debt-reset reason=view-complete"
                    + " dimension=" + primaryPlayerLevel(current).dimension().identifier()
                    + " viewDelivered=" + viewDelivered + '/' + viewTracked);
        }
    }

    /**
     * Records that a whole-chunk packet just crossed the wire, so the
     * reconciler treats that chunk as delivered.
     *
     * <p>Without this the reconciler duplicates every chunk send. Mojang's
     * {@code PlayerChunkSender} clears its pending flag the moment it sends
     * a chunk; the reconcile that runs after the tick then sees the chunk as
     * send-ready, not pending, and not in the known set, re-admits it, and
     * vanilla sends it a second time on the next tick. Measured: 46
     * whole-chunk packets in a 3 s steady-state window (a settled world
     * should resend ~none). Each duplicate is a stale-data race against
     * in-flight client block predictions — the observed break → reappear →
     * re-break flip cycle. Wire delivery is the ground truth the reconciler
     * was missing: a chunk that crossed the wire is by definition known to
     * the client, whatever the pending flag says.</p>
     */
    public static void noteChunkSentOnWire(int x, int z) {
        try {
            IntegratedServer current = server;
            if (current == null) {
                return;
            }
            long packed = net.minecraft.world.level.ChunkPos.pack(x, z);
            for (net.minecraft.server.level.ServerPlayer player : current.getPlayerList().getPlayers()) {
                java.util.UUID playerId = player.getUUID();
                java.util.Set<Long> known = queuedChunks.get(playerId);
                // No entry yet means the reconciler has not seeded this
                // player; its seed already covers everything send-ready at
                // that moment, so there is nothing to record against.
                if (known != null) {
                    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension =
                            player.level().dimension();
                    net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>
                            previousDimension = queuedChunkDimensions.get(playerId);
                    if (!java.util.Objects.equals(previousDimension, dimension)) {
                        known = new java.util.HashSet<>();
                        queuedChunks.put(playerId, known);
                        queuedChunkDimensions.put(playerId, dimension);
                        // A destination chunk may cross the wire before the
                        // next reconciliation pass. Reset first, then retain
                        // that packet as delivered in the new dimension.
                        reportDimensionChange(player, previousDimension, dimension);
                    }
                    known.add(packed);
                }
            }
        } catch (Throwable ignored) {
            // A delivery record is an optimization for the reconciler; it
            // must never disturb the send it is observing.
        }
    }

    /**
     * Reports the exact server-side state behind the visible chunk horizon.
     *
     * <p>Every part is a separate {@link #section} because Mojang's holder
     * futures are legitimately null before they are scheduled. A single such
     * null used to abort the whole probe and replace 20 measured fields with
     * one exception name, which is exactly the state this must never be in
     * while it is the only view of the pipeline.</p>
     */
    private static String describeChunkSendState(
            IntegratedServer current,
            net.minecraft.server.level.ServerChunkCache chunks
    ) {
        StringBuilder state = new StringBuilder();
        section(state, "readyChunks", () -> {
            int[] totalReady = {0};
            chunks.chunkMap.forEachReadyToSendChunk(chunk -> totalReady[0]++);
            state.append(" readyChunks=").append(totalReady[0]);
        });
        // Which dispatcher is holding the work that ChunkMap.hasWork() reports.
        // `sleeping` with queued work is a hard stall: pollTask() only sets it
        // when popTasks() returns null, and nothing re-polls until the next
        // submit()/release().
        section(state, "dispatchers", () -> state.append(" dispatchers=[worldgen work=")
                .append(chunks.chunkMap.worldgenTaskDispatcher.hasWork())
                .append(" sleeping=").append(chunks.chunkMap.worldgenTaskDispatcher.sleeping)
                .append(" | light work=")
                .append(chunks.chunkMap.lightTaskDispatcher.hasWork())
                .append(" sleeping=").append(chunks.chunkMap.lightTaskDispatcher.sleeping)
                .append(']'));
        section(state, "serverLoop", () -> state.append(" serverLoop=[sameThread=")
                .append(current.isSameThread())
                .append(" queued=").append(current.getPendingTasksCount())
                .append(" chunkMapHasWork=").append(chunks.chunkMap.hasWork())
                .append(']'));
        // The player-ticket dispatcher admits only a handful of chunks into
        // execution at once and releases a slot when the chunk's full future
        // completes. If it reports the same positions tick after tick, ticket
        // promotion — not the send path — is the horizon's limit.
        section(state, "ticketDispatcher", () -> state.append(" ticketDispatcher=[")
                .append(chunks.chunkMap.getDistanceManager().getDebugStatus())
                .append(']'));
        for (net.minecraft.server.level.ServerPlayer player : current.getPlayerList().getPlayers()) {
            net.minecraft.server.level.ServerChunkCache playerChunks =
                    player.level().getChunkSource();
            section(state, "dimension", () -> state.append(" playerDimension=")
                    .append(player.level().dimension().identifier()));
            section(state, "trackingView", () -> appendTrackingView(state, playerChunks, player));
            section(state, "sender", () -> appendSender(state, player));
            section(state, "statuses", () -> appendStatusHistogram(state, playerChunks, player));
            section(state, "nearHolders", () -> state.append(" nearHolders=[")
                    .append(describeNearbyHolders(playerChunks, player)).append(']'));
            section(state, "stuck", () -> state.append(" stuck=[")
                    .append(describeStuckHolder(playerChunks, player)).append(']'));
            section(state, "genRoot", () -> state.append(" genRoot=[")
                    .append(describeGenerationRoot(playerChunks, player)).append(']'));
        }
        return state.toString();
    }

    /**
     * Runs one probe section, keeping every field the earlier sections produced.
     *
     * <p>A failing section rewinds only its own partial output and names itself,
     * so one null future costs one field group instead of the whole report.</p>
     */
    private static void section(StringBuilder out, String name, Runnable body) {
        int mark = out.length();
        try {
            body.run();
        } catch (Throwable failure) {
            out.setLength(mark);
            out.append(' ').append(name).append("-failed=")
                    .append(failure.getClass().getSimpleName());
        }
    }

    /** Splits the tracking view along the exact gates that permit a send. */
    private static void appendTrackingView(
            StringBuilder state,
            net.minecraft.server.level.ServerChunkCache chunks,
            net.minecraft.server.level.ServerPlayer player
    ) {
        net.minecraft.server.level.ChunkTrackingView view = player.getChunkTrackingView();
        // 0 tracked, 1 ready-to-send, 2 pending-send, 3 no holder,
        // 4 ticket level above FULL (33), 5 exactly FULL, 6 BLOCK_TICKING
        // or better, 7 send-sync still pending, 8 sendable level but no
        // ticking chunk yet.
        int[] counts = new int[9];
        view.forEach(pos -> {
            counts[0]++;
            long packed = pos.pack();
            if (chunks.chunkMap.getChunkToSend(packed) != null) {
                counts[1]++;
            }
            if (player.connection.chunkSender.isPending(packed)) {
                counts[2]++;
            }
            // Sending requires FullChunkStatus.BLOCK_TICKING (ticket level
            // <= 32) *and* a completed send-sync future, which waits on the
            // light engine. Splitting the tracking view along exactly those
            // two gates says whether the horizon is stuck on ticket
            // promotion or on light.
            net.minecraft.server.level.ChunkHolder holder =
                    chunks.chunkMap.getUpdatingChunkIfPresent(packed);
            if (holder == null) {
                counts[3]++;
                return;
            }
            int level = holder.getTicketLevel();
            if (level > 33) {
                counts[4]++;
                return;
            }
            if (level == 33) {
                counts[5]++;
                return;
            }
            counts[6]++;
            java.util.concurrent.CompletableFuture<?> sendSync = holder.getSendSyncFuture();
            if (sendSync != null && !sendSync.isDone()) {
                counts[7]++;
            }
            if (holder.getTickingChunk() == null) {
                counts[8]++;
            }
        });
        state.append(" playerView=").append(view)
                .append(" requestedView=").append(player.requestedViewDistance())
                .append(" tracked=").append(counts[0])
                .append(" trackedReady=").append(counts[1])
                .append(" pendingSend=").append(counts[2])
                .append(" noHolder=").append(counts[3])
                .append(" lvlAbove33=").append(counts[4])
                .append(" lvl33=").append(counts[5])
                .append(" lvlSendable=").append(counts[6])
                .append(" sendSyncPending=").append(counts[7])
                .append(" notTicking=").append(counts[8]);
    }

    /**
     * The send throttle itself. desired is what the client's
     * ChunkBatchSizeCalculator asked for; quota is what has accrued toward the
     * next batch. quota &lt; 1 with a non-empty queue means the throttle, not
     * readiness, is the limit.
     */
    private static void appendSender(
            StringBuilder state,
            net.minecraft.server.level.ServerPlayer player
    ) {
        net.minecraft.server.network.PlayerChunkSender sender = player.connection.chunkSender;
        state.append(" desired=").append(sender.desiredChunksPerTick)
                .append(" quota=").append(sender.batchQuota)
                .append(" unackBatches=").append(sender.unacknowledgedBatches)
                .append('/').append(sender.maxUnacknowledgedBatches)
                .append(" senderQueue=").append(sender.pendingChunks.size())
                .append(" reconciled=").append(
                        queuedChunks.getOrDefault(player.getUUID(), java.util.Set.of()).size()
                );
    }

    /**
     * Latest generation status of every holder around the player.
     *
     * <p>Separates "generation is slow" from "generation has stopped": a
     * histogram that does not move between heartbeats while tasks are still
     * being drained means the drained tasks are not generation work.</p>
     */
    private static void appendStatusHistogram(
            StringBuilder state,
            net.minecraft.server.level.ServerChunkCache chunks,
            net.minecraft.server.level.ServerPlayer player
    ) {
        java.util.Map<String, Integer> histogram = new java.util.TreeMap<>();
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        int holders = 0;
        for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                net.minecraft.server.level.ChunkHolder holder =
                        chunks.chunkMap.getUpdatingChunkIfPresent(
                                net.minecraft.world.level.ChunkPos.pack(
                                        center.x() + dx,
                                        center.z() + dz
                                )
                        );
                if (holder == null) {
                    continue;
                }
                holders++;
                String status = String.valueOf(holder.getLatestStatus());
                histogram.merge(status, 1, Integer::sum);
            }
        }
        state.append(" holders=").append(holders).append(" statuses=[");
        boolean first = true;
        for (java.util.Map.Entry<String, Integer> entry : histogram.entrySet()) {
            if (!first) {
                state.append(' ');
            }
            first = false;
            state.append(entry.getKey()).append('=').append(entry.getValue());
        }
        state.append(']');
    }

    /**
     * Per-holder state for the chunks around the player.
     *
     * <p>The player-ticket throttler admits four chunks at a time and frees a
     * slot only when that chunk's <em>entity-ticking</em> future completes
     * ({@code DistanceManager.runAllUpdates} chains the release onto
     * {@code ChunkHolder.getEntityTickingChunkFuture()}). If those futures never
     * complete, the four slots are held forever, no further {@code
     * PLAYER_LOADING} tickets are ever applied, and the rest of the tracking
     * view stays at a ticket level above FULL — which is exactly the observed
     * horizon. The chunks holding the slots are the ones next to the player, so
     * report each nearby holder's ticket level, statuses, and which of the three
     * promotion futures is still pending.</p>
     */
    private static String describeNearbyHolders(
            net.minecraft.server.level.ServerChunkCache chunks,
            net.minecraft.server.level.ServerPlayer player
    ) {
        StringBuilder holders = new StringBuilder();
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long packed = net.minecraft.world.level.ChunkPos.pack(
                        center.x() + dx,
                        center.z() + dz
                );
                net.minecraft.server.level.ChunkHolder holder =
                        chunks.chunkMap.getUpdatingChunkIfPresent(packed);
                if (holders.length() > 0) {
                    holders.append(' ');
                }
                holders.append(center.x() + dx).append(',').append(center.z() + dz).append(':');
                if (holder == null) {
                    holders.append("none");
                    continue;
                }
                // Deliberately terse. The verbose form truncated in the
                // progress-marker pipe after five of the nine holders, and the
                // four it dropped were exactly the stuck ticket slots.
                //
                // Fields: ticket level, first letter of the full status, then
                // the full / block-ticking / entity-ticking promotion futures.
                // The first zero from the left is the step that is stuck.
                // `gen` is the holder's own FULL generation future — the input
                // `prepareTickingChunk` waits on across the whole 3x3, so a
                // neighbour showing gen=0 is what blocks the centre's ticking.
                holders.append(holder.getTicketLevel())
                        .append(String.valueOf(holder.getFullStatus()).charAt(0))
                        .append(' ')
                        .append(holder.getFullChunkFuture().isDone() ? 1 : 0)
                        .append(holder.getTickingChunkFuture().isDone() ? 1 : 0)
                        .append(holder.getEntityTickingChunkFuture().isDone() ? 1 : 0)
                        .append(" gen=").append(fullGenerationState(holder));
            }
        }
        return holders.toString();
    }

    /**
     * The root of the parked-generation cascade.
     *
     * <p>{@code ChunkGenerationTask.waitForScheduledLayer()} parks the task on
     * the last future in its {@code scheduledLayer}, and those futures are the
     * generation futures of <em>neighbouring</em> holders. So one holder whose
     * future never completes parks every task queued behind it, and the visible
     * symptom (a chunk at a sendable ticket level whose FULL never arrives) can
     * be many chunks away from the cause.</p>
     *
     * <p>Parked tasks are unreachable — nothing holds them but the {@code
     * thenRun} continuation on the future they wait for — so this searches from
     * the holder side instead. The holder with the <em>lowest-index</em> pending
     * generation status is the deepest point of the cascade: everything above it
     * is waiting, and it is waiting on nothing earlier. That is the chunk to
     * explain.</p>
     */
    private static String describeGenerationRoot(
            net.minecraft.server.level.ServerChunkCache chunks,
            net.minecraft.server.level.ServerPlayer player
    ) {
        net.minecraft.world.level.ChunkPos center = player.chunkPosition();
        net.minecraft.server.level.ChunkHolder root = null;
        net.minecraft.world.level.chunk.status.ChunkStatus rootStatus = null;
        int rootIndex = Integer.MAX_VALUE;
        int pendingHolders = 0;
        int scanned = 0;
        // Wide enough to cover the tracking view plus the radius-8 generation
        // halo that feeds its edge.
        for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
            for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
                net.minecraft.server.level.ChunkHolder holder =
                        chunks.chunkMap.getUpdatingChunkIfPresent(
                                net.minecraft.world.level.ChunkPos.pack(
                                        center.x() + dx,
                                        center.z() + dz
                                )
                        );
                if (holder == null) {
                    continue;
                }
                scanned++;
                boolean anyPending = false;
                for (com.mojang.datafixers.util.Pair<
                        net.minecraft.world.level.chunk.status.ChunkStatus,
                        java.util.concurrent.CompletableFuture<
                                net.minecraft.server.level.ChunkResult<
                                        net.minecraft.world.level.chunk.ChunkAccess>>> entry
                        : holder.getAllFutures()) {
                    if (entry.getSecond().isDone()) {
                        continue;
                    }
                    anyPending = true;
                    int index = entry.getFirst().getIndex();
                    if (index < rootIndex) {
                        rootIndex = index;
                        root = holder;
                        rootStatus = entry.getFirst();
                    }
                }
                if (anyPending) {
                    pendingHolders++;
                }
            }
        }
        StringBuilder detail = new StringBuilder();
        detail.append("scanned=").append(scanned)
                .append(" pendingHolders=").append(pendingHolders);
        if (root == null) {
            return detail.append(" root=none").toString();
        }
        return detail.append(" root=").append(root.getPos().x())
                .append(',').append(root.getPos().z())
                .append(" lvl=").append(root.getTicketLevel())
                .append(" waitingOn=").append(rootStatus)
                .append(" latest=").append(root.getLatestStatus())
                .toString();
    }

    /**
     * The first wedged holder, and <em>its own</em> 3x3 inputs.
     *
     * <p>Sampling around the player was misleading: it showed nine neighbours
     * all at {@code gen=1} while the centre was promoted fine. The chunks that
     * actually stall sit at the edge of the ticketed region, and what blocks
     * them is their <em>own</em> radius-1 neighbourhood reaching outward into
     * chunks that were never ticketed. {@code prepareTickingChunk} waits on the
     * FULL future of all nine, so a neighbour with {@code gen=0} — or no holder
     * at all — is the thing to fix.</p>
     *
     * <p>Reports the first holder in the tracking view that is at a sendable
     * ticket level ({@code <= 32}) but whose ticking future is still pending.
     * That is exactly the state that keeps a ticket-throttler slot occupied.</p>
     */
    private static String describeStuckHolder(
            net.minecraft.server.level.ServerChunkCache chunks,
            net.minecraft.server.level.ServerPlayer player
    ) {
        net.minecraft.server.level.ChunkHolder[] wedged = {null};
        player.getChunkTrackingView().forEach(pos -> {
            if (wedged[0] != null) {
                return;
            }
            net.minecraft.server.level.ChunkHolder holder =
                    chunks.chunkMap.getUpdatingChunkIfPresent(pos.pack());
            if (holder != null
                    && holder.getTicketLevel() <= 32
                    && !holder.getTickingChunkFuture().isDone()) {
                wedged[0] = holder;
            }
        });
        if (wedged[0] == null) {
            return "none";
        }
        net.minecraft.world.level.ChunkPos at = wedged[0].getPos();
        StringBuilder detail = new StringBuilder();
        detail.append(at.x()).append(',').append(at.z())
                .append(" lvl=").append(wedged[0].getTicketLevel())
                .append(" inputs:");
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                long packed = net.minecraft.world.level.ChunkPos.pack(at.x() + dx, at.z() + dz);
                net.minecraft.server.level.ChunkHolder input =
                        chunks.chunkMap.getUpdatingChunkIfPresent(packed);
                detail.append(' ').append(at.x() + dx).append(',').append(at.z() + dz).append(':');
                if (input == null) {
                    detail.append("NOHOLDER");
                    continue;
                }
                detail.append(input.getTicketLevel())
                        .append('/').append(fullGenerationState(input));
            }
        }
        return detail.toString();
    }

    /**
     * State of this holder's {@code minecraft:full} generation future.
     *
     * <p>{@code prepareTickingChunk} waits on the FULL future of every chunk in
     * the 3x3 around it, so this is the exact per-neighbour input that decides
     * whether the centre chunk can ever be promoted to BLOCK_TICKING.</p>
     *
     * <p>{@code missing} is meaningful on its own: it means no FULL future has
     * been scheduled for this holder at all, which is a different failure from
     * one that is scheduled and pending.</p>
     */
    private static String fullGenerationState(net.minecraft.server.level.ChunkHolder holder) {
        for (com.mojang.datafixers.util.Pair<
                net.minecraft.world.level.chunk.status.ChunkStatus,
                java.util.concurrent.CompletableFuture<
                        net.minecraft.server.level.ChunkResult<
                                net.minecraft.world.level.chunk.ChunkAccess>>> entry
                : holder.getAllFutures()) {
            if (entry.getFirst() == net.minecraft.world.level.chunk.status.ChunkStatus.FULL) {
                return entry.getSecond().isDone() ? "1" : "0";
            }
        }
        return "missing";
    }

    /** The generation statuses this holder has scheduled but not finished. */
    private static String describePendingGeneration(
            net.minecraft.server.level.ChunkHolder holder
    ) {
        StringBuilder pending = new StringBuilder();
        for (com.mojang.datafixers.util.Pair<
                net.minecraft.world.level.chunk.status.ChunkStatus,
                java.util.concurrent.CompletableFuture<
                        net.minecraft.server.level.ChunkResult<
                                net.minecraft.world.level.chunk.ChunkAccess>>> entry
                : holder.getAllFutures()) {
            if (pending.length() > 0) {
                pending.append('+');
            }
            // "none" for an empty list was ambiguous: it could not distinguish
            // "every generation step finished" from "no step was ever
            // scheduled". List every future with its state instead.
            pending.append(entry.getFirst())
                    .append(entry.getSecond().isDone() ? "=done" : "=PENDING");
        }
        return pending.length() == 0 ? "no-futures" : pending.toString();
    }

    /**
     * Set by the client through the server-Worker control channel
     * ({@code mcweb.server.state world-entered}) once it drops the loading
     * screen. The private server realm has no {@code Minecraft} instance, so
     * without this signal {@link #loadingWorld()} stays true there forever:
     * the server never enters its 20 TPS pacing branch, bursts up to ten
     * heavy ticks per pump, and the chunk reconciler never runs. Measured:
     * heartbeat at tick 200 reading {@code loadingTicks=199 idleDrains=0}.
     */
    private static boolean clientWorldEntered;

    public static void markClientWorldEntered() {
        if (!clientWorldEntered) {
            clientWorldEntered = true;
            BrowserGpu.reportProgress("server-world:client-entered");
        }
    }

    /**
     * Streams the server's chunk-status grid to the client's loading screen.
     *
     * <p>Vanilla wires the grid in {@code Minecraft.doWorldLoad} via
     * {@code tracker.setServerChunkStatusView(server.createChunkLoadStatusView(radius))}
     * — code after {@code MinecraftServer.spin}, which the private server-Worker
     * lane never executes. The grid therefore never renders there while the
     * inline lane keeps it. Sampling {@code ChunkMap.getLatestStatus} here and
     * shipping it over the existing {@code levelload:} channel restores the
     * identical client-side view ({@link BrowserChunkLoadStatusView}).</p>
     */
    private static boolean chunkGridEnabled;
    private static long lastGridSampleNanos;

    /** Armed by the server-Worker lane only; see {@link BrowserWorkerServerMain}. */
    public static void enableChunkGrid() {
        chunkGridEnabled = true;
        try {
            BrowserGpu.reportProgress("grid:armed");
        } catch (Throwable ignored) {
            // Arming diagnostics must not disturb world creation.
        }
    }

    private static boolean gridFirstSampleReported;

    private static void sampleChunkGrid(IntegratedServer current, long now) {
        if (now - lastGridSampleNanos < GRID_SAMPLE_INTERVAL_NANOS) {
            return;
        }
        lastGridSampleNanos = now;
        try {
            net.minecraft.server.level.ServerLevel overworld = current.overworld();
            if (overworld == null) {
                reportGridOnce("grid-sample-failed no-overworld");
                return;
            }
            net.minecraft.server.level.ChunkMap chunkMap = overworld.getChunkSource().chunkMap;
            int radius = Math.max(5, 3)
                    + net.minecraft.server.level.ChunkLevel.RADIUS_AROUND_FULL_CHUNK + 1;
            int centerX = 0;
            int centerZ = 0;
            boolean centeredOnPlayer = false;
            try {
                java.util.List<net.minecraft.server.level.ServerPlayer> players =
                        current.getPlayerList() == null
                                ? java.util.List.of() : current.getPlayerList().getPlayers();
                if (!players.isEmpty()) {
                    net.minecraft.world.level.ChunkPos position = players.get(0).chunkPosition();
                    centerX = position.x();
                    centerZ = position.z();
                    centeredOnPlayer = true;
                } else if (overworld.getRespawnData() != null
                        && overworld.getRespawnData().pos() != null) {
                    net.minecraft.core.BlockPos spawn = overworld.getRespawnData().pos();
                    centerX = net.minecraft.world.level.ChunkPos.containing(spawn).x();
                    centerZ = net.minecraft.world.level.ChunkPos.containing(spawn).z();
                }
            } catch (Throwable centerFailure) {
                reportGridOnce("grid-center-failed " + centerFailure.getClass().getSimpleName());
            }
            int side = 2 * radius + 1;
            byte[] grid = new byte[side * side];
            java.util.Arrays.fill(grid, (byte) -1);
            int filled = 0;
            for (int i = 0; i < side; i++) {
                for (int j = 0; j < side; j++) {
                    net.minecraft.world.level.chunk.status.ChunkStatus status =
                            chunkMap.getLatestStatus(net.minecraft.world.level.ChunkPos.pack(
                                    centerX + i - radius, centerZ + j - radius));
                    if (status != null) {
                        grid[i * side + j] = (byte) status.getIndex();
                        filled++;
                    }
                }
            }
            reportGridOnce("grid-sample radius=" + radius + " center=" + centerX + "," + centerZ
                    + " player=" + centeredOnPlayer + " filled=" + filled + "/" + grid.length);
            BrowserGpu.reportProgress("levelload:grid " + centerX + " " + centerZ + " "
                    + radius + " " + java.util.Base64.getEncoder().encodeToString(grid));
        } catch (Throwable sampleFailure) {
            reportGridOnce("grid-sample-failed " + sampleFailure.getClass().getSimpleName()
                    + " " + sampleFailure.getMessage());
        }
    }

    private static void reportGridOnce(String message) {
        if (gridFirstSampleReported) {
            return;
        }
        gridFirstSampleReported = true;
        try {
            BrowserGpu.reportProgress("grid:" + message);
        } catch (Throwable ignored) {
            // A diagnostic must never take down world loading.
        }
    }

    /**
     * True while the client is still getting into the world.
     *
     * <p>Deliberately not just {@code level == null}: the level exists from
     * {@code ClientboundLoginPacket} onward, but the player then waits behind
     * "Loading terrain…" for the spawn chunks — which is precisely the phase
     * that is starved by holding the server to 20 TPS. {@code LevelLoadingScreen}
     * covers both that wait and the earlier world-creation one.</p>
     */
    private static boolean loadingWorld() {
        if (clientWorldEntered) {
            return false;
        }
        net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
        if (client == null || client.level == null) {
            return true;
        }
        return client.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
    }

    /** Catch-up ticks spent on world loading; reported for measurement. */
    public static int loadingTicks() {
        return loadingTicks;
    }

    /**
     * Advances the cooperatively scheduled server while Minecraft's
     * {@code doWorldLoad} method waits for {@code IntegratedServer.isReady()}.
     *
     * <p>Desktop Minecraft starts a server thread before entering that wait.
     * The browser transform deliberately does not start the thread, so waiting
     * without pumping here occupies the only JavaScript thread forever and the
     * normal animation-frame pump never gets a chance to initialize the
     * server.</p>
     */
    public static boolean pumpAndIsReady(MinecraftServer expected) {
        if (McWebRuntimeMode.isThreaded()) {
            AgentExecutorService.servicePrimaryCollectionRequest("world-ready-wait");
            // This is the client-side observation point during vanilla doWorldLoad.
            // The real Server thread owns initServer/runServer and will set isReady;
            // no frame-thread pump or executor stealing is permitted in this path.
            if (expected != null && !expected.isReady()
                    && (readyWaitPumps++ & 0x3F) == 0) {
                BrowserGpu.reportProgress("world-load:ready-wait threaded n="
                        + readyWaitPumps + " " + AgentExecutorService.queueState());
            }
            return expected != null && expected.isReady();
        }
        if (server == expected && !expected.isReady()) {
            /*
             * This wait is inside one Wasm call, so the frame pump is not running and
             * neither is anything it drives - including the agent pool's stall rescue.
             * If every pool worker is parked inside a task that waits on another task,
             * nothing outside this loop can break it, so the loop does it itself.
             * See AgentExecutorService.drainInlineIfStarved.
             */
            AgentExecutorService.maintain();
            AgentExecutorService.drainInlineWhileWaiting(256);
            /*
             * The beacon is the only view of this loop when the browser thread stops
             * returning, so make it say whether the loop is running at all and what the
             * pool looks like. Without this, "wedged inside pumpAndIsReady" and "wedged
             * somewhere inside pump()" produce the identical silence.
             */
            if ((readyWaitPumps++ & 0x3F) == 0) {
                BrowserGpu.reportProgress("world-load:ready-wait n=" + readyWaitPumps
                        + " " + AgentExecutorService.queueState());
            }
            pump();
        }
        reportReadyIfNeeded(expected);
        return expected.isReady();
    }

    /** Emits one marker for both a real WasmLM server thread and the cooperative path. */
    private static void reportReadyIfNeeded(MinecraftServer expected) {
        if (McWebRuntimeMode.isCooperativeServer()
                && expected != null && expected.isReady() && readyReportedServer != expected) {
            readyReportedServer = expected;
            BrowserGpu.reportProgress("integrated-server:ready");
        }
    }

    /** Iterations of the {@code doWorldLoad} ready-wait; reported into the beacon. */
    private static int readyWaitPumps;

    /**
     * Polls the server's chunk source from the primary's cooperative park.
     *
     * <p>The world-load wedge: spawn finding calls {@code getChunk}, which blocks
     * in {@code managedBlock} until the chunk future completes. The generation
     * work runs on the agent pool; its completion callback lands in the
     * {@code MainThreadExecutor}'s queue. {@code managedBlock}'s own
     * {@code pollTask()} processes that queue — but only while the loop runs.
     * Once the primary falls through to {@code CompletableFuture.join()}, the
     * join-spin calls {@code park()} → {@code parkDrain()}, which drains the
     * agent pool (empty) but never touches the {@code MainThreadExecutor}'s
     * queue. The callback sits there forever.
     *
     * <p>Polling the chunk source from here processes the callback, completing
     * the future and unblocking the join. This is exactly what the WasmGC inline
     * executor gets for free: every blocking wait runs its own dependencies.
     */
    private static int parkChunkPolls;

    public static void pollChunkSourceFromPark() {
        IntegratedServer current = server;
        if (current == null) {
            return;
        }
        try {
            grantTaskDeadline(current, System.nanoTime() + 5_000_000L);
            current.getConnection().drainInbound();
            current.runAllTasks();
            net.minecraft.server.level.ServerChunkCache chunkSource =
                    primaryPlayerLevel(current).getChunkSource();
            int polled = 0;
            for (int i = 0; i < 128; i++) {
                if (!chunkSource.pollTask()) {
                    break;
                }
                polled++;
            }
            if ((++parkChunkPolls & 0xFFF) == 0) {
                BrowserGpu.reportProgress("park-chunk-poll #" + parkChunkPolls
                        + " polled=" + polled
                        + " pending=" + chunkSource.getPendingTasksCount()
                        + " " + AgentExecutorService.queueState());
            }
        } catch (Throwable ignored) {
            // A park drain must never break the wait it is servicing.
            // Before createLevels() runs, overworld() throws — harmless.
        }
    }

    /**
     * Poll only the chunk executor while its {@code managedBlock} loop is
     * spinning.  {@link #pollChunkSourceFromPark()} also drains the server's
     * main queue, which is useful from a park callback but can recursively
     * enter another managed block when called from the chunk loop itself.
     *
     * <p>The vanilla {@code MainThreadExecutor} can return {@code true} after
     * distance-manager bookkeeping even when its own completion queue is empty.
     * That leaves {@code managedBlock} spinning without ever reaching
     * {@code waitForTasks}; this narrow poll keeps the chunk completion path
     * moving without re-entering the whole server executor.</p>
     */
    public static void pollChunkSourceFromBlockingWait() {
        IntegratedServer current = server;
        if (current == null) {
            return;
        }
        try {
            grantTaskDeadline(current, System.nanoTime() + 5_000_000L);
            net.minecraft.server.level.ServerChunkCache chunkSource =
                    primaryPlayerLevel(current).getChunkSource();
            for (int i = 0; i < 32; i++) {
                if (!chunkSource.pollTask()) {
                    break;
                }
            }
        } catch (Throwable ignored) {
            // A wait-side poll must never break the wait it is servicing.
        }
    }

}
