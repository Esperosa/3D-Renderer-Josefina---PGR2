import engine.camera.CameraController;
import engine.camera.PerspectiveCamera;
import engine.core.Input;
import engine.math.Quaternion;
import engine.math.Vec3;

import java.util.Random;

public class EngineCameraRegressionTests {

    private static final Vec3 CAMERA_FORWARD = new Vec3(0.0, 0.0, -1.0);
    private static final double EPS = 1e-8;

    public static void main(String[] args) {
        testOutputCameraYawPitchRoundTrip();
        testControllerSyncStateFromCameraKeepsExternalPose();
        testControllerSetModeSyncKeepsExternalPose();
        testSwitchLikeSequenceRestoresExactFpsPose();
        System.out.println("EngineCameraRegressionTests: ALL TESTS PASSED");
    }

    private static void testOutputCameraYawPitchRoundTrip() {
        Random random = new Random(12345L);
        for (int i = 0; i < 100_000; i++) {
            Vec3 forward = randomDirection(random);
            double yaw = -Math.atan2(forward.x, -forward.z);
            double pitch = Math.asin(clamp(forward.y, -1.0, 1.0));
            Vec3 restored = Quaternion.fromEuler(pitch, yaw, 0.0)
                    .rotateVector(CAMERA_FORWARD)
                    .normalize();
            assertVecNear(forward, restored, 1e-7,
                    "Output-camera yaw/pitch round-trip diverged");
        }
    }

    private static void testControllerSyncStateFromCameraKeepsExternalPose() {
        PerspectiveCamera camera = new PerspectiveCamera(70.0, 16.0 / 9.0, 0.1, 500.0);
        camera.setPosition(new Vec3(0.0, 1.7, 5.0));
        camera.lookAt(new Vec3(0.0, 1.3, 0.0));
        CameraController controller = new CameraController(camera, CameraController.Mode.FREE_LOOK);
        Input input = new Input();
        input.poll();

        Vec3 desiredForward = new Vec3(0.58, 0.19, -0.79).normalize();
        camera.lookAt(camera.getPosition().add(desiredForward));
        controller.syncStateFromCamera();
        controller.update(input, 1.0 / 60.0);

        assertVecNear(desiredForward, camera.getForward().normalize(), EPS,
                "Controller syncStateFromCamera should preserve externally set lookAt direction");
    }

    private static void testControllerSetModeSyncKeepsExternalPose() {
        PerspectiveCamera camera = new PerspectiveCamera(70.0, 1.0, 0.1, 500.0);
        camera.setPosition(new Vec3(1.0, 2.0, 3.0));
        camera.lookAt(new Vec3(0.0, 1.0, 0.0));
        CameraController controller = new CameraController(camera, CameraController.Mode.FREE_LOOK);
        Input input = new Input();
        input.poll();

        Vec3 desiredForward = new Vec3(-0.44, 0.27, -0.86).normalize();
        camera.lookAt(camera.getPosition().add(desiredForward));
        controller.setMode(CameraController.Mode.FREE_LOOK);
        controller.update(input, 1.0 / 60.0);

        assertVecNear(desiredForward, camera.getForward().normalize(), EPS,
                "Controller setMode should sync internal yaw/pitch to current camera forward");
    }

    private static void testSwitchLikeSequenceRestoresExactFpsPose() {
        PerspectiveCamera camera = new PerspectiveCamera(70.0, 16.0 / 10.0, 0.1, 500.0);
        CameraController controller = new CameraController(camera, CameraController.Mode.FREE_LOOK);
        Input input = new Input();
        input.poll();

        Vec3 fpsPos = new Vec3(2.0, 1.8, 6.0);
        Vec3 fpsForward = new Vec3(0.36, -0.08, -0.93).normalize();
        camera.setPosition(fpsPos);
        camera.lookAt(fpsPos.add(fpsForward));
        controller.syncStateFromCamera();

        Vec3 blendPos = new Vec3(-3.0, 4.0, 2.5);
        Vec3 blendForward = new Vec3(0.62, -0.31, -0.72).normalize();
        camera.setPosition(blendPos);
        camera.lookAt(blendPos.add(blendForward));
        controller.setOrbitTarget(blendPos.add(blendForward.mul(4.0)));
        controller.setMode(CameraController.Mode.ORBIT);
        controller.update(input, 1.0 / 60.0);

        camera.setPosition(fpsPos);
        camera.lookAt(fpsPos.add(fpsForward));
        controller.setMode(CameraController.Mode.FREE_LOOK);
        controller.update(input, 1.0 / 60.0);

        assertVecNear(fpsPos, camera.getPosition(), EPS,
                "FPS camera position must restore exactly after switch-like sequence");
        assertVecNear(fpsForward, camera.getForward().normalize(), EPS,
                "FPS camera forward must restore exactly after switch-like sequence");
    }

    private static Vec3 randomDirection(Random random) {
        while (true) {
            double x = random.nextDouble() * 2.0 - 1.0;
            double y = random.nextDouble() * 2.0 - 1.0;
            double z = random.nextDouble() * 2.0 - 1.0;
            Vec3 v = new Vec3(x, y, z);
            if (v.lengthSquared() > 1e-10) {
                return v.normalize();
            }
        }
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, double eps, String message) {
        double delta = expected.sub(actual).length();
        if (delta > eps) {
            throw new AssertionError(message
                    + " | expected=" + expected
                    + " actual=" + actual
                    + " delta=" + delta
                    + " eps=" + eps);
        }
    }
}
