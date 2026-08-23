package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Proxy;
import java.nio.ByteBuffer;
import java.util.Base64;
import net.minecraft.SharedConstants;
import net.minecraft.client.ClientBootstrap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.entity.BlockEntity;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;

/**
 * Persistent private-heap image for Tier 1's byte-only section mesh lane.
 *
 * <p>Boot is intentionally the real client resource/model boot. The worker
 * does not reimplement block models or vertex emission; once its private image
 * has loaded the registries, each request calls the supplied JAR's
 * {@link SectionCompiler} over a {@link RenderSectionRegion} backed by bytes.</p>
 */
public final class BrowserMeshWorkerMain {
    private static Minecraft minecraft;
    private static SectionCompiler compiler;
    private static boolean ready;
    private static boolean readyReported;

    private BrowserMeshWorkerMain() {
    }

    public static void main(String[] args) {
        if (!BrowserMeshWorkerTransport.isAvailable()) {
            throw new IllegalStateException("mcWebMeshWorker transport is unavailable");
        }
        try {
            configureRuntime();
            report("mesh:SharedConstants.tryDetectVersion");
            SharedConstants.tryDetectVersion();
            report("mesh:Bootstrap.bootStrap");
            Bootstrap.bootStrap();
            report("mesh:ClientBootstrap.bootstrap");
            ClientBootstrap.bootstrap();
            report("mesh:Bootstrap.validate");
            Bootstrap.validate();
            net.minecraft.world.level.chunk.storage.RegionFileVersion.configure("lz4");
            RenderSystem.initRenderThread();
            AgentExecutorService.primeWorldgenTrace();

            // Match the proven client launcher's class/resource preflight. In
            // particular, initializing Minecraft and DataFixers before the
            // monolithic constructor avoids making their static reload future
            // the first consumer of the private image's executor bridge.
            java.io.File gameDirectory = new java.io.File("/tmp/mcgame-mesh");
            net.minecraft.client.main.GameConfig config =
                    BrowserMinecraftMain.createGameConfig(gameDirectory);
            report("mesh:probe.MemoryUtil");
            org.lwjgl.system.MemoryUtil.memAlloc(16).clear();
            report("mesh:probe.MemoryStack");
            try (org.lwjgl.system.MemoryStack stack = org.lwjgl.system.MemoryStack.stackPush()) {
                stack.malloc(16);
            }
            report("mesh:probe.PointerBuffer");
            org.lwjgl.PointerBuffer.allocateDirect(4);
            report("mesh:probe.MinecraftClassInit");
            try {
                Class.forName("net.minecraft.client.Minecraft");
            } catch (ClassNotFoundException missingMinecraft) {
                throw new IllegalStateException("Minecraft class is not reachable", missingMinecraft);
            }
            report("mesh:probe.DataFixers");
            net.minecraft.util.datafix.DataFixers.getDataFixer();
            report("mesh:probe.ClientPackSource");
            net.minecraft.world.level.validation.DirectoryValidator validator =
                    net.minecraft.world.level.storage.LevelStorageSource.parseValidator(
                            gameDirectory.toPath().resolve("allowed_symlinks.txt")
                    );
            new net.minecraft.client.resources.ClientPackSource(
                    config.location.getExternalAssetSource(), validator
            );
            report("mesh:probe.AuthService");
            net.minecraft.server.Services.create(
                    com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService.createOffline(
                            Proxy.NO_PROXY
                    ),
                    gameDirectory
            );

            report("mesh:Minecraft(GameConfig)");
            minecraft = new Minecraft(
                    config
            );
            InlineExecutorService.activateMainLoopDrain();
            BrowserFramePump.start(minecraft);
            BrowserMeshWorkerTransport.onPump(BrowserMeshWorkerMain::pump);
            BrowserMeshWorkerTransport.onRequest(BrowserMeshWorkerMain::compileRequest);
            pump();
            report("mesh:constructed");
        } catch (Throwable failure) {
            reportFailure("mesh:init", failure);
            throw failure;
        }
    }

    private static void configureRuntime() {
        System.setProperty("joml.nounsafe", "true");
        if ("Browser".equals(System.getProperty("os.name"))) {
            System.setProperty("os.name", "Linux");
        }
        String arch = System.getProperty("os.arch", "");
        if (arch.isEmpty() || "browser".equalsIgnoreCase(arch) || "wasm".equalsIgnoreCase(arch)) {
            System.setProperty("os.arch", "aarch64");
        }
    }

    /** Advances the real client main queue until the model reload is complete. */
    private static void pump() {
        if (ready) {
            return;
        }
        try {
            // Resource reload completion is normally advanced by the browser's
            // real frame pump. Reuse that exact JAR path in this private image;
            // the Worker host supplies inert GPU/input/frame callbacks.
            BrowserFramePump.frame();
            if (!minecraft.isGameLoadFinished()) {
                return;
            }
            ModelManager models = minecraft.getModelManager();
            BlockStateModelSet blockModels = models.getBlockStateModelSet();
            FluidStateModelSet fluidModels = models.getFluidStateModelSet();
            if (blockModels == null || fluidModels == null) {
                return;
            }
            Options options = minecraft.options;
            BlockColors colors = minecraft.getBlockColors();
            compiler = new SectionCompiler(
                    options.ambientOcclusion().get(),
                    options.cutoutLeaves().get(),
                    blockModels,
                    fluidModels,
                    colors
            );
            ready = true;
            if (!readyReported) {
                readyReported = true;
                report("mesh:ready");
                BrowserMeshWorkerTransport.ready();
            }
        } catch (Throwable failure) {
            reportFailure("mesh:pump", failure);
        }
    }

