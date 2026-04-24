package engine.core;

import javax.swing.JPanel;

/**
 * Represents sdílenou přípravu editorových UI panelů a menu.
 */
final class EngineUiSetup {

    private EngineUiSetup() {
    }

    static void setupRightPanel(Engine engine) {
        JPanel sceneBrowser = EngineScenePanels.buildSceneBrowser(engine);
        JPanel renderTab = EngineRenderPanels.buildRenderTab(engine);
        JPanel worldTab = EngineScenePanels.buildWorldTab(engine);
        JPanel inputTab = EngineScenePanels.buildInputTab(engine);
        JPanel itemTab = EngineScenePanels.buildItemTab(engine);
        JPanel outputTab = EngineRenderPanels.buildOutputTab(engine);

        sceneBrowser.revalidate();
        renderTab.revalidate();
        worldTab.revalidate();
        inputTab.revalidate();
        itemTab.revalidate();
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
        engine.window.setContextMenuCallbacks(
                () -> handleViewportContextMenuWillShow(engine),
                () -> restoreViewportContextMenuCapture(engine),
                () -> restoreViewportContextMenuCapture(engine));
        engine.window.clearContextMenuItems();
        java.util.List<EngineSceneActions.SceneAddGroup> groups = EngineSceneActions.sceneAddGroups();
        for (int groupIndex = 0; groupIndex < groups.size(); groupIndex++) {
            if (groupIndex > 0) {
                engine.window.addContextMenuSeparator();
            }
            for (EngineSceneActions.SceneAddAction action : groups.get(groupIndex).actions()) {
                engine.window.addContextMenuItem(action.label(), () -> action.invoke(engine));
            }
        }
    }

    private static void handleViewportContextMenuWillShow(Engine engine) {
        boolean shouldRecapture = engine != null
                && engine.navigationPreset == Engine.NavigationPreset.FPS
                && engine.mouseCaptured;
        if (engine == null) {
            return;
        }
        engine.viewportContextMenuRecapturePending = shouldRecapture;
        if (shouldRecapture) {
            engine.releaseMouseCapture();
        }
    }

    private static void restoreViewportContextMenuCapture(Engine engine) {
        if (engine == null || !engine.viewportContextMenuRecapturePending) {
            return;
        }
        engine.viewportContextMenuRecapturePending = false;
        if (engine.navigationPreset == Engine.NavigationPreset.FPS && !engine.mouseCaptured) {
            engine.captureMouse();
        }
    }
}
