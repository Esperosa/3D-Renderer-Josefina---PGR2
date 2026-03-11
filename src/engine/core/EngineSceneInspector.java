package engine.core;

import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialGraphEvaluator;
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
import engine.ui.UiStrings;
import engine.sim.water.WaterEmitter;
import engine.sim.water.WaterEmitterEntity;
import engine.util.UiBuilder;

import java.awt.Dimension;
import javax.swing.Box;

final class EngineSceneInspector {

    private EngineSceneInspector() {
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

    static void applyOutlinerSelectionFromList(Engine engine) {
        if (engine.sceneOutlinerList == null) {
            return;
        }
        int idx = engine.sceneOutlinerList.getSelectedIndex();
        if (idx < 0 || idx >= engine.sceneOutlinerItems.size()) {
            engine.selectedEntity = null;
            engine.selectedLight = null;
            engine.selectedForceField = null;
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            engine.refreshObjectInspectorValues();
            rebuildSceneDetailsPanel(engine);
            return;
        }
        Engine.SceneItemRef ref = engine.sceneOutlinerItems.get(idx);
        if (ref.type == Engine.SceneItemType.ENTITY && ref.entity != null) {
            engine.setCurrentEntitySelection(ref.entity);
            engine.refreshObjectInspectorValues();
        } else if (ref.type == Engine.SceneItemType.LIGHT && ref.light != null) {
            engine.setCurrentLightSelection(ref.light);
            engine.refreshObjectInspectorValues();
        } else if (ref.type == Engine.SceneItemType.FORCE_FIELD && ref.forceField != null) {
            engine.setCurrentForceFieldSelection(ref.forceField);
            engine.refreshObjectInspectorValues();
        } else if (ref.type == Engine.SceneItemType.WORLD) {
            engine.selectedEntity = null;
            engine.selectedLight = null;
            engine.selectedForceField = null;
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            engine.refreshObjectInspectorValues();
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
            engine.sceneOutlinerItems.add(ref);
            Engine.SceneItemState state = engine.stateFor(entity);
            String suffix = (state.visibleInView ? "" : " [V vyp]") + (state.visibleInOutput ? "" : " [O vyp]");
            engine.sceneOutlinerModel.addElement("[OBJ] " + entity.getName() + suffix);
        }
        for (Light light : engine.scene.getLights()) {
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.LIGHT;
            ref.light = light;
            engine.sceneOutlinerItems.add(ref);
            Engine.SceneItemState state = engine.stateFor(light);
            String suffix = (state.visibleInView ? "" : " [V vyp]") + (state.visibleInOutput ? "" : " [O vyp]");
            engine.sceneOutlinerModel.addElement("[SVT] " + engine.getLightName(light) + " (" + lightTypeName(light) + ")" + suffix);
        }
        for (Engine.ForceField field : engine.forceFields) {
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.FORCE_FIELD;
            ref.forceField = field;
            engine.sceneOutlinerItems.add(ref);
            Engine.SceneItemState state = engine.stateFor(field);
            String suffix = (state.visibleInView ? "" : " [V vyp]") + (state.visibleInOutput ? "" : " [O vyp]");
            engine.sceneOutlinerModel.addElement("[SÍLA] " + field.name + " (" + field.type + ")" + suffix);
        }

        Engine.SceneItemRef worldRef = new Engine.SceneItemRef();
        worldRef.type = Engine.SceneItemType.WORLD;
        engine.sceneOutlinerItems.add(worldRef);
        engine.sceneOutlinerModel.addElement("[SVĚT] Světlo prostředí");

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
            engine.sceneOutlinerList.ensureIndexIsVisible(selectIndex);
        } else {
            engine.sceneOutlinerList.clearSelection();
        }
        engine.suppressSceneOutlinerSelectionEvent = false;
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
        engine.suppressSceneDetailRebuild = true;
        try {
            engine.sceneDetailsPanel.removeAll();
            Engine.SceneItemRef ref = selectedOutlinerRef(engine);
            if (ref == null) {
                engine.sceneDetailsPanel.add(engine.sectionTitle(UiStrings.Common.SELECT_IN_OUTLINER));
                engine.sceneDetailsPanel.revalidate();
                engine.sceneDetailsPanel.repaint();
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
                    engine.sceneDetailsPanel.add(engine.sectionTitle("Světlo prostředí"));
                    engine.sceneDetailsPanel.add(engine.actionButton(UiStrings.Common.OPEN_WORLD_TAB,
                            () -> engine.window.selectRightTab("World")));
                    engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
                    engine.sceneDetailsPanel.add(engine.sectionTitle("Ambientní barva: "
                            + engine.formatTransformValue(engine.worldLightColor.x) + ", "
                            + engine.formatTransformValue(engine.worldLightColor.y) + ", "
                            + engine.formatTransformValue(engine.worldLightColor.z)));
                    engine.sceneDetailsPanel.add(engine.sectionTitle("Síla: " + engine.formatTransformValue(engine.worldLightStrength)));
                    break;
            }
            engine.sceneDetailsPanel.revalidate();
            engine.sceneDetailsPanel.repaint();
        } finally {
            engine.suppressSceneDetailRebuild = false;
        }
    }

