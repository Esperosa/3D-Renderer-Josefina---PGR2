import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class RayWorldLightBehaviorTests {

    private RayWorldLightBehaviorTests() {
    }

    public static void main(String[] args) {
        testRayTracerIgnoresAmbientWithoutRealLights();
        testRayTracerBlackBackgroundMissStaysBlack();
        testPathTracerBlackBackgroundMissStaysBlack();
        System.out.println("RayWorldLightBehaviorTests: ALL TESTS PASSED");
    }

    private static void testRayTracerIgnoresAmbientWithoutRealLights() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ONE);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.addEntity(new Entity("center-tri", createTriangleMesh(0.0f), new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 24.0)));
        scene.update(0.0);

        PerspectiveCamera camera = createCamera();
        FrameBuffer fb = new FrameBuffer(48, 48);
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(48, 48);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 2);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("reflections", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);

        assertBlack(fb.getColor(24, 24), "Ray tracer still receives fake ambient light without real lights");
    }

    private static void testRayTracerBlackBackgroundMissStaysBlack() {
        Scene scene = createBlackBackgroundMissScene();
        PerspectiveCamera camera = createCamera();
        FrameBuffer fb = new FrameBuffer(48, 48);
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(48, 48);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 2);
        renderer.setParameter("sky", true);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("reflections", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);

        assertBlack(fb.getColor(24, 24), "Ray tracer miss shader lights black background");
    }

    private static void testPathTracerBlackBackgroundMissStaysBlack() {
        Scene scene = createBlackBackgroundMissScene();
        PerspectiveCamera camera = createCamera();
        FrameBuffer fb = new FrameBuffer(48, 48);
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(48, 48);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("sky", true);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);

        assertBlack(fb.getColor(24, 24), "Path tracer miss shader lights black background");
    }

    private static Scene createBlackBackgroundMissScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.addLight(new DirectionalLight(new Vec3(0.0, 0.0, 1.0), Vec3.ONE, 4.0));
        scene.addEntity(new Entity("offscreen-tri", createTriangleMesh(4.5f), new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 24.0)));
        scene.update(0.0);
        return scene;
    }

    private static Mesh createTriangleMesh(float xOffset) {
        return new Mesh(
                "tri",
                new float[]{
                        -1.1f + xOffset, -1.0f, 0.0f,
                        1.1f + xOffset, -1.0f, 0.0f,
                        0.0f + xOffset, 1.1f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2}
        );
    }

    private static PerspectiveCamera createCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static void assertBlack(int argb, String message) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r + g + b > 3) {
            throw new AssertionError(message + " rgb=(" + r + "," + g + "," + b + ")");
        }
    }
}
