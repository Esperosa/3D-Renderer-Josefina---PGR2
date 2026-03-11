package engine.core;

import engine.material.MaterialPreviewRenderer;

/**
 * Tady držím čistě UI stav materiálového workspace.
 */
final class MaterialDockViewState {
    private boolean lookdevPanelExpanded = true;
    private final MaterialPreviewRenderer.Settings previewSettings = new MaterialPreviewRenderer.Settings();

    boolean isLookdevPanelExpanded() {
        return lookdevPanelExpanded;
    }

    void setLookdevPanelExpanded(boolean lookdevPanelExpanded) {
        this.lookdevPanelExpanded = lookdevPanelExpanded;
    }

    MaterialPreviewRenderer.Settings previewSettings() {
        return previewSettings;
    }
}
