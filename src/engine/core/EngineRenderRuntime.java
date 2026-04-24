package engine.core;

import java.util.Arrays;

import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.preview.ProgressiveRenderDefaults;
import engine.render.ray.core.RayTracerRenderer;
import engine.util.FastAAUtil;
import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;

final class EngineRenderRuntime {
    private static final double[] PREVIEW_RENDER_SCALE_TIERS = {1.00, 0.90, 0.80, 0.70, 0.60};
    private static final double[] DYNAMIC_RESOLUTION_TIERS = {1.00, 0.90, 0.80, 0.70, 0.60, 0.50, 0.40, 0.33, 0.25, 0.20, 0.16, 0.12};
    private static final double[] PREVIEW_OUTPUT_RESOLUTION_STAGES = {1.00, 0.90, 0.80, 0.70, 0.55, 0.33};
    private static final int PREVIEW_OUTPUT_RESOLUTION_DEGRADE_START_TIER = 2;
    private static final int DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES = 30;
    private static final int DYNAMIC_RESOLUTION_MAX_INDEX = DYNAMIC_RESOLUTION_TIERS.length - 1;
    private static final long DYNAMIC_RESOLUTION_DOWNSHIFT_DWELL_NS = 80_000_000L;
    private static final long DYNAMIC_RESOLUTION_UPSHIFT_DWELL_NS = 900_000_000L;
    private static final long DYNAMIC_RESOLUTION_MOTION_UPSHIFT_DWELL_NS = 420_000_000L;
    private static final int DYNAMIC_RESOLUTION_DOWNSHIFT_ARM_FRAMES = 1;
    private static final int DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES = 10;
    private static final int DYNAMIC_RESOLUTION_MOTION_UPSHIFT_ARM_FRAMES = 6;
    private static final int[] DYNAMIC_RESOLUTION_RECOVER_SAMPLE_GATE = {0, 120, 96, 72, 48, 24, 16, 10, 6, 4, 3, 2};
    private static final double[] DYNAMIC_RESOLUTION_DOWNSHIFT_OVERLOAD = {1.03, 1.08, 1.14, 1.22, 1.32, 1.50, 1.72, 1.95, 2.08, 2.25, 2.45, 99.0};
    private static final double[] DYNAMIC_RESOLUTION_UPSHIFT_OVERLOAD = {0.0, 1.08, 1.18, 1.35, 1.55, 1.75, 1.95, 2.10, 2.30, 2.45, 2.65, 2.85};
    private static final int HEAVY_IDLE_WARMUP_SAMPLE_GATE = 12;
    private static final int HEAVY_IDLE_DEEP_STARVATION_SAMPLE_GATE = 6;
    private static final double HEAVY_IDLE_WARMUP_OVERLOAD_MARGIN = 0.10;
    private static final double HEAVY_LARGE_VIEWPORT_MP = 2.5;
    private static final double HEAVY_HUGE_VIEWPORT_MP = 3.0;
    private static final double[] DYNAMIC_MOVING_BASE_SHADING_SCALE = {1.00, 0.90, 0.80, 0.68, 0.56, 0.46, 0.36, 0.28, 0.20, 0.16, 0.12, 0.10};
    private static final double[] DYNAMIC_MOVING_POLISH_SCALE = {0.70, 0.52, 0.40, 0.30, 0.22, 0.16, 0.12, 0.08, 0.05, 0.04, 0.03, 0.02};
    private static final int[] DYNAMIC_MOVING_SECONDARY_CADENCE = {1, 3, 4, 5, 6, 7, 8, 8, 9, 10, 11, 12};
    private static final int[] DYNAMIC_MOVING_DENOISE_CADENCE = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
    private static final long INTERACTION_LINGER_NS = 220_000_000L;
    private static final long VIEWPORT_MOTION_ENTRY_BOOST_NS = 260_000_000L;
    private static final int VIEWPORT_MOTION_ENTRY_MIN_TIER = 4;
    private static final int VIEWPORT_MOTION_ENTRY_PROFILE_MIN_TIER = 5;
    private static final long VIEWPORT_MOTION_EXIT_HYSTERESIS_NS = 420_000_000L;
    private static final int VIEWPORT_MOTION_EXIT_STABLE_FRAMES = 2;
    private static final long CRITICAL_FALLBACK_DELAY_NS = 900_000_000L;
    private static final long PATH_FALLBACK_DELAY_NS = 240_000_000L;
    private static final double PATH_FALLBACK_FRAME_MULT = 1.35;
    private static final double PATH_FALLBACK_PRESSURE_SECONDS = 1.3;
    private static final double MIN_VIEWPORT_TARGET_FPS = 12.0;
    private static final double MAX_VIEWPORT_TARGET_FPS = 25.0;
    private static final double HEAVY_SCALE_STEP = 0.04;
    private static final double GENERAL_SCALE_STEP = 0.05;
    private static final long VIEWPORT_WARMUP_NS = 320_000_000L;
    private static final long WARMUP_SEED_MAX_LIFETIME_NS = 1_300_000_000L;
    private static final double WARMUP_SEED_DECAY_PER_FRAME = 0.92;
    private static final double WARMUP_SEED_DECAY_IDLE = 0.74;
    private static final double VIEWPORT_TARGET_MS_RAY_INTERACTIVE = 16.0;
    private static final double VIEWPORT_TARGET_MS_PATH_INTERACTIVE = 19.0;
    private static final double VIEWPORT_TARGET_MS_RAY_IDLE = 23.0;
    private static final double VIEWPORT_TARGET_MS_PATH_IDLE = 27.0;
    private static final double VIEWPORT_SOFT_FLOOR_FPS = 24.0;
    private static final double VIEWPORT_SOFT_FLOOR_MS = 1000.0 / VIEWPORT_SOFT_FLOOR_FPS;
    private static final double VIEWPORT_HARD_FLOOR_FPS = 12.0;
    private static final double VIEWPORT_HARD_FLOOR_MS = 1000.0 / VIEWPORT_HARD_FLOOR_FPS;
    private static final double HEAVY_MOTION_MIN_DYNAMIC_SCALE = 0.12;
    private static final double HEAVY_IDLE_RESPONSIVE_FRAME_MULT = 1.75;
    private static final long CRITICAL_FALLBACK_MIN_HOLD_NS = 280_000_000L;
    private static final long CRITICAL_FALLBACK_RECOVERY_NS = 360_000_000L;
    private static final long SAFETY_REENTRY_GUARD_NS = 1_500_000_000L;
    private static final long HEAVY_STALE_GAP_NS = 120_000_000L;
    private static final boolean FORCE_SHARP_RAY_MOTION = false;

    private EngineRenderRuntime() {
    }

    static void toggleFrustumCulling(Engine engine) {
        engine.frustumCullingEnabled = !engine.frustumCullingEnabled;
        setPreviewRasterParameter(engine, "frustumCulling", engine.frustumCullingEnabled);
        System.out.println("Frustum culling: " + (engine.frustumCullingEnabled ? "ON" : "OFF"));
    }

    static void toggleBackfaceCulling(Engine engine) {
        engine.backfaceCullingEnabled = !engine.backfaceCullingEnabled;
        setPreviewRasterParameter(engine, "backfaceCulling", engine.backfaceCullingEnabled);
        System.out.println("Backface culling: " + (engine.backfaceCullingEnabled ? "ON" : "OFF"));
    }

