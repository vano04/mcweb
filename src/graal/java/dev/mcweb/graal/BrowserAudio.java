package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;

/**
 * Java-to-JavaScript bridge for the browser WebAudio subsystem.
 *
 * <p>Replaces LWJGL's OpenAL backend, which has no entry points in Web Image.
 * The host's {@code mcWebAudio} object owns the decode cache and the
 * {@code sounds.json} resolution that Mojang's {@code SoundEngine} would
 * normally do, because the sound files live in the launcher's asset object
 * store rather than in the client JAR.</p>
 *
 * <p>Every method is kept to a short, flat parameter list of strings, doubles
 * and booleans. Web Image's {@code @JS} bridge has been unreliable with richer
 * shapes in this project (the input bridge had to collapse to a single
 * functional callback), so playback state is addressed by an opaque integer
 * handle instead of by passing objects across the boundary.</p>
 */
public final class BrowserAudio {
    private BrowserAudio() {
    }

    /**
     * Starts a sound event and returns a handle, or 0 when the host declines
     * (audio disabled, unknown event). The event is resolved against
     * {@code sounds.json} on the host side; decoding is asynchronous, so a
     * handle is live before the first sample is audible.
     */
    /**
     * {@code looping} is an int, not a boolean, on purpose. The first version
     * of this method took {@code boolean} and threw NullPointerException on
     * every call before reaching JavaScript — the host function was never
     * entered (its play counter stayed at zero). Every {@code @JS} method that
     * demonstrably works in this project uses only String/int/double
     * parameters; {@code BrowserGpu.createTexture} passes seven of them and
     * returns an int, and {@code clearDepth} takes a double, so width and
     * primitive returns are not the problem. A {@code boolean} <em>parameter</em>
     * has no working precedent here.
     */
    @JS.Coerce
    @JS(
            value = "return globalThis.mcWebAudio.playInstance(name, source, volume, pitch, looping);",
            args = {"name", "source", "volume", "pitch", "looping"}
    )
    public static native int playInstance(String name, String source, double volume, double pitch, int looping);

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.positionInstance(handle, x, y, z);", args = {"handle", "x", "y", "z"})
    public static native void positionInstance(int handle, double x, double y, double z);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.isPlaying(handle);", args = {"handle"})
    public static native boolean isPlaying(int handle);

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.stopInstance(handle);", args = {"handle"})
    public static native void stopInstance(int handle);

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.stopAll();", args = {})
    public static native void stopAll();

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.setCategoryVolume(source, volume);", args = {"source", "volume"})
    public static native void setCategoryVolume(String source, double volume);

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.setListener(x, y, z);", args = {"x", "y", "z"})
    public static native void setListener(double x, double y, double z);

    /** int rather than boolean for the same reason as {@link #playInstance}. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.setEnabled(!!enabled);", args = {"enabled"})
    public static native void setEnabled(int enabled);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.isEnabled();", args = {})
    public static native boolean isEnabled();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.state();", args = {})
    public static native String state();

    // ---- bridge self-test -------------------------------------------------
    // SoundManager.play throws NullPointerException before the host function is
    // ever entered (mcWebAudio's own play counter stays at zero), and Web Image
    // strips stack traces, so the throwing element cannot be read off the
    // exception. These probes climb the argument shapes one step at a time so a
    // single run says exactly which one stops working.

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.probe0();", args = {})
    public static native int probe0();

    @JS.Coerce
    @JS(value = "globalThis.mcWebAudio.probe1(name);", args = {"name"})
    public static native void probe1(String name);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.probe2(name);", args = {"name"})
    public static native int probe2(String name);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.probe3(name, volume);", args = {"name", "volume"})
    public static native int probe3(String name, double volume);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebAudio.probe4(name, source, volume, pitch, looping);",
            args = {"name", "source", "volume", "pitch", "looping"})
    public static native int probe4(String name, String source, double volume, double pitch, int looping);

    /** Runs the ladder and reports each step's outcome to the host. */
    public static void reportBridgeSelfTest() {
        run("probe0 int()", () -> String.valueOf(probe0()));
        run("probe1 void(String)", () -> {
            probe1("hello");
            return "ok";
        });
        run("probe2 int(String)", () -> String.valueOf(probe2("hello")));
        run("probe3 int(String,double)", () -> String.valueOf(probe3("hello", 0.5)));
        run("probe4 int(String,String,double,double,int)",
                () -> String.valueOf(probe4("minecraft:ui.button.click", "master", 0.5, 1.0, 0)));
    }

    private static void run(String label, java.util.function.Supplier<String> probe) {
        String outcome;
        try {
            outcome = "= " + probe.get();
        } catch (Throwable failure) {
            outcome = "THREW " + failure.getClass().getName();
        }
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("audio-probe:" + label + " " + outcome);
    }
}
