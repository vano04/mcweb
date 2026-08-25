package net.minecraft.network;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import net.minecraft.SharedConstants;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.handshake.ClientIntent;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.ClientLoginPacketListener;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.ClientStatusPacketListener;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.RunningOnDifferentThreadException;
import net.minecraft.util.Mth;
import net.minecraft.util.debugchart.LocalSampleLogger;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * Classpath-first shadow of Mojang's {@code Connection} for the browser's
 * in-memory singleplayer link.
 *
 * <p>The JAR's class extends {@code io.netty.channel.SimpleChannelInboundHandler}
 * and drives every send through a Netty pipeline. {@code netty-transport} cannot
 * be on the Web Image classpath — adding it expands analysis into
 * {@code sun.nio.ch} selectors, {@code Poller} and finally
 * {@code VirtualThread.runContinuation}, which the wasm backend cannot compile
 * (measured: build fails with {@code Target_java_lang_Thread.isInterrupted() is
 * not available}). The previous approach — a JDK proxy implementing Netty's
 * {@code Channel} injected into the JAR's Connection by reflection — could not
 * work either, because the JAR class itself fails to link without its
 * superclass.</p>
 *
 * <p>So the transport is replaced rather than emulated. Two paired instances
 * exchange {@code Packet} objects through queues drained cooperatively on the
 * browser's single thread: {@code Minecraft.tick} (via {@code pendingConnection}
 * before the level exists, then {@code MultiPlayerGameMode.tick}) drives the
 * client side, and {@code ServerConnectionListener.tick} inside
 * {@code MinecraftServer.tickServer} drives the server side. Everything above
 * the transport — protocol phases, packet types, listeners, the handshake and
 * login state machines — stays Mojang's code.</p>
 *
 * <p><b>Packets cross as bytes.</b> Each send is encoded with
 * {@code ProtocolInfo.codec()} over an {@code Unpooled} buffer and decoded on
 * the far side (see {@link dev.mcweb.graal.net.PacketWire}), which is what
 * vanilla's local channel does too — {@code configureInMemoryPipeline}
 * delegates to {@code configureSerialization}. So client and server never share
 * object graphs, and no Netty transport is needed for it. This also leaves the
 * peer lookup in {@code transfer} as the only remaining tie to a single heap:
 * swap it for a {@code postMessage} and the server half can run in a Worker.</p>
 *
 * <p>Members the browser never reaches are deliberately absent rather than
 * stubbed: {@code connectToServer}, {@code connect}, {@code fromChannel},
 * {@code configureSerialization}, {@code configureInMemoryPipeline} and
 * {@code configurePacketHandler} all take or return Netty transport types, and
 * every caller of them ({@code ConnectScreen}, {@code ServerStatusPinger},
 * {@code RealmsConnect}, the TCP listener) belongs to the multiplayer path.</p>
 */
public class Connection {
    private static final float AVERAGE_PACKETS_SMOOTHING = 0.75F;
    private static final org.slf4j.Logger LOGGER = com.mojang.logging.LogUtils.getLogger();

    public static final Marker ROOT_MARKER = MarkerFactory.getMarker("NETWORK");
    public static final Marker PACKET_MARKER = MarkerFactory.getMarker("NETWORK_PACKETS");
    public static final Marker PACKET_RECEIVED_MARKER = MarkerFactory.getMarker("PACKET_RECEIVED");
    public static final Marker PACKET_SENT_MARKER = MarkerFactory.getMarker("PACKET_SENT");

    /**
     * Vanilla holds {@code HandshakeProtocols.SERVERBOUND} here and uses it
     * both to seed the pipeline codec and to check the initial listener. With
     * serialization gone only the check remains, so this names the phase
     * directly and keeps {@code HandshakeProtocols}' codec construction out of
     * this class's static initializer.
     */
    private static final ConnectionProtocol INITIAL_PROTOCOL = ConnectionProtocol.HANDSHAKING;

    /** Stand-in for Netty's {@code LocalAddress}; only its string form is used. */
    private static final SocketAddress LOCAL_ADDRESS = new SocketAddress() {
        @Override
        public String toString() {
            return "browser-local";
        }
    };

    private static final ChannelFuture SUCCESS = new ChannelFuture() {
        @Override
        public boolean isSuccess() {
            return true;
        }

        @Override
        public Throwable cause() {
            return null;
        }
    };

    private static final ChannelFuture FAILURE = new ChannelFuture() {
        @Override
        public boolean isSuccess() {
            return false;
        }

        @Override
        public Throwable cause() {
            return new java.nio.channels.ClosedChannelException();
        }
    };

    /**
     * The server-side half created by {@code ServerConnectionListener
     * .startMemoryChannel}, waiting for {@link #connectToLocalServer} to pair
     * with it. Vanilla achieves the same rendezvous through a bound
     * {@code LocalServerChannel} and a child-handler initializer.
     */
    private static volatile Connection pendingServerSide;