    static void togglePhysics(Engine engine) {
        engine.physicsEnabled = !engine.physicsEnabled;
        System.out.println("Physics: " + (engine.physicsEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void toggleAnimationPlayback(Engine engine) {
        engine.animationPlaybackEnabled = !engine.animationPlaybackEnabled;
        System.out.println("Animation: " + (engine.animationPlaybackEnabled ? "PLAY" : "PAUSE"));
        engine.refreshUiIndicators();
    }

    static void togglePostAA(Engine engine) {
        engine.postAAEnabled = !engine.postAAEnabled;
        rebuildPostPipeline(engine);
        System.out.println("Post AA: " + (engine.postAAEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void setDitherStyle(Engine engine, DitherRenderer.DitherStyle style) {
        if (engine.ditherRenderer == null || style == null) {
            return;
        }
        engine.ditherRenderer.setParameter("style", style);
        System.out.println("Dither style: " + style);
        engine.refreshUiIndicators();
    }

    static void cycleDitherStyle(Engine engine) {
        if (engine.ditherRenderer == null) {
            return;
        }
        DitherRenderer.DitherStyle current = engine.ditherRenderer.getStyle();
        DitherRenderer.DitherStyle next = switch (current) {
            case BLUE_NOISE -> DitherRenderer.DitherStyle.PATTERN;
            case PATTERN -> DitherRenderer.DitherStyle.ASCII;
            default -> DitherRenderer.DitherStyle.BLUE_NOISE;
        };
        setDitherStyle(engine, next);
        if (engine.activeMode == RenderMode.DITHERING) {
            engine.refreshUiIndicators();
        }
    }

    static void setTemporalNoiseMode(Engine engine, TemporalNoiseRenderer.NoiseMode mode) {
        if (engine.temporalNoiseRenderer == null || mode == null) {
            return;
        }
        engine.temporalNoiseRenderer.setParameter("mode", mode);
        System.out.println("Temporal mode: " + mode);
    }

    static void cycleTemporalNoiseMode(Engine engine) {
        if (engine.temporalNoiseRenderer == null) {
            return;
        }
        TemporalNoiseRenderer.NoiseMode current = engine.temporalNoiseRenderer.getMode();
        TemporalNoiseRenderer.NoiseMode next = switch (current) {
            case OBJECT_MASK -> TemporalNoiseRenderer.NoiseMode.FACE_FLOW;
            case FACE_FLOW -> TemporalNoiseRenderer.NoiseMode.CAMERA_RELATIVE;
            default -> TemporalNoiseRenderer.NoiseMode.OBJECT_MASK;
        };
        setTemporalNoiseMode(engine, next);
    }

    static void cycleTemporalNoiseGrainPreset(Engine engine) {
        if (engine.temporalNoiseRenderer == null) {
            return;
        }
        engine.temporalGrainCellSize = TemporalNoiseRenderer.nextGrainCellSizePreset(engine.temporalGrainCellSize);
        engine.temporalNoiseRenderer.setParameter("grainCellSize", engine.temporalGrainCellSize);
        System.out.println("Temporal grain: " + TemporalNoiseRenderer.grainCellSizePresetLabel(engine.temporalGrainCellSize));
        engine.refreshUiIndicators();
        if (engine.window != null) {
            EngineViewportRenderTabBuilder.build(engine);
        }
    }

    static void cycleHexWowMode(Engine engine) {
        if (engine.hexMosaicRenderer == null) {
            return;
        }
        engine.hexMosaicRenderer.setParameter("cycleWowMode", true);
        if (engine.activeMode == RenderMode.HEX_MOSAIC) {
            System.out.println("Hex style: " + engine.hexMosaicRenderer.getName());
        }
    }

    static void toggleHexDebugCells(Engine engine) {
        if (engine.hexMosaicRenderer == null) {
            return;
        }
        engine.hexDebugCells = !engine.hexDebugCells;
        engine.hexMosaicRenderer.setParameter("debugCells", engine.hexDebugCells);
        System.out.println("Hex debug cells: " + (engine.hexDebugCells ? "ON" : "OFF"));
    }

    static void toggleDebugOverlay(Engine engine) {
        engine.debugOverlayEnabled = !engine.debugOverlayEnabled;
        System.out.println("Debug overlay: " + (engine.debugOverlayEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void toggleEditorOverlay(Engine engine) {
        engine.editorOverlayEnabled = !engine.editorOverlayEnabled;
        System.out.println("Editor overlay: " + (engine.editorOverlayEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void rebuildPostPipeline(Engine engine) {
        if (engine.renderPipeline == null) {
            return;
        }
        engine.renderPipeline.clearPostProcessors();
        if (engine.postAAEnabled && engine.postAAPostProcessor != null) {
            engine.renderPipeline.addPostProcessor(engine.postAAPostProcessor);
        }
    }

    static void toggleUpscaleFilter(Engine engine) {
        engine.smoothUpscaling = !engine.smoothUpscaling;
        if (engine.window != null) {
            engine.window.setSmoothUpscaling(engine.smoothUpscaling);
        }
        System.out.println("Upscale filter: " + (engine.smoothUpscaling ? "SMOOTH" : "SHARP"));
    }

    static void toggleParallelRaster(Engine engine) {
        if (engine.parallelWorkerCount <= 1) {
            engine.parallelRasterEnabled = false;
            setPreviewRasterParameter(engine, "parallel", false);
            System.out.println("Multi-thread raster unavailable (worker count = 1).");
            engine.refreshUiIndicators();
            return;
        }
        engine.parallelRasterEnabled = !engine.parallelRasterEnabled;
        setPreviewRasterParameter(engine, "parallel", engine.parallelRasterEnabled);
        System.out.println("Multi-thread raster: " + (engine.parallelRasterEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void adjustWorkerCount(Engine engine, int delta) {
        int max = ThreadPool.recommendedWorkerCount();
        engine.parallelWorkerCount = Math.max(1, Math.min(max, engine.parallelWorkerCount + delta));
        boolean parallel = engine.parallelRasterEnabled && engine.parallelWorkerCount > 1;
        applyPreviewRasterParallelSettings(engine, engine.parallelWorkerCount, parallel);
        engine.rayTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.pathTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        if (engine.parallelWorkerCount <= 1) {
            engine.parallelRasterEnabled = false;
        }
        System.out.println("Workers: " + engine.parallelWorkerCount + " / " + max);
        engine.refreshUiIndicators();
    }

    static void cycleRenderScale(Engine engine) {
        double current = clamp(engine.renderScale, PREVIEW_RENDER_SCALE_TIERS[PREVIEW_RENDER_SCALE_TIERS.length - 1], PREVIEW_RENDER_SCALE_TIERS[0]);
        for (int i = 0; i < PREVIEW_RENDER_SCALE_TIERS.length; i++) {
            if (Math.abs(current - PREVIEW_RENDER_SCALE_TIERS[i]) <= 0.005) {
                engine.renderScale = PREVIEW_RENDER_SCALE_TIERS[(i + 1) % PREVIEW_RENDER_SCALE_TIERS.length];
                applyRenderScale(engine, true);
                return;
            }
        }
        engine.renderScale = PREVIEW_RENDER_SCALE_TIERS[1];
        applyRenderScale(engine, true);
    }

    static void handleWindowResizeIfNeeded(Engine engine) {
        if (engine.window == null || engine.perspectiveCamera == null || engine.orthographicCamera == null) {
            return;
        }
        int canvasW = Math.max(1, engine.window.getCanvas().getWidth());
        int canvasH = Math.max(1, engine.window.getCanvas().getHeight());
        if (canvasW == engine.lastCanvasWidth && canvasH == engine.lastCanvasHeight) {
            return;
        }

        engine.lastCanvasWidth = canvasW;
        engine.lastCanvasHeight = canvasH;
        engine.baseWidth = canvasW;
        engine.baseHeight = canvasH;

        double aspect = (double) engine.baseWidth / (double) engine.baseHeight;
        engine.perspectiveCamera.setAspectRatio(aspect);
        engine.orthographicCamera.setHalfHeightWithAspect(engine.orthographicCamera.getHalfHeight(), aspect);

        applyRenderScale(engine, false);
        EngineToolbarController.applyResponsiveToolbarLayout(engine);
        System.out.println("Viewport resized: " + engine.baseWidth + "x" + engine.baseHeight
                + " (render " + engine.frameBuffer.getWidth() + "x" + engine.frameBuffer.getHeight() + ")");
    }

    static void applyRenderScale(Engine engine) {
        applyRenderScale(engine, true);
    }

    static void applyRenderScale(Engine engine, boolean verboseLog) {
        int rw = scaledWidth(engine);
        int rh = scaledHeight(engine);
        boolean sizeChanged = engine.frameBuffer == null
                || engine.frameBuffer.getWidth() != rw
                || engine.frameBuffer.getHeight() != rh;

        if (engine.frameBuffer == null) {
            engine.frameBuffer = new FrameBuffer(rw, rh);
        } else if (sizeChanged) {
            engine.frameBuffer.resize(rw, rh);
        }

        int pixelCount = rw * rh;
        if (engine.postAATemp == null || engine.postAATemp.length != pixelCount) {
            engine.postAATemp = new int[pixelCount];
        }

        if (!sizeChanged) {
            return;
        }

        resizeViewportRenderers(engine, rw, rh);
        resizeHighCostViewportRenderer(engine, rw, rh);
        if (verboseLog) {
            System.out.println("Render scale: " + String.format("%.2f", effectiveRenderScale(engine))
                    + " (" + rw + "x" + rh + ")");
        }
    }

    private static void resizeViewportRenderers(Engine engine, int width, int height) {
        Renderer[] viewportRenderers = {
                engine.rasterRenderer,
                engine.ditherRenderer,
                engine.temporalNoiseRenderer,
                engine.wireframeRenderer,
                engine.hexMosaicRenderer
        };
        for (Renderer renderer : viewportRenderers) {
            if (renderer != null) {
                renderer.resize(width, height);
            }
        }
    }

    private static void resizeHighCostViewportRenderer(Engine engine, int width, int height) {
        RenderMode mode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        if (mode == RenderMode.RAY_TRACING) {
            if (engine.rayTracerRenderer != null) {
                engine.rayTracerRenderer.resize(width, height);
            }
            return;
        }
        if (mode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.resize(width, height);
        }
    }

    static int scaledWidth(Engine engine) {
        if (engine != null
                && engine.getExplicitPreviewRenderWidth() > 0
                && engine.getExplicitPreviewRenderHeight() > 0) {
            return Math.max(320, engine.getExplicitPreviewRenderWidth());
        }
        return scaledViewportSize(engine)[0];
    }

    static int scaledHeight(Engine engine) {
        if (engine != null
                && engine.getExplicitPreviewRenderWidth() > 0
                && engine.getExplicitPreviewRenderHeight() > 0) {
            return Math.max(200, engine.getExplicitPreviewRenderHeight());
        }
        return scaledViewportSize(engine)[1];
    }

    private static int[] scaledViewportSize(Engine engine) {
        if (engine == null) {
            return new int[]{320, 200};
        }
        int baseW = Math.max(1, engine.baseWidth);
        int baseH = Math.max(1, engine.baseHeight);
        double scale = effectiveRenderScale(engine);
        int minW = minimumPreviewWidth(engine);
        int minH = minimumPreviewHeight(engine);
        int width = Math.max(minW, (int) Math.round(baseW * scale));
        int height = Math.max(minH, (int) Math.round(baseH * scale));
        int pixelBudget = movingHeavyPixelBudget(engine);
        if (pixelBudget > 0 && (long) width * (long) height > pixelBudget) {
            double aspect = baseW / (double) baseH;
            int budgetW = Math.max(minW, (int) Math.round(Math.sqrt(pixelBudget * aspect)));
            int budgetH = Math.max(minH, (int) Math.round(budgetW / aspect));
            if ((long) budgetW * (long) budgetH > pixelBudget && budgetH > minH) {
                budgetH = Math.max(minH, pixelBudget / Math.max(1, budgetW));
            }
            width = Math.min(width, budgetW);
            height = Math.min(height, budgetH);
        }
        return new int[]{width, height};
    }

    private static int movingHeavyPixelBudget(Engine engine) {
        if (!usesEmergencyHeavyMotionViewport(engine)) {
            return 0;
        }
        RenderMode mode = engine != null && engine.activeMode != null ? engine.activeMode : RenderMode.PHONG;
        int tier = clampDynamicResolutionTierIndex(engine.viewportDynamicResolutionTierIndex);
        if (mode == RenderMode.RAY_TRACING) {
            return tier >= 11 ? 10_500 : (tier >= 10 ? 14_000 : (tier >= 9 ? 22_500 : 28_000));
        }
        if (mode == RenderMode.PATH_TRACING) {
            return tier >= 11 ? 16_000 : (tier >= 10 ? 22_500 : (tier >= 9 ? 30_000 : 38_000));
        }
        return 0;
    }

    private static int minimumPreviewWidth(Engine engine) {
        if (!usesEmergencyHeavyMotionViewport(engine)) {
            return 320;
        }
        RenderMode mode = engine != null && engine.activeMode != null ? engine.activeMode : RenderMode.PHONG;
        return mode == RenderMode.RAY_TRACING ? 128 : 160;
    }

    private static int minimumPreviewHeight(Engine engine) {
        if (!usesEmergencyHeavyMotionViewport(engine)) {
            return 200;
        }
        RenderMode mode = engine != null && engine.activeMode != null ? engine.activeMode : RenderMode.PHONG;
        return mode == RenderMode.RAY_TRACING ? 72 : 90;
    }

    private static boolean usesEmergencyHeavyMotionViewport(Engine engine) {
        if (engine == null || !engine.progressiveViewportEnabled) {
            return false;
        }
        RenderMode mode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        return isHeavyViewportMode(mode)
                && engine.viewportInteractionActiveLast
                && isViewportMotionActive(engine);
    }

    static double effectiveRenderScale(Engine engine) {
        RenderMode activeMode = engine != null && engine.activeMode != null ? engine.activeMode : RenderMode.PHONG;
        boolean sharpRayMotion = shouldForceSharpRayMotion();
        double scale = (sharpRayMotion ? 1.0 : engine.renderScale) * modeResolutionFactor(engine, activeMode);
        if (!sharpRayMotion && engine.progressiveViewportEnabled && engine.interactiveRenderScaleActive) {
            scale *= interactiveScaleFactor(engine);
        }
        scale *= clamp(engine.safetyViewportScaleClamp, 0.35, 1.0);
        return scale;
    }

    private static double interactiveScaleFactor(Engine engine) {
        RenderMode mode = engine != null && engine.activeMode != null ? engine.activeMode : RenderMode.PHONG;
        boolean heavyMotion = (mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING)
                && engine != null
                && engine.viewportInteractionActiveLast;
 // Allow heavy preview modes to fully use dynamic resolution tiers under motion.
        double minScale = heavyMotion ? HEAVY_MOTION_MIN_DYNAMIC_SCALE : 0.35;
        return clamp(engine.viewportAdaptiveScaleApplied, minScale, 1.0);
    }

    static double modeResolutionFactor(Engine engine, RenderMode mode) {
        return 1.0;
    }

    static void updateRealtimePerformanceState(Engine engine, boolean interactionActive) {
        if (engine == null) {
            return;
        }
        long now = System.nanoTime();
        RenderMode activeMode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        boolean heavyMode = isHeavyViewportMode(activeMode);
        boolean interactionStarted = interactionActive && !engine.viewportInteractionActiveLast;
        engine.viewportInteractionActiveLast = interactionActive;
        if (interactionStarted) {
            engine.viewportWarmupUntilNanos = now + VIEWPORT_WARMUP_NS;
            beginWarmupSampling(engine);
            resetDynamicResolutionDecisionWindow(engine);
            if (heavyMode && engine.progressiveViewportEnabled) {
                engine.viewportMotionEntryBoostUntilNanos = now + VIEWPORT_MOTION_ENTRY_BOOST_NS;
                if (explicitResolutionTierOverride(engine) < 0) {
                    int previousTier = clampDynamicResolutionTierIndex(engine.viewportDynamicResolutionTierIndex);
                    int boostedTier = Math.max(previousTier, VIEWPORT_MOTION_ENTRY_MIN_TIER);
                    if (boostedTier != previousTier) {
                        engine.viewportDynamicResolutionTierIndex = boostedTier;
                        engine.viewportDynamicResolutionSwitchCount++;
                        engine.viewportDynamicResolutionDownshiftCount++;
                        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_SWITCHES, 1L);
                        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS, 1L);
                    }
 // Allow immediate additional downshift in the next heavy-assist step when frame time is bad.
                    engine.viewportDynamicResolutionLastSwitchNanos = now - DYNAMIC_RESOLUTION_DOWNSHIFT_DWELL_NS;
                    engine.viewportDynamicResolutionDownshiftArmFrames = DYNAMIC_RESOLUTION_DOWNSHIFT_ARM_FRAMES;
                }
            }
        }
        if (interactionActive) {
            engine.lastViewportInteractionNanos = now;
        }
        updateViewportMotionHysteresis(engine, now);
        boolean motionActiveNow = isViewportMotionActive(engine)
                || engine.viewportCameraMotionActive
                || engine.viewportSceneMotionActive;
        boolean liveInteractionActive = interactionActive || motionActiveNow;
        if (motionActiveNow) {
            engine.lastViewportInteractionNanos = now;
        }
        if (activeMode == RenderMode.PATH_TRACING
                && engine.pathAccumulationLock
                && !liveInteractionActive
                && !shouldKeepPathLockAdaptiveRescue(engine)) {
            engine.viewportAdaptiveScaleCurrent = 1.0;
            engine.viewportAdaptiveScaleApplied = 1.0;
            engine.viewportScalePressureSeconds = 0.0;
            engine.viewportCriticalPressureSeconds = 0.0;
            engine.viewportCriticalPreviewActive = false;
            engine.viewportCriticalPreviewStartNanos = 0L;
            engine.interactiveRenderScaleActive = false;
 // Keep renderer motion profile synchronized even when accumulation lock short-circuits
 // adaptive scaling updates, otherwise PT can stay in reduced moving settings.
            applyViewportRayPathProfile(
                    engine,
                    activeMode,
                    liveInteractionActive,
                    false,
                    false,
                    now);
            resetViewportAdaptiveAssist(engine);
            return;
        }
        boolean withinInteractionTail = liveInteractionActive || now - engine.lastViewportInteractionNanos < INTERACTION_LINGER_NS;

        double previousAppliedScale = engine.viewportAdaptiveScaleApplied;
        boolean previousCriticalPreview = engine.viewportCriticalPreviewActive;
        if (engine.progressiveViewportEnabled && heavyMode) {
            updateHeavyViewportAssist(engine, now, liveInteractionActive, withinInteractionTail, activeMode);
        } else if (engine.progressiveViewportEnabled && supportsInteractiveViewportScaling(engine, activeMode)) {
            updateAdaptiveViewportAssist(engine, activeMode, now, liveInteractionActive, withinInteractionTail);
        } else {
            resetAdaptiveViewportState(engine, 0.14);
        }

        if (shouldForceSharpRayMotion()) {
            forceFullResolutionViewportScale(engine);
        }

        boolean shouldUseInteractiveScale = engine.viewportAdaptiveScaleApplied < 0.995;
        boolean scaleChanged = Math.abs(previousAppliedScale - engine.viewportAdaptiveScaleApplied) > 1e-6;
        boolean interactiveStateChanged = shouldUseInteractiveScale != engine.interactiveRenderScaleActive;
        boolean criticalPreviewChanged = previousCriticalPreview != engine.viewportCriticalPreviewActive;
 // Keep FAST denoise only for interaction/pressure windows; static heavy modes can stay QUALITY.
        boolean useFastViewportDenoise = withinInteractionTail
            || engine.viewportCriticalPreviewActive
            || shouldUseInteractiveScale;
        applyViewportRayPathProfile(
            engine,
            activeMode,
            liveInteractionActive,
            useFastViewportDenoise,
            engine.viewportCriticalPreviewActive,
            now);
        if (interactiveStateChanged) {
            engine.interactiveRenderScaleActive = shouldUseInteractiveScale;
        }
        if (interactiveStateChanged || scaleChanged || criticalPreviewChanged) {
            applyRenderScale(engine, false);
        }
    }

    private static boolean supportsInteractiveViewportScaling(Engine engine, RenderMode mode) {
        if (mode == null) {
            return false;
        }
        if (mode == RenderMode.DITHERING
                && engine != null
                && engine.ditherRenderer != null
                && engine.ditherRenderer.getStyle() == DitherRenderer.DitherStyle.ASCII) {
            return false;
        }
        return true;
    }

    static RenderMode resolveViewportRenderMode(Engine engine, boolean interactionActive) {
        if (engine == null) {
            return RenderMode.PHONG;
        }
        long now = System.nanoTime();
        RenderMode active = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        boolean heavyMode = active == RenderMode.RAY_TRACING || active == RenderMode.PATH_TRACING;
        if (!heavyMode) {
            engine.viewportFallbackLockActive = false;
            return active;
        }
        if (!isViewportMotionActive(engine)) {
            engine.viewportFallbackLockActive = false;
        }
        if (engine.safetyRecoveryActive) {
            engine.viewportFallbackLockActive = false;
            return active;
        }
        if (heavyMode
                && engine.safetyLastRecoveryNanos > 0L
                && now - engine.safetyLastRecoveryNanos < SAFETY_REENTRY_GUARD_NS) {
            engine.viewportFallbackLockActive = false;
            return active;
        }

        if (!engine.viewportNavigationPreviewEnabled || !interactionActive) {
            engine.viewportFallbackLockActive = false;
            return active;
        }
        engine.viewportFallbackLockActive = false;
        return active;
    }

    static boolean shouldAdvanceDynamicScene(Engine engine, boolean viewportInteractionActive) {
        if (engine == null || !engine.animationPlaybackEnabled) {
            return false;
        }
        RenderMode active = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        if (active != RenderMode.RAY_TRACING && active != RenderMode.PATH_TRACING) {
            return true;
        }
        if (engine.timelineEnabled) {
            return true;
        }
        return viewportInteractionActive;
    }

    static Renderer configureRendererForMode(Engine engine, RenderMode mode) {
        if (engine == null) {
            return null;
        }
        RenderMode safeMode = mode == null ? RenderMode.PHONG : mode;
        return switch (safeMode) {
            case MODEL -> configureMaterialProfile(configureRasterRenderer(engine, true, true), "PHONG");
            case BASIC -> configureMaterialProfile(configureRasterRenderer(engine, true, false), "PHONG");
            case PHONG -> configureMaterialProfile(configureRasterRenderer(engine, false, false), "PHONG");
            case WIREFRAME -> engine.wireframeRenderer;
            case DITHERING -> configureMaterialProfile(engine.ditherRenderer, "DITHER");
            case TEMPORAL_NOISE -> engine.temporalNoiseRenderer;
            case HEX_MOSAIC -> engine.hexMosaicRenderer;
            case RAY_TRACING -> configureMaterialProfile(engine.rayTracerRenderer, "RT");
            case PATH_TRACING -> configureMaterialProfile(engine.pathTracerRenderer, "PT");
            default -> configureMaterialProfile(configureRasterRenderer(engine, false, false), "PHONG");
        };
    }

    private static Renderer configureMaterialProfile(Renderer renderer, String profile) {
        if (renderer != null && profile != null && !profile.isBlank()) {
            renderer.setParameter("materialProfile", profile);
        }
        return renderer;
    }

    private static void setPreviewRasterParameter(Engine engine, String name, Object value) {
        if (engine == null || name == null) {
            return;
        }
        if (engine.rasterRenderer != null) {
            engine.rasterRenderer.setParameter(name, value);
        }
        if (engine.ditherRenderer != null) {
            engine.ditherRenderer.setParameter(name, value);
        }
        if (engine.temporalNoiseRenderer != null) {
            engine.temporalNoiseRenderer.setParameter(name, value);
        }
        if (engine.hexMosaicRenderer != null) {
            engine.hexMosaicRenderer.setParameter(name, value);
        }
    }

    private static void applyPreviewRasterParallelSettings(Engine engine, int workerCount, boolean parallelEnabled) {
        if (engine == null) {
            return;
        }
        if (engine.rasterRenderer != null) {
            engine.rasterRenderer.setParameter("workerCount", workerCount);
            engine.rasterRenderer.setParameter("parallel", parallelEnabled);
        }
        if (engine.ditherRenderer != null) {
            engine.ditherRenderer.setParameter("workerCount", workerCount);
            engine.ditherRenderer.setParameter("parallel", parallelEnabled);
        }
        if (engine.temporalNoiseRenderer != null) {
            engine.temporalNoiseRenderer.setParameter("workerCount", workerCount);
            engine.temporalNoiseRenderer.setParameter("parallel", parallelEnabled);
        }
        if (engine.hexMosaicRenderer != null) {
            engine.hexMosaicRenderer.setParameter("workerCount", workerCount);
            engine.hexMosaicRenderer.setParameter("parallel", parallelEnabled);
        }
    }

    private static void applyRasterPreviewMode(Engine engine, boolean unlitMode, boolean modelPreviewMode) {
        if (engine == null || engine.rasterRenderer == null) {
            return;
        }
        engine.rasterRenderer.setParameter("unlitMode", unlitMode);
        engine.rasterRenderer.setParameter("modelPreviewMode", modelPreviewMode);
        engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
    }

    private static Renderer configureRasterRenderer(Engine engine, boolean unlitMode, boolean modelPreviewMode) {
        applyRasterPreviewMode(engine, unlitMode, modelPreviewMode);
        return engine.rasterRenderer;
    }

    static void applyFastPostAA(Engine engine, FrameBuffer fb) {
        if (shouldForceSharpRayMotion()) {
            return;
        }
        engine.postAATemp = FastAAUtil.applyFastPostAA(fb.getColorBuffer(), fb.getWidth(), fb.getHeight(), engine.postAATemp);
    }

    static void resetViewportAdaptiveAssist(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportSmoothedFrameMs = 1000.0 / clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        engine.viewportFastFrameMs = engine.viewportSmoothedFrameMs;
        engine.viewportPredictedFrameMs = engine.viewportSmoothedFrameMs;
        engine.viewportHeavySmoothedFrameMs = engine.viewportSmoothedFrameMs;
        engine.viewportHeavyFastFrameMs = engine.viewportSmoothedFrameMs;
        engine.viewportHeavyPredictedFrameMs = engine.viewportSmoothedFrameMs;
        engine.viewportLastHeavyFrameNanos = 0L;
        engine.viewportFrameDropStreak = 0;
        engine.viewportAdaptiveScaleCurrent = 1.0;
        engine.viewportAdaptiveScaleApplied = 1.0;
        engine.viewportScalePressureSeconds = 0.0;
        engine.viewportCriticalPressureSeconds = 0.0;
        engine.viewportCriticalPreviewActive = false;
        engine.viewportCriticalPreviewStartNanos = 0L;
        engine.viewportCriticalPreviewHoldUntilNanos = 0L;
        engine.viewportCriticalRecoverStartNanos = 0L;
        engine.interactiveRenderScaleActive = false;
        engine.viewportAutoPolicyTier = "BALANCED";
        engine.viewportAutoOverloadRatio = 1.0;
        engine.viewportDynamicResolutionTierIndex = 0;
        engine.viewportDynamicResolutionLastSwitchNanos = 0L;
        engine.viewportDynamicResolutionSwitchCount = 0;
        engine.viewportDynamicResolutionDownshiftCount = 0;
        engine.viewportDynamicResolutionUpshiftCount = 0;
        engine.viewportDynamicResolutionDownshiftArmFrames = 0;
        engine.viewportDynamicResolutionRecoverStableFrames = 0;
        engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
        engine.viewportDynamicDecisionWindowCount = 0;
        engine.viewportDynamicDecisionWindowIndex = 0;
        engine.viewportDynamicDecisionWindowSumMs = 0.0;
        engine.viewportDynamicDecisionWindowMeanMs = engine.viewportSmoothedFrameMs;
        if (engine.viewportDynamicDecisionWindowSamplesMs != null) {
            Arrays.fill(engine.viewportDynamicDecisionWindowSamplesMs, 0.0);
        }
        engine.viewportUpshiftEvaluationFrames = 0;
        engine.viewportUpshiftBlockedByDwellCount = 0;
        engine.viewportUpshiftBlockedByArmCount = 0;
        engine.viewportUpshiftBlockedByOverloadCount = 0;
        engine.viewportUpshiftBlockedByFrameDropCount = 0;
        engine.viewportUpshiftBlockedByCriticalPressureCount = 0;
        engine.viewportUpshiftBlockedByScalePressureCount = 0;
        engine.viewportUpshiftBlockedByQualityTierCount = 0;
        engine.viewportUpshiftBlockedBySampleGateCount = 0;
        engine.viewportUpshiftLastOverloadRatio = 1.0;
        engine.viewportUpshiftLastOverloadThreshold = 0.0;
        engine.viewportUpshiftLastSampleCount = 0;
        engine.viewportUpshiftLastSampleGate = 0;
        engine.viewportUpshiftLastQualityTier = "";
        engine.viewportUpshiftLastMotionActive = false;
        engine.viewportWarmupUntilNanos = 0L;
        engine.viewportWarmupCaptureActive = false;
        engine.viewportWarmupSampleCount = 0;
        engine.viewportWarmupSampleWriteIndex = 0;
        if (engine.viewportWarmupFrameSamples != null) {
            Arrays.fill(engine.viewportWarmupFrameSamples, 0.0);
        }
        engine.viewportWarmupSeedMs = engine.viewportSmoothedFrameMs;
        engine.viewportWarmupSeedWeight = 0.0;
        engine.viewportWarmupSeedExpiresNanos = 0L;
        engine.viewportInteractionActiveLast = false;
        engine.viewportSceneMotionActive = false;
        engine.viewportMotionLatchedActive = false;
        engine.viewportMotionHoldUntilNanos = 0L;
        engine.viewportMotionExitStableFrames = 0;
        engine.viewportPathGentleMotionSeconds = 0.0;
        engine.viewportPathDenoiseEnabledApplied = true;
        engine.viewportPathDenoiseProfileApplied = "QUALITY";
        engine.viewportPathDenoiseRuntimeModeApplied = "FULL_FRAME";
    }

    private static void applyViewportRayPathProfile(Engine engine,
                                                    RenderMode activeMode,
                                                    boolean interactionActive,
                                                    boolean useFastViewportDenoise,
                                                    boolean criticalFallbackActive,
                                                    long now) {
        if (engine == null) {
            return;
        }
        boolean motionActive = isViewportMotionActive(engine);
        boolean motionEntryBoostActive = motionActive && now > 0L && now < engine.viewportMotionEntryBoostUntilNanos;
        if (engine.rayTracerRenderer != null) {
            boolean movingRay = activeMode == RenderMode.RAY_TRACING && motionActive;
            int movingTier = resolveRayMotionTier(engine, movingRay);
            if (movingRay && motionEntryBoostActive) {
                movingTier = Math.max(movingTier, VIEWPORT_MOTION_ENTRY_PROFILE_MIN_TIER);
            }
            boolean rayEmergencyTier = movingRay && movingTier >= 5;
            boolean rayAutoHardwareProbe = !movingRay || movingTier < 3;
            double rayInteractiveTargetMs = rayEmergencyTier ? 14.5 : VIEWPORT_TARGET_MS_RAY_INTERACTIVE;
            String rayRuntimeMode = movingRay ? "AUTO" : "FULL_FRAME";
            engine.rayTracerRenderer.setParameter("autohardware", rayAutoHardwareProbe);
            engine.rayTracerRenderer.setParameter("autoworkers", true);
            engine.rayTracerRenderer.setParameter("autotilesize", true);
            engine.rayTracerRenderer.setParameter(
                    "autoschedulingtargetms",
                    (activeMode == RenderMode.RAY_TRACING && movingRay)
                            ? rayInteractiveTargetMs
                            : VIEWPORT_TARGET_MS_RAY_IDLE);
                applyRayPreviewMotionParameters(engine.rayTracerRenderer, movingRay, movingTier);
            engine.rayTracerRenderer.setParameter("denoiseRadius",
                    movingRay ? 1 : ProgressiveRenderDefaults.RAY_VIEWPORT_DENOISE_RADIUS);
            engine.rayTracerRenderer.setParameter("denoiseProfile", movingRay && useFastViewportDenoise ? "FAST" : "QUALITY");
            engine.rayTracerRenderer.setParameter("denoiseRuntimeMode", rayRuntimeMode);
        }
        if (engine.pathTracerRenderer != null) {
            boolean movingPath = activeMode == RenderMode.PATH_TRACING && motionActive;
            int movingTier = clampDynamicResolutionTierIndex(engine.viewportDynamicResolutionTierIndex);
            if (movingPath && motionEntryBoostActive) {
                movingTier = Math.max(movingTier, VIEWPORT_MOTION_ENTRY_PROFILE_MIN_TIER);
            }
            boolean pathCritical = movingPath && criticalFallbackActive;
            boolean pathEmergencyByTier = movingPath && movingTier >= 5;
            boolean pathUltraEmergencyByTier = movingPath && movingTier >= 6;
            boolean pathEmergency = movingPath && (pathEmergencyByTier
                    || engine.viewportFrameDropStreak >= 2
                    || engine.viewportHeavyPredictedFrameMs >= VIEWPORT_SOFT_FLOOR_MS * 1.13);
            boolean pathGentleRecovery = movingPath
                    && !pathCritical
                    && !pathEmergency
                    && !motionEntryBoostActive
                    && engine.viewportPathGentleMotionSeconds >= 0.70
                    && engine.viewportAutoOverloadRatio <= 1.22
                    && engine.viewportCriticalPressureSeconds <= 0.55;
            String pathDenoiseProfile = pathGentleRecovery
                    ? "QUALITY"
                    : ((movingPath && (useFastViewportDenoise || motionEntryBoostActive)) ? "FAST" : "QUALITY");
            String pathDenoiseRuntimeMode = pathCritical
                    ? "TILED"
                    : (pathGentleRecovery ? "FULL_FRAME" : (movingPath ? "AUTO" : "FULL_FRAME"));
            boolean pathDenoiseEnabled = !pathCritical;
            double pathInteractiveTargetMs = (pathCritical || pathEmergency) ? 15.0 : VIEWPORT_TARGET_MS_PATH_INTERACTIVE;
            engine.pathTracerRenderer.setParameter("autohardware", true);
            engine.pathTracerRenderer.setParameter("autoworkers", true);
            engine.pathTracerRenderer.setParameter("autotilesize", true);
            engine.pathTracerRenderer.setParameter(
                    "autoschedulingtargetms",
                    (activeMode == RenderMode.PATH_TRACING && movingPath)
                            ? pathInteractiveTargetMs
                            : VIEWPORT_TARGET_MS_PATH_IDLE);
                applyPathPreviewMotionParameters(
                    engine.pathTracerRenderer,
                    movingPath,
                    movingTier,
                    pathCritical,
                    pathEmergency,
                    pathUltraEmergencyByTier,
                    pathGentleRecovery);
            engine.pathTracerRenderer.setParameter("denoiseRadius",
                    movingPath ? 1 : ProgressiveRenderDefaults.PATH_VIEWPORT_DENOISE_RADIUS);
            engine.pathTracerRenderer.setParameter("denoise", pathDenoiseEnabled);
            engine.pathTracerRenderer.setParameter("denoiseProfile", pathDenoiseProfile);
            engine.pathTracerRenderer.setParameter("denoiseRuntimeMode", pathDenoiseRuntimeMode);
            engine.viewportPathDenoiseEnabledApplied = pathDenoiseEnabled;
            engine.viewportPathDenoiseProfileApplied = pathDenoiseProfile;
            engine.viewportPathDenoiseRuntimeModeApplied = pathDenoiseRuntimeMode;
        }
    }

    static void synchronizeDisplayedPreviewLayerPolicy(Engine engine, RenderMode viewportRenderMode) {
        if (engine == null || engine.rayTracerRenderer == null) {
            return;
        }
        boolean movingDisplayedRay = viewportRenderMode == RenderMode.RAY_TRACING
            && isViewportMotionActive(engine);
        int movingTier = resolveRayMotionTier(engine, movingDisplayedRay);
        applyRayPreviewMotionParameters(engine.rayTracerRenderer, movingDisplayedRay, movingTier);
    }

    private static void applyRayPreviewMotionParameters(RayTracerRenderer renderer, boolean movingRay, int movingTier) {
        boolean rayEmergency = movingRay && movingTier >= 5;
        boolean rayUltraEmergency = movingRay && movingTier >= 6;
        int secondaryCadence = movingRay ? DYNAMIC_MOVING_SECONDARY_CADENCE[movingTier] : 1;
        int denoiseCadence = movingRay ? DYNAMIC_MOVING_DENOISE_CADENCE[movingTier] : 1;
        int baseCompositeCadence = movingRay ? Math.max(1, secondaryCadence) : 1;
        int tileSubsetCadence = !movingRay ? 1 : (rayUltraEmergency ? 4 : (rayEmergency ? 3 : (movingTier >= 3 ? 2 : 1)));
        double polishScale = !movingRay ? 1.0 : DYNAMIC_MOVING_POLISH_SCALE[movingTier];
        double baseShadingScale = !movingRay ? 1.0 : DYNAMIC_MOVING_BASE_SHADING_SCALE[movingTier];
        int motionDepth = !movingRay ? 0 : (rayEmergency ? 1 : 2);
        int maxLocalLights = !movingRay ? -1 : (rayUltraEmergency ? 1 : (rayEmergency ? 2 : (movingTier >= 3 ? 3 : 4)));
        int maxShadowedLocalLights = !movingRay ? -1 : (rayUltraEmergency ? 0 : (rayEmergency ? 1 : (movingTier >= 3 ? 1 : 2)));
        boolean dominantContributionOnly = movingRay && rayUltraEmergency;
        double throughputTermination = !movingRay ? 0.0 : (rayUltraEmergency ? 0.12 : (rayEmergency ? 0.08 : (movingTier >= 3 ? 0.05 : 0.03)));
        double roughnessSecondarySkip = !movingRay ? 0.0 : (rayUltraEmergency ? 0.45 : (rayEmergency ? 0.32 : (movingTier >= 3 ? 0.24 : 0.18)));

        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", movingRay);
        renderer.setParameter("previewMotionSecondaryCadence", secondaryCadence);
        renderer.setParameter("previewMotionDenoiseCadence", denoiseCadence);
        renderer.setParameter("previewMotionBaseCompositeCadence", baseCompositeCadence);
        renderer.setParameter("previewMotionTileSubsetCadence", tileSubsetCadence);
        renderer.setParameter("previewMotionSamplesPerFrame", movingRay ? 1 : 0);
        renderer.setParameter("previewMotionMaxDepth", motionDepth);
        renderer.setParameter("previewMotionDominantContributionOnly", dominantContributionOnly);
        renderer.setParameter("previewMotionMaxLocalLights", maxLocalLights);
        renderer.setParameter("previewMotionMaxShadowedLocalLights", maxShadowedLocalLights);
        renderer.setParameter("previewMotionThroughputTermination", throughputTermination);
        renderer.setParameter("previewMotionRoughnessSecondarySkip", roughnessSecondarySkip);
        renderer.setParameter("previewMotionPolishScale", polishScale);
        renderer.setParameter("previewMotionBaseShadingScale", baseShadingScale);
    }

    private static void applyPathPreviewMotionParameters(PathTracerRenderer renderer,
                                                         boolean movingPath,
                                                         int movingTier,
                                                         boolean pathCritical,
                                                         boolean pathEmergency,
                                                         boolean pathUltraEmergency,
                                                         boolean pathGentleRecovery) {
        int secondaryCadence = movingPath ? DYNAMIC_MOVING_SECONDARY_CADENCE[movingTier] : 1;
        int denoiseCadence = movingPath ? DYNAMIC_MOVING_DENOISE_CADENCE[movingTier] : 1;
        int bounceLimit = movingPath ? (pathCritical || movingTier >= 5 ? 1 : 2) : 0;
        int tileSubsetCadence = !movingPath ? 1 : (pathUltraEmergency ? 4 : (pathEmergency ? 3 : (movingTier >= 3 ? 2 : 1)));
        boolean dominantContributionOnly = movingPath && (pathUltraEmergency || movingTier >= 6 || (pathCritical && movingTier >= 4));
        int maxLocalLights = !movingPath ? -1 : (pathUltraEmergency ? 1 : (pathEmergency ? 2 : (movingTier >= 3 ? 3 : 4)));
        int maxShadowedLocalLights = !movingPath ? -1 : (pathUltraEmergency ? 0 : (pathEmergency ? 1 : (movingTier >= 3 ? 1 : 2)));
        double localLightImportanceThreshold = !movingPath ? 0.0 : (pathUltraEmergency ? 0.20 : (pathEmergency ? 0.12 : (movingTier >= 3 ? 0.08 : 0.04)));
        double throughputTermination = !movingPath ? 0.0 : (pathUltraEmergency ? 0.12 : (pathEmergency ? 0.08 : (movingTier >= 3 ? 0.05 : 0.03)));
        double roughnessSecondarySkip = !movingPath ? 0.0 : (pathUltraEmergency ? 0.45 : (pathEmergency ? 0.32 : (movingTier >= 3 ? 0.24 : 0.18)));

        if (pathGentleRecovery) {
            secondaryCadence = Math.max(2, secondaryCadence - 1);
            denoiseCadence = Math.max(2, denoiseCadence - 1);
            tileSubsetCadence = Math.max(1, tileSubsetCadence - 1);
            dominantContributionOnly = false;
            maxLocalLights = Math.max(3, maxLocalLights);
            maxShadowedLocalLights = Math.max(1, maxShadowedLocalLights);
            localLightImportanceThreshold = Math.min(localLightImportanceThreshold, 0.06);
            throughputTermination = Math.min(throughputTermination, 0.04);
            roughnessSecondarySkip = Math.min(roughnessSecondarySkip, 0.20);
            bounceLimit = 2;
        }

        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", movingPath);
        renderer.setParameter("previewMotionSecondaryCadence", secondaryCadence);
        renderer.setParameter("previewMotionDenoiseCadence", denoiseCadence);
        renderer.setParameter("previewMotionSamplesPerFrame", movingPath ? 1 : 0);
        renderer.setParameter("previewMotionMaxDepth", bounceLimit);
        renderer.setParameter("previewMotionTileSubsetCadence", tileSubsetCadence);
        renderer.setParameter("previewMotionDominantContributionOnly", dominantContributionOnly);
        renderer.setParameter("previewMotionMaxLocalLights", maxLocalLights);
        renderer.setParameter("previewMotionMaxShadowedLocalLights", maxShadowedLocalLights);
        renderer.setParameter("previewMotionLocalLightImportanceThreshold", localLightImportanceThreshold);
        renderer.setParameter("previewMotionThroughputTermination", throughputTermination);
        renderer.setParameter("previewMotionRoughnessSecondarySkip", roughnessSecondarySkip);
    }

    private static int resolveRayMotionTier(Engine engine,
                                            boolean movingRay) {
        if (!movingRay) {
            return 0;
        }
        return shouldForceSharpRayMotion()
                ? 0
                : clampDynamicResolutionTierIndex(engine.viewportDynamicResolutionTierIndex);
    }

    static void recordViewportFrameTime(Engine engine, double frameTimeMs) {
        RenderMode renderedMode = RenderMode.PHONG;
        if (engine != null) {
            renderedMode = engine.viewportDisplayedMode != null
                    ? engine.viewportDisplayedMode
                    : (engine.activeMode == null ? RenderMode.PHONG : engine.activeMode);
        }
        recordViewportFrameTime(engine, frameTimeMs, renderedMode);
    }

    static void recordViewportFrameTime(Engine engine, double frameTimeMs, RenderMode renderedMode) {
        if (engine == null || !Double.isFinite(frameTimeMs) || frameTimeMs <= 0.0) {
            return;
        }
        long now = System.nanoTime();
        RenderMode activeMode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        boolean activeHeavy = isHeavyViewportMode(activeMode);
        boolean renderedHeavy = isHeavyViewportMode(renderedMode);

        updateWarmupSeedSampling(engine, frameTimeMs, now, renderedMode);
        updateGenericPredictor(engine, frameTimeMs);

        if (activeHeavy && renderedHeavy) {
            updateHeavyPredictor(engine, frameTimeMs);
            applyWarmupSeedBias(engine, now);
            engine.viewportLastHeavyFrameNanos = now;
        } else if (!activeHeavy) {
            engine.viewportHeavySmoothedFrameMs = engine.viewportSmoothedFrameMs;
            engine.viewportHeavyFastFrameMs = engine.viewportFastFrameMs;
            engine.viewportHeavyPredictedFrameMs = engine.viewportPredictedFrameMs;
            engine.viewportLastHeavyFrameNanos = now;
            applyWarmupSeedBias(engine, now);
        } else {
            double staleHeavyMs = effectiveHeavyPredictedMs(engine, now);
            engine.viewportHeavySmoothedFrameMs = engine.viewportHeavySmoothedFrameMs * 0.96 + staleHeavyMs * 0.04;
            engine.viewportHeavyFastFrameMs = engine.viewportHeavyFastFrameMs * 0.92 + staleHeavyMs * 0.08;
            engine.viewportHeavyPredictedFrameMs = Math.max(engine.viewportHeavyPredictedFrameMs * 0.992, engine.viewportHeavySmoothedFrameMs);
            applyWarmupSeedBias(engine, now);
        }

        if (activeHeavy) {
            engine.viewportPredictedFrameMs = engine.viewportHeavyPredictedFrameMs;
        }

        boolean motionSampleActive = isViewportMotionActive(engine)
                || engine.viewportCameraMotionActive
                || engine.viewportSceneMotionActive;
        double sampleMs = activeHeavy
                ? (motionSampleActive ? frameTimeMs : effectiveHeavyPredictedMs(engine, now))
                : engine.viewportPredictedFrameMs;
        if (activeHeavy) {
            recordDynamicResolutionDecisionSample(engine, sampleMs);
        }
        updateViewportPressureFromSample(engine, sampleMs, frameTimeMs);
    }

    private static void recordDynamicResolutionDecisionSample(Engine engine, double sampleMs) {
        if (engine == null || !Double.isFinite(sampleMs) || sampleMs <= 0.0) {
            return;
        }
        if (engine.viewportDynamicDecisionWindowSamplesMs == null
                || engine.viewportDynamicDecisionWindowSamplesMs.length != DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES) {
            engine.viewportDynamicDecisionWindowSamplesMs = new double[DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES];
            engine.viewportDynamicDecisionWindowCount = 0;
            engine.viewportDynamicDecisionWindowIndex = 0;
            engine.viewportDynamicDecisionWindowSumMs = 0.0;
        }

        int index = engine.viewportDynamicDecisionWindowIndex;
        if (engine.viewportDynamicDecisionWindowCount >= DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES) {
            engine.viewportDynamicDecisionWindowSumMs -= engine.viewportDynamicDecisionWindowSamplesMs[index];
        } else {
            engine.viewportDynamicDecisionWindowCount++;
        }
        engine.viewportDynamicDecisionWindowSamplesMs[index] = sampleMs;
        engine.viewportDynamicDecisionWindowSumMs += sampleMs;
        engine.viewportDynamicDecisionWindowIndex = (index + 1) % DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES;
        engine.viewportDynamicDecisionWindowMeanMs = engine.viewportDynamicDecisionWindowSumMs
                / Math.max(1, engine.viewportDynamicDecisionWindowCount);
    }

    private static void resetDynamicResolutionDecisionWindow(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportDynamicDecisionWindowCount = 0;
        engine.viewportDynamicDecisionWindowIndex = 0;
        engine.viewportDynamicDecisionWindowSumMs = 0.0;
        if (engine.viewportDynamicDecisionWindowSamplesMs != null) {
            Arrays.fill(engine.viewportDynamicDecisionWindowSamplesMs, 0.0);
        }
        engine.viewportDynamicDecisionWindowMeanMs = Math.max(
                1.0,
                Double.isFinite(engine.viewportHeavyPredictedFrameMs)
                        ? engine.viewportHeavyPredictedFrameMs
                        : engine.viewportSmoothedFrameMs);
    }

    private static double resolveDynamicResolutionDecisionMs(Engine engine, double fallbackMs) {
        if (engine == null || !Double.isFinite(fallbackMs) || fallbackMs <= 0.0) {
            return Math.max(1.0, fallbackMs);
        }
        if (engine.viewportDynamicDecisionWindowCount < DYNAMIC_RESOLUTION_DECISION_WINDOW_FRAMES) {
            return fallbackMs;
        }
        double averaged = engine.viewportDynamicDecisionWindowMeanMs;
        if (!Double.isFinite(averaged) || averaged <= 0.0) {
            return fallbackMs;
        }
        return averaged;
    }

    private static void updateGenericPredictor(Engine engine, double frameTimeMs) {
        if (engine.viewportSmoothedFrameMs <= 0.0 || !Double.isFinite(engine.viewportSmoothedFrameMs)) {
            engine.viewportSmoothedFrameMs = frameTimeMs;
        } else {
            engine.viewportSmoothedFrameMs = engine.viewportSmoothedFrameMs * 0.88 + frameTimeMs * 0.12;
        }
        if (engine.viewportFastFrameMs <= 0.0 || !Double.isFinite(engine.viewportFastFrameMs)) {
            engine.viewportFastFrameMs = frameTimeMs;
        } else {
            engine.viewportFastFrameMs = engine.viewportFastFrameMs * 0.62 + frameTimeMs * 0.38;
        }
        double risingDeltaMs = Math.max(0.0, engine.viewportFastFrameMs - engine.viewportSmoothedFrameMs);
        engine.viewportPredictedFrameMs = clamp(
                Math.max(engine.viewportFastFrameMs, engine.viewportSmoothedFrameMs) + risingDeltaMs * 1.35,
                4.0,
                500.0);
    }

    private static void updateHeavyPredictor(Engine engine, double frameTimeMs) {
        if (engine.viewportHeavySmoothedFrameMs <= 0.0 || !Double.isFinite(engine.viewportHeavySmoothedFrameMs)) {
            engine.viewportHeavySmoothedFrameMs = frameTimeMs;
        } else {
            engine.viewportHeavySmoothedFrameMs = engine.viewportHeavySmoothedFrameMs * 0.86 + frameTimeMs * 0.14;
        }
        if (engine.viewportHeavyFastFrameMs <= 0.0 || !Double.isFinite(engine.viewportHeavyFastFrameMs)) {
            engine.viewportHeavyFastFrameMs = frameTimeMs;
        } else {
            engine.viewportHeavyFastFrameMs = engine.viewportHeavyFastFrameMs * 0.58 + frameTimeMs * 0.42;
        }
        double risingDeltaMs = Math.max(0.0, engine.viewportHeavyFastFrameMs - engine.viewportHeavySmoothedFrameMs);
        engine.viewportHeavyPredictedFrameMs = clamp(
                Math.max(engine.viewportHeavyFastFrameMs, engine.viewportHeavySmoothedFrameMs) + risingDeltaMs * 1.35,
                4.0,
                500.0);
    }

    private static void updateViewportPressureFromSample(Engine engine, double sampleMs, double frameTimeMs) {
        double targetFps = clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        double pressureArmFps = Math.max(VIEWPORT_HARD_FLOOR_FPS, Math.min(VIEWPORT_SOFT_FLOOR_FPS, targetFps));
        double criticalArmFps = Math.max(18.0, Math.min(23.0, targetFps));
        double pressureArmMs = 1000.0 / Math.max(MIN_VIEWPORT_TARGET_FPS, pressureArmFps);
        double criticalArmMs = 1000.0 / Math.max(MIN_VIEWPORT_TARGET_FPS, criticalArmFps);
        double frameSeconds = clamp(frameTimeMs / 1000.0, 0.0, 0.25);

        boolean underSoftFloor = sampleMs >= VIEWPORT_SOFT_FLOOR_MS * 1.015;
        boolean underHardFloor = sampleMs >= VIEWPORT_HARD_FLOOR_MS * 0.995;
        if (underSoftFloor) {
            engine.viewportFrameDropStreak = Math.min(120, engine.viewportFrameDropStreak + (underHardFloor ? 2 : 1));
        } else {
            engine.viewportFrameDropStreak = Math.max(0, engine.viewportFrameDropStreak - 1);
        }

        if (sampleMs >= pressureArmMs) {
            engine.viewportScalePressureSeconds = Math.min(8.0, engine.viewportScalePressureSeconds + frameSeconds);
        } else {
            engine.viewportScalePressureSeconds = Math.max(0.0, engine.viewportScalePressureSeconds - frameSeconds * 1.5);
        }

        if (sampleMs >= criticalArmMs) {
            engine.viewportCriticalPressureSeconds = Math.min(8.0, engine.viewportCriticalPressureSeconds + frameSeconds);
        } else {
            engine.viewportCriticalPressureSeconds = Math.max(0.0, engine.viewportCriticalPressureSeconds - frameSeconds * 1.9);
        }
        if (underSoftFloor) {
            engine.viewportCriticalPressureSeconds = Math.min(8.0, engine.viewportCriticalPressureSeconds + frameSeconds * 0.55);
        }
        if (underHardFloor) {
            engine.viewportCriticalPressureSeconds = Math.min(8.0, engine.viewportCriticalPressureSeconds + frameSeconds * 0.90);
        }
    }

    private static void beginWarmupSampling(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportWarmupCaptureActive = true;
        engine.viewportWarmupSampleCount = 0;
        engine.viewportWarmupSampleWriteIndex = 0;
        if (engine.viewportWarmupFrameSamples != null) {
            Arrays.fill(engine.viewportWarmupFrameSamples, 0.0);
        }
        engine.viewportWarmupSeedWeight = 0.0;
        engine.viewportWarmupSeedExpiresNanos = 0L;
    }

    private static void updateWarmupSeedSampling(Engine engine, double frameTimeMs, long now, RenderMode renderedMode) {
        if (engine == null || !isHeavyViewportMode(engine.activeMode == null ? RenderMode.PHONG : engine.activeMode)) {
            return;
        }
        if (engine.viewportWarmupCaptureActive
                && engine.viewportInteractionActiveLast
                && now < engine.viewportWarmupUntilNanos
                && isHeavyViewportMode(renderedMode)) {
            appendWarmupFrameSample(engine, frameTimeMs);
            return;
        }
        boolean warmupCaptureExpired = engine.viewportWarmupCaptureActive
                && (now >= engine.viewportWarmupUntilNanos || !engine.viewportInteractionActiveLast);
        if (warmupCaptureExpired) {
            finalizeWarmupSeed(engine, now);
        }
    }

    private static void appendWarmupFrameSample(Engine engine, double frameTimeMs) {
        if (engine.viewportWarmupFrameSamples == null || engine.viewportWarmupFrameSamples.length == 0) {
            return;
        }
        int index = engine.viewportWarmupSampleWriteIndex;
        engine.viewportWarmupFrameSamples[index] = clamp(frameTimeMs, 2.0, 500.0);
        engine.viewportWarmupSampleWriteIndex = (index + 1) % engine.viewportWarmupFrameSamples.length;
        if (engine.viewportWarmupSampleCount < engine.viewportWarmupFrameSamples.length) {
            engine.viewportWarmupSampleCount++;
        }
    }

    private static void finalizeWarmupSeed(Engine engine, long now) {
        engine.viewportWarmupCaptureActive = false;
        if (engine.viewportWarmupSampleCount < 3 || engine.viewportWarmupFrameSamples == null) {
            engine.viewportWarmupSampleCount = 0;
            engine.viewportWarmupSampleWriteIndex = 0;
            return;
        }

        int sampleCount = engine.viewportWarmupSampleCount;
        double[] sorted = new double[sampleCount];
        if (sampleCount == engine.viewportWarmupFrameSamples.length) {
            int start = engine.viewportWarmupSampleWriteIndex;
            for (int i = 0; i < sampleCount; i++) {
                sorted[i] = engine.viewportWarmupFrameSamples[(start + i) % sampleCount];
            }
        } else {
            System.arraycopy(engine.viewportWarmupFrameSamples, 0, sorted, 0, sampleCount);
        }
        Arrays.sort(sorted);

        double p50 = percentile(sorted, 0.50);
        double p75 = percentile(sorted, 0.75);
        double p90 = percentile(sorted, 0.90);
        double spreadRatio = (p90 - p50) / Math.max(1e-6, p50);
        double seedMs = Math.max(p75, p50 * 1.08);
        if (p90 > p75 * 1.22) {
            seedMs += (p90 - p75) * 0.35;
        }
        seedMs = clamp(seedMs, 4.0, 500.0);

        double weight = 0.52;
        if (sampleCount >= 8) {
            weight += 0.08;
        }
        if (spreadRatio <= 0.25) {
            weight += 0.08;
        } else if (spreadRatio >= 0.60) {
            weight -= 0.10;
        }
        weight = clamp(weight, 0.25, 0.72);

        engine.viewportWarmupSeedMs = seedMs;
        engine.viewportWarmupSeedWeight = weight;
        engine.viewportWarmupSeedExpiresNanos = now + WARMUP_SEED_MAX_LIFETIME_NS;
        engine.viewportSmoothedFrameMs = engine.viewportSmoothedFrameMs * 0.78 + seedMs * 0.22;
        engine.viewportFastFrameMs = engine.viewportFastFrameMs * 0.55 + seedMs * 0.45;
        engine.viewportPredictedFrameMs = Math.max(engine.viewportPredictedFrameMs, seedMs);
        engine.viewportHeavySmoothedFrameMs = engine.viewportHeavySmoothedFrameMs * 0.78 + seedMs * 0.22;
        engine.viewportHeavyFastFrameMs = engine.viewportHeavyFastFrameMs * 0.55 + seedMs * 0.45;
        engine.viewportHeavyPredictedFrameMs = Math.max(engine.viewportHeavyPredictedFrameMs, seedMs);
        engine.viewportWarmupSampleCount = 0;
        engine.viewportWarmupSampleWriteIndex = 0;
    }

    private static void applyWarmupSeedBias(Engine engine, long now) {
        if (engine.viewportWarmupSeedWeight <= 1e-4) {
            engine.viewportWarmupSeedWeight = 0.0;
            return;
        }
        if (!engine.viewportInteractionActiveLast || now >= engine.viewportWarmupSeedExpiresNanos) {
            engine.viewportWarmupSeedWeight *= WARMUP_SEED_DECAY_IDLE;
        } else {
            double seedBlend = clamp(engine.viewportWarmupSeedWeight, 0.0, 0.80);
            double seededPrediction = engine.viewportPredictedFrameMs * (1.0 - seedBlend)
                    + engine.viewportWarmupSeedMs * seedBlend;
            engine.viewportPredictedFrameMs = Math.max(engine.viewportPredictedFrameMs, seededPrediction);
                double heavySeededPrediction = engine.viewportHeavyPredictedFrameMs * (1.0 - seedBlend)
                    + engine.viewportWarmupSeedMs * seedBlend;
                engine.viewportHeavyPredictedFrameMs = Math.max(engine.viewportHeavyPredictedFrameMs, heavySeededPrediction);
            engine.viewportWarmupSeedWeight *= WARMUP_SEED_DECAY_PER_FRAME;
        }
        if (engine.viewportWarmupSeedWeight <= 0.02) {
            engine.viewportWarmupSeedWeight = 0.0;
        }
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted == null || sorted.length == 0) {
            return 0.0;
        }
        double p = clamp(percentile, 0.0, 1.0);
        double position = p * (sorted.length - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double frac = position - lower;
        return sorted[lower] + (sorted[upper] - sorted[lower]) * frac;
    }

    private static double effectiveHeavyPredictedMs(Engine engine, long now) {
        double baseHeavyMs = clamp(
                Math.max(engine.viewportHeavyPredictedFrameMs, engine.viewportHeavySmoothedFrameMs),
                4.0,
                500.0);
        if (engine.viewportLastHeavyFrameNanos <= 0L) {
            return baseHeavyMs;
        }
        long staleNs = Math.max(0L, now - engine.viewportLastHeavyFrameNanos);
        if (staleNs <= HEAVY_STALE_GAP_NS) {
            return baseHeavyMs;
        }
        double staleMs = clamp(staleNs / 1_000_000.0, 0.0, 2000.0);
        double stalePenalty = Math.min(9.0, staleMs / 260.0);
        return clamp(baseHeavyMs + stalePenalty, 4.0, 500.0);
    }

    private static void resetAdaptiveViewportState(Engine engine, double recoverBlend) {
        engine.viewportAdaptiveScaleCurrent = approach(engine.viewportAdaptiveScaleCurrent, 1.0, recoverBlend);
        engine.viewportAdaptiveScaleApplied = quantizeScale(engine.viewportAdaptiveScaleCurrent, GENERAL_SCALE_STEP);
        if (engine.viewportAdaptiveScaleApplied >= 0.995) {
            engine.viewportAdaptiveScaleCurrent = 1.0;
            engine.viewportAdaptiveScaleApplied = 1.0;
        }
        engine.viewportDynamicResolutionTierIndex = 0;
        engine.viewportDynamicResolutionDownshiftArmFrames = 0;
        engine.viewportDynamicResolutionRecoverStableFrames = 0;
        engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
        engine.viewportUpshiftEvaluationFrames = 0;
        engine.viewportUpshiftBlockedByDwellCount = 0;
        engine.viewportUpshiftBlockedByArmCount = 0;
        engine.viewportUpshiftBlockedByOverloadCount = 0;
        engine.viewportUpshiftBlockedByFrameDropCount = 0;
        engine.viewportUpshiftBlockedByCriticalPressureCount = 0;
        engine.viewportUpshiftBlockedByScalePressureCount = 0;
        engine.viewportUpshiftBlockedByQualityTierCount = 0;
        engine.viewportUpshiftBlockedBySampleGateCount = 0;
        engine.viewportUpshiftLastOverloadRatio = 1.0;
        engine.viewportUpshiftLastOverloadThreshold = 0.0;
        engine.viewportUpshiftLastSampleCount = 0;
        engine.viewportUpshiftLastSampleGate = 0;
        engine.viewportUpshiftLastQualityTier = "";
        engine.viewportUpshiftLastMotionActive = false;
        engine.viewportUpshiftLastBlockReason = "";
        engine.viewportCriticalPreviewActive = false;
        engine.viewportCriticalPreviewStartNanos = 0L;
    }

    private static void updateHeavyViewportAssist(
            Engine engine,
            long now,
            boolean interactionActive,
            boolean withinInteractionTail,
            RenderMode activeMode) {
        if (shouldForceSharpRayMotion()) {
            forceFullResolutionViewportScale(engine);
            return;
        }
        double targetFps = clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        double targetMs = 1000.0 / targetFps;
        boolean motionActive = isViewportMotionActive(engine)
                || engine.viewportCameraMotionActive
                || engine.viewportSceneMotionActive;
        double measuredMs = clamp(effectiveHeavyPredictedMs(engine, now), targetMs * 0.40, targetMs * 5.0);
        double decisionMs = resolveDynamicResolutionDecisionMs(engine, measuredMs);
        if (motionActive
                && engine.viewportDynamicDecisionWindowCount >= Math.max(4, DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES / 2)
                && Double.isFinite(engine.viewportDynamicDecisionWindowMeanMs)
                && engine.viewportDynamicDecisionWindowMeanMs > 0.0) {
            decisionMs = engine.viewportDynamicDecisionWindowMeanMs;
        }
        double overloadRatio = decisionMs / Math.max(1e-6, targetMs);
        int explicitTierOverride = explicitResolutionTierOverride(engine);

        int previousTier = clampDynamicResolutionTierIndex(engine.viewportDynamicResolutionTierIndex);
        int tier = explicitTierOverride >= 0 ? explicitTierOverride : previousTier;
        long sinceLastSwitchNs = now - engine.viewportDynamicResolutionLastSwitchNanos;
        boolean canDownshift = sinceLastSwitchNs >= DYNAMIC_RESOLUTION_DOWNSHIFT_DWELL_NS;
        boolean canUpshift = sinceLastSwitchNs >= (motionActive
                ? DYNAMIC_RESOLUTION_MOTION_UPSHIFT_DWELL_NS
                : DYNAMIC_RESOLUTION_UPSHIFT_DWELL_NS);
        boolean aggressiveIdleWarmupRescue = false;
        boolean stillReferencePriority = false;
        boolean liveMotionScaleActive = motionActive || interactionActive;
        boolean motionEntryBoostActive = motionActive
                && now > 0L
                && now < engine.viewportMotionEntryBoostUntilNanos;
        double recentActualFrameMs = Double.isFinite(engine.viewportFastFrameMs)
                ? engine.viewportFastFrameMs
                : decisionMs;
        boolean measuredMotionHeadroom = motionActive
                && !motionEntryBoostActive
                && recentActualFrameMs > 0.0
                && recentActualFrameMs <= targetMs * 0.82;
        if (measuredMotionHeadroom) {
            decisionMs = Math.min(decisionMs, recentActualFrameMs);
            overloadRatio = decisionMs / Math.max(1e-6, targetMs);
        }
        boolean heavyOverload = !measuredMotionHeadroom
            && (overloadRatio >= 1.35
            || engine.viewportFrameDropStreak >= 2
                || engine.viewportCriticalPressureSeconds >= 1.2);
        if (explicitTierOverride >= 0) {
            engine.viewportDynamicResolutionDownshiftArmFrames = 0;
            engine.viewportDynamicResolutionRecoverStableFrames = 0;
            engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            if (tier > 0) {
                engine.viewportUpshiftEvaluationFrames++;
                markUpshiftBlocked(engine, UpshiftBlockReason.SAMPLE_GATE);
            }
        } else if (interactionActive) {
            double motionFloorSourceMs = motionEntryBoostActive
                    ? Math.max(measuredMs, Math.max(engine.viewportHeavyFastFrameMs, engine.viewportHeavyPredictedFrameMs))
                    : decisionMs;
            if (measuredMotionHeadroom) {
                motionFloorSourceMs = Math.min(motionFloorSourceMs, recentActualFrameMs);
            }
            double instantMotionMs = clamp(motionFloorSourceMs, targetMs * 0.40, targetMs * 5.0);
            int immediateMotionFloorTier = resolveImmediateMotionFloorTier(
                    engine,
                    activeMode,
                    instantMotionMs,
                    motionEntryBoostActive);
            boolean appliedImmediateMotionFloor = false;
            if (immediateMotionFloorTier > tier) {
                tier = immediateMotionFloorTier;
                appliedImmediateMotionFloor = true;
                engine.viewportDynamicResolutionDownshiftArmFrames = 0;
                engine.viewportDynamicResolutionRecoverStableFrames = 0;
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            }

            int dropStep = heavyOverload ? 2 : 1;
            double downshiftThreshold = motionActive ? 1.06 : DYNAMIC_RESOLUTION_DOWNSHIFT_OVERLOAD[tier];
            boolean downshiftArmed = overloadRatio >= downshiftThreshold
                    || engine.viewportFrameDropStreak >= 2;
            if (measuredMotionHeadroom) {
                downshiftArmed = false;
                engine.viewportFrameDropStreak = 0;
                engine.viewportScalePressureSeconds = Math.max(0.0, engine.viewportScalePressureSeconds - 0.10);
                engine.viewportCriticalPressureSeconds = Math.max(0.0, engine.viewportCriticalPressureSeconds - 0.10);
            }
            if (appliedImmediateMotionFloor) {
                engine.viewportDynamicResolutionDownshiftArmFrames = 0;
            } else if (downshiftArmed) {
                engine.viewportDynamicResolutionDownshiftArmFrames++;
            } else {
                engine.viewportDynamicResolutionDownshiftArmFrames = Math.max(0, engine.viewportDynamicResolutionDownshiftArmFrames - 1);
            }

            if (!appliedImmediateMotionFloor && canDownshift && tier < DYNAMIC_RESOLUTION_MAX_INDEX) {
                if (heavyOverload || engine.viewportDynamicResolutionDownshiftArmFrames >= DYNAMIC_RESOLUTION_DOWNSHIFT_ARM_FRAMES) {
                    int motionStep = motionActive && overloadRatio >= 1.18 ? Math.max(dropStep, 2) : dropStep;
                    int candidate = Math.min(DYNAMIC_RESOLUTION_MAX_INDEX, tier + motionStep);
                    if (candidate != tier) {
                        tier = candidate;
                    }
                    engine.viewportDynamicResolutionDownshiftArmFrames = 0;
                }
            }
            // If we are safely below the 25 FPS cap target during motion, reclaim quality tiers.
            int upshiftArmFrames = motionActive
                    ? DYNAMIC_RESOLUTION_MOTION_UPSHIFT_ARM_FRAMES
                    : DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES;
            boolean upshiftHeadroom = tier > 0
                    && overloadRatio <= 0.96
                    && engine.viewportFrameDropStreak <= 0
                    && engine.viewportCriticalPressureSeconds <= 0.25
                    && engine.viewportScalePressureSeconds <= 0.35;
            upshiftHeadroom = upshiftHeadroom || measuredMotionHeadroom;
            engine.viewportUpshiftLastMotionActive = motionActive;
            engine.viewportUpshiftLastOverloadRatio = overloadRatio;
            engine.viewportUpshiftLastOverloadThreshold = 0.96;
            if (upshiftHeadroom) {
                engine.viewportDynamicResolutionRecoverStableFrames++;
                if (canUpshift && engine.viewportDynamicResolutionRecoverStableFrames >= upshiftArmFrames) {
                    tier--;
                    engine.viewportDynamicResolutionRecoverStableFrames = 0;
                    engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
                }
            } else {
                engine.viewportDynamicResolutionRecoverStableFrames = 0;
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            }
        } else {
            engine.viewportDynamicResolutionDownshiftArmFrames = 0;
            String qualityTier = previewQualityTier(engine, activeMode);
            int samples = previewAccumulatedSamples(engine, activeMode);
            boolean qualityStable = qualityTier.startsWith("STILL") || qualityTier.contains("REFERENCE");
            boolean warmupStarved = isHeavyIdleWarmupStarved(activeMode, qualityTier, samples);
            double upshiftOverloadThreshold = DYNAMIC_RESOLUTION_UPSHIFT_OVERLOAD[tier];
            double recoveryFrameMs = clamp(
                resolveDynamicResolutionDecisionMs(
                        engine,
                        Math.max(engine.viewportFastFrameMs, engine.viewportSmoothedFrameMs)),
                targetMs * 0.40,
                targetMs * 3.0);
            double recoveryOverloadRatio = recoveryFrameMs / Math.max(1e-6, targetMs);
            boolean staticResponsivePressure = recoveryOverloadRatio >= HEAVY_IDLE_RESPONSIVE_FRAME_MULT
                    || engine.viewportFrameDropStreak >= 2
                    || engine.viewportCriticalPressureSeconds >= 0.45
                    || engine.viewportScalePressureSeconds >= 0.65;
            stillReferencePriority = !isViewportMotionActive(engine)
                    && !engine.renderModeSwitchTransitionActive
                    && !engine.safetyRecoveryActive
                    && !staticResponsivePressure;
            boolean overloadOk = recoveryOverloadRatio <= upshiftOverloadThreshold;
            boolean frameDropOk = true;
            boolean criticalPressureOk = true;
            boolean scalePressureOk = true;

            if (!stillReferencePriority
                    && canDownshift
                    && tier < DYNAMIC_RESOLUTION_MAX_INDEX
                    && (warmupStarved || staticResponsivePressure)) {
                double warmupDownshiftThreshold = Math.max(
                        1.01,
                        DYNAMIC_RESOLUTION_DOWNSHIFT_OVERLOAD[tier] - HEAVY_IDLE_WARMUP_OVERLOAD_MARGIN);
                boolean overloadedWarmup = overloadRatio >= warmupDownshiftThreshold
                        || recoveryOverloadRatio >= warmupDownshiftThreshold
                        || staticResponsivePressure
                        || heavyOverload
                        || engine.viewportFrameDropStreak >= 1
                        || engine.viewportCriticalPressureSeconds >= 0.45
                        || engine.renderModeSwitchTransitionActive
                        || engine.safetyRecoveryActive;
                if (overloadedWarmup) {
                    int dropStep = (heavyOverload
                            || samples < HEAVY_IDLE_DEEP_STARVATION_SAMPLE_GATE
                            || recoveryOverloadRatio >= HEAVY_IDLE_RESPONSIVE_FRAME_MULT * 1.35
                            || engine.safetyRecoveryActive) ? 2 : 1;
                    int warmupFloorTier = resolveHeavyIdleWarmupFloorTier(engine, activeMode, samples);
                    int candidate = Math.max(tier + dropStep, warmupFloorTier);
                    tier = Math.min(DYNAMIC_RESOLUTION_MAX_INDEX, candidate);
                    engine.viewportDynamicResolutionRecoverStableFrames = 0;
                    engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
                    aggressiveIdleWarmupRescue = true;
                }
            }
            if (stillReferencePriority && tier > 0) {
                tier = 0;
                engine.viewportDynamicResolutionRecoverStableFrames = 0;
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
                clearUpshiftLastBlockReason(engine);
            }
            boolean stableForRecovery = overloadOk
                    && frameDropOk
                    && criticalPressureOk
                    && scalePressureOk
                    && qualityStable;

            if (tier > 0) {
                engine.viewportUpshiftEvaluationFrames++;
                clearUpshiftLastBlockReason(engine);
                if (!overloadOk) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.OVERLOAD);
                }
                if (!frameDropOk) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.FRAME_DROP);
                }
                if (!criticalPressureOk) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.CRITICAL_PRESSURE);
                }
                if (!scalePressureOk) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.SCALE_PRESSURE);
                }
                if (!qualityStable) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.QUALITY_TIER);
                }
                engine.viewportUpshiftLastOverloadRatio = recoveryOverloadRatio;
                engine.viewportUpshiftLastOverloadThreshold = upshiftOverloadThreshold;
                engine.viewportUpshiftLastQualityTier = qualityTier == null ? "" : qualityTier;
                engine.viewportUpshiftLastMotionActive = false;
            }
            if (stableForRecovery) {
                engine.viewportDynamicResolutionRecoverStableFrames++;
            } else {
                engine.viewportDynamicResolutionRecoverStableFrames = 0;
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            }

            int sampleGate = DYNAMIC_RESOLUTION_RECOVER_SAMPLE_GATE[tier];
            if (tier > 0) {
                engine.viewportUpshiftLastSampleCount = samples;
                engine.viewportUpshiftLastSampleGate = sampleGate;
            }
            if (stableForRecovery && samples >= sampleGate) {
                engine.viewportDynamicResolutionRecoverSampleGateFrames++;
            } else if (!stableForRecovery) {
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            } else if (tier > 0) {
                markUpshiftBlocked(engine, UpshiftBlockReason.SAMPLE_GATE);
            }

            int requiredSampleGateFrames = Math.max(2, DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES / 2);
            if (tier > 0) {
                if (!canUpshift) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.DWELL);
                }
                if (engine.viewportDynamicResolutionRecoverStableFrames < DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.ARM);
                }
                if (engine.viewportDynamicResolutionRecoverSampleGateFrames < requiredSampleGateFrames) {
                    markUpshiftBlocked(engine, UpshiftBlockReason.SAMPLE_GATE_FRAMES);
                }
            }
            if (tier > 0
                    && canUpshift
                    && engine.viewportDynamicResolutionRecoverStableFrames >= DYNAMIC_RESOLUTION_UPSHIFT_ARM_FRAMES
                    && engine.viewportDynamicResolutionRecoverSampleGateFrames >= requiredSampleGateFrames) {
                tier--;
                engine.viewportDynamicResolutionRecoverStableFrames = 0;
                engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
            }
        }

        tier = clampDynamicResolutionTierIndex(tier);
        if (tier != previousTier) {
            engine.viewportDynamicResolutionTierIndex = tier;
            engine.viewportDynamicResolutionLastSwitchNanos = now;
            resetDynamicResolutionDecisionWindow(engine);
            if (explicitTierOverride < 0) {
                engine.viewportDynamicResolutionSwitchCount++;
                if (tier > previousTier) {
                    engine.viewportDynamicResolutionDownshiftCount++;
                } else {
                    engine.viewportDynamicResolutionUpshiftCount++;
                }
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_SWITCHES, 1L);
                RuntimeInstrumentation.addCounter(
                        tier > previousTier
                                ? RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS
                                : RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_UPSHIFTS,
                        1L);
            }
        }

