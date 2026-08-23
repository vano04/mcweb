package dev.mcweb.graal;

import dev.mcweb.graal.webgpu.BrowserGpu;
import java.nio.ByteBuffer;

/**
 * Names the buffer a texture upload is about to read.
 *
 * <p>{@code CommandEncoder.writeToTexture} validates that the source buffer
 * holds {@code width * height * bytesPerPixel} and throws "Copy would overrun
 * the source buffer" otherwise. That message gives the shortfall but not the
 * buffer: it cannot say whether the capacity is wrong (a bad allocation) or the
 * position is advanced (a shared buffer being consumed), and those have
 * opposite fixes. A transform in build.gradle calls this at the head of the
 * ByteBuffer overload.</p>
 */
public final class BrowserTextureUploadProbe {
    private static int reports;

    private BrowserTextureUploadProbe() {
    }

    public static void note(ByteBuffer source, int width, int height) {
        try {
            if (source == null) {
                return;
            }
            int needed = width * height * 4;
            boolean short_ = source.remaining() < needed;
            if (!short_ && reports >= 25) {
                return;
            }
            reports++;
            BrowserGpu.reportProgress((short_ ? "tex:upload-SHORT " : "tex:upload ")
                    + width + "x" + height
                    + " needs=" + needed
                    + " remaining=" + source.remaining()
                    + " capacity=" + source.capacity()
                    + " position=" + source.position()
                    + " limit=" + source.limit()
                    + " direct=" + source.isDirect()
                    + " hasArray=" + source.hasArray()
                    + " readOnly=" + source.isReadOnly());
        } catch (Throwable ignored) {
            // A probe must never change the upload it describes.
        }
    }
}
