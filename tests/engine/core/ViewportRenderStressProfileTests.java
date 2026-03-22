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
import engine.util.HardwareTelemetrySampler;

public final class ViewportRenderStressProfileTests {

    private ViewportRenderStressProfileTests() {
    }

    public static void main(String[] args) {
        testWarmupDoesNotSwitchViewportMode();
        runStressSweep();
        System.out.println("ViewportRenderStressProfileTests: ALL TESTS PASSED");
    }

    private static void testWarmupDoesNotSwitchViewportMode() {
        Engine engine = new Engine();
        engine.safetyRecoveryActive = false;

        engine.activeMode = RenderMode.RAY_TRACING;
        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        RenderMode rayResolved = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (rayResolved != RenderMode.RAY_TRACING) {
            throw new AssertionError("Viewport warmup must not switch ray mode to fallback.");
        }

        engine.activeMode = RenderMode.PATH_TRACING;
        engine.pathAccumulationLock = false;
        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        RenderMode pathResolved = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (pathResolved != RenderMode.PATH_TRACING) {
            throw new AssertionError("Viewport warmup must not switch path mode to fallback.");
        }
    }

    private static void runStressSweep() {
        Scene scene = buildStressScene();
        PerspectiveCamera camera = buildCamera(1.0);

        List<LoadCase> loadCases = List.of(
                new LoadCase("small", 96, 54, 4),
                new LoadCase("medium", 160, 90, 4),
                new LoadCase("stress", 256, 144, 4)
        );

        for (LoadCase load : loadCases) {
            WorkloadStats rayViewport = runWorkload(scene, camera, load, true, true);
            WorkloadStats pathViewport = runWorkload(scene, camera, load, false, true);
            WorkloadStats rayOutput = runWorkload(scene, camera, load, true, false);
            WorkloadStats pathOutput = runWorkload(scene, camera, load, false, false);

            assertReasonable(load.name + " ray viewport", rayViewport);
            assertReasonable(load.name + " path viewport", pathViewport);
            assertReasonable(load.name + " ray output", rayOutput);
            assertReasonable(load.name + " path output", pathOutput);

            System.out.println(formatStats(load.name, "ray", "viewport", rayViewport));
            System.out.println(formatStats(load.name, "path", "viewport", pathViewport));
            System.out.println(formatStats(load.name, "ray", "output", rayOutput));
            System.out.println(formatStats(load.name, "path", "output", pathOutput));
        }
    }

    private static WorkloadStats runWorkload(Scene scene,
                                             PerspectiveCamera sourceCamera,
                                             LoadCase load,
                                             boolean ray,
                                             boolean viewportProfile) {
        Renderer renderer = ray ? new RayTracerRenderer() : new PathTracerRenderer();
        renderer.init(load.width, load.height);

        int workers = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
        renderer.setParameter("workerCount", workers);
        renderer.setParameter("tileSize", 24);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", viewportProfile ? 0.54 : 0.62);
        renderer.setParameter("autohardware", viewportProfile);
        renderer.setParameter("autoworkers", viewportProfile);
        renderer.setParameter("autotilesize", viewportProfile);
        renderer.setParameter("autoschedulingtargetms", ray ? (viewportProfile ? 16.0 : 26.0) : (viewportProfile ? 19.0 : 30.0));
        renderer.setParameter("denoiseProfile", viewportProfile ? "FAST" : "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", viewportProfile ? "AUTO" : "FULL_FRAME");

        FrameBuffer fb = new FrameBuffer(load.width, load.height);
        PerspectiveCamera camera = copyCamera(sourceCamera, (double) load.width / (double) load.height);

        long wallStart = System.nanoTime();
        HardwareTelemetrySampler.Sample startSample = HardwareTelemetrySampler.sample();
        long cpuStart = startSample.processCpuTimeNanos();
        long gcStart = startSample.gcTimeMillis();
        List<Double> samplesMs = new ArrayList<>();
        List<Double> processCpuLoads = new ArrayList<>();
        List<Double> systemCpuLoads = new ArrayList<>();
        List<Double> heapRatios = new ArrayList<>();
        int peakThreads = Math.max(1, startSample.liveThreadCount());

        for (int i = 0; i < load.frames; i++) {
            animateCamera(camera, i);
            long frameStart = System.nanoTime();
            renderer.render(scene, camera, fb, i * 0.016);
            double frameMs = (System.nanoTime() - frameStart) / 1_000_000.0;
            samplesMs.add(frameMs);

            HardwareTelemetrySampler.Sample frameSample = HardwareTelemetrySampler.sample();
            collectIfFinite(processCpuLoads, frameSample.processCpuLoad());
            collectIfFinite(systemCpuLoads, frameSample.systemCpuLoad());
            collectHeapRatio(heapRatios, frameSample.usedHeapBytes(), frameSample.maxHeapBytes());
            peakThreads = Math.max(peakThreads, frameSample.liveThreadCount());
        }

        long wallEnd = System.nanoTime();
        HardwareTelemetrySampler.Sample endSample = HardwareTelemetrySampler.sample();
        long cpuEnd = endSample.processCpuTimeNanos();
        long gcEnd = endSample.gcTimeMillis();
        collectIfFinite(processCpuLoads, endSample.processCpuLoad());
        collectIfFinite(systemCpuLoads, endSample.systemCpuLoad());
        collectHeapRatio(heapRatios, endSample.usedHeapBytes(), endSample.maxHeapBytes());
        peakThreads = Math.max(peakThreads, endSample.liveThreadCount());
        renderer.setParameter("shutdown", true);

        return computeStats(samplesMs,
                wallEnd - wallStart,
                cpuEnd - cpuStart,
                gcEnd - gcStart,
                averageOrNaN(processCpuLoads),
                averageOrNaN(systemCpuLoads),
                averageOrNaN(heapRatios),
                peakThreads);
    }

