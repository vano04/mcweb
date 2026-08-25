package net.minecraft.server.packs;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.minecraft.server.packs.resources.ResourceProvider;

/**
 * Browser replacement for the vanilla resource pack. The browser image has
 * no jar on its virtual filesystem, so every asset is served from the
 * image-embedded classpath resources (the build embeds the supplied jar's
 * assets/ and data/ trees). Directory listing, which the image cannot provide, is
 * backed by a build-time generated index resource (mcweb-asset-index.txt).
 */
public class VanillaPackResources implements PackResources {
    private static final String INDEX_RESOURCE = "/mcweb-asset-index.txt";
    private static volatile Map<String, List<String>> indexByNamespace;

    private final PackLocationInfo location;
    private final ResourceMetadata metadata;
    private final Set<String> namespaces;

    VanillaPackResources(
            PackLocationInfo location,
            ResourceMetadata metadata,
            Set<String> namespaces,
            List<Path> universalPaths,
            Map<PackType, List<Path>> typePaths
    ) {
        this.location = location;
        this.metadata = metadata == null ? ResourceMetadata.EMPTY : metadata;
        this.namespaces = new LinkedHashSet<>(namespaces);
        for (String key : index().keySet()) {
            int separator = key.indexOf('/');
            if (separator >= 0 && separator < key.length() - 1) {
                this.namespaces.add(key.substring(separator + 1));
            }
        }
    }

    private static Map<String, List<String>> index() {
        Map<String, List<String>> cached = indexByNamespace;
        if (cached != null) {
            return cached;
        }
        synchronized (VanillaPackResources.class) {
            if (indexByNamespace != null) {
                return indexByNamespace;
            }
            Map<String, List<String>> loaded = new LinkedHashMap<>();
            try (InputStream input = VanillaPackResources.class.getResourceAsStream(INDEX_RESOURCE)) {
                if (input != null) {
                    BufferedReader reader = new BufferedReader(
                            new InputStreamReader(input, StandardCharsets.UTF_8));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.isEmpty()) {
                            continue;
                        }
                        int typeSeparator = line.indexOf('/');
                        int namespaceSeparator = line.indexOf('/', typeSeparator + 1);
                        if (typeSeparator <= 0
                                || namespaceSeparator <= typeSeparator + 1
                                || namespaceSeparator == line.length() - 1) {
                            continue;
                        }
                        String type = line.substring(0, typeSeparator);
                        String namespace = line.substring(typeSeparator + 1, namespaceSeparator);
                        String path = line.substring(namespaceSeparator + 1);
                        loaded.computeIfAbsent(type + "/" + namespace, key -> new ArrayList<>())
                                .add(path);
                    }
                }
            } catch (IOException failure) {
                // An empty index simply hides all resources.
            }
            indexByNamespace = loaded;
            return loaded;
        }
    }

    private static String resourcePath(PackType type, Identifier id) {
        String prefix = type == PackType.CLIENT_RESOURCES ? "assets" : "data";
        return "/" + prefix + "/" + id.getNamespace() + "/" + id.getPath();
    }

    private static String indexKey(PackType type, String namespace) {
        String prefix = type == PackType.CLIENT_RESOURCES ? "assets" : "data";
        return prefix + "/" + namespace;
    }

    private static boolean hasResource(PackType type, Identifier id) {
        List<String> paths = index().get(indexKey(type, id.getNamespace()));
        return paths != null && paths.contains(id.getPath());
    }

    public IoSupplier<InputStream> getRootResource(String... paths) {
        // Root files (pack.png etc.) are not embedded; report absence.
        return null;
    }

    public void listRawPaths(PackType type, Identifier id, Consumer<Path> consumer) {
        // No filesystem paths exist in the browser image.
    }

    public void listResources(
            PackType type,
            String namespace,
            String pathPrefix,
            ResourceOutput output
    ) {
        List<String> paths = index().get(indexKey(type, namespace));
        if (paths == null) {
            return;
        }
        String requiredPrefix = pathPrefix.isEmpty() ? "" : pathPrefix + "/";
        for (String path : paths) {
            if (!path.startsWith(requiredPrefix) || path.endsWith("/")) {
                continue;
            }
            Identifier id = Identifier.fromNamespaceAndPath(namespace, path);
            output.accept(id, () -> checkedStream(resourcePath(type, id)));
        }
    }

    private static InputStream checkedStream(String path) {
        InputStream input = VanillaPackResources.class.getResourceAsStream(path);
        if (input == null || !path.endsWith(".png")) {
            return input;
        }
        // Report (but still serve) any PNG with a broken signature so the
        // offending resource can be identified.
        java.io.PushbackInputStream checking = new java.io.PushbackInputStream(input, 8);
        byte[] signature = new byte[8];
        int read = 0;
        try {
            while (read < 8) {
                int n = checking.read(signature, read, 8 - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        } catch (IOException failure) {
            read = -1;
        }
        if (read < 8
                || (signature[0] & 0xFF) != 0x89 || signature[1] != 'P'
                || signature[2] != 'N' || signature[3] != 'G') {
            dev.mcweb.graal.webgpu.BrowserGpu.reportProgress(
                    "badpng:" + path + ":read=" + read);
        }
        if (read > 0) {
            try {
                checking.unread(signature, 0, read);
            } catch (IOException failure) {
                // Fall through; the stream is already consumed.
            }
        }
        return checking;
    }

    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (!hasResource(type, id)) {
            return null;
        }
        String path = resourcePath(type, id);
        return () -> checkedStream(path);
    }

    public Set<String> getNamespaces(PackType type) {
        LinkedHashSet<String> available = new LinkedHashSet<>(namespaces);
        String prefix = type == PackType.CLIENT_RESOURCES ? "assets/" : "data/";
        for (String key : index().keySet()) {
            if (key.startsWith(prefix) && key.length() > prefix.length()) {
                available.add(key.substring(prefix.length()));
            }
        }
        return Set.copyOf(available);
    }

    public <T> T getMetadataSection(MetadataSectionType<T> type) {
        return metadata.getSection(type).orElse(null);
    }

    public PackLocationInfo location() {
        return location;
    }

    public void close() {
    }

    public ResourceProvider asProvider() {
        return id -> {
            IoSupplier<InputStream> supplier = getResource(PackType.CLIENT_RESOURCES, id);
            if (supplier == null) {
                return Optional.empty();
            }
            return Optional.of(new Resource(this, supplier, () -> metadata));
        };
    }
}
