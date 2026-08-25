package dev.mcweb.graal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;

/**
 * The single executor boundary used by the public WasmGC image.
 *
 * <p>GraalVM's WasmGC image is cooperative and has one Java thread. Minecraft
 * still asks for several named executors and the transformed JAR still contains
 * diagnostic callbacks around world generation, so this class keeps those ABI
 * seams explicit while routing every executor to the proven inline service.
 * The public distribution has one fixed WasmGC execution path.</p>
 */
public final class BrowserExecutorService {
    private BrowserExecutorService() {
    }

    public static ExecutorService pool() {
        return InlineExecutorService.INSTANCE;
    }

    public static ExecutorService backgroundPool() {
        return InlineExecutorService.INSTANCE;
    }

    public static ExecutorService ioPool() {
        return InlineExecutorService.INSTANCE;
    }

    public static ExecutorService serverWorldgenPool() {
        return InlineExecutorService.INSTANCE;
    }

    public static ExecutorService serverExecutor(MinecraftServer server) {
        return InlineExecutorService.INSTANCE;
    }

    public static void maintain() {
    }

    public static int drainInlineIfStarved(int budget) {
        InlineExecutorService.drainMainLoopFromFrame();
        return 0;
    }

    public static int drainInlineWhileWaiting(int budget) {
        InlineExecutorService.drainMainLoopFromFrame();
        return 0;
    }

    public static void drainInlineForBlockedWait() {
        InlineExecutorService.drainMainLoopFromFrame();
    }

    public static void onBlockingWaitSpin(BlockableEventLoop<?> loop) {
        InlineExecutorService.drainMainLoopFromFrame();
    }

    public static void safepointPoll() {
    }

    public static String stats() {
        return "executor=inline";
    }

    public static String queueState() {
        return "executor=inline pending=0";
    }

    public static void reportServerRunEnter(MinecraftServer server) {
    }

    public static void reportServerReady(MinecraftServer server) {
    }

    public static void reportServerRunExit(MinecraftServer server) {
    }

    public static void reportWorldgenEnter(ChunkAccess chunk) {
    }

    public static void reportWorldgenExit() {
    }

    public static void reportWorldgenBiomesEnter(ChunkAccess chunk) {
    }

    public static void reportWorldgenBiomesExit() {
    }

    public static void reportWorldgenBiomesPhase(int phase) {
    }

    public static void reportWorldgenBiomesObjects(int phase, Object first, Object second) {
    }

    public static void reportWorldgenRegionMissing(
            WorldGenRegion region,
            int x,
            int z,
            ChunkStatus status,
            ChunkStep step,
            GenerationChunkHolder holder
    ) {
    }

    public static void reportGenerationTaskWait(
            ChunkGenerationTask task,
            CompletableFuture<?> dependency
    ) {
    }

    public static void reportGenerationStepFailure(
            GenerationChunkHolder holder,
            ChunkStep step,
            ChunkAccess chunk,
            Throwable failure
    ) {
    }

    public static void reportGenerationFutureComplete(
            GenerationChunkHolder holder,
            ChunkStatus status,
            ChunkAccess chunk,
            boolean published
    ) {
    }
}
