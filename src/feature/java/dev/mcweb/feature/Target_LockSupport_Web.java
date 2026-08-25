package dev.mcweb.feature;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Cooperative-single-threaded LockSupport for the browser Web Image.
 *
 * <p>SVM ships no LockSupport substitution, so the original bytecode runs and
 * calls {@code jdk.internal.misc.Unsafe.park/unpark} — which the Web Image
 * backend substitutes to throw {@code UnsupportedOperationException:
 * Unsafe.park/unpark} (via VMError.unimplemented). Any {@code java.util.
 * concurrent} blocking (CompletableFuture waiters, AQS, FutureTask, queues)
 * therefore dies the moment a future completes and postComplete unparks a
 * registered waiter — observed as {@code CompletionException:
 * Unsafe.unpark} on the first rendered frame.
 *
 * <p>There is exactly one thread, so: {@code park*} returns immediately (a
 * spurious wakeup — every j.u.c caller re-checks its predicate in a loop),
 * {@code unpark} is a no-op (nobody is ever really suspended), and
 * {@code getBlocker} has nothing to report. CompletableFuture waiters then
 * busy-check until their future completes inline on this same thread.
 *
 * <p>Registered via {@code -H:+AllowDeprecatedBuilderClassesOnImageClasspath};
 * unlike the Parker shadowing attempt, LockSupport has no competing built-in
 * substitution, so this one wins.
 */
@TargetClass(className = "java.util.concurrent.locks.LockSupport")
final class Target_LockSupport_Web {

    @Substitute
    public static void unpark(Thread thread) {
        // No thread is ever suspended; nothing to wake.
    }

    @Substitute
    public static void park() {
        // Immediate spurious wakeup.
    }

    @Substitute
    public static void park(Object blocker) {
    }

    @Substitute
    public static void parkNanos(long nanos) {
    }

    @Substitute
    public static void parkNanos(Object blocker, long nanos) {
    }

    @Substitute
    public static void parkUntil(long deadline) {
    }

    @Substitute
    public static void parkUntil(Object blocker, long deadline) {
    }

    @Substitute
    public static Object getBlocker(Thread thread) {
        return null;
    }
}
