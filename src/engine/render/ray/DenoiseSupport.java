package engine.render.ray;

final class DenoiseSupport {

    private static final double NOISE_FLOOR = 0.025;
    private static final double NOISE_SCALE = 3.2;
    private static final long MIN_FIREFLY_HISTORY_SAMPLES = 4L;
    private static final double FIREFLY_BASE_LUMA = 0.18;
    private static final double FIREFLY_HISTORY_SCALE = 4.75;
    private static final double FIREFLY_SOFT_CLIP = 0.18;

    private DenoiseSupport() {
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    static double relativeNoise(double luminanceSum, double luminanceSquaredSum, long sampleCount) {
        if (sampleCount <= 1L) {
            return 1.0;
        }
        double invSamples = 1.0 / Math.max(1L, sampleCount);
        double mean = luminanceSum * invSamples;
        double meanSquared = luminanceSquaredSum * invSamples;
        double variance = Math.max(0.0, meanSquared - mean * mean);
        double stdDev = Math.sqrt(variance);
        double relative = stdDev / Math.max(NOISE_FLOOR, mean + NOISE_FLOOR);
        return clamp01(relative * NOISE_SCALE);
    }

    static double fireflyScale(double sampleR,
                               double sampleG,
                               double sampleB,
                               double referenceLuminance,
                               long historySampleCount) {
        if (historySampleCount < MIN_FIREFLY_HISTORY_SAMPLES || !Double.isFinite(referenceLuminance) || referenceLuminance <= 0.0) {
            return 1.0;
        }
        double sampleLuminance = luminance(sampleR, sampleG, sampleB);
        if (!Double.isFinite(sampleLuminance) || sampleLuminance <= 0.0) {
            return 1.0;
        }
        double clampLuminance = Math.max(FIREFLY_BASE_LUMA, referenceLuminance * FIREFLY_HISTORY_SCALE + FIREFLY_BASE_LUMA);
        if (sampleLuminance <= clampLuminance) {
            return 1.0;
        }
        double compressedLuminance = clampLuminance + (sampleLuminance - clampLuminance) * FIREFLY_SOFT_CLIP;
        return compressedLuminance / sampleLuminance;
    }

    static double referenceLuminance(double accumulatedLuminanceSum,
                                     long accumulatedSampleCount,
                                     double currentBatchLuminanceSum,
                                     int currentBatchSampleCount) {
        long sampleCount = Math.max(0L, accumulatedSampleCount) + Math.max(0, currentBatchSampleCount);
        if (sampleCount <= 0L) {
            return Double.NaN;
        }
        double luminanceSum = Math.max(0.0, accumulatedLuminanceSum) + Math.max(0.0, currentBatchLuminanceSum);
        return luminanceSum / sampleCount;
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}
