package dev.mcweb.graal;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.DisplayData;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuSurface;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import dev.mcweb.graal.webgpu.BrowserGpu;
import dev.mcweb.graal.webgpu.WebGpuDeviceBackend;
import java.util.OptionalInt;
import org.joml.Vector4f;

/**
 * First graphical port checkpoint through Minecraft's real GpuDevice facade.
 */
public final class GraalMinecraftGpuProbe {
    private GraalMinecraftGpuProbe() {
    }

    public static void main(String[] args) throws Exception {
        DisplayData display = new DisplayData(
                BrowserGpu.canvasWidth(),
                BrowserGpu.canvasHeight(),
                OptionalInt.of(89),
                OptionalInt.of(166),
                false
        );

        int argb = 0xFF59A64D;
        float red = ((argb >>> 16) & 0xFF) / 255.0F;
        float green = ((argb >>> 8) & 0xFF) / 255.0F;
        float blue = (argb & 0xFF) / 255.0F;

        WebGpuDeviceBackend backend = new WebGpuDeviceBackend();
        GpuDevice device = new GpuDevice(backend, () -> {
        });
        try (GpuSurface surface = device.createSurface(1L);
             GpuTexture texture = device.createTexture(
                     "Minecraft 26.2 main target",
                     GpuTexture.USAGE_COPY_DST
                             | GpuTexture.USAGE_COPY_SRC
                             | GpuTexture.USAGE_RENDER_ATTACHMENT,
                     GpuFormat.RGBA8_UNORM,
                     display.width(),
                     display.height(),
                     1,
                     1
             );
             GpuTextureView view = device.createTextureView(texture)) {
            surface.configure(new GpuSurface.Configuration(
                    display.width(),
                    display.height(),
                    GpuSurface.PresentMode.FIFO
            ));

            CommandEncoder encoder = device.createCommandEncoder();
            encoder.clearColorTexture(texture, new Vector4f(red, green, blue, 1.0F));
            surface.acquireNextTexture();
            surface.blitFromTexture(encoder, view);
            encoder.submit();
            surface.present();

            BrowserGpu.reportSuccess(
                    argb,
                    device.getDeviceInfo().backendName(),
                    "Minecraft GpuDevice → CommandEncoder → WebGPU canvas"
            );
        } finally {
            device.close();
        }
    }
}
