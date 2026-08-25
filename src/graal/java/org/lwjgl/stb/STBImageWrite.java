package org.lwjgl.stb;

/**
 * Browser STBImageWrite stub. Screenshots (NativeImage.writeToFile) are not
 * supported in the browser runtime; the write entry point reports failure.
 */
public final class STBImageWrite {
    private STBImageWrite() {
    }

    public static int nstbi_write_png_to_func(
            long callback,
            long context,
            int width,
            int height,
            int components,
            long data,
            int strideInBytes
    ) {
        return 0;
    }
}
