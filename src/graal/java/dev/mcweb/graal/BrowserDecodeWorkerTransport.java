package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;
import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

/** Java-to-JavaScript bridge for persistent byte-oriented decode Workers. */
public final class BrowserDecodeWorkerTransport {
    private static DecodeHandler requestHandler;
    private BrowserDecodeWorkerTransport() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebDecodeWorker !== undefined;", args = {})
    public static native boolean isAvailable();

    public static void onRequest(DecodeHandler handler) {
        requestHandler = handler;
        installOnRequest();
    }

    @JSRawCall
    @JS("globalThis.mcWebDecodeWorker.onRequest((id,data,channels)=>"
            + "getExport('mcweb.decode.request')(id|0,toJavaString(String(data)),channels|0));")
    private static native void installOnRequest();

    @WasmExport(value = "mcweb.decode.request", comment = "Dispatch a decode Worker request into Java")
    public static void dispatchRequest(int id, String dataBase64, int desiredChannels) {
        requestHandler.accept(id, dataBase64, desiredChannels);
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebDecodeWorker.respond(id,width,height,comp,pixelsBase64);",
            args = {"id", "width", "height", "comp", "pixelsBase64"})
    public static native void respond(int id, int width, int height, int comp, String pixelsBase64);

    @JS.Coerce
    @JS(value = "globalThis.mcWebDecodeWorker.fail(id,type,message);",
            args = {"id", "type", "message"})
    public static native void fail(int id, String type, String message);

    @FunctionalInterface
    public interface DecodeHandler {
        void accept(int id, String dataBase64, int desiredChannels);
    }
}
