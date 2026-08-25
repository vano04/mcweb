package dev.mcweb.graal;

import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;

/**
 * Managed-buffer equivalents of the address-based ByteBuffer copies.
 *
 * <p><b>Why these exist at all on the fast path.</b> {@code ByteBuffer.put(
 * ByteBuffer)} is a bulk copy on a real JVM. Here it is not: the JDK routes it
 * through {@code Unsafe}/{@code ScopedMemoryAccess}, and the WasmGC backend can
 * only implement those as a loop over
 * {@code WasmGCUnalignedUnsafeSupport.readArrayByte/writeArrayByte}, one call
 * per byte. On hoplite.gg that made a single call site —
 * {@code StagedVertexBuffer.uploadDrawsToBuffers}, which runs once a frame from
 * {@code FeatureRenderDispatcher.prepareFrame} — <b>2.94% of total frame
 * time</b>, with the unaligned helpers adding another 4.89% across the port.
 *
 * <p>Both buffers in that path are heap-backed, and for two heap buffers the
 * copy is just {@link System#arraycopy}, which the backend lowers to a real
 * bulk array copy.</p>
 */
public final class BrowserBufferCompat {
    private BrowserBufferCompat() {
    }

    /**
     * Copies {@code source}'s remaining bytes into {@code destination} without
     * moving either position — the LWJGL address-copy replacement.
     */
    public static void copy(ByteBuffer source, ByteBuffer destination) {
        int length = source.remaining();
        if (length == 0) {
            return;
        }
        if (bulkable(source, destination, length)) {
            System.arraycopy(
                    source.array(), source.arrayOffset() + source.position(),
                    destination.array(), destination.arrayOffset() + destination.position(),
                    length
            );
            return;
        }
        destination.duplicate().put(source.duplicate());
    }

    /**
     * {@link ByteBuffer#put(ByteBuffer)} with the bulk path, including its
     * position advance and its exceptions, so a rewritten call site behaves
     * exactly as the original bytecode did.
     */
    public static ByteBuffer put(ByteBuffer destination, ByteBuffer source) {
        if (source == destination) {
            throw new IllegalArgumentException("The source buffer is this buffer");
        }
        if (destination.isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        int length = source.remaining();
        if (length > destination.remaining()) {
            throw new BufferOverflowException();
        }
        if (length == 0) {
            return destination;
        }
        if (bulkable(source, destination, length)) {
            System.arraycopy(
                    source.array(), source.arrayOffset() + source.position(),
                    destination.array(), destination.arrayOffset() + destination.position(),
                    length
            );
            destination.position(destination.position() + length);
            source.position(source.position() + length);
            return destination;
        }
        return destination.put(source);
    }

    /**
     * Both sides heap-backed and writable. {@code hasArray()} is already false
     * for a read-only buffer, so a read-only source falls through to the
     * ordinary path rather than reaching {@code array()}.
     */
    private static boolean bulkable(ByteBuffer source, ByteBuffer destination, int length) {
        return length > 0 && source.hasArray() && destination.hasArray();
    }
}