        double targetPreviewScale = explicitTierOverride >= 0
            ? DYNAMIC_RESOLUTION_TIERS[engine.viewportDynamicResolutionTierIndex]
            : resolveHeavyPreviewScale(engine.viewportDynamicResolutionTierIndex, liveMotionScaleActive);
        double downBlend = 0.24;
        double upBlend = 0.14;
        if (stillReferencePriority && targetPreviewScale >= 0.999) {
            engine.viewportAdaptiveScaleCurrent = 1.0;
            engine.viewportAdaptiveScaleApplied = 1.0;
        } else if (liveMotionScaleActive && targetPreviewScale < engine.viewportAdaptiveScaleCurrent) {
            engine.viewportAdaptiveScaleCurrent = targetPreviewScale;
            engine.viewportAdaptiveScaleApplied = targetPreviewScale;
        } else if (aggressiveIdleWarmupRescue && targetPreviewScale < engine.viewportAdaptiveScaleCurrent) {
            engine.viewportAdaptiveScaleCurrent = targetPreviewScale;
            engine.viewportAdaptiveScaleApplied = targetPreviewScale;
        } else {
            double blend = targetPreviewScale < engine.viewportAdaptiveScaleCurrent ? downBlend : upBlend;
            engine.viewportAdaptiveScaleCurrent = approach(engine.viewportAdaptiveScaleCurrent, targetPreviewScale, blend);
            engine.viewportAdaptiveScaleApplied = quantizeScale(
                    engine.viewportAdaptiveScaleCurrent,
                    HEAVY_SCALE_STEP,
                    HEAVY_MOTION_MIN_DYNAMIC_SCALE);
            if (Math.abs(engine.viewportAdaptiveScaleApplied - targetPreviewScale) <= HEAVY_SCALE_STEP * 0.6) {
                engine.viewportAdaptiveScaleCurrent = targetPreviewScale;
                engine.viewportAdaptiveScaleApplied = targetPreviewScale;
            }
        }
        RuntimeInstrumentation.addCounter(resolveTierFrameCounter(engine.viewportDynamicResolutionTierIndex), 1L);

