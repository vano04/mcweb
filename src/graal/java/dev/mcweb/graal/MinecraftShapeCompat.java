package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/** Browser-runtime replacement for Block's broken static Guava shape cache. */
public final class MinecraftShapeCompat {
    private static boolean reportedFireShapeRecovery;

    private MinecraftShapeCompat() {
    }

    public static boolean isShapeFullBlock(VoxelShape shape) {
        // The first state is AIR. Web Image exposes Shapes.EMPTY as null while
        // Blocks.<clinit> is populating state caches; vanilla's answer for an
        // empty shape is false, so preserve that result without dereferencing it.
        if (shape == null) {
            return false;
        }
        return !Shapes.joinIsNotEmpty(Shapes.block(), shape, BooleanOp.NOT_SAME);
    }

    /**
     * Restores the exact vanilla FireBlock outline when its precomputed
     * state-to-shape function returns null in Web Image. The original method
     * starts with {@code Block.boxZ(16, 0, 1)}, rotates it to each enabled
     * face, unions those faces, and falls back to
     * {@code Block.column(16, 0, 1)} when none are enabled.
     */
    public static VoxelShape restoreFireShape(VoxelShape shape, BlockState state) {
        if (shape != null) {
            return shape;
        }
        VoxelShape side = Block.boxZ(16.0D, 0.0D, 1.0D);
        Map<Direction, VoxelShape> sides = Shapes.rotateAll(side);
        VoxelShape restored = Shapes.empty();
        if (state.getValue(FireBlock.NORTH)) {
            restored = Shapes.or(restored, sides.get(Direction.NORTH));
        }
        if (state.getValue(FireBlock.EAST)) {
            restored = Shapes.or(restored, sides.get(Direction.EAST));
        }
        if (state.getValue(FireBlock.SOUTH)) {
            restored = Shapes.or(restored, sides.get(Direction.SOUTH));
        }
        if (state.getValue(FireBlock.WEST)) {
            restored = Shapes.or(restored, sides.get(Direction.WEST));
        }
        if (state.getValue(FireBlock.UP)) {
            restored = Shapes.or(restored, sides.get(Direction.UP));
        }
        if (restored.isEmpty()) {
            restored = Block.column(16.0D, 0.0D, 1.0D);
        }
        if (!reportedFireShapeRecovery) {
            reportedFireShapeRecovery = true;
            BrowserGpu.reportProgress("fire-shape:recovered-null");
        }
        return restored;
    }

    /** Restores the vanilla one-pixel-high outline shared by base fire blocks. */
    public static VoxelShape restoreBaseFireShape(VoxelShape shape) {
        return shape != null ? shape : Block.column(16.0D, 0.0D, 1.0D);
    }
}
