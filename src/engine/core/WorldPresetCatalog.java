package engine.core;

import engine.math.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WorldPresetCatalog {

    // Bundled HDRIs are CC0 from Poly Haven:
    // https://polyhaven.com/license
    // https://polyhaven.com/a/farmland_overcast
    public static final Definition CLOUDY_DAY = new Definition(
            "Cloudy Day",
            "Oblacny den",
            new String[]{"cloudy day", "oblacny den"},
            new Vec3(0.18, 0.20, 0.23),
            new Vec3(0.11, 0.13, 0.15),
            0.92,
            0.82,
            0.34,
            0.24,
            0.36,
            new Vec3(0.94, 0.96, 1.0),
            new Vec3(0.62, 0.68, 0.78),
            new Vec3(0.94, 0.82, 0.72),
            new Vec3(0.62, 0.74, 0.96),
            "farmland_overcast_1k",
            "assets/environments/farmland_overcast_1k.hdr",
            0.46
    );

    // https://polyhaven.com/a/plains_sunset
    public static final Definition WARM_SUNSET = new Definition(
            "Warm Sunset",
            "Teply zapad",
            new String[]{"warm sunset", "teply zapad"},
            new Vec3(0.34, 0.20, 0.12),
            new Vec3(0.18, 0.09, 0.06),
            1.15,
            1.45,
            0.28,
            0.95,
            0.25,
            new Vec3(1.0, 0.83, 0.65),
            new Vec3(0.42, 0.50, 0.72),
            new Vec3(1.0, 0.78, 0.58),
            new Vec3(0.42, 0.56, 0.86),
            "plains_sunset_1k",
            "assets/environments/plains_sunset_1k.hdr",
            0.38
    );

    // https://polyhaven.com/a/ludwikowice_farmland
    public static final Definition CLASSIC_DAY = new Definition(
            "Classic Day",
            "Klasicky den",
            new String[]{"classic day", "klasicky den", "high contrast", "vysoky kontrast"},
            new Vec3(0.24, 0.27, 0.30),
            new Vec3(0.14, 0.18, 0.24),
            1.06,
            1.42,
            0.30,
            0.46,
            0.34,
            new Vec3(1.0, 0.97, 0.92),
            new Vec3(0.56, 0.67, 0.82),
            new Vec3(1.0, 0.86, 0.68),
            new Vec3(0.58, 0.76, 1.0),
            "ludwikowice_farmland_1k",
            "assets/environments/ludwikowice_farmland_1k.hdr",
            0.44
    );

    // https://polyhaven.com/a/studio_small_08
    public static final Definition STUDIO_SOFT = new Definition(
            "Studio Soft",
            "Studio svetla",
            new String[]{"studio soft", "studio svetla", "studio neutral", "neutralni studio"},
            new Vec3(0.18, 0.20, 0.24),
            new Vec3(0.05, 0.06, 0.08),
            0.96,
            1.26,
            0.46,
            0.40,
            0.34,
            new Vec3(1.0, 0.97, 0.92),
            new Vec3(0.74, 0.78, 0.86),
            new Vec3(1.0, 0.90, 0.82),
            new Vec3(0.78, 0.86, 1.0),
            "studio_small_08_1k",
            "assets/environments/studio_small_08_1k.hdr",
            0.40
    );

    // https://polyhaven.com/a/qwantani_night
    public static final Definition COOL_NIGHT = new Definition(
            "Cool Night",
            "Chladna noc",
            new String[]{"cool night", "chladna noc"},
            new Vec3(0.10, 0.16, 0.25),
            new Vec3(0.03, 0.05, 0.11),
            0.78,
            0.58,
            0.32,
            0.28,
            0.78,
            new Vec3(0.70, 0.80, 1.0),
            new Vec3(0.34, 0.44, 0.72),
            new Vec3(0.96, 0.72, 0.52),
            new Vec3(0.55, 0.74, 1.0),
            "qwantani_night_1k",
            "assets/environments/qwantani_night_1k.hdr",
            0.84
    );

    private static final Definition[] ORDERED_PRESETS = {
            CLOUDY_DAY,
            STUDIO_SOFT,
            WARM_SUNSET,
            CLASSIC_DAY,
            COOL_NIGHT
    };

    private WorldPresetCatalog() {
    }

    public static Definition defaultPreset() {
        return CLOUDY_DAY;
    }

    public static Definition resolve(String rawPreset) {
        String normalized = normalize(rawPreset);
        if (normalized.isEmpty()) {
            return defaultPreset();
        }
        for (Definition preset : ORDERED_PRESETS) {
            if (preset.matches(normalized)) {
                return preset;
            }
        }
        return defaultPreset();
    }

    public static String resolveKey(String rawPreset) {
        return resolve(rawPreset).key();
    }

    public static String resolveLabel(String rawPreset) {
        return resolve(rawPreset).label();
    }

    public static String[] labels() {
        String[] labels = new String[ORDERED_PRESETS.length];
        for (int i = 0; i < ORDERED_PRESETS.length; i++) {
            labels[i] = ORDERED_PRESETS[i].label();
        }
        return labels;
    }

    public static List<String> keys() {
        List<String> keys = new ArrayList<>(ORDERED_PRESETS.length);
        for (Definition preset : ORDERED_PRESETS) {
            keys.add(preset.key());
        }
        return keys;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public static final class Definition {
        private final String key;
        private final String label;
        private final String[] aliases;
        private final Vec3 ambientColor;
        private final Vec3 backgroundColor;
        private final double strength;
        private final double sunIntensity;
        private final double fillIntensity;
        private final double warmIntensity;
        private final double coolIntensity;
        private final Vec3 sunColor;
        private final Vec3 fillColor;
        private final Vec3 warmColor;
        private final Vec3 coolColor;
        private final String environmentAssetKey;
        private final String environmentPath;
        private final double environmentExposure;

        private Definition(String key,
                           String label,
                           String[] aliases,
                           Vec3 ambientColor,
                           Vec3 backgroundColor,
                           double strength,
                           double sunIntensity,
                           double fillIntensity,
                           double warmIntensity,
                           double coolIntensity,
                           Vec3 sunColor,
                           Vec3 fillColor,
                           Vec3 warmColor,
                           Vec3 coolColor,
                           String environmentAssetKey,
                           String environmentPath,
                           double environmentExposure) {
            this.key = key;
            this.label = label;
            this.aliases = aliases;
            this.ambientColor = ambientColor;
            this.backgroundColor = backgroundColor;
            this.strength = strength;
            this.sunIntensity = sunIntensity;
            this.fillIntensity = fillIntensity;
            this.warmIntensity = warmIntensity;
            this.coolIntensity = coolIntensity;
            this.sunColor = sunColor;
            this.fillColor = fillColor;
            this.warmColor = warmColor;
            this.coolColor = coolColor;
            this.environmentAssetKey = environmentAssetKey;
            this.environmentPath = environmentPath;
            this.environmentExposure = environmentExposure;
        }

        public String key() {
            return key;
        }

        public String label() {
            return label;
        }

        public Vec3 ambientColor() {
            return ambientColor;
        }

        public Vec3 backgroundColor() {
            return backgroundColor;
        }

        public double strength() {
            return strength;
        }

        public double sunIntensity() {
            return sunIntensity;
        }

        public double fillIntensity() {
            return fillIntensity;
        }

        public double warmIntensity() {
            return warmIntensity;
        }

        public double coolIntensity() {
            return coolIntensity;
        }

        public Vec3 sunColor() {
            return sunColor;
        }

        public Vec3 fillColor() {
            return fillColor;
        }

        public Vec3 warmColor() {
            return warmColor;
        }

        public Vec3 coolColor() {
            return coolColor;
        }

        public String environmentAssetKey() {
            return environmentAssetKey;
        }

        public String environmentPath() {
            return environmentPath;
        }

        public double environmentExposure() {
            return environmentExposure;
        }

        private boolean matches(String normalized) {
            if (normalize(key).equals(normalized) || normalize(label).equals(normalized)) {
                return true;
            }
            for (String alias : aliases) {
                if (normalize(alias).equals(normalized)) {
                    return true;
                }
            }
            return false;
        }
    }
}
