package engine.core;

public final class EngineSafetyControllerTests {

    private EngineSafetyControllerTests() {
    }

    public static void main(String[] args) {
        armsRecoveryAfterRepeatedSevereFrames();
        releasesRecoveryAfterCooldown();
        System.out.println("EngineSafetyControllerTests: ALL TESTS PASSED");
    }

    private static void armsRecoveryAfterRepeatedSevereFrames() {
        Engine engine = new Engine();
        long now = 1_000_000_000L;

        EngineSafetyController.recordFrame(engine, 720.0, now);
        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Single slow frame should not arm recovery immediately.");
        }

        EngineSafetyController.recordFrame(engine, 720.0, now + 40_000_000L);
        if (!engine.safetyRecoveryActive) {
            throw new AssertionError("Repeated severe frames should arm recovery.");
        }
        if (engine.safetyViewportScaleClamp >= 0.99) {
            throw new AssertionError("Recovery should lower the temporary safety scale clamp.");
        }
        if (engine.effectiveRenderScale() >= 0.99) {
            throw new AssertionError("Effective render scale should respect the safety clamp.");
        }
        if (!EngineSafetyController.shouldHoldFrame(engine, now + 60_000_000L)) {
            throw new AssertionError("Recovery should hold the next frame while cooling down.");
        }
    }

    private static void releasesRecoveryAfterCooldown() {
        Engine engine = new Engine();
        long now = 2_000_000_000L;

        EngineSafetyController.recordRenderFailure(engine, now);
        if (!engine.safetyRecoveryActive) {
            throw new AssertionError("Render failure should arm safety recovery.");
        }
        if (!EngineSafetyController.shouldHoldFrame(engine, now + 200_000_000L)) {
            throw new AssertionError("Recovery should stay active during the cooldown window.");
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 3_000_000_000L)) {
            throw new AssertionError("Recovery should release after the cooldown window ends.");
        }
        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Recovery flag should be cleared after cooldown.");
        }
        if (Math.abs(engine.safetyViewportScaleClamp - 1.0) > 1e-9) {
            throw new AssertionError("Safety scale clamp should return to full quality after recovery.");
        }
    }
}
