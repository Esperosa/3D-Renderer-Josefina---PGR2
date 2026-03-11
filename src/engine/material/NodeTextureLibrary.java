package engine.material;

import engine.render.Texture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tady držím sdílenou mezipaměť pro textury materiálových uzlů načítané ze souborů.
 */
public final class NodeTextureLibrary {

    private static final Map<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private NodeTextureLibrary() {
    }

    public static Texture load(String filePath) {
        CacheEntry entry = inspect(filePath);
        return entry == null ? null : entry.texture;
    }

    public static String describe(String filePath) {
        CacheEntry entry = inspect(filePath);
        if (entry == null) {
            return "No image file selected.";
        }
        if (entry.texture != null) {
            return entry.texture.getWidth() + "x" + entry.texture.getHeight() + " loaded";
        }
        return entry.error;
    }

    public static void invalidate(String filePath) {
        String key = normalize(filePath);
        if (key != null) {
            CACHE.remove(key);
        }
    }

    private static CacheEntry inspect(String filePath) {
        String key = normalize(filePath);
        if (key == null) {
            return null;
        }
        return CACHE.computeIfAbsent(key, NodeTextureLibrary::loadEntry);
    }

    private static CacheEntry loadEntry(String normalizedPath) {
        try {
            Path path = Path.of(normalizedPath);
            if (!Files.exists(path)) {
                return CacheEntry.failure("File not found: " + normalizedPath);
            }
            return CacheEntry.success(Texture.load(normalizedPath));
        } catch (RuntimeException ex) {
            return CacheEntry.failure(ex.getMessage());
        }
    }

    private static String normalize(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        try {
            return Path.of(filePath.trim()).toAbsolutePath().normalize().toString();
        } catch (RuntimeException ex) {
            return filePath.trim();
        }
    }

    private static final class CacheEntry {
        final Texture texture;
        final String error;

        private CacheEntry(Texture texture, String error) {
            this.texture = texture;
            this.error = error;
        }

        static CacheEntry success(Texture texture) {
            return new CacheEntry(texture, null);
        }

        static CacheEntry failure(String error) {
            return new CacheEntry(null, error == null ? "Texture load failed." : error);
        }
    }
}
