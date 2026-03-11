package engine.render.ray;

import engine.util.ThreadPool;

import java.util.Arrays;

public final class JointBilateralDenoiserTests {

    private JointBilateralDenoiserTests() {
    }

    public static void main(String[] args) {
        testGuideBuffersPreserveEdges();
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

        JointBilateralDenoiser.apply(width, height, 1, null, 1, 1.0, 1.0, 1.0,
                accumR, accumG, accumB, sameDepth, sameNormal, sameR, sameG, sameB, sameDepthColor);
        JointBilateralDenoiser.apply(width, height, 1, null, 1, 1.0, 1.0, 1.0,
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
        JointBilateralDenoiser.apply(width, height, 1, null, 2, 0.65, 1.1, 1.0,
                accumR, accumG, accumB, depth, normal, seqR, seqG, seqB, seqColor);

        double[] parR = new double[count];
        double[] parG = new double[count];
        double[] parB = new double[count];
        int[] parColor = new int[count];
        ThreadPool pool = new ThreadPool(3);
        try {
            JointBilateralDenoiser.apply(width, height, 3, pool, 2, 0.65, 1.1, 1.0,
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

    private static void setNormal(float[] normal, int index, float nx, float ny, float nz) {
        int base = index * 3;
        normal[base] = nx;
        normal[base + 1] = ny;
        normal[base + 2] = nz;
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
