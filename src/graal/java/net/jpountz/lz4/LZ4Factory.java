package net.jpountz.lz4;

/**
 * Reflection-free binding to lz4-java's pure-Java safe implementation.
 *
 * <p>The upstream factory derives implementation class names and obtains their
 * {@code INSTANCE} fields with reflection. Closed-world Web Image cannot see
 * those targets, even though lz4-java ships every safe implementation in the
 * image classpath. Keeping this class in the library's own package lets the
 * browser bind the same package-private implementations directly. All public
 * factory entry points intentionally resolve to JavaSafe: the browser has
 * neither JNI nor an Unsafe fast path.</p>
 */
public final class LZ4Factory {
    private static final LZ4Factory JAVA_SAFE_INSTANCE = new LZ4Factory();

    private final LZ4Compressor fastCompressor = LZ4JavaSafeCompressor.INSTANCE;
    private final LZ4Compressor highCompressor = LZ4HCJavaSafeCompressor.INSTANCE;
    private final LZ4FastDecompressor fastDecompressor =
            LZ4JavaSafeFastDecompressor.INSTANCE;
    private final LZ4SafeDecompressor safeDecompressor =
            LZ4JavaSafeSafeDecompressor.INSTANCE;
    private final LZ4Compressor[] highCompressors = new LZ4Compressor[18];

    private LZ4Factory() {
        highCompressors[9] = highCompressor;
        for (int level = 1; level <= 17; level++) {
            if (level != 9) {
                highCompressors[level] = new LZ4HCJavaSafeCompressor(level);
            }
        }
    }

    public static synchronized LZ4Factory nativeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized LZ4Factory nativeInsecureInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized LZ4Factory safeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized LZ4Factory unsafeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized LZ4Factory unsafeInsecureInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static LZ4Factory fastestJavaInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static LZ4Factory fastestInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public LZ4Compressor fastCompressor() {
        return fastCompressor;
    }

    public LZ4Compressor highCompressor() {
        return highCompressor;
    }

    public LZ4Compressor highCompressor(int level) {
        if (level > 17) {
            level = 17;
        } else if (level < 1) {
            level = 9;
        }
        return highCompressors[level];
    }

    public LZ4FastDecompressor fastDecompressor() {
        return fastDecompressor;
    }

    public LZ4SafeDecompressor safeDecompressor() {
        return safeDecompressor;
    }

    public LZ4UnknownSizeDecompressor unknownSizeDecompressor() {
        return safeDecompressor;
    }

    public LZ4Decompressor decompressor() {
        return fastDecompressor;
    }

    public static void main(String[] args) {
        System.out.println("Fastest instance is " + fastestInstance());
        System.out.println("Fastest Java instance is " + fastestJavaInstance());
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + ":JavaSafe";
    }
}
