package dev.mcweb.graal;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.texture.TextureContents;
import net.minecraft.client.renderer.texture.ReloadableTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.TracingExecutor;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.lwjgl.system.MemoryUtil;

/**
 * Bounded, transition-oriented diagnostics for Minecraft's resource reload graph.
 *
 * <p>The threaded WasmLM client can keep rendering while one reload future remains
 * incomplete. Java stack traces are unavailable there, and logging every one of the
 * roughly fourteen thousand executor tasks overflows the browser's diagnostic pipe.
 * This probe instead names every listener transition, attributes executor work to the
 * listener and prepare/apply lane that submitted it, and emits a compact snapshot every
 * few seconds. A single stalled run therefore distinguishes an unstarted task, a wedged
 * worker, an undrained main-executor task, an incomplete preparation barrier, a serial
 * dependency on the previous listener, and an exceptional future.
 *
 * <p>The jar transform routes only {@code SimpleReloadInstance}'s lifecycle seams here.
 * With no WasmLM agents, every method preserves Mojang's call directly and records
 * nothing, so the shipping WasmGC path is unchanged.
 */
public final class BrowserReloadDiagnostics {
    private static final long SNAPSHOT_MILLIS = 5_000L;
    private static final int TASK_REPORT_INTERVAL = 512;

    private static final AtomicInteger NEXT_RELOAD = new AtomicInteger();
    private static final AtomicLong NEXT_EVENT = new AtomicLong();
    private static final ConcurrentHashMap<SimpleReloadInstance<?>, ReloadProbe> RELOADS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<PreparableReloadListener, ListenerProbe> LISTENERS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Runnable, TaskBinding> AGENT_TASKS =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, ActiveTask> ACTIVE_TASKS =
            new ConcurrentHashMap<>();

    private static volatile ReloadProbe latest;
    private static volatile boolean active;
    /**
     * Lightweight lifecycle signal consumed by the browser frame pump.  The
     * completion callback may run on any reload executor, so it only publishes
     * an integer and timestamp; it never calls the host bridge or triggers GC.
     */
    private static volatile int reloadCompletion;
    private static volatile long reloadCompletionMillis;

    private BrowserReloadDiagnostics() {
    }

    /** Called at the first instruction of {@code SimpleReloadInstance.prepareTasks}. */
    public static void begin(
            SimpleReloadInstance<?> instance,
            List<PreparableReloadListener> listeners,
            CompletableFuture<?> initialFuture,
            Executor preparationExecutor,
            Executor mainExecutor
    ) {
        if (!McWebRuntimeMode.usesBackgroundAgents() || diagnosticsRequested() == 0) {
            return;
        }
        active = true;
        try {
            ReloadProbe probe = new ReloadProbe(
                    NEXT_RELOAD.incrementAndGet(),
                    System.currentTimeMillis(),
                    instance,
                    initialFuture,
                    listeners,
                    preparationExecutor,
                    mainExecutor
            );
            RELOADS.put(instance, probe);
            latest = probe;
            for (ListenerProbe listener : probe.listeners) {
                LISTENERS.put(listener.listener, listener);
            }
            emit(probe, "begin listeners=" + probe.listeners.size()
                    + " initial=" + futureState(initialFuture)
                    + " prepareExecutor=" + className(preparationExecutor)
                    + " mainExecutor=" + className(mainExecutor));
            initialFuture.whenComplete((ignored, failure) -> emit(
                    probe,
                    "initial-future " + completion(failure)
            ));
            for (ListenerProbe listener : probe.listeners) {
                emit(probe, "listener-register " + listener.label);
            }
        } catch (Throwable failure) {
            emitBare("begin-probe-failed " + describeFailure(failure));
        }
    }

    /** Whether the expensive per-task attribution probe is enabled for this run. */
    public static boolean isActive() {
        return active;
    }

    /** Returns and clears the most recent all-done result (1 = success, -1 = failure). */
    public static int consumeReloadCompletion() {
        int result = reloadCompletion;
        if (result != 0) {
            reloadCompletion = 0;
        }
        return result;
    }

    public static long reloadCompletionMillis() {
        return reloadCompletionMillis;
    }

    /** Records completion of the synchronous shared-state pass preceding listener reloads. */
    public static void sharedStatePrepared(SimpleReloadInstance<?> instance) {
        ReloadProbe probe = RELOADS.get(instance);
        if (probe == null) {
            return;
        }
        probe.sharedStatePrepared = true;
        emit(probe, "shared-state-prepared listeners=" + probe.listeners.size());
    }

