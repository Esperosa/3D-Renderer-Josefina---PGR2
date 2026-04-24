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
    private static final KeyStroke TIMELINE_PLAY_PAUSE = KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0);
    private static final KeyStroke TIMELINE_PREVIOUS_FRAME = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0);
    private static final KeyStroke TIMELINE_NEXT_FRAME = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
    private static final KeyStroke TIMELINE_ADD_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, 0);
    private static final KeyStroke TIMELINE_REMOVE_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK);
    private static final KeyStroke TIMELINE_ADD_ALL_KEYS = KeyStroke.getKeyStroke(KeyEvent.VK_INSERT, InputEvent.CTRL_DOWN_MASK);
    private static final KeyStroke TIMELINE_ADD_KEY_ALT = KeyStroke.getKeyStroke(KeyEvent.VK_K, 0);
    private static final KeyStroke TIMELINE_ADD_RELEASE_KEY = KeyStroke.getKeyStroke(KeyEvent.VK_K, InputEvent.SHIFT_DOWN_MASK);

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
        if (TIMELINE_PLAY_PAUSE.equals(stroke)) {
            return EditorActionId.TIMELINE_PLAY_PAUSE;
        }
        if (TIMELINE_PREVIOUS_FRAME.equals(stroke)) {
            return EditorActionId.TIMELINE_PREVIOUS_FRAME;
        }
        if (TIMELINE_NEXT_FRAME.equals(stroke)) {
            return EditorActionId.TIMELINE_NEXT_FRAME;
        }
        if (TIMELINE_ADD_KEY.equals(stroke) || TIMELINE_ADD_KEY_ALT.equals(stroke)) {
            return EditorActionId.TIMELINE_ADD_KEY;
        }
        if (TIMELINE_REMOVE_KEY.equals(stroke)) {
            return EditorActionId.TIMELINE_REMOVE_KEY;
        }
        if (TIMELINE_ADD_ALL_KEYS.equals(stroke)) {
            return EditorActionId.TIMELINE_ADD_ALL_KEYS;
        }
        if (TIMELINE_ADD_RELEASE_KEY.equals(stroke)) {
            return EditorActionId.TIMELINE_ADD_RELEASE_KEY;
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
        if (EditorActionId.TIMELINE_PLAY_PAUSE.equals(actionId)) {
            return "Space";
        }
        if (EditorActionId.TIMELINE_PREVIOUS_FRAME.equals(actionId)) {
            return "Left";
        }
        if (EditorActionId.TIMELINE_NEXT_FRAME.equals(actionId)) {
            return "Right";
        }
        if (EditorActionId.TIMELINE_ADD_KEY.equals(actionId)) {
            return "Insert / K";
        }
        if (EditorActionId.TIMELINE_REMOVE_KEY.equals(actionId)) {
            return "Shift+Insert";
        }
        if (EditorActionId.TIMELINE_ADD_ALL_KEYS.equals(actionId)) {
            return "Ctrl+Insert";
        }
        if (EditorActionId.TIMELINE_ADD_RELEASE_KEY.equals(actionId)) {
            return "Shift+K";
        }
        return "";
    }
}
