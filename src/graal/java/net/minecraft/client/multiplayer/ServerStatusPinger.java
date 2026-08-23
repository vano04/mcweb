package net.minecraft.client.multiplayer;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.EventLoopGroupHolder;

/**
 * Classpath-first shadow of Mojang's server-list pinger, over the relay.
 *
 * <p>The JAR's class cannot initialise in this image: it pings over Netty
 * through {@code Connection.connectToServer}, which the browser
 * {@link net.minecraft.network.Connection} deliberately does not have. Its
 * static initialiser therefore failed, and because that surfaced as a
 * {@code NoClassDefFoundError} — an {@code Error}, not an {@code Exception} —
 * it slipped through every {@code catch (Exception)} in the client and in this
 * port, leaving SafetyScreen's Proceed button silently dead.</p>
 *
 * <p>The status protocol itself needs none of Netty: it is a handshake with
 * next-state 1, a status request, and one JSON response. {@code mcWebNet}
 * performs that over its own short-lived WebSocket — the relay routes
 * next-state 1 to a plain pass-through session, so no account, encryption or
 * compression is involved — and this class turns the answer into the fields the
 * server list draws.</p>
 *
 * <p>Requests are started here and <em>applied elsewhere</em>: this is called
 * from the list's render path, so it only kicks off the socket and returns.
 * {@code BrowserMultiplayerCompat.pump()} drains the finished pings once per
 * frame and completes them on the client thread, where the screens live.</p>
 */
public class ServerStatusPinger {

    /** A ping in flight: the entry to fill in and the callbacks to fire. */
    private static final class Pending {
        final int id;
        final ServerData server;
        final Runnable onSuccess;
        final Runnable onFinished;

        Pending(int id, ServerData server, Runnable onSuccess, Runnable onFinished) {
            this.id = id;
            this.server = server;
            this.onSuccess = onSuccess;
            this.onFinished = onFinished;
        }
    }

    /**
     * Static because results arrive through one page-wide queue, and because
     * the pump that applies them has no handle on the screen's pinger instance.
     */
    private static final List<Pending> PENDING = new ArrayList<>();

    public ServerStatusPinger() {
    }

    /** Everything still waiting on the relay, for the pump to complete. */
    public static List<Object[]> drainMatching(int id) {
        List<Object[]> matched = new ArrayList<>();
        for (int i = PENDING.size() - 1; i >= 0; i--) {
            Pending pending = PENDING.get(i);
            if (pending.id == id) {
                matched.add(new Object[] { pending.server, pending.onSuccess, pending.onFinished });
                PENDING.remove(i);
            }
        }
        return matched;
    }

    /** Drops requests whose answer never came, so the list can be re-pinged. */
    public static void forgetAll() {
        PENDING.clear();
    }

    /**
     * Starts a status ping. Returns immediately — the socket is asynchronous and
     * this runs while the entry is being drawn.
     *
     * <p>The browser first checks the policy advertised by the same-origin
     * gateway. The gateway independently enforces that policy before opening
     * TCP, so a modified page cannot widen its own capability.</p>
     */
    public void pingServer(
            final ServerData server,
            final Runnable onSuccess,
            final Runnable onFinished,
            final EventLoopGroupHolder eventLoopGroup
    ) throws UnknownHostException {
        String address = server.ip == null ? "" : server.ip.trim();
        String host = address;
        int port = 25565;
        int colon = address.lastIndexOf(':');
        if (colon > 0 && address.indexOf(']') < colon) {
            host = address.substring(0, colon);
            try {
                port = Integer.parseInt(address.substring(colon + 1).trim());
            } catch (NumberFormatException malformed) {
                port = 25565;
            }
        }
        if (!dev.mcweb.graal.BrowserMultiplayerCompat.isTargetAllowed(host, port)) {
            server.motd = Component.literal("Server not allowed by this app")
                    .withStyle(ChatFormatting.GRAY);
            server.status = Component.empty();
            server.setState(ServerData.State.INCOMPATIBLE);
            if (onFinished != null) onFinished.run();
            return;
        }
        int id = dev.mcweb.graal.BrowserMultiplayerCompat.startStatusPing(host, port);
        PENDING.add(new Pending(id, server, onSuccess, onFinished));
    }

    /** Vanilla's "online/max" label, kept so the list renders the same shape. */
    public static Component formatPlayerCount(final int online, final int max) {
        return Component.literal(Integer.toString(online))
                .append(Component.literal("/").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(Integer.toString(max)))
                .withStyle(ChatFormatting.GRAY);
    }

    /** No sockets are held here; the page owns them and closes each on answer. */
    public void tick() {
    }

    public void removeAll() {
        forgetAll();
    }
}
