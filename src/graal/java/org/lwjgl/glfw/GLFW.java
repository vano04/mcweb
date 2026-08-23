package org.lwjgl.glfw;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.nio.ByteBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.PointerBuffer;

/**
 * Browser GLFW: every entry point is a browser-safe no-op or default. The
 * real class loads native function pointers in its static initializer, which
 * cannot exist in Web Image; this shadow replaces the class wholesale, so
 * any Minecraft code path that still references GLFW degrades gracefully.
 * Real input flows DOM -> BrowserInputCompat -> the retained GLFW callback
 * interfaces; window state comes from the canvas.
 */
public final class GLFW {
    // Platform constants referenced by a few code paths.
    public static final int GLFW_PLATFORM_WAYLAND = 0x00060006;
    public static final int GLFW_TRUE = 1;
    public static final int GLFW_FALSE = 0;
    public static final int GLFW_RELEASE = 0;
    public static final int GLFW_PRESS = 1;
    public static final int GLFW_REPEAT = 2;
    public static final int GLFW_KEY_UNKNOWN = -1;
    public static final int GLFW_CURSOR = 0x00033001;
    public static final int GLFW_CURSOR_NORMAL = 0x00034001;
    public static final int GLFW_CURSOR_DISABLED = 0x00034003;

    private static final long START_NANOS = System.nanoTime();
    private static String clipboard;
    private static int cursorMode = GLFW_CURSOR_NORMAL;

    private GLFW() {
    }

    // ---- lifecycle ----------------------------------------------------------

    public static boolean glfwInit() {
        return true;
    }

    public static void glfwTerminate() {
    }

    public static void glfwInitHint(int hint, int value) {
    }

    public static void glfwGetVersion(IntBuffer major, IntBuffer minor, IntBuffer rev) {
        if (major != null) {
            major.put(0, 3);
        }
        if (minor != null) {
            minor.put(0, 4);
        }
        if (rev != null) {
            rev.put(0, 0);
        }
    }

    public static void glfwGetVersion(int[] major, int[] minor, int[] rev) {
        if (major != null && major.length > 0) {
            major[0] = 3;
        }
        if (minor != null && minor.length > 0) {
            minor[0] = 4;
        }
        if (rev != null && rev.length > 0) {
            rev[0] = 0;
        }
    }

    public static String glfwGetVersionString() {
        return "3.4.0 browser WebGPU shadow";
    }

    public static int glfwGetPlatform() {
        return GLFW_PLATFORM_WAYLAND;
    }

    public static boolean glfwPlatformSupported(int platform) {
        return platform == GLFW_PLATFORM_WAYLAND;
    }

    public static boolean glfwVulkanSupported() {
        return false;
    }

    public static PointerBuffer glfwGetRequiredInstanceExtensions() {
        return null;
    }

    public static GLFWErrorCallback glfwSetErrorCallback(GLFWErrorCallbackI cbfun) {
        return null;
    }

    // ---- monitors ------------------------------------------------------------

    public static PointerBuffer glfwGetMonitors() {
        return null;
    }

    public static long glfwGetPrimaryMonitor() {
        return 0L;
    }

    public static GLFWMonitorCallback glfwSetMonitorCallback(GLFWMonitorCallbackI cbfun) {
        return null;
    }

    public static GLFWVidMode.Buffer glfwGetVideoModes(long monitor) {
        return null;
    }

    public static GLFWVidMode glfwGetVideoMode(long monitor) {
        return null;
    }

    public static void glfwGetMonitorPos(long monitor, IntBuffer xpos, IntBuffer ypos) {
        if (xpos != null) {
            xpos.put(0, 0);
        }
        if (ypos != null) {
            ypos.put(0, 0);
        }
    }

    public static void glfwGetMonitorWorkarea(long monitor, IntBuffer xpos, IntBuffer ypos, IntBuffer width, IntBuffer height) {
        if (xpos != null) {
            xpos.put(0, 0);
        }
        if (ypos != null) {
            ypos.put(0, 0);
        }
        if (width != null) {
            width.put(0, BrowserGpu.canvasWidth());
        }
        if (height != null) {
            height.put(0, BrowserGpu.canvasHeight());
        }
    }

    public static void glfwGetMonitorPhysicalSize(long monitor, IntBuffer width, IntBuffer height) {
        if (width != null) {
            width.put(0, 0);
        }
        if (height != null) {
            height.put(0, 0);
        }
    }

    public static void glfwGetMonitorContentScale(long monitor, FloatBuffer xscale, FloatBuffer yscale) {
        if (xscale != null) {
            xscale.put(0, 1.0f);
        }
        if (yscale != null) {
            yscale.put(0, 1.0f);
        }
    }

    public static String glfwGetMonitorName(long monitor) {
        return "Browser Canvas";
    }

    public static void glfwSetGamma(long monitor, float gamma) {
    }

    public static GLFWGammaRamp glfwGetGammaRamp(long monitor) {
        return null;
    }

