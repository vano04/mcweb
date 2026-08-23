package dev.mcweb.feature;

import com.oracle.svm.guest.staging.core.thread.OSThreadHandle;
import com.oracle.svm.guest.staging.core.thread.OSThreadId;
import com.oracle.svm.core.thread.VMThreads;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.word.WordFactory;

/**
 * Single-threaded VMThreads for the browser. The LTS svm-wasm backend ships
 * single-threaded monitor/parker substitutes but no VMThreads implementation,
 * while the analysis universe still references it (thread join/id/handle
 * helpers). Every operation is a no-op or a null word: the browser runtime
 * has exactly one thread that is never joined, identified, or transitioned.
 */
public final class BrowserVMThreads extends VMThreads {
    @Override
    public void failFatally(int exitCode, CCharPointer message) {
        throw new IllegalStateException("fatal native VM error (exit code " + exitCode + ")");
    }

    @Override
    protected void joinNoTransition(OSThreadHandle osThreadHandle) {
        // No second OS thread exists to join.
    }

    @Override
    protected OSThreadHandle getCurrentOSThreadHandle() {
        return WordFactory.nullPointer();
    }

    @Override
    protected OSThreadId getCurrentOSThreadId() {
        return WordFactory.nullPointer();
    }
}
