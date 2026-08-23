package dev.mcweb.graal;

import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;
import org.graalvm.webimage.api.JS;

/** Main-realm bridge for the opt-in live SectionRenderDispatcher lane. */
public final class BrowserLiveMeshTransport {
    private static boolean installed;

    private BrowserLiveMeshTransport() {
    }

    /** Installs result callbacks before starting the persistent private image. */
    public static void start() {
        if (!installed) {
            installResultHandler();
            installFailureHandler();
            installed = true;
        }
        startHost();
    }

    @JSRawCall
    @JS("globalThis.mcWebLiveMesh.installResult((id,data)=>"
            + "getExport('mcweb.live.mesh.result')(id|0,toJavaString(String(data))));")
    private static native void installResultHandler();

    @JSRawCall
    @JS("globalThis.mcWebLiveMesh.installFailure((id,message)=>"
            + "getExport('mcweb.live.mesh.failure')(id|0,toJavaString(String(message))));")
    private static native void installFailureHandler();

    @JS.Coerce
    @JS(value = "globalThis.mcWebLiveMesh.start();", args = {})
    private static native void startHost();

    @WasmExport(value = "mcweb.live.mesh.result", comment = "Commit a live section mesh result")
    public static void dispatchResult(int id, String resultBase64) {
        BrowserLiveMeshDispatcher.acceptResult(id, resultBase64);
    }

    @WasmExport(value = "mcweb.live.mesh.failure", comment = "Report a live section mesh failure")
    public static void dispatchFailure(int id, String message) {
        BrowserLiveMeshDispatcher.acceptFailure(id, message);
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebLiveMesh.submit(id,resultBase64);",
            args = {"id", "resultBase64"})
    public static native void submit(int id, String resultBase64);

    @JS.Coerce
    @JS(value = "globalThis.mcWebLiveMesh.cancel(id);", args = {"id"})
    public static native void cancel(int id);
}
