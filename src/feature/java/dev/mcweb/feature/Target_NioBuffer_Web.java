package dev.mcweb.feature;

import com.oracle.svm.core.annotate.Alias;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.nio.CharBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

/**
 * Web-safe JDK 25 NIO bulk paths reachable while ICU loads calendar data in a
 * Web Image.
 *
 * <p>The optimized JDK implementations use {@code Unsafe.copySwapMemory0}
 * for addressable, non-native-order buffers. The WasmGC backend does not
 * implement that native primitive. Backing primitive arrays retain the JDK's
 * {@link System#arraycopy} fast path; only views without an accessible matching
 * array use scalar access. The substitutions retain the indexed contracts:
 * neither source nor destination positions are changed, and the private array
 * helpers return the receiving buffer.</p>
 */
@TargetClass(className = "java.nio.CharBuffer")
final class Target_CharBuffer_Web {

    @Alias
    public native char get(int index);

    @Alias
    public native CharBuffer put(int index, char value);

    @Substitute
    private CharBuffer getArray(int index, char[] destination, int offset, int length) {
        CharBuffer receiver = (CharBuffer) (Object) this;
        if (receiver.hasArray()) {
            System.arraycopy(receiver.array(), receiver.arrayOffset() + index,
                    destination, offset, length);
            return receiver;
        }
        for (int element = 0; element < length; element++) {
            destination[offset + element] = get(index + element);
        }
        return receiver;
    }

    @Substitute
    void putBuffer(int index, CharBuffer source, int sourceIndex, int length) {
        CharBuffer receiver = (CharBuffer) (Object) this;
        if (receiver.hasArray() && source.hasArray()) {
            System.arraycopy(source.array(), source.arrayOffset() + sourceIndex,
                    receiver.array(), receiver.arrayOffset() + index, length);
            return;
        }
        /*
         * Buffer.put(index, source, sourceIndex, length) is specified as if
         * the source values were first copied to an intermediate array. A
         * snapshot is therefore required not only for self-copy, but also
         * for overlapping slices or duplicates that share the same storage.
         */
        char[] snapshot = new char[length];
        for (int element = 0; element < length; element++) {
            snapshot[element] = source.get(sourceIndex + element);
        }
        for (int element = 0; element < length; element++) {
            put(index + element, snapshot[element]);
        }
    }
}

@TargetClass(className = "java.nio.ShortBuffer")
final class Target_ShortBuffer_Web {

    @Alias
    public native short get(int index);

    @Substitute
    private ShortBuffer getArray(int index, short[] destination, int offset, int length) {
        ShortBuffer receiver = (ShortBuffer) (Object) this;
        if (receiver.hasArray()) {
            System.arraycopy(receiver.array(), receiver.arrayOffset() + index,
                    destination, offset, length);
            return receiver;
        }
        for (int element = 0; element < length; element++) {
            destination[offset + element] = get(index + element);
        }
        return receiver;
    }
}

@TargetClass(className = "java.nio.IntBuffer")
final class Target_IntBuffer_Web {

    @Alias
    public native int get(int index);

    @Substitute
    private IntBuffer getArray(int index, int[] destination, int offset, int length) {
        IntBuffer receiver = (IntBuffer) (Object) this;
        if (receiver.hasArray()) {
            System.arraycopy(receiver.array(), receiver.arrayOffset() + index,
                    destination, offset, length);
            return receiver;
        }
        for (int element = 0; element < length; element++) {
            destination[offset + element] = get(index + element);
        }
        return receiver;
    }
}

@TargetClass(className = "java.nio.LongBuffer")
final class Target_LongBuffer_Web {

    @Alias
    public native long get(int index);

    @Substitute
    private LongBuffer getArray(int index, long[] destination, int offset, int length) {
        LongBuffer receiver = (LongBuffer) (Object) this;
        if (receiver.hasArray()) {
            System.arraycopy(receiver.array(), receiver.arrayOffset() + index,
                    destination, offset, length);
            return receiver;
        }
        for (int element = 0; element < length; element++) {
            destination[offset + element] = get(index + element);
        }
        return receiver;
    }
}
