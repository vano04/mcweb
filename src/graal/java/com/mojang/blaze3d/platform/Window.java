package com.mojang.blaze3d.platform;

import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.minecraft.server.packs.PackResources;
import com.mojang.blaze3d.platform.cursor.CursorType;

/** Canvas-backed ABI replacement for Minecraft's final GLFW Window class. */
public final class Window implements AutoCloseable {
    public static final int BASE_WIDTH = 320;
    public static final int BASE_HEIGHT = 240;

    public static class WindowInitFailed
            extends net.minecraft.client.main.SilentInitException {
        public WindowInitFailed(String message) {
            super(message);
        }
    }

    private final WindowEventHandler eventHandler;
    private final GpuBackend backend;
    private final long handle = 1L;
    private int width;
    private int height;
    private int guiScaledWidth;
    private int guiScaledHeight;
    private int guiScale = 1;
    private int x;
    private int y;
    private boolean fullscreen;
    private boolean allowCursorChanges = true;
    private Optional<VideoMode> preferredFullscreenVideoMode = Optional.empty();
    private Runnable closeCallback = () -> {
    };

    public Window(
            WindowEventHandler eventHandler,
            DisplayData displayData,
            String title,
            boolean exclusiveFullscreen,
            String preferredFullscreenMode,
            MonitorManager monitorManager,
            GpuBackend backend
    ) throws BackendCreationException {
        this.eventHandler = eventHandler;
        this.backend = backend;
        this.width = Math.max(1, BrowserGpu.canvasWidth());
        this.height = Math.max(1, BrowserGpu.canvasHeight());
        this.guiScaledWidth = width;
        this.guiScaledHeight = height;
    }

    public static long createGlfwWindow(
            int width,
            int height,
            String title,
            long monitor,
            GpuBackend backend
    ) throws BackendCreationException {
        return 1L;
    }

    public static String getPlatform() {
        return "Web";
    }

    public int getRefreshRate() {
        return 60;
    }

    public boolean shouldClose() {
        return false;
    }

    public static void checkGlfwError(BiConsumer<Integer, String> errorConsumer) {
    }

    public void setIcon(PackResources resources, IconSet iconSet) throws IOException {
    }

    public void setErrorSection(String section) {
    }

    public void defaultErrorCallback(int error, long description) {
    }

    public void setDefaultErrorCallback() {
    }

    @Override
    public void close() {
    }

    public void updateFullscreenIfChanged() {
    }

    public Optional<VideoMode> getPreferredFullscreenVideoMode() {
        return preferredFullscreenVideoMode;
    }

    public void setPreferredFullscreenVideoMode(Optional<VideoMode> mode) {
        preferredFullscreenVideoMode = mode;
    }

    public void changeFullscreenVideoMode() {
    }

    public void toggleFullScreen() {
        fullscreen = !fullscreen;
    }

    public void setWindowed(int width, int height) {
        fullscreen = false;
        setWidth(width);
        setHeight(height);
    }

    public int calculateScale(int maxScale, boolean forceUnicode) {
        int scale = 1;
        while (scale != maxScale
                && scale < width
                && scale < height
                && width / (scale + 1) >= BASE_WIDTH
                && height / (scale + 1) >= BASE_HEIGHT) {
            scale++;
        }
        if (forceUnicode && scale % 2 != 0) {
            scale++;
        }
        return scale;
    }

    /**
     * Applies a new canvas backing-store size and lets Minecraft re-lay-out.
     *
     * <p>This shadow replaces the JAR's {@code Window}, so blaze3d's GLFW
     * framebuffer/window-size callbacks do not exist here — nothing registers
     * them and nothing fires them. Without this method the size was read once
     * in the constructor and never changed again: Minecraft kept rendering at
     * the boot resolution while the canvas grew, and the host composite
     * stretched that stale frame to fill it. That was the "resizing stretches
     * instead of rescaling" bug, and it is why patching the JAR's Window had no
     * effect — that class never runs.</p>
     *
     * <p>{@code framebufferSizeChanged()} is what Minecraft uses to rebuild
     * render targets and re-derive GUI scale, i.e. the work the desktop
     * framebuffer-size callback would have triggered.</p>
     */
    public void resize(int newWidth, int newHeight) {
        int clampedWidth = Math.max(1, newWidth);
        int clampedHeight = Math.max(1, newHeight);
        if (clampedWidth == width && clampedHeight == height) {
            return;
        }

        width = clampedWidth;
        height = clampedHeight;
        setGuiScale(guiScale);
        eventHandler.framebufferSizeChanged();
    }

    public void setGuiScale(int scale) {
        guiScale = Math.max(1, scale);
        guiScaledWidth = (int) Math.ceil((double) width / guiScale);
        guiScaledHeight = (int) Math.ceil((double) height / guiScale);
    }

    public void setTitle(String title) {
    }

    public long handle() {
        return handle;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    public boolean isIconified() {
        return false;
    }

    public boolean isFocused() {
        return true;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void setWidth(int width) {
        this.width = Math.max(1, width);
        setGuiScale(guiScale);
    }

    public void setHeight(int height) {
        this.height = Math.max(1, height);
        setGuiScale(guiScale);
    }

    public int getScreenWidth() {
        return width;
    }

    public int getScreenHeight() {
        return height;
    }

    public int getGuiScaledWidth() {
        return guiScaledWidth;
    }

    public int getGuiScaledHeight() {
        return guiScaledHeight;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getGuiScale() {
        return guiScale;
    }

    public Monitor findBestMonitor() {
        return null;
    }

    public void updateRawMouseInput(boolean enabled) {
    }

    public void setWindowCloseCallback(Runnable callback) {
        closeCallback = callback;
    }

    public boolean isMinimized() {
        return false;
    }

    public void setAllowCursorChanges(boolean allowCursorChanges) {
        this.allowCursorChanges = allowCursorChanges;
    }

    public void selectCursor(CursorType cursorType) {
        if (!allowCursorChanges) {
            return;
        }
    }

    public float getAppropriateLineWidth() {
        return Math.max(1.0F, guiScale);
    }

    public GpuBackend backend() {
        return backend;
    }
}
