package org.lwjgl.system.libffi;

import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.NativeResource;
import org.lwjgl.system.Struct;
import org.lwjgl.system.StructBuffer;

/**
 * Browser FFIClosure with a fixed layout. Avoids native {@code offsets} clinit.
 */
public class FFIClosure extends Struct<FFIClosure> implements NativeResource {
    public static final int SIZEOF = 32;
    public static final int ALIGNOF = 8;
    public static final int CIF = 0;
    public static final int FUN = 8;
    public static final int USER_DATA = 16;

    public FFIClosure(ByteBuffer container) {
        super(MemoryUtil.memAddress(container), MemoryUtil.memSlice(container, 0, SIZEOF));
    }

    public FFIClosure(long address, ByteBuffer container) {
        super(address, container);
    }

    @Override
    protected FFIClosure create(long address, ByteBuffer container) {
        return new FFIClosure(address, container);
    }

    @Override
    public int sizeof() {
        return SIZEOF;
    }

    public FFICIF cif() {
        return ncif(address());
    }

    public long fun() {
        return nfun(address());
    }

    public long user_data() {
        return nuser_data(address());
    }

    public static FFICIF ncif(long struct) {
        return FFICIF.create(MemoryUtil.memGetAddress(struct + CIF));
    }

    public static long nfun(long struct) {
        return MemoryUtil.memGetAddress(struct + FUN);
    }

    public static long nuser_data(long struct) {
        return MemoryUtil.memGetAddress(struct + USER_DATA);
    }

    public static FFIClosure malloc() {
        return new FFIClosure(MemoryUtil.nmemAllocChecked(SIZEOF), null);
    }

    public static FFIClosure calloc() {
        return new FFIClosure(MemoryUtil.nmemCallocChecked(1, SIZEOF), null);
    }

    public static FFIClosure create() {
        return new FFIClosure(MemoryUtil.memAlloc(SIZEOF));
    }

    public static FFIClosure create(long address) {
        return new FFIClosure(address, null);
    }

    public static FFIClosure createSafe(long address) {
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

    public static FFIClosure malloc(MemoryStack stack) {
        return new FFIClosure(stack.nmalloc(ALIGNOF, SIZEOF), null);
    }

    public static FFIClosure calloc(MemoryStack stack) {
        return new FFIClosure(stack.ncalloc(ALIGNOF, 1, SIZEOF), null);
    }

    public static Buffer malloc(int capacity, MemoryStack stack) {
        return new Buffer(stack.nmalloc(ALIGNOF, capacity * SIZEOF), capacity);
    }

    public static Buffer calloc(int capacity, MemoryStack stack) {
        return new Buffer(stack.ncalloc(ALIGNOF, capacity, SIZEOF), capacity);
    }

    public static class Buffer extends StructBuffer<FFIClosure, Buffer> implements NativeResource {
        private static final FFIClosure ELEMENT_FACTORY = FFIClosure.create(-1L);

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
        protected FFIClosure getElementFactory() {
            return ELEMENT_FACTORY;
        }

        @Override
        public void free() {
            MemoryUtil.nmemFree(address());
        }
    }
}