    public static void glfwSetGammaRamp(long monitor, GLFWGammaRamp ramp) {
    }

    // ---- window hints and creation --------------------------------------------

    public static void glfwDefaultWindowHints() {
    }

    public static void glfwWindowHint(int hint, int value) {
    }

    // ---- window management ------------------------------------------------------

    public static boolean glfwWindowShouldClose(long window) {
        return false;
    }

    public static void glfwSetWindowShouldClose(long window, boolean value) {
    }

    public static void glfwSetWindowTitle(long window, ByteBuffer title) {
    }

    public static void glfwSetWindowTitle(long window, CharSequence title) {
    }

    public static void glfwSetWindowIcon(long window, GLFWImage.Buffer images) {
    }

    public static void glfwGetWindowPos(long window, IntBuffer xpos, IntBuffer ypos) {
        if (xpos != null) {
            xpos.put(0, 0);
        }
        if (ypos != null) {
            ypos.put(0, 0);
        }
    }

    /**
     * The {@code int[]} overload, which is the one Minecraft actually calls
     * ({@code glfwGetWindowPos:(J[I[I)V}).
     *
     * <p>Its absence was why resizing stretched instead of re-laying-out.
     * blaze3d's {@code Window} constructor takes this branch whenever the
     * primary monitor is null — and this port's {@code glfwGetPrimaryMonitor}
     * returns 0, so it always does — several lines <em>before</em> it registers
     * the framebuffer- and window-size callbacks. Without the method the
     * constructor never got that far, both callbacks stayed null, and every
     * resize was dropped on the floor while the host went on stretching the
     * last good frame across the new canvas.</p>
     */
    public static void glfwGetWindowPos(long window, int[] xpos, int[] ypos) {
        if (xpos != null && xpos.length > 0) {
            xpos[0] = 0;
        }
        if (ypos != null && ypos.length > 0) {
            ypos[0] = 0;
        }
    }

    /** Same story as {@link #glfwGetWindowPos(long, int[], int[])}; called from setMode(). */
    public static void glfwGetMonitorPos(long monitor, int[] xpos, int[] ypos) {
        if (xpos != null && xpos.length > 0) {
            xpos[0] = 0;
        }
        if (ypos != null && ypos.length > 0) {
            ypos[0] = 0;
        }
    }

    public static void glfwSetWindowPos(long window, int xpos, int ypos) {
    }

    public static void glfwGetWindowSize(long window, IntBuffer width, IntBuffer height) {
        if (width != null) {
            width.put(0, BrowserGpu.canvasWidth());
        }
        if (height != null) {
            height.put(0, BrowserGpu.canvasHeight());
        }
    }

    public static void glfwSetWindowSizeLimits(long window, int minwidth, int minheight, int maxwidth, int maxheight) {
    }

    public static void glfwSetWindowSize(long window, int width, int height) {
    }

    public static void glfwGetFramebufferSize(long window, IntBuffer width, IntBuffer height) {
        if (width != null) {
            width.put(0, BrowserGpu.canvasWidth());
        }
        if (height != null) {
            height.put(0, BrowserGpu.canvasHeight());
        }
    }

    public static void glfwIconifyWindow(long window) {
    }

    public static void glfwRestoreWindow(long window) {
    }

    public static void glfwMaximizeWindow(long window) {
    }

    public static void glfwShowWindow(long window) {
    }

    public static void glfwHideWindow(long window) {
    }

    public static void glfwFocusWindow(long window) {
    }

    public static void glfwRequestWindowAttention(long window) {
    }

    public static long glfwGetWindowMonitor(long window) {
        return 0L;
    }

    public static void glfwSetWindowMonitor(long window, long monitor, int xpos, int ypos, int width, int height, int refreshRate) {
    }

    public static int glfwGetWindowAttrib(long window, int attrib) {
        return 0;
    }

    public static void glfwSetWindowAttrib(long window, int attrib, int value) {
    }

    // ---- window callbacks -------------------------------------------------------

    public static GLFWWindowPosCallback glfwSetWindowPosCallback(long window, GLFWWindowPosCallbackI cbfun) {
        return null;
    }

    public static GLFWWindowSizeCallback glfwSetWindowSizeCallback(long window, GLFWWindowSizeCallbackI cbfun) {
        // Retained rather than dropped: the page host invokes it on a browser
        // resize. See BrowserWindowCompat.
        dev.mcweb.graal.BrowserWindowCompat.setWindowSizeCallback(window, cbfun);
        return null;
    }

    public static GLFWWindowCloseCallback glfwSetWindowCloseCallback(long window, GLFWWindowCloseCallbackI cbfun) {
        return null;
    }

    public static GLFWWindowFocusCallback glfwSetWindowFocusCallback(long window, GLFWWindowFocusCallbackI cbfun) {
        return null;
    }

    public static GLFWWindowIconifyCallback glfwSetWindowIconifyCallback(long window, GLFWWindowIconifyCallbackI cbfun) {
        return null;
    }

