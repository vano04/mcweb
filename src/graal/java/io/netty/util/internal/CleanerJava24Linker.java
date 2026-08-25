package io.netty.util.internal;

import java.nio.ByteBuffer;

/**
 * Browser replacement for Netty's JDK-24 foreign-linker based cleaner. The
 * original builds static MethodHandles to MemorySegment allocation/free
 * through jdk.internal.foreign; SVM's folding of those handles emits
 * invalid WAT (mistyped global.set) in the wasm backend. Reporting the
 * linker as unsupported makes Netty select its plain cleaner paths.
 */
public class CleanerJava24Linker implements Cleaner {
    public CleanerJava24Linker() {
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
