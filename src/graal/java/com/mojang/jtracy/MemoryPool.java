package com.mojang.jtracy;

/** Browser substitution for Tracy's allocation tracking pool. */
public class MemoryPool {
    static final MemoryPool UNAVAILABLE = new MemoryPool(0L);

    private final long id;

    MemoryPool(long id) {
        this.id = id;
    }

    public void malloc(long address, int size) {
    }

    public void free(long address) {
    }
}
