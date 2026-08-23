package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassBackend;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Collection;
import java.util.function.Supplier;
import org.lwjgl.PointerBuffer;

/**
 * Browser render pass.  The state/cache and draw batching mirror the command
 * lifecycle of Mojang's Vulkan backend while retaining WebGPU as the actual
 * browser backend.  The immediate bridge remains selectable with
 * {@code ?mcweb_gpu_immediate=1} for regression bisects.
 */
final class WebGpuRenderPass implements RenderPassBackend {
    private static final int INDEXED_DRAW_TUPLE_INTS = 3;
    private static final int DRAW_TUPLE_INTS = 2;
    private static final int INDIRECT_INDEXED_STRIDE = 20;
    private static final int INDIRECT_STRIDE = 16;

    /* MCRP v1. Keep these opcode values in sync with
       web/render-command-stream.js. */
    private static final int COMMAND_MAGIC = 0x4d435250;
    private static final int COMMAND_VERSION = 1;
    private static final int CMD_SET_PIPELINE = 1;
    private static final int CMD_BIND_TEXTURE = 2;
    private static final int CMD_SET_UNIFORM = 3;
    private static final int CMD_SET_VERTEX_BUFFER = 4;
    private static final int CMD_SET_INDEX_BUFFER = 5;
    private static final int CMD_SCISSOR = 6;
    private static final int CMD_DISABLE_SCISSOR = 7;
    private static final int CMD_DRAW = 8;
    private static final int CMD_DRAW_INDEXED = 9;
    private static final int CMD_DRAW_INDIRECT = 10;
    private static final int CMD_DRAW_INDEXED_INDIRECT = 11;
    private static final int CMD_PUSH_DEBUG_GROUP = 12;
    private static final int CMD_POP_DEBUG_GROUP = 13;
    /* Command encoders permit only one active render pass. Render-pass creation
       and submission are render-thread confined, so retain the largest buffer
       for the next pass instead of allocating and repeatedly growing one for
       every pass (about 75,000 arrays in a two-minute gameplay run). */
    private static int[] spareCommandWords;

    private final int handle;
    private final WebGpuDeviceBackend device;
    private final boolean commandStreamEnabled;
    private final boolean batchEnabled;
    private boolean pipelineValid;

    /* Numeric render commands. The host consumes the view synchronously, so
       the array can return to the render-thread pool after pass submission. */
    private int[] commandWords;
    private int commandWordCount;

    /* Five ints per indexed draw; four per non-indexed draw. These arrays are
       used only by the legacy immediate path. */
    private int[] indexedBatch;
    private int indexedBatchCount;
    private int[] drawBatch;
    private int drawBatchCount;

    /* Vulkan-style command state cache. Buffer contents may mutate, but a
       repeated binding of the same range is still the same GPU state. */
    private int lastPipeline = Integer.MIN_VALUE;
    private final int[] lastVertexBuffer = new int[8];
    private final int[] lastVertexOffset = new int[8];
    private final int[] lastVertexSize = new int[8];
    private int lastIndexBuffer = Integer.MIN_VALUE;
    private String lastIndexFormat;
    private boolean scissorEnabled;
    private int scissorX;
    private int scissorY;
    private int scissorWidth;
    private int scissorHeight;

    /* Uniform and texture bindings, keyed by name. Same justification as the
       cache above, and load-bearing for batching rather than merely saving a
       bridge call: setUniform is issued more often than draw (measured
       1,431,302 against 1,155,456 in one 60 s window), so without this every
       draw is still preceded by a flush and no batch can exceed one draw.
       Allocated lazily because a pass is constructed per render pass. */
    private java.util.HashMap<String, int[]> lastUniform;
    private java.util.HashMap<String, int[]> lastTexture;

    WebGpuRenderPass(int handle, WebGpuDeviceBackend device) {
        this.handle = handle;
        this.device = device;
        this.commandStreamEnabled = BrowserGpu.renderPassCommandStreamEnabled();
        this.batchEnabled = !commandStreamEnabled && BrowserGpu.drawBatchEnabled() != 0;
        if (commandStreamEnabled) {
            commandWords = acquireCommandWords();
        } else {
            indexedBatch = new int[40];
            drawBatch = new int[32];
        }
        Arrays.fill(lastVertexBuffer, Integer.MIN_VALUE);
    }

