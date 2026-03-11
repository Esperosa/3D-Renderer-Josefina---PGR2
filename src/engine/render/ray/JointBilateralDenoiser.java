package engine.render.ray;

import engine.util.ThreadPool;

import java.util.concurrent.atomic.AtomicInteger;

final class JointBilateralDenoiser {

    private static final double INV_GAMMA = 1.0 / 2.2;

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
                      float[] guideDepth,
                      float[] guideNormal,
                      double[] denoiseR,
                      double[] denoiseG,
                      double[] denoiseB,
                      int[] outColor) {
        int localWidth = Math.max(1, width);
        int count = Math.min(
                Math.max(0, localWidth * Math.max(1, height)),
                Math.min(outColor == null ? 0 : outColor.length,
                        Math.min(accumR == null ? 0 : accumR.length,
                                Math.min(accumG == null ? 0 : accumG.length,
                                        Math.min(accumB == null ? 0 : accumB.length,
                                                Math.min(denoiseR == null ? 0 : denoiseR.length,
                                                        Math.min(denoiseG == null ? 0 : denoiseG.length,
                                                                Math.min(denoiseB == null ? 0 : denoiseB.length,
                                                                        Math.min(guideDepth == null ? 0 : guideDepth.length,
                                                                                (guideNormal == null ? 0 : guideNormal.length / 3)))))))))
        );
        if (count <= 0) {
            return;
        }
        int localHeight = Math.max(1, Math.min(height, count / localWidth));
        if (localHeight <= 0) {
            return;
        }

        int clampedRadius = Math.max(1, Math.min(4, radius));
        double clampedStrength = clamp01(strength);
        double[] spatialKernel = buildSpatialKernel(clampedRadius, clampedStrength);

        if (threadPool == null || workerCount <= 1 || localHeight <= 4) {
            processRows(0, localHeight, localWidth, localHeight, clampedRadius, clampedStrength, exposure, invSamples,
                    spatialKernel, accumR, accumG, accumB, guideDepth, guideNormal, denoiseR, denoiseG, denoiseB, outColor, count);
            return;
        }

        AtomicInteger rowCursor = new AtomicInteger(0);
        int rowBlock = Math.max(2, Math.min(16, clampedRadius * 4));
        Runnable[] tasks = new Runnable[workerCount];
        for (int i = 0; i < tasks.length; i++) {
            tasks[i] = () -> {
                while (true) {
                    int startRow = rowCursor.getAndAdd(rowBlock);
                    if (startRow >= localHeight) {
                        return;
                    }
                    int endRow = Math.min(localHeight, startRow + rowBlock);
                    processRows(startRow, endRow, localWidth, localHeight, clampedRadius, clampedStrength, exposure, invSamples,
                            spatialKernel, accumR, accumG, accumB, guideDepth, guideNormal, denoiseR, denoiseG, denoiseB, outColor, count);
                }
            };
        }
        threadPool.submitAndWait(tasks);
    }

    private static void processRows(int yStart,
                                    int yEnd,
                                    int width,
                                    int height,
                                    int radius,
                                    double strength,
                                    double exposure,
                                    double invSamples,
                                    double[] spatialKernel,
                                    double[] accumR,
                                    double[] accumG,
                                    double[] accumB,
                                    float[] guideDepth,
                                    float[] guideNormal,
                                    double[] denoiseR,
                                    double[] denoiseG,
                                    double[] denoiseB,
                                    int[] outColor,
                                    int count) {
        double depthScale = 18.0 + 42.0 * strength;
        double normalPower = 10.0 + 36.0 * strength;
        int kernelSize = radius * 2 + 1;

        for (int y = yStart; y < yEnd; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= count) {
                    continue;
                }

                double centerR = accumR[idx] * invSamples;
                double centerG = accumG[idx] * invSamples;
                double centerB = accumB[idx] * invSamples;
                double centerL = luminance(centerR, centerG, centerB);
                float centerDepth = guideDepth[idx];
                int normalBase = idx * 3;
                float centerNx = guideNormal[normalBase];
                float centerNy = guideNormal[normalBase + 1];
                float centerNz = guideNormal[normalBase + 2];
                boolean centerHit = Float.isFinite(centerDepth);
                boolean centerNormalValid = centerNx != 0.0f || centerNy != 0.0f || centerNz != 0.0f;
                double colorSigma = 0.035 + (1.0 - strength) * 0.10 + centerL * 0.08;
                double invColorSigma2 = 1.0 / Math.max(1e-6, 2.0 * colorSigma * colorSigma);

                double sumW = 1.0;
                double sumR = centerR;
                double sumG = centerG;
                double sumB = centerB;

                for (int oy = -radius; oy <= radius; oy++) {
                    int sy = y + oy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    for (int ox = -radius; ox <= radius; ox++) {
                        int sx = x + ox;
                        if ((ox == 0 && oy == 0) || sx < 0 || sx >= width) {
                            continue;
                        }
                        int sidx = sy * width + sx;
                        if (sidx < 0 || sidx >= count) {
                            continue;
                        }

                        double sr = accumR[sidx] * invSamples;
                        double sg = accumG[sidx] * invSamples;
                        double sb = accumB[sidx] * invSamples;
                        double colorDiffR = sr - centerR;
                        double colorDiffG = sg - centerG;
                        double colorDiffB = sb - centerB;
                        double colorWeight = Math.exp(-(colorDiffR * colorDiffR
                                + colorDiffG * colorDiffG
                                + colorDiffB * colorDiffB) * invColorSigma2);

                        float sampleDepth = guideDepth[sidx];
                        boolean sampleHit = Float.isFinite(sampleDepth);
                        double depthWeight;
                        if (centerHit && sampleHit) {
                            double relativeDepth = Math.abs(sampleDepth - centerDepth)
                                    / Math.max(1e-4, Math.max(Math.abs(centerDepth), Math.abs(sampleDepth)));
                            depthWeight = 1.0 / (1.0 + relativeDepth * depthScale);
                        } else if (centerHit == sampleHit) {
                            depthWeight = 1.0;
                        } else {
                            depthWeight = 0.03;
                        }

                        int sampleNormalBase = sidx * 3;
                        float sampleNx = guideNormal[sampleNormalBase];
                        float sampleNy = guideNormal[sampleNormalBase + 1];
                        float sampleNz = guideNormal[sampleNormalBase + 2];
                        boolean sampleNormalValid = sampleNx != 0.0f || sampleNy != 0.0f || sampleNz != 0.0f;
                        double normalWeight;
                        if (centerNormalValid && sampleNormalValid) {
                            double dot = centerNx * sampleNx + centerNy * sampleNy + centerNz * sampleNz;
                            normalWeight = Math.pow(Math.max(0.0, Math.min(1.0, dot)), normalPower);
                        } else if (centerNormalValid == sampleNormalValid) {
                            normalWeight = 1.0;
                        } else {
                            normalWeight = 0.03;
                        }

                        double spatialWeight = spatialKernel[(oy + radius) * kernelSize + (ox + radius)];
                        double weight = spatialWeight * colorWeight * depthWeight * normalWeight;
                        if (weight <= 1e-8) {
                            continue;
                        }
                        sumW += weight;
                        sumR += sr * weight;
                        sumG += sg * weight;
                        sumB += sb * weight;
                    }
                }

                double filteredR = sumR / sumW;
                double filteredG = sumG / sumW;
                double filteredB = sumB / sumW;
                double finalR = lerp(centerR, filteredR, strength);
                double finalG = lerp(centerG, filteredG, strength);
                double finalB = lerp(centerB, filteredB, strength);
                denoiseR[idx] = finalR;
                denoiseG[idx] = finalG;
                denoiseB[idx] = finalB;
                outColor[idx] = packColor(toneMap(finalR, exposure), toneMap(finalG, exposure), toneMap(finalB, exposure));
            }
        }
    }

    private static double[] buildSpatialKernel(int radius, double strength) {
        int size = radius * 2 + 1;
        double sigma = Math.max(1.0, radius * (0.80 + strength * 0.55));
        double invSigma2 = 1.0 / (2.0 * sigma * sigma);
        double[] kernel = new double[size * size];
        for (int oy = -radius; oy <= radius; oy++) {
            for (int ox = -radius; ox <= radius; ox++) {
                double distSq = ox * ox + oy * oy;
                kernel[(oy + radius) * size + (ox + radius)] = Math.exp(-distSq * invSigma2);
            }
        }
        return kernel;
    }

    private static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double toneMap(double c, double exposure) {
        double mapped = 1.0 - Math.exp(-Math.max(0.0, c) * exposure);
        mapped = clamp01(mapped);
        return Math.pow(mapped, INV_GAMMA);
    }

    private static int packColor(double r, double g, double b) {
        int ir = (int) (clamp01(r) * 255.0 + 0.5);
        int ig = (int) (clamp01(g) * 255.0 + 0.5);
        int ib = (int) (clamp01(b) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }
}
