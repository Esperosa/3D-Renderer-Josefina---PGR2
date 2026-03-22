package engine.render.ray;

final class ToneMapSupport {
    static final int MODE_EXPOSURE = 0;
    static final int MODE_FILMIC = 1;
    static final int MODE_ACES = 2;

    private static final double INV_GAMMA = 1.0 / 2.2;

    private ToneMapSupport() {
    }

    static int parseMode(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number) {
            int mode = ((Number) value).intValue();
            return clampMode(mode);
        }
        if (value instanceof String) {
            String text = ((String) value).trim().toLowerCase();
            if (text.isEmpty()) {
                return fallback;
            }
            if (text.startsWith("exp")) {
                return MODE_EXPOSURE;
            }
            if (text.startsWith("film")) {
                return MODE_FILMIC;
            }
            if (text.startsWith("aces")) {
                return MODE_ACES;
            }
        }
        return fallback;
    }

    static double toneMap(double color, double exposure, int mode) {
        double x = Math.max(0.0, color) * Math.max(0.01, exposure);
        double mapped = switch (mode) {
            case MODE_FILMIC -> filmicMap(x);
            case MODE_ACES -> acesMap(x);
            default -> exposureMap(x);
        };
        mapped = clamp01(mapped);
        return Math.pow(mapped, INV_GAMMA);
    }

    private static double exposureMap(double x) {
        return 1.0 - Math.exp(-x);
    }

    private static double filmicMap(double x) {
        double a = 0.22;
        double b = 0.30;
        double c = 0.10;
        double d = 0.20;
        double e = 0.01;
        double f = 0.30;
        double w = 11.2;
        double num = (x * (a * x + c * b) + d * e);
        double den = (x * (a * x + b) + d * f);
        double mapped = (num / Math.max(1e-9, den)) - (e / f);
        double white = ((w * (a * w + c * b) + d * e) / (w * (a * w + b) + d * f)) - (e / f);
        if (white > 1e-9) {
            mapped /= white;
        }
        return mapped;
    }

    private static double acesMap(double x) {
        double a = 2.51;
        double b = 0.03;
        double c = 2.43;
        double d = 0.59;
        double e = 0.14;
        double num = x * (a * x + b);
        double den = x * (c * x + d) + e;
        return num / Math.max(1e-9, den);
    }

    private static double clamp01(double value) {
        if (value <= 0.0) {
            return 0.0;
        }
        if (value >= 1.0) {
            return 1.0;
        }
        return value;
    }

    private static int clampMode(int mode) {
        if (mode < MODE_EXPOSURE) {
            return MODE_EXPOSURE;
        }
        if (mode > MODE_ACES) {
            return MODE_ACES;
        }
        return mode;
    }
}
