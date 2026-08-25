package dev.mcweb.graal;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.TracingExecutor;
import org.graalvm.webimage.api.JS;

/**
 * Paced executor for the client {@code SectionRenderDispatcher} on the
 * cooperative WasmGC lane.
 *
 * <p><b>Why pacing is needed here.</b> Vanilla's {@code runTask()} meshes one
 * section and re-submits itself to the dispatcher's executor; on a desktop
 * thread pool that is naturally paced. On this port the dispatcher's executor
 * resolved to {@code InlineExecutorService}, whose {@code execute} runs the
 * task synchronously — so the self-resubmission becomes a recursive drain of
 * the entire mesh queue inside one frame. The measured signature on a
 * superflat walk: up to 204 {@code UberBuffer solid 0} staging uploads
 * (~11.7 MB) bursting in a single frame, frames spiking to 40–52 ms against a
 * 4.7 ms baseline, and upload bytes correlating 1:1 with frame time (fast
 * frames average ~63 KB, slow frames ~490 KB). Every byte pays the WasmGC
 * string-bridge toll, so one reveal burst is tens of milliseconds of encoding.
 *
 * <p>This executor queues tasks and drains them from {@link BrowserFramePump}
 * under both a task budget and a time budget ({@code ?mcweb_mesh_ms}, default
 * 5 ms). Interactive drains are also rate-limited independently of rendered
 * FPS ({@code ?mcweb_mesh_hz}, default 75) and prefer frames whose Minecraft
 * work has used less than {@code ?mcweb_mesh_start_ms} (default 5 ms). A
 * section compile on real terrain costs several milliseconds; starting one on
 * every 180+ FPS frame made faster rendering schedule proportionally more
 * synchronous chunk work until cold streaming self-capped near 98 FPS. A
 * bounded 50 ms deferral guarantees progress when no slack frame appears.
 * Loading screens remain unlimited.
 *
 * <p><b>Bulk streaming.</b> All of that assumes a drawn world being topped up.
 * Joining a server is the opposite case and has no loading screen to hide
 * behind, so a backlog of at least {@code ?mcweb_mesh_bulk} sections (default
 * 32) drops the interactive rate and slack gates and drains under the time
 * budget alone. Without it the slack gate — which skips any frame that has
 * already spent 5 ms on Minecraft's own work, i.e. every frame on a server —
 * held meshing to ~11 sections/s and left hoplite.gg's 265 chunks, all of
 * which had arrived within 8 s, still being drawn 24 s after world entry.
 *
 * <p>{@code ?mcweb_mesh_pace=N} overrides the per-frame task budget
 * ({@code 0} = drain everything, reproducing the pre-pacing behavior for
 * A/B runs).
 */
public final class BrowserMeshPaceExecutor extends AbstractExecutorService {

    private static final BrowserMeshPaceExecutor INSTANCE = new BrowserMeshPaceExecutor();
    private static final TracingExecutor TRACING = new TracingExecutor(INSTANCE);
    private static final int DEFAULT_TASKS_PER_FRAME = 4;
    private static final long DEFAULT_DRAIN_BUDGET_NANOS = 5_000_000L;
    private static final int DEFAULT_INTERACTIVE_RATE_HZ = 75;
    private static final long DEFAULT_START_BUDGET_NANOS = 5_000_000L;
    private static final long MAX_SLACK_DEFERRAL_NANOS = 50_000_000L;
    private static final int DEFAULT_BULK_QUEUE_THRESHOLD = 32;

    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private static volatile int budgetOverride = -1;
    private static volatile long budgetNanosOverride = -1L;
    private static volatile int rateHzOverride = -1;
    private static volatile int bulkThresholdOverride = -1;
    private static volatile long startBudgetNanosOverride = -1L;
    private long nextInteractiveStartNanos;
    private long completedTasks;
    private long rateDeferredFrames;
    private long slackDeferredFrames;
    private long bulkFrames;
    private int maxQueuedTasks;

    private BrowserMeshPaceExecutor() {
    }

    /**
     * The dispatcher's executor for the cooperative WasmGC image.
     */
    public static TracingExecutor tracing() {
        return TRACING;
    }

    /**
     * Runs tasks under the task + time budgets. Called once per frame from
     * {@link BrowserFramePump}, after {@code runTick}: the meshes completed
     * here stage their uploads for the next frame's
     * {@code uploadTerrainBuffersToGpu}, so a burst spreads across frames
     * instead of landing inside one.
     */
    public static void drainFromFrame(long frameStartedNanos) {
        INSTANCE.drain(frameStartedNanos);
    }

