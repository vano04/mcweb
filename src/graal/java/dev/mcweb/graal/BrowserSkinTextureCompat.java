package dev.mcweb.graal;

import com.oracle.svm.webimage.annotation.JSRawCall;
import com.oracle.svm.webimage.wasmgc.annotation.WasmExport;
import com.google.common.collect.ArrayListMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.SignatureState;
import com.mojang.authlib.minecraft.MinecraftProfileTextures;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelPart;
import net.minecraft.world.entity.player.PlayerSkin;
import org.graalvm.webimage.api.JS;

/**
 * Browser transport for Mojang's asynchronous skin/cape download pipeline.
 *
 * <p>{@code SkinTextureDownloader} normally runs a supplier on the Download
 * executor. That supplier opens an {@code HttpURLConnection}, writes the
 * response to Minecraft's cache, decodes it through {@code NativeImage}, fixes
 * legacy skins, and finally registers a {@code DynamicTexture}. Web Image has
 * no HTTP URL protocol handler or socket transport, so only the first of those
 * steps is a browser platform seam.
 *
 * <p>The exact-counted JAR transform redirects the one skin-download
 * {@code CompletableFuture.supplyAsync} call here and supplies the cache path
 * and URL as extra context. A browser {@code fetch} fills that same cache file;
 * the captured vanilla supplier then runs unchanged and takes its existing
 * local-cache branch. Mojang therefore still owns PNG decoding, legacy layout
 * conversion, texture registration, and future/error behavior.
 */
public final class BrowserSkinTextureCompat {
    private static final Map<Integer, Pending<?>> PENDING = new HashMap<>();
    private static final Map<Integer, CompletableFuture<Boolean>> SIGNATURE_PENDING =
            new HashMap<>();

    private static int nextRequestId;
    private static int nextSignatureRequestId;
    private static int nextProbeId;
    private static boolean callbacksInstalled;
    private static boolean signatureCallbacksInstalled;
    private static String lastLivePlayerState;

    private BrowserSkinTextureCompat() {
    }

    /**
     * Installs a full-client probe only when {@code ?mcweb_skin_probe=1} is
     * present. It remains separate from the player-control agent and calls the
     * public Mojang downloader, so the resulting future covers PNG decode,
     * skin processing, DynamicTexture creation, and TextureManager registration
     * in addition to this class's browser-fetch boundary.
     */
    @JSRawCall
    @JS("if(new URLSearchParams(globalThis.location?.search||'').get('mcweb_skin_probe')==='1'){"
            + "globalThis.mcWebSkinFetch.runJavaProbe=url=>"
            + "getExport('mcweb.skin.runtime.probe')(toJavaString(String(url)));"
            + "globalThis.mcWebSkinFetch.runJavaProfileProbe=(skin,cape)=>"
            + "getExport('mcweb.skin.profile.probe')"
            + "(toJavaString(String(skin)),toJavaString(String(cape)));}")
    public static native void installRuntimeProbe();

