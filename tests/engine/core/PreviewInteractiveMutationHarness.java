package engine.core;

import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPresets;
import engine.material.PhongMaterial;
import engine.math.Quaternion;
import engine.math.Vec3;
import engine.render.Texture;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.util.HardwareTelemetrySampler;
import engine.util.RuntimeInstrumentation;

public final class PreviewInteractiveMutationHarness {
    private static final long VIEWPORT_WARMUP_STABLE_NS = 1_000_000_000L;
    private static final long RT_WARMUP_ENTRY_SAMPLES = 20L;
    private static final long PT_WARMUP_ENTRY_SAMPLES = 8L;
    private static final Vec3 NAVIGATION_LOOK_TARGET = new Vec3(0.0, 0.34, 0.30);

    private PreviewInteractiveMutationHarness() {
    }

    public static void main(String[] args) throws Exception {
        RuntimeInstrumentation.setEnabled(true);
        AtomicReference<Throwable> engineFailure = new AtomicReference<>();
        Engine engine = new Engine();
        MaterialMemorySoakResult materialMemorySoakResult = null;
        Throwable pendingFailure = null;
        engine.baseWidth = 960;
        engine.baseHeight = 540;
        engine.launchFullscreen = false;
        engine.parallelWorkerCount = Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors()));

        Thread engineThread = new Thread(() -> {
            try {
                engine.start();
            } catch (Throwable ex) {
                engineFailure.set(ex);
                throw ex;
            }
        }, "preview-interactive-mutation");
        engineThread.setDaemon(true);
        engineThread.setUncaughtExceptionHandler((thread, ex) -> engineFailure.compareAndSet(null, ex));
        engineThread.start();

        try {
            waitForWindow(engine, 20_000L);
            configureRuntime(engine);
            materialMemorySoakResult = runMaterialMemorySoak(engine, engineThread, engineFailure);
        } catch (Throwable ex) {
            pendingFailure = ex;
        } finally {
            tryRunOnEngineThread(engine, () -> releaseSyntheticMotion(engine));
            engine.shutdown();
            engineThread.join(20_000L);
            RuntimeInstrumentation.setEnabled(false);
        }

        if (pendingFailure == null && materialMemorySoakResult != null) {
            HardwareTelemetrySampler.Sample postShutdown = waitForThreadSettle(materialMemorySoakResult.baselineThreads() + 20, 10_000L);
            int postShutdownThreads = postShutdown.liveThreadCount();
            List<String> lingeringWorkers = captureLiveThreadNamesMatching(
                    "RenderWorker-",
                    "preview-interactive-mutation",
                    "scene-import-worker",
                    "output-render-worker");
            if (!lingeringWorkers.isEmpty()) {
                throw new AssertionError("Interactive mutation soak left runtime worker threads alive after shutdown: "
                        + joinThreadNames(lingeringWorkers, 10));
            }
            if (postShutdownThreads > materialMemorySoakResult.baselineThreads() + 20) {
                throw new AssertionError("Interactive mutation soak left suspiciously high thread count after shutdown: baseline="
                        + materialMemorySoakResult.baselineThreads() + " postShutdown=" + postShutdownThreads);
            }

            System.out.println(String.format(
                    Locale.ROOT,
                    "MaterialMemorySoak mode_profile=model-basic-phong-ray-path blocks=%d mode_switches=%d dwell_ms=%d core_entities=%d ui_spawned=%d peak_entities=%d scenario_lights=%d camera_travel=%.2f mean_frame=%.2fms p95_frame=%.2fms live_mean_heap=%s live_peak_heap=%s retained_start=%s retained_peak=%s retained_end=%s retained_delta=%s gc_delta_ms=%d peak_threads=%d post_shutdown_threads=%d safety_recovery_hits=%d fallback_lock_hits=%d lingering_runtime_threads=%s",
                    materialMemorySoakResult.blocks(),
                    materialMemorySoakResult.modeSwitches(),
                    materialMemorySoakResult.dwellMillis(),
                    materialMemorySoakResult.coreEntityCount(),
                    materialMemorySoakResult.uiSpawnCount(),
                    materialMemorySoakResult.peakEntities(),
                    materialMemorySoakResult.scenarioLightCount(),
                    materialMemorySoakResult.cameraTravelDistance(),
                    materialMemorySoakResult.meanFrameMs(),
                    materialMemorySoakResult.p95FrameMs(),
                    formatPercent(materialMemorySoakResult.liveMeanHeapRatio()),
                    formatPercent(materialMemorySoakResult.livePeakHeapRatio()),
                    formatBytes(materialMemorySoakResult.retainedStartBytes()),
                    formatBytes(materialMemorySoakResult.retainedPeakBytes()),
                    formatBytes(materialMemorySoakResult.retainedEndBytes()),
                    formatSignedBytes(materialMemorySoakResult.retainedDeltaBytes()),
                    materialMemorySoakResult.gcDeltaMillis(),
                    materialMemorySoakResult.peakThreads(),
                    postShutdownThreads,
                    materialMemorySoakResult.safetyRecoveryHits(),
                    materialMemorySoakResult.fallbackLockHits(),
                    lingeringWorkers.isEmpty() ? "none" : joinThreadNames(lingeringWorkers, 10)));
        }

        if (pendingFailure != null) {
            if (pendingFailure instanceof Exception exception) {
                throw exception;
            }
            if (pendingFailure instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(pendingFailure);
        }
    }

    private static void configureRuntime(Engine engine) throws InterruptedException {
        try {
            stabilizeViewportWindow(engine);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to stabilize viewport window before interactive soak.", ex);
        }
        long workerBudget = Math.min(6, Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
        runOnEngineThread(engine, () -> {
            engine.parallelWorkerCount = (int) workerBudget;
            engine.raySamplesPerFrame = 1;
            engine.rayTileSize = 24;
            engine.rayDenoise = true;
            engine.rayDenoiseRadius = 2;
            engine.rayDenoiseStrength = 0.56;
            engine.pathSamplesPerFrame = 1;
            engine.pathTileSize = 24;
            engine.pathDenoise = true;
            engine.pathDenoiseRadius = 2;
            engine.pathDenoiseStrength = 0.58;
            engine.applyRaySettings();
            engine.applyPathSettings();
            if (engine.rasterRenderer != null) {
                engine.rasterRenderer.setParameter("parallel", true);
                engine.rasterRenderer.setParameter("workerCount", (int) workerBudget);
                engine.rasterRenderer.setParameter("materialProfile", "PHONG");
            }
        });
        waitForStableRuntime(engine, 6_000L);
    }

    private static void stabilizeViewportWindow(Engine engine) throws Exception {
        if (engine == null || engine.window == null) {
            return;
        }
        EventQueue.invokeAndWait(() -> {
            if (engine.window == null) {
                return;
            }
            engine.window.getFrame().setResizable(true);
            engine.window.getFrame().setExtendedState(java.awt.Frame.MAXIMIZED_BOTH);
            engine.window.getFrame().setLocationRelativeTo(null);
            engine.window.getFrame().validate();
            engine.window.getFrame().toFront();
        });
        Thread.sleep(800L);
    }

    @SuppressWarnings("unused")
    private static SoakResult runMutationSoak(Engine engine,
                                              Thread engineThread,
                                              AtomicReference<Throwable> engineFailure) throws Exception {
        List<ImportedTemplate> importedTemplates = loadImportedTemplates();
        Deque<Entity> rollingEntities = new ArrayDeque<>();
        List<Double> heapRatios = new ArrayList<>();
        List<Double> frameTimesMs = new ArrayList<>();
        HardwareTelemetrySampler.Sample startSample = HardwareTelemetrySampler.sample();
        int baselineThreads = Math.max(1, startSample.liveThreadCount());
        int peakThreads = baselineThreads;
        int peakEntities = callOnEngineThread(engine, () -> engine.scene.getAllMeshEntities().size());
        int modeSwitches = 0;
        int safetyRecoveryHits = 0;
        int fallbackLockHits = 0;
        int iterations = 60;
        long stepSleepMs = 140L;

        for (int step = 0; step < iterations; step++) {
            ensureEngineHealthy(engineThread, engineFailure);

            RenderMode mode = switch (step % 3) {
                case 0 -> RenderMode.PHONG;
                case 1 -> RenderMode.RAY_TRACING;
                default -> RenderMode.PATH_TRACING;
            };
            runOnEngineThread(engine, () -> engine.setRenderMode(mode));
            modeSwitches++;
            waitForDisplayedMode(engine, mode, 4_000L);

            final int currentStep = step;
            runOnEngineThread(engine, () -> {
                applySyntheticMotion(engine, mode, true);
                mutateLiveScene(engine, rollingEntities, importedTemplates, currentStep);
                animateCamera(engine, currentStep);
            });

            Thread.sleep(stepSleepMs);

            ensureEngineHealthy(engineThread, engineFailure);
            peakEntities = Math.max(peakEntities, callOnEngineThread(engine, () -> engine.scene.getAllMeshEntities().size()));
            if (engine.safetyRecoveryActive) {
                safetyRecoveryHits++;
            }
            if (engine.viewportFallbackLockActive) {
                fallbackLockHits++;
            }

            HardwareTelemetrySampler.Sample sample = HardwareTelemetrySampler.sample();
            peakThreads = Math.max(peakThreads, sample.liveThreadCount());
            collectHeapRatio(heapRatios, sample.usedHeapBytes(), sample.maxHeapBytes());
            if (Double.isFinite(engine.hudFrameTimeMs) && engine.hudFrameTimeMs > 0.0) {
                frameTimesMs.add(engine.hudFrameTimeMs);
            }
        }

        runOnEngineThread(engine, () -> releaseSyntheticMotion(engine));
        Thread.sleep(800L);

        HardwareTelemetrySampler.Sample endSample = HardwareTelemetrySampler.sample();
        double meanHeap = averageOrNaN(heapRatios);
        double peakHeap = maxOrNaN(heapRatios);
        double meanFrame = averageOrNaN(frameTimesMs);
        double p95Frame = percentile(frameTimesMs, 0.95);
        int preShutdownThreads = endSample.liveThreadCount();

        if (peakEntities < 60) {
            throw new AssertionError("Interactive mutation soak did not reach the intended scene density: peakEntities=" + peakEntities);
        }
        if (Double.isFinite(peakHeap) && peakHeap > 0.78) {
            throw new AssertionError("Interactive mutation soak pushed heap usage too high: " + (peakHeap * 100.0) + "%");
        }

        return new SoakResult(
                baselineThreads,
                iterations,
                modeSwitches,
                peakEntities,
                importedTemplates.size(),
                meanFrame,
                p95Frame,
                meanHeap,
                peakHeap,
                peakThreads,
                preShutdownThreads,
                safetyRecoveryHits,
                fallbackLockHits);
    }

    private static MaterialMemorySoakResult runMaterialMemorySoak(Engine engine,
                                                                  Thread engineThread,
                                                                  AtomicReference<Throwable> engineFailure) throws Exception {
        MaterialScenarioState state = new MaterialScenarioState();
        List<ImportedTemplate> importedTemplates = loadImportedTemplates();
        runOnEngineThread(engine, () -> setupMaterialMemoryScenario(engine, state, importedTemplates));
        waitForStableRuntime(engine, 4_000L);

        HardwareTelemetrySampler.Sample retainedStart = captureRetainedHeapSample(3, 140L);
        List<Double> liveHeapRatios = new ArrayList<>();
        List<Double> frameTimesMs = new ArrayList<>();
        long retainedPeakBytes = retainedStart.usedHeapBytes();
        double cameraTravelDistance = 0.0;
        int peakThreads = retainedStart.liveThreadCount();
        int peakEntities = callOnEngineThread(engine, () -> engine.scene.getAllMeshEntities().size());
        int coreEntityCount = state.coreEntities.size();
        int scenarioLightCount = state.lights.size();
        int baselineThreads = Math.max(1, retainedStart.liveThreadCount());
        int uiSpawnCount = 0;
        int modeSwitches = 0;
        int safetyRecoveryHits = 0;
        int fallbackLockHits = 0;
        long gcStartMillis = retainedStart.gcTimeMillis();
        RenderMode[] modes = {
                RenderMode.MODEL,
                RenderMode.BASIC,
                RenderMode.PHONG,
                RenderMode.RAY_TRACING,
                RenderMode.PATH_TRACING
        };
        int rounds = 1;
        long dwellMillis = 10_000L;
        long tickSleepMillis = 100L;
        long[] spawnScheduleMillis = {1_500L, 4_800L, 8_000L};
        int tick = 0;

        for (int round = 0; round < rounds; round++) {
            for (RenderMode mode : modes) {
                ensureEngineHealthy(engineThread, engineFailure);
                runOnEngineThread(engine, () -> releaseSyntheticMotion(engine));
                sleepQuietly(180L);
                runOnEngineThread(engine, () -> engine.setRenderMode(mode));
                modeSwitches++;
                waitForDisplayedMode(engine, mode, 4_000L);
                WarmupSnapshot warmup = waitForViewportWarmup(engine, mode, warmupTimeoutMillis(mode));
                System.out.println(String.format(
                        Locale.ROOT,
                        "Warmup[%s] wait_ms=%d samples=%d quality=%s frame_ms=%.2f fallback=%s critical=%s warmup_capture=%s switch_overlay=%s",
                        mode,
                        warmup.waitMillis(),
                        warmup.accumulatedSamples(),
                        warmup.qualityTier(),
                        warmup.frameTimeMs(),
                        warmup.fallbackLockActive(),
                        warmup.criticalPreviewActive(),
                        warmup.warmupCaptureActive(),
                        warmup.switchOverlayActive()));
                state.currentModeUiEntities.clear();
                int createdThisMode = 0;
                Vec3 previousCameraPos = callOnEngineThread(engine, () -> engine.camera == null ? Vec3.ZERO : engine.camera.getPosition());

                long modeStartNanos = System.nanoTime();
                long deadline = modeStartNanos + dwellMillis * 1_000_000L;
                long completionDeadline = deadline + 5_000_000_000L;
                while ((System.nanoTime() < deadline || createdThisMode < spawnScheduleMillis.length)
                        && System.nanoTime() < completionDeadline) {
                    ensureEngineHealthy(engineThread, engineFailure);
                    final int currentTick = tick++;
                    long elapsedMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - modeStartNanos));
                    final int spawnIndex = createdThisMode < spawnScheduleMillis.length && elapsedMillis >= spawnScheduleMillis[createdThisMode]
                            ? createdThisMode
                            : -1;
                    if (spawnIndex >= 0) {
                        createdThisMode++;
                    }
                    runOnEngineThread(engine, () -> {
                        applySyntheticMotion(engine, mode, true);
                        mutateMaterialMemoryScene(engine, state, currentTick, mode, spawnIndex);
                    });
                    if (spawnIndex >= 0) {
                        uiSpawnCount++;
                    }

                    sleepQuietly(tickSleepMillis);

                    HardwareTelemetrySampler.Sample sample = HardwareTelemetrySampler.sample();
                    peakThreads = Math.max(peakThreads, sample.liveThreadCount());
                    collectHeapRatio(liveHeapRatios, sample.usedHeapBytes(), sample.maxHeapBytes());
                    if (Double.isFinite(engine.hudFrameTimeMs) && engine.hudFrameTimeMs > 0.0) {
                        frameTimesMs.add(engine.hudFrameTimeMs);
                    }
                    Vec3 currentCameraPos = callOnEngineThread(engine, () -> engine.camera == null ? Vec3.ZERO : engine.camera.getPosition());
                    cameraTravelDistance += currentCameraPos.sub(previousCameraPos).length();
                    previousCameraPos = currentCameraPos;
                    peakEntities = Math.max(peakEntities, callOnEngineThread(engine, () -> engine.scene.getAllMeshEntities().size()));
                    if (engine.safetyRecoveryActive) {
                        safetyRecoveryHits++;
                    }
                    if (engine.viewportFallbackLockActive) {
                        fallbackLockHits++;
                    }
                }
                if (createdThisMode < spawnScheduleMillis.length) {
                    throw new AssertionError("Material memory soak did not complete all required UI spawns in mode "
                            + mode + ": created=" + createdThisMode);
                }

                HardwareTelemetrySampler.Sample retainedDuringRun = captureRetainedHeapSample(1, 120L);
                retainedPeakBytes = Math.max(retainedPeakBytes, retainedDuringRun.usedHeapBytes());
            }
        }

        runOnEngineThread(engine, () -> {
            releaseSyntheticMotion(engine);
            cleanupMaterialMemoryScenario(engine, state);
        });
        sleepQuietly(400L);

        HardwareTelemetrySampler.Sample retainedEnd = captureRetainedHeapSample(4, 140L);
        retainedPeakBytes = Math.max(retainedPeakBytes, retainedEnd.usedHeapBytes());
        long retainedDeltaBytes = Math.max(Long.MIN_VALUE + 1, retainedEnd.usedHeapBytes() - retainedStart.usedHeapBytes());
        double liveMeanHeap = averageOrNaN(liveHeapRatios);
        double livePeakHeap = maxOrNaN(liveHeapRatios);
        double meanFrame = averageOrNaN(frameTimesMs);
        double p95Frame = percentile(frameTimesMs, 0.95);
        long gcDeltaMillis = Math.max(0L, retainedEnd.gcTimeMillis() - gcStartMillis);

        if (coreEntityCount != 5) {
            throw new AssertionError("Material memory soak did not create the expected 5 core entities.");
        }
        if (peakEntities < 18) {
            throw new AssertionError("Material memory soak did not create enough live scene churn: peakEntities=" + peakEntities);
        }
        if (Double.isFinite(livePeakHeap) && livePeakHeap > 0.78) {
            throw new AssertionError("Material memory soak pushed live heap usage too high: " + (livePeakHeap * 100.0) + "%");
        }
        double retainedDeltaRatio = retainedEnd.maxHeapBytes() > 0L
                ? retainedDeltaBytes / (double) retainedEnd.maxHeapBytes()
                : Double.NaN;
        long suspiciousDeltaBytes = 192L * 1024L * 1024L;
        if (retainedDeltaBytes > suspiciousDeltaBytes
                && Double.isFinite(retainedDeltaRatio)
                && retainedDeltaRatio > 0.08) {
            throw new AssertionError("Material memory soak retained too much heap after cleanup: delta="
                    + formatSignedBytes(retainedDeltaBytes));
        }

        return new MaterialMemorySoakResult(
                rounds * modes.length,
                modeSwitches,
                dwellMillis,
                baselineThreads,
                coreEntityCount,
                uiSpawnCount,
                peakEntities,
                scenarioLightCount,
                cameraTravelDistance,
                meanFrame,
                p95Frame,
                liveMeanHeap,
                livePeakHeap,
                retainedStart.usedHeapBytes(),
                retainedPeakBytes,
                retainedEnd.usedHeapBytes(),
                retainedDeltaBytes,
                gcDeltaMillis,
                peakThreads,
                safetyRecoveryHits,
                fallbackLockHits);
    }

    private static void waitForWindow(Engine engine, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine.running && engine.window != null && engine.window.getCanvas() != null && engine.scene != null) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("Engine window did not become ready within timeout.");
    }

    private static void waitForStableRuntime(Engine engine, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine.running
                    && engine.window != null
                    && engine.frameBuffer != null
                    && !engine.safetyRecoveryActive
                    && !engine.viewportCriticalPreviewActive) {
                return;
            }
            Thread.sleep(30L);
        }
        throw new IllegalStateException("Engine runtime did not settle into a representative preview state.");
    }

    private static void waitForDisplayedMode(Engine engine, RenderMode mode, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine.viewportDisplayedMode == mode && engine.activeMode == mode) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("Viewport mode switch did not settle: requested=" + mode
                + " displayed=" + engine.viewportDisplayedMode);
    }

    private static WarmupSnapshot waitForViewportWarmup(Engine engine, RenderMode mode, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long stableStart = 0L;
        long waitStart = System.nanoTime();
        PreviewState lastPreviewState = capturePreviewState(engine, mode);
        boolean heavyMode = mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING;
        while (System.nanoTime() < deadline) {
            lastPreviewState = capturePreviewState(engine, mode);
            boolean runtimeReady = engine.running
                    && engine.window != null
                    && engine.window.getCanvas() != null
                    && engine.frameBuffer != null
                    && engine.window.getCanvas().getWidth() > 0
                    && engine.window.getCanvas().getHeight() > 0
                    && engine.frameBuffer.getWidth() > 0
                    && engine.frameBuffer.getHeight() > 0;
            boolean displayed = engine.viewportDisplayedMode == mode
                    && engine.activeMode == mode
                    && !engine.viewportNavigationPreviewActive;
            boolean frameReady = Double.isFinite(engine.hudFrameTimeMs) && engine.hudFrameTimeMs > 0.0;
            boolean safetyReady = !engine.safetyRecoveryActive
                    && !engine.viewportCriticalPreviewActive
                    && !engine.viewportFallbackLockActive
                    && !engine.renderModeSwitchTransitionActive;
            boolean warmupSeedReady = !heavyMode || !engine.viewportWarmupCaptureActive;
            boolean samplesReady = lastPreviewState.accumulatedSamples() >= requiredWarmupSamples(engine, mode);
            boolean qualityReady = !heavyMode
                    || !lastPreviewState.qualityTier().endsWith("_UNAVAILABLE");
            if (runtimeReady && displayed && frameReady && safetyReady && warmupSeedReady && samplesReady && qualityReady) {
                if (stableStart == 0L) {
                    stableStart = System.nanoTime();
                }
                if (System.nanoTime() - stableStart >= VIEWPORT_WARMUP_STABLE_NS) {
                    long waitMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStart);
                    return new WarmupSnapshot(
                            waitMillis,
                            lastPreviewState.accumulatedSamples(),
                            lastPreviewState.qualityTier(),
                            engine.hudFrameTimeMs,
                            engine.viewportFallbackLockActive,
                            engine.viewportCriticalPreviewActive,
                            engine.viewportWarmupCaptureActive,
                            engine.renderModeSwitchTransitionActive);
                }
            } else {
                stableStart = 0L;
            }
            Thread.sleep(50L);
        }
        throw new IllegalStateException("Viewport did not warm up before dwell: mode=" + mode
                + " samples=" + lastPreviewState.accumulatedSamples()
                + " quality=" + lastPreviewState.qualityTier()
                + " frame_ms=" + engine.hudFrameTimeMs
                + " fallback=" + engine.viewportFallbackLockActive
                + " critical=" + engine.viewportCriticalPreviewActive
                + " warmup_capture=" + engine.viewportWarmupCaptureActive
                + " switch_overlay=" + engine.renderModeSwitchTransitionActive);
    }

    private static long warmupTimeoutMillis(RenderMode mode) {
        return switch (mode) {
            case RAY_TRACING -> 75_000L;
            case PATH_TRACING -> 120_000L;
            default -> 8_000L;
        };
    }

    private static long requiredWarmupSamples(Engine engine, RenderMode mode) {
        double viewportMegaPixels = 0.0;
        if (engine != null && engine.frameBuffer != null) {
            viewportMegaPixels = (engine.frameBuffer.getWidth() * engine.frameBuffer.getHeight()) / 1_000_000.0;
        }
        return switch (mode) {
            case RAY_TRACING -> viewportMegaPixels >= 2.5 ? 1L : RT_WARMUP_ENTRY_SAMPLES;
            case PATH_TRACING -> viewportMegaPixels >= 2.5 ? 1L : PT_WARMUP_ENTRY_SAMPLES;
            default -> 0L;
        };
    }

    private static PreviewState capturePreviewState(Engine engine, RenderMode mode) {
        if (engine == null || mode == null) {
            return new PreviewState(0L, "UNAVAILABLE");
        }
        return switch (mode) {
            case RAY_TRACING -> engine.rayTracerRenderer == null
                    ? new PreviewState(0L, "RT_UNAVAILABLE")
                    : new PreviewState(engine.rayTracerRenderer.getAccumulatedSamples(),
                            engine.rayTracerRenderer.getActivePreviewQualityTier());
            case PATH_TRACING -> engine.pathTracerRenderer == null
                    ? new PreviewState(0L, "PT_UNAVAILABLE")
                    : new PreviewState(engine.pathTracerRenderer.getAccumulatedSamples(),
                            engine.pathTracerRenderer.getActivePreviewQualityTier());
            default -> new PreviewState(0L, mode + "_REALTIME");
        };
    }

    private static void ensureEngineHealthy(Thread engineThread, AtomicReference<Throwable> engineFailure) {
        Throwable failure = engineFailure.get();
        if (failure != null) {
            throw new IllegalStateException("Engine thread failed during interactive soak.", failure);
        }
        if (!engineThread.isAlive()) {
            throw new IllegalStateException("Engine thread terminated unexpectedly during interactive soak.");
        }
    }

    private static void applySyntheticMotion(Engine engine, RenderMode mode, boolean active) {
        if (engine == null || engine.input == null) {
            return;
        }
        engine.draggingSelectedObject = active;
        engine.gizmoDragActive = active;
        engine.viewportCameraMotionActive = active;
        if (mode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            engine.rayTracerRenderer.setParameter("previewQualityLadder", true);
            engine.rayTracerRenderer.setParameter("previewMotionActive", active);
        } else if (mode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.setParameter("previewQualityLadder", true);
            engine.pathTracerRenderer.setParameter("previewMotionActive", active);
        }
    }

    private static void releaseSyntheticMotion(Engine engine) {
        if (engine == null || engine.input == null) {
            return;
        }
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_W, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_S, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_A, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_D, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_SPACE, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_CONTROL, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_J, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_L, false);
        engine.draggingSelectedObject = false;
        engine.gizmoDragActive = false;
        engine.viewportCameraMotionActive = false;
        if (engine.rayTracerRenderer != null) {
            engine.rayTracerRenderer.setParameter("previewMotionActive", false);
        }
        if (engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.setParameter("previewMotionActive", false);
        }
    }

    private static void setupMaterialMemoryScenario(Engine engine,
                                                    MaterialScenarioState state,
                                                    List<ImportedTemplate> importedTemplates) {
        cleanupMaterialMemoryScenario(engine, state);

        Entity glassEntity;
        if (importedTemplates != null && !importedTemplates.isEmpty()) {
            ImportedTemplate template = importedTemplates.get(0);
            glassEntity = new Entity("memory-glass-model", template.mesh(), createScenarioMaterial(MaterialScenarioProfile.GLASS, 11));
            glassEntity.getTransform().setPosition(new Vec3(-0.90, 0.14, 0.18));
            glassEntity.getTransform().setRotation(template.rotation());
            glassEntity.getTransform().setScale(template.scale().mul(0.42));
        } else {
            glassEntity = new Entity("memory-glass", MeshGenerator.sphere(0.64, 34, 26), createScenarioMaterial(MaterialScenarioProfile.GLASS, 11));
            glassEntity.getTransform().setPosition(new Vec3(-0.90, 0.18, 0.18));
        }
        ScenarioEntity glass = new ScenarioEntity(glassEntity, MaterialScenarioProfile.GLASS, 0);

        ScenarioEntity water = new ScenarioEntity(
                new Entity("memory-water", MeshGenerator.cylinder(0.54, 0.22, 28, 1), createScenarioMaterial(MaterialScenarioProfile.WATER, 23)),
                MaterialScenarioProfile.WATER,
                0);
        water.entity().getTransform().setPosition(new Vec3(-0.30, -0.16, 0.92));
        water.entity().getTransform().setScale(new Vec3(1.25, 0.40, 1.25));

        ScenarioEntity metallic = new ScenarioEntity(
                new Entity("memory-metal", MeshGenerator.torus(0.68, 0.18, 42, 20), createScenarioMaterial(MaterialScenarioProfile.METALLIC, 37)),
                MaterialScenarioProfile.METALLIC,
                0);
        metallic.entity().getTransform().setPosition(new Vec3(0.40, 0.10, -0.18));

        ScenarioEntity emissive = new ScenarioEntity(
                new Entity("memory-emissive", MeshGenerator.crystal(0.82, 1.55, 7), createScenarioMaterial(MaterialScenarioProfile.EMISSIVE, 41)),
                MaterialScenarioProfile.EMISSIVE,
                0);
        emissive.entity().getTransform().setPosition(new Vec3(0.98, 0.30, 0.58));

        ScenarioEntity coated = new ScenarioEntity(
                new Entity("memory-coated", MeshGenerator.capsule(0.34, 1.32, 10, 22), createScenarioMaterial(MaterialScenarioProfile.COATED_GLOSSY, 53)),
                MaterialScenarioProfile.COATED_GLOSSY,
                0);
        coated.entity().getTransform().setPosition(new Vec3(0.08, 0.34, 0.36));

        Collections.addAll(state.coreEntities, glass, water, metallic, emissive, coated);
        for (ScenarioEntity scenarioEntity : state.coreEntities) {
            addRuntimeEntity(engine, scenarioEntity.entity());
        }

        DirectionalLight key = new DirectionalLight(new Vec3(-0.58, -1.0, -0.34), new Vec3(1.0, 0.96, 0.92), 1.25);
        DirectionalLight fill = new DirectionalLight(new Vec3(0.36, -0.42, 0.22), new Vec3(0.34, 0.46, 0.72), 0.48);
        PointLight warm = new PointLight(new Vec3(-1.8, 1.5, 1.9), new Vec3(1.0, 0.50, 0.24), 2.7);
        warm.setAttenuation(1.0, 0.045, 0.012);
        PointLight cool = new PointLight(new Vec3(1.9, 1.7, -1.4), new Vec3(0.20, 0.68, 1.0), 2.5);
        cool.setAttenuation(1.0, 0.050, 0.014);
        ConeLight spot = new ConeLight(new Vec3(0.0, 3.0, 2.8), new Vec3(0.98, 0.86, 0.58), 3.6);
        spot.setDirection(new Vec3(0.0, -0.94, -0.22));
        spot.setConeAngleDegrees(28.0);
        spot.setSoftness(0.28);
        spot.setAttenuation(1.0, 0.025, 0.009);
        AreaLight area = new AreaLight(new Vec3(-2.2, 2.3, -1.1), new Vec3(0.46, 0.92, 0.78), 2.4);
        area.setEmissionDirection(new Vec3(0.66, -0.72, 0.16));
        area.setSpreadAngleDegrees(68.0);
        area.setAttenuation(1.0, 0.035, 0.012);

        Collections.addAll(state.lights, key, fill, warm, cool, spot, area);
        for (Light light : state.lights) {
            engine.scene.addLight(light);
        }

        engine.scene.setAmbientColor(new Vec3(0.10, 0.11, 0.13));
        engine.scene.setEnvironmentStrength(1.18);
        engine.scene.setEnvironmentYawDegrees(18.0);
        engine.scene.setEnvironmentPitchDegrees(-4.0);
        engine.scene.setBackgroundColor(new Vec3(0.025, 0.03, 0.045));
        engine.selectedEntity = glass.entity();
        engine.setNavigationPreset(Engine.NavigationPreset.FPS);
        if (!engine.mouseCaptured) {
            EngineCameraRuntime.captureMouse(engine);
        }
        if (engine.camera instanceof engine.camera.PerspectiveCamera camera) {
            camera.setPosition(new Vec3(0.0, 1.05, 3.10));
            camera.lookAt(new Vec3(0.0, 0.24, 0.28));
        }
        engine.scene.update(0.016);
    }

    private static void cleanupMaterialMemoryScenario(Engine engine, MaterialScenarioState state) {
        while (!state.allUiSpawnedEntities.isEmpty()) {
            removeRuntimeEntity(engine, state.allUiSpawnedEntities.removeFirst().entity());
        }
        state.currentModeUiEntities.clear();
        for (int i = state.coreEntities.size() - 1; i >= 0; i--) {
            removeRuntimeEntity(engine, state.coreEntities.get(i).entity());
        }
        state.coreEntities.clear();
        for (int i = state.lights.size() - 1; i >= 0; i--) {
            engine.scene.removeLight(state.lights.get(i));
        }
        state.lights.clear();
        if (engine.mouseCaptured) {
            EngineCameraRuntime.releaseMouseCapture(engine);
        }
        engine.scene.update(0.016);
    }

    private static void mutateMaterialMemoryScene(Engine engine,
                                                  MaterialScenarioState state,
                                                  int tick,
                                                  RenderMode mode,
                                                  int spawnIndex) {
        applyNavigationPattern(engine, tick, mode);
        int materialRefreshIndex = Math.floorMod(tick, Math.max(1, state.coreEntities.size()));
        for (int i = 0; i < state.coreEntities.size(); i++) {
            ScenarioEntity scenarioEntity = state.coreEntities.get(i);
            Entity entity = scenarioEntity.entity();
            double angle = tick * 0.07 + i * 1.07;
            double radius = 0.72 + i * 0.16;
            double wobble = Math.sin(tick * 0.12 + i * 0.63) * 0.12;
            entity.getTransform().setPosition(new Vec3(
                    Math.cos(angle) * radius,
                    0.05 + Math.sin(angle * 0.85) * 0.12 + wobble,
                    0.25 + Math.sin(angle * 1.04) * (0.36 + i * 0.08)));
            entity.getTransform().setEulerAngles(angle * 0.28, angle * 0.46, Math.cos(angle * 0.41) * 0.14);
            if (i == materialRefreshIndex && (tick % 2) == 0) {
                entity.setMaterial(createScenarioMaterial(scenarioEntity.profile(), tick * 29 + i * 17));
            }
        }

        if (spawnIndex >= 0) {
            ScenarioEntity spawnedEntity = spawnUiViewportPrimitive(engine, mode, spawnIndex, tick);
            if (spawnedEntity != null) {
                state.currentModeUiEntities.add(spawnedEntity);
                state.allUiSpawnedEntities.addLast(spawnedEntity);
            }
        }
        while (state.allUiSpawnedEntities.size() > 15) {
            ScenarioEntity removed = state.allUiSpawnedEntities.removeFirst();
            state.currentModeUiEntities.remove(removed);
            removeRuntimeEntity(engine, removed.entity());
        }
        int transientIndex = 0;
        for (ScenarioEntity scenarioEntity : state.currentModeUiEntities) {
            Entity entity = scenarioEntity.entity();
            Vec3 cameraPosition = engine.camera == null ? new Vec3(0.0, 1.0, 3.0) : engine.camera.getPosition();
            Vec3 forward = engine.camera == null ? new Vec3(0.0, 0.0, -1.0) : engine.camera.getForward().normalize();
            Vec3 right = forward.cross(Vec3.UP).normalize();
            if (right.lengthSquared() < 1e-8) {
                right = new Vec3(1.0, 0.0, 0.0);
            }
            Vec3 up = right.cross(forward).normalize();
            double lane = uiLane(scenarioEntity.profile());
            double depth = 2.0 + transientIndex * 0.28 + Math.sin(tick * 0.05 + transientIndex) * 0.08;
            Vec3 basePosition = cameraPosition
                    .add(forward.mul(depth))
                    .add(right.mul(lane * 0.72))
                    .add(up.mul(-0.18 + transientIndex * 0.08));
            int ageTicks = Math.max(0, tick - scenarioEntity.spawnTick());
            double uniformScale = switch (scenarioEntity.profile()) {
                case METALLIC -> 1.00 + Math.sin(tick * 0.08 + transientIndex * 0.4) * 0.12;
                case WATER -> 1.18 + Math.cos(tick * 0.07 + transientIndex * 0.5) * 0.14;
                case EMISSIVE -> 3.00 + Math.min(1.80, ageTicks * 0.035);
                default -> 0.90 + transientIndex * 0.10;
            };
            applyEntityTransformViaInspector(engine,
                    entity,
                    basePosition,
                    new Vec3(Math.toDegrees(tick * 0.02), Math.toDegrees(tick * 0.03 + transientIndex * 0.2), 0.0),
                    new Vec3(uniformScale, uniformScale, uniformScale));
            transientIndex++;
        }

        animateScenarioLights(state, tick);
        engine.selectedEntity = state.coreEntities.get(Math.floorMod(tick, state.coreEntities.size())).entity();
        engine.scene.setEnvironmentYawDegrees(tick * 7.5);
        engine.scene.setEnvironmentPitchDegrees(Math.sin(tick * 0.09) * 7.0);
        engine.scene.setEnvironmentStrength(mode == RenderMode.PATH_TRACING ? 1.22 : mode == RenderMode.RAY_TRACING ? 1.16 : 1.08);
        engine.scene.update(0.016);
    }

    private static void applyNavigationPattern(Engine engine, int tick, RenderMode mode) {
        if (engine == null || engine.input == null || engine.camera == null) {
            return;
        }
        Vec3 cameraPosition = engine.camera.getPosition();
        Vec3 forward = engine.camera.getForward().normalize();
        Vec3 right = forward.cross(Vec3.UP).normalize();
        if (right.lengthSquared() < 1e-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 desiredPosition = desiredNavigationPosition(tick, mode);
        Vec3 positionError = desiredPosition.sub(cameraPosition);
        double forwardError = positionError.dot(forward);
        double rightError = positionError.dot(right);

        boolean moveForward = forwardError > 0.18;
        boolean moveBackward = forwardError < -0.18;
        boolean strafeLeft = rightError < -0.14;
        boolean strafeRight = rightError > 0.14;
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_W, moveForward);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_S, moveBackward);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_A, strafeLeft);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_D, strafeRight);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_SPACE, false);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_CONTROL, false);

        Vec3 desiredForward = NAVIGATION_LOOK_TARGET.sub(cameraPosition).normalize();
        double currentYaw = Math.atan2(forward.x, -forward.z);
        double desiredYaw = Math.atan2(desiredForward.x, -desiredForward.z);
        double yawError = wrapAngle(desiredYaw - currentYaw);
        double currentPitch = Math.atan2(forward.y, Math.sqrt(forward.x * forward.x + forward.z * forward.z));
        double desiredPitch = Math.atan2(desiredForward.y, Math.sqrt(desiredForward.x * desiredForward.x + desiredForward.z * desiredForward.z));
        double pitchError = desiredPitch - currentPitch;

        int baseYawGain = switch (mode) {
            case MODEL -> 22;
            case BASIC -> 24;
            case PHONG -> 24;
            case RAY_TRACING -> 20;
            case PATH_TRACING -> 18;
            default -> 22;
        };
        int dx = clampInt((int) Math.round(yawError * baseYawGain), -10, 10);
        int dy = clampInt((int) Math.round(-pitchError * 16.0), -5, 5);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_J, dx < -1);
        engine.input.setKeyDownStateForTesting(KeyEvent.VK_L, dx > 1);
        engine.input.forceMouseDelta(dx, dy);
    }

    private static Vec3 desiredNavigationPosition(int tick, RenderMode mode) {
        double phase = tick * (mode == RenderMode.PATH_TRACING ? 0.040 : mode == RenderMode.RAY_TRACING ? 0.048 : 0.055);
        double x = Math.sin(phase) * 1.10;
        double z = 2.85 + Math.cos(phase * 1.12) * 0.58;
        double y = 1.42;
        return new Vec3(x, y, z);
    }

    private static ScenarioEntity spawnUiViewportPrimitive(Engine engine,
                                                           RenderMode mode,
                                                           int slot,
                                                           int tick) {
        String type = uiPrimitiveType(mode, slot);
        MaterialScenarioProfile profile = uiMaterialProfile(mode, slot);
        engine.addPrimitive(type);
        Entity entity = engine.selectedEntity;
        if (entity == null) {
            return null;
        }
        applyMaterialPresetViaInspectorWorkflow(engine, entity, profile);
        Vec3 forward = engine.camera == null ? new Vec3(0.0, 0.0, -1.0) : engine.camera.getForward().normalize();
        Vec3 right = forward.cross(Vec3.UP).normalize();
        if (right.lengthSquared() < 1e-8) {
            right = new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 up = right.cross(forward).normalize();
        double lane = uiLane(profile);
        Vec3 targetPos = engine.camera.getPosition()
                .add(forward.mul(2.0 + slot * 0.32))
                .add(right.mul(lane * 0.78))
                .add(up.mul(-0.12 + slot * 0.05));
        double baseScale = profile == MaterialScenarioProfile.EMISSIVE ? 3.0 : profile == MaterialScenarioProfile.WATER ? 1.18 : 1.0;
        applyEntityTransformViaInspector(engine,
                entity,
                targetPos,
                new Vec3(0.0, tick * 3.5 + slot * 18.0, 0.0),
                new Vec3(baseScale, baseScale, baseScale));
        System.out.println(String.format(
                Locale.ROOT,
                "Spawn[%s][slot=%d] primitive=%s material=%s scale=%.2f",
                mode,
                slot,
                type,
                profile,
                baseScale));
        return new ScenarioEntity(entity, profile, tick);
    }

    private static void applyMaterialPresetViaInspectorWorkflow(Engine engine,
                                                                Entity entity,
                                                                MaterialScenarioProfile profile) {
        if (engine == null || entity == null) {
            return;
        }
        engine.setCurrentEntitySelection(entity);
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
        if (!(entity.getMaterial() instanceof PhongMaterial material)) {
            return;
        }
        PhongMaterial before = engine.captureMaterialHistoryState(entity);
        MaterialPresets.apply(switch (profile) {
            case GLASS -> MaterialPresets.Preset.GLASS;
            case WATER -> MaterialPresets.Preset.WATER;
            case METALLIC -> MaterialPresets.Preset.METALLIC;
            case EMISSIVE -> MaterialPresets.Preset.EMISSIVE;
            case COATED_GLOSSY -> MaterialPresets.Preset.GLOSSY;
        }, material);
        engine.pushMaterialHistoryCommand("Použití předvolby materiálu", entity, before, engine.captureMaterialHistoryState(entity));
        engine.rebuildSceneDetailsPanel();
        engine.rebuildMaterialDock();
    }

    private static void applyEntityTransformViaInspector(Engine engine,
                                                         Entity entity,
                                                         Vec3 position,
                                                         Vec3 eulerDegrees,
                                                         Vec3 scale) {
        if (engine == null || entity == null) {
            return;
        }
        engine.setCurrentEntitySelection(entity);
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.refreshObjectInspectorValues();
        if (engine.posXField == null || engine.posYField == null || engine.posZField == null
                || engine.rotXField == null || engine.rotYField == null || engine.rotZField == null
                || engine.scaleXField == null || engine.scaleYField == null || engine.scaleZField == null) {
            entity.getTransform().setPosition(position);
            entity.getTransform().setEulerAngles(
                    Math.toRadians(eulerDegrees.x),
                    Math.toRadians(eulerDegrees.y),
                    Math.toRadians(eulerDegrees.z));
            entity.getTransform().setScale(scale);
            return;
        }
        engine.posXField.setText(engine.formatTransformValue(position.x));
        engine.posYField.setText(engine.formatTransformValue(position.y));
        engine.posZField.setText(engine.formatTransformValue(position.z));
        engine.rotXField.setText(engine.formatTransformValue(eulerDegrees.x));
        engine.rotYField.setText(engine.formatTransformValue(eulerDegrees.y));
        engine.rotZField.setText(engine.formatTransformValue(eulerDegrees.z));
        engine.scaleXField.setText(engine.formatTransformValue(scale.x));
        engine.scaleYField.setText(engine.formatTransformValue(scale.y));
        engine.scaleZField.setText(engine.formatTransformValue(scale.z));
        EngineObjectInspectorController.applyObjectInspectorValues(engine);
    }

    private static String uiPrimitiveType(RenderMode mode, int slot) {
        return switch (mode) {
            case MODEL -> switch (slot) {
                case 0 -> "cube";
                case 1 -> "sphere";
                default -> "cone";
            };
            case BASIC -> switch (slot) {
                case 0 -> "cylinder";
                case 1 -> "capsule";
                default -> "torus";
            };
            case PHONG -> switch (slot) {
                case 0 -> "crystal";
                case 1 -> "cube";
                default -> "sphere";
            };
            case RAY_TRACING -> switch (slot) {
                case 0 -> "sphere";
                case 1 -> "torus-knot";
                default -> "cylinder";
            };
            case PATH_TRACING -> switch (slot) {
                case 0 -> "crystal";
                case 1 -> "capsule";
                default -> "torus";
            };
            default -> "cube";
        };
    }

    private static MaterialScenarioProfile uiMaterialProfile(RenderMode mode, int slot) {
        return switch (slot) {
            case 0 -> MaterialScenarioProfile.METALLIC;
            case 1 -> MaterialScenarioProfile.WATER;
            default -> MaterialScenarioProfile.EMISSIVE;
        };
    }

    private static double uiLane(MaterialScenarioProfile profile) {
        return switch (profile) {
            case METALLIC -> -1.05;
            case WATER -> 1.05;
            case EMISSIVE -> 0.0;
            default -> 0.0;
        };
    }

    private static void animateScenarioLights(MaterialScenarioState state, int tick) {
        for (int i = 0; i < state.lights.size(); i++) {
            Light light = state.lights.get(i);
            double angle = tick * 0.08 + i * 0.95;
            if (light instanceof DirectionalLight directionalLight) {
                directionalLight.setDirection(new Vec3(
                        Math.cos(angle) * 0.42,
                        -1.0,
                        Math.sin(angle) * 0.36));
            } else if (light instanceof ConeLight coneLight) {
                coneLight.setPosition(new Vec3(
                        Math.cos(angle * 0.72) * 1.9,
                        2.9 + Math.sin(angle * 0.61) * 0.18,
                        2.3 + Math.sin(angle * 0.72) * 0.34));
                coneLight.setDirection(new Vec3(
                        -Math.cos(angle * 0.72) * 0.12,
                        -0.95,
                        -0.20));
                coneLight.setIntensity(3.2 + Math.sin(angle * 0.53) * 0.45);
            } else if (light instanceof AreaLight areaLight) {
                areaLight.setPosition(new Vec3(
                        -1.9 + Math.cos(angle) * 0.28,
                        2.3 + Math.sin(angle * 0.7) * 0.14,
                        -1.0 + Math.sin(angle) * 0.22));
                areaLight.setEmissionDirection(new Vec3(0.64, -0.74 + Math.sin(angle * 0.44) * 0.05, 0.14));
                areaLight.setIntensity(2.3 + Math.cos(angle * 0.58) * 0.20);
            } else if (light instanceof PointLight pointLight) {
                pointLight.setPosition(new Vec3(
                        Math.cos(angle) * (1.45 + i * 0.08),
                        1.45 + Math.sin(angle * 0.82) * 0.16,
                        Math.sin(angle * 0.94) * (1.55 + i * 0.06)));
                pointLight.setIntensity(2.25 + Math.sin(angle * 0.63) * 0.42);
            }
        }
    }

    @SuppressWarnings("unused")
    private static void animateMaterialMemoryCamera(Engine engine, int tick, RenderMode mode) {
        if (!(engine.camera instanceof engine.camera.PerspectiveCamera camera)) {
            return;
        }
        double speed = mode == RenderMode.PATH_TRACING ? 0.08 : mode == RenderMode.RAY_TRACING ? 0.10 : 0.12;
        double angle = tick * speed;
        double radius = 2.55 + Math.sin(tick * 0.07) * 0.22;
        camera.setPosition(new Vec3(
                Math.cos(angle) * radius,
                0.92 + Math.cos(tick * 0.11) * 0.16,
                0.35 + Math.sin(angle) * (radius * 0.88)));
        camera.lookAt(new Vec3(
                Math.sin(tick * 0.08) * 0.10,
                0.18 + Math.cos(tick * 0.10) * 0.06,
                0.28 + Math.cos(tick * 0.06) * 0.08));
    }

    @SuppressWarnings("unused")
    private static ScenarioEntity createTransientScenarioEntity(int tick) {
        MaterialScenarioProfile profile = MaterialScenarioProfile.values()[Math.floorMod(tick, MaterialScenarioProfile.values().length)];
        Entity entity = new Entity(
                "memory-transient-" + tick,
                switch (Math.floorMod(tick, 5)) {
                    case 0 -> MeshGenerator.cube(0.42);
                    case 1 -> MeshGenerator.torus(0.36, 0.10, 24, 10);
                    case 2 -> MeshGenerator.cone(0.28, 0.78, 16);
                    case 3 -> MeshGenerator.cylinder(0.24, 0.72, 16, 1);
                    default -> MeshGenerator.sphere(0.28, 18, 14);
                },
                createScenarioMaterial(profile, tick * 17 + 7));
        return new ScenarioEntity(entity, profile, tick);
    }

    private static PhongMaterial createScenarioMaterial(MaterialScenarioProfile profile, int seed) {
        Vec3 base = switch (profile) {
            case GLASS -> new Vec3(0.88, 0.95, 1.0);
            case WATER -> new Vec3(0.18, 0.48, 0.86);
            case METALLIC -> new Vec3(0.78, 0.76, 0.72);
            case EMISSIVE -> new Vec3(1.0, 0.52, 0.20);
            case COATED_GLOSSY -> new Vec3(0.24, 0.66, 0.74);
        };
        PhongMaterial material = new PhongMaterial(base, 64.0);
        MaterialPresets.apply(switch (profile) {
            case GLASS -> MaterialPresets.Preset.GLASS;
            case WATER -> MaterialPresets.Preset.WATER;
            case METALLIC -> MaterialPresets.Preset.METALLIC;
            case EMISSIVE -> MaterialPresets.Preset.EMISSIVE;
            case COATED_GLOSSY -> MaterialPresets.Preset.GLOSSY;
        }, material);

        switch (profile) {
            case GLASS -> {
                material.setDiffuseColor(new Vec3(0.92, 0.97, 1.0));
                material.setOpacity(0.12 + Math.floorMod(seed, 3) * 0.03);
                material.setClearcoatFactor(0.10);
                material.setThickness(0.40 + Math.floorMod(seed, 4) * 0.04);
            }
            case WATER -> {
                material.setDiffuseColor(new Vec3(0.16, 0.52, 0.90));
                material.setMediumColor(new Vec3(0.42, 0.72, 1.0));
                material.setDensity(0.15 + Math.floorMod(seed, 4) * 0.03);
                material.setAnisotropy(0.04 + Math.floorMod(seed, 3) * 0.03);
                material.setOpacity(0.22);
            }
            case METALLIC -> {
                material.setDiffuseColor(new Vec3(0.78, 0.78 - Math.floorMod(seed, 3) * 0.04, 0.72));
                material.setReflectivity(0.64 + Math.floorMod(seed, 3) * 0.06);
                material.setRoughness(0.10 + Math.floorMod(seed, 4) * 0.05);
                material.setClearcoatFactor(0.08 + Math.floorMod(seed, 2) * 0.04);
            }
            case EMISSIVE -> {
                material.setDiffuseColor(new Vec3(0.44, 0.20, 0.10));
                material.setEmissionColor(new Vec3(1.0, 0.50 + Math.floorMod(seed, 4) * 0.08, 0.18));
                material.setEmissionStrength(2.8 + Math.floorMod(seed, 4) * 0.55);
                material.setRoughness(0.78);
            }
            case COATED_GLOSSY -> {
                material.setDiffuseColor(new Vec3(0.24, 0.64, 0.74));
                material.setReflectivity(0.24 + Math.floorMod(seed, 3) * 0.04);
                material.setClearcoatFactor(0.42 + Math.floorMod(seed, 3) * 0.08);
                material.setClearcoatRoughness(0.06 + Math.floorMod(seed, 3) * 0.04);
                material.setRoughness(0.16 + Math.floorMod(seed, 4) * 0.06);
            }
        }

        attachScenarioTextures(material, seed, profile == MaterialScenarioProfile.EMISSIVE);
        attachProceduralGraph(material, seed);
        MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
        return material;
    }

    private static void attachScenarioTextures(PhongMaterial material, int seed, boolean emissiveProfile) {
        int size = 64;
        material.setDiffuseTexture(createColorPatternTexture(seed, size, size));
        material.setNormalTexture(createNormalPatternTexture(seed + 17, size, size));
        material.setMetallicRoughnessTexture(createMetallicRoughnessTexture(seed + 31, size, size));
        material.setEmissiveTexture(createEmissivePatternTexture(seed + 47, size, size, emissiveProfile));
        material.setNormalScale(0.35 + Math.floorMod(seed, 5) * 0.12);
        material.setTextureFilteringLinear(true);
    }

    private static Texture createColorPatternTexture(int seed, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double fy = y / (double) Math.max(1, height - 1);
            for (int x = 0; x < width; x++) {
                double fx = x / (double) Math.max(1, width - 1);
                double wave = 0.5 + 0.5 * Math.sin((fx * (5.0 + Math.floorMod(seed, 4))) + (fy * (7.0 + Math.floorMod(seed, 3))) + seed * 0.11);
                int r = clamp8((int) Math.round(40 + 170 * fx + 45 * wave));
                int g = clamp8((int) Math.round(35 + 150 * fy + 38 * (1.0 - wave)));
                int b = clamp8((int) Math.round(55 + 135 * wave));
                pixels[y * width + x] = argb(255, r, g, b);
            }
        }
        return new Texture(width, height, pixels);
    }

    private static Texture createNormalPatternTexture(int seed, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double nx = 0.5 + 0.5 * Math.sin((x + seed) * 0.19);
                double ny = 0.5 + 0.5 * Math.cos((y - seed) * 0.23);
                int r = clamp8((int) Math.round(nx * 255.0));
                int g = clamp8((int) Math.round(ny * 255.0));
                pixels[y * width + x] = argb(255, r, g, 255);
            }
        }
        return new Texture(width, height, pixels);
    }

    private static Texture createMetallicRoughnessTexture(int seed, int width, int height) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            double fy = y / (double) Math.max(1, height - 1);
            for (int x = 0; x < width; x++) {
                double fx = x / (double) Math.max(1, width - 1);
                int roughness = clamp8((int) Math.round(60 + 140 * Math.abs(Math.sin((fx + fy) * 4.2 + seed * 0.09))));
                int metallic = clamp8((int) Math.round(80 + 150 * Math.abs(Math.cos((fx - fy) * 3.6 + seed * 0.07))));
                pixels[y * width + x] = argb(255, 0, roughness, metallic);
            }
        }
        return new Texture(width, height, pixels);
    }

    private static Texture createEmissivePatternTexture(int seed, int width, int height, boolean emissiveProfile) {
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double pulse = 0.5 + 0.5 * Math.sin((x + y) * 0.24 + seed * 0.13);
                int r = emissiveProfile ? clamp8((int) Math.round(110 + 145 * pulse)) : clamp8((int) Math.round(20 + 45 * pulse));
                int g = emissiveProfile ? clamp8((int) Math.round(48 + 120 * pulse)) : clamp8((int) Math.round(10 + 28 * pulse));
                int b = emissiveProfile ? clamp8((int) Math.round(18 + 72 * pulse)) : clamp8((int) Math.round(6 + 18 * pulse));
                pixels[y * width + x] = argb(255, r, g, b);
            }
        }
        return new Texture(width, height, pixels);
    }

    private static void mutateLiveScene(Engine engine,
                                        Deque<Entity> rollingEntities,
                                        List<ImportedTemplate> importedTemplates,
                                        int step) {
        int mutableIndex = 0;
        for (Entity entity : engine.scene.getAllMeshEntities()) {
            if (entity == null || entity == engine.floorEntity || entity == engine.outputCameraEntity) {
                continue;
            }
            double angle = step * 0.21 + mutableIndex * 0.17;
            double radius = 1.9 + (mutableIndex % 8) * 0.31;
            double y = -0.45 + (mutableIndex % 5) * 0.28 + Math.sin(angle * 0.85) * 0.10;
            entity.getTransform().setPosition(new Vec3(
                    Math.cos(angle) * radius,
                    y,
                    Math.sin(angle * 1.12) * radius));
            entity.getTransform().setEulerAngles(angle * 0.14, angle * 0.52, angle * 0.07);
            if (((mutableIndex + step) % 3) == 0) {
                entity.setMaterial(complexStressMaterial(step * 37 + mutableIndex));
            }
            mutableIndex++;
        }

        while (rollingEntities.size() > 60) {
            Entity removed = rollingEntities.removeFirst();
            removeRuntimeEntity(engine, removed);
        }

        for (int slot = 0; slot < 4; slot++) {
            Entity entity = !importedTemplates.isEmpty() && ((step + slot) % 4) == 0
                    ? createImportedStressEntity(importedTemplates, step, slot)
                    : createPrimitiveStressEntity(step, slot);
            rollingEntities.addLast(entity);
            addRuntimeEntity(engine, entity);
            if ((step + slot) % 5 == 0) {
                engine.selectedEntity = entity;
            }
        }

        engine.scene.setEnvironmentStrength(0.95 + 0.34 * Math.abs(Math.sin(step * 0.23)));
        engine.scene.setEnvironmentYawDegrees(step * 19.0);
        engine.scene.setEnvironmentPitchDegrees(Math.cos(step * 0.19) * 11.0);
        engine.scene.setBackgroundColor(new Vec3(
                0.02 + 0.01 * ((step + 1) % 4),
                0.03 + 0.015 * ((step + 2) % 4),
                0.05 + 0.02 * ((step + 3) % 4)));
        engine.scene.update(0.016);
    }

    private static void runOnEngineThread(Engine engine, Runnable task) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        long startNanos = System.nanoTime();
        engine.enqueueRuntimeTask(() -> {
            try {
                task.run();
            } catch (Throwable ex) {
                failure.set(ex);
            } finally {
                latch.countDown();
            }
        });
        long timeoutSeconds = runtimeTaskTimeoutSeconds(engine);
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for runtime task execution. mode="
                    + (engine == null ? "UNKNOWN" : engine.activeMode)
                    + " timeout_s=" + timeoutSeconds);
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        if (elapsedMillis >= 2_000L) {
            System.out.println(String.format(
                    Locale.ROOT,
                    "SlowRuntimeTask mode=%s elapsed_ms=%d",
                    engine == null ? "UNKNOWN" : engine.activeMode,
                    elapsedMillis));
        }
        if (failure.get() != null) {
            throw new IllegalStateException("Runtime task failed on engine thread.", failure.get());
        }
    }

    private static long runtimeTaskTimeoutSeconds(Engine engine) {
        if (engine == null || engine.activeMode == null) {
            return 10L;
        }
        return switch (engine.activeMode) {
            case PATH_TRACING -> 30L;
            case RAY_TRACING -> 20L;
            default -> 10L;
        };
    }

    private static void tryRunOnEngineThread(Engine engine, Runnable task) {
        try {
            if (engine != null && engine.running) {
                runOnEngineThread(engine, task);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IllegalStateException ignored) {
        }
    }

    private static <T> T callOnEngineThread(Engine engine, Supplier<T> task) throws InterruptedException {
        AtomicReference<T> result = new AtomicReference<>();
        runOnEngineThread(engine, () -> result.set(task.get()));
        return result.get();
    }

    private static void addRuntimeEntity(Engine engine, Entity entity) {
        engine.scene.addEntity(entity);
        engine.stateFor(entity);
        entity.computeWorldBounds();
    }

    private static void removeRuntimeEntity(Engine engine, Entity entity) {
        if (entity == null) {
            return;
        }
        if (engine.physicsWorld != null && entity.getRigidBody() != null) {
            engine.physicsWorld.removeBody(entity.getRigidBody());
            entity.setRigidBody(null);
        }
        engine.scene.removeEntity(entity);
        engine.sceneItemStates.remove(entity);
        EngineTimelineController.removeEntityTrack(engine, entity);
        if (engine.selectedEntity == entity) {
            engine.selectedEntity = null;
        }
    }

    private static void animateCamera(Engine engine, int step) {
        if (!(engine.camera instanceof engine.camera.PerspectiveCamera camera)) {
            return;
        }
        double angle = step * 0.43;
        double radius = 3.2 + Math.sin(step * 0.18) * 1.3;
        double x = Math.cos(angle) * radius;
        double z = Math.sin(angle * 1.08) * (radius * 0.92);
        double y = 0.65 + Math.cos(step * 0.31) * 0.95;
        camera.setPosition(new Vec3(x, y, z));
        camera.lookAt(new Vec3(
                Math.sin(step * 0.17) * 0.85,
                -0.15 + Math.cos(step * 0.23) * 0.32,
                Math.cos(step * 0.21) * 0.85));
    }

    private static Entity createPrimitiveStressEntity(int step, int slot) {
        int seed = step * 19 + slot * 7;
        Entity entity = new Entity(
                "runtime-rapid-" + step + "-" + slot,
                switch ((step + slot) % 7) {
                    case 0 -> MeshGenerator.torusKnot(0.58, 0.18, 0.12, 132, 14, 2, 3);
                    case 1 -> MeshGenerator.crystal(0.68, 1.3, 6);
                    case 2 -> MeshGenerator.capsule(0.34, 1.05, 9, 18);
                    case 3 -> MeshGenerator.torus(0.52, 0.17, 28, 12);
                    case 4 -> MeshGenerator.cylinder(0.32, 1.15, 18, 1);
                    case 5 -> MeshGenerator.cone(0.42, 1.18, 18);
                    default -> MeshGenerator.cube(0.88);
                },
                complexStressMaterial(seed));
        double angle = step * 0.39 + slot * 0.88;
        entity.getTransform().setPosition(new Vec3(
                Math.cos(angle) * (2.0 + slot * 0.44),
                -0.28 + slot * 0.40,
                Math.sin(angle) * (1.9 + slot * 0.36)));
        entity.getTransform().setEulerAngles(angle * 0.22, angle * 0.61, 0.0);
        return entity;
    }

    private static Entity createImportedStressEntity(List<ImportedTemplate> importedTemplates, int step, int slot) {
        ImportedTemplate template = importedTemplates.get(Math.floorMod(step + slot, importedTemplates.size()));
        Entity entity = new Entity(
                "runtime-imported-" + step + "-" + slot,
                template.mesh(),
                complexStressMaterial(step * 31 + slot * 13));
        double angle = step * 0.24 + slot * 0.59;
        entity.getTransform().setPosition(template.position().mul(0.55).add(new Vec3(
                Math.cos(angle) * (1.45 + slot * 0.25),
                -0.35 + slot * 0.34,
                Math.sin(angle) * (1.42 + slot * 0.22))));
        entity.getTransform().setRotation(template.rotation());
        entity.getTransform().setScale(template.scale().mul(0.72 + slot * 0.08));
        return entity;
    }

    private static PhongMaterial complexStressMaterial(int seed) {
        int r = 70 + Math.floorMod(seed * 29, 150);
        int g = 60 + Math.floorMod(seed * 17, 160);
        int b = 50 + Math.floorMod(seed * 11, 170);
        PhongMaterial material = new PhongMaterial(new Vec3(r / 255.0, g / 255.0, b / 255.0), 38.0 + Math.floorMod(seed, 5) * 12.0);
        material.setSpecularColor(new Vec3(0.85, 0.87, 0.90));
        material.setSpecularFactor(0.62 + Math.floorMod(seed, 4) * 0.08);
        material.setReflectivity(0.12 + Math.floorMod(seed, 5) * 0.08);
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
                templates.add(new ImportedTemplate(
                        entry.getName() == null ? "imported-" + index : entry.getName(),
                        entry.getMesh(),
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

    private static void collectHeapRatio(List<Double> ratios, long usedHeapBytes, long maxHeapBytes) {
        if (usedHeapBytes < 0L || maxHeapBytes <= 0L) {
            return;
        }
        double ratio = usedHeapBytes / (double) maxHeapBytes;
        if (Double.isFinite(ratio) && ratio >= 0.0) {
            ratios.add(Math.min(1.0, ratio));
        }
    }

    private static HardwareTelemetrySampler.Sample waitForThreadSettle(int maxThreadCount, long timeoutMs) {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        HardwareTelemetrySampler.Sample latest = HardwareTelemetrySampler.sample();
        while (System.nanoTime() < deadline) {
            System.gc();
            sleepQuietly(80L);
            latest = HardwareTelemetrySampler.sample();
            if (latest.liveThreadCount() <= maxThreadCount) {
                return latest;
            }
        }
        return latest;
    }

    private static HardwareTelemetrySampler.Sample captureRetainedHeapSample(int passes, long settleMillis) {
        HardwareTelemetrySampler.Sample latest = HardwareTelemetrySampler.sample();
        int samplePasses = Math.max(1, passes);
        for (int i = 0; i < samplePasses; i++) {
            System.gc();
            sleepQuietly(settleMillis);
            latest = HardwareTelemetrySampler.sample();
        }
        return latest;
    }

    private static List<String> captureLiveThreadNamesMatching(String... prefixes) {
        Set<String> names = new LinkedHashSet<>();
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (thread == null || !thread.isAlive()) {
                continue;
            }
            String name = thread.getName();
            if (name == null || name.isBlank()) {
                continue;
            }
            for (String prefix : prefixes) {
                if (prefix != null && !prefix.isBlank() && name.startsWith(prefix)) {
                    names.add(name);
                    break;
                }
            }
        }
        return new ArrayList<>(names);
    }

    private static String joinThreadNames(List<String> names, int maxItems) {
        if (names == null || names.isEmpty()) {
            return "none";
        }
        int limit = Math.max(1, maxItems);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < names.size() && i < limit; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(names.get(i));
        }
        if (names.size() > limit) {
            builder.append(" (+").append(names.size() - limit).append(" more)");
        }
        return builder.toString();
    }

    private static String formatPercent(double ratio) {
        return Double.isFinite(ratio) ? String.format(Locale.ROOT, "%.1f%%", ratio * 100.0) : "n/a";
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0L) {
            return "n/a";
        }
        double mib = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1fMiB", mib);
    }

    private static String formatSignedBytes(long bytes) {
        double mib = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%+.1fMiB", mib);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double wrapAngle(double radians) {
        double wrapped = radians;
        while (wrapped <= -Math.PI) {
            wrapped += Math.PI * 2.0;
        }
        while (wrapped > Math.PI) {
            wrapped -= Math.PI * 2.0;
        }
        return wrapped;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
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

    private static int argb(int a, int r, int g, int b) {
        return (clamp8(a) << 24) | (clamp8(r) << 16) | (clamp8(g) << 8) | clamp8(b);
    }

    private static int clamp8(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private record ImportedTemplate(String name,
                                    Mesh mesh,
                                    Vec3 position,
                                    Quaternion rotation,
                                    Vec3 scale) {
    }

    private record SoakResult(int baselineThreads,
                              int steps,
                              int modeSwitches,
                              int peakEntities,
                              int importedTemplateCount,
                              double meanFrameMs,
                              double p95FrameMs,
                              double meanHeapRatio,
                              double peakHeapRatio,
                              int peakThreads,
                              int preShutdownThreads,
                              int safetyRecoveryHits,
                              int fallbackLockHits) {
    }

    private record MaterialMemorySoakResult(int blocks,
                                            int modeSwitches,
                                            long dwellMillis,
                                            int baselineThreads,
                                            int coreEntityCount,
                                            int uiSpawnCount,
                                            int peakEntities,
                                            int scenarioLightCount,
                                            double cameraTravelDistance,
                                            double meanFrameMs,
                                            double p95FrameMs,
                                            double liveMeanHeapRatio,
                                            double livePeakHeapRatio,
                                            long retainedStartBytes,
                                            long retainedPeakBytes,
                                            long retainedEndBytes,
                                            long retainedDeltaBytes,
                                            long gcDeltaMillis,
                                            int peakThreads,
                                            int safetyRecoveryHits,
                                            int fallbackLockHits) {
    }

    private record PreviewState(long accumulatedSamples, String qualityTier) {
    }

    private record WarmupSnapshot(long waitMillis,
                                  long accumulatedSamples,
                                  String qualityTier,
                                  double frameTimeMs,
                                  boolean fallbackLockActive,
                                  boolean criticalPreviewActive,
                                  boolean warmupCaptureActive,
                                  boolean switchOverlayActive) {
    }

    private static final class MaterialScenarioState {
        private final List<ScenarioEntity> coreEntities = new ArrayList<>();
        private final List<ScenarioEntity> currentModeUiEntities = new ArrayList<>();
        private final Deque<ScenarioEntity> allUiSpawnedEntities = new ArrayDeque<>();
        private final List<Light> lights = new ArrayList<>();
    }

    private enum MaterialScenarioProfile {
        GLASS,
        WATER,
        METALLIC,
        EMISSIVE,
        COATED_GLOSSY
    }

    private record ScenarioEntity(Entity entity, MaterialScenarioProfile profile, int spawnTick) {
    }
}
