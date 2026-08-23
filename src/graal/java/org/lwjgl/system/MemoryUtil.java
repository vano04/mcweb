package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.CLongBuffer;
import org.lwjgl.PointerBuffer;

/**
 * Browser MemoryUtil: managed heap allocations with synthetic addresses.
 * Avoids HotSpot-only Unsafe DirectByteBuffer field-offset discovery that fails
 * under GraalVM Web Image.
 */
public final class MemoryUtil {
    public static final long NULL = 0L;
    public static final int PAGE_SIZE = 4096;
    public static final int CACHE_LINE_SIZE = 64;

    private MemoryUtil() {
    }

    public interface MemoryAllocator {
        long getMalloc();

        long getCalloc();

        long getRealloc();

        long getFree();

        long getAlignedAlloc();

        long getAlignedFree();

        long malloc(long size);

        long calloc(long num, long size);

        long realloc(long ptr, long size);

        void free(long ptr);

        long aligned_alloc(long alignment, long size);

        void aligned_free(long ptr);
    }

    public interface MemoryAllocationReport {
        void invoke(long address, long memory, long threadId, String threadName, StackTraceElement... stacktrace);

        enum Aggregate {
            ALL,
            GROUP_BY_METHOD,
            GROUP_BY_STACKTRACE
        }
    }

    public static MemoryAllocator getAllocator() {
        return getAllocator(false);
    }

    public static MemoryAllocator getAllocator(boolean tracked) {
        return MemoryManage.getInstance();
    }

    public static long nmemAlloc(long size) {
        return BrowserNativeMemory.malloc(size);
    }

