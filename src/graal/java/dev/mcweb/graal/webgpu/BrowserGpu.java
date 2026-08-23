package dev.mcweb.graal.webgpu;

import org.graalvm.webimage.api.JS;
import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;

/** Java-to-browser bridge. WebGPU objects stay in a JavaScript handle table. */
public final class BrowserGpu {
    private static Runnable frameCallback;
    private static dev.mcweb.graal.BrowserInputDispatcher inputDispatcher;
    private BrowserGpu() {
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.isReady();", args = {})
    public static native boolean isReady();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.canvasWidth();", args = {})
    public static native int canvasWidth();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.canvasHeight();", args = {})
    public static native int canvasHeight();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.adapterName();", args = {})
    public static native String adapterName();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createTexture(label, usage, format, width, height, depth, mips);",
            args = {"label", "usage", "format", "width", "height", "depth", "mips"})
    public static native int createTexture(
            String label,
            int usage,
            String format,
            int width,
            int height,
            int depth,
            int mips
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.destroyTexture(handle);", args = {"handle"})
    public static native void destroyTexture(int handle);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createCommandEncoder();", args = {})
    public static native int createCommandEncoder();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createBuffer(label, usage, size);",
            args = {"label", "usage", "size"})
    public static native int createBuffer(String label, int usage, int size);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.destroyBuffer(handle);", args = {"handle"})
    public static native void destroyBuffer(int handle);

    /**
     * Allocates a render-path byte buffer, naming the site and size if it fails.
     *
     * <p>Measured at the moment of failure, 128 MiB still allocates immediately
     * after the OutOfMemoryError that kills the frame -- so the heap is not
     * exhausted and the throw is one oversized or miscalculated request. Web
     * Image strips stack traces, so the only way to identify which request is
     * to have each candidate say its own name and length on the way down.</p>
     */
    public static byte[] allocate(int length, String what) {
        if (length < 0 || length > (1 << 28)) {
            reportProgress("native:alloc-suspicious " + what + " length=" + length);
        }
        try {
            return new byte[length];
        } catch (OutOfMemoryError exhausted) {
            reportProgress("native:alloc-failed " + what + " length=" + length);
            throw exhausted;
        }
    }

    static String encodeBase64(byte[] data, int offset, int length) {
        byte[] slice = allocate(length, "encodeBase64");
        System.arraycopy(data, offset, slice, 0, length);
        return java.util.Base64.getEncoder().encodeToString(slice);
    }

    /**
     * Compatibility upload for WasmGC. WasmLM selects the raw linear-memory
     * methods below because it has no need for the base64 copy path.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.writeBuffer64(handle, destinationOffset, base64);",
            args = {"handle", "destinationOffset", "base64"})
    public static native void writeBuffer64(
            int handle,
            int destinationOffset,
            String base64
    );

    /** WasmGC upload packed as two payload bytes per JavaScript UTF-16 code unit. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.writeBufferText(handle, destinationOffset, text, byteLength);",
            args = {"handle", "destinationOffset", "text", "byteLength"})
    private static native void writeBufferText(
            int handle,
            int destinationOffset,
            String text,
            int byteLength
    );

    /**
     * WasmGC upload without Java {@link String} materialization.
     *
     * <p>WasmGC arrays are opaque references in JavaScript, but Web Image
     * exports an exact {@code char[]} reader. Java keeps the existing
     * two-bytes-per-char scratch representation and this raw import expands it
     * directly into the typed array consumed by WebGPU. This avoids the
     * expensive {@code charArrayToString(proxyCharArray(...))} bridge while
     * preserving byte-for-byte upload semantics.</p>
     */
    @JSRawCall
    @JS(value = "const read = getExport('array.char.read');"
            + "if (typeof read !== 'function') throw new Error('array.char.read unavailable');"
            + "if ((charCount << 1) < byteLength) throw new Error('packed upload is truncated');"
            + "const bytes = new Uint8Array(byteLength);"
            + "let out = 0;"
            + "for (let i = 0; i < charCount && out < byteLength; i++) {"
            + "  const pair = read(chars, i) | 0;"
            + "  bytes[out++] = pair & 255;"
            + "  if (out < byteLength) bytes[out++] = (pair >>> 8) & 255;"
            + "}"
            + "globalThis.mcWebGpu.writeBufferRaw(handle, destinationOffset, bytes);",
            args = {"handle", "destinationOffset", "chars", "charCount", "byteLength"})
    private static native void writeBufferWasmGc(
            int handle,
            int destinationOffset,
            char[] chars,
            int charCount,
            int byteLength
    );

