package dev.mcweb.graal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.server.PackDownloader;
import net.minecraft.server.packs.DownloadQueue;
import org.graalvm.webimage.api.JS;

/**
 * Downloads server-pushed resource packs through the browser.
 *
 * <p>Vanilla's {@code DownloadQueue} cannot run here for two independent
 * reasons. It downloads over {@code HttpURLConnection}, which Web Image has no
 * implementation for; and what it downloads is a deflated zip, which the image
 * cannot open because {@code Inflater} needs the native zlib the browser
 * runtime does not carry. So the page does both halves — {@code fetch} plus its
 * native {@code DecompressionStream} — and hands back an unpacked tree, which
 * this class materialises as an ordinary directory pack. A matching transform
 * makes {@code loadRequestedPacks} build a {@code PathPackResources} supplier
 * instead of the zip one, because the path it now receives is a directory.</p>
 *
 * <p>The request is started from whichever thread Minecraft asked on, but every
 * completion is applied from {@link #pump()} on the client thread, where the
 * pack repository and the reload it triggers belong.</p>
 */
public final class BrowserServerPacks {
    /** Mirrors `mcWebServerPacks.root`; the page owns the stored copy. */
    private static final String STORAGE_ROOT = "server-resource-packs";

    private static final Map<UUID, Batch> active = new LinkedHashMap<>();
    private static Path downloadRoot;

    private BrowserServerPacks() {
    }

    /** One `PackDownloader.download` call: every request in it resolves together. */
    private record Batch(Map<UUID, DownloadQueue.DownloadRequest> requests,
                         Consumer<DownloadQueue.BatchResult> consumer,
                         Map<UUID, Path> downloaded,
                         Set<UUID> failed) {
    }

