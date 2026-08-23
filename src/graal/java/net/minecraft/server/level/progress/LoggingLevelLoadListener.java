package net.minecraft.server.level.progress;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Classpath-first shadow that routes world-load progress to the browser host.
 *
 * <p>The JAR's class only writes to slf4j on a timer, which is invisible here.
 * {@code Minecraft.doWorldLoad} composes it with the client's own tracker
 * ({@code LevelLoadListener.compose(loadTracker, forSingleplayer())}), so
 * shadowing it gives the exact stage and chunk counts the loading screen is
 * waiting on — the difference between "spawn chunks are generating slowly" and
 * "the chunk future never completes" — without touching any gameplay class.</p>
 *
 * <p>{@code update} fires every server tick with unchanged numbers while a
 * stage is stalled, so identical messages are collapsed.</p>
 */
public class LoggingLevelLoadListener implements LevelLoadListener {
    private final boolean includePlayerChunks;
    private String lastReport = "";

    public LoggingLevelLoadListener(boolean includePlayerChunks) {
        this.includePlayerChunks = includePlayerChunks;
    }

    public static LoggingLevelLoadListener forDedicatedServer() {
        return new LoggingLevelLoadListener(false);
    }

    public static LoggingLevelLoadListener forSingleplayer() {
        return new LoggingLevelLoadListener(true);
    }

    @Override
    public void start(LevelLoadListener.Stage stage, int total) {
        report("start " + stage + " total=" + total);
    }

    @Override
    public void update(LevelLoadListener.Stage stage, int ready, int total) {
        if (!includePlayerChunks && stage == LevelLoadListener.Stage.LOAD_PLAYER_CHUNKS) {
            return;
        }
        report("update " + stage + " " + ready + "/" + total);
    }

    @Override
    public void finish(LevelLoadListener.Stage stage) {
        report("finish " + stage);
    }

    @Override
    public void updateFocus(ResourceKey<Level> dimension, ChunkPos center) {
        report("focus " + dimension.identifier() + " " + center);
    }

    private int reports;
    private int suppressed;

    /**
     * Collapses repeats, but never goes silent.
     *
     * <p>Suppressing identical messages outright made a ticking server and a wedged one
     * produce byte-identical evidence: the last marker simply stopped changing. That is
     * the difference between "spawn chunks are generating slowly" and "the chunk future
     * never completes", which is the entire question this listener exists to answer. So
     * a repeat still reports, every 64th time, carrying the count — a marker whose
     * counter climbs is progress, and one that stops is a stall.
     */
    private synchronized void report(String message) {
        reports++;
        if (message.equals(lastReport)) {
            suppressed++;
            if ((suppressed & 0x3F) != 0) {
                return;
            }
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "levelload:" + message + " x" + suppressed);
            return;
        }
        lastReport = message;
        suppressed = 0;
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("levelload:" + message);
    }
}
