package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.EnvironmentMap;
import engine.render.FrameBuffer;
import engine.scene.Entity;
import engine.scene.Scene;

public final class PathTracerEnvironmentSamplingTests {

    private PathTracerEnvironmentSamplingTests() {
    }

    public static void main(String[] args) {
        testReferencePathTracerSamplesEnvironmentAsDirectLight();
        System.out.println("PathTracerEnvironmentSamplingTests: ALL TESTS PASSED");
    }

    private static void testReferencePathTracerSamplesEnvironmentAsDirectLight() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(1.2);
        scene.setEnvironmentExposure(0.46);
        scene.setEnvironmentMapKey("farmland_overcast_1k");
        scene.setEnvironmentMap(EnvironmentMap.loadRadiance("assets/environments/farmland_overcast_1k.hdr"));

        Mesh planeMesh = new Mesh(
                "plane",
                new float[]{
                        -2.2f, 0.0f, -2.2f,
                        2.2f, 0.0f, -2.2f,
                        2.2f, 0.0f, 2.2f,
                        -2.2f, 0.0f, 2.2f
                },
                new float[]{
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new int[]{0, 1, 2, 0, 2, 3}
        );
        PhongMaterial material = new PhongMaterial(new Vec3(0.78, 0.80, 0.83), 24.0);
        material.setRoughness(0.9);
        scene.addEntity(new Entity("plane", planeMesh, material));
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(55.0, 1.0, 0.1, 20.0);
        camera.setPosition(new Vec3(0.0, 1.6, 3.6));
        camera.lookAt(new Vec3(0.0, 0.15, 0.0));

        int directPixel = renderCenterPixel(scene, camera, true);
        int baselinePixel = renderCenterPixel(scene, camera, false);
        int directSum = rgbSum(directPixel);
        int baselineSum = rgbSum(baselinePixel);

        if (directSum <= baselineSum + 30) {
            throw new AssertionError("Reference path tracer should sample the environment directly. direct="
                    + directSum + " baseline=" + baselineSum);
        }
        if (directSum <= 42) {
            throw new AssertionError("Environment direct lighting should produce a clearly lit first-bounce result. direct="
                    + directSum);
        }
    }

    private static int renderCenterPixel(Scene scene, PerspectiveCamera camera, boolean directLighting) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(48, 48);
        renderer.init(48, 48);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("directLighting", directLighting);
        renderer.setParameter("sky", true);
        renderer.setParameter("denoise", false);
        renderer.setParameter("referenceMode", true);
        renderer.render(scene, camera, fb, 0.0);
        return fb.getColor(24, 24);
    }

    private static int rgbSum(int argb) {
        return ((argb >> 16) & 0xFF) + ((argb >> 8) & 0xFF) + (argb & 0xFF);
    }
}