    /** Marks entry into the protected {@code StateFactory.create} bridge. */
    public static void listenerCreateStarted(
            SimpleReloadInstance<?> instance,
            PreparableReloadListener listener
    ) {
        ReloadProbe probe = RELOADS.get(instance);
        ListenerProbe listenerProbe = LISTENERS.get(listener);
        if (probe == null || listenerProbe == null) {
            return;
        }
        listenerProbe.createStartedMillis = System.currentTimeMillis();
        emit(probe, "listener-create " + listenerProbe.label);
    }

    /** Returns a listener-attributing executor only for the threaded probe run. */
    public static Executor listenerExecutor(
            PreparableReloadListener listener,
            String lane,
            Executor executor,
            boolean agentBacked
    ) {
        ListenerProbe listenerProbe = LISTENERS.get(listener);
        return listenerProbe == null
                ? executor
                : new ListenerExecutor(listenerProbe, lane, executor, agentBacked);
    }

    /** Tracks the full future returned by the protected state factory. */
    public static <S> CompletableFuture<S> trackListenerFuture(
            SimpleReloadInstance<?> instance,
            PreparableReloadListener listener,
            CompletableFuture<S> future
    ) {
        ReloadProbe probe = RELOADS.get(instance);
        ListenerProbe listenerProbe = LISTENERS.get(listener);
        if (probe == null || listenerProbe == null) {
            return future;
        }
        listenerProbe.fullFuture = future;
        future.whenComplete((ignored, failure) -> {
            listenerProbe.fullDoneMillis = System.currentTimeMillis();
            listenerProbe.fullFailure = failure;
            emit(probe, "listener-complete " + listenerProbe.label + ' '
                    + completion(failure) + ' ' + listenerProbe.taskSummary());
        });
        emit(probe, "listener-created " + listenerProbe.label
                + " future=" + futureState(future));
        return future;
    }

    /** Records a synchronous throw from the protected state factory. */
    public static void listenerCreateFailed(
            SimpleReloadInstance<?> instance,
            PreparableReloadListener listener,
            Throwable failure
    ) {
        ReloadProbe probe = RELOADS.get(instance);
        ListenerProbe listenerProbe = LISTENERS.get(listener);
        if (probe == null || listenerProbe == null) {
            return;
        }
        listenerProbe.fullDoneMillis = System.currentTimeMillis();
        listenerProbe.fullFailure = failure;
        emit(probe, "listener-create-failed " + listenerProbe.label + ' '
                + describeFailure(failure));
    }

    /** Called when a listener reaches {@code PreparationBarrier.wait}. */
    public static void barrierWait(
            PreparableReloadListener listener,
            CompletableFuture<?> previousBarrier,
            CompletableFuture<?> allPreparations
    ) {
        ListenerProbe probe = LISTENERS.get(listener);
        if (probe == null) {
            return;
        }
        probe.barrierWaitMillis = System.currentTimeMillis();
        probe.previousFuture = previousBarrier;
        probe.allPreparationsFuture = allPreparations;
        emit(probe.reload, "barrier-wait " + probe.label
                + " previous=" + futureState(previousBarrier)
                + " allPreparations=" + futureState(allPreparations)
                + ' ' + probe.taskSummary());
    }

    /**
     * Exact replacement for the main-executor submission in the barrier. The wrapped
     * runnable is one per listener, not one per resource task.
     */
    public static void submitBarrier(
            Executor executor,
            Runnable command,
            PreparableReloadListener listener,
            CompletableFuture<?> previousBarrier,
            CompletableFuture<?> allPreparations
    ) {
        ListenerProbe probe = LISTENERS.get(listener);
        if (probe == null) {
            executor.execute(command);
            return;
        }
        probe.barrierSubmittedMillis = System.currentTimeMillis();
        emit(probe.reload, "barrier-submit-main " + probe.label
                + " previous=" + futureState(previousBarrier)
                + " allPreparations=" + futureState(allPreparations)
                + " executor=" + className(executor));
        executor.execute(new BarrierRunnable(
                probe,
                command,
                previousBarrier,
                allPreparations
        ));
    }

