package dev.mcweb.graal;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Packed input for the private section-mesh image.
 *
 * <p>The worker boundary deliberately contains no Minecraft object references:
 * a 3x3 section neighbourhood is represented by a state-id palette, palette
 * indices, and one packed sky/block-light byte per block. The worker resolves
 * those ids against its own built-in registries and then calls the JAR's real
 * {@code SectionCompiler}.</p>
 */
public final class MeshSnapshotWire {
    private static final int MAGIC = 0x4D575331; // MWS1
    private static final int VERSION = 2;
    private static final int SECTIONS = 27;
    private static final int BLOCKS_PER_SECTION = 16 * 16 * 16;
    public static final int BLOCK_COUNT = SECTIONS * BLOCKS_PER_SECTION;

    private MeshSnapshotWire() {
    }

    public static final class Snapshot {
        public final int minSectionX;
        public final int minSectionY;
        public final int minSectionZ;
        public final int targetSectionX;
        public final int targetSectionY;
        public final int targetSectionZ;
        public final int minY;
        public final int height;
        /** Camera position relative to the target section origin for translucent sorting. */
        public final float sortX;
        public final float sortY;
        public final float sortZ;
        public final int[] palette;
        public final int[] stateIndices;
        public final byte[] packedLight;
        /** Reserved for the biome palette; zero means the fixed tint fallback. */
        public final int[] biomeIndices;

        Snapshot(
                int minSectionX,
                int minSectionY,
                int minSectionZ,
                int targetSectionX,
                int targetSectionY,
                int targetSectionZ,
                int minY,
                int height,
                float sortX,
                float sortY,
                float sortZ,
                int[] palette,
                int[] stateIndices,
                byte[] packedLight,
                int[] biomeIndices
        ) {
            this.minSectionX = minSectionX;
            this.minSectionY = minSectionY;
            this.minSectionZ = minSectionZ;
            this.targetSectionX = targetSectionX;
            this.targetSectionY = targetSectionY;
            this.targetSectionZ = targetSectionZ;
            this.minY = minY;
            this.height = height;
            this.sortX = sortX;
            this.sortY = sortY;
            this.sortZ = sortZ;
            this.palette = palette;
            this.stateIndices = stateIndices;
            this.packedLight = packedLight;
            this.biomeIndices = biomeIndices;
        }

        public BlockState stateAt(BlockPos pos) {
            int sectionX = SectionPos.blockToSectionCoord(pos.getX());
            int sectionY = SectionPos.blockToSectionCoord(pos.getY());
            int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
            if (sectionX < minSectionX || sectionX > minSectionX + 2
                    || sectionY < minSectionY || sectionY > minSectionY + 2
                    || sectionZ < minSectionZ || sectionZ > minSectionZ + 2) {
                return Blocks.AIR.defaultBlockState();
            }
            int section = (sectionX - minSectionX) * 9
                    + (sectionY - minSectionY) * 3
                    + (sectionZ - minSectionZ);
            int local = localIndex(
                    SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()),
                    SectionPos.sectionRelative(pos.getZ())
            );
            int paletteIndex = stateIndices[section * BLOCKS_PER_SECTION + local];
            if (paletteIndex < 0 || paletteIndex >= palette.length) {
                throw new IllegalArgumentException("mesh snapshot state palette index " + paletteIndex);
            }
            BlockState state = Block.stateById(palette[paletteIndex]);
            if (state == null) {
                throw new IllegalArgumentException("mesh snapshot unknown block state id "
                        + palette[paletteIndex]);
            }
            return state;
        }

        public int lightAt(BlockPos pos, LightLayer layer) {
            int sectionX = SectionPos.blockToSectionCoord(pos.getX());
            int sectionY = SectionPos.blockToSectionCoord(pos.getY());
            int sectionZ = SectionPos.blockToSectionCoord(pos.getZ());
            if (sectionX < minSectionX || sectionX > minSectionX + 2
                    || sectionY < minSectionY || sectionY > minSectionY + 2
                    || sectionZ < minSectionZ || sectionZ > minSectionZ + 2) {
                return 0;
            }
            int section = (sectionX - minSectionX) * 9
                    + (sectionY - minSectionY) * 3
                    + (sectionZ - minSectionZ);
            int local = localIndex(
                    SectionPos.sectionRelative(pos.getX()),
                    SectionPos.sectionRelative(pos.getY()),
                    SectionPos.sectionRelative(pos.getZ())
            );
            int packed = packedLight[section * BLOCKS_PER_SECTION + local] & 0xFF;
            return layer == LightLayer.SKY ? (packed >>> 4) & 0xF : packed & 0xF;
        }

