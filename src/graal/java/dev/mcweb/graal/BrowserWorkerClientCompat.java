package dev.mcweb.graal;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.chat.report.ReportEnvironment;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.login.ServerboundHelloPacket;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.level.progress.LevelLoadListener;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.graalvm.webimage.api.JS;

/** Main-realm state machine that starts and connects to the integrated-server Worker. */
public final class BrowserWorkerClientCompat {
    private static Minecraft minecraft;
    private static String commandJson;
    private static LevelLoadTracker loadTracker;
    private static Instant startedAt;
    private static boolean newWorld;
    private static boolean commandSent;
    private static boolean connected;
    private static boolean failureReported;
    private static Connection workerConnection;
    /** World identity and write-back state for the snapshot exchange. */
    private static String levelId;
    private static boolean levelWasLive;
    private static boolean snapshotRequested;
    /** True from asking the Worker to save until its answer lands. */
    private static boolean snapshotPending;
    /** A world start held until the previous world's snapshot arrives. */
    private static boolean pendingLaunch;
    private static boolean pendingWorkerStop;
    private static JsonObject pendingCommand;
    private static Path pendingWorldRoot;
    private static long launchDeadlineMillis;
    /** How long a world start waits for a failed Worker to answer a previous save. */
    private static final long SNAPSHOT_WAIT_MILLIS = 30_000L;

    private BrowserWorkerClientCompat() {
    }

    /**
     * Starts the supported separate-heap server unless the explicit
     * {@code ?mcweb_inline_server=1} diagnostic override is present. The inline
     * path remains useful for seam comparison, but it shares the render realm
     * and cannot keep up with server work during ordinary play.
     */
    public static boolean tryBeginWorld(
            Minecraft client,
            LevelStorageSource.LevelStorageAccess access,
            PackRepository packs,
            WorldStem stem,
            Optional<GameRules> gameRules,
            boolean creating
    ) {
        if (!BrowserWorkerClientTransport.isRequested()) {
            return false;
        }
        return beginWorld(client, access, packs, stem, gameRules, creating);
    }