    /** Exact replacement for the barrier's {@code thenCombine}. */
    public static <A, B, R> CompletableFuture<R> combineBarrier(
            CompletableFuture<A> allPreparations,
            CompletionStage<? extends B> previousBarrier,
            BiFunction<? super A, ? super B, ? extends R> function,
            PreparableReloadListener listener
    ) {
        CompletableFuture<R> combined = allPreparations.thenCombine(previousBarrier, function);
        ListenerProbe probe = LISTENERS.get(listener);
        if (probe == null) {
            return combined;
        }
        probe.combinedFuture = combined;
        emit(probe.reload, "barrier-combine " + probe.label
                + " allPreparations=" + futureState(allPreparations)
                + " previous=" + futureState(previousBarrier));
        combined.whenComplete((ignored, failure) -> {
            probe.combinedDoneMillis = System.currentTimeMillis();
            probe.combinedFailure = failure;
            emit(probe.reload, "barrier-ready-for-apply " + probe.label + ' '
                    + completion(failure));
        });
        return combined;
    }

    /** Tracks the fail-fast sequence future returned by {@code prepareTasks}. */
    public static <S> CompletableFuture<List<S>> trackAllDone(
            CompletableFuture<List<S>> allDone,
            SimpleReloadInstance<?> instance
    ) {
        /*
         * This hook is also the production lifecycle signal.  Keep the expensive
         * listener/task graph below opt-in, but always publish completion so the
         * frame pump can collect while the loading overlay is still visible.
         */
        allDone.whenComplete((ignored, failure) -> {
            reloadCompletionMillis = System.currentTimeMillis();
            reloadCompletion = failure == null ? 1 : -1;
            if (failure != null) {
                // Always, not only under the opt-in probe. "reload failed" with
                // the cause discarded is unactionable, and a reload that fails
                // after boot -- applying a server's resource pack, say -- gets
                // the player disconnected with no way to find out why.
                Throwable cause = failure;
                while (cause.getCause() != null
                        && (cause instanceof java.util.concurrent.CompletionException
                            || cause instanceof java.util.concurrent.ExecutionException)) {
                    cause = cause.getCause();
                }
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "reload:failed " + describeFailure(cause));
                // The class and message alone name a symptom, not a site. A
                // pack reload that dies in one texture reports "Copy would
                // overrun the source buffer" with no way to tell which
                // resource, which loader, or which of this port's seams
                // produced the short buffer.
                reportFailureFrames(cause);
            }
        });
        ReloadProbe probe = RELOADS.get(instance);
        if (probe == null) {
            return allDone;
        }
        probe.allDoneFuture = allDone;
        emit(probe, "all-done-created state=" + futureState(allDone));
        allDone.whenComplete((ignored, failure) -> {
            probe.allDoneMillis = System.currentTimeMillis();
            probe.allDoneFailure = failure;
            emit(probe, "all-done " + completion(failure));
            snapshot(probe, true);
        });
        return allDone;
    }
    /**
     * Exact replacement for TextureManager's five initial supplyAsync calls. The
     * listener probe found one never returns; this names the slot, resource and
     * concrete texture on both sides of that call.
     */
    public static <T> CompletableFuture<T> supplyTexture(
            Supplier<T> supplier,
            Executor executor,
            Identifier slot,
            ReloadableTexture texture
    ) {
        ReloadProbe probe = latest;
        if (probe == null) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }
        String description = "slot=" + slot
                + " resource=" + texture.resourceId()
                + " texture=" + texture.getClass().getName();
        return CompletableFuture.supplyAsync(() -> {
            long startedMillis = System.currentTimeMillis();
            emit(probe, "texture-load-start " + description);
            try {
                T value = supplier.get();
                emit(probe, "texture-load-done " + description
                        + " durationMs=" + (System.currentTimeMillis() - startedMillis));
                return value;
            } catch (RuntimeException | Error failure) {
                emit(probe, "texture-load-failed " + description + ' '
                        + describeFailure(failure));
                throw failure;
            }
        }, executor);
    }
    public static TextureContents cubeFaceLoad(
            ResourceManager resourceManager,
            Identifier face
    ) throws IOException {
        ReloadProbe probe = latest;
        if (probe == null) {
            return TextureContents.load(resourceManager, face);
        }
        emit(probe, "cube-face-start face=" + face);
        try {
            TextureContents contents = TextureContents.load(resourceManager, face);
            emit(probe, "cube-face-done face=" + face
                    + " size=" + contents.image().getWidth() + 'x' + contents.image().getHeight());
            return contents;
        } catch (IOException | RuntimeException | Error failure) {
            emit(probe, "cube-face-failed face=" + face + ' ' + describeFailure(failure));
            throw failure;
        }
    }

    public static NativeImage cubeImage(int width, int height, boolean clear) {
        ReloadProbe probe = latest;
        if (probe == null) {
            return new NativeImage(width, height, clear);
        }
        emit(probe, "cube-image-start size=" + width + 'x' + height + " clear=" + clear);
        try {
            NativeImage image = new NativeImage(width, height, clear);
            emit(probe, "cube-image-done size=" + width + 'x' + height);
            return image;
        } catch (RuntimeException | Error failure) {
            emit(probe, "cube-image-failed size=" + width + 'x' + height + ' '
                    + describeFailure(failure));
            throw failure;
        }
    }

    public static void cubeCopyRect(
            NativeImage source,
            NativeImage target,
            int sourceX,
            int sourceY,
            int targetX,
            int targetY,
            int width,
            int height,
            boolean flipX,
            boolean flipY
    ) {
        ReloadProbe probe = latest;
        if (probe != null) {
            emit(probe, "cube-copy-start targetY=" + targetY
                    + " size=" + width + 'x' + height);
        }
        if (flipX) {
            // CubeMapTexture does not request this path. Preserve Mojang's generic
            // behavior rather than inventing a second horizontal-flip implementation.
            source.copyRect(
                    target, sourceX, sourceY, targetX, targetY, width, height, true, flipY
            );
        } else {
            // Both images are RGBA. Copying a row at a time preserves copyRect's
            // vertical flip while avoiding two synthetic-memory resolutions and two
            // Java allocations per pixel in BrowserNativeMemory.block().
            long rowBytes = (long) width * 4L;
            for (int sourceRow = 0; sourceRow < height; sourceRow++) {
                int targetRow = flipY ? height - 1 - sourceRow : sourceRow;
                long sourceAddress = source.getPointer()
                        + ((long) (sourceY + sourceRow) * source.getWidth() + sourceX) * 4L;
                long targetAddress = target.getPointer()
                        + ((long) (targetY + targetRow) * target.getWidth() + targetX) * 4L;
                MemoryUtil.memCopy(sourceAddress, targetAddress, rowBytes);
            }
        }
        if (probe != null) {
            emit(probe, "cube-copy-done targetY=" + targetY
                    + " size=" + width + 'x' + height);
        }
    }

    /**
     * Names the exact caller if parallel model baking observes an uninitialized layer.
     */
    public static ModelPart bakeLayer(
            EntityModelSet models,
            ModelLayerLocation layer,
            String caller
    ) {
        if (layer == null) {
            ReloadProbe probe = latest;
            if (probe != null) {
                emit(probe, "model-layer-null caller=" + caller);
            }
        }
        return models.bakeLayer(layer);
    }

    public static void cubeClose(TextureContents contents) {
        ReloadProbe probe = latest;
        if (probe != null) {
            emit(probe, "cube-close-start size="
                    + contents.image().getWidth() + 'x' + contents.image().getHeight());
        }
        contents.close();
        if (probe != null) {
            emit(probe, "cube-close-done");
        }
    }



    /**
     * Preserves listener attribution through SimpleReloadInstance's own counting
     * executor, which wraps the listener's Runnable before it reaches the agent pool.
     */
    public static void submitPreparedTask(
            Executor executor,
            Runnable wrappedCommand,
            Runnable listenerCommand
    ) {
        TaskBinding binding = AGENT_TASKS.remove(listenerCommand);
        if (binding != null) {
            AGENT_TASKS.put(wrappedCommand, binding);
        }
        try {
            executor.execute(wrappedCommand);
        } catch (RuntimeException | Error failure) {
            if (binding != null) {
                AGENT_TASKS.remove(wrappedCommand, binding);
            }
            throw failure;
        }
    }

    /** Called by the agent pool immediately before running an original task. */
    public static String agentTaskStarted(Runnable command) {
        try {
            TaskBinding binding = AGENT_TASKS.remove(command);
            if (binding == null) {
                return className(command);
            }
            ActiveTask active = startTask(binding);
            ACTIVE_TASKS.put(Thread.currentThread().getName(), active);
            return active.description();
        } catch (Throwable failure) {
            emitBare("agent-task-start-probe-failed " + describeFailure(failure));
            return className(command);
        }
    }

    /** Called by the agent pool after the original task returns or throws. */
    public static void agentTaskFinished(Runnable command, Throwable failure) {
        try {
            String thread = Thread.currentThread().getName();
            ActiveTask active = ACTIVE_TASKS.remove(thread);
            if (active != null) {
                finishTask(active, failure);
            }
        } catch (Throwable probeFailure) {
            emitBare("agent-task-finish-probe-failed " + describeFailure(probeFailure));
        }
    }

    /** Frame-pump heartbeat; emits at most one snapshot every five seconds. */
    public static void heartbeat(long frame) {
        if (!active) {
            return;
        }
        ReloadProbe probe = latest;
        if (probe == null || probe.allDoneMillis != 0L) {
            return;
        }
        probe.lastFrame = frame;
        snapshot(probe, false);
    }

    private static void snapshot(ReloadProbe probe, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - probe.lastSnapshotMillis < SNAPSHOT_MILLIS) {
            return;
        }
        probe.lastSnapshotMillis = now;
        StringBuilder pending = new StringBuilder();
        int complete = 0;
        for (ListenerProbe listener : probe.listeners) {
            String state = listener.state();
            if ("done".equals(state)) {
                complete++;
                continue;
            }
            if (pending.length() > 0) {
                pending.append(';');
            }
            pending.append(listener.shortLabel()).append('=').append(state)
                    .append('(').append(listener.taskSummary())
                    .append(",classes=").append(listener.taskClassSummary()).append(')');
        }
        StringBuilder active = new StringBuilder();
        for (ActiveTask task : ACTIVE_TASKS.values()) {
            if (task.binding.listener.reload != probe) {
                continue;
            }
            if (active.length() > 0) {
                active.append(';');
            }
            active.append(task.description())
                    .append(" ageMs=").append(now - task.startedMillis);
        }
        emit(probe, "snapshot frame=" + probe.lastFrame
                + " sharedState=" + probe.sharedStatePrepared
                + " initial=" + futureState(probe.initialFuture)
                + " allDone=" + futureState(probe.allDoneFuture)
                + " complete=" + complete + '/' + probe.listeners.size()
                + " pending=[" + pending + ']'
                + " active=[" + active + ']'
                + ' ' + AgentExecutorService.stats());
    }

    private static ActiveTask startTask(TaskBinding binding) {
        ListenerProbe listener = binding.listener;
        long now = System.currentTimeMillis();
        int started = listener.started(binding.lane).incrementAndGet();
        listener.taskClasses.computeIfAbsent(
                binding.lane + ':' + binding.taskClass,
                ignored -> new AtomicInteger()
        ).incrementAndGet();
        boolean sameThread = isMinecraftThread();
        (sameThread ? listener.sameThreadTrue : listener.sameThreadFalse).incrementAndGet();
        ActiveTask active = new ActiveTask(binding, now, sameThread);
        if (started == 1) {
            emit(listener.reload, "task-first-start " + active.description()
                    + " queueMs=" + (now - binding.submittedMillis));
        }
        return active;
    }

    private static void finishTask(ActiveTask active, Throwable failure) {
        ListenerProbe listener = active.binding.listener;
        listener.finished(active.binding.lane).incrementAndGet();
        if (failure != null) {
            listener.taskFailures.incrementAndGet();
            emit(listener.reload, "task-failed " + active.description() + ' '
                    + describeFailure(failure));
        }
        int total = listener.reload.finishedTasks.incrementAndGet();
        if (total % TASK_REPORT_INTERVAL == 0) {
            snapshot(listener.reload, true);
        }
    }

    private static final class ListenerExecutor implements Executor {
        private final ListenerProbe listener;
        private final String lane;
        private final Executor delegate;
        private final boolean agentBacked;

        private ListenerExecutor(
                ListenerProbe listener,
                String lane,
                Executor delegate,
                boolean agentBacked
        ) {
            this.listener = listener;
            this.lane = lane;
            this.delegate = delegate;
            this.agentBacked = agentBacked || isAgentBacked(delegate);
        }

        @Override
        public void execute(Runnable command) {
            Objects.requireNonNull(command, "command");
            listener.submitted(lane).incrementAndGet();
            TaskBinding binding = new TaskBinding(
                    listener,
                    lane,
                    command,
                    System.currentTimeMillis()
            );
            if (agentBacked) {
                TaskBinding replaced = AGENT_TASKS.put(command, binding);
                if (replaced != null) {
                    emit(listener.reload, "task-identity-reused " + listener.label
                            + " lane=" + lane + " class=" + binding.taskClass);
                }
                try {
                    delegate.execute(command);
                } catch (RuntimeException | Error failure) {
                    AGENT_TASKS.remove(command, binding);
                    listener.taskFailures.incrementAndGet();
                    emit(listener.reload, "task-submit-failed " + listener.label
                            + " lane=" + lane + ' ' + describeFailure(failure));
                    throw failure;
                }
                return;
            }
            delegate.execute(new TrackedRunnable(binding));
        }
    }

    private static final class TrackedRunnable implements Runnable {
        private final TaskBinding binding;

        private TrackedRunnable(TaskBinding binding) {
            this.binding = binding;
        }

        @Override
        public void run() {
            ActiveTask active = startTask(binding);
            String thread = Thread.currentThread().getName();
            ACTIVE_TASKS.put(thread, active);
            Throwable failure = null;
            try {
                binding.command.run();
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                throw thrown;
            } finally {
                ACTIVE_TASKS.remove(thread);
                finishTask(active, failure);
            }
        }
    }

    private static final class BarrierRunnable implements Runnable {
        private final ListenerProbe listener;
        private final Runnable command;
        private final CompletableFuture<?> previousBarrier;
        private final CompletableFuture<?> allPreparations;

        private BarrierRunnable(
                ListenerProbe listener,
                Runnable command,
                CompletableFuture<?> previousBarrier,
                CompletableFuture<?> allPreparations
        ) {
            this.listener = listener;
            this.command = command;
            this.previousBarrier = previousBarrier;
            this.allPreparations = allPreparations;
        }

        @Override
        public void run() {
            TaskBinding binding = new TaskBinding(
                    listener,
                    "barrier-main",
                    command,
                    listener.barrierSubmittedMillis
            );
            ActiveTask active = startTask(binding);
            String thread = Thread.currentThread().getName();
            ACTIVE_TASKS.put(thread, active);
            Throwable failure = null;
            try {
                command.run();
                listener.barrierExecutedMillis = System.currentTimeMillis();
                emit(listener.reload, "barrier-main-ran " + listener.label
                        + " previous=" + futureState(previousBarrier)
                        + " allPreparations=" + futureState(allPreparations));
            } catch (RuntimeException | Error thrown) {
                failure = thrown;
                emit(listener.reload, "barrier-main-failed " + listener.label + ' '
                        + describeFailure(thrown));
                throw thrown;
            } finally {
                ACTIVE_TASKS.remove(thread);
                finishTask(active, failure);
            }
        }
    }

    private static boolean isAgentBacked(Executor executor) {
        if (executor instanceof AgentExecutorService) {
            return true;
        }
        return executor instanceof TracingExecutor tracing
                && tracing.service() instanceof AgentExecutorService;
    }

    private static boolean isMinecraftThread() {
        try {
            Minecraft instance = Minecraft.getInstance();
            return instance != null && instance.isSameThread();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String futureState(CompletionStage<?> stage) {
        if (stage == null) {
            return "unset";
        }
        if (!(stage instanceof CompletableFuture<?> future)) {
            return "stage";
        }
        if (!future.isDone()) {
            return "pending/dependents=" + future.getNumberOfDependents();
        }
        return future.isCompletedExceptionally() ? "failed" : "done";
    }

    private static String completion(Throwable failure) {
        return failure == null ? "done" : "failed=" + describeFailure(failure);
    }

    /**
     * Top frames of a reload failure, plus the causal chain, emitted as their
     * own markers so the ring keeps them at a readable length.
     */
    private static void reportFailureFrames(Throwable failure) {
        try {
            Throwable current = failure;
            for (int depth = 0; current != null && depth < 4; depth++) {
                StackTraceElement[] frames = current.getStackTrace();
                StringBuilder line = new StringBuilder("reload:failed-at[")
                        .append(depth).append("] ")
                        .append(current.getClass().getSimpleName()).append(' ');
                for (int i = 0; i < frames.length && i < 12; i++) {
                    line.append(frames[i].getClassName()).append('.')
                            .append(frames[i].getMethodName()).append(" < ");
                }
                // Not clean(): that caps at 160 chars, which is under two
                // frames of Minecraft package names.
                String text = line.toString().replace('\n', ' ').replace('\r', ' ');
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        text.length() <= 600 ? text : text.substring(0, 600));
                current = current.getCause() == current ? null : current.getCause();
            }
        } catch (Throwable ignored) {
            // A diagnostic must never replace the failure it is describing.
        }
    }

    private static String describeFailure(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getName()
                + (message == null || message.isBlank() ? "" : ':' + clean(message));
    }

    private static String className(Object value) {
        return value == null ? "<null>" : value.getClass().getName();
    }

    private static String clean(String value) {
        String cleaned = value.replace('\n', ' ').replace('\r', ' ').replace(';', ',');
        return cleaned.length() <= 160 ? cleaned : cleaned.substring(0, 160);
    }

    private static void emit(ReloadProbe probe, String event) {
        emitBare("reload=" + probe.id
                + " elapsedMs=" + (System.currentTimeMillis() - probe.startedMillis)
                + " event=" + event);
    }

    private static void emitBare(String event) {
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportReloadProbe(
                    "seq=" + NEXT_EVENT.incrementAndGet()
                            + " thread=" + clean(Thread.currentThread().getName())
                            + " sameThread=" + isMinecraftThread()
                            + ' ' + event
            );
        } catch (Throwable ignored) {
            // Diagnostics must never replace Minecraft's task or future result.
        }
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(
            value = "return new URLSearchParams(globalThis.location?.search || '')"
                    + ".has('mcweb_reload_diag') ? 1 : 0;",
            args = {}
    )
    private static native int diagnosticsRequested();

    private static final class ReloadProbe {
        private final int id;
        private final long startedMillis;
        private final SimpleReloadInstance<?> instance;
        private final CompletableFuture<?> initialFuture;
        private final List<ListenerProbe> listeners;
        private final AtomicInteger finishedTasks = new AtomicInteger();
        private volatile CompletableFuture<?> allDoneFuture;
        private volatile Throwable allDoneFailure;
        private volatile long allDoneMillis;
        private volatile long lastSnapshotMillis;
        private volatile long lastFrame;
        private volatile boolean sharedStatePrepared;

        private ReloadProbe(
                int id,
                long startedMillis,
                SimpleReloadInstance<?> instance,
                CompletableFuture<?> initialFuture,
                List<PreparableReloadListener> listeners,
                Executor preparationExecutor,
                Executor mainExecutor
        ) {
            this.id = id;
            this.startedMillis = startedMillis;
            this.instance = instance;
            this.initialFuture = initialFuture;
            this.listeners = java.util.stream.IntStream.range(0, listeners.size())
                    .mapToObj(index -> new ListenerProbe(
                            this,
                            index,
                            listeners.get(index),
                            preparationExecutor,
                            mainExecutor
                    ))
                    .toList();
        }
    }

    private static final class ListenerProbe {
        private final ReloadProbe reload;
        private final int index;
        private final PreparableReloadListener listener;
        private final String label;
        private final AtomicInteger prepareSubmitted = new AtomicInteger();
        private final AtomicInteger prepareStarted = new AtomicInteger();
        private final AtomicInteger prepareFinished = new AtomicInteger();
        private final AtomicInteger applySubmitted = new AtomicInteger();
        private final AtomicInteger applyStarted = new AtomicInteger();
        private final AtomicInteger applyFinished = new AtomicInteger();
        private final AtomicInteger barrierSubmitted = new AtomicInteger();
        private final AtomicInteger barrierStarted = new AtomicInteger();
        private final AtomicInteger barrierFinished = new AtomicInteger();
        private final AtomicInteger sameThreadTrue = new AtomicInteger();
        private final AtomicInteger sameThreadFalse = new AtomicInteger();
        private final AtomicInteger taskFailures = new AtomicInteger();
        private final ConcurrentHashMap<String, AtomicInteger> taskClasses =
                new ConcurrentHashMap<>();
        private volatile CompletableFuture<?> previousFuture;
        private volatile CompletableFuture<?> allPreparationsFuture;
        private volatile CompletableFuture<?> combinedFuture;
        private volatile CompletableFuture<?> fullFuture;
        private volatile Throwable combinedFailure;
        private volatile Throwable fullFailure;
        private volatile long createStartedMillis;
        private volatile long barrierWaitMillis;
        private volatile long barrierSubmittedMillis;
        private volatile long barrierExecutedMillis;
        private volatile long combinedDoneMillis;
        private volatile long fullDoneMillis;

        private ListenerProbe(
                ReloadProbe reload,
                int index,
                PreparableReloadListener listener,
                Executor preparationExecutor,
                Executor mainExecutor
        ) {
            this.reload = reload;
            this.index = index;
            this.listener = listener;
            this.label = String.format("%02d:%s[%s]", index, listenerName(listener),
                    shortClass(preparationExecutor) + "->" + shortClass(mainExecutor));
        }

        private AtomicInteger submitted(String lane) {
            return switch (lane) {
                case "prepare" -> prepareSubmitted;
                case "apply" -> applySubmitted;
                default -> barrierSubmitted;
            };
        }

        private AtomicInteger started(String lane) {
            return switch (lane) {
                case "prepare" -> prepareStarted;
                case "apply" -> applyStarted;
                default -> barrierStarted;
            };
        }

        private AtomicInteger finished(String lane) {
            return switch (lane) {
                case "prepare" -> prepareFinished;
                case "apply" -> applyFinished;
                default -> barrierFinished;
            };
        }

        private String state() {
            if (fullDoneMillis != 0L) {
                return fullFailure == null ? "done" : "failed";
            }
            if (barrierWaitMillis == 0L) {
                return prepareStarted.get() == 0 ? "prepare-not-started" : "preparing";
            }
            if (barrierExecutedMillis == 0L) {
                return "barrier-main-queued";
            }
            if (combinedDoneMillis == 0L) {
                if (allPreparationsFuture != null && !allPreparationsFuture.isDone()) {
                    return "waiting-all-preparations";
                }
                if (previousFuture != null && !previousFuture.isDone()) {
                    return "waiting-previous-listener";
                }
                return "barrier-combine-pending";
            }
            if (combinedFailure != null) {
                return "barrier-failed";
            }
            return applyStarted.get() == 0 ? "apply-not-started" : "applying";
        }

        private String taskSummary() {
            return "p=" + prepareSubmitted.get() + '/' + prepareStarted.get() + '/'
                    + prepareFinished.get()
                    + ",a=" + applySubmitted.get() + '/' + applyStarted.get() + '/'
                    + applyFinished.get()
                    + ",b=" + barrierSubmitted.get() + '/' + barrierStarted.get() + '/'
                    + barrierFinished.get()
                    + ",same=" + sameThreadTrue.get() + '/' + sameThreadFalse.get()
                    + ",fail=" + taskFailures.get();
        }

        private String taskClassSummary() {
            StringBuilder result = new StringBuilder();
            int shown = 0;
            for (var entry : taskClasses.entrySet()) {
                if (shown++ == 8) {
                    result.append("+more");
                    break;
                }
                if (result.length() > 0) {
                    result.append('|');
                }
                String key = entry.getKey();
                int laneSeparator = key.indexOf(':');
                String lane = laneSeparator < 0 ? "" : key.substring(0, laneSeparator + 1);
                String taskClass = laneSeparator < 0 ? key : key.substring(laneSeparator + 1);
                int classSeparator = Math.max(
                        taskClass.lastIndexOf('.'),
                        taskClass.lastIndexOf('$')
                );
                result.append(lane)
                        .append(classSeparator < 0
                                ? taskClass
                                : taskClass.substring(classSeparator + 1))
                        .append('x').append(entry.getValue().get());
            }
            return result.toString();
        }

        private String shortLabel() {
            int bracket = label.indexOf('[');
            return bracket < 0 ? label : label.substring(0, bracket);
        }
    }

    private record TaskBinding(
            ListenerProbe listener,
            String lane,
            Runnable command,
            long submittedMillis,
            String taskClass
    ) {
        private TaskBinding(
                ListenerProbe listener,
                String lane,
                Runnable command,
                long submittedMillis
        ) {
            this(listener, lane, command, submittedMillis, className(command));
        }
    }

    private record ActiveTask(TaskBinding binding, long startedMillis, boolean sameThread) {
        private String description() {
            return binding.listener.shortLabel()
                    + " lane=" + binding.lane
                    + " task=" + binding.taskClass
                    + " same=" + sameThread;
        }
    }

    private static String listenerName(PreparableReloadListener listener) {
        try {
            String name = listener.getName();
            if (name != null && !name.isBlank()) {
                return clean(name) + ':' + shortClass(listener);
            }
        } catch (Throwable ignored) {
            // Fall through to the concrete class, which is always available.
        }
        return shortClass(listener);
    }

    private static String shortClass(Object value) {
        if (value == null) {
            return "null";
        }
        String name = value.getClass().getName();
        int separator = Math.max(name.lastIndexOf('.'), name.lastIndexOf('$'));
        return separator < 0 ? name : name.substring(separator + 1);
    }
}
