package com.mojang.blaze3d.platform;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Browser replacement for the small GLFW bridge used by RenderSystem. */
public class GLX {
    public GLX() {
    }

    public static int _getRefreshRate(Window window) {
        return 60;
    }

    public static String _getLWJGLVersion() {
        return "browser WebGPU";
    }

    public static LongSupplier _initGlfw() {
        return System::nanoTime;
    }

    public static int getGlfwPlatform() {
        return -1;
    }

    /**
     * Desktop takes {@code GLFWErrorCallbackI}. That interface's clinit pulls
     * LWJGL libffi natives, which Web Image cannot link. Accept Object so the
     * callback type is never loaded on the browser path.
     */
    public static void _setGlfwErrorCallback(Object callback) {
    }

    public static boolean _shouldClose(Window window) {
        return window.shouldClose();
    }

    public static String _getCpuInfo() {
        return "WebAssembly-GC";
    }

    public static <T> T make(Supplier<T> supplier) {
        return supplier.get();
    }

    public static int glfwBool(boolean value) {
        return value ? 1 : 0;
    }
}