    /** The client half of the singleplayer link, for the frame-pump drain. */
    private static Connection browserClient;
    /** Cross-realm endpoints; each field exists in only its owning image heap. */
    private static Connection workerClientEndpoint;
    private static Connection workerServerEndpoint;

    /**
     * Bring-up diagnostics: the login and configuration handshake is a couple
     * of dozen packets, so reporting the first few of each is enough to see
     * exactly where a stalled world load stops, and small enough not to flood.
     */
    private static final int PACKET_REPORT_LIMIT = 400;
    private static int reportedSends;
    private static int reportedHandles;

    private static String directionLabel(final PacketFlow flow) {
        return flow == PacketFlow.SERVERBOUND ? "C->S" : "S->C";
    }

    /**
     * Gameplay event timeline: unlike the 400-marker bring-up ring above,
     * this one is unlimited and narrow to the handful of packet types that
     * bracket a block-break/item round trip. Superflat mining produces a few
     * of these per second, never hundreds, so the stage ring stays readable.
     * Marker shape: {@code evt:<PacketName> <C->S|S->C>} — the test polls the
     * stage ring and times first appearance with its own clock.
     */
    private static boolean isGameplayEvent(String packetName) {
        switch (packetName) {
            case "ServerboundPlayerActionPacket":
            case "ClientboundBlockChangedAckPacket":
            case "ClientboundAddEntityPacket":
            case "ClientboundTakeItemEntityPacket":
            case "ClientboundSectionBlocksUpdatePacket":
            case "ClientboundBlockUpdatePacket":
            case "ClientboundBundlePacket":
            case "ClientboundLevelChunkWithLightPacket":
            case "ClientboundPlayerCombatKillPacket":
            case "ServerboundAttackPacket":
            case "ClientboundHurtAnimationPacket":
            case "ClientboundSetHealthPacket":
            case "ClientboundSetEntityMotionPacket":
            case "ClientboundTeleportEntityPacket":
            case "ServerboundPlayerLoadedPacket":
            case "ServerboundClientCommandPacket":
            case "ClientboundEntityEventPacket":
            case "ClientboundDamageEventPacket":
                return true;
            default:
                return false;
        }
    }

