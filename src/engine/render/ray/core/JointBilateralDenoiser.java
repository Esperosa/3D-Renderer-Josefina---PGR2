package engine.render.ray.core;

import engine.render.ray.bvh.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;

final class JointBilateralDenoiser {

    private static final double CENTER_WEIGHT = 1.85;
    private static final int MAX_PASSES = 4;
    private static final int FAST_MAX_PASSES = 3;
    private static final int FAST_TAP_COUNT = 8;
    private static final int TELEMETRY_SAMPLE_MASK = 7;
    private static final long TELEMETRY_SAMPLE_SCALE = 64L;
    private static final double STABLE_FRAME_NOISE_THRESHOLD = 0.08;
    private static final double VERY_NOISY_FRAME_THRESHOLD = 0.72;
    private static final int STABLE_SAMPLE_START = 16;
    private static final int FULL_STABLE_SAMPLES = 96;
    private static final double LOW_NOISE_GUARD = 0.03;
    private static final double HIGH_NOISE_GUARD = 0.25;
    private static final double MAX_STABLE_STRENGTH_REDUCTION = 0.74;
    private static final double BLEND_BASE_FLOOR = 0.22;
    private static final double BLEND_NOISE_SCALE = 0.60;
    private static final double BLEND_PASS_GATE_NOISE = 1.08;
    private static final double BLEND_PASS_GATE_DECAY = 0.26;
    private static final double BLEND_PASS_GATE_BIAS = 0.16;
    private static final double HOT_PIXEL_BASE = 0.20;
    private static final double HOT_PIXEL_NOISE_SCALE = 0.70;
    private static final double HOT_PIXEL_NOISE_MIN = 0.10;
    private static final double HOT_PIXEL_THRESHOLD_BASE = 1.45;
    private static final double HOT_PIXEL_THRESHOLD_STABILITY_SCALE = 0.35;
    private static final double HOT_PIXEL_THRESHOLD_LUMA_BIAS = 0.035;
    private static final double HOT_PIXEL_THRESHOLD_STRENGTH_BIAS = 0.025;
    private static final double HOT_PIXEL_NORMALIZATION_FLOOR = 0.08;
    private static final double COLOR_SIGMA_BASE = 0.009;
    private static final double COLOR_SIGMA_LUMA_SCALE = 0.031;
    private static final double COLOR_SIGMA_STRENGTH_SCALE = 0.015;
    private static final double COLOR_SIGMA_NOISE_BASE = 0.050;
    private static final double COLOR_SIGMA_PASS_SCALE = 0.009;
    private static final double ROUGHNESS_BALANCE_RANGE = 0.24;
    private static final double DETAIL_RECOVERY_BASE = 0.38;
    private static final double DETAIL_RECOVERY_NOISE_SCALE = 1.10;
    private static final double DETAIL_RECOVERY_SAMPLE_START = 12.0;
    private static final double DETAIL_RECOVERY_SAMPLE_RANGE = 72.0;
    private static final double DETAIL_RECOVERY_SAMPLE_BOOST = 0.60;
    private static final double SUBPIXEL_FLAT_GRADIENT_THRESHOLD = 0.020;
    private static final double SUBPIXEL_NOISE_START = 0.08;
    private static final double SUBPIXEL_NOISE_RANGE = 0.30;
    private static final double SUBPIXEL_MAX_EXTRA_BLEND = 0.24;
    private static final double SUBPIXEL_GUIDE_WEIGHT_SCALE = 2.8;
    private static final double DIFFUSE_BLEND_BASE = 1.04;
    private static final double DIFFUSE_BLEND_ROUGHNESS_SCALE = 0.26;
    private static final double SPECULAR_BLEND_BASE = 0.47;
    private static final double SPECULAR_BLEND_ROUGHNESS_SCALE = 0.44;
    private static final FilterTap[] FILTER_TAPS = new FilterTap[]{
            new FilterTap(-1, 0, 0.92),
            new FilterTap(1, 0, 0.92),
            new FilterTap(0, -1, 0.92),
            new FilterTap(0, 1, 0.92),
            new FilterTap(-1, -1, 0.68),
            new FilterTap(-1, 1, 0.68),
            new FilterTap(1, -1, 0.68),
            new FilterTap(1, 1, 0.68),
            new FilterTap(-2, 0, 0.34),
            new FilterTap(2, 0, 0.34),
            new FilterTap(0, -2, 0.34),
            new FilterTap(0, 2, 0.34)
    };

