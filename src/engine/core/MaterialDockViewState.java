package engine.core;

import engine.material.MaterialPreviewRenderer;
import engine.ui.UiTheme;

/**
 * Represents čistě UI stav materiálového workspace.
 */
final class MaterialDockViewState {
    enum WorkspaceMode {
        BALANCED(true, true),
        GRAPH_FOCUS(false, true),
        LOOKDEV_FOCUS(true, false);

        private final boolean showsLookdev;
        private final boolean showsInspector;

        WorkspaceMode(boolean showsLookdev, boolean showsInspector) {
            this.showsLookdev = showsLookdev;
            this.showsInspector = showsInspector;
        }

        boolean showsLookdev() {
            return showsLookdev;
        }

        boolean showsInspector() {
            return showsInspector;
        }
    }

    private boolean lookdevPanelExpanded = true;
    private final MaterialPreviewRenderer.Settings previewSettings = new MaterialPreviewRenderer.Settings();
    private WorkspaceMode workspaceMode = WorkspaceMode.BALANCED;
    private int inspectorWidth = UiTheme.MATERIAL_WORKSPACE_INSPECTOR_WIDTH;

    boolean isLookdevPanelExpanded() {
        return lookdevPanelExpanded;
    }

    void setLookdevPanelExpanded(boolean lookdevPanelExpanded) {
        this.lookdevPanelExpanded = lookdevPanelExpanded;
    }

    MaterialPreviewRenderer.Settings previewSettings() {
        return previewSettings;
    }

    WorkspaceMode workspaceMode() {
        return workspaceMode;
    }

    void setWorkspaceMode(WorkspaceMode workspaceMode) {
        this.workspaceMode = workspaceMode == null ? WorkspaceMode.BALANCED : workspaceMode;
    }

    int inspectorWidth() {
        return inspectorWidth;
    }

    void setInspectorWidth(int inspectorWidth) {
        this.inspectorWidth = Math.max(UiTheme.MATERIAL_WORKSPACE_INSPECTOR_MIN_WIDTH,
                Math.min(840, inspectorWidth));
    }
}
