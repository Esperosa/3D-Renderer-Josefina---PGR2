package engine.core;

import java.awt.event.MouseEvent;
import java.lang.reflect.Field;

import engine.camera.CameraController;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.scene.Scene;

public final class EngineCameraProjectionTests {

    private EngineCameraProjectionTests() {
    }

    public static void main(String[] args) {
        testPerspectiveToOrthoKeepsVisualScale();
        testOutputCameraUsesActiveOrthoZoom();
        testAxisWidgetSnapEnforcesOrthoAndFlips();
        testAxisWidgetSnapKeepsOrbitResponsiveAtPole();
        testAxisSnapMovementRestoresOriginalProjection();
        testOrthographicClippingExpandsForFarSceneContent();
        testFpsPoseTrackingSkipsOutputCameraView();
        testFpsPoseTrackingSkipsTimelineDrivenCamera();
        System.out.println("EngineCameraProjectionTests: ALL TESTS PASSED");
    }

    private static void testPerspectiveToOrthoKeepsVisualScale() {
        Engine engine = baseEngine();
        Vec3 orbitTarget = new Vec3(0.0, 1.0, 0.0);
        engine.cameraController.setOrbitTarget(orbitTarget);

        double distance = engine.camera.getPosition().sub(orbitTarget).length();
        double expectedHalfHeight = Math.tan(engine.perspectiveCamera.getFovY() * 0.5) * distance;

        EngineCameraRuntime.toggleProjectionCamera(engine);

        assertTrue(engine.orthographicProjection, "Přepnutí musí aktivovat ortho projekci.");
        assertSame(engine.orthographicCamera, engine.camera, "Aktivní kamera musí být ortografická.");
        assertNear(expectedHalfHeight, engine.orthographicCamera.getHalfHeight(), 1e-6,
                "Ortho výška musí navázat na aktuální perspektivní framing.");
    }

    private static void testOutputCameraUsesActiveOrthoZoom() {
        PerspectiveCamera perspective = new PerspectiveCamera(70.0, 16.0 / 9.0, 0.1, 300.0);
        perspective.setPosition(new Vec3(0.0, 2.0, 8.0));
        perspective.lookAt(new Vec3(0.0, 1.0, 0.0));
        OrthographicCamera ortho = new OrthographicCamera(-8.0, 8.0, -4.5, 4.5, 0.1, 300.0);
        ortho.setHalfHeightWithAspect(3.25, 16.0 / 9.0);

        var renderCamera = CameraViewUtil.buildOutputRenderCamera(
                null,
                perspective,
                perspective,
                ortho,
                true,
                1440,
                900);

        assertTrue(renderCamera instanceof OrthographicCamera, "Výstupní kamera musí zůstat ortografická.");
        OrthographicCamera outputOrtho = (OrthographicCamera) renderCamera;
        assertNear(3.25, outputOrtho.getHalfHeight(), 1e-6,
                "Výstup musí převzít aktuální ortho zoom.");
    }

    private static void testAxisWidgetSnapEnforcesOrthoAndFlips() {
        Engine engine = baseEngine();
        engine.navigationPreset = Engine.NavigationPreset.BLENDER;
        engine.cameraController.setOrbitTarget(new Vec3(0.0, 0.0, 0.0));

        EngineCameraRuntime.snapToWorldAxis(engine, Window.AxisWidgetTarget.POS_X);
        assertTrue(engine.orthographicProjection, "Axis snap musí přepnout do ortho projekce.");
        assertVecNear(new Vec3(1.0, 0.0, 0.0), engine.camera.getForward().normalize(), 1e-6,
                "První snap musí mířit na zvolenou osu.");

        EngineCameraRuntime.snapToWorldAxis(engine, Window.AxisWidgetTarget.POS_X);
        assertVecNear(new Vec3(-1.0, 0.0, 0.0), engine.camera.getForward().normalize(), 1e-6,
                "Opakovaný klik na stejnou osu musí přepnout pohled o 180 stupňů.");
    }

    private static void testAxisWidgetSnapKeepsOrbitResponsiveAtPole() {
        Engine engine = baseEngine();
        engine.navigationPreset = Engine.NavigationPreset.BLENDER;
        engine.input = new Input();
        Vec3 orbitTarget = new Vec3(0.0, 0.0, 0.0);
        engine.cameraController.setOrbitTarget(orbitTarget);

        EngineCameraRuntime.snapToWorldAxis(engine, Window.AxisWidgetTarget.POS_Y);
        Vec3 before = engine.camera.getForward().normalize();
        double snappedDistance = engine.camera.getPosition().sub(orbitTarget).length();

        setMouseButtonState(engine.input, MouseEvent.BUTTON2, true);
        engine.input.forceMouseDelta(28, 0);
        engine.cameraController.update(engine.input, 1.0 / 60.0);

        Vec3 after = engine.camera.getForward().normalize();
        assertTrue(before.dot(after) < 0.9999,
                "Po snapu na pólovou osu musí zůstat orbit horizontálně ovladatelný.");
        assertNear(snappedDistance, engine.camera.getPosition().sub(orbitTarget).length(), 1e-6,
                "Orbit po snapu musí dál fungovat kolem stejného středu.");
    }

