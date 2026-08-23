package io.netty.util.internal;

import java.nio.ByteBuffer;

/**
 * Browser replacement for Netty's JDK-cleaner probe hub. Desktop Netty
 * resolves the DirectByteBuffer cleaner through sun.misc.Cleaner / JDK
 * internals; in Web Image there is no native direct memory to release
 * (browser buffers are heap-backed), so freeing is a no-op. Replacing the
 * class also removes the probing static initializer from the image.
 */
final class Cleaner0 {
    private Cleaner0() {
    }

    static void freeDirectBuffer(ByteBuffer buffer) {
        // Browser direct buffers have no native backing to release.
    }
}
