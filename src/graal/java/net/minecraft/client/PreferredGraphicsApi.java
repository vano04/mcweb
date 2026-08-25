package net.minecraft.client;

import com.mojang.blaze3d.systems.GpuBackend;
import com.mojang.serialization.Codec;
import dev.mcweb.graal.webgpu.WebGpuBackend;
import net.minecraft.network.chat.Component;
import net.minecraft.util.StringRepresentable;

/** Browser-profile replacement for the JAR's backend ordering method. */
public enum PreferredGraphicsApi implements StringRepresentable {
    DEFAULT("default", "options.graphicsApi.default"),
    OPENGL("opengl", "options.graphicsApi.opengl"),
    VULKAN("vulkan", "options.graphicsApi.vulkan");

    public static final Codec<PreferredGraphicsApi> CODEC =
            StringRepresentable.fromEnum(PreferredGraphicsApi::values);

    private final String serializedName;
    private final Component key;

    PreferredGraphicsApi(String serializedName, String translationKey) {
        this.serializedName = serializedName;
        this.key = Component.translatable(translationKey);
    }

    public Component caption() {
        return key;
    }

    @Override
    public String getSerializedName() {
        return serializedName;
    }

    public GpuBackend[] getBackendsToTry() {
        return new GpuBackend[] {new WebGpuBackend()};
    }
}
