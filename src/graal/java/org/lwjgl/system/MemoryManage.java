package org.lwjgl.system;

/**
 * Browser stand-in for LWJGL's allocator selector. Always returns the pure-Java
 * allocator backed by {@link BrowserNativeMemory}.
 */
final class MemoryManage {
    private MemoryManage() {
    }

    static MemoryUtil.MemoryAllocator getInstance() {
        return BrowserAllocator.INSTANCE;
    }

    private static final class BrowserAllocator implements MemoryUtil.MemoryAllocator {
        static final BrowserAllocator INSTANCE = new BrowserAllocator();

        private BrowserAllocator() {
        }

        @Override
        public long getMalloc() {
            return 0L;
        }

        @Override
        public long getCalloc() {
            return 0L;
        }

        @Override
        public long getRealloc() {
            return 0L;
        }

        @Override
        public long getFree() {
            return 0L;
        }

        @Override
        public long getAlignedAlloc() {
            return 0L;
        }

        @Override
        public long getAlignedFree() {
            return 0L;
        }

        @Override
        public long malloc(long size) {
            return BrowserNativeMemory.malloc(size);
        }

        @Override
        public long calloc(long num, long size) {
            return BrowserNativeMemory.calloc(num, size);
        }

        @Override
        public long realloc(long ptr, long size) {
            return BrowserNativeMemory.realloc(ptr, size);
        }

        @Override
        public void free(long ptr) {
            BrowserNativeMemory.free(ptr);
        }

        @Override
        public long aligned_alloc(long alignment, long size) {
            return BrowserNativeMemory.malloc(size);
        }

        @Override
        public void aligned_free(long ptr) {
            BrowserNativeMemory.free(ptr);
        }
    }
}
