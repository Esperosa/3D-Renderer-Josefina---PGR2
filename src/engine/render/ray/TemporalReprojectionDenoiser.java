package engine.render.ray;

final class TemporalReprojectionDenoiser {

    private static final double DEPTH_RELATIVE_REJECTION_SCALE = 32.0;
    private static final double NORMAL_REJECTION_POWER = 10.0;
    private static final double ALBEDO_REJECTION_SCALE = 14.0;
    private static final double BASE_BLEND = 0.05;
    private static final double NOISE_BLEND_SCALE = 0.34;
    private static final double LOW_SAMPLE_BLEND_SCALE = 0.22;
    private static final double CLAMP_BASE = 0.028;
    private static final double CLAMP_NOISE_SCALE = 0.22;
    private static final double CLAMP_LUMA_SCALE = 0.24;

    private TemporalReprojectionDenoiser() {
    }

    static void apply(int count,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      double[] noiseMap,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      double[] historyR,
                      double[] historyG,
                      double[] historyB,
                      float[] historyDepth,
                      float[] historyNormal,
                      float[] historyAlbedo,
                      double historyBlendScale) {
        apply(count,
                sampleCount,
                pixelSampleCounts,
                noiseMap,
                denoiseR,
                denoiseG,
                denoiseB,
                guideDepth,
                guideNormal,
                guideAlbedo,
                historyR,
                historyG,
                historyB,
                historyDepth,
                historyNormal,
                historyAlbedo,
                historyBlendScale,
                null);
    }

    static void apply(int count,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      double[] noiseMap,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      double[] historyR,
                      double[] historyG,
                      double[] historyB,
                      float[] historyDepth,
                      float[] historyNormal,
                      float[] historyAlbedo,
                      double historyBlendScale,
                      boolean[] pixelMask) {
        double effectiveBlendScale = DenoiseSupport.clamp01(historyBlendScale);
        if (effectiveBlendScale <= 1e-5) {
            return;
        }
        for (int i = 0; i < count; i++) {
            if (pixelMask != null && (i >= pixelMask.length || !pixelMask[i])) {
                continue;
            }
            float currentDepth = guideDepth[i];
            float previousDepth = historyDepth[i];
            boolean currentHit = Float.isFinite(currentDepth);
            boolean previousHit = Float.isFinite(previousDepth);
            if (currentHit != previousHit) {
                continue;
            }

            int base = i * 3;
            double guideConfidence = resolveGuideConfidence(
                    currentHit,
                    currentDepth,
                    previousDepth,
                    guideNormal,
                    historyNormal,
                    guideAlbedo,
                    historyAlbedo,
                    base);
            if (guideConfidence <= 1e-5) {
                continue;
            }

            double noise = noiseMap == null || i >= noiseMap.length
                    ? 1.0
                    : DenoiseSupport.clamp01(noiseMap[i]);
            int resolvedSampleCount = AdaptiveSamplingSupport.resolveSampleCount(pixelSampleCounts, i, sampleCount);
            double lowSampleFactor = 1.0 - DenoiseSupport.clamp01(resolvedSampleCount / 20.0);
            double blend = DenoiseSupport.clamp01((BASE_BLEND + noise * NOISE_BLEND_SCALE + lowSampleFactor * LOW_SAMPLE_BLEND_SCALE)
                    * guideConfidence
                    * effectiveBlendScale);
            if (blend <= 1e-5) {
                continue;
            }

            double currentR = denoiseR[i];
            double currentG = denoiseG[i];
            double currentB = denoiseB[i];
            double clampRange = CLAMP_BASE
                    + noise * CLAMP_NOISE_SCALE
                    + DenoiseSupport.luminance(currentR, currentG, currentB) * CLAMP_LUMA_SCALE;

            double clampedHistoryR = clampAround(currentR, historyR[i], clampRange);
            double clampedHistoryG = clampAround(currentG, historyG[i], clampRange);
            double clampedHistoryB = clampAround(currentB, historyB[i], clampRange);

            denoiseR[i] = DenoiseSupport.lerp(currentR, clampedHistoryR, blend);
            denoiseG[i] = DenoiseSupport.lerp(currentG, clampedHistoryG, blend);
            denoiseB[i] = DenoiseSupport.lerp(currentB, clampedHistoryB, blend);
        }
    }

    private static double resolveGuideConfidence(boolean hit,
                                                 float currentDepth,
                                                 float previousDepth,
                                                 float[] guideNormal,
                                                 float[] historyNormal,
                                                 float[] guideAlbedo,
                                                 float[] historyAlbedo,
                                                 int base) {
        if (!hit) {
            return 1.0;
        }

        double depthRelativeDiff = Math.abs(currentDepth - previousDepth)
                / Math.max(1e-4, Math.max(Math.abs(currentDepth), Math.abs(previousDepth)));
        double depthConfidence = DenoiseSupport.clamp01(1.0 - depthRelativeDiff * DEPTH_RELATIVE_REJECTION_SCALE);
        if (depthConfidence <= 1e-5) {
            return 0.0;
        }

        float currentNx = guideNormal[base];
        float currentNy = guideNormal[base + 1];
        float currentNz = guideNormal[base + 2];
        float previousNx = historyNormal[base];
        float previousNy = historyNormal[base + 1];
        float previousNz = historyNormal[base + 2];
        double normalDot = Math.max(0.0, currentNx * previousNx + currentNy * previousNy + currentNz * previousNz);
        double normalConfidence = Math.pow(DenoiseSupport.clamp01(normalDot), NORMAL_REJECTION_POWER);
        if (normalConfidence <= 1e-5) {
            return 0.0;
        }

        if (guideAlbedo == null || historyAlbedo == null) {
            return depthConfidence * normalConfidence;
        }
        double diffR = guideAlbedo[base] - historyAlbedo[base];
        double diffG = guideAlbedo[base + 1] - historyAlbedo[base + 1];
        double diffB = guideAlbedo[base + 2] - historyAlbedo[base + 2];
        double albedoDistance = Math.sqrt(diffR * diffR + diffG * diffG + diffB * diffB);
        double albedoConfidence = Math.exp(-albedoDistance * ALBEDO_REJECTION_SCALE);
        return depthConfidence * normalConfidence * albedoConfidence;
    }

    private static double clampAround(double center, double value, double range) {
        return Math.max(center - range, Math.min(center + range, value));
    }
}