    int handle() {
        return handle;
    }

    @Override
    public void pushDebugGroup(Supplier<String> label) {
        flushBatches();
        String value = label.get();
        if (commandStreamEnabled) {
            command1(CMD_PUSH_DEBUG_GROUP, BrowserGpu.bindingNameId(value));
        } else {
            BrowserGpu.rpPushDebugGroup(handle, value);
        }
    }

    @Override
    public void popDebugGroup() {
        flushBatches();
        if (commandStreamEnabled) {
            command0(CMD_POP_DEBUG_GROUP);
        } else {
            BrowserGpu.rpPopDebugGroup(handle);
        }
    }

    /*
     * Flush inside the changed-state branch, never before it.
     *
     * A queued draw belongs to the state that was current when it was queued, so
     * the batch must be flushed before any state actually changes -- but a call
     * that changes nothing must not flush, or the batch can never hold more than
     * one draw. Mojang sets pipeline, vertex buffer, and index buffer before
     * every section draw with near-identical values, so an unconditional flush
     * closed every batch at exactly one draw: the census read `rpDrawBatch`
     * n == `rpDraw` n == 3,206,926. At size one the batch path is a net loss,
     * because `encodeIntBatch` base64s a single draw for nothing.
     *
     * The same shape applies to setVertexBuffer, setIndexBuffer, enableScissor,
     * disableScissor, setUniform, and bindTexture.
     */
    @Override
    public void setPipeline(RenderPipeline pipeline) {
        CompiledRenderPipeline compiled = device.compiledPipelineFor(pipeline);
        pipelineValid = compiled != null && compiled.isValid();
        int next = pipelineValid ? ((WebGpuCompiledPipeline) compiled).handle() : 0;
        if (next != lastPipeline) {
            flushBatches();
            if (commandStreamEnabled) {
                command1(CMD_SET_PIPELINE, next);
            } else {
                BrowserGpu.rpSetPipeline(handle, next);
            }
            lastPipeline = next;
        }
    }

    @Override
    public void bindTexture(String name, GpuTextureView textureView, GpuSampler sampler) {
        int view = ((WebGpuTextureView) textureView).handle();
        int samplerHandle = ((WebGpuSampler) sampler).handle();
        if (lastTexture == null) {
            lastTexture = new java.util.HashMap<>();
        }
        int[] last = lastTexture.get(name);
        if (last != null && last[0] == view && last[1] == samplerHandle) {
            return;
        }
        flushBatches();
        // Cache the interned id next to the redundancy state so a repeat binding
        // costs neither a host string nor a second intern lookup.
        int nameId = last != null ? last[2] : BrowserGpu.bindingNameId(name);
        if (commandStreamEnabled) {
            command3(CMD_BIND_TEXTURE, nameId, view, samplerHandle);
        } else {
            BrowserGpu.rpBindTexture(handle, nameId, view, samplerHandle);
        }
        if (last == null) {
            lastTexture.put(name, new int[] {view, samplerHandle, nameId});
        } else {
            last[0] = view;
            last[1] = samplerHandle;
        }
    }

    @Override
    public void setUniform(String name, GpuBuffer buffer) {
        setUniform(name, ((WebGpuBuffer) buffer).handle(), 0, (int) buffer.size());
    }

    @Override
    public void setUniform(String name, GpuBufferSlice slice) {
        setUniform(
                name,
                ((WebGpuBuffer) slice.buffer()).handle(),
                (int) slice.offset(),
                (int) slice.length()
        );
    }

    private void setUniform(String name, int buffer, int offset, int size) {
        if (lastUniform == null) {
            lastUniform = new java.util.HashMap<>();
        }
        int[] last = lastUniform.get(name);
        if (last != null && last[0] == buffer && last[1] == offset && last[2] == size) {
            return;
        }
        flushBatches();
        int nameId = last != null ? last[3] : BrowserGpu.bindingNameId(name);
        if (commandStreamEnabled) {
            command4(CMD_SET_UNIFORM, nameId, buffer, offset, size);
        } else {
            BrowserGpu.rpSetUniform(handle, nameId, buffer, offset, size);
        }
        if (last == null) {
            lastUniform.put(name, new int[] {buffer, offset, size, nameId});
        } else {
            last[0] = buffer;
            last[1] = offset;
            last[2] = size;
        }
    }

