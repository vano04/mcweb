package dev.mcweb.graal;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.TracingExecutor;
import net.minecraft.server.MinecraftServer;

/**
 * Integrated-server thread ownership and legacy executor diagnostics.
 *
 * <p>The current WasmLM path binds this object before Mojang starts the real
 * {@code Server thread}, so diagnostics can distinguish that owner from the browser
 * and background workers. It is deliberately <em>not</em> installed in
 * {@code MinecraftServer.executor}: vanilla captures its shared
 * {@code Util.backgroundExecutor()} there for worldgen/light work. The execute methods
 * remain for compatibility with older staged images and should stay at zero in current
 * runs.</p>
 */
public final class ServerOwnedExecutorService extends AbstractExecutorService {
    private static final ConcurrentMap<MinecraftServer, ServerOwnedExecutorService> OWNERS =
            new ConcurrentHashMap<>();
    /**
     * Authoritative logical owner for code executing inside runServer().
     *
     * <p>The WasmLM runtime sets Thread.currentThread() when an agent claims a Java
     * Thread, but some Web Image fast-local paths can still expose a wrapper object.
     * Mojang's runServer entry is exact-count transformed and therefore provides a
     * stronger boundary than Thread object identity.  ThreadLocal is per logical Java
     * thread in this runtime and was separately verified by the thread probes.</p>
     */
    private static final ThreadLocal<ServerOwnedExecutorService> CURRENT_OWNER =
            new ThreadLocal<>();

    private final MinecraftServer server;
    private final TracingExecutor tracing;
    private final AtomicLong submitted = new AtomicLong();
    private final AtomicLong inline = new AtomicLong();
    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger maxQueueDepth = new AtomicInteger();

    private volatile Thread serverThread;
    private volatile boolean shutdown;

    private ServerOwnedExecutorService(MinecraftServer server) {
        this.server = Objects.requireNonNull(server, "server");
        this.tracing = new TracingExecutor(this);
    }

    /** Create and register the owner during MinecraftServer construction. */
    public static java.util.concurrent.ExecutorService create(MinecraftServer server) {
        ServerOwnedExecutorService owner = new ServerOwnedExecutorService(server);
        ServerOwnedExecutorService previous = OWNERS.putIfAbsent(server, owner);
        return previous == null ? owner : previous;
    }

    /** Bind the Mojang Thread object immediately before Thread.start(). */
    public static void bind(MinecraftServer server, Thread thread) {
        ServerOwnedExecutorService owner = OWNERS.get(server);
        if (owner == null) {
            owner = (ServerOwnedExecutorService) create(server);
        }
        owner.serverThread = Objects.requireNonNull(thread, "thread");
        AgentExecutorService.reportServerBound(server, thread);
    }

    /** Called from the server-thread lifecycle exit hook. */
    public static void unregister(MinecraftServer server) {
        ServerOwnedExecutorService owner = OWNERS.remove(server);
        if (owner != null) {
            owner.shutdown = true;
            AgentExecutorService.reportServerExecutorExit(server, owner);
        }
    }

    /** Bind the current logical Java thread for the duration of runServer(). */
    public static void enter(MinecraftServer server) {
        ServerOwnedExecutorService owner = OWNERS.get(server);
        if (owner != null) {
            CURRENT_OWNER.set(owner);
        }
    }

    /** Clear the runServer ownership marker before the Thread exits. */
    public static void leave(MinecraftServer server) {
        ServerOwnedExecutorService owner = CURRENT_OWNER.get();
        if (owner != null && owner.server == server) {
            CURRENT_OWNER.remove();
        }
    }

    /** Return the owner whose real server thread is making this call, if any. */
    public static ServerOwnedExecutorService currentFor(Thread thread) {
        ServerOwnedExecutorService logicalOwner = CURRENT_OWNER.get();
        if (logicalOwner != null) {
            return logicalOwner;
        }
        if (thread == null) {
            return null;
        }
        for (ServerOwnedExecutorService owner : OWNERS.values()) {
            /*
             * The WasmLM thread bridge keeps Thread objects in the shared heap, but
             * a few JDK/Web Image call paths can materialize the current-thread
             * wrapper in an agent-local fast-thread-local slot.  Minecraft's own
             * event-loop predicate is the authoritative ownership check in that
             * case; relying only on object identity makes server-origin work look
             * cross-thread and queues it behind MainThreadExecutor.managedBlock.
             */
            if (owner.serverThread == thread
                    || owner.server.isSameThread()
                    || owner.sameThreadName(thread)) {
                return owner;
            }
        }
        return null;
    }

    public static ServerOwnedExecutorService forServer(MinecraftServer server) {
        return OWNERS.get(server);
    }

    public MinecraftServer server() {
        return server;
    }

    public TracingExecutor tracing() {
        return tracing;
    }

    public int queueDepth() {
        return queueDepth.get();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public long inlineCount() {
        return inline.get();
    }

    public long queuedCount() {
        return queued.get();
    }

    public long completedCount() {
        return completed.get();
    }

    public long rejectedCount() {
        return rejected.get();
    }

    public int maxQueueDepth() {
        return maxQueueDepth.get();
    }

    @Override
    public void execute(Runnable command) {
        Objects.requireNonNull(command, "command");
        if (shutdown || server.isShutdown()) {
            rejected.incrementAndGet();
            throw new RejectedExecutionException("integrated server is shut down");
        }
        submitted.incrementAndGet();
        boolean sameThread = CURRENT_OWNER.get() == this
                || serverThread == Thread.currentThread()
                || server.isSameThread()
                || sameThreadName(Thread.currentThread());
        if (sameThread) {
            inline.incrementAndGet();
        } else {
            queued.incrementAndGet();
        }
        if (!sameThread) {
            int depth = queueDepth.incrementAndGet();
            maxQueueDepth.accumulateAndGet(depth, Math::max);
        }
        Runnable owned = () -> {
            if (!sameThread) {
                queueDepth.decrementAndGet();
            }
            try {
                command.run();
            } finally {
                completed.incrementAndGet();
            }
        };
        try {
            /*
             * Phase 2 intentionally serializes server-origin generation, lighting,
             * parsing and IO through Mojang's real Server event loop. This preserves
             * its same-thread/reentrant behavior and ordered queue polling while
             * preventing the WasmLM client pool from running independent generation
             * steps on three agents. The latter ended in vanilla's own invariant:
             * "Requested chunk unavailable during world generation".
             */
            server.execute(owned);
        } catch (RejectedExecutionException failure) {
            if (!sameThread) {
                queueDepth.decrementAndGet();
            }
            rejected.incrementAndGet();
            throw failure;
        }
    }

    /**
     * WasmLM keeps the Java Thread object in the shared heap, but the current-thread
     * fast-local can still be a wrapper whose identity is not the constructor's
     * object.  The real Minecraft server name is unique for this image and is set
     * before start(), so it is a safe last-resort ownership key until the VM-local
     * identity is repaired.  Never use this for arbitrary executors; this class owns
     * exactly one live integrated-server lane at a time.
     */
    private boolean sameThreadName(Thread thread) {
        Thread ownerThread = serverThread;
        return ownerThread != null
                && thread != null
                && "Server thread".equals(ownerThread.getName())
                && "Server thread".equals(thread.getName());
    }

    @Override
    public void shutdown() {
        shutdown = true;
    }

    @Override
    public List<Runnable> shutdownNow() {
        shutdown = true;
        return Collections.emptyList();
    }

    @Override
    public boolean isShutdown() {
        return shutdown || server.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return isShutdown() && queueDepth.get() == 0;
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isTerminated() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        return isTerminated();
    }
}
