package engine.core;

import java.awt.Canvas;
import java.awt.EventQueue;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.util.RuntimeInstrumentation;

public final class PreviewInteractiveSessionHarness {

    private static final long RT_EARLY_STILL_ENTRY_SAMPLES = 20L;
    private static final long RT_EARLY_STILL_CAPTURE_SAMPLES = 20L;
    private static final long RT_HIGH_STILL_ENTRY_SAMPLES = 115L;
    private static final long RT_HIGH_STILL_CAPTURE_SAMPLES = 130L;
    private static final long PT_STILL_ENTRY_SAMPLES = 6L;
    private static final long PT_STILL_CAPTURE_SAMPLES = 18L;
    private static final int[] DYNAMIC_TIER_PERCENTS = {100, 90, 80, 70, 60, 50, 40, 33};

    private PreviewInteractiveSessionHarness() {
    }

    public static void main(String[] args) throws Exception {
        RenderMode mode = args.length > 0 ? RenderMode.valueOf(args[0].trim().toUpperCase(Locale.ROOT)) : RenderMode.PATH_TRACING;
        int width = args.length > 1 ? Integer.parseInt(args[1]) : 800;
        int height = args.length > 2 ? Integer.parseInt(args[2]) : 450;
        long warmupMs = args.length > 3 ? Long.parseLong(args[3]) : 1500L;
        long motionCaptureMs = args.length > 4 ? Long.parseLong(args[4]) : 1800L;
        boolean fullscreen = args.length > 5 ? Boolean.parseBoolean(args[5]) : true;
        int explicitRenderWidth = args.length > 6 ? Integer.parseInt(args[6]) : 0;
        int explicitRenderHeight = args.length > 7 ? Integer.parseInt(args[7]) : 0;
        String captureProfile = args.length > 8 ? args[8].trim().toUpperCase(Locale.ROOT) : "FULL";

        RuntimeInstrumentation.setEnabled(true);
        Engine engine = new Engine();
        engine.baseWidth = Math.max(320, width);
        engine.baseHeight = Math.max(180, height);
        engine.lastCanvasWidth = engine.baseWidth;
        engine.lastCanvasHeight = engine.baseHeight;
        engine.launchFullscreen = fullscreen;
        engine.setExplicitPreviewRenderResolution(explicitRenderWidth, explicitRenderHeight);

        Thread engineThread = new Thread(engine::start, "preview-interactive-session");
        engineThread.setDaemon(true);
        engineThread.start();

        try {
            waitForWindow(engine, 15_000L);
            Canvas canvas = engine.window.getCanvas();
            focusCanvas(canvas);

            engine.setRenderMode(mode);
            Thread.sleep(Math.max(500L, warmupMs));
            waitForRepresentativeReady(engine, mode, 20_000L);

            if ("STARTUP_FOCUS".equalsIgnoreCase(captureProfile) || "STARTUP".equalsIgnoreCase(captureProfile)) {
                runStartupFocusCapture(engine, mode);
                return;
            }

            switch (mode) {
                case RAY_TRACING -> runRayTracingCapture(engine, canvas, mode, motionCaptureMs, captureProfile);
                case PATH_TRACING -> runPathTracingCapture(engine, canvas, mode, motionCaptureMs, captureProfile);
                default -> runSimpleCapture(engine, canvas, mode, Math.max(800L, warmupMs), motionCaptureMs);
            }
        } finally {
            engine.shutdown();
            engineThread.join(15_000L);
            RuntimeInstrumentation.setEnabled(false);
        }
    }

    private static void waitForWindow(Engine engine, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine.running && engine.window != null && engine.window.getCanvas() != null) {
                return;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("Preview window did not become ready within timeout.");
    }

    private static void waitForRepresentativeReady(Engine engine,
                                                   RenderMode mode,
                                                   long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        long stableStart = 0L;
        while (System.nanoTime() < deadline) {
            boolean ready = engine != null
                    && engine.running
                    && engine.window != null
                    && engine.window.getCanvas() != null
                    && engine.frameBuffer != null
                    && engine.window.getCanvas().getWidth() > 0
                    && engine.window.getCanvas().getHeight() > 0
                    && engine.frameBuffer.getWidth() > 0
                    && engine.frameBuffer.getHeight() > 0
                    && engine.viewportDisplayedMode == mode
                    && !engine.safetyRecoveryActive
                    && !engine.viewportCriticalPreviewActive
                    && !engine.viewportFallbackLockActive
                    && !engine.viewportWarmupCaptureActive;
            if (ready) {
                if (stableStart == 0L) {
                    stableStart = System.nanoTime();
                }
                if (System.nanoTime() - stableStart >= 900_000_000L) {
                    return;
                }
            } else {
                stableStart = 0L;
            }
            Thread.sleep(25L);
        }
        throw new IllegalStateException("Representative preview state not reached for mode=" + mode);
    }

    private static void focusCanvas(Canvas canvas) throws Exception {
        EventQueue.invokeAndWait(() -> {
            canvas.requestFocus();
            canvas.requestFocusInWindow();
        });
        Thread.sleep(120L);
    }

    private static void dispatchKey(Canvas canvas, int eventId, int keyCode) throws Exception {
        EventQueue.invokeAndWait(() -> {
            KeyEvent event = new KeyEvent(
                    canvas,
                    eventId,
                    System.currentTimeMillis(),
                    0,
                    keyCode,
                    KeyEvent.CHAR_UNDEFINED);
            canvas.dispatchEvent(event);
        });
    }

