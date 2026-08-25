package io.netty.util.internal;

import java.nio.ByteBuffer;

/**
 * Browser replacement for Netty's JDK-25 Arena-based cleaner; see
 * CleanerJava24Linker for why the foreign-API MethodHandles are removed.
 * Netty's image config build-time-initializes this class, so the shadow
 * must be static-free enough to fold harmlessly.
 */
final class CleanerJava25 implements Cleaner {
    CleanerJava25() {
    }

    static boolean isSupported() {
        return false;
    }

    public CleanableDirectBuffer allocate(int capacity) {
        throw new UnsupportedOperationException(
                "Foreign-memory direct buffers are unavailable in the browser");
    }

    @Override
    public void freeDirectBuffer(ByteBuffer buffer) {
        // Browser direct buffers have no native backing to release.
    }

    @Override
    public boolean hasExpensiveClean() {
        return true;
    }
}