    private static void compileRequest(int id, String snapshotBase64) {
        long started = System.currentTimeMillis();
        try {
            if (!ready) {
                throw new IllegalStateException("mesh model registry is not ready");
            }
            MeshSnapshotWire.Snapshot snapshot;
            if ("fixture".equals(snapshotBase64)) {
                snapshot = MeshSnapshotWire.fixedFixture();
            } else {
                snapshot = MeshSnapshotWire.decode(
                        Base64.getDecoder().decode(snapshotBase64)
                );
            }
            SectionPos target = SectionPos.of(
                    snapshot.targetSectionX,
                    snapshot.targetSectionY,
                    snapshot.targetSectionZ
            );
            RenderSectionRegion region = new RenderSectionRegion(snapshot);
            SectionCompiler.Results results = compiler.compile(
                    target,
                    region,
                    VertexSorting.byDistance(snapshot.sortX, snapshot.sortY, snapshot.sortZ),
                    new SectionBufferBuilderPack()
            );
            try {
                byte[] encoded = MeshResultWire.encode(target, results);
                BrowserMeshWorkerTransport.respond(
                        id,
                        encoded,
                        System.currentTimeMillis() - started
                );
            } finally {
                results.release();
            }
        } catch (Throwable failure) {
            BrowserMeshWorkerTransport.fail(
                    id,
                    failure.getClass().getName(),
                    BrowserMinecraftMain.describeFailure(failure)
            );
        }
    }

    /** Compile one snapshot in the current client image for the Tier 1 byte gate. */
    static byte[] compileInline(
            Minecraft client,
            SectionPos target,
            RenderSectionRegion region
    ) {
        ModelManager models = client.getModelManager();
        SectionCompiler inlineCompiler = new SectionCompiler(
                client.options.ambientOcclusion().get(),
                client.options.cutoutLeaves().get(),
                models.getBlockStateModelSet(),
                models.getFluidStateModelSet(),
                client.getBlockColors()
        );
        SectionCompiler.Results results = inlineCompiler.compile(
                target,
                region,
                VertexSorting.DISTANCE_TO_ORIGIN,
                new SectionBufferBuilderPack()
        );
        try {
            return MeshResultWire.encode(target, results);
        } finally {
            results.release();
        }
    }

    private static void report(String stage) {
        BrowserGpu.reportProgress(stage);
    }

    private static void reportFailure(String stage, Throwable failure) {
        BrowserGpu.reportJavaFailure(
                stage,
                failure.getClass().getName(),
                BrowserMinecraftMain.describeFailure(failure)
        );
    }

    /** Compact output wire: layer metadata, raw GPU bytes, and visibility. */
    static final class MeshResultWire {
        private static final int MAGIC = 0x4D525331; // MRS1
        private static final int VERSION = 2;

        private MeshResultWire() {
        }

        static byte[] encode(SectionPos target, SectionCompiler.Results results) {
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(256_000);
                DataOutputStream out = new DataOutputStream(bytes);
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(target.x());
                out.writeInt(target.y());
                out.writeInt(target.z());

                VisibilitySet visibility = results.visibilitySet;
                for (Direction from : Direction.values()) {
                    for (Direction to : Direction.values()) {
                        out.writeBoolean(visibility.visibilityBetween(from, to));
                    }
                }

                int layerCount = 0;
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    if (results.renderedLayers.containsKey(layer)) {
                        layerCount++;
                    }
                }
                out.writeInt(layerCount);
                for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                    MeshData mesh = results.renderedLayers.get(layer);
                    if (mesh == null) {
                        continue;
                    }
                    MeshData.DrawState state = mesh.drawState();
                    out.writeInt(layer.ordinal());
                    out.writeInt(state.vertexCount());
                    out.writeInt(state.indexCount());
                    out.writeInt(state.primitiveTopology().ordinal());
                    out.writeInt(state.indexType().ordinal());
                    out.writeInt(state.format().getVertexSize());
                    writeBuffer(out, mesh.vertexBuffer());
                    ByteBuffer index = mesh.indexBuffer();
                    if (index == null) {
                        out.writeInt(-1);
                    } else {
                        writeBuffer(out, index);
                    }
                }

                MeshData.SortState transparency = results.transparencyState;
                out.writeBoolean(transparency != null);
                if (transparency != null) {
                    CompactVectorArray centroids = transparency.centroids();
                    out.writeInt(centroids.size());
                    out.writeInt(transparency.indexType().ordinal());
                    for (int i = 0; i < centroids.size(); i++) {
                        out.writeFloat(centroids.getX(i));
                        out.writeFloat(centroids.getY(i));
                        out.writeFloat(centroids.getZ(i));
                    }
                }

                // Block entities remain main-realm data. The first fixed gate
                // has none; reserve the count so the protocol can carry their
                // packed positions without ever serializing the entity object.
                out.writeInt(results.blockEntities.size());
                for (BlockEntity blockEntity : results.blockEntities) {
                    out.writeLong(blockEntity.getBlockPos().asLong());
                }
                out.flush();
                return bytes.toByteArray();
            } catch (IOException impossible) {
                throw new AssertionError(impossible);
            }
        }

        private static void writeBuffer(DataOutputStream out, ByteBuffer source)
                throws IOException {
            ByteBuffer copy = source.duplicate();
            copy.position(0);
            int length = copy.remaining();
            byte[] bytes = new byte[length];
            copy.get(bytes);
            out.writeInt(length);
            out.write(bytes);
        }
    }
}
