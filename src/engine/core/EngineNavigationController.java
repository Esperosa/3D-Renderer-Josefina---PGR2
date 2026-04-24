package engine.core;

import java.util.List;

import engine.camera.CameraController;
import engine.math.Vec3;
import engine.scene.Entity;

final class EngineNavigationController {

    private EngineNavigationController() {
    }

    static void cycleRenderMode(Engine engine) {
        RenderMode nextMode = switch (engine.activeMode) {
            case MODEL -> RenderMode.BASIC;
            case BASIC -> RenderMode.PHONG;
            case PHONG -> RenderMode.WIREFRAME;
            case WIREFRAME -> RenderMode.DITHERING;
            case DITHERING -> RenderMode.TEMPORAL_NOISE;
            case TEMPORAL_NOISE -> RenderMode.RAY_TRACING;
            case RAY_TRACING -> RenderMode.PATH_TRACING;
            case PATH_TRACING -> RenderMode.HEX_MOSAIC;
            default -> RenderMode.MODEL;
        };
        engine.setRenderMode(nextMode);
    }

    static void cycleCameraMode(Engine engine) {
        if (engine.navigationPreset == Engine.NavigationPreset.BLENDER) {
            engine.cameraController.setMode(CameraController.Mode.ORBIT);
            System.out.println("Camera mode: BLENDER_ORBIT");
            engine.refreshUiIndicators();
            return;
        }

        CameraController.Mode mode = engine.cameraController.getMode();
        CameraController.Mode nextMode = mode == CameraController.Mode.FREE_LOOK
                ? CameraController.Mode.FIRST_PERSON
                : CameraController.Mode.FREE_LOOK;
        engine.cameraController.setMode(nextMode);
        engine.fpsCameraMode = engine.cameraController.getMode();
        System.out.println("Camera mode: " + engine.cameraController.getMode());
        engine.refreshUiIndicators();
    }

    static void setNavigationPreset(Engine engine, Engine.NavigationPreset preset) {
        if (preset == null || engine.cameraController == null) {
            return;
        }
        Engine.NavigationPreset previous = engine.navigationPreset;
        engine.navigationPreset = preset;

        if (previous == Engine.NavigationPreset.FPS) {
            engine.rememberCurrentFpsPose();
        } else if (previous == Engine.NavigationPreset.BLENDER) {
            engine.rememberCurrentBlendPose();
        }

        if (engine.navigationPreset == Engine.NavigationPreset.FPS) {
            CameraController.Mode mode = engine.fpsCameraMode;
            if (mode == null || mode == CameraController.Mode.ORBIT) {
                mode = CameraController.Mode.FREE_LOOK;
            }
            if (engine.savedFpsPoseValid && engine.savedFpsPosition != null && engine.savedFpsForward != null) {
                engine.applyCameraPose(engine.savedFpsPosition, engine.savedFpsForward);
            } else if (engine.outputCameraEntity != null) {
                Vec3 outPos = engine.outputCameraEntity.getTransform().getPosition();
                Vec3 outFwd = engine.outputCameraForward();
                engine.applyCameraPose(outPos, outFwd);
                engine.rememberCurrentFpsPose();
            }
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.gizmoDragActive = false;
            engine.cameraController.setMode(mode);
            engine.captureMouse();
            System.out.println("Navigation: FPS");
        } else {
            if (engine.savedBlendPoseValid && engine.savedBlendPosition != null && engine.savedBlendForward != null) {
                engine.applyCameraPose(engine.savedBlendPosition, engine.savedBlendForward);
            } else {
                engine.rememberCurrentBlendPose();
            }

            CameraController.Mode current = engine.cameraController.getMode();
            if (current != CameraController.Mode.ORBIT) {
                engine.fpsCameraMode = current;
            }
            engine.releaseMouseCapture();
            Vec3 pivot = engine.selectionPivotPosition();
            if (pivot != null) {
                engine.cameraController.setOrbitTarget(pivot);
            } else {
                engine.cameraController.setOrbitTarget(engine.camera.getPosition().add(engine.camera.getForward().mul(4.0)));
            }
            engine.cameraController.setMode(CameraController.Mode.ORBIT);
            engine.rememberCurrentBlendPose();
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            System.out.println("Navigation: BLENDER");
        }
        engine.refreshUiIndicators();
    }

    static void toggleTransformMode(Engine engine, Engine.TransformMode mode) {
        if (!engine.selectionSupportsTransform()) {
            System.out.println("Select transformable item first (object/light/force).");
            return;
        }
        if (engine.transformMode == mode) {
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
        } else {
            engine.transformMode = mode;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            System.out.println("Transform mode: " + engine.transformMode + " (X/Y/Z for axis)");
        }
    }

    static void selectRelative(Engine engine, int delta) {
        List<Entity> candidates = engine.scene.getAllMeshEntities();
        if (candidates.isEmpty()) {
            return;
        }
        int index = candidates.indexOf(engine.selectedEntity);
        if (index < 0) {
            index = 0;
        }
        int next = (index + delta) % candidates.size();
        if (next < 0) {
            next += candidates.size();
        }
        engine.setCurrentEntitySelection(candidates.get(next));
        engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
        System.out.println("Selected: " + engine.selectedEntity.getName());
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }
}
