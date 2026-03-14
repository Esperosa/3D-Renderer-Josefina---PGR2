package engine.render.ray;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class DenoiseScheduleTests {

    private DenoiseScheduleTests() {
    }

    public static void main(String[] args) throws Exception {
        testScheduleStartsImmediatelyAndWarmsDownByThirtySamples();
        testReferenceLuminanceIncludesCurrentBatchHistory();
        testRayTracerUsesScheduledWarmupParameters();
        testPathTracerDenoisesFromFirstSample();
        testGuidesAreCapturedWhenDenoiseTurnsOn();
        System.out.println("DenoiseScheduleTests: ALL TESTS PASSED");
    }

    private static void testScheduleStartsImmediatelyAndWarmsDownByThirtySamples() {
        DenoiseSchedule.State firstSample = DenoiseSchedule.resolve(1, 2, 0.58);
        DenoiseSchedule.State settled = DenoiseSchedule.resolve(30, 2, 0.58);

        if (!firstSample.active() || !settled.active()) {
            throw new AssertionError("Denoise should stay active from the first accumulated sample.");
        }
        if (firstSample.radius() <= settled.radius()) {
            throw new AssertionError("Warmup denoise should temporarily widen the filter footprint.");
        }
        if (firstSample.strength() <= settled.strength()) {
            throw new AssertionError("Warmup denoise should be stronger before the viewport stabilizes.");
        }
        if (settled.radius() != 2) {
            throw new AssertionError("Settled denoise should return to the configured radius.");
        }
        assertNear(0.58, settled.strength(), 1e-9,
                "Settled denoise strength should match the configured viewport value.");
        if (DenoiseSchedule.settledSamples() != 30L) {
            throw new AssertionError("Denoise warmup should settle by roughly 30 samples for viewport work.");
        }
    }

    private static void testReferenceLuminanceIncludesCurrentBatchHistory() {
        double accumulatedOnly = DenoiseSupport.referenceLuminance(1.2, 4L, 0.0, 0);
        assertNear(0.3, accumulatedOnly, 1e-9,
                "Reference luminance should match accumulated history when the current batch is empty.");

        double withBatch = DenoiseSupport.referenceLuminance(1.2, 4L, 0.8, 2);
        assertNear((1.2 + 0.8) / 6.0, withBatch, 1e-9,
                "Reference luminance should include already collected samples from the current frame.");

        double empty = DenoiseSupport.referenceLuminance(0.0, 0L, 0.0, 0);
        if (Double.isFinite(empty)) {
            throw new AssertionError("Reference luminance should stay undefined when there is no history at all.");
        }
    }

    private static void testRayTracerUsesScheduledWarmupParameters() throws Exception {
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(3, 1);
        configureRenderer(renderer, 1L, 2, 0.58);

        DenoiseSchedule.State state = DenoiseSchedule.resolve(1L, 2, 0.58);
        double[] expectedR = new double[3];
        double[] expectedG = new double[3];
        double[] expectedB = new double[3];
        double[] scratchR = new double[3];
        double[] scratchG = new double[3];
        double[] scratchB = new double[3];
        int[] expectedOut = new int[3];
        JointBilateralDenoiser.apply(
                3,
                1,
                1,
                null,
                state.radius(),
                state.strength(),
                1.0,
                1.0,
                getDoubleArray(renderer, "accumR"),
                getDoubleArray(renderer, "accumG"),
                getDoubleArray(renderer, "accumB"),
                getDoubleArray(renderer, "accumLuma"),
                getDoubleArray(renderer, "accumLumaSq"),
                1L,
                getFloatArray(renderer, "guideDepth"),
                getFloatArray(renderer, "guideNormal"),
                getFloatArray(renderer, "guideAlbedo"),
                expectedR,
                expectedG,
                expectedB,
                scratchR,
                scratchG,
                scratchB,
                expectedOut
        );

        invokeApply(renderer, 1.0);
        assertNear(expectedR[1], getDoubleArray(renderer, "denoiseR")[1], 1e-9,
                "Ray tracer should use the scheduled warmup denoise parameters.");
    }

    private static void testPathTracerDenoisesFromFirstSample() throws Exception {
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(3, 1);
        configureRenderer(renderer, 1L, 2, 0.58);

        invokeApply(renderer, 1.0);
        if (getDoubleArray(renderer, "denoiseR")[1] == 0.0) {
            throw new AssertionError("Path tracer should update denoise buffers from the first accumulated sample.");
        }
    }

    private static void testGuidesAreCapturedWhenDenoiseTurnsOn() throws Exception {
        Scene scene = buildGuideScene();
        PerspectiveCamera camera = buildGuideCamera();
        FrameBuffer fb = new FrameBuffer(48, 48);

        RayTracerRenderer ray = new RayTracerRenderer();
        ray.init(48, 48);
        ray.setParameter("workerCount", 1);
        ray.setParameter("samplesPerFrame", 1);
        ray.setParameter("denoise", false);
        ray.render(scene, camera, fb, 0.0);
        if (getBoolean(ray, "guidesReady")) {
            throw new AssertionError("Ray tracer should not mark guides ready while denoise is disabled.");
        }
        ray.setParameter("denoise", true);
        ray.render(scene, camera, fb, 0.0);
        assertGuideReady(ray, 24 + 24 * 48, "Ray tracer should capture guides when denoise is enabled later.");

        PathTracerRenderer path = new PathTracerRenderer();
        path.init(48, 48);
        path.setParameter("workerCount", 1);
        path.setParameter("samplesPerFrame", 1);
        path.setParameter("denoise", false);
        path.render(scene, camera, fb, 0.0);
        if (getBoolean(path, "guidesReady")) {
            throw new AssertionError("Path tracer should not mark guides ready while denoise is disabled.");
        }
        path.setParameter("denoise", true);
        path.render(scene, camera, fb, 0.0);
        assertGuideReady(path, 24 + 24 * 48, "Path tracer should capture guides when denoise is enabled later.");
    }

    private static void configureRenderer(
            Object renderer,
            long accumulatedSamples,
            int denoiseRadius,
            double denoiseStrength) throws Exception {
        setInt(renderer, "width", 3);
        setInt(renderer, "height", 1);
        setInt(renderer, "workerCount", 1);
        setInt(renderer, "denoiseRadius", denoiseRadius);
        setDouble(renderer, "denoiseStrength", denoiseStrength);
        setDouble(renderer, "exposure", 1.0);
        setLong(renderer, "accumulatedSamples", accumulatedSamples);

        set(renderer, "accumR", new double[]{0.45, 0.35, 0.45});
        set(renderer, "accumG", new double[]{0.45, 0.35, 0.45});
        set(renderer, "accumB", new double[]{0.45, 0.35, 0.45});
        set(renderer, "accumLuma", new double[]{0.45, 0.35, 0.45});
        set(renderer, "accumLumaSq", new double[]{0.2025, 0.1225, 0.2025});
        set(renderer, "denoiseR", new double[3]);
        set(renderer, "denoiseG", new double[3]);
        set(renderer, "denoiseB", new double[3]);
        set(renderer, "denoiseScratchR", new double[3]);
        set(renderer, "denoiseScratchG", new double[3]);
        set(renderer, "denoiseScratchB", new double[3]);
        set(renderer, "guideDepth", new float[]{1.0f, 1.0f, 1.0f});
        set(renderer, "guideNormal", new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        });
        set(renderer, "guideAlbedo", new float[]{
                0.6f, 0.6f, 0.6f,
                0.6f, 0.6f, 0.6f,
                0.6f, 0.6f, 0.6f
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

    private static boolean getBoolean(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
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

    private static void assertGuideReady(Object renderer, int pixelIndex, String message) throws Exception {
        if (!getBoolean(renderer, "guidesReady")) {
            throw new AssertionError(message);
        }
        float[] depth = getFloatArray(renderer, "guideDepth");
        if (pixelIndex < 0 || pixelIndex >= depth.length || !Float.isFinite(depth[pixelIndex])) {
            throw new AssertionError(message + " depth=" + (pixelIndex >= 0 && pixelIndex < depth.length ? depth[pixelIndex] : Float.NaN));
        }
    }

    private static Scene buildGuideScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.08, 0.08, 0.08));
        scene.setBackgroundColor(new Vec3(0.01, 0.02, 0.03));
        scene.addLight(new DirectionalLight(new Vec3(-0.4, -0.5, -1.0), Vec3.ONE, 1.4));

        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.8, 18, 12),
                new PhongMaterial(new Vec3(0.92, 0.28, 0.18), 48.0));
        sphere.getTransform().setPosition(new Vec3(0.0, 0.0, 0.2));
        scene.addEntity(sphere);
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildGuideCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }
}
