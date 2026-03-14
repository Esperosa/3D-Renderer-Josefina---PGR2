package engine.render.ray;

import engine.util.ThreadPool;

import java.util.concurrent.atomic.AtomicInteger;

final class JointBilateralDenoiser {

    private static final double INV_GAMMA = 1.0 / 2.2;
    private static final double CENTER_WEIGHT = 1.45;
    private static final int MAX_PASSES = 4;
    private static final double STABLE_FRAME_NOISE_THRESHOLD = 0.08;
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
        apply(width, height, workerCount, threadPool, radius, strength, exposure, invSamples,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount,
                guideDepth, guideNormal, guideAlbedo,
                denoiseR, denoiseG, denoiseB, null, scratchR, scratchG, scratchB, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
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
        FilterContext context = createContext(
                width,
                height,
                workerCount,
                threadPool,
                radius,
                strength,
                exposure,
                invSamples,
                accumR,
                accumG,
                accumB,
                accumLuma,
                accumLumaSq,
                sampleCount,
                guideDepth,
                guideNormal,
                guideAlbedo,
                denoiseR,
                denoiseG,
                denoiseB,
                noiseMap,
                scratchR,
                scratchG,
                scratchB,
                outColor
        );
        if (context == null) {
            return;
        }

        seedMeanColor(context);
        prepareNoiseProfile(context);

        double[] sourceR = context.denoiseR;
        double[] sourceG = context.denoiseG;
        double[] sourceB = context.denoiseB;
        double[] targetR = context.scratchR;
        double[] targetG = context.scratchG;
        double[] targetB = context.scratchB;

        int passCount = resolvePassCount(context.radius, context.peakNoise);
        for (int passIndex = 0; passIndex < passCount; passIndex++) {
            FilterPass pass = new FilterPass(passIndex, 1 << passIndex);
            runPass(context, pass, sourceR, sourceG, sourceB, targetR, targetG, targetB);

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

        commitDenoisedColor(context, sourceR, sourceG, sourceB);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
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
        apply(width, height, workerCount, threadPool, radius, strength, exposure, invSamples,
                accumR, accumG, accumB, null, null, 0L, guideDepth, guideNormal, guideAlbedo,
                denoiseR, denoiseG, denoiseB, null, null, null, null, outColor);
    }

    static void apply(int width,
                      int height,
                      int workerCount,
                      ThreadPool threadPool,
                      int radius,
                      double strength,
                      double exposure,
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
        apply(width, height, workerCount, threadPool, radius, strength, exposure, invSamples,
                accumR, accumG, accumB, null, null, 0L, guideDepth, guideNormal, null,
                denoiseR, denoiseG, denoiseB, null, null, null, null, outColor);
    }

    private static FilterContext createContext(int width,
                                               int height,
                                               int workerCount,
                                               ThreadPool threadPool,
                                               int radius,
                                               double strength,
                                               double exposure,
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
        int localWidth = Math.max(1, width);
        int count = resolvePixelCount(outColor, accumR, accumG, accumB, denoiseR, denoiseG, denoiseB,
                guideDepth, guideNormal, guideAlbedo, accumLuma, accumLumaSq);
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
                invSamples,
                sampleCount,
                accumR,
                accumG,
                accumB,
                accumLuma,
                accumLumaSq,
                guideDepth,
                guideNormal,
                guideAlbedo,
                denoiseR,
                denoiseG,
                denoiseB,
                localNoiseMap,
                localScratchR,
                localScratchG,
                localScratchB,
                outColor
        );
    }

    private static void seedMeanColor(FilterContext context) {
        for (int i = 0; i < context.count; i++) {
            context.denoiseR[i] = context.accumR[i] * context.invSamples;
            context.denoiseG[i] = context.accumG[i] * context.invSamples;
            context.denoiseB[i] = context.accumB[i] * context.invSamples;
        }
    }

