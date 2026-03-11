package engine.core;

import engine.camera.CameraController;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.math.AABB;
import engine.math.Vec3;

public final class EngineSceneImportFocusTests {

    private static final double EPS = 1e-6;

    private EngineSceneImportFocusTests() {
    }

    public static void main(String[] args) {
        testImportedBoundsRefocusesCameraAndOrbitTarget();
        System.out.println("EngineSceneImportFocusTests: ALL TESTS PASSED");
    }

    private static void testImportedBoundsRefocusesCameraAndOrbitTarget() {
        Engine engine = new Engine();
        engine.perspectiveCamera = new PerspectiveCamera(60.0, 16.0 / 9.0, 0.1, 500.0);
        engine.orthographicCamera = new OrthographicCamera(-4.0, 4.0, -3.0, 3.0, 0.1, 500.0);
        engine.camera = engine.perspectiveCamera;
        engine.camera.setPosition(new Vec3(0.0, 2.0, 8.0));
        engine.camera.lookAt(Vec3.ZERO);
        engine.cameraController = new CameraController(engine.camera, CameraController.Mode.FREE_LOOK);
        engine.cameraController.syncStateFromCamera();

        Vec3 before = engine.camera.getPosition();
        AABB bounds = new AABB(new Vec3(94.0, -2.0, -3.0), new Vec3(112.0, 7.0, 5.0));
        Vec3 center = bounds.center();

        EngineSceneActions.focusCameraOnImportedBounds(engine, bounds, null, null);

        Vec3 after = engine.camera.getPosition();
        Vec3 forward = engine.camera.getForward().normalize();
        Vec3 toCenter = center.sub(after).normalize();

        assertTrue(after.sub(before).length() > 20.0, "Camera position should move to imported scene");
        assertTrue(Math.abs(engine.cameraController.getOrbitTarget().sub(center).length()) <= EPS,
                "Orbit target should match imported bounds center");
        assertTrue(forward.sub(toCenter).length() <= 1e-5,
                "Camera should look directly at imported bounds center");
        assertTrue(after.sub(center).length() > 5.0,
                "Camera should keep distance large enough to frame imported bounds");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
