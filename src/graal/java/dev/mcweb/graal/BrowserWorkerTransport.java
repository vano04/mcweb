package dev.mcweb.graal;

import org.graalvm.webimage.api.JS;
import java.util.Base64;
import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

/**
 * Java-to-JavaScript bridge for the server Worker's packet transport.
 * The Worker's {@code mcWebServerTransport} object is set up by
 * {@code server-worker.js} after the Wasm instantiates.
 */
public final class BrowserWorkerTransport {
    private static PacketHandler packetHandler;
    private static CommandHandler commandHandler;
    private static StateHandler stateHandler;
    private static Runnable tickCallback;
    private BrowserWorkerTransport() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServerTransport !== undefined;", args = {})
    public static native boolean isAvailable();

    public static void send(byte[] bytes) {
        sendBase64(Base64.getEncoder().encodeToString(bytes));
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebServerTransport.sendBase64(base64);", args = {"base64"})
    private static native void sendBase64(String base64);

    /**
     * Makes packets already produced by Java visible to the client realm before
     * this Worker enters another long cooperative server slice. Optional by
     * design: the inline WasmGC lane shares this class but has no Worker host.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebServerTransport?.flush?.();", args = {})
    public static native void flush();

    public static void onPacket(PacketHandler handler) {
        packetHandler = handler;
        installPacketHandler();
    }

    @JSRawCall
    @JS("globalThis.mcWebServerTransport.onPacket(base64=>"
            + "getExport('mcweb.server.packet')(toJavaString(String(base64))));")
    private static native void installPacketHandler();

    @WasmExport(value = "mcweb.server.packet", comment = "Dispatch a client packet into the server Worker")
    public static void dispatchPacket(String base64) {
        packetHandler.accept(Base64.getDecoder().decode(base64));
    }

    public static void onCommand(CommandHandler handler) {
        commandHandler = handler;
        installCommandHandler();
    }

    @JSRawCall
    @JS("globalThis.mcWebServerControl.onCommand(json=>"
            + "getExport('mcweb.server.command')(toJavaString(String(json))));")
    private static native void installCommandHandler();

    @WasmExport(value = "mcweb.server.command", comment = "Dispatch a browser command into the server Worker")
    public static void dispatchCommand(String json) {
        commandHandler.accept(json);
    }

    public static void onState(StateHandler handler) {
        stateHandler = handler;
        installStateHandler();
    }

    @JSRawCall
    @JS("globalThis.mcWebServerControl.onState(state=>"
            + "getExport('mcweb.server.state')(toJavaString(String(state))));")
    private static native void installStateHandler();

    @WasmExport(value = "mcweb.server.state", comment = "Dispatch a client state change into the server Worker")
    public static void dispatchState(String state) {
        StateHandler handler = stateHandler;
        if (handler != null) {
            handler.accept(state);
        }
    }

    public static void registerTickCallback(Runnable callback) {
        tickCallback = callback;
        installTickCallback();
    }

    @JSRawCall
    @JS("globalThis.mcWebServerPump.register(()=>getExport('mcweb.server.tick')());")
    private static native void installTickCallback();

    @WasmExport(value = "mcweb.server.tick", comment = "Run one integrated-server tick")
    public static void dispatchTick() {
        tickCallback.run();
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebServerPump.reportTick(count);", args = {"count"})
    public static native void reportTick(long count);

    /**
     * Ships a saved world back to the client realm. An empty string is the
     * "nothing to send" answer, which still releases a client that is waiting.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebServerControl.sendSnapshot(json);", args = {"json"})
    public static native void sendSnapshot(String json);

    @JS.Coerce
    @JS(value = "const r={stage,type,message};"
            + "globalThis.__mcWebServerFailure=r;"
            + "if(globalThis.mcWebServerStatus){"
            + "globalThis.mcWebServerStatus.lastFailure=r;"
            + "if(typeof globalThis.mcWebServerStatus.reportFailure==='function'){"
            + "try{globalThis.mcWebServerStatus.reportFailure(stage,type,message);}"
            + "catch(e){}}}",
            args = {"stage", "type", "message"})
    public static native void reportFailure(String stage, String type, String message);

    @JS.Coerce
    @JS(value = "globalThis.__mcWebServerStage = stage;"
            + "if(globalThis.mcWebServerStatus){"
            + "globalThis.mcWebServerStatus.reportProgress(stage);}",
            args = {"stage"})
    public static native void reportProgress(String stage);

    @FunctionalInterface
    public interface CommandHandler {
        void accept(String json);
    }

    @FunctionalInterface
    public interface PacketHandler {
        void accept(byte[] data);
    }

    @FunctionalInterface
    public interface StateHandler {
        void accept(String state);
    }
}
