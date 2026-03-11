package engine.core;

import engine.ui.UiStrings;

import javax.swing.JPanel;

/**
 * Tady držím sdílenou přípravu editorových UI panelů a menu.
 */
final class EngineUiSetup {

    private EngineUiSetup() {
    }

    static void setupRightPanel(Engine engine) {
        JPanel renderTab = EngineRenderPanels.buildRenderTab(engine);
        JPanel sceneTab = EngineScenePanels.buildSceneTab(engine);
        JPanel worldTab = EngineScenePanels.buildWorldTab(engine);
        JPanel inputTab = EngineScenePanels.buildInputTab(engine);
        JPanel objectTab = EngineScenePanels.buildObjectTab(engine);
        JPanel outputTab = EngineRenderPanels.buildOutputTab(engine);

        renderTab.revalidate();
        sceneTab.revalidate();
        worldTab.revalidate();
        inputTab.revalidate();
        objectTab.revalidate();
        outputTab.revalidate();
        engine.refreshObjectInspectorValues();
        engine.refreshSceneOutliner();
        engine.rebuildSceneDetailsPanel();
        engine.refreshTimelineUi();
        engine.rebuildMaterialDock();
    }

    static void setupViewportContextMenu(Engine engine) {
        if (engine.window == null) {
            return;
        }
        engine.window.clearContextMenuItems();
        for (String type : EngineSceneActions.basicPrimitiveTypes()) {
            engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_PREFIX + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type));
        }
        for (String type : EngineSceneActions.featuredPrimitiveTypes()) {
            engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_PREFIX + EngineSceneActions.primitiveLabel(type),
                    () -> engine.addPrimitive(type));
        }
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_WATER_EMITTER, engine::addWaterEmitter);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.IMPORT_MODEL_SCENE, engine::importModelOrSceneFromDialog);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_POINT_LIGHT, engine::addPointLight);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_AREA_LIGHT, engine::addAreaLight);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_CONE_LIGHT, engine::addConeLight);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_VECTOR_FORCE, engine::addVectorForceField);
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_POINT_ATTRACTOR, () -> engine.addPointForceField(true));
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_POINT_REPULSOR, () -> engine.addPointForceField(false));
        engine.window.addContextMenuItem(UiStrings.ContextMenu.ADD_TURBULENCE, engine::addTurbulenceForceField);
    }
}
