import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class SmoothShadingRegressionTests {

    private SmoothShadingRegressionTests() {
    }

    public static void main(String[] args) {
        Scene scene = createScene();
        PerspectiveCamera camera = createCamera();

        assertRasterSmooth(scene, camera);
        assertRaySmooth(scene, camera);
        assertPathSmooth(scene, camera);

        System.out.println("SmoothShadingRegressionTests: ALL TESTS PASSED");
    }

    private static Scene createScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.addLight(new DirectionalLight(new Vec3(0.0, -1.0, 0.0), Vec3.ONE, 1.8));

        Mesh mesh = new Mesh(
                "smooth-tri",
                new float[]{
                        -1.5f, -1.2f, 0.0f,
                        1.5f, -1.2f, 0.0f,
                        0.0f, 1.6f, 0.0f
                },
                new float[]{
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 2, 1}
        );

        PhongMaterial material = new PhongMaterial(new Vec3(0.95, 0.95, 0.95), 8.0);
        material.setSpecularColor(Vec3.ZERO);
        material.setSpecularFactor(0.0);
        material.setReflectivity(0.0);
        material.setClearcoatFactor(0.0);

        scene.addEntity(new Entity("smooth-tri", mesh, material));
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera createCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static void assertRasterSmooth(Scene scene, PerspectiveCamera camera) {
        RasterRenderer renderer = new RasterRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.init(96, 96);
        renderer.setParameter("unlitMode", false);
        renderer.render(scene, camera, fb, 0.0);
        assertLit(fb.getColor(48, 48), "Raster");
    }

    private static void assertRaySmooth(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.init(96, 96);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", false);
        renderer.setParameter("reflections", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertLit(fb.getColor(48, 48), "RayTracer");
    }

    private static void assertPathSmooth(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.init(96, 96);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertLit(fb.getColor(48, 48), "PathTracer");
    }

    private static void assertLit(int argb, String label) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r + g + b < 120) {
            throw new AssertionError(label + " fell back to flat/dark shading at center pixel: rgb=(" + r + "," + g + "," + b + ")");
        }
    }
}