    private static void runRayTracingCapture(Engine engine,
                                             Canvas canvas,
                                             RenderMode mode,
                                             long motionCaptureMs,
                                             String captureProfile) throws Exception {
        resetPreviewAccumulation(engine, mode);

        boolean movingOnly = "MOVING_ONLY".equalsIgnoreCase(captureProfile);
        boolean dynamicMoving = "DYNAMIC_MOVING".equalsIgnoreCase(captureProfile);
        boolean dynamicPhased = "DYNAMIC_PHASED".equalsIgnoreCase(captureProfile)
            || "PHASED_DYNAMIC".equalsIgnoreCase(captureProfile);
        boolean sampleSweep = "STILL_SAMPLE_SWEEP".equalsIgnoreCase(captureProfile)
                || "SAMPLE_SWEEP".equalsIgnoreCase(captureProfile);
        if (sampleSweep) {
            runStillSampleSweep(engine, mode, new long[]{0L, 20L, 45L, 75L, 115L, 165L});
            return;
        }
        if (dynamicPhased) {
            RuntimeInstrumentation.reset();
            DynamicPhasedStats phasedStats = new DynamicPhasedStats();

            long phaseAStillMs = Math.max(2400L, motionCaptureMs / 2L);
            long phaseBMoveMs = Math.max(3400L, motionCaptureMs);
            long phaseCStillMs = Math.max(6200L, motionCaptureMs * 2L);
            long phaseDMoveMs = Math.max(1400L, motionCaptureMs / 2L);
            long phaseDStillMs = Math.max(4200L, motionCaptureMs);

            captureDynamicPhase(engine, mode, "A_STILL", phaseAStillMs, phasedStats);

            setCameraMotion(engine, canvas, true);
            waitForMotionActive(engine, 5_000L);
            captureDynamicPhase(engine, mode, "B_MOVE", phaseBMoveMs, phasedStats);

            setCameraMotion(engine, canvas, false);
            captureDynamicPhase(engine, mode, "C_STILL", phaseCStillMs, phasedStats);

            setCameraMotion(engine, canvas, true);
            waitForMotionActive(engine, 5_000L);
            captureDynamicPhase(engine, mode, "D_MOVE", phaseDMoveMs, phasedStats);

            setCameraMotion(engine, canvas, false);
            captureDynamicPhase(engine, mode, "D_STILL", phaseDStillMs, phasedStats);

            RuntimeInstrumentation.Snapshot phasedSnapshot = RuntimeInstrumentation.snapshotAndReset();
            printSnapshot("dynamic_phased", mode, engine, phasedSnapshot, capturePreviewState(engine, mode));

            RuntimeInstrumentation.FrameKind kind = RuntimeInstrumentation.FrameKind.PREVIEW;
            long switches = phasedSnapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_SWITCHES);
            long downshifts = phasedSnapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS);
            long upshifts = phasedSnapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_UPSHIFTS);
            long frameCount = Math.max(1L, phasedSnapshot.frameCount(kind));
            boolean oscillation = switches > Math.max(10L, frameCount / 2L);

            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][switch_timeline] %s",
                phasedStats.switchTimeline));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][phase_samples] %s",
                phasedStats.phaseSamples));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][motion_timeline] %s",
                phasedStats.motionTimeline));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][quality_tier_timeline] %s",
                phasedStats.qualityTierTimeline));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][emergency_latch_timeline] %s",
                phasedStats.emergencyLatchTimeline));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][recovery_timeline] %s",
                phasedStats.recoveryTimeline));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][phase_summary] avg_smoothed_frame_ms=%.3f motion_active_samples=%d still_samples=%d",
                phasedStats.averageSmoothedFrameMs(),
                phasedStats.motionActiveSamples,
                phasedStats.stillSamples));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][upshift_blockers] method=EngineRenderRuntime.updateHeavyViewportAssist eval_frames=%d blocked_overload=%d blocked_dwell=%d blocked_arm=%d blocked_frame_drop=%d blocked_critical_pressure=%d blocked_scale_pressure=%d blocked_quality_tier=%d blocked_sample_gate=%d current_overload_ratio=%.3f current_overload_threshold=%.3f current_quality_tier=%s current_samples=%d current_sample_gate=%d last_block_reason=%s",
                engine.viewportUpshiftEvaluationFrames,
                engine.viewportUpshiftBlockedByOverloadCount,
                engine.viewportUpshiftBlockedByDwellCount,
                engine.viewportUpshiftBlockedByArmCount,
                engine.viewportUpshiftBlockedByFrameDropCount,
                engine.viewportUpshiftBlockedByCriticalPressureCount,
                engine.viewportUpshiftBlockedByScalePressureCount,
                engine.viewportUpshiftBlockedByQualityTierCount,
                engine.viewportUpshiftBlockedBySampleGateCount,
                engine.viewportUpshiftLastOverloadRatio,
                engine.viewportUpshiftLastOverloadThreshold,
                engine.viewportUpshiftLastQualityTier == null ? "" : engine.viewportUpshiftLastQualityTier,
                engine.viewportUpshiftLastSampleCount,
                engine.viewportUpshiftLastSampleGate,
                engine.viewportUpshiftLastBlockReason == null ? "" : engine.viewportUpshiftLastBlockReason));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[dynamic_phased][dynamic_moving_test] activated_tiers=%s downshifts=%d upshifts=%d total_switches=%d oscillation_detected=%s final_tier=%d average_frame_ms=%.3f average_preview_render_total=%.3f",
                phasedStats.activatedTierList(),
                downshifts,
                upshifts,
                switches,
                oscillation,
                resolutionTierPercent(engine),
                phasedSnapshot.averageFrameMs(kind),
                phasedSnapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL)));
            Thread.sleep(250L);
            return;
        }
        if (dynamicMoving) {
            RuntimeInstrumentation.reset();
            setSyntheticMotion(engine, true);
            dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_W);
            dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_J);
            waitForMotionActive(engine, 5_000L);
            Thread.sleep(Math.max(3000L, motionCaptureMs));
            dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
            dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
            setSyntheticMotion(engine, false);
            Thread.sleep(Math.max(2800L, motionCaptureMs / 2));
            RuntimeInstrumentation.Snapshot dynamicSnapshot = RuntimeInstrumentation.snapshotAndReset();
            printSnapshot("dynamic_moving", mode, engine, dynamicSnapshot, capturePreviewState(engine, mode));
            long tier100Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_100_FRAMES);
            long tier90Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_90_FRAMES);
            long tier80Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_80_FRAMES);
            long tier70Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_70_FRAMES);
            long tier60Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_60_FRAMES);
            long tier50Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_50_FRAMES);
            long tier40Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_40_FRAMES);
            long tier33Frames = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_33_FRAMES);
            long switches = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_SWITCHES);
            long downshifts = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS);
            long upshifts = dynamicSnapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_UPSHIFTS);
            long frameCount = Math.max(1L, dynamicSnapshot.frameCount(RuntimeInstrumentation.FrameKind.PREVIEW));
            boolean oscillation = switches > Math.max(8L, frameCount / 2L);
            System.out.println(String.format(
                    Locale.ROOT,
                    "Interactive[dynamic_moving][dynamic_moving_test] activated_tiers=%s downshifts=%d upshifts=%d total_switches=%d oscillation_detected=%s",
                    activatedTierList(tier100Frames, tier90Frames, tier80Frames, tier70Frames, tier60Frames, tier50Frames, tier40Frames, tier33Frames),
                    downshifts,
                    upshifts,
                    switches,
                    oscillation));
            Thread.sleep(250L);
            return;
        }

        if (movingOnly) {
            setPreviewMotionOverride(engine, mode, true);
            setSyntheticMotion(engine, true);
            dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_W);
            dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_J);
            waitForMotionActive(engine, 5_000L);
            RuntimeInstrumentation.reset();
            captureMotionWindow(engine, mode, motionCaptureMs);
            printSnapshot("moving", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));
            dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
            dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
            setSyntheticMotion(engine, false);
            setPreviewMotionOverride(engine, mode, false);
            Thread.sleep(250L);
            return;
        }

        RuntimeInstrumentation.reset();
        waitForAccumulatedSamples(engine, mode, RT_EARLY_STILL_ENTRY_SAMPLES, 45_000L);
        RuntimeInstrumentation.reset();
        waitForAccumulatedSamples(engine, mode, RT_EARLY_STILL_CAPTURE_SAMPLES, 45_000L);
        printSnapshot("early_still", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));

        waitForAccumulatedSamples(engine, mode, RT_HIGH_STILL_ENTRY_SAMPLES, 90_000L);
        RuntimeInstrumentation.reset();
        waitForAccumulatedSamples(engine, mode, RT_HIGH_STILL_CAPTURE_SAMPLES, 30_000L);
        printSnapshot("high_sample", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));

        setSyntheticMotion(engine, true);
        setPreviewMotionOverride(engine, mode, true);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_W);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_J);
        waitForMotionActive(engine, 5_000L);
        RuntimeInstrumentation.reset();
        captureMotionWindow(engine, mode, motionCaptureMs);
        printSnapshot("moving", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
        setSyntheticMotion(engine, false);
        setPreviewMotionOverride(engine, mode, false);
        Thread.sleep(250L);
    }

    private static void setCameraMotion(Engine engine, Canvas canvas, boolean active) throws Exception {
        setSyntheticMotion(engine, active);
        dispatchKey(canvas, active ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
        dispatchKey(canvas, active ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
    }

    private static void captureDynamicPhase(Engine engine,
                                            RenderMode mode,
                                            String phaseLabel,
                                            long phaseDurationMs,
                                            DynamicPhasedStats stats) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(300L, phaseDurationMs) * 1_000_000L;
        boolean movingPhase = phaseLabel != null && phaseLabel.contains("MOVE");
        while (System.nanoTime() < deadline) {
            setSyntheticMotion(engine, movingPhase);
            setPreviewMotionOverride(engine, mode, movingPhase);
            PreviewState previewState = capturePreviewState(engine, mode);
            boolean motionActive = engine != null && engine.viewportCameraMotionActive;
            int tierPercent = resolutionTierPercent(engine);
            double smoothedFrameMs = engine == null ? 0.0 : engine.viewportHeavySmoothedFrameMs;
            int sampleGate = recoverSampleGateByTierPercent(tierPercent);
            int stableFrames = engine == null ? 0 : engine.viewportDynamicResolutionRecoverStableFrames;
            int sampleGateFrames = engine == null ? 0 : engine.viewportDynamicResolutionRecoverSampleGateFrames;
                boolean emergencyLatch = previewState != null
                    && previewState.qualityTier() != null
                    && previewState.qualityTier().startsWith("MOVING_");
                boolean stableForRecovery = stableFrames > 0;
                String blockReason = engine == null || engine.viewportUpshiftLastBlockReason == null
                    ? ""
                    : engine.viewportUpshiftLastBlockReason;
                double overloadRatio = engine == null ? 0.0 : engine.viewportUpshiftLastOverloadRatio;
            long elapsedMs = stats.elapsedMs();

            stats.capture(
                    elapsedMs,
                    phaseLabel,
                    tierPercent,
                    motionActive,
                    smoothedFrameMs,
                    previewState == null ? 0L : previewState.accumulatedSamples(),
                    stableFrames,
                    sampleGateFrames,
                    sampleGate,
                    previewState == null ? "UNKNOWN" : previewState.qualityTier(),
                    emergencyLatch,
                    stableForRecovery,
                    blockReason,
                    overloadRatio);
            Thread.sleep(120L);
        }
    }

    private static int recoverSampleGateByTierPercent(int tierPercent) {
        return switch (tierPercent) {
            case 100 -> 0;
            case 90 -> 96;
            case 80 -> 72;
            case 70 -> 48;
            case 60 -> 28;
            case 50 -> 12;
            case 40 -> 8;
            default -> 4;
        };
    }

    private static void runPathTracingCapture(Engine engine,
                                              Canvas canvas,
                                              RenderMode mode,
                                              long motionCaptureMs,
                                              String captureProfile) throws Exception {
        boolean sampleSweep = "STILL_SAMPLE_SWEEP".equalsIgnoreCase(captureProfile)
                || "SAMPLE_SWEEP".equalsIgnoreCase(captureProfile);
        if (sampleSweep) {
            runStillSampleSweep(engine, mode, new long[]{0L, 20L, 45L, 75L, 115L, 165L});
            return;
        }
        resetPreviewAccumulation(engine, mode);

        RuntimeInstrumentation.reset();
        waitForAccumulatedSamples(engine, mode, PT_STILL_ENTRY_SAMPLES, 45_000L);
        RuntimeInstrumentation.reset();
        waitForAccumulatedSamples(engine, mode, PT_STILL_CAPTURE_SAMPLES, 45_000L);
        printSnapshot("still", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));

        setSyntheticMotion(engine, true);
        setPreviewMotionOverride(engine, mode, true);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_W);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_J);
        waitForMotionActive(engine, 5_000L);
        RuntimeInstrumentation.reset();
        captureMotionWindow(engine, mode, motionCaptureMs);
        printSnapshot("moving", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
        setSyntheticMotion(engine, false);
        setPreviewMotionOverride(engine, mode, false);
        Thread.sleep(250L);
    }

    private static void runStillSampleSweep(Engine engine,
                                            RenderMode mode,
                                            long[] sampleThresholds) throws InterruptedException {
        resetPreviewAccumulation(engine, mode);
        if (sampleThresholds == null || sampleThresholds.length == 0) {
            return;
        }
        for (long threshold : sampleThresholds) {
            long target = Math.max(0L, threshold);
            if (target > 0L) {
                waitForAccumulatedSamples(engine, mode, target, 120_000L);
            }
            RuntimeInstrumentation.reset();
            Thread.sleep(900L);
            printSnapshot("still_samples_" + target, mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));
        }
    }

    private static void runSimpleCapture(Engine engine,
                                         Canvas canvas,
                                         RenderMode mode,
                                         long stillCaptureMs,
                                         long motionCaptureMs) throws Exception {
        RuntimeInstrumentation.reset();
        Thread.sleep(stillCaptureMs);
        printSnapshot("still", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));

        setSyntheticMotion(engine, true);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_W);
        dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_J);
        waitForMotionActive(engine, 5_000L);
        RuntimeInstrumentation.reset();
        Thread.sleep(motionCaptureMs);
        printSnapshot("moving", mode, engine, RuntimeInstrumentation.snapshotAndReset(), capturePreviewState(engine, mode));
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_J);
        dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_W);
        setSyntheticMotion(engine, false);
        Thread.sleep(250L);
    }

    private static void setSyntheticMotion(Engine engine, boolean active) {
        if (engine == null) {
            return;
        }
        engine.draggingSelectedObject = active;
        engine.gizmoDragActive = active;
        engine.viewportCameraMotionActive = active;
        if (engine.input != null) {
            engine.input.setKeyDownStateForTesting(KeyEvent.VK_W, active);
            engine.input.setKeyDownStateForTesting(KeyEvent.VK_J, active);
        }
    }

    private static void captureMotionWindow(Engine engine,
                                            RenderMode mode,
                                            long durationMs) throws InterruptedException {
        long deadline = System.nanoTime() + Math.max(250L, durationMs) * 1_000_000L;
        while (System.nanoTime() < deadline) {
            setSyntheticMotion(engine, true);
            setPreviewMotionOverride(engine, mode, true);
            Thread.sleep(30L);
        }
    }

    private static void setPreviewMotionOverride(Engine engine, RenderMode mode, boolean active) {
        if (engine == null || mode == null) {
            return;
        }
        if (mode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            engine.rayTracerRenderer.setParameter("previewQualityLadder", true);
            engine.rayTracerRenderer.setParameter("previewMotionActive", active);
            return;
        }
        if (mode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.setParameter("previewQualityLadder", true);
            engine.pathTracerRenderer.setParameter("previewMotionActive", active);
        }
    }

    private static void runStartupFocusCapture(Engine engine, RenderMode mode) throws InterruptedException {
        long waitDeadline = System.nanoTime() + 8_000_000_000L;
        while (System.nanoTime() < waitDeadline) {
            if (engine.startupSplashClosedNanos > 0L
                    && (engine.startupFocusAcquiredNanos > 0L || engine.startupFocusRequestedNanos > 0L)) {
                break;
            }
            Thread.sleep(50L);
        }
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[startup_focus][startup_timeline] app_start=0.000ms window_created=%s first_rendered_frame=%s first_presented_frame=%s first_interactive_frame=%s splash_closed=%s focus_requested=%s focus_acquired=%s input_ready=%s",
                formatStartupEvent(engine, engine.startupWindowCreatedNanos),
                formatStartupEvent(engine, engine.startupFirstRenderedFrameNanos),
                formatStartupEvent(engine, engine.startupFirstPresentedFrameNanos),
                formatStartupEvent(engine, engine.startupFirstInteractiveFrameNanos),
                formatStartupEvent(engine, engine.startupSplashClosedNanos),
                formatStartupEvent(engine, engine.startupFocusRequestedNanos),
                formatStartupEvent(engine, engine.startupFocusAcquiredNanos),
                engine.startupInputReady));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[startup_focus][startup_result] splash_close_point=%s first_interactive_frame=%s focus_acquired=%s input_ready=%s active_quality_tier=%s",
                formatStartupEvent(engine, engine.startupSplashClosedNanos),
                formatStartupEvent(engine, engine.startupFirstInteractiveFrameNanos),
                engine.startupFocusReady,
                engine.startupInputReady,
                capturePreviewState(engine, mode).qualityTier()));
    }

    private static void resetPreviewAccumulation(Engine engine, RenderMode mode) {
        if (engine == null || mode == null) {
            return;
        }
        switch (mode) {
            case RAY_TRACING -> {
                if (engine.rayTracerRenderer != null) {
                    engine.rayTracerRenderer.setParameter("reset", true);
                }
            }
            case PATH_TRACING -> {
                if (engine.pathTracerRenderer != null) {
                    engine.pathTracerRenderer.setParameter("reset", true);
                }
            }
            default -> {
            }
        }
    }

    private static void waitForAccumulatedSamples(Engine engine,
                                                  RenderMode mode,
                                                  long targetSamples,
                                                  long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (capturePreviewState(engine, mode).accumulatedSamples() >= targetSamples) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new IllegalStateException("Accumulated sample target not reached: mode=" + mode + " target=" + targetSamples);
    }

    private static void waitForMotionActive(Engine engine, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (engine != null && engine.viewportCameraMotionActive) {
                return;
            }
            Thread.sleep(15L);
        }
        System.out.println("Interactive[warn] viewport motion state did not become active within timeout; continuing capture.");
    }

    private static PreviewState capturePreviewState(Engine engine, RenderMode mode) {
        if (engine == null || mode == null) {
            return new PreviewState(0L, "UNAVAILABLE");
        }
        return switch (mode) {
            case RAY_TRACING -> {
                RayTracerRenderer renderer = engine.rayTracerRenderer;
                yield renderer == null
                        ? new PreviewState(0L, "RT_UNAVAILABLE")
                        : new PreviewState(renderer.getAccumulatedSamples(), renderer.getActivePreviewQualityTier());
            }
            case PATH_TRACING -> {
                PathTracerRenderer renderer = engine.pathTracerRenderer;
                yield renderer == null
                        ? new PreviewState(0L, "PT_UNAVAILABLE")
                        : new PreviewState(renderer.getAccumulatedSamples(), renderer.getActivePreviewQualityTier());
            }
            default -> new PreviewState(0L, mode + "_REALTIME");
        };
    }

    private static void printSnapshot(String label,
                                      RenderMode mode,
                                      Engine engine,
                                      RuntimeInstrumentation.Snapshot snapshot,
                                      PreviewState previewState) {
        RuntimeInstrumentation.FrameKind kind = RuntimeInstrumentation.FrameKind.PREVIEW;
        long fallbackEvents = snapshot.fallbackCounts(kind).values().stream().mapToLong(Long::longValue).sum();
        long tier100Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_100_FRAMES);
        long tier90Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_90_FRAMES);
        long tier80Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_80_FRAMES);
        long tier70Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_70_FRAMES);
        long tier60Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_60_FRAMES);
        long tier50Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_50_FRAMES);
        long tier40Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_40_FRAMES);
        long tier33Frames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_33_FRAMES);
        long tierSwitches = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_SWITCHES);
        long tierDownshifts = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS);
        long tierUpshifts = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_UPSHIFTS);
        long motionActiveFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOTION_FRAMES);
        long softResetCount = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.CAMERA_RESETS_SOFT);
        long hardResetCount = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.CAMERA_RESETS_HARD);
        long executedPolishFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_EXECUTED_FRAMES);
        long executedPolishResolveFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_EXECUTED_FRAMES);
        long reusedPolishResolveFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REUSED_FRAMES);
        long polishCacheInvalidations = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_CACHE_INVALIDATIONS);
        long polishResolveCadenceHits = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_CADENCE_HITS);
        long polishReuseAllowedFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_ALLOWED_FRAMES);
        long polishReuseBlockedFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCKED_FRAMES);
        long polishReuseBlockedSoftMotion = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_SOFT_MOTION);
        long polishReuseBlockedIntegrand = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_INTEGRAND);
        long polishReuseBlockedRebuild = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_REBUILD);
        long polishReuseBlockedScaleChange = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_SCALE_CHANGE);
        long polishReuseBlockedTierChange = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_TIER_CHANGE);
        long movingComposeGenerationMismatches = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FRAME_GENERATION_MISMATCHES);
        long movingComposeCacheReuseGenerationMismatches = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_CACHE_REUSE_GENERATION_MISMATCHES);
        long movingComposeStaleActivePixels = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_STALE_ACTIVE_PIXELS);
        long movingComposeSkippedReuseFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_SKIPPED_REUSE_FRAMES);
        long movingComposePartialActiveRegionFrames = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PARTIAL_ACTIVE_REGION_FRAMES);
        double configuredPolishScale = averageScale(snapshot, kind,
                RuntimeInstrumentation.Counter.PREVIEW_CONFIGURED_POLISH_SCALE_X1000,
                Math.max(1L, snapshot.frameCount(kind)));
        double executedPolishScale = averageScale(snapshot, kind,
                RuntimeInstrumentation.Counter.PREVIEW_EXECUTED_POLISH_SCALE_X1000,
                executedPolishFrames);
        long executedPolishWidth = averageCounter(snapshot, kind,
                RuntimeInstrumentation.Counter.PREVIEW_EXECUTED_POLISH_BUFFER_WIDTH,
                executedPolishFrames);
        long executedPolishHeight = averageCounter(snapshot, kind,
                RuntimeInstrumentation.Counter.PREVIEW_EXECUTED_POLISH_BUFFER_HEIGHT,
                executedPolishFrames);
        double previewRenderTotalMs = snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
        double geometrySignatureMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_GEOMETRY_SIGNATURE_NS);
        double lightingSignatureMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_LIGHTING_SIGNATURE_NS);
        double cameraSignatureMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIGNATURE_NS);
        double depthClearMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_DEPTH_CLEAR_NS);
        double runtimeBufferMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_RUNTIME_BUFFER_ENSURE_NS);
        double renderSetupMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_RENDER_SETUP_NS);
        double polishBufferMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_BUFFER_ENSURE_NS);
        double hybridBaseResourceSyncMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_RESOURCE_SYNC_NS);
        double cameraStateBuildMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_STATE_BUILD_NS);
        double hybridBaseTotalMs = snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_TRACE);
        double hybridBaseSetupMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_SETUP_NS);
        double hybridBasePrepareMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_PREPARE_NS);
        double hybridBaseBinningMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_BINNING_NS);
        double hybridBaseRasterMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_RASTER_NS);
        double hybridBaseOutputMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_OUTPUT_NS);
        double hybridBaseReducedShadeMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_REDUCED_SHADE_NS);
        double hybridBaseGuidedUpscaleMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_GUIDED_UPSCALE_NS);
        double hybridBaseReducedShadeStoreMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_REDUCED_SHADE_STORE_NS);
        double hybridBaseReducedShadeComputeMs = Math.max(0.0, hybridBaseReducedShadeMs - hybridBaseReducedShadeStoreMs);
        double hybridBaseInactiveScanMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_INACTIVE_SCAN_NS);
        double hybridBaseGuidePrecheckMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_GUIDE_PRECHECK_NS);
        double hybridBaseFastUpscaleMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_UPSCALE_FAST_PATH_NS);
        double hybridBaseEdgeWeightMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_EDGE_WEIGHT_NS);
        double hybridBaseFinalWriteMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_FINAL_COMPOSITE_WRITE_NS);
        double hybridBaseUpscaleMapBuildMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_UPSCALE_MAP_BUILD_NS);
        long hybridBaseFastPathPixels = averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_FAST_PATH_PIXELS, Math.max(1L, snapshot.frameCount(kind)));
        long hybridBaseEdgePathPixels = averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_EDGE_PATH_PIXELS, Math.max(1L, snapshot.frameCount(kind)));
        double hybridBaseChildSumMs = hybridBaseSetupMs + hybridBasePrepareMs + hybridBaseBinningMs + hybridBaseRasterMs + hybridBaseOutputMs;
        double hybridBaseChildDeltaMs = hybridBaseTotalMs - hybridBaseChildSumMs;
        double rtRenderOverheadMs = snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.RT_OR_PT_RENDER);
        double polishTraceTotalMs = snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.POLISH_TRACE);
        double polishResolveTotalMs = snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.POLISH_RESOLVE);
        double polishReuseComposeTotalMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_COMPOSE_NS);
        double carrierResolveTotalMs = snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_RESOLVE);
        double polishResolveDirectPackMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_DIRECT_PACK_NS);
        double polishResolveResolveLowResMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_RESOLVE_LOWRES_NS);
        double polishResolveUpscalePackMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_UPSCALE_PACK_NS);
        double polishResolveStillCacheMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_STILL_CACHE_WORK_NS);
        double polishResolveMapBuildMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_UPSCALE_MAP_BUILD_NS);
        double polishResolveChildSumMs = polishResolveDirectPackMs
                + polishResolveResolveLowResMs
                + polishResolveUpscalePackMs
                + polishResolveStillCacheMs
                + polishResolveMapBuildMs;
        double polishResolveChildDeltaMs = polishResolveTotalMs - polishResolveChildSumMs;
        double autoSchedMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_NS);
        double ptPathBounceMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_PATH_BOUNCE_NS);
        double ptDirectLightMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_DIRECT_LIGHT_NS);
        double ptShadowQueryMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_SHADOW_QUERY_NS);
        double ptDirectionalMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_DIRECTIONAL_LIGHT_NS);
        double ptPointMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_POINT_LIGHT_NS);
        double ptEnvironmentLightMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_ENVIRONMENT_LIGHTING_NS);
        double ptEmissiveLightMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_EMISSIVE_LIGHTING_NS);
        long ptLocalCandidates = averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_CANDIDATES, Math.max(1L, snapshot.frameCount(kind)));
        long ptLocalShaded = averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_SHADED, Math.max(1L, snapshot.frameCount(kind)));
        long ptLocalShadowed = averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_SHADOWED, Math.max(1L, snapshot.frameCount(kind)));
        double cameraSigExtractMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_EXTRACT_NS);
        double cameraSigProjectionMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_PROJECTION_NS);
        double cameraSigHashMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_HASH_NS);
        double cameraSigDeltaMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_DELTA_NS);
        double cameraSigCompareMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_COMPARE_NS);
        double cameraSigResetApplyMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_RESET_APPLY_NS);
        double cameraSigChildSumMs = cameraSigExtractMs
                + cameraSigProjectionMs
                + cameraSigHashMs
                + cameraSigDeltaMs
                + cameraSigCompareMs
                + cameraSigResetApplyMs;
        double cameraSigChildDeltaMs = cameraSignatureMs - cameraSigChildSumMs;
        double autoSchedTileCostMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_TILE_COST_NS);
        double autoSchedHardwareSampleMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_HW_SAMPLE_NS);
        double autoSchedSmoothingMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_SMOOTHING_NS);
        double autoSchedThresholdMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_THRESHOLD_NS);
        double autoSchedDecisionMs = averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_DECISION_NS);
        double autoSchedChildSumMs = autoSchedTileCostMs
                + autoSchedHardwareSampleMs
                + autoSchedSmoothingMs
                + autoSchedThresholdMs
                + autoSchedDecisionMs;
        double autoSchedChildDeltaMs = autoSchedMs - autoSchedChildSumMs;
        long autoSchedHardwareSampleHits = snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_HW_SAMPLE_HITS);
        double exactChildSumMs = geometrySignatureMs
                + lightingSignatureMs
                + cameraSignatureMs
                + depthClearMs
                + runtimeBufferMs
                + renderSetupMs
                + polishBufferMs
                + hybridBaseResourceSyncMs
                + cameraStateBuildMs
                + rtRenderOverheadMs
                + hybridBaseTotalMs
                + polishTraceTotalMs
                + carrierResolveTotalMs
                + polishResolveTotalMs
                + autoSchedMs;
        double exactDeltaMs = previewRenderTotalMs - exactChildSumMs;
        int currentResolutionTier = resolutionTierPercent(engine);
        int internalRenderWidth = engine != null && engine.frameBuffer != null ? engine.frameBuffer.getWidth() : 0;
        int internalRenderHeight = engine != null && engine.frameBuffer != null ? engine.frameBuffer.getHeight() : 0;
        int frameW = engine != null && engine.window != null && engine.window.getFrame() != null ? engine.window.getFrame().getWidth() : 0;
        int frameH = engine != null && engine.window != null && engine.window.getFrame() != null ? engine.window.getFrame().getHeight() : 0;
        int canvasW = engine != null && engine.window != null && engine.window.getCanvas() != null ? engine.window.getCanvas().getWidth() : 0;
        int canvasH = engine != null && engine.window != null && engine.window.getCanvas() != null ? engine.window.getCanvas().getHeight() : 0;
        printConfig(label, mode, engine, previewState);
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][%s] frames=%d frame_ms=%.3f camera_ms=%.3f scene_ms=%.3f vis_ms=%.3f render_ms=%.3f trace_ms=%.3f carrier_trace_ms=%.3f polish_trace_ms=%.3f denoise_ms=%.3f carrier_denoise_ms=%.3f polish_denoise_ms=%.3f carrier_resolve_ms=%.3f polish_resolve_ms=%.3f overlays_ms=%.3f hud_ui_ms=%.3f blit_ms=%.3f window_present_ms=%.3f accumulated_samples=%d quality_tier=%s configured_polish_scale=%.2f actual_executed_polish_scale=%.2f executed_polish=%dx%d motion_frames=%d camera_changes=%d hard_resets=%d soft_resets=%d secondary_reduced=%d denoise_skipped=%d polish_hits=%d polish_executed=%d polish_resolve_executed=%d polish_resolve_reused=%d polish_resolve_cadence_hits=%d polish_cache_invalidations=%d polish_reuse_allowed=%d polish_reuse_blocked=%d block_soft_motion=%d block_integrand=%d block_rebuild=%d block_scale_change=%d block_tier_change=%d polish_half_hits=%d polish_quarter_hits=%d polish_full_hits=%d polish_skipped_cadence=%d polish_skipped_disabled=%d fallback_events=%d alloc_bytes=%.0f",
                label,
                mode,
                snapshot.frameCount(kind),
                snapshot.averageFrameMs(kind),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.CAMERA_UPDATE),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.SCENE_UPDATE),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.VISIBILITY),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.RT_OR_PT_RENDER),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_TRACE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.POLISH_TRACE),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.DENOISE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_DENOISE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.POLISH_DENOISE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_RESOLVE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.POLISH_RESOLVE),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.OVERLAYS),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.HUD_UI),
                snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.BLIT_PRESENT),
                snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.WINDOW_PRESENT),
                previewState == null ? 0L : previewState.accumulatedSamples(),
                previewState == null ? "UNKNOWN" : previewState.qualityTier(),
                configuredPolishScale,
                executedPolishScale,
                executedPolishWidth,
                executedPolishHeight,
                motionActiveFrames,
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.CAMERA_SIGNATURE_CHANGES),
                hardResetCount,
                softResetCount,
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_SECONDARY_REDUCED_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_CADENCE_HITS),
                executedPolishFrames,
                executedPolishResolveFrames,
                reusedPolishResolveFrames,
                polishResolveCadenceHits,
                polishCacheInvalidations,
                polishReuseAllowedFrames,
                polishReuseBlockedFrames,
                polishReuseBlockedSoftMotion,
                polishReuseBlockedIntegrand,
                polishReuseBlockedRebuild,
                polishReuseBlockedScaleChange,
                polishReuseBlockedTierChange,
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_EXECUTED_HALF_RES_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_EXECUTED_QUARTER_RES_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_EXECUTED_FULL_RES_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_SKIPPED_CADENCE_FRAMES),
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_SKIPPED_DISABLED_FRAMES),
                fallbackEvents,
                snapshot.averageAllocatedBytes(kind)));
            if (mode == RenderMode.PATH_TRACING) {
                System.out.println(String.format(
                    Locale.ROOT,
                    "Interactive[%s][product_metrics] frame_ms=%.3f PREVIEW_RENDER_TOTAL=%.3f PATH_TRACE_TOTAL=%.3f PATH_BOUNCE_TOTAL=%.3f PT_DIRECT_LIGHT=%.3f PT_SHADOW_QUERY=%.3f PT_DENOISE_TOTAL=%.3f PT_RESOLVE_TOTAL=%.3f AUTOSCHED_TOTAL=%.3f current_resolution_tier=%d tier_switches=%d tier_downshifts=%d tier_upshifts=%d fallback_event_count=%d active_quality_tier=%s internal_render_resolution=%dx%d canvas_resolution=%dx%d window_resolution=%dx%d",
                    label,
                    snapshot.averageFrameMs(kind),
                    previewRenderTotalMs,
                    snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.RT_OR_PT_RENDER),
                    ptPathBounceMs,
                    ptDirectLightMs,
                    ptShadowQueryMs,
                    snapshot.averageStageMs(kind, RuntimeInstrumentation.Stage.DENOISE),
                    snapshot.averageExclusiveStageMs(kind, RuntimeInstrumentation.Stage.CARRIER_RESOLVE),
                    autoSchedMs,
                    currentResolutionTier,
                    tierSwitches,
                    tierDownshifts,
                    tierUpshifts,
                    fallbackEvents,
                    previewState == null ? "UNKNOWN" : previewState.qualityTier(),
                    internalRenderWidth,
                    internalRenderHeight,
                    canvasW,
                    canvasH,
                    frameW,
                    frameH));
            } else {
                System.out.println(String.format(
                    Locale.ROOT,
                    "Interactive[%s][product_metrics] frame_ms=%.3f PREVIEW_RENDER_TOTAL=%.3f HYBRID_BASE_TOTAL=%.3f HYBRID_BASE_OUTPUT=%.3f inactive_scan=%.3f POLISH_TRACE_TOTAL=%.3f POLISH_RESOLVE_TOTAL=%.3f current_resolution_tier=%d tier_switches=%d tier_downshifts=%d tier_upshifts=%d fallback_event_count=%d active_quality_tier=%s internal_render_resolution=%dx%d canvas_resolution=%dx%d window_resolution=%dx%d",
                    label,
                    snapshot.averageFrameMs(kind),
                    previewRenderTotalMs,
                    hybridBaseTotalMs,
                    hybridBaseOutputMs,
                    hybridBaseInactiveScanMs,
                    polishTraceTotalMs,
                    polishResolveTotalMs,
                    currentResolutionTier,
                    tierSwitches,
                    tierDownshifts,
                    tierUpshifts,
                    fallbackEvents,
                    previewState == null ? "UNKNOWN" : previewState.qualityTier(),
                    internalRenderWidth,
                    internalRenderHeight,
                    canvasW,
                    canvasH,
                    frameW,
                    frameH));
            }
            String qualityTier = previewState == null ? "UNKNOWN" : previewState.qualityTier();
                boolean previewMotionActive = qualityTier.startsWith("MOVING_")
                    || qualityTier.startsWith("PT_MOTION")
                    || qualityTier.startsWith("PT_MOVING");
            boolean movingHybridPath = qualityTier.startsWith("MOVING_HYBRID_BASE");
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][moving_proof] motion_active_count=%d soft_reset_count=%d hard_reset_count=%d active_quality_tier=%s HYBRID_BASE_TOTAL=%.3f HYBRID_BASE_OUTPUT=%.3f POLISH_TRACE_TOTAL=%.3f POLISH_RESOLVE_TOTAL=%.3f previewMotionActive=%s moving_hybrid_path=%s",
                label,
                motionActiveFrames,
                softResetCount,
                hardResetCount,
                qualityTier,
                hybridBaseTotalMs,
                hybridBaseOutputMs,
                polishTraceTotalMs,
                polishResolveTotalMs,
                previewMotionActive,
                movingHybridPath));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][dynamic_resolution_tiers] tier_100_frames=%d tier_90_frames=%d tier_80_frames=%d tier_70_frames=%d tier_60_frames=%d tier_50_frames=%d tier_40_frames=%d tier_33_frames=%d switches=%d downshifts=%d upshifts=%d",
                label,
                tier100Frames,
                tier90Frames,
                tier80Frames,
                tier70Frames,
                tier60Frames,
                tier50Frames,
                tier40Frames,
                tier33Frames,
                tierSwitches,
                tierDownshifts,
                tierUpshifts));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][render_exact] preview_render_total_ms=%.3f geometry_sig_ms=%.3f lighting_sig_ms=%.3f camera_sig_ms=%.3f depth_clear_ms=%.3f runtime_buffer_ms=%.3f render_setup_ms=%.3f polish_buffer_ms=%.3f hybrid_base_resource_sync_ms=%.3f camera_state_build_ms=%.3f hybrid_base_total_ms=%.3f hybrid_base_setup_ms=%.3f hybrid_base_prepare_ms=%.3f hybrid_base_binning_ms=%.3f hybrid_base_raster_ms=%.3f hybrid_base_output_ms=%.3f hybrid_base_child_sum_ms=%.3f hybrid_base_child_delta_ms=%.3f rt_render_overhead_ms=%.3f polish_trace_total_ms=%.3f polish_resolve_total_ms=%.3f polish_reuse_compose_total_ms=%.3f carrier_resolve_total_ms=%.3f auto_sched_ms=%.3f exact_child_sum_ms=%.3f exact_delta_ms=%.3f",
                label,
                previewRenderTotalMs,
                geometrySignatureMs,
                lightingSignatureMs,
                cameraSignatureMs,
                depthClearMs,
                runtimeBufferMs,
                renderSetupMs,
                polishBufferMs,
                hybridBaseResourceSyncMs,
                cameraStateBuildMs,
                hybridBaseTotalMs,
                hybridBaseSetupMs,
                hybridBasePrepareMs,
                hybridBaseBinningMs,
                hybridBaseRasterMs,
                hybridBaseOutputMs,
                hybridBaseChildSumMs,
                hybridBaseChildDeltaMs,
                rtRenderOverheadMs,
                polishTraceTotalMs,
                polishResolveTotalMs,
                polishReuseComposeTotalMs,
                carrierResolveTotalMs,
                autoSchedMs,
                exactChildSumMs,
                exactDeltaMs));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][camera_sig_leaf] extract_ms=%.3f projection_ms=%.3f hash_ms=%.3f delta_ms=%.3f compare_ms=%.3f reset_apply_ms=%.3f child_sum_ms=%.3f child_delta_ms=%.3f",
                label,
                cameraSigExtractMs,
                cameraSigProjectionMs,
                cameraSigHashMs,
                cameraSigDeltaMs,
                cameraSigCompareMs,
                cameraSigResetApplyMs,
                cameraSigChildSumMs,
                cameraSigChildDeltaMs));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][autosched_leaf] tile_cost_ms=%.3f hw_sample_ms=%.3f smoothing_ms=%.3f threshold_ms=%.3f decision_ms=%.3f hw_sample_hits=%d child_sum_ms=%.3f child_delta_ms=%.3f",
                label,
                autoSchedTileCostMs,
                autoSchedHardwareSampleMs,
                autoSchedSmoothingMs,
                autoSchedThresholdMs,
                autoSchedDecisionMs,
                autoSchedHardwareSampleHits,
                autoSchedChildSumMs,
                autoSchedChildDeltaMs));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][polish_resolve_exact] direct_pack_ms=%.3f resolve_lowres_ms=%.3f upscale_pack_ms=%.3f map_build_ms=%.3f still_cache_ms=%.3f reuse_compose_ms=%.3f child_sum_ms=%.3f child_delta_ms=%.3f",
                label,
                polishResolveDirectPackMs,
                polishResolveResolveLowResMs,
                polishResolveUpscalePackMs,
                polishResolveMapBuildMs,
                polishResolveStillCacheMs,
                polishReuseComposeTotalMs,
                polishResolveChildSumMs,
                polishResolveChildDeltaMs));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][base_leaf] prepare_ms=%.3f binning_ms=%.3f raster_ms=%.3f depth_ms=%.3f shading_ms=%.3f direct_light_ms=%.3f material_ms=%.3f guides_ms=%.3f framebuffer_write_ms=%.3f",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_PREPARE_TRANSFORM_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_TILE_BINNING_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_RASTER_FILL_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_DEPTH_TEST_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_SHADING_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_DIRECT_LIGHT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_MATERIAL_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_GUIDES_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_BASE_FRAMEBUFFER_WRITE_NS)));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][base_output_exact] reduced_shade_ms=%.3f reduced_shade_compute_ms=%.3f reduced_shade_store_ms=%.3f inactive_scan_ms=%.3f guide_precheck_ms=%.3f normal_fetch_ms=%.3f upscale_sampling_ms=%.3f edge_weight_ms=%.3f final_write_ms=%.3f map_build_ms=%.3f guided_upscale_ms=%.3f fast_pixels=%d edge_pixels=%d child_sum_ms=%.3f child_delta_ms=%.3f",
                label,
                hybridBaseReducedShadeMs,
                hybridBaseReducedShadeComputeMs,
                hybridBaseReducedShadeStoreMs,
                hybridBaseInactiveScanMs,
                hybridBaseGuidePrecheckMs,
                0.0,
                hybridBaseFastUpscaleMs,
                hybridBaseEdgeWeightMs,
                hybridBaseFinalWriteMs,
                hybridBaseUpscaleMapBuildMs,
                hybridBaseGuidedUpscaleMs,
                hybridBaseFastPathPixels,
                hybridBaseEdgePathPixels,
                hybridBaseReducedShadeMs
                        + hybridBaseInactiveScanMs
                        + hybridBaseGuidePrecheckMs
                        + hybridBaseFastUpscaleMs
                        + hybridBaseEdgeWeightMs
                        + hybridBaseFinalWriteMs
                        + hybridBaseUpscaleMapBuildMs,
                hybridBaseOutputMs - (hybridBaseReducedShadeMs
                        + hybridBaseInactiveScanMs
                        + hybridBaseGuidePrecheckMs
                        + hybridBaseFastUpscaleMs
                        + hybridBaseEdgeWeightMs
                        + hybridBaseFinalWriteMs
                        + hybridBaseUpscaleMapBuildMs)));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][carrier_leaf] traversal_ms=%.3f surface_ms=%.3f direct_ms=%.3f shadow_ms=%.3f env_ms=%.3f extra_lobes_ms=%.3f dir_light_ms=%.3f point_light_ms=%.3f spot_light_ms=%.3f area_light_ms=%.3f local_candidates=%d local_shaded=%d local_shadowed=%d",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_PRIMARY_INTERSECTION_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SURFACE_SAMPLE_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DIRECT_LIGHT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SHADOW_QUERY_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_ENVIRONMENT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_EXTRA_MATERIAL_LOBES_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DIRECTIONAL_LIGHT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_POINT_LIGHT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SPOT_LIGHT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_AREA_LIGHT_NS),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_CANDIDATES, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_SHADED, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_SHADOWED, Math.max(1L, snapshot.frameCount(kind)))));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][pt_leaf] path_bounce_ms=%.3f direct_ms=%.3f shadow_ms=%.3f directional_ms=%.3f local_ms=%.3f env_light_ms=%.3f emissive_light_ms=%.3f local_candidates=%d local_shaded=%d local_shadowed=%d",
                label,
                ptPathBounceMs,
                ptDirectLightMs,
                ptShadowQueryMs,
                ptDirectionalMs,
                ptPointMs,
                ptEnvironmentLightMs,
                ptEmissiveLightMs,
                ptLocalCandidates,
                ptLocalShaded,
                ptLocalShadowed));
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][carrier_resolve_leaf] metrics_ms=%.3f from_accum_ms=%.3f base_copy_ms=%.3f depth_copy_ms=%.3f cached_polish_compose_ms=%.3f cached_polish_fetch_ms=%.3f cached_polish_blend_ms=%.3f temporal_ms=%.3f output_write_ms=%.3f seed_ms=%.3f noise_profile_ms=%.3f filter_ms=%.3f commit_ms=%.3f denoise_passes=%d hot_pixels=%d edge_taps=%d",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_METRICS_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_FROM_ACCUM_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_BASE_COPY_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_DEPTH_COPY_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_COMPOSE_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_FETCH_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_BLEND_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_TEMPORAL_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_OUTPUT_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_SEED_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_NOISE_PROFILE_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_FILTER_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_COMMIT_NS),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_PASS_COUNT, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_HOT_PIXEL_PIXELS, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_EDGE_TAPS, Math.max(1L, snapshot.frameCount(kind)))));
            System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][moving_polish_compose_exact] full_frame_scan_ms=%.3f region_mask_ms=%.3f identity_checks_ms=%.3f cached_fetch_ms=%.3f blend_write_ms=%.3f passthrough_ms=%.3f active_pixels=%d passthrough_pixels=%d active_tile_pixels=%d",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FULL_FRAME_SCAN_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_REGION_MASK_BUILD_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_IDENTITY_CHECK_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FETCH_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_BLEND_WRITE_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_NS),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_PIXELS, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_PIXELS, Math.max(1L, snapshot.frameCount(kind))),
                averageCounter(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_TILE_PIXELS, Math.max(1L, snapshot.frameCount(kind)))));
            if (mode == RenderMode.RAY_TRACING && engine != null && engine.rayTracerRenderer != null) {
                System.out.println(String.format(
                    Locale.ROOT,
                    "Interactive[%s][moving_polish_generation_sync] frame_generation=%d base_generation=%d polish_generation=%d compose_generation=%d compose_base_generation=%d compose_polish_generation=%d generation_mismatch_frames=%d cache_reuse_generation_mismatch_frames=%d stale_active_pixels=%d skipped_reuse_frames=%d partial_active_region_frames=%d",
                    label,
                    engine.rayTracerRenderer.getActivePreviewFrameGeneration(),
                    engine.rayTracerRenderer.getActiveBaseCarrierGeneration(),
                    engine.rayTracerRenderer.getActivePolishCompositeGeneration(),
                    engine.rayTracerRenderer.getLastComposedFrameGeneration(),
                    engine.rayTracerRenderer.getLastComposedBaseGeneration(),
                    engine.rayTracerRenderer.getLastComposedPolishGeneration(),
                    movingComposeGenerationMismatches,
                    movingComposeCacheReuseGenerationMismatches,
                    movingComposeStaleActivePixels,
                    movingComposeSkippedReuseFrames,
                    movingComposePartialActiveRegionFrames));
            }
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][polish_cache_leaf] packed_cache_rebuild_ms=%.3f packed_cache_reuse_ms=%.3f still_cache_work_ms=%.3f",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_PACKED_CACHE_REBUILD_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_PACKED_CACHE_REUSE_NS),
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_STILL_CACHE_WORK_NS)));
        boolean mapReuseActive = engine != null
                && mode == RenderMode.RAY_TRACING
                && engine.rayTracerRenderer != null
                && engine.rayTracerRenderer.isActivePolishUpscaleMapReuseActive();
        long mapBytes = engine != null
                && mode == RenderMode.RAY_TRACING
                && engine.rayTracerRenderer != null
                ? engine.rayTracerRenderer.getActivePolishUpscaleMapMemoryBytes()
                : 0L;
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][polish_map] map_build_ms=%.3f map_reuse_active=%s map_invalidations=%d map_bytes=%d",
                label,
                averageCounterMs(snapshot, kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_UPSCALE_MAP_BUILD_NS),
                mapReuseActive,
                snapshot.totalCounter(kind, RuntimeInstrumentation.Counter.PREVIEW_POLISH_UPSCALE_MAP_INVALIDATIONS),
                mapBytes));
        System.out.println("Interactive[" + label + "][mode_counts] " + formatMap(snapshot.modeCounts(kind)));
        System.out.println("Interactive[" + label + "][fallback_counts] " + formatMap(snapshot.fallbackCounts(kind)));
        System.out.println("Interactive[" + label + "][mode_timeline] " + formatTimeline(snapshot.recentModeTimeline(kind)));
        System.out.println("Interactive[" + label + "][fallback_timeline] " + formatTimeline(snapshot.recentFallbackTimeline(kind)));
    }

    private static void printConfig(String label, RenderMode mode, Engine engine, PreviewState previewState) {
        int frameW = engine != null && engine.window != null && engine.window.getFrame() != null ? engine.window.getFrame().getWidth() : 0;
        int frameH = engine != null && engine.window != null && engine.window.getFrame() != null ? engine.window.getFrame().getHeight() : 0;
        int canvasW = engine != null && engine.window != null && engine.window.getCanvas() != null ? engine.window.getCanvas().getWidth() : 0;
        int canvasH = engine != null && engine.window != null && engine.window.getCanvas() != null ? engine.window.getCanvas().getHeight() : 0;
        int renderW = engine != null && engine.frameBuffer != null ? engine.frameBuffer.getWidth() : 0;
        int renderH = engine != null && engine.frameBuffer != null ? engine.frameBuffer.getHeight() : 0;
        double polishScale = 1.0;
        double configuredMotionPolishScale = 1.0;
        double baseShadeScale = 1.0;
        double configuredMotionBaseShadeScale = 1.0;
        int polishW = 0;
        int polishH = 0;
        int baseShadeW = 0;
        int baseShadeH = 0;
        if (engine != null && mode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            polishScale = engine.rayTracerRenderer.getActivePreviewPolishScale();
            configuredMotionPolishScale = engine.rayTracerRenderer.getConfiguredPreviewMotionPolishScale();
            baseShadeScale = engine.rayTracerRenderer.getActiveHybridBaseShadingScale();
            configuredMotionBaseShadeScale = engine.rayTracerRenderer.getConfiguredPreviewMotionBaseShadingScale();
            polishW = engine.rayTracerRenderer.getActivePreviewPolishWidth();
            polishH = engine.rayTracerRenderer.getActivePreviewPolishHeight();
            baseShadeW = engine.rayTracerRenderer.getActiveHybridBaseShadingWidth();
            baseShadeH = engine.rayTracerRenderer.getActiveHybridBaseShadingHeight();
        }
        System.out.println(String.format(
                Locale.ROOT,
                "Interactive[%s][config][%s] fullscreen=%s frame=%dx%d canvas=%dx%d render=%dx%d explicit_render=%dx%d base_shade=%dx%d base_shade_scale=%.2f configured_motion_base_shade_scale=%.2f polish=%dx%d polish_scale=%.2f configured_motion_polish_scale=%.2f debug_hud=%s editor_overlay=%s render_scale=%.2f effective_scale=%.2f interactive_min_scale=%.2f accumulated_samples=%d quality_tier=%s",
                label,
                mode,
                engine != null && engine.window != null && engine.window.isFullscreen(),
                frameW,
                frameH,
                canvasW,
                canvasH,
                renderW,
                renderH,
                engine != null ? engine.getExplicitPreviewRenderWidth() : 0,
                engine != null ? engine.getExplicitPreviewRenderHeight() : 0,
                baseShadeW,
                baseShadeH,
                baseShadeScale,
                configuredMotionBaseShadeScale,
                polishW,
                polishH,
                polishScale,
                configuredMotionPolishScale,
                engine != null && engine.debugOverlayEnabled,
                engine != null && engine.editorOverlayEnabled,
                engine != null ? engine.renderScale : 0.0,
                engine != null ? engine.effectiveRenderScale() : 0.0,
                engine != null ? engine.interactiveRenderScale : 0.0,
                previewState == null ? 0L : previewState.accumulatedSamples(),
                previewState == null ? "UNKNOWN" : previewState.qualityTier()));
    }

    private static String formatMap(Map<String, Long> values) {
        return values == null || values.isEmpty() ? "{}" : values.toString();
    }

    private static String formatTimeline(List<String> timeline) {
        if (timeline == null || timeline.isEmpty()) {
            return "[]";
        }
        int from = Math.max(0, timeline.size() - 12);
        return timeline.subList(from, timeline.size()).toString();
    }

    private static double averageScale(RuntimeInstrumentation.Snapshot snapshot,
                                       RuntimeInstrumentation.FrameKind kind,
                                       RuntimeInstrumentation.Counter counter,
                                       long divisor) {
        if (snapshot == null || counter == null || divisor <= 0L) {
            return 0.0;
        }
        return snapshot.totalCounter(kind, counter) / 1000.0 / divisor;
    }

    private static long averageCounter(RuntimeInstrumentation.Snapshot snapshot,
                                       RuntimeInstrumentation.FrameKind kind,
                                       RuntimeInstrumentation.Counter counter,
                                       long divisor) {
        if (snapshot == null || counter == null || divisor <= 0L) {
            return 0L;
        }
        return Math.round(snapshot.totalCounter(kind, counter) / (double) divisor);
    }

    private static double averageCounterMs(RuntimeInstrumentation.Snapshot snapshot,
                                           RuntimeInstrumentation.FrameKind kind,
                                           RuntimeInstrumentation.Counter counter) {
        if (snapshot == null || counter == null || snapshot.frameCount(kind) <= 0L) {
            return 0.0;
        }
        return snapshot.totalCounter(kind, counter) / 1_000_000.0 / snapshot.frameCount(kind);
    }

    private static String activatedTierList(long tier100Frames,
                                            long tier90Frames,
                                            long tier80Frames,
                                            long tier70Frames,
                                            long tier60Frames,
                                            long tier50Frames,
                                            long tier40Frames,
                                            long tier33Frames) {
        StringBuilder sb = new StringBuilder();
        if (tier100Frames > 0) {
            sb.append("100,");
        }
        if (tier90Frames > 0) {
            sb.append("90,");
        }
        if (tier80Frames > 0) {
            sb.append("80,");
        }
        if (tier70Frames > 0) {
            sb.append("70,");
        }
        if (tier60Frames > 0) {
            sb.append("60,");
        }
        if (tier50Frames > 0) {
            sb.append("50,");
        }
        if (tier40Frames > 0) {
            sb.append("40,");
        }
        if (tier33Frames > 0) {
            sb.append("33,");
        }
        if (sb.length() == 0) {
            return "none";
        }
        sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static final class DynamicPhasedStats {
        private final long startNanos = System.nanoTime();
        private final LinkedHashSet<Integer> activatedTiers = new LinkedHashSet<>();
        private final List<String> switchTimeline = new ArrayList<>();
        private final List<String> phaseSamples = new ArrayList<>();
        private final List<String> motionTimeline = new ArrayList<>();
        private final List<String> qualityTierTimeline = new ArrayList<>();
        private final List<String> emergencyLatchTimeline = new ArrayList<>();
        private final List<String> recoveryTimeline = new ArrayList<>();
        private int lastTierPercent = Integer.MIN_VALUE;
        private boolean lastMotionActive = false;
        private String lastQualityTier = "";
        private boolean lastEmergencyLatch = false;
        private String lastBlockReason = "";
        private long sampleCount = 0L;
        private long motionActiveSamples = 0L;
        private long stillSamples = 0L;
        private double smoothedFrameMsSum = 0.0;

        private long elapsedMs() {
            return (System.nanoTime() - startNanos) / 1_000_000L;
        }

        private void capture(long elapsedMs,
                             String phaseLabel,
                             int tierPercent,
                             boolean motionActive,
                             double smoothedFrameMs,
                             long samples,
                             int stableFrames,
                             int sampleGateFrames,
                             int sampleGate,
                             String qualityTier,
                             boolean emergencyLatch,
                             boolean stableForRecovery,
                             String blockReason,
                             double overloadRatio) {
            sampleCount++;
            smoothedFrameMsSum += smoothedFrameMs;
            if (motionActive) {
                motionActiveSamples++;
            } else {
                stillSamples++;
            }
            activatedTiers.add(tierPercent);
            String normalizedTier = qualityTier == null ? "UNKNOWN" : qualityTier;
            String normalizedBlockReason = blockReason == null ? "" : blockReason;
            if (lastTierPercent != tierPercent) {
                switchTimeline.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s tier=%d motion=%s smooth_ms=%.3f samples=%d",
                        elapsedMs,
                        phaseLabel,
                        tierPercent,
                        motionActive,
                        smoothedFrameMs,
                        samples));
                lastTierPercent = tierPercent;
            }
            if (sampleCount == 1L || lastMotionActive != motionActive) {
                motionTimeline.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s motion=%s",
                        elapsedMs,
                        phaseLabel,
                        motionActive));
                lastMotionActive = motionActive;
            }
            if (sampleCount == 1L || !lastQualityTier.equals(normalizedTier)) {
                qualityTierTimeline.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s quality_tier=%s samples=%d",
                        elapsedMs,
                        phaseLabel,
                        normalizedTier,
                        samples));
                lastQualityTier = normalizedTier;
            }
            if (sampleCount == 1L || lastEmergencyLatch != emergencyLatch) {
                emergencyLatchTimeline.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s emergency_latch=%s quality_tier=%s",
                        elapsedMs,
                        phaseLabel,
                        emergencyLatch,
                        normalizedTier));
                lastEmergencyLatch = emergencyLatch;
            }
            if (sampleCount == 1L || !lastBlockReason.equals(normalizedBlockReason) || sampleCount % 8L == 0L) {
                recoveryTimeline.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s stableForRecovery=%s recover_stable=%d recover_sample_frames=%d sample_gate=%d overload_ratio=%.3f block_reason=%s",
                        elapsedMs,
                        phaseLabel,
                        stableForRecovery,
                        stableFrames,
                        sampleGateFrames,
                        sampleGate,
                        overloadRatio,
                        normalizedBlockReason));
                lastBlockReason = normalizedBlockReason;
            }
            if (phaseSamples.size() < 48 || sampleCount % 10L == 0L) {
                phaseSamples.add(String.format(
                        Locale.ROOT,
                        "t=%dms phase=%s tier=%d motion=%s quality_tier=%s smooth_ms=%.3f samples=%d recover_stable=%d recover_sample_frames=%d sample_gate=%d emergency_latch=%s block_reason=%s overload_ratio=%.3f",
                        elapsedMs,
                        phaseLabel,
                        tierPercent,
                        motionActive,
                        normalizedTier,
                        smoothedFrameMs,
                        samples,
                        stableFrames,
                        sampleGateFrames,
                        sampleGate,
                        emergencyLatch,
                        normalizedBlockReason,
                        overloadRatio));
            }
        }

        private String activatedTierList() {
            if (activatedTiers.isEmpty()) {
                return "none";
            }
            StringBuilder sb = new StringBuilder();
            for (int tier : activatedTiers) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(tier);
            }
            return sb.toString();
        }

        private double averageSmoothedFrameMs() {
            return sampleCount <= 0L ? 0.0 : smoothedFrameMsSum / sampleCount;
        }
    }

    private record PreviewState(long accumulatedSamples, String qualityTier) {
    }

    private static int resolutionTierPercent(Engine engine) {
        if (engine == null) {
            return 100;
        }
        int index = Math.max(0, Math.min(DYNAMIC_TIER_PERCENTS.length - 1, engine.viewportDynamicResolutionTierIndex));
        return DYNAMIC_TIER_PERCENTS[index];
    }

    private static String formatStartupEvent(Engine engine, long eventNanos) {
        if (engine == null || engine.startupAppStartNanos <= 0L || eventNanos <= 0L) {
            return "n/a";
        }
        return String.format(Locale.ROOT, "%.3fms", Math.max(0.0, (eventNanos - engine.startupAppStartNanos) / 1_000_000.0));
    }
}
