package engine.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;

public final class PreviewQualityRegressionTests {

    private PreviewQualityRegressionTests() {
    }

    public static void main(String[] args) throws Exception {
        verifyStillRayPreviewRunsFullDenoiseEveryFrame();
        verifyStillPathPreviewRunsFullDenoiseEveryFrame();
        System.out.println("PreviewQualityRegressionTests: ALL TESTS PASSED");
    }

    private static void verifyStillRayPreviewRunsFullDenoiseEveryFrame() throws Exception {
        RayTracerRenderer renderer = new RayTracerRenderer();
        setBoolean(renderer, "previewQualityLadderEnabled", true);
        setBoolean(renderer, "previewMotionActive", false);
        setBoolean(renderer, "temporalHistoryValid", true);
        setInt(renderer, "activePreviewPolishSamplesPerFrame", 0);

        Method method = RayTracerRenderer.class.getDeclaredMethod("resolveRunFullDenoise", long.class);
        method.setAccessible(true);
        boolean first = (Boolean) method.invoke(renderer, 1L);
        boolean second = (Boolean) method.invoke(renderer, 2L);
        if (!first || !second) {
            throw new AssertionError("Still RT preview must keep full denoise enabled on every sample frame.");
        }
    }

    private static void verifyStillPathPreviewRunsFullDenoiseEveryFrame() throws Exception {
        PathTracerRenderer renderer = new PathTracerRenderer();
        setBoolean(renderer, "previewQualityLadderEnabled", true);
        setBoolean(renderer, "previewMotionActive", false);
        setBoolean(renderer, "referenceMode", false);
        setBoolean(renderer, "temporalHistoryValid", true);
        setInt(renderer, "activeStillEnvironmentSamples", 0);

        Method method = PathTracerRenderer.class.getDeclaredMethod("resolveRunFullDenoise", long.class);
        method.setAccessible(true);
        boolean first = (Boolean) method.invoke(renderer, 1L);
        boolean second = (Boolean) method.invoke(renderer, 2L);
        if (!first || !second) {
            throw new AssertionError("Still PT preview must keep full denoise enabled on every sample frame.");
        }
    }

    private static void setBoolean(Object target, String fieldName, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static void setInt(Object target, String fieldName, int value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setInt(target, value);
    }
}
