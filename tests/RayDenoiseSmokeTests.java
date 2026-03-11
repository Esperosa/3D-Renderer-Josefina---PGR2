import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class RayDenoiseSmokeTests {

    private RayDenoiseSmokeTests() {
    }

    public static void main(String[] args) {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.08, 0.08, 0.08));
        scene.setBackgroundColor(new Vec3(0.01, 0.02, 0.03));
        scene.addLight(new DirectionalLight(new Vec3(-0.4, -0.5, -1.0), Vec3.ONE, 1.4));

        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.8, 20, 14),
                new PhongMaterial(new Vec3(0.92, 0.28, 0.18), 48.0));
        sphere.getTransform().setPosition(new Vec3(0.0, 0.0, 0.2));
        scene.addEntity(sphere);
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);

        assertRay(scene, camera);
        assertPath(scene, camera);
        System.out.println("RayDenoiseSmokeTests: ALL TESTS PASSED");
    }

    private static void assertRay(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("workerCount", 2);
        renderer.setParameter("samplesPerFrame", 3);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseStartSamples", 1);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", 0.65);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", true);
        renderer.render(scene, camera, fb, 0.0);
        assertVisible(fb.getColor(32, 32), "ray");
    }

    private static void assertPath(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("workerCount", 2);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseStartSamples", 1);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", 0.55);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.render(scene, camera, fb, 0.0);
        assertVisible(fb.getColor(32, 32), "path");
    }

    private static void assertVisible(int argb, String label) {
        int r = (argb >>> 16) & 0xFF;
        int g = (argb >>> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r < 12 && g < 12 && b < 12) {
            throw new AssertionError(label + " denoised render produced only background/dark output");
        }
    }
}
