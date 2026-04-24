package engine.core;

import engine.render.EnvironmentMap;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class WorldEnvironmentLibrary {

    private static final Map<String, EnvironmentMap> CACHE = new HashMap<>();
    private static final Set<String> FAILED_PATHS = new HashSet<>();

    private WorldEnvironmentLibrary() {
    }

    static EnvironmentBinding resolveForPreset(String presetKey) {
        WorldPresetCatalog.Definition preset = WorldPresetCatalog.resolve(presetKey);
        EnvironmentMap map = loadCached(preset);
        return new EnvironmentBinding(preset.environmentAssetKey(), map, preset.environmentExposure());
    }

    private static EnvironmentMap loadCached(WorldPresetCatalog.Definition preset) {
        String path = preset.environmentPath();
        synchronized (CACHE) {
            EnvironmentMap cached = CACHE.get(path);
            if (cached != null) {
                return cached;
            }
            if (FAILED_PATHS.contains(path)) {
                return null;
            }
            try {
                EnvironmentMap loaded = EnvironmentMap.loadRadiance(path);
                CACHE.put(path, loaded);
                return loaded;
            } catch (RuntimeException ex) {
                FAILED_PATHS.add(path);
                System.out.println("Failed to load bundled HDRI '" + path + "': " + ex.getMessage());
                return null;
            }
        }
    }

    static final class EnvironmentBinding {
        final String assetKey;
        final EnvironmentMap map;
        final double exposure;

        EnvironmentBinding(String assetKey, EnvironmentMap map, double exposure) {
            this.assetKey = assetKey;
            this.map = map;
            this.exposure = exposure;
        }
    }
}
