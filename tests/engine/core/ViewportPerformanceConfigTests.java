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
        testHeavyViewportAdaptiveScaleDropsTowardTarget();
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
            throw new AssertionError("Heavy viewport should stay in PATH mode until critical fallback is armed.");
        }

        engine.viewportCriticalPreviewActive = true;
        RenderMode critical = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (critical != RenderMode.MODEL) {
            throw new AssertionError("Critical fallback should switch the viewport to MODEL.");
        }

        engine.activeMode = RenderMode.PHONG;
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

    private static void testHeavyViewportAdaptiveScaleDropsTowardTarget() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.progressiveViewportEnabled = true;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.60;
        engine.viewportSmoothedFrameMs = 120.0;

        for (int i = 0; i < 44; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 120.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (!engine.interactiveRenderScaleActive) {
            throw new AssertionError("Heavy viewport should enable adaptive scale when frame time is too high.");
        }
        if (engine.viewportAdaptiveScaleApplied >= 0.99) {
            throw new AssertionError("Adaptive scale should drop below full resolution under sustained heavy load.");
        }
        if (engine.viewportCriticalPreviewActive) {
            throw new AssertionError("Adaptive scale alone should not immediately trigger fallback preview.");
        }
    }
}
