import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.raster.RasterRenderer;
import engine.scene.Entity;
import engine.scene.Scene;

public final class ModelPreviewRenderTests {

    private ModelPreviewRenderTests() {
    }

    public static void main(String[] args) {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.0, 0.0, 0.0));
        scene.setBackgroundColor(new Vec3(0.0, 0.0, 0.0));

        PhongMaterial material = new PhongMaterial(new Vec3(0.95, 0.15, 0.12), 16.0);
        Entity entity = new Entity("cube", MeshGenerator.cube(1.2), material);
        scene.addEntity(entity);
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.4));
        camera.lookAt(Vec3.ZERO);

        int basic = renderCenter(scene, camera, false);
        int model = renderCenter(scene, camera, true);

        if (basic == model) {
            throw new AssertionError("Model preview should not match basic unlit material color exactly.");
        }
        int basicR = (basic >> 16) & 0xFF;
        int modelR = (model >> 16) & 0xFF;
        if (Math.abs(basicR - modelR) < 10) {
            throw new AssertionError("Model preview should ignore the source material tint.");
        }
        System.out.println("ModelPreviewRenderTests: ALL TESTS PASSED");
    }

    private static int renderCenter(Scene scene, PerspectiveCamera camera, boolean modelPreview) {
        RasterRenderer renderer = new RasterRenderer();
        FrameBuffer fb = new FrameBuffer(80, 80);
        renderer.init(80, 80);
        renderer.setParameter("unlitMode", true);
        renderer.setParameter("modelPreviewMode", modelPreview);
        renderer.setParameter("backfaceCulling", false);
        renderer.render(scene, camera, fb, 0.0);
        return fb.getColor(40, 40);
    }
}
