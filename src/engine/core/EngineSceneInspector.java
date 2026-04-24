package engine.core;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;

import engine.material.Material;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPresets;
import engine.material.MaterialSupportMatrix;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.sim.water.WaterEmitter;
import engine.sim.water.WaterEmitterEntity;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

final class EngineSceneInspector {
    private static final int OUTLINER_ROW_HEIGHT = 38;
    private static final int TOGGLE_ICON_SIZE = 14;
    private static final int TOGGLE_GAP = 5;

    private EngineSceneInspector() {
    }

    static javax.swing.ListCellRenderer<? super Engine.SceneItemRef> createSceneOutlinerRenderer(Engine engine) {
        return new SceneOutlinerCellRenderer(engine);
    }

    static int outlinerRowHeight() {
        return OUTLINER_ROW_HEIGHT;
    }

    static void installSceneOutlinerInteractions(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return;
        }
        for (java.awt.event.MouseListener listener : engine.sceneOutlinerList.getMouseListeners()) {
            if (listener instanceof SceneOutlinerMouseHandler) {
                engine.sceneOutlinerList.removeMouseListener(listener);
            }
        }
        for (java.awt.event.MouseMotionListener listener : engine.sceneOutlinerList.getMouseMotionListeners()) {
            if (listener instanceof SceneOutlinerMouseHandler) {
                engine.sceneOutlinerList.removeMouseMotionListener(listener);
            }
        }
        SceneOutlinerMouseHandler handler = new SceneOutlinerMouseHandler(engine);
        engine.sceneOutlinerList.addMouseListener(handler);
        engine.sceneOutlinerList.addMouseMotionListener(handler);
    }

    static Object outlinerKey(Engine engine, Engine.SceneItemRef ref) {
        if (ref == null) {
            return null;
        }
        switch (ref.type) {
            case ENTITY:
                return ref.entity;
            case LIGHT:
                return ref.light;
            case FORCE_FIELD:
                return ref.forceField;
            case WORLD:
                return engine.scene;
            default:
                return null;
        }
    }

    static Engine.SceneItemRef selectedOutlinerRef(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return null;
        }
        int idx = engine.sceneOutlinerList.getSelectedIndex();
        if (idx < 0 || idx >= engine.sceneOutlinerItems.size()) {
            return null;
        }
        return engine.sceneOutlinerItems.get(idx);
    }

    private static void clearSceneSelectionState(Engine engine) {
        engine.selectedEntity = null;
        engine.selectedLight = null;
        engine.selectedForceField = null;
        engine.objectFocusMode = false;
        engine.draggingSelectedObject = false;
        engine.transformMode = Engine.TransformMode.NONE;
        engine.axisConstraint = Engine.AxisConstraint.NONE;
        engine.gizmoDragActive = false;
    }

    private static void showItemInspector(Engine engine) {
        engine.window.selectRightTab("Item");
        engine.refreshObjectInspectorValues();
    }

    private static void showWorldInspector(Engine engine) {
        engine.window.selectRightTab("World");
        engine.refreshObjectInspectorValues();
    }

    private static void addDetailsInfoRow(Engine engine, String label, String value) {
        UiBuilder.addReadOnlyRow(engine.sceneDetailsPanel, label, value);
    }

    static void applyOutlinerSelectionFromList(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return;
        }
        int idx = engine.sceneOutlinerList.getSelectedIndex();
        if (idx < 0 || idx >= engine.sceneOutlinerItems.size()) {
            clearSceneSelectionState(engine);
            engine.refreshObjectInspectorValues();
            rebuildSceneDetailsPanel(engine);
            return;
        }
        Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(idx);
        if (ref.type == Engine.SceneItemType.ENTITY && ref.entity != null) {
            engine.setCurrentEntitySelection(ref.entity);
            showItemInspector(engine);
        } else if (ref.type == Engine.SceneItemType.LIGHT && ref.light != null) {
            engine.setCurrentLightSelection(ref.light);
            showItemInspector(engine);
        } else if (ref.type == Engine.SceneItemType.FORCE_FIELD && ref.forceField != null) {
            engine.setCurrentForceFieldSelection(ref.forceField);
            showItemInspector(engine);
        } else if (ref.type == Engine.SceneItemType.WORLD) {
            clearSceneSelectionState(engine);
            showWorldInspector(engine);
        }
        rebuildSceneDetailsPanel(engine);
    }

    static boolean deleteSelectedOutlinerItem(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return false;
        }
        Engine.SceneItemRef ref = selectedOutlinerRef(engine);
        if (ref == null) {
            return false;
        }
        if (ref.type == Engine.SceneItemType.ENTITY && ref.entity != null) {
            engine.setCurrentEntitySelection(ref.entity);
        } else if (ref.type == Engine.SceneItemType.LIGHT && ref.light != null) {
            engine.setCurrentLightSelection(ref.light);
        } else if (ref.type == Engine.SceneItemType.FORCE_FIELD && ref.forceField != null) {
            engine.setCurrentForceFieldSelection(ref.forceField);
        } else {
            return false;
        }
        return engine.deleteCurrentSelection();
    }

    static void refreshSceneOutliner(Engine engine) {
        if (engine.sceneOutlinerModel == null || engine.sceneOutlinerList == null || engine.scene == null) {
            return;
        }
        int previousIndex = engine.sceneOutlinerList.getSelectedIndex();
        int totalItems = engine.scene.getEntities().size()
                + engine.scene.getLights().size()
                + engine.forceFields.size()
                + 1;
        Object selectedKey = engine.selectedEntity;
        if (selectedKey == null) {
            selectedKey = engine.selectedLight;
        }
        if (selectedKey == null) {
            selectedKey = engine.selectedForceField;
        }
        if (selectedKey == null) {
            selectedKey = outlinerKey(engine, selectedOutlinerRef(engine));
        }

        engine.suppressSceneOutlinerSelectionEvent = true;
        engine.sceneOutlinerItems.clear();
        engine.sceneOutlinerModel.clear();

        for (Entity entity : engine.scene.getEntities()) {
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.ENTITY;
            ref.entity = entity;
            if (matchesSceneBrowserFilter(engine, ref)) {
                engine.sceneOutlinerItems.add(ref);
                engine.sceneOutlinerModel.addElement(ref);
            }
        }
        for (Light light : engine.scene.getLights()) {
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.LIGHT;
            ref.light = light;
            if (matchesSceneBrowserFilter(engine, ref)) {
                engine.sceneOutlinerItems.add(ref);
                engine.sceneOutlinerModel.addElement(ref);
            }
        }
        for (Engine.ForceField field : engine.forceFields) {
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.FORCE_FIELD;
            ref.forceField = field;
            if (matchesSceneBrowserFilter(engine, ref)) {
                engine.sceneOutlinerItems.add(ref);
                engine.sceneOutlinerModel.addElement(ref);
            }
        }

        Engine.SceneItemRef worldRef = new Engine.SceneItemRef();
        worldRef.type = Engine.SceneItemType.WORLD;
        if (matchesSceneBrowserFilter(engine, worldRef)) {
            engine.sceneOutlinerItems.add(worldRef);
            engine.sceneOutlinerModel.addElement(worldRef);
        }

        int selectIndex = -1;
        if (selectedKey != null) {
            for (int i = 0; i < engine.sceneOutlinerItems.size(); i++) {
                if (outlinerKey(engine, engine.sceneOutlinerItems.get(i)) == selectedKey) {
                    selectIndex = i;
                    break;
                }
            }
        }
        if (selectIndex < 0 && engine.selectedEntity != null) {
            for (int i = 0; i < engine.sceneOutlinerItems.size(); i++) {
                Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(i);
                if (ref.type == Engine.SceneItemType.ENTITY && ref.entity == engine.selectedEntity) {
                    selectIndex = i;
                    break;
                }
            }
        }
        if (selectIndex >= 0) {
            engine.sceneOutlinerList.setSelectedIndex(selectIndex);
            if (selectIndex != previousIndex) {
                engine.sceneOutlinerList.ensureIndexIsVisible(selectIndex);
            }
        } else {
            engine.sceneOutlinerList.clearSelection();
        }
        if (engine.sceneBrowserStatusLabel != null) {
            int visible = engine.sceneOutlinerItems.size();
            engine.sceneBrowserStatusLabel.setText((engine.sceneBrowserFilterText == null || engine.sceneBrowserFilterText.isBlank())
                    ? visible + " položek"
                    : visible + " / " + totalItems);
        }
        engine.suppressSceneOutlinerSelectionEvent = false;
    }

    private static boolean matchesSceneBrowserFilter(Engine engine, Engine.SceneItemRef ref) {
        if (ref == null) {
            return false;
        }
        String filter = engine.sceneBrowserFilterText;
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String normalized = filter.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.isEmpty()) {
            return true;
        }
        return outlinerPrimaryLabel(engine, ref).toLowerCase(java.util.Locale.ROOT).contains(normalized)
                || outlinerSecondaryLabel(engine, ref).toLowerCase(java.util.Locale.ROOT).contains(normalized);
    }

    static void syncOutlinerSelectionToCurrentSelection(Engine engine) {
        if (engine.sceneOutlinerList == null || engine.suppressSceneOutlinerSelectionEvent) {
            return;
        }
        Object key = engine.selectedEntity;
        if (key == null) {
            key = engine.selectedLight;
        }
        if (key == null) {
            key = engine.selectedForceField;
        }
        if (key == null) {
            engine.suppressSceneOutlinerSelectionEvent = true;
            engine.sceneOutlinerList.clearSelection();
            engine.suppressSceneOutlinerSelectionEvent = false;
            return;
        }
        int current = engine.sceneOutlinerList.getSelectedIndex();
        if (current >= 0 && current < engine.sceneOutlinerItems.size()) {
            Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(current);
            if (outlinerKey(engine, ref) == key) {
                return;
            }
        }
        for (int i = 0; i < engine.sceneOutlinerItems.size(); i++) {
            Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(i);
            if (outlinerKey(engine, ref) == key) {
                engine.suppressSceneOutlinerSelectionEvent = true;
                engine.sceneOutlinerList.setSelectedIndex(i);
                engine.sceneOutlinerList.ensureIndexIsVisible(i);
                engine.suppressSceneOutlinerSelectionEvent = false;
                rebuildSceneDetailsPanel(engine);
                return;
            }
        }
    }

    static String lightTypeName(Light light) {
        if (light instanceof DirectionalLight) {
            return "Směrové";
        }
        if (light instanceof ConeLight) {
            return "Kuželové";
        }
        if (light instanceof AreaLight) {
            return "Plošné";
        }
        if (light instanceof PointLight) {
            return "Bodové";
        }
        return light == null ? "Světlo" : light.getClass().getSimpleName();
    }

    static void rebuildSceneDetailsPanel(Engine engine) {
        if (engine.sceneDetailsPanel == null || engine.suppressSceneDetailRebuild) {
            return;
        }
        Point scrollPosition = captureScrollPosition(engine.sceneDetailsPanel);
        engine.suppressSceneDetailRebuild = true;
        try {
            engine.sceneDetailsPanel.removeAll();
            Engine.SceneItemRef ref = selectedOutlinerRef(engine);
            if (ref == null) {
                engine.sceneDetailsPanel.add(UiBuilder.infoLine("Bez výběru"));
                engine.sceneDetailsPanel.revalidate();
                engine.sceneDetailsPanel.repaint();
                restoreScrollPosition(engine.sceneDetailsPanel, scrollPosition);
                return;
            }

            switch (ref.type) {
                case ENTITY:
                    buildEntityDetails(engine, ref.entity);
                    break;
                case LIGHT:
                    buildLightDetails(engine, ref.light);
                    break;
                case FORCE_FIELD:
                    buildForceFieldDetails(engine, ref.forceField);
                    break;
                case WORLD:
                default:
                    engine.sceneDetailsPanel.add(engine.sectionTitle("Prostředí"));
                    engine.sceneDetailsPanel.add(engine.actionButton(UiStrings.Common.OPEN_WORLD_TAB,
                            () -> engine.window.selectRightTab("World")));
                    engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
                    addDetailsInfoRow(engine, "Ambient",
                            engine.formatTransformValue(engine.worldLightColor.x) + ", "
                                    + engine.formatTransformValue(engine.worldLightColor.y) + ", "
                                    + engine.formatTransformValue(engine.worldLightColor.z));
                    addDetailsInfoRow(engine, "Síla", engine.formatTransformValue(engine.worldLightStrength));
                    break;
            }
            engine.sceneDetailsPanel.revalidate();
            engine.sceneDetailsPanel.repaint();
            restoreScrollPosition(engine.sceneDetailsPanel, scrollPosition);
        } finally {
            engine.suppressSceneDetailRebuild = false;
        }
    }

    private static Point captureScrollPosition(javax.swing.JComponent component) {
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
        if (scrollPane == null) {
            return null;
        }
        return scrollPane.getViewport().getViewPosition();
    }

    private static void restoreScrollPosition(javax.swing.JComponent component, Point position) {
        if (position == null) {
            return;
        }
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, component);
        if (scrollPane == null) {
            return;
        }
        Point target = new Point(Math.max(0, position.x), Math.max(0, position.y));
        SwingUtilities.invokeLater(() -> scrollPane.getViewport().setViewPosition(target));
    }

    static void buildEntityDetails(Engine engine, Entity entity) {
        if (entity == null) {
            engine.sceneDetailsPanel.add(engine.sectionTitle("Bez objektu"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(entity);
        engine.addTextRow(engine.sceneDetailsPanel, "Název", entity.getName(), value -> {
            String sanitized = value == null || value.isBlank() ? entity.getName() : value.trim();
            engine.applySceneEdit("Přejmenování objektu", () -> {
                entity.setName(sanitized);
                refreshSceneOutliner(engine);
                rebuildSceneDetailsPanel(engine);
            });
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Viditelné ve viewportu", state.visibleInView, value -> {
            engine.applySceneEdit("Změna viditelnosti objektu", () -> {
                state.visibleInView = value;
                engine.applySceneVisibility(false);
                refreshSceneOutliner(engine);
            });
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Viditelné ve výstupu", state.visibleInOutput, value -> {
            engine.applySceneEdit("Změna viditelnosti objektu", () -> {
                state.visibleInOutput = value;
                refreshSceneOutliner(engine);
            });
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Vrhat stíny", entity.isCastShadow(), value -> {
            engine.applySceneEdit("Změna vrhání stínů", () -> entity.setCastShadow(value));
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Statický objekt", entity.isStatic(), value -> {
            engine.applySceneEdit("Změna statického objektu", () -> entity.setStatic(value));
        });

        Material currentMaterial = entity.getMaterial();
        if (!(currentMaterial instanceof PhongMaterial)) {
            String currentType = currentMaterial == null
                    ? "Žádný"
                    : currentMaterial.getClass().getSimpleName();
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
            engine.sceneDetailsPanel.add(UiBuilder.infoLine(currentType + " · nodes vyžadují Phong"));
            engine.sceneDetailsPanel.add(engine.actionButton("Převést na Phong materiál", () -> {
                engine.applySceneEdit("Převod materiálu na Phong", () -> {
                    Material source = entity.getMaterial();
                    Vec3 base = source != null ? source.getBaseColor() : new Vec3(0.7, 0.7, 0.7);
                    PhongMaterial converted = new PhongMaterial(base, 32.0);
                    if (source != null) {
                        converted.copyFrom(source);
                    }
                    converted.setNodeGraph(MaterialGraphAuthoring.createAuthoringGraphFromMaterial(converted));
                    MaterialGraphAuthoring.syncCompatibilityBindings(converted);
                    entity.setMaterial(converted);
                });
                engine.rebuildMaterialDock();
                rebuildSceneDetailsPanel(engine);
            }));
            return;
        }
        PhongMaterial material = (PhongMaterial) currentMaterial;
        engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Materiál · " + material.getName()));
        engine.addComboRow(
                engine.sceneDetailsPanel,
                "Předvolba",
                MaterialPresets.presetNames(),
                MaterialPresets.displayNameForId(material.getPresetName()),
                value -> {
                    PhongMaterial before = engine.captureMaterialHistoryState(entity);
                    MaterialPresets.apply(value, material);
                    engine.pushMaterialHistoryCommand("Použití předvolby materiálu", entity, before, engine.captureMaterialHistoryState(entity));
                    rebuildSceneDetailsPanel(engine);
                }
        );
        MaterialNodeGraph graph = material.getNodeGraph();
        MaterialNodeGraph.Node output = graph == null ? null : graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        boolean surfaceConnected = output != null && graph.findInputLink(output.getId(), "surface") != null;
        boolean volumeConnected = output != null && graph.findInputLink(output.getId(), "volume") != null;
        String route = graph == null ? "Fallback bez grafu"
                : surfaceConnected && volumeConnected ? "Povrch + objem"
                : surfaceConnected ? "Povrch"
                : volumeConnected ? "Objem"
                : "Bez výstupu";
        addDetailsInfoRow(engine, "Graph", graph == null
                ? "Připraví se při první editaci"
                : "Připraven");
        int graphNodeCount = graph == null ? 0 : graph.getNodes().size();
        int graphLinkCount = graph == null ? 0 : graph.getLinks().size();
        addDetailsInfoRow(engine, "Uzly", graphNodeCount + " / " + graphLinkCount + " · " + route);
        MaterialSupportMatrix.GraphSupport support = MaterialSupportMatrix.summarize(graph);
        addDetailsInfoRow(engine, "Kompatibilita", support.compactSummary()
                + (MaterialGraphAuthoring.hasConnectedNormalPath(material) ? " · normála" : ""));
        engine.sceneDetailsPanel.add(engine.actionButton("Upravit v panelu Materiál", () -> {
            engine.showMaterialWorkspace();
            engine.rebuildMaterialDock();
        }));
        engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.sceneDetailsPanel.add(engine.actionButton("Duplikovat materiál na objektu", () -> {
            PhongMaterial before = engine.captureMaterialHistoryState(entity);
            entity.setMaterial(material.copy());
            engine.pushMaterialHistoryCommand("Duplikace materiálu", entity, before, engine.captureMaterialHistoryState(entity));
            engine.rebuildMaterialDock();
            rebuildSceneDetailsPanel(engine);
        }));
        WaterEmitter emitter = resolveWaterEmitter(entity);
        if (emitter != null) {
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
            engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Spray.TITLE));
            engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Spray.EMISSION));
            engine.addBooleanRow(engine.sceneDetailsPanel, "Emise zapnuta", emitter.isEnabled(), emitter::setEnabled);
            engine.addNumericRow(engine.sceneDetailsPanel, "Rychlost emise", engine.formatTransformValue(emitter.getEmitRate()), text -> {
                emitter.setEmitRate(Math.max(0.0, engine.parseOrFallback(text, emitter.getEmitRate())));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Počáteční rychlost", engine.formatTransformValue(emitter.getInitialSpeed()), text -> {
                emitter.setInitialSpeed(Math.max(0.0, engine.parseOrFallback(text, emitter.getInitialSpeed())));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Rozptyl (st.)", engine.formatTransformValue(emitter.getSpreadAngleDegrees()), text -> {
                emitter.setSpreadAngleDegrees(engine.parseOrFallback(text, emitter.getSpreadAngleDegrees()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Životnost", engine.formatTransformValue(emitter.getParticleLifetime()), text -> {
                emitter.setParticleLifetime(engine.parseOrFallback(text, emitter.getParticleLifetime()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Poloměr částice", engine.formatTransformValue(emitter.getParticleRadius()), text -> {
                emitter.setParticleRadius(engine.parseOrFallback(text, emitter.getParticleRadius()));
            });
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Spray.MOTION));
            engine.addNumericRow(engine.sceneDetailsPanel, "Odpor", engine.formatTransformValue(emitter.getDrag()), text -> {
                emitter.setDrag(engine.parseOrFallback(text, emitter.getDrag()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Gravitační násobek", engine.formatTransformValue(emitter.getGravityScale()), text -> {
                emitter.setGravityScale(engine.parseOrFallback(text, emitter.getGravityScale()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Jitter", engine.formatTransformValue(emitter.getJitter()), text -> {
                emitter.setJitter(engine.parseOrFallback(text, emitter.getJitter()));
            });
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Spray.COLLISIONS));
            engine.addNumericRow(engine.sceneDetailsPanel, "Odraz", engine.formatTransformValue(emitter.getBounce()), text -> {
                emitter.setBounce(engine.parseOrFallback(text, emitter.getBounce()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Tlumení", engine.formatTransformValue(emitter.getSurfaceDamping()), text -> {
                emitter.setSurfaceDamping(engine.parseOrFallback(text, emitter.getSurfaceDamping()));
            });
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
            engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Spray.RENDERING));
            engine.addNumericRow(engine.sceneDetailsPanel, "Průhlednost částic", engine.formatTransformValue(emitter.getOpacity()), text -> {
                emitter.setOpacity(engine.parseOrFallback(text, emitter.getOpacity()));
            });
            engine.addColorPickerRow(engine.sceneDetailsPanel, "Tint částic", emitter.getTint(), color -> {
                emitter.setTint(new Vec3(clamp01(color.x), clamp01(color.y), clamp01(color.z)));
            });
            engine.sceneDetailsPanel.add(engine.actionButton(UiStrings.Spray.APPLY_CLEAR_MATERIAL, () -> {
                PhongMaterial before = engine.captureMaterialHistoryState(entity);
                MaterialPresets.apply(MaterialPresets.Preset.WATER, material);
                engine.pushMaterialHistoryCommand("Použití spray materiálu", entity, before, engine.captureMaterialHistoryState(entity));
                rebuildSceneDetailsPanel(engine);
            }));
        }
    }

    static void buildLightDetails(Engine engine, Light light) {
        if (light == null) {
            engine.sceneDetailsPanel.add(engine.sectionTitle("Bez světla"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(light);
        engine.sceneDetailsPanel.add(engine.sectionTitle(lightTypeName(light)));
        engine.addBooleanRow(engine.sceneDetailsPanel, "Viditelné ve viewportu", state.visibleInView, value -> {
            engine.applySceneEdit("Úprava světla", () -> {
                state.visibleInView = value;
                engine.applySceneVisibility(false);
                refreshSceneOutliner(engine);
            });
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Viditelné ve výstupu", state.visibleInOutput, value -> {
            engine.applySceneEdit("Úprava světla", () -> {
                state.visibleInOutput = value;
                refreshSceneOutliner(engine);
            });
        });
        engine.addNumericRow(engine.sceneDetailsPanel, "Intenzita", engine.formatTransformValue(light.getIntensity()), text -> {
            engine.applySceneEdit("Úprava světla", () ->
                    light.setIntensity(Math.max(0.0, engine.parseOrFallback(text, light.getIntensity()))));
        });
        engine.addColorPickerRow(engine.sceneDetailsPanel, "Barva", light.getColor(), color -> {
            engine.applySceneEdit("Úprava světla", () ->
                    light.setColor(new Vec3(clamp01(color.x), clamp01(color.y), clamp01(color.z))));
        });

        if (light instanceof DirectionalLight) {
            DirectionalLight dl = (DirectionalLight) light;
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr X", engine.formatTransformValue(dl.getDirection().x), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = dl.getDirection();
                    dl.setDirection(new Vec3(engine.parseOrFallback(text, d.x), d.y, d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Y", engine.formatTransformValue(dl.getDirection().y), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = dl.getDirection();
                    dl.setDirection(new Vec3(d.x, engine.parseOrFallback(text, d.y), d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Z", engine.formatTransformValue(dl.getDirection().z), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = dl.getDirection();
                    dl.setDirection(new Vec3(d.x, d.y, engine.parseOrFallback(text, d.z)));
                });
            });
            return;
        }

        if (light instanceof PointLight) {
            PointLight pl = (PointLight) light;
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice X", engine.formatTransformValue(pl.getPosition().x), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 p = pl.getPosition();
                    pl.setPosition(new Vec3(engine.parseOrFallback(text, p.x), p.y, p.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice Y", engine.formatTransformValue(pl.getPosition().y), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 p = pl.getPosition();
                    pl.setPosition(new Vec3(p.x, engine.parseOrFallback(text, p.y), p.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice Z", engine.formatTransformValue(pl.getPosition().z), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 p = pl.getPosition();
                    pl.setPosition(new Vec3(p.x, p.y, engine.parseOrFallback(text, p.z)));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Útlum lin.", engine.formatTransformValue(pl.getLinear()), text -> {
                engine.applySceneEdit("Úprava světla", () ->
                        pl.setAttenuation(pl.getConstant(), Math.max(0.0, engine.parseOrFallback(text, pl.getLinear())), pl.getQuadratic()));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Útlum kvad.", engine.formatTransformValue(pl.getQuadratic()), text -> {
                engine.applySceneEdit("Úprava světla", () ->
                        pl.setAttenuation(pl.getConstant(), pl.getLinear(), Math.max(0.0, engine.parseOrFallback(text, pl.getQuadratic()))));
            });
        }

        if (light instanceof AreaLight) {
            AreaLight al = (AreaLight) light;
            engine.addNumericRow(engine.sceneDetailsPanel, "Rozptyl (st.)", engine.formatTransformValue(al.getSpreadAngleDegrees()), text -> {
                engine.applySceneEdit("Úprava světla", () ->
                        al.setSpreadAngleDegrees(engine.parseOrFallback(text, al.getSpreadAngleDegrees())));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr emise X", engine.formatTransformValue(al.getEmissionDirection().x), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = al.getEmissionDirection();
                    al.setEmissionDirection(new Vec3(engine.parseOrFallback(text, d.x), d.y, d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr emise Y", engine.formatTransformValue(al.getEmissionDirection().y), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = al.getEmissionDirection();
                    al.setEmissionDirection(new Vec3(d.x, engine.parseOrFallback(text, d.y), d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr emise Z", engine.formatTransformValue(al.getEmissionDirection().z), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = al.getEmissionDirection();
                    al.setEmissionDirection(new Vec3(d.x, d.y, engine.parseOrFallback(text, d.z)));
                });
            });
        } else if (light instanceof ConeLight) {
            ConeLight cl = (ConeLight) light;
            engine.addNumericRow(engine.sceneDetailsPanel, "Kužel (st.)", engine.formatTransformValue(cl.getConeAngleDegrees()), text -> {
                engine.applySceneEdit("Úprava světla", () ->
                        cl.setConeAngleDegrees(engine.parseOrFallback(text, cl.getConeAngleDegrees())));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Měkkost", engine.formatTransformValue(cl.getSoftness()), text -> {
                engine.applySceneEdit("Úprava světla", () ->
                        cl.setSoftness(engine.parseOrFallback(text, cl.getSoftness())));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr X", engine.formatTransformValue(cl.getDirection().x), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = cl.getDirection();
                    cl.setDirection(new Vec3(engine.parseOrFallback(text, d.x), d.y, d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Y", engine.formatTransformValue(cl.getDirection().y), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = cl.getDirection();
                    cl.setDirection(new Vec3(d.x, engine.parseOrFallback(text, d.y), d.z));
                });
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Z", engine.formatTransformValue(cl.getDirection().z), text -> {
                engine.applySceneEdit("Úprava světla", () -> {
                    Vec3 d = cl.getDirection();
                    cl.setDirection(new Vec3(d.x, d.y, engine.parseOrFallback(text, d.z)));
                });
            });
        }
    }

    static void buildForceFieldDetails(Engine engine, Engine.ForceField field) {
        if (field == null) {
            engine.sceneDetailsPanel.add(engine.sectionTitle("Bez síly"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(field);
        engine.sceneDetailsPanel.add(engine.sectionTitle(field.type.toString()));
        engine.addBooleanRow(engine.sceneDetailsPanel, "Zapnuté ve viewportu", state.visibleInView, value -> {
            engine.applySceneEdit("Úprava síly", () -> {
                state.visibleInView = value;
                refreshSceneOutliner(engine);
            });
        });
        engine.addBooleanRow(engine.sceneDetailsPanel, "Zapnuté ve výstupu", state.visibleInOutput, value -> {
            engine.applySceneEdit("Úprava síly", () -> {
                state.visibleInOutput = value;
                refreshSceneOutliner(engine);
            });
        });
        engine.addNumericRow(engine.sceneDetailsPanel, "Síla", engine.formatTransformValue(field.strength), text -> {
            engine.applySceneEdit("Úprava síly", () ->
                    field.strength = Math.max(0.0, engine.parseOrFallback(text, field.strength)));
        });

        if (field.type == Engine.ForceFieldType.VECTOR) {
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr X", engine.formatTransformValue(field.direction.x), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.direction = new Vec3(engine.parseOrFallback(text, field.direction.x), field.direction.y, field.direction.z));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Y", engine.formatTransformValue(field.direction.y), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.direction = new Vec3(field.direction.x, engine.parseOrFallback(text, field.direction.y), field.direction.z));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Směr Z", engine.formatTransformValue(field.direction.z), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.direction = new Vec3(field.direction.x, field.direction.y, engine.parseOrFallback(text, field.direction.z)));
            });
        } else {
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice X", engine.formatTransformValue(field.position.x), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.position = new Vec3(engine.parseOrFallback(text, field.position.x), field.position.y, field.position.z));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice Y", engine.formatTransformValue(field.position.y), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.position = new Vec3(field.position.x, engine.parseOrFallback(text, field.position.y), field.position.z));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Pozice Z", engine.formatTransformValue(field.position.z), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.position = new Vec3(field.position.x, field.position.y, engine.parseOrFallback(text, field.position.z)));
            });
            engine.addNumericRow(engine.sceneDetailsPanel, "Poloměr", engine.formatTransformValue(field.radius), text -> {
                engine.applySceneEdit("Úprava síly", () ->
                        field.radius = Math.max(0.0, engine.parseOrFallback(text, field.radius)));
            });
            if (field.type == Engine.ForceFieldType.POINT) {
                engine.addBooleanRow(engine.sceneDetailsPanel, "Přitahovat (vyp = odpuzovat)", field.attract,
                        value -> engine.applySceneEdit("Úprava síly", () -> field.attract = value));
            } else if (field.type == Engine.ForceFieldType.TURBULENCE) {
                engine.addNumericRow(engine.sceneDetailsPanel, "Měřítko noise", engine.formatTransformValue(field.turbulenceScale), text -> {
                    engine.applySceneEdit("Úprava síly", () ->
                            field.turbulenceScale = Math.max(0.05, engine.parseOrFallback(text, field.turbulenceScale)));
                });
            }
        }
        engine.sceneDetailsPanel.add(engine.actionButton("Položit ke kameře",
                () -> engine.applySceneEdit("Přesun síly", () -> field.position = engine.spawnInFrontOfCamera(2.5))));
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static WaterEmitter resolveWaterEmitter(Entity entity) {
        if (entity instanceof WaterEmitterEntity) {
            return ((WaterEmitterEntity) entity).getEmitter();
        }
        return null;
    }

    private static String outlinerPrimaryLabel(Engine engine, Engine.SceneItemRef ref) {
        if (ref == null) {
            return "";
        }
        return switch (ref.type) {
            case ENTITY -> ref.entity == null ? "Objekt" : ref.entity.getName();
            case LIGHT -> ref.light == null ? "Světlo" : engine.getLightName(ref.light);
            case FORCE_FIELD -> ref.forceField == null ? "Síla" : ref.forceField.name;
            case WORLD -> "Prostředí";
        };
    }

    private static String outlinerSecondaryLabel(Engine engine, Engine.SceneItemRef ref) {
        if (ref == null) {
            return "";
        }
        return switch (ref.type) {
            case ENTITY -> entityBrowserLabel(ref.entity);
            case LIGHT -> ref.light == null ? "Světlo" : lightTypeName(ref.light) + " · " + engine.formatTransformValue(ref.light.getIntensity());
            case FORCE_FIELD -> ref.forceField == null ? "Síla" : switch (ref.forceField.type) {
                case VECTOR -> "Vektor · " + engine.formatTransformValue(ref.forceField.strength);
                case POINT -> (ref.forceField.attract ? "Přitažlivá" : "Odpudivá") + " · " + engine.formatTransformValue(ref.forceField.strength);
                case TURBULENCE -> "Turbulence · " + engine.formatTransformValue(ref.forceField.strength);
            };
            case WORLD -> UiStrings.worldPresetLabel(engine.worldPresetKey) + " · " + engine.formatTransformValue(engine.worldLightStrength);
        };
    }

    private static String entityBrowserLabel(Entity entity) {
        if (entity == null) {
            return "Objekt";
        }
        Material material = entity.getMaterial();
        if (material instanceof PhongMaterial phong) {
            String preset = phong.getPresetName();
            if (preset != null && !preset.isBlank()) {
                return MaterialPresets.displayNameForId(preset);
            }
        }
        return material == null ? "Objekt" : material.getName();
    }

    private static Engine.SceneItemState stateForRef(Engine engine, Engine.SceneItemRef ref) {
        if (ref == null) {
            return null;
        }
        return switch (ref.type) {
            case ENTITY -> ref.entity == null ? null : engine.stateFor(ref.entity);
            case LIGHT -> ref.light == null ? null : engine.stateFor(ref.light);
            case FORCE_FIELD -> ref.forceField == null ? null : engine.stateFor(ref.forceField);
            case WORLD -> null;
        };
    }

    private static Color colorForLight(Light light) {
        if (light == null || light.getColor() == null) {
            return new Color(255, 211, 122);
        }
        Vec3 color = light.getColor();
        double r = Math.max(0.0, Math.min(1.0, color.x));
        double g = Math.max(0.0, Math.min(1.0, color.y));
        double b = Math.max(0.0, Math.min(1.0, color.z));
        r = 0.35 + r * 0.65;
        g = 0.35 + g * 0.65;
        b = 0.35 + b * 0.65;
        return new Color((float) r, (float) g, (float) b);
    }

    private static Color colorForWorld(Engine engine) {
        Vec3 color = engine.worldBackgroundColor != null ? engine.worldBackgroundColor : engine.worldLightColor;
        if (color == null) {
            return new Color(255, 196, 96);
        }
        double r = 0.40 + Math.max(0.0, Math.min(1.0, color.x)) * 0.60;
        double g = 0.40 + Math.max(0.0, Math.min(1.0, color.y)) * 0.60;
        double b = 0.40 + Math.max(0.0, Math.min(1.0, color.z)) * 0.60;
        return new Color((float) r, (float) g, (float) b);
    }

    private static ToggleTarget resolveToggleTarget(JList<?> list, int index, int x, int y) {
        java.awt.Rectangle bounds = list.getCellBounds(index, index);
        if (bounds == null || !bounds.contains(x, y)) {
            return ToggleTarget.NONE;
        }
        if (y < bounds.y + 3 || y > bounds.y + bounds.height - 3) {
            return ToggleTarget.NONE;
        }
        int outputX = bounds.x + bounds.width - 10 - TOGGLE_ICON_SIZE;
        int viewX = outputX - TOGGLE_ICON_SIZE - TOGGLE_GAP;
        if (x >= outputX && x <= outputX + TOGGLE_ICON_SIZE) {
            return ToggleTarget.OUTPUT;
        }
        if (x >= viewX && x <= viewX + TOGGLE_ICON_SIZE) {
            return ToggleTarget.VIEW;
        }
        return ToggleTarget.NONE;
    }

    private static void toggleOutlinerVisibility(Engine engine, Engine.SceneItemRef ref, ToggleTarget target) {
        if (ref == null || ref.type == Engine.SceneItemType.WORLD || target == ToggleTarget.NONE) {
            return;
        }
        Engine.SceneItemState state = stateForRef(engine, ref);
        if (state == null) {
            return;
        }
        engine.applySceneEdit("Změna viditelnosti položky", () -> {
            if (target == ToggleTarget.VIEW) {
                state.visibleInView = !state.visibleInView;
                if (ref.type != Engine.SceneItemType.FORCE_FIELD) {
                    engine.applySceneVisibility(false);
                }
            } else if (target == ToggleTarget.OUTPUT) {
                state.visibleInOutput = !state.visibleInOutput;
            }
        });
        refreshSceneOutliner(engine);
        rebuildSceneDetailsPanel(engine);
    }

    private enum ToggleTarget {
        NONE,
        VIEW,
        OUTPUT
    }

    private static final class SceneOutlinerMouseHandler extends MouseAdapter {
        private final Engine engine;

        private SceneOutlinerMouseHandler(Engine engine) {
            this.engine = engine;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            handleToggleClick(e);
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            // Selection nechávám na Swingu; toggle řeším už při stisku.
        }

        @Override
        public void mouseMoved(MouseEvent e) {
            if (engine.sceneOutlinerList == null || e == null) {
                return;
            }
            int index = engine.sceneOutlinerList.locationToIndex(e.getPoint());
            ToggleTarget target = index < 0 ? ToggleTarget.NONE
                    : resolveToggleTarget(engine.sceneOutlinerList, index, e.getX(), e.getY());
            engine.sceneOutlinerList.setCursor(target == ToggleTarget.NONE
                    ? java.awt.Cursor.getDefaultCursor()
                    : java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (engine.sceneOutlinerList != null) {
                engine.sceneOutlinerList.setCursor(java.awt.Cursor.getDefaultCursor());
            }
        }

        private void handleToggleClick(MouseEvent e) {
            if (engine.sceneOutlinerList == null || e == null || !SwingUtilities.isLeftMouseButton(e)) {
                return;
            }
            int index = engine.sceneOutlinerList.locationToIndex(e.getPoint());
            if (index < 0 || index >= engine.sceneOutlinerItems.size()) {
                return;
            }
            ToggleTarget target = resolveToggleTarget(engine.sceneOutlinerList, index, e.getX(), e.getY());
            if (target == ToggleTarget.NONE) {
                return;
            }
            Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(index);
            if (ref.type == Engine.SceneItemType.WORLD) {
                return;
            }
            engine.sceneOutlinerList.setSelectedIndex(index);
            toggleOutlinerVisibility(engine, ref, target);
            e.consume();
        }
    }

    private static final class SceneOutlinerCellRenderer extends JPanel implements javax.swing.ListCellRenderer<Engine.SceneItemRef> {
        private final Engine engine;
        private final JLabel iconLabel;
        private final JLabel nameLabel;
        private final JLabel metaLabel;
        private final JLabel viewLabel;
        private final JLabel outputLabel;

        private SceneOutlinerCellRenderer(Engine engine) {
            this.engine = engine;
            setLayout(new BorderLayout(6, 0));
            setBorder(new EmptyBorder(2, 7, 2, 7));
            setOpaque(true);

            iconLabel = new JLabel();
            iconLabel.setBorder(new EmptyBorder(0, 0, 0, 1));
            add(iconLabel, BorderLayout.WEST);

            JPanel textPanel = new JPanel(new BorderLayout(6, 0));
            textPanel.setOpaque(false);

            nameLabel = new JLabel();
            nameLabel.setFont(nameLabel.getFont().deriveFont(Font.PLAIN, 13.0f));
            metaLabel = new JLabel();
            metaLabel.setFont(metaLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
            metaLabel.setHorizontalAlignment(JLabel.RIGHT);

            textPanel.add(nameLabel, BorderLayout.WEST);
            textPanel.add(metaLabel, BorderLayout.EAST);
            add(textPanel, BorderLayout.CENTER);

            JPanel togglePanel = new JPanel(new BorderLayout(TOGGLE_GAP, 0));
            togglePanel.setOpaque(false);
            togglePanel.setBorder(new EmptyBorder(0, 6, 0, 0));

            JPanel toggleRow = new JPanel();
            toggleRow.setOpaque(false);
            toggleRow.setLayout(new javax.swing.BoxLayout(toggleRow, javax.swing.BoxLayout.X_AXIS));

            viewLabel = new JLabel();
            outputLabel = new JLabel();
            toggleRow.add(viewLabel);
            toggleRow.add(Box.createRigidArea(new Dimension(TOGGLE_GAP, 0)));
            toggleRow.add(outputLabel);
            togglePanel.add(toggleRow, BorderLayout.CENTER);
            add(togglePanel, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends Engine.SceneItemRef> list,
                Engine.SceneItemRef value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {
            Engine.SceneItemState state = stateForRef(engine, value);
            boolean viewVisible = state == null || state.visibleInView;
            boolean outputVisible = state == null || state.visibleInOutput;

            setBackground(isSelected ? UiTheme.SELECTION_BG_SOFT : UiTheme.PANEL_INSET);
            setBorder(isSelected
                    ? BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, 2, 0, 0, UiTheme.ACCENT),
                            new EmptyBorder(2, 6, 2, 7))
                    : new EmptyBorder(2, 7, 2, 7));

            nameLabel.setText(outlinerPrimaryLabel(engine, value));
            nameLabel.setForeground(isSelected ? UiTheme.TEXT_PRIMARY
                    : (viewVisible || outputVisible ? UiTheme.TEXT_PRIMARY : UiTheme.TEXT_MUTED));
            metaLabel.setText(outlinerSecondaryLabel(engine, value));
            metaLabel.setForeground(isSelected ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT_HINT);

            iconLabel.setIcon(new SceneBrowserIcon(iconFor(value), colorFor(value), 18));
            boolean showToggles = value != null && value.type != Engine.SceneItemType.WORLD;
            viewLabel.setVisible(showToggles);
            outputLabel.setVisible(showToggles);
            if (showToggles) {
                viewLabel.setIcon(new SceneBrowserIcon(viewVisible ? Glyph.VIEW_ON : Glyph.VIEW_OFF,
                        viewVisible ? UiTheme.ACCENT_GLOW : UiTheme.TEXT_HINT, TOGGLE_ICON_SIZE));
                outputLabel.setIcon(new SceneBrowserIcon(outputVisible ? Glyph.OUTPUT_ON : Glyph.OUTPUT_OFF,
                        outputVisible ? UiTheme.ACCENT_GLOW : UiTheme.TEXT_HINT, TOGGLE_ICON_SIZE));
                viewLabel.setToolTipText(viewVisible ? "Skrýt ve viewportu" : "Zobrazit ve viewportu");
                outputLabel.setToolTipText(outputVisible ? "Skrýt ve výstupu" : "Zobrazit ve výstupu");
            } else {
                viewLabel.setToolTipText(null);
                outputLabel.setToolTipText(null);
            }
            return this;
        }

        private static Glyph iconFor(Engine.SceneItemRef ref) {
            if (ref == null) {
                return Glyph.ENTITY;
            }
            return switch (ref.type) {
                case ENTITY -> Glyph.ENTITY;
                case LIGHT -> Glyph.LIGHT;
                case FORCE_FIELD -> Glyph.FORCE;
                case WORLD -> Glyph.WORLD;
            };
        }

        private Color colorFor(Engine.SceneItemRef ref) {
            if (ref == null) {
                return UiTheme.ACCENT_GLOW;
            }
            return switch (ref.type) {
                case ENTITY -> UiTheme.ACCENT_GLOW;
                case LIGHT -> colorForLight(ref.light);
                case FORCE_FIELD -> new Color(134, 198, 255);
                case WORLD -> colorForWorld(engine);
            };
        }
    }

    private enum Glyph {
        ENTITY,
        LIGHT,
        FORCE,
        WORLD,
        VIEW_ON,
        VIEW_OFF,
        OUTPUT_ON,
        OUTPUT_OFF
    }

    private static final class SceneBrowserIcon implements Icon {
        private final Glyph glyph;
        private final Color color;
        private final int size;

        private SceneBrowserIcon(Glyph glyph, Color color, int size) {
            this.glyph = glyph;
            this.color = color;
            this.size = Math.max(12, size);
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.setStroke(new java.awt.BasicStroke(1.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
            int s = size;
            int cx = x + s / 2;
            int cy = y + s / 2;
            switch (glyph) {
                case ENTITY -> {
                    g2.drawRoundRect(x + 2, y + 3, s - 5, s - 6, 4, 4);
                    g2.drawLine(x + 5, y + 5, x + s - 5, y + 5);
                }
                case LIGHT -> {
                    g2.drawOval(x + 4, y + 3, s - 8, s - 8);
                    g2.drawLine(cx, y + 1, cx, y + 4);
                    g2.drawLine(cx, y + s - 4, cx, y + s - 1);
                    g2.drawLine(x + 1, cy, x + 4, cy);
                    g2.drawLine(x + s - 4, cy, x + s - 1, cy);
                }
                case FORCE -> {
                    g2.drawArc(x + 2, y + 2, s - 6, s - 6, 30, 240);
                    g2.drawLine(x + s - 5, y + 5, x + s - 3, y + 2);
                    g2.drawLine(x + s - 5, y + 5, x + s - 1, y + 6);
                }
                case WORLD -> {
                    g2.drawOval(cx - 4, cy - 4, 8, 8);
                    for (int i = 0; i < 8; i++) {
                        double angle = Math.PI * 0.25 * i;
                        int x1 = cx + (int) Math.round(Math.cos(angle) * 6);
                        int y1 = cy + (int) Math.round(Math.sin(angle) * 6);
                        int x2 = cx + (int) Math.round(Math.cos(angle) * 8);
                        int y2 = cy + (int) Math.round(Math.sin(angle) * 8);
                        g2.drawLine(x1, y1, x2, y2);
                    }
                }
                case VIEW_ON, VIEW_OFF -> {
                    g2.drawArc(x + 2, cy - 4, s - 4, 8, 0, 180);
                    g2.drawArc(x + 2, cy - 4, s - 4, 8, 0, -180);
                    g2.drawOval(cx - 2, cy - 2, 4, 4);
                    if (glyph == Glyph.VIEW_OFF) {
                        g2.drawLine(x + 3, y + s - 3, x + s - 3, y + 3);
                    }
                }
                case OUTPUT_ON, OUTPUT_OFF -> {
                    g2.drawRoundRect(x + 2, y + 4, s - 4, s - 7, 3, 3);
                    g2.drawOval(x + 5, y + 6, s - 10, s - 11);
                    if (glyph == Glyph.OUTPUT_OFF) {
                        g2.drawLine(x + 3, y + s - 3, x + s - 3, y + 3);
                    }
                }
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

}
