package dev.mcweb.graal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copies a world directory between the client realm and the server Worker.
 *
 * <p>The two realms have separate in-memory filesystems, so a world is only
 * ever a copy on either side. The client ships one at world start
 * ({@link BrowserWorkerClientCompat#beginWorld}); the Worker ships one back
 * when the player leaves, because everything the server writes after world
 * start — region data, player data, the updated {@code level.dat}, and the
 * {@code data/world_gen_settings.dat} that 26.2 moved out of {@code level.dat}
 * — exists only in the Worker's filesystem until then.</p>
 *
 * <p>Losing that write-back is not a partial loss: without
 * {@code data/world_gen_settings.dat} a reopened world has no dimensions, so
 * {@code WorldDimensions.bake} throws {@code IllegalStateException: Overworld
 * settings missing} and Minecraft reports the save as corrupted.</p>
 */
public final class BrowserWorldSnapshot {
    /**
     * Never travels. The lock is held open by whichever realm owns the
     * directory, and copying one across would hand the other realm a lock file
     * describing a session it does not have.
     */
    private static final String SESSION_LOCK = "session.lock";

    private BrowserWorldSnapshot() {
    }

    /**
     * Chunk storage. Everything outside these is small — level.dat, the saved
     * data under {@code data/}, player data, advancements — which is all the
     * client needs to *open* a world; the bulk only carries what was built in it.
     */
    private static final List<String> BULK_DIRECTORIES = List.of("region", "entities", "poi");

    /** Serializes every regular file under {@code root}, sorted for determinism. */
    public static JsonArray captureFiles(Path root) throws IOException {
        return captureFiles(root, true);
    }

    /**
     * @param includeBulk false to skip chunk storage, leaving only the files
     *                    that decide whether the world can be opened at all
     */
    public static JsonArray captureFiles(Path root, boolean includeBulk) throws IOException {
        JsonArray files = new JsonArray();
        if (!Files.isDirectory(root)) {
            return files;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            List<Path> regular = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
            for (Path path : regular) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                if (SESSION_LOCK.equals(relative)) {
                    continue;
                }
                if (!includeBulk && isBulk(relative)) {
                    continue;
                }
                JsonObject file = new JsonObject();
                file.addProperty("path", relative);
                file.addProperty("data",
                        Base64.getEncoder().encodeToString(Files.readAllBytes(path)));
                files.add(file);
            }
        }
        return files;
    }

    private static boolean isBulk(String relative) {
        for (String segment : relative.split("/")) {
            if (BULK_DIRECTORIES.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    /** A whole-world message: {@code {levelId, files:[{path,data}]}}. */
    public static String capture(String levelId, Path root) throws IOException {
        return capture(levelId, root, true);
    }

    public static String capture(String levelId, Path root, boolean includeBulk)
            throws IOException {
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("levelId", levelId);
        snapshot.add("files", captureFiles(root, includeBulk));
        return snapshot.toString();
    }

    /**
     * Writes a captured file list into {@code root}, replacing existing files.
     *
     * <p>Files absent from the snapshot are left alone rather than deleted: the
     * snapshot is a copy of a live directory, and a delete pass would race the
     * owning realm for no benefit on the paths that matter.</p>
     */
    public static int apply(Path root, JsonArray files) throws IOException {
        // LevelStorageAccess may hand this seam a syntactically non-canonical
        // root (for example, one ending in the ROOT resource's "." component).
        // Comparing a normalized child against that spelling rejects ordinary
        // descendants after an IndexedDB restore as directory escapes. Anchor
        // both creation and containment to the same absolute normalized path.
        Path safeRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(safeRoot);
        int written = 0;
        for (JsonElement element : files) {
            JsonObject file = element.getAsJsonObject();
            String relative = file.get("path").getAsString();
            if (SESSION_LOCK.equals(relative)) {
                continue;
            }
            Path target = safeRoot.resolve(relative).normalize();
            if (!target.startsWith(safeRoot)) {
                throw new IllegalArgumentException("World file escapes its directory: " + relative);
            }
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(target, Base64.getDecoder().decode(file.get("data").getAsString()));
            written++;
        }
        return written;
    }
}
