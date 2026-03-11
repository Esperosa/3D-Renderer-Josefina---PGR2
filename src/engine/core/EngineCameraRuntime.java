package engine.core;

import engine.camera.Camera;
import engine.camera.CameraController;
import engine.math.Vec3;
import engine.scene.Entity;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;

final class EngineCameraRuntime {
    private EngineCameraRuntime() {
    }

    static void toggleProjectionCamera(Engine engine) {
        Camera previous = engine.camera;
        CameraController.Mode mode = engine.cameraController.getMode();
        double moveSpeed = engine.cameraController.getMoveSpeed();
        double rotateSpeed = engine.cameraController.getRotateSpeed();
        Vec3 orbitTarget = engine.cameraController.getOrbitTarget();

        engine.orthographicProjection = !engine.orthographicProjection;
        engine.camera = engine.orthographicProjection ? engine.orthographicCamera : engine.perspectiveCamera;
        copyCameraPose(previous, engine.camera);

        engine.cameraController = new CameraController(engine.camera, mode);
        engine.cameraController.setMoveSpeed(moveSpeed);
        engine.cameraController.setRotateSpeed(rotateSpeed);
        engine.cameraController.setOrbitTarget(orbitTarget);
        engine.cameraController.setMouseLookAlways(engine.mouseCaptured);

        System.out.println("Projection: " + (engine.orthographicProjection ? "ORTHOGRAPHIC" : "PERSPECTIVE"));
        engine.refreshUiIndicators();
    }

    static void copyCameraPose(Camera from, Camera to) {
        to.setPosition(from.getPosition());
        to.lookAt(from.getPosition().add(from.getForward()));
    }

    static void updateMouseCaptureDelta(Engine engine) {
        if (!engine.mouseCaptured || engine.mouseRobot == null) {
            return;
        }

        Point center = engine.window.getCanvasCenterOnScreen();
        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        if (center == null || pointerInfo == null) {
            return;
        }

        Point current = pointerInfo.getLocation();
        int dx = current.x - center.x;
        int dy = current.y - center.y;
        if (Math.abs(dx) > 700 || Math.abs(dy) > 700) {
            dx = 0;
            dy = 0;
        }
        dx = Math.max(-45, Math.min(45, dx));
        dy = Math.max(-45, Math.min(45, dy));
        engine.input.forceMouseDelta(dx, dy);

        if (current.x != center.x || current.y != center.y) {
            engine.mouseRobot.mouseMove(center.x, center.y);
        }
    }

    static void captureMouse(Engine engine) {
        engine.mouseCaptured = true;
        engine.captureSelectLatch = false;
        engine.window.setCursorCaptured(true);
        engine.cameraController.setMouseLookAlways(true);
        Point center = engine.window.getCanvasCenterOnScreen();
        if (engine.mouseRobot != null && center != null) {
            engine.mouseRobot.mouseMove(center.x, center.y);
        }
        engine.input.forceMouseDelta(0, 0);
        engine.refreshUiIndicators();
    }

    static void releaseMouseCapture(Engine engine) {
        engine.mouseCaptured = false;
        engine.captureSelectLatch = false;
        engine.window.setCursorCaptured(false);
        engine.cameraController.setMouseLookAlways(false);
        engine.input.forceMouseDelta(0, 0);
        System.out.println("Mouse released.");
        engine.refreshUiIndicators();
    }

    static Entity createOutputCameraEntity() {
        Entity entity = new Entity("output-camera");
        entity.setStatic(true);
        entity.getTransform().setScale(new Vec3(1.0, 1.0, 1.0));
        entity.getTransform().setPosition(new Vec3(0.0, 2.0, 6.2));
        return entity;
    }

    static void syncOutputCameraFromCurrentView(Engine engine) {
        if (engine.camera == null) {
            return;
        }
        if (engine.scene != null && engine.outputCameraEntity == null) {
            engine.outputCameraEntity = createOutputCameraEntity();
            engine.scene.addEntity(engine.outputCameraEntity);
        }
        if (engine.outputCameraEntity == null) {
            return;
        }
        CameraViewUtil.syncOutputCameraFromCurrentView(engine.outputCameraEntity, engine.camera);
    }

    static void applyCameraPose(Engine engine, Vec3 position, Vec3 forward) {
        if (engine.camera == null || position == null || forward == null) {
            return;
        }
        Vec3 fwd = forward.normalize();
        if (fwd.lengthSquared() < 1e-10) {
            fwd = new Vec3(0.0, 0.0, -1.0);
        }
        engine.camera.setPosition(position);
        engine.camera.lookAt(position.add(fwd));
        if (engine.orthographicProjection) {
            copyCameraPose(engine.camera, engine.perspectiveCamera);
        } else {
            copyCameraPose(engine.camera, engine.orthographicCamera);
        }
        if (engine.cameraController != null) {
            engine.cameraController.syncStateFromCamera();
        }
    }

    static void rememberCurrentFpsPose(Engine engine) {
        if (engine.camera == null) {
            return;
        }
        Vec3 pos = engine.camera.getPosition();
        Vec3 fwd = engine.camera.getForward().normalize();
        if (fwd.lengthSquared() < 1e-10) {
            return;
        }
        engine.savedFpsPosition = new Vec3(pos.x, pos.y, pos.z);
        engine.savedFpsForward = new Vec3(fwd.x, fwd.y, fwd.z);
        engine.savedFpsPoseValid = true;
    }

    static void rememberCurrentBlendPose(Engine engine) {
        if (engine.camera == null) {
            return;
        }
        Vec3 pos = engine.camera.getPosition();
        Vec3 fwd = engine.camera.getForward().normalize();
        if (fwd.lengthSquared() < 1e-10) {
            return;
        }
        engine.savedBlendPosition = new Vec3(pos.x, pos.y, pos.z);
        engine.savedBlendForward = new Vec3(fwd.x, fwd.y, fwd.z);
        engine.savedBlendPoseValid = true;
    }

    static void jumpViewToOutputCamera(Engine engine, boolean fpsCapture) {
        if (engine.outputCameraEntity == null || engine.camera == null) {
            return;
        }
        Vec3 pos = engine.outputCameraEntity.getTransform().getPosition();
        Vec3 fwd = engine.outputCameraForward();
        if (fpsCapture) {
            engine.savedFpsPosition = new Vec3(pos.x, pos.y, pos.z);
            engine.savedFpsForward = new Vec3(fwd.x, fwd.y, fwd.z);
            engine.savedFpsPoseValid = true;
            engine.setNavigationPreset(Engine.NavigationPreset.FPS);
        } else if (engine.navigationPreset == Engine.NavigationPreset.BLENDER) {
            applyCameraPose(engine, pos, fwd);
            rememberCurrentBlendPose(engine);
            engine.cameraController.setOrbitTarget(pos.add(fwd.mul(4.0)));
        } else {
            applyCameraPose(engine, pos, fwd);
        }
    }

    static Camera buildOutputRenderCamera(Engine engine, int width, int height) {
        return CameraViewUtil.buildOutputRenderCamera(
                engine.outputCameraEntity,
                engine.camera,
                engine.perspectiveCamera,
                engine.orthographicProjection,
                width,
                height);
    }
}
