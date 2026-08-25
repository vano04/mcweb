package dev.mcweb.graal;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import com.mojang.serialization.Dynamic;
import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.commands.Commands;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Services;
import net.minecraft.server.WorldLoader;
import net.minecraft.server.WorldStem;
import net.minecraft.server.level.progress.LoggingLevelLoadListener;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;

/** Reconstructs a client-prepared world inside the server Worker's own heap. */
public final class BrowserWorkerWorldLoader {
    private static final Path GAME_DIRECTORY = Path.of("/tmp/mcgame-server");
    private static final Path SAVES_DIRECTORY = GAME_DIRECTORY.resolve("saves");

    /** Set by {@link #load}, so the snapshot write-back knows what to ship home. */
    private static volatile String loadedLevelId;
    private static volatile Path loadedWorldDirectory;

    private BrowserWorkerWorldLoader() {
    }

    public static String levelId() {
        return loadedLevelId;
    }

    public static Path worldDirectory() {
        return loadedWorldDirectory;
    }

    public static IntegratedServer load(String commandJson) throws Exception {
        JsonObject command = JsonParser.parseString(commandJson).getAsJsonObject();
        String levelId = requiredString(command, "levelId");
        String playerName = requiredString(command, "playerName");
        UUID playerId = UUID.fromString(requiredString(command, "playerId"));
        int viewDistance = command.get("viewDistance").getAsInt();
        int simulationDistance = command.get("simulationDistance").getAsInt();
        double entityDistanceScaling = command.get("entityDistanceScaling").getAsDouble();
        boolean demo = command.get("demo").getAsBoolean();
        boolean synchronousWrites = command.get("synchronousWrites").getAsBoolean();
        boolean persistWorld = command.has("persistWorld")
                && command.get("persistWorld").getAsBoolean();

        // Web Image has no zlib. Mojang's built-in LZ4 region format remains
        // fully compatible with RegionFile's per-chunk version ids, while the
        // classpath-first factories bind lz4-java's JavaSafe implementations
        // without reflection. Keep writes disabled only for the explicit
        // no-write-back diagnostic arm, whose private filesystem is discarded.
        if (persistWorld) {
            net.minecraft.world.level.chunk.storage.RegionFileVersion.configure("lz4");
        }
        BrowserWorkerTransport.reportProgress("server:persist-world=" + persistWorld
                + " codec=" + (persistWorld ? "lz4" : "discard"));

        Files.createDirectories(SAVES_DIRECTORY);
        Path worldDirectory = SAVES_DIRECTORY.resolve(levelId).normalize();
        if (!worldDirectory.startsWith(SAVES_DIRECTORY)) {
            throw new IllegalArgumentException("World id escapes the saves directory");
        }
        Files.createDirectories(worldDirectory);
        BrowserWorldSnapshot.apply(worldDirectory, command.getAsJsonArray("files"));
        loadedLevelId = levelId;
        loadedWorldDirectory = worldDirectory;

        BrowserIntegratedServerPlatform.configure(
                playerName,
                playerId,
                viewDistance,
                simulationDistance,
                entityDistanceScaling,
                demo,
                synchronousWrites
        );

        LevelStorageSource levelSource = LevelStorageSource.createDefault(SAVES_DIRECTORY);
        LevelStorageSource.LevelStorageAccess access = levelSource.validateAndCreateAccess(levelId);
        PackRepository packs = ServerPacksSource.createPackRepository(access);
        WorldStem stem = command.get("creating").getAsBoolean()
                ? loadFreshWorld(command, packs)
                : loadExistingWorld(access, packs);

        YggdrasilAuthenticationService authentication =
                YggdrasilAuthenticationService.createOffline(Proxy.NO_PROXY);
        Services services = Services.create(authentication, GAME_DIRECTORY.toFile());
        return new IntegratedServer(
                Thread.currentThread(),
                null,
                access,
                packs,
                stem,
                Optional.empty(),
                services,
                LoggingLevelLoadListener.forSingleplayer()
        );
    }

