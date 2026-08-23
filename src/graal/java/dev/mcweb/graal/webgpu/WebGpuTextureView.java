package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

final class WebGpuTextureView extends GpuTextureView {
    /** GpuTexture.USAGE_CUBEMAP_COMPATIBLE; cube textures get cube views. */
    private static final int USAGE_CUBEMAP_COMPATIBLE = 16;

    private final int handle;
    private boolean closed;

    WebGpuTextureView(GpuTexture texture, int baseMipLevel, int mipLevels) {
        super(texture, baseMipLevel, mipLevels);
        WebGpuTexture webTexture = (WebGpuTexture) texture;
        String dimension = (texture.usage() & USAGE_CUBEMAP_COMPATIBLE) != 0 ? "cube" : "2d";
        this.handle = BrowserGpu.createTextureView(
                webTexture.handle(),
                baseMipLevel,
                mipLevels,
                dimension
        );
    }

    int handle() {
        return handle;
    }

    WebGpuTexture webGpuTexture() {
        return (WebGpuTexture) texture();
    }

    @Override
    public void close() {
        if (!closed) {
            BrowserGpu.destroyObject(handle);
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }
}
