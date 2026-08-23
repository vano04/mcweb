package com.mojang.blaze3d.platform;

/** Browser substitution: the canvas has no GLFW monitor enumeration. */
public class MonitorManager implements AutoCloseable {
    public MonitorManager() {
    }

    public Monitor getMonitor(long handle) {
        return null;
    }

    public Monitor findBestMonitor(Window window) {
        return null;
    }

    public static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    @Override
    public void close() {
    }
}
