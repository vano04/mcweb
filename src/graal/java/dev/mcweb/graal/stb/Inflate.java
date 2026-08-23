package dev.mcweb.graal.stb;

/**
 * Pure-Java DEFLATE (RFC 1951) decompressor. Web Image has no native zlib
 * (Inflater.init fails in the browser runtime), so PNG IDAT streams are
 * inflated in Java. Supports stored, fixed-Huffman, and dynamic-Huffman
 * blocks.
 */
final class Inflate {
    private static final int[] LENGTH_BASE = {
            3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31,
            35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258
    };
    private static final int[] LENGTH_EXTRA = {
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2,
            3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0
    };
    private static final int[] DIST_BASE = {
            1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193,
            257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145,
            8193, 12289, 16385, 24577
    };
    private static final int[] DIST_EXTRA = {
            0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6,
            7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13
    };
    private static final int[] CODE_LENGTH_ORDER = {
            16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15
    };

    private final byte[] input;
    private byte[] output;
    private int bitBuffer;
    private int bitCount;
    private int inputPosition;
    private int outputPosition;
    private int[] literalLengths;
    private int[] distanceLengths;

    private Inflate(byte[] input, int expectedSize) {
        this.input = input;
        this.output = new byte[Math.max(expectedSize, 64)];
    }

    static byte[] decompress(byte[] data, int expectedSize) {
        Inflate inflater = new Inflate(data, expectedSize);
        return inflater.run();
    }

    private byte[] run() {
        boolean finalBlock = false;
        while (!finalBlock) {
            finalBlock = readBits(1) == 1;
            int type = readBits(2);
            switch (type) {
                case 0 -> copyStoredBlock();
                case 1 -> {
                    buildFixedTables();
                    inflateBlock();
                }
                case 2 -> {
                    buildDynamicTables();
                    inflateBlock();
                }
                default -> throw new IllegalStateException("Invalid DEFLATE block type " + type);
            }
        }
        byte[] result = new byte[outputPosition];
        System.arraycopy(output, 0, result, 0, outputPosition);
        return result;
    }

    private int readBits(int count) {
        while (bitCount < count) {
            if (inputPosition >= input.length) {
                throw new IllegalStateException("DEFLATE stream is truncated");
            }
            bitBuffer |= (input[inputPosition++] & 0xFF) << bitCount;
            bitCount += 8;
        }
        int value = bitBuffer & ((1 << count) - 1);
        bitBuffer >>>= count;
        bitCount -= count;
        return value;
    }

    private void alignToByte() {
        bitBuffer = 0;
        bitCount = 0;
    }

    private int readByteAligned() {
        if (inputPosition >= input.length) {
            throw new IllegalStateException("DEFLATE stream is truncated");
        }
        return input[inputPosition++] & 0xFF;
    }

    private void copyStoredBlock() {
        alignToByte();
        int length = readByteAligned() | (readByteAligned() << 8);
        int complement = readByteAligned() | (readByteAligned() << 8);
        if ((length ^ complement) != 0xFFFF) {
            throw new IllegalStateException("Stored block length check failed");
        }
        ensureCapacity(outputPosition + length);
        for (int i = 0; i < length; i++) {
            output[outputPosition++] = (byte) readByteAligned();
        }
    }

    private void buildFixedTables() {
        int[] literal = new int[288];
        for (int i = 0; i < 144; i++) {
            literal[i] = 8;
        }
        for (int i = 144; i < 256; i++) {
            literal[i] = 9;
        }
        for (int i = 256; i < 280; i++) {
            literal[i] = 7;
        }
        for (int i = 280; i < 288; i++) {
            literal[i] = 8;
        }
        literalLengths = canonicalCodes(literal);
        int[] distance = new int[30];
        java.util.Arrays.fill(distance, 5);
        distanceLengths = canonicalCodes(distance);
    }

