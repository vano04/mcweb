package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;

final class WebGpuTexture extends GpuTexture {
    private final int handle;
    private boolean closed;

    WebGpuTexture(
            int usage,
            String label,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels
    ) {
        super(usage, label, format, width, height, depthOrLayers, mipLevels);
        this.handle = BrowserGpu.createTexture(
                label,
                usage,
                format.name(),
                width,
                height,
                depthOrLayers,
                mipLevels
        );
    }

    int handle() {
        if (closed) {
            throw new IllegalStateException("Texture is closed");
        }
        return handle;
    }

    @Override
    public void close() {
        if (!closed) {
            BrowserGpu.destroyTexture(handle);
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