    private JointBilateralDenoiser() {
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, null,
                guideDepth, guideNormal, guideAlbedo, (float[]) null,
            denoiseR, denoiseG, denoiseB, null, null, null, scratchR, scratchG, scratchB, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      float[] guideRoughness,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, null,
                guideDepth, guideNormal, guideAlbedo, guideRoughness,
            denoiseR, denoiseG, denoiseB, null, null, null, scratchR, scratchG, scratchB, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] noiseMap,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, null,
                guideDepth, guideNormal, guideAlbedo, (float[]) null,
            denoiseR, denoiseG, denoiseB, noiseMap, null, null, scratchR, scratchG, scratchB, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      float[] guideRoughness,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] noiseMap,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, pixelSampleCounts,
                guideDepth, guideNormal, guideAlbedo, guideRoughness,
            denoiseR, denoiseG, denoiseB, noiseMap, null, null, scratchR, scratchG, scratchB, outColor, false);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      float[] guideRoughness,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] noiseMap,
                      double[] smartBlendScale,
                      double[] smartDetailScale,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor,
                      boolean fastMode) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, pixelSampleCounts,
                guideDepth, guideNormal, guideAlbedo, guideRoughness,
                denoiseR, denoiseG, denoiseB, noiseMap, smartBlendScale, smartDetailScale,
                scratchR, scratchG, scratchB, outColor, fastMode, 0);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      float[] guideRoughness,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] noiseMap,
                      double[] smartBlendScale,
                      double[] smartDetailScale,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor,
                      boolean fastMode,
                      int passCap) {
        FilterContext context = createContext(
                width,
                height,
                workerCount,
                threadPool,
                radius,
                strength,
                exposure,
                toneMapMode,
                invSamples,
                accumR,
                accumG,
                accumB,
                accumLuma,
                accumLumaSq,
                sampleCount,
                pixelSampleCounts,
                fastMode,
                guideDepth,
                guideNormal,
                guideAlbedo,
                guideRoughness,
                denoiseR,
                denoiseG,
                denoiseB,
                noiseMap,
                smartBlendScale,
                smartDetailScale,
                scratchR,
                scratchG,
                scratchB,
                outColor
        );
        if (context == null) {
            return;
        }

        long seedStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        seedMeanColor(context);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_SEED_NS,
                    System.nanoTime() - seedStart);
        }
        long noiseProfileStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        prepareNoiseProfile(context);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_NOISE_PROFILE_NS,
                    System.nanoTime() - noiseProfileStart);
        }

        double[] sourceR = context.denoiseR;
        double[] sourceG = context.denoiseG;
        double[] sourceB = context.denoiseB;
        double[] targetR = context.scratchR;
        double[] targetG = context.scratchG;
        double[] targetB = context.scratchB;

        int passCount = resolvePassCount(context.radius, context.peakNoise, context.fastMode);
        if (passCap > 0) {
            passCount = Math.max(1, Math.min(passCount, passCap));
        }
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_PASS_COUNT,
                passCount);
        for (int passIndex = 0; passIndex < passCount; passIndex++) {
            FilterPass pass = new FilterPass(passIndex, 1 << passIndex);
            long passStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
            runPass(context, pass, sourceR, sourceG, sourceB, targetR, targetG, targetB);
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_FILTER_NS,
                        System.nanoTime() - passStart);
            }

            double[] swapR = sourceR;
            double[] swapG = sourceG;
            double[] swapB = sourceB;
            sourceR = targetR;
            sourceG = targetG;
            sourceB = targetB;
            targetR = swapR;
            targetG = swapG;
            targetB = swapB;
        }

        long commitStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        commitDenoisedColor(context, sourceR, sourceG, sourceB);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_COMMIT_NS,
                    System.nanoTime() - commitStart);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_HOT_PIXEL_PIXELS,
                    context.hotPixelPixels.sum());
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_EDGE_TAPS,
                    context.edgeTapCount.sum());
        }
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      double[] accumLuma,
                      double[] accumLumaSq,
                      long sampleCount,
                      int[] pixelSampleCounts,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      float[] guideRoughness,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      double[] noiseMap,
                      double[] smartBlendScale,
                      double[] smartDetailScale,
                      double[] scratchR,
                      double[] scratchG,
                      double[] scratchB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount, pixelSampleCounts,
                guideDepth, guideNormal, guideAlbedo, guideRoughness,
                denoiseR, denoiseG, denoiseB, noiseMap, smartBlendScale, smartDetailScale,
                scratchR, scratchG, scratchB, outColor, false);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      float[] guideDepth,
                      float[] guideNormal,
                      float[] guideAlbedo,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, null, null, 0L, null, guideDepth, guideNormal, guideAlbedo, (float[]) null,
            denoiseR, denoiseG, denoiseB, null, null, null, null, null, null, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
                      int toneMapMode,
                      double invSamples,
                      double[] accumR,
                      double[] accumG,
                      double[] accumB,
                      float[] guideDepth,
                      float[] guideNormal,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      int[] outColor) {
        apply(width, height, workerCount, threadPool, radius, strength, exposure, toneMapMode, invSamples,
                accumR, accumG, accumB, null, null, 0L, null, guideDepth, guideNormal, null, (float[]) null,
            denoiseR, denoiseG, denoiseB, null, null, null, null, null, null, outColor);
    }

    private static FilterContext createContext(int width,
                                               int height,
                                               int workerCount,
                                               ThreadPool threadPool,
                                               int radius,
                                               double strength,
                                               double exposure,
                                               int toneMapMode,
                                               double invSamples,
                                               double[] accumR,
                                               double[] accumG,
                                               double[] accumB,
                                               double[] accumLuma,
                                               double[] accumLumaSq,
                                               long sampleCount,
                                               int[] pixelSampleCounts,
                                               boolean fastMode,
                                               float[] guideDepth,
                                               float[] guideNormal,
                                               float[] guideAlbedo,
                                               float[] guideRoughness,
                                               double[] denoiseR,
                                               double[] denoiseG,
                                               double[] denoiseB,
                                               double[] noiseMap,
                                               double[] smartBlendScale,
                                               double[] smartDetailScale,
                                               double[] scratchR,
                                               double[] scratchG,
                                               double[] scratchB,
                                               int[] outColor) {
        int localWidth = Math.max(1, width);
        int count = resolvePixelCount(outColor, accumR, accumG, accumB, denoiseR, denoiseG, denoiseB,
            guideDepth, guideNormal, guideAlbedo, guideRoughness, accumLuma, accumLumaSq,
            smartBlendScale, smartDetailScale);
        if (count <= 0) {
            return null;
        }

        int maxHeight = Math.max(1, height);
        int localHeight = Math.max(1, Math.min(maxHeight, (count + localWidth - 1) / localWidth));
        if (localHeight <= 0) {
            return null;
        }

        double[] localScratchR = ensureBuffer(scratchR, count);
        double[] localScratchG = ensureBuffer(scratchG, count);
        double[] localScratchB = ensureBuffer(scratchB, count);
        double[] localNoiseMap = ensureBuffer(noiseMap, count);
        return new FilterContext(
                localWidth,
                localHeight,
                count,
                Math.max(1, workerCount),
                threadPool,
                clampRadius(radius),
                DenoiseSupport.clamp01(strength),
                exposure,
                toneMapMode,
                fastMode,
                RuntimeInstrumentation.isEnabled(),
                invSamples,
                sampleCount,
                pixelSampleCounts,
                accumR,
                accumG,
                accumB,
                accumLuma,
                accumLumaSq,
                guideDepth,
                guideNormal,
                guideAlbedo,
                guideRoughness,
                denoiseR,
                denoiseG,
                denoiseB,
                localNoiseMap,
                smartBlendScale,
                smartDetailScale,
                localScratchR,
                localScratchG,
                localScratchB,
                outColor
        );
    }

    private static void seedMeanColor(FilterContext context) {
        for (int i = 0; i < context.count; i++) {
            double invSamples = resolveInverseSampleCount(context, i);
            context.denoiseR[i] = context.accumR[i] * invSamples;
            context.denoiseG[i] = context.accumG[i] * invSamples;
            context.denoiseB[i] = context.accumB[i] * invSamples;
        }
    }

    private static void prepareNoiseProfile(FilterContext context) {
        double peakNoise = 1.0;
        if (context.accumLuma == null || context.accumLumaSq == null) {
            for (int i = 0; i < context.count; i++) {
                context.noiseMap[i] = 1.0;
            }
            context.peakNoise = peakNoise;
            return;
        }

        peakNoise = 0.0;
        for (int i = 0; i < context.count; i++) {
            int sampleCount = resolvePixelSampleCount(context, i);
            double noise = DenoiseSupport.relativeNoise(context.accumLuma[i], context.accumLumaSq[i], sampleCount);
            context.noiseMap[i] = noise;
            peakNoise = Math.max(peakNoise, noise);
        }
        context.peakNoise = peakNoise;
    }

    private static void runPass(FilterContext context,
                                FilterPass pass,
                                double[] sourceR,
                                double[] sourceG,
                                double[] sourceB,
                                double[] targetR,
                                double[] targetG,
                                double[] targetB) {
        if (context.threadPool == null || context.workerCount <= 1 || context.height <= 4) {
            filterRows(context, pass, 0, context.height, sourceR, sourceG, sourceB, targetR, targetG, targetB);
            return;
        }

        AtomicInteger rowCursor = new AtomicInteger(0);
        int rowBlock = Math.max(2, Math.min(24, pass.step * 6));
        context.threadPool.submitAndWait(context.workerCount, workerIndex -> {
            while (true) {
                int startRow = rowCursor.getAndAdd(rowBlock);
                if (startRow >= context.height) {
                    return;
                }
                int endRow = Math.min(context.height, startRow + rowBlock);
                filterRows(context, pass, startRow, endRow, sourceR, sourceG, sourceB, targetR, targetG, targetB);
            }
        });
    }

    private static void filterRows(FilterContext context,
                                   FilterPass pass,
                                   int yStart,
                                   int yEnd,
                                   double[] sourceR,
                                   double[] sourceG,
                                   double[] sourceB,
                                   double[] targetR,
                                   double[] targetG,
                                   double[] targetB) {
        for (int y = yStart; y < yEnd; y++) {
            int row = y * context.width;
            for (int x = 0; x < context.width; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= context.count) {
                    continue;
                }

                double centerR = sourceR[idx];
                double centerG = sourceG[idx];
                double centerB = sourceB[idx];
                double noise = context.noiseMap[idx];
                double roughness = resolveGuideRoughness(context.guideRoughness, idx);
                double strengthScale = resolveSampleStrengthScale(context, idx, noise);
                double smartBlend = resolveSmartBlendScale(context.smartBlendScale, idx);
                double blend = resolveBlend(context.strength * strengthScale * smartBlend, noise, pass.index, roughness);
                if (blend <= 1e-5) {
                    targetR[idx] = centerR;
                    targetG[idx] = centerG;
                    targetB[idx] = centerB;
                    continue;
                }

                double centerLuma = DenoiseSupport.luminance(centerR, centerG, centerB);
                double invColorSigma2 = 1.0 / Math.max(1e-6, 2.0 * square(resolveColorSigma(
                        centerLuma,
                        context.strength,
                        noise,
                        pass.index,
                        roughness)));
                double depthScale = resolveDepthScale(noise, pass.index);
                double normalPower = resolveNormalPower(noise, pass.index);
                double invAlbedoSigma2 = 0.0;
                if (context.guideAlbedo != null) {
                    double albedoSigma = resolveAlbedoSigma(noise, pass.index);
                    invAlbedoSigma2 = 1.0 / Math.max(1e-6, 2.0 * albedoSigma * albedoSigma);
                }

                float centerDepth = context.guideDepth[idx];
                boolean centerHit = Float.isFinite(centerDepth);
                int centerBase = idx * 3;
                float centerNx = context.guideNormal[centerBase];
                float centerNy = context.guideNormal[centerBase + 1];
                float centerNz = context.guideNormal[centerBase + 2];
                boolean centerNormalValid = centerNx != 0.0f || centerNy != 0.0f || centerNz != 0.0f;
                float centerAlbedoR = 0.0f;
                float centerAlbedoG = 0.0f;
                float centerAlbedoB = 0.0f;
                if (context.guideAlbedo != null && centerHit) {
                    centerAlbedoR = context.guideAlbedo[centerBase];
                    centerAlbedoG = context.guideAlbedo[centerBase + 1];
                    centerAlbedoB = context.guideAlbedo[centerBase + 2];
                }

                double sumW = CENTER_WEIGHT;
                double sumR = centerR * CENTER_WEIGHT;
                double sumG = centerG * CENTER_WEIGHT;
                double sumB = centerB * CENTER_WEIGHT;
                long acceptedEdgeTaps = 0L;
                boolean telemetryPixel = context.telemetryEnabled
                        && ((x & TELEMETRY_SAMPLE_MASK) == 0)
                        && ((y & TELEMETRY_SAMPLE_MASK) == 0);

                int tapLimit = context.fastMode ? Math.min(FAST_TAP_COUNT, FILTER_TAPS.length) : FILTER_TAPS.length;
                for (int tapIndex = 0; tapIndex < tapLimit; tapIndex++) {
                    FilterTap tap = FILTER_TAPS[tapIndex];
                    int sx = x + tap.offsetX * pass.step;
                    int sy = y + tap.offsetY * pass.step;
                    if (sx < 0 || sx >= context.width || sy < 0 || sy >= context.height) {
                        continue;
                    }
                    int sampleIndex = sy * context.width + sx;
                    if (sampleIndex < 0 || sampleIndex >= context.count) {
                        continue;
                    }

                    double sampleR = sourceR[sampleIndex];
                    double sampleG = sourceG[sampleIndex];
                    double sampleB = sourceB[sampleIndex];
                    double colorWeight = colorWeight(centerR, centerG, centerB, sampleR, sampleG, sampleB, invColorSigma2);
                    if (colorWeight <= 1e-5) {
                        continue;
                    }

                    double depthWeight = depthWeight(context.guideDepth, sampleIndex, centerDepth, centerHit, depthScale);
                    if (depthWeight <= 1e-5) {
                        continue;
                    }

                    double normalWeight = normalWeight(context.guideNormal, centerBase, sampleIndex * 3, centerNormalValid, normalPower);
                    if (normalWeight <= 1e-5) {
                        continue;
                    }

                    double albedoWeight = albedoWeight(context.guideAlbedo, centerBase, sampleIndex * 3,
                            centerHit, Float.isFinite(context.guideDepth[sampleIndex]),
                            centerAlbedoR, centerAlbedoG, centerAlbedoB, invAlbedoSigma2);
                    if (albedoWeight <= 1e-5) {
                        continue;
                    }

                    double weight = tap.weight * colorWeight * depthWeight * normalWeight * albedoWeight;
                    sumW += weight;
                    sumR += sampleR * weight;
                    sumG += sampleG * weight;
                    sumB += sampleB * weight;
                    acceptedEdgeTaps++;
                }
                if (telemetryPixel && acceptedEdgeTaps > 0L) {
                    context.edgeTapCount.add(acceptedEdgeTaps * TELEMETRY_SAMPLE_SCALE);
                }

                double filteredR = sumR / sumW;
                double filteredG = sumG / sumW;
                double filteredB = sumB / sumW;
                double effectiveBlend = blend;
                if (pass.index == 0) {
                    double filteredLuma = DenoiseSupport.luminance(filteredR, filteredG, filteredB);
                    double hotPixelBlend = resolveHotPixelBlend(
                            context.strength,
                            noise,
                            roughness,
                            centerLuma,
                            filteredLuma);
                    if (telemetryPixel && hotPixelBlend > 1e-5) {
                        context.hotPixelPixels.add(TELEMETRY_SAMPLE_SCALE);
                    }
                    if (hotPixelBlend > 1e-5) {
                        effectiveBlend = DenoiseSupport.clamp01(blend + hotPixelBlend * (1.0 - blend));
                    }
                }
                effectiveBlend = resolveSubpixelBlendBoost(
                        effectiveBlend,
                        centerLuma,
                        filteredR,
                        filteredG,
                        filteredB,
                        noise,
                        sumW);
                    if (context.guideAlbedo != null && centerHit) {
                        double projectionCenter = projectOnAlbedo(
                            centerR,
                            centerG,
                            centerB,
                            centerAlbedoR,
                            centerAlbedoG,
                            centerAlbedoB);
                        double projectionFiltered = projectOnAlbedo(
                            filteredR,
                            filteredG,
                            filteredB,
                            centerAlbedoR,
                            centerAlbedoG,
                            centerAlbedoB);

                        double centerDiffuseR = centerAlbedoR * projectionCenter;
                        double centerDiffuseG = centerAlbedoG * projectionCenter;
                        double centerDiffuseB = centerAlbedoB * projectionCenter;
                        double filteredDiffuseR = centerAlbedoR * projectionFiltered;
                        double filteredDiffuseG = centerAlbedoG * projectionFiltered;
                        double filteredDiffuseB = centerAlbedoB * projectionFiltered;

                        double centerSpecularR = centerR - centerDiffuseR;
                        double centerSpecularG = centerG - centerDiffuseG;
                        double centerSpecularB = centerB - centerDiffuseB;
                        double filteredSpecularR = filteredR - filteredDiffuseR;
                        double filteredSpecularG = filteredG - filteredDiffuseG;
                        double filteredSpecularB = filteredB - filteredDiffuseB;

                        double diffuseBlend = DenoiseSupport.clamp01(effectiveBlend
 * (DIFFUSE_BLEND_BASE + roughness * DIFFUSE_BLEND_ROUGHNESS_SCALE));
                        double specularBlend = DenoiseSupport.clamp01(effectiveBlend
 * (SPECULAR_BLEND_BASE + roughness * SPECULAR_BLEND_ROUGHNESS_SCALE));

                        targetR[idx] = DenoiseSupport.lerp(centerDiffuseR, filteredDiffuseR, diffuseBlend)
                            + DenoiseSupport.lerp(centerSpecularR, filteredSpecularR, specularBlend);
                        targetG[idx] = DenoiseSupport.lerp(centerDiffuseG, filteredDiffuseG, diffuseBlend)
                            + DenoiseSupport.lerp(centerSpecularG, filteredSpecularG, specularBlend);
                        targetB[idx] = DenoiseSupport.lerp(centerDiffuseB, filteredDiffuseB, diffuseBlend)
                            + DenoiseSupport.lerp(centerSpecularB, filteredSpecularB, specularBlend);
                    } else {
                        targetR[idx] = DenoiseSupport.lerp(centerR, filteredR, effectiveBlend);
                        targetG[idx] = DenoiseSupport.lerp(centerG, filteredG, effectiveBlend);
                        targetB[idx] = DenoiseSupport.lerp(centerB, filteredB, effectiveBlend);
                    }
            }
        }
    }

                private static double projectOnAlbedo(double colorR,
                                  double colorG,
                                  double colorB,
                                  double albedoR,
                                  double albedoG,
                                  double albedoB) {
                double denominator = albedoR * albedoR + albedoG * albedoG + albedoB * albedoB;
                if (denominator <= 1e-7) {
                    return 0.0;
                }
                return Math.max(0.0, (colorR * albedoR + colorG * albedoG + colorB * albedoB) / denominator);
                }

    private static double colorWeight(double centerR,
                                      double centerG,
                                      double centerB,
                                      double sampleR,
                                      double sampleG,
                                      double sampleB,
                                      double invColorSigma2) {
        double diffR = sampleR - centerR;
        double diffG = sampleG - centerG;
        double diffB = sampleB - centerB;
        return Math.exp(-(diffR * diffR + diffG * diffG + diffB * diffB) * invColorSigma2);
    }

    private static double depthWeight(float[] guideDepth,
                                      int sampleIndex,
                                      float centerDepth,
                                      boolean centerHit,
                                      double depthScale) {
        float sampleDepth = guideDepth[sampleIndex];
        boolean sampleHit = Float.isFinite(sampleDepth);
        if (centerHit && sampleHit) {
            double relativeDepth = Math.abs(sampleDepth - centerDepth)
                    / Math.max(1e-4, Math.max(Math.abs(centerDepth), Math.abs(sampleDepth)));
            return 1.0 / (1.0 + relativeDepth * depthScale);
        }
        if (centerHit == sampleHit) {
            return 1.0;
        }
        return 0.002;
    }

    private static double normalWeight(float[] guideNormal,
                                       int centerBase,
                                       int sampleBase,
                                       boolean centerNormalValid,
                                       double normalPower) {
        float centerNx = guideNormal[centerBase];
        float centerNy = guideNormal[centerBase + 1];
        float centerNz = guideNormal[centerBase + 2];
        float sampleNx = guideNormal[sampleBase];
        float sampleNy = guideNormal[sampleBase + 1];
        float sampleNz = guideNormal[sampleBase + 2];
        boolean sampleNormalValid = sampleNx != 0.0f || sampleNy != 0.0f || sampleNz != 0.0f;
        if (centerNormalValid && sampleNormalValid) {
            double dot = centerNx * sampleNx + centerNy * sampleNy + centerNz * sampleNz;
            return Math.pow(Math.max(0.0, Math.min(1.0, dot)), normalPower);
        }
        if (centerNormalValid == sampleNormalValid) {
            return 1.0;
        }
        return 0.002;
    }

    private static double albedoWeight(float[] guideAlbedo,
                                       int centerBase,
                                       int sampleBase,
                                       boolean centerHit,
                                       boolean sampleHit,
                                       float centerAlbedoR,
                                       float centerAlbedoG,
                                       float centerAlbedoB,
                                       double invAlbedoSigma2) {
        if (guideAlbedo == null) {
            return 1.0;
        }
        if (centerHit && sampleHit) {
            double diffR = guideAlbedo[sampleBase] - centerAlbedoR;
            double diffG = guideAlbedo[sampleBase + 1] - centerAlbedoG;
            double diffB = guideAlbedo[sampleBase + 2] - centerAlbedoB;
            return Math.exp(-(diffR * diffR + diffG * diffG + diffB * diffB) * invAlbedoSigma2);
        }
        if (centerHit == sampleHit) {
            return 1.0;
        }
        return 0.002;
    }

    private static double resolveBlend(double baseStrength, double noise, int passIndex, double roughness) {
        double surfaceFactor = balancedSurfaceFactor(roughness);
        double baseBlend = DenoiseSupport.clamp01(baseStrength
 * (BLEND_BASE_FLOOR + noise * BLEND_NOISE_SCALE)
 * surfaceFactor);
        double passGate = DenoiseSupport.clamp01(noise * BLEND_PASS_GATE_NOISE
                - passIndex * BLEND_PASS_GATE_DECAY
                + BLEND_PASS_GATE_BIAS);
        return DenoiseSupport.clamp01(baseBlend * passGate);
    }

    private static double resolveSampleStrengthScale(FilterContext context, int pixelIndex, double noise) {
        int sampleCount = resolvePixelSampleCount(context, pixelIndex);
        int stableRange = Math.max(1, FULL_STABLE_SAMPLES - STABLE_SAMPLE_START);
        double sampleProgress = DenoiseSupport.clamp01((sampleCount - STABLE_SAMPLE_START) / (double) stableRange);
        double noiseProgress = DenoiseSupport.clamp01((noise - LOW_NOISE_GUARD)
                / Math.max(1e-6, HIGH_NOISE_GUARD - LOW_NOISE_GUARD));
        double stability = sampleProgress * (1.0 - noiseProgress);
        return 1.0 - MAX_STABLE_STRENGTH_REDUCTION * stability;
    }

    private static double resolveEffectiveBlend(double baseStrength,
                                                double noise,
                                                int passIndex,
                                                double roughness,
                                                double centerLuma,
                                                double filteredR,
                                                double filteredG,
                                                double filteredB,
                                                double baseBlend) {
        if (passIndex != 0) {
            return baseBlend;
        }
        double filteredLuma = DenoiseSupport.luminance(filteredR, filteredG, filteredB);
        double hotPixelBlend = resolveHotPixelBlend(baseStrength, noise, roughness, centerLuma, filteredLuma);
        if (hotPixelBlend <= 1e-5) {
            return baseBlend;
        }
        return DenoiseSupport.clamp01(baseBlend + hotPixelBlend * (1.0 - baseBlend));
    }

    private static double resolveSubpixelBlendBoost(double currentBlend,
                                                    double centerLuma,
                                                    double filteredR,
                                                    double filteredG,
                                                    double filteredB,
                                                    double noise,
                                                    double sumW) {
        double filteredLuma = DenoiseSupport.luminance(filteredR, filteredG, filteredB);
        double localGradient = Math.abs(centerLuma - filteredLuma);
        double flatFactor = DenoiseSupport.clamp01(1.0 - localGradient / SUBPIXEL_FLAT_GRADIENT_THRESHOLD);
        if (flatFactor <= 1e-5) {
            return currentBlend;
        }
        double noiseFactor = DenoiseSupport.clamp01((noise - SUBPIXEL_NOISE_START) / SUBPIXEL_NOISE_RANGE);
        if (noiseFactor <= 1e-5) {
            return currentBlend;
        }
        double guideFactor = DenoiseSupport.clamp01((sumW - CENTER_WEIGHT) / SUBPIXEL_GUIDE_WEIGHT_SCALE);
        if (guideFactor <= 1e-5) {
            return currentBlend;
        }
        double boost = SUBPIXEL_MAX_EXTRA_BLEND * flatFactor * noiseFactor * guideFactor;
        return DenoiseSupport.clamp01(currentBlend + boost * (1.0 - currentBlend));
    }

    private static double resolveHotPixelBlend(double baseStrength,
                                               double noise,
                                               double roughness,
                                               double centerLuma,
                                               double filteredLuma) {
        if (noise <= HOT_PIXEL_NOISE_MIN || !Double.isFinite(centerLuma) || !Double.isFinite(filteredLuma)) {
            return 0.0;
        }
        double safeFilteredLuma = Math.max(0.0, filteredLuma);
        double thresholdLuma = safeFilteredLuma
 * (HOT_PIXEL_THRESHOLD_BASE + (1.0 - noise) * HOT_PIXEL_THRESHOLD_STABILITY_SCALE)
            + HOT_PIXEL_THRESHOLD_LUMA_BIAS
            + (1.0 - baseStrength) * HOT_PIXEL_THRESHOLD_STRENGTH_BIAS;
        if (centerLuma <= thresholdLuma) {
            return 0.0;
        }
        double overflow = centerLuma - thresholdLuma;
        double normalizedOverflow = overflow / Math.max(HOT_PIXEL_NORMALIZATION_FLOOR, thresholdLuma);
        double surfaceFactor = balancedSurfaceFactor(roughness);
        return DenoiseSupport.clamp01(normalizedOverflow
 * (HOT_PIXEL_BASE + noise * HOT_PIXEL_NOISE_SCALE)
 * surfaceFactor);
    }

    private static double resolveColorSigma(double centerLuma,
                                            double baseStrength,
                                            double noise,
                                            int passIndex,
                                            double roughness) {
        double surfaceFactor = balancedSurfaceFactor(roughness);
        return COLOR_SIGMA_BASE
                + centerLuma * COLOR_SIGMA_LUMA_SCALE * surfaceFactor
                + (1.0 - baseStrength) * COLOR_SIGMA_STRENGTH_SCALE
                + noise * (COLOR_SIGMA_NOISE_BASE + passIndex * COLOR_SIGMA_PASS_SCALE) * surfaceFactor;
    }

    private static double balancedSurfaceFactor(double roughness) {
        double centeredRoughness = DenoiseSupport.clamp01(roughness) * 2.0 - 1.0;
        return 1.0 + centeredRoughness * ROUGHNESS_BALANCE_RANGE;
    }

    private static double resolveDepthScale(double noise, int passIndex) {
        return 34.0 + (1.0 - noise) * 54.0 + passIndex * 14.0;
    }

    private static double resolveNormalPower(double noise, int passIndex) {
        return 26.0 + (1.0 - noise) * 44.0 + passIndex * 7.0;
    }

    private static double resolveAlbedoSigma(double noise, int passIndex) {
        return 0.024 + noise * 0.070 + passIndex * 0.006;
    }

    static int resolvePassCount(int radius, double peakNoise) {
        return resolvePassCount(radius, peakNoise, false);
    }

    static int resolvePassCount(int radius, double peakNoise, boolean fastMode) {
        int maxPassCount = Math.max(2, Math.min(MAX_PASSES, radius + 2));
        if (fastMode) {
            maxPassCount = Math.max(2, Math.min(FAST_MAX_PASSES, maxPassCount - 1));
        }
        if (maxPassCount <= 2) {
            return maxPassCount;
        }
        if (peakNoise >= VERY_NOISY_FRAME_THRESHOLD) {
            return maxPassCount;
        }
        if (peakNoise < STABLE_FRAME_NOISE_THRESHOLD) {
            return Math.max(2, maxPassCount - 2);
        }
        return Math.max(2, maxPassCount - 1);
    }

    private static void commitDenoisedColor(FilterContext context,
                                            double[] sourceR,
                                            double[] sourceG,
                                            double[] sourceB) {
        if (sourceR != context.denoiseR) {
            System.arraycopy(sourceR, 0, context.denoiseR, 0, context.count);
            System.arraycopy(sourceG, 0, context.denoiseG, 0, context.count);
            System.arraycopy(sourceB, 0, context.denoiseB, 0, context.count);
        }
        for (int i = 0; i < context.count; i++) {
            double resolvedR = context.denoiseR[i];
            double resolvedG = context.denoiseG[i];
            double resolvedB = context.denoiseB[i];
            double detailRecovery = context.fastMode ? 0.0 : resolveDetailRecovery(context, i);
            if (detailRecovery > 1e-5) {
                double invSamples = resolveInverseSampleCount(context, i);
                double meanR = context.accumR[i] * invSamples;
                double meanG = context.accumG[i] * invSamples;
                double meanB = context.accumB[i] * invSamples;
                resolvedR = DenoiseSupport.lerp(resolvedR, meanR, detailRecovery);
                resolvedG = DenoiseSupport.lerp(resolvedG, meanG, detailRecovery);
                resolvedB = DenoiseSupport.lerp(resolvedB, meanB, detailRecovery);
                context.denoiseR[i] = resolvedR;
                context.denoiseG[i] = resolvedG;
                context.denoiseB[i] = resolvedB;
            }
            context.outColor[i] = packColor(
                    toneMap(resolvedR, context.exposure, context.toneMapMode),
                    toneMap(resolvedG, context.exposure, context.toneMapMode),
                    toneMap(resolvedB, context.exposure, context.toneMapMode)
            );
        }
    }

    private static double resolveDetailRecovery(FilterContext context, int pixelIndex) {
        if (pixelIndex < 0 || pixelIndex >= context.count) {
            return 0.0;
        }
        double roughness = resolveGuideRoughness(context.guideRoughness, pixelIndex);
        double noise = context.noiseMap[pixelIndex];
        int sampleCount = resolvePixelSampleCount(context, pixelIndex);
        double smartDetailScale = resolveSmartDetailScale(context.smartDetailScale, pixelIndex);
        double stability = DenoiseSupport.clamp01(1.0 - noise * DETAIL_RECOVERY_NOISE_SCALE);
        double sampleBoost = DenoiseSupport.clamp01((sampleCount - DETAIL_RECOVERY_SAMPLE_START)
                / DETAIL_RECOVERY_SAMPLE_RANGE);
        double baseRecovery = DETAIL_RECOVERY_BASE * balancedSurfaceFactor(roughness) * smartDetailScale;
        if (context.guideRoughness == null) {
            // Bez guide roughness drzi filtr konzervativni profil, ale stale povoli mirne obnoveni textury.
            baseRecovery *= 0.60;
        }
        return DenoiseSupport.clamp01(stability * baseRecovery * (1.0 + DETAIL_RECOVERY_SAMPLE_BOOST * sampleBoost));
    }

    private static int resolvePixelSampleCount(FilterContext context, int pixelIndex) {
        return AdaptiveSamplingSupport.resolveSampleCount(context.sampleCounts, pixelIndex, context.sampleCount);
    }

    private static double resolveInverseSampleCount(FilterContext context, int pixelIndex) {
        int sampleCount = resolvePixelSampleCount(context, pixelIndex);
        return AdaptiveSamplingSupport.inverseSampleCount(sampleCount, context.sampleCount);
    }

    private static double toneMap(double c, double exposure, int toneMapMode) {
        return ToneMapSupport.toneMap(c, exposure, toneMapMode);
    }

    private static int packColor(double r, double g, double b) {
        int ir = (int) (DenoiseSupport.clamp01(r) * 255.0 + 0.5);
        int ig = (int) (DenoiseSupport.clamp01(g) * 255.0 + 0.5);
        int ib = (int) (DenoiseSupport.clamp01(b) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private static int resolvePixelCount(int[] outColor,
                                         double[] accumR,
                                         double[] accumG,
                                         double[] accumB,
                                         double[] denoiseR,
                                         double[] denoiseG,
                                         double[] denoiseB,
                                         float[] guideDepth,
                                         float[] guideNormal,
                                         float[] guideAlbedo,
                                         float[] guideRoughness,
                                         double[] accumLuma,
                                         double[] accumLumaSq,
                                         double[] smartBlendScale,
                                         double[] smartDetailScale) {
        int count = arrayLength(outColor);
        count = Math.min(count, arrayLength(accumR));
        count = Math.min(count, arrayLength(accumG));
        count = Math.min(count, arrayLength(accumB));
        count = Math.min(count, arrayLength(denoiseR));
        count = Math.min(count, arrayLength(denoiseG));
        count = Math.min(count, arrayLength(denoiseB));
        count = Math.min(count, arrayLength(guideDepth));
        count = Math.min(count, vectorLength(guideNormal));
        if (guideAlbedo != null) {
            count = Math.min(count, vectorLength(guideAlbedo));
        }
        if (guideRoughness != null) {
            count = Math.min(count, arrayLength(guideRoughness));
        }
        if (accumLuma != null && accumLumaSq != null) {
            count = Math.min(count, arrayLength(accumLuma));
            count = Math.min(count, arrayLength(accumLumaSq));
        }
        if (smartBlendScale != null) {
            count = Math.min(count, arrayLength(smartBlendScale));
        }
        if (smartDetailScale != null) {
            count = Math.min(count, arrayLength(smartDetailScale));
        }
        return count;
    }

    private static double[] ensureBuffer(double[] buffer, int count) {
        if (buffer != null && buffer.length >= count) {
            return buffer;
        }
        return new double[count];
    }

    private static int clampRadius(int radius) {
        return Math.max(1, Math.min(4, radius));
    }

    private static int arrayLength(int[] values) {
        return values == null ? 0 : values.length;
    }

    private static int arrayLength(double[] values) {
        return values == null ? 0 : values.length;
    }

    private static int arrayLength(float[] values) {
        return values == null ? 0 : values.length;
    }

    private static int vectorLength(float[] values) {
        return values == null ? 0 : values.length / 3;
    }

    private static double resolveGuideRoughness(float[] guideRoughness, int index) {
        if (guideRoughness == null || index < 0 || index >= guideRoughness.length) {
            return 1.0;
        }
        return DenoiseSupport.clamp01(guideRoughness[index]);
    }

    private static double resolveSmartBlendScale(double[] smartBlendScale, int index) {
        if (smartBlendScale == null || index < 0 || index >= smartBlendScale.length) {
            return 1.0;
        }
        return 0.20 + DenoiseSupport.clamp01(smartBlendScale[index]) * 1.20;
    }

    private static double resolveSmartDetailScale(double[] smartDetailScale, int index) {
        if (smartDetailScale == null || index < 0 || index >= smartDetailScale.length) {
            return 1.0;
        }
        return 0.35 + DenoiseSupport.clamp01(smartDetailScale[index]) * 1.55;
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class FilterContext {
        private final int width;
        private final int height;
        private final int count;
        private final int workerCount;
        private final ThreadPool threadPool;
        private final int radius;
        private final double strength;
        private final double exposure;
        private final int toneMapMode;
        private final boolean fastMode;
        private final boolean telemetryEnabled;
        private final double invSamples;
        private final long sampleCount;
        private final int[] sampleCounts;
        private final double[] accumR;
        private final double[] accumG;
        private final double[] accumB;
        private final double[] accumLuma;
        private final double[] accumLumaSq;
        private final float[] guideDepth;
        private final float[] guideNormal;
        private final float[] guideAlbedo;
        private final float[] guideRoughness;
        private final double[] denoiseR;
        private final double[] denoiseG;
        private final double[] denoiseB;
        private final double[] noiseMap;
        private final double[] smartBlendScale;
        private final double[] smartDetailScale;
        private final double[] scratchR;
        private final double[] scratchG;
        private final double[] scratchB;
        private final int[] outColor;
        private final LongAdder hotPixelPixels;
        private final LongAdder edgeTapCount;
        private double peakNoise;

        private FilterContext(int width,
                              int height,
                              int count,
                              int workerCount,
                              ThreadPool threadPool,
                              int radius,
                              double strength,
                              double exposure,
                              int toneMapMode,
                              boolean fastMode,
                              boolean telemetryEnabled,
                              double invSamples,
                              long sampleCount,
                              int[] sampleCounts,
                              double[] accumR,
                              double[] accumG,
                              double[] accumB,
                              double[] accumLuma,
                              double[] accumLumaSq,
                              float[] guideDepth,
                              float[] guideNormal,
                              float[] guideAlbedo,
                              float[] guideRoughness,
                              double[] denoiseR,
                              double[] denoiseG,
                              double[] denoiseB,
                              double[] noiseMap,
                              double[] smartBlendScale,
                              double[] smartDetailScale,
                              double[] scratchR,
                              double[] scratchG,
                              double[] scratchB,
                              int[] outColor) {
            this.width = width;
            this.height = height;
            this.count = count;
            this.workerCount = workerCount;
            this.threadPool = threadPool;
            this.radius = radius;
            this.strength = strength;
            this.exposure = exposure;
            this.toneMapMode = toneMapMode;
            this.fastMode = fastMode;
            this.telemetryEnabled = telemetryEnabled;
            this.invSamples = invSamples;
            this.sampleCount = sampleCount;
            this.sampleCounts = sampleCounts;
            this.accumR = accumR;
            this.accumG = accumG;
            this.accumB = accumB;
            this.accumLuma = accumLuma;
            this.accumLumaSq = accumLumaSq;
            this.guideDepth = guideDepth;
            this.guideNormal = guideNormal;
            this.guideAlbedo = guideAlbedo;
            this.guideRoughness = guideRoughness;
            this.denoiseR = denoiseR;
            this.denoiseG = denoiseG;
            this.denoiseB = denoiseB;
            this.noiseMap = noiseMap;
            this.smartBlendScale = smartBlendScale;
            this.smartDetailScale = smartDetailScale;
            this.scratchR = scratchR;
            this.scratchG = scratchG;
            this.scratchB = scratchB;
            this.outColor = outColor;
            this.hotPixelPixels = telemetryEnabled ? new LongAdder() : null;
            this.edgeTapCount = telemetryEnabled ? new LongAdder() : null;
        }
    }

    private static final class FilterPass {
        private final int index;
        private final int step;

        private FilterPass(int index, int step) {
            this.index = index;
            this.step = step;
        }
    }

    private static final class FilterTap {
        private final int offsetX;
        private final int offsetY;
        private final double weight;

        private FilterTap(int offsetX, int offsetY, double weight) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.weight = weight;
        }
    }
}
