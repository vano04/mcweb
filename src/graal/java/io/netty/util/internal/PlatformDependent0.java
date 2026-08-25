package io.netty.util.internal;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import org.lwjgl.system.BrowserNativeMemory;

/**
 * Browser replacement for Netty's low-level platform accessor. The original
 * probes sun.misc.Unsafe, JDK cleaner internals, and generic
 * Lookup.findVarHandle MethodHandle folding that makes SVM register
 * double/float CAS accessors the wasm backend cannot compile. Netty is
 * designed to degrade: hasUnsafe() is false, feature probes report
 * unavailable, and object-relative accessors throw (they are unreachable
 * when Netty selects its pure-Java paths). Address-based accessors route to
 * the browser's synthetic native memory; array-index accessors are plain
 * array operations.
 */
final class PlatformDependent0 {
    // Netty ships SVM @TargetClass substitutions (io.netty.util.internal.svm)
    // that @Alias this field; the shadow must declare it or the build fails.
    private static final long ADDRESS_FIELD_OFFSET = -1L;

    private static final int HASH_CODE_C1 = 0x1f1f1f1f;
    private static final int HASH_CODE_C2 = 0x1f1f1f1e;

    private PlatformDependent0() {
    }

    // ---- capability probes ------------------------------------------------

    static boolean hasUnsafe() {
        return false;
    }

    static boolean isExplicitNoUnsafe() {
        return true;
    }

    private static Throwable explicitNoUnsafeCause0() {
        return null;
    }

    static Throwable getUnsafeUnavailabilityCause() {
        return new UnsupportedOperationException("sun.misc.Unsafe is unavailable in the browser");
    }

    static boolean isNativeImage() {
        return true;
    }

    static boolean isAndroid() {
        return false;
    }

    private static boolean isAndroid0() {
        return false;
    }

    static boolean isUnaligned() {
        return true;
    }

    static boolean unalignedAccess() {
        return true;
    }

    static boolean isExplicitTryReflectionSetAccessible() {
        return false;
    }

    private static boolean explicitTryReflectionSetAccessible0() {
        return false;
    }

    static boolean hasMemorySegmentAddressOfBuffer() {
        return false;
    }

    static boolean hasDirectBufferNoCleanerConstructor() {
        return false;
    }

    static boolean hasDirectByteBufferAddress(ByteBuffer buffer) {
        return false;
    }

    static boolean hasAlignSliceMethod() {
        return false;
    }

    static boolean hasOffsetSliceMethod() {
        return false;
    }

    static boolean hasAbsolutePutBufferMethod() {
        return false;
    }

    static boolean hasAbsolutePutArrayMethod() {
        return false;
    }

    static boolean hasAllocateArrayMethod() {
        return false;
    }

    static long bitsMaxDirectMemory() {
        return -1L;
    }

    static int javaVersion() {
        return javaVersion0();
    }

    private static int javaVersion0() {
        return majorVersionFromJavaSpecificationVersion();
    }

    static int majorVersionFromJavaSpecificationVersion() {
        return majorVersion(System.getProperty("java.specification.version", "25"));
    }

    static int majorVersion(String javaSpecVersion) {
        final String[] components = javaSpecVersion.split("\\.");
        final int[] version = new int[components.length];
        for (int i = 0; i < components.length; i++) {
            version[i] = Integer.parseInt(components[i]);
        }
        if (version[0] == 1) {
            assert version[1] >= 6;
            return version[1];
        }
        return version[0];
    }

    static boolean isVirtualThread(Thread thread) {
        // Virtual threads are not used by the single-threaded browser runtime.
        return false;
    }

    private static java.lang.invoke.MethodHandle getIsVirtualThreadMethodHandle() {
        return null;
    }

    // ---- exceptions --------------------------------------------------------