    public static long nmemAllocChecked(long size) {
        long address = nmemAlloc(size == 0L ? 1L : size);
        if (address == 0L) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    public static ByteBuffer memAlloc(int size) {
        return memByteBuffer(nmemAllocChecked(size), size);
    }

    public static ShortBuffer memAllocShort(int size) {
        return memShortBuffer(nmemAllocChecked(size * 2L), size);
    }

    public static IntBuffer memAllocInt(int size) {
        return memIntBuffer(nmemAllocChecked(size * 4L), size);
    }

    public static FloatBuffer memAllocFloat(int size) {
        return memFloatBuffer(nmemAllocChecked(size * 4L), size);
    }

    public static LongBuffer memAllocLong(int size) {
        return memLongBuffer(nmemAllocChecked(size * 8L), size);
    }

    public static CLongBuffer memAllocCLong(int size) {
        return memCLongBuffer(nmemAllocChecked(size * 8L), size);
    }

    public static DoubleBuffer memAllocDouble(int size) {
        return memDoubleBuffer(nmemAllocChecked(size * 8L), size);
    }

    public static PointerBuffer memAllocPointer(int size) {
        return memPointerBuffer(nmemAllocChecked(size * 8L), size);
    }

    public static void nmemFree(long ptr) {
        BrowserNativeMemory.free(ptr);
    }

    public static void memFree(Buffer buffer) {
        if (buffer != null) {
            nmemFree(memAddress0(buffer));
        }
    }

    public static void memFree(ByteBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(ShortBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(CharBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(IntBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(LongBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(FloatBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(DoubleBuffer buffer) {
        memFree((Buffer) buffer);
    }

    public static void memFree(CustomBuffer<?> buffer) {
        if (buffer != null) {
            nmemFree(buffer.address());
        }
    }

    public static long nmemCalloc(long num, long size) {
        return BrowserNativeMemory.calloc(num, size);
    }

    public static long nmemCallocChecked(long num, long size) {
        long address = nmemCalloc(num, size);
        if (address == 0L) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    public static ByteBuffer memCalloc(int num, int size) {
        return memByteBuffer(nmemCallocChecked(num, size), num * size);
    }

    public static ByteBuffer memCalloc(int size) {
        return memCalloc(size, 1);
    }

    public static ShortBuffer memCallocShort(int size) {
        return memShortBuffer(nmemCallocChecked(size, 2), size);
    }

    public static IntBuffer memCallocInt(int size) {
        return memIntBuffer(nmemCallocChecked(size, 4), size);
    }

    public static FloatBuffer memCallocFloat(int size) {
        return memFloatBuffer(nmemCallocChecked(size, 4), size);
    }

    public static LongBuffer memCallocLong(int size) {
        return memLongBuffer(nmemCallocChecked(size, 8), size);
    }

    public static CLongBuffer memCallocCLong(int size) {
        return memCLongBuffer(nmemCallocChecked(size, 8), size);
    }

    public static DoubleBuffer memCallocDouble(int size) {
        return memDoubleBuffer(nmemCallocChecked(size, 8), size);
    }

    public static PointerBuffer memCallocPointer(int size) {
        return memPointerBuffer(nmemCallocChecked(size, 8), size);
    }

    public static long nmemRealloc(long ptr, long size) {
        return BrowserNativeMemory.realloc(ptr, size);
    }

    public static long nmemReallocChecked(long ptr, long size) {
        long address = nmemRealloc(ptr, size);
        if (address == 0L) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    public static ByteBuffer memRealloc(ByteBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        ByteBuffer target = memByteBuffer(nmemReallocChecked(address, size), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size));
        }
        return target;
    }

    public static ShortBuffer memRealloc(ShortBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        ShortBuffer target = memShortBuffer(nmemReallocChecked(address, size * 2L), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size * 2));
        }
        return target;
    }

    public static IntBuffer memRealloc(IntBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        IntBuffer target = memIntBuffer(nmemReallocChecked(address, size * 4L), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size * 4));
        }
        return target;
    }

    public static LongBuffer memRealloc(LongBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        LongBuffer target = memLongBuffer(nmemReallocChecked(address, size * 8L), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size * 8));
        }
        return target;
    }

    public static CLongBuffer memRealloc(CLongBuffer buffer, int size) {
        long address = buffer == null ? 0L : buffer.address();
        CLongBuffer target = memCLongBuffer(nmemReallocChecked(address, size * 8L), size);
        if (buffer != null) {
            target.position((int) Math.min(buffer.position(), (long) size));
        }
        return target;
    }

    public static FloatBuffer memRealloc(FloatBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        FloatBuffer target = memFloatBuffer(nmemReallocChecked(address, size * 4L), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size * 4));
        }
        return target;
    }

    public static DoubleBuffer memRealloc(DoubleBuffer buffer, int size) {
        long address = buffer == null ? 0L : memAddress0(buffer);
        DoubleBuffer target = memDoubleBuffer(nmemReallocChecked(address, size * 8L), size);
        if (buffer != null) {
            target.position(Math.min(buffer.position(), size * 8));
        }
        return target;
    }

    public static PointerBuffer memRealloc(PointerBuffer buffer, int size) {
        long address = buffer == null ? 0L : buffer.address();
        PointerBuffer target = memPointerBuffer(nmemReallocChecked(address, size * 8L), size);
        if (buffer != null) {
            target.position((int) Math.min(buffer.position(), (long) size));
        }
        return target;
    }

    public static long nmemAlignedAlloc(long alignment, long size) {
        return BrowserNativeMemory.malloc(size);
    }

    public static long nmemAlignedAllocChecked(long alignment, long size) {
        long address = nmemAlignedAlloc(alignment, size);
        if (address == 0L) {
            throw new OutOfMemoryError();
        }
        return address;
    }

    public static ByteBuffer memAlignedAlloc(int alignment, int size) {
        return memByteBuffer(nmemAlignedAllocChecked(alignment, size), size);
    }

    public static void nmemAlignedFree(long ptr) {
        nmemFree(ptr);
    }

    public static void memAlignedFree(ByteBuffer buffer) {
        memFree(buffer);
    }

    public static void memReport(MemoryAllocationReport report) {
    }

    public static void memReport(
            MemoryAllocationReport report,
            MemoryAllocationReport.Aggregate groupBy,
            boolean exact
    ) {
    }

    public static long memAddress0(Buffer buffer) {
        return BrowserNativeMemory.addressOf(buffer);
    }

    public static long memAddress0(ByteBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(ShortBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(CharBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(IntBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(LongBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(FloatBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    public static long memAddress0(DoubleBuffer buffer) {
        return memAddress0((Buffer) buffer);
    }

    private static long shift(Buffer buffer) {
        if (buffer instanceof ByteBuffer) {
            return 0L;
        }
        if (buffer instanceof ShortBuffer || buffer instanceof CharBuffer) {
            return (long) buffer.position() * 2L;
        }
        if (buffer instanceof IntBuffer || buffer instanceof FloatBuffer) {
            return (long) buffer.position() * 4L;
        }
        if (buffer instanceof LongBuffer || buffer instanceof DoubleBuffer) {
            return (long) buffer.position() * 8L;
        }
        return buffer.position();
    }


    /** Mirrors LWJGL's private realloc position transfer. */
    private static <T extends Buffer> T transferPosition(Buffer old, T target, int size) {
        if (old != null) {
            target.position(Math.min(old.position(), size));
        }
        return target;
    }

    public static long memAddress(ByteBuffer buffer) {
        return memAddress0(buffer) + buffer.position();
    }

    public static long memAddress(ByteBuffer buffer, int position) {
        return memAddress0(buffer) + position;
    }

    public static long memAddress(ShortBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 2L;
    }

    public static long memAddress(ShortBuffer buffer, int position) {
        return memAddress0(buffer) + position * 2L;
    }

    public static long memAddress(CharBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 2L;
    }

    public static long memAddress(CharBuffer buffer, int position) {
        return memAddress0(buffer) + position * 2L;
    }

    public static long memAddress(IntBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 4L;
    }

    public static long memAddress(IntBuffer buffer, int position) {
        return memAddress0(buffer) + position * 4L;
    }

    public static long memAddress(FloatBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 4L;
    }

    public static long memAddress(FloatBuffer buffer, int position) {
        return memAddress0(buffer) + position * 4L;
    }

    public static long memAddress(LongBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 8L;
    }

    public static long memAddress(LongBuffer buffer, int position) {
        return memAddress0(buffer) + position * 8L;
    }

    public static long memAddress(DoubleBuffer buffer) {
        return memAddress0(buffer) + (long) buffer.position() * 8L;
    }

    public static long memAddress(DoubleBuffer buffer, int position) {
        return memAddress0(buffer) + position * 8L;
    }

    public static long memAddress(Buffer buffer) {
        return memAddress0(buffer) + shift(buffer);
    }

    public static long memAddress(CustomBuffer<?> buffer) {
        return buffer == null ? 0L : buffer.address();
    }

    public static long memAddress(CustomBuffer<?> buffer, int position) {
        return buffer == null ? 0L : buffer.address(position);
    }

    public static long memAddressSafe(ByteBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(ShortBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(CharBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(IntBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(FloatBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(LongBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(DoubleBuffer buffer) {
        return buffer == null ? 0L : memAddress(buffer);
    }

    public static long memAddressSafe(Pointer pointer) {
        return pointer == null ? 0L : pointer.address();
    }

    public static ByteBuffer memByteBuffer(long address, int capacity) {
        return BrowserNativeMemory.wrapByte(address, capacity);
    }

    /** Package-private entry used by MemoryStack and PointerBuffer helpers. */
    static ByteBuffer wrapBufferByte(long address, int capacity) {
        return memByteBuffer(address, capacity);
    }

    static ShortBuffer wrapBufferShort(long address, int capacity) {
        return memShortBuffer(address, capacity);
    }

    static CharBuffer wrapBufferChar(long address, int capacity) {
        return memCharBuffer(address, capacity);
    }

    static IntBuffer wrapBufferInt(long address, int capacity) {
        return memIntBuffer(address, capacity);
    }

    static LongBuffer wrapBufferLong(long address, int capacity) {
        return memLongBuffer(address, capacity);
    }

    static FloatBuffer wrapBufferFloat(long address, int capacity) {
        return memFloatBuffer(address, capacity);
    }

    static DoubleBuffer wrapBufferDouble(long address, int capacity) {
        return memDoubleBuffer(address, capacity);
    }

    static int encodeASCIIUnsafe(CharSequence text, boolean nullTerminated, long target) {
        int length = text.length();
        for (int i = 0; i < length; i++) {
            memPutByte(target + i, (byte) text.charAt(i));
        }
        if (nullTerminated) {
            memPutByte(target + length, (byte) 0);
            return length + 1;
        }
        return length;
    }

    static int encodeUTF8Unsafe(CharSequence text, boolean nullTerminated, long target) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            memPutByte(target + i, bytes[i]);
        }
        if (nullTerminated) {
            memPutByte(target + bytes.length, (byte) 0);
            return bytes.length + 1;
        }
        return bytes.length;
    }

    static int encodeUTF16Unsafe(CharSequence text, boolean nullTerminated, long target) {
        int length = text.length();
        for (int i = 0; i < length; i++) {
            memPutShort(target + i * 2L, (short) text.charAt(i));
        }
        if (nullTerminated) {
            memPutShort(target + length * 2L, (short) 0);
            return (length + 1) * 2;
        }
        return length * 2;
    }

    public static ByteBuffer memByteBufferSafe(long address, int capacity) {
        return address == 0L ? null : memByteBuffer(address, capacity);
    }

    public static ByteBuffer memByteBuffer(ShortBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 2);
    }

    public static ByteBuffer memByteBuffer(CharBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 2);
    }

    public static ByteBuffer memByteBuffer(IntBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 4);
    }

    public static ByteBuffer memByteBuffer(LongBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 8);
    }

    public static ByteBuffer memByteBuffer(FloatBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 4);
    }

    public static ByteBuffer memByteBuffer(DoubleBuffer buffer) {
        return memByteBuffer(memAddress0(buffer), buffer.remaining() * 8);
    }

    public static ByteBuffer memByteBuffer(CustomBuffer<?> buffer) {
        return memByteBuffer(buffer.address(), buffer.remaining() * buffer.sizeof());
    }

    public static <T extends Struct<T>> ByteBuffer memByteBuffer(T value) {
        return memByteBuffer(value.address(), value.sizeof());
    }

    public static ShortBuffer memShortBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 2).asShortBuffer();
    }

    public static ShortBuffer memShortBufferSafe(long address, int capacity) {
        return address == 0L ? null : memShortBuffer(address, capacity);
    }

    public static CharBuffer memCharBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 2).asCharBuffer();
    }

    public static CharBuffer memCharBufferSafe(long address, int capacity) {
        return address == 0L ? null : memCharBuffer(address, capacity);
    }

    public static IntBuffer memIntBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 4).asIntBuffer();
    }

    public static IntBuffer memIntBufferSafe(long address, int capacity) {
        return address == 0L ? null : memIntBuffer(address, capacity);
    }

    public static LongBuffer memLongBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 8).asLongBuffer();
    }

    public static LongBuffer memLongBufferSafe(long address, int capacity) {
        return address == 0L ? null : memLongBuffer(address, capacity);
    }

    public static CLongBuffer memCLongBuffer(long address, int capacity) {
        return CLongBuffer.create(address, capacity);
    }

    public static CLongBuffer memCLongBufferSafe(long address, int capacity) {
        return address == 0L ? null : memCLongBuffer(address, capacity);
    }

    public static FloatBuffer memFloatBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 4).asFloatBuffer();
    }

    public static FloatBuffer memFloatBufferSafe(long address, int capacity) {
        return address == 0L ? null : memFloatBuffer(address, capacity);
    }

    public static DoubleBuffer memDoubleBuffer(long address, int capacity) {
        return memByteBuffer(address, capacity * 8).asDoubleBuffer();
    }

    public static DoubleBuffer memDoubleBufferSafe(long address, int capacity) {
        return address == 0L ? null : memDoubleBuffer(address, capacity);
    }

    public static PointerBuffer memPointerBuffer(long address, int capacity) {
        return PointerBuffer.create(address, capacity);
    }

    public static PointerBuffer memPointerBufferSafe(long address, int capacity) {
        return address == 0L ? null : memPointerBuffer(address, capacity);
    }

    public static ByteBuffer memDuplicate(ByteBuffer buffer) {
        ByteBuffer duplicate = buffer.duplicate().order(buffer.order());
        BrowserNativeMemory.register(duplicate, memAddress0(buffer));
        return duplicate;
    }

    public static ShortBuffer memDuplicate(ShortBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asShortBuffer();
    }

    public static CharBuffer memDuplicate(CharBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asCharBuffer();
    }

    public static IntBuffer memDuplicate(IntBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asIntBuffer();
    }

    public static LongBuffer memDuplicate(LongBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asLongBuffer();
    }

    public static FloatBuffer memDuplicate(FloatBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asFloatBuffer();
    }

    public static DoubleBuffer memDuplicate(DoubleBuffer buffer) {
        return memDuplicate(memByteBuffer(buffer)).asDoubleBuffer();
    }

    public static ByteBuffer memSlice(ByteBuffer buffer) {
        ByteBuffer slice = buffer.slice().order(buffer.order());
        BrowserNativeMemory.register(slice, memAddress(buffer));
        return slice;
    }

    public static ShortBuffer memSlice(ShortBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asShortBuffer();
    }

    public static CharBuffer memSlice(CharBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asCharBuffer();
    }

    public static IntBuffer memSlice(IntBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asIntBuffer();
    }

    public static LongBuffer memSlice(LongBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asLongBuffer();
    }

    public static FloatBuffer memSlice(FloatBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asFloatBuffer();
    }

    public static DoubleBuffer memSlice(DoubleBuffer buffer) {
        return memSlice(memByteBuffer(buffer)).asDoubleBuffer();
    }

    public static ByteBuffer memSlice(ByteBuffer buffer, int offset, int capacity) {
        ByteBuffer slice = memSlice(buffer);
        slice.position(offset).limit(offset + capacity);
        return slice.slice().order(buffer.order());
    }

    public static ShortBuffer memSlice(ShortBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 2, capacity * 2).asShortBuffer();
    }

    public static CharBuffer memSlice(CharBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 2, capacity * 2).asCharBuffer();
    }

    public static IntBuffer memSlice(IntBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 4, capacity * 4).asIntBuffer();
    }

    public static LongBuffer memSlice(LongBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 8, capacity * 8).asLongBuffer();
    }

    public static FloatBuffer memSlice(FloatBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 4, capacity * 4).asFloatBuffer();
    }

    public static DoubleBuffer memSlice(DoubleBuffer buffer, int offset, int capacity) {
        return memSlice(memByteBuffer(buffer), offset * 8, capacity * 8).asDoubleBuffer();
    }

    public static <T extends CustomBuffer<T>> T memSlice(T buffer, int offset, int capacity) {
        return buffer.slice(offset, capacity);
    }

    public static void memSet(ByteBuffer buffer, int value) {
        for (int i = buffer.position(); i < buffer.limit(); i++) {
            buffer.put(i, (byte) value);
        }
    }

    public static void memSet(ShortBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static void memSet(CharBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static void memSet(IntBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static void memSet(LongBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static void memSet(FloatBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static void memSet(DoubleBuffer buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static <T extends CustomBuffer<T>> void memSet(T buffer, int value) {
        memSet(memByteBuffer(buffer), value);
    }

    public static <T extends Struct<T>> void memSet(T value, int fill) {
        memSet(value.address(), fill, value.sizeof());
    }

    public static void memCopy(ByteBuffer src, ByteBuffer dst) {
        int length = Math.min(src.remaining(), dst.remaining());
        int srcPos = src.position();
        int dstPos = dst.position();
        for (int i = 0; i < length; i++) {
            dst.put(dstPos + i, src.get(srcPos + i));
        }
    }

    public static void memCopy(ShortBuffer src, ShortBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static void memCopy(CharBuffer src, CharBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static void memCopy(IntBuffer src, IntBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static void memCopy(LongBuffer src, LongBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static void memCopy(FloatBuffer src, FloatBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static void memCopy(DoubleBuffer src, DoubleBuffer dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static <T extends CustomBuffer<T>> void memCopy(T src, T dst) {
        memCopy(memByteBuffer(src), memByteBuffer(dst));
    }

    public static <T extends Struct<T>> void memCopy(T src, T dst) {
        memCopy(src.address(), dst.address(), src.sizeof());
    }

    public static void memCopy(byte[] src, ByteBuffer dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(short[] src, ByteBuffer dst) {
        for (short value : src) {
            dst.putShort(value);
        }
    }

    public static void memCopy(short[] src, ShortBuffer dst) {
        dst.put(src);
    }

    public static void memCopy(int[] src, ByteBuffer dst) {
        for (int value : src) {
            dst.putInt(value);
        }
    }

    public static void memCopy(int[] src, IntBuffer dst) {
        dst.put(src);
    }

    public static void memCopy(long[] src, ByteBuffer dst) {
        for (long value : src) {
            dst.putLong(value);
        }
    }

    public static void memCopy(long[] src, LongBuffer dst) {
        dst.put(src);
    }

    public static void memCopy(float[] src, ByteBuffer dst) {
        for (float value : src) {
            dst.putFloat(value);
        }
    }

    public static void memCopy(float[] src, FloatBuffer dst) {
        dst.put(src);
    }

    public static void memCopy(double[] src, ByteBuffer dst) {
        for (double value : src) {
            dst.putDouble(value);
        }
    }

    public static void memCopy(double[] src, DoubleBuffer dst) {
        dst.put(src);
    }

    public static void memCopy(byte[] src, ByteBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(short[] src, ByteBuffer dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst.putShort(src[offset + i]);
        }
    }

    public static void memCopy(short[] src, ShortBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(int[] src, ByteBuffer dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst.putInt(src[offset + i]);
        }
    }

    public static void memCopy(int[] src, IntBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(long[] src, ByteBuffer dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst.putLong(src[offset + i]);
        }
    }

    public static void memCopy(long[] src, LongBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(float[] src, ByteBuffer dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst.putFloat(src[offset + i]);
        }
    }

    public static void memCopy(float[] src, FloatBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(double[] src, ByteBuffer dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst.putDouble(src[offset + i]);
        }
    }

    public static void memCopy(double[] src, DoubleBuffer dst, int offset, int length) {
        dst.put(src, offset, length);
    }

    public static void memCopy(ByteBuffer src, byte[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(ByteBuffer src, short[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = src.getShort();
        }
    }

    public static void memCopy(ShortBuffer src, short[] dst) {
        src.get(dst);
    }

    public static void memCopy(ByteBuffer src, int[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = src.getInt();
        }
    }

    public static void memCopy(IntBuffer src, int[] dst) {
        src.get(dst);
    }

    public static void memCopy(ByteBuffer src, long[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = src.getLong();
        }
    }

    public static void memCopy(LongBuffer src, long[] dst) {
        src.get(dst);
    }

    public static void memCopy(ByteBuffer src, float[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = src.getFloat();
        }
    }

    public static void memCopy(FloatBuffer src, float[] dst) {
        src.get(dst);
    }

    public static void memCopy(ByteBuffer src, double[] dst) {
        for (int i = 0; i < dst.length; i++) {
            dst[i] = src.getDouble();
        }
    }

    public static void memCopy(DoubleBuffer src, double[] dst) {
        src.get(dst);
    }

    public static void memCopy(ByteBuffer src, byte[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memCopy(ByteBuffer src, short[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = src.getShort();
        }
    }

    public static void memCopy(ShortBuffer src, short[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memCopy(ByteBuffer src, int[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = src.getInt();
        }
    }

    public static void memCopy(IntBuffer src, int[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memCopy(ByteBuffer src, long[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = src.getLong();
        }
    }

    public static void memCopy(LongBuffer src, long[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memCopy(ByteBuffer src, float[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = src.getFloat();
        }
    }

    public static void memCopy(FloatBuffer src, float[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memCopy(ByteBuffer src, double[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = src.getDouble();
        }
    }

    public static void memCopy(DoubleBuffer src, double[] dst, int offset, int length) {
        src.get(dst, offset, length);
    }

    public static void memSet(long ptr, int value, long bytes) {
        BrowserNativeMemory.set(ptr, value, bytes);
    }

    public static void memCopy(long src, long dst, long bytes) {
        BrowserNativeMemory.copy(src, dst, bytes);
    }

    public static void memCopy(byte[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(short[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(int[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(long[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(float[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(double[] src, long dst) {
        memCopy(src, dst, 0, src.length);
    }

    public static void memCopy(byte[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putByte(dst + i, src[offset + i]);
        }
    }

    public static void memCopy(short[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putShort(dst + i * 2L, src[offset + i]);
        }
    }

    public static void memCopy(int[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putInt(dst + i * 4L, src[offset + i]);
        }
    }

    public static void memCopy(long[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putLong(dst + i * 8L, src[offset + i]);
        }
    }

    public static void memCopy(float[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putFloat(dst + i * 4L, src[offset + i]);
        }
    }

    public static void memCopy(double[] src, long dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            BrowserNativeMemory.putDouble(dst + i * 8L, src[offset + i]);
        }
    }

    public static void memCopy(long src, byte[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, short[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, int[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, long[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, float[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, double[] dst) {
        memCopy(src, dst, 0, dst.length);
    }

    public static void memCopy(long src, byte[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getByte(src + i);
        }
    }

    public static void memCopy(long src, short[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getShort(src + i * 2L);
        }
    }

    public static void memCopy(long src, int[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getInt(src + i * 4L);
        }
    }

    public static void memCopy(long src, long[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getLong(src + i * 8L);
        }
    }

    public static void memCopy(long src, float[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getFloat(src + i * 4L);
        }
    }

    public static void memCopy(long src, double[] dst, int offset, int length) {
        for (int i = 0; i < length; i++) {
            dst[offset + i] = BrowserNativeMemory.getDouble(src + i * 8L);
        }
    }

    public static boolean memGetBoolean(long ptr) {
        return memGetByte(ptr) != 0;
    }

    public static byte memGetByte(long ptr) {
        return BrowserNativeMemory.getByte(ptr);
    }

    public static short memGetShort(long ptr) {
        return BrowserNativeMemory.getShort(ptr);
    }

    public static int memGetInt(long ptr) {
        return BrowserNativeMemory.getInt(ptr);
    }

    public static long memGetLong(long ptr) {
        return BrowserNativeMemory.getLong(ptr);
    }

    public static float memGetFloat(long ptr) {
        return BrowserNativeMemory.getFloat(ptr);
    }

    public static double memGetDouble(long ptr) {
        return BrowserNativeMemory.getDouble(ptr);
    }

    public static long memGetCLong(long ptr) {
        return memGetLong(ptr);
    }

    public static long memGetAddress(long ptr) {
        return BrowserNativeMemory.getAddress(ptr);
    }

    public static void memPutByte(long ptr, byte value) {
        BrowserNativeMemory.putByte(ptr, value);
    }

    public static void memPutShort(long ptr, short value) {
        BrowserNativeMemory.putShort(ptr, value);
    }

    public static void memPutInt(long ptr, int value) {
        BrowserNativeMemory.putInt(ptr, value);
    }

    public static void memPutLong(long ptr, long value) {
        BrowserNativeMemory.putLong(ptr, value);
    }

    public static void memPutFloat(long ptr, float value) {
        BrowserNativeMemory.putFloat(ptr, value);
    }

    public static void memPutDouble(long ptr, double value) {
        BrowserNativeMemory.putDouble(ptr, value);
    }

    public static void memPutCLong(long ptr, long value) {
        memPutLong(ptr, value);
    }

    public static void memPutAddress(long ptr, long value) {
        BrowserNativeMemory.putAddress(ptr, value);
    }

    public static <T> T memGlobalRefToObject(long globalRef) {
        return null;
    }

    public static ByteBuffer memASCII(CharSequence text) {
        return memASCII(text, true);
    }

    public static ByteBuffer memASCIISafe(CharSequence text) {
        return text == null ? null : memASCII(text);
    }

    public static ByteBuffer memASCII(CharSequence text, boolean nullTerminated) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer buffer = memAlloc(bytes.length + (nullTerminated ? 1 : 0));
        buffer.put(bytes);
        if (nullTerminated) {
            buffer.put((byte) 0);
        }
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer memASCIISafe(CharSequence text, boolean nullTerminated) {
        return text == null ? null : memASCII(text, nullTerminated);
    }

    public static int memASCII(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        return memASCII(text, nullTerminated, target, target.position());
    }

    public static int memASCII(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < bytes.length; i++) {
            target.put(offset + i, bytes[i]);
        }
        if (nullTerminated) {
            target.put(offset + bytes.length, (byte) 0);
            return bytes.length + 1;
        }
        return bytes.length;
    }

    public static int memLengthASCII(CharSequence value, boolean nullTerminated) {
        return value.length() + (nullTerminated ? 1 : 0);
    }

    public static ByteBuffer memUTF8(CharSequence text) {
        return memUTF8(text, true);
    }

    public static ByteBuffer memUTF8Safe(CharSequence text) {
        return text == null ? null : memUTF8(text);
    }

    public static ByteBuffer memUTF8(CharSequence text, boolean nullTerminated) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = memAlloc(bytes.length + (nullTerminated ? 1 : 0));
        buffer.put(bytes);
        if (nullTerminated) {
            buffer.put((byte) 0);
        }
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer memUTF8Safe(CharSequence text, boolean nullTerminated) {
        return text == null ? null : memUTF8(text, nullTerminated);
    }

    public static int memUTF8(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        return memUTF8(text, nullTerminated, target, target.position());
    }

    public static int memUTF8(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        byte[] bytes = text.toString().getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            target.put(offset + i, bytes[i]);
        }
        if (nullTerminated) {
            target.put(offset + bytes.length, (byte) 0);
            return bytes.length + 1;
        }
        return bytes.length;
    }

    public static int memLengthUTF8(CharSequence value, boolean nullTerminated) {
        return value.toString().getBytes(StandardCharsets.UTF_8).length + (nullTerminated ? 1 : 0);
    }

    public static ByteBuffer memUTF16(CharSequence text) {
        return memUTF16(text, true);
    }

    public static ByteBuffer memUTF16Safe(CharSequence text) {
        return text == null ? null : memUTF16(text);
    }

    public static ByteBuffer memUTF16(CharSequence text, boolean nullTerminated) {
        byte[] bytes = text.toString().getBytes(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? StandardCharsets.UTF_16LE
                : StandardCharsets.UTF_16BE);
        ByteBuffer buffer = memAlloc(bytes.length + (nullTerminated ? 2 : 0));
        buffer.put(bytes);
        if (nullTerminated) {
            buffer.putShort((short) 0);
        }
        buffer.flip();
        return buffer;
    }

    public static ByteBuffer memUTF16Safe(CharSequence text, boolean nullTerminated) {
        return text == null ? null : memUTF16(text, nullTerminated);
    }

    public static int memUTF16(CharSequence text, boolean nullTerminated, ByteBuffer target) {
        return memUTF16(text, nullTerminated, target, target.position());
    }

    public static int memUTF16(CharSequence text, boolean nullTerminated, ByteBuffer target, int offset) {
        byte[] bytes = text.toString().getBytes(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? StandardCharsets.UTF_16LE
                : StandardCharsets.UTF_16BE);
        for (int i = 0; i < bytes.length; i++) {
            target.put(offset + i, bytes[i]);
        }
        if (nullTerminated) {
            target.put(offset + bytes.length, (byte) 0);
            target.put(offset + bytes.length + 1, (byte) 0);
            return bytes.length + 2;
        }
        return bytes.length;
    }

    public static int memLengthUTF16(CharSequence value, boolean nullTerminated) {
        return value.length() * 2 + (nullTerminated ? 2 : 0);
    }

    public static int memLengthNT1(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.hasRemaining() && buffer.get() != 0) {
            // scan
        }
        int end = buffer.position() - (buffer.hasRemaining() || buffer.get(buffer.position() - 1) == 0 ? 1 : 0);
        buffer.position(start);
        return Math.max(0, end - start);
    }

    public static int memLengthNT2(ByteBuffer buffer) {
        int start = buffer.position();
        while (buffer.remaining() >= 2 && buffer.getShort() != 0) {
            // scan
        }
        buffer.position(start);
        return 0;
    }

    public static ByteBuffer memByteBufferNT1(long address) {
        return memByteBufferNT1(address, Integer.MAX_VALUE);
    }

    public static ByteBuffer memByteBufferNT1(long address, int maxLength) {
        int length = 0;
        while (length < maxLength && memGetByte(address + length) != 0) {
            length++;
        }
        return memByteBuffer(address, length);
    }

    public static ByteBuffer memByteBufferNT1Safe(long address) {
        return address == 0L ? null : memByteBufferNT1(address);
    }

    public static ByteBuffer memByteBufferNT1Safe(long address, int maxLength) {
        return address == 0L ? null : memByteBufferNT1(address, maxLength);
    }

    public static ByteBuffer memByteBufferNT2(long address) {
        return memByteBufferNT2(address, Integer.MAX_VALUE);
    }

    public static ByteBuffer memByteBufferNT2(long address, int maxLength) {
        int length = 0;
        while (length + 1 < maxLength && memGetShort(address + length) != 0) {
            length += 2;
        }
        return memByteBuffer(address, length);
    }

    public static ByteBuffer memByteBufferNT2Safe(long address) {
        return address == 0L ? null : memByteBufferNT2(address);
    }

    public static ByteBuffer memByteBufferNT2Safe(long address, int maxLength) {
        return address == 0L ? null : memByteBufferNT2(address, maxLength);
    }

    public static String memASCII(long address) {
        return memASCII(memByteBufferNT1(address));
    }

    public static String memASCII(long address, int length) {
        return memASCII(memByteBuffer(address, length));
    }

    public static String memASCII(ByteBuffer buffer) {
        return StandardCharsets.US_ASCII.decode(buffer.duplicate()).toString();
    }

    public static String memASCIISafe(long address) {
        return address == 0L ? null : memASCII(address);
    }

    public static String memASCIISafe(long address, int length) {
        return address == 0L ? null : memASCII(address, length);
    }

    public static String memASCIISafe(ByteBuffer buffer) {
        return buffer == null ? null : memASCII(buffer);
    }

    public static String memASCII(ByteBuffer buffer, int length) {
        return memASCII(buffer, buffer.position(), length);
    }

    public static String memASCII(ByteBuffer buffer, int offset, int length) {
        ByteBuffer view = buffer.duplicate();
        view.position(offset).limit(offset + length);
        return memASCII(view);
    }

    public static String memUTF8(long address) {
        return memUTF8(memByteBufferNT1(address));
    }

    public static String memUTF8(long address, int length) {
        return memUTF8(memByteBuffer(address, length));
    }

    public static String memUTF8(ByteBuffer buffer) {
        return StandardCharsets.UTF_8.decode(buffer.duplicate()).toString();
    }

    public static String memUTF8Safe(long address) {
        return address == 0L ? null : memUTF8(address);
    }

    public static String memUTF8Safe(long address, int length) {
        return address == 0L ? null : memUTF8(address, length);
    }

    public static String memUTF8Safe(ByteBuffer buffer) {
        return buffer == null ? null : memUTF8(buffer);
    }

    public static String memUTF8(ByteBuffer buffer, int length) {
        return memUTF8(buffer, buffer.position(), length);
    }

    public static String memUTF8(ByteBuffer buffer, int offset, int length) {
        ByteBuffer view = buffer.duplicate();
        view.position(offset).limit(offset + length);
        return memUTF8(view);
    }

    public static String memUTF16(long address) {
        return memUTF16(memByteBufferNT2(address));
    }

    public static String memUTF16(long address, int length) {
        return memUTF16(memByteBuffer(address, length * 2));
    }

    public static String memUTF16(ByteBuffer buffer) {
        return (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN
                ? StandardCharsets.UTF_16LE
                : StandardCharsets.UTF_16BE)
                .decode(buffer.duplicate())
                .toString();
    }

    public static String memUTF16Safe(long address) {
        return address == 0L ? null : memUTF16(address);
    }

    public static String memUTF16Safe(long address, int length) {
        return address == 0L ? null : memUTF16(address, length);
    }

    public static String memUTF16Safe(ByteBuffer buffer) {
        return buffer == null ? null : memUTF16(buffer);
    }

    public static String memUTF16(ByteBuffer buffer, int length) {
        return memUTF16(buffer, buffer.position(), length);
    }

    public static String memUTF16(ByteBuffer buffer, int offset, int length) {
        ByteBuffer view = buffer.duplicate();
        view.position(offset).limit(offset + length * 2);
        return memUTF16(view);
    }
}