        /** Fixed snapshots use the same neutral biome color at every position. */
        public int blockTint(BlockPos pos) {
            return 0xFFFFFF;
        }
    }

    public static Snapshot decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new IllegalArgumentException("empty mesh snapshot");
        }
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != MAGIC) {
                throw new IllegalArgumentException("unsupported mesh snapshot header");
            }
            int version = in.readInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException("unsupported mesh snapshot version " + version);
            }
            int minSectionX = in.readInt();
            int minSectionY = in.readInt();
            int minSectionZ = in.readInt();
            int targetSectionX = in.readInt();
            int targetSectionY = in.readInt();
            int targetSectionZ = in.readInt();
            int minY = in.readInt();
            int height = in.readInt();
            float sortX = version >= 2 ? in.readFloat() : 0.0f;
            float sortY = version >= 2 ? in.readFloat() : 0.0f;
            float sortZ = version >= 2 ? in.readFloat() : 0.0f;
            int paletteCount = in.readInt();
            if (paletteCount < 1 || paletteCount > 65536) {
                throw new IllegalArgumentException("invalid mesh state palette size " + paletteCount);
            }
            int[] palette = new int[paletteCount];
            for (int i = 0; i < palette.length; i++) {
                palette[i] = in.readInt();
            }
            int[] stateIndices = readIntArray(in, BLOCK_COUNT, "state indices");
            byte[] packedLight = readByteArray(in, BLOCK_COUNT, "packed light");
            int biomeCount = in.readInt();
            int[] biomeIndices = biomeCount == 0
                    ? null
                    : readIntArray(in, BLOCK_COUNT, "biome indices");
            if (biomeCount != 0 && biomeCount != BLOCK_COUNT) {
                throw new IllegalArgumentException("invalid biome index count " + biomeCount);
            }
            if (in.available() != 0) {
                throw new IllegalArgumentException("trailing mesh snapshot bytes: " + in.available());
            }
            return new Snapshot(
                    minSectionX, minSectionY, minSectionZ,
                    targetSectionX, targetSectionY, targetSectionZ,
                    minY, height, sortX, sortY, sortZ,
                    palette, stateIndices, packedLight, biomeIndices
            );
        } catch (IOException failure) {
            throw new IllegalArgumentException("invalid mesh snapshot", failure);
        }
    }

    public static byte[] encode(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("null mesh snapshot");
        }
        validate(snapshot);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(600_000);
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(snapshot.minSectionX);
            out.writeInt(snapshot.minSectionY);
            out.writeInt(snapshot.minSectionZ);
            out.writeInt(snapshot.targetSectionX);
            out.writeInt(snapshot.targetSectionY);
            out.writeInt(snapshot.targetSectionZ);
            out.writeInt(snapshot.minY);
            out.writeInt(snapshot.height);
            out.writeFloat(snapshot.sortX);
            out.writeFloat(snapshot.sortY);
            out.writeFloat(snapshot.sortZ);
            out.writeInt(snapshot.palette.length);
            for (int stateId : snapshot.palette) {
                out.writeInt(stateId);
            }
            out.writeInt(BLOCK_COUNT);
            for (int index : snapshot.stateIndices) {
                out.writeInt(index);
            }
            out.writeInt(BLOCK_COUNT);
            out.write(snapshot.packedLight);
            if (snapshot.biomeIndices == null) {
                out.writeInt(0);
            } else {
                out.writeInt(BLOCK_COUNT);
                for (int index : snapshot.biomeIndices) {
                    out.writeInt(index);
                }
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /** Captures the public region API; no Minecraft objects cross the wire. */
    public static Snapshot capture(RenderSectionRegion region, SectionPos target) {
        return capture(region, target, null);
    }

    /** Captures a region and the camera-relative sort origin used by the JAR. */
    public static Snapshot capture(RenderSectionRegion region, SectionPos target, Vec3 camera) {
        Map<Integer, Integer> paletteMap = new HashMap<>();
        int[] stateIndices = new int[BLOCK_COUNT];
        byte[] packedLight = new byte[BLOCK_COUNT];
        int[] paletteScratch = new int[BLOCK_COUNT];
        int paletteSize = 0;
        int cursor = 0;
        int minSectionX = target.x() - 1;
        int minSectionY = target.y() - 1;
        int minSectionZ = target.z() - 1;
        for (int sectionX = minSectionX; sectionX <= minSectionX + 2; sectionX++) {
            for (int sectionY = minSectionY; sectionY <= minSectionY + 2; sectionY++) {
                for (int sectionZ = minSectionZ; sectionZ <= minSectionZ + 2; sectionZ++) {
                    for (int localY = 0; localY < 16; localY++) {
                        for (int localZ = 0; localZ < 16; localZ++) {
                            for (int localX = 0; localX < 16; localX++) {
                                BlockPos pos = new BlockPos(
                                        (sectionX << 4) + localX,
                                        (sectionY << 4) + localY,
                                        (sectionZ << 4) + localZ
                                );
                                int stateId = Block.getId(region.getBlockState(pos));
                                Integer paletteIndex = paletteMap.get(stateId);
                                if (paletteIndex == null) {
                                    paletteIndex = paletteSize;
                                    paletteMap.put(stateId, paletteIndex);
                                    paletteScratch[paletteSize++] = stateId;
                                }
                                stateIndices[cursor] = paletteIndex;
                                int block = region.getBrightness(LightLayer.BLOCK, pos);
                                int sky = region.getBrightness(LightLayer.SKY, pos);
                                packedLight[cursor] = (byte) ((sky << 4) | (block & 0xF));
                                cursor++;
                            }
                        }
                    }
                }
            }
        }
        return new Snapshot(
                minSectionX, minSectionY, minSectionZ,
                target.x(), target.y(), target.z(),
                region.getMinY(), region.getHeight(),
                camera == null ? 0.0f : (float) (camera.x - target.minBlockX()),
                camera == null ? 0.0f : (float) (camera.y - target.minBlockY()),
                camera == null ? 0.0f : (float) (camera.z - target.minBlockZ()),
                Arrays.copyOf(paletteScratch, paletteSize),
                stateIndices, packedLight, null
        );
    }

    /** A deterministic non-empty fixture for worker lifecycle and byte gates. */
    public static Snapshot fixedFixture() {
        int air = Block.getId(Blocks.AIR.defaultBlockState());
        int stone = Block.getId(Blocks.STONE.defaultBlockState());
        int[] palette = {air, stone};
        int[] stateIndices = new int[BLOCK_COUNT];
        byte[] packedLight = new byte[BLOCK_COUNT];
        Arrays.fill(packedLight, (byte) 0xF0);
        // The middle section is an eight-block-high stone platform. Its six
        // exposed boundaries exercise culling, lighting, and vertex emission.
        int centerSection = 13; // (x,y,z)=(1,1,1) in the order below.
        for (int localY = 0; localY < 8; localY++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int localX = 0; localX < 16; localX++) {
                    int local = localIndex(localX, localY, localZ);
                    stateIndices[centerSection * BLOCKS_PER_SECTION + local] = 1;
                }
            }
        }
        return new Snapshot(
                -1, 3, -1,
                0, 4, 0,
                0, 384, 0.0f, 0.0f, 0.0f,
                palette, stateIndices, packedLight, null
        );
    }

    private static int localIndex(int localX, int localY, int localZ) {
        return (localY << 8) | (localZ << 4) | localX;
    }

    private static int[] readIntArray(DataInputStream in, int expected, String label)
            throws IOException {
        int count = in.readInt();
        if (count != expected) {
            throw new IllegalArgumentException("invalid mesh " + label + " count " + count);
        }
        int[] values = new int[count];
        for (int i = 0; i < count; i++) {
            values[i] = in.readInt();
        }
        return values;
    }

    private static byte[] readByteArray(DataInputStream in, int expected, String label)
            throws IOException {
        int count = in.readInt();
        if (count != expected) {
            throw new IllegalArgumentException("invalid mesh " + label + " count " + count);
        }
        byte[] values = new byte[count];
        in.readFully(values);
        return values;
    }

    private static void validate(Snapshot snapshot) {
        if (snapshot.palette.length < 1 || snapshot.palette.length > 65536
                || snapshot.stateIndices.length != BLOCK_COUNT
                || snapshot.packedLight.length != BLOCK_COUNT
                || !Float.isFinite(snapshot.sortX)
                || !Float.isFinite(snapshot.sortY)
                || !Float.isFinite(snapshot.sortZ)
                || (snapshot.biomeIndices != null && snapshot.biomeIndices.length != BLOCK_COUNT)) {
            throw new IllegalArgumentException("invalid mesh snapshot array lengths");
        }
        for (int index : snapshot.stateIndices) {
            if (index < 0 || index >= snapshot.palette.length) {
                throw new IllegalArgumentException("invalid mesh state palette index " + index);
            }
        }
    }
}