    private static void noteGameplayEvent(Packet<?> packet, String direction) {
        if (packet instanceof net.minecraft.network.protocol.BundlePacket<?> bundle) {
            // 26.2 wraps entity pairing — including item-entity spawns — in
            // ClientboundBundlePacket. Without unwrapping, the wire-level
            // marker only ever sees the wrapper and hides when the inner
            // AddEntityPacket/TakeItemEntityPacket actually crosses.
            for (Packet<?> sub : bundle.subPackets()) {
                noteGameplayEvent(sub, direction);
            }
            return;
        }
        String name = packet.getClass().getSimpleName();
        if (!isGameplayEvent(name)) {
            return;
        }
        String suffix = "";
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundAddEntityPacket add) {
            // Entity re-pairing floods the ring with non-item spawns; a
            // type suffix lets probes count only the dropped item's spawn.
            try {
                suffix = " " + net.minecraft.world.entity.EntityType.getKey(add.getType()).getPath();
            } catch (Throwable ignored) {
                // Diagnostic marker; a registry miss must not disturb play.
            }
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundLevelChunkWithLightPacket chunkPacket) {
            // Position suffix: which chunk crossed the wire. Duplicate sends
            // of one chunk are the stomp carrier; the suffix makes them
            // countable per position.
            suffix = " " + chunkPacket.getX() + "," + chunkPacket.getZ();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ServerboundInteractPacket interact) {
            // Interaction round trip (right-click use / older builds).
            suffix = " entity=" + interact.entityId();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ServerboundAttackPacket attack) {
            // 26.2 split entity attacking out of ServerboundInteractPacket
            // into its own packet; probes measuring the attack round trip
            // must count this one, not the interact packet.
            suffix = " entity=" + attack.entityId();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundHurtAnimationPacket hurt) {
            suffix = " id=" + hurt.id();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundSetEntityMotionPacket motion) {
            suffix = " id=" + motion.id();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundTeleportEntityPacket teleport) {
            suffix = " id=" + teleport.id();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ServerboundClientCommandPacket command) {
            // Respawn handshake — the other half of the client-loaded gate.
            suffix = " action=" + command.getAction();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundEntityEventPacket entityEvent) {
            // Mob hurt feedback travels as event byte 2 on this packet, NOT
            // as ClientboundHurtAnimationPacket (that one is player-only).
            suffix = " event=" + entityEvent.getEventId();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundDamageEventPacket damage) {
            // 26.2 mob hurt feedback: damage events carry the entity id.
            // This is the packet the user experiences as "the hit landed".
            suffix = " entity=" + damage.entityId();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ServerboundPlayerActionPacket action) {
            // The dig itself, with the position, so a break can be matched
            // against whatever the server later says about that same block.
            net.minecraft.core.BlockPos pos = action.getPos();
            suffix = " action=" + action.getAction()
                    + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
        }
        if (packet instanceof net.minecraft.network.protocol.game
                .ClientboundBlockUpdatePacket update) {
            // The other half: a break that "does not register" is this packet
            // arriving for the dug position carrying a non-air block again.
            net.minecraft.core.BlockPos pos = update.getPos();
            suffix = " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ()
                    + " block=" + net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(update.getBlockState().getBlock()).getPath();
        }
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket kill) {
            // What killed the player — the superflat-survival spawn-death
            // probe needs the cause, and this packet is the only seam that
            // carries it client-side.
            String message;
            try {
                message = kill.message().getString().replace('\n', ' ');
            } catch (Throwable ignored) {
                message = "?";
            }
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "evt:" + name + " " + direction + " " + message);
            return;
        }
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                "evt:" + name + " " + direction + suffix);
    }

    /** True when frames leave through the relay rather than a Worker port. */
    private boolean remoteTransport;
    private static volatile Connection remoteClientEndpoint;

    private final PacketFlow receiving;
    private final Queue<Consumer<Connection>> pendingActions = new ConcurrentLinkedQueue<>();
    /**
     * Pending inbound entries: either an encoded {@code byte[]} frame or, for
     * the pre-protocol handshake window, a {@code Packet} passed by reference.
     *
     * <p>Frames stay encoded until {@link #drainInbound} reads them. Decoding
     * them eagerly at send time was wrong: handling one packet can switch this
     * side's protocol phase, and later frames must be decoded with the phase in
     * effect when they are <em>read</em>. Doing it early made the client encode
     * with CONFIGURATION while the server still decoded with LOGIN, which
     * surfaced as "Failed to decode packet 'serverbound/minecraft:hello'".
     * Vanilla has the same property for free, because bytes sit in the channel
     * until the decoder pulls them.</p>
     */
    private final Queue<Object> inbound = new ConcurrentLinkedQueue<>();

    private volatile Connection peer;
    private boolean workerTransport;
    private volatile boolean open;
    private volatile boolean readOnly;
    private volatile SocketAddress address;

    private ProtocolInfo<?> inboundProtocol;
    private ProtocolInfo<?> outboundProtocol;
    private net.minecraft.network.protocol.BundlerInfo.Bundler inboundBundler;

    private volatile boolean sendLoginDisconnect = true;
    private volatile PacketListener disconnectListener;
    private volatile PacketListener packetListener;
    private DisconnectionDetails disconnectionDetails;
    private DisconnectionDetails delayedDisconnect;
    private boolean disconnectionHandled;
    private int receivedPackets;
    private int sentPackets;
    private float averageReceivedPackets;
    private float averageSentPackets;
    private int tickCount;
    private UUID intendedProfileId;

    public Connection(final PacketFlow receiving) {
        this.receiving = receiving;
    }

    // ---------------------------------------------------------------- pairing

    /**
     * Publishes the server half so the next {@link #connectToLocalServer} call
     * pairs with it. Called by the browser {@code ServerConnectionListener}.
     */
    public static SocketAddress startBrowserMemoryChannel(final Connection serverSide) {
        pendingServerSide = serverSide;
        return LOCAL_ADDRESS;
    }

    public static Connection connectToLocalServer(final SocketAddress address) {
        Connection serverSide = pendingServerSide;
        pendingServerSide = null;
        if (serverSide == null) {
            throw new IllegalStateException(
                    "No browser memory channel is awaiting a client; "
                            + "startMemoryChannel() must run first"
            );
        }

        Connection clientSide = new Connection(PacketFlow.CLIENTBOUND);
        // Both halves must be open before either flushes its pending actions:
        // a queued send on the first half would otherwise find the second half
        // still closed and be dropped as undeliverable.
        clientSide.pairWith(serverSide);
        serverSide.pairWith(clientSide);
        clientSide.onConnected();
        serverSide.onConnected();
        browserClient = clientSide;
        return clientSide;
    }

    /**
     * Opens the client half against a real server, through the WebSocket relay.
     *
     * <p>Byte-for-byte the Worker path, with a different pipe: the relay speaks
     * whole packet bodies, which is exactly what {@link
     * dev.mcweb.graal.net.PacketWire} already produces, so the codec, the
     * protocol phases and the listener chain are all vanilla's.</p>
     */
    public static Connection connectToRemoteServer() {
        if (!dev.mcweb.graal.BrowserRemoteTransport.isAvailable()) {
            throw new IllegalStateException("Browser remote transport is unavailable");
        }
        Connection clientSide = new Connection(PacketFlow.CLIENTBOUND);
        clientSide.remoteTransport = true;
        clientSide.address = LOCAL_ADDRESS;
        clientSide.open = true;
        remoteClientEndpoint = clientSide;
        dev.mcweb.graal.BrowserRemoteTransport.onPacket(Connection::receiveRemoteClientFrame);
        clientSide.onConnected();
        return clientSide;
    }

    /**
     * Detaches one finished browser connection from the static WebSocket sink.
     *
     * <p>The desktop channel owns its listener lifetime.  The browser transport
     * has one static callback instead, so retaining an old endpoint after the
     * player quits lets a later WebSocket deliver its first login packet to the
     * previous protocol state.  Clear only the expected endpoint: a late cleanup
     * from the old screen must never detach a newer connection.</p>
     */
    public static void releaseRemoteClient(final Connection expected) {
        if (remoteClientEndpoint == expected) {
            remoteClientEndpoint = null;
        }
    }

    private static void receiveRemoteClientFrame(final byte[] frame) {
        Connection endpoint = remoteClientEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("Remote client endpoint is not attached");
        }
        endpoint.inbound.add(frame);
    }

    /** Drains one frame's worth of packets from the relay. */
    public static void drainBrowserRemote() {
        Connection endpoint = remoteClientEndpoint;
        if (endpoint == null) return;
        dev.mcweb.graal.BrowserRemoteTransport.drainInbound();
        endpoint.tick();
        String transportState = dev.mcweb.graal.BrowserRemoteTransport.state();
        if (endpoint.isConnected()
                && ("closed".equals(transportState) || "error".equals(transportState))) {
            String reason = dev.mcweb.graal.BrowserRemoteTransport.lastError();
            if (reason == null || reason.isBlank()) {
                reason = "Minecraft gateway connection closed";
            }
            endpoint.disconnect(Component.literal(reason));
            // Netty would deliver channelInactive immediately after the socket
            // closes. Give the vanilla listener the same frame-boundary event
            // so ConnectingScreen cannot remain open forever.
            endpoint.tick();
        }
    }

    /** Opens the client half against the server Worker's MessagePort. */
    public static Connection connectToWorkerServer() {
        if (!dev.mcweb.graal.BrowserWorkerClientTransport.isAvailable()) {
            throw new IllegalStateException("Browser server Worker transport is unavailable");
        }
        Connection clientSide = new Connection(PacketFlow.CLIENTBOUND);
        clientSide.workerTransport = true;
        clientSide.address = LOCAL_ADDRESS;
        clientSide.open = true;
        workerClientEndpoint = clientSide;
        browserClient = clientSide;
        dev.mcweb.graal.BrowserWorkerClientTransport.onPacket(Connection::receiveWorkerClientFrame);
        clientSide.onConnected();
        return clientSide;
    }

    /** Attaches the server half created by startMemoryChannel to its Worker port. */
    public static void attachPendingWorkerServer() {
        Connection serverSide = pendingServerSide;
        pendingServerSide = null;
        if (serverSide == null) {
            throw new IllegalStateException("No server connection is waiting for the Worker transport");
        }
        serverSide.workerTransport = true;
        serverSide.inboundProtocol = HandshakeProtocols.SERVERBOUND;
        serverSide.address = LOCAL_ADDRESS;
        serverSide.open = true;
        workerServerEndpoint = serverSide;
        serverSide.onConnected();
    }

    private static void receiveWorkerClientFrame(final byte[] frame) {
        Connection endpoint = workerClientEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("Client Worker endpoint is not attached");
        }
        endpoint.inbound.add(frame);
    }

    public static void receiveWorkerServerFrame(final byte[] frame) {
        Connection endpoint = workerServerEndpoint;
        if (endpoint == null) {
            throw new IllegalStateException("Server Worker endpoint is not attached");
        }
        endpoint.inbound.add(frame);
    }

    private void pairWith(final Connection newPeer) {
        this.peer = newPeer;
        this.address = LOCAL_ADDRESS;
        this.open = true;
    }

    /** Mirrors what Netty's {@code channelActive} does on the real transport. */
    private void onConnected() {
        if (this.delayedDisconnect != null) {
            this.disconnect(this.delayedDisconnect);
        }
        this.flushQueue();
    }

    /**
     * Drains the client half from the frame pump.
     *
     * <p>Minecraft only ticks this connection through {@code pendingConnection}
     * while there is no level, and through {@code MultiPlayerGameMode.tick}
     * once there is — and the latter is skipped while the game is paused. On
     * the desktop a Netty thread keeps delivering regardless; draining at the
     * frame boundary restores that, and does it at the same safe point where
     * Minecraft's own task queue runs.</p>
     */
    public static void drainBrowserClient() {
        Connection client = browserClient;
        if (client != null && client.isConnected()) {
            client.drainInbound();
        }
    }

    // ------------------------------------------------------------ delivery

    /**
     * Hands queued packets to the listener. Split out of {@link #tick()} so the
     * server pump can drain before {@code tickServer} rather than in the middle
     * of it, keeping packet handling in the same tick it arrived.
     */
    public void drainInbound() {
        if (this.readOnly) {
            return;
        }

        Object entry;
        while (this.open && (entry = this.inbound.poll()) != null) {
            Packet<?> packet;
            if (entry instanceof byte[] frame) {
                // Decoded here, not at send time, so a phase switch performed
                // while handling an earlier packet applies to this one.
                packet = this.decodeFrame(frame);
                if (packet == null) {
                    // Mid-bundle: the bundler is still collecting children.
                    continue;
                }
            } else {
                packet = (Packet<?>) entry;
            }

            // Re-read every iteration: handling a packet can switch protocol
            // phase and install a different listener for the rest of the queue.
            PacketListener listener = this.packetListener;
            if (listener == null) {
                throw new IllegalStateException("Received a packet before the packet listener was initialized");
            }

            if (!listener.shouldHandleMessage(packet)) {
                continue;
            }

            if (reportedHandles < PACKET_REPORT_LIMIT) {
                reportedHandles++;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "packet:handle " + directionLabel(this.receiving)
                                + " " + packet.getClass().getSimpleName()
                                + " by " + listener.getClass().getSimpleName()
                );
            }
            noteGameplayEvent(packet, directionLabel(this.receiving));

            try {
                genericsFtw(packet, listener);
            } catch (RunningOnDifferentThreadException deferred) {
                // The listener re-queued it onto its own PacketProcessor.
            } catch (RejectedExecutionException shuttingDown) {
                this.disconnect(Component.translatable("multiplayer.disconnect.server_shutdown"));
            } catch (ClassCastException wrongPhase) {
                this.disconnect(Component.translatable("multiplayer.disconnect.invalid_packet"));
            }

            this.receivedPackets++;
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends PacketListener> void genericsFtw(final Packet<T> packet, final PacketListener listener) {
        // Same unchecked hop as Mojang's method of the same name: the protocol
        // phase guarantees the pairing, and a mismatch surfaces as the
        // ClassCastException the drain loop already handles.
        packet.handle((T) listener);
    }

    // ------------------------------------------------------------- protocol

    private void validateListener(final ProtocolInfo<?> protocol, final PacketListener packetListener) {
        Objects.requireNonNull(packetListener, "packetListener");
        PacketFlow listenerFlow = packetListener.flow();
        if (listenerFlow != this.receiving) {
            throw new IllegalStateException(
                    "Trying to set listener for wrong side: connection is " + this.receiving
                            + ", but listener is " + listenerFlow
            );
        }

        ConnectionProtocol listenerProtocol = packetListener.protocol();
        if (protocol.id() != listenerProtocol) {
            throw new IllegalStateException(
                    "Listener protocol (" + listenerProtocol + ") does not match requested one " + protocol
            );
        }
    }

    public <T extends PacketListener> void setupInboundProtocol(final ProtocolInfo<T> protocol, final T packetListener) {
        this.validateListener(protocol, packetListener);
        if (protocol.flow() != this.getReceiving()) {
            throw new IllegalStateException("Invalid inbound protocol: " + protocol.id());
        }

        this.packetListener = packetListener;
        this.disconnectListener = null;
        // Retained so this half can decode; a protocol switch also abandons any
        // half-received bundle, which cannot span a phase change.
        this.inboundProtocol = protocol;
        this.inboundBundler = null;
        dev.mcweb.graal.BrowserRemoteTransport.publishKeepAliveId(protocol);
    }

    public void setupOutboundProtocol(final ProtocolInfo<?> protocol) {
        if (protocol.flow() != this.getSending()) {
            throw new IllegalStateException("Invalid outbound protocol: " + protocol.id());
        }

        // Vanilla pushes this through the pipeline so it lands in order with
        // pending writes; with a direct queue the switch is already ordered.
        this.sendLoginDisconnect = protocol.id() == ConnectionProtocol.LOGIN;
        this.outboundProtocol = protocol;
        dev.mcweb.graal.BrowserRemoteTransport.publishKeepAliveId(protocol);
    }

    public void setListenerForServerboundHandshake(final PacketListener packetListener) {
        if (this.packetListener != null) {
            throw new IllegalStateException("Listener already set");
        }

        if (this.receiving == PacketFlow.SERVERBOUND
                && packetListener.flow() == PacketFlow.SERVERBOUND
                && packetListener.protocol() == INITIAL_PROTOCOL) {
            this.packetListener = packetListener;
        } else {
            throw new IllegalStateException("Invalid initial listener");
        }
    }

    public void initiateServerboundStatusConnection(
            final String hostName, final int port, final ClientStatusPacketListener listener
    ) {
        this.initiateServerboundConnection(
                hostName, port, StatusProtocols.SERVERBOUND, StatusProtocols.CLIENTBOUND, listener, ClientIntent.STATUS
        );
    }

    public void initiateServerboundPlayConnection(
            final String hostName, final int port, final ClientLoginPacketListener listener
    ) {
        this.initiateServerboundConnection(
                hostName, port, LoginProtocols.SERVERBOUND, LoginProtocols.CLIENTBOUND, listener, ClientIntent.LOGIN
        );
    }

    public <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundPlayConnection(
            final String hostName,
            final int port,
            final ProtocolInfo<S> outbound,
            final ProtocolInfo<C> inbound,
            final C listener,
            final boolean transfer
    ) {
        this.initiateServerboundConnection(
                hostName, port, outbound, inbound, listener, transfer ? ClientIntent.TRANSFER : ClientIntent.LOGIN
        );
    }

    private <S extends ServerboundPacketListener, C extends ClientboundPacketListener> void initiateServerboundConnection(
            final String hostName,
            final int port,
            final ProtocolInfo<S> outbound,
            final ProtocolInfo<C> inbound,
            final C listener,
            final ClientIntent intent
    ) {
        if (outbound.id() != inbound.id()) {
            throw new IllegalStateException("Mismatched initial protocols");
        }

        this.disconnectListener = listener;
        this.runOnceConnected(connection -> {
            this.setupInboundProtocol(inbound, listener);
            connection.sendPacket(
                    new ClientIntentionPacket(
                            SharedConstants.getCurrentVersion().protocolVersion(), hostName, port, intent
                    ),
                    null,
                    true
            );
            this.setupOutboundProtocol(outbound);
        });
    }

    // ----------------------------------------------------------------- send

    public void send(final Packet<?> packet) {
        this.send(packet, null);
    }

    public void send(final Packet<?> packet, final ChannelFutureListener listener) {
        this.send(packet, listener, true);
    }

    public void send(final Packet<?> packet, final ChannelFutureListener listener, final boolean flush) {
        if (this.isConnected()) {
            this.flushQueue();
            this.sendPacket(packet, listener, flush);
        } else {
            this.pendingActions.add(connection -> connection.sendPacket(packet, listener, flush));
        }
    }

    public void runOnceConnected(final Consumer<Connection> action) {
        if (this.isConnected()) {
            this.flushQueue();
            action.accept(this);
        } else {
            this.pendingActions.add(action);
        }
    }

    private void sendPacket(final Packet<?> packet, final ChannelFutureListener listener, final boolean flush) {
        this.sentPackets++;
        Connection target = this.peer;
        boolean delivered = this.open
                && (this.workerTransport || this.remoteTransport
                        || (target != null && target.open));
        if (delivered) {
            this.transfer(target, packet);
        }

        if (reportedSends < PACKET_REPORT_LIMIT) {
            reportedSends++;
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "packet:send " + directionLabel(this.getSending())
                            + " " + packet.getClass().getSimpleName()
                            + (delivered ? "" : " DROPPED(peer closed)")
            );
        }
        noteGameplayEvent(packet, directionLabel(this.getSending()));
        if (delivered
                && packet instanceof net.minecraft.network.protocol.game
                        .ClientboundLevelChunkWithLightPacket chunkPacket) {
            // Ground truth for the chunk reconciler: this chunk is now known
            // to the client, so it must never be re-admitted as "missing".
            dev.mcweb.graal.BrowserIntegratedServerCompat.noteChunkSentOnWire(
                    chunkPacket.getX(), chunkPacket.getZ());
        }

        if (listener != null) {
            try {
                listener.operationComplete(delivered ? SUCCESS : FAILURE);
            } catch (Exception listenerFailure) {
                // Netty would route this to exceptionCaught rather than let it
                // unwind into the tick that happened to send the packet.
                LOGGER.warn("Browser send listener failed", listenerFailure);
            }
        }
    }

    /**
     * Moves one packet to the paired half as bytes.
     *
     * <p>Vanilla's local channel serializes too, so this restores its semantics
     * rather than departing from them: client and server stop sharing object
     * graphs, and a mutable object hanging off a packet (an {@code ItemStack} in
     * entity sync data, say) is no longer the same instance on both sides.</p>
     *
     * <p>It is also the prerequisite for hosting the server in a Worker. Once
     * every packet is already a {@code byte[]} at the boundary, the peer lookup
     * below is the only thing tying the two halves to one heap — replace it with
     * a {@code postMessage} and the server can live in another realm.</p>
     *
     * <p>The local path can pass the initial handshake object by reference
     * before an outbound protocol is installed. A Worker cannot, so that one
     * packet is encoded explicitly with {@link HandshakeProtocols#SERVERBOUND}.
     */
    private void transfer(final Connection target, final Packet<?> packet) {
        ProtocolInfo<?> outbound = this.outboundProtocol;
        if (outbound == null) {
            if (!this.workerTransport && !this.remoteTransport) {
                target.inbound.add(packet);
                return;
            }
            if (!(packet instanceof ClientIntentionPacket)) {
                throw new IllegalStateException(
                        "Only the initial intention packet may precede an outbound protocol"
                );
            }
            this.deliverFrame(target, dev.mcweb.graal.net.PacketWire.encode(
                    HandshakeProtocols.SERVERBOUND,
                    packet
            ));
            return;
        }

        try {
            // A BundlePacket has no wire form of its own -- only a delimiter
            // around its children -- so it must be split before encoding, the
            // same way PacketBundlePacker sits ahead of vanilla's encoder.
            //
            // Only the PLAY protocol has a bundler: measured, LOGIN reports
            // bundlerInfo=NULL, and calling unbundlePacket on that threw an
            // NPE before any encoding happened, stalling the handshake at
            // ClientboundLoginFinishedPacket. Phases without bundling send the
            // packet as a single frame.
            net.minecraft.network.protocol.BundlerInfo bundler = outbound.bundlerInfo();
            if (bundler == null) {
                this.deliverFrame(
                        target,
                        dev.mcweb.graal.net.PacketWire.encode(outbound, packet)
                );
            } else {
                bundler.unbundlePacket(packet, single -> this.deliverFrame(
                        target,
                        dev.mcweb.graal.net.PacketWire.encode(outbound, single)
                ));
            }
        } catch (Throwable failure) {
            // Dropping one packet silently would strand the handshake with no
            // clue why; report it and let the phase fail loudly instead.
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "packet:codec-failed " + packet.getClass().getSimpleName()
                            + " " + failure.getClass().getName()
                            + ":" + failure.getMessage()
                            // Web Image populates no stack traces at all
                            // (measured: every getStackTrace() here is empty),
                            // so the throw site is unavailable and the packet's
                            // own contents are the only way to find which field
                            // the codec choked on. Packets are records, so the
                            // generated toString names a null outright.
                            + " packet=" + describeSafely(packet)
                            + " out[" + dev.mcweb.graal.net.PacketWire.describeProtocol(outbound) + "]"
            );
            throw failure;
        }
    }

    private void deliverFrame(final Connection target, final byte[] frame) {
        if (this.remoteTransport) {
            dev.mcweb.graal.BrowserRemoteTransport.send(frame);
            return;
        }
        if (!this.workerTransport) {
            target.inbound.add(frame);
            return;
        }
        if (dev.mcweb.graal.BrowserWorkerTransport.isAvailable()) {
            dev.mcweb.graal.BrowserWorkerTransport.send(frame);
            return;
        }
        if (dev.mcweb.graal.BrowserWorkerClientTransport.isAvailable()) {
            dev.mcweb.graal.BrowserWorkerClientTransport.send(frame);
            return;
        }
        throw new IllegalStateException("No cross-realm packet transport is available");
    }

    /** toString of a packet that is already failing; must not throw itself. */
    private static String describeSafely(final Packet<?> packet) {
        try {
            return String.valueOf(packet);
        } catch (Throwable describeFailure) {
            return packet.getClass().getSimpleName()
                    + "(toString failed: " + describeFailure.getClass().getName() + ")";
        }
    }

    /**
     * Decodes one frame with the protocol currently in effect on this side.
     *
     * <p>Returns {@code null} while a bundle is still being collected. The
     * re-bundling mirrors {@code PacketBundleUnpacker}: a delimiter opens a
     * bundler that swallows packets until it returns the assembled bundle.</p>
     */
    private Packet<?> decodeFrame(final byte[] frame) {
        ProtocolInfo<?> protocol = this.inboundProtocol;
        if (protocol == null) {
            throw new IllegalStateException(
                    "Received an encoded frame before an inbound protocol was set"
            );
        }

        Packet<?> packet;
        try {
            packet = dev.mcweb.graal.net.PacketWire.decode(protocol, frame);
        } catch (Throwable failure) {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "packet:decode-failed " + failure.getClass().getName()
                            + ":" + failure.getMessage()
                            + " in[" + dev.mcweb.graal.net.PacketWire.describeProtocol(protocol) + "]"
            );
            throw failure;
        }

        if (this.inboundBundler != null) {
            Packet<?> bundle = this.inboundBundler.addPacket(packet);
            if (bundle == null) {
                return null;
            }
            this.inboundBundler = null;
            return bundle;
        }

        net.minecraft.network.protocol.BundlerInfo bundlerInfo = protocol.bundlerInfo();
        if (bundlerInfo == null) {
            // No bundling in this phase (LOGIN, HANDSHAKING); see transfer().
            return packet;
        }

        net.minecraft.network.protocol.BundlerInfo.Bundler bundler =
                bundlerInfo.startPacketBundling(packet);
        if (bundler != null) {
            this.inboundBundler = bundler;
            return null;
        }
        return packet;
    }

    /** No-op: a direct queue has nothing buffered to push. Kept for the ABI. */
    public void flushChannel() {
    }

    private void flushQueue() {
        if (this.isConnected()) {
            synchronized (this.pendingActions) {
                Consumer<Connection> pendingAction;
                while ((pendingAction = this.pendingActions.poll()) != null) {
                    pendingAction.accept(this);
                }
            }
        }
    }

    // ----------------------------------------------------------------- tick

    public void tick() {
        this.flushQueue();
        this.drainInbound();
        if (this.packetListener instanceof TickablePacketListener tickable) {
            tickable.tick();
        }

        if (!this.isConnected() && !this.disconnectionHandled) {
            this.handleDisconnection();
        }

        if (this.tickCount++ % 20 == 0) {
            this.tickSecond();
        }
    }

    protected void tickSecond() {
        this.averageSentPackets = Mth.lerp(AVERAGE_PACKETS_SMOOTHING, this.sentPackets, this.averageSentPackets);
        this.averageReceivedPackets =
                Mth.lerp(AVERAGE_PACKETS_SMOOTHING, this.receivedPackets, this.averageReceivedPackets);
        this.sentPackets = 0;
        this.receivedPackets = 0;
    }

    // ----------------------------------------------------------- lifecycle

    public SocketAddress getRemoteAddress() {
        return this.address;
    }

    public String getLoggableAddress(final boolean logIPs) {
        if (this.address == null) {
            return "local";
        }
        return logIPs ? this.address.toString() : "IP hidden";
    }

    public void disconnect(final Component reason) {
        this.disconnect(new DisconnectionDetails(reason));
    }

    public void disconnect(final DisconnectionDetails details) {
        if (this.peer == null) {
            this.delayedDisconnect = details;
        }

        if (this.isConnected()) {
            this.open = false;
            this.disconnectionDetails = details;
            Connection other = this.peer;
            if (other != null) {
                other.onPeerClosed();
            }
        }
    }

    /** Netty would deliver {@code channelInactive} to the surviving half. */
    private void onPeerClosed() {
        if (this.open) {
            this.open = false;
            if (this.disconnectionDetails == null) {
                this.disconnectionDetails =
                        new DisconnectionDetails(Component.translatable("disconnect.endOfStream"));
            }
        }
    }

    public void handleDisconnection() {
        if (!this.open) {
            if (this.disconnectionHandled) {
                return;
            }

            this.disconnectionHandled = true;
            PacketListener listener = this.getPacketListener();
            PacketListener target = listener != null ? listener : this.disconnectListener;
            if (target != null) {
                DisconnectionDetails details = Objects.requireNonNullElseGet(
                        this.getDisconnectionDetails(),
                        () -> new DisconnectionDetails(Component.translatable("multiplayer.disconnect.generic"))
                );
                target.onDisconnect(details);
            }
        }
    }

    public boolean isMemoryConnection() {
        // Every connection in this port used to be in-process, so returning true
        // unconditionally was right. A relay connection to a real server is not:
        // ClientConfigurationPacketListenerImpl passes this straight into
        // RegistryDataCollector.collectGameRegistries, where "memory" means the
        // server shares this JVM's registries and their TAGS are already bound.
        // Claiming that over a real connection skipped the local tag load, and
        // the configuration-phase registry parse then died on
        // "Missing tag: minecraft:infiniburn_overworld in minecraft:block".
        return !this.remoteTransport;
    }

    public boolean isConnected() {
        // A relay connection has no peer and no Worker port; without this it
        // reads as "not connected", so every send() lands in pendingActions and
        // the handshake never reaches the wire (measured: sent=0).
        return this.open && (this.workerTransport || this.remoteTransport || this.peer != null);
    }

    public boolean isConnecting() {
        return !this.workerTransport && !this.remoteTransport && this.peer == null;
    }

    public PacketFlow getReceiving() {
        return this.receiving;
    }

    public PacketFlow getSending() {
        return this.receiving.getOpposite();
    }

    public PacketListener getPacketListener() {
        return this.packetListener;
    }

    public DisconnectionDetails getDisconnectionDetails() {
        return this.disconnectionDetails;
    }

    /** Vanilla clears channel auto-read; here it stops the inbound drain. */
    public void setReadOnly() {
        this.readOnly = true;
    }

    /** Local connections never compress: vanilla's threshold is only sent over TCP. */
    public void setupCompression(final int threshold, final boolean validateDecompressed) {
    }

    /** Local connections are never encrypted; the login path skips the request. */
    public void setEncryptionKey(final Cipher decryptCipher, final Cipher encryptCipher) {
    }

    public float getAverageReceivedPackets() {
        return this.averageReceivedPackets;
    }

    public float getAverageSentPackets() {
        return this.averageSentPackets;
    }

    /** Bandwidth sampling reads Netty byte counts, which no longer exist. */
    public void setBandwidthLogger(final LocalSampleLogger bandwidthLogger) {
    }

    public void setIntendedProfileId(final UUID profileId) {
        this.intendedProfileId = profileId;
    }

    public UUID getIntendedProfileId() {
        return this.intendedProfileId;
    }
}
