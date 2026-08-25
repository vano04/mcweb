package dev.mcweb.graal;

import java.util.Base64;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.progress.ChunkLoadStatusView;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Client-side rendering source for the loading screen's chunk-status grid on
 * the private server-Worker lane.
 *
 * <p>Vanilla wires the grid in {@code Minecraft.doWorldLoad}:
 * {@code tracker.setServerChunkStatusView(server.createChunkLoadStatusView(radius))},
 * which the Worker lane never executes because it returns from {@code doWorldLoad}
 * before {@code MinecraftServer.spin}. The server Worker samples its own
 * {@code ChunkMap.getLatestStatus} grid instead and ships it over the load-progress
 * channel as {@code levelload:grid cx cz radius base64} (one status index per
 * byte, -1 for ungenerated); this view decodes it into the same
 * {@link ChunkLoadStatusView} shape the screen already renders.</p>
 */
public final class BrowserChunkLoadStatusView implements ChunkLoadStatusView {
    private final int radius;
    private final byte[] grid;

    private BrowserChunkLoadStatusView(int radius, byte[] grid) {
        this.radius = radius;
        this.grid = grid;
    }

    /**
     * Parses one {@code levelload:grid} payload. Returns null on any malformed
     * input: a diagnostic frame must not take down the loading screen.
     */
    public static BrowserChunkLoadStatusView parse(String[] parts) {
        try {
            if (parts.length != 5) {
                return null;
            }
            int radius = Integer.parseInt(parts[3]);
            if (radius <= 0 || radius > 64) {
                return null;
            }
            byte[] grid = Base64.getDecoder().decode(parts[4]);
            int side = 2 * radius + 1;
            if (grid.length != side * side) {
                return null;
            }
            return new BrowserChunkLoadStatusView(radius, grid);
        } catch (IllegalArgumentException | IndexOutOfBoundsException invalid) {
            return null;
        }
    }

    @Override
    public void moveTo(ResourceKey<Level> dimension, ChunkPos center) {
        // The grid message carries its own center; focus changes do not move it.
    }

    @Override
    public ChunkStatus get(int x, int z) {
        int side = 2 * radius + 1;
        if (x < 0 || x >= side || z < 0 || z >= side) {
            return null;
        }
        int index = grid[x * side + z];
        if (index < 0) {
            return null;
        }
        List<ChunkStatus> statuses = ChunkStatus.getStatusList();
        return statuses.get(Math.min(index, statuses.size() - 1));
    }

    @Override
    public int radius() {
        return radius;
    }
}
