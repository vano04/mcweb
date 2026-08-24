package dev.mcweb.graal;

import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.io.File;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.User;
import net.minecraft.client.main.GameConfig;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.server.Bootstrap;
import org.graalvm.webimage.api.JS;

/**
 * Browser launcher for the real client. It omits only desktop launcher/native
 * setup and enters Minecraft through its original GameConfig constructor.
 */
public final class BrowserMinecraftMain {
    private static Minecraft minecraft;
    private static boolean benchmarkSeedApplied;

    private BrowserMinecraftMain() {
    }

    public static void main(String[] args) throws Throwable {
        if (args.length > 0 && "--decode-worker".equals(args[0])) {
            BrowserDecodeWorkerMain.main(args);
            return;
        }
        if (args.length > 0 && "--mesh-worker".equals(args[0])) {
            BrowserMeshWorkerMain.main(args);
            return;
        }
        if (args.length > 0 && "--server".equals(args[0])) {
            BrowserWorkerServerMain.main(args);
            return;
        }
        // Web Image buffers are managed heap views; force JOML's safe NIO
        // writers instead of its desktop Unsafe/direct-address fast path.
        // SLF4J picks a provider by ServiceLoader, and log4j-slf4j2-impl is on
        // the classpath offering one — while the log4j backend itself is
        // excluded from the image. Bound to that, every Mojang log line is
        // discarded, which is why a full world load emitted 1317 console lines
        // and zero [MC] ones and every `catch { LOGGER.warn(...) }` in vanilla
        // was a black hole. Name our provider explicitly (slf4j-api 2.0.9+).
        System.setProperty("slf4j.provider", "dev.mcweb.graal.log.McwebSlf4jProvider");
        System.setProperty("joml.nounsafe", "true");
        // Graal Web Image reports os.name=Browser, which LWJGL's Platform
        // clinit rejects with LinkageError. Claim a supported desktop OS so
        // library path mappers can initialize; natives are never loaded.
        if ("Browser".equals(System.getProperty("os.name"))) {
            System.setProperty("os.name", "Linux");
        }
        String arch = System.getProperty("os.arch", "");
        if (arch.isEmpty() || "browser".equalsIgnoreCase(arch) || "wasm".equalsIgnoreCase(arch)) {
            System.setProperty("os.arch", "aarch64");
        }
        String stage = "SharedConstants.tryDetectVersion";
        BrowserGpu.reportProgress(stage);

        try {
            SharedConstants.tryDetectVersion();
            stage = "Bootstrap.bootStrap";
            BrowserGpu.reportProgress(stage);
            Bootstrap.bootStrap();
            stage = "ClientBootstrap.bootstrap";
            BrowserGpu.reportProgress(stage);
            ClientBootstrap.bootstrap();
            stage = "Bootstrap.validate";
            BrowserGpu.reportProgress(stage);
            Bootstrap.validate();
            stage = "RegionFileVersion.configure";
            BrowserGpu.reportProgress(stage);
            // Web Image has no native zlib. The classpath-first LZ4/XXHash
            // factories bind lz4-java's JavaSafe implementations directly, so
            // browser storage can use Minecraft's compressed region format.
            net.minecraft.world.level.chunk.storage.RegionFileVersion.configure("lz4");
            stage = "RenderSystem.initRenderThread";
            BrowserGpu.reportProgress(stage);
            RenderSystem.initRenderThread();

            File gameDirectory = new File("/tmp/mcgame");
            gameDirectory.mkdirs();
            BrowserPersistentStorage.restoreStartup(gameDirectory.toPath());
            GameConfig config = createGameConfig(gameDirectory);

            // Probe LWJGL seams that fail in static initializers before the
            // monolithic constructor swallows the class name.
            stage = "probe.MemoryUtil";
            BrowserGpu.reportProgress(stage);
            org.lwjgl.system.MemoryUtil.memAlloc(16).clear();
            stage = "probe.MemoryStack";
            BrowserGpu.reportProgress(stage);
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                stack.malloc(16);
            }
            stage = "probe.PointerBuffer";
            BrowserGpu.reportProgress(stage);
            org.lwjgl.PointerBuffer.allocateDirect(4);

            stage = "probe.MinecraftClassInit";
            BrowserGpu.reportProgress(stage);
            Class.forName("net.minecraft.client.Minecraft");

            stage = "probe.DataFixers";
            BrowserGpu.reportProgress(stage);
            net.minecraft.util.datafix.DataFixers.getDataFixer();

            stage = "probe.ClientPackSource";
            BrowserGpu.reportProgress(stage);
            net.minecraft.world.level.validation.DirectoryValidator validator =
                    net.minecraft.world.level.storage.LevelStorageSource.parseValidator(
                            gameDirectory.toPath().resolve("allowed_symlinks.txt")
                    );
            net.minecraft.client.resources.ClientPackSource packSource =
                    new net.minecraft.client.resources.ClientPackSource(
                            config.location.getExternalAssetSource(),
                            validator
                    );

            stage = "probe.AuthService";
            BrowserGpu.reportProgress(stage);
            // Browser-safe authlib factory: passes ServicesKeySet.EMPTY and skips
            // YggdrasilServicesKeyInfo.get, whose static scheduled executor
            // starts a daemon thread (IllegalThreadStateException in Web Image)
            // and blocks on CompletableFuture.join. The local Node gateway has
            // already required a live official Launcher session before this image starts;
            // authlib URL construction still runs for the profile bridges.
            com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService authService =
                    com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService.createOffline(Proxy.NO_PROXY);
            net.minecraft.server.Services services =
                    net.minecraft.server.Services.create(authService, gameDirectory);

            fsRecon();

            stage = "Minecraft(GameConfig)";
            BrowserGpu.reportProgress(stage);
            try {
                minecraft = new Minecraft(config);
            } catch (Throwable t) {
                stage = "Minecraft(GameConfig).failed:" + t.getClass().getName() + ":" + t.getMessage();
                BrowserGpu.reportProgress(stage);
                throw t;
            }
            InlineExecutorService.activateMainLoopDrain();
            clampRenderDistance();
            configureFrameOptions();
            benchmarkSeedApplied = false;
            BrowserSkinTextureCompat.installRuntimeProbe();
            BrowserGpu.reportSuccess(
                    0xFF59A64D,
                    "WebGPU",
                    "Minecraft(GameConfig) constructed from the 26.2 JAR"
            );

            // One-shot: which @JS argument shapes actually cross the boundary.
            // SoundManager's call throws before reaching JavaScript and Web
            // Image strips stack traces, so the shape has to be bisected here.
            BrowserAudio.reportBridgeSelfTest();

            // Hand scheduling to the browser: one real runTick per browser
            // frame task. Desktop Minecraft.run() is never called as a
            // blocking loop; BrowserFramePump honors the real VSync/max-FPS
            // options configured above.
            BrowserFramePump.start(minecraft);
        } catch (Throwable failure) {
            String activeStage = MinecraftInitProgress.lastStage;
            if (activeStage == null || activeStage.isEmpty()) {
                activeStage = stage;
            }
            String detail = describeFailure(failure);
            if (isHeapExhaustion(failure)) {
                detail += " | " + describeHeapAtFailure();
            }
            BrowserGpu.reportJavaFailure(
                    activeStage,
                    failure.getClass().getName(),
                    detail
            );
            throw failure;
        }
    }

    /** Render distance the browser heap can actually mesh; see below. */
    private static final int BROWSER_RENDER_DISTANCE = 8;

    /**
     * Widest view worth attempting. Vanilla's own maximum is 32; anything above
     * that is a typo rather than an experiment.
     */
    private static final int MAX_RENDER_DISTANCE = 32;

    /**
     * The normal client and a private mesh image need the same browser-shaped
     * GameConfig. Keeping this construction in one place also makes it explicit
     * that the mesh image gets its own virtual save directory.
     */
    static GameConfig createGameConfig(File gameDirectory) {
        String name;
        UUID uuid;
        try {
            String launcherName = BrowserRemoteTransport.launcherProfileName();
            String launcherId = BrowserRemoteTransport.launcherProfileId();
            UUID authenticatedId = parseProfileId(launcherId);
            if (authenticatedId == null || launcherName == null
                    || launcherName.isEmpty() || launcherName.length() > 16) {
                throw new IllegalStateException("authenticated official Launcher profile required");
            }
            name = launcherName;
            uuid = authenticatedId;
        } catch (Throwable unavailable) {
            throw new IllegalStateException("authenticated official Launcher profile required", unavailable);
        }
        User user = new User(name, uuid, "", Optional.empty(), Optional.empty());
        gameDirectory.mkdirs();
        File optionsFile = new File(gameDirectory, "options.txt");
        if (!optionsFile.exists()) {
            try {
                java.nio.file.Files.writeString(optionsFile.toPath(),
                        "version:4903\nmipmapLevels:0\n"
                                + "enableVsync:false\nmaxFps:260\n",
                        StandardCharsets.UTF_8);
            } catch (java.io.IOException optionsFailure) {
                // Non-fatal: the client falls back to defaults.
            }
        }

        return new GameConfig(
                new GameConfig.UserData(user, Proxy.NO_PROXY),
                new DisplayData(
                        BrowserGpu.canvasWidth(),
                        BrowserGpu.canvasHeight(),
                        OptionalInt.empty(),
                        OptionalInt.empty(),
                        false
                ),
                new GameConfig.FolderData(
                        gameDirectory,
                        new File(gameDirectory, "resourcepacks"),
                        new File("/assets"),
                        null
                ),
                new GameConfig.GameData(
                        false,
                        "26.2",
                        "release",
                        true,
                        true,
                        false,
                        false,
                        false,
                        PreferredGraphicsApi.DEFAULT,
                        true
                ),
                new GameConfig.QuickPlayData(
                        null,
                        GameConfig.QuickPlayVariant.DISABLED
                )
        );
    }

    private static UUID parseProfileId(String raw) {
        if (raw == null) {
            return null;
        }
        String compact = raw.replace("-", "");
        if (compact.length() != 32) {
            return null;
        }
        try {
            return UUID.fromString(
                    compact.substring(0, 8) + "-"
                            + compact.substring(8, 12) + "-"
                            + compact.substring(12, 16) + "-"
                            + compact.substring(16, 20) + "-"
                            + compact.substring(20)
            );
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Applies the optional benchmark world overrides to the real Create World
     * state once it exists. The browser test still drives Mojang's own screen
     * and create button; these only remove otherwise random variation from a
     * controlled run. No query parameter means the shipping UI is unchanged.
     *
     * <p>{@code ?mcweb_seed=N} fixes the seed (existing).
     * {@code ?mcweb_gamemode=creative} selects the creative game mode.
     * {@code ?mcweb_worldtype=superflat} selects the flat world preset. Both
     * use the same {@code WorldCreationUiState} setters the UI buttons call,
     * so the Worker command carries the selection exactly as a hand click
     * would.</p>
     */
    static void applyBenchmarkSeedIfPresent() {
        if (benchmarkSeedApplied || minecraft == null) {
            return;
        }
        String seed = requestedBenchmarkSeed();
        String gameMode = requestedBenchmarkGameMode();
        String worldType = requestedBenchmarkWorldType();
        if (seed.isEmpty() && gameMode.isEmpty() && worldType.isEmpty()) {
            benchmarkSeedApplied = true;
            return;
        }
        if (!(minecraft.gui.screen() instanceof CreateWorldScreen createWorld)) {
            return;
        }
        net.minecraft.client.gui.screens.worldselection.WorldCreationUiState uiState =
                createWorld.getUiState();
        if ("superflat".equalsIgnoreCase(worldType)) {
            boolean found = false;
            for (net.minecraft.client.gui.screens.worldselection
                    .WorldCreationUiState.WorldTypeEntry entry : uiState.getNormalPresetList()) {
                if (entry.preset().is(net.minecraft.world.level.levelgen.presets
                        .WorldPresets.FLAT)) {
                    uiState.setWorldType(entry);
                    found = true;
                    break;
                }
            }
            BrowserGpu.reportProgress(found
                    ? "benchmark:worldtype=superflat"
                    : "benchmark:worldtype=superflat NOT FOUND in preset list");
        }
        if ("1".equals(requestedBenchmarkCheats())) {
            // Allow commands so probes can /summon mobs and run controlled
            // combat experiments; vanilla ships the toggle as
            // WorldCreationUiState.setAllowCommands.
            uiState.setAllowCommands(true);
            BrowserGpu.reportProgress("benchmark:cheats=on");
        }
        if ("creative".equalsIgnoreCase(gameMode)) {
            uiState.setGameMode(net.minecraft.client.gui.screens.worldselection
                    .WorldCreationUiState.SelectedGameMode.CREATIVE);
            BrowserGpu.reportProgress("benchmark:gamemode=creative");
        }
        // Seed last, mirroring the UI flow where the type is chosen before the
        // seed is edited: setWorldType rebuilds the world dimensions.
        if (!seed.isEmpty()) {
            uiState.setSeed(seed);
            BrowserGpu.reportProgress("benchmark:seed=" + seed);
        }
        benchmarkSeedApplied = true;
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_seed') || '';", args = {})
    private static native String requestedBenchmarkSeed();

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_cheats') || '';", args = {})
    private static native String requestedBenchmarkCheats();

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_gamemode') || '';", args = {})
    private static native String requestedBenchmarkGameMode();

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_worldtype') || '';", args = {})
    private static native String requestedBenchmarkWorldType();

    /**
     * Sets render distance after construction, defaulting to what the heap can mesh.
     *
     * <p>Set here rather than in the pre-seeded {@code options.txt}: the file
     * is written before the client starts, but a {@code renderDistance:8} line
     * in it demonstrably did not stick — the option still read 16 in-world —
     * whereas this runs against the constructed {@code Options} and is
     * verifiable from the same probe that reads it.</p>
     *
     * <p>Why at all: the default 16 gives a {@code ViewArea} of 33*33*24 =
     * 26136 sections. The Wasm heap runs out a few frames into meshing, and the
     * resulting bare {@code OutOfMemoryError} stops the frame pump permanently
     * — which is the "terrain does not render" symptom, not anything wrong in
     * the section pipeline (measured healthy: visible/compiled/renderable all
     * climb until the crash). 8 is 17*17*24 = 6936 sections.</p>
     */
    private static void clampRenderDistance() {
        try {
            // Set unconditionally rather than only clamping down, so a requested
            // distance is the distance measured. The old form was a one-sided
            // clamp, which silently left Mojang's default in place whenever the
            // request was at or above it.
            int requested = requestedRenderDistance();
            if (minecraft.options.renderDistance().get() != requested) {
                minecraft.options.renderDistance().set(requested);
            }
            BrowserGpu.reportProgress(
                    "options:renderDistance=" + minecraft.options.renderDistance().get()
            );
        } catch (Throwable optionFailure) {
            // A clamp that fails is a performance problem, not a boot problem.
            BrowserGpu.reportProgress(
                    "options:renderDistance-failed:" + optionFailure.getClass().getName()
            );
        }
    }

    /**
     * Browser defaults for the real Minecraft video options.
     *
     * <p>The browser filesystem is in-memory, so the repository-level
     * {@code minecraft/options.txt} is not the file read by the constructed
     * client. Apply these defaults to Minecraft's own {@code OptionInstance}s,
     * exactly as the Video Settings screen would. Query parameters retain a
     * deterministic test/manual override; later in-game changes are observed
     * by {@link BrowserFramePump} and immediately reconfigure the host.</p>
     */
    private static void configureFrameOptions() {
        try {
            boolean vsync = requestedVsync();
            int maxFps = requestedMaxFps();
            minecraft.options.enableVsync().set(vsync);
            minecraft.options.framerateLimit().set(maxFps);
            BrowserGpu.reportProgress("options:vsync="
                    + minecraft.options.enableVsync().get()
                    + " maxFps=" + minecraft.options.framerateLimit().get());
        } catch (Throwable optionFailure) {
            BrowserGpu.reportProgress(
                    "options:frame-pacing-failed:" + optionFailure.getClass().getName()
            );
        }
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_vsync') === '1';", args = {})
    private static native boolean requestedVsync();

    @JS.Coerce
    @JS(value = "const raw = new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_max_fps');"
            + "const value = Number(raw);"
            + "return Number.isInteger(value) && value >= 10 && value <= 260 ? value : 260;",
            args = {})
    private static native int requestedMaxFps();

    /**
     * Render distance for this launch, from {@code ?mcweb_render_distance=N}.
     *
     * <p>A query parameter rather than a constant because sweeping the view is
     * the measurement: each rebuild of this image costs about nine minutes, so
     * baking the distance in makes a three-point sweep three builds. The client
     * also sends this value to the private server Worker as {@code viewDistance}
     * (see {@code BrowserWorkerClientCompat}), so one knob moves both sides and
     * the server cannot end up feeding a wider client an 8-chunk radius.</p>
     *
     * <p>Out-of-range and unparseable values fall back to the default rather
     * than failing the launch: a typo in a query string should cost a
     * measurement, not a boot.</p>
     */
    private static int requestedRenderDistance() {
        String requested = requestedRenderDistanceParam();
        if (requested == null || requested.isEmpty()) {
            return BROWSER_RENDER_DISTANCE;
        }
        try {
            int value = Integer.parseInt(requested.trim());
            if (value < 2 || value > MAX_RENDER_DISTANCE) {
                BrowserGpu.reportProgress("options:renderDistance-out-of-range=" + value);
                return BROWSER_RENDER_DISTANCE;
            }
            return value;
        } catch (NumberFormatException malformed) {
            BrowserGpu.reportProgress("options:renderDistance-malformed=" + requested);
            return BROWSER_RENDER_DISTANCE;
        }
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_render_distance') || '';", args = {})
    private static native String requestedRenderDistanceParam();

    /**
     * Fixed-size OOM-time dump: final heap numbers plus the largest live
     * synthetic-native blocks. Allocations are minimized because the heap is
     * already exhausted when this runs.
     */
    static String describeHeapAtFailure() {
        try {
            return MinecraftInitProgress.heapStats() + " "
                    + org.lwjgl.system.BrowserNativeMemory.describeLargest(12);
        } catch (Throwable dumpFailure) {
            return "heap-dump-failed:" + dumpFailure.getClass().getName();
        }
    }

    static boolean isHeapExhaustion(Throwable failure) {
        Throwable cursor = failure;
        int depth = 0;
        while (cursor != null && depth < 8) {
            if (cursor instanceof OutOfMemoryError) {
                return true;
            }
            Throwable cause = cursor.getCause();
            if (cause == cursor) {
                break;
            }
            cursor = cause;
            depth++;
        }
        return false;
    }

    /**
     * Web Image often leaves outer wrapper messages empty
     * ({@code ExceptionInInitializerError}); surface the cause chain so the
     * browser host can show the next real seam.
     */
    static String describeFailure(Throwable failure) {
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
            // Include frames from every cause; Web Image often blanks the outer
            // stack while the root NPE still names the initializer class.
            StackTraceElement[] stack = cursor.getStackTrace();
            int frames = Math.min(stack == null ? 0 : stack.length, 8);
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

    /**
     * Crash reports carry the useful owner/stage details that a
     * {@link net.minecraft.ReportedException}'s message drops.  Keep the dump
     * bounded because this is a diagnostic path that can run while the heap is
     * already under pressure.
     */
    static void reportCrashReport(String prefix, net.minecraft.CrashReport report) {
        if (report == null) {
            return;
        }
        String details = report.getDetails();
        int chunkSize = 1400;
        int count = Math.max(1, (details.length() + chunkSize - 1) / chunkSize);
        for (int index = 0; index < count; index++) {
            int start = index * chunkSize;
            int end = Math.min(details.length(), start + chunkSize);
            BrowserGpu.reportProgress(prefix + (index + 1) + '/' + count + ':'
                    + details.substring(start, end));
        }
    }

    public static Minecraft minecraft() {
        return minecraft;
    }

    /**
     * One-shot filesystem reconnaissance: what does the browser image's
     * virtual FS expose, and can image-embedded resources be streamed?
     */
    static void fsRecon() {
        try {
            StringBuilder report = new StringBuilder();
            report.append("user.dir=").append(System.getProperty("user.dir"));
            java.nio.file.Path cwd = java.nio.file.Path.of(System.getProperty("user.dir", "/"));
            report.append(" | root:");
            try (var stream = java.nio.file.Files.list(cwd.getRoot())) {
                stream.limit(15).forEach(p -> report.append(' ').append(p));
            } catch (Throwable failure) {
                report.append(" list-fail:").append(failure.getClass().getSimpleName());
            }
            report.append(" | cwd:");
            try (var stream = java.nio.file.Files.list(cwd)) {
                stream.limit(15).forEach(p -> report.append(' ').append(p));
            } catch (Throwable failure) {
                report.append(" list-fail:").append(failure.getClass().getSimpleName());
            }
            try {
                var codeSource = Minecraft.class.getProtectionDomain().getCodeSource();
                report.append(" | codeSource=").append(
                        codeSource == null ? "null" : String.valueOf(codeSource.getLocation()));
            } catch (Throwable failure) {
                report.append(" | codeSource-fail:").append(failure.getClass().getSimpleName());
            }
            for (String candidate : new String[]{
                    "/minecraft-26.2-browser-input.jar",
                    "/minecraft/minecraft-26.2-browser-input.jar",
                    "/minecraft",
                    "/assets/minecraft/textures/gui/title/background/panorama_0.png"
            }) {
                boolean exists;
                try {
                    exists = java.nio.file.Files.exists(java.nio.file.Path.of(candidate));
                } catch (Throwable failure) {
                    report.append(" | ").append(candidate).append("=err");
                    continue;
                }
                report.append(" | ").append(candidate).append('=').append(exists ? "YES" : "no");
            }
            try (var input = BrowserMinecraftMain.class.getResourceAsStream(
                    "/assets/minecraft/textures/gui/title/background/panorama_0.png")) {
                if (input == null) {
                    report.append(" | resStream=null");
                } else {
                    report.append(" | resStream=").append(input.readAllBytes().length).append("B");
                }
            } catch (Throwable failure) {
                report.append(" | resStream-fail:").append(failure.getClass().getSimpleName());
            }
            BrowserGpu.reportProgress("fsrecon:" + report);
        } catch (Throwable failure) {
            BrowserGpu.reportProgress("fsrecon-outer-fail:" + failure);
        }
    }
}
