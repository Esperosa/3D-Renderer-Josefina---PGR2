package engine.core;

import engine.math.Vec3;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

final class EngineScenePanels {
    private static final Dimension SCENE_BROWSER_SEARCH_PREFERRED = new Dimension(140, 24);
    private static final Dimension SCENE_BROWSER_SEARCH_MIN = new Dimension(108, 24);
    private static final Dimension SCENE_BROWSER_SEARCH_MAX = new Dimension(168, 24);

    private EngineScenePanels() {
    }

    static JPanel buildSceneBrowser(Engine engine) {
        JPanel browser = new JPanel(new BorderLayout(0, 4));
        browser.setOpaque(true);
        browser.setBackground(UiTheme.PANEL_BG);
        browser.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        browser.setMinimumSize(new Dimension(0, 0));

        JPanel header = new JPanel(new BorderLayout(6, 0));
        header.setOpaque(false);
        engine.sceneBrowserStatusLabel = new JLabel("0 položek", engine.window.createVectorIcon("scene", 13), JLabel.LEFT);
        engine.sceneBrowserStatusLabel.setForeground(UiTheme.TEXT_MUTED);
        engine.sceneBrowserStatusLabel.setFont(engine.sceneBrowserStatusLabel.getFont().deriveFont(java.awt.Font.PLAIN, 11.5f));
        header.add(engine.sceneBrowserStatusLabel, BorderLayout.WEST);

        engine.sceneBrowserSearchField = new JTextField(engine.sceneBrowserFilterText == null ? "" : engine.sceneBrowserFilterText);
        EditorFocusContext.mark(engine.sceneBrowserSearchField, EditorFocusContext.TEXT_INPUT);
        UiBuilder.styleInspectorField(engine.sceneBrowserSearchField);
        engine.sceneBrowserSearchField.setToolTipText("Filtrovat položky podle názvu nebo typu.");
        engine.sceneBrowserSearchField.setFont(engine.sceneBrowserSearchField.getFont().deriveFont(12.0f));
        engine.sceneBrowserSearchField.setPreferredSize(SCENE_BROWSER_SEARCH_PREFERRED);
        engine.sceneBrowserSearchField.setMinimumSize(SCENE_BROWSER_SEARCH_MIN);
        engine.sceneBrowserSearchField.setMaximumSize(SCENE_BROWSER_SEARCH_MAX);
        engine.sceneBrowserSearchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                updateSceneBrowserFilter(engine);
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                updateSceneBrowserFilter(engine);
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                updateSceneBrowserFilter(engine);
            }
        });
        header.add(engine.sceneBrowserSearchField, BorderLayout.EAST);
        browser.add(header, BorderLayout.NORTH);

        engine.sceneOutlinerModel = new DefaultListModel<>();
        engine.sceneOutlinerList = new javax.swing.JList<>(engine.sceneOutlinerModel);
        EditorFocusContext.mark(engine.sceneOutlinerList, EditorFocusContext.OUTLINER);
        engine.sceneOutlinerList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        engine.sceneOutlinerList.setBackground(UiTheme.PANEL_INSET);
        engine.sceneOutlinerList.setForeground(UiTheme.TEXT_PRIMARY);
        engine.sceneOutlinerList.setSelectionBackground(UiTheme.SELECTION_BG_SOFT);
        engine.sceneOutlinerList.setSelectionForeground(UiTheme.TEXT_PRIMARY);
        engine.sceneOutlinerList.setFixedCellHeight(EngineSceneInspector.outlinerRowHeight());
        engine.sceneOutlinerList.setCellRenderer(EngineSceneInspector.createSceneOutlinerRenderer(engine));
        engine.sceneOutlinerList.getActionMap().put(EditorActionId.DELETE, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (engine.deleteSelectedOutlinerItem()) {
                    engine.focusCanvas();
                }
            }
        });
        bindSceneOutlinerShortcuts(engine);
        EngineSceneInspector.installSceneOutlinerInteractions(engine);

        JScrollPane outlinerScroll = new JScrollPane(engine.sceneOutlinerList);
        outlinerScroll.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER_SUBTLE, 1, true));
        outlinerScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        outlinerScroll.setMinimumSize(new Dimension(0, 0));
        UiTheme.styleScrollPane(outlinerScroll, UiTheme.PANEL_INSET);
        browser.add(outlinerScroll, BorderLayout.CENTER);

        engine.window.setRightSidebarSceneBrowser(browser);
        return browser;
    }

    private static void updateSceneBrowserFilter(Engine engine) {
        String next = engine.sceneBrowserSearchField != null ? engine.sceneBrowserSearchField.getText() : "";
        engine.sceneBrowserFilterText = next == null ? "" : next.trim();
        engine.refreshSceneOutliner();
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
        java.awt.Point scrollPosition = engine.window.captureRightTabViewPosition("World");
        JPanel worldTab = engine.window.createRightTab("World", UiStrings.Tabs.WORLD, "world");
        worldTab.removeAll();
        worldTab.add(UiBuilder.panelHeader(UiStrings.World.HEADER_TITLE, null));
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
        worldTab.revalidate();
        worldTab.repaint();
        engine.window.restoreRightTabViewPosition("World", scrollPosition);
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
        inputTab.add(UiBuilder.panelHeader(UiStrings.View.HEADER_TITLE, null));
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

    static JPanel buildItemTab(Engine engine) {
        JPanel itemTab = engine.window.createRightTab("Item", UiStrings.Tabs.ITEM, "item");
        itemTab.removeAll();
        itemTab.add(UiBuilder.panelHeader("Položka", null));
        itemTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        engine.objectHeaderLabel = engine.sectionTitle("Bez výběru");
        itemTab.add(engine.objectHeaderLabel);
        itemTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        engine.sceneDetailsPanel = engine.addCollapsibleSection(itemTab, UiStrings.Scene.SELECTED_ITEM, true);

        engine.itemTransformSection = engine.addCollapsibleSection(itemTab, UiStrings.Object.TRANSFORM, true);
        engine.itemTransformSection.add(engine.sectionTitle(UiStrings.Object.POSITION));
        engine.posXField = engine.addTransformField(engine.itemTransformSection, "X");
        engine.posYField = engine.addTransformField(engine.itemTransformSection, "Y");
        engine.posZField = engine.addTransformField(engine.itemTransformSection, "Z");
        engine.itemTransformSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        engine.itemTransformSection.add(engine.sectionTitle(UiStrings.Object.ROTATION));
        engine.rotXField = engine.addTransformField(engine.itemTransformSection, "X");
        engine.rotYField = engine.addTransformField(engine.itemTransformSection, "Y");
        engine.rotZField = engine.addTransformField(engine.itemTransformSection, "Z");
        engine.itemTransformSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        engine.itemTransformSection.add(engine.sectionTitle(UiStrings.Object.SCALE));
        engine.scaleXField = engine.addTransformField(engine.itemTransformSection, "X");
        engine.scaleYField = engine.addTransformField(engine.itemTransformSection, "Y");
        engine.scaleZField = engine.addTransformField(engine.itemTransformSection, "Z");
        engine.itemTransformSection.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        engine.itemTransformSection.add(engine.actionButton(UiStrings.Object.APPLY_TRANSFORM, engine::applyObjectInspectorValues));

        engine.itemOperationsSection = engine.addCollapsibleSection(itemTab, UiStrings.Object.OPERATIONS, false);
        engine.itemOperationsSection.add(engine.actionButton(UiStrings.Object.FOCUS_SELECTION_ACTION, () -> {
            if (engine.selectedEntity != null) {
                engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
                engine.camera.lookAt(engine.selectedEntity.getTransform().getPosition());
            }
        }));
        engine.itemOperationsSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.itemOperationsSection.add(engine.actionButton(UiStrings.Object.RELEASE_FOCUS, () -> {
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
        }));
        engine.itemOperationsSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.itemOperationsSection.add(engine.actionButton(UiStrings.Object.ADD_RELEASE_KEY, engine::addTimelineReleaseKeyForSelection));
        engine.itemOperationsSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.itemOperationsSection.add(engine.actionButton(UiStrings.Object.REMOVE_RELEASE_KEY, engine::removeTimelineReleaseKeyForSelection));
        return itemTab;
    }
}
