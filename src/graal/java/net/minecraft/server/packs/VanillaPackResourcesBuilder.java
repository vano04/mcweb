package net.minecraft.server.packs;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.server.packs.resources.ResourceMetadata;

/**
 * Browser replacement for Mojang's vanilla pack builder. The original walks
 * the code-source jar/directory to discover namespaces, but the browser
 * image has no jar on its virtual filesystem. This builder records metadata
 * and exposed namespaces only; the browser VanillaPackResources shadow
 * serves every asset from the image-embedded classpath resources.
 */
public class VanillaPackResourcesBuilder {
    public static Consumer<VanillaPackResourcesBuilder> developmentConfig = builder -> {
    };

    private ResourceMetadata metadata = ResourceMetadata.EMPTY;
    private final Set<String> namespaces = new LinkedHashSet<>();

    public VanillaPackResourcesBuilder() {
    }

    public VanillaPackResourcesBuilder pushJarResources() {
        return this;
    }

    public VanillaPackResourcesBuilder pushClasspathResources(PackType type, Class<?> anchor) {
        return this;
    }

    public VanillaPackResourcesBuilder applyDevelopmentConfig() {
        Consumer<VanillaPackResourcesBuilder> config = developmentConfig;
        if (config != null) {
            config.accept(this);
        }
        return this;
    }

    public VanillaPackResourcesBuilder pushUniversalPath(Path path) {
        return this;
    }

    public VanillaPackResourcesBuilder pushAssetPath(PackType type, Path path) {
        return this;
    }

    public VanillaPackResourcesBuilder setMetadata(ResourceMetadata metadata) {
        this.metadata = metadata;
        return this;
    }

    public VanillaPackResourcesBuilder exposeNamespace(String... namespaces) {
        for (String namespace : namespaces) {
            this.namespaces.add(namespace);
        }
        return this;
    }

    public VanillaPackResources build(PackLocationInfo location) {
        return new VanillaPackResources(location, metadata, namespaces, List.of(), Map.of());
    }
}
