package engine.render.ray;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class DenoiseScheduleTests {

    private DenoiseScheduleTests() {
    }

    public static void main(String[] args) throws Exception {
        testRayTracerUsesFullStrengthFromSampleTwo();
        testPathTracerUsesFullStrengthFromSampleTwo();
        System.out.println("DenoiseScheduleTests: ALL TESTS PASSED");
    }

    private static void testRayTracerUsesFullStrengthFromSampleTwo() throws Exception {
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(3, 1);
        configureRenderer(renderer);

        double[] expectedR = new double[3];
        double[] expectedG = new double[3];
        double[] expectedB = new double[3];
        int[] expectedOut = new int[3];
        JointBilateralDenoiser.apply(
                3,
                1,
                1,
                null,
                1,
                1.0,
                1.0,
                0.5,
                getDoubleArray(renderer, "accumR"),
                getDoubleArray(renderer, "accumG"),
                getDoubleArray(renderer, "accumB"),
                getFloatArray(renderer, "guideDepth"),
                getFloatArray(renderer, "guideNormal"),
                expectedR,
                expectedG,
                expectedB,
                expectedOut
        );

        invokeApply(renderer, 0.5);
        assertNear(expectedR[1], getDoubleArray(renderer, "denoiseR")[1], 1e-9, "Ray tracer denoise should run at full strength from sample 2");
    }

    private static void testPathTracerUsesFullStrengthFromSampleTwo() throws Exception {
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(3, 1);
        configureRenderer(renderer);

        double[] expectedR = new double[3];
        double[] expectedG = new double[3];
        double[] expectedB = new double[3];
        int[] expectedOut = new int[3];
        JointBilateralDenoiser.apply(
                3,
                1,
                1,
                null,
                1,
                1.0,
                1.0,
                0.5,
                getDoubleArray(renderer, "accumR"),
                getDoubleArray(renderer, "accumG"),
                getDoubleArray(renderer, "accumB"),
                getFloatArray(renderer, "guideDepth"),
                getFloatArray(renderer, "guideNormal"),
                expectedR,
                expectedG,
                expectedB,
                expectedOut
        );

        invokeApply(renderer, 0.5);
        assertNear(expectedR[1], getDoubleArray(renderer, "denoiseR")[1], 1e-9, "Path tracer denoise should run at full strength from sample 2");
    }

    private static void configureRenderer(Object renderer) throws Exception {
        setInt(renderer, "width", 3);
        setInt(renderer, "height", 1);
        setInt(renderer, "workerCount", 1);
        setInt(renderer, "denoiseRadius", 1);
        setInt(renderer, "denoiseStartSamples", 64);
        setDouble(renderer, "denoiseStrength", 1.0);
        setDouble(renderer, "exposure", 1.0);
        setLong(renderer, "accumulatedSamples", 2L);

        set(renderer, "accumR", new double[]{2.0, 0.0, 2.0});
        set(renderer, "accumG", new double[]{2.0, 0.0, 2.0});
        set(renderer, "accumB", new double[]{2.0, 0.0, 2.0});
        set(renderer, "denoiseR", new double[3]);
        set(renderer, "denoiseG", new double[3]);
        set(renderer, "denoiseB", new double[3]);
        set(renderer, "guideDepth", new float[]{1.0f, 1.0f, 1.0f});
        set(renderer, "guideNormal", new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        });
        set(renderer, "threadPool", null);
    }

    private static void invokeApply(Object renderer, double invSamples) throws Exception {
        Method method = renderer.getClass().getDeclaredMethod("applyDenoiseAndResolve", int[].class, double.class);
        method.setAccessible(true);
        method.invoke(renderer, new int[3], invSamples);
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }

    private static void setLong(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static void setDouble(Object target, String fieldName, double value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setDouble(target, value);
    }

    private static double[] getDoubleArray(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (double[]) field.get(target);
    }

    private static float[] getFloatArray(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (float[]) field.get(target);
    }

    private static void assertNear(double expected, double actual, double eps, String message) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