    /**
     * The replacement for {@code DownloadedPackSource.createDownloader}; the
     * transform in build.gradle rewrites that method to return this.
     */
    /**
     * Replaces {@code ClientCommonPacketListenerImpl.parseResourcePackUrl}.
     *
     * <p>Vanilla builds a {@code java.net.URL} and treats a failure as
     * `INVALID_URL`. Web Image registers no protocol handler for `http`, so
     * {@code new URL("http://…")} throws `unknown protocol` for *every* server
     * pack: the client answered the push with INVALID_URL and the manager was
     * never asked to download anything. That is one reply packet and total
     * silence afterwards, which is exactly what a real server showed.</p>
     *
     * <p>The three-argument constructor takes an explicit handler and so skips
     * the registry lookup entirely. The handler is inert on purpose — nothing
     * here ever opens the connection, because the page performs the download.</p>
     */
    public static java.net.URL parsePackUrl(String spec) {
        try {
            String scheme = java.net.URI.create(spec).getScheme();
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                // Include the spec: "scheme=cdn" says a pack was dropped but
                // not whether the server sent something we should support or
                // something genuinely unusable.
                BrowserGpu.reportProgress("serverpack:url-rejected scheme=" + scheme
                        + " spec=" + (spec == null ? "<null>"
                            : spec.length() <= 160 ? spec : spec.substring(0, 160)));
                return null;
            }
            return new java.net.URL(null, spec, INERT_HANDLER);
        } catch (Throwable invalid) {
            BrowserGpu.reportProgress("serverpack:url-unparseable "
                    + invalid.getClass().getSimpleName());
            return null;
        }
    }

    private static final java.net.URLStreamHandler INERT_HANDLER = new java.net.URLStreamHandler() {
        @Override
        protected java.net.URLConnection openConnection(java.net.URL url) throws IOException {
            throw new IOException("resource packs are downloaded by the page, not URLConnection");
        }
    };

    public static PackDownloader downloader() {
        BrowserGpu.reportProgress("serverpack:downloader-installed");
        return BrowserServerPacks::download;
    }

    /**
     * Where {@code ServerPackManager}'s own update ticks are run.
     *
     * <p>Vanilla schedules them on {@code minecraft::execute}. On the game
     * thread that runs the task **inline**, so the tick fires re-entrantly from
     * inside `pushPack` — before the pack it is meant to notice has been
     * registered — and nothing ever schedules another one. The pack then sits
     * accepted and undownloaded forever, which is exactly what a server push
     * did here: the client answered once and stopped.</p>
     *
     * <p>Queueing to the next frame restores the "later, on the client thread"
     * semantics vanilla gets from a real event loop.</p>
     */
    public static java.util.concurrent.Executor frameExecutor() {
        return pendingTicks::add;
    }

    private static final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingTicks =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    private static void download(
            Map<UUID, DownloadQueue.DownloadRequest> requests,
            Consumer<DownloadQueue.BatchResult> consumer
    ) {
        BrowserGpu.reportProgress("serverpack:download-called n=" + requests.size());
        if (requests.isEmpty()) {
            consumer.accept(new DownloadQueue.BatchResult(Map.of(), Set.of()));
            return;
        }
        UUID batchId = UUID.randomUUID();
        Batch batch = new Batch(
                new HashMap<>(requests), consumer, new HashMap<>(), new HashSet<>());
        active.put(batchId, batch);
        for (Map.Entry<UUID, DownloadQueue.DownloadRequest> entry : requests.entrySet()) {
            String url = entry.getValue().url().toString();
            BrowserGpu.reportProgress("serverpack:start id=" + entry.getKey());
            if (!startDownload(entry.getKey().toString(), url)) {
                batch.failed().add(entry.getKey());
            }
        }
    }

    /** Called once per client frame; applies whatever the page has finished. */
    public static void pump() {
        for (Runnable tick = pendingTicks.poll(); tick != null; tick = pendingTicks.poll()) {
            try {
                tick.run();
            } catch (Throwable failure) {
                BrowserGpu.reportProgress("serverpack:tick-failed "
                        + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            }
        }
        if (active.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Batch> batchEntry : new ArrayList<>(active.entrySet())) {
            Batch batch = batchEntry.getValue();
            for (UUID id : new ArrayList<>(batch.requests().keySet())) {
                if (batch.downloaded().containsKey(id) || batch.failed().contains(id)) {
                    continue;
                }
                JsonObject state;
                try {
                    state = JsonParser.parseString(pollDownload(id.toString()))
                            .getAsJsonObject();
                } catch (Throwable malformed) {
                    fail(batch, id, "malformed download state: " + malformed.getMessage());
                    continue;
                }
                String phase = state.get("state").getAsString();
                if ("downloading".equals(phase)) {
                    continue;
                }
                if (!"ready".equals(phase)) {
                    fail(batch, id, state.has("error")
                            ? state.get("error").getAsString() : phase);
                    continue;
                }
                try {
                    Path unpacked = materialise(id, state.getAsJsonArray("files"));
                    batch.downloaded().put(id, unpacked);
                    BrowserGpu.reportProgress("serverpack:ready id=" + id
                            + " bytes=" + state.get("bytes").getAsLong()
                            + " via=" + state.get("via").getAsString());
                } catch (Throwable failure) {
                    fail(batch, id, failure.getClass().getSimpleName()
                            + ":" + failure.getMessage());
                }
            }
            if (batch.downloaded().size() + batch.failed().size()
                    < batch.requests().size()) {
                continue;
            }
            active.remove(batchEntry.getKey());
            batch.consumer().accept(new DownloadQueue.BatchResult(
                    Map.copyOf(batch.downloaded()), Set.copyOf(batch.failed())));
        }
    }

    private static void fail(Batch batch, UUID id, String reason) {
        batch.failed().add(id);
        forgetDownload(id.toString());
        BrowserGpu.reportProgress("serverpack:failed id=" + id + " " + reason);
    }

    /**
     * Copies the page's unpacked tree into the image filesystem.
     *
     * <p>One entry per crossing on purpose: a pack is routinely tens of
     * megabytes, and asking for the whole tree in a single JSON value would
     * hold it base64-encoded on both sides of the boundary at once.</p>
     */
    private static Path materialise(UUID id, JsonArray files) throws IOException {
        Path root = downloadRoot().resolve(id.toString()).normalize();
        if (!root.startsWith(downloadRoot())) {
            throw new IOException("server pack escapes its download directory");
        }
        Files.createDirectories(root);
        String prefix = STORAGE_ROOT + "/" + id + "/";
        int written = 0;
        for (JsonElement element : files) {
            String stored = element.getAsString();
            if (!stored.startsWith(prefix)) {
                throw new IOException("server pack file outside its prefix: " + stored);
            }
            Path target = root.resolve(stored.substring(prefix.length())).normalize();
            if (!target.startsWith(root)) {
                throw new IOException("server pack file escapes its root: " + stored);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, Base64.getDecoder().decode(storedFile(stored)));
            written++;
        }
        if (!Files.isRegularFile(root.resolve("pack.mcmeta"))) {
            throw new IOException("server pack has no pack.mcmeta");
        }
        // The page's copy was a transfer buffer, not a cache: the pack now
        // exists in the image filesystem, and leaving a second copy in
        // IndexedDB would grow without bound across servers.
        discardStored(id.toString());
        BrowserGpu.reportProgress("serverpack:materialised id=" + id + " files=" + written);
        return root;
    }

    private static Path downloadRoot() {
        if (downloadRoot == null) {
            Minecraft minecraft = Minecraft.getInstance();
            Path base = minecraft == null
                    ? Path.of("/tmp/mcgame")
                    : minecraft.gameDirectory.toPath();
            downloadRoot = base.resolve(STORAGE_ROOT).toAbsolutePath().normalize();
        }
        return downloadRoot;
    }

    @JS.Coerce
    @JS(value = "return Boolean(globalThis.mcWebServerPacks?.start?.(id, url));",
            args = {"id", "url"})
    private static native boolean startDownload(String id, String url);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebServerPacks?.poll?.(id) || '{\"state\":\"unknown\"}';",
            args = {"id"})
    private static native String pollDownload(String id);

    @JS.Coerce
    @JS(value = "globalThis.mcWebServerPacks?.forget?.(id);", args = {"id"})
    private static native void forgetDownload(String id);

    @JS.Coerce
    @JS(value = "globalThis.mcWebServerPacks?.discard?.(id);", args = {"id"})
    private static native void discardStored(String id);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebStorage?.fileData?.(path) || '';", args = {"path"})
    private static native String storedFile(String path);

    /** Diagnostic: which packs the repository actually discovered. */
    public static void reportDiscoveredPacks(Minecraft minecraft) {
        try {
            List<String> ids = new ArrayList<>(
                    minecraft.getResourcePackRepository().getAvailableIds());
            BrowserGpu.reportProgress("packs:available " + String.join(",", ids));
        } catch (Throwable unavailable) {
            BrowserGpu.reportProgress("packs:available-failed "
                    + unavailable.getClass().getSimpleName());
        }
    }
}
