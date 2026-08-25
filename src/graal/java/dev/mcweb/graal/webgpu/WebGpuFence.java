package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.buffers.GpuFence;

final class WebGpuFence implements GpuFence {
    @Override
    public void close() {
    }

    @Override
    public boolean awaitCompletion(long timeoutNanos) {
        return true;
    }
}
