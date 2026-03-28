package engine.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.PhongMaterial;
import engine.math.Quaternion;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.Texture;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.HardwareTelemetrySampler;

public final class ViewportRenderStressProfileTests {

    private ViewportRenderStressProfileTests() {
    }

    public static void main(String[] args) {
        testWarmupDoesNotSwitchViewportMode();
        testRendererSwitchChurnStaysBounded();
        testPhongRayPathMutationChurnStaysBounded();
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

    private static void testRendererSwitchChurnStaysBounded() {
        Scene scene = buildStressScene();
        PerspectiveCamera camera = buildCamera(16.0 / 9.0);
        RayTracerRenderer ray = new RayTracerRenderer();
        PathTracerRenderer path = new PathTracerRenderer();
        int width = 128;
        int height = 72;
        FrameBuffer fb = new FrameBuffer(width, height);
        ray.init(width, height);
        path.init(width, height);

        HardwareTelemetrySampler.Sample startSample = HardwareTelemetrySampler.sample();
        double startHeapRatio = heapRatio(startSample.usedHeapBytes(), startSample.maxHeapBytes());
        List<Double> heapRatios = new ArrayList<>();
        int baselineThreads = Math.max(1, startSample.liveThreadCount());
        int peakThreads = baselineThreads;
        Entity transientEntity = null;
        int workerCap = Math.min(4, Math.max(2, Runtime.getRuntime().availableProcessors() / 4));

        try {
            for (int frame = 0; frame < 10; frame++) {
                Renderer renderer = (frame & 1) == 0 ? ray : path;
                int workers = 1 + (frame % workerCap);
                renderer.setParameter("workerCount", workers);
                renderer.setParameter("tileSize", 16 + (frame % 3) * 8);
                renderer.setParameter("samplesPerFrame", 1);
                renderer.setParameter("denoise", true);
                renderer.setParameter("denoiseProfile", "FAST");
                renderer.setParameter("denoiseRuntimeMode", "AUTO");
                renderer.setParameter("autohardware", true);
                renderer.setParameter("autoworkers", true);
                renderer.setParameter("autotilesize", true);
                renderer.setParameter("autoschedulingtargetms", (frame & 1) == 0 ? 18.0 : 22.0);

                transientEntity = mutateStressScene(scene, transientEntity, frame);
                animateCamera(camera, frame);

                long frameStart = System.nanoTime();
                renderer.render(scene, camera, fb, frame * 0.016);
                double frameMs = (System.nanoTime() - frameStart) / 1_000_000.0;
                if (!Double.isFinite(frameMs) || frameMs <= 0.0 || frameMs > 2500.0) {
                    throw new AssertionError("Renderer switch churn produced invalid frame time: " + frameMs + "ms");
                }

                HardwareTelemetrySampler.Sample sample = HardwareTelemetrySampler.sample();
                collectHeapRatio(heapRatios, sample.usedHeapBytes(), sample.maxHeapBytes());
                peakThreads = Math.max(peakThreads, sample.liveThreadCount());
            }
        } finally {
            if (transientEntity != null) {
                scene.removeEntity(transientEntity);
            }
            ray.setParameter("shutdown", true);
            path.setParameter("shutdown", true);
        }

        HardwareTelemetrySampler.Sample endSample = waitForThreadSettle(baselineThreads + 4, 2000L);
        double endHeapRatio = heapRatio(endSample.usedHeapBytes(), endSample.maxHeapBytes());
        double maxHeapRatio = maxOrNaN(heapRatios);
        int peakDelta = peakThreads - baselineThreads;
        int settledDelta = endSample.liveThreadCount() - baselineThreads;
        int allowedPeakDelta = Math.max(48, Runtime.getRuntime().availableProcessors() * 3);

        if (peakDelta > allowedPeakDelta) {
            throw new AssertionError("Renderer switch churn spawned too many extra worker threads: +" + peakDelta);
        }
        if (settledDelta > 4) {
            throw new AssertionError("Worker threads did not settle back after shutdown: +" + settledDelta);
        }
        if (Double.isFinite(maxHeapRatio) && maxHeapRatio > 0.82) {
            throw new AssertionError("Renderer switch churn pushed heap usage too high: " + (maxHeapRatio * 100.0) + "%");
        }
        if (Double.isFinite(startHeapRatio)
                && Double.isFinite(endHeapRatio)
                && endHeapRatio > Math.max(0.72, startHeapRatio + 0.18)) {
            throw new AssertionError("Heap usage stayed elevated after shutdown: start=" + startHeapRatio + " end=" + endHeapRatio);
        }

        System.out.println(String.format(
                "SwitchChurn meanHeap=%s peakHeap=%s peakThreads=%d settledThreads=%d",
                Double.isFinite(averageOrNaN(heapRatios)) ? String.format("%.1f%%", averageOrNaN(heapRatios) * 100.0) : "n/a",
                Double.isFinite(maxHeapRatio) ? String.format("%.1f%%", maxHeapRatio * 100.0) : "n/a",
                peakThreads,
                endSample.liveThreadCount()));
    }

    private static void testPhongRayPathMutationChurnStaysBounded() {
        Scene scene = buildStressScene();
        PerspectiveCamera camera = buildCamera(16.0 / 9.0);
        RasterRenderer phong = new RasterRenderer();
        RayTracerRenderer ray = new RayTracerRenderer();
        PathTracerRenderer path = new PathTracerRenderer();
        int width = 160;
        int height = 90;
        FrameBuffer fb = new FrameBuffer(width, height);
        phong.init(width, height);
        ray.init(width, height);
        path.init(width, height);

        List<ImportedTemplate> importedTemplates = loadImportedTemplates();
        List<Entity> rollingEntities = new ArrayList<>();
        List<Double> frameSamplesMs = new ArrayList<>();
        List<Double> heapRatios = new ArrayList<>();
        HardwareTelemetrySampler.Sample startSample = HardwareTelemetrySampler.sample();
        int baselineThreads = Math.max(1, startSample.liveThreadCount());
        int peakThreads = baselineThreads;
        int peakEntityCount = scene.getAllMeshEntities().size();
        int frames = 18;

        try {
            for (int frame = 0; frame < frames; frame++) {
                Renderer renderer;
                switch (frame % 3) {
                    case 0 -> renderer = phong;
                    case 1 -> renderer = ray;
                    default -> renderer = path;
                }
                configureMutationRenderer(renderer, frame);
                mutateInteractiveScene(scene, rollingEntities, importedTemplates, frame);
                animateAggressiveCamera(camera, frame);

                long frameStart = System.nanoTime();
                renderer.render(scene, camera, fb, frame * 0.016);
                double frameMs = (System.nanoTime() - frameStart) / 1_000_000.0;
                if (!Double.isFinite(frameMs) || frameMs <= 0.0 || frameMs > 3000.0) {
                    throw new AssertionError("Phong/Ray/Path mutation churn produced invalid frame time: " + frameMs + "ms");
                }
                frameSamplesMs.add(frameMs);
                peakEntityCount = Math.max(peakEntityCount, scene.getAllMeshEntities().size());

                HardwareTelemetrySampler.Sample sample = HardwareTelemetrySampler.sample();
                collectHeapRatio(heapRatios, sample.usedHeapBytes(), sample.maxHeapBytes());
                peakThreads = Math.max(peakThreads, sample.liveThreadCount());
            }
        } finally {
            for (Entity entity : rollingEntities) {
                scene.removeEntity(entity);
            }
            phong.setParameter("shutdown", true);
            ray.setParameter("shutdown", true);
            path.setParameter("shutdown", true);
        }

        HardwareTelemetrySampler.Sample endSample = waitForThreadSettle(baselineThreads + 4, 2500L);
        double meanHeapRatio = averageOrNaN(heapRatios);
        double maxHeapRatio = maxOrNaN(heapRatios);
        double meanFrameMs = averageOrNaN(frameSamplesMs);
        double p95FrameMs = percentile(frameSamplesMs, 0.95);
        int peakDelta = peakThreads - baselineThreads;
        int settledDelta = endSample.liveThreadCount() - baselineThreads;
        int allowedPeakDelta = Math.max(56, Runtime.getRuntime().availableProcessors() * 3);

        if (peakEntityCount < 40) {
            throw new AssertionError("Mutation churn did not reach the intended scene density: peakEntities=" + peakEntityCount);
        }
        if (peakDelta > allowedPeakDelta) {
            throw new AssertionError("Phong/Ray/Path mutation churn spawned too many extra worker threads: +" + peakDelta);
        }
        if (settledDelta > 4) {
            throw new AssertionError("Phong/Ray/Path mutation churn worker threads did not settle back after shutdown: +" + settledDelta);
        }
        if (Double.isFinite(maxHeapRatio) && maxHeapRatio > 0.84) {
            throw new AssertionError("Phong/Ray/Path mutation churn pushed heap usage too high: " + (maxHeapRatio * 100.0) + "%");
        }
        if (!Double.isFinite(meanFrameMs) || !Double.isFinite(p95FrameMs)) {
            throw new AssertionError("Phong/Ray/Path mutation churn did not produce valid timing statistics.");
        }

        System.out.println(String.format(
                "MutationChurn[phong-ray-path] frames=%d peakEntities=%d importedTemplates=%d mean=%.2fms p95=%.2fms meanHeap=%s peakHeap=%s peakThreads=%d settledThreads=%d",
                frameSamplesMs.size(),
                peakEntityCount,
                importedTemplates.size(),
                meanFrameMs,
                p95FrameMs,
                Double.isFinite(meanHeapRatio) ? String.format("%.1f%%", meanHeapRatio * 100.0) : "n/a",
                Double.isFinite(maxHeapRatio) ? String.format("%.1f%%", maxHeapRatio * 100.0) : "n/a",
                peakThreads,
                endSample.liveThreadCount()));
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
        if (Double.isFinite(stats.meanProcessCpuLoad) && stats.meanProcessCpuLoad < 0.002) {
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

    private static double maxOrNaN(List<Double> values) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        double max = Double.NEGATIVE_INFINITY;
        for (double value : values) {
            if (Double.isFinite(value)) {
                max = Math.max(max, value);
            }
        }
        return Double.isFinite(max) ? max : Double.NaN;
    }

    private static double percentile(List<Double> values, double quantile) {
        if (values.isEmpty()) {
            return Double.NaN;
        }
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = Math.min(sorted.size() - 1,
                Math.max(0, (int) Math.ceil(sorted.size() * Math.max(0.0, Math.min(1.0, quantile))) - 1));
        return sorted.get(index);
    }

    private static double heapRatio(long usedHeapBytes, long maxHeapBytes) {
        if (usedHeapBytes < 0L || maxHeapBytes <= 0L) {
            return Double.NaN;
        }
        double ratio = usedHeapBytes / (double) maxHeapBytes;
        return Double.isFinite(ratio) && ratio >= 0.0 ? Math.min(1.0, ratio) : Double.NaN;
    }

    private static HardwareTelemetrySampler.Sample waitForThreadSettle(int maxThreadCount, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        HardwareTelemetrySampler.Sample latest = HardwareTelemetrySampler.sample();
        while (System.nanoTime() < deadline) {
            System.gc();
            sleepQuietly(60L);
            latest = HardwareTelemetrySampler.sample();
            if (latest.liveThreadCount() <= maxThreadCount) {
                return latest;
            }
        }
        return latest;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void configureMutationRenderer(Renderer renderer, int frameIndex) {
        int workerBudget = 1 + (frameIndex % Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
        if (renderer instanceof RasterRenderer raster) {
            raster.setParameter("materialProfile", "PHONG");
            raster.setParameter("backfaceCulling", true);
            raster.setParameter("frustumCulling", true);
            raster.setParameter("parallel", true);
            raster.setParameter("workerCount", workerBudget);
            raster.setParameter("tileSize", 24);
            return;
        }
        renderer.setParameter("workerCount", workerBudget);
        renderer.setParameter("tileSize", 16 + (frameIndex % 4) * 8);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", (frameIndex & 1) == 0 ? "FAST" : "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", (frameIndex % 3) == 0 ? "AUTO" : "FULL_FRAME");
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", true);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("autoschedulingtargetms", (frameIndex % 3) == 1 ? 18.0 : 24.0);
    }

    private static Entity mutateStressScene(Scene scene, Entity previousTransient, int frameIndex) {
        if (previousTransient != null) {
            scene.removeEntity(previousTransient);
        }

        int movableIndex = 0;
        for (Entity entity : scene.getAllMeshEntities()) {
            if (entity == null || "floor".equals(entity.getName())) {
                continue;
            }
            double angle = frameIndex * 0.18 + movableIndex * 0.41;
            double radius = 2.1 + (movableIndex % 3) * 0.32;
            double y = -0.35 + (movableIndex % 4) * 0.32 + Math.sin(angle * 0.7) * 0.08;
            entity.getTransform().setPosition(new Vec3(Math.cos(angle) * radius, y, Math.sin(angle) * radius));
            if (entity.getMaterial() instanceof PhongMaterial material) {
                material.setRoughness(Math.max(0.04, Math.min(0.94, 0.16 + 0.07 * (movableIndex % 5) + 0.05 * Math.abs(Math.sin(angle)))));
                material.setMetallic((movableIndex % 2) == 0 ? 0.18 : 0.0);
                material.setTransmission((movableIndex % 3) == 0 ? 0.28 : 0.0);
                material.setDensity((movableIndex % 4) == 0 ? 0.04 : 0.0);
                material.setEmissionStrength((movableIndex % 5) == 0 ? 0.35 : 0.0);
            }
            movableIndex++;
        }

        scene.setEnvironmentStrength(0.85 + 0.25 * Math.abs(Math.sin(frameIndex * 0.23)));
        scene.setEnvironmentYawDegrees(frameIndex * 11.0);
        scene.setBackgroundColor(new Vec3(
                0.02 + 0.01 * (frameIndex % 3),
                0.03 + 0.015 * ((frameIndex + 1) % 3),
                0.05 + 0.02 * ((frameIndex + 2) % 3)));

        Entity transientEntity = new Entity(
                "transient-" + frameIndex,
                switch (frameIndex % 4) {
                    case 0 -> MeshGenerator.torus(0.55, 0.16, 28, 12);
                    case 1 -> MeshGenerator.cylinder(0.34, 1.1, 20, 1);
                    case 2 -> MeshGenerator.cone(0.48, 1.2, 20);
                    default -> MeshGenerator.cube(0.85);
                },
                transientMaterial(frameIndex));
        transientEntity.getTransform().setPosition(new Vec3(-1.2 + frameIndex * 0.24, 0.4, -1.5 + 0.3 * Math.sin(frameIndex * 0.5)));
        scene.addEntity(transientEntity);
        scene.update(0.016);
        return transientEntity;
    }

    private static void mutateInteractiveScene(Scene scene,
                                               List<Entity> rollingEntities,
                                               List<ImportedTemplate> importedTemplates,
                                               int frameIndex) {
        int movableIndex = 0;
        for (Entity entity : scene.getAllMeshEntities()) {
            if (entity == null || "floor".equals(entity.getName())) {
                continue;
            }
            double angle = frameIndex * 0.31 + movableIndex * 0.22;
            double radius = 1.8 + (movableIndex % 7) * 0.34;
            double y = -0.55 + (movableIndex % 5) * 0.30 + Math.cos(angle * 0.8) * 0.12;
            entity.getTransform().setPosition(new Vec3(
                    Math.cos(angle) * radius,
                    y,
                    Math.sin(angle * 1.15) * radius));
            entity.getTransform().setEulerAngles(angle * 0.18, angle * 0.55, angle * 0.09);
            if (((movableIndex + frameIndex) % 3) == 0) {
                entity.setMaterial(complexStressMaterial(frameIndex * 31 + movableIndex));
            }
            movableIndex++;
        }

        while (rollingEntities.size() > 42) {
            Entity removed = rollingEntities.remove(0);
            scene.removeEntity(removed);
        }

        for (int slot = 0; slot < 3; slot++) {
            Entity entity = !importedTemplates.isEmpty() && ((frameIndex + slot) % 4) == 0
                    ? createImportedStressEntity(importedTemplates, frameIndex, slot)
                    : createPrimitiveStressEntity(frameIndex, slot);
            rollingEntities.add(entity);
            scene.addEntity(entity);
        }

        scene.setEnvironmentStrength(0.92 + 0.36 * Math.abs(Math.sin(frameIndex * 0.24)));
        scene.setEnvironmentYawDegrees(frameIndex * 17.0);
        scene.setEnvironmentPitchDegrees(Math.sin(frameIndex * 0.17) * 12.0);
        scene.setBackgroundColor(new Vec3(
                0.02 + 0.02 * ((frameIndex + 1) % 4),
                0.03 + 0.02 * ((frameIndex + 2) % 4),
                0.05 + 0.025 * ((frameIndex + 3) % 4)));
        scene.update(0.016);
    }

    private static PhongMaterial transientMaterial(int frameIndex) {
        PhongMaterial material = new PhongMaterial(
                new Vec3(
                        0.32 + 0.08 * (frameIndex % 4),
                        0.24 + 0.06 * ((frameIndex + 1) % 4),
                        0.20 + 0.09 * ((frameIndex + 2) % 4)),
                48.0);
        material.setRoughness(0.10 + 0.08 * (frameIndex % 4));
        material.setMetallic((frameIndex & 1) == 0 ? 0.22 : 0.0);
        material.setTransmission((frameIndex % 3) == 0 ? 0.36 : 0.0);
        material.setDensity((frameIndex % 3) == 0 ? 0.03 : 0.0);
        material.setThickness(0.18 + 0.04 * (frameIndex % 3));
        material.setEmissionStrength((frameIndex % 4) == 0 ? 0.45 : 0.0);
        return material;
    }

    private static Entity createPrimitiveStressEntity(int frameIndex, int slot) {
        int seed = frameIndex * 17 + slot * 13;
        Entity entity = new Entity(
                "rapid-" + frameIndex + "-" + slot,
                switch ((frameIndex + slot) % 7) {
                    case 0 -> MeshGenerator.torusKnot(0.58, 0.18, 0.12, 132, 14, 2, 3);
                    case 1 -> MeshGenerator.crystal(0.68, 1.3, 6);
                    case 2 -> MeshGenerator.capsule(0.34, 1.05, 9, 18);
                    case 3 -> MeshGenerator.torus(0.52, 0.17, 28, 12);
                    case 4 -> MeshGenerator.cylinder(0.32, 1.15, 18, 1);
                    case 5 -> MeshGenerator.cone(0.42, 1.18, 18);
                    default -> MeshGenerator.cube(0.88);
                },
                complexStressMaterial(seed));
        double angle = frameIndex * 0.43 + slot * 1.07;
        entity.getTransform().setPosition(new Vec3(
                Math.cos(angle) * (2.1 + slot * 0.45),
                -0.30 + slot * 0.42,
                Math.sin(angle) * (2.0 + slot * 0.38)));
        entity.getTransform().setEulerAngles(angle * 0.25, angle * 0.65, 0.0);
        return entity;
    }

    private static Entity createImportedStressEntity(List<ImportedTemplate> importedTemplates, int frameIndex, int slot) {
        ImportedTemplate template = importedTemplates.get(Math.floorMod(frameIndex + slot, importedTemplates.size()));
        Entity entity = new Entity(
                "imported-rapid-" + frameIndex + "-" + slot,
                template.mesh(),
                complexStressMaterial(frameIndex * 29 + slot * 11));
        double angle = frameIndex * 0.29 + slot * 0.63;
        entity.getTransform().setPosition(template.position().mul(0.55).add(new Vec3(
                Math.cos(angle) * (1.6 + slot * 0.25),
                -0.35 + slot * 0.36,
                Math.sin(angle) * (1.5 + slot * 0.22))));
        entity.getTransform().setRotation(template.rotation());
        entity.getTransform().setScale(template.scale().mul(0.72 + slot * 0.08));
        return entity;
    }

    private static PhongMaterial complexStressMaterial(int seed) {
        int r = 70 + Math.floorMod(seed * 29, 150);
        int g = 60 + Math.floorMod(seed * 17, 160);
        int b = 50 + Math.floorMod(seed * 11, 170);
        PhongMaterial material = new PhongMaterial(new Vec3(r / 255.0, g / 255.0, b / 255.0), 36.0 + Math.floorMod(seed, 5) * 14.0);
        material.setSpecularColor(new Vec3(0.85, 0.87, 0.90));
        material.setSpecularFactor(0.65 + Math.floorMod(seed, 4) * 0.08);
        material.setReflectivity(0.10 + Math.floorMod(seed, 5) * 0.08);
        material.setRefractiveIndex(1.12 + Math.floorMod(seed, 4) * 0.12);
        material.setRoughness(0.06 + Math.floorMod(seed, 6) * 0.10);
        material.setMetallic((seed & 1) == 0 ? 0.22 + Math.floorMod(seed, 3) * 0.16 : 0.0);
        material.setTransmission(Math.floorMod(seed, 3) == 0 ? 0.22 + Math.floorMod(seed, 4) * 0.10 : 0.0);
        material.setDensity(Math.floorMod(seed, 4) == 0 ? 0.02 + Math.floorMod(seed, 3) * 0.03 : 0.0);
        material.setThickness(0.18 + Math.floorMod(seed, 5) * 0.05);
        material.setEmissionColor(new Vec3(0.85, 0.48 + Math.floorMod(seed, 4) * 0.08, 0.18 + Math.floorMod(seed, 5) * 0.06));
        material.setEmissionStrength(Math.floorMod(seed, 5) == 0 ? 0.45 + Math.floorMod(seed, 3) * 0.30 : 0.0);
        material.setClearcoatFactor(0.12 + Math.floorMod(seed, 4) * 0.16);
        material.setClearcoatRoughness(0.04 + Math.floorMod(seed, 3) * 0.08);
        material.setSheenColor(new Vec3(0.08 + Math.floorMod(seed, 3) * 0.05, 0.06, 0.04));
        material.setSheenRoughness(0.18 + Math.floorMod(seed, 4) * 0.12);
        material.setDoubleSided((seed & 1) == 0);
        material.setAlphaMode(Math.floorMod(seed, 4) == 0 ? PhongMaterial.AlphaMode.BLEND : PhongMaterial.AlphaMode.OPAQUE);
        material.setOpacity(Math.floorMod(seed, 4) == 0 ? 0.72 : 1.0);
        material.setDiffuseTexture(new Texture(1, 1, new int[]{argb(255, r, g, b)}));
        material.setNormalTexture(new Texture(1, 1, new int[]{0xFF8080FF}));
        material.setNormalScale(0.45 + Math.floorMod(seed, 4) * 0.20);
        material.setMetallicRoughnessTexture(new Texture(1, 1, new int[]{argb(255, 40 + Math.floorMod(seed, 90), 80 + Math.floorMod(seed * 3, 120), 140 + Math.floorMod(seed * 5, 100))}));
        material.setEmissiveTexture(new Texture(1, 1, new int[]{argb(255, 90 + Math.floorMod(seed * 7, 120), 40 + Math.floorMod(seed * 5, 80), 20 + Math.floorMod(seed * 3, 50))}));
        material.setTextureFilteringLinear(true);
        if ((seed & 1) == 0) {
            attachProceduralGraph(material, seed);
        }
        return material;
    }

    private static void attachProceduralGraph(PhongMaterial material, int seed) {
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node bsdf = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (bsdf == null) {
            return;
        }
        graph.disconnectInput(bsdf.getId(), "base_color");
        graph.disconnectInput(bsdf.getId(), "roughness");
        graph.disconnectInput(bsdf.getId(), "metallic");

        MaterialNodeGraph.Node noise = graph.addNode(MaterialNodeGraph.NodeType.NOISE_TEXTURE, 60.0, 60.0);
        noise.setNumber("scale", 6.0 + Math.floorMod(seed, 6) * 2.5);
        noise.setNumber("detail", 2.0 + Math.floorMod(seed, 5));
        noise.setNumber("roughness", 0.25 + Math.floorMod(seed, 4) * 0.15);
        noise.setEnum("coordinate_source", MaterialNodeGraph.CoordinateSource.UV0.name());

        MaterialNodeGraph.Node ramp = graph.addNode(MaterialNodeGraph.NodeType.COLOR_RAMP, 280.0, 60.0);
        ramp.setColor("color_a", new Vec3(0.06, 0.08 + Math.floorMod(seed, 3) * 0.04, 0.12));
        ramp.setColor("color_b", new Vec3(0.85, 0.42 + Math.floorMod(seed, 4) * 0.08, 0.22 + Math.floorMod(seed, 5) * 0.05));

        MaterialNodeGraph.Node value = graph.addNode(MaterialNodeGraph.NodeType.VALUE, 280.0, 180.0);
        value.setNumber("value", 0.55 + Math.floorMod(seed, 4) * 0.18);

        MaterialNodeGraph.Node math = graph.addNode(MaterialNodeGraph.NodeType.MATH, 500.0, 180.0);
        math.setEnum("operation", MaterialNodeGraph.MathOperation.MULTIPLY.name());

        graph.connect(noise.getId(), "factor", ramp.getId(), "factor");
        graph.connect(ramp.getId(), "color", bsdf.getId(), "base_color");
        graph.connect(noise.getId(), "factor", math.getId(), "a");
        graph.connect(value.getId(), "value", math.getId(), "b");
        graph.connect(math.getId(), "value", bsdf.getId(), "roughness");
        graph.connect(noise.getId(), "factor", bsdf.getId(), "metallic");
        MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
    }

    private static int argb(int a, int r, int g, int b) {
        return (clamp8(a) << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }

    private static int clamp8(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static List<ImportedTemplate> loadImportedTemplates() {
        List<ImportedTemplate> templates = new ArrayList<>();
        try {
            ImportedScene imported = new ModelImporter().importScene(EngineSceneBootstrap.STARTUP_MODEL_PATH);
            if (imported == null || imported.getEntries() == null) {
                return templates;
            }
            int index = 0;
            for (ImportedScene.Entry entry : imported.getEntries()) {
                if (entry == null || entry.getMesh() == null) {
                    continue;
                }
                PhongMaterial material = entry.getMaterial() != null ? entry.getMaterial().copy() : complexStressMaterial(300 + index);
                templates.add(new ImportedTemplate(
                        entry.getName() == null ? "imported-" + index : entry.getName(),
                        entry.getMesh(),
                        material,
                        entry.getPosition(),
                        entry.getRotation(),
                        entry.getScale()));
                index++;
                if (templates.size() >= 6) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
        }
        return templates;
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

    private static void animateAggressiveCamera(PerspectiveCamera camera, int frameIndex) {
        double angle = frameIndex * 0.41;
        double radius = 3.6 + Math.sin(frameIndex * 0.23) * 1.4;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle * 1.12) * (radius * 0.88);
        double y = 0.7 + Math.cos(frameIndex * 0.37) * 0.85;
        camera.setPosition(new Vec3(x, y, z));
        camera.lookAt(new Vec3(
                Math.sin(frameIndex * 0.19) * 0.9,
                -0.2 + Math.cos(frameIndex * 0.27) * 0.35,
                Math.cos(frameIndex * 0.21) * 0.9));
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

    private record ImportedTemplate(String name,
                                    engine.geometry.Mesh mesh,
                                    PhongMaterial material,
                                    Vec3 position,
                                    Quaternion rotation,
                                    Vec3 scale) {
    }
}
