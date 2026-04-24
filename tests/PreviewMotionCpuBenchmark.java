import java.util.Arrays;
import java.util.Locale;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.PointLight;
import engine.scene.Scene;

public final class PreviewMotionCpuBenchmark {

    private static final int BASE_WIDTH = 320;
    private static final int BASE_HEIGHT = 180;
    private static final int WARMUP_FRAMES = 24;
    private static final int MEASURE_FRAMES = 72;
    private static final int WORKERS = 12;
    private static final double[] SCALE_TIERS = {1.00, 0.90, 0.80};

    private PreviewMotionCpuBenchmark() {
    }

    public static void main(String[] args) {
        double rtMedianDeltaSum = 0.0;
        double rtP90DeltaSum = 0.0;
        double ptMedianDeltaSum = 0.0;
        double ptP90DeltaSum = 0.0;

        for (double scaleTier : SCALE_TIERS) {
            int width = scaledDimension(BASE_WIDTH, scaleTier);
            int height = scaledDimension(BASE_HEIGHT, scaleTier);
            int scalePercent = (int) Math.round(scaleTier * 100.0);

            CaseResult rtBaseline = runRayCase("RT_BASELINE", false, false, scaleTier, width, height);
            CaseResult rtOptimized = runRayCase("RT_OPTIMIZED", true, true, scaleTier, width, height);
            CaseResult ptBaseline = runPathCase("PT_BASELINE", false, false, scaleTier, width, height);
            CaseResult ptOptimized = runPathCase("PT_OPTIMIZED", true, true, scaleTier, width, height);

            printCase(rtBaseline);
            printCase(rtOptimized);
            printCase(ptBaseline);
            printCase(ptOptimized);

            DeltaStats rtDelta = printDelta("RT_DELTA", scalePercent, rtBaseline, rtOptimized);
            DeltaStats ptDelta = printDelta("PT_DELTA", scalePercent, ptBaseline, ptOptimized);
            rtMedianDeltaSum += rtDelta.medianFpsDelta;
            rtP90DeltaSum += rtDelta.p90FpsDelta;
            ptMedianDeltaSum += ptDelta.medianFpsDelta;
            ptP90DeltaSum += ptDelta.p90FpsDelta;
        }

        int tiers = Math.max(1, SCALE_TIERS.length);
        System.out.printf(
                Locale.ROOT,
                "MOTION_SUMMARY|RT|tier_count=%d|avg_median_fps_delta=%.3f|avg_p90_fps_delta=%.3f%n",
                tiers,
                rtMedianDeltaSum / tiers,
                rtP90DeltaSum / tiers);
        System.out.printf(
                Locale.ROOT,
                "MOTION_SUMMARY|PT|tier_count=%d|avg_median_fps_delta=%.3f|avg_p90_fps_delta=%.3f%n",
                tiers,
                ptMedianDeltaSum / tiers,
                ptP90DeltaSum / tiers);
    }

    private static CaseResult runRayCase(String id,
                                         boolean optimized,
                                         boolean tileLimitedComposite,
                                         double scaleTier,
                                         int width,
                                         int height) {
        Scene scene = buildHeavyScene();
        PerspectiveCamera camera = buildCamera(width, height);
        FrameBuffer fb = new FrameBuffer(width, height);
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(width, height);
        renderer.setParameter("workerCount", WORKERS);
        renderer.setParameter("tileSize", 24);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("denoise", false);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);

        if (optimized) {
            renderer.setParameter("previewMotionSecondaryCadence", scaleTier <= 0.80 ? 7 : 6);
            renderer.setParameter("previewMotionDenoiseCadence", scaleTier <= 0.80 ? 6 : 5);
            renderer.setParameter("previewMotionBaseCompositeCadence", tileLimitedComposite ? (scaleTier <= 0.90 ? 5 : 4) : 3);
            renderer.setParameter("previewMotionSamplesPerFrame", 1);
            renderer.setParameter("previewMotionMaxDepth", 1);
            renderer.setParameter("previewMotionPolishScale", 0.22);
            renderer.setParameter("previewMotionBaseShadingScale", Math.max(0.56, scaleTier));
        } else {
            renderer.setParameter("previewMotionSecondaryCadence", 2);
            renderer.setParameter("previewMotionDenoiseCadence", 2);
            renderer.setParameter("previewMotionBaseCompositeCadence", 2);
            renderer.setParameter("previewMotionSamplesPerFrame", 1);
            renderer.setParameter("previewMotionMaxDepth", 2);
            renderer.setParameter("previewMotionPolishScale", 0.50);
            renderer.setParameter("previewMotionBaseShadingScale", scaleTier);
        }

