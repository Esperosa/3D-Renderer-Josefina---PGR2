package engine.core;

import java.util.ArrayList;
import java.util.List;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.render.ray.preview.ProgressiveRenderDefaults;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.ThreadPool;

public final class OutputRenderPerformanceEvidenceTests {

    private static final int WIDTH = 256;
    private static final int HEIGHT = 144;
    private static final int TARGET_SAMPLES = 12;
    private static final int MEASURED_RUNS = 3;

    private OutputRenderPerformanceEvidenceTests() {
    }

    public static void main(String[] args) {
        Evidence ray = benchmarkRayTracing();
        Evidence path = benchmarkPathTracing();

        System.out.println(String.format(
                "OutputEvidence[ray] baseline=%.2fms enhanced=%.2fms delta=%.1f%%",
                ray.baselineMeanMs,
                ray.enhancedMeanMs,
                ray.deltaPercent));
        System.out.println(String.format(
                "OutputEvidence[path] baseline=%.2fms enhanced=%.2fms delta=%.1f%%",
                path.baselineMeanMs,
                path.enhancedMeanMs,
                path.deltaPercent));

        if (ray.deltaPercent < 10.0) {
            throw new AssertionError("Ray output throughput improvement is too small: " + ray.deltaPercent + "%");
        }
        if (path.deltaPercent < 10.0) {
            throw new AssertionError("Path output throughput improvement is too small: " + path.deltaPercent + "%");
        }

        System.out.println("OutputRenderPerformanceEvidenceTests: ALL TESTS PASSED");
    }

    private static Evidence benchmarkRayTracing() {
        warmUpRay(false);
        warmUpRay(true);
        return new Evidence(
                measureRay(false),
                measureRay(true));
    }

    private static Evidence benchmarkPathTracing() {
        warmUpPath(false);
        warmUpPath(true);
        return new Evidence(
                measurePath(false),
                measurePath(true));
    }

    private static void warmUpRay(boolean enhanced) {
        measureSingleRayRun(enhanced);
    }

    private static void warmUpPath(boolean enhanced) {
        measureSinglePathRun(enhanced);
    }

