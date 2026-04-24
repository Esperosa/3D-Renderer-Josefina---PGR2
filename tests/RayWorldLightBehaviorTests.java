import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class RayWorldLightBehaviorTests {

    private RayWorldLightBehaviorTests() {
    }

    public static void main(String[] args) {
        testRayTracerUsesAmbientWithoutRealLights();
        testPathTracerUsesAmbientWithoutRealLights();
        testRayTracerBlackBackgroundMissStaysBlack();
        testPathTracerBlackBackgroundMissStaysBlack();
        testRayTracerEnvironmentStrengthScalesBackground();
        testPathTracerEnvironmentStrengthScalesBackground();
        System.out.println("RayWorldLightBehaviorTests: ALL TESTS PASSED");
    }

    private static void testRayTracerUsesAmbientWithoutRealLights() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ONE);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(1.0);
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

        assertBright(fb.getColor(24, 24), 42, "Ray tracer should receive world ambient light without real lights");
    }

    private static void testPathTracerUsesAmbientWithoutRealLights() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.7, 0.7, 0.7));
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(1.0);
        scene.addEntity(new Entity("center-tri", createTriangleMesh(0.0f), new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 24.0)));
        scene.update(0.0);

        PerspectiveCamera camera = createCamera();
        FrameBuffer fb = new FrameBuffer(48, 48);
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(48, 48);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);

        assertBright(fb.getColor(24, 24), 28, "Path tracer should receive world ambient light without real lights");
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
        scene.setEnvironmentStrength(1.0);
        scene.addLight(new DirectionalLight(new Vec3(0.0, 0.0, 1.0), Vec3.ONE, 4.0));
        scene.addEntity(new Entity("offscreen-tri", createTriangleMesh(4.5f), new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 24.0)));
        scene.update(0.0);
        return scene;
    }

    private static void testRayTracerEnvironmentStrengthScalesBackground() {
        Scene dark = createEnvironmentOnlyScene(0.0);
        Scene bright = createEnvironmentOnlyScene(3.0);

        int darkColor = renderRayBackground(dark);
        int brightColor = renderRayBackground(bright);
        assertLumaLessThan(darkColor, 2, "Ray tracer should go black when world environment strength is zero");
        assertLumaGreaterThan(brightColor, 70, "Ray tracer should brighten visible environment when world strength rises");
    }

    private static void testPathTracerEnvironmentStrengthScalesBackground() {
        Scene dark = createEnvironmentOnlyScene(0.0);
        Scene bright = createEnvironmentOnlyScene(3.0);

        int darkColor = renderPathBackground(dark);
        int brightColor = renderPathBackground(bright);
        assertLumaLessThan(darkColor, 2, "Path tracer should go black when world environment strength is zero");
        assertLumaGreaterThan(brightColor, 70, "Path tracer should brighten visible environment when world strength rises");
    }

    private static Scene createEnvironmentOnlyScene(double strength) {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(new Vec3(0.14, 0.18, 0.24));
        scene.setEnvironmentStrength(strength);
        scene.update(0.0);
        return scene;
    }

    private static int renderRayBackground(Scene scene) {
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
        return fb.getColor(24, 24);
    }

    private static int renderPathBackground(Scene scene) {
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
        return fb.getColor(24, 24);
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

    private static void assertBright(int argb, int minimumLuma, String message) {
        assertLumaGreaterThan(argb, minimumLuma, message);
    }

    private static void assertLumaGreaterThan(int argb, int minimumLuma, String message) {
        int luma = luma(argb);
        if (luma < minimumLuma) {
            throw new AssertionError(message + " luma=" + luma);
        }
    }

    private static void assertLumaLessThan(int argb, int maximumLuma, String message) {
        int luma = luma(argb);
        if (luma > maximumLuma) {
            throw new AssertionError(message + " luma=" + luma);
        }
    }

    private static int luma(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return r + g + b;
    }
}
