package net.minecraft.network;

import com.mojang.logging.LogUtils;
import io.netty.channel.ChannelFutureListener;
import java.util.function.Supplier;
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;

/**
 * Classpath-first shadow of Mojang's send-listener factory.
 *
 * <p>The JAR's version builds lambdas whose failure branch reaches into the
 * Netty pipeline ({@code future.channel().pipeline().fireExceptionCaught}),
 * which the browser transport does not have. Shadowing it here is what lets
 * {@code io.netty.channel.ChannelFutureListener} stay a minimal interface:
 * these two factories are the only producers of listeners in the reachable
 * game (verified by scanning every constant pool in the client JAR).</p>
 *
 * <p>Semantics are preserved for the success path, which is the only path a
 * local in-memory connection takes: {@code thenRun}'s runnable always runs.
 * The failure branches only log — a browser send fails solely when the peer is
 * already gone, so both re-sending the fallback packet and firing a pipeline
 * exception would fail too.</p>
 */
public class PacketSendListener {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static ChannelFutureListener thenRun(final Runnable runnable) {
        return future -> {
            runnable.run();
            if (!future.isSuccess()) {
                LOGGER.warn("Browser packet send failed", future.cause());
            }
        };
    }

    public static ChannelFutureListener exceptionallySend(final Supplier<Packet<?>> handler) {
        return future -> {
            if (!future.isSuccess()) {
                Packet<?> fallback = handler.get();
                LOGGER.warn(
                        "Failed to deliver packet, fallback {}",
                        fallback == null ? "none" : fallback.type(),
                        future.cause()
                );
            }
        };
    }
}
