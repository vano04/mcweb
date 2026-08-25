package dev.mcweb.graal;

import org.lwjgl.glfw.GLFWFramebufferSizeCallback;
import org.lwjgl.glfw.GLFWFramebufferSizeCallbackI;
import org.lwjgl.glfw.GLFWWindowSizeCallback;
import org.lwjgl.glfw.GLFWWindowSizeCallbackI;

/**
 * Delivers browser resizes to Minecraft.
 *
 * <p>Resizing used to stretch the picture rather than re-lay-out: the canvas
 * grew, but Minecraft kept rendering at the size it read during boot, and the
 * host composite scaled that stale frame to fill the new canvas.</p>
 *
 * <p>The reason is that {@code com.mojang.blaze3d.platform.Window} is itself a
 * classpath-first shadow in this port. The JAR's {@code Window} — the class that
 * registers GLFW framebuffer/window-size callbacks — never executes, so no
 * callback exists to fire and the shadow's dimensions were fixed at
 * construction. Retargeting those registrations in the JAR class therefore had
 * no effect; the size has to be pushed into the shadow instead, which
 * {@link #dispatchResize} does via {@code Window.resize}.</p>
 *
 * <p>The GLFW callback fields below are kept because they cost nothing and make
 * this correct either way: if the {@code Window} shadow is ever dropped in
 * favour of the JAR's class, the registrations will populate them and the same
 * dispatch will drive Mojang's own path.</p>
 */
public final class BrowserWindowCompat {
    private static volatile long windowHandle;
    private static volatile GLFWFramebufferSizeCallbackI framebufferSize;
    private static volatile GLFWWindowSizeCallbackI windowSize;
    private static volatile int lastWidth;
    private static volatile int lastHeight;

    private BrowserWindowCompat() {
    }

    public static void setFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI callback) {
        windowHandle = window;
        framebufferSize = callback;
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                "window:framebuffer-callback-registered handle=" + window
        );
    }

    public static void setWindowSizeCallback(long window, GLFWWindowSizeCallbackI callback) {
        windowHandle = window;
        windowSize = callback;
    }

    // ---- ASM retarget targets ---------------------------------------------
    // Same names and descriptors as GLFW so the browserMinecraftJar transform
    // only has to rewrite a call's owner. These are currently reached by
    // nothing: the only caller would be the JAR's Window, which the shadow
    // replaces. They are kept as the landing point should that shadow go away.

    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(
            long window, GLFWFramebufferSizeCallbackI cbfun
    ) {
        setFramebufferSizeCallback(window, cbfun);
        return null;
    }

    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(
            long window, GLFWWindowSizeCallbackI cbfun
    ) {
        setWindowSizeCallback(window, cbfun);
        return null;
    }

    /** blaze3d's own handle if the window exists yet, else the captured one. */
    private static long resolveWindowHandle() {
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.getWindow() != null) {
                return client.getWindow().handle();
            }
        } catch (Throwable ignored) {
            // Before Minecraft exists the captured handle is all there is.
        }
        return windowHandle;
    }

    /**
     * Reports a new canvas backing-store size. The host has already resized the
     * canvas and reconfigured the WebGPU surface; this is what makes Minecraft
     * re-derive GUI scale and rebuild its render targets.
     */
    public static void dispatchResize(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == lastWidth && height == lastHeight) {
            return;
        }
        lastWidth = width;
        lastHeight = height;

        // Drive the Window shadow directly. The retained GLFW callbacks below
        // are never populated in this port: com.mojang.blaze3d.platform.Window
        // is itself a classpath-first shadow, so the JAR's Window -- the class
        // that registers framebuffer/window-size callbacks -- never runs. Five
        // build cycles were spent retargeting those registrations in a class
        // that is not on the executed path; the size lives in the shadow and
        // has to be pushed there.
        String observed = "unavailable";
        try {
            net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
            if (client != null && client.getWindow() != null) {
                com.mojang.blaze3d.platform.Window window = client.getWindow();
                window.resize(width, height);
                observed = window.getWidth() + "x" + window.getHeight()
                        + " gui=" + window.getGuiScaledWidth() + "x" + window.getGuiScaledHeight();
            }
        } catch (Throwable failure) {
            observed = "failed:" + failure.getClass().getName();
        }

        // Still invoke any real GLFW callbacks, so this keeps working if the
        // Window shadow is ever dropped in favour of the JAR's class.
        GLFWWindowSizeCallbackI window = windowSize;
        if (window != null) {
            window.invoke(windowHandle, width, height);
        }
        GLFWFramebufferSizeCallbackI framebuffer = framebufferSize;
        if (framebuffer != null) {
            framebuffer.invoke(windowHandle, width, height);
        }

        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                "window:resize:" + width + "x" + height + " -> window=" + observed
        );
    }
}
