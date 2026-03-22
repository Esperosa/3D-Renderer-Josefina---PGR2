package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.util.ThreadPool;

import java.util.Arrays;

public final class JointBilateralDenoiserTests {

    private JointBilateralDenoiserTests() {
    }

    public static void main(String[] args) {
        testGuideBuffersPreserveEdges();
        testAlbedoGuidePreservesMaterialBoundaries();
        testVarianceGuidedPixelsSmoothMoreWhenTheyAreNoisy();
        testRoughnessGuideLetsDiffusePixelsSmoothMoreThanGlossyOnes();
        testStableFramesUseFewerPasses();
        testParallelMatchesSequential();
        System.out.println("JointBilateralDenoiserTests: ALL TESTS PASSED");
    }

    private static void testGuideBuffersPreserveEdges() {
        int width = 5;
        int height = 1;
        double[] accumR = new double[]{0.45, 0.45, 0.35, 0.45, 0.45};
        double[] accumG = new double[5];
        double[] accumB = new double[5];

        int[] sameDepthColor = new int[5];
        int[] separatedColor = new int[5];
        double[] sameR = new double[5];
        double[] sameG = new double[5];
        double[] sameB = new double[5];
        double[] separatedR = new double[5];
        double[] separatedG = new double[5];
        double[] separatedB = new double[5];

        float[] sameDepth = new float[]{1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        float[] separatedDepth = new float[]{1.0f, 1.0f, 5.0f, 1.0f, 1.0f};
        float[] sameNormal = new float[15];
        float[] separatedNormal = new float[15];
        for (int i = 0; i < 5; i++) {
            setNormal(sameNormal, i, 0.0f, 0.0f, 1.0f);
            if (i == 2) {
                setNormal(separatedNormal, i, 0.0f, 0.0f, 1.0f);
            } else {
                setNormal(separatedNormal, i, 0.0f, 1.0f, 0.0f);
            }
        }

        JointBilateralDenoiser.apply(width, height, 1, null, 1, 1.0, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0,
                accumR, accumG, accumB, sameDepth, sameNormal, sameR, sameG, sameB, sameDepthColor);
        JointBilateralDenoiser.apply(width, height, 1, null, 1, 1.0, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0,
                accumR, accumG, accumB, separatedDepth, separatedNormal, separatedR, separatedG, separatedB, separatedColor);

        double sameCenter = sameR[2];
        double separatedCenter = separatedR[2];
        if (sameCenter <= separatedCenter + 0.005) {
            throw new AssertionError("Guide depth/normal should preserve edge contrast. same="
                    + sameCenter + " separated=" + separatedCenter);
        }
    }

    private static void testParallelMatchesSequential() {
        int width = 4;
        int height = 3;
        int count = width * height;
        double[] accumR = new double[count];
        double[] accumG = new double[count];
        double[] accumB = new double[count];
        float[] depth = new float[count];
        float[] normal = new float[count * 3];
        for (int i = 0; i < count; i++) {
            accumR[i] = (i % width) * 0.35 + 0.1;
            accumG[i] = (i / width) * 0.25 + 0.05;
            accumB[i] = ((i % 3) * 0.18) + 0.02;
            depth[i] = 1.0f + (i / width) * 0.5f;
            setNormal(normal, i, 0.0f, 0.0f, 1.0f);
        }
        setNormal(normal, 5, 0.0f, 1.0f, 0.0f);
        depth[5] = 3.0f;

        double[] seqR = new double[count];
        double[] seqG = new double[count];
        double[] seqB = new double[count];
        int[] seqColor = new int[count];
        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.65, 1.1, ToneMapSupport.MODE_EXPOSURE, 1.0,
                accumR, accumG, accumB, depth, normal, seqR, seqG, seqB, seqColor);

        double[] parR = new double[count];
        double[] parG = new double[count];
        double[] parB = new double[count];
        int[] parColor = new int[count];
        ThreadPool pool = new ThreadPool(3);
        try {
                JointBilateralDenoiser.apply(width, height, 3, pool, 2, 0.65, 1.1, ToneMapSupport.MODE_EXPOSURE, 1.0,
                    accumR, accumG, accumB, depth, normal, parR, parG, parB, parColor);
        } finally {
            pool.shutdown();
        }

        if (!Arrays.equals(seqColor, parColor)) {
            throw new AssertionError("Parallel denoise output colors must match sequential path.");
        }
        assertDoubleArrayEquals("denoiseR", seqR, parR);
        assertDoubleArrayEquals("denoiseG", seqG, parG);
        assertDoubleArrayEquals("denoiseB", seqB, parB);
    }

