package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Browser transient memory. The browser host cannot share synchronous GPU
 * mappings, so CPU-visible scratch is heap-backed and GPU-visible scratch is
 * COPY_DST buffers flushed through queue.writeBuffer on unmap, matching
 * Mojang's MappableRingBuffer replacement pattern from the port boundary.
 */
final class WebGpuTransientMemory implements TransientMemory {
    /**
     * CPU-only transient allocations are valid until the command encoder submit
     * that consumes them. Reusing a small arena keeps each upload from creating
     * another large Java byte[] in the Web Image heap. GPU resources are
     * intentionally not destroyed at every submit: the host graveyard is safe,
     * but per-submit destruction was measured as a severe frame/chunk regression.
     */
    private final CpuArena cpuArena = new CpuArena();

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long offset, long padding) {
        return cpuArena.allocate(size, alignment);
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
            long size,
            long alignment,
            int usage,
            long offset,
            long padding
    ) {
        WebGpuBuffer buffer = new WebGpuBuffer(
                "transient staging",
                mappableUsage(usage),
                allocationSize(size)
        );
        return buffer.map(0L, size, false, true);
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long offset, long padding) {
        // Native allocateGpu is device-local and never exposes a mapped CPU
        // view. COPY_DST is the browser replacement for the later writeToBuffer
        // operation, while skipping the shadow avoids mirroring transient GPU
        // allocations in the Java heap.
        WebGpuBuffer buffer = new WebGpuBuffer(
                "transient gpu",
                usage | GpuBuffer.USAGE_COPY_DST,
                allocationSize(size),
                false
        );
        return buffer.slice(0L, size);
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
            long size,
            long alignment,
            int usage,
            long offset,
            long padding
    ) {
        WebGpuBuffer buffer = new WebGpuBuffer(
                "transient gpu mapped",
                mappableUsage(usage),
                allocationSize(size)
        );
        return buffer.map(0L, size, false, true);
    }

    @Override
    public GpuBufferSlice uploadStaging(ByteBuffer data, long alignment, int usage, long offset, long padding) {
        return upload(data, usage, offset, padding, true);
    }

    @Override
    public GpuBufferSlice uploadGpu(ByteBuffer data, long alignment, int usage, long offset, long padding) {
        return upload(data, usage, offset, padding, false);
    }

    @Override
    public GpuBufferSlice uploadStaging(List<ByteBuffer> dataParts, long alignment, int usage, long offset, long padding) {
        return upload(gather(dataParts), usage, offset, padding, true);
    }

    @Override
    public GpuBufferSlice uploadGpu(List<ByteBuffer> dataParts, long alignment, int usage, long offset, long padding) {
        return upload(gather(dataParts), usage, offset, padding, false);
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> dataParts, long alignment, int usage) {
        return multiUpload(dataParts, usage, true);
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> dataParts, long alignment, int usage) {
        return multiUpload(dataParts, usage, false);
    }

    private GpuBufferSlice upload(
            ByteBuffer data,
            int usage,
            long offset,
            long padding,
            boolean staging
    ) {
        ByteBuffer source = data.duplicate();
        // uploadStaging needs a CPU shadow because copyBufferToTexture is a
        // synchronous browser seam. uploadGpu is device-local by contract and
        // can stream the source directly through the bounded WasmGC upload bridge.
        int bufferUsage = staging
                ? mappableUsage(usage)
                : usage | GpuBuffer.USAGE_COPY_DST;
        WebGpuBuffer buffer = new WebGpuBuffer(
                "transient upload",
                bufferUsage,
                source.remaining(),
                staging
        );
        buffer.upload(0L, source);
        long size = buffer.size();
        return new GpuBufferSlice(buffer, 0L, size);
    }

    private List<GpuBufferSlice> multiUpload(
            List<ByteBuffer> dataParts,
            int usage,
            boolean staging
    ) {
        List<GpuBufferSlice> slices = new ArrayList<>(dataParts.size());
        for (ByteBuffer part : dataParts) {
            slices.add(upload(part, usage, 0L, 0L, staging));
        }
        return slices;
    }

    /** Resets CPU scratch after its command encoder has been submitted. */
    void resetAfterSubmit() {
        cpuArena.reset();
    }

    private static ByteBuffer gather(List<ByteBuffer> dataParts) {
        int total = 0;
        for (ByteBuffer part : dataParts) {
            total += part.remaining();
        }
        byte[] gathered = BrowserGpu.allocate(total, "transientGather");
        int position = 0;
        for (ByteBuffer part : dataParts) {
            ByteBuffer source = part.duplicate();
            int remaining = source.remaining();
            source.get(gathered, position, remaining);
            position += remaining;
        }
        return ByteBuffer.wrap(gathered);
    }

    private static int mappableUsage(int usage) {
        // WebGPU forbids MAP_WRITE combined with UNIFORM/VERTEX/INDEX; the
        // browser buffer keeps a CPU shadow and flushes through COPY_DST.
        // MAP_WRITE also keeps uploads shadow-routed, which tolerates
        // non-four-byte-aligned data that writeBuffer64 would reject.
        return usage | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_MAP_WRITE;
    }

    /**
     * The extra TransientMemory arguments are block-allocator rollover hints,
     * not bytes to prepend to the returned slice. Native backends normally
     * allocate the requested size at an allocator-chosen offset (zero for a
     * fresh browser buffer); adding the hint again doubled every small
     * transient allocation in this single-buffer implementation.
     */
    private static long allocationSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE - 3L) {
            throw new IllegalArgumentException("Transient allocation is out of range: " + size);
        }
        return Math.max(1L, size);
    }

    /**
     * A submit-scoped byte arena for the CPU side of TransientMemory. The first
     * block is deliberately modest; a larger request gets one appropriately sized
     * block which is then retained and reused on later submits.
     */
    private static final class CpuArena {
        private static final int BLOCK_BYTES = 4 * 1024 * 1024;
        private final List<byte[]> blocks = new ArrayList<>();
        private int blockIndex;
        private int cursor;

        ByteBuffer allocate(long size, long alignment) {
            int bytes = (int) allocationSize(size);
            int align = checkedAlignment(alignment);
            long minimumLong = (long) bytes + align - 1L;
            if (minimumLong > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Transient allocation alignment overflows: " + size);
            }
            int minimum = (int) minimumLong;
            for (;;) {
                byte[] block = block(blockIndex, minimum);
                int start = alignUp(cursor, align);
                if (start <= block.length - bytes) {
                    cursor = start + bytes;
                    // Native transient memory is scratch, not zero-initialized storage;
                    // callers fill the returned range before the GPU consumes it.
                    return ByteBuffer.wrap(block, start, bytes).slice();
                }
                blockIndex++;
                cursor = 0;
            }
        }

        void reset() {
            blockIndex = 0;
            cursor = 0;
        }

        private byte[] block(int index, int minimum) {
            while (blocks.size() <= index) {
                int capacity = Math.max(BLOCK_BYTES, minimum);
                blocks.add(BrowserGpu.allocate(capacity, "transientCpuArena"));
            }
            byte[] block = blocks.get(index);
            if (block.length >= minimum) {
                return block;
            }
            byte[] replacement = BrowserGpu.allocate(minimum, "transientCpuArenaGrow");
            blocks.set(index, replacement);
            return replacement;
        }

        private static int checkedAlignment(long alignment) {
            if (alignment <= 1L) {
                return 1;
            }
            if (alignment > Integer.MAX_VALUE || (alignment & (alignment - 1L)) != 0L) {
                throw new IllegalArgumentException("Transient alignment is out of range: " + alignment);
            }
            return (int) alignment;
        }

        private static int alignUp(int value, int alignment) {
            int mask = alignment - 1;
            if (value > Integer.MAX_VALUE - mask) {
                return Integer.MAX_VALUE;
            }
            return (value + mask) & ~mask;
        }
    }
}