    private void buildDynamicTables() {
        int literalCount = readBits(5) + 257;
        int distanceCount = readBits(5) + 1;
        int codeLengthCount = readBits(4) + 4;
        int[] codeLengthLengths = new int[19];
        for (int i = 0; i < codeLengthCount; i++) {
            codeLengthLengths[CODE_LENGTH_ORDER[i]] = readBits(3);
        }
        int[] codeLengthCodes = canonicalCodes(codeLengthLengths);

        int total = literalCount + distanceCount;
        int[] lengths = new int[total];
        int index = 0;
        while (index < total) {
            int symbol = decodeSymbol(codeLengthCodes);
            if (symbol < 16) {
                lengths[index++] = symbol;
            } else if (symbol == 16) {
                if (index == 0) {
                    throw new IllegalStateException("DEFLATE repeat with no previous length");
                }
                int repeat = readBits(2) + 3;
                int previous = lengths[index - 1];
                for (int i = 0; i < repeat && index < total; i++) {
                    lengths[index++] = previous;
                }
            } else if (symbol == 17) {
                int repeat = readBits(3) + 3;
                for (int i = 0; i < repeat && index < total; i++) {
                    lengths[index++] = 0;
                }
            } else if (symbol == 18) {
                int repeat = readBits(7) + 11;
                for (int i = 0; i < repeat && index < total; i++) {
                    lengths[index++] = 0;
                }
            } else {
                throw new IllegalStateException("Invalid code length symbol " + symbol);
            }
        }
        literalLengths = canonicalCodes(java.util.Arrays.copyOfRange(lengths, 0, literalCount));
        distanceLengths = canonicalCodes(java.util.Arrays.copyOfRange(lengths, literalCount, total));
    }

    /**
     * Canonical Huffman decode table: for each symbol, the code bits are
     * stored right-aligned with its length in the high byte. Decoding walks
     * bits one at a time comparing against the code prefix.
     */
    private static int[] canonicalCodes(int[] lengths) {
        int maxBits = 0;
        for (int length : lengths) {
            maxBits = Math.max(maxBits, length);
        }
        int[] blCount = new int[maxBits + 1];
        for (int length : lengths) {
            if (length > 0) {
                blCount[length]++;
            }
        }
        int[] nextCode = new int[maxBits + 1];
        int code = 0;
        for (int bits = 1; bits <= maxBits; bits++) {
            code = (code + blCount[bits - 1]) << 1;
            nextCode[bits] = code;
        }
        int[] codes = new int[lengths.length];
        for (int symbol = 0; symbol < lengths.length; symbol++) {
            int length = lengths[symbol];
            if (length > 0) {
                codes[symbol] = (length << 16) | nextCode[length]++;
            }
        }
        return codes;
    }

    private int decodeSymbol(int[] codes) {
        int code = 0;
        for (int bits = 1; bits <= 15; bits++) {
            code = (code << 1) | readBits(1);
            for (int symbol = 0; symbol < codes.length; symbol++) {
                int entry = codes[symbol];
                if (entry == 0) {
                    continue;
                }
                int length = entry >>> 16;
                if (length == bits && (entry & 0xFFFF) == code) {
                    return symbol;
                }
            }
        }
        throw new IllegalStateException("Invalid DEFLATE Huffman code");
    }

    private void inflateBlock() {
        while (true) {
            int symbol = decodeSymbol(literalLengths);
            if (symbol == 256) {
                return;
            }
            if (symbol < 256) {
                ensureCapacity(outputPosition + 1);
                output[outputPosition++] = (byte) symbol;
                continue;
            }
            int lengthIndex = symbol - 257;
            if (lengthIndex >= LENGTH_BASE.length) {
                throw new IllegalStateException("Invalid DEFLATE length symbol " + symbol);
            }
            int length = LENGTH_BASE[lengthIndex] + readBits(LENGTH_EXTRA[lengthIndex]);
            int distanceSymbol = decodeSymbol(distanceLengths);
            if (distanceSymbol >= DIST_BASE.length) {
                throw new IllegalStateException("Invalid DEFLATE distance symbol " + distanceSymbol);
            }
            int distance = DIST_BASE[distanceSymbol] + readBits(DIST_EXTRA[distanceSymbol]);
            if (distance > outputPosition) {
                throw new IllegalStateException("DEFLATE distance points before the window start");
            }
            ensureCapacity(outputPosition + length);
            int source = outputPosition - distance;
            for (int i = 0; i < length; i++) {
                output[outputPosition++] = output[source + i];
            }
        }
    }

    private void ensureCapacity(int required) {
        if (required <= output.length) {
            return;
        }
        int grown = output.length;
        while (grown < required) {
            grown = grown < (1 << 20) ? grown * 2 : grown + (grown >> 1);
        }
        byte[] replacement = new byte[grown];
        System.arraycopy(output, 0, replacement, 0, outputPosition);
        output = replacement;
    }
}
