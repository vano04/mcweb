package net.minecraft.server.network;

import java.net.InetAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;

/**
 * Classpath-first shadow of Mojang's connection listener.
 *
 * <p>The JAR's class binds Netty {@code ServerBootstrap}s — a TCP listener and a
 * {@code LocalServerChannel} for singleplayer — neither of which exists without
 * {@code netty-transport}. Only the memory channel matters in the browser, and
 * with {@code net.minecraft.network.Connection} also shadowed it reduces to
 * publishing a server-side half for the client to pair with.</p>
 *
 * <p>{@link #tick()} keeps vanilla's shape (skip connections still connecting,
 * tick live ones, reap dead ones) so the crash semantics of a failing memory
 * connection are unchanged.</p>
 */
public class ServerConnectionListener {
    private final MinecraftServer server;
    public volatile boolean running;
    private volatile UUID sessionId;
    private final List<Connection> connections = Collections.synchronizedList(new ArrayList<>());

    public ServerConnectionListener(final MinecraftServer server) {
        this.server = server;
        this.running = true;
    }

    /** The browser never opens a socket; published worlds are not reachable. */
    public void startTcpServerListener(final InetAddress address, final int port) {
    }

    public SocketAddress startMemoryChannel() {
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("local-channel:before-server-connection");
        try {
            Connection serverSide = new Connection(PacketFlow.SERVERBOUND);
            serverSide.setListenerForServerboundHandshake(
                    new MemoryServerHandshakePacketListenerImpl(this.server, serverSide)
            );
            this.connections.add(serverSide);
            SocketAddress address = Connection.startBrowserMemoryChannel(serverSide);
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("local-channel:published");
            return address;
        } catch (RuntimeException | Error failure) {
            dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                    "local-channel:startMemoryChannel",
                    failure.getClass().getName(),
                    String.valueOf(failure)
            );
            throw failure;
        }
    }

    /**
     * Drains inbound packets on every live connection without running the rest
     * of {@link #tick()}. The browser server pump calls this immediately before
     * {@code tickServer} so a packet is handled in the tick it arrived in
     * rather than one tick later.
     */
    public void drainInbound() {
        synchronized (this.connections) {
            for (Connection connection : this.connections) {
                if (connection.isConnected()) {
                    connection.drainInbound();
                }
            }
        }
    }

    // acceptChannel(Channel, UUID) is deliberately absent: it takes a Netty
    // transport type, and no class in the client JAR calls it.

    public void stop() {
        this.running = false;
    }

    public void stopTcpServerListener() {
    }

    public void tick() {
        synchronized (this.connections) {
            Iterator<Connection> iterator = this.connections.iterator();

            while (iterator.hasNext()) {
                Connection connection = iterator.next();
                if (connection.isConnecting()) {
                    continue;
                }

                if (connection.isConnected()) {
                    try {
                        connection.tick();
                    } catch (Exception failure) {
                        // Vanilla rethrows for memory connections rather than
                        // limping on, and so does this: a broken singleplayer
                        // link is the bug, not something to disconnect around.
                        dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                                "local-channel:tick",
                                failure.getClass().getName(),
                                String.valueOf(failure)
                        );
                        connection.setReadOnly();
                        throw failure;
                    }
                } else {
                    iterator.remove();
                    connection.handleDisconnection();
                }
            }
        }
    }

    public MinecraftServer getServer() {
        return this.server;
    }

    public List<Connection> getConnections() {
        return this.connections;
    }

    public UUID getSessionId() {
        UUID uuid = this.sessionId;
        if (uuid == null) {
            uuid = UUID.randomUUID();
            this.sessionId = uuid;
        }
        return uuid;
    }
}
