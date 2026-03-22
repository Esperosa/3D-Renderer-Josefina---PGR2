package engine.core;

import engine.math.Vec3;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

import java.awt.Dimension;
import javax.swing.Box;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

final class EngineScenePanels {

    private EngineScenePanels() {
    }

    static JPanel buildSceneTab(Engine engine) {
        JPanel sceneTab = engine.window.createRightTab("Scene", UiStrings.Tabs.SCENE, "scene");
        sceneTab.removeAll();
        sceneTab.add(UiBuilder.panelHeader(UiStrings.Scene.HEADER_TITLE, UiStrings.Scene.HEADER_SUBTITLE));
        sceneTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        JPanel outlinerSection = engine.addCollapsibleSection(sceneTab, UiStrings.Scene.OUTLINER, true);
        engine.sceneOutlinerModel = new DefaultListModel<>();
        engine.sceneOutlinerList = new JList<>(engine.sceneOutlinerModel);
        EditorFocusContext.mark(engine.sceneOutlinerList, EditorFocusContext.OUTLINER);
        engine.sceneOutlinerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        engine.sceneOutlinerList.setBackground(UiTheme.PANEL_INSET);
        engine.sceneOutlinerList.setForeground(UiTheme.TEXT_PRIMARY);
        engine.sceneOutlinerList.setSelectionBackground(UiTheme.SELECTION_BG);
        engine.sceneOutlinerList.setSelectionForeground(UiTheme.TEXT_PRIMARY);
        engine.sceneOutlinerList.getActionMap().put(EditorActionId.DELETE, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (engine.deleteSelectedOutlinerItem()) {
                    engine.focusCanvas();
                }
            }
        });
        JScrollPane outlinerScroll = new JScrollPane(engine.sceneOutlinerList);
        outlinerScroll.setAlignmentX(0.0f);
        outlinerScroll.setPreferredSize(new Dimension(220, 240));
        outlinerScroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 280));
        UiTheme.styleScrollPane(outlinerScroll, UiTheme.PANEL_INSET);
        outlinerSection.add(outlinerScroll);
        outlinerSection.add(Box.createRigidArea(new Dimension(0, 6)));
        outlinerSection.add(UiBuilder.helperText(UiStrings.Scene.QUICK_ADD_HINT));

        JPanel outlinerQuickAdd = createButtonGrid();
        for (String type : EngineSceneActions.basicPrimitiveTypes()) {
            outlinerQuickAdd.add(engine.actionButton("+ " + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type)));
        }
        outlinerSection.add(outlinerQuickAdd);
        outlinerSection.add(Box.createRigidArea(new Dimension(0, 6)));

        JPanel featuredQuickAdd = createButtonGrid();
        for (String type : EngineSceneActions.featuredPrimitiveTypes()) {
            featuredQuickAdd.add(engine.actionButton("+ " + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type)));
        }
        outlinerSection.add(featuredQuickAdd);

        engine.sceneDetailsPanel = engine.addCollapsibleSection(sceneTab, UiStrings.Scene.SELECTED_ITEM, true);
        engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Common.SELECT_IN_OUTLINER));
        bindSceneOutlinerShortcuts(engine);

        JPanel addSection = engine.addCollapsibleSection(sceneTab, UiStrings.Scene.ADD_TO_SCENE, false);
        addSection.add(engine.sectionTitle(UiStrings.Scene.BASIC_OBJECTS));
        JPanel basicGrid = createButtonGrid();
        for (String type : EngineSceneActions.basicPrimitiveTypes()) {
            basicGrid.add(engine.actionButton(UiStrings.ContextMenu.ADD_PREFIX + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type)));
        }
        addSection.add(basicGrid);
        addSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        addSection.add(engine.sectionTitle(UiStrings.Scene.FEATURED_OBJECTS));
        JPanel featuredGrid = createButtonGrid();
        for (String type : EngineSceneActions.featuredPrimitiveTypes()) {
            featuredGrid.add(engine.actionButton(UiStrings.ContextMenu.ADD_PREFIX + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type)));
        }
        addSection.add(featuredGrid);
        addSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_2)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.IMPORT_MODEL_SCENE, engine::importModelOrSceneFromDialog));
        addSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        addSection.add(engine.sectionTitle(UiStrings.Scene.LIGHTS));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_POINT_LIGHT, engine::addPointLight));
        addSection.add(Box.createRigidArea(new Dimension(0, 4)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_AREA_LIGHT, engine::addAreaLight));
        addSection.add(Box.createRigidArea(new Dimension(0, 4)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_CONE_LIGHT, engine::addConeLight));
        addSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        addSection.add(engine.sectionTitle(UiStrings.Scene.FORCE_FIELDS));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_VECTOR_FORCE, engine::addVectorForceField));
        addSection.add(Box.createRigidArea(new Dimension(0, 4)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_POINT_ATTRACTOR, () -> engine.addPointForceField(true)));
        addSection.add(Box.createRigidArea(new Dimension(0, 4)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_POINT_REPULSOR, () -> engine.addPointForceField(false)));
        addSection.add(Box.createRigidArea(new Dimension(0, 4)));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_TURBULENCE, engine::addTurbulenceForceField));
        addSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        addSection.add(engine.sectionTitle(UiStrings.Scene.SIMULATION));
        addSection.add(engine.actionButton(UiStrings.ContextMenu.ADD_WATER_EMITTER, engine::addWaterEmitter));

        JPanel selectionSection = engine.addCollapsibleSection(sceneTab, UiStrings.Scene.SELECTION, false);
        selectionSection.add(engine.actionButton(UiStrings.Scene.SELECT_PREVIOUS, () -> engine.selectRelative(-1)));
        selectionSection.add(Box.createRigidArea(new Dimension(0, 4)));
        selectionSection.add(engine.actionButton(UiStrings.Scene.SELECT_NEXT, () -> engine.selectRelative(1)));
        selectionSection.add(Box.createRigidArea(new Dimension(0, 4)));
        selectionSection.add(engine.actionButton(UiStrings.Scene.FOCUS_SELECTION, () -> {
            if (engine.selectedEntity != null) {
                engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
            }
        }));
        selectionSection.add(Box.createRigidArea(new Dimension(0, 4)));
        selectionSection.add(engine.actionButton(UiStrings.Scene.CLEAR_SELECTION, () -> engine.clearSelection(UiStrings.Scene.SELECTION_CLEARED_MSG)));

        return sceneTab;
    }

    static void bindSceneOutlinerShortcuts(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return;
        }
        engine.sceneOutlinerList.addListSelectionListener(e -> {
            if (engine.suppressSceneOutlinerSelectionEvent) {
                return;
            }
            engine.applyOutlinerSelectionFromList();
        });
    }

    static JPanel buildWorldTab(Engine engine) {
        JPanel worldTab = engine.window.createRightTab("World", UiStrings.Tabs.WORLD, "scene");
        worldTab.removeAll();
        worldTab.add(UiBuilder.panelHeader(UiStrings.World.HEADER_TITLE, UiStrings.World.HEADER_SUBTITLE));
        worldTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        JPanel worldSection = engine.addCollapsibleSection(worldTab, UiStrings.World.LIGHT, true);
        engine.addColorPickerRow(worldSection, UiStrings.World.AMBIENT_COLOR, engine.worldLightColor, color -> {
            engine.applySceneEdit(UiStrings.World.HISTORY_CHANGE_AMBIENT_COLOR, () -> {
                engine.worldLightColor = new Vec3(
                        Math.max(0.0, Math.min(1.0, color.x)),
                        Math.max(0.0, Math.min(1.0, color.y)),
                        Math.max(0.0, Math.min(1.0, color.z))
                );
                engine.applyWorldLightSettings();
            });
        });
        engine.addNumericRow(worldSection, UiStrings.World.STRENGTH, engine.formatTransformValue(engine.worldLightStrength), text -> {
            engine.applySceneEdit(UiStrings.World.HISTORY_CHANGE_STRENGTH, () -> {
                engine.worldLightStrength = Math.max(0.0, Math.min(8.0, engine.parseOrFallback(text, engine.worldLightStrength)));
                engine.applyWorldLightSettings();
            });
        });
        engine.addColorPickerRow(worldSection, UiStrings.World.BACKGROUND, engine.worldBackgroundColor, color -> {
            engine.applySceneEdit(UiStrings.World.HISTORY_CHANGE_BACKGROUND_COLOR, () -> {
                engine.worldBackgroundColor = new Vec3(
                        Math.max(0.0, Math.min(1.0, color.x)),
                        Math.max(0.0, Math.min(1.0, color.y)),
                        Math.max(0.0, Math.min(1.0, color.z))
                );
                engine.applyWorldLightSettings();
            });
        });
        engine.addNumericRow(worldSection, UiStrings.World.ENVIRONMENT_YAW, engine.formatTransformValue(engine.worldEnvironmentYawDegrees), text -> {
            engine.applySceneEdit(UiStrings.World.HISTORY_CHANGE_ENV_YAW, () -> {
                double next = engine.parseOrFallback(text, engine.worldEnvironmentYawDegrees);
                engine.worldEnvironmentYawDegrees = clampAngleDegrees(next, -360.0, 360.0);
                engine.applyWorldLightSettings();
            });
        });
        engine.addNumericRow(worldSection, UiStrings.World.ENVIRONMENT_PITCH, engine.formatTransformValue(engine.worldEnvironmentPitchDegrees), text -> {
            engine.applySceneEdit(UiStrings.World.HISTORY_CHANGE_ENV_PITCH, () -> {
                double next = engine.parseOrFallback(text, engine.worldEnvironmentPitchDegrees);
                engine.worldEnvironmentPitchDegrees = clampAngleDegrees(next, -90.0, 90.0);
                engine.applyWorldLightSettings();
            });
        });
        engine.addComboRow(worldSection, UiStrings.World.PRESET,
                WorldPresetCatalog.labels(),
                UiStrings.worldPresetLabel(engine.worldPresetKey),
                value -> engine.applyWorldPreset(UiStrings.worldPresetKey(value)));
        engine.addBooleanRow(worldSection, UiStrings.World.ANIMATE_WORLD_LIGHT, engine.worldLightAnimationEnabled, value ->
                engine.applySceneEdit(UiStrings.World.HISTORY_TOGGLE_ANIMATION, () -> engine.worldLightAnimationEnabled = value));
        return worldTab;
    }

    private static double clampAngleDegrees(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return 0.0;
        }
        return Math.max(min, Math.min(max, value));
    }

    static JPanel buildInputTab(Engine engine) {
        JPanel inputTab = engine.window.createRightTab("Controls", UiStrings.Tabs.VIEW, "controls");
        inputTab.removeAll();
        inputTab.add(UiBuilder.panelHeader(UiStrings.View.HEADER_TITLE, UiStrings.View.HEADER_SUBTITLE));
        inputTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        JPanel navSection = engine.addCollapsibleSection(inputTab, UiStrings.View.NAVIGATION, true);
        navSection.add(engine.actionButton(UiStrings.View.FPS_MODE, () -> engine.setNavigationPreset(Engine.NavigationPreset.FPS)));
        navSection.add(Box.createRigidArea(new Dimension(0, 4)));
        navSection.add(engine.actionButton(UiStrings.View.BLENDER_MODE, () -> engine.setNavigationPreset(Engine.NavigationPreset.BLENDER)));
        navSection.add(Box.createRigidArea(new Dimension(0, 4)));
        navSection.add(engine.actionButton(UiStrings.View.TOGGLE_CURSOR_CAPTURE, () -> {
            if (engine.mouseCaptured) {
                engine.releaseMouseCapture();
            } else {
                engine.captureMouse();
            }
        }));
        navSection.add(Box.createRigidArea(new Dimension(0, 4)));
        navSection.add(engine.actionButton(UiStrings.View.CYCLE_CAMERA_MODE, engine::cycleCameraMode));
        navSection.add(Box.createRigidArea(new Dimension(0, 4)));
        navSection.add(engine.actionButton(UiStrings.View.TOGGLE_PROJECTION, engine::toggleProjectionCamera));

        JPanel speedSection = engine.addCollapsibleSection(inputTab, UiStrings.View.MOTION, false);
        engine.addNumericRow(speedSection, UiStrings.View.MOVE_SPEED, engine.formatTransformValue(engine.cameraController.getMoveSpeed()), text -> {
            double next = Math.max(0.1, engine.parseOrFallback(text, engine.cameraController.getMoveSpeed()));
            engine.cameraController.setMoveSpeed(next);
        });
        engine.addNumericRow(speedSection, UiStrings.View.ROTATE_SENSITIVITY, engine.formatTransformValue(engine.cameraController.getRotateSpeed()), text -> {
            double next = Math.max(1e-5, engine.parseOrFallback(text, engine.cameraController.getRotateSpeed()));
            engine.cameraController.setRotateSpeed(next);
        });
        engine.addBooleanRow(speedSection, UiStrings.View.ANIMATION_PLAYBACK, engine.animationPlaybackEnabled, value -> {
            engine.animationPlaybackEnabled = value;
            engine.refreshUiIndicators();
        });
        engine.addBooleanRow(speedSection, UiStrings.View.PHYSICS, engine.physicsEnabled, value -> {
            engine.physicsEnabled = value;
            engine.refreshUiIndicators();
        });
        return inputTab;
    }

    static JPanel buildObjectTab(Engine engine) {
        JPanel objectTab = engine.window.createRightTab("Object", UiStrings.Tabs.OBJECT, "object");
        objectTab.removeAll();
        objectTab.add(UiBuilder.panelHeader(UiStrings.Object.HEADER_TITLE, UiStrings.Object.HEADER_SUBTITLE));
        objectTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        engine.objectHeaderLabel = engine.sectionTitle(UiStrings.Object.NONE_SELECTED);
        objectTab.add(engine.objectHeaderLabel);
        objectTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        JPanel objectTransform = engine.addCollapsibleSection(objectTab, UiStrings.Object.TRANSFORM, true);
        objectTransform.add(engine.sectionTitle(UiStrings.Object.POSITION));
        engine.posXField = engine.addTransformField(objectTransform, "X");
        engine.posYField = engine.addTransformField(objectTransform, "Y");
        engine.posZField = engine.addTransformField(objectTransform, "Z");
        objectTransform.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        objectTransform.add(engine.sectionTitle(UiStrings.Object.ROTATION));
        engine.rotXField = engine.addTransformField(objectTransform, "X");
        engine.rotYField = engine.addTransformField(objectTransform, "Y");
        engine.rotZField = engine.addTransformField(objectTransform, "Z");
        objectTransform.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        objectTransform.add(engine.sectionTitle(UiStrings.Object.SCALE));
        engine.scaleXField = engine.addTransformField(objectTransform, "X");
        engine.scaleYField = engine.addTransformField(objectTransform, "Y");
        engine.scaleZField = engine.addTransformField(objectTransform, "Z");
        objectTransform.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        objectTransform.add(engine.actionButton(UiStrings.Object.APPLY_TRANSFORM, engine::applyObjectInspectorValues));

        JPanel objectOps = engine.addCollapsibleSection(objectTab, UiStrings.Object.OPERATIONS, false);
        objectOps.add(engine.actionButton(UiStrings.Object.FOCUS_SELECTION_ACTION, () -> {
            if (engine.selectedEntity != null) {
                engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
                engine.camera.lookAt(engine.selectedEntity.getTransform().getPosition());
            }
        }));
        objectOps.add(Box.createRigidArea(new Dimension(0, 4)));
        objectOps.add(engine.actionButton(UiStrings.Object.RELEASE_FOCUS, () -> {
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
        }));
        objectOps.add(Box.createRigidArea(new Dimension(0, 4)));
        objectOps.add(engine.actionButton(UiStrings.Object.ADD_RELEASE_KEY, engine::addTimelineReleaseKeyForSelection));
        objectOps.add(Box.createRigidArea(new Dimension(0, 4)));
        objectOps.add(engine.actionButton(UiStrings.Object.REMOVE_RELEASE_KEY, engine::removeTimelineReleaseKeyForSelection));
        return objectTab;
    }

    private static JPanel createButtonGrid() {
        JPanel grid = new JPanel(new java.awt.GridLayout(0, 2, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(0.0f);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return grid;
    }
}
