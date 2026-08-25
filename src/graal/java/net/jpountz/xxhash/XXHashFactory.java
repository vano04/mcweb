package net.jpountz.xxhash;

/**
 * Reflection- and JNI-free binding for the checksums used by LZ4 block streams.
 *
 * <p>{@code LZ4BlockInputStream} and {@code LZ4BlockOutputStream} ask this
 * factory for XXHash32. The upstream factory locates all four JavaSafe classes
 * reflectively, so fixing only {@code LZ4Factory} still leaves Minecraft's LZ4
 * region codec unreachable in a closed-world image.</p>
 */
public final class XXHashFactory {
    private static final XXHashFactory JAVA_SAFE_INSTANCE = new XXHashFactory();

    private final XXHash32 hash32 = XXHash32JavaSafe.INSTANCE;
    private final XXHash64 hash64 = XXHash64JavaSafe.INSTANCE;
    private final StreamingXXHash32.Factory streamingHash32Factory =
            StreamingXXHash32JavaSafe.Factory.INSTANCE;
    private final StreamingXXHash64.Factory streamingHash64Factory =
            StreamingXXHash64JavaSafe.Factory.INSTANCE;

    private XXHashFactory() {
    }

    public static synchronized XXHashFactory nativeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized XXHashFactory safeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static synchronized XXHashFactory unsafeInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static XXHashFactory fastestJavaInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public static XXHashFactory fastestInstance() {
        return JAVA_SAFE_INSTANCE;
    }

    public XXHash32 hash32() {
        return hash32;
    }

    public XXHash64 hash64() {
        return hash64;
    }

    public StreamingXXHash32 newStreamingHash32(int seed) {
        return streamingHash32Factory.newStreamingHash(seed);
    }

    public StreamingXXHash64 newStreamingHash64(long seed) {
        return streamingHash64Factory.newStreamingHash(seed);
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
