package engine.core;

import engine.math.Vec3;
import engine.physics.RigidBody;
import engine.scene.Entity;

import java.util.Set;

public final class SceneTimelineReleaseTests {

    private static final double EPS = 1e-9;

    private SceneTimelineReleaseTests() {
    }

    public static void main(String[] args) {
        testReleaseKeyStopsTransformOverrideUntilNextRegularKey();
        testReleaseFramesAreExposedForSelectedTimelineStrip();
        System.out.println("SceneTimelineReleaseTests: ALL TESTS PASSED");
    }

    private static void testReleaseKeyStopsTransformOverrideUntilNextRegularKey() {
        Engine engine = new Engine();
        SceneTimeline timeline = engine.sceneTimeline;

        Entity entity = new Entity("TimelineReleaseEntity");
        entity.getTransform().setPosition(new Vec3(0.0, 0.0, 0.0));
        RigidBody rb = new RigidBody(entity, RigidBody.BodyType.DYNAMIC, 1.0);
        rb.setVelocity(new Vec3(1.0, 2.0, 3.0));

        timeline.addOrReplaceEntityKey(entity, 0);
        entity.getTransform().setPosition(new Vec3(20.0, 0.0, 0.0));
        timeline.addOrReplaceEntityKey(entity, 20);
        timeline.addOrReplaceEntityReleaseKey(entity, 10);

        timeline.applyAtFrame(engine, 5);
        assertNear(5.0, entity.getTransform().getPosition().x,
                "Before release key, timeline interpolation must drive transform");

        entity.getTransform().setPosition(new Vec3(42.0, 3.0, -7.0));
        rb.setVelocity(new Vec3(3.0, -1.0, 0.5));
        timeline.applyAtFrame(engine, 12);
        assertNear(42.0, entity.getTransform().getPosition().x,
                "After release key, transform must stay free for physics");
        assertVecNear(new Vec3(3.0, -1.0, 0.5), rb.getVelocity(),
                "After release key, timeline must not zero rigid-body velocity");

        rb.setVelocity(new Vec3(8.0, 0.0, 0.0));
        timeline.applyAtFrame(engine, 22);
        assertNear(20.0, entity.getTransform().getPosition().x,
                "Regular key after release must re-enable timeline transform");
        assertVecNear(Vec3.ZERO, rb.getVelocity(),
                "When timeline transform is active, rigid-body velocity should be reset");
    }

    private static void testReleaseFramesAreExposedForSelectedTimelineStrip() {
        SceneTimeline timeline = new SceneTimeline();
        Entity entity = new Entity("StripEntity");
        timeline.addOrReplaceEntityKey(entity, 1);
        timeline.addOrReplaceEntityReleaseKey(entity, 8);

        Set<Integer> selectedFrames = timeline.selectedFramesFor(entity, null, null, null);
        if (!selectedFrames.contains(1) || !selectedFrames.contains(8)) {
            throw new AssertionError("Selected timeline frames must include normal and release keys.");
        }
    }

    private static void assertNear(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, String message) {
        if (expected.sub(actual).length() > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
