package dev.mcweb.graal;

import java.util.Base64;
import org.graalvm.webimage.api.JS;
import dev.mcweb.graal.webgpu.BrowserGpu;

/** Main-realm Java bridge to the integrated-server Worker packet port. */
public final class BrowserWorkerClientTransport {
    private static PacketHandler packetHandler;
    private BrowserWorkerClientTransport() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServer !== undefined;", args = {})
    public static native boolean isAvailable();

    @JS.Coerce
    @JS(value = "const q=new URLSearchParams(globalThis.location?.search || '');"
            + "return q.get('mcweb_inline_server') !== '1'"
            + "&& q.get('mcweb_server_worker') !== '0';", args = {})
    public static native boolean isRequested();

    /** Default world write-back; {@code ?mcweb_world_writeback=0} is diagnostic only. */
    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_world_writeback') !== '0';", args = {})
    public static native boolean isWorldWriteBackEnabled();

    @JS.Coerce
    @JS(value = "globalThis.mcWebServer.launch(image).catch(e=>console.error(e));",
            args = {"image"})
    public static native void launch(String image);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServer.info().state;", args = {})
    public static native String state();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServer.consumeLoadProgress();", args = {})
    public static native String consumeLoadProgress();

    @JS.Coerce
    @JS(value = "const r=globalThis.mcWebServer.startWorld(json);"
            + "if(r.error)throw new Error(r.error);",
            args = {"json"})
    public static native void startWorld(String json);

    @JS.Coerce
    @JS(value = "globalThis.mcWebServer.stop();", args = {})
    public static native void stop();
    public static void send(byte[] bytes) {
        sendBase64(Base64.getEncoder().encodeToString(bytes));
    }

    /**
     * Control-plane state push to the server Worker. {@code world-entered}
     * flips the Worker server out of its accelerated world-load branch into
     * the normal 20 TPS pacing once the client drops the loading screen; see
     * {@link BrowserIntegratedServerCompat#markClientWorldEntered()}.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebServer.sendState(state);", args = {"state"})
    public static native void sendState(String state);

    /**
     * Returns the saved world the Worker shipped back, or null while none has
     * arrived. The Worker answers every {@code save-snapshot} request exactly
     * once, with {@code ""} when it had nothing to send.
     */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebServer.consumeWorldSnapshot();", args = {})
    public static native String consumeWorldSnapshot();

    @JS.Coerce
    @JS(value = "globalThis.mcWebServer.sendPacket64(base64);", args = {"base64"})
    private static native void sendBase64(String base64);

    public static void onPacket(PacketHandler handler) {
        packetHandler = handler;
        // Deliberately no per-packet handler: see drainInbound().
    }

    /**
     * Drains one frame's worth of inbound packets in a single boundary crossing.
     *
     * <p>The previous seam installed a JS callback that ran
     * {@code getExport('mcweb.client.packet')(toJavaString(...))} <em>per packet</em>.
     * Measured against a private server Worker, inbound runs at a sustained
     * ~2,500 packets/s (about 125 per 20 Hz server tick, ~16 bytes each late in a
     * run) against ~34 outbound — so that seam was doing ~2,500 Java string
     * materialisations and ~2,500 wasm crossings every second. A CPU profile of
     * the moving render thread put {@code charArrayToString} at 34.4% of it, the
     * single largest entry.</p>
     *
     * <p>The host now queues frames and hands over one length-prefixed blob, so
     * the cost becomes one crossing and one Base64 decode per frame regardless of
     * how many packets arrived. Dispatching at the frame boundary rather than on
     * the port event also matches how the same-heap path already behaves
     * ({@code Connection.drainBrowserClient}).</p>
     */
    public static void drainInbound() {
        PacketHandler handler = packetHandler;
        if (handler == null) {
            return;
        }
        String batch = drainPackets64();
        if (batch == null || batch.isEmpty()) {
            return;
        }
        byte[] data = Base64.getDecoder().decode(batch);
        int offset = 0;
        while (offset + 4 <= data.length) {
            int length = ((data[offset] & 0xFF) << 24)
                    | ((data[offset + 1] & 0xFF) << 16)
                    | ((data[offset + 2] & 0xFF) << 8)
                    | (data[offset + 3] & 0xFF);
            offset += 4;
            // A truncated tail means the host framing and this reader disagree;
            // dropping the remainder is safer than handing a partial packet to
            // the protocol decoder, which would desynchronise the connection.
            if (length < 0 || offset + length > data.length) {
                BrowserGpu.reportProgress("worker-client:truncated-batch length=" + length
                        + " remaining=" + (data.length - offset));
                break;
            }
            handler.accept(java.util.Arrays.copyOfRange(data, offset, offset + length));
            offset += length;
        }
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServer.drainPackets64();", args = {})
    private static native String drainPackets64();

    @FunctionalInterface
    public interface PacketHandler {
        void accept(byte[] data);
    }
}
