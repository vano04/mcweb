package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendEquation;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.PolygonMode;
import com.mojang.blaze3d.shaders.ShaderSource;
import net.minecraft.client.renderer.ShaderDefines;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.DeviceFeatures;
import com.mojang.blaze3d.systems.DeviceInfo;
import com.mojang.blaze3d.systems.DeviceLimits;
import com.mojang.blaze3d.systems.DeviceType;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.GpuSurfaceBackend;
import com.mojang.blaze3d.systems.HintsAndWorkarounds;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.function.Supplier;

public final class WebGpuDeviceBackend implements GpuDeviceBackend {
    // DIAG one-shot dedupe sets for the atlas-upload trace. Remove with the trace.
    static final Set<String> diagCopyTexLabels = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    static final Set<String> diagWriteTexLabels = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private final DeviceInfo deviceInfo;
    private final WebGpuCommandEncoderBackend commandEncoder =
            new WebGpuCommandEncoderBackend(this);
    private final WebGpuTransientMemory transientMemory = new WebGpuTransientMemory();
    private final Map<RenderPipeline, CompiledRenderPipeline> pipelines = new HashMap<>();

    public WebGpuDeviceBackend() {
        if (!BrowserGpu.isReady()) {
            throw new IllegalStateException("The browser WebGPU device is not initialized");
        }
        deviceInfo = new DeviceInfo(
                BrowserGpu.adapterName(),
                "browser",
                "WebGPU",
                true,
                "WebGPU",
                1.0F,
                new DeviceLimits(16, 256, 8192, 1L << 30, 512, 8),
                // Core WebGPU: no multi-draw; drawIndirect + nonZeroFirstInstance
                // are supported; persistent mapping stays off (browser maps are
                // asynchronous).
                new DeviceFeatures(false, false, false, false, true, true, false),
                Set.of("browser-webgpu"),
                new HintsAndWorkarounds(false, false),
                DeviceType.OTHER
        );
    }

    WebGpuTransientMemory webTransientMemory() {
        return transientMemory;
    }

    @Override
    public GpuSurfaceBackend createSurface(long window) {
        return new WebGpuSurfaceBackend();
    }

    @Override
    public CommandEncoderBackend createCommandEncoder() {
        // Mojang's native backends expose one persistent encoder backend.
        // Sharing it preserves commands recorded by temporary facade objects
        // that intentionally do not submit on their own.
        return commandEncoder;
    }

    @Override
    public com.mojang.blaze3d.textures.GpuSampler createSampler(
            com.mojang.blaze3d.textures.AddressMode addressModeU,
            com.mojang.blaze3d.textures.AddressMode addressModeV,
            com.mojang.blaze3d.textures.FilterMode minFilter,
            com.mojang.blaze3d.textures.FilterMode magFilter,
            int maxAnisotropy,
            OptionalDouble maxLod
    ) {
        return new WebGpuSampler(
                addressModeU,
                addressModeV,
                minFilter,
                magFilter,
                maxAnisotropy,
                maxLod
        );
    }

    @Override
    public com.mojang.blaze3d.textures.GpuTexture createTexture(
            Supplier<String> label,
            int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels
    ) {
        return createTexture(label.get(), usage, format, width, height, depthOrLayers, mipLevels);
    }

    @Override
    public com.mojang.blaze3d.textures.GpuTexture createTexture(
            String label,
            int usage,
            GpuFormat format,
            int width,
            int height,
            int depthOrLayers,
            int mipLevels
    ) {
        return new WebGpuTexture(
                usage,
                label,
                format,
                width,
                height,
                depthOrLayers,
                mipLevels
        );
    }

    @Override
    public com.mojang.blaze3d.textures.GpuTextureView createTextureView(com.mojang.blaze3d.textures.GpuTexture texture) {
        return new WebGpuTextureView(texture, 0, texture.getMipLevels());
    }

