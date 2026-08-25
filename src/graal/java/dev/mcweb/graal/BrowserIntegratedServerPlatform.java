package dev.mcweb.graal;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.DataFixer;
import java.io.File;
import java.net.Proxy;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.ModCheck;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.debugchart.LocalSampleLogger;
import net.minecraft.util.debugchart.TpsDebugDimensions;

/** Primitive platform state used by an IntegratedServer in a separate heap. */
public final class BrowserIntegratedServerPlatform {
    /*
     * MinecraftServer records one full-tick sample plus partial samples at the
     * TpsDebugDimensions ordinals. A one-dimensional logger accepts only the
     * full sample and throws as soon as tickServer records ordinal 1.
     */
    private static final LocalSampleLogger TICK_LOGGER =
            new LocalSampleLogger(TpsDebugDimensions.values().length);
    private static final File SERVER_DIRECTORY = new File("/tmp/mcgame-server");

    private static String playerName = "Player";
    private static UUID playerId = UUID.nameUUIDFromBytes("OfflinePlayer:Player".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    private static int viewDistance = 8;
    private static int simulationDistance = 8;
    private static double entityDistanceScaling = 1.0D;
    private static boolean demo;
    private static boolean synchronousWrites;

    private BrowserIntegratedServerPlatform() {
    }

    public static void configure(
            String name,
            UUID id,
            int view,
            int simulation,
            double entityScale,
            boolean demoMode,
            boolean syncWrites
    ) {
        playerName = name;
        playerId = id;
        viewDistance = view;
        simulationDistance = simulation;
        entityDistanceScaling = entityScale;
        demo = demoMode;
        synchronousWrites = syncWrites;
    }

    public static Proxy proxy(Minecraft minecraft) {
        return minecraft == null ? Proxy.NO_PROXY : minecraft.getProxy();
    }

    public static DataFixer fixer(Minecraft minecraft) {
        return minecraft == null ? DataFixers.getDataFixer() : minecraft.getFixerUpper();
    }

    public static GameProfile profile(Minecraft minecraft) {
        return minecraft == null ? new GameProfile(playerId, playerName) : minecraft.getGameProfile();
    }

    public static boolean demo(Minecraft minecraft) {
        return minecraft == null ? demo : minecraft.isDemo();
    }

    public static boolean paused(Minecraft minecraft) {
        return minecraft != null && minecraft.isPaused();
    }

    public static int renderDistance() {
        return viewDistance;
    }

    public static int simulationDistance() {
        return simulationDistance;
    }

    public static LocalSampleLogger tickTimeLogger() {
        return TICK_LOGGER;
    }

    public static java.nio.file.Path serverDirectory() {
        return SERVER_DIRECTORY.toPath();
    }

    public static boolean useNativeTransport() {
        return false;
    }

    public static ModCheck moddedStatus() {
        return Minecraft.checkModStatus();
    }

    public static LocalPlayer localPlayer(Minecraft minecraft) {
        return minecraft == null ? null : minecraft.player;
    }

    public static int scaledTrackingDistance(int distance) {
        return (int) (entityDistanceScaling * distance);
    }

    public static boolean forceSynchronousWrites() {
        return synchronousWrites;
    }

    public static void sendLowDiskSpaceWarning(Minecraft minecraft) {
        if (minecraft != null) minecraft.sendLowDiskSpaceWarning();
    }

    public static void executeClient(Minecraft minecraft, Runnable action) {
        if (minecraft != null) minecraft.execute(action);
    }
}
