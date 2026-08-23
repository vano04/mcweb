package net.minecraft.server.packs.resources;

import dev.mcweb.graal.BrowserReloadDiagnostics;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Package bridge for the protected {@link SimpleReloadInstance.StateFactory}. */
public final class ReloadDiagnosticsBridge {
    private ReloadDiagnosticsBridge() {
    }

    /** Exact semantic replacement for {@link SimpleReloadInstance.StateFactory#create}. */
    public static <S> CompletableFuture<S> createState(
            SimpleReloadInstance.StateFactory<S> factory,
            PreparableReloadListener.SharedState sharedState,
            PreparableReloadListener.PreparationBarrier barrier,
            PreparableReloadListener listener,
            Executor preparationExecutor,
            Executor mainExecutor,
            SimpleReloadInstance<?> instance,
            boolean preparationIsAgentBacked
    ) {
        BrowserReloadDiagnostics.listenerCreateStarted(instance, listener);
        try {
            CompletableFuture<S> future = factory.create(
                    sharedState,
                    barrier,
                    listener,
                    BrowserReloadDiagnostics.listenerExecutor(
                            listener,
                            "prepare",
                            preparationExecutor,
                            preparationIsAgentBacked
                    ),
                    BrowserReloadDiagnostics.listenerExecutor(
                            listener,
                            "apply",
                            mainExecutor,
                            false
                    )
            );
            return BrowserReloadDiagnostics.trackListenerFuture(instance, listener, future);
        } catch (RuntimeException | Error failure) {
            BrowserReloadDiagnostics.listenerCreateFailed(instance, listener, failure);
            throw failure;
        }
    }
}
