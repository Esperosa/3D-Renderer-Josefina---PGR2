package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.Entity;
import engine.scene.Scene;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public final class NoiseQualityMetricsTests {

    private static final int WIDTH = 64;
    private static final int HEIGHT = 64;
    private static final int[] TARGET_SAMPLES = new int[]{1, 2, 4, 8, 16, 32};
    private static final double[] NOISE_THRESHOLDS = new double[]{0.08, 0.05, 0.03};
    private static final double OUTLIER_NOISE_THRESHOLD = 0.25;
    private static final long DEFAULT_SEED = 913862L;

    private NoiseQualityMetricsTests() {
    }

    public static void main(String[] args) throws Exception {
        long seed = resolveSeed();
        boolean strict = shouldUseStrictAssertions(seed);
        System.out.println("NoiseQualityMetricsTests seed=" + seed + " strict=" + strict);

        List<SceneProfile> profiles = buildSceneProfiles(seed);

        for (SceneProfile profile : profiles) {
            runRayProfile("ray-viewport/" + profile.label, false, profile.sceneA, profile.sceneB, strict);
            runRayProfile("ray-output/" + profile.label, true, profile.sceneA, profile.sceneB, strict);
            runPathProfile("path-viewport/" + profile.label, false, profile.sceneA, profile.sceneB, strict);
            runPathProfile("path-output/" + profile.label, true, profile.sceneA, profile.sceneB, strict);
        }

        runAggregatedProfiles("ray-viewport/all", false, profiles, strict, true);
        runAggregatedProfiles("ray-output/all", true, profiles, strict, true);
        runAggregatedProfiles("path-viewport/all", false, profiles, strict, false);
        runAggregatedProfiles("path-output/all", true, profiles, strict, false);

        System.out.println("NoiseQualityMetricsTests: ALL TESTS PASSED");
    }

    private static void runRayProfile(String label,
                                      boolean outputProfile,
                                      Scene sceneA,
                                      Scene sceneB,
                                      boolean strict) throws Exception {
        List<NoiseMetrics> metrics = collectRayMetrics(outputProfile, sceneA, sceneB);
        emitSummary(label, metrics);
        assertImprovement(label, metrics, strict);
    }

    private static void runPathProfile(String label,
                                       boolean outputProfile,
                                       Scene sceneA,
                                       Scene sceneB,
                                       boolean strict) throws Exception {
        List<NoiseMetrics> metrics = collectPathMetrics(outputProfile, sceneA, sceneB);
        emitSummary(label, metrics);
        assertImprovement(label, metrics, strict);
    }

    private static void runAggregatedProfiles(String label,
                                              boolean outputProfile,
                                              List<SceneProfile> profiles,
                                              boolean strict,
                                              boolean rayMode) throws Exception {
        List<NoiseMetrics> combined = new ArrayList<>();
        for (int target : TARGET_SAMPLES) {
            NoiseMetrics merged = null;
            for (SceneProfile profile : profiles) {
                NoiseMetrics a = rayMode
                        ? renderRayScene(profile.sceneA, outputProfile, target)
                        : renderPathScene(profile.sceneA, outputProfile, target);
                NoiseMetrics b = rayMode
                        ? renderRayScene(profile.sceneB, outputProfile, target)
                        : renderPathScene(profile.sceneB, outputProfile, target);
                NoiseMetrics pair = NoiseMetrics.average(a, b);
                merged = NoiseMetrics.average(merged, pair);
            }
            combined.add(merged);
        }
        emitSummary(label, combined);
        assertImprovement(label, combined, strict);
    }

    private static List<NoiseMetrics> collectRayMetrics(boolean outputProfile,
                                                        Scene sceneA,
                                                        Scene sceneB) throws Exception {
        List<NoiseMetrics> metrics = new ArrayList<>();
        for (int target : TARGET_SAMPLES) {
            NoiseMetrics a = renderRayScene(sceneA, outputProfile, target);
            NoiseMetrics b = renderRayScene(sceneB, outputProfile, target);
            metrics.add(NoiseMetrics.average(a, b));
        }
        return metrics;
    }

    private static List<NoiseMetrics> collectPathMetrics(boolean outputProfile,
                                                         Scene sceneA,
                                                         Scene sceneB) throws Exception {
        List<NoiseMetrics> metrics = new ArrayList<>();
        for (int target : TARGET_SAMPLES) {
            NoiseMetrics a = renderPathScene(sceneA, outputProfile, target);
            NoiseMetrics b = renderPathScene(sceneB, outputProfile, target);
            metrics.add(NoiseMetrics.average(a, b));
        }
        return metrics;
    }

    private static NoiseMetrics renderRayScene(Scene scene,
                                               boolean outputProfile,
                                               int targetSamples) throws Exception {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(WIDTH, HEIGHT);
        renderer.init(WIDTH, HEIGHT);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("tileSize", outputProfile ? 24 : 16);
        renderer.setParameter("maxDepth", outputProfile ? 6 : 4);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", true);
        renderer.setParameter("reflections", true);
        renderer.setParameter("sky", true);
        renderer.setParameter("denoise", false);
        renderer.setParameter("adaptiveSampling", false);
        renderer.setParameter("reset", true);

        PerspectiveCamera camera = buildCamera();
        advanceToSamples(renderer, scene, camera, fb, targetSamples);
        return measureNoise(renderer, targetSamples);
    }

    private static NoiseMetrics renderPathScene(Scene scene,
                                                boolean outputProfile,
                                                int targetSamples) throws Exception {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(WIDTH, HEIGHT);
        renderer.init(WIDTH, HEIGHT);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("tileSize", outputProfile ? 24 : 16);
        renderer.setParameter("maxDepth", outputProfile ? 7 : 5);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("sky", true);
        renderer.setParameter("denoise", false);
        renderer.setParameter("adaptiveSampling", false);
        renderer.setParameter("referenceMode", true);
        renderer.setParameter("historyFireflyClamp", !outputProfile);
        renderer.setParameter("reset", true);

        PerspectiveCamera camera = buildCamera();
        advanceToSamples(renderer, scene, camera, fb, targetSamples);
        return measureNoise(renderer, targetSamples);
    }

    private static void advanceToSamples(RayTracerRenderer renderer,
                                         Scene scene,
                                         PerspectiveCamera camera,
                                         FrameBuffer fb,
                                         int targetSamples) {
        long guard = 0;
        while (renderer.getAccumulatedSamples() < targetSamples && guard < 2000) {
            renderer.render(scene, camera, fb, 0.0);
            guard++;
        }
    }

    private static void advanceToSamples(PathTracerRenderer renderer,
                                         Scene scene,
                                         PerspectiveCamera camera,
                                         FrameBuffer fb,
                                         int targetSamples) {
        long guard = 0;
        while (renderer.getAccumulatedSamples() < targetSamples && guard < 2000) {
            renderer.render(scene, camera, fb, 0.0);
            guard++;
        }
    }

    private static NoiseMetrics measureNoise(Object renderer, int targetSamples) throws Exception {
        double[] luma = getDoubleArray(renderer, "accumLuma");
        double[] lumaSq = getDoubleArray(renderer, "accumLumaSq");
        int[] sampleCounts = getIntArray(renderer, "sampleCounts");
        int count = Math.min(luma.length, lumaSq.length);
        double[] relNoise = new double[count];
        double meanLumaSum = 0.0;
        double meanStdSum = 0.0;
        double relNoiseSum = 0.0;
        int hotCount = 0;
        for (int i = 0; i < count; i++) {
            int samples = resolveSampleCount(sampleCounts, i, targetSamples);
            if (samples <= 0) {
                relNoise[i] = 0.0;
                continue;
            }
            double mean = luma[i] / samples;
            double meanSq = lumaSq[i] / samples;
            double variance = Math.max(0.0, meanSq - mean * mean);
            double std = Math.sqrt(variance / Math.max(1.0, samples));
            double relative = std / Math.max(1e-6, mean);
            meanLumaSum += mean;
            meanStdSum += std;
            relNoiseSum += relative;
            relNoise[i] = relative;
            if (relative >= OUTLIER_NOISE_THRESHOLD) {
                hotCount++;
            }
        }
        Arrays.sort(relNoise);
        double avgLuma = meanLumaSum / Math.max(1, count);
        double avgStd = meanStdSum / Math.max(1, count);
        double avgRel = relNoiseSum / Math.max(1, count);
        double p90 = percentile(relNoise, 0.90);
        double p99 = percentile(relNoise, 0.99);
        double p999 = percentile(relNoise, 0.999);
        double snr = avgStd > 1e-9 ? (avgLuma / avgStd) : 0.0;
        double hotRatio = count > 0 ? (hotCount / (double) count) : 0.0;
        return new NoiseMetrics(targetSamples, avgLuma, avgStd, avgRel, p90, p99, p999, snr, hotRatio);
    }

    private static void emitSummary(String label, List<NoiseMetrics> metrics) {
        System.out.println("=== " + label + " ===");
        for (NoiseMetrics metric : metrics) {
            System.out.println(metric.toLine());
        }
        for (double threshold : NOISE_THRESHOLDS) {
            int samples = resolveCleanSampleCount(metrics, threshold);
            System.out.println("  clean<= " + threshold + " @ " + samples + " samples");
        }
    }

    private static void assertImprovement(String label, List<NoiseMetrics> metrics, boolean strict) {
        if (metrics.isEmpty()) {
            throw new AssertionError(label + " metrics are empty.");
        }
        NoiseMetrics first = firstComparable(metrics);
        NoiseMetrics last = metrics.get(metrics.size() - 1);
        if (!Double.isFinite(first.avgRelativeNoise) || !Double.isFinite(last.avgRelativeNoise)) {
            throw new AssertionError(label + " produced non-finite noise metrics.");
        }
        if (strict) {
            double firstScore = first.compositeScore();
            double lastScore = last.compositeScore();
            if (lastScore > firstScore * 1.10) {
                throw new AssertionError(label + " noise did not improve enough. first="
                        + firstScore + " last=" + lastScore);
            }
        }
    }

    private static int resolveCleanSampleCount(List<NoiseMetrics> metrics, double threshold) {
        int fallback = metrics.isEmpty() ? 0 : metrics.get(metrics.size() - 1).samples;
        for (NoiseMetrics metric : metrics) {
            if (metric.samples < 2) {
                continue;
            }
            if (metric.avgRelativeNoise <= threshold) {
                return metric.samples;
            }
        }
        return fallback;
    }

    private static NoiseMetrics firstComparable(List<NoiseMetrics> metrics) {
        for (NoiseMetrics metric : metrics) {
            if (metric.samples >= 2) {
                return metric;
            }
        }
        return metrics.get(0);
    }

    private static int resolveSampleCount(int[] sampleCounts, int index, int fallback) {
        if (sampleCounts != null && index >= 0 && index < sampleCounts.length && sampleCounts[index] > 0) {
            return sampleCounts[index];
        }
        return fallback;
    }

    private static double percentile(double[] values, double percentile) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double clamped = Math.max(0.0, Math.min(1.0, percentile));
        int idx = (int) Math.round(clamped * (values.length - 1));
        idx = Math.max(0, Math.min(values.length - 1, idx));
        return values[idx];
    }

        private static List<SceneProfile> buildSceneProfiles(long seed) {
        List<SceneProfile> profiles = new ArrayList<>();
        profiles.add(new SceneProfile("balanced",
            buildRandomScene(seed, 18, 2,
                0.2, 0.9,
                0.2, 0.9,
                0.25, 0.7,
                0.85,
                3.0, 6.0, 0.35),
            buildRandomScene(seed ^ 0x9E3779B97F4A7C15L, 20, 3,
                0.2, 0.9,
                0.2, 0.9,
                0.25, 0.7,
                0.85,
                3.0, 6.0, 0.35)));
        profiles.add(new SceneProfile("glossy-emissive",
            buildRandomScene(seed ^ 0xC6A4A7935BD1E995L, 26, 6,
                0.05, 0.35,
                0.5, 0.95,
                0.4, 0.9,
                1.2,
                6.0, 12.0, 0.55),
            buildRandomScene(seed ^ 0x91E10DA5C79E7B1DL, 28, 6,
                0.05, 0.35,
                0.5, 0.95,
                0.4, 0.9,
                1.2,
                6.0, 12.0, 0.55)));
        profiles.add(new SceneProfile("diffuse-dim",
            buildRandomScene(seed ^ 0xD4E12C77A2B3C4D5L, 16, 1,
                0.65, 0.95,
                0.0, 0.25,
                0.15, 0.55,
                0.45,
                2.0, 4.0, 0.25),
            buildRandomScene(seed ^ 0xF1E2D3C4B5A69788L, 18, 1,
                0.65, 0.95,
                0.0, 0.25,
                0.15, 0.55,
                0.45,
                2.0, 4.0, 0.25)));
        return profiles;
        }

        private static Scene buildRandomScene(long seed,
                          int triangleCount,
                          int emissiveCount,
                          double roughMin,
                          double roughMax,
                          double reflectMin,
                          double reflectMax,
                          double colorMin,
                          double colorMax,
                          double environmentStrength,
                          double emissionMin,
                          double emissionMax,
                          double emissiveChance) {
        Random rng = new Random(seed);
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.02, 0.02, 0.02));
        scene.setBackgroundColor(new Vec3(0.08, 0.10, 0.12));
        scene.setEnvironmentStrength(environmentStrength);

        addFloor(scene, rng, roughMax);
        addRandomTriangles(scene, rng, Math.max(6, triangleCount), emissiveCount,
            roughMin, roughMax,
            reflectMin, reflectMax,
            colorMin, colorMax,
            emissionMin, emissionMax,
            emissiveChance);
        scene.update(0.0);
        return scene;
        }

    private static void addFloor(Scene scene, Random rng, double roughMax) {
        Mesh mesh = new Mesh(
                "floor",
                new float[]{
                        -3.5f, -1.2f, -3.5f,
                        3.5f, -1.2f, -3.5f,
                        3.5f, -1.2f, 3.5f,
                        -3.5f, -1.2f, 3.5f
                },
                new float[]{
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new int[]{0, 1, 2, 0, 2, 3}
        );
        PhongMaterial material = new PhongMaterial(randomColor(rng, 0.25, 0.7), 18.0 + rng.nextDouble() * 42.0);
        material.setRoughness(Math.max(0.55, roughMax - 0.15) + rng.nextDouble() * 0.2);
        scene.addEntity(new Entity("floor", mesh, material));
    }

    private static void addRandomTriangles(Scene scene,
                                           Random rng,
                                           int triangleCount,
                                           int emissiveCount,
                                           double roughMin,
                                           double roughMax,
                                           double reflectMin,
                                           double reflectMax,
                                           double colorMin,
                                           double colorMax,
                                           double emissionMin,
                                           double emissionMax,
                                           double emissiveChance) {
        int emissiveLeft = Math.max(0, emissiveCount);
        for (int i = 0; i < triangleCount; i++) {
            Vec3 a = randomPoint(rng, -1.8, 1.8, -0.6, 1.6, -1.6, 1.6);
            Vec3 b = randomPoint(rng, -1.8, 1.8, -0.6, 1.6, -1.6, 1.6);
            Vec3 c = randomPoint(rng, -1.8, 1.8, -0.6, 1.6, -1.6, 1.6);
            Vec3 normal = computeNormal(a, b, c);
            Mesh mesh = new Mesh(
                    "tri-" + i,
                    new float[]{
                            (float) a.x, (float) a.y, (float) a.z,
                            (float) b.x, (float) b.y, (float) b.z,
                            (float) c.x, (float) c.y, (float) c.z
                    },
                    new float[]{
                            (float) normal.x, (float) normal.y, (float) normal.z,
                            (float) normal.x, (float) normal.y, (float) normal.z,
                            (float) normal.x, (float) normal.y, (float) normal.z
                    },
                    new int[]{0, 1, 2}
            );

            PhongMaterial material = new PhongMaterial(randomColor(rng, colorMin, colorMax), 12.0 + rng.nextDouble() * 72.0);
            material.setRoughness(lerp(roughMin, roughMax, rng.nextDouble()));
            material.setReflectivity(lerp(reflectMin, reflectMax, rng.nextDouble()));

            if (emissiveLeft > 0 && rng.nextDouble() < emissiveChance) {
                material.setEmissionColor(randomColor(rng, 0.6, 1.0));
                material.setEmissionStrength(lerp(emissionMin, emissionMax, rng.nextDouble()));
                material.setDoubleSided(true);
                emissiveLeft--;
            }

            scene.addEntity(new Entity("tri-entity-" + i, mesh, material));
        }
    }

    private static PerspectiveCamera buildCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(55.0, 1.0, 0.1, 20.0);
        camera.setPosition(new Vec3(0.0, 0.8, 3.6));
        camera.lookAt(new Vec3(0.0, 0.2, 0.0));
        return camera;
    }

    private static Vec3 randomPoint(Random rng,
                                    double minX, double maxX,
                                    double minY, double maxY,
                                    double minZ, double maxZ) {
        return new Vec3(
                lerp(minX, maxX, rng.nextDouble()),
                lerp(minY, maxY, rng.nextDouble()),
                lerp(minZ, maxZ, rng.nextDouble())
        );
    }

    private static Vec3 computeNormal(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 ab = b.sub(a);
        Vec3 ac = c.sub(a);
        Vec3 normal = ab.cross(ac);
        double len = Math.sqrt(Math.max(1e-10, normal.lengthSquared()));
        return normal.mul(1.0 / len);
    }

    private static Vec3 randomColor(Random rng, double min, double max) {
        return new Vec3(
                lerp(min, max, rng.nextDouble()),
                lerp(min, max, rng.nextDouble()),
                lerp(min, max, rng.nextDouble())
        );
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long resolveSeed() {
        String raw = System.getProperty("noise.seed");
        if (raw == null || raw.isBlank()) {
            return DEFAULT_SEED;
        }
        if ("random".equalsIgnoreCase(raw)) {
            long seed = System.nanoTime();
            System.out.println("Noise metrics using random seed=" + seed);
            return seed;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return DEFAULT_SEED;
        }
    }

    private static boolean shouldUseStrictAssertions(long seed) {
        String mode = System.getProperty("noise.strict");
        if (mode != null && mode.equalsIgnoreCase("false")) {
            return false;
        }
        return seed == DEFAULT_SEED;
    }

    private static double[] getDoubleArray(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (double[]) field.get(target);
    }

    private static int[] getIntArray(Object target, String name) throws Exception {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (int[]) field.get(target);
        } catch (NoSuchFieldException ex) {
            return null;
        }
    }

    private static final class NoiseMetrics {
        private final int samples;
        private final double avgLuma;
        private final double avgStd;
        private final double avgRelativeNoise;
        private final double p90;
        private final double p99;
        private final double p999;
        private final double snr;
        private final double hotRatio;

        private NoiseMetrics(int samples,
                             double avgLuma,
                             double avgStd,
                             double avgRelativeNoise,
                             double p90,
                             double p99,
                             double p999,
                             double snr,
                             double hotRatio) {
            this.samples = samples;
            this.avgLuma = avgLuma;
            this.avgStd = avgStd;
            this.avgRelativeNoise = avgRelativeNoise;
            this.p90 = p90;
            this.p99 = p99;
            this.p999 = p999;
            this.snr = snr;
            this.hotRatio = hotRatio;
        }

        private static NoiseMetrics average(NoiseMetrics a, NoiseMetrics b) {
            if (a == null) {
                return b;
            }
            if (b == null) {
                return a;
            }
            return new NoiseMetrics(
                    a.samples,
                    (a.avgLuma + b.avgLuma) * 0.5,
                    (a.avgStd + b.avgStd) * 0.5,
                    (a.avgRelativeNoise + b.avgRelativeNoise) * 0.5,
                    (a.p90 + b.p90) * 0.5,
                    (a.p99 + b.p99) * 0.5,
                    (a.p999 + b.p999) * 0.5,
                    (a.snr + b.snr) * 0.5,
                    (a.hotRatio + b.hotRatio) * 0.5
            );
        }

        private String toLine() {
            return "  samples=" + samples
                    + " avgLuma=" + fmt(avgLuma)
                    + " avgStd=" + fmt(avgStd)
                    + " avgRelNoise=" + fmt(avgRelativeNoise)
                    + " p90=" + fmt(p90)
                    + " p99=" + fmt(p99)
                    + " p999=" + fmt(p999)
                    + " hot%=" + fmt(hotRatio * 100.0)
                    + " snr=" + fmt(snr);
        }

        private double compositeScore() {
            return avgRelativeNoise * 0.6 + p99 * 0.4;
        }

        private static String fmt(double value) {
            return String.format("%.4f", value);
        }
    }

    private static final class SceneProfile {
        private final String label;
        private final Scene sceneA;
        private final Scene sceneB;

        private SceneProfile(String label, Scene sceneA, Scene sceneB) {
            this.label = label;
            this.sceneA = sceneA;
            this.sceneB = sceneB;
        }
    }

}
