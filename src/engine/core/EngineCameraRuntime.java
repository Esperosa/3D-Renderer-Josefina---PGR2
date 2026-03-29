package engine.core;

import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;

import engine.camera.Camera;
import engine.camera.CameraController;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
import engine.scene.Entity;

final class EngineCameraRuntime {
    private static final double ORTHO_MATCH_SCALE = 1.0;

    private EngineCameraRuntime() {
    }

    static void toggleProjectionCamera(Engine engine) {
        Camera previous = engine.camera;
        CameraController.Mode mode = engine.cameraController.getMode();
        double moveSpeed = engine.cameraController.getMoveSpeed();
        double rotateSpeed = engine.cameraController.getRotateSpeed();
        Vec3 orbitTarget = engine.cameraController.getOrbitTarget();
        engine.axisSnapViewActive = false;

        if (!engine.orthographicProjection) {
            syncOrthographicScaleFromCurrentView(engine, orbitTarget);
        }

        engine.orthographicProjection = !engine.orthographicProjection;
        engine.camera = engine.orthographicProjection ? engine.orthographicCamera : engine.perspectiveCamera;
        copyCameraPose(previous, engine.camera);

        engine.cameraController = new CameraController(engine.camera, mode);
        engine.cameraController.setMoveSpeed(moveSpeed);
        engine.cameraController.setRotateSpeed(rotateSpeed);
        engine.cameraController.setOrbitTarget(orbitTarget);
        engine.cameraController.setMouseLookAlways(engine.mouseCaptured);
        refreshOrthographicClipping(engine);

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

        Point center = engine.window.getCapturePointOnScreen();
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
        engine.window.getCanvas().requestFocusInWindow();
        engine.cameraController.setMouseLookAlways(true);
        Point center = engine.window.getCapturePointOnScreen();
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
        Camera companionCamera = engine.orthographicProjection
                ? engine.perspectiveCamera
                : engine.orthographicCamera;
        copyCameraPose(engine.camera, companionCamera);
        if (engine.cameraController != null) {
            engine.cameraController.syncStateFromCamera();
        }
    }

