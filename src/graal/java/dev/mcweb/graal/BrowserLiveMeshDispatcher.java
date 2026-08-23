package dev.mcweb.graal;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.graalvm.webimage.api.JS;
import org.lwjgl.system.MemoryUtil;

/**
 * Opt-in controller for live section compilation through private WasmGC
 * workers. Minecraft's immutable SectionCompiler still creates the mesh; this
 * class only snapshots the public region, transports bytes, and hands the
 * returned buffers back to the JAR's own dispatcher upload seam.
 */
public final class BrowserLiveMeshDispatcher {
    private static final int RESULT_MAGIC = 0x4D525331; // MRS1
    private static final int RESULT_VERSION = 2;
    private static final int MAX_BUFFER_BYTES = 64 * 1024 * 1024;
    /** Bound byte snapshots/results retained by the main realm while Workers drain. */
    private static final int MAX_PENDING = 8;
    private static final IdentityHashMap<SectionRenderDispatcher.RenderSection, Request> BY_SECTION =
            new IdentityHashMap<>();
    private static final Map<Integer, Request> BY_ID = new java.util.HashMap<>();
    private static final IdentityHashMap<SectionRenderDispatcher.RenderSection, Install> INSTALLS =
            new IdentityHashMap<>();
    private static final ArrayDeque<Install> INSTALL_QUEUE = new ArrayDeque<>();
    /**
     * The Java callback paths are not all guaranteed to run on the render
     * thread: a JAR compile hook may run on a WasmLM carrier while a Worker
     * result or the frame pump is entering the primary instance.  The maps
     * above are ownership state, not concurrent collections.  Serialize the
     * short state transition around submit/cancel/result/pump so a reset and
     * a late result cannot interleave between the identity checks and map
     * updates.
     */
    private static final Object STATE_LOCK = new Object();
    private static boolean configured;
    private static boolean requested;
    private static boolean transportStarted;
    private static int nextRequestId = 1;
    private static int submitted;
    private static int completed;
    private static int failed;
    private static int fallbacks;

    private BrowserLiveMeshDispatcher() {
    }

    /** Called from the exact-counted JAR hook; cached so the default lane is cheap. */
    public static boolean enabled() {
        synchronized (STATE_LOCK) {
            return enabledLocked();
        }
    }

    private static boolean enabledLocked() {
        if (!configured) {
            requested = enabledFromQuery();
            configured = true;
            if (requested) {
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("live-mesh:enabled workers=2");
            }
        }
        return requested;
    }

