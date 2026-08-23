package dev.mcweb.graal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.time.Duration;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;

/**
 * Joins a real Minecraft server from the browser.
 *
 * <p>Vanilla's route — {@code ConnectScreen} spawning a thread that calls
 * {@code Connection.connect(InetSocketAddress, …)} — needs Netty and a socket,
 * neither of which exists here, so the port never had it. Everything *above*
 * the socket does exist: {@link Connection} already moves encoded packets as
 * {@code byte[]}, and {@link dev.mcweb.graal.net.PacketWire} produces exactly
 * the body a server expects. So this reuses the whole vanilla handshake and
 * only supplies a different pipe ({@link BrowserRemoteTransport} over the
 * relay).</p>
 *
 * <p>Entry is a page request rather than a UI hook for now: from the console,
 * {@code mcWebNet.join(mcWebNet.gatewayUrl(), "127.0.0.1", 25565, "Name")}.
 * Wiring {@code ConnectScreen} to this is the next step and needs an ASM
 * redirect of its Netty call.</p>
 */
public final class BrowserMultiplayerCompat {
    private static Minecraft minecraft;
    private static Connection connection;
    private static boolean joining;

    private BrowserMultiplayerCompat() {
    }

    public static void install(Minecraft client) {
        minecraft = client;
        restoreSkipMultiplayerWarning(client);
    }

    /**
     * Re-applies "Do not show this screen again" from a previous visit.
     *
     * <p>Vanilla keeps this in {@code options.txt}, which here lives in an
     * in-memory filesystem that does not survive a reload — so the box was
     * ticked, saved, and forgotten every time. {@code localStorage} is the only
     * store on the page that outlives the tab, so the flag is mirrored there
     * and restored before the title screen can offer the warning.</p>
     */
    private static void restoreSkipMultiplayerWarning(Minecraft client) {
        try {
            if (client != null && client.options != null && readSkipWarning()) {
                client.options.skipMultiplayerWarning = true;
                BrowserGpu.reportProgress("multiplayer:warning-skip-restored");
            }
        } catch (Throwable ignored) {
            // A missing or blocked localStorage just means the screen shows.
        }
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value =
            "try { return globalThis.localStorage?.getItem('mcweb.skipMultiplayerWarning') === '1'; }"
            + " catch (e) { return false; }", args = {})
    private static native boolean readSkipWarning();

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(value =
            "try { globalThis.localStorage?.setItem('mcweb.skipMultiplayerWarning', on ? '1' : '0'); }"
            + " catch (e) { /* private mode or blocked storage */ }", args = {"on"})
    private static native void writeSkipWarning(boolean on);

    public static boolean isActive() {
        return connection != null;
    }