        CaseResult result = runCase(id, scaleTier, width, height, tileLimitedComposite, renderer, scene, camera, fb);
        renderer.setParameter("shutdown", true);
        return result;
    }

    private static CaseResult runPathCase(String id,
                                          boolean optimized,
                                          boolean tileLimitedComposite,
                                          double scaleTier,
                                          int width,
                                          int height) {
        Scene scene = buildHeavyScene();
        PerspectiveCamera camera = buildCamera(width, height);
        FrameBuffer fb = new FrameBuffer(width, height);
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(width, height);
        renderer.setParameter("workerCount", WORKERS);
        renderer.setParameter("tileSize", 24);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("denoise", false);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);

        if (optimized) {
            renderer.setParameter("previewMotionSecondaryCadence", scaleTier <= 0.80 ? 7 : 6);
            renderer.setParameter("previewMotionDenoiseCadence", scaleTier <= 0.80 ? 6 : 5);
            renderer.setParameter("previewMotionSamplesPerFrame", 1);
            renderer.setParameter("previewMotionMaxDepth", 1);
            renderer.setParameter("previewMotionTileSubsetCadence", tileLimitedComposite ? (scaleTier <= 0.80 ? 4 : 3) : 2);
            renderer.setParameter("previewMotionDominantContributionOnly", tileLimitedComposite);
            renderer.setParameter("previewMotionMaxLocalLights", 2);
            renderer.setParameter("previewMotionMaxShadowedLocalLights", 1);
            renderer.setParameter("previewMotionLocalLightImportanceThreshold", 0.12);
            renderer.setParameter("previewMotionThroughputTermination", 0.08);
            renderer.setParameter("previewMotionRoughnessSecondarySkip", 0.32);
        } else {
            renderer.setParameter("previewMotionSecondaryCadence", 2);
            renderer.setParameter("previewMotionDenoiseCadence", 2);
            renderer.setParameter("previewMotionSamplesPerFrame", 1);
            renderer.setParameter("previewMotionMaxDepth", 2);
            renderer.setParameter("previewMotionTileSubsetCadence", 1);
            renderer.setParameter("previewMotionDominantContributionOnly", false);
            renderer.setParameter("previewMotionMaxLocalLights", -1);
            renderer.setParameter("previewMotionMaxShadowedLocalLights", -1);
            renderer.setParameter("previewMotionLocalLightImportanceThreshold", 0.0);
            renderer.setParameter("previewMotionThroughputTermination", 0.0);
            renderer.setParameter("previewMotionRoughnessSecondarySkip", 0.0);
        }

        CaseResult result = runCase(id, scaleTier, width, height, tileLimitedComposite, renderer, scene, camera, fb);
        renderer.setParameter("shutdown", true);
        return result;
    }

    private static CaseResult runCase(String id,
                                      double scaleTier,
                                      int width,
                                      int height,
                                      boolean tileLimitedComposite,
                                      Renderer renderer,
                                      Scene scene,
                                      PerspectiveCamera camera,
                                      FrameBuffer fb) {
        for (int i = 0; i < WARMUP_FRAMES; i++) {
            double time = i * 0.17;
            updateDynamicCamera(scene, camera, time, i);
            renderer.render(scene, camera, fb, time);
        }

        double[] frameMs = new double[MEASURE_FRAMES];
        for (int i = 0; i < MEASURE_FRAMES; i++) {
            double time = (WARMUP_FRAMES + i) * 0.17;
            updateDynamicCamera(scene, camera, time, WARMUP_FRAMES + i);
            long start = System.nanoTime();
            renderer.render(scene, camera, fb, time);
            frameMs[i] = (System.nanoTime() - start) / 1_000_000.0;
        }
        return new CaseResult(id, scaleTier, width, height, tileLimitedComposite, frameMs);
    }

    private static void updateDynamicCamera(Scene scene, PerspectiveCamera camera, double time, int frameIndex) {
        double orbitAngle = Math.toRadians(-18.0 + frameIndex * 4.5);
        double orbitRadius = 7.40 + Math.cos(time * 0.7) * 0.35;
        double x = Math.sin(orbitAngle) * 1.55;
        double y = 1.20 + Math.sin(time * 1.1) * 0.22;
        double z = orbitRadius + Math.cos(orbitAngle) * 0.90;
        camera.setPosition(new Vec3(x, y, z));
        camera.lookAt(new Vec3(
                Math.sin(time * 0.8) * 0.30,
                0.18 + Math.cos(time * 0.6) * 0.10,
                Math.sin(orbitAngle * 0.55) * 0.30
        ));
        scene.update(time);
    }

    private static PerspectiveCamera buildCamera(int width, int height) {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, width / (double) height, 0.1, 120.0);
        camera.setPosition(new Vec3(0.0, 1.3, 7.4));
        camera.lookAt(new Vec3(0.0, 0.2, 0.0));
        return camera;
    }

    private static int scaledDimension(int base, double scaleTier) {
        return Math.max(96, (int) Math.round(base * scaleTier));
    }

    private static Scene buildHeavyScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.09, 0.09, 0.10));
        scene.setBackgroundColor(new Vec3(0.03, 0.035, 0.045));

        Entity floor = new Entity("floor",
                MeshGenerator.plane(18.0, 18.0, 24, 24),
                new PhongMaterial(new Vec3(0.34, 0.36, 0.40), 16.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.6, 0.0));
        scene.addEntity(floor);

        Entity knot = new Entity("knot",
                MeshGenerator.torusKnot(0.95, 0.34, 0.12, 220, 24, 2, 3),
                new PhongMaterial(new Vec3(0.87, 0.46, 0.20), 34.0));
        knot.getTransform().setPosition(new Vec3(0.0, 0.05, 0.0));
        knot.getTransform().setEulerAngles(Math.toRadians(22.0), Math.toRadians(28.0), Math.toRadians(10.0));
        scene.addEntity(knot);

        Entity torus = new Entity("torus",
                MeshGenerator.torus(1.05, 0.20, 60, 28),
                new PhongMaterial(new Vec3(0.17, 0.71, 0.84), 42.0));
        torus.getTransform().setPosition(new Vec3(-2.30, 0.10, -0.55));
        scene.addEntity(torus);

        Entity sphere = new Entity("sphere",
                MeshGenerator.sphere(0.95, 46, 34),
                new PhongMaterial(new Vec3(0.24, 0.62, 0.98), 58.0));
        sphere.getTransform().setPosition(new Vec3(2.20, -0.20, 0.20));
        scene.addEntity(sphere);

        scene.addLight(new DirectionalLight(new Vec3(-0.58, -1.0, -0.36), new Vec3(1.0, 0.96, 0.92), 1.30));
        scene.addLight(new DirectionalLight(new Vec3(0.38, -0.42, 0.28), new Vec3(0.38, 0.46, 0.66), 0.42));

        PointLight p0 = new PointLight(new Vec3(-2.4, 1.6, 1.8), new Vec3(1.0, 0.44, 0.24), 2.8);
        p0.setAttenuation(1.0, 0.045, 0.012);
        scene.addLight(p0);

        PointLight p1 = new PointLight(new Vec3(2.5, 1.8, 1.2), new Vec3(0.18, 0.72, 1.0), 2.4);
        p1.setAttenuation(1.0, 0.05, 0.014);
        scene.addLight(p1);

        ConeLight spot = new ConeLight(new Vec3(1.0, 4.4, 4.2), new Vec3(1.0, 0.88, 0.56), 3.8);
        spot.setDirection(new Vec3(-0.15, -0.96, -0.24));
        spot.setConeAngleDegrees(30.0);
        spot.setSoftness(0.30);
        spot.setAttenuation(1.0, 0.025, 0.009);
        scene.addLight(spot);

        scene.update(0.0);
        return scene;
    }

    private static void printCase(CaseResult result) {
        System.out.printf(
                Locale.ROOT,
                "MOTION_BENCH|%s|scale_pct=%d|size=%dx%d|tile_limited_composite=%s|median_ms=%.4f|p90_ms=%.4f|mean_ms=%.4f|min_ms=%.4f|max_ms=%.4f|stddev_ms=%.4f|median_fps=%.3f|p90_fps=%.3f%n",
                result.id,
                (int) Math.round(result.scaleTier * 100.0),
                result.width,
                result.height,
                Boolean.toString(result.tileLimitedComposite),
                result.stats.medianMs,
                result.stats.p90Ms,
                result.stats.meanMs,
                result.stats.minMs,
                result.stats.maxMs,
                result.stats.stdDevMs,
                1000.0 / Math.max(1e-9, result.stats.medianMs),
                1000.0 / Math.max(1e-9, result.stats.p90Ms));
    }

    private static DeltaStats printDelta(String id, int scalePercent, CaseResult baseline, CaseResult optimized) {
        double baselineMedianFps = 1000.0 / Math.max(1e-9, baseline.stats.medianMs);
        double optimizedMedianFps = 1000.0 / Math.max(1e-9, optimized.stats.medianMs);
        double baselineP90Fps = 1000.0 / Math.max(1e-9, baseline.stats.p90Ms);
        double optimizedP90Fps = 1000.0 / Math.max(1e-9, optimized.stats.p90Ms);
        double medianDelta = optimizedMedianFps - baselineMedianFps;
        double p90Delta = optimizedP90Fps - baselineP90Fps;
        System.out.printf(
                Locale.ROOT,
                "MOTION_DELTA|%s|scale_pct=%d|median_fps_delta=%.3f|p90_fps_delta=%.3f%n",
                id,
                scalePercent,
                medianDelta,
                p90Delta);
        return new DeltaStats(medianDelta, p90Delta);
    }

    private record CaseResult(String id,
                              double scaleTier,
                              int width,
                              int height,
                              boolean tileLimitedComposite,
                              Stats stats) {
        private CaseResult(String id,
                           double scaleTier,
                           int width,
                           int height,
                           boolean tileLimitedComposite,
                           double[] frameMs) {
            this(id, scaleTier, width, height, tileLimitedComposite, Stats.from(frameMs));
        }
    }

    private record DeltaStats(double medianFpsDelta, double p90FpsDelta) {
    }

    private static final class Stats {
        final double medianMs;
        final double p90Ms;
        final double meanMs;
        final double minMs;
        final double maxMs;
        final double stdDevMs;

        private Stats(double medianMs,
                      double p90Ms,
                      double meanMs,
                      double minMs,
                      double maxMs,
                      double stdDevMs) {
            this.medianMs = medianMs;
            this.p90Ms = p90Ms;
            this.meanMs = meanMs;
            this.minMs = minMs;
            this.maxMs = maxMs;
            this.stdDevMs = stdDevMs;
        }

        static Stats from(double[] samples) {
            double[] sorted = Arrays.copyOf(samples, samples.length);
            Arrays.sort(sorted);
            double min = sorted.length == 0 ? 0.0 : sorted[0];
            double max = sorted.length == 0 ? 0.0 : sorted[sorted.length - 1];
            double sum = 0.0;
            for (double value : sorted) {
                sum += value;
            }
            double mean = sum / Math.max(1, sorted.length);
            double varianceSum = 0.0;
            for (double value : sorted) {
                double delta = value - mean;
                varianceSum += delta * delta;
            }
            double stdDev = Math.sqrt(varianceSum / Math.max(1, sorted.length));
            return new Stats(percentile(sorted, 0.50), percentile(sorted, 0.90), mean, min, max, stdDev);
        }

        private static double percentile(double[] sorted, double p) {
            if (sorted.length == 0) {
                return 0.0;
            }
            double pos = Math.max(0.0, Math.min(1.0, p)) * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            if (lo == hi) {
                return sorted[lo];
            }
            double t = pos - lo;
            return sorted[lo] + (sorted[hi] - sorted[lo]) * t;
        }
    }
}