    public static boolean beginWorld(
            Minecraft client,
            LevelStorageSource.LevelStorageAccess access,
            PackRepository packs,
            WorldStem stem,
            Optional<GameRules> gameRules,
            boolean creating
    ) {
        if (!BrowserWorkerClientTransport.isAvailable()) {
            throw new IllegalStateException("Browser server Worker host is unavailable");
        }
        try {
            drainWorldSnapshot();
            // The previous Worker may still be saving the world the player just
            // left. Stopping it here kills that mid-save: measured, clicking Play
            // 1965 ms after quitting lost the world entirely and it reopened as
            // "corrupted save data". So only the Java connection closes now; the
            // Worker itself is stopped in finishPendingLaunch(), once its
            // snapshot has landed or the wait times out.
            boolean previousWorker = minecraft != null || workerConnection != null;
            if (!previousWorker) {
                // No Worker exists to answer, so a flag left set by a lost
                // answer would stall this start for the whole wait.
                snapshotPending = false;
            }
            disconnectWorkerConnection();

            client.disconnectWithProgressScreen();
            // Startup restores only world-list metadata. Pull the selected
            // world's complete durable snapshot now, immediately before the
            // existing client->Worker capture reads its files.
            if (!creating) {
                BrowserPersistentStorage.restoreWorld(
                        access.getLevelId(), access.getLevelPath(LevelResource.ROOT));
            }
            access.saveDataTag(stem.worldDataAndGenSettings().data());
            // 26.2 keeps the world generator settings OUTSIDE level.dat, in
            // data/world_gen_settings.dat, and only a running server writes it —
            // which here is the Worker, whose filesystem the client never sees.
            // Without this file `WorldDimensions.bake` throws "Overworld settings
            // missing" and Minecraft calls the save corrupted, so the client
            // writes its own copy now rather than depending on the write-back
            // landing before the player clicks Play again (measured: the player
            // wins that race by two seconds).
            LevelStorageSource.writeWorldGenSettings(
                    stem.registries().compositeAccess(),
                    access.getLevelPath(LevelResource.ROOT),
                    stem.worldDataAndGenSettings().genSettings()
            );
            // Everything except the file list, which must be read AFTER the
            // pending snapshot is applied or it would ship the pre-save world.
            pendingCommand = createCommand(client, access, stem, creating);
            pendingWorldRoot = access.getLevelPath(LevelResource.ROOT);
            pendingWorkerStop = previousWorker;
            pendingLaunch = true;
            launchDeadlineMillis = System.currentTimeMillis() + SNAPSHOT_WAIT_MILLIS;

            commandJson = null;
            minecraft = client;
            levelId = access.getLevelId();
            levelWasLive = false;
            snapshotRequested = false;
            newWorld = creating;
            commandSent = false;
            connected = false;
            failureReported = false;
            startedAt = Instant.now();
            loadTracker = new LevelLoadTracker(creating ? 500L : 0L);
            client.gui.setScreen(new LevelLoadingScreen(
                    loadTracker,
                    LevelLoadingScreen.Reason.OTHER
            ));

            stem.close();
            access.safeClose();
            BrowserGpu.reportProgress("server-worker:launch-pending snapshotPending="
                    + snapshotPending);
            return true;
        } catch (Throwable failure) {
            BrowserGpu.reportJavaFailure(
                    "server-worker-start",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
            throw new IllegalStateException("Failed to start integrated-server Worker", failure);
        }
    }

    public static void pump() {
        if (minecraft == null) return;
        try {
            // Draining the snapshot comes first, and while a launch is pending
            // nothing else may run: the transport still belongs to the PREVIOUS
            // Worker, so its packets and its "ready" state would be read as this
            // world's and start it against a command that has no files yet.
            drainWorldSnapshot();
            if (pendingLaunch) {
                finishPendingLaunch();
                return;
            }
            // Above the state machine on purpose: the branches below return
            // early, and inbound must be drained on every frame once connected.
            // Before the client connects there is no handler yet, so the host
            // queue simply holds those packets until there is one.
            BrowserWorkerClientTransport.drainInbound();
            drainServerLoadProgress();
            trackWorldExit();
            String state = BrowserWorkerClientTransport.state();
            if (!connected && !commandSent && "ready".equals(state)) {
                BrowserWorkerClientTransport.startWorld(commandJson);
                commandSent = true;
                BrowserGpu.reportProgress("server-worker:world-starting");
                return;
            }
            if (!connected && "server:world-ready".equals(state)) {
                connectClient();
                return;
            }
            if ("error".equals(state) || "worker-error".equals(state) || "stopped".equals(state)) {
                reportWorkerFailure(state);
            }
        } catch (Throwable failure) {
            if (!failureReported) {
                failureReported = true;
                BrowserGpu.reportJavaFailure(
                        "server-worker-pump",
                        failure.getClass().getName(),
                        failure.getMessage() == null ? "" : failure.getMessage()
                );
            }
        }
    }

    /**
     * Starts the Worker once the previous world's save has come home.
     *
     * <p>The wait is bounded: a Worker that died owes a snapshot it can never
     * send, and the player must still be able to open the world.</p>
     */
    private static void finishPendingLaunch() {
        if (snapshotPending && System.currentTimeMillis() < launchDeadlineMillis) {
            return;
        }
        if (snapshotPending) {
            BrowserGpu.reportProgress("server-worker:snapshot-wait-timeout level=" + levelId);
            snapshotPending = false;
        }
        pendingLaunch = false;
        try {
            // Read the world only now: the snapshot above may have just rewritten it.
            pendingCommand.add("files", BrowserWorldSnapshot.captureFiles(pendingWorldRoot));
            commandJson = pendingCommand.toString();
        } catch (Throwable failure) {
            BrowserGpu.reportJavaFailure(
                    "server-worker-command",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
            return;
        } finally {
            pendingCommand = null;
            pendingWorldRoot = null;
        }
        // launch() replaces any previous Worker itself; a separate stop() would
        // park the state at "stopped", which the state machine reads as fatal.
        pendingWorkerStop = false;
        BrowserWorkerClientTransport.launch("minecraft-client");
        BrowserGpu.reportProgress("server-worker:launching");
    }

    /** Closes the Java half of the previous world without stopping its Worker. */
    private static void disconnectWorkerConnection() {
        Connection connection = workerConnection;
        workerConnection = null;
        if (connection == null) return;
        try {
            connection.disconnect(Component.translatable("multiplayer.disconnect.generic"));
        } catch (Throwable ignored) {
            // Cleanup must continue even if a partially initialized listener rejects
            // the synthetic disconnect.
        }
    }

    /**
     * Asks the Worker for the saved world the moment the player leaves it.
     *
     * <p>Leaving is the only point at which the two filesystems can be
     * reconciled: the Worker owns the live world, the client owns the directory
     * the world list and {@code WorldOpenFlows} read, and nothing has ever
     * crossed back. "Save and Quit to Title" is not observable here — the port
     * never routes it to this Worker — but the client dropping its level is,
     * and it happens on exactly the same disconnect.</p>
     */
    private static void trackWorldExit() {
        if (!connected) return;
        if (minecraft.level != null) {
            levelWasLive = true;
            return;
        }
        if (!levelWasLive || snapshotRequested) return;
        snapshotRequested = true;
        if (!BrowserWorkerClientTransport.isWorldWriteBackEnabled()) {
            // Diagnostic opt-out only. Worlds still reopen without write-back
            // because beginWorld writes world_gen_settings.dat, but terrain and
            // player mutations cannot survive the private Worker's lifetime.
            BrowserGpu.reportProgress("server-worker:write-back-disabled");
            return;
        }
        snapshotPending = true;
        BrowserWorkerClientTransport.sendState("save-snapshot");
        BrowserGpu.reportProgress("server-worker:snapshot-requested level=" + levelId);
    }

    /** Writes a returned world into the client's own saves directory. */
    private static void drainWorldSnapshot() {
        String json = BrowserWorkerClientTransport.consumeWorldSnapshot();
        if (json == null) return;
        snapshotPending = false;
        if (json.isEmpty()) {
            BrowserGpu.reportProgress("server-worker:snapshot-empty");
            return;
        }
        long started = System.nanoTime();
        try {
            JsonObject snapshot = JsonParser.parseString(json).getAsJsonObject();
            long parsed = System.nanoTime();
            String id = snapshot.get("levelId").getAsString();
            Path root = minecraft.getLevelSource().getBaseDir().resolve(id).normalize();
            int written = BrowserWorldSnapshot.apply(root, snapshot.getAsJsonArray("files"));
            long applied = System.nanoTime();
            BrowserGpu.reportProgress(
                    "server-worker:snapshot-applied level=" + id
                            + " files=" + written
                            + " chars=" + json.length()
                            + " parseMs=" + (parsed - started) / 1_000_000L
                            + " applyMs=" + (applied - parsed) / 1_000_000L
                            + " totalMs=" + (applied - started) / 1_000_000L);
        } catch (Throwable failure) {
            // Files are written one at a time, so a failure here can leave the
            // directory partly updated. Report it loudly: the next reopen is
            // where that would otherwise surface, as another "corrupted save".
            BrowserGpu.reportJavaFailure(
                    "server-worker-snapshot",
                    failure.getClass().getName(),
                    failure.getMessage() == null ? "" : failure.getMessage()
            );
        }
    }

    /**
     * The server image has its own heap, so its load listener cannot be composed
     * directly with the client's tracker. Consume the ordered event queue at the
     * client frame boundary and apply each event under the tracker's own monitor.
     * This keeps the loading UI tied to the server's actual stage rather than to
     * packet arrival or an unrelated host-side diagnostic ring.
     */
    private static void drainServerLoadProgress() {
        if (loadTracker == null) return;
        String batch = BrowserWorkerClientTransport.consumeLoadProgress();
        if (batch == null || batch.isEmpty()) return;
        for (String message : batch.split("\\n")) {
            applyServerLoadProgress(message);
        }
    }

    private static void applyServerLoadProgress(String message) {
        String[] parts = message.split(" ");
        if (parts.length < 2) return;
        if ("grid".equals(parts[0])) {
            BrowserChunkLoadStatusView view = BrowserChunkLoadStatusView.parse(parts);
            if (view != null && loadTracker != null) {
                loadTracker.setServerChunkStatusView(view);
            }
            return;
        }
        LevelLoadListener.Stage stage;
        try {
            stage = LevelLoadListener.Stage.valueOf(parts[1]);
        } catch (IllegalArgumentException ignored) {
            return;
        }
        try {
            switch (parts[0]) {
                case "start" -> {
                    if (parts.length < 3 || !parts[2].startsWith("total=")) return;
                    loadTracker.start(stage, Integer.parseInt(parts[2].substring("total=".length())));
                }
                case "update" -> {
                    if (parts.length < 3) return;
                    int slash = parts[2].indexOf('/');
                    if (slash <= 0 || slash == parts[2].length() - 1) return;
                    int ready = Integer.parseInt(parts[2].substring(0, slash));
                    int total = Integer.parseInt(parts[2].substring(slash + 1));
                    loadTracker.update(stage, ready, total);
                }
                case "finish" -> loadTracker.finish(stage);
                default -> {
                    // Focus markers and future listener extensions are not part of
                    // the scalar loading bar; leave them in the host diagnostic ring.
                }
            }
        } catch (NumberFormatException ignored) {
            // A malformed diagnostic event must not take down the client pump.
        }
    }

    public static boolean isActive() {
        return minecraft != null;
    }

    public static void stop() {
        if (workerConnection != null) {
            try {
                workerConnection.disconnect(Component.translatable("multiplayer.disconnect.generic"));
            } catch (Throwable ignored) {
                // Cleanup must continue even if a partially initialized listener rejects
                // the synthetic disconnect.
            }
            workerConnection = null;
        }
        if (minecraft != null) {
            BrowserWorkerClientTransport.stop();
        }
        minecraft = null;
        commandJson = null;
        loadTracker = null;
        levelId = null;
        levelWasLive = false;
        snapshotRequested = false;
        snapshotPending = false;
        pendingLaunch = false;
        pendingWorkerStop = false;
        pendingCommand = null;
        pendingWorldRoot = null;
        commandSent = false;
        connected = false;
        failureReported = false;
    }

    private static void connectClient() {
        Connection connection = Connection.connectToWorkerServer();
        workerConnection = connection;
        Duration elapsed = Duration.between(startedAt, Instant.now());
        ClientHandshakePacketListenerImpl listener = new ClientHandshakePacketListenerImpl(
                connection,
                minecraft,
                null,
                null,
                newWorld,
                elapsed,
                component -> { },
                loadTracker,
                null
        );
        connection.initiateServerboundPlayConnection("browser-worker", 0, listener);
        connection.send(new ServerboundHelloPacket(
                minecraft.getUser().getName(),
                minecraft.getUser().getProfileId()
        ));
        minecraft.pendingConnection = connection;
        minecraft.isLocalServer = true;
        minecraft.updateReportEnvironment(ReportEnvironment.local());
        connected = true;
        BrowserGpu.reportProgress("server-worker:client-connected");
    }

    private static void reportWorkerFailure(String state) {
        if (failureReported) {
            return;
        }
        failureReported = true;
        connected = false;
        Connection connection = workerConnection;
        workerConnection = null;
        if (connection != null) {
            try {
                connection.disconnect(Component.translatable("multiplayer.disconnect.endOfStream"));
            } catch (Throwable ignored) {
                // The diagnostic below is the authoritative failure signal.
            }
        }
        try {
            // An error is terminal for this transport. Release the host Worker now;
            // a later world start should not be responsible for collecting a failed
            // server instance as a side effect of its own cleanup.
            BrowserWorkerClientTransport.stop();
        } catch (Throwable ignored) {
            // Preserve the original Worker failure even if host cleanup is partial.
        }
        BrowserGpu.reportJavaFailure(
                "server-worker",
                "java.lang.IllegalStateException",
                "Integrated-server Worker entered state " + state
        );
    }

    private static JsonObject createCommand(
            Minecraft client,
            LevelStorageSource.LevelStorageAccess access,
            WorldStem stem,
            boolean creating
    ) throws Exception {
        JsonObject command = new JsonObject();
        command.addProperty("levelId", access.getLevelId());
        command.addProperty("playerName", client.getUser().getName());
        command.addProperty("playerId", client.getUser().getProfileId().toString());
        command.addProperty("viewDistance", client.options.renderDistance().get());
        command.addProperty("simulationDistance", client.options.simulationDistance().get());
        command.addProperty("entityDistanceScaling", client.options.entityDistanceScaling().get());
        command.addProperty("demo", client.isDemo());
        command.addProperty("synchronousWrites", client.options.syncWrites);
        command.addProperty("creating", creating);
        command.addProperty("persistWorld",
                BrowserWorkerClientTransport.isWorldWriteBackEnabled());
        net.minecraft.world.level.storage.LevelDataAndDimensions.WorldDataAndGenSettings data =
                stem.worldDataAndGenSettings();
        net.minecraft.world.level.LevelSettings settings = data.data().getLevelSettings();
        net.minecraft.world.level.levelgen.WorldGenSettings generation = data.genSettings();
        command.addProperty("levelName", settings.levelName());
        command.addProperty("gameType", settings.gameType().getName());
        command.addProperty("difficulty", settings.difficultySettings().difficulty().getId());
        command.addProperty("hardcore", settings.difficultySettings().hardcore());
        command.addProperty("difficultyLocked", settings.difficultySettings().locked());
        command.addProperty("allowCommands", settings.allowCommands());
        command.addProperty("seed", benchmarkSeed(generation.options().seed()));
        command.addProperty("generateStructures", generation.options().generateStructures());
        command.addProperty("bonusChest", generation.options().generateBonusChest());
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.levelgen.presets.WorldPreset> preset =
                net.minecraft.world.level.levelgen.presets.WorldPresets
                        .fromSettings(generation.dimensions())
                        .orElse(net.minecraft.world.level.levelgen.presets.WorldPresets.NORMAL);
        command.addProperty("worldPreset", preset.identifier().toString());

        // "files" is attached later, by finishPendingLaunch, so the world that
        // ships is the one after any in-flight save has been written back.
        return command;
    }

    /**
     * Keep the normal Create World UI authoritative, but let the benchmark harness
     * choose one seed for the private-server byte-boundary arm. The override is
     * deliberately read only while serializing the Worker command: it does not
     * change the client UI or the shipping integrated-server path.
     */
    private static String benchmarkSeed(long generatedSeed) {
        String requested = requestedBenchmarkSeed();
        if (requested == null || requested.isEmpty()) {
            return Long.toString(generatedSeed);
        }
        try {
            return Long.toString(Long.parseLong(requested));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("mcweb_seed must be a signed 64-bit integer", invalid);
        }
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_seed') || '';", args = {})
    private static native String requestedBenchmarkSeed();
}
