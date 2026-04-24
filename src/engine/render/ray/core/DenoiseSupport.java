package engine.render.ray.core;

final class DenoiseSupport {

    private static final double NOISE_FLOOR = 0.025;
    private static final double NOISE_SCALE = 3.2;
    private static final long MIN_FIREFLY_HISTORY_SAMPLES = 2L;
    private static final double FIREFLY_BASE_LUMA = 0.12;
    private static final double FIREFLY_STDDEV_FLOOR = 0.03;
    private static final double FIREFLY_MEAN_SCALE = 1.85;
    private static final double FIREFLY_STDDEV_SCALE = 2.25;
    private static final double FIREFLY_STDDEV_CAP_SCALE = 1.35;
    private static final double FIREFLY_SOFT_CLIP = 0.10;

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
                               double referenceStdDev,
                               long historySampleCount) {
        if (historySampleCount < MIN_FIREFLY_HISTORY_SAMPLES || !Double.isFinite(referenceLuminance) || referenceLuminance <= 0.0) {
            return 1.0;
        }
        double sampleLuminance = luminance(sampleR, sampleG, sampleB);
        if (!Double.isFinite(sampleLuminance) || sampleLuminance <= 0.0) {
            return 1.0;
        }
        double boundedStdDev = referenceStdDev;
        if (!Double.isFinite(boundedStdDev) || boundedStdDev < 0.0) {
            boundedStdDev = 0.0;
        }
        boundedStdDev = Math.max(FIREFLY_STDDEV_FLOOR, boundedStdDev);
        boundedStdDev = Math.min(boundedStdDev, FIREFLY_BASE_LUMA + referenceLuminance * FIREFLY_STDDEV_CAP_SCALE);

        double clampLuminance = Math.max(
                FIREFLY_BASE_LUMA,
                referenceLuminance * FIREFLY_MEAN_SCALE + boundedStdDev * FIREFLY_STDDEV_SCALE + FIREFLY_BASE_LUMA);
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

    static double referenceLuminanceStdDev(double accumulatedLuminanceSum,
                                           double accumulatedLuminanceSquaredSum,
                                           long accumulatedSampleCount,
                                           double currentBatchLuminanceSum,
                                           double currentBatchLuminanceSquaredSum,
                                           int currentBatchSampleCount) {
        long sampleCount = Math.max(0L, accumulatedSampleCount) + Math.max(0, currentBatchSampleCount);
        if (sampleCount <= 0L) {
            return Double.NaN;
        }
        double luminanceSum = Math.max(0.0, accumulatedLuminanceSum) + Math.max(0.0, currentBatchLuminanceSum);
        double luminanceSquaredSum = Math.max(0.0, accumulatedLuminanceSquaredSum)
                + Math.max(0.0, currentBatchLuminanceSquaredSum);
        double invSamples = 1.0 / sampleCount;
        double mean = luminanceSum * invSamples;
        double meanSquared = luminanceSquaredSum * invSamples;
        double variance = Math.max(0.0, meanSquared - mean * mean);
        return Math.sqrt(variance);
    }

    static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}