    @WasmExport(value = "mcweb.skin.runtime.probe",
            comment = "Exercise Mojang's full skin decode and registration pipeline")
    public static void runRuntimeProbe(String url) {
        int probeId = ++nextProbeId;
        try {
            Minecraft minecraft = BrowserMinecraftMain.minecraft();
            if (minecraft == null) {
                throw new IllegalStateException("Minecraft is not constructed");
            }
            Path cachePath = Path.of("/tmp/mcgame/skin-runtime-probe-" + probeId + ".png");
            Files.deleteIfExists(cachePath);
            Identifier textureId = Identifier.fromNamespaceAndPath(
                    "mcweb", "skin_runtime_probe_" + probeId
            );
            SkinTextureDownloader downloader = new SkinTextureDownloader(
                    Proxy.NO_PROXY,
                    minecraft.getTextureManager(),
                    minecraft::execute
            );
            downloader.downloadAndRegisterSkin(textureId, cachePath, url, true)
                    .whenComplete((texture, failure) -> {
                        if (failure == null) {
                            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                                    "skin-runtime-probe:ok id=" + probeId
                                            + " texture=" + texture.texturePath()
                            );
                        } else {
                            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                                    "skin-runtime-probe:failed id=" + probeId
                                            + " " + failure.getClass().getName()
                                            + ":" + String.valueOf(failure.getMessage())
                            );
                        }
                    });
        } catch (Throwable failure) {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "skin-runtime-probe:failed id=" + probeId
                            + " " + failure.getClass().getName()
                            + ":" + String.valueOf(failure.getMessage())
            );
        }
    }

    /**
     * Hermetic full-pipeline probe used by Playwright with fulfilled Mojang-CDN
     * URLs and a loopback signature verifier. It covers signed GameProfile
     * unpacking, both skin and cape caches, DynamicTexture registration, and
     * the secure PlayerInfo selection consumed by AvatarRenderer. A second
     * profile has a tampered signature and must select the vanilla fallback.
     */
    @WasmExport(value = "mcweb.skin.profile.probe",
            comment = "Exercise signed profile skin/cape selection through PlayerInfo")
    public static void runProfileProbe(String skinUrl, String capeUrl) {
        int probeId = ++nextProbeId;
        try {
            Minecraft minecraft = BrowserMinecraftMain.minecraft();
            if (minecraft == null) {
                throw new IllegalStateException("Minecraft is not constructed");
            }

            GameProfile validProfile = probeProfile(
                    probeId, "valid", skinUrl, capeUrl, "mcweb-fixture-valid"
            );
            GameProfile tamperedProfile = probeProfile(
                    probeId, "tampered", skinUrl, capeUrl, "mcweb-fixture-tampered"
            );
            CompletableFuture<java.util.Optional<PlayerSkin>> validFuture =
                    minecraft.getSkinManager().get(validProfile);
            CompletableFuture<java.util.Optional<PlayerSkin>> tamperedFuture =
                    minecraft.getSkinManager().get(tamperedProfile);

            CompletableFuture.allOf(validFuture, tamperedFuture)
                    .whenComplete((unused, failure) -> {
                        try {
                            if (failure != null) {
                                throw new IllegalStateException("profile futures failed", failure);
                            }
                            PlayerSkin loadedValid = validFuture.join().orElseThrow();
                            PlayerSkin loadedTampered = tamperedFuture.join().orElseThrow();
                            PlayerSkin selectedValid = new PlayerInfo(validProfile, false).getSkin();
                            PlayerSkin selectedTampered =
                                    new PlayerInfo(tamperedProfile, false).getSkin();

                            Identifier body = loadedValid.body().texturePath();
                            Identifier cape = Objects.requireNonNull(
                                    loadedValid.cape(), "valid cape"
                            ).texturePath();
                            boolean bodyRegistered = minecraft.getTextureManager()
                                    .getTexture(body) instanceof DynamicTexture;
                            boolean capeRegistered = minecraft.getTextureManager()
                                    .getTexture(cape) instanceof DynamicTexture;
                            boolean validSelected = loadedValid.secure()
                                    && selectedValid.secure()
                                    && selectedValid.body().texturePath().equals(body)
                                    && selectedValid.cape() != null
                                    && selectedValid.cape().texturePath().equals(cape);
                            boolean tamperedRejected = !loadedTampered.secure()
                                    && !selectedTampered.body().texturePath().equals(
                                            loadedTampered.body().texturePath())
                                    && selectedTampered.cape() == null;
                            if (!bodyRegistered || !capeRegistered
                                    || !validSelected || !tamperedRejected) {
                                throw new IllegalStateException(
                                        "bodyRegistered=" + bodyRegistered
                                                + " capeRegistered=" + capeRegistered
                                                + " validSelected=" + validSelected
                                                + " tamperedRejected=" + tamperedRejected
                                );
                            }
                            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                                    "skin-profile-probe:ok id=" + probeId
                                            + " body=" + body
                                            + " cape=" + cape
                                            + " tamperedFallback=true"
                            );
                        } catch (Throwable probeFailure) {
                            reportProfileProbeFailure(probeId, probeFailure);
                        }
                    });
        } catch (Throwable failure) {
            reportProfileProbeFailure(probeId, failure);
        }
    }

    private static GameProfile probeProfile(
            int probeId,
            String variant,
            String skinUrl,
            String capeUrl,
            String signature
    ) {
        UUID id = UUID.nameUUIDFromBytes(
                ("mcweb-skin-profile-" + probeId + "-" + variant)
                        .getBytes(StandardCharsets.UTF_8)
        );
        String name = variant.equals("valid") ? "SkinProbe" : "CapeProbe";
        String payload = "{\"timestamp\":1,\"profileId\":\""
                + id.toString().replace("-", "")
                + "\",\"profileName\":\"" + name
                + "\",\"textures\":{\"SKIN\":{\"url\":\""
                + jsonEscape(skinUrl)
                + "\",\"metadata\":{\"model\":\"slim\"}},\"CAPE\":{\"url\":\""
                + jsonEscape(capeUrl) + "\"}}}";
        Property property = new Property(
                "textures",
                Base64.getEncoder().encodeToString(payload.getBytes(StandardCharsets.UTF_8)),
                signature
        );
        ArrayListMultimap<String, Property> values = ArrayListMultimap.create();
        values.put("textures", property);
        return new GameProfile(id, name, new PropertyMap(values));
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static void reportProfileProbeFailure(int probeId, Throwable failure) {
        dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                "skin-profile-probe:failed id=" + probeId
                        + " " + failure.getClass().getName()
                        + ":" + String.valueOf(failure.getMessage())
        );
    }

    /** One change-only marker for the actual PlayerInfo state used by rendering. */
    public static void reportLivePlayerState(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null
                || minecraft.getConnection() == null) {
            return;
        }
        try {
            UUID playerId = minecraft.player.getUUID();
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(playerId);
            if (info == null) {
                return;
            }
            PlayerSkin selected = minecraft.player.getSkin();
            Identifier body = selected.body().texturePath();
            Identifier cape = selected.cape() == null
                    ? null : selected.cape().texturePath();
            int properties = info.getProfile().properties().get("textures").size();
            String state = "user=" + minecraft.getUser().getName()
                    + "/" + minecraft.getUser().getProfileId()
                    + " player=" + info.getProfile().name() + "/" + playerId
                    + " identityMatch=" + minecraft.isLocalPlayer(playerId)
                    + " textures=" + properties
                    + " secure=" + selected.secure()
                    + " model=" + selected.model()
                    + " body=" + body
                    + " bodyDynamic=" + (minecraft.getTextureManager().getTexture(body)
                            instanceof DynamicTexture)
                    + " cape=" + String.valueOf(cape)
                    + " capeDynamic=" + (cape != null
                            && minecraft.getTextureManager().getTexture(cape)
                                    instanceof DynamicTexture)
                    + " capeShown=" + minecraft.player.isModelPartShown(
                            PlayerModelPart.CAPE
                    );
            if (!state.equals(lastLivePlayerState)) {
                lastLivePlayerState = state;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "skin-live:" + state
                );
            }
        } catch (Throwable failure) {
            String state = "failed=" + failure.getClass().getName()
                    + ":" + String.valueOf(failure.getMessage());
            if (!state.equals(lastLivePlayerState)) {
                lastLivePlayerState = state;
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "skin-live:" + state
                );
            }
        }
    }

    /** Replacement for the one download supplier in SkinTextureDownloader. */
    public static <T> CompletableFuture<T> supplyAsync(
            Supplier<T> supplier,
            Executor executor,
            Path cachePath,
            String url
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(cachePath, "cachePath");
        Objects.requireNonNull(url, "url");

        // Preserve Mojang's cache-hit path byte for byte, including its chosen
        // executor and exception wrapping.
        if (Files.isRegularFile(cachePath)) {
            return CompletableFuture.supplyAsync(supplier, executor);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        int requestId;
        try {
            synchronized (PENDING) {
                if (!callbacksInstalled) {
                    installResultHandler();
                    installFailureHandler();
                    callbacksInstalled = true;
                }
                requestId = ++nextRequestId;
                PENDING.put(requestId, new Pending<>(cachePath, supplier, executor, result));
            }
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
            return result;
        }

        try {
            startFetch(requestId, url);
        } catch (Throwable failure) {
            take(requestId);
            result.completeExceptionally(failure);
        }
        return result;
    }

    /**
     * Browser replacement for SkinManager's one asynchronous profile unpack.
     *
     * <p>The normal authlib session service validates the signed textures
     * property with Mojang's rotating service keys. This image deliberately
     * uses {@code ServicesKeySet.EMPTY}: java.security RSA and authlib's
     * scheduled key-fetcher thread are unavailable in Web Image. The
     * localhost relay already owns the online-mode crypto boundary, so it
     * performs that exact RSA-SHA1 check and returns only a boolean. A missing,
     * unsigned, malformed, unreachable, or tampered property is never promoted
     * to secure.
     *
     * <p>Everything else remains Mojang's pipeline. The captured supplier still
     * calls {@code MinecraftSessionService.unpackTextures}, including authlib's
     * base64/JSON parsing and texture-domain validation. Only an INVALID result
     * with an explicit signature is eligible for relay verification; a valid
     * result from a future browser crypto implementation passes through.
     */
    public static CompletableFuture<MinecraftProfileTextures> supplyUnpackedAsync(
            Supplier<MinecraftProfileTextures> supplier,
            Executor executor,
            Property packedTextures
    ) {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(executor, "executor");

        CompletableFuture<MinecraftProfileTextures> unpacked =
                CompletableFuture.supplyAsync(supplier, executor);
        if (packedTextures == null || !packedTextures.hasSignature()) {
            return unpacked;
        }

        return unpacked.thenCompose(textures -> {
            if (textures == null || textures.signatureState() != SignatureState.INVALID) {
                return CompletableFuture.completedFuture(textures);
            }
            return verifyProfileProperty(packedTextures).handle((valid, failure) -> {
                if (failure != null || !Boolean.TRUE.equals(valid)) {
                    return textures;
                }
                dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                        "skin-profile:signature-verified"
                );
                return new MinecraftProfileTextures(
                        textures.skin(),
                        textures.cape(),
                        textures.elytra(),
                        SignatureState.SIGNED
                );
            });
        });
    }

    private static CompletableFuture<Boolean> verifyProfileProperty(Property property) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        int requestId;
        try {
            synchronized (SIGNATURE_PENDING) {
                if (!signatureCallbacksInstalled) {
                    installSignatureResultHandler();
                    installSignatureFailureHandler();
                    signatureCallbacksInstalled = true;
                }
                requestId = ++nextSignatureRequestId;
                SIGNATURE_PENDING.put(requestId, result);
            }
            startSignatureVerification(requestId, property.value(), property.signature());
        } catch (Throwable failure) {
            synchronized (SIGNATURE_PENDING) {
                SIGNATURE_PENDING.values().remove(result);
            }
            result.completeExceptionally(failure);
        }
        return result;
    }

    @JSRawCall
    @JS("globalThis.mcWebSkinFetch.onResult((id,data)=>"
            + "getExport('mcweb.skin.fetch.result')(id|0,toJavaString(String(data))));")
    private static native void installResultHandler();

    @JSRawCall
    @JS("globalThis.mcWebSkinFetch.onFailure((id,message)=>"
            + "getExport('mcweb.skin.fetch.failure')(id|0,toJavaString(String(message))));")
    private static native void installFailureHandler();

    @JSRawCall
    @JS("globalThis.mcWebSkinFetch.onSignatureResult((id,valid)=>"
            + "getExport('mcweb.skin.signature.result')(id|0,valid?1:0));")
    private static native void installSignatureResultHandler();

    @JSRawCall
    @JS("globalThis.mcWebSkinFetch.onSignatureFailure((id,message)=>"
            + "getExport('mcweb.skin.signature.failure')(id|0,toJavaString(String(message))));")
    private static native void installSignatureFailureHandler();

    @JS.Coerce
    @JS(value = "globalThis.mcWebSkinFetch.start(id,url);", args = {"id", "url"})
    private static native void startFetch(int id, String url);

    @JS.Coerce
    @JS(value = "globalThis.mcWebSkinFetch.verifyProfileProperty(id,value,signature);",
            args = {"id", "value", "signature"})
    private static native void startSignatureVerification(
            int id,
            String value,
            String signature
    );

    @WasmExport(value = "mcweb.skin.fetch.result", comment = "Finish a browser skin texture fetch")
    public static void dispatchResult(int requestId, String bytesBase64) {
        Pending<?> pending = take(requestId);
        if (pending == null) {
            return;
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(bytesBase64);
            Path parent = pending.cachePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(pending.cachePath(), bytes, new OpenOption[0]);
            pending.runVanillaSupplier();
        } catch (Throwable failure) {
            pending.fail(failure);
        }
    }

    @WasmExport(value = "mcweb.skin.fetch.failure", comment = "Fail a browser skin texture fetch")
    public static void dispatchFailure(int requestId, String message) {
        Pending<?> pending = take(requestId);
        if (pending != null) {
            pending.fail(new IOException("Skin texture fetch failed: " + message));
        }
    }

    @WasmExport(value = "mcweb.skin.signature.result",
            comment = "Finish localhost verification of a signed textures property")
    public static void dispatchSignatureResult(int requestId, int valid) {
        CompletableFuture<Boolean> pending;
        synchronized (SIGNATURE_PENDING) {
            pending = SIGNATURE_PENDING.remove(requestId);
        }
        if (pending != null) {
            pending.complete(valid != 0);
        }
    }

    @WasmExport(value = "mcweb.skin.signature.failure",
            comment = "Fail closed when signed textures property verification is unavailable")
    public static void dispatchSignatureFailure(int requestId, String message) {
        CompletableFuture<Boolean> pending;
        synchronized (SIGNATURE_PENDING) {
            pending = SIGNATURE_PENDING.remove(requestId);
        }
        if (pending != null) {
            pending.completeExceptionally(new IOException(
                    "Profile texture signature verification failed: " + message
            ));
        }
    }

    private static Pending<?> take(int requestId) {
        synchronized (PENDING) {
            return PENDING.remove(requestId);
        }
    }

    private record Pending<T>(
            Path cachePath,
            Supplier<T> supplier,
            Executor executor,
            CompletableFuture<T> result
    ) {
        private void runVanillaSupplier() {
            try {
                executor.execute(() -> {
                    try {
                        result.complete(supplier.get());
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                });
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }

        private void fail(Throwable failure) {
            result.completeExceptionally(failure);
        }
    }
}
