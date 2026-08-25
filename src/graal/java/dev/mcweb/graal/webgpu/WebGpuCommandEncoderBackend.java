package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.GpuFence;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.joml.Vector4fc;

final class WebGpuCommandEncoderBackend implements CommandEncoderBackend {
    private final WebGpuDeviceBackend device;
    private int handle;
    private int activePass;
    private WebGpuRenderPass activeRenderPass;

    WebGpuCommandEncoderBackend(WebGpuDeviceBackend device) {
        this.device = device;
    }

    int handle() {
        if (handle == 0) {
            handle = BrowserGpu.createCommandEncoder();
        }
        return handle;
    }

    private UnsupportedOperationException unsupported(String operation) {
        return new UnsupportedOperationException(operation + " is not ported to WebGPU yet");
    }

    @Override
    public void submit() {
        if (handle != 0) {
            BrowserGpu.submit(handle);
            handle = 0;
            device.webTransientMemory().resetAfterSubmit();
        }
    }

    @Override
    public TransientMemory transientMemory() {
        return device.webTransientMemory();
    }

    @Override
    public RenderPassBackend createRenderPass(RenderPassDescriptor descriptor) {
        if (activePass != 0) {
            throw new IllegalStateException("a render pass is already active");
        }
        StringBuilder spec = new StringBuilder(256);
        spec.append("{\"color\":[");
        int passHeight = 0;
        List<RenderPassDescriptor.Attachment<Optional<Vector4fc>>> colorAttachments =
                descriptor.colorAttachments();
        boolean first = true;
        for (RenderPassDescriptor.Attachment<Optional<Vector4fc>> attachment : colorAttachments) {
            if (!first) {
                spec.append(",");
            }
            first = false;
            GpuTextureView view = attachment.textureView();
            if (view == null) {
                spec.append("null");
                continue;
            }
            WebGpuTexture texture = ((WebGpuTextureView) view).webGpuTexture();
            if (passHeight == 0) {
                passHeight = texture.getHeight(0);
            }
            spec.append("{\"view\":").append(((WebGpuTextureView) view).handle());
            Optional<Vector4fc> clear = attachment.clearValue();
            if (clear.isPresent()) {
                Vector4fc color = clear.get();
                spec.append(",\"clear\":[").append(color.x()).append(",").append(color.y())
                        .append(",").append(color.z()).append(",").append(color.w()).append("]");
            }
            spec.append("}");
        }
        spec.append("]");

        RenderPassDescriptor.Attachment<OptionalDouble> depthAttachment = descriptor.depthAttachment();
        if (depthAttachment != null && depthAttachment.textureView() != null) {
            WebGpuTexture depthTexture = ((WebGpuTextureView) depthAttachment.textureView()).webGpuTexture();
            if (passHeight == 0) {
                passHeight = depthTexture.getHeight(0);
            }
            spec.append(",\"depth\":{\"view\":").append(((WebGpuTextureView) depthAttachment.textureView()).handle());
            OptionalDouble depthClear = depthAttachment.clearValue();
            if (depthClear.isPresent()) {
                spec.append(",\"clear\":").append(depthClear.getAsDouble());
            }
            spec.append("}");
        }

        RenderPass.RenderArea area = descriptor.renderArea;
        if (area != null) {
            spec.append(",\"area\":[").append(area.x()).append(",").append(area.y())
                    .append(",").append(area.width()).append(",").append(area.height()).append("]");
        }
        spec.append(",\"height\":").append(passHeight);
        spec.append("}");

        activePass = BrowserGpu.beginRenderPass(handle(), spec.toString());
        activeRenderPass = new WebGpuRenderPass(activePass, device);
        return activeRenderPass;
    }

    @Override
    public void submitRenderPass() {
        if (activePass != 0) {
            try {
                if (activeRenderPass != null) {
                    activeRenderPass.end();
                } else {
                    BrowserGpu.rpEnd(activePass);
                }
            } finally {
                // A failed host replay has already aborted the pass. Do not
                // retain and retry that handle on the next encoder operation.
                activePass = 0;
                activeRenderPass = null;
            }
        }
    }

