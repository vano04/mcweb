package com.mojang.blaze3d.vulkan;

import com.mojang.blaze3d.GLFWErrorCapture;
import com.mojang.blaze3d.shaders.GpuDebugOptions;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.systems.BackendCreationException;
import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.List;
import java.util.Set;

/** Browser substitution that makes the desktop Vulkan probe safely unavailable. */
public class VulkanBackend implements GpuBackend {
    public static final Set<String> REQUIRED_DEVICE_EXTENSIONS = Set.of();
    public static final VulkanPNextStruct VK10_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct VK11_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct VK12_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct SYNC2_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct DYNAMIC_RENDERING_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct VERTEX_ATTRIB_DIVISOR_FEATURES_STRUCT = null;
    public static final VulkanPNextStruct MULTI_DRAW_FEATURES_STRUCT = null;
    public static final Set<VulkanFeature> REQUIRED_DEVICE_FEATURES = Set.of();
    public static BackendCreationException checkBackendAvailable() {
        return unavailable();
    }

    private static BackendCreationException unavailable() {
        return new BackendCreationException(
                "Desktop Vulkan is replaced by browser WebGPU",
                BackendCreationException.Reason.VULKAN_LOADER_MISSING,
                List.of()
        );
    }

    @Override
    public String getName() {
        return "Vulkan (unavailable in browser)";
    }

    @Override
    public void setWindowHints() {
    }

    @Override
    public void handleWindowCreationErrors(GLFWErrorCapture.Error error)
            throws BackendCreationException {
        throw unavailable();
    }

    @Override
    public GpuDevice createDevice(
            long window,
            ShaderSource shaderSource,
            GpuDebugOptions debugOptions,
            Runnable criticalShaderLoader
    ) throws BackendCreationException {
        throw unavailable();
    }
}
