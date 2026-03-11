package engine.core;

import engine.math.Vec3;
import engine.math.Ray;
import engine.geometry.Mesh;
import engine.scene.Entity;
import engine.scene.Light;

import java.awt.event.MouseEvent;

final class EngineSelectionController {
    private EngineSelectionController() {
    }

    static void handleObjectInteraction(Engine engine) {
        if (engine.mouseCaptured) {
            boolean lmbDown = engine.input.isMouseButtonDown(MouseEvent.BUTTON1);
            if (lmbDown && !engine.captureSelectLatch) {
                int cx = engine.window.getCanvas().getWidth() / 2;
                int cy = engine.window.getCanvas().getHeight() / 2;
                Engine.SceneItemRef hit = pickSceneItemUnderMouse(engine, cx, cy);
                if (hit != null) {
                    applyHitSelection(engine, hit, true);
                }
            }
            engine.captureSelectLatch = lmbDown;
            engine.draggingSelectedObject = false;
            return;
        }
        engine.captureSelectLatch = false;

        if (engine.input.isMouseButtonPressed(MouseEvent.BUTTON1)) {
            if (engine.navigationPreset == Engine.NavigationPreset.BLENDER
                    && engine.selectionSupportsTransform()
                    && EngineViewportOverlay.tryActivateGizmoHandleAtCanvas(
                    engine, engine.input.getMouseX(), engine.input.getMouseY())) {
                engine.draggingSelectedObject = false;
                return;
            }
            Engine.SceneItemRef hit = pickSceneItemUnderMouse(
                    engine,
                    engine.input.getMouseX(), engine.input.getMouseY());
            if (hit != null) {
                applyHitSelection(engine, hit, false);
            } else {
                engine.draggingSelectedObject = false;
                if (engine.objectFocusMode) {
                    engine.objectFocusMode = false;
                    engine.transformMode = Engine.TransformMode.NONE;
                    engine.axisConstraint = Engine.AxisConstraint.NONE;
                    engine.gizmoDragActive = false;
                    System.out.println("Object focus: OFF");
                } else if (!engine.window.isCursorCaptured()
                        && engine.navigationPreset == Engine.NavigationPreset.FPS) {
                    engine.captureMouse();
                }
            }
        }

        if (!engine.input.isMouseButtonDown(MouseEvent.BUTTON1)) {
            engine.commitSceneGesture();
            engine.draggingSelectedObject = false;
        }
    }

    static void updateSelectedObjectDrag(Engine engine, double dt) {
        if (engine.mouseCaptured || engine.selectedEntity == null || engine.selectedEntity.isStatic()) {
            return;
        }
        if (!engine.draggingSelectedObject || !engine.input.isMouseButtonDown(MouseEvent.BUTTON1)) {
            return;
        }

        int dx = engine.input.getMouseDX();
        int dy = engine.input.getMouseDY();
        if (dx == 0 && dy == 0) {
            return;
        }

        double distance = engine.camera.getPosition().sub(engine.selectedEntity.getTransform().getPosition()).length();
        double dragScale = Math.max(0.0015, distance * 0.0018) * (engine.input.isShiftDown() ? 0.35 : 1.0);
        Vec3 delta = engine.camera.getRight().mul(dx * dragScale).add(engine.camera.getUp().mul(-dy * dragScale));
        engine.beginSceneGesture("Přesun objektu");
        engine.selectedEntity.getTransform().translate(delta);
        if (engine.selectedEntity.getRigidBody() != null) {
            engine.selectedEntity.getRigidBody().setVelocity(Vec3.ZERO);
        }
    }

    static boolean shouldDeselectOnDoubleClick(Engine engine, Entity hitEntity) {
        if (hitEntity == null) {
            return false;
        }
        long now = System.nanoTime();
        boolean doubleClick = hitEntity == engine.selectedEntity
                && hitEntity == engine.lastSelectionClickEntity
                && (now - engine.lastSelectionClickNanos) <= Engine.DOUBLE_CLICK_THRESHOLD_NS;
        engine.lastSelectionClickNanos = now;
        engine.lastSelectionClickEntity = hitEntity;
        return doubleClick;
    }

