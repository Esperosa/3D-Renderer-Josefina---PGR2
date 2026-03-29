package engine.core;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.scene.Scene;

public final class ViewportPerformanceConfigTests {

    private ViewportPerformanceConfigTests() {
    }

    public static void main(String[] args) {
        testViewDistanceCullingHidesFarEntitiesOnlyInViewport();
        testRasterViewportKeepsFullScaleWhenOnBudget();
        testRasterViewportAdaptiveScaleOnlyUnderPressure();
        testHeavyViewportKeepsRendererUntilCriticalFallback();
        testHeavyViewportKeepsStableScaleUntilCriticalFallback();
        testHeavyViewportPreemptiveFallbackArmsOnRapidDrop();
        testHeavyViewportWarmupSeedsPredictor();
        testHeavyViewportWarmupSeedDecaysAfterStabilization();
        testWarmupIgnoresModelFallbackFrames();
        testModelFallbackDoesNotFakeHeavyRecovery();
        testCriticalPreviewKeepsHeavyRendererScheduled();
        testPathViewportSelfHealsDenoiseQualityOnGentleMotion();
        testHeavyViewportMotionEntryAppliesImmediateDownshiftFloor();
        testMotionEntryForcesBaselineDownshift();
        testRayHeavyOverloadCanReachTwentyPercentScale();
        testInteractionSignalTriggersMotionProfileImmediately();
        testHeavyViewportIdleRecoveryIsSmooth();
        testHeavyViewportIdleEventuallyRestoresFullFidelity();
        testHeavyViewportIdleHardRestoreBreaksStuckMotionLatch();
        testHeavyViewportOfflineFidelityLockForcesFullQuality();
        testHeavyViewportFreezesDynamicSceneWhenIdle();
        System.out.println("ViewportPerformanceConfigTests: ALL TESTS PASSED");
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
        if (sanitizedFallback != RenderMode.MODEL) {
            throw new AssertionError("Safety fallback mode must still sanitize heavy fallback targets to MODEL.");
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
        engine.activeMode = RenderMode.PHONG;
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
            throw new AssertionError("Raster viewport should keep full resolution when frame time is on budget.");
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

    private static void testHeavyViewportKeepsStableScaleUntilCriticalFallback() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.60;
        engine.viewportSmoothedFrameMs = 120.0;
        engine.viewportCameraMotionActive = true;

        for (int i = 0; i < 44; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 120.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (!engine.interactiveRenderScaleActive) {
            throw new AssertionError("Heavy progressive viewport should activate adaptive scaling during sustained motion overload.");
        }
        if (engine.viewportAdaptiveScaleApplied >= 0.90) {
            throw new AssertionError("Heavy viewport should downshift internal preview scale under sustained motion overload.");
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

    private static void testHeavyViewportPreemptiveFallbackArmsOnRapidDrop() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.viewportCriticalPressureSeconds = 0.2;
        engine.viewportScalePressureSeconds = 0.4;
        engine.viewportSmoothedFrameMs = 47.0;
        engine.lastViewportInteractionNanos = System.nanoTime();
        engine.viewportFastFrameMs = 56.0;
        engine.viewportPredictedFrameMs = 60.0;
        engine.viewportHeavySmoothedFrameMs = 47.0;
        engine.viewportHeavyFastFrameMs = 56.0;
        engine.viewportHeavyPredictedFrameMs = 60.0;
        engine.viewportFrameDropStreak = 3;
        engine.viewportCriticalPreviewStartNanos = System.nanoTime() - 1_000_000_000L;
        engine.lastViewportInteractionNanos = System.nanoTime();

        EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        if (!engine.viewportCriticalPreviewActive && engine.viewportCriticalPreviewStartNanos == 0L) {
            throw new AssertionError("Heavy viewport should at least arm fallback countdown preemptively on rapid FPS collapse.");
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
        ray.progressiveViewportEnabled = true;
        ray.viewportTargetFps = 25.0;
        ray.viewportDynamicResolutionTierIndex = 0;
        ray.viewportHeavySmoothedFrameMs = 96.0;
        ray.viewportHeavyFastFrameMs = 112.0;
        ray.viewportHeavyPredictedFrameMs = 118.0;
        ray.viewportSmoothedFrameMs = 96.0;
        ray.viewportFastFrameMs = 112.0;
        ray.viewportPredictedFrameMs = 118.0;

        EngineRenderRuntime.updateRealtimePerformanceState(ray, true);

        if (ray.viewportDynamicResolutionTierIndex < 6) {
            throw new AssertionError("RAY motion entry should immediately jump to a strong downshift floor on obvious overload.");
        }
        if (ray.viewportAdaptiveScaleApplied > 0.45) {
            throw new AssertionError("RAY motion entry should reduce preview scale immediately under obvious overload.");
        }

        Engine path = new Engine();
        path.activeMode = RenderMode.PATH_TRACING;
        path.progressiveViewportEnabled = true;
        path.viewportTargetFps = 25.0;
        path.viewportDynamicResolutionTierIndex = 0;
        path.viewportHeavySmoothedFrameMs = 72.0;
        path.viewportHeavyFastFrameMs = 84.0;
        path.viewportHeavyPredictedFrameMs = 90.0;
        path.viewportSmoothedFrameMs = 72.0;
        path.viewportFastFrameMs = 84.0;
        path.viewportPredictedFrameMs = 90.0;

        EngineRenderRuntime.updateRealtimePerformanceState(path, true);

        if (path.viewportDynamicResolutionTierIndex < 5) {
            throw new AssertionError("PATH motion entry should immediately jump to a protective downshift floor on obvious overload.");
        }
        if (path.viewportAdaptiveScaleApplied > 0.50) {
            throw new AssertionError("PATH motion entry should reduce preview scale immediately under obvious overload.");
        }
    }

    private static void testMotionEntryForcesBaselineDownshift() {
        Engine ray = new Engine();
        ray.activeMode = RenderMode.RAY_TRACING;
        ray.progressiveViewportEnabled = true;
        ray.viewportTargetFps = 24.0;
        ray.viewportDynamicResolutionTierIndex = 0;
        ray.viewportHeavySmoothedFrameMs = 26.0;
        ray.viewportHeavyFastFrameMs = 28.0;
        ray.viewportHeavyPredictedFrameMs = 30.0;
        ray.viewportSmoothedFrameMs = 26.0;
        ray.viewportFastFrameMs = 28.0;
        ray.viewportPredictedFrameMs = 30.0;
        ray.viewportCameraMotionActive = true;

        EngineRenderRuntime.updateRealtimePerformanceState(ray, true);

        if (ray.viewportDynamicResolutionTierIndex < 5) {
            throw new AssertionError("RAY motion start should clamp to an aggressive base downshift tier even without measured overload.");
        }
        if (ray.viewportAdaptiveScaleApplied > 0.80) {
            throw new AssertionError("RAY motion start should immediately reduce preview scale before overload is observed.");
        }

        Engine path = new Engine();
        path.activeMode = RenderMode.PATH_TRACING;
        path.progressiveViewportEnabled = true;
        path.viewportTargetFps = 24.0;
        path.viewportDynamicResolutionTierIndex = 0;
        path.viewportHeavySmoothedFrameMs = 24.0;
        path.viewportHeavyFastFrameMs = 26.0;
        path.viewportHeavyPredictedFrameMs = 28.0;
        path.viewportSmoothedFrameMs = 24.0;
        path.viewportFastFrameMs = 26.0;
        path.viewportPredictedFrameMs = 28.0;
        path.viewportCameraMotionActive = true;

        EngineRenderRuntime.updateRealtimePerformanceState(path, true);

        if (path.viewportDynamicResolutionTierIndex < 4) {
            throw new AssertionError("PATH motion start should clamp to the baseline downshift tier immediately.");
        }
        if (path.viewportAdaptiveScaleApplied > 0.84) {
            throw new AssertionError("PATH motion start should trim preview scale right when motion begins.");
        }
    }

    private static void testRayHeavyOverloadCanReachTwentyPercentScale() {
        Engine ray = new Engine();
        ray.activeMode = RenderMode.RAY_TRACING;
        ray.progressiveViewportEnabled = true;
        ray.viewportTargetFps = 24.0;
        ray.viewportDynamicResolutionTierIndex = 0;
        ray.viewportCameraMotionActive = true;
        ray.viewportHeavySmoothedFrameMs = 260.0;
        ray.viewportHeavyFastFrameMs = 300.0;
        ray.viewportHeavyPredictedFrameMs = 320.0;
        ray.viewportSmoothedFrameMs = 260.0;
        ray.viewportFastFrameMs = 300.0;
        ray.viewportPredictedFrameMs = 320.0;

        for (int i = 0; i < 12; i++) {
            EngineRenderRuntime.updateRealtimePerformanceState(ray, true);
        }

        if (ray.viewportDynamicResolutionTierIndex < 9) {
            throw new AssertionError("RAY heavy overload should be able to downshift to the lowest tier.");
        }
        if (ray.viewportAdaptiveScaleApplied > 0.21) {
            throw new AssertionError("RAY heavy overload should allow preview scale near 20%.");
        }
    }

    private static void testInteractionSignalTriggersMotionProfileImmediately() {
        Engine ray = new Engine();
        ray.activeMode = RenderMode.RAY_TRACING;
        ray.progressiveViewportEnabled = true;
        ray.viewportTargetFps = 24.0;
        ray.viewportDynamicResolutionTierIndex = 0;
        ray.viewportCameraMotionActive = false;
        ray.viewportSceneMotionActive = false;
        ray.viewportHeavySmoothedFrameMs = 28.0;
        ray.viewportHeavyFastFrameMs = 30.0;
        ray.viewportHeavyPredictedFrameMs = 32.0;
        ray.viewportSmoothedFrameMs = 28.0;
        ray.viewportFastFrameMs = 30.0;
        ray.viewportPredictedFrameMs = 32.0;

        EngineRenderRuntime.updateRealtimePerformanceState(ray, true);

        if (ray.viewportDynamicResolutionTierIndex < 5) {
            throw new AssertionError("Interaction-triggered motion must apply the entry downshift in the same update tick.");
        }
        if (ray.viewportAdaptiveScaleApplied > 0.50) {
            throw new AssertionError("Interaction-triggered motion should start with startup floor quality near 50% in the same update tick.");
        }
    }

    private static void testHeavyViewportIdleRecoveryIsSmooth() {
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
        engine.lastViewportInteractionNanos = System.nanoTime();
        engine.viewportSmoothedFrameMs = 80.0;
        engine.viewportFastFrameMs = 80.0;
        engine.viewportPredictedFrameMs = 80.0;
        engine.viewportHeavySmoothedFrameMs = 80.0;
        engine.viewportHeavyFastFrameMs = 80.0;
        engine.viewportHeavyPredictedFrameMs = 80.0;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);

        if (engine.viewportDynamicResolutionTierIndex <= 0) {
            throw new AssertionError("Idle heavy viewport should avoid abrupt one-frame tier reset to full quality.");
        }
        if (engine.viewportAdaptiveScaleApplied <= 0.55 || engine.viewportAdaptiveScaleApplied >= 1.0) {
            throw new AssertionError("Idle heavy viewport should recover scale smoothly, not stay stuck or snap to full in one frame.");
        }
    }

    private static void testHeavyViewportIdleEventuallyRestoresFullFidelity() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.viewportAdaptiveScaleCurrent = 0.45;
        engine.viewportAdaptiveScaleApplied = 0.45;
        engine.viewportDynamicResolutionTierIndex = 7;
        engine.viewportMotionLatchedActive = false;
        engine.viewportCameraMotionActive = false;
        engine.viewportSceneMotionActive = false;
        engine.renderModeSwitchTransitionActive = false;
        engine.safetyRecoveryActive = false;
        engine.viewportScalePressureSeconds = 0.0;
        engine.viewportCriticalPressureSeconds = 0.0;
        engine.renderScale = 0.60;
        engine.lastViewportInteractionNanos = System.nanoTime() - 1_200_000_000L;
        engine.viewportLastMotionNanos = System.nanoTime() - 1_200_000_000L;
        engine.safetyViewportScaleClamp = 0.58;
        engine.viewportSmoothedFrameMs = 22.0;
        engine.viewportFastFrameMs = 21.0;
        engine.viewportPredictedFrameMs = 22.0;
        engine.viewportHeavySmoothedFrameMs = 22.0;
        engine.viewportHeavyFastFrameMs = 21.0;
        engine.viewportHeavyPredictedFrameMs = 22.0;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);

        if (engine.viewportDynamicResolutionTierIndex != 0) {
            throw new AssertionError("Stable idle viewport should eventually restore dynamic resolution tier to full.");
        }
        if (Math.abs(engine.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("Stable idle viewport should eventually restore full preview scale.");
        }
        if (Math.abs(EngineRenderRuntime.effectiveRenderScale(engine) - 1.0) > 1e-9) {
            throw new AssertionError("Stable idle viewport should eventually restore effective full resolution scale.");
        }
        if (Math.abs(engine.safetyViewportScaleClamp - 1.0) > 1e-9) {
            throw new AssertionError("Stable idle viewport should release safety scale clamp outside active recovery.");
        }
    }

    private static void testHeavyViewportIdleHardRestoreBreaksStuckMotionLatch() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportMotionLatchedActive = true;
        engine.viewportAdaptiveScaleCurrent = 0.40;
        engine.viewportAdaptiveScaleApplied = 0.40;
        engine.viewportDynamicResolutionTierIndex = 8;
        engine.viewportScalePressureSeconds = 0.0;
        engine.viewportCriticalPressureSeconds = 0.0;
        engine.lastViewportInteractionNanos = System.nanoTime() - 1_500_000_000L;
        engine.viewportLastMotionNanos = System.nanoTime() - 1_500_000_000L;

        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);

        if (engine.viewportMotionLatchedActive) {
            throw new AssertionError("Long idle should clear stale motion latch and restore offline quality mode.");
        }
        if (engine.viewportDynamicResolutionTierIndex != 0) {
            throw new AssertionError("Long idle hard restore should force full resolution tier.");
        }
        if (Math.abs(engine.viewportAdaptiveScaleApplied - 1.0) > 1e-9) {
            throw new AssertionError("Long idle hard restore should force full preview scale.");
        }
    }

    private static void testHeavyViewportOfflineFidelityLockForcesFullQuality() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.renderScale = 0.60;
        engine.safetyViewportScaleClamp = 0.58;
        engine.viewportAdaptiveScaleCurrent = 0.50;
        engine.viewportAdaptiveScaleApplied = 0.50;
        engine.viewportMotionLatchedActive = true;
        engine.viewportScalePressureSeconds = 0.0;
        engine.viewportCriticalPressureSeconds = 0.0;
        engine.lastViewportInteractionNanos = System.nanoTime() - 2_000_000_000L;
        engine.viewportLastMotionNanos = System.nanoTime() - 2_000_000_000L;
        engine.viewportDisplayedMode = RenderMode.PATH_TRACING;

        for (int i = 0; i < 240; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 20.0, RenderMode.PATH_TRACING);
        }

        EngineRenderRuntime.updateRealtimePerformanceState(engine, false);

        if (Math.abs(EngineRenderRuntime.effectiveRenderScale(engine) - 1.0) > 1e-9) {
            throw new AssertionError("Offline fidelity lock should force effective full resolution scale.");
        }
        if (engine.viewportMotionLatchedActive) {
            throw new AssertionError("Offline fidelity lock should clear stale motion latch.");
        }
    }
}
