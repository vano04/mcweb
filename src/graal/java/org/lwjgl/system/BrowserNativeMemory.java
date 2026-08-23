package org.lwjgl.system;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic address space for browser LWJGL. Allocations are heap {@code byte[]}
 * blocks; NIO views are registered so {@code memAddress} can recover the token.
 */
public final class BrowserNativeMemory {
    public static final int POINTER_SIZE = 8;

    private static final AtomicLong NEXT = new AtomicLong(0x1_0000L);
    /**
     * Live blocks by base address. Ordered, not hashed: every interior-address lookup
     * ({@link #block}, {@link #realloc}, {@link #free}) is a {@code floorEntry}, which a
     * {@code ConcurrentHashMap} cannot answer without scanning every entry.
     */
    private static final ConcurrentSkipListMap<Long, byte[]> BLOCKS = new ConcurrentSkipListMap<>();
    private static final Map<Buffer, Long> BUFFER_ADDRESSES = new IdentityHashMap<>();
    private static final AtomicLong LIVE_BYTES = new AtomicLong();
    private static final AtomicLong PEAK_BYTES = new AtomicLong();
    private static final AtomicLong ALLOCATIONS = new AtomicLong();

    /**
     * Reuse large native backing arrays after their NIO view is freed. These arrays are
     * Java objects in this browser port, so repeatedly allocating a new 16-128 MiB
     * payload is also repeatedly asking the non-moving WasmLM heap for a large
     * contiguous object. Keeping a small bounded pool makes the first asset/terrain
     * allocation pay that cost once and lets later churn reuse the same storage.
     *
     * <p>The pool is deliberately byte-array based rather than a collection of
     * {@code ByteBuffer}s: a new synthetic address is assigned on each borrow, while
     * the old view has already been removed from {@link #BLOCKS}. That keeps stale NIO
     * addresses invalid and makes reuse invisible to callers.</p>
     */
    private static final int LARGE_POOL_MIN_BYTES = 1 * 1024 * 1024;
    private static final int LARGE_POOL_MAX_BYTES = 128 * 1024 * 1024;
    private static final long LARGE_POOL_LIMIT_BYTES = 256L * 1024 * 1024;
    private static final int LARGE_POOL_SLOTS = 12;
    private static final Object LARGE_POOL_LOCK = new Object();
    private static final byte[][] LARGE_POOL = new byte[LARGE_POOL_SLOTS][];
    private static long pooledBytes;

    private BrowserNativeMemory() {
    }

    /**
     * Soft cap for a single allocation; larger requests fail with a clear size.
     *
     * <p>This is this port's own guard, not a browser limit. 64 MiB was fine
     * until the client actually entered a world: the terrain renderer asks for
     * a single 98 MiB buffer once chunks start meshing, and the guard turned
     * that into an OutOfMemoryError that killed the frame pump the moment the
     * player spawned. The Wasm heap has room (native usage peaked around 88 MiB
     * across all blocks), so the cap is raised rather than the request refused.
     */
    private static final int MAX_SINGLE_ALLOCATION = 512 * 1024 * 1024;

    /** Report allocations this large so a runaway buffer stays visible. */
    private static final int LARGE_ALLOCATION_REPORT = 16 * 1024 * 1024;

