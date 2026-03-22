import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.post.TemporalNoiseRenderer;
import engine.scene.Entity;
import engine.scene.Scene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Represents pomocnou vizuální sondu pro ruční kontrolu temporal-noise výstupu.
 */
public final class TemporalNoiseProbe {

    private TemporalNoiseProbe() {
    }

    public static void main(String[] args) throws Exception {
        render("probe_sphere.png", buildSphereScene(), buildCamera(), 192, 192, 0.55);
        render("probe_cylinder.png", buildCylinderScene(), buildCylinderCamera(), 192, 192, 0.55);
        render("probe_cube.png", buildCubeScene(), buildCubeCamera(), 192, 192, 0.55);
    }

    private static void render(String fileName,
                               Scene scene,
                               PerspectiveCamera camera,
                               int width,
                               int height,
                               double time) throws Exception {
        TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
        renderer.init(width, height);
        FrameBuffer fb = new FrameBuffer(width, height);
        renderer.render(scene, camera, fb, time);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, width, height, fb.getColorBuffer(), 0, width);
        ImageIO.write(image, "png", new File("build/tests/" + fileName));
    }

    private static Scene buildSphereScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 16.0);
        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.9, 24, 18), material);
        scene.addEntity(sphere);
        scene.update(0.0);
        return scene;
    }

    private static Scene buildCylinderScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.82, 0.82, 0.82), 10.0);
        Entity cylinder = new Entity("cylinder", MeshGenerator.cylinder(0.75, 1.7, 28, 1), material);
        cylinder.getTransform().setEulerAngles(Math.toRadians(12.0), Math.toRadians(26.0), 0.0);
        scene.addEntity(cylinder);
        scene.update(0.0);
        return scene;
    }

    private static Scene buildCubeScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.86, 0.86, 0.86), 12.0);
        Entity cube = new Entity("cube", MeshGenerator.cube(1.4), material);
        cube.getTransform().setEulerAngles(Math.toRadians(22.0), Math.toRadians(34.0), Math.toRadians(-8.0));
        scene.addEntity(cube);
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static PerspectiveCamera buildCylinderCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.1, 3.4));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static PerspectiveCamera buildCubeCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.05, 3.3));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }
}