    @Override
    public com.mojang.blaze3d.textures.GpuTextureView createTextureView(
            com.mojang.blaze3d.textures.GpuTexture texture,
            int baseMipLevel,
            int mipLevels
    ) {
        return new WebGpuTextureView(texture, baseMipLevel, mipLevels);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, long size) {
        return new WebGpuBuffer(label.get(), usage, size);
    }

    @Override
    public GpuBuffer createBuffer(Supplier<String> label, int usage, ByteBuffer data) {
        return new WebGpuBuffer(label.get(), usage, data);
    }

    @Override
    public List<String> getLastDebugMessages() {
        return List.of();
    }

    @Override
    public boolean isDebuggingEnabled() {
        return false;
    }

    @Override
    public CompiledRenderPipeline precompilePipeline(
            RenderPipeline pipeline,
            ShaderSource shaderSource
    ) {
        CompiledRenderPipeline cached = pipelines.get(pipeline);
        if (cached != null) {
            return cached;
        }
        CompiledRenderPipeline compiled = compilePipeline(pipeline, shaderSource);
        pipelines.put(pipeline, compiled);
        return compiled;
    }

    /** Cache lookup for RenderPass.setPipeline (compiled during resource load). */
    CompiledRenderPipeline compiledPipelineFor(RenderPipeline pipeline) {
        return pipelines.get(pipeline);
    }

