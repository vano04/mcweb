package io.netty.util.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Browser replacement for Netty's VarHandle factory. The original routes
 * privateFindVarHandle through MethodHandle.invokeExact(Lookup, Class,
 * String, Class), so SVM cannot specialize the folded Lookup.findVarHandle
 * call and registers VarHandle accessors for every primitive — including
 * double/float CAS variants the wasm backend cannot compile ("unhandled
 * compare"). This shadow dispatches on constant carrier classes so every
 * fold is type-specific, and omits float/double entirely (no Netty caller
 * uses them). isSupported() reports false so Netty selects its atomic
 * field updater fallbacks for reference counting.
 */
final class VarHandleFactory {
    private static final VarHandle LONG_LE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_BE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_LE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_BE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle SHORT_LE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle SHORT_BE_ARRAY_VIEW =
            MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_LE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_BE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_LE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_BE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle SHORT_LE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle SHORT_BE_BYTE_BUFFER_VIEW =
            MethodHandles.byteBufferViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);

    private VarHandleFactory() {
    }

    public static boolean isSupported() {
        return false;
    }

    public static Throwable unavailableCause() {
        return new UnsupportedOperationException(
                "Netty VarHandle support is disabled for the browser port");
    }

    private static MethodHandles.Lookup privateLookup(MethodHandles.Lookup lookup, Class<?> klass)
            throws IllegalAccessException {
        return MethodHandles.privateLookupIn(klass, lookup);
    }

    public static VarHandle privateFindVarHandle(
            MethodHandles.Lookup lookup,
            Class<?> klass,
            String name,
            Class<?> type
    ) {
        try {
            MethodHandles.Lookup privateLookup = privateLookup(lookup, klass);
            // Constant carrier classes keep the SVM fold type-specific;
            // float/double are deliberately unsupported (wasm backend bug).
            if (type == int.class) {
                return privateLookup.findVarHandle(klass, name, int.class);
            }
            if (type == long.class) {
                return privateLookup.findVarHandle(klass, name, long.class);
            }
            if (type == boolean.class) {
                return privateLookup.findVarHandle(klass, name, boolean.class);
            }
            if (type == short.class) {
                return privateLookup.findVarHandle(klass, name, short.class);
            }
            if (type == byte.class) {
                return privateLookup.findVarHandle(klass, name, byte.class);
            }
            if (type == char.class) {
                return privateLookup.findVarHandle(klass, name, char.class);
            }
            throw new IllegalArgumentException("Unsupported browser VarHandle field type: " + type);
        } catch (IllegalAccessException | NoSuchFieldException failure) {
            throw new IllegalStateException(
                    "Cannot obtain a private lookup for " + klass.getName(), failure);
        }
    }

    public static VarHandle longLeArrayView() {
        return LONG_LE_ARRAY_VIEW;
    }

    public static VarHandle longBeArrayView() {
        return LONG_BE_ARRAY_VIEW;
    }

    public static VarHandle intLeArrayView() {
        return INT_LE_ARRAY_VIEW;
    }

    public static VarHandle intBeArrayView() {
        return INT_BE_ARRAY_VIEW;
    }

    public static VarHandle shortLeArrayView() {
        return SHORT_LE_ARRAY_VIEW;
    }

    public static VarHandle shortBeArrayView() {
        return SHORT_BE_ARRAY_VIEW;
    }

    public static VarHandle longLeByteBufferView() {
        return LONG_LE_BYTE_BUFFER_VIEW;
    }

    public static VarHandle longBeByteBufferView() {
        return LONG_BE_BYTE_BUFFER_VIEW;
    }

    public static VarHandle intLeByteBufferView() {
        return INT_LE_BYTE_BUFFER_VIEW;
    }

    public static VarHandle intBeByteBufferView() {
        return INT_BE_BYTE_BUFFER_VIEW;
    }

    public static VarHandle shortLeByteBufferView() {
        return SHORT_LE_BYTE_BUFFER_VIEW;
    }

    public static VarHandle shortBeByteBufferView() {
        return SHORT_BE_BYTE_BUFFER_VIEW;
    }
}
