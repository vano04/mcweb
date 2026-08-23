package dev.mcweb.graal.webgpu;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;

/** Browser implementation of Minecraft 26.2's existing GpuBackend seam. */
public final class WebGpuBackend implements GpuBackend {
    @Override
    public String getName() {
        return "WebGPU";
    }

    @Override
    public void setWindowHints() {
        // Canvas configuration replaces GLFW window hints.
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error)
            throws BackendCreationException {
        // The browser host acquires the adapter and device before Java starts.
    }

    @Override
    public GpuDevice createDevice(
            long window,
            ShaderSource shaderSource,
            GpuDebugOptions debugOptions,
            Runnable criticalShaderLoader
    ) throws BackendCreationException {
        return new GpuDevice(new WebGpuDeviceBackend(), criticalShaderLoader);
    }
}
