package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.LevelLoadTracker;

/**
 * Keeps the browser on Minecraft's loading screen until the first terrain
 * meshes are actually renderable.  The server's load tracker describes world
 * generation, not the later client-side compile/upload step, so using that
 * signal alone exposes the grey interim world seen on the Worker lane.
 */
public final class BrowserTerrainEntryGate {
    private static final int REQUIRED_NEARBY_RENDERABLE = 4;
    private static final int REQUIRED_CONSECUTIVE_FRAMES = 2;

    private static LevelLoadTracker lastTracker;
    private static int consecutiveReady;
    private static int sampleCount;
    private static boolean reportedReady;

    private BrowserTerrainEntryGate() {
    }

    /**
     * Called from the real JAR's LevelLoadingScreen.tick() by an exact-counted
     * bytecode seam.  The vanilla tracker remains authoritative; this only
     * adds the browser renderer's missing readiness condition on the private
     * server-Worker lane.
     */
    public static boolean isLevelReady(LevelLoadTracker tracker) {
        if (tracker != lastTracker) {
            lastTracker = tracker;
            consecutiveReady = 0;
            sampleCount = 0;
            reportedReady = false;
        }

        boolean vanillaReady = tracker != null && tracker.isLevelReady();
        if (!vanillaReady) {
            consecutiveReady = 0;
            return false;
        }

        // The cooperative integrated-server path does not have the separate
        // server/render pipeline this gate is repairing. Preserve its vanilla
        // screen timing while the private Worker lane gets the visual guard.
        if (!BrowserWorkerClientCompat.isActive()) {
            return true;
        }

        TerrainCounts counts = sampleTerrain();
        sampleCount++;
        if (counts.nearbyRenderable < REQUIRED_NEARBY_RENDERABLE) {
            consecutiveReady = 0;
            if (sampleCount == 1 || sampleCount % 30 == 0) {
                BrowserGpu.reportProgress("terrain-entry:hold nearby="
                        + counts.nearbyRenderable + " visible=" + counts.visibleRenderable);
            }
            return false;
        }

        consecutiveReady++;
        if (consecutiveReady < REQUIRED_CONSECUTIVE_FRAMES) {
            return false;
        }

        if (!reportedReady) {
            reportedReady = true;
            BrowserGpu.reportProgress("terrain-entry:ready nearby="
                    + counts.nearbyRenderable + " visible=" + counts.visibleRenderable);
            if (BrowserWorkerClientCompat.isActive()) {
                // The Worker server realm has no Minecraft instance, so its
                // loadingWorld() can only learn about this screen dropping via
                // the control channel. Without the signal it keeps bursting
                // accelerated load ticks forever and never reaches 20 TPS.
                BrowserWorkerClientTransport.sendState("world-entered");
            }
        }
        return true;
    }

    private static TerrainCounts sampleTerrain() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.level == null || client.levelRenderer == null
                    || client.levelRenderer.viewArea() == null || client.player == null) {
                return TerrainCounts.EMPTY;
            }

            int visibleRenderable = 0;
            for (var section : client.levelRenderer.visibleSections()) {
                var mesh = section.getSectionMesh();
                if (mesh != null && mesh.hasRenderableLayers()) {
                    visibleRenderable++;
                }
            }

            int nearbyRenderable = 0;
            var area = client.levelRenderer.viewArea();
            var origin = client.player.blockPosition();
            for (int dx = -32; dx <= 32; dx += 16) {
                for (int dz = -32; dz <= 32; dz += 16) {
                    for (int dy = -32; dy <= 16; dy += 16) {
                        var section = area.getRenderSectionAt(origin.offset(dx, dy, dz));
                        if (section == null) {
                            continue;
                        }
                        var mesh = section.getSectionMesh();
                        if (mesh != null && mesh.hasRenderableLayers()) {
                            nearbyRenderable++;
                        }
                    }
                }
            }
            return new TerrainCounts(visibleRenderable, nearbyRenderable);
        } catch (Throwable ignored) {
            // A transient renderer teardown must keep the loading screen up;
            // it must never turn a diagnostic probe into a frame failure.
            return TerrainCounts.EMPTY;
        }
    }

    private static final class TerrainCounts {
        private static final TerrainCounts EMPTY = new TerrainCounts(0, 0);

        private final int visibleRenderable;
        private final int nearbyRenderable;

        private TerrainCounts(int visibleRenderable, int nearbyRenderable) {
            this.visibleRenderable = visibleRenderable;
            this.nearbyRenderable = nearbyRenderable;
        }
    }
}
