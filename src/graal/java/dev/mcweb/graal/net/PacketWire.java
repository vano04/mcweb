package dev.mcweb.graal.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;

/**
 * Turns packets into bytes and back using Mojang's own protocol codecs.
 *
 * <p>The browser's singleplayer link originally handed {@code Packet} objects
 * straight from one {@code Connection} half to the other. That works while both
 * halves share a heap, but it is exactly what stops the integrated server from
 * moving into a Worker: Workers are separate realms with separate heaps and can
 * exchange only bytes. It also aliases mutable objects across the client/server
 * boundary, which vanilla never does — its local channel still runs packets
 * through the codec ({@code configureInMemoryPipeline} delegates to
 * {@code configureSerialization}).</p>
 *
 * <p>No Netty transport is involved. {@code ProtocolInfo.codec()} is a
 * {@code StreamCodec<ByteBuf, Packet<? super T>>} over a <em>plain</em>
 * {@code ByteBuf} — the registry-aware decorator is already baked in via
 * {@code mapStream} — so an {@code Unpooled} heap buffer is the whole
 * requirement. netty-buffer and netty-codec are on the image classpath; only
 * netty-transport, -handler and -resolver are excluded (they drag in
 * {@code sun.nio.ch} selectors and then virtual threads, which the wasm backend
 * cannot compile).</p>
 */
public final class PacketWire {
    private PacketWire() {
    }

    /**
     * Encodes one already-unbundled packet.
     *
     * <p>Bundles must be split by {@link net.minecraft.network.protocol.BundlerInfo}
     * before reaching this method, exactly as vanilla's {@code PacketBundlePacker}
     * does ahead of its encoder: a {@code BundlePacket} has no wire form of its
     * own, only a delimiter around its children.</p>
     */
    public static byte[] encode(final ProtocolInfo<?> protocol, final Packet<?> packet) {
        ByteBuf buffer = Unpooled.buffer();
        try {
            codecOf(protocol).encode(buffer, packet);
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return bytes;
        } finally {
            buffer.release();
        }
    }

    /**
     * Which part of the protocol plumbing is missing.
     *
     * <p>With no stack traces and a packet whose every field is populated, the
     * remaining suspects are the protocol's own pieces rather than the payload:
     * a null {@code bundlerInfo()} makes {@code unbundlePacket} throw before any
     * encoding happens, and a null {@code codec()} throws inside it. Reporting
     * both plus the codec's implementation class separates them in one run.</p>
     */
    public static String describeProtocol(final ProtocolInfo<?> protocol) {
        if (protocol == null) {
            return "protocol=null";
        }
        StringBuilder detail = new StringBuilder();
        try {
            detail.append("id=").append(protocol.id()).append(" flow=").append(protocol.flow());
        } catch (Throwable failure) {
            detail.append("id/flow-failed:").append(failure.getClass().getName());
        }
        try {
            Object bundler = protocol.bundlerInfo();
            detail.append(" bundlerInfo=")
                    .append(bundler == null ? "NULL" : bundler.getClass().getName());
        } catch (Throwable failure) {
            detail.append(" bundlerInfo-threw:").append(failure.getClass().getName());
        }
        try {
            Object codec = protocol.codec();
            detail.append(" codec=")
                    .append(codec == null ? "NULL" : codec.getClass().getName());
        } catch (Throwable failure) {
            detail.append(" codec-threw:").append(failure.getClass().getName());
        }
        return detail.toString();
    }

    /**
     * Innermost stack frames of a codec failure.
     *
     * <p>A codec is a deep tree of composed {@code StreamCodec}s, so the
     * exception type alone does not say which field failed — an unadorned
     * {@code NullPointerException} from somewhere inside a profile codec is
     * indistinguishable from a dozen other causes. The frames name the codec.</p>
     */
    public static String describeThrowSite(final Throwable failure, final int limit) {
        try {
            StackTraceElement[] trace = failure.getStackTrace();
            if (trace == null || trace.length == 0) {
                return "<no stack trace>";
            }
            StringBuilder frames = new StringBuilder();
            for (int i = 0; i < Math.min(limit, trace.length); i++) {
                if (i > 0) {
                    frames.append(" < ");
                }
                frames.append(trace[i]);
            }
            return frames.toString();
        } catch (Throwable dumpFailure) {
            return "<frames-failed:" + dumpFailure.getClass().getName() + ">";
        }
    }

    /** Decodes one packet previously produced by {@link #encode}. */
    public static Packet<?> decode(final ProtocolInfo<?> protocol, final byte[] bytes) {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            Packet<?> packet = codecOf(protocol).decode(buffer);
            if (buffer.isReadable()) {
                throw new IllegalStateException(
                        "Packet " + packet.getClass().getSimpleName() + " left "
                                + buffer.readableBytes() + " bytes unread"
                );
            }
            return packet;
        } finally {
            buffer.release();
        }
    }

    /**
     * The same unchecked hop Mojang makes in {@code Connection.genericsFtw}: the
     * protocol phase guarantees the packet belongs to this codec, and a mismatch
     * surfaces as a decode failure rather than being preventable by the type
     * system through a {@code ProtocolInfo<?>}.
     */
    @SuppressWarnings("unchecked")
    private static StreamCodec<ByteBuf, Packet<?>> codecOf(final ProtocolInfo<?> protocol) {
        return (StreamCodec<ByteBuf, Packet<?>>) (StreamCodec<ByteBuf, ?>) protocol.codec();
    }
}