    /** Polled once per frame from {@link BrowserFramePump}. */
    public static void pump() {
        if (minecraft == null || !BrowserRemoteTransport.isAvailable()) {
            return;
        }
        if (connection != null) {
            // A user-initiated Disconnect closes Minecraft's Connection before
            // the next frame.  Desktop drops that closed channel and then opens
            // JoinMultiplayerScreen; ticking it once more calls onDisconnect
            // with ClientLevel.DEFAULT_QUIT_MESSAGE and replaces the list with
            // a spurious "Connection Lost / Quitting" screen.  It also leaves
            // the static browser packet sink attached to the old LOGIN/PLAY
            // protocol, so a subsequent server can receive intention + Hello
            // yet never receive LoginAcknowledged.  Release it before any drain.
            if (!connection.isConnected()) {
                releaseConnection("local-close");
            } else {
                Connection.drainBrowserRemote();
                if (connection.isConnected()) {
                    return;
                }
                releaseConnection("remote-close");
            }
        }
        // Only while out of a game: the server list is the only thing that
        // pings, and this keeps the check off the in-world frame entirely.
        drainStatusPings();
        String request = BrowserRemoteTransport.consumeJoin();
        if (request == null || request.isEmpty() || joining) {
            return;
        }
        joining = true;
        try {
            join(JsonParser.parseString(request).getAsJsonObject());
        } catch (Throwable failure) {
            BrowserGpu.reportJavaFailure(
                    "multiplayer-join",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
        } finally {
            joining = false;
        }
    }

    /**
     * Hands the pack source over to the server, which vanilla does from
     * {@code ConnectScreen}'s connect thread — a method this port replaces
     * wholesale, so without this a server's resource pack was pushed, answered
     * immediately, and never downloaded.
     *
     * <p>A server this client has no {@code ServerData} for is PROMPT in
     * vanilla, which raises {@code PackConfirmScreen} from the packet handler.
     * That screen never becomes visible here — the same
     * {@code Gui.setScreen}-from-a-packet-handler problem that made
     * SafetyScreen's Proceed a dead button — so PROMPT would leave the pack
     * pending forever and the server's textures would simply never arrive.
     * Unknown servers are therefore ALLOWED; an explicit per-server choice,
     * when the player has made one, is still honoured.</p>
     */
    private static void configureServerPacks(Connection opened) {
        try {
            net.minecraft.client.multiplayer.ServerData current = minecraft.getCurrentServer();
            net.minecraft.client.multiplayer.ServerData.ServerPackStatus status =
                    current == null
                            ? net.minecraft.client.multiplayer.ServerData.ServerPackStatus.ENABLED
                            : current.getResourcePackStatus();
            net.minecraft.client.resources.server.ServerPackManager.PackPromptStatus prompt =
                    switch (status) {
                        case ENABLED ->
                            net.minecraft.client.resources.server
                                    .ServerPackManager.PackPromptStatus.ALLOWED;
                        case DISABLED ->
                            net.minecraft.client.resources.server
                                    .ServerPackManager.PackPromptStatus.DECLINED;
                        // Only reachable from a saved server the player left on
                        // "Prompt"; the confirmation screen is unreachable here,
                        // so treat it as the ask it stands in for.
                        case PROMPT ->
                            net.minecraft.client.resources.server
                                    .ServerPackManager.PackPromptStatus.ALLOWED;
                    };
            minecraft.getDownloadedPackSource().configureForServerControl(opened, prompt);
            BrowserGpu.reportProgress("multiplayer:server-packs=" + prompt);
        } catch (Throwable failure) {
            BrowserGpu.reportProgress("multiplayer:server-packs-failed "
                    + failure.getClass().getName());
        }
    }

    /** Drops a live server connection; safe to call when there is none. */
    public static void disconnect(String cause) {
        if (connection != null) {
            releaseConnection(cause);
        }
    }

    private static void releaseConnection(String cause) {
        Connection finished = connection;
        connection = null;
        if (finished != null) {
            Connection.releaseRemoteClient(finished);
        }
        try {
            // Drops the server's packs; the counterpart to configureServerPacks.
            minecraft.getDownloadedPackSource().cleanupAfterDisconnect();
        } catch (Throwable ignored) {
            // Leaving a stale server pack selected is not worth failing on.
        }
        BrowserRemoteTransport.disconnect();
        BrowserGpu.reportProgress("multiplayer:connection-release-cause=" + cause);
        BrowserGpu.reportProgress("multiplayer:connection-released");
    }

    private static void join(JsonObject request) {
        String relay = request.get("relay").getAsString();
        String host = request.get("host").getAsString();
        int port = request.get("port").getAsInt();
        String name = request.get("name").getAsString();
        BrowserGpu.reportProgress("multiplayer:join " + host + ":" + port + " as " + name);

        BrowserRemoteTransport.connect(relay, host, port);
        // The socket opens asynchronously, but nothing may be sent before it
        // does, so the handshake waits for the transport in pumpUntilOpen().
        pendingHost = host;
        pendingPort = port;
        pendingName = name;
        awaitingOpen = true;
    }

    /** Entry from the real Multiplayer UI (ASM-redirected `ConnectScreen.connect`). */
    public static void connectFromUi(
            Minecraft client,
            net.minecraft.client.multiplayer.resolver.ServerAddress address
    ) {
        install(client);
        // A player can select another row immediately after the previous
        // disconnect.  Make the new WebSocket and Java packet sink one atomic
        // generation instead of letting delayed cleanup close the new socket.
        if (connection != null) {
            releaseConnection("ui-reconnect");
        }
        String host = address.getHost();
        int port = address.getPort();
        if (!isTargetAllowed(host, port)) {
            BrowserGpu.reportProgress("multiplayer:refused target " + host + ":" + port);
            client.gui.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
                    () -> client.gui.setScreen(new net.minecraft.client.gui.screens.TitleScreen()),
                    net.minecraft.network.chat.Component.literal("Server not allowed"),
                    net.minecraft.network.chat.Component.literal(
                            "This app's Minecraft gateway does not permit "
                            + host + ":" + port),
                    net.minecraft.network.chat.CommonComponents.GUI_BACK,
                    true
            ));
            return;
        }
        client.gui.setScreen(new net.minecraft.client.gui.screens.GenericMessageScreen(
                net.minecraft.network.chat.Component.literal("Connecting to " + host + ":" + port)));
        pendingHost = host;
        pendingPort = port;
        pendingName = client.getUser().getName();
        awaitingOpen = true;
        BrowserGpu.reportProgress("multiplayer:ui-connect " + host + ":" + port);
        BrowserRemoteTransport.connect(relayUrl(), host, port);
    }

    /**
     * Opens the server list from the third-party-play caution screen.
     *
     * <p>SafetyScreen's Proceed built a {@code JoinMultiplayerScreen} and handed
     * it to {@code Gui.setScreen}, and nothing happened: the screen never
     * changed and no exception reached the frame pump, the input bridge or the
     * crash reporter. Mouse buttons are dispatched from the DOM handler rather
     * than from inside {@code frame()}, so a throw there had nowhere to surface.
     * Proceed is redirected here so the construction happens somewhere that can
     * report, and a failure becomes a screen the player can read instead of a
     * dead button.</p>
     */
    /**
     * SafetyScreen's Proceed, with the "Do not show this screen again" state.
     *
     * <p>The transform hands the checkbox value here rather than keeping
     * vanilla's own `if` around it, so the patched method stays branch-free —
     * see the note in build.gradle. Everything vanilla did with the value is
     * done here instead, plus the part vanilla cannot: {@code options.save()}
     * writes to an in-memory filesystem that a reload discards, so the flag is
     * also mirrored into {@code localStorage}.</p>
     */
    public static void proceedFromWarning(boolean stopShowing) {
        Minecraft client = Minecraft.getInstance();
        try {
            if (client != null && client.options != null) {
                if (stopShowing) {
                    client.options.skipMultiplayerWarning = true;
                    client.options.save();
                }
                writeSkipWarning(stopShowing);
                BrowserGpu.reportProgress("multiplayer:warning-skip=" + stopShowing);
            }
        } catch (Throwable failure) {
            // Remembering the preference is not worth losing the button over.
            BrowserGpu.reportProgress("multiplayer:warning-skip-failed "
                    + failure.getClass().getName());
        }
        openServerList();
    }

    public static void openServerList() {
        Minecraft client = Minecraft.getInstance();
        try {
            net.minecraft.client.gui.screens.Screen back =
                    new net.minecraft.client.gui.screens.TitleScreen();
            BrowserGpu.reportProgress("multiplayer:opening-server-list");
            client.gui.setScreen(
                    new net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen(back));
            BrowserGpu.reportProgress("multiplayer:server-list-open");
        } catch (Throwable failure) {
            String detail = failure.getClass().getName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
            BrowserGpu.reportJavaFailure("multiplayer-server-list",
                    failure.getClass().getName(), detail);
            try {
                client.gui.setScreen(new net.minecraft.client.gui.screens.AlertScreen(
                        () -> client.gui.setScreen(
                                new net.minecraft.client.gui.screens.TitleScreen()),
                        net.minecraft.network.chat.Component.literal("Server list unavailable"),
                        net.minecraft.network.chat.Component.literal(detail),
                        net.minecraft.network.chat.CommonComponents.GUI_BACK,
                        true
                ));
            } catch (Throwable ignored) {
                // The report above is the authoritative signal.
            }
        }
    }

    /**
     * Runs a server-list ping without starting a thread.
     *
     * <p>{@code ServerSelectionList$OnlineServerEntry.extractContent} — the
     * render path — submits its ping to a {@code ScheduledThreadPoolExecutor}
     * the first time it draws an entry whose state is still {@code INITIAL}.
     * Submitting starts a worker thread, and this image cannot start one:
     * {@code Thread.start()} throws {@link IllegalThreadStateException}. Thrown
     * from inside rendering, that became {@code ReportedException: Rendering
     * screen} on the very first frame that drew a saved server — a black screen
     * and a crash box. An empty list has no entry to draw, which is why Direct
     * Connection was unaffected and why this hid from every test that did not
     * first save a server.</p>
     *
     * <p>The work is run inline instead. It is safe to do so because it no
     * longer blocks: the lambda reaches
     * {@link net.minecraft.client.multiplayer.ServerStatusPinger}, which opens
     * a relay socket and returns without waiting for the answer. The one thing
     * that stopped it — {@code EventLoopGroupHolder.remote(boolean)}, which
     * exists in the JAR but not in this image and so raised
     * {@code NoSuchMethodError} — is redirected to {@link #noEventLoopGroup} in
     * the same transform. Note the lambda's own catch is {@code Exception},
     * which is precisely why that {@code Error} escaped it.</p>
     *
     * <p>The guard is the standing lesson from this bug: nothing decorative may
     * take rendering down again, and a status ping is decoration beside a list
     * entry. A failure is reported with {@code reportProgress} rather than
     * {@code reportJavaFailure} so it stays in diagnostics instead of putting a
     * failure box in front of someone who only opened a menu. The returned
     * {@code Future} is discarded by the caller — the bytecode pops it — and
     * this is the only call site.</p>
     */
    public static java.util.concurrent.Future<?> submitServerPing(
            java.util.concurrent.ThreadPoolExecutor pool,
            Runnable ping
    ) {
        try {
            ping.run();
        } catch (Throwable failure) {
            BrowserGpu.reportProgress("multiplayer:ping-failed "
                    + failure.getClass().getName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
        }
        return null;
    }

    /**
     * Stands in for {@code EventLoopGroupHolder.remote(boolean)}, which this
     * image does not have — it is reachable only through Netty channel classes,
     * so it compiles away and calling it raises {@code NoSuchMethodError}.
     * Nothing downstream uses the value: the shadow pinger ignores its
     * event-loop argument and dials through the relay instead.
     */
    public static net.minecraft.server.network.EventLoopGroupHolder noEventLoopGroup(
            boolean useNativeTransport
    ) {
        return null;
    }

    /** Browser-visible copy of the gateway policy, shared with joins and pings. */
    public static boolean isTargetAllowed(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            return false;
        }
        return targetAllowed(host, port);
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(
            value = "return globalThis.mcWebNet?.targetAllowed?.(host, port) === true;",
            args = {"host", "port"})
    private static native boolean targetAllowed(String host, int port);

    /** Opens a status ping through the relay; returns its id. */
    public static int startStatusPing(String host, int port) {
        return pingStatus(relayUrl(), host, port,
                net.minecraft.SharedConstants.getProtocolVersion());
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(
            value = "return globalThis.mcWebNet.pingStatus(relay, host, port, protocol);",
            args = {"relay", "host", "port", "protocol"})
    private static native int pingStatus(String relay, String host, int port, int protocol);

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(
            value = "return globalThis.mcWebNet.consumePingResults();", args = {})
    private static native String consumePingResults();

    /**
     * Applies finished status pings to their list entries.
     *
     * <p>Called from the frame pump rather than from the socket callback: these
     * writes are read by the renderer, and the rest of the client assumes its
     * screens are only touched on the client thread.</p>
     */
    private static void drainStatusPings() {
        String batch;
        try {
            batch = consumePingResults();
        } catch (Throwable unavailable) {
            return;
        }
        if (batch == null || batch.isEmpty()) {
            return;
        }
        com.google.gson.JsonArray results;
        try {
            results = JsonParser.parseString(batch).getAsJsonArray();
        } catch (Throwable malformed) {
            return;
        }
        for (com.google.gson.JsonElement element : results) {
            try {
                applyStatusPing(element.getAsJsonObject());
            } catch (Throwable failure) {
                BrowserGpu.reportProgress("multiplayer:ping-apply-failed "
                        + failure.getClass().getName());
            }
        }
    }

    private static void applyStatusPing(JsonObject result) {
        int id = result.get("id").getAsInt();
        boolean ok = result.has("ok") && result.get("ok").getAsBoolean();
        for (Object[] pending : net.minecraft.client.multiplayer.ServerStatusPinger.drainMatching(id)) {
            net.minecraft.client.multiplayer.ServerData server =
                    (net.minecraft.client.multiplayer.ServerData) pending[0];
            Runnable onSuccess = (Runnable) pending[1];
            Runnable onFinished = (Runnable) pending[2];
            if (!ok) {
                server.motd = net.minecraft.network.chat.Component.literal(
                        "Can't connect to server").withStyle(
                                net.minecraft.ChatFormatting.DARK_RED);
                server.status = net.minecraft.network.chat.Component.empty();
                server.ping = -1L;
                server.setState(
                        net.minecraft.client.multiplayer.ServerData.State.UNREACHABLE);
                BrowserGpu.reportProgress("multiplayer:ping-unreachable "
                        + (result.has("error") ? result.get("error").getAsString() : "?"));
            } else {
                applyStatusJson(server, result);
                BrowserGpu.reportProgress("multiplayer:ping-ok " + server.ping + "ms");
                if (onSuccess != null) onSuccess.run();
            }
            if (onFinished != null) onFinished.run();
        }
    }

    /** Fills an entry from the server's status JSON. */
    private static void applyStatusJson(
            net.minecraft.client.multiplayer.ServerData server,
            JsonObject result
    ) {
        JsonObject status = JsonParser.parseString(result.get("json").getAsString())
                .getAsJsonObject();

        server.motd = status.has("description")
                ? net.minecraft.network.chat.Component.literal(
                        plainText(status.get("description")))
                : net.minecraft.network.chat.Component.empty();

        if (status.has("version") && status.get("version").isJsonObject()) {
            JsonObject version = status.getAsJsonObject("version");
            if (version.has("name")) {
                server.version = net.minecraft.network.chat.Component.literal(
                        version.get("name").getAsString());
            }
            if (version.has("protocol")) {
                server.protocol = version.get("protocol").getAsInt();
            }
        }

        int online = 0;
        int max = 0;
        if (status.has("players") && status.get("players").isJsonObject()) {
            JsonObject players = status.getAsJsonObject("players");
            online = players.has("online") ? players.get("online").getAsInt() : 0;
            max = players.has("max") ? players.get("max").getAsInt() : 0;
        }
        server.players = new net.minecraft.network.protocol.status.ServerStatus.Players(
                max, online, java.util.List.of());
        server.status = net.minecraft.client.multiplayer.ServerStatusPinger
                .formatPlayerCount(online, max);
        server.playerList = java.util.List.of();

        server.ping = result.has("latencyMs") ? result.get("latencyMs").getAsLong() : 0L;
        server.setState(
                net.minecraft.client.multiplayer.ServerData.State.SUCCESSFUL);

        if (status.has("favicon")) {
            try {
                String favicon = status.get("favicon").getAsString();
                int comma = favicon.indexOf(',');
                if (favicon.startsWith("data:image/png;base64") && comma > 0) {
                    server.setIconBytes(
                            net.minecraft.client.multiplayer.ServerData.validateIcon(
                                    java.util.Base64.getDecoder().decode(
                                            favicon.substring(comma + 1).replaceAll("\\s", ""))));
                }
            } catch (Throwable ignored) {
                // A bad icon must not cost the entry its MOTD.
            }
        }
    }

    /**
     * Flattens a chat-component MOTD to text.
     *
     * <p>Mojang's own deserialiser needs a registry-aware ops that is only
     * available once a level is loaded, and the server list runs before any
     * exists. Legacy section-sign colours inside the strings still render,
     * since the font handles those; per-component JSON colours are lost, which
     * is a fair trade for not carrying a registry into the main menu.</p>
     */
    private static String plainText(com.google.gson.JsonElement description) {
        if (description == null || description.isJsonNull()) {
            return "";
        }
        if (description.isJsonPrimitive()) {
            return description.getAsString();
        }
        StringBuilder text = new StringBuilder();
        if (description.isJsonArray()) {
            for (com.google.gson.JsonElement part : description.getAsJsonArray()) {
                text.append(plainText(part));
            }
            return text.toString();
        }
        JsonObject object = description.getAsJsonObject();
        if (object.has("text")) {
            text.append(object.get("text").getAsString());
        }
        if (object.has("extra")) {
            text.append(plainText(object.get("extra")));
        }
        return text.toString();
    }

    @org.graalvm.webimage.api.JS.Coerce
    @org.graalvm.webimage.api.JS(
            value = "return globalThis.mcWebNet?.gatewayUrl?.()"
                    + " || ((globalThis.location?.protocol === 'https:' ? 'wss://' : 'ws://')"
                    + " + globalThis.location.host + '/mcweb/socket');", args = {})
    private static native String relayUrl();

    private static String pendingHost;
    private static int pendingPort;
    private static String pendingName;
    private static boolean awaitingOpen;

    /**
     * Second half of the join, once the relay reports the socket open. Called
     * from the frame pump so it, too, runs on the client thread.
     */
    public static void pumpConnect() {
        if (!awaitingOpen || minecraft == null) {
            return;
        }
        String state = BrowserRemoteTransport.state();
        if ("connecting".equals(state)) {
            return;
        }
        awaitingOpen = false;
        if (!"open".equals(state)) {
            BrowserGpu.reportJavaFailure("multiplayer-join", "java.lang.IllegalStateException",
                    "relay transport entered state " + state);
            return;
        }
        try {
            Connection opened = Connection.connectToRemoteServer();
            connection = opened;
            configureServerPacks(opened);
            UUID id = minecraft.getUser().getProfileId();
            ClientHandshakePacketListenerImpl listener = new ClientHandshakePacketListenerImpl(
                    opened,
                    minecraft,
                    null,
                    null,
                    false,
                    Duration.ZERO,
                    component -> { },
                    new LevelLoadTracker(0L),
                    null
            );
            // Vanilla's own handshake: intention packet with LOGIN intent, then
            // hello. Everything after this is Mojang's login/configuration
            // state machine, unmodified.
            opened.initiateServerboundPlayConnection(pendingHost, pendingPort, listener);
            opened.send(new ServerboundHelloPacket(pendingName, id));
            minecraft.pendingConnection = opened;
            minecraft.isLocalServer = false;
            minecraft.updateReportEnvironment(ReportEnvironment.thirdParty(
                    pendingHost + ":" + pendingPort));
            BrowserGpu.reportProgress("multiplayer:handshake-sent");
        } catch (Throwable failure) {
            connection = null;
            BrowserGpu.reportJavaFailure(
                    "multiplayer-handshake",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
        }
    }
}
