package dev.mcweb.graal;

import java.util.Base64;
import org.graalvm.webimage.api.JS;
import dev.mcweb.graal.webgpu.BrowserGpu;

/**
 * Main-realm bridge to a real Minecraft server, reached through the WebSocket
 * same-origin gateway (embedded in {@code tools/dev-server.mjs}; the standalone
 * {@code tools/mc-relay.mjs} remains a diagnostic option).
 *
 * <p>Deliberately the same shape as {@link BrowserWorkerClientTransport}: the
 * relay hands the page whole packet bodies and the host queues them into one
 * length-prefixed batch per frame, so this is a transport swap rather than a
 * second protocol implementation. The relay owns Minecraft's VarInt framing —
 * and will own compression, because Web Image has no zlib.</p>
 */
public final class BrowserRemoteTransport {
    private static PacketHandler packetHandler;

    private BrowserRemoteTransport() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet !== undefined;", args = {})
    public static native boolean isAvailable();

    @JS.Coerce
    @JS(value = "const r = globalThis.mcWebNet.connect(relay, host, port);"
            + "if (r && r.error) throw new Error(r.error);",
            args = {"relay", "host", "port"})
    public static native void connect(String relay, String host, int port);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet.state();", args = {})
    public static native String state();

    /** Human-readable WebSocket/gateway failure retained by the host. */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet?.info?.()?.lastError || '';", args = {})
    public static native String lastError();

    /** Authenticated profile metadata fetched from the app origin before boot. */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet?.identity?.()?.name || '';", args = {})
    public static native String launcherProfileName();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet?.identity?.()?.id || '';", args = {})
    public static native String launcherProfileId();

    @JS.Coerce
    @JS(value = "globalThis.mcWebNet.disconnect();", args = {})
    public static native void disconnect();

    /**
     * Tells the gateway which packet id answers a keep-alive on this phase.
     *
     * <p>Applying a large server resource pack blocks this thread for half a
     * minute, so the keep-alive that arrives during it is not answered until
     * long after the server has given up — a real server (hoplite.gg) closes
     * the connection at 30 s while the pack is still loading. The gateway is
     * the only participant still running, so it answers on the client's behalf;
     * it just needs to know the id, which is a per-phase protocol detail only
     * this side has. Encoding a throwaway packet is the least fragile way to
     * read it: no registry lookup, no hardcoded number, correct by
     * construction on every protocol version.</p>
     */
    public static void publishKeepAliveId(
            final net.minecraft.network.ProtocolInfo<?> protocol
    ) {
        if (protocol.id() != net.minecraft.network.ConnectionProtocol.PLAY
                && protocol.id() != net.minecraft.network.ConnectionProtocol.CONFIGURATION) {
            return;
        }
        // Both directions are needed and they are different numbers: the
        // gateway recognises the server's keep-alive by one id and must reply
        // with the other.
        boolean serverbound = protocol.flow() == net.minecraft.network.protocol.PacketFlow.SERVERBOUND;
        publish(protocol, serverbound
                ? new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(0L)
                : new net.minecraft.network.protocol.common.ClientboundKeepAlivePacket(0L),
                serverbound ? "serverbound" : "clientbound");
    }

    private static void publish(
            final net.minecraft.network.ProtocolInfo<?> protocol,
            final net.minecraft.network.protocol.Packet<?> packet,
            final String kind
    ) {
        try {
            byte[] encoded = dev.mcweb.graal.net.PacketWire.encode(protocol, packet);
            // The id is a VarInt; every keep-alive id in practice is one byte,
            // and a multi-byte id would need a wider control message anyway.
            if (encoded.length != 9 || (encoded[0] & 0x80) != 0) {
                return;
            }
            reportKeepAliveId(kind, encoded[0] & 0xff, encoded.length);
            BrowserGpu.reportProgress("multiplayer:keepalive-id " + kind
                    + " phase=" + protocol.id() + " id=" + (encoded[0] & 0xff));
        } catch (Throwable unavailable) {
            // The gateway simply keeps forwarding keep-alives if this fails.
            BrowserGpu.reportProgress("multiplayer:keepalive-id-failed " + kind + " "
                    + unavailable.getClass().getSimpleName());
        }
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebNet?.publishKeepAlive?.(kind, id, length);",
            args = {"kind", "id", "length"})
    private static native void reportKeepAliveId(String kind, int id, int length);

    /**
     * A join asked for from the page (console or harness), or null. Polled from
     * the frame pump so the connect happens on the client thread, in the same
     * place the Worker lane's state machine runs.
     */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet.consumeJoin();", args = {})
    public static native String consumeJoin();

    public static void send(byte[] bytes) {
        sendPacket64(Base64.getEncoder().encodeToString(bytes));
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebNet.sendPacket64(base64);", args = {"base64"})
    private static native void sendPacket64(String base64);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebNet.drainPackets64();", args = {})
    private static native String drainPackets64();

    public static void onPacket(PacketHandler handler) {
        packetHandler = handler;
    }

    /** One frame's inbound packets in a single crossing; see the Worker twin. */
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
            if (length < 0 || offset + length > data.length) {
                BrowserGpu.reportProgress("remote:truncated-batch length=" + length
                        + " remaining=" + (data.length - offset));
                break;
            }
            handler.accept(java.util.Arrays.copyOfRange(data, offset, offset + length));
            offset += length;
        }
    }

    @FunctionalInterface
    public interface PacketHandler {
        void accept(byte[] data);
    }
}
