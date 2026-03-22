package engine.core;

import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.ProgressiveRenderDefaults;
import engine.scene.Entity;
import engine.scene.Scene;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class PathTracingReferenceModeTests {

    private PathTracingReferenceModeTests() {
    }

    public static void main(String[] args) throws Exception {
        testReferenceModeRemovesAmbientPreviewBias();
        testViewportPathTracingUsesReferenceTransportWithPreviewClamp();
        testOutputPathTracingUsesReferenceModeAndPreservesFinalDenoise();
        System.out.println("PathTracingReferenceModeTests: ALL TESTS PASSED");
    }

    private static void testReferenceModeRemovesAmbientPreviewBias() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.8, 0.15, 0.15));
        scene.setBackgroundColor(Vec3.ZERO);

        Mesh mesh = new Mesh(
                "cover",
                new float[]{
                        -2.0f, -2.0f, 0.0f,
                        2.0f, -2.0f, 0.0f,
                        0.0f, 2.2f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2}
        );
        PhongMaterial material = new PhongMaterial(new Vec3(0.8, 0.8, 0.8), 32.0);
        scene.addEntity(new Entity("plane", mesh, material));
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 20.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);

        int previewPixel = renderCenterPixel(scene, camera, false);
        int referencePixel = renderCenterPixel(scene, camera, true);
        int previewSum = rgbSum(previewPixel);
        int referenceSum = rgbSum(referencePixel);

        if (previewSum <= 18) {
            throw new AssertionError("Preview path tracer should still show the ambient viewport fill.");
        }
        if (referenceSum >= 12) {
            throw new AssertionError("Reference path tracer should not inject ambient light into the export integrator.");
        }
    }

    private static void testOutputPathTracingUsesReferenceModeAndPreservesFinalDenoise() throws Exception {
        OutputRenderController controller = new OutputRenderController();
        OutputRenderController.Settings settings = controller.settings();
        settings.mode = RenderMode.PATH_TRACING;
        settings.denoise = true;
        settings.pathClampIndirect = 6.5;
        settings.width = 96;
        settings.height = 96;

        OutputRenderPendingRequest request = new OutputRenderPendingRequest();
        request.type = OutputRenderRequestType.STILL;
        request.outputFormat = "png";

        Method buildJob = OutputRenderController.class.getDeclaredMethod(
                "buildOutputRenderJob",
                OutputRenderPendingRequest.class,
                boolean.class,
                boolean.class);
        buildJob.setAccessible(true);
        OutputRenderJob job = (OutputRenderJob) buildJob.invoke(controller, request, true, true);

        if (!job.referencePathMode) {
            throw new AssertionError("Output PATH_TRACING job should run in reference mode.");
        }
        if (!job.denoise) {
            throw new AssertionError("Reference output should still allow final denoise when requested.");
        }
        if (Math.abs(job.pathClampIndirect - 6.5) > 1e-9) {
            throw new AssertionError("Output PATH_TRACING job should preserve the configured indirect clamp.");
        }

        Method createRenderer = OutputRenderController.class.getDeclaredMethod(
                "createOutputRenderer",
                OutputRenderJob.class,
                FrameBuffer.class);
        createRenderer.setAccessible(true);
        Renderer renderer = (Renderer) createRenderer.invoke(controller, job, new FrameBuffer(64, 64));
        if (!(renderer instanceof PathTracerRenderer pathTracer)) {
            throw new AssertionError("PATH_TRACING output should create a PathTracerRenderer.");
        }
        if (!getBoolean(pathTracer, "referenceMode")) {
            throw new AssertionError("Output path tracer should propagate reference mode into the renderer.");
        }
        if (!getBoolean(pathTracer, "denoiseEnabled")) {
            throw new AssertionError("Reference output renderer should preserve final denoise.");
        }
        if (!getBoolean(pathTracer, "adaptiveSamplingEnabled")) {
            throw new AssertionError("Reference output renderer should keep adaptive sampling enabled.");
        }
        if (getInt(pathTracer, "adaptiveMinSamples") != ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_MIN_SAMPLES) {
            throw new AssertionError("Reference output renderer should use the output adaptive minimum sample budget.");
        }
        if (Math.abs(getDouble(pathTracer, "adaptiveThreshold") - ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_THRESHOLD) > 1e-9) {
            throw new AssertionError("Reference output renderer should use the output adaptive convergence threshold.");
        }
        if (Math.abs(getDouble(pathTracer, "clampIndirect") - 6.5) > 1e-9) {
            throw new AssertionError("Reference output renderer should propagate path clamp settings.");
        }
        if (getBoolean(pathTracer, "historyFireflyClampEnabled")) {
            throw new AssertionError("Reference output renderer should not use the viewport history firefly clamp.");
        }
    }

    private static void testViewportPathTracingUsesReferenceTransportWithPreviewClamp() throws Exception {
        PathTracerRenderer renderer = new PathTracerRenderer();

        RenderSettingsSync.applyPathSettings(
                renderer,
                2,
                24,
                6,
                5,
                4,
                2,
                3,
                true,
                true,
                true,
                2,
                0.66,
                0.0,
            4.5,
            "EXPOSURE"
        );

        if (getBoolean(renderer, "referenceMode")) {
            throw new AssertionError("Viewport path tracing should use the interactive transport branch.");
        }
        if (!getBoolean(renderer, "historyFireflyClampEnabled")) {
            throw new AssertionError("Viewport path tracing should keep history firefly clamp enabled for hot-pixel suppression.");
        }
    }

    private static int renderCenterPixel(Scene scene, PerspectiveCamera camera, boolean referenceMode) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(48, 48);
        renderer.init(48, 48);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 3);
        renderer.setParameter("directLighting", false);
        renderer.setParameter("sky", false);
        renderer.setParameter("denoise", false);
        renderer.setParameter("referenceMode", referenceMode);
        renderer.render(scene, camera, fb, 0.0);
        return fb.getColor(24, 24);
    }

    private static int rgbSum(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }

    private static boolean getBoolean(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static double getDouble(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getDouble(target);
    }

    private static int getInt(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(target);
    }
}
