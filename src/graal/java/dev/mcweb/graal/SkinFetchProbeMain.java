package dev.mcweb.graal;

import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import org.graalvm.webimage.api.JS;

/** Isolated Web Image probe for the browser skin-fetch-to-cache boundary. */
public final class SkinFetchProbeMain {
    private SkinFetchProbeMain() {
    }

    public static void main(String[] args) throws IOException {
        String textureHash = new MinecraftProfileTexture(
                "https://textures.minecraft.net/texture/mcweb-known-hash",
                Map.of()
        ).getHash();
        if (!"mcweb-known-hash".equals(textureHash)) {
            publish(0, "unexpected texture hash: " + textureHash);
            return;
        }

        Path cachePath = Path.of("skin-fetch-probe", "texture.png");
        Files.deleteIfExists(cachePath);

        BrowserSkinTextureCompat.supplyAsync(
                () -> {
                    try {
                        return Base64.getEncoder().encodeToString(Files.readAllBytes(cachePath));
                    } catch (IOException failure) {
                        throw new UncheckedIOException(failure);
                    }
                },
                Runnable::run,
                cachePath,
                probeUrl()
        ).whenComplete((bytes, failure) -> {
            if (failure == null) {
                publish(1, bytes);
            } else {
                publish(0, failure.getClass().getName() + ":" + failure.getMessage());
            }
        });
    }

    @JS.Coerce
    @JS(value = "return new URL('/mock-skin.png', globalThis.location.href).href;", args = {})
    private static native String probeUrl();

    @JS.Coerce
    @JS(value = "globalThis.mcWebSkinProbeResult={ok:ok|0,detail:String(detail)};",
            args = {"ok", "detail"})
    private static native void publish(int ok, String detail);
}
