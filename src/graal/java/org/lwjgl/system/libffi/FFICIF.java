package org.lwjgl.system.libffi;

import java.nio.ByteBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

/**
 * Browser FFICIF with a fixed 64-bit layout. The real class uses a native
 * {@code offsets} helper during clinit; Web Image cannot link it.
 */
public class FFICIF extends Struct<FFICIF> implements NativeResource {
    // Approximate libffi cif layout used only as opaque storage for callbacks.
    public static final int SIZEOF = 64;
    public static final int ALIGNOF = 8;
    public static final int ABI = 0;
    public static final int NARGS = 4;
    public static final int ARG_TYPES = 8;
    public static final int RTYPE = 16;
    public static final int BYTES = 24;
    public static final int FLAGS = 28;

    public FFICIF(ByteBuffer container) {
        super(MemoryUtil.memAddress(container), MemoryUtil.memSlice(container, 0, SIZEOF));
    }

    public FFICIF(long address, ByteBuffer container) {
        super(address, container);
    }

    @Override
    protected FFICIF create(long address, ByteBuffer container) {
        return new FFICIF(address, container);
    }

    @Override
    public int sizeof() {
        return SIZEOF;
    }

    public int abi() {
        return nabi(address());
    }

    public int nargs() {
        return nnargs(address());
    }

    public PointerBuffer arg_types() {
        return narg_types(address());
    }

    public FFIType rtype() {
        return nrtype(address());
    }

    public int bytes() {
        return nbytes(address());
    }

    public int flags() {
        return nflags(address());
    }

    public static FFICIF malloc() {
        return new FFICIF(MemoryUtil.nmemAllocChecked(SIZEOF), null);
    }

    public static FFICIF calloc() {
        return new FFICIF(MemoryUtil.nmemCallocChecked(1, SIZEOF), null);
    }

    public static FFICIF create() {
        ByteBuffer container = MemoryUtil.memAlloc(SIZEOF);
        return new FFICIF(container);
    }

    public static FFICIF create(long address) {
        return new FFICIF(address, null);
    }

    public static FFICIF createSafe(long address) {
        return address == 0L ? null : create(address);
    }

    public static Buffer malloc(int capacity) {
        return new Buffer(MemoryUtil.nmemAllocChecked((long) capacity * SIZEOF), capacity);
    }

    public static Buffer calloc(int capacity) {
        return new Buffer(MemoryUtil.nmemCallocChecked(capacity, SIZEOF), capacity);
    }

    public static Buffer create(int capacity) {
        ByteBuffer container = MemoryUtil.memAlloc(capacity * SIZEOF);
        return new Buffer(MemoryUtil.memAddress(container), container, -1, 0, capacity, capacity);
    }

    public static Buffer create(long address, int capacity) {
        return new Buffer(address, capacity);
    }

    public static Buffer createSafe(long address, int capacity) {
        return address == 0L ? null : create(address, capacity);
    }

    public static FFICIF malloc(MemoryStack stack) {
        return new FFICIF(stack.nmalloc(ALIGNOF, SIZEOF), null);
    }

    public static FFICIF calloc(MemoryStack stack) {
        return new FFICIF(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
    }

    public static Buffer malloc(int capacity, MemoryStack stack) {
        return new Buffer(stack.nmalloc(ALIGNOF, capacity * SIZEOF), capacity);
    }

    public static Buffer calloc(int capacity, MemoryStack stack) {
        return new Buffer(stack.ncalloc(ALIGNOF, capacity, SIZEOF), capacity);
    }

    public static int nabi(long struct) {
        return MemoryUtil.memGetInt(struct + ABI);
    }

    public static int nnargs(long struct) {
        return MemoryUtil.memGetInt(struct + NARGS);
    }

    public static PointerBuffer narg_types(long struct) {
        return PointerBuffer.create(MemoryUtil.memGetAddress(struct + ARG_TYPES), nnargs(struct));
    }

    public static FFIType nrtype(long struct) {
        return FFIType.create(MemoryUtil.memGetAddress(struct + RTYPE));
    }

    public static int nbytes(long struct) {
        return MemoryUtil.memGetInt(struct + BYTES);
    }

    public static int nflags(long struct) {
        return MemoryUtil.memGetInt(struct + FLAGS);
    }

    public static class Buffer extends StructBuffer<FFICIF, Buffer> implements NativeResource {
        private static final FFICIF ELEMENT_FACTORY = FFICIF.create(-1L);

        public Buffer(ByteBuffer container) {
            super(container, container.remaining() / SIZEOF);
        }

        public Buffer(long address, int capacity) {
            super(address, null, -1, 0, capacity, capacity);
        }

        Buffer(long address, ByteBuffer container, int mark, int position, int limit, int capacity) {
            super(address, container, mark, position, limit, capacity);
        }

        @Override
        protected Buffer self() {
            return this;
        }

        @Override
        protected Buffer create(
                long address,
                ByteBuffer container,
                int mark,
                int position,
                int limit,
                int capacity
        ) {
            return new Buffer(address, container, mark, position, limit, capacity);
        }

        @Override
        protected FFICIF getElementFactory() {
            return ELEMENT_FACTORY;
        }

        @Override
        public void free() {
            MemoryUtil.nmemFree(address());
        }
    }
}
