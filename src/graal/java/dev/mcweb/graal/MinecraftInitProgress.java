package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import org.lwjgl.system.BrowserNativeMemory;

public final class MinecraftInitProgress {
    public static volatile String lastStage = "Minecraft(GameConfig)";

    private MinecraftInitProgress() {
    }

    public static void trace(String stage) {
        lastStage = stage;
        String context = "";
        if (stage.startsWith("world-create:")) {
            Thread current = Thread.currentThread();
            context = " thread=" + current.getName()
                    + "/" + current.getId()
                    + " queue=" + AgentExecutorService.queueState();
        }
        String line = stage + context + " [" + heapStats() + "]";
        System.out.println("[MC-INIT] " + line);
        try {
            BrowserGpu.reportProgress(line);
        } catch (RuntimeException reportFailure) {
            // Diagnostics must never change Minecraft startup behavior.
        }
    }

    /**
     * Boundary marker for a path where the allocator itself is under test.
     * Do not build heap statistics or a Java diagnostic context here: the marker
     * must not allocate between a carrier-preparation call and the next Mojang
     * call whose owner it is meant to identify.
     */
    public static void traceLight(String stage) {
        lastStage = stage;
        try {
            BrowserGpu.reportProgress(stage);
        } catch (RuntimeException reportFailure) {
            // Diagnostics must never change Minecraft startup behavior.
        }
    }

    /**
     * Current heap and synthetic-native usage, appended to every trace line so
     * a failing run shows where the heap filled. Web Image may not implement
     * every Runtime query; degrade to placeholders instead of throwing.
     */
    public static String heapStats() {
        String heap;
        try {
            Runtime runtime = Runtime.getRuntime();
            long total = runtime.totalMemory();
            long free = runtime.freeMemory();
            long max = runtime.maxMemory();
            heap = "heap=" + mib(total - free) + "/" + mib(total) + "MiB max=" + mib(max) + "MiB";
        } catch (Throwable statsFailure) {
            heap = "heap=?";
        }
        String nativeStats;
        try {
            nativeStats = BrowserNativeMemory.stats();
        } catch (Throwable statsFailure) {
            nativeStats = "native=?";
        }
        return heap + " " + nativeStats;
    }

    private static String mib(long bytes) {
        return String.valueOf(bytes / (1024L * 1024L));
    }
}
