package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.TransientMemory;
import java.nio.ByteBuffer;
import java.util.List;

final class UnsupportedTransientMemory implements TransientMemory {
    static final UnsupportedTransientMemory INSTANCE = new UnsupportedTransientMemory();

    private UnsupportedTransientMemory() {
    }

    private UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Transient WebGPU allocation is not ported yet");
    }

    @Override
    public ByteBuffer allocateCpu(long size, long alignment, long first, long second) {
        throw unsupported();
    }

    @Override
    public GpuBufferSlice.MappedView allocateStaging(
            long size,
            long alignment,
            int usage,
            long first,
            long second
    ) {
        throw unsupported();
    }

    @Override
    public GpuBufferSlice allocateGpu(long size, long alignment, int usage, long first, long second) {
        throw unsupported();
    }

    @Override
    public GpuBufferSlice.MappedView allocateGpuMapped(
            long size,
            long alignment,
            int usage,
            long first,
            long second
    ) {
        throw unsupported();
    }

    @Override
    public GpuBufferSlice uploadStaging(
            List<ByteBuffer> data,
            long alignment,
            int usage,
            long first,
            long second
    ) {
        throw unsupported();
    }

    @Override
    public GpuBufferSlice uploadGpu(
            List<ByteBuffer> data,
            long alignment,
            int usage,
            long first,
            long second
    ) {
        throw unsupported();
    }

    @Override
    public List<GpuBufferSlice> multiUploadStaging(List<ByteBuffer> data, long alignment, int usage) {
        throw unsupported();
    }

    @Override
    public List<GpuBufferSlice> multiUploadGpu(List<ByteBuffer> data, long alignment, int usage) {
        throw unsupported();
    }
}
