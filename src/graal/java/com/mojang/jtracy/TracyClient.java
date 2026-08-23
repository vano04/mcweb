package com.mojang.jtracy;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

/** Browser substitution: the native Tracy profiler is unavailable in WebAssembly. */
public class TracyClient {
    public TracyClient() {
    }

    public static boolean isAvailable() {
        return false;
    }

    public static synchronized void load() throws UnsatisfiedLinkError {
    }

    public static void markFrame() {
    }

    public static void frameImage(
            ByteBuffer image,
            int width,
            int height,
            int offset,
            boolean flip
    ) {
    }

    public static Zone beginZone(String name, boolean includeStackFrames) {
        return Zone.UNAVAILABLE;
    }

    public static Zone beginZone(String name, String function, String file, int line) {
        return Zone.UNAVAILABLE;
    }

    public static void setThreadName(String name, int groupHint) {
    }

    public static Plot createPlot(String name) {
        return Plot.UNAVAILABLE;
    }

    public static DiscontinuousFrame createDiscontinuousFrame(String name) {
        return DiscontinuousFrame.UNAVAILABLE;
    }

    public static ContinuousFrame createContinuousFrame(String name) {
        return ContinuousFrame.UNAVAILABLE;
    }

    public static MemoryPool createMemoryPool(String name) {
        return MemoryPool.UNAVAILABLE;
    }

    public static void reportAppInfo(String value) {
    }

    public static void message(String value) {
    }

    public static void message(String value, int color) {
    }

    public static void message(Supplier<String> value) {
    }

    public static void message(Supplier<String> value, int color) {
    }

    public static GpuContext createGpuContext(GpuApi api, long handle, float period) {
        return GpuContext.UNAVAILABLE;
    }
}