    /** Whether the current image has the WasmLM linear-memory upload path. */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebRuntimeMode === 'WASMLM_THREADED'"
            + " || globalThis.mcWebRuntimeMode === 'WASMLM_INLINE';", args = {})
    private static native boolean linearMemoryRuntime();

    /**
     * Finds the byte-array payload offset for this image's WasmLM object layout.
     * The result is cached, so this probe allocates only once per image.
     */
    @JSRawCall
    @JS(value = "const memory = getExport('memory');"
            + "if (!memory) return -1;"
            + "try {"
            + "  const view = new Uint8Array(memory.buffer, array >>> 0, 512);"
            + "  for (let i = 0; i < 509; i++) {"
            + "    if (view[i] === 0x7b && view[i + 1] === 0x7c && view[i + 2] === 0x7d) return i;"
            + "  }"
            + "} catch (error) {}"
            + "return -1;", args = {"array"})
    private static native int findByteArrayBase(byte[] array);

    /** Finds the first int payload in a WasmLM {@code int[]} object. */
    @JSRawCall
    @JS(value = "const memory = getExport('memory');"
            + "if (!memory) return -1;"
            + "try {"
            + "  const view = new DataView(memory.buffer, array >>> 0, 512);"
            + "  for (let i = 0; i <= 500; i += 4) {"
            + "    if (view.getInt32(i, true) === 0x13579bdf"
            + "      && view.getInt32(i + 4, true) === 0x2468ace0"
            + "      && view.getInt32(i + 8, true) === 0x0badc0de) return i;"
            + "  }"
            + "} catch (error) {}"
            + "return -1;", args = {"array"})
    private static native int findIntArrayBase(int[] array);

    /**
     * Raw WasmLM upload. The Java reference is lowered to the array's linear
     * memory address; the import wrapper turns it into a Uint8Array view before
     * calling the host. No JavaScript copy is made here.
     */
    @JSRawCall
    @JS(value = "const memory = getExport('memory');"
            + "if (!memory) throw new Error('WasmLM memory export is unavailable');"
            + "const view = new Uint8Array(memory.buffer,"
            + " (array + base + sourceOffset) >>> 0, length >>> 0);"
            + "globalThis.mcWebGpu.writeBufferRaw(handle, destinationOffset, view);",
            args = {"handle", "destinationOffset", "array", "base", "sourceOffset", "length"})
    private static native void writeBufferRaw(
            int handle,
            int destinationOffset,
            byte[] array,
            int base,
            int sourceOffset,
            int length
    );

    /** WasmLM-only texture upload over a shared linear-memory view. */
    @JSRawCall
    @JS(value = "const memory = getExport('memory');"
            + "if (!memory) throw new Error('WasmLM memory export is unavailable');"
            + "const view = new Uint8Array(memory.buffer,"
            + " (array + base + sourceOffset) >>> 0, length >>> 0);"
            + "globalThis.mcWebGpu.writeTextureRaw(handle, view, mipLevel, depthOrLayer,"
            + " x, y, width, height, bytesPerRow, rowsPerImage);",
            args = {"handle", "array", "base", "sourceOffset", "length", "mipLevel",
                    "depthOrLayer", "x", "y", "width", "height", "bytesPerRow",
                    "rowsPerImage"})
    private static native void writeTextureRaw(
            int handle,
            byte[] array,
            int base,
            int sourceOffset,
            int length,
            int mipLevel,
            int depthOrLayer,
            int x,
            int y,
            int width,
            int height,
            int bytesPerRow,
            int rowsPerImage
    );

    /** Selected once: 0 unknown, 1 base64, 2 WasmLM linear memory. */
    private static volatile int uploadPath;
    private static volatile int wasmGcArrayPath = -1;
    private static volatile int packedTextPath = -1;
    private static volatile int byteArrayBase = -1;
    private static volatile int intArrayBase = -1;
    private static char[] packedByteChars;
    private static char[] packedWordChars;

    /**
     * Uploads a byte-array range while keeping the WasmGC compatibility path.
     * Callers must provide a four-byte-compatible range when WebGPU requires
     * one; the buffer layer pads its final range before calling this method.
     */
    static void writeBuffer(int handle, int destinationOffset, byte[] data, int offset, int length) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException(
                    "upload range offset=" + offset + " length=" + length + " array=" + data.length
            );
        }
        if (usesLinearMemoryUploads()) {
            writeBufferRaw(handle, destinationOffset, data, arrayBase(), offset, length);
        } else if (wasmGcArrayTransportEnabled()) {
            int charCount = (length + 1) >>> 1;
            writeBufferWasmGc(
                    handle,
                    destinationOffset,
                    packBytes(data, offset, length, charCount),
                    charCount,
                    length
            );
        } else if (packedTextTransportEnabled()) {
            writeBufferText(
                    handle,
                    destinationOffset,
                    encodePackedBytes(data, offset, length),
                    length
            );
        } else {
            writeBuffer64(handle, destinationOffset, encodeBase64(data, offset, length));
        }
    }

    /** Uploads a byte-array texture range using linear memory when available. */
    static void writeTexture(
            int handle,
            byte[] data,
            int offset,
            int length,
            int mipLevel,
            int depthOrLayer,
            int x,
            int y,
            int width,
            int height,
            int bytesPerRow,
            int rowsPerImage
    ) {
        if (data == null) {
            throw new NullPointerException("data");
        }
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new IndexOutOfBoundsException(
                    "texture upload range offset=" + offset + " length=" + length
                            + " array=" + data.length
            );
        }
        if (usesLinearMemoryUploads()) {
            writeTextureRaw(
                    handle,
                    data,
                    arrayBase(),
                    offset,
                    length,
                    mipLevel,
                    depthOrLayer,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    rowsPerImage
            );
        } else {
            writeTexture64(
                    handle,
                    encodeBase64(data, offset, length),
                    mipLevel,
                    depthOrLayer,
                    x,
                    y,
                    width,
                    height,
                    bytesPerRow,
                    rowsPerImage
            );
        }
    }

    private static boolean usesLinearMemoryUploads() {
        int path = uploadPath;
        if (path == 0) {
            boolean linear = linearMemoryRuntime();
            uploadPath = linear ? 2 : 1;
            try {
                reportProgress("webgpu:buffer-upload-path=" + (linear ? "linear-memory" : "base64"));
            } catch (Throwable ignored) {
                // The path choice is functional; diagnostics must not affect it.
            }
            return linear;
        }
        return path == 2;
    }

    private static boolean packedTextTransportEnabled() {
        int path = packedTextPath;
        if (path < 0) {
            path = queryPackedTextTransport();
            packedTextPath = path;
            try {
                reportProgress("webgpu:wasmgc-string-transport="
                        + (path != 0 ? "packed-utf16" : "base64"));
            } catch (Throwable ignored) {
                // Diagnostics must not affect uploads.
            }
        }
        return path != 0;
    }

    private static boolean wasmGcArrayTransportEnabled() {
        int path = wasmGcArrayPath;
        if (path < 0) {
            path = queryWasmGcArrayTransport();
            wasmGcArrayPath = path;
            try {
                reportProgress("webgpu:wasmgc-array-transport="
                        + (path != 0 ? "raw-readers" : "unavailable"));
            } catch (Throwable ignored) {
                // Diagnostics must not affect uploads.
            }
        }
        return path != 0;
    }

    @JS.Coerce
    @JS(value = "const params = new URLSearchParams("
            + "globalThis.location ? globalThis.location.search : '');"
            + "const disabled = params.has('mcweb_gpu_base64')"
            + " || params.has('mcweb_gpu_packed_text');"
            + "const host = globalThis.mcWebGpu;"
            + "const protocol = globalThis.mcWebRenderCommands;"
            + "return !disabled && typeof host?.writeBufferRaw === 'function'"
            + " && typeof host?.rpCommandStreamWasmGc === 'function'"
            + " && typeof protocol?.replayReader === 'function' ? 1 : 0;", args = {})
    private static native int queryWasmGcArrayTransport();

    @JS.Coerce
    @JS(value = "const disabled = new URLSearchParams("
            + "globalThis.location ? globalThis.location.search : '').has('mcweb_gpu_base64');"
            + "const host = globalThis.mcWebGpu;"
            + "const protocol = globalThis.mcWebRenderCommands;"
            + "return !disabled && typeof host?.writeBufferText === 'function'"
            + " && typeof host?.rpCommandStreamText === 'function'"
            + " && typeof protocol?.replayText === 'function' ? 1 : 0;", args = {})
    private static native int queryPackedTextTransport();

    private static String encodePackedBytes(byte[] data, int offset, int length) {
        int charCount = (length + 1) >>> 1;
        char[] chars = packBytes(data, offset, length, charCount);
        return new String(chars, 0, charCount);
    }

    private static char[] packBytes(byte[] data, int offset, int length, int charCount) {
        char[] chars = packedByteChars;
        if (chars == null || chars.length < charCount) {
            chars = new char[Math.max(256, charCount)];
            packedByteChars = chars;
        }
        for (int i = 0; i < charCount; i++) {
            int source = offset + i * 2;
            int low = data[source] & 0xff;
            int high = source + 1 < offset + length ? (data[source + 1] & 0xff) : 0;
            chars[i] = (char) (low | (high << 8));
        }
        return chars;
    }

    private static String encodePackedWords(int[] words, int wordCount) {
        int charCount = wordCount * 2;
        char[] chars = packedWordChars;
        if (chars == null || chars.length < charCount) {
            chars = new char[Math.max(256, charCount)];
            packedWordChars = chars;
        }
        for (int i = 0; i < wordCount; i++) {
            int value = words[i];
            chars[i * 2] = (char) value;
            chars[i * 2 + 1] = (char) (value >>> 16);
        }
        return new String(chars, 0, charCount);
    }

    private static int arrayBase() {
        int base = byteArrayBase;
        if (base >= 0) {
            return base;
        }
        byte[] probe = new byte[64];
        probe[0] = 0x7b;
        probe[1] = 0x7c;
        probe[2] = 0x7d;
        base = findByteArrayBase(probe);
        if (base < 0) {
            throw new IllegalStateException("could not locate the WasmLM byte[] payload");
        }
        byteArrayBase = base;
        try {
            reportProgress("webgpu:byte-array-base=" + base);
        } catch (Throwable ignored) {
            // Diagnostics must not affect the upload path.
        }
        return base;
    }

    private static int intArrayBase() {
        int base = intArrayBase;
        if (base >= 0) {
            return base;
        }
        int[] probe = new int[] {0x13579bdf, 0x2468ace0, 0x0badc0de};
        base = findIntArrayBase(probe);
        if (base < 0) {
            throw new IllegalStateException("could not locate the WasmLM int[] payload");
        }
        intArrayBase = base;
        try {
            reportProgress("webgpu:int-array-base=" + base);
        } catch (Throwable ignored) {
            // Diagnostics must not affect the command transport.
        }
        return base;
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.writeTexture64(handle, base64, mipLevel, depthOrLayer, x, y, width, height, bytesPerRow, rowsPerImage);",
            args = {"handle", "base64", "mipLevel", "depthOrLayer", "x", "y", "width", "height", "bytesPerRow", "rowsPerImage"})
    public static native void writeTexture64(
            int handle,
            String base64,
            int mipLevel,
            int depthOrLayer,
            int x,
            int y,
            int width,
            int height,
            int bytesPerRow,
            int rowsPerImage
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.clearColorTexture(encoder, texture, r, g, b, a);",
            args = {"encoder", "texture", "r", "g", "b", "a"})
    public static native void clearColorTexture(
            int encoder,
            int texture,
            float r,
            float g,
            float b,
            float a
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.copyTexture(encoder, source, destination, "
            + "sourceX, sourceY, destinationX, destinationY, width, height, mipLevel);",
            args = {"encoder", "source", "destination", "sourceX", "sourceY",
                    "destinationX", "destinationY", "width", "height", "mipLevel"})
    public static native void copyTexture(
            int encoder,
            int source,
            int destination,
            int sourceX,
            int sourceY,
            int destinationX,
            int destinationY,
            int width,
            int height,
            int mipLevel
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.copyBuffer(encoder, source, sourceOffset, destination, destinationOffset, size);",
            args = {"encoder", "source", "sourceOffset", "destination", "destinationOffset", "size"})
    public static native void copyBuffer(
            int encoder,
            int source,
            int sourceOffset,
            int destination,
            int destinationOffset,
            int size
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.submit(encoder);", args = {"encoder"})
    public static native void submit(int encoder);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.configureCanvas(width, height);", args = {"width", "height"})
    public static native void configureCanvas(int width, int height);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.acquireCanvasTexture();", args = {})
    public static native int acquireCanvasTexture();

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.present(handle);", args = {"handle"})
    public static native void present(int handle);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.reportSuccess(argb, backend, path);",
            args = {"argb", "backend", "path"})
    public static native void reportSuccess(int argb, String backend, String path);

    // ---- Pipelines ----

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createPipeline(specJson);", args = {"specJson"})
    public static native int createPipeline(String specJson);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.destroyObject(handle);", args = {"handle"})
    public static native void destroyObject(int handle);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createSamplerJson(specJson);", args = {"specJson"})
    public static native int createSamplerJson(String specJson);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.createTextureView(texture, baseMip, mipLevels, dimension);",
            args = {"texture", "baseMip", "mipLevels", "dimension"})
    public static native int createTextureView(int texture, int baseMip, int mipLevels, String dimension);

    // ---- Render passes ----

    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.beginRenderPass(encoder, descriptorJson);",
            args = {"encoder", "descriptorJson"})
    public static native int beginRenderPass(int encoder, String descriptorJson);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpEnd(pass);", args = {"pass"})
    public static native void rpEnd(int pass);

    /** Selected once per image: command stream by default, immediate for bisects. */
    private static volatile int renderPassMode = -1;

    static boolean renderPassCommandStreamEnabled() {
        int mode = renderPassMode;
        if (mode < 0) {
            mode = queryRenderPassMode();
            renderPassMode = mode;
        }
        return mode != 0;
    }

    @JS.Coerce
    @JS(value = "const disabled = new URLSearchParams("
            + "globalThis.location ? globalThis.location.search : '').has('mcweb_gpu_immediate');"
            + "const protocol = globalThis.mcWebRenderCommands;"
            + "const host = globalThis.mcWebGpu;"
            + "return !disabled && protocol && protocol.VERSION === 1"
            + " && typeof host?.rpCommandStreamRaw === 'function'"
            + " && typeof host?.rpCommandStream64 === 'function' ? 1 : 0;", args = {})
    private static native int queryRenderPassMode();

    /** WasmLM render-command submission over a synchronous linear-memory view. */
    @JSRawCall
    @JS(value = "const memory = getExport('memory');"
            + "if (!memory) throw new Error('WasmLM memory export is unavailable');"
            + "const bytes = new Uint8Array(memory.buffer,"
            + " (words + base) >>> 0, (wordCount * 4) >>> 0);"
            + "globalThis.mcWebGpu.rpCommandStreamRaw(pass, bytes, wordCount * 4, end);",
            args = {"pass", "words", "base", "wordCount", "end"})
    private static native void rpCommandStreamRaw(
            int pass,
            int[] words,
            int base,
            int wordCount,
            int end
    );

    /** WasmGC compatibility transport for the same little-endian command words. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpCommandStream64(pass, base64, byteLength, end);",
            args = {"pass", "base64", "byteLength", "end"})
    private static native void rpCommandStream64(
            int pass,
            String base64,
            int byteLength,
            int end
    );

    /** WasmGC command transport using two UTF-16 code units per i32 word. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpCommandStreamText(pass, text, wordCount, end);",
            args = {"pass", "text", "wordCount", "end"})
    private static native void rpCommandStreamText(
            int pass,
            String text,
            int wordCount,
            int end
    );

    /** Exact i32 reader used only by the WasmGC raw command transport. */
    @WasmExport(value = "mcweb.gpu.commandWord", comment = "Read one WasmGC render command word")
    public static int readCommandWord(int[] words, int index) {
        return words[index];
    }

    /** Replay MCRP directly from an opaque WasmGC {@code int[]} reference. */
    @JSRawCall
    @JS(value = "const read = getExport('mcweb.gpu.commandWord');"
            + "if (typeof read !== 'function') throw new Error('command word reader unavailable');"
            + "globalThis.mcWebGpu.rpCommandStreamWasmGc("
            + "pass, words, wordCount, end, read);",
            args = {"pass", "words", "wordCount", "end"})
    private static native void rpCommandStreamWasmGc(
            int pass,
            int[] words,
            int wordCount,
            int end
    );

    static void rpCommandStream(int pass, int[] words, int wordCount, boolean end) {
        if (words == null) {
            throw new NullPointerException("words");
        }
        if (wordCount < 0 || wordCount > words.length) {
            throw new IndexOutOfBoundsException(
                    "command words=" + wordCount + " array=" + words.length
            );
        }
        int endFlag = end ? 1 : 0;
        if (usesLinearMemoryUploads()) {
            rpCommandStreamRaw(pass, words, intArrayBase(), wordCount, endFlag);
        } else if (wasmGcArrayTransportEnabled()) {
            rpCommandStreamWasmGc(pass, words, wordCount, endFlag);
        } else if (packedTextTransportEnabled()) {
            rpCommandStreamText(
                    pass, encodePackedWords(words, wordCount), wordCount, endFlag
            );
        } else {
            rpCommandStream64(pass, encodeIntBatch(words, wordCount), wordCount * 4, endFlag);
        }
    }

    /**
     * Base64 has a fixed per-pass cost that dominates tiny WasmGC command
     * buffers. WasmLM never takes this branch: its linear-memory view is the
     * cheapest transport even for a small pass.
     */
    static boolean replaySmallRenderPassImmediately(int wordCount) {
        return wordCount > 0 && wordCount <= 64
                && !usesLinearMemoryUploads()
                && !wasmGcArrayTransportEnabled();
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpSetPipeline(pass, pipeline);", args = {"pass", "pipeline"})
    public static native void rpSetPipeline(int pass, int pipeline);

    /**
     * Interned binding names, so the render path never crosses with a String.
     *
     * <p>{@code rpSetUniform} and {@code rpBindTexture} are issued about 1,130
     * times per frame and both took a {@code String}. A CPU profile of the moving
     * render thread put {@code charArrayToString} at <b>34.4%</b> of it — the
     * largest entry by a wide margin — with the Proxy {@code get}/{@code has}
     * traffic that marshalling drags along adding another 13%. The names come
     * from a fixed vocabulary of a few dozen, so materialising one per call is
     * pure waste.</p>
     *
     * <p>Each distinct name is published to the host once and passed as an int
     * thereafter. Interning also makes the render pass encodable as a typed
     * array later, which a String never could be.</p>
     */
    private static final java.util.HashMap<String, Integer> BINDING_NAME_IDS = new java.util.HashMap<>();
    private static int nextBindingNameId;

    /** Interns a binding name, publishing it to the host the first time it is seen. */
    public static int bindingNameId(String name) {
        Integer existing = BINDING_NAME_IDS.get(name);
        if (existing != null) {
            return existing;
        }
        int assigned = nextBindingNameId++;
        BINDING_NAME_IDS.put(name, assigned);
        registerBindingName(assigned, name);
        return assigned;
    }

    @JS.Coerce
    @JS(value = "(globalThis.mcWebGpu._bindingNames ||= [])[id] = name;", args = {"id", "name"})
    private static native void registerBindingName(int id, String name);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpBindTexture(pass, name, view, sampler);",
            args = {"pass", "name", "view", "sampler"})
    public static native void rpBindTexture(int pass, int name, int view, int sampler);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpSetUniform(pass, name, buffer, offset, size);",
            args = {"pass", "name", "buffer", "offset", "size"})
    public static native void rpSetUniform(int pass, int name, int buffer, int offset, int size);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpSetVertexBuffer(pass, slot, buffer, offset, size);",
            args = {"pass", "slot", "buffer", "offset", "size"})
    public static native void rpSetVertexBuffer(int pass, int slot, int buffer, int offset, int size);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpSetIndexBuffer(pass, buffer, format);",
            args = {"pass", "buffer", "format"})
    public static native void rpSetIndexBuffer(int pass, int buffer, String format);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpScissor(pass, x, y, width, height);",
            args = {"pass", "x", "y", "width", "height"})
    public static native void rpScissor(int pass, int x, int y, int width, int height);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDisableScissor(pass);", args = {"pass"})
    public static native void rpDisableScissor(int pass);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDraw(pass, firstVertex, vertexCount, instanceCount, baseInstance);",
            args = {"pass", "firstVertex", "vertexCount", "instanceCount", "baseInstance"})
    public static native void rpDraw(int pass, int firstVertex, int vertexCount, int instanceCount, int baseInstance);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDrawIndexed(pass, indexCount, instanceCount, firstIndex, baseVertex, firstInstance);",
            args = {"pass", "indexCount", "instanceCount", "firstIndex", "baseVertex", "firstInstance"})
    public static native void rpDrawIndexed(
            int pass,
            int indexCount,
            int instanceCount,
            int firstIndex,
            int baseVertex,
            int firstInstance
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDrawIndirect(pass, buffer, offset, drawCount);",
            args = {"pass", "buffer", "offset", "drawCount"})
    public static native void rpDrawIndirect(int pass, int buffer, int offset, int drawCount);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDrawIndexedIndirect(pass, buffer, offset, drawCount);",
            args = {"pass", "buffer", "offset", "drawCount"})
    public static native void rpDrawIndexedIndirect(int pass, int buffer, int offset, int drawCount);

    /** Whether the measured batched draw bridge is enabled for this run. */
    @JS.Coerce
    @JS(value = "return globalThis.mcWebGpu.drawBatchEnabled();", args = {})
    public static native int drawBatchEnabled();

    /**
     * Whether the per-operation worldgen trace is armed for this run
     * (`?mcweb_worldgen_trace=1`). Off by default: those markers fire from 25 injected
     * sites inside the chunk generator and cost a String plus a boundary crossing each.
     */
    @JS.Coerce
    @JS(value = "return new URLSearchParams(globalThis.location ? globalThis.location.search : '')"
            + ".has('mcweb_worldgen_trace') ? 1 : 0;", args = {})
    public static native int worldgenTraceEnabled();

    /**
     * Compact consecutive indexed draws into one Java/JS crossing. The payload is
     * intentionally opaque base64 because Web Image's @JS bridge cannot pass a
     * byte[] as a WebIDL BufferSource; the host decodes it directly into a small
     * DataView and replays the same ordered draw calls.
     */
    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDrawIndexedBatch(pass, base64, drawCount);",
            args = {"pass", "base64", "drawCount"})
    public static native void rpDrawIndexedBatch(int pass, String base64, int drawCount);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpDrawBatch(pass, base64, drawCount);",
            args = {"pass", "base64", "drawCount"})
    public static native void rpDrawBatch(int pass, String base64, int drawCount);

    static String encodeIntBatch(int[] values, int ints) {
        byte[] bytes = new byte[ints * 4];
        for (int i = 0; i < ints; i++) {
            int value = values[i];
            int offset = i * 4;
            bytes[offset] = (byte) value;
            bytes[offset + 1] = (byte) (value >>> 8);
            bytes[offset + 2] = (byte) (value >>> 16);
            bytes[offset + 3] = (byte) (value >>> 24);
        }
        return java.util.Base64.getEncoder().encodeToString(bytes);
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpPushDebugGroup(pass, label);", args = {"pass", "label"})
    public static native void rpPushDebugGroup(int pass, String label);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpPushDebugGroupId(pass, labelId);",
            args = {"pass", "labelId"})
    public static native void rpPushDebugGroupId(int pass, int labelId);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.rpPopDebugGroup(pass);", args = {"pass"})
    public static native void rpPopDebugGroup(int pass);

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.clearColorAndDepth(encoder, colorTexture, r, g, b, a, depthTexture, depth);",
            args = {"encoder", "colorTexture", "r", "g", "b", "a", "depthTexture", "depth"})
    public static native void clearColorAndDepth(
            int encoder,
            int colorTexture,
            float r,
            float g,
            float b,
            float a,
            int depthTexture,
            double depth
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.clearColorAndDepthRegion(encoder, colorTexture, r, g, b, a, depthTexture, depth, x, y, width, height);",
            args = {"encoder", "colorTexture", "r", "g", "b", "a", "depthTexture", "depth",
                    "x", "y", "width", "height"})
    public static native void clearColorAndDepthRegion(
            int encoder,
            int colorTexture,
            float r,
            float g,
            float b,
            float a,
            int depthTexture,
            double depth,
            int x,
            int y,
            int width,
            int height
    );

    @JS.Coerce
    @JS(value = "globalThis.mcWebGpu.clearDepth(encoder, texture, depth);",
            args = {"encoder", "texture", "depth"})
    public static native void clearDepth(int encoder, int texture, double depth);

    /** Apply Minecraft's current VSync choice and effective frame limit. */
    @JS.Coerce
    @JS(value = "globalThis.mcWebPump.configure(vsync, frameLimit);",
            args = {"vsync", "frameLimit"})
    public static native void configureFramePacing(boolean vsync, int frameLimit);

    public static void registerFrameCallback(Runnable callback) {
        frameCallback = callback;
        installFrameCallback();
    }

    @JSRawCall
    @JS("globalThis.mcWebPump.register(()=>getExport('mcweb.client.frame')());"
            + "const n=v=>typeof v==='bigint'?Number(v):v;"
            + "globalThis.mcWebGpu._javaUploadDedupStats=()=>({"
            + "directCalls:n(getExport('mcweb.gpu.dedup.directCalls')()),"
            + "directBytes:n(getExport('mcweb.gpu.dedup.directBytes')()),"
            + "mappedCalls:n(getExport('mcweb.gpu.dedup.mappedCalls')()),"
            + "mappedBytes:n(getExport('mcweb.gpu.dedup.mappedBytes')()),"
            + "snapshotBytes:n(getExport('mcweb.gpu.dedup.snapshotBytes')()),"
            + "snapshotPeakBytes:n(getExport('mcweb.gpu.dedup.snapshotPeakBytes')()),"
            + "snapshotGrowthPeak:n(getExport('mcweb.gpu.dedup.snapshotGrowthPeak')()),"
            + "snapshotBuffers:n(getExport('mcweb.gpu.dedup.snapshotBuffers')()),"
            + "snapshotPeakBuffers:n(getExport('mcweb.gpu.dedup.snapshotPeakBuffers')()),"
            + "snapshotMaxSingle:n(getExport('mcweb.gpu.dedup.snapshotMaxSingle')()),"
            + "snapshotTooLarge:n(getExport('mcweb.gpu.dedup.snapshotTooLarge')()),"
            + "snapshotBudgetMisses:n(getExport('mcweb.gpu.dedup.snapshotBudgetMisses')()),"
            + "overlappingMaps:n(getExport('mcweb.gpu.dedup.overlappingMaps')())});")
    private static native void installFrameCallback();

    @WasmExport(value = "mcweb.client.frame", comment = "Run one browser client frame")
    public static void dispatchFrame() {
        frameCallback.run();
    }

    public static void registerInputBridge(dev.mcweb.graal.BrowserInputDispatcher bridge) {
        inputDispatcher = bridge;
        installInputBridge();
    }

    @JSRawCall
    @JS("globalThis.mcWebInput.register((name,a,b,c,d)=>"
            + "getExport('mcweb.client.input')(toJavaString(String(name)),+a,+b,+c,+d));")
    private static native void installInputBridge();

    @WasmExport(value = "mcweb.client.input", comment = "Dispatch one browser input event")
    public static void dispatchInput(String name, double first, double second, double third, double fourth) {
        inputDispatcher.dispatch(name, first, second, third, fourth);
    }

    @JS.Coerce
    @JS(value = "if (globalThis.mcWebPump) globalThis.mcWebPump.frameReported(count);", args = {"count"})
    public static native void reportFrame(long count);

    @JS.Coerce
    @JS(value = "globalThis.__mcWebLastStage = stage; if (globalThis.mcWebGpu) globalThis.mcWebGpu.reportProgress(stage);", args = {"stage"})
    public static native void reportProgress(String stage);

    /**
     * Probe channel for code that runs while this thread is not returning to the browser.
     *
     * <p>{@link #reportProgress} writes a console line, a stage-ring entry and two DOM
     * nodes, all of which live on the main thread's JS heap — unreachable exactly when a
     * wedge is being diagnosed — and none of which survive being called from inside a
     * spin loop. This one only writes the shared-memory beacon, on a ring of its own so
     * a fast probe cannot overwrite the progress history that localises the stall.</p>
     */
    @JS.Coerce
    @JS(value = "const host=globalThis.mcWebGpu;"
            + "if(host&&typeof host.reportDiag==='function'){host.reportDiag(text);}",
            args = {"text"})
    public static native void reportDiag(String text);

    /**
     * Dedicated reload-probe channel. Unlike the general progress ring, this retains
     * the listener/task graph without console traffic or unrelated rendering markers.
     */
    @JS.Coerce
    @JS(value = "const host=globalThis.mcWebGpu;"
            + "if(host&&typeof host.reportReloadProbe==='function'){"
            + "host.reportReloadProbe(event);}", args = {"event"})
    public static native void reportReloadProbe(String event);

    @JS.Coerce
    @JS(value = "const result={stage,type,message};"
            + "globalThis.__mcWebLastJavaFailure=result;"
            + "const host=globalThis.mcWebGpu;"
            + "if(host){host.lastJavaFailure=result;"
            + "if(typeof host.reportJavaFailure==='function'){"
            + "try{host.reportJavaFailure(stage,type,message);}"
            + "catch(error){result.telemetryError=String(error);}}}",
            args = {"stage", "type", "message"})
    public static native void reportJavaFailure(String stage, String type, String message);
}
