/*
 * MC-Web builder patch: System.nanoTime() without a JS boundary crossing.
 *
 * Web Image lowers System.nanoTime() to JSFunctionIntrinsics.performanceNow(), a
 * wasm->JS call. That is fine on a thread that can block, and ruinous on the browser
 * thread: McWebLMThreads.park returns immediately there (the primary may not block), so
 * every `while (!done) park()` in java.util.concurrent becomes a tight loop that
 * recomputes its deadline every turn.
 *
 * Measured on the threaded lane before this patch:
 *   - 5.0-7.6 MILLION performance.now crossings per second
 *   - 126,000-158,000 per rendered frame, already at the menu with no world loaded
 *   - a CPU profile attributing 45.3% of the game thread to `performance.now`,
 *     12.0% to `now` and 3.0% to `wasm-to-js`
 * and it is why a frame cost ~33 ms whether 5 or 329 chunks were on screen: the cost is
 * fixed per frame and independent of what is drawn.
 *
 * Two cheaper-looking fixes were measured and both regressed, so do not retry them:
 *   - pausing inside the primary's park (512 read-only spins) slowed managedBlock's task
 *     draining, because that loop is `while (!done) { if (!pollTask()) waitForTasks(); }`
 *     and is doing real work: world load fell to 2 client chunks in 7 minutes, from 56.
 *   - caching performance.now() in the bridge and refreshing every 16th call froze the
 *     clock that deadline loops need in order to terminate: frameMs p50 1132 ms vs 33 ms.
 *
 * A worker publishes performance.now() into the shared control block continuously (see
 * web/thread-host.js), so the value keeps advancing in real time and the loop keeps its
 * iteration rate; only the crossing disappears. The seqlock lets a 64-bit value be read
 * without tearing: the writer bumps the version to odd, writes lo/hi, bumps it to even.
 */
package com.oracle.svm.hosted.webimage.wasm.gc;

import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.webimage.functionintrinsics.JSFunctionIntrinsics;
import com.oracle.svm.webimage.platform.WebImageWasmLMPlatform;

import jdk.graal.compiler.word.Word;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.Pointer;

@Platforms(WebImageWasmLMPlatform.class)
public final class McWebLMClock {

    /** Must match MCWEB_CLOCK_*_OFFSET in web/thread-host.js. */
    private static final int VERSION_OFFSET = 4000;
    private static final int LO_OFFSET = 4004;
    private static final int HI_OFFSET = 4008;

    /** Retries before giving up on a stable read and falling back to the JS clock. */
    private static final int SEQLOCK_RETRIES = 8;

    private McWebLMClock() {
    }

    /**
     * Replacement body for {@code Target_java_lang_System_Web.nanoTime}.
     *
     * <p>The branch lives here rather than in the injected bytecode on purpose: the
     * substitution class carries stack map frames, and this builder patcher must use
     * {@code COMPUTE_MAXS} only (COMPUTE_FRAMES needs a type hierarchy it does not
     * have), so an injected jump produces
     * {@code VerifyError: Expecting a stackmap frame at branch target}. The patched
     * method therefore becomes a single INVOKESTATIC + LRETURN with no control flow.
     */
    @Uninterruptible(reason = "Replaces System.nanoTime, which uninterruptible code calls"
                    + " (TimeUtils.millisSinceNanos, WasmLMGC.mcwebCollectAtSafepoint)",
                    calleeMustBe = false)
    public static long nanoTime() {
        long shared = sharedNanos();
        if (shared != 0L) {
            return shared;
        }
        return (long) (JSFunctionIntrinsics.performanceNow() * 1000000.0d);
    }

    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    private static Pointer control(int offset) {
        return Word.pointer(offset);
    }

    /**
     * Shared-memory nanosecond clock, or 0 when no publisher is running.
     *
     * <p>Zero is the untouched initial state of the control block, so an image without
     * the publisher (a probe, a Node harness, the unshared lane) transparently keeps the
     * original JS-crossing clock.
     */
    @Uninterruptible(reason = "Called from uninterruptible code.", mayBeInlined = true)
    public static long sharedNanos() {
        for (int attempt = 0; attempt < SEQLOCK_RETRIES; attempt++) {
            int before = control(VERSION_OFFSET).readInt(0);
            if ((before & 1) != 0) {
                continue;
            }
            long lo = control(LO_OFFSET).readInt(0) & 0xffff_ffffL;
            long hi = control(HI_OFFSET).readInt(0) & 0xffff_ffffL;
            if (control(VERSION_OFFSET).readInt(0) == before) {
                return (hi << 32) | lo;
            }
        }
        return 0L;
    }
}