        updatePathMotionQualityRecovery(engine, activeMode, interactionActive, measuredMs, targetMs);
        boolean scaleBudgetExhausted = engine.viewportDynamicResolutionTierIndex >= DYNAMIC_RESOLUTION_MAX_INDEX;
        updateHeavyCriticalFallback(engine, activeMode, now, interactionActive, withinInteractionTail, measuredMs, targetMs, scaleBudgetExhausted);
    }

    private static int clampDynamicResolutionTierIndex(int tierIndex) {
        return Math.max(0, Math.min(DYNAMIC_RESOLUTION_MAX_INDEX, tierIndex));
    }

    private static double resolvePreviewOutputResolutionScale(int qualityTierIndex) {
        int stageIndex = resolvePreviewOutputResolutionStageIndex(qualityTierIndex);
        return PREVIEW_OUTPUT_RESOLUTION_STAGES[stageIndex];
    }

    private static double resolveHeavyPreviewScale(int qualityTierIndex, boolean motionActive) {
        int tier = clampDynamicResolutionTierIndex(qualityTierIndex);
        if (motionActive) {
            return DYNAMIC_RESOLUTION_TIERS[tier];
        }
        return resolvePreviewOutputResolutionScale(tier);
    }

    private static int resolvePreviewOutputResolutionStageIndex(int qualityTierIndex) {
        int delayed = qualityTierIndex - PREVIEW_OUTPUT_RESOLUTION_DEGRADE_START_TIER;
        return Math.max(0, Math.min(PREVIEW_OUTPUT_RESOLUTION_STAGES.length - 1, delayed));
    }

    private enum UpshiftBlockReason {
        OVERLOAD,
        FRAME_DROP,
        CRITICAL_PRESSURE,
        SCALE_PRESSURE,
        QUALITY_TIER,
        SAMPLE_GATE,
        DWELL,
        ARM,
        SAMPLE_GATE_FRAMES
    }

    private static void markUpshiftBlocked(Engine engine, UpshiftBlockReason reason) {
        if (engine == null || reason == null) {
            return;
        }
        switch (reason) {
            case OVERLOAD -> {
                engine.viewportUpshiftBlockedByOverloadCount++;
                noteUpshiftBlockReason(engine, "overload");
            }
            case FRAME_DROP -> {
                engine.viewportUpshiftBlockedByFrameDropCount++;
                noteUpshiftBlockReason(engine, "frame_drop");
            }
            case CRITICAL_PRESSURE -> {
                engine.viewportUpshiftBlockedByCriticalPressureCount++;
                noteUpshiftBlockReason(engine, "critical_pressure");
            }
            case SCALE_PRESSURE -> {
                engine.viewportUpshiftBlockedByScalePressureCount++;
                noteUpshiftBlockReason(engine, "scale_pressure");
            }
            case QUALITY_TIER -> {
                engine.viewportUpshiftBlockedByQualityTierCount++;
                noteUpshiftBlockReason(engine, "quality_tier");
            }
            case SAMPLE_GATE -> {
                engine.viewportUpshiftBlockedBySampleGateCount++;
                noteUpshiftBlockReason(engine, "sample_gate");
            }
            case DWELL -> {
                engine.viewportUpshiftBlockedByDwellCount++;
                noteUpshiftBlockReason(engine, "dwell");
            }
            case ARM -> {
                engine.viewportUpshiftBlockedByArmCount++;
                noteUpshiftBlockReason(engine, "arm");
            }
            case SAMPLE_GATE_FRAMES -> {
                engine.viewportUpshiftBlockedBySampleGateCount++;
                noteUpshiftBlockReason(engine, "sample_gate_frames");
            }
        }
    }

    private static void clearUpshiftLastBlockReason(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportUpshiftLastBlockReason = "";
    }

    private static void noteUpshiftBlockReason(Engine engine, String reason) {
        if (engine == null || reason == null || reason.isBlank()) {
            return;
        }
        if (engine.viewportUpshiftLastBlockReason == null || engine.viewportUpshiftLastBlockReason.isEmpty()) {
            engine.viewportUpshiftLastBlockReason = reason;
        }
    }

    private static boolean shouldForceSharpRayMotion() {
        return FORCE_SHARP_RAY_MOTION;
    }

    private static void updateViewportMotionHysteresis(Engine engine, long now) {
        if (engine == null) {
            return;
        }
        boolean rawMotion = engine.viewportCameraMotionActive || engine.viewportSceneMotionActive;
        if (rawMotion) {
            engine.viewportMotionLatchedActive = true;
            engine.viewportMotionHoldUntilNanos = now + VIEWPORT_MOTION_EXIT_HYSTERESIS_NS;
            engine.viewportMotionExitStableFrames = 0;
            return;
        }
        engine.viewportMotionExitStableFrames = Math.min(
                VIEWPORT_MOTION_EXIT_STABLE_FRAMES + 1,
                engine.viewportMotionExitStableFrames + 1);
        if (engine.viewportMotionLatchedActive && now < engine.viewportMotionHoldUntilNanos) {
            return;
        }
        if (engine.viewportMotionExitStableFrames < VIEWPORT_MOTION_EXIT_STABLE_FRAMES) {
            return;
        }
        engine.viewportMotionLatchedActive = false;
        engine.viewportMotionEntryBoostUntilNanos = 0L;
        engine.viewportMotionHoldUntilNanos = 0L;
    }

    private static boolean isViewportMotionActive(Engine engine) {
        return engine != null && engine.viewportMotionLatchedActive;
    }

    private static void forceFullResolutionViewportScale(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportAdaptiveScaleCurrent = 1.0;
        engine.viewportAdaptiveScaleApplied = 1.0;
        engine.viewportDynamicResolutionTierIndex = 0;
        engine.viewportDynamicResolutionDownshiftArmFrames = 0;
        engine.viewportDynamicResolutionRecoverStableFrames = 0;
        engine.viewportDynamicResolutionRecoverSampleGateFrames = 0;
        engine.viewportUpshiftLastMotionActive = false;
        engine.viewportUpshiftLastSampleCount = 0;
        engine.viewportUpshiftLastSampleGate = 0;
        engine.viewportUpshiftLastBlockReason = "";
    }

    private static int explicitResolutionTierOverride(Engine engine) {
        if (engine == null) {
            return -1;
        }
        int explicitWidth = engine.getExplicitPreviewRenderWidth();
        int explicitHeight = engine.getExplicitPreviewRenderHeight();
        if (explicitWidth <= 0 || explicitHeight <= 0) {
            return -1;
        }
        double scale = Math.min(explicitWidth / 1920.0, explicitHeight / 1080.0);
        double bestDelta = Double.MAX_VALUE;
        int bestIndex = 0;
        for (int i = 0; i < DYNAMIC_RESOLUTION_TIERS.length; i++) {
            double delta = Math.abs(scale - DYNAMIC_RESOLUTION_TIERS[i]);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int previewAccumulatedSamples(Engine engine, RenderMode activeMode) {
        if (engine == null || activeMode == null) {
            return 0;
        }
        if (activeMode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, engine.rayTracerRenderer.getAccumulatedSamples()));
        }
        if (activeMode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, engine.pathTracerRenderer.getAccumulatedSamples()));
        }
        return 0;
    }

    private static boolean isHeavyIdleWarmupStarved(RenderMode activeMode, String qualityTier, int samples) {
        if (!isHeavyViewportMode(activeMode)) {
            return false;
        }
        if (samples < HEAVY_IDLE_WARMUP_SAMPLE_GATE) {
            return true;
        }
        if (qualityTier == null || qualityTier.isBlank()) {
            return true;
        }
        return qualityTier.contains("STILL_T0")
                || qualityTier.contains("STILL_T1")
                || qualityTier.contains("STILL_T2")
                || qualityTier.endsWith("_BASE")
                || qualityTier.endsWith("_REFLECTIONS")
                || qualityTier.endsWith("_TRANSMISSION");
    }

    private static int resolveImmediateMotionFloorTier(Engine engine,
                                                       RenderMode activeMode,
                                                       double instantMotionMs,
                                                       boolean motionEntryBoostActive) {
        if (!isHeavyViewportMode(activeMode)) {
            return 0;
        }
        double safeMs = Math.max(1.0, instantMotionMs);
        int floorTier;
        if (activeMode == RenderMode.RAY_TRACING) {
            if (safeMs >= 150.0) {
                floorTier = 11;
            } else if (safeMs >= 120.0) {
                floorTier = 10;
            } else if (safeMs >= 92.0) {
                floorTier = 9;
            } else if (safeMs >= 70.0) {
                floorTier = 8;
            } else if (safeMs >= 54.0) {
                floorTier = 7;
            } else if (safeMs >= 44.0) {
                floorTier = 6;
            } else if (safeMs >= 36.0) {
                floorTier = 5;
            } else {
                floorTier = 3;
            }
        } else {
            if (safeMs >= 165.0) {
                floorTier = 11;
            } else if (safeMs >= 135.0) {
                floorTier = 10;
            } else if (safeMs >= 108.0) {
                floorTier = 9;
            } else if (safeMs >= 82.0) {
                floorTier = 8;
            } else if (safeMs >= 62.0) {
                floorTier = 7;
            } else if (safeMs >= 48.0) {
                floorTier = 6;
            } else if (safeMs >= 38.0) {
                floorTier = 5;
            } else {
                floorTier = 3;
            }
        }
        if (motionEntryBoostActive) {
            floorTier = Math.max(
                    floorTier,
                    activeMode == RenderMode.RAY_TRACING ? VIEWPORT_MOTION_ENTRY_PROFILE_MIN_TIER : VIEWPORT_MOTION_ENTRY_MIN_TIER);
        }
        if (engine != null) {
            if (engine.viewportFrameDropStreak >= 1) {
                floorTier++;
            }
            if (currentViewportMegaPixels(engine) >= HEAVY_LARGE_VIEWPORT_MP) {
                floorTier++;
            }
        }
        return clampDynamicResolutionTierIndex(floorTier);
    }

    private static int resolveHeavyIdleWarmupFloorTier(Engine engine, RenderMode activeMode, int samples) {
        double viewportMegaPixels = currentViewportMegaPixels(engine);
        if (viewportMegaPixels >= HEAVY_HUGE_VIEWPORT_MP) {
            if (activeMode == RenderMode.PATH_TRACING) {
                return samples < HEAVY_IDLE_DEEP_STARVATION_SAMPLE_GATE ? 6 : 5;
            }
            if (activeMode == RenderMode.RAY_TRACING) {
                return samples < HEAVY_IDLE_DEEP_STARVATION_SAMPLE_GATE ? 5 : 4;
            }
        }
        if (viewportMegaPixels >= HEAVY_LARGE_VIEWPORT_MP) {
            if (activeMode == RenderMode.PATH_TRACING) {
                return 5;
            }
            if (activeMode == RenderMode.RAY_TRACING) {
                return 4;
            }
        }
        if (viewportMegaPixels >= 1.8) {
            if (activeMode == RenderMode.PATH_TRACING) {
                return 4;
            }
            if (activeMode == RenderMode.RAY_TRACING) {
                return 3;
            }
        }
        return 0;
    }

    private static double currentViewportMegaPixels(Engine engine) {
        if (engine == null || engine.frameBuffer == null) {
            return 0.0;
        }
        int width = Math.max(0, engine.frameBuffer.getWidth());
        int height = Math.max(0, engine.frameBuffer.getHeight());
        if (width <= 0 || height <= 0) {
            return 0.0;
        }
        return ((long) width * (long) height) / 1_000_000.0;
    }

    private static boolean shouldKeepPathLockAdaptiveRescue(Engine engine) {
        if (engine == null) {
            return false;
        }
        if (currentViewportMegaPixels(engine) >= HEAVY_LARGE_VIEWPORT_MP) {
            return true;
        }
        if (engine.viewportAdaptiveScaleApplied < 0.995
                || engine.viewportFrameDropStreak > 0
                || engine.viewportScalePressureSeconds >= 0.18
                || engine.viewportCriticalPressureSeconds >= 0.12
                || engine.viewportFallbackLockActive
                || engine.viewportCriticalPreviewActive
                || engine.renderModeSwitchTransitionActive
                || engine.safetyRecoveryActive) {
            return true;
        }
        double predictedMs = Math.max(
                engine.viewportHeavyPredictedFrameMs,
                Math.max(engine.viewportFastFrameMs, engine.viewportSmoothedFrameMs));
        return predictedMs >= VIEWPORT_SOFT_FLOOR_MS * 0.92;
    }

    private static String previewQualityTier(Engine engine, RenderMode activeMode) {
        if (engine == null || activeMode == null) {
            return "";
        }
        if (activeMode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            String tier = engine.rayTracerRenderer.getActivePreviewQualityTier();
            return tier == null ? "" : tier;
        }
        if (activeMode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            String tier = engine.pathTracerRenderer.getActivePreviewQualityTier();
            return tier == null ? "" : tier;
        }
        return "";
    }

    private static RuntimeInstrumentation.Counter resolveTierFrameCounter(int tierIndex) {
        return switch (clampDynamicResolutionTierIndex(tierIndex)) {
            case 0 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_100_FRAMES;
            case 1 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_90_FRAMES;
            case 2 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_80_FRAMES;
            case 3 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_70_FRAMES;
            case 4 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_60_FRAMES;
            case 5 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_50_FRAMES;
            case 6 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_40_FRAMES;
            case 7 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_33_FRAMES;
            case 8 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_25_FRAMES;
            case 9 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_20_FRAMES;
            case 10 -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_16_FRAMES;
            default -> RuntimeInstrumentation.Counter.PREVIEW_DYNAMIC_RES_TIER_12_FRAMES;
        };
    }

    private static void updatePathMotionQualityRecovery(Engine engine,
                                                        RenderMode activeMode,
                                                        boolean interactionActive,
                                                        double measuredMs,
                                                        double targetMs) {
        if (engine == null || activeMode != RenderMode.PATH_TRACING) {
            if (engine != null) {
                engine.viewportPathGentleMotionSeconds = 0.0;
            }
            return;
        }
        double frameSeconds = clamp(measuredMs / 1000.0, 0.0, 0.25);
        double overloadRatio = measuredMs / Math.max(1e-6, targetMs);
        double pressure = Math.max(engine.viewportCriticalPressureSeconds, engine.viewportScalePressureSeconds * 0.70);
        boolean gentleMotion = interactionActive && overloadRatio <= 1.18 && pressure <= 0.65;
        if (gentleMotion) {
            engine.viewportPathGentleMotionSeconds = Math.min(2.0, engine.viewportPathGentleMotionSeconds + frameSeconds);
            return;
        }
        double decay = interactionActive ? frameSeconds * 1.4 : frameSeconds * 3.2;
        engine.viewportPathGentleMotionSeconds = Math.max(0.0, engine.viewportPathGentleMotionSeconds - decay);
    }

    private static void updateAdaptiveViewportAssist(
            Engine engine,
            RenderMode activeMode,
            long now,
            boolean interactionActive,
            boolean withinInteractionTail) {
        if (shouldForceSharpRayMotion()) {
            forceFullResolutionViewportScale(engine);
            return;
        }
        double minScale = clamp(engine.interactiveRenderScale, 0.35, 1.0);
        double targetFps = clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        double targetMs = 1000.0 / targetFps;
        double measuredMs = clamp(engine.viewportSmoothedFrameMs, targetMs * 0.40, targetMs * 5.0);
        boolean heavyMode = isHeavyViewportMode(activeMode);
        boolean sustainedPressure = engine.viewportScalePressureSeconds >= 2.0
                || engine.viewportAdaptiveScaleApplied < 0.995;

        double desiredScale;
        if (!sustainedPressure) {
            desiredScale = 1.0;
        } else if (measuredMs > targetMs * 1.02) {
            desiredScale = clamp(Math.sqrt(targetMs / measuredMs), minScale, 1.0);
        } else if (engine.viewportScalePressureSeconds <= 0.15 && measuredMs < targetMs * 0.96) {
            desiredScale = 1.0;
        } else {
            desiredScale = engine.viewportAdaptiveScaleCurrent <= 0.0 ? 1.0 : engine.viewportAdaptiveScaleCurrent;
        }

        if (!interactionActive && !heavyMode && measuredMs <= targetMs * 1.03) {
            desiredScale = 1.0;
        }

        double downBlend = heavyMode ? 0.12 : 0.10;
        double upBlend = interactionActive ? 0.06 : 0.10;
        double blend = desiredScale < engine.viewportAdaptiveScaleCurrent ? downBlend : upBlend;
        engine.viewportAdaptiveScaleCurrent = approach(engine.viewportAdaptiveScaleCurrent, desiredScale, blend);
        engine.viewportAdaptiveScaleApplied = quantizeScale(
                engine.viewportAdaptiveScaleCurrent,
                heavyMode ? HEAVY_SCALE_STEP : GENERAL_SCALE_STEP);

        if (engine.viewportAdaptiveScaleApplied >= 0.995) {
            engine.viewportAdaptiveScaleCurrent = 1.0;
            engine.viewportAdaptiveScaleApplied = 1.0;
        }

        if (heavyMode) {
            boolean scaleBudgetExhausted = engine.viewportAdaptiveScaleApplied <= minScale + 0.021;
            updateHeavyCriticalFallback(
                    engine,
                    activeMode,
                    now,
                    interactionActive,
                    withinInteractionTail,
                    measuredMs,
                    targetMs,
                    scaleBudgetExhausted);
        } else {
            engine.viewportCriticalPreviewActive = false;
            engine.viewportCriticalPreviewStartNanos = 0L;
        }
    }

    private static void updateHeavyCriticalFallback(
            Engine engine,
            RenderMode activeMode,
            long now,
            boolean interactionActive,
            boolean withinInteractionTail,
            double measuredMs,
            double targetMs,
            boolean scaleBudgetExhausted) {
        if (engine == null) {
            return;
        }
        if (!engine.viewportNavigationPreviewEnabled || !interactionActive) {
            engine.viewportCriticalPreviewStartNanos = 0L;
            engine.viewportCriticalPreviewHoldUntilNanos = 0L;
            engine.viewportCriticalRecoverStartNanos = 0L;
            engine.viewportCriticalPreviewActive = false;
            engine.viewportAutoPolicyTier = "BALANCED";
            engine.viewportAutoOverloadRatio = 1.0;
            return;
        }

        double predictedMs = Math.max(measuredMs, effectiveHeavyPredictedMs(engine, now));
        double predictedOverloadRatio = predictedMs / Math.max(1e-6, targetMs);
        double pressureSeconds = Math.max(engine.viewportCriticalPressureSeconds, engine.viewportScalePressureSeconds * 0.72);
        double multiplier = activeMode == RenderMode.PATH_TRACING ? PATH_FALLBACK_FRAME_MULT : 1.45;
        double pressureSecondsThreshold = activeMode == RenderMode.PATH_TRACING
            ? PATH_FALLBACK_PRESSURE_SECONDS
            : 1.6;
        long fallbackDelayNs = activeMode == RenderMode.PATH_TRACING
            ? PATH_FALLBACK_DELAY_NS
            : CRITICAL_FALLBACK_DELAY_NS;
        String autoPolicyTier = "BALANCED";

 // Auto-tune fallback aggressiveness from real viewport pressure. No manual preset needed.
        if (predictedOverloadRatio >= 2.0 || pressureSeconds >= 2.5 || engine.viewportFrameDropStreak >= 4) {
            autoPolicyTier = "AGGRESSIVE";
            multiplier -= 0.12;
            pressureSecondsThreshold -= 0.35;
            fallbackDelayNs = (long) (fallbackDelayNs * 0.55);
        } else if (predictedOverloadRatio <= 1.25 && pressureSeconds <= 0.8 && engine.viewportFrameDropStreak <= 0) {
            autoPolicyTier = "RELAXED";
            multiplier += 0.10;
            pressureSecondsThreshold += 0.25;
            fallbackDelayNs = (long) (fallbackDelayNs * 1.15);
        }
        multiplier = clamp(multiplier, 1.18, 1.80);
        pressureSecondsThreshold = clamp(pressureSecondsThreshold, 0.7, 3.2);
        fallbackDelayNs = Math.max(120_000_000L, Math.min(2_000_000_000L, fallbackDelayNs));

        engine.viewportAutoPolicyTier = autoPolicyTier;
        engine.viewportAutoOverloadRatio = predictedOverloadRatio;
        boolean severeOverload = predictedMs >= targetMs * multiplier;
        boolean approachingSoftFloor = predictedMs >= VIEWPORT_SOFT_FLOOR_MS * 0.98;
        boolean hardFloorRisk = predictedMs >= VIEWPORT_HARD_FLOOR_MS * 0.995;
        boolean sustainedCriticalPressure = engine.viewportCriticalPressureSeconds >= pressureSecondsThreshold;
        boolean emergencyDrop = engine.viewportFrameDropStreak >= 2 && predictedMs >= VIEWPORT_SOFT_FLOOR_MS * 1.04;
        boolean fallbackPressure = (severeOverload && (sustainedCriticalPressure || scaleBudgetExhausted || emergencyDrop))
            || (emergencyDrop && predictedOverloadRatio >= 1.08)
            || (hardFloorRisk && (engine.viewportFrameDropStreak >= 1 || pressureSeconds >= pressureSecondsThreshold * 0.65))
            || (approachingSoftFloor && sustainedCriticalPressure && engine.viewportFrameDropStreak >= 2);
        if (emergencyDrop) {
            fallbackDelayNs = Math.min(fallbackDelayNs, 100_000_000L);
        }
        if (hardFloorRisk) {
            fallbackDelayNs = Math.min(fallbackDelayNs, 60_000_000L);
        }

        if (engine.viewportCriticalPreviewActive) {
            boolean holdActive = now < engine.viewportCriticalPreviewHoldUntilNanos;
            boolean recovered = !withinInteractionTail
                    || predictedMs <= targetMs * 1.02
                    || engine.viewportCriticalPressureSeconds <= 0.15;
            if (!holdActive && recovered) {
                if (engine.viewportCriticalRecoverStartNanos == 0L) {
                    engine.viewportCriticalRecoverStartNanos = now;
                }
                if (now - engine.viewportCriticalRecoverStartNanos >= CRITICAL_FALLBACK_RECOVERY_NS) {
                    engine.viewportCriticalPreviewActive = false;
                    engine.viewportCriticalPreviewStartNanos = 0L;
                    engine.viewportCriticalPreviewHoldUntilNanos = 0L;
                    engine.viewportCriticalRecoverStartNanos = 0L;
                    engine.viewportAutoPolicyTier = "BALANCED";
                }
            } else {
                engine.viewportCriticalRecoverStartNanos = 0L;
            }
            return;
        }

        if (!fallbackPressure) {
            boolean recovered = !withinInteractionTail
                    || predictedMs <= targetMs * 1.05
                    || engine.viewportCriticalPressureSeconds <= 0.20;
            if (recovered) {
                engine.viewportCriticalPreviewStartNanos = 0L;
                engine.viewportCriticalPreviewActive = false;
                engine.viewportAutoPolicyTier = "BALANCED";
            }
            return;
        }

        if (engine.viewportCriticalPreviewStartNanos == 0L) {
            engine.viewportCriticalPreviewStartNanos = now;
        }
        if (now - engine.viewportCriticalPreviewStartNanos >= fallbackDelayNs) {
            engine.viewportCriticalPreviewActive = true;
            engine.viewportCriticalPreviewHoldUntilNanos = now + CRITICAL_FALLBACK_MIN_HOLD_NS;
            engine.viewportCriticalRecoverStartNanos = 0L;
        }
    }

    private static double quantizeScale(double value, double step) {
        return quantizeScale(value, step, 0.35);
    }

    private static double quantizeScale(double value, double step, double min) {
        double safeStep = Math.max(0.01, step);
        double safeMin = clamp(min, 0.01, 1.0);
        double quantized = Math.round(clamp(value, safeMin, 1.0) / safeStep) * safeStep;
        if (Math.abs(1.0 - quantized) < safeStep * 0.55) {
            return 1.0;
        }
        return clamp(quantized, safeMin, 1.0);
    }

    private static boolean isHeavyViewportMode(RenderMode mode) {
        return mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING;
    }

    private static double approach(double current, double target, double factor) {
        double f = clamp(factor, 0.0, 1.0);
        double next = current + (target - current) * f;
        if (Math.abs(target - next) < 0.01) {
            return target;
        }
        return next;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
