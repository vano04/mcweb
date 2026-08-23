package dev.mcweb.graal;

/** Browser replacements for desktop-only GLFW window policy calls. */
public final class BrowserGlfwCompat {
    private BrowserGlfwCompat() {
    }

    public static void glfwDefaultWindowHints() {
        // WebGPU canvas configuration replaces GLFW window hints.
    }

    public static void glfwWindowHint(int hint, int value) {
        // Browser window behavior is controlled by the document and canvas.
    }

    public static void glfwSetWindowSizeLimits(
            long window,
            int minimumWidth,
            int minimumHeight,
            int maximumWidth,
            int maximumHeight
    ) {
        // CSS and the browser viewport own canvas size limits.
    }

    public static void glfwShowWindow(long window) {
        // The canvas is visible as soon as the page is rendered.
    }

    public static void glfwPollEvents() {
        // DOM events are delivered to the page host and dispatched through
        // BrowserInputCompat; there is no native event queue to poll.
    }

    /**
     * blaze3d {@code Window}'s constructor calls this on the path to
     * registering its size callbacks, whenever the primary monitor is null —
     * which it always is here, since {@code glfwGetPrimaryMonitor} returns 0.
     * Left pointing at real LWJGL it never got past this call, so the size
     * callbacks were never installed and every browser resize was discarded.
     * The window is always at the origin in a canvas.
     */
    public static void glfwGetWindowPos(long window, int[] xpos, int[] ypos) {
        if (xpos != null && xpos.length > 0) {
            xpos[0] = 0;
        }
        if (ypos != null && ypos.length > 0) {
            ypos[0] = 0;
        }
    }

    public static void glfwGetFramebufferSize(long window, int[] width, int[] height) {
        // The canvas framebuffer is sized in CSS pixels; device-pixel scaling
        // is handled by the WebGPU surface configuration on the host.
        if (width != null && width.length > 0) {
            width[0] = dev.mcweb.graal.webgpu.BrowserGpu.canvasWidth();
        }
        if (height != null && height.length > 0) {
            height[0] = dev.mcweb.graal.webgpu.BrowserGpu.canvasHeight();
        }
    }

    /**
     * Drop-in for {@code RenderSystem.setErrorCallback}. Accepting Object avoids
     * loading {@code GLFWErrorCallbackI}, whose static init requires libffi
     * natives that Web Image cannot provide.
     */
    public static void setErrorCallback(Object callback) {
    }
}
