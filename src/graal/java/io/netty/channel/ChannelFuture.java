package io.netty.channel;

/**
 * Minimal browser stand-in for Netty's {@code ChannelFuture}.
 *
 * <p>Netty's transport JAR cannot be on the Web Image classpath: its selector
 * stack reaches {@code sun.nio.ch.Poller} and then {@code VirtualThread}, which
 * the wasm backend refuses to compile. This repository therefore excludes
 * {@code netty-transport} entirely and shadows the handful of its types that
 * survive in reachable Minecraft signatures.</p>
 *
 * <p>Only {@link #isSuccess()} and {@link #cause()} are ever called: the sole
 * producers of {@code ChannelFutureListener} in the reachable game are
 * {@code PacketSendListener.thenRun} and {@code exceptionallySend}, both of
 * which are shadowed alongside this type. Deliberately not related to
 * {@code io.netty.util.concurrent.Future}; nothing in the browser build needs
 * the promise machinery, and inheriting it would drag netty-common's executor
 * types into the image.</p>
 */
public interface ChannelFuture {
    boolean isSuccess();

    Throwable cause();
}