    private void drain(long frameStartedNanos) {
        boolean loading = isLoadingScreenActive();
        boolean bulk = false;
        int budget;
        long deadline;
        if (loading) {
            // While the loading screen is up nothing interactive competes for
            // the frame, so the queue drains without a budget.
            budget = Integer.MAX_VALUE;
            deadline = Long.MAX_VALUE;
        } else {
            budget = tasksPerFrame();
            if (budget == 0) {
                // Budget 0 is the legacy unlimited drain, kept for A/B runs.
                budget = Integer.MAX_VALUE;
                deadline = Long.MAX_VALUE;
            } else {
                int queued;
                synchronized (queue) {
                    queued = queue.size();
                }
                if (queued == 0) {
                    nextInteractiveStartNanos = 0L;
                    return;
                }
                long now = System.nanoTime();
                int rateHz = interactiveRateHz();
                // Bulk streaming overrides the interactive pace.
                //
                // Pacing assumes the world is already drawn and the queue is
                // topping it up. That holds in single-player, where the
                // LevelLoadingScreen covers the initial fill and pacing is off
                // for it. Joining a *server* has no such screen: the player is
                // in the world immediately and this paced path is the only
                // thing that fills it, so the pace became the join time.
                //
                // Worse, the slack preference below cannot be satisfied on a
                // server at all. It skips any frame that has already spent
                // startBudgetNanos (5 ms) on Minecraft's own work, and a real
                // server frame costs 12-21 ms, so every frame was skipped and
                // the only meshes that ran were the ones the 50 ms deferral
                // bound forced through. Measured on hoplite.gg: all 265 chunks
                // arrived over the network in 8 s, then meshing crawled at a
                // flat ~11 sections/s and the world was not fully drawn until
                // 24 s after entry.
                //
                // A backlog this size means the player is looking at holes, so
                // spend the frame budget on closing them. The time budget below
                // still bounds what one frame may do.
                bulk = queued >= bulkQueueThreshold();
                if (bulk) {
                    bulkFrames++;
                    // The next interactive start is measured from the last
                    // mesh, and bulk frames are meshing continuously; leaving a
                    // stale deadline behind would make the first interactive
                    // frame after the backlog clears look overdue.
                    nextInteractiveStartNanos = 0L;
                }
                if (rateHz > 0 && !bulk) {
                    if (nextInteractiveStartNanos == 0L) {
                        nextInteractiveStartNanos = now;
                    }
                    if (now < nextInteractiveStartNanos) {
                        rateDeferredFrames++;
                        return;
                    }
                    long overdue = now - nextInteractiveStartNanos;
                    if (now - frameStartedNanos >= startBudgetNanos()
                            && overdue < MAX_SLACK_DEFERRAL_NANOS) {
                        slackDeferredFrames++;
                        return;
                    }
                    // Rate is starts/second, not tasks/rendered-frame. Never
                    // repay missed starts as a same-frame burst.
                    budget = Math.min(budget, 1);
                }
                deadline = now + drainBudgetNanos();
            }
        }
        int run = 0;
        while (budget-- > 0) {
            Runnable task;
            synchronized (queue) {
                task = queue.pollFirst();
            }
            if (task == null) {
                break;
            }
            task.run();
            run++;
            completedTasks++;
            // Time budget, checked after at least one task so the backlog
            // always advances.
            if (!loading && run >= 1 && System.nanoTime() >= deadline) {
                break;
            }
        }
        if (run > 0) {
            if (!loading && !bulk && interactiveRateHz() > 0) {
                nextInteractiveStartNanos = System.nanoTime()
                        + 1_000_000_000L / interactiveRateHz();
            }
            // Match InlineExecutorService semantics once per frame, not once
            // per task: a mesh completion may enqueue main-thread follow-ups
            // that other waits depend on. Per-task drains multiplied the
            // main-queue walk by the task count on busy frames.
            InlineExecutorService.drainMainLoopFromFrame();
            if (completedTasks == 1L || completedTasks % 256L == 0L) {
                int queued;
                synchronized (queue) {
                    queued = queue.size();
                }
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "mesh-pace:completed=" + completedTasks
                                + " queued=" + queued
                                + " maxQueued=" + maxQueuedTasks
                                + " rateHz=" + interactiveRateHz()
                                + " rateDeferred=" + rateDeferredFrames
                                + " slackDeferred=" + slackDeferredFrames
                                + " bulkFrames=" + bulkFrames
                );
            }
        }
    }

    /**
     * Queue depth at which the paced path switches to bulk streaming.
     * {@code ?mcweb_mesh_bulk=0} disables the override entirely, restoring the
     * interactive-only pace for an A/B; a large value has the same effect.
     */
    private static int bulkQueueThreshold() {
        int override = bulkThresholdOverride;
        if (override >= 0) {
            return override;
        }
        override = readBulkThreshold();
        override = override < 0 ? DEFAULT_BULK_QUEUE_THRESHOLD : override;
        bulkThresholdOverride = override;
        return override == 0 ? Integer.MAX_VALUE : override;
    }

    @JS.Coerce
    @JS(
            value = "const raw = new URLSearchParams("
                    + "globalThis.location?.search || '').get('mcweb_mesh_bulk'); "
                    + "const v = raw === null ? -1 : Number(raw); "
                    + "return Number.isFinite(v) && v >= 0 ? Math.trunc(v) : -1;",
            args = {})
    private static native int readBulkThreshold();

    private static int interactiveRateHz() {
        int override = rateHzOverride;
        if (override >= 0) {
            return override;
        }
        override = readInteractiveRateHz();
        override = override < 0 ? DEFAULT_INTERACTIVE_RATE_HZ : override;
        rateHzOverride = override;
        return override;
    }

    @JS.Coerce
    @JS(
            value = "const raw = new URLSearchParams("
                    + "globalThis.location?.search || '').get('mcweb_mesh_hz'); "
                    + "const v = raw === null ? -1 : Number(raw); "
                    + "return Number.isFinite(v) && v >= 0 && v <= 1000 ? Math.trunc(v) : -1;",
            args = {})
    private static native int readInteractiveRateHz();

    private static long startBudgetNanos() {
        long override = startBudgetNanosOverride;
        if (override >= 0L) {
            return override;
        }
        int ms = readStartBudgetMs();
        override = ms < 0 ? DEFAULT_START_BUDGET_NANOS : ms * 1_000_000L;
        startBudgetNanosOverride = override;
        return override;
    }

    @JS.Coerce
    @JS(
            value = "const raw = new URLSearchParams("
                    + "globalThis.location?.search || '').get('mcweb_mesh_start_ms'); "
                    + "const v = raw === null ? -1 : Number(raw); "
                    + "return Number.isFinite(v) && v >= 0 && v <= 1000 ? Math.trunc(v) : -1;",
            args = {})
    private static native int readStartBudgetMs();

    private static long drainBudgetNanos() {
        long override = budgetNanosOverride;
        if (override >= 0) {
            return override;
        }
        int ms = readDrainBudgetMs();
        override = ms < 0 ? DEFAULT_DRAIN_BUDGET_NANOS : ms * 1_000_000L;
        budgetNanosOverride = override;
        return override;
    }

    @JS.Coerce
    @JS(
            value = "const raw = new URLSearchParams("
                    + "globalThis.location?.search || '').get('mcweb_mesh_ms'); "
                    + "const v = raw === null ? -1 : Number(raw); "
                    + "return Number.isFinite(v) && v >= 0 ? v : -1;",
            args = {})
    private static native int readDrainBudgetMs();

    /**
     * While the loading screen is up the world needs every section it can
     * get as fast as possible and nothing interactive competes for the
     * frame, so pacing defers. Pacing starts only when play begins, which is
     * when the walk/reveal bursts become visible. Package-visible for
     * {@link BrowserTerrainUploadPace}, which applies the same rule to its
     * upload byte budget.
     */
    static boolean isLoadingScreenActive() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            return client != null && client.gui != null
                    && client.gui.screen() instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
        } catch (Throwable ignored) {
            // Pre-client or teardown frame: pacing simply stays off.
            return true;
        }
    }

    private static int tasksPerFrame() {
        int override = budgetOverride;
        if (override >= 0) {
            return override;
        }
        override = readBudgetOverride();
        // Missing parameter: readBudgetOverride returns -1; use the default.
        override = override < 0 ? DEFAULT_TASKS_PER_FRAME : override;
        budgetOverride = override;
        return override;
    }

    @JS.Coerce
    @JS(
            value = "const raw = new URLSearchParams("
                    + "globalThis.location?.search || '').get('mcweb_mesh_pace'); "
                    + "const v = raw === null ? -1 : Number(raw); "
                    + "return Number.isFinite(v) && v >= 0 ? v : -1;",
            args = {})
    private static native int readBudgetOverride();

    @Override
    public void execute(final Runnable command) {
        Objects.requireNonNull(command, "command");
        synchronized (queue) {
            queue.addLast(command);
            maxQueuedTasks = Math.max(maxQueuedTasks, queue.size());
        }
    }

    @Override
    public void shutdown() {
        synchronized (queue) {
            queue.clear();
        }
    }

    @Override
    public List<Runnable> shutdownNow() {
        synchronized (queue) {
            queue.clear();
        }
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return false;
    }

    @Override
    public boolean isTerminated() {
        return false;
    }

    @Override
    public boolean awaitTermination(final long timeout, final TimeUnit unit) {
        return true;
    }
}
