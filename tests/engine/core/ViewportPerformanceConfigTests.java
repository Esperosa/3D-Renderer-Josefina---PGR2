package engine.core;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.ray.preview.ProgressiveRenderDefaults;
import engine.scene.Entity;
import engine.scene.Scene;

public final class ViewportPerformanceConfigTests {

    private ViewportPerformanceConfigTests() {
    }

    public static void main(String[] args) {
        testDefaultViewportSampleSteps();
        testViewDistanceCullingHidesFarEntitiesOnlyInViewport();
        testRasterViewportKeepsFullScaleWhenOnBudget();
        testLightMotionScaleForCostlierRasterModes();
        testRasterViewportAdaptiveScaleOnlyUnderPressure();
        testHeavyViewportKeepsRendererUntilCriticalFallback();
        testHeavyViewportDropsScaleImmediatelyForLiveMotion();
        testHeavyViewportRapidDropKeepsLiveRenderer();
        testHeavyViewportWarmupSeedsPredictor();
        testHeavyViewportWarmupSeedDecaysAfterStabilization();
        testWarmupIgnoresModelFallbackFrames();
        testModelFallbackDoesNotFakeHeavyRecovery();
        testCriticalPreviewKeepsHeavyRendererScheduled();
        testPathViewportSelfHealsDenoiseQualityOnGentleMotion();
        testHeavyViewportMotionEntryAppliesImmediateDownshiftFloor();
        testHeavyViewportMotionAllowsEmergencyPreviewDimensions();
        testHeavyViewportIdleSnapRestoresFullScale();
        testHeavyViewportFreezesDynamicSceneWhenIdle();
        System.out.println("ViewportPerformanceConfigTests: ALL TESTS PASSED");
    }

    private static void testDefaultViewportSampleSteps() {
        Engine engine = new Engine();
        if (ProgressiveRenderDefaults.RAY_VIEWPORT_SAMPLES_PER_FRAME != 4) {
            throw new AssertionError("Ray viewport default should advance by 4 samples per frame.");
        }
        if (ProgressiveRenderDefaults.PATH_VIEWPORT_SAMPLES_PER_FRAME != 1) {
            throw new AssertionError("Path viewport default should advance by 1 sample per frame.");
        }
        if (engine.raySamplesPerFrame != 4) {
            throw new AssertionError("Engine should initialize Ray samples/frame to 4.");
        }
        if (engine.pathSamplesPerFrame != 1) {
            throw new AssertionError("Engine should initialize Path samples/frame to 1.");
        }
    }

    private static void testViewDistanceCullingHidesFarEntitiesOnlyInViewport() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.camera = new PerspectiveCamera(60.0, 1.0, 0.1, 200.0);
        engine.camera.setPosition(Vec3.ZERO);
        engine.camera.lookAt(new Vec3(0.0, 0.0, 1.0));
        engine.viewDistanceCullingEnabled = true;
        engine.viewDistanceLimit = 12.0;