    public static long malloc(long size) {
        int bytes = sanitize(size);
        if (bytes > MAX_SINGLE_ALLOCATION) {
            throw new OutOfMemoryError(
                    "browser single allocation too large: " + bytes + " bytes"
            );
        }
        boolean reportLarge = bytes >= LARGE_ALLOCATION_REPORT;
        if (reportLarge) {
            try {
                // The last progress marker is the only attribution available:
                // Web Image strips stack traces, so a 98 MiB block that shows
                // up as the largest live allocation at OOM time cannot
                // otherwise be traced to whatever asked for it.
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "native:large-alloc " + (bytes / (1024 * 1024)) + "MiB live="
                                + (LIVE_BYTES.get() / (1024 * 1024)) + "MiB blocks=" + BLOCKS.size()
                                + " during=" + dev.mcweb.graal.MinecraftInitProgress.lastStage
                );
            } catch (Throwable ignored) {
                // Telemetry must never break an allocation.
            }
        }
        try {
            byte[] block = takePooled(bytes);
            if (block == null) {
                block = new byte[bytes];
            } else {
                // Preserve the visible zero-filled behavior of the old new byte[] path
                // without clearing unused capacity when a larger pooled block is reused
                // for a smaller request.
                Arrays.fill(block, 0, bytes, (byte) 0);
            }
            // Reserve address space for the block that actually exists, not for
            // the size that was asked for.
            //
            // takePooled returns the smallest pooled array that *can satisfy*
            // the request, so it is routinely larger than `bytes` — while
            // block() resolves an address by `address < base + data.length`.
            // Advancing NEXT by `bytes + 32` therefore let a pooled block's
            // apparent span swallow the addresses handed out after it, and any
            // lookup landing in that overlap resolved to the wrong block with a
            // bogus offset. wrapByte then clamped the view to whatever remained
            // of the wrong block and returned it silently: a 16x16 RGBA image
            // came back with 640 of its 1024 bytes, and the failure surfaced
            // much later as CommandEncoder's "Copy would overrun the source
            // buffer", killing the whole resource reload — which is why a
            // server's resource pack intermittently did not apply at all.
            long address = NEXT.getAndAdd(block.length + 32L);
            if (reportLarge) {
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("native:large-array-ready " + (bytes / (1024 * 1024)) + "MiB");
            }
            BLOCKS.put(address, block);
            if (reportLarge) {
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress("native:large-mapped " + (bytes / (1024 * 1024)) + "MiB");
            }
            noteAllocated(bytes);
            return address;
        } catch (OutOfMemoryError error) {
            throw new OutOfMemoryError(
                    "browser heap exhausted allocating " + bytes + " bytes ("
                            + BLOCKS.size() + " live blocks, "
                            + LIVE_BYTES.get() + " live bytes)"
            );
        }
    }

    private static void noteAllocated(long bytes) {
        ALLOCATIONS.incrementAndGet();
        long live = LIVE_BYTES.addAndGet(bytes);
        long peak;
        do {
            peak = PEAK_BYTES.get();
            if (live <= peak) {
                return;
            }
        } while (!PEAK_BYTES.compareAndSet(peak, live));
    }

    /** Live/peak synthetic-native usage for init progress traces. */
    public static String stats() {
        return "native=" + (LIVE_BYTES.get() / 1024L) + "KiB/"
                + (PEAK_BYTES.get() / 1024L) + "KiB-peak #"
                + ALLOCATIONS.get() + "allocs/"
                + BLOCKS.size() + "blocks pooled=" + (pooledBytes / 1024L) + "KiB";
    }

    /**
     * Largest live blocks at failure time, for OOM attribution. Fixed-size
     * scan so it stays usable while the heap is already exhausted.
     */
    public static String describeLargest(int count) {
        long[] sizes = new long[count];
        long[] addresses = new long[count];
        int tracked = 0;
        for (Map.Entry<Long, byte[]> entry : BLOCKS.entrySet()) {
            long size = entry.getValue().length;
            int slot = -1;
            for (int i = 0; i < tracked; i++) {
                if (size > sizes[i]) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                if (tracked < count) {
                    slot = tracked++;
                } else {
                    continue;
                }
            }
            for (int i = tracked - 1; i > slot; i--) {
                sizes[i] = sizes[i - 1];
                addresses[i] = addresses[i - 1];
            }
            sizes[slot] = size;
            addresses[slot] = entry.getKey();
        }
        StringBuilder result = new StringBuilder("largest-native-blocks:");
        for (int i = 0; i < tracked; i++) {
            result.append(' ')
                    .append(addresses[i])
                    .append('=')
                    .append(sizes[i] / 1024L)
                    .append("KiB");
        }
        return result.toString();
    }

    public static long calloc(long num, long size) {
        return malloc(Math.multiplyExact(num, size));
    }

    public static long realloc(long ptr, long size) {
        if (ptr == 0L) {
            return malloc(size);
        }
        // Callers may pass interior addresses, so resolve the containing
        // block and copy from the offset rather than requiring an exact
        // base hit (an exact-only lookup silently dropped the old data).
        long base = ptr;
        byte[] previous = BLOCKS.get(ptr);
        int sourceOffset = 0;
        if (previous == null) {
            Map.Entry<Long, byte[]> containing = containingEntry(ptr);
            if (containing != null) {
                base = containing.getKey();
                previous = containing.getValue();
                sourceOffset = (int) (ptr - base);
            }
        }
        long next = malloc(size);
        if (previous != null) {
            byte[] allocated = BLOCKS.get(next);
            if (allocated != null) {
                System.arraycopy(
                        previous,
                        sourceOffset,
                        allocated,
                        0,
                        Math.min(previous.length - sourceOffset, allocated.length)
                );
            }
            if (BLOCKS.remove(base) != null) {
                LIVE_BYTES.addAndGet(-previous.length);
                invalidate(base);
                releasePooled(previous);
            }
        }
        return next;
    }

    /** The live block containing {@code address}, or null. Never maps anything. */
    private static Map.Entry<Long, byte[]> containingEntry(long address) {
        Map.Entry<Long, byte[]> entry = BLOCKS.floorEntry(address);
        return entry != null && address < entry.getKey() + entry.getValue().length ? entry : null;
    }

    /** Drops the locality cache if it still points at a block that has been freed. */
    private static void invalidate(long base) {
        Block hit = cache;
        if (hit != null && hit.base == base) {
            cache = null;
        }
    }

    public static void free(long ptr) {
        if (ptr == 0L) {
            return;
        }
        byte[] removed = BLOCKS.remove(ptr);
        long base = ptr;
        if (removed == null) {
            // Buffers may be freed at an interior address (e.g. an NIO view's
            // memAddress after its position moved). Fall back to the block
            // that contains the address so the allocation does not leak.
            Map.Entry<Long, byte[]> containing = containingEntry(ptr);
            if (containing != null) {
                base = containing.getKey();
                removed = BLOCKS.remove(base);
            }
        }
        if (removed != null) {
            LIVE_BYTES.addAndGet(-removed.length);
            invalidate(base);
            releasePooled(removed);
        }
    }

    /** Returns the smallest pooled array that can satisfy {@code bytes}. */
    private static byte[] takePooled(int bytes) {
        if (bytes < LARGE_POOL_MIN_BYTES) {
            return null;
        }
        synchronized (LARGE_POOL_LOCK) {
            int best = -1;
            int bestLength = Integer.MAX_VALUE;
            for (int i = 0; i < LARGE_POOL.length; i++) {
                byte[] candidate = LARGE_POOL[i];
                if (candidate != null && candidate.length >= bytes && candidate.length < bestLength) {
                    best = i;
                    bestLength = candidate.length;
                }
            }
            if (best < 0) {
                return null;
            }
            byte[] reused = LARGE_POOL[best];
            LARGE_POOL[best] = null;
            pooledBytes -= reused.length;
            return reused;
        }
    }

    /** Retains a bounded large array for a later native allocation. */
    private static void releasePooled(byte[] block) {
        int length = block.length;
        if (length < LARGE_POOL_MIN_BYTES || length > LARGE_POOL_MAX_BYTES) {
            return;
        }
        synchronized (LARGE_POOL_LOCK) {
            if (pooledBytes + length > LARGE_POOL_LIMIT_BYTES) {
                return;
            }
            for (int i = 0; i < LARGE_POOL.length; i++) {
                if (LARGE_POOL[i] == null) {
                    LARGE_POOL[i] = block;
                    pooledBytes += length;
                    return;
                }
            }
        }
    }

    public static ByteBuffer wrapByte(long address, int capacity) {
        if (address == 0L || capacity <= 0) {
            ByteBuffer empty = ByteBuffer.allocate(0).order(ByteOrder.nativeOrder());
            register(empty, address);
            return empty;
        }
        Block block = block(address);
        int offset = (int) (address - block.base);
        int available = block.data.length - offset;
        int length = Math.min(capacity, Math.max(available, 0));
        if (length < capacity) {
            // Silent truncation turns an allocator fault into a wrong picture
            // or an exception many frames away: a caller that asked for a
            // 16x16 RGBA image and got 640 of the 1024 bytes fails inside
            // CommandEncoder.writeToTexture as "Copy would overrun the source
            // buffer", naming neither the address nor the allocation. Say so
            // here, where the block that was actually resolved is still known.
            reportTruncation(address, capacity, block, offset, available);
        }
        ByteBuffer buffer = ByteBuffer.wrap(block.data, offset, length)
                .slice()
                .order(ByteOrder.nativeOrder());
        register(buffer, address);
        return buffer;
    }

    private static int truncationReports;

    /**
     * A short view means the address did not resolve to the allocation the
     * caller believes it holds — a freed block, an interior address, or the
     * lazy scratch mapping in {@link #block}. Bounded so a repeating fault
     * cannot flood the marker ring (see the CDP console-pipe limit).
     */
    private static void reportTruncation(
            long address, int capacity, Block block, int offset, int available) {
        if (truncationReports >= 12) {
            return;
        }
        truncationReports++;
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "native:short-view addr=" + address
                            + " want=" + capacity
                            + " got=" + Math.max(available, 0)
                            + " blockBase=" + block.base
                            + " blockLen=" + block.data.length
                            + " offset=" + offset
                            + " exactBase=" + (offset == 0)
                            + " liveBlocks=" + BLOCKS.size());
        } catch (Throwable ignored) {
            // Diagnostics never break the allocation they describe.
        }
    }

    public static void register(Buffer buffer, long address) {
        if (buffer == null) {
            return;
        }
        synchronized (BUFFER_ADDRESSES) {
            BUFFER_ADDRESSES.put(buffer, address);
        }
    }

    /**
     * Add an existing managed byte array to the synthetic native address
     * space. Browser GPU mappings are backed by Java arrays rather than native
     * allocations, but Minecraft still writes their vertices through LWJGL's
     * {@code memPut*} address APIs. Registering the same array (without copying
     * it) makes those writes visible to the buffer's later WebGPU flush.
     */
    public static long registerExternal(byte[] storage) {
        if (storage == null) {
            throw new NullPointerException("storage");
        }
        long address = NEXT.getAndAdd(storage.length + 32L);
        BLOCKS.put(address, storage);
        noteAllocated(storage.length);
        return address;
    }

    /** Base address of the buffer's storage, independent of its position. */
    public static long addressOf(Buffer buffer) {
        if (buffer == null) {
            return 0L;
        }
        synchronized (BUFFER_ADDRESSES) {
            Long mapped = BUFFER_ADDRESSES.get(buffer);
            if (mapped != null) {
                return mapped;
            }
        }
        return 0L;
    }

    public static byte getByte(long address) {
        Block b = block(address);
        return b.data[(int) (address - b.base)];
    }

    public static void putByte(long address, byte value) {
        Block b = block(address);
        b.data[(int) (address - b.base)] = value;
    }

    public static short getShort(long address) {
        Block b = block(address);
        byte[] d = b.data;
        int o = (int) (address - b.base);
        return (short) ((d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8));
    }

    public static void putShort(long address, short value) {
        Block b = block(address);
        byte[] d = b.data;
        int o = (int) (address - b.base);
        d[o] = (byte) value;
        d[o + 1] = (byte) (value >>> 8);
    }

    public static int getInt(long address) {
        Block b = block(address);
        byte[] d = b.data;
        int o = (int) (address - b.base);
        return (d[o] & 0xFF)
                | ((d[o + 1] & 0xFF) << 8)
                | ((d[o + 2] & 0xFF) << 16)
                | ((d[o + 3] & 0xFF) << 24);
    }

    public static void putInt(long address, int value) {
        Block b = block(address);
        byte[] d = b.data;
        int o = (int) (address - b.base);
        d[o] = (byte) value;
        d[o + 1] = (byte) (value >>> 8);
        d[o + 2] = (byte) (value >>> 16);
        d[o + 3] = (byte) (value >>> 24);
    }

    public static long getLong(long address) {
        return (getInt(address) & 0xFFFF_FFFFL) | ((long) getInt(address + 4) << 32);
    }

    public static void putLong(long address, long value) {
        putInt(address, (int) value);
        putInt(address + 4, (int) (value >>> 32));
    }

    public static float getFloat(long address) {
        return Float.intBitsToFloat(getInt(address));
    }

    public static void putFloat(long address, float value) {
        putInt(address, Float.floatToRawIntBits(value));
    }

    public static double getDouble(long address) {
        return Double.longBitsToDouble(getLong(address));
    }

    public static void putDouble(long address, double value) {
        putLong(address, Double.doubleToRawLongBits(value));
    }

    public static long getAddress(long address) {
        return getLong(address);
    }

    public static void putAddress(long address, long value) {
        putLong(address, value);
    }

    /**
     * Fill, one {@link Arrays#fill} per block rather than one resolve per byte. These are
     * {@code memset}/{@code memcpy}: Minecraft's asset load moves megabytes through them,
     * and resolving per byte made each call linear in the number of live blocks *times*
     * its length.
     */
    public static void set(long address, int value, long bytes) {
        long remaining = bytes;
        long at = address;
        while (remaining > 0) {
            Block b = block(at);
            int offset = (int) (at - b.base);
            int run = (int) Math.min(remaining, b.data.length - offset);
            Arrays.fill(b.data, offset, offset + run, (byte) value);
            at += run;
            remaining -= run;
        }
    }

    /** Copy, one {@link System#arraycopy} per source/destination block pair. */
    public static void copy(long src, long dst, long bytes) {
        long remaining = bytes;
        long from = src;
        long to = dst;
        while (remaining > 0) {
            // Resolved separately and in this order every iteration: the cache holds one
            // block, so a straddling copy alternates, and the run length is bounded by
            // whichever side reaches its block end first.
            Block source = block(from);
            int sourceOffset = (int) (from - source.base);
            int sourceRun = source.data.length - sourceOffset;
            Block target = block(to);
            int targetOffset = (int) (to - target.base);
            int run = (int) Math.min(remaining, Math.min(sourceRun, target.data.length - targetOffset));
            System.arraycopy(source.data, sourceOffset, target.data, targetOffset, run);
            from += run;
            to += run;
            remaining -= run;
        }
    }

    private static int elementSize(Buffer buffer) {
        if (buffer instanceof java.nio.ByteBuffer) {
            return 1;
        }
        if (buffer instanceof java.nio.ShortBuffer || buffer instanceof java.nio.CharBuffer) {
            return 2;
        }
        if (buffer instanceof java.nio.IntBuffer || buffer instanceof java.nio.FloatBuffer) {
            return 4;
        }
        if (buffer instanceof java.nio.LongBuffer || buffer instanceof java.nio.DoubleBuffer) {
            return 8;
        }
        return 1;
    }

    private static int sanitize(long size) {
        if (size <= 0L) {
            return 1;
        }
        if (size > Integer.MAX_VALUE) {
            throw new OutOfMemoryError("browser allocation too large: " + size);
        }
        return (int) size;
    }

    /**
     * One-entry locality cache. A single immutable {@link Block} rather than a
     * {@code (base, data)} field pair: two volatile fields can be read across an
     * intervening update and yield one block's array with another block's base, which
     * silently reads or writes the wrong offset. Under the WasmLM thread agents that is
     * a live hazard, not a theoretical one.
     */
    private static volatile Block cache;

    /**
     * Block containing {@code address}, allocation-free on a cache hit.
     *
     * <p>Blocks never overlap - {@link #NEXT} hands out strictly increasing bases with a
     * gap - so "the block containing this address" is exactly "the highest base at or
     * below it", which is one {@link ConcurrentSkipListMap#floorEntry} rather than a walk
     * over every live block. That walk was the boot's dominant cost: {@link #copy} and
     * {@link #set} resolve per byte, and a copy between two blocks misses the one-entry
     * cache on every single byte, so the scan ran once per byte over hundreds of blocks.
     */
    private static Block block(long address) {
        Block hit = cache;
        if (hit != null && address >= hit.base && address < hit.base + hit.data.length) {
            return hit;
        }
        Map.Entry<Long, byte[]> entry = BLOCKS.floorEntry(address);
        if (entry != null) {
            long base = entry.getKey();
            byte[] data = entry.getValue();
            if (address < base + data.length) {
                Block found = new Block(base, data);
                cache = found;
                return found;
            }
        }
        // Lazy-map unmapped probes so field-offset discovery and temporary
        // addresses do not crash; grow a small scratch block.
        long base = address & ~0xFFFL;
        if (base <= 0L) {
            base = 0x1000L;
        }
        byte[] scratch = BLOCKS.computeIfAbsent(base, ignored -> new byte[4096]);
        if (address >= base + scratch.length) {
            throw new IllegalArgumentException("unmapped browser address: " + address);
        }
        Block found = new Block(base, scratch);
        cache = found;
        return found;
    }

    private record Block(long base, byte[] data) {
    }
}
