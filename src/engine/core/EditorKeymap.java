package engine.core;

import javax.swing.KeyStroke;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

final class EditorKeymap {
    private static final KeyStroke UNDO = KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke REDO = KeyStroke.getKeyStroke(KeyEvent.VK_Z,
            InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
    private static final KeyStroke REDO_ALT = KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke DELETE = KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0);
    private static final KeyStroke DUPLICATE = KeyStroke.getKeyStroke(KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke FRAME_SELECTED = KeyStroke.getKeyStroke(KeyEvent.VK_F, 0);
    private static final KeyStroke CANCEL = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
    private static final KeyStroke FRAME_ALL = KeyStroke.getKeyStroke(KeyEvent.VK_HOME, 0);

    private EditorKeymap() {
    }

    static String resolveActionId(KeyStroke stroke) {
        if (stroke == null) {
            return null;
        }
        if (UNDO.equals(stroke)) {
            return EditorActionId.UNDO;
        }
        if (REDO.equals(stroke) || REDO_ALT.equals(stroke)) {
            return EditorActionId.REDO;
        }
        if (DELETE.equals(stroke)) {
            return EditorActionId.DELETE;
        }
        if (DUPLICATE.equals(stroke)) {
            return EditorActionId.DUPLICATE;
        }
        if (FRAME_SELECTED.equals(stroke)) {
            return EditorActionId.FRAME_SELECTED;
        }
        if (CANCEL.equals(stroke)) {
            return EditorActionId.CANCEL;
        }
        if (FRAME_ALL.equals(stroke)) {
            return EditorActionId.FRAME_ALL;
        }
        return null;
    }

    static String shortcutLabel(String actionId) {
        if (EditorActionId.UNDO.equals(actionId)) {
            return "Ctrl+Z";
        }
        if (EditorActionId.REDO.equals(actionId)) {
            return "Ctrl+Shift+Z / Ctrl+Y";
        }
        if (EditorActionId.DELETE.equals(actionId)) {
            return "Delete";
        }
        if (EditorActionId.DUPLICATE.equals(actionId)) {
            return "Ctrl+D";
        }
        if (EditorActionId.FRAME_SELECTED.equals(actionId)) {
            return "F";
        }
        if (EditorActionId.FRAME_ALL.equals(actionId)) {
            return "Home";
        }
        if (EditorActionId.CANCEL.equals(actionId)) {
            return "Escape";
        }
        return "";
    }
}
