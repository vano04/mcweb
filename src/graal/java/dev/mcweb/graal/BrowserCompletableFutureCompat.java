package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import net.minecraft.ReportedException;

/**
 * Browser-safe retrieval for futures that Mojang has already waited to
 * completion with {@code Minecraft.managedBlock}.
 *
 * <p>Web Image's cooperatively single-threaded runtime can enter
 * {@link CompletableFuture#join()} after {@code isDone()} became true yet
 * remain in the JDK blocking-wait path because park/unpark are browser no-ops.
 * {@code getNow} preserves the completed value and exceptional-completion
 * behavior without registering a waiter that no second thread can wake.
 */
public final class BrowserCompletableFutureCompat {
    private BrowserCompletableFutureCompat() {
    }

    public static Object joinCompleted(CompletableFuture<?> future) {
        Future.State state = future.state();
        BrowserGpu.reportProgress("world-create:future-state:" + state);
        if (state == Future.State.FAILED) {
            Throwable failure = future.exceptionNow();
            BrowserGpu.reportProgress(
                    "world-create:future-failure:"
                            + failure.getClass().getName() + ":"
                            + BrowserMinecraftMain.describeFailure(failure)
            );
            if (failure instanceof ReportedException reported) {
                reportChunks(
                        "world-create:crash-report:",
                        reported.getReport().getDetails()
                );
            }
            throw new CompletionException(failure);
        }
        if (state != Future.State.SUCCESS) {
            throw new IllegalStateException(
                    "World-creation future was not successful after managedBlock: " + state
            );
        }
        Object result = future.resultNow();
        BrowserGpu.reportProgress("world-create:resultNow-returned");
        return result;
    }

    private static void reportChunks(String prefix, String value) {
        int chunkSize = 1500;
        int count = Math.max(1, (value.length() + chunkSize - 1) / chunkSize);
        for (int index = 0; index < count; index++) {
            int start = index * chunkSize;
            int end = Math.min(value.length(), start + chunkSize);
            BrowserGpu.reportProgress(
                    prefix + (index + 1) + "/" + count + ":" + value.substring(start, end)
            );
        }
    }
}