    public static GLFWFramebufferSizeCallback glfwSetFramebufferSizeCallback(long window, GLFWFramebufferSizeCallbackI cbfun) {
        dev.mcweb.graal.BrowserWindowCompat.setFramebufferSizeCallback(window, cbfun);
        return null;
    }

    // ---- events -------------------------------------------------------------------

    public static void glfwPollEvents() {
    }

    public static void glfwWaitEvents() {
    }

    public static void glfwWaitEventsTimeout(double timeout) {
    }

    // ---- input ---------------------------------------------------------------------

    public static int glfwGetInputMode(long window, int mode) {
        if (mode == GLFW_CURSOR) {
            return cursorMode;
        }
        return 0;
    }

    public static void glfwSetInputMode(long window, int mode, int value) {
        if (mode == GLFW_CURSOR) {
            cursorMode = value;
            dev.mcweb.graal.BrowserInputCompat.setCursorDisabled(
                    value == GLFW_CURSOR_DISABLED ? 1 : 0
            );
        }
    }

    public static boolean glfwRawMouseMotionSupported() {
        return false;
    }

    public static void glfwGetCursorPos(long window, DoubleBuffer xpos, DoubleBuffer ypos) {
        if (xpos != null) {
            xpos.put(0, 0.0);
        }
        if (ypos != null) {
            ypos.put(0, 0.0);
        }
    }

    public static void glfwSetCursorPos(long window, double xpos, double ypos) {
    }

    public static long glfwCreateStandardCursor(int shape) {
        return 1L;
    }

    public static void glfwDestroyCursor(long cursor) {
    }

    public static void glfwSetCursor(long window, long cursor) {
    }

    public static void glfwSetPreeditCursorRectangle(long window, int x, int y, int width, int height) {
    }

    public static int glfwGetKey(long window, int key) {
        return GLFW_RELEASE;
    }

    public static String glfwGetKeyName(int key, int scancode) {
        if (key >= 'A' && key <= 'Z') {
            return String.valueOf((char) Character.toLowerCase(key));
        }
        if (key >= '0' && key <= '9') {
            return String.valueOf((char) key);
        }
        if (key == ' ') {
            return " ";
        }
        return null;
    }

    // ---- input callbacks -----------------------------------------------------------

    public static GLFWKeyCallback glfwSetKeyCallback(long window, GLFWKeyCallbackI cbfun) {
        return null;
    }

    public static GLFWCharCallback glfwSetCharCallback(long window, GLFWCharCallbackI cbfun) {
        return null;
    }

    public static GLFWCharModsCallback glfwSetCharModsCallback(long window, GLFWCharModsCallbackI cbfun) {
        return null;
    }

    public static GLFWPreeditCallback glfwSetPreeditCallback(long window, GLFWPreeditCallbackI cbfun) {
        return null;
    }

    public static GLFWIMEStatusCallback glfwSetIMEStatusCallback(long window, GLFWIMEStatusCallbackI cbfun) {
        return null;
    }

    public static GLFWMouseButtonCallback glfwSetMouseButtonCallback(long window, GLFWMouseButtonCallbackI cbfun) {
        return null;
    }

    public static GLFWCursorPosCallback glfwSetCursorPosCallback(long window, GLFWCursorPosCallbackI cbfun) {
        return null;
    }

    public static GLFWCursorEnterCallback glfwSetCursorEnterCallback(long window, GLFWCursorEnterCallbackI cbfun) {
        return null;
    }

    public static GLFWScrollCallback glfwSetScrollCallback(long window, GLFWScrollCallbackI cbfun) {
        return null;
    }

    public static GLFWDropCallback glfwSetDropCallback(long window, GLFWDropCallbackI cbfun) {
        return null;
    }

    // ---- clipboard ---------------------------------------------------------------------

    public static void glfwSetClipboardString(long window, ByteBuffer string) {
        // The browser clipboard API is asynchronous; keep a Java-side echo.
    }

    public static void glfwSetClipboardString(long window, CharSequence string) {
        clipboard = string == null ? null : string.toString();
    }

    public static String glfwGetClipboardString(long window) {
        return clipboard;
    }

    // ---- time -----------------------------------------------------------------------------

    public static double glfwGetTime() {
        return (System.nanoTime() - START_NANOS) / 1_000_000_000.0;
    }

    public static void glfwSetTime(double time) {
    }

    public static long glfwGetTimerValue() {
        return System.nanoTime();
    }

    public static long glfwGetTimerFrequency() {
        return 1_000_000_000L;
    }

    // ---- contexts ---------------------------------------------------------------------------

    public static void glfwMakeContextCurrent(long window) {
    }

    public static long glfwGetCurrentContext() {
        return 0L;
    }

    public static void glfwSwapBuffers(long window) {
    }

    public static void glfwSwapInterval(int interval) {
    }

    public static boolean glfwExtensionSupported(ByteBuffer extension) {
        return false;
    }

    public static boolean glfwExtensionSupported(CharSequence extension) {
        return false;
    }
}
