import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.raster.RasterRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

import java.util.Arrays;

public final class RasterTiledParallelTests {

    private RasterTiledParallelTests() {
    }

    public static void main(String[] args) {
        testParallelTiledMatchesSequentialWithGBuffer();
        System.out.println("RasterTiledParallelTests: ALL TESTS PASSED");
    }

    private static void testParallelTiledMatchesSequentialWithGBuffer() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 4.0));
        camera.lookAt(Vec3.ZERO);

        FrameBuffer sequential = render(scene, camera, false, 1, 20);
        FrameBuffer parallel = render(scene, camera, true, 4, 20);

        assertIntArrayEquals("color", sequential.getColorBuffer(), parallel.getColorBuffer());
        assertFloatArrayEquals("depth", sequential.getDepthBuffer(), parallel.getDepthBuffer());
        assertIntArrayEquals("objectId", sequential.getObjectIdBuffer(), parallel.getObjectIdBuffer());
        assertIntArrayEquals("faceId", sequential.getFaceIdBuffer(), parallel.getFaceIdBuffer());
        assertFloatArrayEquals("normal", sequential.getNormalBuffer(), parallel.getNormalBuffer());
        assertFloatArrayEquals("worldPos", sequential.getWorldPosBuffer(), parallel.getWorldPosBuffer());
    }

    private static Scene buildScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.18, 0.18, 0.20));
        scene.setBackgroundColor(new Vec3(0.03, 0.04, 0.06));
        scene.addLight(new DirectionalLight(new Vec3(-0.35, -0.45, -1.0), Vec3.ONE, 1.2));

        Entity clippedBackdrop = new Entity("clipped-backdrop", buildClippedQuad(),
                new PhongMaterial(new Vec3(0.22, 0.54, 0.92), 14.0));
        scene.addEntity(clippedBackdrop);

        Entity cube = new Entity("cube", MeshGenerator.cube(1.25),
                new PhongMaterial(new Vec3(0.95, 0.28, 0.18), 36.0));
        cube.getTransform().setPosition(new Vec3(-0.25, -0.05, 0.55));
        cube.getTransform().setEulerAngles(Math.toRadians(18.0), Math.toRadians(32.0), 0.0);
        scene.addEntity(cube);

        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.75, 16, 12),
                new PhongMaterial(new Vec3(0.16, 0.90, 0.56), 28.0));
        sphere.getTransform().setPosition(new Vec3(0.75, 0.35, 0.15));
        scene.addEntity(sphere);

        scene.update(0.0);
        return scene;
    }

    private static Mesh buildClippedQuad() {
        float[] positions = new float[]{
                -3.2f, -2.2f, -0.2f,
                3.2f, -2.2f, -0.2f,
                3.2f, 2.6f, -0.2f,
                -3.2f, 2.6f, -0.2f
        };
        float[] normals = new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        };
        int[] indices = new int[]{0, 1, 2, 0, 2, 3};
        return new Mesh("clipped-quad", positions, normals, indices);
    }

    private static FrameBuffer render(Scene scene,
                                      PerspectiveCamera camera,
                                      boolean parallel,
                                      int workerCount,
                                      int tileSize) {
        RasterRenderer renderer = new RasterRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96, true);
        renderer.init(96, 96);
        renderer.setParameter("unlitMode", false);
        renderer.setParameter("backfaceCulling", false);
        renderer.setParameter("parallel", parallel);
        renderer.setParameter("workerCount", workerCount);
        renderer.setParameter("tileSize", tileSize);
        renderer.render(scene, camera, fb, 0.0);
        return fb;
    }

    private static void assertIntArrayEquals(String label, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            for (int i = 0; i < expected.length; i++) {
                if (expected[i] != actual[i]) {
                    throw new AssertionError(label + " mismatch at index " + i
                            + ": expected=" + expected[i] + ", actual=" + actual[i]);
                }
            }
            throw new AssertionError(label + " mismatch");
        }
    }

    private static void assertFloatArrayEquals(String label, float[] expected, float[] actual) {
        if (!Arrays.equals(expected, actual)) {
            for (int i = 0; i < expected.length; i++) {
                if (Float.floatToIntBits(expected[i]) != Float.floatToIntBits(actual[i])) {
                    throw new AssertionError(label + " mismatch at index " + i
                            + ": expected=" + expected[i] + ", actual=" + actual[i]);
                }
            }
            throw new AssertionError(label + " mismatch");
        }
    }
}