    private CompiledRenderPipeline compilePipeline(RenderPipeline pipeline, ShaderSource shaderSource) {
        try {
            net.minecraft.resources.Identifier vertexShaderId = pipeline.getVertexShader();
            String vertexShader = vertexShaderId != null
                    ? vertexShaderId.toString() : "minecraft:core/screenquad";
            String fragmentShader = pipeline.getFragmentShader() != null
                    ? pipeline.getFragmentShader().toString() : "";
            // NOTE: do NOT dereference `shaderSource` here. PostChain compiles
            // its pipelines via GpuDevice.precompilePipeline(RenderPipeline),
            // which forwards a NULL ShaderSource (verified in GpuDevice's
            // bytecode: aconst_null). Doing so NPE'd every minecraft:blur/N
            // pipeline into the structural fallback (the black-textured-UI
            // prime suspect). The source text is unused: the host's WGSL
            // generator keys on the shader ID + defines.

            StringBuilder spec = new StringBuilder(512);
            spec.append("{");
            spec.append("\"label\":").append(jsonString(
                    pipeline.getLocation() != null ? pipeline.getLocation().toString() : "unknown")).append(",");
            spec.append("\"vertexShader\":").append(jsonString(vertexShader)).append(",");
            spec.append("\"fragmentShader\":").append(jsonString(fragmentShader)).append(",");

            spec.append("\"defines\":[");
            boolean first = true;
            ShaderDefines defines = pipeline.getShaderDefines();
            Iterable<String> flags = defines != null ? defines.flags() : null;
            if (flags != null) {
                for (String flag : flags) {
                    if (!first) {
                        spec.append(",");
                    }
                    spec.append(jsonString(flag));
                    first = false;
                }
            }
            Map<String, String> defineValues = defines != null ? defines.values() : null;
            if (defineValues != null) {
                for (Map.Entry<String, String> value : defineValues.entrySet()) {
                    if (!first) {
                        spec.append(",");
                    }
                    spec.append(jsonString(value.getKey() + "=" + value.getValue()));
                    first = false;
                }
            }
            spec.append("],");

            PrimitiveTopology topology = pipeline.getPrimitiveTopology();
            spec.append("\"topology\":").append(jsonString(topology != null ? topology.name() : "TRIANGLES")).append(",");
            spec.append("\"cull\":").append(pipeline.isCull()).append(",");
            PolygonMode polygonMode = pipeline.getPolygonMode();
            spec.append("\"polygonMode\":").append(jsonString(polygonMode != null ? polygonMode.name() : "FILL")).append(",");

            spec.append("\"vertexFormats\":[");
            VertexFormat[] bindings = pipeline.getVertexFormatBindings();
            if (bindings == null) {
                bindings = new VertexFormat[0];
            }
            for (int i = 0; i < bindings.length; i++) {
                VertexFormat format = bindings[i];
                if (format == null) {
                    continue;
                }
                if (i > 0) {
                    spec.append(",");
                }
                spec.append("{\"stride\":").append(format.getVertexSize());
                spec.append(",\"stepRate\":").append(format.getStepRate());
                spec.append(",\"elements\":[");
                boolean firstElement = true;
                java.util.List<VertexFormatElement> elements = format.getElements();
                if (elements == null) {
                    elements = java.util.List.of();
                }
                for (VertexFormatElement element : elements) {
                    if (element == null || element.format() == null) {
                        continue;
                    }
                    if (!firstElement) {
                        spec.append(",");
                    }
                    spec.append("{\"name\":").append(jsonString(element.name()));
                    spec.append(",\"offset\":").append(element.offset());
                    spec.append(",\"format\":").append(jsonString(element.format().name()));
                    spec.append("}");
                    firstElement = false;
                }
                spec.append("]}");
            }
            spec.append("],");

            spec.append("\"colorTargets\":[");
            ColorTargetState[] targets = pipeline.getColorTargetStates();
            if (targets != null) {
                for (int i = 0; i < targets.length; i++) {
                    if (i > 0) {
                        spec.append(",");
                    }
                    ColorTargetState target = targets[i];
                    if (target == null || target.format() == null) {
                        spec.append("null");
                        continue;
                    }
                    spec.append("{\"format\":").append(jsonString(target.format().name()));
                    spec.append(",\"writeMask\":").append(target.writeMask());
                    Optional<BlendFunction> blendFunction = target.blendFunction();
                    if (blendFunction != null && blendFunction.isPresent()) {
                        BlendFunction blend = blendFunction.get();
                        BlendEquation color = blend.color();
                        BlendEquation alpha = blend.alpha();
                        if (color != null && alpha != null) {
                            spec.append(",\"blend\":{\"colorSrc\":").append(jsonString(color.sourceFactor().name()));
                            spec.append(",\"colorDst\":").append(jsonString(color.destFactor().name()));
                            spec.append(",\"colorOp\":").append(jsonString(color.op().name()));
                            spec.append(",\"alphaSrc\":").append(jsonString(alpha.sourceFactor().name()));
                            spec.append(",\"alphaDst\":").append(jsonString(alpha.destFactor().name()));
                            spec.append(",\"alphaOp\":").append(jsonString(alpha.op().name()));
                            spec.append("}");
                        }
                    }
                    spec.append("}");
                }
            }
            spec.append("],");

            DepthStencilState depthStencil = pipeline.getDepthStencilState();
            if (depthStencil != null && depthStencil.depthTest() != null) {
                spec.append("\"depthStencil\":{");
                spec.append("\"depthTest\":").append(jsonString(depthStencil.depthTest().name()));
                spec.append(",\"writeDepth\":").append(depthStencil.writeDepth());
                spec.append(",\"biasScale\":").append(depthStencil.depthBiasScaleFactor());
                spec.append(",\"biasConstant\":").append(depthStencil.depthBiasConstant());
                spec.append("},");
            } else {
                spec.append("\"depthStencil\":null,");
            }

            appendBindGroups(spec, pipeline);

            spec.append("}");
            int handle = BrowserGpu.createPipeline(spec.toString());
            return handle == 0 ? WebGpuCompiledPipeline.INVALID : new WebGpuCompiledPipeline(handle);
        } catch (RuntimeException failure) {
            // ShaderManager treats a failed post-process pipeline as a fatal
            // crash, so returning INVALID is not an option: build a structural
            // fallback spec from the pipeline's own bind-group layouts (the
            // only thing that must match at bind time) and let the host compile
            // a generic shader against it. The deep NPE that got us here is in
            // some accessor the blanked wasm stack won't name; the fallback
            // sidesteps all of it.
            try {
                StringBuilder fallback = new StringBuilder(256);
                fallback.append("{\"label\":").append(jsonString(
                        pipeline.getLocation() != null ? pipeline.getLocation().toString() : "fallback")).append(",");
                fallback.append("\"vertexShader\":\"minecraft:core/screenquad\",");
                fallback.append("\"fragmentShader\":\"\",");
                fallback.append("\"defines\":[],\"topology\":\"TRIANGLES\",\"cull\":false,\"polygonMode\":\"FILL\",");
                fallback.append("\"vertexFormats\":[],\"colorTargets\":[],\"depthStencil\":null,");
                appendBindGroups(fallback, pipeline);
                fallback.append("}");
                int handle = BrowserGpu.createPipeline(fallback.toString());
                BrowserGpu.reportProgress(
                        "pipeline-fallback:" + pipeline.getLocation() + ":" + failure.getClass().getSimpleName()
                                + (handle == 0 ? ":HOST_REJECTED" : ":ok")
                );
                return handle == 0 ? WebGpuCompiledPipeline.INVALID : new WebGpuCompiledPipeline(handle);
            } catch (RuntimeException fallbackFailure) {
                BrowserGpu.reportProgress(
                        "pipeline-failed:" + pipeline.getLocation() + ":" + failure.getClass().getSimpleName()
                                + "/fallback:" + fallbackFailure.getClass().getSimpleName()
                );
                return WebGpuCompiledPipeline.INVALID;
            }
        }
    }

