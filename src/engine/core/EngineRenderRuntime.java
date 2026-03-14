package engine.core;

import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.util.FastAAUtil;
import engine.util.ThreadPool;

final class EngineRenderRuntime {
    private static final long INTERACTION_LINGER_NS = 220_000_000L;
    private static final long CRITICAL_FALLBACK_DELAY_NS = 900_000_000L;
    private static final double MIN_VIEWPORT_TARGET_FPS = 12.0;
    private static final double MAX_VIEWPORT_TARGET_FPS = 120.0;
    private static final double HEAVY_SCALE_STEP = 0.04;
    private static final double GENERAL_SCALE_STEP = 0.05;

    private EngineRenderRuntime() {
    }

    static void toggleFrustumCulling(Engine engine) {
        engine.frustumCullingEnabled = !engine.frustumCullingEnabled;
        engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.ditherRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.temporalNoiseRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.hexMosaicRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        System.out.println("Frustum culling: " + (engine.frustumCullingEnabled ? "ON" : "OFF"));
    }

    static void toggleBackfaceCulling(Engine engine) {
        engine.backfaceCullingEnabled = !engine.backfaceCullingEnabled;
        engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
        engine.ditherRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
        engine.temporalNoiseRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
        engine.hexMosaicRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
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
        DitherRenderer.DitherStyle next;
        if (current == DitherRenderer.DitherStyle.BLUE_NOISE) {
            next = DitherRenderer.DitherStyle.PATTERN;
        } else if (current == DitherRenderer.DitherStyle.PATTERN) {
            next = DitherRenderer.DitherStyle.ASCII;
        } else {
            next = DitherRenderer.DitherStyle.BLUE_NOISE;
        }
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
        TemporalNoiseRenderer.NoiseMode next;
        if (current == TemporalNoiseRenderer.NoiseMode.OBJECT_MASK) {
            next = TemporalNoiseRenderer.NoiseMode.FACE_FLOW;
        } else if (current == TemporalNoiseRenderer.NoiseMode.FACE_FLOW) {
            next = TemporalNoiseRenderer.NoiseMode.CAMERA_RELATIVE;
        } else {
            next = TemporalNoiseRenderer.NoiseMode.OBJECT_MASK;
        }
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
            engine.rasterRenderer.setParameter("parallel", false);
            engine.ditherRenderer.setParameter("parallel", false);
            engine.temporalNoiseRenderer.setParameter("parallel", false);
            engine.hexMosaicRenderer.setParameter("parallel", false);
            System.out.println("Multi-thread raster unavailable (worker count = 1).");
            engine.refreshUiIndicators();
            return;
        }
        engine.parallelRasterEnabled = !engine.parallelRasterEnabled;
        engine.rasterRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.ditherRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.temporalNoiseRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.hexMosaicRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        System.out.println("Multi-thread raster: " + (engine.parallelRasterEnabled ? "ON" : "OFF"));
        engine.refreshUiIndicators();
    }

    static void adjustWorkerCount(Engine engine, int delta) {
        int max = ThreadPool.recommendedWorkerCount();
        engine.parallelWorkerCount = Math.max(1, Math.min(max, engine.parallelWorkerCount + delta));
        engine.rasterRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.rasterRenderer.setParameter("parallel", engine.parallelRasterEnabled && engine.parallelWorkerCount > 1);
        engine.ditherRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.ditherRenderer.setParameter("parallel", engine.parallelRasterEnabled && engine.parallelWorkerCount > 1);
        engine.temporalNoiseRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.temporalNoiseRenderer.setParameter("parallel", engine.parallelRasterEnabled && engine.parallelWorkerCount > 1);
        engine.hexMosaicRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.hexMosaicRenderer.setParameter("parallel", engine.parallelRasterEnabled && engine.parallelWorkerCount > 1);
        engine.rayTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.pathTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        if (engine.parallelWorkerCount <= 1) {
            engine.parallelRasterEnabled = false;
        }
        System.out.println("Workers: " + engine.parallelWorkerCount + " / " + max);
        engine.refreshUiIndicators();
    }

    static void cycleRenderScale(Engine engine) {
        if (engine.renderScale > 0.90) {
            engine.renderScale = 0.75;
        } else if (engine.renderScale > 0.70) {
            engine.renderScale = 0.50;
        } else if (engine.renderScale > 0.45) {
            engine.renderScale = 0.35;
        } else {
            engine.renderScale = 1.00;
        }
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
        engine.orthographicCamera.setExtents(-6.0 * aspect, 6.0 * aspect, -6.0, 6.0);

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
        if (engine.rasterRenderer != null) {
            engine.rasterRenderer.resize(width, height);
        }
        if (engine.ditherRenderer != null) {
            engine.ditherRenderer.resize(width, height);
        }
        if (engine.temporalNoiseRenderer != null) {
            engine.temporalNoiseRenderer.resize(width, height);
        }
        if (engine.wireframeRenderer != null) {
            engine.wireframeRenderer.resize(width, height);
        }
        if (engine.hexMosaicRenderer != null) {
            engine.hexMosaicRenderer.resize(width, height);
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
        return Math.max(320, (int) Math.round(engine.baseWidth * effectiveRenderScale(engine)));
    }

    static int scaledHeight(Engine engine) {
        return Math.max(200, (int) Math.round(engine.baseHeight * effectiveRenderScale(engine)));
    }

    static double effectiveRenderScale(Engine engine) {
        double scale = engine.renderScale * modeResolutionFactor(engine, engine.activeMode);
        if (engine.progressiveViewportEnabled && engine.interactiveRenderScaleActive) {
            scale *= interactiveScaleFactor(engine);
        }
        scale *= clamp(engine.safetyViewportScaleClamp, 0.35, 1.0);
        return scale;
    }

    private static double interactiveScaleFactor(Engine engine) {
        return clamp(engine.viewportAdaptiveScaleApplied, 0.35, 1.0);
    }

    static double modeResolutionFactor(Engine engine, RenderMode mode) {
        return 1.0;
    }

    static void updateRealtimePerformanceState(Engine engine, boolean interactionActive) {
        if (engine == null) {
            return;
        }
        long now = System.nanoTime();
        if (interactionActive) {
            engine.lastViewportInteractionNanos = now;
        }
        RenderMode activeMode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        boolean withinInteractionTail = interactionActive || now - engine.lastViewportInteractionNanos < INTERACTION_LINGER_NS;

        double previousAppliedScale = engine.viewportAdaptiveScaleApplied;
        boolean previousCriticalPreview = engine.viewportCriticalPreviewActive;
        if (engine.progressiveViewportEnabled && supportsInteractiveViewportScaling(engine, activeMode)) {
            updateAdaptiveViewportAssist(engine, activeMode, now, interactionActive, withinInteractionTail);
        } else {
            resetAdaptiveViewportState(engine, 0.14);
        }

        boolean shouldUseInteractiveScale = engine.viewportAdaptiveScaleApplied < 0.995;
        boolean scaleChanged = Math.abs(previousAppliedScale - engine.viewportAdaptiveScaleApplied) > 1e-6;
        boolean interactiveStateChanged = shouldUseInteractiveScale != engine.interactiveRenderScaleActive;
        boolean criticalPreviewChanged = previousCriticalPreview != engine.viewportCriticalPreviewActive;
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
        RenderMode active = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        if (!engine.viewportNavigationPreviewEnabled || !interactionActive) {
            return active;
        }
        if (active != RenderMode.RAY_TRACING && active != RenderMode.PATH_TRACING) {
            return active;
        }
        if (!engine.viewportCriticalPreviewActive) {
            return active;
        }
        RenderMode fallback = engine.viewportNavigationFallbackMode == null
                ? RenderMode.MODEL
                : engine.viewportNavigationFallbackMode;
        if (fallback == RenderMode.RAY_TRACING || fallback == RenderMode.PATH_TRACING) {
            return RenderMode.MODEL;
        }
        return fallback;
    }

    static Renderer configureRendererForMode(Engine engine, RenderMode mode) {
        if (engine == null) {
            return null;
        }
        RenderMode safeMode = mode == null ? RenderMode.PHONG : mode;
        switch (safeMode) {
            case MODEL:
                engine.rasterRenderer.setParameter("unlitMode", true);
                engine.rasterRenderer.setParameter("modelPreviewMode", true);
                engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
                engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
                return engine.rasterRenderer;
            case BASIC:
                engine.rasterRenderer.setParameter("unlitMode", true);
                engine.rasterRenderer.setParameter("modelPreviewMode", false);
                engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
                engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
                return engine.rasterRenderer;
            case PHONG:
                engine.rasterRenderer.setParameter("unlitMode", false);
                engine.rasterRenderer.setParameter("modelPreviewMode", false);
                engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
                engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
                return engine.rasterRenderer;
            case WIREFRAME:
                return engine.wireframeRenderer;
            case DITHERING:
                return engine.ditherRenderer;
            case TEMPORAL_NOISE:
                return engine.temporalNoiseRenderer;
            case HEX_MOSAIC:
                return engine.hexMosaicRenderer;
            case RAY_TRACING:
                return engine.rayTracerRenderer;
            case PATH_TRACING:
                return engine.pathTracerRenderer;
            default:
                engine.rasterRenderer.setParameter("unlitMode", false);
                engine.rasterRenderer.setParameter("modelPreviewMode", false);
                engine.rasterRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
                engine.rasterRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
                return engine.rasterRenderer;
        }
    }

    static void applyFastPostAA(Engine engine, FrameBuffer fb) {
        engine.postAATemp = FastAAUtil.applyFastPostAA(fb.getColorBuffer(), fb.getWidth(), fb.getHeight(), engine.postAATemp);
    }

    static void resetViewportAdaptiveAssist(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.viewportSmoothedFrameMs = 1000.0 / clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        engine.viewportAdaptiveScaleCurrent = 1.0;
        engine.viewportAdaptiveScaleApplied = 1.0;
        engine.viewportScalePressureSeconds = 0.0;
        engine.viewportCriticalPressureSeconds = 0.0;
        engine.viewportCriticalPreviewActive = false;
        engine.viewportCriticalPreviewStartNanos = 0L;
        engine.interactiveRenderScaleActive = false;
    }

    static void recordViewportFrameTime(Engine engine, double frameTimeMs) {
        if (engine == null || !Double.isFinite(frameTimeMs) || frameTimeMs <= 0.0) {
            return;
        }
        if (engine.viewportSmoothedFrameMs <= 0.0 || !Double.isFinite(engine.viewportSmoothedFrameMs)) {
            engine.viewportSmoothedFrameMs = frameTimeMs;
        } else {
            engine.viewportSmoothedFrameMs = engine.viewportSmoothedFrameMs * 0.88 + frameTimeMs * 0.12;
        }
        double targetFps = clamp(engine.viewportTargetFps, MIN_VIEWPORT_TARGET_FPS, MAX_VIEWPORT_TARGET_FPS);
        double pressureArmFps = Math.min(20.0, targetFps);
        double criticalArmFps = Math.min(16.0, targetFps);
        double pressureArmMs = 1000.0 / Math.max(MIN_VIEWPORT_TARGET_FPS, pressureArmFps);
        double criticalArmMs = 1000.0 / Math.max(MIN_VIEWPORT_TARGET_FPS, criticalArmFps);
        double frameSeconds = clamp(frameTimeMs / 1000.0, 0.0, 0.25);

        if (engine.viewportSmoothedFrameMs >= pressureArmMs) {
            engine.viewportScalePressureSeconds = Math.min(8.0, engine.viewportScalePressureSeconds + frameSeconds);
        } else {
            engine.viewportScalePressureSeconds = Math.max(0.0, engine.viewportScalePressureSeconds - frameSeconds * 1.6);
        }

        if (engine.viewportSmoothedFrameMs >= criticalArmMs) {
            engine.viewportCriticalPressureSeconds = Math.min(8.0, engine.viewportCriticalPressureSeconds + frameSeconds);
        } else {
            engine.viewportCriticalPressureSeconds = Math.max(0.0, engine.viewportCriticalPressureSeconds - frameSeconds * 2.0);
        }
    }

    private static void resetAdaptiveViewportState(Engine engine, double recoverBlend) {
        engine.viewportAdaptiveScaleCurrent = approach(engine.viewportAdaptiveScaleCurrent, 1.0, recoverBlend);
        engine.viewportAdaptiveScaleApplied = quantizeScale(engine.viewportAdaptiveScaleCurrent, GENERAL_SCALE_STEP);
        if (engine.viewportAdaptiveScaleApplied >= 0.995) {
            engine.viewportAdaptiveScaleCurrent = 1.0;
            engine.viewportAdaptiveScaleApplied = 1.0;
        }
        engine.viewportCriticalPreviewActive = false;
        engine.viewportCriticalPreviewStartNanos = 0L;
    }

    private static void updateAdaptiveViewportAssist(
            Engine engine,
            RenderMode activeMode,
            long now,
            boolean interactionActive,
            boolean withinInteractionTail) {
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
            updateHeavyCriticalFallback(engine, now, interactionActive, withinInteractionTail, measuredMs, targetMs, minScale);
        } else {
            engine.viewportCriticalPreviewActive = false;
            engine.viewportCriticalPreviewStartNanos = 0L;
        }
    }

    private static void updateHeavyCriticalFallback(
            Engine engine,
            long now,
            boolean interactionActive,
            boolean withinInteractionTail,
            double measuredMs,
            double targetMs,
            double minScale) {
        boolean criticalPressure = interactionActive
                && withinInteractionTail
                && engine.viewportNavigationPreviewEnabled
                && engine.viewportCriticalPressureSeconds >= 2.6
                && engine.viewportAdaptiveScaleApplied <= minScale + 0.021;
        if (criticalPressure) {
            if (engine.viewportCriticalPreviewStartNanos == 0L) {
                engine.viewportCriticalPreviewStartNanos = now;
            }
            if (now - engine.viewportCriticalPreviewStartNanos >= CRITICAL_FALLBACK_DELAY_NS) {
                engine.viewportCriticalPreviewActive = true;
            }
            return;
        }

        engine.viewportCriticalPreviewStartNanos = 0L;
        if (!withinInteractionTail || measuredMs < targetMs * 1.55) {
            engine.viewportCriticalPreviewActive = false;
        }
    }

    private static double quantizeScale(double value, double step) {
        double safeStep = Math.max(0.01, step);
        double quantized = Math.round(clamp(value, 0.35, 1.0) / safeStep) * safeStep;
        if (Math.abs(1.0 - quantized) < safeStep * 0.55) {
            return 1.0;
        }
        return clamp(quantized, 0.35, 1.0);
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
