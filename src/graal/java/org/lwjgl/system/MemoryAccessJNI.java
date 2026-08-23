package org.lwjgl.system;

/**
 * Browser stand-in for LWJGL's native memory accessors. Backed by
 * {@link BrowserNativeMemory}'s synthetic address space.
 */
final class MemoryAccessJNI {
    static final long malloc = 0L;
    static final long calloc = 0L;
    static final long realloc = 0L;
    static final long free = 0L;
    static final long aligned_alloc = 0L;
    static final long aligned_free = 0L;

    private MemoryAccessJNI() {
    }

    static int getPointerSize() {
        return BrowserNativeMemory.POINTER_SIZE;
    }

    static byte ngetByte(long address) {
        return BrowserNativeMemory.getByte(address);
    }

    static byte getByte(long address) {
        return BrowserNativeMemory.getByte(address);
    }

    static short ngetShort(long address) {
        return BrowserNativeMemory.getShort(address);
    }

    static short getShort(long address) {
        return BrowserNativeMemory.getShort(address);
    }

    static int ngetInt(long address) {
        return BrowserNativeMemory.getInt(address);
    }

    static int getInt(long address) {
        return BrowserNativeMemory.getInt(address);
    }

    static long ngetLong(long address) {
        return BrowserNativeMemory.getLong(address);
    }

    static long getLong(long address) {
        return BrowserNativeMemory.getLong(address);
    }

    static float ngetFloat(long address) {
        return BrowserNativeMemory.getFloat(address);
    }

    static float getFloat(long address) {
        return BrowserNativeMemory.getFloat(address);
    }

    static double ngetDouble(long address) {
        return BrowserNativeMemory.getDouble(address);
    }

    static double getDouble(long address) {
        return BrowserNativeMemory.getDouble(address);
    }

    static long ngetAddress(long address) {
        return BrowserNativeMemory.getAddress(address);
    }

    static long getAddress(long address) {
        return BrowserNativeMemory.getAddress(address);
    }

    static void nputByte(long address, byte value) {
        BrowserNativeMemory.putByte(address, value);
    }

    static void putByte(long address, byte value) {
        BrowserNativeMemory.putByte(address, value);
    }

    static void nputShort(long address, short value) {
        BrowserNativeMemory.putShort(address, value);
    }

    static void putShort(long address, short value) {
        BrowserNativeMemory.putShort(address, value);
    }

    static void nputInt(long address, int value) {
        BrowserNativeMemory.putInt(address, value);
    }

    static void putInt(long address, int value) {
        BrowserNativeMemory.putInt(address, value);
    }

    static void nputLong(long address, long value) {
        BrowserNativeMemory.putLong(address, value);
    }

    static void putLong(long address, long value) {
        BrowserNativeMemory.putLong(address, value);
    }

    static void nputFloat(long address, float value) {
        BrowserNativeMemory.putFloat(address, value);
    }

    static void putFloat(long address, float value) {
        BrowserNativeMemory.putFloat(address, value);
    }

    static void nputDouble(long address, double value) {
        BrowserNativeMemory.putDouble(address, value);
    }

    static void putDouble(long address, double value) {
        BrowserNativeMemory.putDouble(address, value);
    }

    static void nputAddress(long address, long value) {
        BrowserNativeMemory.putAddress(address, value);
    }

    static void putAddress(long address, long value) {
        BrowserNativeMemory.putAddress(address, value);
    }
}
