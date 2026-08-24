package dev.mcweb.feature;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.thread.JavaSpinLockUtils;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Replaces SVM's pause-node retry loop with a plain bounded CAS loop.
 *
 * <p>Neither Web Image Wasm backend lowers {@code PauseNode}
 * ({@code Thread.onSpinWait}), so the upstream overload fails while compiling.
 * Retrying the existing one-shot atomic fast path preserves the lock contract
 * without introducing that unsupported compiler node. The public image is
 * single-threaded, but the bounded retry preserves the upstream lock contract.
 */
@TargetClass(JavaSpinLockUtils.class)
final class Target_JavaSpinLockUtils_Web {

    @Substitute
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static boolean tryLock(Object obj, long intFieldOffset, int retries) {
        for (int i = 0; i < retries; i++) {
            if (JavaSpinLockUtils.tryLock(obj, intFieldOffset)) {
                return true;
            }
        }
        return false;
    }
}