    private static void assertReasonable(String label, WorkloadStats stats) {
        if (!Double.isFinite(stats.meanMs) || stats.meanMs <= 0.0) {
            throw new AssertionError(label + " invalid mean frame time.");
        }
        if (!Double.isFinite(stats.p95Ms) || stats.p95Ms <= 0.0) {
            throw new AssertionError(label + " invalid p95 frame time.");
        }
        if (stats.p95Ms > 2500.0) {
            throw new AssertionError(label + " p95 frame time is too high: " + stats.p95Ms + "ms");
        }
        if (!Double.isFinite(stats.smoothnessScore) || stats.smoothnessScore <= 0.0) {
            throw new AssertionError(label + " invalid smoothness score.");
        }
        if (stats.smoothnessScore > 2.7) {
            throw new AssertionError(label + " jitter is too high: " + stats.smoothnessScore);
        }
        if (Double.isFinite(stats.meanProcessCpuLoad) && stats.meanProcessCpuLoad < 0.03) {
            throw new AssertionError(label + " process CPU utilization is unexpectedly low: " + stats.meanProcessCpuLoad);
        }
        if (Double.isFinite(stats.gcSharePercent) && stats.gcSharePercent > 55.0) {
            throw new AssertionError(label + " GC share is unexpectedly high: " + stats.gcSharePercent + "%");
        }
    }

    private static String formatStats(String load, String mode, String profile, WorkloadStats stats) {
        String cpu = Double.isFinite(stats.processCpuPercent)
                ? String.format("%.1f%%", stats.processCpuPercent)
                : "n/a";
        String procLoad = Double.isFinite(stats.meanProcessCpuLoad)
            ? String.format("%.1f%%", stats.meanProcessCpuLoad * 100.0)
            : "n/a";
        String sysLoad = Double.isFinite(stats.meanSystemCpuLoad)
            ? String.format("%.1f%%", stats.meanSystemCpuLoad * 100.0)
            : "n/a";
        String heap = Double.isFinite(stats.meanHeapUsageRatio)
            ? String.format("%.1f%%", stats.meanHeapUsageRatio * 100.0)
            : "n/a";
        String gc = Double.isFinite(stats.gcSharePercent)
            ? String.format("%.1f%%", stats.gcSharePercent)
            : "n/a";
        return String.format(
            "Stress[%s][%s][%s] mean=%.2fms p95=%.2fms smooth=%.3f cpu=%s proc=%s sys=%s heap=%s gc=%s threads=%d",
                load,
                mode,
                profile,
                stats.meanMs,
                stats.p95Ms,
                stats.smoothnessScore,
            cpu,
            procLoad,
            sysLoad,
            heap,
            gc,
            stats.peakThreadCount);
    }

