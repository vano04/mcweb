package org.lwjgl.stb;

import org.lwjgl.system.BrowserNativeMemory;

/**
 * Browser STBImageResize: bilinear downscale/upscale over the synthetic
 * native address space. Used by NativeImage mipmap generation. Not
 * gamma-corrected — acceptable for power-of-two texture mipmaps.
 */
public final class STBImageResize {
    private STBImageResize() {
    }

    /**
     * Mirrors {@code nstbir_resize_uint8_linear}; returns the output pointer
     * on success, 0 on failure. A stride of 0 means tightly packed rows.
     */
    public static long nstbir_resize_uint8_linear(
            long inputPixels,
            int inputWidth,
            int inputHeight,
            int inputStrideInBytes,
            long outputPixels,
            int outputWidth,
            int outputHeight,
            int outputStrideInBytes,
            int numChannels
    ) {
        if (inputWidth <= 0 || inputHeight <= 0 || outputWidth <= 0 || outputHeight <= 0
                || numChannels <= 0 || inputPixels == 0L || outputPixels == 0L) {
            return 0L;
        }
        int inStride = inputStrideInBytes == 0 ? inputWidth * numChannels : inputStrideInBytes;
        int outStride = outputStrideInBytes == 0 ? outputWidth * numChannels : outputStrideInBytes;
        try {
            for (int y = 0; y < outputHeight; y++) {
                float sourceY = (y + 0.5f) * inputHeight / (float) outputHeight - 0.5f;
                int y0 = Math.max(0, (int) Math.floor(sourceY));
                int y1 = Math.min(inputHeight - 1, y0 + 1);
                float fy = Math.max(0.0f, Math.min(1.0f, sourceY - y0));
                long inRow0 = inputPixels + (long) y0 * inStride;
                long inRow1 = inputPixels + (long) y1 * inStride;
                long outRow = outputPixels + (long) y * outStride;
                for (int x = 0; x < outputWidth; x++) {
                    float sourceX = (x + 0.5f) * inputWidth / (float) outputWidth - 0.5f;
                    int x0 = Math.max(0, (int) Math.floor(sourceX));
                    int x1 = Math.min(inputWidth - 1, x0 + 1);
                    float fx = Math.max(0.0f, Math.min(1.0f, sourceX - x0));
                    int base00 = x0 * numChannels;
                    int base01 = x1 * numChannels;
                    for (int channel = 0; channel < numChannels; channel++) {
                        int c00 = BrowserNativeMemory.getByte(inRow0 + base00 + channel) & 0xFF;
                        int c01 = BrowserNativeMemory.getByte(inRow0 + base01 + channel) & 0xFF;
                        int c10 = BrowserNativeMemory.getByte(inRow1 + base00 + channel) & 0xFF;
                        int c11 = BrowserNativeMemory.getByte(inRow1 + base01 + channel) & 0xFF;
                        float top = c00 + (c01 - c00) * fx;
                        float bottom = c10 + (c11 - c10) * fx;
                        int value = Math.round(top + (bottom - top) * fy);
                        BrowserNativeMemory.putByte(
                                outRow + (long) x * numChannels + channel,
                                (byte) Math.max(0, Math.min(255, value))
                        );
                    }
                }
            }
            return outputPixels;
        } catch (RuntimeException failure) {
            return 0L;
        }
    }
}
