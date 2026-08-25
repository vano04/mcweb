package dev.mcweb.graal;

import java.io.File;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Entry point for the server Worker instance. Runs Minecraft's integrated
 * server in a dedicated Web Worker, communicating with the client instance
 * on the main thread via {@link BrowserWorkerTransport}.
 *
 * <p>Activated when the Wasm image is launched with {@code --server} as the
 * first argument (see {@code server-worker.js}).
 */
public final class BrowserWorkerServerMain {
    private static net.minecraft.client.server.IntegratedServer server;
    private static boolean initialized;
    private static boolean transportAttached;
    private static long pumpCount;

    private BrowserWorkerServerMain() {
    }

    public static void main(String[] args) throws Throwable {
        // SLF4J picks a provider by ServiceLoader, and log4j-slf4j2-impl is on
        // the classpath offering one — while the log4j backend itself is
        // excluded from the image. Bound to that, every Mojang log line is
        // discarded, which is why a full world load emitted 1317 console lines
        // and zero [MC] ones and every `catch { LOGGER.warn(...) }` in vanilla
        // was a black hole. Name our provider explicitly (slf4j-api 2.0.9+).
        System.setProperty("slf4j.provider", "dev.mcweb.graal.log.McwebSlf4jProvider");
        System.setProperty("joml.nounsafe", "true");
        if ("Browser".equals(System.getProperty("os.name"))) {
            System.setProperty("os.name", "Linux");
        }
        String arch = System.getProperty("os.arch", "");
        if (arch.isEmpty() || "browser".equalsIgnoreCase(arch) || "wasm".equalsIgnoreCase(arch)) {
            System.setProperty("os.arch", "aarch64");
        }

        String stage = "server:SharedConstants";
        BrowserWorkerTransport.reportProgress(stage);

        try {
            SharedConstants.tryDetectVersion();
            stage = "server:Bootstrap";
            BrowserWorkerTransport.reportProgress(stage);
            Bootstrap.bootStrap();
            stage = "server:Bootstrap.validate";
            BrowserWorkerTransport.reportProgress(stage);
            Bootstrap.validate();

            File gameDirectory = new File("/tmp/mcgame-server");
            gameDirectory.mkdirs();

            stage = "server:transport-check";
            BrowserWorkerTransport.reportProgress(stage);
            if (!BrowserWorkerTransport.isAvailable()) {
                throw new IllegalStateException(
                        "mcWebServerTransport not available; "
                        + "server-worker.js must set it up before Wasm runs");
            }

            BrowserWorkerTransport.onPacket(BrowserWorkerServerMain::onPacket);
            BrowserWorkerTransport.onCommand(BrowserWorkerServerMain::startWorld);
            BrowserWorkerTransport.onState(BrowserWorkerServerMain::onState);

            stage = "server:ready";
            BrowserWorkerTransport.reportProgress(stage);

            BrowserWorkerTransport.registerTickCallback(BrowserWorkerServerMain::tick);
        } catch (Throwable failure) {
            BrowserWorkerTransport.reportFailure(
                    stage,
                    failure.getClass().getName(),
                    describeFailure(failure)
            );
            throw failure;
        }
    }

    static void tick() {
        try {
            if (server == null) {
                return;
            }
            if (!initialized) {
                /*
                 * The cooperative server pump owns the browser-specific deadline,
                 * chunk-source polling, and bounded task drains. Calling raw
                 * initServer/tickServer here skips those seams: the Worker reaches
                 * login, then prepareLevels leaves LOAD_PLAYER_CHUNKS at 0/9 because
                 * the server's next-tick deadline is already expired when its task
                 * queue is polled.
                 */
                BrowserIntegratedServerCompat.pump();
                if (!server.isReady) {
                    return;
                }
                initialized = true;
                BrowserWorkerTransport.reportProgress("server:initialized");
                server.getConnection().startMemoryChannel();
                net.minecraft.network.Connection.attachPendingWorkerServer();
                transportAttached = true;
                BrowserWorkerTransport.reportProgress("server:network-ready");
                BrowserWorkerTransport.reportProgress("server:world-ready");
                pumpCount++;
                BrowserWorkerTransport.reportTick(
                        BrowserIntegratedServerCompat.completedTickCount());
                return;
            }

            BrowserIntegratedServerCompat.pump();
            pumpCount++;
            BrowserWorkerTransport.reportTick(
                    BrowserIntegratedServerCompat.completedTickCount());
        } catch (Throwable failure) {
            BrowserWorkerTransport.reportFailure(
                    "server:pump#" + pumpCount,
                    failure.getClass().getName(),
                    describeFailure(failure)
            );
        }
    }

