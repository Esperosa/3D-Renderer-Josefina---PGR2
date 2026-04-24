package engine.core;

public final class EngineSafetyControllerTests {

    private EngineSafetyControllerTests() {
    }

    public static void main(String[] args) {
        armsRecoveryAfterRepeatedSevereFrames();
        ignoresStaticPathTracingFrames();
        releasesRecoveryAfterCooldown();
        guardsHeavyModeImmediatelyAfterRecovery();
        keepsHeavyRendererWhileCameraKeepsMoving();
        keepsHeavyRendererOnMovementInteraction();
        System.out.println("EngineSafetyControllerTests: ALL TESTS PASSED");
    }

    private static void armsRecoveryAfterRepeatedSevereFrames() {
        Engine engine = new Engine();
        long now = 1_000_000_000L;

        EngineSafetyController.recordFrame(engine, 720.0, now, true);
        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Single slow frame should not arm recovery immediately.");
        }

        EngineSafetyController.recordFrame(engine, 720.0, now + 40_000_000L, true);
        if (!engine.safetyRecoveryActive) {
            throw new AssertionError("Repeated severe frames should arm recovery.");
        }
        if (engine.safetyViewportScaleClamp >= 0.99) {
            throw new AssertionError("Recovery should lower the temporary safety scale clamp.");
        }
        if (engine.effectiveRenderScale() >= 0.99) {
            throw new AssertionError("Effective render scale should respect the safety clamp.");
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 60_000_000L)) {
            throw new AssertionError("Safety recovery must not freeze viewport frames.");
        }
    }

    private static void releasesRecoveryAfterCooldown() {
        Engine engine = new Engine();
        long now = 2_000_000_000L;

        EngineSafetyController.recordRenderFailure(engine, now);
        if (!engine.safetyRecoveryActive) {
            throw new AssertionError("Render failure should arm safety recovery.");
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 200_000_000L)) {
            throw new AssertionError("Recovery must remain non-blocking during cooldown.");
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 3_000_000_000L)) {
            throw new AssertionError("Safety hold should remain disabled after cooldown.");
        }
        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Recovery flag should be cleared after cooldown.");
        }
        if (Math.abs(engine.safetyViewportScaleClamp - 1.0) > 1e-9) {
            throw new AssertionError("Safety scale clamp should return to full quality after recovery.");
        }
    }

    private static void ignoresStaticPathTracingFrames() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.pathAccumulationLock = true;
        engine.timelineEnabled = false;
        engine.animationPlaybackEnabled = true;

        long now = 5_000_000_000L;
        EngineSafetyController.recordFrame(engine, 820.0, now, false);
        EngineSafetyController.recordFrame(engine, 900.0, now + 40_000_000L, false);

        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Static path tracing should not enter recovery on slow frames.");
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 80_000_000L)) {
            throw new AssertionError("Static path tracing should not arm recovery from safety hold checks.");
        }
    }

    private static void guardsHeavyModeImmediatelyAfterRecovery() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.viewportNavigationPreviewEnabled = true;

        engine.safetyRecoveryActive = false;
        engine.safetyLastRecoveryNanos = System.nanoTime();
        RenderMode guardedMode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (guardedMode != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy mode should stay in the active live renderer immediately after recovery.");
        }

        engine.safetyLastRecoveryNanos = System.nanoTime() - 2_000_000_000L;
        RenderMode releasedMode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (releasedMode != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy mode should be allowed again after re-entry guard window.");
        }
    }

    private static void keepsHeavyRendererWhileCameraKeepsMoving() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportNavigationFallbackMode = RenderMode.MODEL;

        engine.viewportCameraMotionActive = true;
        RenderMode firstDrop = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (firstDrop != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy mode should stay in the same renderer during camera motion.");
        }
        if (engine.viewportFallbackLockActive) {
            throw new AssertionError("Normal camera motion should not lock a fallback renderer anymore.");
        }

        RenderMode lockedMode = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (lockedMode != RenderMode.PATH_TRACING) {
            throw new AssertionError("Repeated movement checks should keep the heavy renderer active.");
        }

        engine.viewportCameraMotionActive = false;
        RenderMode releasedMode = EngineRenderRuntime.resolveViewportRenderMode(engine, false);
        if (releasedMode != RenderMode.PATH_TRACING) {
            throw new AssertionError("Heavy mode should remain active after camera motion stops.");
        }
        if (engine.viewportFallbackLockActive) {
            throw new AssertionError("Fallback lock should stay cleared for normal navigation.");
        }
    }

    private static void keepsHeavyRendererOnMovementInteraction() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.RAY_TRACING;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportNavigationFallbackMode = RenderMode.MODEL;

        engine.viewportCameraMotionActive = true;
        RenderMode moving = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
        if (moving != RenderMode.RAY_TRACING) {
            throw new AssertionError("Heavy viewport should not switch to model mode during movement interaction.");
        }

        engine.viewportCameraMotionActive = false;
        RenderMode idle = EngineRenderRuntime.resolveViewportRenderMode(engine, false);
        if (idle != RenderMode.RAY_TRACING) {
            throw new AssertionError("Heavy viewport should stay in heavy mode after movement stops.");
        }
    }
}
