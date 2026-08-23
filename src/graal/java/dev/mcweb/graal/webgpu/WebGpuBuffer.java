package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.BrowserNativeMemory;

/** Mojang GpuBuffer backed by a WebGPU buffer and a CPU mapping shadow. */
final class WebGpuBuffer extends GpuBuffer {
    private static final int MAP_READ = 1;
    private static final int MAP_WRITE = 2;
    private static final int COPY_DST = 8;

    /**
     * Size at which the CPU shadow stops being allocated up front.
     *
     * <p>The shadow doubles every GPU buffer into the Wasm heap. That was
     * affordable until the client entered a world: terrain meshing creates
     * section uber-buffers of ~128 MiB each, and mirroring them took live
     * native usage past 430 MiB and threw a bare {@code OutOfMemoryError} a
     * few frames after spawn — which is what "terrain does not render" actually
     * was. The section pipeline itself is healthy; measured on the frame before
     * the crash it reached {@code visible=6 visRenderable=6 compiled=6}.</p>
     *
     * <p>Buffers this large are written through {@code upload}, which already
     * has a shadow-free path, and drawn from — never read back on the CPU. The
     * small ones keep their eager shadow because the atlas path depends on it:
     * Mojang fills COPY_DST-only staging buffers via {@code queue.writeBuffer}
     * and then reads them back through the shadow for
     * {@code copyBufferToTexture}, so dropping it there uploads black atlases.
     * A buffer above the limit that really is mapped still gets a shadow, just
     * at {@code map()} time rather than at construction.</p>
     */
    private static final int EAGER_SHADOW_LIMIT = 32 * 1024 * 1024;

    /**
     * Redundant mapped-write detection must not recreate the memory problem
     * the eager-shadow limit solved. A single retained snapshot is capped at
     * 4 MiB and all live snapshots together at 16 MiB; larger/budget-exhausted
     * mappings simply take the original upload path.
     */
    private static final int MAPPING_SNAPSHOT_LIMIT = 4 * 1024 * 1024;
    private static final long TOTAL_MAPPING_SNAPSHOT_LIMIT = 16L * 1024 * 1024;

    // Read-only diagnostics exported through BrowserGpu.installFrameCallback.
    // The render path is single-threaded on WasmGC; approximate counters are
    // also sufficient on the experimental WasmLM lane.
    private static long directSkippedCalls;
    private static long directSkippedBytes;
    private static long mappedSkippedCalls;
    private static long mappedSkippedBytes;
    /** Bytes a dirty-span flush did not upload but the whole-extent flush would have. */
    private static long mappedTrimmedBytes;
    private static long retainedSnapshotBytes;
    private static long peakRetainedSnapshotBytes;
    private static long peakSnapshotGrowthBytes;
    private static int retainedSnapshotBuffers;
    private static int peakRetainedSnapshotBuffers;
    private static int maxRetainedSnapshotBytes;
    private static long snapshotTooLargeMaps;
    private static long snapshotBudgetMisses;
    private static long overlappingMaps;

    private final int handle;
    private final int physicalSize;
    private byte[] mappingShadow;
    private long mappingAddress;
    /**
     * True only while {@link #mappingShadow} exactly describes the GPU buffer.
     * WebGPU buffers start zero-filled, and every CPU upload below updates both
     * copies. Encoder-side GPU copies invalidate the destination explicitly in
     * {@link WebGpuCommandEncoderBackend#copyToBuffer}; a later partial CPU
     * write cannot make an otherwise unknown buffer coherent again.
     */
    private boolean shadowCoherent;
    /** Reused old-value snapshot for the common, non-overlapping mapped write. */
    private byte[] mappingSnapshot;
    private boolean mappingSnapshotBorrowed;
    private boolean closed;
    private int mappingRefCount;

    WebGpuBuffer(String label, int usage, long size) {
        this(label, usage, size, true);
    }

    WebGpuBuffer(String label, int usage, long size, boolean eagerShadow) {
        super(usage, checkedSize(size));
        this.physicalSize = physicalSize(size);

        if (eagerShadow && physicalSize < EAGER_SHADOW_LIMIT) {
            allocateShadow();
        } else if (physicalSize >= EAGER_SHADOW_LIMIT) {
            reportDeferredShadow(label, physicalSize, usage);
        }
        this.handle = BrowserGpu.createBuffer(label, usage, physicalSize);
        // WebGPU guarantees newly-created buffers are zero-initialized, which
        // is also the state of a freshly allocated Java shadow.
        this.shadowCoherent = mappingShadow != null;
    }