    private void appendBindGroups(StringBuilder spec, RenderPipeline pipeline) {
        spec.append("\"bindGroups\":[");
        List<BindGroupLayout> layouts = pipeline.getBindGroupLayouts();
        if (layouts == null) {
            layouts = java.util.List.of();
        }
        for (int i = 0; i < layouts.size(); i++) {
            BindGroupLayout layout = layouts.get(i);
            if (layout == null) {
                spec.append("{\"uniforms\":[],\"samplers\":[]}");
                if (i < layouts.size() - 1) {
                    spec.append(",");
                }
                continue;
            }
            if (i > 0) {
                spec.append(",");
            }
            spec.append("{\"uniforms\":[");
            boolean firstUniform = true;
            java.util.Collection<BindGroupLayout.UniformDescription> uniforms = layout.getUniforms();
            if (uniforms == null) {
                uniforms = java.util.List.of();
            }
            for (BindGroupLayout.UniformDescription uniform : uniforms) {
                if (uniform == null || uniform.name() == null || uniform.type() == null) {
                    continue;
                }
                if (!firstUniform) {
                    spec.append(",");
                }
                spec.append("{\"name\":").append(jsonString(uniform.name()));
                spec.append(",\"type\":").append(jsonString(uniform.type().name()));
                if (uniform.gpuFormat() != null) {
                    spec.append(",\"format\":").append(jsonString(uniform.gpuFormat().name()));
                }
                spec.append("}");
                firstUniform = false;
            }
            spec.append("],\"samplers\":[");
            boolean firstSampler = true;
            java.util.Collection<String> samplers = layout.getSamplers();
            if (samplers == null) {
                samplers = java.util.List.of();
            }
            for (String sampler : samplers) {
                if (sampler == null) {
                    continue;
                }
                if (!firstSampler) {
                    spec.append(",");
                }
                spec.append(jsonString(sampler));
                firstSampler = false;
            }
            spec.append("]}");
        }
        spec.append("]");
    }

    static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) {
                        result.append(String.format("\\u%04x", (int) c));
                    } else {
                        result.append(c);
                    }
                }
            }
        }
        result.append('"');
        return result.toString();
    }

    @Override
    public void clearPipelineCache() {
    }

    @Override
    public void close() {
    }

    @Override
    public GpuQueryPool createTimestampQueryPool(int size) {
        return new WebGpuQueryPool(size);
    }

    @Override
    public long getTimestampNow() {
        return System.nanoTime();
    }

    @Override
    public DeviceInfo getDeviceInfo() {
        return deviceInfo;
    }
}