        Entity near = new Entity("near", MeshGenerator.cube(1.0), new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 16.0));
        near.getTransform().setPosition(new Vec3(0.0, 0.0, 5.0));
        engine.scene.addEntity(near);

        Entity far = new Entity("far", MeshGenerator.cube(1.0), new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 16.0));
        far.getTransform().setPosition(new Vec3(0.0, 0.0, 26.0));
        engine.scene.addEntity(far);

        engine.initializeSceneItemStateDefaults();
        engine.applySceneVisibility(false);
        if (!near.isVisible()) {
            throw new AssertionError("Near entity should stay visible inside the view range.");
        }
        if (far.isVisible()) {
            throw new AssertionError("Far entity should be hidden by viewport distance culling.");
        }

        engine.applySceneVisibility(true);
        if (!far.isVisible()) {
            throw new AssertionError("Output pass should ignore viewport-only distance culling.");
        }
    }

    private static void testHeavyViewportKeepsRendererUntilCriticalFallback() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportNavigationFallbackMode = RenderMode.MODEL;

        RenderMode moving = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (moving != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy viewport should stay in PATH mode during navigation.");
        }

        engine.viewportCriticalPreviewActive = true;
        RenderMode critical = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (critical != RenderMode.PATH_TRACING) {
            throw new AssertionError("Critical preview pressure should keep the same heavy renderer active.");
        }

        RenderMode idleCritical = EngineRenderRuntime.resolveViewportRenderMode(engine, false);
        if (idleCritical != RenderMode.PATH_TRACING) {
            throw new AssertionError("Idle critical state should still keep the heavy renderer active.");
        }

        engine.viewportNavigationFallbackMode = RenderMode.RAY_TRACING;
        engine.safetyRecoveryActive = true;
        RenderMode sanitizedFallback = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (sanitizedFallback != RenderMode.PATH_TRACING) {
            throw new AssertionError("Safety recovery must keep heavy viewport in the active live renderer.");
        }
        engine.safetyRecoveryActive = false;

        engine.activeMode = RenderMode.PHONG;
        engine.viewportCriticalPreviewActive = false;
        RenderMode phongMoving = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (phongMoving != RenderMode.PHONG) {
            throw new AssertionError("Realtime raster modes should stay active during navigation.");
        }
    }

    private static void testRasterViewportKeepsFullScaleWhenOnBudget() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.BASIC;
        engine.progressiveViewportEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.60;
        engine.viewportSmoothedFrameMs = 18.0;

        for (int i = 0; i < 12; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 18.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (engine.interactiveRenderScaleActive) {
            throw new AssertionError("Viewport should stay at full quality when it is already above the FPS target.");
        }
        if (engine.viewportAdaptiveScaleApplied != 1.0) {
            throw new AssertionError("Lightweight raster viewport should keep full resolution when frame time is on budget.");
        }
    }

    private static void testLightMotionScaleForCostlierRasterModes() {
        Engine phong = new Engine();
        phong.activeMode = RenderMode.PHONG;
        phong.progressiveViewportEnabled = true;
        phong.viewportTargetFps = 25.0;
        phong.viewportSmoothedFrameMs = 16.0;
        phong.viewportFastFrameMs = 16.0;

        for (int i = 0; i < 6; i++) {
            EngineRenderRuntime.recordViewportFrameTime(phong, 16.0);
            EngineRenderRuntime.updateRealtimePerformanceState(phong, true);
        }

        if (!phong.interactiveRenderScaleActive) {
            throw new AssertionError("PHONG should use a barely visible motion scale while navigating.");
        }
        if (phong.viewportAdaptiveScaleApplied < 0.94 || phong.viewportAdaptiveScaleApplied > 0.98) {
            throw new AssertionError("PHONG motion scale should stay visually light: " + phong.viewportAdaptiveScaleApplied);
        }

        for (int i = 0; i < 12; i++) {
            EngineRenderRuntime.recordViewportFrameTime(phong, 16.0);
            EngineRenderRuntime.updateRealtimePerformanceState(phong, false);
        }

        if (Math.abs(phong.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("PHONG should restore full resolution after navigation stops.");
        }

        Engine model = new Engine();
        model.activeMode = RenderMode.MODEL;
        model.progressiveViewportEnabled = true;
        model.viewportTargetFps = 25.0;
        model.viewportSmoothedFrameMs = 16.0;
        for (int i = 0; i < 8; i++) {
            EngineRenderRuntime.recordViewportFrameTime(model, 16.0);
            EngineRenderRuntime.updateRealtimePerformanceState(model, true);
        }

        if (model.interactiveRenderScaleActive || Math.abs(model.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("MODEL should remain at full scale on budget.");
        }
    }

    private static void testRasterViewportAdaptiveScaleOnlyUnderPressure() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.DITHERING;
        engine.progressiveViewportEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.55;
        engine.viewportSmoothedFrameMs = 72.0;

        for (int i = 0; i < 40; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 72.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (!engine.interactiveRenderScaleActive) {
            throw new AssertionError("Raster viewport should adapt when frame time is far above target.");
        }
        if (engine.viewportAdaptiveScaleApplied >= 1.0) {
            throw new AssertionError("Raster viewport should reduce internal resolution under real pressure.");
        }
        if (engine.viewportCriticalPreviewActive) {
            throw new AssertionError("Non-heavy modes should never trigger critical fallback preview.");
        }
    }

    private static void testHeavyViewportDropsScaleImmediatelyForLiveMotion() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.viewportDisplayedMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.60;
        engine.viewportSmoothedFrameMs = 120.0;
        engine.viewportFastFrameMs = 120.0;
        engine.viewportPredictedFrameMs = 120.0;
        engine.viewportHeavySmoothedFrameMs = 120.0;
        engine.viewportHeavyFastFrameMs = 120.0;
        engine.viewportHeavyPredictedFrameMs = 120.0;
        engine.viewportCameraMotionActive = true;

        for (int i = 0; i < 44; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 120.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (!engine.interactiveRenderScaleActive) {
            throw new AssertionError("Heavy progressive viewport should immediately downscale for live motion.");
        }
        if (engine.viewportAdaptiveScaleApplied > 0.60) {
            throw new AssertionError("Heavy viewport should enter a low internal resolution immediately during motion.");
        }
        RenderMode liveMode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (liveMode != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy viewport motion must stay in the active live renderer.");
        }

        long now = System.nanoTime();
        engine.viewportCriticalPressureSeconds = 3.0;
        engine.viewportSmoothedFrameMs = 140.0;
        engine.viewportFastFrameMs = 140.0;
        engine.viewportPredictedFrameMs = 140.0;
        engine.viewportHeavySmoothedFrameMs = 140.0;
        engine.viewportHeavyFastFrameMs = 140.0;
        engine.viewportHeavyPredictedFrameMs = 140.0;
        engine.viewportFrameDropStreak = 2;
        engine.viewportCriticalPreviewStartNanos = now - 1_000_000_000L;
        engine.lastViewportInteractionNanos = now;
        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        if (!engine.viewportCriticalPreviewActive) {
            throw new AssertionError("Heavy viewport should arm critical preview pressure only after sustained overload.");
        }

        engine.viewportCriticalPressureSeconds = 0.0;
        engine.viewportSmoothedFrameMs = 28.0;
        engine.viewportCriticalPreviewHoldUntilNanos = 0L;
        engine.viewportCriticalRecoverStartNanos = System.nanoTime() - 1_000_000_000L;
        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);
        if (engine.viewportCriticalPreviewActive) {
            throw new AssertionError("Critical fallback preview should disarm once interaction and pressure subside.");
        }
    }

    private static void testHeavyViewportRapidDropKeepsLiveRenderer() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.viewportDisplayedMode = RenderMode.RAY_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.viewportCriticalPressureSeconds = 0.2;
        engine.viewportScalePressureSeconds = 0.4;
        engine.viewportSmoothedFrameMs = 47.0;
        engine.viewportFastFrameMs = 56.0;
        engine.viewportPredictedFrameMs = 60.0;
        engine.viewportFrameDropStreak = 3;
        engine.viewportCriticalPreviewStartNanos = System.nanoTime() - 1_000_000_000L;
        engine.lastViewportInteractionNanos = System.nanoTime();
        engine.viewportCameraMotionActive = true;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        if (engine.viewportDynamicResolutionTierIndex < 4) {
            throw new AssertionError("Heavy viewport should downshift quality immediately on rapid FPS collapse.");
        }
        if (engine.viewportAdaptiveScaleApplied > 0.60) {
            throw new AssertionError("Heavy viewport should apply a low live scale immediately on rapid FPS collapse.");
        }
        RenderMode mode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (mode != RenderMode.RAY_TRACING) {
            throw new AssertionError("Rapid FPS collapse must keep the active live renderer instead of a model fallback.");
        }
    }

    private static void testHeavyViewportWarmupSeedsPredictor() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        if (!engine.viewportWarmupCaptureActive) {
            throw new AssertionError("Warmup capture should arm at interaction start in heavy mode.");
        }

        double[] warmupSamples = {44.0, 47.0, 43.0, 49.0, 46.0, 52.0, 45.0, 48.0};
        for (double sample : warmupSamples) {
            EngineRenderRuntime.recordViewportFrameTime(engine, sample, RenderMode.PATH_TRACING);
        }

        engine.viewportWarmupUntilNanos = System.nanoTime() - 1L;
        EngineRenderRuntime.recordViewportFrameTime(engine, 46.0, RenderMode.PATH_TRACING);

        if (engine.viewportWarmupSeedWeight <= 0.0) {
            throw new AssertionError("Warmup completion should seed predictor with a positive confidence weight.");
        }
        if (engine.viewportPredictedFrameMs < 43.0) {
            throw new AssertionError("Warmup seed should raise predicted frame time toward observed warmup pressure.");
        }
    }

    private static void testHeavyViewportWarmupSeedDecaysAfterStabilization() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.viewportInteractionActiveLast = true;
        engine.viewportWarmupCaptureActive = false;
        engine.viewportWarmupSeedMs = 62.0;
        engine.viewportWarmupSeedWeight = 0.65;
        engine.viewportWarmupSeedExpiresNanos = System.nanoTime() + 1_000_000_000L;
        engine.viewportSmoothedFrameMs = 30.0;
        engine.viewportFastFrameMs = 32.0;
        engine.viewportPredictedFrameMs = 34.0;

        for (int i = 0; i < 28; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 24.0, RenderMode.RAY_TRACING);
        }

        if (engine.viewportWarmupSeedWeight >= 0.20) {
            throw new AssertionError("Warmup seed confidence should decay after a stable frame-time run.");
        }
        if (engine.viewportPredictedFrameMs > 38.0) {
            throw new AssertionError("Predictor should converge toward new stable performance after warmup window.");
        }
    }

    private static void testWarmupIgnoresModelFallbackFrames() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        if (!engine.viewportWarmupCaptureActive) {
            throw new AssertionError("Warmup capture should start at heavy interaction begin.");
        }

        for (int i = 0; i < 8; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 11.0, RenderMode.MODEL);
        }

        if (engine.viewportWarmupSampleCount != 0) {
            throw new AssertionError("Warmup should ignore fallback MODEL frames for heavy predictor seed.");
        }
    }

    private static void testModelFallbackDoesNotFakeHeavyRecovery() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportCriticalPreviewActive = true;
        engine.viewportCriticalPreviewHoldUntilNanos = System.nanoTime() + 1_000_000_000L;
        engine.viewportHeavySmoothedFrameMs = 56.0;
        engine.viewportHeavyFastFrameMs = 58.0;
        engine.viewportHeavyPredictedFrameMs = 61.0;
        engine.viewportPredictedFrameMs = 61.0;
        engine.viewportLastHeavyFrameNanos = System.nanoTime() - 900_000_000L;
        engine.viewportCameraMotionActive = true;

        for (int i = 0; i < 14; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 11.5, RenderMode.MODEL);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (engine.viewportHeavyPredictedFrameMs < 40.0) {
            throw new AssertionError("Heavy predictor should not collapse to MODEL fallback frame times.");
        }
        if (!engine.viewportCriticalPreviewActive) {
            throw new AssertionError("MODEL fallback speed must not immediately disarm heavy critical preview.");
        }
    }

    private static void testCriticalPreviewKeepsHeavyRendererScheduled() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportCriticalPreviewActive = true;
        engine.viewportLastHeavyFrameNanos = System.nanoTime() - 900_000_000L;

        for (int i = 0; i < 12; i++) {
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
            RenderMode mode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
            if (mode != RenderMode.RAY_TRACING) {
                throw new AssertionError("Critical preview should keep scheduling the same heavy renderer.");
            }
        }
    }

    private static void testHeavyViewportFreezesDynamicSceneWhenIdle() {
        Engine engine = new Engine();
        engine.animationPlaybackEnabled = true;
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.timelineEnabled = false;

        if (EngineRenderRuntime.shouldAdvanceDynamicScene(engine, false)) {
            throw new AssertionError("Idle path viewport should freeze dynamic scene updates to preserve accumulation.");
        }
        if (!EngineRenderRuntime.shouldAdvanceDynamicScene(engine, true)) {
            throw new AssertionError("Heavy viewport should keep scene playback alive while the user is interacting.");
        }

        engine.timelineEnabled = true;
        if (!EngineRenderRuntime.shouldAdvanceDynamicScene(engine, false)) {
            throw new AssertionError("Explicit timeline playback should keep the heavy viewport animated.");
        }
    }

    private static void testPathViewportSelfHealsDenoiseQualityOnGentleMotion() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.viewportSmoothedFrameMs = 30.0;

        for (int i = 0; i < 34; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 30.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (engine.viewportPathGentleMotionSeconds < 0.70) {
            throw new AssertionError("Gentle motion window should accumulate enough time for PATH quality recovery.");
        }
        if (!"QUALITY".equals(engine.viewportPathDenoiseProfileApplied)) {
            throw new AssertionError("PATH gentle motion should auto-restore denoise profile to QUALITY.");
        }
        if (!"FULL_FRAME".equals(engine.viewportPathDenoiseRuntimeModeApplied)) {
            throw new AssertionError("PATH gentle motion should auto-restore full-frame denoise runtime mode.");
        }
        if (!engine.viewportPathDenoiseEnabledApplied) {
            throw new AssertionError("PATH gentle motion should keep denoise enabled.");
        }
        if (engine.viewportCriticalPreviewActive) {
            throw new AssertionError("Gentle PATH motion should not arm critical fallback preview.");
        }
    }

    private static void testHeavyViewportMotionEntryAppliesImmediateDownshiftFloor() {
        Engine ray = new Engine();
        ray.activeMode = RenderMode.RAY_TRACING;
        ray.viewportDisplayedMode = RenderMode.RAY_TRACING;
        ray.progressiveViewportEnabled = true;
        ray.viewportTargetFps = 25.0;
        ray.viewportDynamicResolutionTierIndex = 0;
        ray.viewportHeavySmoothedFrameMs = 96.0;
        ray.viewportHeavyFastFrameMs = 112.0;
        ray.viewportHeavyPredictedFrameMs = 118.0;
        ray.viewportSmoothedFrameMs = 96.0;
        ray.viewportFastFrameMs = 112.0;
        ray.viewportPredictedFrameMs = 118.0;
        ray.viewportCameraMotionActive = true;

        EngineRenderRuntime.updateRealtimePerformanceState(ray, true);

        if (ray.viewportDynamicResolutionTierIndex < 6) {
            throw new AssertionError("RAY motion entry should immediately jump to a strong downshift floor on obvious overload.");
        }
        if (ray.viewportAdaptiveScaleApplied > 0.26) {
            throw new AssertionError("RAY motion entry should reduce preview scale immediately under obvious overload.");
        }

        Engine path = new Engine();
        path.activeMode = RenderMode.PATH_TRACING;
        path.viewportDisplayedMode = RenderMode.PATH_TRACING;
        path.progressiveViewportEnabled = true;
        path.viewportTargetFps = 25.0;
        path.viewportDynamicResolutionTierIndex = 0;
        path.viewportHeavySmoothedFrameMs = 72.0;
        path.viewportHeavyFastFrameMs = 84.0;
        path.viewportHeavyPredictedFrameMs = 90.0;
        path.viewportSmoothedFrameMs = 72.0;
        path.viewportFastFrameMs = 84.0;
        path.viewportPredictedFrameMs = 90.0;
        path.viewportCameraMotionActive = true;

        EngineRenderRuntime.updateRealtimePerformanceState(path, true);

        if (path.viewportDynamicResolutionTierIndex < 5) {
            throw new AssertionError("PATH motion entry should immediately jump to a protective downshift floor on obvious overload.");
        }
        if (path.viewportAdaptiveScaleApplied > 0.34) {
            throw new AssertionError("PATH motion entry should reduce preview scale immediately under obvious overload.");
        }
    }

    private static void testHeavyViewportMotionAllowsEmergencyPreviewDimensions() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.baseWidth = 704;
        engine.baseHeight = 440;
        engine.viewportAdaptiveScaleApplied = 0.25;
        engine.viewportAdaptiveScaleCurrent = 0.25;
        engine.interactiveRenderScaleActive = true;
        engine.viewportInteractionActiveLast = true;
        engine.viewportMotionLatchedActive = true;

        int width = EngineRenderRuntime.scaledWidth(engine);
        int height = EngineRenderRuntime.scaledHeight(engine);
        if (width >= 320 || height >= 200) {
            throw new AssertionError("Heavy live motion should be allowed below the static 320x200 preview floor.");
        }
        if (width != 176 || height != 110) {
            throw new AssertionError("Unexpected emergency heavy motion preview size: " + width + "x" + height);
        }
    }

    private static void testHeavyViewportIdleSnapRestoresFullScale() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.viewportAdaptiveScaleCurrent = 0.55;
        engine.viewportAdaptiveScaleApplied = 0.55;
        engine.viewportDynamicResolutionTierIndex = 5;
        engine.viewportMotionLatchedActive = false;
        engine.viewportCameraMotionActive = false;
        engine.viewportSceneMotionActive = false;
        engine.renderModeSwitchTransitionActive = false;
        engine.safetyRecoveryActive = false;
        engine.viewportSmoothedFrameMs = 45.0;
        engine.viewportFastFrameMs = 45.0;
        engine.viewportPredictedFrameMs = 45.0;
        engine.viewportHeavySmoothedFrameMs = 45.0;
        engine.viewportHeavyFastFrameMs = 45.0;
        engine.viewportHeavyPredictedFrameMs = 45.0;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);

        if (engine.viewportDynamicResolutionTierIndex != 0) {
            throw new AssertionError("Idle heavy viewport should restore full dynamic-resolution tier immediately.");
        }
        if (Math.abs(engine.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("Idle heavy viewport should snap back to full preview scale in one step.");
        }

        Engine pressured = new Engine();
        pressured.activeMode = RenderMode.PATH_TRACING;
        pressured.progressiveViewportEnabled = true;
        pressured.viewportTargetFps = 25.0;
        pressured.viewportAdaptiveScaleCurrent = 0.55;
        pressured.viewportAdaptiveScaleApplied = 0.55;
        pressured.viewportDynamicResolutionTierIndex = 5;
        pressured.viewportHeavySmoothedFrameMs = 120.0;
        pressured.viewportHeavyFastFrameMs = 120.0;
        pressured.viewportHeavyPredictedFrameMs = 120.0;
        pressured.viewportSmoothedFrameMs = 120.0;
        pressured.viewportFastFrameMs = 120.0;
        pressured.viewportPredictedFrameMs = 120.0;

        EngineRenderRuntime.updateRealtimePerformanceState(pressured, false);

        if (pressured.viewportDynamicResolutionTierIndex != 0) {
            throw new AssertionError("Idle heavy viewport should force full dynamic-resolution tier after motion stops.");
        }
        if (Math.abs(pressured.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("Idle heavy viewport should restore full preview scale even after sustained pressure.");
        }
    }
}
