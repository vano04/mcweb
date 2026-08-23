package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;
import com.oracle.svm.webimage.annotation.JSRawCall;

/**
 * Tags the next exact {@link Thread#start()} call in this Wasm instance.
 *
 * <p>The low-level WasmLM Thread patch deliberately does not depend on the
 * Minecraft application classes. A tiny per-instance JS tag lets ordinary
 * starts pass a role through that boundary. ForkJoin-created workers retain a
 * stable prefix in their factory because their start call occurs after the
 * factory returns.</p>
 */
public final class McWebThreadRole {
    public static final int GENERIC = 0;
    public static final int SERVER = 1;
    public static final int BACKGROUND = 2;
    public static final int IO = 3;

    private McWebThreadRole() {
    }

    public static void start(Thread thread, int role) {
        setNext(role);
        try {
            thread.start();
        } finally {
            clear();
        }
    }

    /** Set the per-Wasm-instance tag for a custom Thread.start override. */
    public static void setNext(int role) {
        set(role);
    }

    /** Clear a tag after the synchronous Thread.start dispatch returns. */
    public static void clear() {
        set(GENERIC);
    }

    @JSRawCall
    @JS(value = "globalThis.mcwebThreadRole = role | 0;", args = {"role"})
    private static native void set(int role);
}
