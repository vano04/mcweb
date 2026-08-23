package net.minecraft.client.renderer.chunk;

import dev.mcweb.graal.MeshSnapshotWire;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;

/**
 * The JAR's render-region view, with one byte-boundary constructor for a
 * private mesh image.
 *
 * <p>The ordinary constructor and all ordinary methods retain the supplied
 * JAR behaviour. The snapshot form is intentionally data-only: it does not
 * manufacture a {@link ClientLevel}, copy block entities, or smuggle object
 * references across a Worker boundary. {@code SectionCompiler} remains the
 * renderer in both cases.</p>
 */
public class RenderSectionRegion implements BlockAndTintGetter {
    public static final int RADIUS = 1;
    public static final int SIZE = 3;

    private final int minSectionX;
    private final int minSectionY;
    private final int minSectionZ;
    private final SectionCopy[] sections;
    private final ClientLevel level;
    private final CardinalLighting cardinalLighting;
    private final LevelLightEngine lightEngine;
    private final MeshSnapshotWire.Snapshot snapshot;

    public RenderSectionRegion(
            ClientLevel level,
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            SectionCopy[] sections
    ) {
        this.level = level;
        this.minSectionX = minSectionX;
        this.minSectionY = minSectionY;
        this.minSectionZ = minSectionZ;
        this.sections = sections;
        this.cardinalLighting = level.cardinalLighting();
        this.lightEngine = level.getLightEngine();
        this.snapshot = null;
    }

    /** Constructs the worker-side region from a validated packed snapshot. */
    public RenderSectionRegion(MeshSnapshotWire.Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("null mesh snapshot");
        }
        this.level = null;
        this.minSectionX = snapshot.minSectionX;
        this.minSectionY = snapshot.minSectionY;
        this.minSectionZ = snapshot.minSectionZ;
        this.sections = null;
        this.cardinalLighting = CardinalLighting.DEFAULT;
        this.lightEngine = LevelLightEngine.EMPTY;
        this.snapshot = snapshot;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        if (snapshot != null) {
            return snapshot.stateAt(pos);
        }
        return getSection(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())
        ).getBlockState(pos);
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public CardinalLighting cardinalLighting() {
        return cardinalLighting;
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return lightEngine;
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        if (snapshot != null) {
            // Block entities are deliberately kept in the main realm. The
            // output protocol carries positions in a later integration step.
            return null;
        }
        return getSection(
                SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getY()),
                SectionPos.blockToSectionCoord(pos.getZ())
        ).getBlockEntity(pos);
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        return snapshot != null ? snapshot.blockTint(pos) : level.getBlockTint(pos, resolver);
    }

    @Override
    public int getMinY() {
        return snapshot != null ? snapshot.minY : level.getMinY();
    }

    @Override
    public int getHeight() {
        return snapshot != null ? snapshot.height : level.getHeight();
    }

    /** Snapshot light is already packed; avoid constructing a light engine. */
    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return snapshot != null
                ? snapshot.lightAt(pos, layer)
                : lightEngine.getLayerListener(layer).getLightValue(pos);
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        if (snapshot == null) {
            return lightEngine.getRawBrightness(pos, amount);
        }
        return Math.max(0, snapshot.lightAt(pos, LightLayer.SKY) - amount);
    }

    private SectionCopy getSection(int sectionX, int sectionY, int sectionZ) {
        return sections[index(
                minSectionX, minSectionY, minSectionZ,
                sectionX, sectionY, sectionZ
        )];
    }

    public static int index(
            int minSectionX,
            int minSectionY,
            int minSectionZ,
            int sectionX,
            int sectionY,
            int sectionZ
    ) {
        return (sectionZ - minSectionZ)
                + (sectionY - minSectionY) * 3
                + (sectionX - minSectionX) * 9;
    }
}
