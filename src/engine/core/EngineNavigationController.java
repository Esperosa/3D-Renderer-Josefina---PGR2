package engine.core;

import engine.camera.CameraController;
import engine.math.Vec3;
import engine.scene.Entity;

import java.util.List;

final class EngineNavigationController {

    private EngineNavigationController() {
    }

    static void cycleRenderMode(Engine engine) {
        if (engine.activeMode == RenderMode.MODEL) {
            engine.setRenderMode(RenderMode.BASIC);
        } else if (engine.activeMode == RenderMode.BASIC) {
            engine.setRenderMode(RenderMode.PHONG);
        } else if (engine.activeMode == RenderMode.PHONG) {
            engine.setRenderMode(RenderMode.WIREFRAME);
        } else if (engine.activeMode == RenderMode.WIREFRAME) {
            engine.setRenderMode(RenderMode.DITHERING);
        } else if (engine.activeMode == RenderMode.DITHERING) {
            engine.setRenderMode(RenderMode.TEMPORAL_NOISE);
        } else if (engine.activeMode == RenderMode.TEMPORAL_NOISE) {
            engine.setRenderMode(RenderMode.RAY_TRACING);
        } else if (engine.activeMode == RenderMode.RAY_TRACING) {
            engine.setRenderMode(RenderMode.PATH_TRACING);
        } else if (engine.activeMode == RenderMode.PATH_TRACING) {
            engine.setRenderMode(RenderMode.HEX_MOSAIC);
        } else {
            engine.setRenderMode(RenderMode.MODEL);
        }
    }

    static void cycleCameraMode(Engine engine) {
        if (engine.navigationPreset == Engine.NavigationPreset.BLENDER) {
            engine.cameraController.setMode(CameraController.Mode.ORBIT);
            System.out.println("Camera mode: BLENDER_ORBIT");
            engine.refreshUiIndicators();
            return;
        }

        CameraController.Mode mode = engine.cameraController.getMode();
        if (mode == CameraController.Mode.FREE_LOOK) {
            engine.cameraController.setMode(CameraController.Mode.FIRST_PERSON);
        } else {
            engine.cameraController.setMode(CameraController.Mode.FREE_LOOK);
        }
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
