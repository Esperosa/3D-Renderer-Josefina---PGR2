package engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class AdaptiveSchedulerEvidenceTests {

    private AdaptiveSchedulerEvidenceTests() {
    }

    public static void main(String[] args) {
        verifySchedulerImprovementEvidence();
        System.out.println("AdaptiveSchedulerEvidenceTests: ALL TESTS PASSED");
    }

    private static void verifySchedulerImprovementEvidence() {
        Scene scene = buildScene();
        PerspectiveCamera baseCamera = buildCamera(16.0 / 9.0);
        Load load = new Load(320, 180, 12, 3);

        Metrics rayBaseline = runCase(scene, baseCamera, load, true, false);
        Metrics rayEnhanced = runCase(scene, baseCamera, load, true, true);
        assertImprovement("ray", rayBaseline, rayEnhanced);

        Metrics pathBaseline = runCase(scene, baseCamera, load, false, false);
        Metrics pathEnhanced = runCase(scene, baseCamera, load, false, true);
        assertImprovement("path", pathBaseline, pathEnhanced);

        System.out.println(format("ray", rayBaseline, rayEnhanced));
        System.out.println(format("path", pathBaseline, pathEnhanced));
    }

    private static Metrics runCase(Scene scene,
                                   PerspectiveCamera baseCamera,
                                   Load load,
                                   boolean ray,
                                   boolean enhancedScheduler) {
        Renderer renderer = ray ? new RayTracerRenderer() : new PathTracerRenderer();
        renderer.init(load.width, load.height);

        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        renderer.setParameter("workerCount", workers);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", ray ? 0.54 : 0.60);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", true);
        renderer.setParameter("autoschedulingtargetms", ray ? 16.0 : 20.0);
        renderer.setParameter("autohwtelemetry", false);
        renderer.setParameter("tileSize", 128);
        renderer.setParameter("autotilesize", enhancedScheduler);
        renderer.setParameter("autotilecost", enhancedScheduler);

        FrameBuffer fb = new FrameBuffer(load.width, load.height);
        PerspectiveCamera camera = copyCamera(baseCamera, (double) load.width / (double) load.height);

        List<Double> measured = new ArrayList<>();
        for (int i = 0; i < load.frames; i++) {
            animateCamera(camera, i);
            long started = System.nanoTime();
            renderer.render(scene, camera, fb, i * 0.016);
            double frameMs = (System.nanoTime() - started) / 1_000_000.0;
            if (i >= load.warmupFrames) {
                measured.add(frameMs);
            }
        }

        renderer.setParameter("shutdown", true);
        return Metrics.from(measured);
    }

    private static void assertImprovement(String label, Metrics baseline, Metrics enhanced) {
        if (!Double.isFinite(baseline.meanMs) || !Double.isFinite(enhanced.meanMs)) {
            throw new AssertionError(label + " invalid metrics.");
        }
        if (enhanced.meanMs > baseline.meanMs * 1.08) {
            throw new AssertionError(label + " adaptive scheduler regressed mean frame time. baseline="
                    + baseline.meanMs + " enhanced=" + enhanced.meanMs);
        }
        boolean meanImproved = enhanced.meanMs < baseline.meanMs * 0.98;
        boolean p95Improved = enhanced.p95Ms < baseline.p95Ms * 0.96;
        if (!meanImproved && !p95Improved) {
            throw new AssertionError(label + " did not show measurable improvement. baseline(mean="
                    + baseline.meanMs + ", p95=" + baseline.p95Ms + ") enhanced(mean="
                    + enhanced.meanMs + ", p95=" + enhanced.p95Ms + ")");
        }
    }

    private static String format(String label, Metrics baseline, Metrics enhanced) {
        double meanDelta = ((baseline.meanMs - enhanced.meanMs) / Math.max(1e-6, baseline.meanMs)) * 100.0;
        double p95Delta = ((baseline.p95Ms - enhanced.p95Ms) / Math.max(1e-6, baseline.p95Ms)) * 100.0;
        return String.format(
                "AdaptiveEvidence[%s] baseline(mean=%.2fms p95=%.2fms) enhanced(mean=%.2fms p95=%.2fms) delta(mean=%.1f%% p95=%.1f%%)",
                label,
                baseline.meanMs,
                baseline.p95Ms,
                enhanced.meanMs,
                enhanced.p95Ms,
                meanDelta,
                p95Delta);
    }

    private static Scene buildScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.08, 0.08, 0.09));
        scene.setBackgroundColor(new Vec3(0.02, 0.03, 0.05));
        scene.setEnvironmentStrength(1.0);
        scene.addLight(new DirectionalLight(new Vec3(-0.35, -0.9, -0.8), new Vec3(1.0, 0.98, 0.92), 1.7));

        Entity floor = new Entity("floor",
                MeshGenerator.plane(20.0, 20.0, 1, 1),
                new PhongMaterial(new Vec3(0.50, 0.52, 0.58), 20.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.0, 0.0));
        scene.addEntity(floor);

        for (int i = 0; i < 36; i++) {
            double ring = i / 12.0;
            double angle = (i % 12) / 12.0 * Math.PI * 2.0;
            double radius = 1.8 + ring * 1.1;
            double x = Math.cos(angle) * radius;
            double z = Math.sin(angle) * radius;
            double y = -0.3 + (i % 4) * 0.45;
            Entity sphere = new Entity("s" + i,
                    MeshGenerator.sphere(0.36, 22, 16),
                    new PhongMaterial(new Vec3(0.28 + 0.03 * (i % 6), 0.24 + 0.04 * (i % 5), 0.20 + 0.05 * (i % 4)), 52.0));
            sphere.getTransform().setPosition(new Vec3(x, y, z));
            scene.addEntity(sphere);
        }

        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera(double aspect) {
        PerspectiveCamera camera = new PerspectiveCamera(62.0, aspect, 0.1, 100.0);
        camera.setPosition(new Vec3(0.0, 1.2, 5.0));
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
        return camera;
    }

    private static PerspectiveCamera copyCamera(PerspectiveCamera source, double aspect) {
        PerspectiveCamera camera = new PerspectiveCamera(source.getFovY(), aspect, source.getNear(), source.getFar());
        camera.setPosition(source.getPosition());
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
        return camera;
    }

    private static void animateCamera(PerspectiveCamera camera, int frame) {
        double angle = frame * 0.16;
        double radius = 4.8;
        camera.setPosition(new Vec3(Math.cos(angle) * radius, 1.1, Math.sin(angle) * radius));
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
    }

    private record Load(int width, int height, int frames, int warmupFrames) {
    }

    private static final class Metrics {
        final double meanMs;
        final double p95Ms;

        private Metrics(double meanMs, double p95Ms) {
            this.meanMs = meanMs;
            this.p95Ms = p95Ms;
        }

        static Metrics from(List<Double> values) {
            if (values == null || values.isEmpty()) {
                return new Metrics(Double.NaN, Double.NaN);
            }
            List<Double> sorted = new ArrayList<>(values);
            Collections.sort(sorted);
            double sum = 0.0;
            for (double value : values) {
                sum += value;
            }
            double mean = sum / values.size();
            int p95Index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
            return new Metrics(mean, sorted.get(p95Index));
        }
    }
}
