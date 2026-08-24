package dev.mcweb.feature;

import com.oracle.svm.core.thread.Parker;
import com.oracle.svm.shared.Uninterruptible;

/**
 * Cooperatively single-threaded Parker for the browser. park() returns
 * immediately as a spurious wakeup; unpark/reset/release are no-ops because
 * no second thread can ever park or unpark in Web Image's browser runtime.
 */
final class BrowserParker extends Parker {
    BrowserParker() {
        super();
    }

    @Override
    protected void reset() {
    }

    @Override
    protected void park(boolean isAbsolute, long time) {
        // Immediate spurious wakeup: the caller re-checks its condition.
    }

    @Override
    protected void unpark() {
    }

    // Parker.release() is @Uninterruptible (it runs while a thread is being
    // torn down); an override that drops the annotation fails the build with
    // "violations of @Uninterruptible usage".
    @Uninterruptible(reason = "Called during thread teardown.")
    @Override
    protected void release() {
    }
}
