package engine.core;

import javax.swing.JPanel;

/**
 * Tady držím tenkou vstupní vrstvu pro stavbu panelů vykreslení.
 */
final class EngineRenderPanels {

    private EngineRenderPanels() {
    }

    static JPanel buildRenderTab(Engine engine) {
        return EngineViewportRenderTabBuilder.build(engine);
    }

    static JPanel buildOutputTab(Engine engine) {
        return EngineOutputTabBuilder.build(engine);
    }
}
