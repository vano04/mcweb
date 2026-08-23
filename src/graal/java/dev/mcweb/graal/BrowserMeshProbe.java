package dev.mcweb.graal;

import java.util.Base64;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;
import org.graalvm.webimage.api.JS;

/** Opt-in fixed-snapshot comparison between the inline JAR path and a Worker. */
public final class BrowserMeshProbe {
    private static boolean attempted;
    private static boolean configured;
    private static boolean requested;

    private BrowserMeshProbe() {
    }

    public static void maybeRun(Minecraft client) {
        if (attempted || !requested() || client == null || !client.isGameLoadFinished()) {
            return;
        }
        attempted = true;
        try {
            MeshSnapshotWire.Snapshot snapshot = MeshSnapshotWire.fixedFixture();
            SectionPos target = SectionPos.of(
                    snapshot.targetSectionX,
                    snapshot.targetSectionY,
                    snapshot.targetSectionZ
            );
            RenderSectionRegion region = new RenderSectionRegion(snapshot);
            byte[] snapshotBytes = MeshSnapshotWire.encode(snapshot);
            byte[] inlineBytes = BrowserMeshWorkerMain.compileInline(client, target, region);
            publish(
                    Base64.getEncoder().encodeToString(snapshotBytes),
                    Base64.getEncoder().encodeToString(inlineBytes),
                    target.x(), target.y(), target.z()
            );
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "mesh-probe:published snapshot=" + snapshotBytes.length
                            + " inline=" + inlineBytes.length
            );
        } catch (Throwable failure) {
            dev.mcweb.graal.webgpu.BrowserGpu.reportJavaFailure(
                    "mesh-probe",
                    failure.getClass().getName(),
                    BrowserMinecraftMain.describeFailure(failure)
            );
        }
    }

    private static boolean requested() {
        if (!configured) {
            requested = enabled();
            configured = true;
        }
        return requested;
    }

    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location?.search || '')"
            + ".get('mcweb_mesh_probe') === '1';", args = {})
    private static native boolean enabled();

    @JS.Coerce
    @JS(value = "if(globalThis.mcWebMeshProbe&&typeof globalThis.mcWebMeshProbe.publish==='function')"
            + "globalThis.mcWebMeshProbe.publish(snapshotBase64,inlineBase64,x,y,z);",
            args = {"snapshotBase64", "inlineBase64", "x", "y", "z"})
    private static native void publish(
            String snapshotBase64,
            String inlineBase64,
            int x,
            int y,
            int z
    );
}