    private static void prepareNoiseProfile(FilterContext context) {
        double peakNoise = 1.0;
        if (context.accumLuma == null || context.accumLumaSq == null || context.sampleCount <= 0L) {
            for (int i = 0; i < context.count; i++) {
                context.noiseMap[i] = 1.0;
            }
            context.peakNoise = peakNoise;
            return;
        }

        peakNoise = 0.0;
        for (int i = 0; i < context.count; i++) {
            double noise = DenoiseSupport.relativeNoise(context.accumLuma[i], context.accumLumaSq[i], context.sampleCount);
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
        Runnable[] tasks = new Runnable[context.workerCount];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = () -> {
                while (true) {
                    int startRow = rowCursor.getAndAdd(rowBlock);
                    if (startRow >= context.height) {
                        return;
                    }
                    int endRow = Math.min(context.height, startRow + rowBlock);
                    filterRows(context, pass, startRow, endRow, sourceR, sourceG, sourceB, targetR, targetG, targetB);
                }
            };
        }
        context.threadPool.submitAndWait(tasks);
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
                double blend = resolveBlend(context.strength, noise, pass.index);
                if (blend <= 1e-5) {
                    targetR[idx] = centerR;
                    targetG[idx] = centerG;
                    targetB[idx] = centerB;
                    continue;
                }

                double centerLuma = DenoiseSupport.luminance(centerR, centerG, centerB);
                double invColorSigma2 = 1.0 / Math.max(1e-6, 2.0 * square(resolveColorSigma(centerLuma, context.strength, noise, pass.index)));
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

                for (FilterTap tap : FILTER_TAPS) {
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
                }

                double filteredR = sumR / sumW;
                double filteredG = sumG / sumW;
                double filteredB = sumB / sumW;
                targetR[idx] = DenoiseSupport.lerp(centerR, filteredR, blend);
                targetG[idx] = DenoiseSupport.lerp(centerG, filteredG, blend);
                targetB[idx] = DenoiseSupport.lerp(centerB, filteredB, blend);
            }
        }
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
        return 0.02;
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
        return 0.02;
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
        return 0.02;
    }

    private static double resolveBlend(double baseStrength, double noise, int passIndex) {
        double baseBlend = DenoiseSupport.clamp01(baseStrength * (0.58 + noise * 0.92));
        double passGate = DenoiseSupport.clamp01(noise * 1.35 - passIndex * 0.24 + 0.32);
        return DenoiseSupport.clamp01(baseBlend * passGate);
    }

    private static double resolveColorSigma(double centerLuma, double baseStrength, double noise, int passIndex) {
        return 0.018
                + centerLuma * 0.055
                + (1.0 - baseStrength) * 0.028
                + noise * (0.095 + passIndex * 0.018);
    }

    private static double resolveDepthScale(double noise, int passIndex) {
        return 20.0 + (1.0 - noise) * 34.0 + passIndex * 10.0;
    }

    private static double resolveNormalPower(double noise, int passIndex) {
        return 14.0 + (1.0 - noise) * 30.0 + passIndex * 6.0;
    }

    private static double resolveAlbedoSigma(double noise, int passIndex) {
        return 0.05 + noise * 0.16 + passIndex * 0.015;
    }

    static int resolvePassCount(int radius, double peakNoise) {
        int maxPassCount = Math.max(2, Math.min(MAX_PASSES, radius + 1));
        if (maxPassCount <= 2) {
            return maxPassCount;
        }
        if (peakNoise < STABLE_FRAME_NOISE_THRESHOLD) {
            return maxPassCount - 1;
        }
        return maxPassCount;
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
            context.outColor[i] = packColor(
                    toneMap(context.denoiseR[i], context.exposure),
                    toneMap(context.denoiseG[i], context.exposure),
                    toneMap(context.denoiseB[i], context.exposure)
            );
        }
    }

    private static double toneMap(double c, double exposure) {
        double mapped = 1.0 - Math.exp(-Math.max(0.0, c) * exposure);
        mapped = DenoiseSupport.clamp01(mapped);
        return Math.pow(mapped, INV_GAMMA);
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
                                         double[] accumLuma,
                                         double[] accumLumaSq) {
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
        if (accumLuma != null && accumLumaSq != null) {
            count = Math.min(count, arrayLength(accumLuma));
            count = Math.min(count, arrayLength(accumLumaSq));
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
        private final double invSamples;
        private final long sampleCount;
        private final double[] accumR;
        private final double[] accumG;
        private final double[] accumB;
        private final double[] accumLuma;
        private final double[] accumLumaSq;
        private final float[] guideDepth;
        private final float[] guideNormal;
        private final float[] guideAlbedo;
        private final double[] denoiseR;
        private final double[] denoiseG;
        private final double[] denoiseB;
        private final double[] noiseMap;
        private final double[] scratchR;
        private final double[] scratchG;
        private final double[] scratchB;
        private final int[] outColor;
        private double peakNoise;

        private FilterContext(int width,
                              int height,
                              int count,
                              int workerCount,
                              ThreadPool threadPool,
                              int radius,
                              double strength,
                              double exposure,
                              double invSamples,
                              long sampleCount,
                              double[] accumR,
                              double[] accumG,
                              double[] accumB,
                              double[] accumLuma,
                              double[] accumLumaSq,
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
            this.width = width;
            this.height = height;
            this.count = count;
            this.workerCount = workerCount;
            this.threadPool = threadPool;
            this.radius = radius;
            this.strength = strength;
            this.exposure = exposure;
            this.invSamples = invSamples;
            this.sampleCount = sampleCount;
            this.accumR = accumR;
            this.accumG = accumG;
            this.accumB = accumB;
            this.accumLuma = accumLuma;
            this.accumLumaSq = accumLumaSq;
            this.guideDepth = guideDepth;
            this.guideNormal = guideNormal;
            this.guideAlbedo = guideAlbedo;
            this.denoiseR = denoiseR;
            this.denoiseG = denoiseG;
            this.denoiseB = denoiseB;
            this.noiseMap = noiseMap;
            this.scratchR = scratchR;
            this.scratchG = scratchG;
            this.scratchB = scratchB;
            this.outColor = outColor;
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
