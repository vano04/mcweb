package org.lwjgl.stb;

import dev.mcweb.graal.stb.PngDecoder;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.system.BrowserNativeMemory;
import org.lwjgl.system.MemoryUtil;

/**
 * Browser STBImage: decodes PNGs in pure Java (Minecraft's JAR contains only
 * PNG image assets) and hands the pixels out through the synthetic native
 * address space, preserving STB's allocate/free contract.
 */
public final class STBImage {
    private static final String NOT_PNG = "browser STBImage decodes PNG only";

    private static String failureReason = "";

    private STBImage() {
    }

    public static ByteBuffer stbi_load_from_memory(
            ByteBuffer buffer,
            IntBuffer width,
            IntBuffer height,
            IntBuffer components,
            int desiredChannels
    ) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        try {
            PngDecoder.Decoded decoded = PngDecoder.decode(data, desiredChannels);
            if (width != null) {
                width.put(0, decoded.width());
            }
            if (height != null) {
                height.put(0, decoded.height());
            }
            if (components != null) {
                components.put(0, decoded.comp());
            }
            byte[] pixels = decoded.pixels();
            long address = BrowserNativeMemory.malloc(pixels.length);
            ByteBuffer result = MemoryUtil.memByteBuffer(address, pixels.length);
            result.put(pixels);
            result.rewind();
            failureReason = "";
            return result;
        } catch (Exception failure) {
            failureReason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return null;
        }
    }

    public static ByteBuffer stbi_load_from_memory(
            ByteBuffer buffer,
            int[] width,
            int[] height,
            int[] components,
            int desiredChannels
    ) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        try {
            PngDecoder.Decoded decoded = PngDecoder.decode(data, desiredChannels);
            if (width != null) {
                width[0] = decoded.width();
            }
            if (height != null) {
                height[0] = decoded.height();
            }
            if (components != null) {
                components[0] = decoded.comp();
            }
            byte[] pixels = decoded.pixels();
            long address = BrowserNativeMemory.malloc(pixels.length);
            ByteBuffer result = MemoryUtil.memByteBuffer(address, pixels.length);
            result.put(pixels);
            result.rewind();
            failureReason = "";
            return result;
        } catch (Exception failure) {
            failureReason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return null;
        }
    }

    public static boolean stbi_info_from_memory(
            ByteBuffer buffer,
            IntBuffer width,
            IntBuffer height,
            IntBuffer components
    ) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        try {
            PngDecoder.Decoded decoded = PngDecoder.info(data);
            if (width != null) {
                width.put(0, decoded.width());
            }
            if (height != null) {
                height.put(0, decoded.height());
            }
            if (components != null) {
                components.put(0, decoded.comp());
            }
            return true;
        } catch (Exception failure) {
            failureReason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return false;
        }
    }

    public static boolean stbi_info_from_memory(ByteBuffer buffer, int[] width, int[] height, int[] components) {
        byte[] data = new byte[buffer.remaining()];
        buffer.duplicate().get(data);
        try {
            PngDecoder.Decoded decoded = PngDecoder.info(data);
            if (width != null) {
                width[0] = decoded.width();
            }
            if (height != null) {
                height[0] = decoded.height();
            }
            if (components != null) {
                components[0] = decoded.comp();
            }
            return true;
        } catch (Exception failure) {
            failureReason = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return false;
        }
    }

    public static boolean stbi_is_hdr_from_memory(ByteBuffer buffer) {
        return false;
    }

    public static String stbi_failure_reason() {
        return failureReason;
    }

    public static void stbi_image_free(ByteBuffer pixelData) {
        if (pixelData != null) {
            nstbi_image_free(MemoryUtil.memAddress0(pixelData));
        }
    }

    /** Direct native entry referenced by NativeImage's pixel disposal. */
    public static void nstbi_image_free(long pixelData) {
        BrowserNativeMemory.free(pixelData);
    }
}