    static void rememberCurrentFpsPose(Engine engine) {
        if (!shouldTrackFpsPose(engine)) {
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

    static boolean shouldTrackFpsPose(Engine engine) {
        if (engine == null
                || engine.camera == null
                || engine.navigationPreset != Engine.NavigationPreset.FPS) {
            return false;
        }
        if (engine.axisSnapViewActive
                || engine.viewportContextMenuRecapturePending
                || EngineViewportOverlay.isViewingThroughOutputCamera(engine)) {
            return false;
        }
        return !(engine.timelineEnabled
                && engine.animationPlaybackEnabled
                && engine.sceneTimeline != null
                && engine.sceneTimeline.hasAnyCameraKeys());
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
        } else {
            applyCameraPose(engine, pos, fwd);
            if (engine.navigationPreset == Engine.NavigationPreset.BLENDER) {
                rememberCurrentBlendPose(engine);
                engine.cameraController.setOrbitTarget(pos.add(fwd.mul(4.0)));
            }
        }
    }

    static Camera buildOutputRenderCamera(Engine engine, int width, int height) {
        return CameraViewUtil.buildOutputRenderCamera(
                engine.outputCameraEntity,
                engine.camera,
                engine.perspectiveCamera,
                engine.orthographicCamera,
                engine.orthographicProjection,
                width,
                height);
    }

    static void snapToWorldAxis(Engine engine, Window.AxisWidgetTarget target) {
        if (engine == null || target == null || target == Window.AxisWidgetTarget.NONE || engine.camera == null) {
            return;
        }
        if (EngineViewportOverlay.isViewingThroughOutputCamera(engine)) {
            restoreBlendViewport(engine);
        }
        engine.axisSnapRestoreOrthographicProjection = engine.orthographicProjection;
        engine.axisSnapViewActive = true;

        Vec3 requested = axisDirection(target);
        Vec3 current = engine.camera.getForward().normalize();
        Vec3 finalDirection = current.dot(requested) > 0.999 ? requested.mul(-1.0) : requested;
        Vec3 focusTarget = resolveAxisSnapTarget(engine);
        double distance = Math.max(2.2, engine.camera.getPosition().sub(focusTarget).length());
        CameraController.Mode controllerMode = engine.cameraController != null
                ? engine.cameraController.getMode()
                : CameraController.Mode.ORBIT;
        double moveSpeed = engine.cameraController != null ? engine.cameraController.getMoveSpeed() : 2.2;
        double rotateSpeed = engine.cameraController != null ? engine.cameraController.getRotateSpeed() : 0.00138;
        boolean mouseLookAlways = engine.cameraController != null && engine.cameraController.isMouseLookAlways();

        if (!engine.orthographicProjection) {
            syncOrthographicScaleForDistance(engine, distance);
            engine.orthographicProjection = true;
            engine.camera = engine.orthographicCamera;
            engine.cameraController = new CameraController(engine.camera, controllerMode);
            engine.cameraController.setMoveSpeed(moveSpeed);
            engine.cameraController.setRotateSpeed(rotateSpeed);
            engine.cameraController.setMouseLookAlways(mouseLookAlways);
        }

        applyCameraPose(engine, focusTarget.sub(finalDirection.mul(distance)), finalDirection);
        refreshOrthographicClipping(engine);
        if (engine.cameraController != null) {
            if (engine.cameraController.getMode() != CameraController.Mode.ORBIT) {
                engine.cameraController.setMode(CameraController.Mode.ORBIT);
            }
            engine.cameraController.setOrbitTarget(focusTarget);
            engine.cameraController.syncStateFromCamera();
        }
        engine.objectFocusMode = false;
        engine.draggingSelectedObject = false;
        engine.pendingSelectedObjectDrag = false;
        engine.transformMode = Engine.TransformMode.NONE;
        engine.axisConstraint = Engine.AxisConstraint.NONE;
        engine.gizmoDragActive = false;
        engine.captureSelectLatch = false;
        if (engine.input != null) {
            engine.input.forceMouseDelta(0, 0);
        }
        if (engine.window != null && engine.window.getCanvas() != null) {
            engine.window.getCanvas().requestFocusInWindow();
        }
        rememberCurrentBlendPose(engine);
        engine.refreshUiIndicators();
    }

    static void restoreProjectionAfterAxisSnap(Engine engine) {
        if (engine == null || !engine.axisSnapViewActive) {
            return;
        }
        if (engine.axisSnapRestoreOrthographicProjection || engine.camera == null) {
            engine.axisSnapViewActive = false;
            refreshOrthographicClipping(engine);
            return;
        }
        Camera previous = engine.camera;
        CameraController.Mode mode = engine.cameraController != null
                ? engine.cameraController.getMode()
                : CameraController.Mode.ORBIT;
        double moveSpeed = engine.cameraController != null ? engine.cameraController.getMoveSpeed() : 2.2;
        double rotateSpeed = engine.cameraController != null ? engine.cameraController.getRotateSpeed() : 0.00138;
        Vec3 orbitTarget = engine.cameraController != null ? engine.cameraController.getOrbitTarget() : resolveAxisSnapTarget(engine);
        boolean mouseLookAlways = engine.cameraController != null && engine.cameraController.isMouseLookAlways();

        engine.orthographicProjection = false;
        engine.camera = engine.perspectiveCamera;
        copyCameraPose(previous, engine.camera);
        engine.cameraController = new CameraController(engine.camera, mode);
        engine.cameraController.setMoveSpeed(moveSpeed);
        engine.cameraController.setRotateSpeed(rotateSpeed);
        engine.cameraController.setOrbitTarget(orbitTarget);
        engine.cameraController.setMouseLookAlways(mouseLookAlways);
        engine.axisSnapViewActive = false;
        engine.refreshUiIndicators();
    }

    static void refreshOrthographicClipping(Engine engine) {
        if (engine == null || !engine.orthographicProjection || engine.orthographicCamera == null || engine.camera == null) {
            return;
        }

        Vec3 cameraPos = engine.camera.getPosition();
        Vec3 forward = engine.camera.getForward().normalize();
        if (forward.lengthSquared() < 1e-10) {
            forward = new Vec3(0.0, 0.0, -1.0);
        }

        double minDepth = Double.POSITIVE_INFINITY;
        double maxDepth = Double.NEGATIVE_INFINITY;
        if (engine.scene != null) {
            for (Entity entity : engine.scene.getEntities()) {
                if (entity == null || !entity.isVisible()) {
                    continue;
                }
                if (entity.getWorldBounds() == null) {
                    entity.computeWorldBounds();
                }
                Vec3 center = entity.getWorldBounds() != null
                        ? entity.getWorldBounds().center()
                        : entity.getTransform().getPosition();
                double radius = entity.getWorldBounds() != null
                        ? entity.getWorldBounds().getMax().sub(center).length()
                        : Math.max(0.35, entity.getTransform().getScale().length() * 0.5);
                double depth = center.sub(cameraPos).dot(forward);
                minDepth = Math.min(minDepth, depth - radius);
                maxDepth = Math.max(maxDepth, depth + radius);
            }
        }

        double halfHeight = engine.orthographicCamera.getHalfHeight();
        double padding = Math.max(12.0, halfHeight * 1.5);
        if (!Double.isFinite(minDepth) || !Double.isFinite(maxDepth)) {
            minDepth = 0.05;
            maxDepth = Math.max(800.0, halfHeight * 12.0);
        }

        double near = 0.01;
        double far = Math.max(near + 64.0, maxDepth + padding);
        far = Math.max(far, near + 512.0);
        engine.orthographicCamera.setClipping(near, far);
    }

    static void syncOrthographicScaleFromCurrentView(Engine engine, Vec3 focusTarget) {
        if (engine == null || engine.camera == null || engine.perspectiveCamera == null || engine.orthographicCamera == null) {
            return;
        }
        Vec3 target = focusTarget;
        if (target == null) {
            target = resolveAxisSnapTarget(engine);
        }
        double distance = Math.max(0.25, engine.camera.getPosition().sub(target).length());
        syncOrthographicScaleForDistance(engine, distance);
    }

    private static void syncOrthographicScaleForDistance(Engine engine, double distance) {
        if (engine == null || engine.perspectiveCamera == null || engine.orthographicCamera == null) {
            return;
        }
        PerspectiveCamera perspective = engine.perspectiveCamera;
        double halfHeight = Math.tan(perspective.getFovY() * 0.5) * Math.max(0.25, distance) * ORTHO_MATCH_SCALE;
        engine.orthographicCamera.setHalfHeightWithAspect(
                halfHeight,
                Math.max(1e-4, perspective.getAspectRatio()));
    }

    private static Vec3 resolveAxisSnapTarget(Engine engine) {
        if (engine == null || engine.camera == null) {
            return Vec3.ZERO;
        }
        Vec3 selectionPivot = engine.selectionPivotPosition();
        if (selectionPivot != null) {
            return selectionPivot;
        }
        if (engine.cameraController != null && engine.cameraController.getOrbitTarget() != null) {
            return engine.cameraController.getOrbitTarget();
        }
        return engine.camera.getPosition().add(engine.camera.getForward().mul(4.0));
    }

    private static void restoreBlendViewport(Engine engine) {
        if (engine == null) {
            return;
        }
        if (engine.savedBlendPoseValid && engine.savedBlendPosition != null && engine.savedBlendForward != null) {
            applyCameraPose(engine, engine.savedBlendPosition, engine.savedBlendForward);
            return;
        }
        if (engine.outputCameraEntity != null) {
            Vec3 outPos = engine.outputCameraEntity.getTransform().getPosition();
            Vec3 outFwd = engine.outputCameraForward();
            applyCameraPose(engine, outPos.sub(outFwd.mul(2.8)), outFwd);
        }
    }

    private static Vec3 axisDirection(Window.AxisWidgetTarget target) {
        return switch (target) {
            case POS_X -> new Vec3(1.0, 0.0, 0.0);
            case NEG_X -> new Vec3(-1.0, 0.0, 0.0);
            case POS_Y -> new Vec3(0.0, 1.0, 0.0);
            case NEG_Y -> new Vec3(0.0, -1.0, 0.0);
            case POS_Z -> new Vec3(0.0, 0.0, 1.0);
            case NEG_Z -> new Vec3(0.0, 0.0, -1.0);
            case NONE -> new Vec3(0.0, 0.0, -1.0);
        };
    }
}
