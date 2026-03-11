package engine.core;

import java.awt.Component;
import javax.swing.JComponent;
import javax.swing.text.JTextComponent;

enum EditorFocusContext {
    VIEWPORT,
    OUTLINER,
    MATERIAL_WORKSPACE,
    MATERIAL_GRAPH,
    TIMELINE,
    TEXT_INPUT,
    GENERIC_EDITOR;

    private static final String CLIENT_PROPERTY = "editor.focusContext";

    static void mark(JComponent component, EditorFocusContext context) {
        if (component == null) {
            return;
        }
        component.putClientProperty(CLIENT_PROPERTY, context);
    }

    static EditorFocusContext resolve(Component focusOwner, Engine engine) {
        if (focusOwner instanceof JTextComponent textComponent && textComponent.isEditable() && textComponent.isEnabled()) {
            return TEXT_INPUT;
        }
        if (engine != null && engine.window != null && focusOwner == engine.window.getCanvas()) {
            return VIEWPORT;
        }
        for (Component cursor = focusOwner; cursor != null; cursor = cursor.getParent()) {
            if (!(cursor instanceof JComponent component)) {
                continue;
            }
            Object value = component.getClientProperty(CLIENT_PROPERTY);
            if (value instanceof EditorFocusContext context) {
                return context;
            }
        }
        return GENERIC_EDITOR;
    }
}
