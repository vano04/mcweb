package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.util.function.Function;
import net.minecraft.server.MinecraftServer;

/**
 * Diagnostic wrapper for the integrated-server factory call inside
 * {@code MinecraftServer.spin}. The factory constructs
 * {@code IntegratedServer}; if that constructor throws or hangs, this
 * class reports the exact failure through the browser host.
 */
public final class BrowserServerSpinCompat {
    private BrowserServerSpinCompat() {
    }

    public static Object applyWithDiagnostics(Function<Object, Object> factory, Object argument) {
        Object selectedThread = Thread.currentThread();
        BrowserGpu.reportProgress("spin:before-apply backend=wasmgc thread="
                + (selectedThread instanceof Thread thread ? thread.getName() : "unknown"));
        try {
            Object result = factory.apply(selectedThread);
            BrowserGpu.reportProgress("spin:after-apply:"
                    + (result == null ? "null" : result.getClass().getName()));
            return result;
        } catch (Throwable failure) {
            BrowserGpu.reportJavaFailure(
                    "spin:apply",
                    failure.getClass().getName(),
                    BrowserMinecraftMain.describeFailure(failure)
            );
            throw failure;
        }
    }

    public static void startOrRegister(Thread serverThread, MinecraftServer server) {
        BrowserIntegratedServerCompat.register(server);
        BrowserGpu.reportProgress("integrated-server:registered backend=wasmgc");
    }
}
