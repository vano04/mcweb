package org.lwjgl.system.jni;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.CharBuffer;
import java.nio.DoubleBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;
import java.util.IdentityHashMap;
import java.util.Map;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.Checks;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser stand-in for LWJGL's JNI function table. MemoryUtil's static init
 * discovers DirectByteBuffer field offsets by creating probe buffers via
 * {@link #nNewDirectByteBuffer}; Web Image has no real NewDirectByteBuffer.
 */
public class JNINativeInterface {
    public static final int JNI_VERSION_1_1 = 0x00010001;
    public static final int JNI_VERSION_1_2 = 0x00010002;
    public static final int JNI_VERSION_1_4 = 0x00010004;
    public static final int JNI_VERSION_1_6 = 0x00010006;
    public static final int JNI_VERSION_1_8 = 0x00010008;
    public static final int JNI_VERSION_9 = 0x00090000;
    public static final int JNI_VERSION_10 = 0x000a0000;
    public static final int JNI_VERSION_19 = 0x00130000;
    public static final int JNI_VERSION_20 = 0x00140000;
    public static final int JNI_VERSION_21 = 0x00150000;
    public static final int JNI_VERSION_24 = 0x00180000;

    public static final int JNIInvalidRefType = 0;
    public static final int JNILocalRefType = 1;
    public static final int JNIGlobalRefType = 2;
    public static final int JNIWeakGlobalRefType = 3;

    public static final int JNI_FALSE = 0;
    public static final int JNI_TRUE = 1;

    public static final int JNI_OK = 0;
    public static final int JNI_ERR = -1;
    public static final int JNI_EDETACHED = -2;
    public static final int JNI_EVERSION = -3;
    public static final int JNI_ENOMEM = -4;
    public static final int JNI_EEXIST = -5;
    public static final int JNI_EINVAL = -6;

    public static final int JNI_COMMIT = 1;
    public static final int JNI_ABORT = 2;

    private static final Map<Buffer, Long> BUFFER_ADDRESSES = new IdentityHashMap<>();

    protected JNINativeInterface() {
        throw new UnsupportedOperationException();
    }

    public static int GetVersion() {
        return JNI_VERSION_24;
    }

    public static long FromReflectedMethod(Method method) {
        return 0L;
    }

    public static long FromReflectedField(Field field) {
        return 0L;
    }

    public static Method nToReflectedMethod(Class<?> clazz, long methodID, boolean isStatic) {
        return null;
    }

    public static Method ToReflectedMethod(Class<?> clazz, long methodID, boolean isStatic) {
        return nToReflectedMethod(clazz, methodID, isStatic);
    }

    public static Field nToReflectedField(Class<?> clazz, long fieldID, boolean isStatic) {
        return null;
    }

    public static Field ToReflectedField(Class<?> clazz, long fieldID, boolean isStatic) {
        return nToReflectedField(clazz, fieldID, isStatic);
    }

    public static long NewGlobalRef(Object obj) {
        return 0L;
    }

    public static void nDeleteGlobalRef(long globalRef) {
    }

    public static void DeleteGlobalRef(long globalRef) {
    }

    public static long nGetBooleanArrayElements(byte[] array, long isCopy) {
        return 0L;
    }

    public static ByteBuffer GetBooleanArrayElements(byte[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseBooleanArrayElements(byte[] array, long elements, int mode) {
    }

    public static void ReleaseBooleanArrayElements(byte[] array, ByteBuffer elements, int mode) {
    }

    public static long nGetByteArrayElements(byte[] array, long isCopy) {
        return 0L;
    }

    public static ByteBuffer GetByteArrayElements(byte[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseByteArrayElements(byte[] array, long elements, int mode) {
    }

    public static void ReleaseByteArrayElements(byte[] array, ByteBuffer elements, int mode) {
    }

    public static long nGetCharArrayElements(char[] array, long isCopy) {
        return 0L;
    }

    public static ShortBuffer GetCharArrayElements(char[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseCharArrayElements(char[] array, long elements, int mode) {
    }

    public static void ReleaseCharArrayElements(char[] array, ShortBuffer elements, int mode) {
    }

    public static long nGetShortArrayElements(short[] array, long isCopy) {
        return 0L;
    }

    public static ShortBuffer GetShortArrayElements(short[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseShortArrayElements(short[] array, long elements, int mode) {
    }

    public static void ReleaseShortArrayElements(short[] array, ShortBuffer elements, int mode) {
    }

    public static long nGetIntArrayElements(int[] array, long isCopy) {
        return 0L;
    }

    public static IntBuffer GetIntArrayElements(int[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseIntArrayElements(int[] array, long elements, int mode) {
    }

    public static void ReleaseIntArrayElements(int[] array, IntBuffer elements, int mode) {
    }

    public static long nGetLongArrayElements(long[] array, long isCopy) {
        return 0L;
    }

    public static LongBuffer GetLongArrayElements(long[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseLongArrayElements(long[] array, long elements, int mode) {
    }

    public static void ReleaseLongArrayElements(long[] array, LongBuffer elements, int mode) {
    }

    public static long nGetFloatArrayElements(float[] array, long isCopy) {
        return 0L;
    }

    public static FloatBuffer GetFloatArrayElements(float[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseFloatArrayElements(float[] array, long elements, int mode) {
    }

    public static void ReleaseFloatArrayElements(float[] array, FloatBuffer elements, int mode) {
    }

    public static long nGetDoubleArrayElements(double[] array, long isCopy) {
        return 0L;
    }

    public static DoubleBuffer GetDoubleArrayElements(double[] array, ByteBuffer isCopy) {
        return null;
    }

    public static void nReleaseDoubleArrayElements(double[] array, long elements, int mode) {
    }

    public static void ReleaseDoubleArrayElements(double[] array, DoubleBuffer elements, int mode) {
    }

    public static void nGetBooleanArrayRegion(byte[] array, int start, int len, long buf) {
    }

    public static void GetBooleanArrayRegion(byte[] array, int start, ByteBuffer buf) {
    }

    public static void nSetBooleanArrayRegion(byte[] array, int start, int len, long buf) {
    }

    public static void SetBooleanArrayRegion(byte[] array, int start, ByteBuffer buf) {
    }

    public static void nGetByteArrayRegion(byte[] array, int start, int len, long buf) {
    }

    public static void GetByteArrayRegion(byte[] array, int start, ByteBuffer buf) {
    }

    public static void nSetByteArrayRegion(byte[] array, int start, int len, long buf) {
    }

    public static void SetByteArrayRegion(byte[] array, int start, ByteBuffer buf) {
    }

    public static void nGetCharArrayRegion(char[] array, int start, int len, long buf) {
    }

    public static void GetCharArrayRegion(char[] array, int start, ShortBuffer buf) {
    }

    public static void nSetCharArrayRegion(char[] array, int start, int len, long buf) {
    }

    public static void SetCharArrayRegion(char[] array, int start, ShortBuffer buf) {
    }

    public static void nGetShortArrayRegion(short[] array, int start, int len, long buf) {
    }

    public static void GetShortArrayRegion(short[] array, int start, ShortBuffer buf) {
    }

    public static void nSetShortArrayRegion(short[] array, int start, int len, long buf) {
    }

    public static void SetShortArrayRegion(short[] array, int start, ShortBuffer buf) {
    }

    public static void nGetIntArrayRegion(int[] array, int start, int len, long buf) {
    }

    public static void GetIntArrayRegion(int[] array, int start, IntBuffer buf) {
    }

    public static void nSetIntArrayRegion(int[] array, int start, int len, long buf) {
    }

    public static void SetIntArrayRegion(int[] array, int start, IntBuffer buf) {
    }

    public static void nGetLongArrayRegion(long[] array, int start, int len, long buf) {
    }

    public static void GetLongArrayRegion(long[] array, int start, LongBuffer buf) {
    }

    public static void nSetLongArrayRegion(long[] array, int start, int len, long buf) {
    }

    public static void SetLongArrayRegion(long[] array, int start, LongBuffer buf) {
    }

    public static void nGetFloatArrayRegion(float[] array, int start, int len, long buf) {
    }

    public static void GetFloatArrayRegion(float[] array, int start, FloatBuffer buf) {
    }

    public static void nSetFloatArrayRegion(float[] array, int start, int len, long buf) {
    }

    public static void SetFloatArrayRegion(float[] array, int start, FloatBuffer buf) {
    }

    public static void nGetDoubleArrayRegion(double[] array, int start, int len, long buf) {
    }

    public static void GetDoubleArrayRegion(double[] array, int start, DoubleBuffer buf) {
    }

    public static void nSetDoubleArrayRegion(double[] array, int start, int len, long buf) {
    }

    public static void SetDoubleArrayRegion(double[] array, int start, DoubleBuffer buf) {
    }

    public static int nRegisterNatives(Class<?> clazz, long methods, int nMethods) {
        return JNI_OK;
    }

    public static int RegisterNatives(Class<?> clazz, JNINativeMethod.Buffer methods) {
        return JNI_OK;
    }

    public static int UnregisterNatives(Class<?> clazz) {
        return JNI_OK;
    }

    public static int nGetJavaVM(long vm) {
        return JNI_OK;
    }

    public static int GetJavaVM(PointerBuffer vm) {
        return JNI_OK;
    }

    public static void nGetStringRegion(String str, int start, int len, long buf) {
    }

    public static void GetStringRegion(String str, int start, ByteBuffer buf) {
    }

    public static void nGetStringUTFRegion(String str, int start, int len, long buf) {
    }

    public static void GetStringUTFRegion(String str, int start, int len, ByteBuffer buf) {
    }

    public static long NewWeakGlobalRef(Object obj) {
        return 0L;
    }

    public static void nDeleteWeakGlobalRef(long weakGlobalRef) {
    }

    /**
     * MemoryUtil field-offset probes call this with large capacities and magic
     * addresses. Allocate a real direct buffer (Web Image supports Java
     * allocateDirect) and remember any non-zero address for later lookups.
     */
    public static ByteBuffer nNewDirectByteBuffer(long address, long capacity) {
        int cap = sanitizeCapacity(capacity);
        ByteBuffer buffer = ByteBuffer.allocateDirect(Math.max(cap, 1)).order(ByteOrder.nativeOrder());
        if (cap == 0) {
            buffer.limit(0);
        }
        if (address != 0L && address != -1L) {
            synchronized (BUFFER_ADDRESSES) {
                BUFFER_ADDRESSES.put(buffer, address);
            }
        }
        return buffer;
    }

    public static ByteBuffer NewDirectByteBuffer(long address, long capacity) {
        if (Checks.CHECKS) {
            Checks.check(address);
        }
        return nNewDirectByteBuffer(address, capacity);
    }

    public static long GetDirectBufferAddress(Buffer buffer) {
        if (buffer == null) {
            return 0L;
        }
        synchronized (BUFFER_ADDRESSES) {
            Long mapped = BUFFER_ADDRESSES.get(buffer);
            if (mapped != null) {
                return mapped;
            }
        }
        // Fall back to MemoryUtil once offsets are initialized; during early
        // probes MemoryUtil.ADDRESS may still be zero and Unsafe will return 0.
        try {
            return MemoryUtil.memAddress(buffer);
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    public static int GetObjectRefType(Object obj) {
        return JNIInvalidRefType;
    }

    public static void noop() {
    }

    private static int sanitizeCapacity(long capacity) {
        if (capacity <= 0L) {
            return 0;
        }
        // LWJGL offset probes use ~209 MiB capacities; keep that working while
        // rejecting absurd values that would OOM the browser tab.
        long capped = Math.min(capacity, 256L * 1024L * 1024L);
        if (capped > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) capped;
    }
}
