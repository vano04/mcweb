package io.netty.channel;

/**
 * Minimal browser stand-in for Netty's {@code ChannelFutureListener}; see
 * {@link ChannelFuture} for why netty-transport cannot be on the image
 * classpath.
 *
 * <p>Real Netty declares this as {@code GenericFutureListener<ChannelFuture>},
 * so a lambda compiled against it carries the erased SAM descriptor
 * {@code (Lio/netty/util/concurrent/Future;)V}. Nothing in the reachable game
 * creates such a lambda directly — every listener comes from the shadowed
 * {@code net.minecraft.network.PacketSendListener} — so this interface declares
 * its own SAM and stays free of netty-common.</p>
 */
@FunctionalInterface
public interface ChannelFutureListener {
    void operationComplete(ChannelFuture future) throws Exception;
}