    static void clearSelection(Engine engine, String reason) {
        engine.selectedEntity = null;
        engine.selectedLight = null;
        engine.selectedForceField = null;
        engine.objectFocusMode = false;
        engine.draggingSelectedObject = false;
        engine.transformMode = Engine.TransformMode.NONE;
        engine.axisConstraint = Engine.AxisConstraint.NONE;
        engine.gizmoDragActive = false;
        engine.refreshObjectInspectorValues();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
        if (reason != null && !reason.isEmpty()) {
            System.out.println(reason);
        }
    }

    static boolean deleteCurrentSelection(Engine engine) {
        if (engine.scene == null) {
            return false;
        }
        if (engine.selectedEntity != null) {
            Entity entity = engine.selectedEntity;
            String deletedName = entity.getName();
            engine.applySceneEdit("Smazání objektu", () -> {
                if (engine.physicsWorld != null && entity.getRigidBody() != null) {
                    engine.physicsWorld.removeBody(entity.getRigidBody());
                    entity.setRigidBody(null);
                }
                engine.scene.removeEntity(entity);
                engine.sceneItemStates.remove(entity);
                EngineTimelineController.removeEntityTrack(engine, entity);
                if (entity == engine.demoEntity) {
                    engine.demoEntity = null;
                }
                if (entity == engine.floorEntity) {
                    engine.floorEntity = null;
                }
                if (entity == engine.outputCameraEntity) {
                    engine.outputCameraEntity = null;
                }
                clearSelection(engine, "Deleted entity: " + deletedName);
                engine.applySceneVisibility(false);
                engine.refreshSceneOutliner();
            });
            return true;
        }
        if (engine.selectedLight != null) {
            Light light = engine.selectedLight;
            String deletedName = engine.getLightName(light);
            engine.applySceneEdit("Smazání světla", () -> {
                engine.scene.removeLight(light);
                engine.sceneItemStates.remove(light);
                engine.lightNames.remove(light);
                EngineTimelineController.removeLightTrack(engine, light);
                if (light == engine.sunLight) {
                    engine.sunLight = null;
                }
                if (light == engine.fillLight) {
                    engine.fillLight = null;
                }
                if (light == engine.warmWorldLight) {
                    engine.warmWorldLight = null;
                }
                if (light == engine.coolWorldLight) {
                    engine.coolWorldLight = null;
                }
                clearSelection(engine, "Deleted light: " + deletedName);
                engine.applySceneVisibility(false);
                engine.refreshSceneOutliner();
            });
            return true;
        }
        if (engine.selectedForceField != null) {
            Engine.ForceField field = engine.selectedForceField;
            String deletedName = field.name;
            engine.applySceneEdit("Smazání síly", () -> {
                engine.forceFields.remove(field);
                engine.sceneItemStates.remove(field);
                EngineTimelineController.removeForceTrack(engine, field);
                clearSelection(engine, "Deleted force: " + deletedName);
                engine.applySceneVisibility(false);
                engine.refreshSceneOutliner();
            });
            return true;
        }
        return false;
    }

    static Engine.SceneItemRef pickSceneItemUnderMouse(Engine engine, int mouseX, int mouseY) {
        Engine.SceneItemRef overlayRef = EngineViewportOverlay.pickOverlayItemUnderMouse(engine, mouseX, mouseY);
        if (overlayRef != null) {
            return overlayRef;
        }
        Engine.SceneItemRef outputCameraRef = EngineViewportOverlay.pickOutputCameraWireUnderMouse(engine, mouseX, mouseY);
        if (outputCameraRef != null) {
            return outputCameraRef;
        }
        Entity hitEntity = pickEntityUnderMouse(engine, mouseX, mouseY);
        if (hitEntity == null) {
            return null;
        }
        Engine.SceneItemRef ref = new Engine.SceneItemRef();
        ref.type = Engine.SceneItemType.ENTITY;
        ref.entity = hitEntity;
        return ref;
    }