    private static void startWorld(String commandJson) {
        if (server != null) {
            throw new IllegalStateException("A Worker world is already running");
        }
        try {
            BrowserWorkerTransport.reportProgress("server:world-load");
            server = BrowserWorkerWorldLoader.load(commandJson);
            // This entrypoint constructs IntegratedServer directly instead of
            // going through MinecraftServer.spin, so the normal browser seam
            // cannot register it for BrowserIntegratedServerCompat.pump().
            BrowserIntegratedServerCompat.register(server);
            // The vanilla chunk-status grid is wired in Minecraft.doWorldLoad after
            // MinecraftServer.spin — code this lane never runs. Stream it instead.
            BrowserIntegratedServerCompat.enableChunkGrid();
            initialized = false;
            transportAttached = false;
            pumpCount = 0L;
            BrowserWorkerTransport.reportProgress("server:constructed");
        } catch (Throwable failure) {
            BrowserWorkerTransport.reportFailure(
                    "server:world-load",
                    failure.getClass().getName(),
                    describeFailure(failure)
            );
        }
    }

    private static void onState(String state) {
        if ("world-entered".equals(state)) {
            BrowserIntegratedServerCompat.markClientWorldEntered();
        } else if ("save-snapshot".equals(state)) {
            sendWorldSnapshot();
        }
    }

    /**
     * Saves the world and ships the whole directory back to the client realm.
     *
     * <p>The client's copy of the save is frozen at world start — this Worker
     * has its own filesystem, and nothing the server writes has ever crossed
     * back. That includes {@code data/world_gen_settings.dat}, which 26.2 moved
     * out of {@code level.dat} and which the client needs to reopen the world at
     * all; without it {@code WorldDimensions.bake} throws "Overworld settings
     * missing" and the world reads as corrupted.</p>
     */
    private static void sendWorldSnapshot() {
        java.nio.file.Path worldDirectory = BrowserWorkerWorldLoader.worldDirectory();
        if (server == null || worldDirectory == null) {
            // No world here to save. The empty answer is what the client reads
            // as "nothing came back", rather than leaving the request unanswered.
            BrowserWorkerTransport.sendSnapshot("");
            return;
        }
        try {
            BrowserWorkerTransport.reportProgress("server:snapshot-saving");
            long started = System.nanoTime();
            server.saveEverything(true, true, true);
            long saved = System.nanoTime();
            String snapshot = BrowserWorldSnapshot.capture(
                    BrowserWorkerWorldLoader.levelId(),
                    worldDirectory
            );
            long captured = System.nanoTime();
            BrowserWorkerTransport.sendSnapshot(snapshot);
            BrowserWorkerTransport.reportProgress("server:snapshot-sent chars=" + snapshot.length()
                    + " saveMs=" + (saved - started) / 1_000_000L
                    + " captureMs=" + (captured - saved) / 1_000_000L
                    + " totalMs=" + (captured - started) / 1_000_000L);
        } catch (Throwable failure) {
            // Send the empty answer rather than none, so a failed save cannot be
            // mistaken for one still in flight, and report the cause separately.
            BrowserWorkerTransport.sendSnapshot("");
            BrowserWorkerTransport.reportFailure(
                    "server:snapshot",
                    failure.getClass().getName(),
                    describeFailure(failure)
            );
        }
    }

    private static void onPacket(byte[] data) {
        if (!transportAttached) {
            BrowserWorkerTransport.send(data);
            return;
        }
        net.minecraft.network.Connection.receiveWorkerServerFrame(data);
    }


    private static String describeFailure(Throwable failure) {
        StringBuilder detail = new StringBuilder();
        Throwable cursor = failure;
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (depth > 0) {
                detail.append(" <- ");
            }
            detail.append(cursor.getClass().getName());
            String message = cursor.getMessage();
            if (message != null && !message.isEmpty()) {
                detail.append(": ").append(message);
            }
            StackTraceElement[] stack = cursor.getStackTrace();
            int frames = Math.min(stack == null ? 0 : stack.length, 6);
            for (int i = 0; i < frames; i++) {
                detail.append(" @ ").append(stack[i].toString());
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) {
                break;
            }
            cursor = cause;
            depth++;
        }
        return detail.toString();
    }
}
