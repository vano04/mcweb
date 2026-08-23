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
        boolean threaded = McWebRuntimeMode.isThreaded();
        Object selectedThread = threaded ? argument : Thread.currentThread();
        BrowserGpu.reportProgress("spin:before-apply mode="
                + (threaded ? "wasmlm-threaded" : "cooperative") + " thread="
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
        if (McWebRuntimeMode.isThreaded()) {
            // Bind diagnostics before start, then honor Mojang's one real Server
            // thread exactly. Vanilla's separate shared background executor handles
            // only the worldgen/light/IO futures the JAR explicitly submits to it.
            ServerOwnedExecutorService.bind(server, serverThread);
            BrowserGpu.reportProgress("integrated-server:thread-start owner=Server thread");
            McWebThreadRole.start(serverThread, McWebThreadRole.SERVER);
            // Existing-world paths may not pass through CreateWorldScreen, so keep
            // this idempotent fallback at the server boundary.
            AgentExecutorService.activateTerrainParallelism();
            BrowserGpu.reportProgress("integrated-server:vanilla-background "
                    + AgentExecutorService.queueState());
            return;
        }
        BrowserIntegratedServerCompat.register(server);
        BrowserGpu.reportProgress("integrated-server:cooperative-register threaded=false");
    }
}