    private static WorldStem loadFreshWorld(JsonObject command, PackRepository packs)
            throws Exception {
        WorldLoader.PackConfig packConfig = new WorldLoader.PackConfig(
                packs,
                net.minecraft.world.level.WorldDataConfiguration.DEFAULT,
                false,
                false
        );
        WorldLoader.InitConfig initConfig = initConfig(packConfig);
        CompletableFuture<WorldStem> loaded = WorldLoader.load(
                initConfig,
                context -> {
                    net.minecraft.resources.Identifier presetId =
                            net.minecraft.resources.Identifier.parse(
                                    requiredString(command, "worldPreset")
                            );
                    net.minecraft.resources.ResourceKey<
                            net.minecraft.world.level.levelgen.presets.WorldPreset> presetKey =
                            net.minecraft.resources.ResourceKey.create(
                                    Registries.WORLD_PRESET,
                                    presetId
                            );
                    net.minecraft.core.HolderLookup.RegistryLookup<
                            net.minecraft.world.level.levelgen.presets.WorldPreset> presets =
                            context.datapackWorldgen().lookupOrThrow(Registries.WORLD_PRESET);
                    net.minecraft.world.level.levelgen.WorldDimensions dimensions =
                            presets.getOrThrow(presetKey).value().createWorldDimensions();
                    net.minecraft.world.level.levelgen.WorldDimensions.Complete complete =
                            dimensions.bake(
                                    context.datapackDimensions()
                                            .lookupOrThrow(Registries.LEVEL_STEM)
                            );
                    net.minecraft.world.level.LevelSettings settings =
                            new net.minecraft.world.level.LevelSettings(
                                    requiredString(command, "levelName"),
                                    net.minecraft.world.level.GameType.byName(
                                            requiredString(command, "gameType")
                                    ),
                                    new net.minecraft.world.level.LevelSettings.DifficultySettings(
                                            net.minecraft.world.Difficulty.byId(
                                                    command.get("difficulty").getAsInt()
                                            ),
                                            command.get("hardcore").getAsBoolean(),
                                            command.get("difficultyLocked").getAsBoolean()
                                    ),
                                    command.get("allowCommands").getAsBoolean(),
                                    context.dataConfiguration()
                            );
                    net.minecraft.world.level.levelgen.WorldOptions options =
                            new net.minecraft.world.level.levelgen.WorldOptions(
                                    Long.parseLong(requiredString(command, "seed")),
                                    command.get("generateStructures").getAsBoolean(),
                                    command.get("bonusChest").getAsBoolean()
                            );
                    net.minecraft.world.level.storage.PrimaryLevelData worldData =
                            new net.minecraft.world.level.storage.PrimaryLevelData(
                                    settings,
                                    complete.specialWorldProperty(),
                                    complete.lifecycle()
                            );
                    net.minecraft.world.level.levelgen.WorldGenSettings generation =
                            new net.minecraft.world.level.levelgen.WorldGenSettings(
                                    options,
                                    dimensions
                            );
                    return new WorldLoader.DataLoadOutput<>(
                            new LevelDataAndDimensions.WorldDataAndGenSettings(
                                    worldData,
                                    generation
                            ),
                            complete.dimensionsRegistryAccess()
                    );
                },
                WorldStem::new,
                net.minecraft.util.Util.backgroundExecutor(),
                Runnable::run
        );
        return loaded.get();
    }

    private static WorldStem loadExistingWorld(
            LevelStorageSource.LevelStorageAccess access,
            PackRepository packs
    ) throws Exception {
        Dynamic<?> data = access.getUnfixedDataTag(false);
        WorldLoader.PackConfig packConfig = LevelStorageSource.getPackConfig(data, packs, false);
        CompletableFuture<WorldStem> loaded = WorldLoader.load(
                initConfig(packConfig),
                context -> {
                    Registry<LevelStem> dimensions = context.datapackDimensions()
                            .lookupOrThrow(Registries.LEVEL_STEM);
                    LevelDataAndDimensions levelData = LevelStorageSource.getLevelDataAndDimensions(
                            access,
                            data,
                            context.dataConfiguration(),
                            dimensions,
                            context.datapackWorldgen()
                    );
                    return new WorldLoader.DataLoadOutput<>(
                            levelData.worldDataAndGenSettings(),
                            levelData.dimensions().dimensionsRegistryAccess()
                    );
                },
                WorldStem::new,
                net.minecraft.util.Util.backgroundExecutor(),
                Runnable::run
        );
        return loaded.get();
    }

    private static WorldLoader.InitConfig initConfig(WorldLoader.PackConfig packConfig) {
        return new WorldLoader.InitConfig(
                packConfig,
                Commands.CommandSelection.INTEGRATED,
                LevelBasedPermissionSet.GAMEMASTER
        );
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            throw new IllegalArgumentException("Missing worker world field: " + name);
        }
        return value.getAsString();
    }
}
