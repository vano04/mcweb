package dev.mcweb.graal;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Same-thread executor for the cooperatively single-threaded browser runtime.
 * Every submitted task runs inline on the calling thread; no worker thread is
 * ever started. This replaces Minecraft's ForkJoinPool-backed executors: Web
 * Image substitutes {@code jdk.internal.misc.Unsafe.park/unpark} to throw, and
 * ForkJoinPool calls those directly (signalWork unparks parked workers), so any
 * real pool task ends in {@code UnsupportedOperationException: Unsafe.unpark}.
 * Inline execution keeps all of Mojang's CompletableFuture chains on one
 * thread, where they complete before anyone parks.
 *
 * <p><b>Drain-after-run (the title-screen fix).</b> Inline execution creates a
 * single-thread circular wait during the initial reload: the main thread submits
 * a reload task here and blocks on its result (via {@code .get()}/{@code
 * .join()}); that task, while running inline on the main thread, posts a
 * follow-up to Minecraft's <i>main</i> executor and blocks waiting for it — but
 * the main thread is already blocked, so the follow-up never runs and the
 * reload stalls forever on the loading splash (the title screen never appears).
 * The constructor's {@code blockUntilDone} is drained by the transplanted
 * {@code mcwebDrainMainLoop}, but the post-constructor loading loop is not.
 * Running the tasks makes the circular dependency visible to the same thread,
 * so after each task we drain the main loop's queue ({@code runAllTasks()});
 * any main-thread work the task queued — and is waiting on — then completes
 * before the task returns, breaking the deadlock. The lookup is cached and
 * reflection-based (the holder is not on Minecraft's classpath at compile time).
 */
public final class InlineExecutorService extends AbstractExecutorService {
    public static final InlineExecutorService INSTANCE = new InlineExecutorService();

    private static volatile Method drainMethod;
    private static volatile boolean drainEnabled;
    private static volatile boolean taskDiagnosticsEnabled;
    private static int taskCount;

    private InlineExecutorService() {
    }

    /**
     * Enables the post-task Minecraft queue drain after the real client
     * constructor has completed. Resolving this method from early bootstrap
     * tasks can initialize Minecraft too soon; resolving it here is both safe
     * and deterministic.
     */
    public static void activateMainLoopDrain() {
        try {
            Class<?> mc = Class.forName("net.minecraft.client.Minecraft");
            drainMethod = mc.getMethod("mcwebDrainMainLoop");
            drainEnabled = true;
        } catch (Throwable failure) {
            drainMethod = null;
            drainEnabled = false;
            try {
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "inline-drain-activation-failed:"
                                + failure.getClass().getSimpleName());
            } catch (Throwable ignored) {
                // Telemetry must never replace the original failure.
            }
        }
    }

    public static void armTaskDiagnostics() {
        taskDiagnosticsEnabled = true;
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        // DIAG: a sparse reload-progress breadcrumb. Every 32nd task produced more
        // than 330 console messages during a normal boot, which both hid the useful
        // failure and made DevTools/CDP capture do work on the performance path. A
        // 1024-task cadence still proves that a long reload is advancing while keeping
        // the hosting console bounded.
        if ((++taskCount & 0x3FF) == 1) {
            try {
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "inline-task#" + taskCount + ":" + command.getClass().getSimpleName());
            } catch (Throwable ignored) {
                // Telemetry must never break the boot.
            }
        }
        int currentTask = taskCount;
        if (taskDiagnosticsEnabled) {
            reportActiveTask("enter", currentTask, command);
        }
        command.run();
        if (taskDiagnosticsEnabled) {
            reportActiveTask("exit", currentTask, command);
        }
        drainMainLoop();
    }

    /**
     * Drains work posted to Minecraft's main executor from the browser frame
     * pump. Some CompletableFuture completions are enqueued only after the
     * inline worker task returns, so a post-task drain alone can miss them.
     */
    public static void drainMainLoopFromFrame() {
        drainMainLoop();
    }

    private static void reportActiveTask(String phase, int number, Runnable command) {
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "inline-active-" + phase + "#" + number + ":"
                            + command.getClass().getSimpleName());
        } catch (Throwable ignored) {
            // DIAG only; task execution must remain authoritative.
        }
    }

    /**
     * Best-effort drain of Minecraft's main-thread task queue. Reflective
     * because the transplanted {@code mcwebDrainMainLoop} lives in
     * {@code net.minecraft.client.Minecraft} (same package as {@code
     * runAllTasks()}, which is protected) and is not visible here at compile
     * time. Cached; failures (class not loaded yet, not on the game thread) are
     * swallowed — the next task retries.
     */
    private static void drainMainLoop() {
        if (!drainEnabled) {
            return;
        }
        Method m = drainMethod;
        if (m == null) {
            return;
        }
        try {
            m.invoke(null);
        } catch (Throwable failure) {
            if (taskDiagnosticsEnabled) {
                try {
                    dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                            "inline-drain-failed:" + failure.getClass().getSimpleName());
                } catch (Throwable ignored) {
                    // DIAG only.
                }
            }
        }
    }

    @Override
    public void shutdown() {
        // No workers to stop.
    }

    @Override
    public List<Runnable> shutdownNow() {
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
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        return true;
    }
}
