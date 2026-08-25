package dev.mcweb.graal.stb;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Minimal pure-Java PNG decoder standing in for STBImage in the browser,
 * where no native STB exists. Covers the subset Minecraft assets use:
 * bit depth 8 (and 1/2/4 for paletted images), color types
 * grayscale/RGB/palette/gray+alpha/RGBA, non-interlaced. Output is always
 * 8-bit, matching the stbi_load (non-HDR, non-16) entry points.
 */
public final class PngDecoder {
    public record Decoded(int width, int height, int comp, byte[] pixels) {
    }

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private PngDecoder() {
    }

    /**
     * Decode a PNG, converting to {@code desiredChannels} components per pixel
     * (0 keeps the file's native count; 1 gray, 2 gray+alpha, 3 RGB, 4 RGBA).
     */
    public static Decoded decode(byte[] data, int desiredChannels) throws IOException {
        if (data.length < SIGNATURE.length) {
            throw new IOException("PNG data is truncated");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (data[i] != SIGNATURE[i]) {
                throw new IOException("not a PNG image");
            }
        }

        int width = 0;
        int height = 0;
        int bitDepth = 0;
        int colorType = 0;
        int interlace = 0;
        byte[] palette = null;
        byte[] transparency = null;
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();

        int pos = SIGNATURE.length;
        boolean finished = false;
        while (!finished) {
            if (pos + 12 > data.length) {
                throw new IOException("PNG chunk header overruns the data");
            }
            int length = readInt(data, pos);
            String type = new String(data, pos + 4, 4, StandardCharsets.US_ASCII);
            int payload = pos + 8;
            if (payload + length + 4 > data.length) {
                throw new IOException("PNG chunk '" + type + "' overruns the data");
            }
            switch (type) {
                case "IHDR" -> {
                    width = readInt(data, payload);
                    height = readInt(data, payload + 4);
                    bitDepth = data[payload + 8] & 0xFF;
                    colorType = data[payload + 9] & 0xFF;
                    int compression = data[payload + 10] & 0xFF;
                    int filter = data[payload + 11] & 0xFF;
                    interlace = data[payload + 12] & 0xFF;
                    if (width <= 0 || height <= 0) {
                        throw new IOException("invalid PNG dimensions " + width + "x" + height);
                    }
                    if (compression != 0 || filter != 0) {
                        throw new IOException("unsupported PNG compression/filter method");
                    }
                }
                case "PLTE" -> palette = slice(data, payload, length);
                case "tRNS" -> transparency = slice(data, payload, length);
                case "IDAT" -> compressed.write(data, payload, length);
                case "IEND" -> finished = true;
                default -> {
                    // ancillary chunk; skip
                }
            }
            pos = payload + length + 4;
        }
        if (width == 0 || bitDepth == 0) {
            throw new IOException("PNG is missing its IHDR chunk");
        }
        if (interlace != 0) {
            throw new IOException("interlaced PNGs are not supported");
        }

        int nativeChannels = switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 3 -> 1; // palette index; expanded through PLTE below
            case 4 -> 2;
            case 6 -> 4;
            default -> throw new IOException("unsupported PNG color type " + colorType);
        };
        boolean paletted = colorType == 3;
        if (paletted && palette == null) {
            throw new IOException("paletted PNG is missing its PLTE chunk");
        }
        // PNG allows 1/2/4-bit samples for grayscale as well as palette, and
        // the vanilla assets use both: 12 of the 3855 textures in the 26.2 jar
        // are sub-byte grayscale. Rejecting them is not a cosmetic loss --
        // TextureAtlas stitches every sprite in one pass, so a single sprite
        // that fails to decode aborts the whole atlas, which is then never
        // uploaded. That is what made terrain, the sun and moon, and the
        // atlas-sourced GUI sprites all invisible while standalone textures
        // (panorama, logo, skins) rendered normally.
        boolean subByteCapable = paletted || colorType == 0;
        if (bitDepth != 8 && !(subByteCapable && (bitDepth == 1 || bitDepth == 2 || bitDepth == 4))) {
            throw new IOException("unsupported PNG bit depth " + bitDepth
                    + " for color type " + colorType);
        }

        byte[] raw = inflate(compressed.toByteArray(), height, width, bitDepth, nativeChannels);
        byte[] rgba = unfilterAndNormalize(raw, width, height, bitDepth, colorType, palette, transparency);

