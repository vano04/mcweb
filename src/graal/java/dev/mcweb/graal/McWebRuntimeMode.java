package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;

/**
 * Explicit execution contract selected by the browser launcher before the image runs.
 *
 * <p>Background capacity and Server semantics are deliberately separate decisions:
 * a threaded image may have zero Background workers while still owning Mojang's real
 * Server thread, and a cooperative image must never acquire threaded Server semantics
 * merely because a host reports a non-zero agent count.</p>
 */
public final class McWebRuntimeMode {
    public enum Runtime {
        WASMGC_COOPERATIVE,
        WASMLM_INLINE,
        WASMLM_THREADED
    }

    public enum Server {
        COOPERATIVE,
        THREADED
    }

    private static volatile Runtime runtime;

    private McWebRuntimeMode() {
    }

    /** The launcher sets this before injecting the generated Web Image loader. */
    @JS.Coerce
    @JS(value = "return String(globalThis.mcWebRuntimeMode || '');", args = {})
    private static native String launcherMode();

    public static Runtime runtime() {
        Runtime current = runtime;
        if (current != null) {
            return current;
        }
        synchronized (McWebRuntimeMode.class) {
            current = runtime;
            if (current == null) {
                String selected;
                try {
                    selected = launcherMode();
                } catch (Throwable failure) {
                    selected = "";
                }
                current = switch (selected) {
                    case "WASMLM_INLINE" -> Runtime.WASMLM_INLINE;
                    case "WASMLM_THREADED" -> Runtime.WASMLM_THREADED;
                    case "WASMGC_COOPERATIVE" -> Runtime.WASMGC_COOPERATIVE;
                    default -> Runtime.WASMGC_COOPERATIVE;
                };
                runtime = current;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "runtime-mode:" + current.name());
            }
        }
        return current;
    }

    public static Server server() {
        return runtime() == Runtime.WASMLM_THREADED
                ? Server.THREADED
                : Server.COOPERATIVE;
    }

    public static boolean isThreaded() {
        return runtime() == Runtime.WASMLM_THREADED;
    }

    public static boolean usesBackgroundAgents() {
        return isThreaded();
    }

    public static boolean isCooperativeServer() {
        return server() == Server.COOPERATIVE;
    }

    public static String name() {
        return runtime().name();
    }
}