    @Override
    public void clearColorTexture(GpuTexture texture, Vector4fc color) {
        WebGpuTexture webTexture = (WebGpuTexture) texture;
        BrowserGpu.clearColorTexture(
                handle(),
                webTexture.handle(),
                color.x(),
                color.y(),
                color.z(),
                color.w()
        );
    }

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture,
            Vector4fc color,
            GpuTexture depthTexture,
            double depth
    ) {
        BrowserGpu.clearColorAndDepth(
                handle(),
                ((WebGpuTexture) colorTexture).handle(),
                color.x(),
                color.y(),
                color.z(),
                color.w(),
                ((WebGpuTexture) depthTexture).handle(),
                depth
        );
    }

    @Override
    public void clearColorAndDepthTextures(
            GpuTexture colorTexture,
            Vector4fc color,
            GpuTexture depthTexture,
            double depth,
            int x,
            int y,
            int width,
            int height
    ) {
        BrowserGpu.clearColorAndDepthRegion(
                handle(),
                ((WebGpuTexture) colorTexture).handle(),
                color.x(),
                color.y(),
                color.z(),
                color.w(),
                ((WebGpuTexture) depthTexture).handle(),
                depth,
                x,
                y,
                width,
                height
        );
    }

    @Override
    public void clearDepthTexture(GpuTexture texture, double depth) {
        BrowserGpu.clearDepth(handle(), ((WebGpuTexture) texture).handle(), depth);
    }

    @Override
    public void writeToBuffer(GpuBufferSlice destination, ByteBuffer data) {
        ((WebGpuBuffer) destination.buffer()).upload(destination.offset(), data);
    }

    @Override
    public void copyToBuffer(GpuBufferSlice source, GpuBufferSlice destination) {
        long size = Math.min(source.length(), destination.length());
        if (size == 0) {
            return;
        }
        WebGpuBuffer destinationBuffer = (WebGpuBuffer) destination.buffer();
        // The copy executes on the GPU and bypasses the destination's Java
        // mapping shadow. Invalidate it before recording the command so a
        // later identical-looking CPU write can never be skipped against stale
        // bytes, even if command recording itself throws partway through.
        destinationBuffer.invalidateShadow();
        BrowserGpu.copyBuffer(
                handle(),
                ((WebGpuBuffer) source.buffer()).handle(),
                (int) source.offset(),
                destinationBuffer.handle(),
                (int) destination.offset(),
                (int) size
        );
    }

    @Override
    public void writeToTexture(
            GpuTexture texture,
            ByteBuffer data,
            int mipLevel,
            int depthOrLayer,
            int x,
            int y,
            int width,
            int height
    ) {
        WebGpuTexture webTexture = (WebGpuTexture) texture;
        // DIAG (one-shot per label): does the atlas/gui get filled via writeToTexture?
        {
            String tl = webTexture.getLabel();
            if (tl != null && (tl.contains("atlas") || tl.contains("gui") || tl.contains("widget")
                    || tl.contains("font") || tl.contains("panorama") || tl.contains("cubemap")
                    || tl.contains("title"))
                    && !WebGpuDeviceBackend.diagWriteTexLabels.contains(tl)) {
                WebGpuDeviceBackend.diagWriteTexLabels.add(tl);
                System.out.println("[ATLAS-UPLOAD] writeToTexture label=" + tl + " " + width + "x" + height + " mip=" + mipLevel + " layer=" + depthOrLayer);
            }
        }
        int bytesPerRow = width * texture.getFormat().blockSize();
        int length = bytesPerRow * height;
        if (length <= 0) {
            throw new IllegalArgumentException("Texture write region is empty");
        }
        if (data.hasArray()) {
            BrowserGpu.writeTexture(
                    webTexture.handle(),
                    data.array(),
                    data.arrayOffset() + data.position(),
                    length,
                    mipLevel,
                    depthOrLayer,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    height
            );
            return;
        }
        byte[] bytes = BrowserGpu.allocate(length, "writeToTexture:" + width + "x" + height);
        data.duplicate().get(bytes, 0, length);
        BrowserGpu.writeTexture(
                webTexture.handle(),
                bytes,
                0,
                length,
                mipLevel,
                depthOrLayer,
                x,
                y,
                width,
                height,
                bytesPerRow,
                height
        );
    }

    @Override
    public void copyBufferToTexture(
            GpuBufferSlice source,
            int sourceX,
            int sourceY,
            int sourceWidth,
            int sourceHeight,
            GpuTexture destination,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int mipLevel,
            int destinationLayer
    ) {
        // Staging buffers are CPU-shadowed in the browser, so the "copy" is
        // served from the shadow and queued as a writeTexture upload.
        WebGpuBuffer sourceBuffer = (WebGpuBuffer) source.buffer();
        // DIAG (one-shot per dest label): which path fills the atlas, and is the
        // staging shadow populated? Remove once the black-atlas bug is fixed.
        {
            String dl = destination.getLabel();
            if (dl != null && (dl.contains("atlas") || dl.contains("font") || dl.contains("panorama")
                    || dl.contains("cubemap") || dl.contains("gui") || dl.contains("widget")
                    || dl.contains("default/"))
                    && !WebGpuDeviceBackend.diagCopyTexLabels.contains(dl)) {
                WebGpuDeviceBackend.diagCopyTexLabels.add(dl);
                byte[] sh = sourceBuffer.peekShadow();
                int nz = 0;
                if (sh != null) { for (int i = 0; i < Math.min(sh.length, 4096); i++) if (sh[i] != 0) { nz++; } }
                System.out.println("[ATLAS-UPLOAD] copyBufferToTexture dest=" + dl + " " + width + "x" + height
                        + " mip=" + mipLevel + " shadow=" + (sh == null ? "NULL" : sh.length) + " nonzeroInFirst4K=" + nz
                        + " src=" + sourceX + "," + sourceY + " in " + sourceWidth + "x" + sourceHeight
                        + " srcOff=" + source.offset() + " srcBufSize=" + sourceBuffer.physicalSizeForDiag());
            }
        }
        int blockSize = destination.getFormat().blockSize();
        int tightRowBytes = width * blockSize;
        int sourceRowBytes = sourceWidth * blockSize;
        if (tightRowBytes <= 0 || height <= 0) {
            return;
        }
        long dataOffset = source.offset()
                + ((long) sourceY * sourceWidth + sourceX) * blockSize;
        int uploadLength = tightRowBytes * height;
        byte[] shadow = sourceBuffer.coherentShadow();
        if (sourceRowBytes == tightRowBytes && shadow != null
                && dataOffset >= 0 && dataOffset <= shadow.length - uploadLength) {
            BrowserGpu.writeTexture(
                    ((WebGpuTexture) destination).handle(),
                    shadow,
                    (int) dataOffset,
                    uploadLength,
                    mipLevel,
                    destinationLayer,
                    destinationX,
                    destinationY,
                    width,
                    height,
                    tightRowBytes,
                    height
            );
            return;
        }
        byte[] bytes;
        if (sourceRowBytes == tightRowBytes) {
            bytes = sourceBuffer.readBytes(dataOffset, uploadLength);
        } else {
            // Repack padded source rows into a tightly packed upload.
            bytes = BrowserGpu.allocate(uploadLength, "copyBufferToTexture:" + width + "x" + height);
            for (int row = 0; row < height; row++) {
                byte[] sourceRow = sourceBuffer.readBytes(
                        dataOffset + (long) row * sourceRowBytes,
                        tightRowBytes
                );
                System.arraycopy(sourceRow, 0, bytes, row * tightRowBytes, tightRowBytes);
            }
        }
        BrowserGpu.writeTexture(
                ((WebGpuTexture) destination).handle(),
                bytes,
                0,
                bytes.length,
                mipLevel,
                destinationLayer,
                destinationX,
                destinationY,
                width,
                height,
                tightRowBytes,
                height
        );
    }

    /**
     * GPU→CPU texture readback, which WebGPU can only do asynchronously
     * ({@code copyTextureToBuffer} then {@code buffer.mapAsync}) while
     * Minecraft reads the destination synchronously afterwards.
     *
     * <p>Throwing here used to kill the render frame outright — it is what
     * crashed the game seconds after the player finally spawned. Every caller
     * in the client JAR is a capture or debug path ({@code TracyFrameCapture},
     * {@code TextureUtil}, {@code Screenshot}), never gameplay, so leaving the
     * destination buffer untouched and completing the callback degrades those
     * features (a black screenshot) instead of taking the game down.</p>
     *
     * <p>Reported once so it stays visible rather than becoming silent
     * breakage; implementing it properly means an async map plus somewhere for
     * the result to land.</p>
     */
    @Override
    public void copyTextureToBuffer(
            GpuTexture source,
            GpuBuffer destination,
            long destinationOffset,
            Runnable callback,
            int mipLevel
    ) {
        noteUnsupportedReadback("Texture readback");
        if (callback != null) {
            callback.run();
        }
    }

    @Override
    public void copyTextureToBuffer(
            GpuTexture source,
            GpuBuffer destination,
            long destinationOffset,
            Runnable callback,
            int mipLevel,
            int x,
            int y,
            int width,
            int height
    ) {
        noteUnsupportedReadback("Texture region readback");
        if (callback != null) {
            callback.run();
        }
    }

    private static boolean reportedReadback;

    private static void noteUnsupportedReadback(String operation) {
        if (reportedReadback) {
            return;
        }
        reportedReadback = true;
        try {
            BrowserGpu.reportProgress(
                    "webgpu:unsupported " + operation + " (capture/debug only; frame continues)"
            );
        } catch (Throwable ignored) {
            // Diagnostics must never be the thing that breaks a frame.
        }
    }

    @Override
    public void copyTextureToTexture(
            GpuTexture source,
            GpuTexture destination,
            int sourceX,
            int sourceY,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int mipLevel
    ) {
        BrowserGpu.copyTexture(
                handle(),
                ((WebGpuTexture) source).handle(),
                ((WebGpuTexture) destination).handle(),
                sourceX,
                sourceY,
                destinationX,
                destinationY,
                width,
                height,
                mipLevel
        );
    }

    @Override
    public GpuFence createFence() {
        return new WebGpuFence();
    }

    @Override
    public void writeTimestamp(GpuQueryPool queryPool, int index) {
        ((WebGpuQueryPool) queryPool).write(index, System.nanoTime());
    }
}
