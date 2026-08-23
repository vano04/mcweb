package dev.mcweb.graal;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

/**
 * Track A (Path A) threading conformance image.
 *
 * <p>Build:
 * <pre>
 * ./gradlew buildGraalWeb \
 *   -PgraalMainClass=dev.mcweb.graal.ThreadConformanceMain \
 *   -PgraalOutputName=thread-conformance
 * </pre>
 *
 * <p>Serve, open {@code /?image=thread-conformance}, and poll
 * {@code globalThis.mcWebConformance.reports}. The image runs a fixed matrix
 * of Java concurrency primitives and reports the <i>actual</i> behavior of
 * the installed Web Image runtime, then asks the host to exercise the browser
 * primitives Path A needs (a second Wasm instance in a Worker, and a
 * SharedArrayBuffer/Atomics exchange). Every check is exception-guarded so the
 * matrix always completes.
 *
 * <p><b>Honest scope.</b> This image documents and verifies the boundary; it
 * does not move it. {@code Thread.start()} remains a substituted no-op and the
 * heap remains single-threaded until Web Image itself implements
 * shared-memory codegen + Worker bootstrap (upstream {@code GR-42163}; see
 * {@code docs/SESSION-2026-07-26-TRACK-A-THREADING-HANDOFF.md} for the exact
 * patch inventory).
 */
public final class ThreadConformanceMain {
    /** See monitor.waitTimeout: monitors must be taken on fields, not on
     *  freshly allocated locals, for the WasmLM backend to compile them. */
    private static final Object REENTRANT_LOCK = new Object();
    private static final Object WAIT_LOCK = new Object();

    private ThreadConformanceMain() {
    }

    private static void ok(String name, String detail) {
        BrowserThreadProbe.report(name, "ok", detail);
    }

    private static void fail(String name, String detail) {
        BrowserThreadProbe.report(name, "fail", detail);
    }

    private static void expected(String name, String detail) {
        BrowserThreadProbe.report(name, "expected", detail);
    }

    private static void check(String name, Supplier<String> body) {
        try {
            String detail = body.get();
            ok(name, detail);
        } catch (Throwable t) {
            fail(name, t.getClass().getName() + ": " + t.getMessage());
        }
    }

    public static void main(String[] args) {
        boolean workerMode = args.length > 0 && "--worker-probe".equals(args[0]);
        if (workerMode) {
            runWorkerProbe();
            return;
        }
        runPageSuite();
    }

    /** Runs inside the Worker's second Wasm instance. */
    private static void runWorkerProbe() {
        ok("image.boot", "worker instance entered main; thread=" + Thread.currentThread().getName());
        check("image.alloc", () -> {
            int[] xs = new int[1024];
            for (int i = 0; i < xs.length; i++) {
                xs[i] = i * 3;
            }
            int sum = 0;
            for (int x : xs) {
                sum += x;
            }
            return "allocated + summed 1024 ints, sum=" + sum;
        });
        check("image.cas", () -> {
            AtomicInteger counter = new AtomicInteger();
            for (int i = 0; i < 1000; i++) {
                counter.incrementAndGet();
            }
            return "AtomicInteger reached " + counter.get();
        });
        ok("image.done", "worker probe complete");
    }