    WebGpuBuffer(String label, int usage, ByteBuffer initialData) {
        super(usage, initialData.remaining());
        this.physicalSize = physicalSize(size());

        this.handle = BrowserGpu.createBuffer(label, usage | COPY_DST, physicalSize);

        // This constructor seeds contents from CPU data, so the upload needs a
        // staging array either way; only whether it is retained differs.
        if (physicalSize < EAGER_SHADOW_LIMIT) {
            allocateShadow();
            ByteBuffer source = initialData.duplicate();
            source.get(mappingShadow, 0, source.remaining());
            BrowserGpu.writeBuffer(handle, 0, mappingShadow, 0, physicalSize);
            shadowCoherent = true;
        } else {
            reportDeferredShadow(label, physicalSize, usage);
            // Seed through a small reusable staging array: a byte[physicalSize]
            // here would be the very allocation the deferred shadow exists to
            // avoid.
            ByteBuffer source = initialData.duplicate();
            byte[] staging = BrowserGpu.allocate(Math.min(UPLOAD_CHUNK, physicalSize), "seedStaging");
            for (int written = 0; written < physicalSize; written += staging.length) {
                int span = Math.min(staging.length, physicalSize - written);
                int available = Math.min(span, source.remaining());
                if (available > 0) {
                    source.get(staging, 0, available);
                }
                java.util.Arrays.fill(staging, available, span, (byte) 0);
                BrowserGpu.writeBuffer(handle, written, staging, 0, span);
            }
        }
    }

    /** Materialises the CPU shadow, for construction or a later {@code map()}. */
    private byte[] allocateShadow() {
        if (mappingShadow == null) {
            mappingShadow = BrowserGpu.allocate(physicalSize, "mappingShadow");
            mappingAddress = BrowserNativeMemory.registerExternal(mappingShadow);
        }
        return mappingShadow;
    }

    private static void reportDeferredShadow(String label, int bytes, int usage) {
        try {
            BrowserGpu.reportProgress("webgpu:shadow-deferred " + label
                    + " " + (bytes / (1024 * 1024)) + "MiB usage=" + usage);
        } catch (Throwable ignored) {
            // Telemetry must never break buffer creation.
        }
    }

