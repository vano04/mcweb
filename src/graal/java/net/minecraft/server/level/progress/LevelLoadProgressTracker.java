package net.minecraft.server.level.progress;

import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Classpath-first shadow of the JAR's weighted loading-progress accumulator.
 *
 * <p>The original class assumes that the listener callbacks arrive on one
 * thread. That assumption is not true while the browser server, worldgen,
 * and client-side loading work share the WasmLM heap: the fields making up a
 * weighted update can otherwise be observed or overwritten halfway through a
 * callback. Keep the hot read lock-free, but make each lifecycle callback an
 * atomic update.</p>
 */
public class LevelLoadProgressTracker implements LevelLoadListener {
    private static final int PREPARE_SERVER_WEIGHT = 10;
    private static final int EXPECTED_PLAYER_CHUNKS = Mth.square(7);

    private final boolean includePlayerChunks;
    private int totalWeight;
    private int finalizedWeight;
    private int segmentWeight;
    private float segmentFraction;
    private volatile float progress;

    public LevelLoadProgressTracker(boolean includePlayerChunks) {
        this.includePlayerChunks = includePlayerChunks;
    }

    @Override
    public synchronized void start(LevelLoadListener.Stage stage, int total) {
        if (!tracksStage(stage)) {
            return;
        }

        switch (stage) {
            case LOAD_INITIAL_CHUNKS:
                int playerChunks = includePlayerChunks ? EXPECTED_PLAYER_CHUNKS : 0;
                totalWeight = PREPARE_SERVER_WEIGHT + total + playerChunks;
                beginSegment(PREPARE_SERVER_WEIGHT);
                finishSegment();
                beginSegment(total);
                break;
            case LOAD_PLAYER_CHUNKS:
                beginSegment(EXPECTED_PLAYER_CHUNKS);
                break;
            default:
                break;
        }
    }

    @Override
    public synchronized void update(LevelLoadListener.Stage stage, int ready, int total) {
        if (!tracksStage(stage)) {
            return;
        }

        segmentFraction = total == 0 ? 0.0F : (float) ready / (float) total;
        updateProgress();
    }

    @Override
    public synchronized void finish(LevelLoadListener.Stage stage) {
        if (tracksStage(stage)) {
            finishSegment();
        }
    }

    public float get() {
        return progress;
    }

    @Override
    public void updateFocus(ResourceKey<Level> dimension, ChunkPos center) {
        // The vanilla tracker intentionally ignores focus changes.
    }

    private void beginSegment(int weight) {
        segmentWeight = weight;
        segmentFraction = 0.0F;
        updateProgress();
    }

    private void finishSegment() {
        finalizedWeight += segmentWeight;
        segmentWeight = 0;
        updateProgress();
    }

    private boolean tracksStage(LevelLoadListener.Stage stage) {
        return switch (stage) {
            case LOAD_INITIAL_CHUNKS -> true;
            case LOAD_PLAYER_CHUNKS -> includePlayerChunks;
            default -> false;
        };
    }

    private void updateProgress() {
        float next;
        if (totalWeight == 0) {
            next = 0.0F;
        } else {
            next = (finalizedWeight + segmentFraction * segmentWeight) / (float) totalWeight;
        }

        // Loading weights are cumulative. A stale callback from another
        // carrier must not make the public progress bar move backwards.
        if (next < progress) {
            next = progress;
        }
        progress = next;
    }
}
