package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;

/**
 * Java-to-JavaScript bridge for the Track A threading conformance image.
 *
 * <p><b>This is a probe, not scheduler support.</b> Web Image's substituted
 * {@code Thread.start0()} is empty and its heap/memory model is
 * single-threaded, so nothing here makes {@code java.util.concurrent} or
 * Minecraft's executors threaded. It exposes exactly three things:
 *
 * <ul>
 *   <li>a structured report sink the host test harness polls
 *       ({@code globalThis.mcWebConformance});</li>
 *   <li>environment facts Java cannot see directly
 *       ({@code crossOriginIsolated});</li>
 *   <li>host-side Worker/SharedArrayBuffer primitives
 *       ({@code globalThis.mcWebThreads}) that Path A's generated launcher
 *       must eventually provide itself.</li>
 * </ul>
 */
public final class BrowserThreadProbe {
    private BrowserThreadProbe() {
    }

    /** Append {@code {name, status, detail, t}} to the page's report list. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebConformance.report(name, status, detail);",
            args = {"name", "status", "detail"})
    public static native void report(String name, String status, String detail);

    @JS.Coerce
    @JS(value = "return globalThis.crossOriginIsolated === true;", args = {})
    public static native boolean isCrossOriginIsolated();

    @JS.Coerce
    @JS(value = "return typeof SharedArrayBuffer !== 'undefined';", args = {})
    public static native boolean hasSharedArrayBuffer();

    @JS.Coerce
    @JS(value = "return typeof Atomics !== 'undefined' && typeof Atomics.wait === 'function';",
            args = {})
    public static native boolean hasAtomicsWait();

    /**
     * Ask the host to spawn a Worker that instantiates this same Wasm image
     * (mode {@code "image"}) or runs a raw SharedArrayBuffer/Atomics exchange
     * (mode {@code "atomics"}). Results arrive asynchronously as reports
     * prefixed {@code worker.} / {@code sab.}.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebThreads.spawnWorker(wasmPath, mode, rounds);",
            args = {"wasmPath", "mode", "rounds"})
    public static native void spawnWorker(String wasmPath, String mode, int rounds);
}
