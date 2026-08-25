package dev.mcweb.graal;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Browser replacement for {@code MipmapGenerator.generateMipLevels} (injected
 * by the jar transform). The original body is an interpreted per-pixel,
 * multi-pass alpha-coverage filter that takes minutes per atlas in the wasm
 * runtime; this is a plain 2x2 box filter — cheap, and produces correctly
 * sized mip levels, which {@code NativeImage.upload} bounds-checks (a stub
 * that returns only the base image dies with "Dest texture (16x16) is not
 * large enough ... (at mip level 1)"). Menu rendering never samples upper
 * mips; world rendering gets slightly softer mips.
 */
public final class MipmapCompat {
    private MipmapCompat() {
    }

    public static NativeImage[] generate(NativeImage[] images, int mipLevel) {
        int levels = Math.max(0, mipLevel);
        NativeImage[] result = new NativeImage[levels + 1];
        NativeImage base = images[0];
        result[0] = base;
        NativeImage previous = base;
        for (int level = 1; level <= levels; level++) {
            int width = Math.max(1, previous.getWidth() >> 1);
            int height = Math.max(1, previous.getHeight() >> 1);
            NativeImage next = new NativeImage(previous.format(), width, height, false);
            int sourceWidth = previous.getWidth();
            int sourceHeight = previous.getHeight();
            for (int y = 0; y < height; y++) {
                int sourceY = y * 2;
                int sourceY1 = Math.min(sourceY + 1, sourceHeight - 1);
                for (int x = 0; x < width; x++) {
                    int sourceX = x * 2;
                    int sourceX1 = Math.min(sourceX + 1, sourceWidth - 1);
                    next.setPixel(x, y, average(
                            previous.getPixel(sourceX, sourceY),
                            previous.getPixel(sourceX1, sourceY),
                            previous.getPixel(sourceX, sourceY1),
                            previous.getPixel(sourceX1, sourceY1)
                    ));
                }
            }
            result[level] = next;
            previous = next;
        }
        return result;
    }

    /** Average each byte lane of four packed pixels without carry between lanes. */
    private static int average(int a, int b, int c, int d) {
        int mask = 0x00FF00FF;
        int low = (((a & mask) + (b & mask) + (c & mask) + (d & mask)) >> 2) & mask;
        int high = ((((a >>> 8) & mask) + ((b >>> 8) & mask) + ((c >>> 8) & mask) + ((d >>> 8) & mask)) >> 2) & mask;
        return low | (high << 8);
    }
}