    static void throwException(Throwable cause) {
        PlatformDependent0.<RuntimeException>throwException0(cause);
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void throwException0(Throwable cause) throws E {
        throw (E) cause;
    }

    private static void rethrowIfPossible(Throwable cause) throws Exception {
        if (cause instanceof Exception) {
            throw (Exception) cause;
        }
    }

    // ---- direct buffers (unavailable) --------------------------------------

    static void freeDirectBuffer(ByteBuffer buffer) {
        // Browser direct buffers have no native backing to release.
    }

    static long directBufferAddress(ByteBuffer buffer) {
        throw new UnsupportedOperationException("Direct buffer addresses are unavailable in the browser");
    }

    static ByteBuffer allocateDirectNoCleaner(int capacity) {
        return ByteBuffer.allocateDirect(capacity);
    }

    static ByteBuffer reallocateDirectNoCleaner(ByteBuffer buffer, int capacity) {
        throw new UnsupportedOperationException(
                "Direct buffer reallocation is unavailable in the browser");
    }

    static ByteBuffer newDirectBuffer(long address, int maxCapacity) {
        throw new UnsupportedOperationException("Direct buffer addresses are unavailable in the browser");
    }

    static ByteBuffer alignSlice(ByteBuffer buffer, int alignment) {
        throw new UnsupportedOperationException("alignSlice is unavailable in the browser");
    }

    static ByteBuffer offsetSlice(ByteBuffer buffer, int offset, int length) {
        throw new UnsupportedOperationException("offsetSlice is unavailable in the browser");
    }

    static ByteBuffer absolutePut(ByteBuffer dst, int dstIndex, ByteBuffer src, int srcIndex, int length) {
        throw new UnsupportedOperationException("absolutePut is unavailable in the browser");
    }

    static ByteBuffer absolutePut(ByteBuffer dst, int dstIndex, byte[] src, int srcIndex, int length) {
        throw new UnsupportedOperationException("absolutePut is unavailable in the browser");
    }

    static byte[] allocateUninitializedArray(int size) {
        return new byte[size];
    }

    // ---- object-relative accessors (unreachable in no-Unsafe mode) --------

    static long byteArrayBaseOffset() {
        return 0L;
    }

    static Object getObject(Object obj, long offset) {
        throw unsupportedObjectAccess();
    }

    static int getInt(Object obj, long offset) {
        throw unsupportedObjectAccess();
    }

    static int getIntVolatile(Object obj, long offset) {
        throw unsupportedObjectAccess();
    }

    static void putOrderedInt(Object obj, long offset, int value) {
        throw unsupportedObjectAccess();
    }

    static int getAndAddInt(Object obj, long offset, int delta) {
        throw unsupportedObjectAccess();
    }

    static boolean compareAndSwapInt(Object obj, long offset, int expected, int update) {
        throw unsupportedObjectAccess();
    }

    static void safeConstructPutInt(Object obj, long offset, int value) {
        throw unsupportedObjectAccess();
    }

    private static long getLong(Object obj, long offset) {
        throw unsupportedObjectAccess();
    }

    static void putObject(Object obj, long offset, Object value) {
        throw unsupportedObjectAccess();
    }

    static void putByte(Object obj, long offset, byte value) {
        throw unsupportedObjectAccess();
    }

    static long objectFieldOffset(Field field) {
        throw unsupportedObjectAccess();
    }

    private static UnsupportedOperationException unsupportedObjectAccess() {
        return new UnsupportedOperationException(
                "Object-relative native access is unavailable in the browser");
    }

    // ---- atomic field updaters ---------------------------------------------

    static <U, W> AtomicReferenceFieldUpdater<U, W> newAtomicReferenceFieldUpdater(
            Class<U> tclass,
            String fieldName
    ) throws Exception {
        // Throwing makes PlatformDependent fall back to the JDK's reflective
        // atomic field updaters.
        throw new UnsupportedOperationException("Unsafe field updaters are unavailable in the browser");
    }

    static <T> AtomicIntegerFieldUpdater<T> newAtomicIntegerFieldUpdater(
            Class<?> tclass,
            String fieldName
    ) throws Exception {
        throw new UnsupportedOperationException("Unsafe field updaters are unavailable in the browser");
    }

    static <T> AtomicLongFieldUpdater<T> newAtomicLongFieldUpdater(
            Class<?> tclass,
            String fieldName
    ) throws Exception {
        throw new UnsupportedOperationException("Unsafe field updaters are unavailable in the browser");
    }

    // ---- address-based accessors (synthetic native memory) -----------------

    static byte getByte(long address) {
        return BrowserNativeMemory.getByte(address);
    }

    static short getShort(long address) {
        return BrowserNativeMemory.getShort(address);
    }

    static int getInt(long address) {
        return BrowserNativeMemory.getInt(address);
    }

    static long getLong(long address) {
        return BrowserNativeMemory.getLong(address);
    }

    static void putByte(long address, byte value) {
        BrowserNativeMemory.putByte(address, value);
    }

    static void putShort(long address, short value) {
        BrowserNativeMemory.putShort(address, value);
    }

    static void putShortOrdered(long address, short value) {
        BrowserNativeMemory.putShort(address, value);
    }

    static void putInt(long address, int value) {
        BrowserNativeMemory.putInt(address, value);
    }

    static void putLong(long address, long value) {
        BrowserNativeMemory.putLong(address, value);
    }

    static void copyMemory(long srcAddress, long dstAddress, long bytes) {
        BrowserNativeMemory.copy(srcAddress, dstAddress, bytes);
    }

    private static void copyMemoryWithSafePointPolling(long srcAddress, long dstAddress, long bytes) {
        copyMemory(srcAddress, dstAddress, bytes);
    }

    static void copyMemory(Object src, long srcOffset, Object dst, long dstOffset, long bytes) {
        throw unsupportedObjectAccess();
    }

    private static void copyMemoryWithSafePointPolling(
            Object src, long srcOffset, Object dst, long dstOffset, long bytes) {
        copyMemory(src, srcOffset, dst, dstOffset, bytes);
    }

    static void setMemory(long address, long bytes, byte value) {
        BrowserNativeMemory.set(address, value, bytes);
    }

    static void setMemory(Object obj, long offset, long bytes, byte value) {
        throw unsupportedObjectAccess();
    }

    // ---- array-index accessors ----------------------------------------------

    static byte getByte(byte[] data, int index) {
        return data[index];
    }

    static byte getByte(byte[] data, long index) {
        return data[(int) index];
    }

    static short getShort(byte[] data, int index) {
        return (short) ((data[index] & 0xFF) | ((data[index + 1] & 0xFF) << 8));
    }

    static int getInt(byte[] data, int index) {
        return (data[index] & 0xFF)
                | ((data[index + 1] & 0xFF) << 8)
                | ((data[index + 2] & 0xFF) << 16)
                | ((data[index + 3] & 0xFF) << 24);
    }

    static int getInt(int[] data, long offset) {
        return data[(int) (offset >> 2)];
    }

    static long getLong(byte[] data, int index) {
        return (getInt(data, index) & 0xFFFF_FFFFL) | ((long) getInt(data, index + 4) << 32);
    }

    static long getLong(long[] data, long offset) {
        return data[(int) (offset >> 3)];
    }

    static void putByte(byte[] data, int index, byte value) {
        data[index] = value;
    }

    static void putShort(byte[] data, int index, short value) {
        data[index] = (byte) value;
        data[index + 1] = (byte) (value >>> 8);
    }

    static void putInt(byte[] data, int index, int value) {
        data[index] = (byte) value;
        data[index + 1] = (byte) (value >>> 8);
        data[index + 2] = (byte) (value >>> 16);
        data[index + 3] = (byte) (value >>> 24);
    }

    static void putLong(byte[] data, int index, long value) {
        putInt(data, index, (int) value);
        putInt(data, index + 4, (int) (value >>> 32));
    }

    // ---- comparisons and hashing --------------------------------------------

    static boolean equals(byte[] bytes1, int startPos1, byte[] bytes2, int startPos2, int length) {
        if (length <= 0) {
            return true;
        }
        int end1 = startPos1 + length;
        for (int i = startPos1, j = startPos2; i < end1; i++, j++) {
            if (bytes1[i] != bytes2[j]) {
                return false;
            }
        }
        return true;
    }

    static int equalsConstantTime(byte[] bytes1, int startPos1, byte[] bytes2, int startPos2, int length) {
        int result = 0;
        int end1 = startPos1 + length;
        for (int i = startPos1, j = startPos2; i < end1; i++, j++) {
            result |= bytes1[i] ^ bytes2[j];
        }
        return result == 0 ? 1 : 0;
    }

    static boolean isZero(byte[] bytes, int startPos, int length) {
        if (length <= 0) {
            return true;
        }
        int end = startPos + length;
        for (int i = startPos; i < end; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    static int hashCodeAscii(byte[] bytes, int fromIndex, int length) {
        // Deterministic case-insensitive ASCII hash. Netty's exact constants
        // only matter for cross-runtime stability, which the browser port
        // does not need; per-run consistency is what AsciiString relies on.
        int hashCode = 0;
        int end = fromIndex + length;
        int tail = length & 7;
        for (int i = fromIndex; i + 8 <= end - tail; i += 8) {
            hashCode = hashCodeAsciiCompute(getLong(bytes, i), hashCode);
        }
        for (int i = end - tail; i < end; i++) {
            hashCode = hashCode * HASH_CODE_C1 + hashCodeAsciiSanitize(bytes[i]);
        }
        return hashCode;
    }

    static int hashCodeAsciiCompute(long value, int hashCode) {
        // Mask case bits (0x1f per byte) like Netty's ASCII-insensitive hash.
        return hashCode * HASH_CODE_C1
                + ((int) (value & 0x1f1f1f1f1f1f1f1fL)) * HASH_CODE_C2
                + (int) ((value >>> 32) & 0x1f1f1f1fL);
    }

    static int hashCodeAsciiSanitize(int value) {
        return value & 0x1f1f1f1f;
    }

    static int hashCodeAsciiSanitize(short value) {
        return value & 0x1f1f;
    }

    static int hashCodeAsciiSanitize(byte value) {
        return value & 0x1f;
    }

    // ---- class loaders and memory management --------------------------------

    static ClassLoader getClassLoader(final Class<?> clazz) {
        return clazz.getClassLoader();
    }

    static ClassLoader getContextClassLoader() {
        return Thread.currentThread().getContextClassLoader();
    }

    static ClassLoader getSystemClassLoader() {
        return ClassLoader.getSystemClassLoader();
    }

    static int addressSize() {
        return BrowserNativeMemory.POINTER_SIZE;
    }

    static long allocateMemory(long size) {
        return BrowserNativeMemory.malloc(size);
    }

    static void freeMemory(long address) {
        BrowserNativeMemory.free(address);
    }

    static long reallocateMemory(long address, long newSize) {
        return BrowserNativeMemory.realloc(address, newSize);
    }
}