    private static void testAlbedoGuidePreservesMaterialBoundaries() {
        int width = 5;
        int height = 1;
        double[] accumR = new double[]{0.45, 0.45, 0.35, 0.45, 0.45};
        double[] accumG = new double[5];
        double[] accumB = new double[5];
        float[] depth = new float[]{1.0f, 1.0f, 1.0f, 1.0f, 1.0f};
        float[] normal = new float[15];
        for (int i = 0; i < 5; i++) {
            setNormal(normal, i, 0.0f, 0.0f, 1.0f);
        }

        float[] flatAlbedo = new float[15];
        float[] separatedAlbedo = new float[15];
        for (int i = 0; i < 5; i++) {
            setColor(flatAlbedo, i, 0.6f, 0.6f, 0.6f);
            if (i == 2) {
                setColor(separatedAlbedo, i, 0.1f, 0.8f, 0.1f);
            } else {
                setColor(separatedAlbedo, i, 0.6f, 0.6f, 0.6f);
            }
        }

        double[] flatR = new double[5];
        double[] flatG = new double[5];
        double[] flatB = new double[5];
        int[] flatOut = new int[5];
        double[] separatedR = new double[5];
        double[] separatedG = new double[5];
        double[] separatedB = new double[5];
        int[] separatedOut = new int[5];

        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.7, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0,
                accumR, accumG, accumB, depth, normal, flatAlbedo, flatR, flatG, flatB, flatOut);
        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.7, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0,
                accumR, accumG, accumB, depth, normal, separatedAlbedo, separatedR, separatedG, separatedB, separatedOut);

        if (separatedR[2] >= flatR[2] - 0.004) {
            throw new AssertionError("Albedo guide should keep material boundaries sharper. flat="
                    + flatR[2] + " separated=" + separatedR[2]);
        }
    }

    private static void testVarianceGuidedPixelsSmoothMoreWhenTheyAreNoisy() {
        int width = 3;
        int height = 1;
        long sampleCount = 32L;
        double[] accumR = new double[]{12.8, 9.6, 12.8};
        double[] accumG = new double[3];
        double[] accumB = new double[3];
        float[] depth = new float[]{1.0f, 1.0f, 1.0f};
        float[] normal = new float[9];
        for (int i = 0; i < 3; i++) {
            setNormal(normal, i, 0.0f, 0.0f, 1.0f);
        }

        double[] lowNoiseLuma = new double[]{12.8, 9.6, 12.8};
        double[] lowNoiseLumaSq = new double[]{5.12, 2.88, 5.12};
        double[] highNoiseLuma = new double[]{12.8, 9.6, 12.8};
        double[] highNoiseLumaSq = new double[]{5.12, 5.76, 5.12};

        double[] lowR = new double[3];
        double[] lowG = new double[3];
        double[] lowB = new double[3];
        double[] lowScratchR = new double[3];
        double[] lowScratchG = new double[3];
        double[] lowScratchB = new double[3];
        int[] lowOut = new int[3];

        double[] highR = new double[3];
        double[] highG = new double[3];
        double[] highB = new double[3];
        double[] highScratchR = new double[3];
        double[] highScratchG = new double[3];
        double[] highScratchB = new double[3];
        int[] highOut = new int[3];

        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.62, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0 / sampleCount,
                accumR, accumG, accumB, lowNoiseLuma, lowNoiseLumaSq, sampleCount,
                depth, normal, null, lowR, lowG, lowB, lowScratchR, lowScratchG, lowScratchB, lowOut);
        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.62, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0 / sampleCount,
                accumR, accumG, accumB, highNoiseLuma, highNoiseLumaSq, sampleCount,
                depth, normal, null, highR, highG, highB, highScratchR, highScratchG, highScratchB, highOut);

        if (highR[1] <= lowR[1] + 0.01) {
            throw new AssertionError("Higher-variance pixels should be smoothed more aggressively. low="
                    + lowR[1] + " high=" + highR[1]);
        }
    }

    private static void testRoughnessGuideLetsDiffusePixelsSmoothMoreThanGlossyOnes() {
        int width = 3;
        int height = 1;
        long sampleCount = 48L;
        double[] accumR = new double[]{16.8, 11.4, 16.8};
        double[] accumG = new double[3];
        double[] accumB = new double[3];
        double[] accumLuma = new double[]{16.8, 11.4, 16.8};
        double[] accumLumaSq = new double[]{7.0, 6.0, 7.0};
        float[] depth = new float[]{1.0f, 1.0f, 1.0f};
        float[] normal = new float[9];
        float[] albedo = new float[9];
        for (int i = 0; i < 3; i++) {
            setNormal(normal, i, 0.0f, 0.0f, 1.0f);
            setColor(albedo, i, 0.6f, 0.6f, 0.6f);
        }

        float[] roughGuide = new float[]{0.95f, 0.95f, 0.95f};
        float[] glossyGuide = new float[]{0.08f, 0.08f, 0.08f};

        double[] roughR = new double[3];
        double[] roughG = new double[3];
        double[] roughB = new double[3];
        double[] roughScratchR = new double[3];
        double[] roughScratchG = new double[3];
        double[] roughScratchB = new double[3];
        int[] roughOut = new int[3];

        double[] glossyR = new double[3];
        double[] glossyG = new double[3];
        double[] glossyB = new double[3];
        double[] glossyScratchR = new double[3];
        double[] glossyScratchG = new double[3];
        double[] glossyScratchB = new double[3];
        int[] glossyOut = new int[3];

        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.68, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0 / sampleCount,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount,
                depth, normal, albedo, roughGuide,
                roughR, roughG, roughB, roughScratchR, roughScratchG, roughScratchB, roughOut);
        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.68, 1.0, ToneMapSupport.MODE_EXPOSURE, 1.0 / sampleCount,
                accumR, accumG, accumB, accumLuma, accumLumaSq, sampleCount,
                depth, normal, albedo, glossyGuide,
                glossyR, glossyG, glossyB, glossyScratchR, glossyScratchG, glossyScratchB, glossyOut);

        if (roughR[1] <= glossyR[1] + 0.01) {
            throw new AssertionError("Rough diffuse pixels should accept stronger denoise than glossy ones. rough="
                    + roughR[1] + " glossy=" + glossyR[1]);
        }
    }

    private static void testStableFramesUseFewerPasses() {
        if (JointBilateralDenoiser.resolvePassCount(2, 1.0) != 4) {
            throw new AssertionError("Radius 2 should unlock the widest cleanup budget while the frame is very noisy.");
        }
        if (JointBilateralDenoiser.resolvePassCount(2, 0.20) != 3) {
            throw new AssertionError("Moderately noisy frames should keep a medium denoise budget.");
        }
        if (JointBilateralDenoiser.resolvePassCount(2, 0.04) != 2) {
            throw new AssertionError("Very stable frames should collapse to the minimum useful cleanup footprint.");
        }
        if (JointBilateralDenoiser.resolvePassCount(1, 0.02) != 2) {
            throw new AssertionError("Low-radius denoise should keep its minimum two-pass footprint.");
        }
    }

    private static void setNormal(float[] normal, int index, float nx, float ny, float nz) {
        int base = index * 3;
        normal[base] = nx;
        normal[base + 1] = ny;
        normal[base + 2] = nz;
    }

    private static void setColor(float[] color, int index, float r, float g, float b) {
        int base = index * 3;
        color[base] = r;
        color[base + 1] = g;
        color[base + 2] = b;
    }

    private static void assertDoubleArrayEquals(String label, double[] expected, double[] actual) {
        if (expected.length != actual.length) {
            throw new AssertionError(label + " length mismatch");
        }
        for (int i = 0; i < expected.length; i++) {
            if (Double.doubleToLongBits(expected[i]) != Double.doubleToLongBits(actual[i])) {
                throw new AssertionError(label + " mismatch at index " + i
                        + ": expected=" + expected[i] + " actual=" + actual[i]);
            }
        }
    }
}
