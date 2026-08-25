package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.SurfaceException;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Collection;
import java.util.List;

final class WebGpuSurfaceBackend implements GpuSurfaceBackend {
    private GpuSurface.Configuration configuration;
    private int canvasTextureHandle;

    @Override
    public void configure(GpuSurface.Configuration configuration) throws SurfaceException {
        this.configuration = configuration;
        BrowserGpu.configureCanvas(configuration.width(), configuration.height());
    }

    @Override
    public boolean isSuboptimal() {
        return false;
    }

    @Override
    public void acquireNextTexture() throws SurfaceException {
        if (configuration == null) {
            throw new SurfaceException("WebGPU surface is not configured");
        }
        canvasTextureHandle = BrowserGpu.acquireCanvasTexture();
    }

    @Override
    public void blitFromTexture(CommandEncoderBackend encoder, GpuTextureView textureView) {
        if (canvasTextureHandle == 0) {
            throw new IllegalStateException("No canvas texture has been acquired");
        }
        WebGpuCommandEncoderBackend webEncoder = (WebGpuCommandEncoderBackend) encoder;
        WebGpuTexture source = ((WebGpuTextureView) textureView).webGpuTexture();
        BrowserGpu.copyTexture(
                webEncoder.handle(),
                source.handle(),
                canvasTextureHandle,
                0,
                0,
                0,
                0,
                configuration.width(),
                configuration.height(),
                0
        );
    }

    @Override
    public void present() {
        BrowserGpu.present(canvasTextureHandle);
        canvasTextureHandle = 0;
    }

    @Override
    public void close() {
        canvasTextureHandle = 0;
    }

    @Override
    public Collection<GpuSurface.PresentMode> supportedPresentModes() {
        return List.of(GpuSurface.PresentMode.FIFO, GpuSurface.PresentMode.IMMEDIATE);
    }
}