    private static Entity pickEntityUnderMouse(Engine engine, int mouseX, int mouseY) {
        Ray ray = engine.buildPickRay(mouseX, mouseY);
        if (ray == null) {
            return null;
        }

        Vec3 rayOrigin = ray.getOrigin();
        Vec3 rayDir = ray.getDirection();

        Entity hitEntity = null;
        double bestT = Double.POSITIVE_INFINITY;
        for (Entity entity : engine.scene.getAllMeshEntities()) {
            Mesh mesh = entity.getMesh();
            if (mesh == null || mesh.getIndices() == null || mesh.getPositions() == null) {
                continue;
            }

            double exactT = engine.intersectRayMesh(rayOrigin, rayDir, mesh, entity.getWorldMatrix(), bestT);
            if (Double.isFinite(exactT) && exactT < bestT) {
                bestT = exactT;
                hitEntity = entity;
            }
        }
        return hitEntity;
    }

    private static void applyHitSelection(Engine engine, Engine.SceneItemRef hit, boolean crosshairSelection) {
        if (hit.type == Engine.SceneItemType.ENTITY && hit.entity != null) {
            if (shouldDeselectOnDoubleClick(engine, hit.entity)) {
                clearSelection(engine, "Deselected.");
            } else {
                engine.setCurrentEntitySelection(hit.entity);
                boolean isOutputCameraEntity = engine.selectedEntity == engine.outputCameraEntity;
                if (crosshairSelection) {
                    engine.window.selectRightTab("Object");
                    engine.refreshObjectInspectorValues();
                    engine.syncOutlinerSelectionToCurrentSelection();
                    engine.rebuildSceneDetailsPanel();
                    System.out.println("Selected (crosshair): " + engine.selectedEntity.getName());
                } else {
                    boolean frameOnSelect = engine.selectionViewMode == Engine.SelectionViewMode.FRAME_AND_FOCUS
                            && !isOutputCameraEntity;
                    engine.objectFocusMode = frameOnSelect;
                    engine.draggingSelectedObject = !isOutputCameraEntity;
                    if (frameOnSelect) {
                        engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
                        engine.camera.lookAt(engine.selectedEntity.getTransform().getPosition());
                    }
                    engine.window.selectRightTab(isOutputCameraEntity ? "Output" : "Object");
                    engine.refreshObjectInspectorValues();
                    engine.syncOutlinerSelectionToCurrentSelection();
                    engine.rebuildSceneDetailsPanel();
                    System.out.println(
                            (frameOnSelect ? "Object focus: " : "Selected: ")
                                    + engine.selectedEntity.getName());
                }
            }
            return;
        }

        if (hit.type == Engine.SceneItemType.LIGHT && hit.light != null) {
            engine.setCurrentLightSelection(hit.light);
            engine.window.selectRightTab("Scene");
            engine.refreshObjectInspectorValues();
            engine.syncOutlinerSelectionToCurrentSelection();
            engine.rebuildSceneDetailsPanel();
            System.out.println("Selected light: " + engine.getLightName(hit.light));
            return;
        }

        if (hit.type == Engine.SceneItemType.FORCE_FIELD && hit.forceField != null) {
            engine.setCurrentForceFieldSelection(hit.forceField);
            engine.window.selectRightTab("Scene");
            engine.refreshObjectInspectorValues();
            engine.syncOutlinerSelectionToCurrentSelection();
            engine.rebuildSceneDetailsPanel();
            System.out.println("Selected force: " + hit.forceField.name);
            return;
        }

        clearSelection(engine, "Deselected.");
    }
}
