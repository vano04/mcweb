package dev.mcweb.graal;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.TracingExecutor;

/** Browser-safe equivalent for Minecraft's first startup future. */
public final class BrowserAsyncCompat {

    private static volatile TracingExecutor inlineExecutor;
    private static volatile TracingExecutor backgroundExecutor;
    private static volatile TracingExecutor serverBackgroundExecutor;
    private static volatile java.util.concurrent.ExecutorService serverBackgroundDelegate;
    private static volatile TracingExecutor ioExecutor;
    private static volatile TracingExecutor downloadExecutor;

    private BrowserAsyncCompat() {
    }

    public static <T> CompletableFuture<T> supplyAsync(
            Supplier<T> supplier,
            Executor executor
    ) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(supplier);

        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (Throwable failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Replacement body for {@code Util.makeExecutor}/{@code Util.makeIoExecutor}
     * (injected by the jar transform). Constructing a ForkJoinPool here is fatal in
     * Web Image: its workers call {@code Unsafe.park}/{@code unpark}, which the WasmGC
     * backend substitutes to throw.
     *
     * <p>{@link BrowserExecutorService#pool()} routes work to the inline executor,
     * which runs each task on the browser thread and avoids unsupported desktop
     * worker primitives in Web Image.
     */
    public static TracingExecutor inlineTracingExecutor() {
        TracingExecutor current = inlineExecutor;
        if (current != null) {
            return current;
        }
        synchronized (BrowserAsyncCompat.class) {
            current = inlineExecutor;
            if (current == null) {
                current = new TracingExecutor(InlineExecutorService.INSTANCE);
                inlineExecutor = current;
            }
            return current;
        }
    }

    /** Replacement body for Util.makeExecutor("Main"). */
    public static TracingExecutor makeExecutor(String name) {
        return backgroundTracingExecutor();
    }

    /** Replacement body for Util.makeIoExecutor("IO-Worker-", daemon). */
    public static TracingExecutor makeIoExecutor(String name, boolean daemon) {
        return name != null && name.startsWith("Download")
                ? downloadTracingExecutor()
                : ioTracingExecutor();
    }

    /**
     * Replacement for {@code Util.backgroundExecutor()}.
     *
     * <p>Vanilla exposes one shared Worker-Main pool to client reload/meshing and to
     * server worldgen/light work. Keep that contract. The dedicated Server thread
     * continues to own ticks and server state; only work Mojang explicitly submits to
     * this executor leaves it.
     */
    public static TracingExecutor backgroundTracingExecutor() {
        TracingExecutor current = backgroundExecutor;
        if (current != null) {
            return current;
        }
        synchronized (BrowserAsyncCompat.class) {
            current = backgroundExecutor;
            if (current == null) {
                current = new TracingExecutor(BrowserExecutorService.backgroundPool());
                backgroundExecutor = current;
            }
            return current;
        }
    }

    /**
     * The *server's* view of {@code Util.backgroundExecutor()}.
     *
     * <p>Vanilla shares one pool between client reload/meshing and server
     * worldgen/light, and that works there because it is a ForkJoinPool with
     * {@code availableProcessors()-1} threads: a worker that blocks on a dependency has
     * many others to steal from.
     *
     * <p>Chunk generation is a deep dependency graph — `biomes` needs
     * `STRUCTURE_STARTS` at radius 8, and `ChunkGenerationTask` advances a chunk layer
     * by layer over the chunk *and every required neighbour*
     * (docs/Minecraft/world/chunks.md). A pool resolves that breadth-first and a worker
     * blocked on a dependency cannot run it; inline execution resolves it depth-first on
     * the caller. Measured: 1 pooled worker stalls at 5 client chunks, inline reaches
     * 81+ and climbs. docs/Minecraft/world/chunks.md sanctions the inline fallback
     * explicitly — "a port can serialize on one worker as long as future-completion
     * order per chunk is preserved".
     *
     * <p>The browser image intentionally serializes this dependency graph on the
     * inline executor, preserving completion order without desktop worker threads.
     */
    public static TracingExecutor serverBackgroundTracingExecutor() {
        /* Keep the wrapper stable after the first call. */
        java.util.concurrent.ExecutorService pool = BrowserExecutorService.serverWorldgenPool();
        TracingExecutor current = serverBackgroundExecutor;
        if (current != null && serverBackgroundDelegate == pool) {
            return current;
        }
        synchronized (BrowserAsyncCompat.class) {
            if (serverBackgroundExecutor == null || serverBackgroundDelegate != pool) {
                serverBackgroundDelegate = pool;
                serverBackgroundExecutor = new TracingExecutor(pool);
            }
            return serverBackgroundExecutor;
        }
    }

    /** Replacement for {@code Util.ioPool()}, with independent bounded capacity. */
    public static TracingExecutor ioTracingExecutor() {
        TracingExecutor current = ioExecutor;
        if (current != null) {
            return current;
        }
        synchronized (BrowserAsyncCompat.class) {
            current = ioExecutor;
            if (current == null) {
                current = new TracingExecutor(BrowserExecutorService.ioPool());
                ioExecutor = current;
            }
            return current;
        }
    }

    /** Replacement for {@code Util.nonCriticalIoPool()}. */
    public static TracingExecutor downloadTracingExecutor() {
        TracingExecutor current = downloadExecutor;
        if (current != null) {
            return current;
        }
        synchronized (BrowserAsyncCompat.class) {
            current = downloadExecutor;
            if (current == null) {
                // Downloads are deliberately isolated from worldgen/region IO. They
                // remain inline until a spare carrier is explicitly budgeted.
                current = new TracingExecutor(InlineExecutorService.INSTANCE);
                downloadExecutor = current;
            }
            return current;
        }
    }
}