    static void buildEntityDetails(Engine engine, Entity entity) {
        if (entity == null) {
            engine.sceneDetailsPanel.add(engine.sectionTitle("Objekt: žádný"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(entity);
        engine.sceneDetailsPanel.add(engine.sectionTitle("Objekt: " + entity.getName()));
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

        PhongMaterial material = ensurePhongMaterial(entity);
        engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Materiál: " + material.getName()));
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
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialGraphEvaluator.Result materialPreview = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        boolean surfaceConnected = output != null && graph.findInputLink(output.getId(), "surface") != null;
        boolean volumeConnected = output != null && graph.findInputLink(output.getId(), "volume") != null;
        String route = surfaceConnected && volumeConnected ? "Povrch + objem"
                : surfaceConnected ? "Povrch"
                : volumeConnected ? "Objem"
                : "Bez výstupu";
        engine.sceneDetailsPanel.add(UiBuilder.helperText("Detailní authoring a node graph patří do workspace Materiál. Tady zůstává jen rychlé shrnutí a přiřazení."));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Graf: " + graph.getNodes().size()
                + " uzlů / " + graph.getLinks().size() + " spojení"));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Výstup: " + route));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Lookdev: base "
                + engine.formatTransformValue(materialPreview.baseColor.x) + ", "
                + engine.formatTransformValue(materialPreview.baseColor.y) + ", "
                + engine.formatTransformValue(materialPreview.baseColor.z)
                + " | roughness " + engine.formatTransformValue(materialPreview.roughness)
                + " | metallic " + engine.formatTransformValue(materialPreview.metallic)));
        MaterialSupportMatrix.GraphSupport support = MaterialSupportMatrix.summarize(graph);
        engine.sceneDetailsPanel.add(engine.sectionTitle("Kompatibilita: " + support.compactSummary()
                + (MaterialGraphAuthoring.hasConnectedNormalPath(material) ? " | normála: kompatibilní větev aktivní" : "")));
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
            engine.sceneDetailsPanel.add(UiBuilder.helperText(FeatureMaturityNotes.SPRAY_PARTICLE_SYSTEM));
            engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
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
        engine.sceneDetailsPanel.add(engine.actionButton("Otevřít v panelu Objekt", () -> {
            engine.setCurrentEntitySelection(entity);
            engine.window.selectRightTab("Object");
            engine.refreshObjectInspectorValues();
            syncOutlinerSelectionToCurrentSelection(engine);
            rebuildSceneDetailsPanel(engine);
        }));
        engine.sceneDetailsPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.sceneDetailsPanel.add(engine.actionButton("Zaměřit výběr", () -> {
            engine.setCurrentEntitySelection(entity);
            engine.cameraController.frameTarget(entity.getTransform().getPosition());
            engine.camera.lookAt(entity.getTransform().getPosition());
            syncOutlinerSelectionToCurrentSelection(engine);
            rebuildSceneDetailsPanel(engine);
        }));
    }

    static void buildLightDetails(Engine engine, Light light) {
        if (light == null) {
            engine.sceneDetailsPanel.add(engine.sectionTitle("Světlo: žádné"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(light);
        engine.sceneDetailsPanel.add(engine.sectionTitle("Světlo: " + engine.getLightName(light)));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Typ: " + lightTypeName(light)));
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
            engine.sceneDetailsPanel.add(engine.sectionTitle("Síla: žádná"));
            return;
        }
        Engine.SceneItemState state = engine.stateFor(field);
        engine.sceneDetailsPanel.add(engine.sectionTitle("Síla: " + field.name));
        engine.sceneDetailsPanel.add(engine.sectionTitle("Typ: " + field.type));
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

    private static PhongMaterial ensurePhongMaterial(Entity entity) {
        if (entity.getMaterial() instanceof PhongMaterial) {
            return (PhongMaterial) entity.getMaterial();
        }
        Vec3 base = entity.getMaterial() != null ? entity.getMaterial().getBaseColor() : new Vec3(0.7, 0.7, 0.7);
        PhongMaterial mat = new PhongMaterial(base, 32.0);
        if (entity.getMaterial() != null) {
            mat.copyFrom(entity.getMaterial());
        }
        entity.setMaterial(mat);
        return mat;
    }

    private static WaterEmitter resolveWaterEmitter(Entity entity) {
        if (entity instanceof WaterEmitterEntity) {
            return ((WaterEmitterEntity) entity).getEmitter();
        }
        return null;
    }

}
