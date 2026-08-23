package dev.mcweb.graal;

import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.minecraft.MinecraftSessionService;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.yggdrasil.response.MinecraftTexturesPayload;
import com.mojang.util.UUIDTypeAdapter;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.yggdrasil.YggdrasilAuthenticationService;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.graalvm.webimage.api.JS;

/** Isolated WasmGC probe for authlib unpacking plus relay signature promotion. */
public final class SkinProfileVerificationProbeMain {
    private SkinProfileVerificationProbeMain() {
    }

    public static void main(String[] args) {
        String payload = "{\"timestamp\":1,\"profileId\":"
                + "\"00000000000000000000000000000001\","
                + "\"profileName\":\"SkinProbe\",\"textures\":{"
                + "\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/skin\"},"
                + "\"CAPE\":{\"url\":\"https://textures.minecraft.net/texture/cape\"}}}";
        Property property = new Property(
                "textures",
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                "mcweb-fixture-valid"
        );
        MinecraftSessionService service = YggdrasilAuthenticationService
                .createOffline(Proxy.NO_PROXY)
                .createMinecraftSessionService();
        try {
            Gson gson = new GsonBuilder()
                    .registerTypeAdapter(java.util.UUID.class, new UUIDTypeAdapter())
                    .create();
            MinecraftTexturesPayload decoded = gson.fromJson(
                    payload, MinecraftTexturesPayload.class
            );
            if (decoded == null || decoded.textures() == null
                    || decoded.textures().isEmpty()) {
                publishResult(0, "direct-gson-empty:" + String.valueOf(decoded));
                return;
            }
        } catch (Throwable failure) {
            publishResult(0, "direct-gson:" + describe(failure));
            return;
        }
        BrowserSkinTextureCompat.supplyUnpackedAsync(
                () -> service.unpackTextures(property),
                Runnable::run,
                property
        ).whenComplete((textures, failure) -> publish(textures, failure));
    }

    private static void publish(MinecraftProfileTextures textures, Throwable failure) {
        if (failure != null) {
            publishResult(0, describe(failure));
            return;
        }
        publishResult(
                textures != null && textures.skin() != null && textures.cape() != null
                        && textures.signatureState() == com.mojang.authlib.SignatureState.SIGNED
                        ? 1 : 0,
                String.valueOf(textures)
        );
    }

    private static String describe(Throwable failure) {
        StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (depth > 0) {
                result.append(" <- ");
            }
            result.append(current.getClass().getName())
                    .append(':').append(String.valueOf(current.getMessage()));
            current = current.getCause();
        }
        return result.toString();
    }

    @JS.Coerce
    @JS(value = "globalThis.mcWebSkinProfileProbeResult={ok:ok|0,detail:String(detail)};",
            args = {"ok", "detail"})
    private static native void publishResult(int ok, String detail);
}
