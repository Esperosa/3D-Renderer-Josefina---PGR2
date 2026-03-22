package engine.render.ray;

import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.Entity;
import engine.scene.Scene;

import java.lang.reflect.Field;

public final class AdaptiveSamplingRendererTests {

    private AdaptiveSamplingRendererTests() {
    }

    public static void main(String[] args) throws Exception {
        testRayTracerStopsSamplingStablePixels();
        testPathTracerStopsSamplingStablePixels();
        System.out.println("AdaptiveSamplingRendererTests: ALL TESTS PASSED");
    }

    private static void testRayTracerStopsSamplingStablePixels() throws Exception {
        Scene scene = buildUniformScene();
        PerspectiveCamera camera = buildCamera();

        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(32, 32);
        renderer.init(32, 32);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("directLighting", false);
        renderer.setParameter("shadows", false);
        renderer.setParameter("reflections", false);
        renderer.setParameter("sky", false);
        renderer.setParameter("denoise", false);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", 2);
        renderer.setParameter("adaptiveThreshold", 0.0);

        for (int i = 0; i < 5; i++) {
            renderer.render(scene, camera, fb, 0.0);
        }

        assertCenterSampleCount(renderer, 2, "Ray tracer should stop sampling a converged flat pixel.");
        if (renderer.getAccumulatedSamples() != 5L) {
            throw new AssertionError("Global pass history should still track progressive frames for UI/progress.");
        }
    }

    private static void testPathTracerStopsSamplingStablePixels() throws Exception {
        Scene scene = buildUniformScene();
        PerspectiveCamera camera = buildCamera();

        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(32, 32);
        renderer.init(32, 32);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 2);
        renderer.setParameter("directLighting", false);
        renderer.setParameter("sky", false);
        renderer.setParameter("denoise", false);
        renderer.setParameter("referenceMode", true);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", 2);
        renderer.setParameter("adaptiveThreshold", 0.0);

        for (int i = 0; i < 5; i++) {
            renderer.render(scene, camera, fb, 0.0);
        }

        assertCenterSampleCount(renderer, 2, "Path tracer should stop sampling a converged flat pixel.");
        if (renderer.getAccumulatedSamples() != 5L) {
            throw new AssertionError("Global path pass history should still reflect render progress.");
        }
    }

    private static Scene buildUniformScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(0.0);

        Mesh planeMesh = new Mesh(
                "plane",
                new float[]{
                        -4.0f, -3.0f, 0.0f,
                        4.0f, -3.0f, 0.0f,
                        4.0f, 3.0f, 0.0f,
                        -4.0f, 3.0f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2, 0, 2, 3}
        );
        PhongMaterial material = new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 24.0);
        material.setRoughness(1.0);
        scene.addEntity(new Entity("plane", planeMesh, material));
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(50.0, 1.0, 0.1, 20.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static void assertCenterSampleCount(Object renderer, int expected, String message) throws Exception {
        Field field = renderer.getClass().getDeclaredField("sampleCounts");
        field.setAccessible(true);
        int[] sampleCounts = (int[]) field.get(renderer);
        int actual = sampleCounts[16 + 16 * 32];
        if (actual != expected) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
