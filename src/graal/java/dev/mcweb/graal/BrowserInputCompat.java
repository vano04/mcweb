package dev.mcweb.graal;

import com.mojang.blaze3d.platform.Window;
import org.lwjgl.glfw.GLFWCharCallbackI;
import org.lwjgl.glfw.GLFWCursorPosCallbackI;
import org.lwjgl.glfw.GLFWDropCallbackI;
import org.lwjgl.glfw.GLFWIMEStatusCallbackI;
import org.lwjgl.glfw.GLFWKeyCallbackI;
import org.lwjgl.glfw.GLFWMouseButtonCallbackI;
import org.lwjgl.glfw.GLFWPreeditCallbackI;
import org.lwjgl.glfw.GLFWScrollCallbackI;
import org.graalvm.webimage.api.JS;

/**
 * Browser replacement for InputConstants' GLFW callback installers. LWJGL 3.4
 * builds native dyncallback trampolines for each callback (its trampoline
 * allocation returns null in Web Image, surfacing as a message-less
 * OutOfMemoryError). The retained callback interfaces are driven later by DOM
 * events from the page host through the dispatch methods below.
 */
public final class BrowserInputCompat {
    private static volatile long windowHandle;
    private static volatile GLFWCursorPosCallbackI cursorPos;
    private static volatile GLFWMouseButtonCallbackI mouseButton;
    private static volatile GLFWScrollCallbackI scroll;
    private static volatile GLFWDropCallbackI drop;
    private static volatile GLFWKeyCallbackI key;
    private static volatile GLFWCharCallbackI charMods;
    private static volatile GLFWPreeditCallbackI preedit;
    private static volatile GLFWIMEStatusCallbackI imeStatus;

    private BrowserInputCompat() {
    }

    public static void setupMouseCallbacks(
            Window window,
            GLFWCursorPosCallbackI cursorPosCallback,
            GLFWMouseButtonCallbackI mouseButtonCallback,
            GLFWScrollCallbackI scrollCallback,
            GLFWDropCallbackI dropCallback
    ) {
        windowHandle = window == null ? 0L : window.handle();
        cursorPos = cursorPosCallback;
        mouseButton = mouseButtonCallback;
        scroll = scrollCallback;
        drop = dropCallback;
    }

    public static void setupKeyboardCallbacks(
            Window window,
            GLFWKeyCallbackI keyCallback,
            GLFWCharCallbackI charCallback,
            GLFWPreeditCallbackI preeditCallback,
            GLFWIMEStatusCallbackI imeStatusCallback
    ) {
        windowHandle = window == null ? 0L : window.handle();
        key = keyCallback;
        charMods = charCallback;
        preedit = preeditCallback;
        imeStatus = imeStatusCallback;
    }

    // DOM event entry points for the later input bridge.

    public static void dispatchCursorPos(double x, double y) {
        GLFWCursorPosCallbackI callback = cursorPos;
        if (callback != null) {
            callback.invoke(windowHandle, x, y);
        }
    }

    public static void dispatchMouseButton(int button, int action, int mods) {
        GLFWMouseButtonCallbackI callback = mouseButton;
        if (callback != null) {
            callback.invoke(windowHandle, button, action, mods);
        }
    }

    public static void dispatchScroll(double xOffset, double yOffset) {
        GLFWScrollCallbackI callback = scroll;
        if (callback != null) {
            callback.invoke(windowHandle, xOffset, yOffset);
        }
    }

    public static void dispatchKey(int key, int scancode, int action, int mods) {
        GLFWKeyCallbackI callback = BrowserInputCompat.key;
        if (callback != null) {
            callback.invoke(windowHandle, key, scancode, action, mods);
        }
    }

    public static void dispatchChar(int codepoint) {
        GLFWCharCallbackI callback = charMods;
        if (callback != null) {
            callback.invoke(windowHandle, codepoint);
        }
    }

    /**
     * Applies GLFW cursor-disabled mode to the browser canvas. Pointer lock
     * still requires a user gesture, so the host arms it for the next click.
     */
    @JS.Coerce
    @JS(value = """
            const input = globalThis.mcWebInput;
            if (!input) return;
            input.lockPointerOnNextClick = disabled !== 0;
            if (disabled !== 0) {
              const canvas = document.querySelector('canvas');
              if (canvas) {
                input.syntheticX = canvas.width / 2;
                input.syntheticY = canvas.height / 2;
              }
            } else if (document.pointerLockElement && document.exitPointerLock) {
              document.exitPointerLock();
            }
            """, args = {"disabled"})
    public static native void setCursorDisabled(int disabled);
}
