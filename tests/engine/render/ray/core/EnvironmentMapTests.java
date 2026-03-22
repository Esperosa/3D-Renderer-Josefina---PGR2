package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
import engine.render.EnvironmentMap;
import engine.render.FrameBuffer;
import engine.scene.Scene;

import java.util.HashSet;
import java.util.Set;

public final class EnvironmentMapTests {

    private EnvironmentMapTests() {
    }

    public static void main(String[] args) {
        testRadianceLoaderProducesDirectionalVariation();
        testEnvironmentOnlyRenderUsesHdrBackgroundInRayAndPathModes();
        System.out.println("EnvironmentMapTests: ALL TESTS PASSED");
    }

    private static void testRadianceLoaderProducesDirectionalVariation() {
        EnvironmentMap map = EnvironmentMap.loadRadiance("assets/environments/farmland_overcast_1k.hdr");
        EnvironmentMap.Sample sample = new EnvironmentMap.Sample();
        map.sample(0.0, 1.0, 0.0, sample);
        double upLuma = luminance(sample.r, sample.g, sample.b);
        map.sample(1.0, 0.0, 0.0, sample);
        double sideLuma = luminance(sample.r, sample.g, sample.b);
        if (Math.abs(upLuma - sideLuma) <= 1e-4) {
            throw new AssertionError("HDR environment sampling should vary across directions.");
        }
        map.importanceSample(0.37, 0.71, sample);
        if (sample.pdf <= 0.0) {
            throw new AssertionError("Importance-sampled HDR directions should report a positive pdf.");
        }
    }

    private static void testEnvironmentOnlyRenderUsesHdrBackgroundInRayAndPathModes() {
        Scene scene = new Scene();
        scene.setBackgroundColor(Vec3.ZERO);
        scene.setEnvironmentStrength(1.0);
        scene.setEnvironmentExposure(0.46);
        scene.setEnvironmentMapKey("farmland_overcast_1k");
        scene.setEnvironmentMap(EnvironmentMap.loadRadiance("assets/environments/farmland_overcast_1k.hdr"));

        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 100.0);
        camera.setPosition(Vec3.ZERO);
        camera.lookAt(new Vec3(0.0, 0.0, 1.0));

        assertViewportShowsHdrVariation(renderRay(scene, camera), "Ray tracer");
        assertViewportShowsHdrVariation(renderPath(scene, camera), "Path tracer");
    }

    private static FrameBuffer renderRay(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(32, 32);
        renderer.init(32, 32);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        return fb;
    }

    private static FrameBuffer renderPath(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(32, 32);
        renderer.init(32, 32);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("denoise", false);
        renderer.setParameter("referenceMode", true);
        renderer.render(scene, camera, fb, 0.0);
        return fb;
    }

    private static void assertViewportShowsHdrVariation(FrameBuffer fb, String label) {
        Set<Integer> unique = new HashSet<>();
        unique.add(fb.getColor(0, 0));
        unique.add(fb.getColor(31, 0));
        unique.add(fb.getColor(0, 31));
        unique.add(fb.getColor(31, 31));
        unique.add(fb.getColor(16, 16));
        if (unique.size() < 3) {
            throw new AssertionError(label + " should show directional HDR variation instead of a flat fill.");
        }
    }

    private static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }
}