    @Override
    public void enableScissor(int x, int y, int width, int height) {
        if (!scissorEnabled || scissorX != x || scissorY != y
                || scissorWidth != width || scissorHeight != height) {
            flushBatches();
            if (commandStreamEnabled) {
                command4(CMD_SCISSOR, x, y, width, height);
            } else {
                BrowserGpu.rpScissor(handle, x, y, width, height);
            }
            scissorEnabled = true;
            scissorX = x;
            scissorY = y;
            scissorWidth = width;
            scissorHeight = height;
        }
    }

    @Override
    public void disableScissor() {
        if (scissorEnabled) {
            flushBatches();
            if (commandStreamEnabled) {
                command0(CMD_DISABLE_SCISSOR);
            } else {
                BrowserGpu.rpDisableScissor(handle);
            }
            scissorEnabled = false;
        }
    }

    @Override
    public void setVertexBuffer(int slot, GpuBufferSlice slice) {
        int buffer = ((WebGpuBuffer) slice.buffer()).handle();
        int offset = (int) slice.offset();
        int size = (int) slice.length();
        if (slot < 0 || slot >= lastVertexBuffer.length
                || lastVertexBuffer[slot] != buffer
                || lastVertexOffset[slot] != offset
                || lastVertexSize[slot] != size) {
            flushBatches();
            if (commandStreamEnabled) {
                command4(CMD_SET_VERTEX_BUFFER, slot, buffer, offset, size);
            } else {
                BrowserGpu.rpSetVertexBuffer(handle, slot, buffer, offset, size);
            }
            if (slot >= 0 && slot < lastVertexBuffer.length) {
                lastVertexBuffer[slot] = buffer;
                lastVertexOffset[slot] = offset;
                lastVertexSize[slot] = size;
            }
        }
    }

