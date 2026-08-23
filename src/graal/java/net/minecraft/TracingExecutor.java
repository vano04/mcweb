package net.minecraft;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Single-thread browser implementation of Minecraft's task tracing facade.
 *
 * <p>Execution still delegates to the supplied service. The browser factory
 * supplies {@code InlineExecutorService}, whose post-task main-loop drain is
 * required for resource-reload apply work such as font and atlas updates.
 */
public record TracingExecutor(ExecutorService service) implements Executor {
    public TracingExecutor {
        Objects.requireNonNull(service, "service");
    }

    public Executor forName(String name) {
        return this;
    }

    @Override
    public void execute(Runnable command) {
        service.execute(Objects.requireNonNull(command, "command"));
    }

    public void shutdownAndAwait(long timeout, TimeUnit unit) {
        // No worker is started: all submitted tasks have already run inline.
    }
}
