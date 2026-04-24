package engine.render.ray.core;

final class AdaptiveSamplingSupport {

    private AdaptiveSamplingSupport() {
    }

    static boolean shouldSkipPixel(int sampleCount,
                                   int minSamples,
                                   double threshold,
                                   double luminanceSum,
                                   double luminanceSquaredSum) {
        int requiredSamples = Math.max(1, minSamples);
        if (sampleCount < requiredSamples) {
            return false;
        }
        double noise = DenoiseSupport.relativeNoise(luminanceSum, luminanceSquaredSum, sampleCount);
        return noise <= clampThreshold(threshold);
    }

    static double inverseSampleCount(int sampleCount, long fallbackSampleCount) {
        if (sampleCount > 0) {
            return 1.0 / sampleCount;
        }
        return 1.0 / Math.max(1L, fallbackSampleCount);
    }

    static int resolveSampleCount(int[] sampleCounts, int index, long fallbackSampleCount) {
        if (sampleCounts != null && index >= 0 && index < sampleCounts.length && sampleCounts[index] > 0) {
            return sampleCounts[index];
        }
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, fallbackSampleCount));
    }

    private static double clampThreshold(double threshold) {
        return Math.max(0.0, Math.min(1.0, threshold));
    }
}