    private static WorkloadStats computeStats(List<Double> frameSamplesMs,
                                              long wallNanos,
                              long processCpuNanos,
                              long gcDeltaMillis,
                              double meanProcessCpuLoad,
                              double meanSystemCpuLoad,
                              double meanHeapUsageRatio,
                              int peakThreadCount) {
        List<Double> sorted = new ArrayList<>(frameSamplesMs);
        Collections.sort(sorted);
        double mean = 0.0;
        for (double value : frameSamplesMs) {
            mean += value;
        }
        mean /= Math.max(1, frameSamplesMs.size());

        int p95Index = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(sorted.size() * 0.95) - 1));
        double p95 = sorted.get(p95Index);

        double variance = 0.0;
        for (double value : frameSamplesMs) {
            double d = value - mean;
            variance += d * d;
        }
        variance /= Math.max(1, frameSamplesMs.size());
        double stdDev = Math.sqrt(Math.max(0.0, variance));
        double smoothness = mean <= 1e-9 ? 0.0 : stdDev / mean;

        double cpuPercent = Double.NaN;
        if (wallNanos > 0L && processCpuNanos > 0L) {
            int logicalCores = Math.max(1, Runtime.getRuntime().availableProcessors());
            cpuPercent = (processCpuNanos / (double) wallNanos) * 100.0 / logicalCores;
        }

        double gcSharePercent = Double.NaN;
        if (wallNanos > 0L && gcDeltaMillis >= 0L) {
            gcSharePercent = (gcDeltaMillis * 1_000_000.0 / (double) wallNanos) * 100.0;
        }

        return new WorkloadStats(mean,
                p95,
                smoothness,
                cpuPercent,
                meanProcessCpuLoad,
                meanSystemCpuLoad,
                meanHeapUsageRatio,
                peakThreadCount,
                gcSharePercent);
    }

    private static void collectIfFinite(List<Double> values, double value) {
        if (Double.isFinite(value)) {
            values.add(value);
        }
    }

    private static void collectHeapRatio(List<Double> ratios, long usedHeapBytes, long maxHeapBytes) {
        if (usedHeapBytes < 0L || maxHeapBytes <= 0L) {
            return;
        }
        double ratio = usedHeapBytes / (double) maxHeapBytes;
        if (Double.isFinite(ratio) && ratio >= 0.0) {
            ratios.add(Math.min(1.0, ratio));
        }
    }

    private static double averageOrNaN(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double total = 0.0;
        for (double value : values) {
            total += value;
        }
        return total / values.size();
    }

    private static Scene buildStressScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.09, 0.09, 0.10));
        scene.setBackgroundColor(new Vec3(0.02, 0.03, 0.05));
        scene.setEnvironmentStrength(1.0);
        scene.addLight(new DirectionalLight(new Vec3(-0.4, -0.8, -1.0), new Vec3(1.0, 0.98, 0.92), 1.6));

        Entity floor = new Entity("floor",
            MeshGenerator.plane(18.0, 18.0, 1, 1),
                new PhongMaterial(new Vec3(0.52, 0.54, 0.58), 20.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.0, 0.0));
        scene.addEntity(floor);

        for (int i = 0; i < 12; i++) {
            double t = i / 12.0 * Math.PI * 2.0;
            double x = Math.cos(t) * 2.4;
            double z = Math.sin(t) * 2.4;
            double y = -0.2 + (i % 3) * 0.5;
            Entity sphere = new Entity("s" + i,
                    MeshGenerator.sphere(0.45, 20, 14),
                    new PhongMaterial(new Vec3(0.35 + 0.05 * (i % 5), 0.28 + 0.06 * (i % 4), 0.22 + 0.07 * (i % 3)), 48.0));
            sphere.getTransform().setPosition(new Vec3(x, y, z));
            scene.addEntity(sphere);
        }

        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera(double aspect) {
        PerspectiveCamera camera = new PerspectiveCamera(62.0, aspect, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.2, 4.6));
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
        return camera;
    }

    private static PerspectiveCamera copyCamera(PerspectiveCamera source, double aspect) {
        PerspectiveCamera camera = new PerspectiveCamera(source.getFovY(), aspect, source.getNear(), source.getFar());
        camera.setPosition(source.getPosition());
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
        return camera;
    }

    private static void animateCamera(PerspectiveCamera camera, int frameIndex) {
        double angle = frameIndex * 0.22;
        double radius = 4.4;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle) * radius;
        camera.setPosition(new Vec3(x, 1.1, z));
        camera.lookAt(new Vec3(0.0, -0.1, 0.0));
    }

    private record LoadCase(String name, int width, int height, int frames) {
    }

    private record WorkloadStats(double meanMs,
                                 double p95Ms,
                                 double smoothnessScore,
                                 double processCpuPercent,
                                 double meanProcessCpuLoad,
                                 double meanSystemCpuLoad,
                                 double meanHeapUsageRatio,
                                 int peakThreadCount,
                                 double gcSharePercent) {
    }
}
