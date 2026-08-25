package dev.mcweb.feature;

import com.oracle.svm.core.thread.Parker;
import com.oracle.svm.core.thread.VMThreads;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.hosted.Feature;

/**
 * Registers browser-safe threading singletons that the LTS svm-wasm backend
 * omits.
 *
 * <ul>
 * <li>A {@link Parker.ParkerFactory}: the wasm backend has no Parker, so
 * image builds fail with "ImageSingletons do not contain key
 * Parker$ParkerFactory" as soon as reachable JDK concurrency code references
 * {@code LockSupport.park} (AQS, ForkJoinPool, CompletableFuture waiting).
 * The browser runtime is cooperatively single-threaded: locks are never
 * contended and a parked operation can never be woken by another thread, so
 * a Parker whose park returns immediately (a spurious wakeup) is safe —
 * AQS-style acquire loops re-check their predicate and proceed or fail
 * normally.</li>
 * <li>A {@link VMThreads} singleton: the analysis universe references the
 * VMThreads singleton for thread handle/id/join helpers even though the
 * browser has exactly one thread; the stub no-ops those operations.</li>
 * </ul>
 */
public final class BrowserParkerFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        // Keep the feature idempotent in case a future GraalVM Web Image
        // release provides one of these single-threaded browser singletons.
        if (!ImageSingletons.contains(Parker.ParkerFactory.class)) {
            ImageSingletons.add(Parker.ParkerFactory.class, new BrowserParkerFactory());
        }
        if (!ImageSingletons.contains(VMThreads.class)) {
            ImageSingletons.add(VMThreads.class, new BrowserVMThreads());
        }
        // Netty's default logger factory is java.util.logging, whose
        // LogManager pulls in parallel streams -> ForkJoinPool ->
        // sun.nio.ch.Poller -> virtual threads, none of which the wasm
        // backend supports. Install a no-op factory at build time so the
        // defaultFactory static never holds the JDK factory. Isolated in a
        // helper so the netty reference only links when netty is present.
        try {
            BrowserNettyLoggerSupport.install();
        } catch (Throwable nettyAbsent) {
            // Netty is not on this image's classpath; nothing to override.
        }
    }
}