    @Override
    public void setIndexBuffer(GpuBuffer buffer, IndexType indexType) {
        int next = ((WebGpuBuffer) buffer).handle();
        String format = indexType == IndexType.SHORT ? "uint16" : "uint32";
        if (next != lastIndexBuffer || !format.equals(lastIndexFormat)) {
            flushBatches();
            if (commandStreamEnabled) {
                command2(CMD_SET_INDEX_BUFFER, next, indexType == IndexType.SHORT ? 0 : 1);
            } else {
                BrowserGpu.rpSetIndexBuffer(handle, next, format);
            }
            lastIndexBuffer = next;
            lastIndexFormat = format;
        }
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int baseVertex, int firstInstance) {
        if (pipelineValid) {
            queueIndexed(indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(IntBuffer drawParameters, int stride, int firstInstance, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        int tupleStrideInts = Math.max(INDEXED_DRAW_TUPLE_INTS, stride / 4);
        int base = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            int tuple = base + i * tupleStrideInts;
            queueIndexed(drawParameters.get(tuple + 1), 1, drawParameters.get(tuple),
                    drawParameters.get(tuple + 2), firstInstance);
        }
    }

    @Override
    public void multiDrawIndexed(PointerBuffer firstIndices, IntBuffer indexCounts,
            IntBuffer baseVertices, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        int firstBase = firstIndices.position();
        int countBase = indexCounts.position();
        int vertexBase = baseVertices.position();
        for (int i = 0; i < drawCount; i++) {
            queueIndexed(indexCounts.get(countBase + i), 1,
                    (int) firstIndices.get(firstBase + i), baseVertices.get(vertexBase + i), 0);
        }
    }

    @Override
    public void drawIndexedIndirect(GpuBufferSlice indirectCommands, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        flushBatches();
        WebGpuBuffer buffer = (WebGpuBuffer) indirectCommands.buffer();
        int start = (int) indirectCommands.offset();
        for (int i = 0; i < drawCount; i++) {
            if (commandStreamEnabled) {
                command3(CMD_DRAW_INDEXED_INDIRECT,
                        buffer.handle(), start + i * INDIRECT_INDEXED_STRIDE, 1);
            } else {
                BrowserGpu.rpDrawIndexedIndirect(
                        handle, buffer.handle(), start + i * INDIRECT_INDEXED_STRIDE, 1
                );
            }
        }
    }

    @Override
    public <T> void drawMultipleIndexed(Collection<RenderPass.Draw<T>> draws,
            GpuBuffer fallbackIndexBuffer, IndexType fallbackIndexType,
            Collection<String> uniformNames, T context) {
        if (!pipelineValid) {
            return;
        }
        for (RenderPass.Draw<T> draw : draws) {
            GpuBuffer vertexBuffer = draw.vertexBuffer();
            if (vertexBuffer != null) {
                setVertexBuffer(draw.slot(), vertexBuffer.slice());
            }
            GpuBuffer indexBuffer = draw.indexBuffer() != null ? draw.indexBuffer() : fallbackIndexBuffer;
            IndexType indexType = draw.indexBuffer() != null ? draw.indexType() : fallbackIndexType;
            if (indexBuffer != null) {
                setIndexBuffer(indexBuffer, indexType);
            }
            if (draw.uniformUploaderConsumer() != null) {
                draw.uniformUploaderConsumer().accept(context, this::setUniform);
            }
            queueIndexed(draw.indexCount(), 1, draw.firstIndex(), draw.baseVertex(), 0);
        }
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        if (pipelineValid) {
            queueDraw(firstVertex, vertexCount, instanceCount, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer drawParameters, int stride, int firstInstance, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        int tupleStrideInts = Math.max(DRAW_TUPLE_INTS, stride / 4);
        int base = drawParameters.position();
        for (int i = 0; i < drawCount; i++) {
            int tuple = base + i * tupleStrideInts;
            queueDraw(drawParameters.get(tuple), drawParameters.get(tuple + 1), 1, firstInstance);
        }
    }

    @Override
    public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        int firstBase = firstVertices.position();
        int countBase = vertexCounts.position();
        for (int i = 0; i < drawCount; i++) {
            queueDraw(firstVertices.get(firstBase + i), vertexCounts.get(countBase + i), 1, 0);
        }
    }

    @Override
    public void drawIndirect(GpuBufferSlice indirectCommands, int drawCount) {
        if (!pipelineValid) {
            return;
        }
        flushBatches();
        WebGpuBuffer buffer = (WebGpuBuffer) indirectCommands.buffer();
        int start = (int) indirectCommands.offset();
        for (int i = 0; i < drawCount; i++) {
            if (commandStreamEnabled) {
                command3(CMD_DRAW_INDIRECT, buffer.handle(), start + i * INDIRECT_STRIDE, 1);
            } else {
                BrowserGpu.rpDrawIndirect(handle, buffer.handle(), start + i * INDIRECT_STRIDE, 1);
            }
        }
    }

    @Override
    public void writeTimestamp(GpuQueryPool queryPool, int index) {
        flushBatches();
        flushCommandStream(false);
        ((WebGpuQueryPool) queryPool).write(index, System.nanoTime());
    }

    /** Called by WebGpuCommandEncoderBackend immediately before rpEnd. */
    void flushBatches() {
        if (indexedBatchCount != 0) {
            if (batchEnabled) {
                BrowserGpu.rpDrawIndexedBatch(handle,
                        BrowserGpu.encodeIntBatch(indexedBatch, indexedBatchCount * 5), indexedBatchCount);
            } else {
                for (int i = 0; i < indexedBatchCount; i++) {
                    int base = i * 5;
                    BrowserGpu.rpDrawIndexed(handle, indexedBatch[base], indexedBatch[base + 1],
                            indexedBatch[base + 2], indexedBatch[base + 3], indexedBatch[base + 4]);
                }
            }
            indexedBatchCount = 0;
        }
        if (drawBatchCount != 0) {
            if (batchEnabled) {
                BrowserGpu.rpDrawBatch(handle,
                        BrowserGpu.encodeIntBatch(drawBatch, drawBatchCount * 4), drawBatchCount);
            } else {
                for (int i = 0; i < drawBatchCount; i++) {
                    int base = i * 4;
                    BrowserGpu.rpDraw(handle, drawBatch[base], drawBatch[base + 1],
                            drawBatch[base + 2], drawBatch[base + 3]);
                }
            }
            drawBatchCount = 0;
        }
    }

    private void queueIndexed(int indexCount, int instanceCount, int firstIndex,
            int baseVertex, int firstInstance) {
        if (commandStreamEnabled) {
            command5(CMD_DRAW_INDEXED,
                    indexCount, instanceCount, firstIndex, baseVertex, firstInstance);
            return;
        }
        if (drawBatchCount != 0) {
            flushBatches();
        }
        int needed = (indexedBatchCount + 1) * 5;
        if (needed > indexedBatch.length) {
            indexedBatch = Arrays.copyOf(indexedBatch, Math.max(needed, indexedBatch.length * 2));
        }
        int base = indexedBatchCount++ * 5;
        indexedBatch[base] = indexCount;
        indexedBatch[base + 1] = instanceCount;
        indexedBatch[base + 2] = firstIndex;
        indexedBatch[base + 3] = baseVertex;
        indexedBatch[base + 4] = firstInstance;
    }

    private void queueDraw(int firstVertex, int vertexCount, int instanceCount, int firstInstance) {
        if (commandStreamEnabled) {
            command4(CMD_DRAW, firstVertex, vertexCount, instanceCount, firstInstance);
            return;
        }
        if (indexedBatchCount != 0) {
            flushBatches();
        }
        int needed = (drawBatchCount + 1) * 4;
        if (needed > drawBatch.length) {
            drawBatch = Arrays.copyOf(drawBatch, Math.max(needed, drawBatch.length * 2));
        }
        int base = drawBatchCount++ * 4;
        drawBatch[base] = firstVertex;
        drawBatch[base + 1] = vertexCount;
        drawBatch[base + 2] = instanceCount;
        drawBatch[base + 3] = firstInstance;
    }

    /** Replays all pending commands and optionally closes the host render pass. */
    void end() {
        flushBatches();
        if (commandStreamEnabled) {
            try {
                flushCommandStream(true);
            } finally {
                releaseCommandWords();
            }
        } else {
            BrowserGpu.rpEnd(handle);
        }
    }

    private void flushCommandStream(boolean end) {
        if (!commandStreamEnabled) {
            return;
        }
        if (commandWordCount == 0) {
            if (end) {
                BrowserGpu.rpEnd(handle);
            }
            return;
        }
        if (BrowserGpu.replaySmallRenderPassImmediately(commandWordCount)) {
            replayCommandsImmediately(end);
        } else {
            BrowserGpu.rpCommandStream(handle, commandWords, commandWordCount, end);
        }
        commandWordCount = 0;
    }

    /** Replays a small trusted MCRP buffer without paying WasmGC's Base64 cost. */
    private void replayCommandsImmediately(boolean end) {
        int cursor = 0;
        if (commandWords[cursor++] != COMMAND_MAGIC
                || commandWords[cursor++] != COMMAND_VERSION) {
            throw new IllegalStateException("invalid local render command header");
        }
        while (cursor < commandWordCount) {
            switch (commandWords[cursor++]) {
                case CMD_SET_PIPELINE -> BrowserGpu.rpSetPipeline(
                        handle, commandWords[cursor++]
                );
                case CMD_BIND_TEXTURE -> {
                    BrowserGpu.rpBindTexture(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2]);
                    cursor += 3;
                }
                case CMD_SET_UNIFORM -> {
                    BrowserGpu.rpSetUniform(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2], commandWords[cursor + 3]);
                    cursor += 4;
                }
                case CMD_SET_VERTEX_BUFFER -> {
                    BrowserGpu.rpSetVertexBuffer(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2], commandWords[cursor + 3]);
                    cursor += 4;
                }
                case CMD_SET_INDEX_BUFFER -> {
                    int buffer = commandWords[cursor++];
                    int format = commandWords[cursor++];
                    BrowserGpu.rpSetIndexBuffer(
                            handle, buffer, format == 0 ? "uint16" : "uint32"
                    );
                }
                case CMD_SCISSOR -> {
                    BrowserGpu.rpScissor(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2], commandWords[cursor + 3]);
                    cursor += 4;
                }
                case CMD_DISABLE_SCISSOR -> BrowserGpu.rpDisableScissor(handle);
                case CMD_DRAW -> {
                    BrowserGpu.rpDraw(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2], commandWords[cursor + 3]);
                    cursor += 4;
                }
                case CMD_DRAW_INDEXED -> {
                    BrowserGpu.rpDrawIndexed(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2], commandWords[cursor + 3],
                            commandWords[cursor + 4]);
                    cursor += 5;
                }
                case CMD_DRAW_INDIRECT -> {
                    BrowserGpu.rpDrawIndirect(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2]);
                    cursor += 3;
                }
                case CMD_DRAW_INDEXED_INDIRECT -> {
                    BrowserGpu.rpDrawIndexedIndirect(handle,
                            commandWords[cursor], commandWords[cursor + 1],
                            commandWords[cursor + 2]);
                    cursor += 3;
                }
                case CMD_PUSH_DEBUG_GROUP -> BrowserGpu.rpPushDebugGroupId(
                        handle, commandWords[cursor++]
                );
                case CMD_POP_DEBUG_GROUP -> BrowserGpu.rpPopDebugGroup(handle);
                default -> throw new IllegalStateException("unknown local render command");
            }
        }
        if (end) {
            BrowserGpu.rpEnd(handle);
        }
    }

    private void beginCommand(int opcode, int argumentCount) {
        int headerWords = commandWordCount == 0 ? 2 : 0;
        int needed = commandWordCount + headerWords + argumentCount + 1;
        if (commandWords == null) {
            commandWords = new int[Math.max(64, needed)];
        } else if (needed > commandWords.length) {
            commandWords = Arrays.copyOf(
                    commandWords,
                    Math.max(needed, commandWords.length * 2)
            );
        }
        if (commandWordCount == 0) {
            commandWords[commandWordCount++] = COMMAND_MAGIC;
            commandWords[commandWordCount++] = COMMAND_VERSION;
        }
        commandWords[commandWordCount++] = opcode;
    }

    private static int[] acquireCommandWords() {
        int[] words = spareCommandWords;
        spareCommandWords = null;
        return words != null ? words : new int[256];
    }

    private void releaseCommandWords() {
        int[] words = commandWords;
        commandWords = null;
        commandWordCount = 0;
        if (words != null && (spareCommandWords == null
                || words.length > spareCommandWords.length)) {
            spareCommandWords = words;
        }
    }

    private void command0(int opcode) {
        beginCommand(opcode, 0);
    }

    private void command1(int opcode, int first) {
        beginCommand(opcode, 1);
        commandWords[commandWordCount++] = first;
    }

    private void command2(int opcode, int first, int second) {
        beginCommand(opcode, 2);
        commandWords[commandWordCount++] = first;
        commandWords[commandWordCount++] = second;
    }

    private void command3(int opcode, int first, int second, int third) {
        beginCommand(opcode, 3);
        commandWords[commandWordCount++] = first;
        commandWords[commandWordCount++] = second;
        commandWords[commandWordCount++] = third;
    }

    private void command4(int opcode, int first, int second, int third, int fourth) {
        beginCommand(opcode, 4);
        commandWords[commandWordCount++] = first;
        commandWords[commandWordCount++] = second;
        commandWords[commandWordCount++] = third;
        commandWords[commandWordCount++] = fourth;
    }

    private void command5(int opcode, int first, int second, int third, int fourth, int fifth) {
        beginCommand(opcode, 5);
        commandWords[commandWordCount++] = first;
        commandWords[commandWordCount++] = second;
        commandWords[commandWordCount++] = third;
        commandWords[commandWordCount++] = fourth;
        commandWords[commandWordCount++] = fifth;
    }
}
