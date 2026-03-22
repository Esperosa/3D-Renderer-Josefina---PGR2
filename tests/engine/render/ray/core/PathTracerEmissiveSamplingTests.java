package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.Entity;
import engine.scene.Scene;

import java.lang.reflect.Array;
import java.lang.reflect.Field;

public final class PathTracerEmissiveSamplingTests {

    private PathTracerEmissiveSamplingTests() {
    }

    public static void main(String[] args) {
        testReferencePathTracerSamplesEmissiveGeometryDirectly();
        System.out.println("PathTracerEmissiveSamplingTests: ALL TESTS PASSED");
    }

    private static void testReferencePathTracerSamplesEmissiveGeometryDirectly() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(0.0);

        Mesh receiverMesh = new Mesh(
                "receiver",
                new float[]{
                        -1.8f, -1.4f, 0.0f,
                        1.8f, -1.4f, 0.0f,
                        0.0f, 0.8f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2}
        );
        PhongMaterial receiverMaterial = new PhongMaterial(new Vec3(0.75, 0.75, 0.75), 32.0);
        receiverMaterial.setRoughness(0.85);
        scene.addEntity(new Entity("receiver", receiverMesh, receiverMaterial));

        Mesh emitterMesh = new Mesh(
                "emitter",
                new float[]{
                        -0.6f, 1.8f, 1.0f,
                        0.6f, 1.8f, 1.0f,
                        0.0f, 2.6f, 1.12f
                },
                new float[]{
                        0.0f, 0.0f, -1.0f,
                        0.0f, 0.0f, -1.0f,
                        0.0f, 0.0f, -1.0f
                },
                new int[]{0, 1, 2}
        );
        PhongMaterial emitterMaterial = new PhongMaterial(new Vec3(0.0, 0.0, 0.0), 8.0);
        emitterMaterial.setEmissionColor(new Vec3(1.0, 0.92, 0.80));
        emitterMaterial.setEmissionStrength(12.0);
        emitterMaterial.setDoubleSided(true);
        scene.addEntity(new Entity("emitter", emitterMesh, emitterMaterial));
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(55.0, 1.0, 0.1, 30.0);
        camera.setPosition(new Vec3(0.0, -0.1, 3.2));
        camera.lookAt(new Vec3(0.0, -0.3, 0.0));

        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 3);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("sky", false);
        renderer.setParameter("denoise", false);
        renderer.setParameter("referenceMode", true);
        renderer.render(scene, camera, fb, 0.0);

        int emissiveCount = getArrayLength(renderer, "emissiveLights");
        if (emissiveCount <= 0) {
            throw new AssertionError("Reference path tracer should build an emissive light cache for emissive meshes.");
        }

        int regionBrightness = 0;
        int frameBrightness = 0;
        int lowerFrameBrightness = 0;
        int maxPixel = 0;
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int color = fb.getColor(x, y);
                int pixel = ((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF);
                frameBrightness += pixel;
                if (y >= 24) {
                    lowerFrameBrightness += pixel;
                }
                if (pixel > maxPixel) {
                    maxPixel = pixel;
                }
            }
        }
        for (int y = 24; y <= 44; y++) {
            for (int x = 18; x <= 46; x++) {
                int color = fb.getColor(x, y);
                regionBrightness += ((color >> 16) & 0xFF) + ((color >> 8) & 0xFF) + (color & 0xFF);
            }
        }
        if (lowerFrameBrightness <= 160) {
            throw new AssertionError("Reference path tracer should resolve emissive geometry as a direct light source. brightness="
                    + regionBrightness + " lower=" + lowerFrameBrightness + " frame=" + frameBrightness + " max=" + maxPixel);
        }
    }

    private static int getArrayLength(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            Object value = field.get(target);
            return value == null ? 0 : Array.getLength(value);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to inspect emissive light cache.", ex);
        }
    }
}