    private static void testAxisSnapMovementRestoresOriginalProjection() {
        Engine engine = baseEngine();
        engine.navigationPreset = Engine.NavigationPreset.BLENDER;
        Vec3 orbitTarget = new Vec3(0.0, 0.0, 0.0);
        engine.cameraController.setOrbitTarget(orbitTarget);

        EngineCameraRuntime.snapToWorldAxis(engine, Window.AxisWidgetTarget.POS_X);
        assertTrue(engine.axisSnapViewActive, "Axis snap musí aktivovat přechodový ortho režim.");
        assertTrue(engine.orthographicProjection, "Axis snap musí běžet v ortho projekci.");

        Vec3 snappedForward = engine.camera.getForward().normalize();
        EngineCameraRuntime.restoreProjectionAfterAxisSnap(engine);

        assertTrue(!engine.axisSnapViewActive, "Po prvním pohybu musí přechodový axis režim skončit.");
        assertTrue(!engine.orthographicProjection, "Po prvním pohybu se musí vrátit původní perspektiva.");
        assertSame(engine.perspectiveCamera, engine.camera, "Po návratu musí být aktivní perspektivní kamera.");
        assertVecNear(snappedForward, engine.camera.getForward().normalize(), 1e-6,
                "Návrat do původní projekce nesmí změnit aktuální směr pohledu.");
    }

    private static void testOrthographicClippingExpandsForFarSceneContent() {
        Engine engine = baseEngine();
        engine.scene = new Scene();
        Entity far = new Entity("far");
        far.getTransform().setPosition(new Vec3(0.0, 0.0, -1400.0));
        engine.scene.addEntity(far);
        engine.navigationPreset = Engine.NavigationPreset.BLENDER;
        engine.cameraController.setOrbitTarget(Vec3.ZERO);

        EngineCameraRuntime.snapToWorldAxis(engine, Window.AxisWidgetTarget.NEG_Z);

        assertTrue(engine.orthographicProjection, "Test clippingu musí zůstat v ortho režimu.");
        assertTrue(engine.orthographicCamera.getFar() > 1400.0,
                "Orto clipping musí pokrýt i vzdálenější obsah scény.");
    }

    private static void testFpsPoseTrackingSkipsOutputCameraView() {
        Engine engine = baseEngine();
        engine.navigationPreset = Engine.NavigationPreset.FPS;
        engine.outputCameraEntity = EngineCameraRuntime.createOutputCameraEntity();
        CameraViewUtil.syncOutputCameraFromCurrentView(engine.outputCameraEntity, engine.camera);
        engine.savedFpsPosition = new Vec3(9.0, 8.0, 7.0);
        engine.savedFpsForward = new Vec3(0.0, 0.0, -1.0);
        engine.savedFpsPoseValid = true;

        EngineCameraRuntime.rememberCurrentFpsPose(engine);

        assertVecNear(new Vec3(9.0, 8.0, 7.0), engine.savedFpsPosition, 1e-6,
                "Při pohledu skrz output kameru se nesmí přepsat uložený FPS pohled.");
    }

    private static void testFpsPoseTrackingSkipsTimelineDrivenCamera() {
        Engine engine = baseEngine();
        engine.navigationPreset = Engine.NavigationPreset.FPS;
        engine.timelineEnabled = true;
        engine.animationPlaybackEnabled = true;
        engine.sceneTimeline.addOrReplaceCameraKey(engine, 1);
        engine.savedFpsPosition = new Vec3(4.0, 5.0, 6.0);
        engine.savedFpsForward = new Vec3(0.0, 0.0, -1.0);
        engine.savedFpsPoseValid = true;

        EngineCameraRuntime.rememberCurrentFpsPose(engine);

        assertVecNear(new Vec3(4.0, 5.0, 6.0), engine.savedFpsPosition, 1e-6,
                "Timeline-driven kamera nesmí přepsat poslední stabilní FPS pohled.");
    }

    private static Engine baseEngine() {
        Engine engine = new Engine();
        double aspect = 16.0 / 9.0;
        engine.perspectiveCamera = new PerspectiveCamera(70.0, aspect, 0.1, 300.0);
        engine.perspectiveCamera.setPosition(new Vec3(0.0, 2.4, 7.0));
        engine.perspectiveCamera.lookAt(new Vec3(0.0, 1.0, 0.0));
        engine.orthographicCamera = new OrthographicCamera(-6.0 * aspect, 6.0 * aspect, -6.0, 6.0, 0.1, 300.0);
        EngineCameraRuntime.copyCameraPose(engine.perspectiveCamera, engine.orthographicCamera);
        engine.camera = engine.perspectiveCamera;
        engine.cameraController = new CameraController(engine.camera, CameraController.Mode.ORBIT);
        engine.cameraController.setOrbitTarget(new Vec3(0.0, 1.0, 0.0));
        return engine;
    }

    private static void assertSame(Object expected, Object actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void setMouseButtonState(Input input, int button, boolean down) {
        try {
            Field mouseButtonsField = Input.class.getDeclaredField("mouseButtons");
            mouseButtonsField.setAccessible(true);
            boolean[] buttons = (boolean[]) mouseButtonsField.get(input);
            buttons[button] = down;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Nepodařilo se nastavit testovací stav myši.", ex);
        }
    }

    private static void assertNear(double expected, double actual, double epsilon, String message) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(message + " Expected " + expected + " but was " + actual + ".");
        }
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, double epsilon, String message) {
        if (expected.sub(actual).length() > epsilon) {
            throw new AssertionError(message + " Expected " + expected + " but was " + actual + ".");
        }
    }
}
