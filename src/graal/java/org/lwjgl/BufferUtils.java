package org.lwjgl;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.system.CustomBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser BufferUtils: allocate through managed {@link MemoryUtil} so
 * {@code memAddress} works for MemoryStack and other LWJGL helpers.
 */
public final class BufferUtils {
    private BufferUtils() {
    }

    public static ByteBuffer createByteBuffer(int capacity) {
        return MemoryUtil.memAlloc(Math.max(capacity, 0)).order(ByteOrder.nativeOrder());
    }

    public static ShortBuffer createShortBuffer(int capacity) {
        return MemoryUtil.memAllocShort(Math.max(capacity, 0));
    }

    public static CharBuffer createCharBuffer(int capacity) {
        int count = Math.max(capacity, 0);
        return MemoryUtil.memCharBuffer(MemoryUtil.nmemAllocChecked(count * 2L), count);
    }

    public static IntBuffer createIntBuffer(int capacity) {
        return MemoryUtil.memAllocInt(Math.max(capacity, 0));
    }

    public static LongBuffer createLongBuffer(int capacity) {
        return MemoryUtil.memAllocLong(Math.max(capacity, 0));
    }

    public static CLongBuffer createCLongBuffer(int capacity) {
        return MemoryUtil.memAllocCLong(Math.max(capacity, 0));
    }

    public static FloatBuffer createFloatBuffer(int capacity) {
        return MemoryUtil.memAllocFloat(Math.max(capacity, 0));
    }

    public static DoubleBuffer createDoubleBuffer(int capacity) {
        return MemoryUtil.memAllocDouble(Math.max(capacity, 0));
    }

    public static PointerBuffer createPointerBuffer(int capacity) {
        return MemoryUtil.memAllocPointer(Math.max(capacity, 0));
    }

    /** Used by PointerBuffer/CustomBuffer helpers when sizing allocations. */
    static int getAllocationSize(int elements, int elementShift) {
        return elements << elementShift;
    }

    public static void zeroBuffer(ByteBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(ShortBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(CharBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(IntBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(FloatBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(LongBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static void zeroBuffer(DoubleBuffer buffer) {
        MemoryUtil.memSet(buffer, 0);
    }

    public static <T extends CustomBuffer<T>> void zeroBuffer(T buffer) {
        MemoryUtil.memSet(MemoryUtil.memByteBuffer(buffer), 0);
    }
}