    private static double measureRay(boolean enhanced) {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < MEASURED_RUNS; i++) {
            samples.add(measureSingleRayRun(enhanced));
        }
        return average(samples);
    }

    private static double measurePath(boolean enhanced) {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < MEASURED_RUNS; i++) {
            samples.add(measureSinglePathRun(enhanced));
        }
        return average(samples);
    }

    private static double measureSingleRayRun(boolean enhanced) {
        Scene scene = buildBenchmarkScene();
        PerspectiveCamera camera = buildBenchmarkCamera();
        FrameBuffer fb = new FrameBuffer(WIDTH, HEIGHT);
        RayTracerRenderer renderer = createRayRenderer(enhanced);
        long start = System.nanoTime();
        try {
            while (renderer.getAccumulatedSamples() < TARGET_SAMPLES) {
                renderer.render(scene, camera, fb, 0.0);
            }
            if (enhanced) {
                renderer.setParameter("forceFullDenoiseResolve", true);
                renderer.setParameter("resolveOnly", true);
                renderer.render(scene, camera, fb, 0.0);
            }
            return (System.nanoTime() - start) / 1_000_000.0;
        } finally {
            renderer.setParameter("shutdown", true);
        }
    }

    private static double measureSinglePathRun(boolean enhanced) {
        Scene scene = buildBenchmarkScene();
        PerspectiveCamera camera = buildBenchmarkCamera();
        FrameBuffer fb = new FrameBuffer(WIDTH, HEIGHT);
        PathTracerRenderer renderer = createPathRenderer(enhanced);
        long start = System.nanoTime();
        try {
            while (renderer.getAccumulatedSamples() < TARGET_SAMPLES) {
                renderer.render(scene, camera, fb, 0.0);
            }
            if (enhanced) {
                renderer.setParameter("forceFullDenoiseResolve", true);
                renderer.setParameter("resolveOnly", true);
                renderer.render(scene, camera, fb, 0.0);
            }
            return (System.nanoTime() - start) / 1_000_000.0;
        } finally {
            renderer.setParameter("shutdown", true);
        }
    }

    private static RayTracerRenderer createRayRenderer(boolean enhanced) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(WIDTH, HEIGHT);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", false);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("workerCount", ThreadPool.recommendedOutputWorkerCount());
        renderer.setParameter("tileSize", 24);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 6);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", true);
        renderer.setParameter("reflections", true);
        renderer.setParameter("sky", true);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", ProgressiveRenderDefaults.OUTPUT_RAY_ADAPTIVE_MIN_SAMPLES);
        renderer.setParameter("adaptiveThreshold", ProgressiveRenderDefaults.OUTPUT_RAY_ADAPTIVE_THRESHOLD);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", ProgressiveRenderDefaults.OUTPUT_DENOISE_RADIUS);
        renderer.setParameter("denoiseStrength", ProgressiveRenderDefaults.OUTPUT_DENOISE_STRENGTH);
        renderer.setParameter("denoiseProfile", "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", "FULL_FRAME");
        renderer.setParameter("toneMap", "EXPOSURE");
        renderer.setParameter("denoiseCadenceSamples", enhanced ? 6 : 1);
        renderer.setParameter("reset", true);
        return renderer;
    }

    private static PathTracerRenderer createPathRenderer(boolean enhanced) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(WIDTH, HEIGHT);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", false);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("workerCount", ThreadPool.recommendedOutputWorkerCount());
        renderer.setParameter("tileSize", 24);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 6);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("sky", true);
        renderer.setParameter("referenceMode", true);
        renderer.setParameter("historyFireflyClamp", false);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_MIN_SAMPLES);
        renderer.setParameter("adaptiveThreshold", ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_THRESHOLD);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", ProgressiveRenderDefaults.OUTPUT_DENOISE_RADIUS);
        renderer.setParameter("denoiseStrength", ProgressiveRenderDefaults.OUTPUT_DENOISE_STRENGTH);
        renderer.setParameter("denoiseProfile", "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", "FULL_FRAME");
        renderer.setParameter("toneMap", "EXPOSURE");
        renderer.setParameter("clampDirect", ProgressiveRenderDefaults.OUTPUT_PATH_CLAMP_DIRECT);
        renderer.setParameter("clampIndirect", ProgressiveRenderDefaults.OUTPUT_PATH_CLAMP_INDIRECT);
        renderer.setParameter("referenceClamp", true);
        renderer.setParameter("denoiseCadenceSamples", enhanced ? 7 : 1);
        renderer.setParameter("reset", true);
        return renderer;
    }

    private static Scene buildBenchmarkScene() {
        Scene scene = new Scene();
        scene.setEnvironmentStrength(1.25);
        scene.setEnvironmentYawDegrees(18.0);
        scene.setBackgroundColor(new Vec3(0.028, 0.034, 0.048));

        Entity floor = new Entity("floor", MeshGenerator.cube(12.0), matte(new Vec3(0.26, 0.27, 0.29), 0.82));
        floor.getTransform().setPosition(new Vec3(0.0, -1.20, 0.0));
        floor.getTransform().setScale(new Vec3(1.0, 0.05, 1.0));
        scene.addEntity(floor);

        Entity cube = new Entity("cube", MeshGenerator.cube(1.0), metallic(new Vec3(0.84, 0.56, 0.24), 0.18, 0.72));
        cube.getTransform().setPosition(new Vec3(-1.70, -0.25, 0.40));
        cube.getTransform().setEulerAngles(Math.toRadians(14.0), Math.toRadians(26.0), Math.toRadians(-8.0));
        scene.addEntity(cube);

        Entity glass = new Entity("glass", MeshGenerator.sphere(0.74, 40, 24), glass(new Vec3(0.82, 0.90, 0.98), 0.14, 1.48));
        glass.getTransform().setPosition(new Vec3(0.15, -0.16, -0.10));
        scene.addEntity(glass);

        Entity emission = new Entity("emission", MeshGenerator.sphere(0.50, 32, 20), emissive(new Vec3(1.00, 0.58, 0.22), 2.2));
        emission.getTransform().setPosition(new Vec3(1.65, 0.66, -1.10));
        scene.addEntity(emission);

        Entity torus = new Entity("torus", MeshGenerator.torus(0.68, 0.22, 40, 18), coated(new Vec3(0.22, 0.52, 0.88), 0.28, 0.35));
        torus.getTransform().setPosition(new Vec3(1.95, 0.15, 0.88));
        torus.getTransform().setEulerAngles(Math.toRadians(84.0), Math.toRadians(-14.0), Math.toRadians(10.0));
        scene.addEntity(torus);

        Entity crystal = new Entity("crystal", MeshGenerator.crystal(0.82, 1.55, 7), coated(new Vec3(0.38, 0.78, 0.98), 0.12, 0.08));
        crystal.getTransform().setPosition(new Vec3(-2.45, 0.05, -1.35));
        crystal.getTransform().setEulerAngles(0.0, Math.toRadians(18.0), 0.0);
        scene.addEntity(crystal);

        DirectionalLight sun = new DirectionalLight(new Vec3(-0.42, -1.0, -0.36), new Vec3(1.0, 0.96, 0.92), 2.6);
        scene.addLight(sun);

        return scene;
    }

    private static PerspectiveCamera buildBenchmarkCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, WIDTH / (double) HEIGHT, 0.1, 120.0);
        camera.setPosition(new Vec3(0.0, 1.15, 6.8));
        camera.lookAt(new Vec3(0.0, 0.0, -0.1));
        return camera;
    }

    private static PhongMaterial matte(Vec3 color, double roughness) {
        PhongMaterial material = new PhongMaterial(color, 24.0);
        material.setRoughness(roughness);
        material.setMetallic(0.0);
        material.setReflectivity(0.04);
        return material;
    }

    private static PhongMaterial metallic(Vec3 color, double roughness, double metallic) {
        PhongMaterial material = new PhongMaterial(color, 64.0);
        material.setRoughness(roughness);
        material.setMetallic(metallic);
        material.setReflectivity(0.42);
        return material;
    }

    private static PhongMaterial glass(Vec3 color, double roughness, double ior) {
        PhongMaterial material = new PhongMaterial(color, 96.0);
        material.setRoughness(roughness);
        material.setTransmission(0.92);
        material.setDensity(0.03);
        material.setThickness(0.36);
        material.setRefractiveIndex(ior);
        material.setReflectivity(0.18);
        return material;
    }

    private static PhongMaterial emissive(Vec3 color, double strength) {
        PhongMaterial material = new PhongMaterial(new Vec3(0.26, 0.20, 0.16), 28.0);
        material.setRoughness(0.42);
        material.setEmissionColor(color);
        material.setEmissionStrength(strength);
        return material;
    }

    private static PhongMaterial coated(Vec3 color, double roughness, double clearcoat) {
        PhongMaterial material = new PhongMaterial(color, 72.0);
        material.setRoughness(roughness);
        material.setReflectivity(0.16);
        material.setClearcoatFactor(clearcoat);
        material.setClearcoatRoughness(0.08);
        return material;
    }

    private static double average(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private static final class Evidence {
        final double baselineMeanMs;
        final double enhancedMeanMs;
        final double deltaPercent;

        Evidence(double baselineMeanMs, double enhancedMeanMs) {
            this.baselineMeanMs = baselineMeanMs;
            this.enhancedMeanMs = enhancedMeanMs;
            this.deltaPercent = baselineMeanMs <= 1e-6
                    ? 0.0
                    : Math.max(0.0, (baselineMeanMs - enhancedMeanMs) / baselineMeanMs * 100.0);
        }
    }
}
