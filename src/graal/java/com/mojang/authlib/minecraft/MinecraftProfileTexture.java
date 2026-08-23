package com.mojang.authlib.minecraft;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import org.apache.commons.lang3.builder.ToStringBuilder;

/**
 * Authlib ABI replacement that derives a texture hash without a URL handler.
 *
 * <p>Native Image enables only {@code file:} and {@code resource:} URL
 * protocols in this project. Authlib's original {@link #getHash()} constructs
 * a {@code java.net.URL} solely to read its path, which throws before the
 * browser fetch seam can run. {@link URI} provides the same parsing operation
 * without installing the desktop HTTP stack.
 */
public class MinecraftProfileTexture {
    public enum Type {
        SKIN,
        CAPE,
        ELYTRA
    }

    public static final int PROFILE_TEXTURE_COUNT = Type.values().length;

    private final String url;
    private final Map<String, String> metadata;

    public MinecraftProfileTexture(String url, Map<String, String> metadata) {
        this.url = url;
        this.metadata = metadata;
    }

    public String getUrl() {
        return url;
    }

    public String getMetadata(String key) {
        return metadata == null ? null : metadata.get(key);
    }

    public String getHash() {
        try {
            String path = new URI(url).getPath();
            int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
            String name = path.substring(separator + 1);
            int extension = name.lastIndexOf('.');
            return extension < 0 ? name : name.substring(0, extension);
        } catch (URISyntaxException | RuntimeException failure) {
            throw new IllegalArgumentException("Invalid profile texture url");
        }
    }

    @Override
    public String toString() {
        return new ToStringBuilder(this)
                .append("url", url)
                .append("hash", getHash())
                .toString();
    }
}