    private static long checkedSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE - 3L) {
            throw new IllegalArgumentException("WebGPU buffer size is out of range: " + size);
        }
        return size;
    }

    private static int physicalSize(long size) {
        checkedSize(size);
        return Math.max(4, (int) ((size + 3L) & ~3L));
    }

    int handle() {
        if (closed) {
            throw new IllegalStateException("Buffer is closed");
        }
        return handle;
    }

    // DIAG accessors (used by the one-shot atlas-upload trace). Remove with it.
    byte[] peekShadow() { return mappingShadow; }
    byte[] coherentShadow() { return shadowCoherent ? mappingShadow : null; }
    int physicalSizeForDiag() { return physicalSize; }

    void upload(long destinationOffset, ByteBuffer sourceData) {
        handle();
        int length = sourceData.remaining();
        if (destinationOffset < 0 || destinationOffset > size() - length) {
            throw new IllegalArgumentException("Buffer upload is outside the destination slice");
        }

        ByteBuffer source = sourceData.duplicate();
        if (mappingShadow != null) {
            int destination = (int) destinationOffset;
            // This check happens before changing the Java shadow and therefore
            // before the packed-UTF16 bridge allocates or materialises a JS
            // String. It is exact, not a hash: a collision can never suppress
            // a real Minecraft buffer update.
            if (shadowCoherent && matches(source, mappingShadow, destination, length)) {
                directSkippedCalls++;
                directSkippedBytes += length;
                return;
            }
            source.get(mappingShadow, destination, length);
            flush(destinationOffset, length);
            if (destinationOffset == 0 && length == size()) {
                shadowCoherent = true;
            }
            return;
        }

        // queue.writeBuffer requires four-byte aligned offsets and sizes; pad
        // the tail with zeroes inside the (already rounded) physical buffer.
        if ((destinationOffset & 3L) != 0) {
            throw new IllegalArgumentException(
                    "Non-mappable WebGPU buffer writes must be four-byte aligned"
            );
        }
        int paddedLength = (length + 3) & ~3;
        if (source.hasArray()) {
            writeArrayPadded(destinationOffset, source, length);
        } else {
            byte[] bytes = BrowserGpu.allocate(paddedLength, "bufferUpload");
            source.get(bytes, 0, length);
            writeChunked(destinationOffset, bytes, paddedLength);
        }
    }

    /**
     * Largest slice handed to the host upload bridge at once. It stays a
     * multiple of 3 for the WasmGC base64 fallback and of 4 for
     * {@code queue.writeBuffer}-aligned chunk offsets and sizes.
     */
    private static final int UPLOAD_CHUNK = 768 * 1024;

    /**
     * Uploads in bounded slices.
     *
     * <p>On WasmLM the bridge views the existing array in linear memory, so
     * chunking bounds the browser's synchronous GPU call without creating a
     * second payload. WasmGC keeps the old base64 fallback, where chunking caps
     * the transient range-copy/string cost.</p>
     */
    private void writeChunked(final long destinationOffset, final byte[] bytes, final int length) {
        writeArrayChunked(destinationOffset, bytes, 0, length);
    }

    /** Writes an already four-byte-compatible array range without copying it. */
    private void writeArrayChunked(
            final long destinationOffset,
            final byte[] bytes,
            final int sourceOffset,
            final int length
    ) {
        for (int written = 0; written < length; written += UPLOAD_CHUNK) {
            int span = Math.min(UPLOAD_CHUNK, length - written);
            BrowserGpu.writeBuffer(
                    handle,
                    (int) (destinationOffset + written),
                    bytes,
                    sourceOffset + written,
                    span
            );
        }
    }

    /**
     * Uploads a ByteBuffer whose backing array is already the browser-native
     * storage. Only a final 1-3 byte tail needs a small padding array; the
     * aligned bulk remains in place all the way to WebGPU.
     */
    private void writeArrayPadded(long destinationOffset, ByteBuffer source, int length) {
        int sourceOffset = source.arrayOffset() + source.position();
        int aligned = length & ~3;
        if (aligned != 0) {
            writeArrayChunked(destinationOffset, source.array(), sourceOffset, aligned);
        }
        if (aligned != length) {
            byte[] tail = new byte[4];
            ByteBuffer remainder = source.duplicate();
            remainder.position(aligned);
            remainder.get(tail, 0, length - aligned);
            BrowserGpu.writeBuffer(handle, (int) (destinationOffset + aligned), tail, 0, 4);
        }
    }

    /** Exact ByteBuffer-range comparison that leaves the source position untouched. */
    private static boolean matches(
            ByteBuffer source,
            byte[] destination,
            int destinationOffset,
            int length
    ) {
        int sourcePosition = source.position();
        if (source.hasArray()) {
            byte[] array = source.array();
            int sourceOffset = source.arrayOffset() + sourcePosition;
            for (int i = 0; i < length; i++) {
                if (array[sourceOffset + i] != destination[destinationOffset + i]) {
                    return false;
                }
            }
            return true;
        }
        for (int i = 0; i < length; i++) {
            if (source.get(sourcePosition + i) != destination[destinationOffset + i]) {
                return false;
            }
        }
        return true;
    }

    /** {@link #dirtySpan} result meaning "the mapped range is unchanged". */
    private static final long NO_DIRTY_SPAN = -1L;

    /**
     * The range a caller actually modified in a mapped view, as
     * {@code (first << 32) | last}, or {@link #NO_DIRTY_SPAN} if nothing
     * changed. Both bounds are inclusive and relative to the mapped range.
     *
     * <p>This replaces a plain "did anything change" comparison, at the same
     * scan cost, because the answer that matters is <em>which</em> bytes
     * changed. Unmapping used to re-upload the whole mapped extent whenever a
     * single byte differed, and Minecraft maps far more than it writes: on
     * hoplite.gg the 256 KiB shared staging buffer took 469 KiB of uploads per
     * frame — 91% of all upload traffic, and every byte of it crosses the
     * WasmGC bridge two bytes per boundary call.</p>
     *
     * <p>A scattered write still reports one span covering the outermost
     * changes, which is exactly today's behaviour; a sequentially filled ring
     * buffer — the case that costs — reports only what it filled.</p>
     */
    private static long dirtySpan(
            byte[] left,
            int leftOffset,
            byte[] right,
            int rightOffset,
            int length
    ) {
        int first = 0;
        while (first < length && left[leftOffset + first] == right[rightOffset + first]) {
            first++;
        }
        if (first == length) {
            return NO_DIRTY_SPAN;
        }
        int last = length - 1;
        while (last > first && left[leftOffset + last] == right[rightOffset + last]) {
            last--;
        }
        return ((long) first << 32) | (last & 0xFFFFFFFFL);
    }

    @Override
    public GpuBufferSlice.MappedView map(
            long offset,
            long length,
            boolean read,
            boolean write
    ) {
        handle();
        if (!read && !write) {
            throw new IllegalArgumentException("At least read or write must be true");
        }
        if (read && (usage() & MAP_READ) == 0) {
            throw new IllegalStateException("Buffer is not readable");
        }
        if (write && (usage() & MAP_WRITE) == 0) {
            throw new IllegalStateException("Buffer is not writable");
        }
        if (offset < 0 || length < 0 || length > Integer.MAX_VALUE
                || offset > size() - length) {
            throw new IllegalArgumentException("Mapped buffer range is out of bounds");
        }
        if (read) {
            // Read mapping is served from the CPU shadow rather than the GPU
            // buffer. For the CPU-authored buffers that make up almost all of
            // this port's traffic the shadow *is* the contents, so this is
            // exact; only buffers the GPU wrote come back stale (zeros),
            // because browser WebGPU can read those back solely through the
            // async mapAsync, which cannot serve a synchronous caller.
            //
            // That degradation is confined to capture/debug by construction:
            // in the transformed JAR the only map(read=true, write=false) call
            // sites are TracyFrameCapture, TextureUtil and the Vulkan-only
            // AmdCheckpointExtension. Every renderer on the gameplay path --
            // CloudRenderer, PostPass, FogRenderer, Lightmap and
            // StagingBuffer$PersistentlyMapped -- maps write-only. Throwing
            // here took down the whole render frame with a ReportedException,
            // which is a bad trade for a profiler read.
            noteUnsupportedRead();
        }

        // map(write) exposes the shadow itself, so by close() time the previous
        // bytes would otherwise be gone. Preserve exactly the mapped range.
        // The renderer opens and closes these views synchronously; retain one
        // reusable snapshot per buffer, with a private fallback only for an
        // overlapping map so nested use remains correct.
        byte[] previous = null;
        boolean borrowedReusableSnapshot = false;
        int mappedOffset = (int) offset;
        int mappedLength = (int) length;
        // A large buffer skips the shadow at construction; if it turns out to
        // be mapped after all, it needs one now. The new zero-filled shadow is
        // deliberately still incoherent with any earlier GPU writes.
        byte[] shadow = allocateShadow();
        if (write && shadowCoherent && mappedLength > 0) {
            if (mappedLength > MAPPING_SNAPSHOT_LIMIT) {
                snapshotTooLargeMaps++;
            } else if (mappingSnapshotBorrowed) {
                // Correctness does not require a nested allocation: without a
                // previous-value snapshot this view simply uploads on close.
                overlappingMaps++;
            } else {
                if (mappingSnapshot == null || mappingSnapshot.length < mappedLength) {
                    int oldLength = mappingSnapshot == null ? 0 : mappingSnapshot.length;
                    long nextRetained = retainedSnapshotBytes - oldLength + mappedLength;
                    if (nextRetained <= TOTAL_MAPPING_SNAPSHOT_LIMIT) {
                        // During growth both arrays can be live until the old
                        // one is collected; record that conservative transient
                        // high-water mark separately from retained bytes.
                        peakSnapshotGrowthBytes = Math.max(
                                peakSnapshotGrowthBytes,
                                retainedSnapshotBytes + mappedLength
                        );
                        mappingSnapshot = BrowserGpu.allocate(mappedLength, "mappingSnapshot");
                        retainedSnapshotBytes = nextRetained;
                        if (oldLength == 0) {
                            retainedSnapshotBuffers++;
                            peakRetainedSnapshotBuffers = Math.max(
                                    peakRetainedSnapshotBuffers,
                                    retainedSnapshotBuffers
                            );
                        }
                        peakRetainedSnapshotBytes = Math.max(
                                peakRetainedSnapshotBytes,
                                retainedSnapshotBytes
                        );
                        maxRetainedSnapshotBytes = Math.max(
                                maxRetainedSnapshotBytes,
                                mappedLength
                        );
                    } else {
                        snapshotBudgetMisses++;
                    }
                }
                if (mappingSnapshot != null && mappingSnapshot.length >= mappedLength) {
                    previous = mappingSnapshot;
                    mappingSnapshotBorrowed = true;
                    borrowedReusableSnapshot = true;
                }
            }
            if (previous != null) {
                System.arraycopy(shadow, mappedOffset, previous, 0, mappedLength);
            }
        }

        mappingRefCount++;
        ByteBuffer view = ByteBuffer.wrap(
                shadow,
                (int) offset,
                (int) length
        ).slice().order(ByteOrder.LITTLE_ENDIAN);
        BrowserNativeMemory.register(view, mappingAddress + offset);
        boolean[] mappingClosed = {false};
        byte[] previousBytes = previous;
        boolean releaseReusableSnapshot = borrowedReusableSnapshot;
        Runnable unmap = () -> {
            if (mappingClosed[0]) {
                return;
            }
            mappingClosed[0] = true;
            try {
                // Only a write mapping has anything to push back. Flushing a
                // read-only mapping re-uploaded the range for nothing, and the
                // cost was not academic: Screenshot maps 1280x720x4 read-only,
                // so every capture base64-encoded 3.6 MiB in 768 KiB chunks --
                // ~4.8 MiB of transient String and byte[] per screenshot, on
                // the frame that already carries the readback.
                long span = write && previousBytes != null
                        ? dirtySpan(shadow, mappedOffset, previousBytes, 0, mappedLength)
                        : 0L;
                boolean unchanged = span == NO_DIRTY_SPAN;
                if (unchanged) {
                    mappedSkippedCalls++;
                    mappedSkippedBytes += mappedLength;
                }
                if (write && !unchanged) {
                    if (previousBytes != null) {
                        // Upload only what the caller wrote. This is safe for
                        // exactly the reason the "unchanged -> skip" case above
                        // is: a snapshot is taken only when `shadowCoherent`
                        // held at map time (see map()), so every byte equal to
                        // the snapshot is already the byte on the GPU. Without
                        // a snapshot (budget miss, oversized or overlapping
                        // map) there is nothing to diff against and the whole
                        // extent still goes.
                        int first = (int) (span >>> 32);
                        int last = (int) span;
                        mappedTrimmedBytes += mappedLength - (last - first + 1);
                        flush(offset + first, last - first + 1);
                    } else {
                        flush(offset, length);
                    }
                    if (offset == 0 && length == size()) {
                        shadowCoherent = true;
                    }
                }
            } finally {
                mappingRefCount--;
                if (releaseReusableSnapshot) {
                    mappingSnapshotBorrowed = false;
                }
            }
        };
        return new GpuBufferSlice.MappedView(slice(offset, length), view, unmap);
    }

    private void flush(long offset, long length) {
        if (length == 0) {
            return;
        }
        int start = (int) (offset & ~3L);
        int end = (int) ((offset + length + 3L) & ~3L);
        handle();
        // Chunked for the same reason as upload(): a whole-range base64 encode
        // of a large mapped region is a multi-megabyte transient spike.
        for (int written = start; written < end; written += UPLOAD_CHUNK) {
            int span = Math.min(UPLOAD_CHUNK, end - written);
            BrowserGpu.writeBuffer(
                    handle,
                    written,
                    mappingShadow,
                    written,
                    span
            );
        }
    }

    /**
     * Marks the Java mirror unknown after a GPU-only write to this buffer.
     * Buffer copies are the only such mutation in this backend: render passes
     * bind vertex/index/uniform/indirect (and read-only texel) buffers, and the
     * texture-readback methods are deliberately unsupported and leave their
     * destinations untouched.
     */
    void invalidateShadow() {
        shadowCoherent = false;
    }

    @WasmExport(value = "mcweb.gpu.dedup.directCalls", comment = "Skipped direct uploads")
    public static long directSkippedCalls() { return directSkippedCalls; }

    @WasmExport(value = "mcweb.gpu.dedup.directBytes", comment = "Skipped direct upload bytes")
    public static long directSkippedBytes() { return directSkippedBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.mappedCalls", comment = "Skipped mapped flushes")
    public static long mappedSkippedCalls() { return mappedSkippedCalls; }

    @WasmExport(value = "mcweb.gpu.dedup.mappedBytes", comment = "Skipped mapped flush bytes")
    public static long mappedSkippedBytes() { return mappedSkippedBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.mappedTrimmedBytes",
            comment = "Mapped bytes a dirty-span flush avoided uploading")
    public static long mappedTrimmedBytes() { return mappedTrimmedBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotBytes", comment = "Retained snapshot bytes")
    public static long retainedSnapshotBytes() { return retainedSnapshotBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotPeakBytes", comment = "Peak retained snapshot bytes")
    public static long peakRetainedSnapshotBytes() { return peakRetainedSnapshotBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotGrowthPeak", comment = "Peak snapshot growth bytes")
    public static long peakSnapshotGrowthBytes() { return peakSnapshotGrowthBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotBuffers", comment = "Retained snapshot buffers")
    public static int retainedSnapshotBuffers() { return retainedSnapshotBuffers; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotPeakBuffers", comment = "Peak snapshot buffers")
    public static int peakRetainedSnapshotBuffers() { return peakRetainedSnapshotBuffers; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotMaxSingle", comment = "Largest retained snapshot")
    public static int maxRetainedSnapshotBytes() { return maxRetainedSnapshotBytes; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotTooLarge", comment = "Oversize maps not deduplicated")
    public static long snapshotTooLargeMaps() { return snapshotTooLargeMaps; }

    @WasmExport(value = "mcweb.gpu.dedup.snapshotBudgetMisses", comment = "Snapshot budget misses")
    public static long snapshotBudgetMisses() { return snapshotBudgetMisses; }

    @WasmExport(value = "mcweb.gpu.dedup.overlappingMaps", comment = "Overlapping maps not deduplicated")
    public static long overlappingMaps() { return overlappingMaps; }

    /**
     * CPU view of buffer contents for staging copies into textures. Only
     * buffers with a write-mapping shadow are readable; browser WebGPU has no
     * synchronous GPU readback.
     */
    byte[] readBytes(long offset, int length) {
        handle();
        if (mappingShadow == null || !shadowCoherent) {
            // A missing shadow or one invalidated by a GPU-side copy cannot be
            // used as CPU truth. Browser WebGPU can read it back only
            // asynchronously. Throwing took down the render frame; the callers
            // already tolerate degraded capture/copy output, so return zeros —
            // a black result beats stale bytes or a crashed game.
            noteUnsupportedRead();
            return BrowserGpu.allocate(Math.max(0, length), "readBytesNoShadow");
        }
        if (offset < 0 || length < 0) {
            throw new IllegalArgumentException(
                    "Buffer read range is negative: offset=" + offset + " length=" + length);
        }
        byte[] result = BrowserGpu.allocate(length, "readBytes");
        /*
         * A read past the shadow used to throw, and that throw travelled all the
         * way out through the resource reload: applying a large server resource
         * pack failed with "Buffer read range is out of bounds" and the server
         * then disconnected the player for failing to load its pack. Serve what
         * exists and zero the remainder, for the same reason the missing-shadow
         * branch above returns zeros -- a wrong texture region beats losing the
         * connection -- and report the numbers once so the real overrun is still
         * findable.
         */
        int available = (int) Math.max(0, Math.min(length, mappingShadow.length - offset));
        if (available < length) {
            noteShortRead(offset, length, mappingShadow.length);
        }
        if (available > 0) {
            System.arraycopy(mappingShadow, (int) offset, result, 0, available);
        }
        return result;
    }

    private static boolean reportedShortRead;

    private static void noteShortRead(long offset, int length, int shadowLength) {
        if (reportedShortRead) {
            return;
        }
        reportedShortRead = true;
        try {
            BrowserGpu.reportProgress("webgpu:short-buffer-read offset=" + offset
                    + " length=" + length + " shadow=" + shadowLength
                    + " missing=" + (offset + length - shadowLength));
        } catch (Throwable ignored) {
            // Diagnostics must never take down an upload.
        }
    }

    private static boolean reportedUnsupportedRead;

    private static void noteUnsupportedRead() {
        if (reportedUnsupportedRead) {
            return;
        }
        reportedUnsupportedRead = true;
        try {
            BrowserGpu.reportProgress(
                    "webgpu:synchronous buffer read served from the CPU shadow "
                            + "(capture/debug only; GPU-written contents read as zeros)"
            );
        } catch (Throwable ignored) {
            // Diagnostics must never be the thing that breaks a frame.
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        if (mappingRefCount != 0) {
            throw new IllegalStateException("Attempt to close a mapped buffer");
        }
        BrowserGpu.destroyBuffer(handle);
        if (mappingAddress != 0) {
            BrowserNativeMemory.free(mappingAddress);
        }
        mappingShadow = null;
        if (mappingSnapshot != null) {
            retainedSnapshotBytes -= mappingSnapshot.length;
            retainedSnapshotBuffers--;
        }
        mappingSnapshot = null;
        shadowCoherent = false;
        closed = true;
    }
}
