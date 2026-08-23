package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;

/** Handle to a GPURenderPipeline created by the browser host. */
final class WebGpuCompiledPipeline implements CompiledRenderPipeline {
    static final WebGpuCompiledPipeline INVALID = new WebGpuCompiledPipeline(0);

    private final int handle;

    WebGpuCompiledPipeline(int handle) {
        this.handle = handle;
    }

    int handle() {
        return handle;
    }

    @Override
    public boolean isValid() {
        return handle != 0;
    }
}
