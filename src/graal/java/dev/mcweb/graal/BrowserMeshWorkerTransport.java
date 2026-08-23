package dev.mcweb.graal;

import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;
import java.util.Base64;
import org.graalvm.webimage.api.JS;

/** Java/Worker bridge for packed section-mesh requests and results. */
public final class BrowserMeshWorkerTransport {
    private static MeshHandler requestHandler;
    private static Runnable pumpHandler;

    private BrowserMeshWorkerTransport() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebMeshWorker !== undefined;", args = {})
    public static native boolean isAvailable();

    public static void onRequest(MeshHandler handler) {
        requestHandler = handler;
        installRequestHandler();
    }

    @JSRawCall
    @JS("globalThis.mcWebMeshWorker.onRequest((id,data)=>"
            + "getExport('mcweb.mesh.request')(id|0,toJavaString(String(data))));")
    private static native void installRequestHandler();

    @WasmExport(value = "mcweb.mesh.request", comment = "Dispatch a packed section snapshot into the mesh image")
    public static void dispatchRequest(int id, String snapshotBase64) {
        MeshHandler handler = requestHandler;
        if (handler == null) {
            fail(id, "IllegalStateException", "mesh request handler is not installed");
            return;
        }
        handler.accept(id, snapshotBase64);
    }

    public static void onPump(Runnable handler) {
        pumpHandler = handler;
        installPumpHandler();
    }

    @JSRawCall
    @JS("globalThis.mcWebMeshWorker.registerPump(()=>getExport('mcweb.mesh.pump')());")
    private static native void installPumpHandler();

    @WasmExport(value = "mcweb.mesh.pump", comment = "Advance private mesh-image resource loading")
    public static void dispatchPump() {
        Runnable handler = pumpHandler;
        if (handler != null) {
            handler.run();
        }
    }

    public static void respond(int id, byte[] result, long elapsedMillis) {
        respondBase64(
                id,
                Base64.getEncoder().encodeToString(result),
                elapsedMillis
        );
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebMeshWorker.respond(id,resultBase64,elapsedMillis);",
            args = {"id", "resultBase64", "elapsedMillis"})
    private static native void respondBase64(int id, String resultBase64, long elapsedMillis);

    @JS.Coerce
    @JS(value = "globalThis.mcWebMeshWorker.fail(id,type,message);",
            args = {"id", "type", "message"})
    public static native void fail(int id, String type, String message);

    @JS.Coerce
    @JS(value = "globalThis.mcWebMeshWorker.ready();", args = {})
    public static native void ready();

    @FunctionalInterface
    public interface MeshHandler {
        void accept(int id, String snapshotBase64);
    }
}