    /**
     * Offers a JAR compile task to the private Worker lane.
     *
     * <p>A false return is deliberate: the counted JAR hook continues through the
     * immutable inline compiler when the byte-only lane is saturated or cannot capture
     * the current client state. Without this backpressure valve, a slower private mesh
     * Worker retains an unbounded snapshot/result queue in the main realm and eventually
     * takes the browser down at the Wasm memory ceiling.</p>
     */
    public static boolean submit(
            SectionRenderDispatcher.RenderSection renderSection,
            RenderSectionRegion region
    ) {
        synchronized (STATE_LOCK) {
            if (!enabledLocked() || renderSection == null || region == null) {
                return false;
            }
            cancelLocked(renderSection);
            if (BY_ID.size() + INSTALLS.size() >= MAX_PENDING) {
                fallbacks++;
                if (fallbacks == 1 || fallbacks % 32 == 0) {
                    dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                            "live-mesh:fallback queue-full count=" + fallbacks
                                    + " pending=" + (BY_ID.size() + INSTALLS.size())
                    );
                }
                return false;
            }
            try {
                Minecraft client = Minecraft.getInstance();
                if (client == null || client.level == null || !client.isGameLoadFinished()) {
                    return false;
                }
                SectionPos target = SectionPos.of(renderSection.getSectionNode());
                Vec3 camera = client.gameRenderer.mainCamera().position();
                MeshSnapshotWire.Snapshot snapshot = MeshSnapshotWire.capture(region, target, camera);
                String encoded = Base64.getEncoder().encodeToString(MeshSnapshotWire.encode(snapshot));
                int id = allocateId();
                Request request = new Request(id, renderSection, region, target);
                BY_SECTION.put(renderSection, request);
                BY_ID.put(id, request);
                submitted++;
                if (!transportStarted) {
                    transportStarted = true;
                    BrowserLiveMeshTransport.start();
                }
                BrowserLiveMeshTransport.submit(id, encoded);
                if (submitted == 1 || submitted % 16 == 0) {
                    dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                            "live-mesh:submit count=" + submitted + " pending=" + BY_ID.size()
                            + " snapshot=" + encoded.length()
                    );
                }
                return true;
            } catch (Throwable failure) {
                failed++;
                dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                        "live-mesh:submit",
                        failure.getClass().getName(),
                        BrowserMinecraftMain.describeFailure(failure)
                );
                return false;
            }
        }
    }

    /** Called by the injected RenderSection.reset hook. */
    public static void cancel(SectionRenderDispatcher.RenderSection renderSection) {
        synchronized (STATE_LOCK) {
            cancelLocked(renderSection);
        }
    }

    private static void cancelLocked(SectionRenderDispatcher.RenderSection renderSection) {
        if (renderSection == null) {
            return;
        }
        Request request = BY_SECTION.remove(renderSection);
        if (request != null) {
            BY_ID.remove(request.id);
            if (transportStarted) {
                BrowserLiveMeshTransport.cancel(request.id);
            }
        }
        Install install = INSTALLS.remove(renderSection);
        if (install != null) {
            install.cancelled = true;
            INSTALL_QUEUE.remove(install);
            releaseFromSection(install);
        }
    }

    /** Queues result processing on the normal Minecraft frame boundary. */
    public static void acceptResult(int id, String resultBase64) {
        synchronized (STATE_LOCK) {
            Request request = BY_ID.remove(id);
            if (request == null || BY_SECTION.get(request.renderSection) != request) {
                return;
            }
            try {
                SectionCompiler.Results results = decodeResult(
                        Base64.getDecoder().decode(resultBase64), request.target
                );
                restoreMainRealmBlockEntities(results, request.region, request.target);
                Minecraft client = Minecraft.getInstance();
                Vec3 camera = client.gameRenderer.mainCamera().position();
                CompiledSectionMesh compiled = new CompiledSectionMesh(
                        TranslucencyPointOfView.of(camera, request.renderSection.getSectionNode()),
                        results
                );
                Install install = new Install(request, compiled, results);
                INSTALLS.put(request.renderSection, install);
                INSTALL_QUEUE.add(install);
            } catch (Throwable failure) {
                failed++;
                BY_SECTION.remove(request.renderSection, request);
                dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                        "live-mesh:decode",
                        failure.getClass().getName(),
                        BrowserMinecraftMain.describeFailure(failure)
                );
            }
        }
    }

    public static void acceptFailure(int id, String message) {
        synchronized (STATE_LOCK) {
            Request request = BY_ID.remove(id);
            if (request == null || BY_SECTION.get(request.renderSection) != request) {
                return;
            }
            BY_SECTION.remove(request.renderSection, request);
            failed++;
            dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                    "live-mesh:worker",
                    "WorkerMeshFailure",
                    "request=" + id + " " + message
            );
        }
    }

    /** Advances result installation without putting a long GPU copy in an event callback. */
    public static void pump() {
        synchronized (STATE_LOCK) {
            if (!enabledLocked()) {
                return;
            }
            int budget = 8;
            while (budget-- > 0 && !INSTALL_QUEUE.isEmpty()) {
                Install install = INSTALL_QUEUE.peek();
                if (install.cancelled || BY_SECTION.get(install.request.renderSection) != install.request) {
                    INSTALL_QUEUE.remove();
                    INSTALLS.remove(install.request.renderSection, install);
                    releaseFromSection(install);
                    continue;
                }
                if (install.results.renderedLayers.isEmpty()) {
                    install.request.renderSection.mcwebSetMesh(install.compiled);
                    finish(install);
                    continue;
                }
                if (!install.addNextLayer()) {
                    // The staging buffer is full. The JAR's upload path has already
                    // requested a flush when it is on the render thread; retry on a
                    // later frame if it still cannot append.
                    continue;
                }
                if (install.done()) {
                    finish(install);
                }
            }
        }
    }

    private static void finish(Install install) {
        INSTALL_QUEUE.remove(install);
        INSTALLS.remove(install.request.renderSection, install);
        BY_SECTION.remove(install.request.renderSection, install.request);
        completed++;
        if (completed == 1 || completed % 16 == 0) {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "live-mesh:committed count=" + completed + " pending=" + BY_ID.size()
            );
        }
    }

    private static void releaseFromSection(Install install) {
        try {
            install.request.renderSection.mcwebReleaseMesh(install.compiled);
        } catch (Throwable ignored) {
            install.compiled.close();
        }
    }

    private static int allocateId() {
        for (int attempts = 0; attempts < Integer.MAX_VALUE; attempts++) {
            int id = nextRequestId++;
            if (id <= 0) {
                id = nextRequestId = 1;
            }
            if (!BY_ID.containsKey(id)) {
                return id;
            }
        }
        throw new IllegalStateException("live mesh request id space exhausted");
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_live_mesh') === '1';", args = {})
    private static native boolean enabledFromQuery();

    private static SectionCompiler.Results decodeResult(byte[] bytes, SectionPos expected)
            throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty live mesh result");
        }
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != RESULT_MAGIC) {
            throw new IllegalArgumentException("unsupported live mesh result header");
        }
        int version = in.readInt();
        if (version < 1 || version > RESULT_VERSION) {
            throw new IllegalArgumentException("unsupported live mesh result version " + version);
        }
        SectionPos actual = SectionPos.of(in.readInt(), in.readInt(), in.readInt());
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException("live mesh target mismatch: " + actual + " != " + expected);
        }
        VisibilitySet visibility = new VisibilitySet();
        for (Direction from : Direction.values()) {
            for (Direction to : Direction.values()) {
                visibility.set(from, to, in.readBoolean());
            }
        }
        SectionCompiler.Results results = new SectionCompiler.Results();
        results.visibilitySet = visibility;
        ChunkSectionLayer[] layers = ChunkSectionLayer.values();
        boolean[] seen = new boolean[layers.length];
        int layerCount = in.readInt();
        if (layerCount < 0 || layerCount > layers.length) {
            throw new IllegalArgumentException("invalid live mesh layer count " + layerCount);
        }
        for (int i = 0; i < layerCount; i++) {
            int ordinal = in.readInt();
            if (ordinal < 0 || ordinal >= layers.length || seen[ordinal]) {
                throw new IllegalArgumentException("invalid live mesh layer ordinal " + ordinal);
            }
            seen[ordinal] = true;
            ChunkSectionLayer layer = layers[ordinal];
            int vertexCount = in.readInt();
            int indexCount = in.readInt();
            int topologyOrdinal = in.readInt();
            int indexTypeOrdinal = in.readInt();
            int formatSize = in.readInt();
            if (vertexCount < 0 || indexCount < 0
                    || formatSize != layer.vertexFormat().getVertexSize()) {
                throw new IllegalArgumentException("invalid live mesh draw metadata");
            }
            PrimitiveTopology topology = enumValue(
                    PrimitiveTopology.values(), topologyOrdinal, "primitive topology"
            );
            IndexType indexType = enumValue(IndexType.values(), indexTypeOrdinal, "index type");
            byte[] vertexBytes = readBuffer(in, "vertex");
            long expectedVertexBytes = (long) vertexCount * formatSize;
            if (expectedVertexBytes != vertexBytes.length) {
                throw new IllegalArgumentException("live mesh vertex size mismatch");
            }
            int indexBytesLength = in.readInt();
            byte[] indexBytes = indexBytesLength < 0
                    ? null
                    : readBuffer(in, indexBytesLength, "index");
            if (indexBytes != null
                    && (long) indexCount * indexType.bytes != indexBytes.length) {
                throw new IllegalArgumentException("live mesh index size mismatch");
            }
            MeshData.DrawState drawState = new MeshData.DrawState(
                    layer.vertexFormat(), vertexCount, indexCount, topology, indexType
            );
            ByteBufferBuilder.Result vertex = bufferResult(vertexBytes);
            MeshData mesh;
            if (indexBytes == null) {
                mesh = new MeshData(vertex, drawState);
            } else {
                mesh = new MeshData(vertex, bufferResult(indexBytes), drawState);
            }
            results.renderedLayers.put(layer, mesh);
        }
        if (version >= 2 && in.readBoolean()) {
            int centroidCount = in.readInt();
            if (centroidCount < 1 || centroidCount > 1_000_000) {
                throw new IllegalArgumentException("invalid live mesh transparency centroid count");
            }
            IndexType sortIndexType = enumValue(
                    IndexType.values(), in.readInt(), "transparency index type"
            );
            com.mojang.blaze3d.vertex.CompactVectorArray centroids =
                    new com.mojang.blaze3d.vertex.CompactVectorArray(centroidCount);
            for (int i = 0; i < centroidCount; i++) {
                centroids.set(i, in.readFloat(), in.readFloat(), in.readFloat());
            }
            results.transparencyState = new MeshData.SortState(centroids, sortIndexType);
        }
        int blockEntityCount = in.readInt();
        if (blockEntityCount < 0 || blockEntityCount > 1_000_000) {
            throw new IllegalArgumentException("invalid live mesh block entity count");
        }
        for (int i = 0; i < blockEntityCount; i++) {
            in.readLong();
        }
        if (in.available() != 0) {
            throw new IllegalArgumentException("trailing live mesh bytes: " + in.available());
        }
        return results;
    }

    /** Reattaches main-realm entities after the worker compiled the block bytes. */
    private static void restoreMainRealmBlockEntities(
            SectionCompiler.Results results,
            RenderSectionRegion region,
            SectionPos target
    ) {
        net.minecraft.core.BlockPos.MutableBlockPos pos =
                new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int localY = 0; localY < 16; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    pos.set(
                            target.minBlockX() + localX,
                            target.minBlockY() + localY,
                            target.minBlockZ() + localZ
                    );
                    net.minecraft.world.level.block.entity.BlockEntity entity =
                            region.getBlockEntity(pos);
                    if (entity != null) {
                        results.blockEntities.add(entity);
                    }
                }
            }
        }
    }

    private static byte[] readBuffer(DataInputStream in, String label) throws IOException {
        int length = in.readInt();
        return readBuffer(in, length, label);
    }

    private static byte[] readBuffer(DataInputStream in, int length, String label)
            throws IOException {
        if (length <= 0 || length > MAX_BUFFER_BYTES) {
            throw new IllegalArgumentException("invalid live mesh " + label + " buffer " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }

    private static ByteBufferBuilder.Result bufferResult(byte[] bytes) {
        ByteBufferBuilder builder = ByteBufferBuilder.exactlySized(bytes.length);
        long address = builder.reserve(bytes.length);
        ByteBuffer target = MemoryUtil.memByteBuffer(address, bytes.length);
        target.put(bytes).flip();
        return builder.build();
    }

    private static <T> T enumValue(T[] values, int ordinal, String label) {
        if (ordinal < 0 || ordinal >= values.length) {
            throw new IllegalArgumentException("invalid live mesh " + label + " ordinal " + ordinal);
        }
        return values[ordinal];
    }

    private static final class Request {
        final int id;
        final SectionRenderDispatcher.RenderSection renderSection;
        final RenderSectionRegion region;
        final SectionPos target;

        Request(
                int id,
                SectionRenderDispatcher.RenderSection renderSection,
                RenderSectionRegion region,
                SectionPos target
        ) {
            this.id = id;
            this.renderSection = renderSection;
            this.region = region;
            this.target = target;
        }
    }

    private static final class Install {
        final Request request;
        final CompiledSectionMesh compiled;
        final SectionCompiler.Results results;
        final Iterator<Map.Entry<ChunkSectionLayer, MeshData>> entries;
        boolean cancelled;
        Map.Entry<ChunkSectionLayer, MeshData> current;

        Install(Request request, CompiledSectionMesh compiled, SectionCompiler.Results results) {
            this.request = request;
            this.compiled = compiled;
            this.results = results;
            this.entries = results.renderedLayers.entrySet().iterator();
        }

        boolean addNextLayer() {
            if (current == null) {
                if (!entries.hasNext()) {
                    return true;
                }
                current = entries.next();
            }
            ChunkSectionLayer layer = current.getKey();
            MeshData mesh = current.getValue();
            if (!request.renderSection.mcwebAddLayer(
                    compiled, layer, mesh.vertexBuffer(), mesh.indexBuffer()
            )) {
                return false;
            }
            mesh.close();
            current = null;
            return true;
        }

        boolean done() {
            return current == null && !entries.hasNext();
        }
    }
}
