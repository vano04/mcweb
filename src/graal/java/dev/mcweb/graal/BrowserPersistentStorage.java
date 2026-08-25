package dev.mcweb.graal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mcweb.graal.webgpu.BrowserGpu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.graalvm.webimage.api.JS;

/**
 * Synchronises Minecraft's writable in-memory filesystem with browser
 * IndexedDB.
 *
 * <p>Web Image file APIs are synchronous while IndexedDB is asynchronous.  The
 * page therefore preloads durable records before the image starts; this class
 * restores them synchronously before {@code new Minecraft(GameConfig)}.  During
 * play, small client-owned files are published in change-only batches and the
 * page queues their IndexedDB writes.  Server-Worker worlds use their existing
 * snapshot seam and are restored lazily when a world is opened.</p>
 */
public final class BrowserPersistentStorage {
    private static final long SYNC_INTERVAL_MILLIS = 2_000L;
    private static final long MAX_CLIENT_FILE_BYTES = 128L * 1024L * 1024L;
    private static final Map<String, FileSignature> signatures = new HashMap<>();
    private static final Set<String> knownPaths = new HashSet<>();
    private static Path gameRoot;
    private static long nextSyncMillis;

    private BrowserPersistentStorage() {
    }

    /** Restores client files and world-list metadata before Minecraft boots. */
    public static void restoreStartup(Path root) {
        gameRoot = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(gameRoot);
            int clientFiles = applyFiles(gameRoot, withoutServerPacks(parseArray(startupFiles())));
            int worlds = 0;
            JsonArray metadata = parseArray(startupWorldMetadata());
            for (JsonElement element : metadata) {
                JsonObject world = element.getAsJsonObject();
                String levelId = safeLevelId(world.get("levelId").getAsString());
                Path worldRoot = gameRoot.resolve("saves").resolve(levelId).normalize();
                if (!worldRoot.startsWith(gameRoot.resolve("saves").normalize())) {
                    throw new IllegalArgumentException("Saved world escapes saves: " + levelId);
                }
                applyFiles(worldRoot, world.getAsJsonArray("files"));
                worlds++;
            }
            // The restored bytes already came from IndexedDB. Seed the
            // change detector from the files we just wrote so the first frame
            // does not read/base64/cross the Wasm boundary with a large
            // resource pack merely to store the identical value again.
            seedClientSignatures();
            BrowserGpu.reportProgress("storage:startup-restored files=" + clientFiles
                    + " worlds=" + worlds);
        } catch (Throwable failure) {
            BrowserGpu.reportProgress("storage:startup-restore-failed "
                    + failure.getClass().getName() + ":" + failure.getMessage());
        }
        nextSyncMillis = System.currentTimeMillis() + SYNC_INTERVAL_MILLIS;
    }

    /** Restores the selected world's bulk region files immediately before launch. */
    public static void restoreWorld(String levelId, Path root) throws IOException {
        String safeId = safeLevelId(levelId);
        String json = worldSnapshot(safeId);
        if (json == null || json.isEmpty()) {
            return;
        }
        JsonObject snapshot = JsonParser.parseString(json).getAsJsonObject();
        if (!safeId.equals(snapshot.get("levelId").getAsString())) {
            throw new IOException("Stored world id does not match requested world " + safeId);
        }
        int count = BrowserWorldSnapshot.apply(root, snapshot.getAsJsonArray("files"));
        BrowserGpu.reportProgress("storage:world-restored level=" + safeId
                + " files=" + count + " chars=" + json.length());
    }

    /** Change-only client-file sync, called from the frame boundary. */
    public static void pump() {
        long now = System.currentTimeMillis();
        if (gameRoot == null || now < nextSyncMillis) {
            return;
        }
        nextSyncMillis = now + SYNC_INTERVAL_MILLIS;
        syncClientFilesNow();
    }

    public static void syncClientFilesNow() {
        if (gameRoot == null) return;
        try {
            List<Path> regular = clientOwnedFiles();

            Set<String> seen = new HashSet<>();
            JsonArray changed = new JsonArray();
            for (Path path : regular) {
                String relative = relative(path);
                seen.add(relative);
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class);
                long size = attributes.size();
                if (size > MAX_CLIENT_FILE_BYTES) {
                    BrowserGpu.reportProgress("storage:file-too-large path=" + relative
                            + " bytes=" + size);
                    continue;
                }
                byte[] bytes = null;
                int contentHash = 0;
                // These two files are tiny and can be rewritten with the same
                // size inside a coarse filesystem timestamp. Hash them every
                // pass so an immediate server/options edit cannot be missed.
                if ("servers.dat".equals(relative) || "options.txt".equals(relative)) {
                    bytes = Files.readAllBytes(path);
                    contentHash = java.util.Arrays.hashCode(bytes);
                }
                FileSignature signature = new FileSignature(
                        size, attributes.lastModifiedTime().toMillis(), contentHash);
                if (signature.equals(signatures.get(relative))) {
                    knownPaths.add(relative);
                    continue;
                }
                if (bytes == null) bytes = Files.readAllBytes(path);
                JsonObject file = new JsonObject();
                file.addProperty("path", relative);
                file.addProperty("data", Base64.getEncoder().encodeToString(bytes));
                changed.add(file);
                signatures.put(relative, signature);
                knownPaths.add(relative);
            }

            List<String> deleted = new ArrayList<>();
            for (String old : new HashSet<>(knownPaths)) {
                if (!seen.contains(old)) {
                    deleted.add(old);
                    knownPaths.remove(old);
                    signatures.remove(old);
                }
            }
            if (!changed.isEmpty()) {
                storeFiles(changed.toString());
            }
            if (!deleted.isEmpty()) {
                deleteFiles(new com.google.gson.Gson().toJson(deleted));
            }
            if (!changed.isEmpty() || !deleted.isEmpty()) {
                BrowserGpu.reportProgress("storage:client-sync changed=" + changed.size()
                        + " deleted=" + deleted.size());
            }
            syncDeletedWorlds();
        } catch (Throwable failure) {
            BrowserGpu.reportProgress("storage:client-sync-failed "
                    + failure.getClass().getName() + ":" + failure.getMessage());
        }
    }

    private static void syncDeletedWorlds() {
        JsonArray ids = parseArray(worldIds());
        Path saves = gameRoot.resolve("saves").normalize();
        for (JsonElement element : ids) {
            String levelId = safeLevelId(element.getAsString());
            if (!Files.isDirectory(saves.resolve(levelId).normalize())) {
                deleteWorld(levelId);
                BrowserGpu.reportProgress("storage:world-deleted level=" + levelId);
            }
        }
    }

    private static void seedClientSignatures() throws IOException {
        signatures.clear();
        knownPaths.clear();
        for (Path path : clientOwnedFiles()) {
            String relative = relative(path);
            BasicFileAttributes attributes = Files.readAttributes(
                    path, BasicFileAttributes.class);
            int contentHash = 0;
            if ("servers.dat".equals(relative) || "options.txt".equals(relative)) {
                contentHash = java.util.Arrays.hashCode(Files.readAllBytes(path));
            }
            signatures.put(relative, new FileSignature(
                    attributes.size(), attributes.lastModifiedTime().toMillis(), contentHash));
            knownPaths.add(relative);
        }
    }

    /** Where the page unpacks a server's resource pack; see below. */
    static final String SERVER_PACK_ROOT = "server-resource-packs";

    /**
     * Lists the client-owned regular files, pruning excluded directories as it
     * walks rather than enumerating them and filtering afterwards.
     *
     * <p>This is the difference between a walk proportional to the files we
     * care about and one proportional to everything on disk. {@code Files.walk}
     * enumerates the whole tree before any {@code filter} sees it, so a server
     * that pushes a 19,090-file resource pack made this scan visit all 19,090
     * entries every {@link #SYNC_INTERVAL_MILLIS} — only to discard them, since
     * {@link #isClientOwned} already excludes that directory. Profiled on
     * hoplite.gg it was a 45–59 ms stall on the client thread every 2.0 s: the
     * periodic stutter felt on every server.</p>
     */
    private static List<Path> clientOwnedFiles() throws IOException {
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(gameRoot, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    Path dir, BasicFileAttributes attributes) {
                if (dir.equals(gameRoot) || isClientOwned(dir)) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                return java.nio.file.FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && isClientOwned(file)) {
                    found.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException failure) {
                // A file that vanished mid-walk is not a reason to abandon the
                // sync; the next pass picks up whatever is still there.
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        found.sort(null);
        return found;
    }

    private static boolean isClientOwned(Path path) {
        Path relative = gameRoot.relativize(path);
        if (relative.getNameCount() == 0) return false;
        String first = relative.getName(0).toString();
        // Saves travel through the server-Worker snapshot. Logs and crash
        // reports are diagnostics, not player data, and can grow without bound.
        //
        // Server resource packs are the page's, not the player's: the page
        // downloaded and unpacked them and handed them here. Syncing them back
        // re-read, base64-encoded and re-stored the entire pack -- 19090 files
        // for one real server -- inside a single frame, which showed up as
        // `storage:client-sync changed=19090` and an 8-second pump tick.
        return !"saves".equals(first) && !"logs".equals(first)
                && !"crash-reports".equals(first)
                && !SERVER_PACK_ROOT.equals(first);
    }

    private static String relative(Path path) {
        return gameRoot.relativize(path.toAbsolutePath().normalize())
                .toString().replace('\\', '/');
    }

    private static int applyFiles(Path root, JsonArray files) throws IOException {
        Files.createDirectories(root);
        int count = 0;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String relative = file.get("path").getAsString();
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root.normalize())) {
                throw new IOException("Persistent file escapes root: " + relative);
            }
            Path parent = target.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(target, Base64.getDecoder().decode(file.get("data").getAsString()));
            count++;
        }
        return count;
    }

    /**
     * Drops any server-pack rows a previous build left behind.
     *
     * <p>Restoring them would write tens of thousands of files into the image
     * filesystem during boot for a pack the next join re-materialises anyway.
     * New rows are removed as soon as they are materialised, so this only
     * matters once, for a store written before that.</p>
     */
    private static JsonArray withoutServerPacks(JsonArray files) {
        JsonArray kept = new JsonArray();
        int dropped = 0;
        for (JsonElement element : files) {
            String path = element.getAsJsonObject().get("path").getAsString();
            if (path.startsWith(SERVER_PACK_ROOT + "/")) {
                dropped++;
                continue;
            }
            kept.add(element);
        }
        if (dropped > 0) {
            BrowserGpu.reportProgress("storage:dropped-stale-server-packs files=" + dropped);
        }
        return kept;
    }

    private static JsonArray parseArray(String json) {
        if (json == null || json.isEmpty()) return new JsonArray();
        return JsonParser.parseString(json).getAsJsonArray();
    }

    private static String safeLevelId(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 255
                || raw.contains("/") || raw.contains("\\")
                || ".".equals(raw) || "..".equals(raw)) {
            throw new IllegalArgumentException("Invalid saved world id: " + raw);
        }
        return raw;
    }

    @JS.Coerce
    @JS(value = "return globalThis.mcWebStorage?.startupFiles?.() || '[]';", args = {})
    private static native String startupFiles();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebStorage?.startupWorldMetadata?.() || '[]';", args = {})
    private static native String startupWorldMetadata();

    @JS.Coerce
    @JS(value = "return globalThis.mcWebStorage?.worldSnapshot?.(levelId) || '';",
            args = {"levelId"})
    private static native String worldSnapshot(String levelId);

    @JS.Coerce
    @JS(value = "return globalThis.mcWebStorage?.worldIds?.() || '[]';", args = {})
    private static native String worldIds();

    @JS.Coerce
    @JS(value = "globalThis.mcWebStorage?.deleteWorld?.(levelId);", args = {"levelId"})
    private static native void deleteWorld(String levelId);

    @JS.Coerce
    @JS(value = "globalThis.mcWebStorage?.storeFiles?.(json);", args = {"json"})
    private static native void storeFiles(String json);

    @JS.Coerce
    @JS(value = "globalThis.mcWebStorage?.deleteFiles?.(json);", args = {"json"})
    private static native void deleteFiles(String json);

    private record FileSignature(long size, long modifiedMillis, int contentHash) {
    }
}