    private static void runPageSuite() {
        ok("boot", "thread-conformance image started");

        // --- Environment facts the browser must provide for Path A ---------
        BrowserThreadProbe.report(
                "env.crossOriginIsolated",
                BrowserThreadProbe.isCrossOriginIsolated() ? "ok" : "fail",
                "globalThis.crossOriginIsolated");
        BrowserThreadProbe.report(
                "env.sharedArrayBuffer",
                BrowserThreadProbe.hasSharedArrayBuffer() ? "ok" : "fail",
                "typeof SharedArrayBuffer");
        BrowserThreadProbe.report(
                "env.atomicsWait",
                BrowserThreadProbe.hasAtomicsWait() ? "ok" : "fail",
                "Atomics.wait present");

        // --- 1. Thread lifecycle: the substituted start0 boundary ----------
        boolean[] ran = {false};
        Thread spawned = new Thread(() -> ran[0] = true, "mcweb-conformance");
        spawned.start();
        try {
            spawned.join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (ran[0]) {
            ok("thread.start0", "Runnable executed: Web Image started a real thread");
        } else {
            expected("thread.start0",
                    "start() returned and join(500) completed, but the Runnable never ran"
                            + " (start0 is substituted empty; isAlive=" + spawned.isAlive() + ")");
        }

        // --- 2. Single-instance primitives that MUST keep working ----------
        check("atomics.cas", () -> {
            AtomicInteger i = new AtomicInteger(41);
            boolean swapped = i.compareAndSet(41, 42);
            AtomicReference<String> ref = new AtomicReference<>("a");
            boolean refSwapped = ref.compareAndSet("a", "b");
            return "int CAS=" + swapped + " value=" + i.get()
                    + "; ref CAS=" + refSwapped + " value=" + ref.get();
        });

        check("atomics.getAndAdd", () -> {
            AtomicInteger i = new AtomicInteger(0);
            int prev = i.getAndAdd(7);
            return "prev=" + prev + " now=" + i.get();
        });

        check("volatile.visibility", () -> {
            VolatileBox.flag = false;
            VolatileBox.flag = true;
            return "volatile write/read round-trip=" + VolatileBox.flag;
        });

        check("monitor.reentrant", () -> {
            Object lock = REENTRANT_LOCK;
            synchronized (lock) {
                synchronized (lock) {
                    return "reentrant enter/exit twice";
                }
            }
        });

        check("monitor.waitTimeout", () -> {
            // WAIT_LOCK is a static field on purpose. Locking a freshly
            // allocated, non-escaping local and calling wait() on it makes the
            // WasmLM backend fail to compile ("Tried to lower unknown node:
            // MonitorEnter") -- see docs/SESSION-2026-07-26-WASMLM-SHARED-HEAP.md
            // §5. Locking a field avoids it and matches how real code locks.
            Object lock = WAIT_LOCK;
            long t0 = System.nanoTime();
            synchronized (lock) {
                try {
                    lock.wait(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return "wait(50) interrupted";
                }
            }
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            return "wait(50) returned after ~" + ms + " ms (timeout path)";
        });

        check("park.spurious", () -> {
            long t0 = System.nanoTime();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
            // BrowserParker returns immediately: a real park would take ~50 ms.
            return "parkNanos(50ms) returned after ~" + ms + " ms"
                    + (ms < 10 ? " (immediate spurious wakeup: BrowserParker active)" : "");
        });

        check("threadLocal", () -> {
            ThreadLocal<String> tl = ThreadLocal.withInitial(() -> "initial");
            String first = tl.get();
            tl.set("updated");
            return "initial=" + first + " updated=" + tl.get();
        });

        check("thread.currentThread", () -> {
            Thread current = Thread.currentThread();
            return "name=" + current.getName() + " id=" + current.threadId()
                    + " alive=" + current.isAlive();
        });

        // interrupt() is part of the same single-threaded substitution
        // boundary as start0: under Web Image it is a no-op, so the flag
        // never sets. Report the actual behavior rather than failing.
        try {
            Thread current = Thread.currentThread();
            Thread.interrupted(); // clear residue
            current.interrupt();
            boolean set = current.isInterrupted();
            boolean readAndCleared = Thread.interrupted();
            boolean cleared = !current.isInterrupted();
            if (set && readAndCleared && cleared) {
                ok("thread.interruptFlag",
                        "interrupt() sets flag; Thread.interrupted() reads and clears it");
            } else {
                expected("thread.interruptFlag",
                        "interrupt() is a no-op: set=" + set
                                + " interrupted()=" + readAndCleared
                                + " cleared=" + cleared
                                + " (part of the start0 substitution boundary)");
            }
        } catch (Throwable t) {
            fail("thread.interruptFlag", t.getClass().getName() + ": " + t.getMessage());
        }

        // --- 3. The executor boundary Minecraft actually hits --------------
        // ForkJoinPool.commonPool drives CompletableFuture.*Async and
        // Minecraft's Util.backgroundExecutor on desktop. Under Web Image it
        // reaches jdk.internal.misc.Unsafe.park/unpark, which are substituted
        // to throw; InlineExecutorService exists precisely to avoid this.
        try {
            CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> "async");
            String value = future.get(2, TimeUnit.SECONDS);
            ok("executor.commonPool", "supplyAsync completed: " + value
                    + " (common pool works — revalidate InlineExecutorService)");
        } catch (TimeoutException e) {
            expected("executor.commonPool",
                    "supplyAsync never completed within 2 s (task parked; no worker can unpark)");
        } catch (Throwable t) {
            Throwable root = t;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            expected("executor.commonPool",
                    "supplyAsync failed: " + root.getClass().getName() + ": " + root.getMessage());
        }
        check("executor.forkJoinParallelism", () ->
                "ForkJoinPool.getCommonPoolParallelism=" + ForkJoinPool.getCommonPoolParallelism());

        // --- 4. Host primitives Path A's launcher must provide -------------
        // Async: results arrive as worker.* / sab.* reports.
        BrowserThreadProbe.spawnWorker("graal/thread-conformance.js.wasm", "image", 0);
        BrowserThreadProbe.spawnWorker("", "atomics", 100);
        ok("host.dispatched", "worker image boot + SAB/Atomics ping-pong dispatched to host");
    }

    private static final class VolatileBox {
        static volatile boolean flag;
    }
}