        int outChannels = desiredChannels == 0 ? 4 : desiredChannels;
        if (outChannels < 1 || outChannels > 4) {
            throw new IOException("unsupported desired channel count " + desiredChannels);
        }
        byte[] pixels;
        if (outChannels == 4) {
            pixels = rgba;
        } else {
            pixels = new byte[width * height * outChannels];
            for (int i = 0, j = 0; i < pixels.length; i += outChannels, j += 4) {
                int r = rgba[j] & 0xFF;
                int g = rgba[j + 1] & 0xFF;
                int b = rgba[j + 2] & 0xFF;
                int a = rgba[j + 3] & 0xFF;
                if (outChannels == 1) {
                    pixels[i] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                } else if (outChannels == 2) {
                    pixels[i] = (byte) ((r * 77 + g * 150 + b * 29) >> 8);
                    pixels[i + 1] = (byte) a;
                } else {
                    pixels[i] = (byte) r;
                    pixels[i + 1] = (byte) g;
                    pixels[i + 2] = (byte) b;
                }
            }
        }
        // Self-consistency probe. A texture upload that fails with "Copy would
        // overrun the source buffer" means some buffer is shorter than the
        // dimensions it is being used with; this says whether the decoder is
        // the one disagreeing with itself, or whether it produced exactly what
        // it declared and the mismatch is downstream.
        if (pixels.length != width * height * outChannels) {
            report("png:size-mismatch " + width + "x" + height
                    + " comp=" + outChannels + " pixels=" + pixels.length
                    + " expected=" + (width * height * outChannels)
                    + " colorType=" + colorType + " bitDepth=" + bitDepth);
        } else if (decodeReports < 60) {
            decodeReports++;
            report("png:decoded " + width + "x" + height + " comp=" + outChannels
                    + " bytes=" + pixels.length + " colorType=" + colorType
                    + " bitDepth=" + bitDepth + " desired=" + desiredChannels);
        }
        return new Decoded(width, height, outChannels, pixels);
    }

    private static int decodeReports;

    private static void report(String message) {
        try {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(message);
        } catch (Throwable ignored) {
            // Decoding must not depend on the diagnostic channel existing.
        }
    }

    /** Read metadata without producing pixels (stbi_info_from_memory). */
    public static Decoded info(byte[] data) throws IOException {
        Decoded decoded = decode(data, 4);
        return decoded;
    }

    private static byte[] inflate(byte[] compressed, int height, int width, int bitDepth, int channels)
            throws IOException {
        long bitsPerPixel = (long) bitDepth * channels;
        long rowBits = bitsPerPixel * width;
        long expected = (rowBits + 7) / 8 + 1; // +1 filter byte per row
        long total = expected * height;
        if (total > Integer.MAX_VALUE) {
            throw new IOException("PNG image is too large");
        }
        // java.util.zip.Inflater needs native zlib, which Web Image lacks;
        // inflate the IDAT stream in pure Java instead. PNG wraps DEFLATE in
        // a zlib container (RFC 1950): a two-byte header, optional dictionary,
        // and a trailing Adler-32 that the decompressor ignores.
        int offset = 0;
        if (compressed.length >= 2 && (compressed[0] & 0x0F) == 8) {
            offset = 2;
            if ((compressed[1] & 0x20) != 0) {
                offset += 4; // FDICT: preset dictionary id follows the header
            }
        }
        byte[] raw = offset == 0
                ? compressed
                : java.util.Arrays.copyOfRange(compressed, offset, Math.max(offset, compressed.length - 4));
        byte[] output;
        try {
            output = Inflate.decompress(raw, (int) total);
        } catch (RuntimeException failure) {
            throw new IOException("PNG zlib stream is corrupt: " + failure.getMessage(), failure);
        }
        if (output.length < total) {
            throw new IOException("PNG pixel stream is truncated ("
                    + output.length + " of " + total + " bytes)");
        }
        return output;
    }

    /**
     * Reverse the five PNG filters per row and expand every supported color
     * type to 8-bit RGBA.
     */
    private static byte[] unfilterAndNormalize(
            byte[] raw,
            int width,
            int height,
            int bitDepth,
            int colorType,
            byte[] palette,
            byte[] transparency
    ) throws IOException {
        int channels = switch (colorType) {
            case 0 -> 1;
            case 2 -> 3;
            case 3 -> 1;
            case 4 -> 2;
            default -> 4;
        };
        long bitsPerPixel = Math.max(8L, (long) bitDepth * channels);
        int bytesPerPixel = (int) (bitsPerPixel / 8);
        int stride = (int) (((long) bitDepth * channels * width + 7) / 8);
        byte[] pixels = new byte[stride * height];

        for (int y = 0; y < height; y++) {
            int rowStart = y * (stride + 1);
            int filter = raw[rowStart] & 0xFF;
            int in = rowStart + 1;
            int out = y * stride;
            for (int x = 0; x < stride; x++) {
                int value = raw[in + x] & 0xFF;
                int a = x >= bytesPerPixel ? (pixels[out + x - bytesPerPixel] & 0xFF) : 0;
                int b = y > 0 ? (pixels[out - stride + x] & 0xFF) : 0;
                int c = (x >= bytesPerPixel && y > 0) ? (pixels[out - stride + x - bytesPerPixel] & 0xFF) : 0;
                pixels[out + x] = (byte) switch (filter) {
                    case 0 -> value;
                    case 1 -> value + a;
                    case 2 -> value + b;
                    case 3 -> value + ((a + b) >> 1);
                    case 4 -> value + paeth(a, b, c);
                    default -> throw new IOException("unsupported PNG filter " + filter);
                };
            }
        }

        byte[] rgba = new byte[width * height * 4];
        int dst = 0;
        for (int y = 0; y < height; y++) {
            int row = y * stride;
            for (int x = 0; x < width; x++, dst += 4) {
                switch (colorType) {
                    case 0 -> {
                        int gray = sampleLowDepth(pixels, row, x, bitDepth);
                        int alpha = 255;
                        if (transparency != null && transparency.length == 2) {
                            // tRNS stores the key as a 16-bit big-endian value
                            // in range 0..2^bitdepth-1, so it must be compared
                            // against the *raw* sample, not the widened one.
                            int key = transparency[1] & 0xFF;
                            if (rawSample(pixels, row, x, bitDepth) == key) {
                                alpha = 0;
                            }
                        }
                        rgba[dst] = (byte) gray;
                        rgba[dst + 1] = (byte) gray;
                        rgba[dst + 2] = (byte) gray;
                        rgba[dst + 3] = (byte) alpha;
                    }
                    case 2 -> {
                        int base = row + x * 3;
                        int alpha = 255;
                        if (transparency != null && transparency.length == 6) {
                            int r = pixels[base] & 0xFF;
                            int g = pixels[base + 1] & 0xFF;
                            int b = pixels[base + 2] & 0xFF;
                            if (r == (transparency[1] & 0xFF)
                                    && g == (transparency[3] & 0xFF)
                                    && b == (transparency[5] & 0xFF)) {
                                alpha = 0;
                            }
                        }
                        rgba[dst] = pixels[base];
                        rgba[dst + 1] = pixels[base + 1];
                        rgba[dst + 2] = pixels[base + 2];
                        rgba[dst + 3] = (byte) alpha;
                    }
                    case 3 -> {
                        int index = rawSample(pixels, row, x, bitDepth);
                        int entries = palette.length / 3;
                        if (entries == 0) {
                            throw new IOException("PNG palette is empty");
                        }
                        // Tolerate out-of-range indices like STB does.
                        int clamped = Math.min(index, entries - 1);
                        int entry = clamped * 3;
                        int alpha = transparency != null && index < transparency.length
                                ? transparency[index] & 0xFF
                                : 255;
                        rgba[dst] = palette[entry];
                        rgba[dst + 1] = palette[entry + 1];
                        rgba[dst + 2] = palette[entry + 2];
                        rgba[dst + 3] = (byte) alpha;
                    }
                    case 4 -> {
                        int base = row + x * 2;
                        byte gray = pixels[base];
                        rgba[dst] = gray;
                        rgba[dst + 1] = gray;
                        rgba[dst + 2] = gray;
                        rgba[dst + 3] = pixels[base + 1];
                    }
                    default -> {
                        int base = row + x * 4;
                        rgba[dst] = pixels[base];
                        rgba[dst + 1] = pixels[base + 1];
                        rgba[dst + 2] = pixels[base + 2];
                        rgba[dst + 3] = pixels[base + 3];
                    }
                }
            }
        }
        return rgba;
    }

    /**
     * Raw sub-byte sample, unscaled.
     *
     * <p>Palette indices and grayscale intensities need opposite treatment: an
     * intensity must be widened to 0-255, but an index must be used verbatim.
     * Scaling both (the previous behaviour) turns 4-bit palette index 1 into
     * 17, selecting the wrong colour entry — latent while sub-byte images were
     * rejected outright, and wrong for 399 of the block textures once they
     * decode.</p>
     */
    private static int rawSample(byte[] row, int rowOffset, int x, int bitDepth) {
        if (bitDepth == 8) {
            return row[rowOffset + x] & 0xFF;
        }
        int perByte = 8 / bitDepth;
        int mask = (1 << bitDepth) - 1;
        int packed = row[rowOffset + x / perByte] & 0xFF;
        int shift = (perByte - 1 - (x % perByte)) * bitDepth;
        return (packed >> shift) & mask;
    }

    /** Grayscale intensity widened to the full 0-255 range, as STB does. */
    private static int sampleLowDepth(byte[] row, int rowOffset, int x, int bitDepth) {
        int value = rawSample(row, rowOffset, x, bitDepth);
        if (bitDepth == 8) {
            return value;
        }
        int mask = (1 << bitDepth) - 1;
        return value * 255 / mask;
    }

    private static int paeth(int a, int b, int c) {
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        if (pa <= pb && pa <= pc) {
            return a;
        }
        return pb <= pc ? b : c;
    }

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] result = new byte[length];
        System.arraycopy(data, offset, result, 0, length);
        return result;
    }
}
