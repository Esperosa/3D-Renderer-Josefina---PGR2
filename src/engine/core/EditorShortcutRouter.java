package engine.core;

import engine.math.Vec3;
import engine.util.UiBuilder;

import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

final class EditorShortcutRouter implements KeyEventDispatcher {
    private final Engine engine;
    private boolean installed;

    EditorShortcutRouter(Engine engine) {
        this.engine = engine;
    }

    void install() {
        if (installed) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
        installed = true;
    }

    void uninstall() {
        if (!installed) {
            return;
        }
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        installed = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event == null || event.getID() != KeyEvent.KEY_PRESSED || engine == null || engine.window == null) {
            return false;
        }
        Component focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (!belongsToEditorWindow(focusOwner)) {
            return false;
        }
        KeyStroke stroke = KeyStroke.getKeyStrokeForEvent(event);
        if (!routeShortcut(engine, focusOwner, stroke)) {
            return false;
        }
        event.consume();
        return true;
    }

    static boolean routeShortcut(Engine engine, Component focusOwner, KeyStroke stroke) {
        if (engine == null) {
            return false;
        }
        String actionId = EditorKeymap.resolveActionId(stroke);
        if (actionId == null) {
            return false;
        }

        EditorFocusContext context = EditorFocusContext.resolve(focusOwner, engine);
        if (context == EditorFocusContext.TEXT_INPUT) {
            return handleTextInputShortcut(focusOwner, actionId);
        }

        if (invokeComponentAction(focusOwner, actionId)) {
            return true;
        }

        if (EditorActionId.UNDO.equals(actionId)) {
            engine.undoLastAction();
            return true;
        }
        if (EditorActionId.REDO.equals(actionId)) {
            engine.redoLastAction();
            return true;
        }
        if (EditorActionId.CANCEL.equals(actionId)) {
            return cancelTransientEditorAction(engine);
        }
        if (EditorActionId.FRAME_ALL.equals(actionId) && context == EditorFocusContext.TIMELINE) {
            EngineTimelineController.setCurrentFrame(engine, engine.timelineStartFrame);
            return true;
        }
        if (EditorActionId.DELETE.equals(actionId)
                && (context == EditorFocusContext.VIEWPORT
                || context == EditorFocusContext.OUTLINER
                || context == EditorFocusContext.GENERIC_EDITOR)) {
            return engine.deleteCurrentSelection();
        }
        if (EditorActionId.FRAME_SELECTED.equals(actionId)
                && (context == EditorFocusContext.VIEWPORT
                || context == EditorFocusContext.OUTLINER
                || context == EditorFocusContext.GENERIC_EDITOR)) {
            return frameCurrentSelection(engine);
        }
        return false;
    }

    private boolean belongsToEditorWindow(Component focusOwner) {
        if (focusOwner == null || engine == null || engine.window == null) {
            return false;
        }
        if (focusOwner == engine.window.getCanvas()) {
            return true;
        }
        Window owner = SwingUtilities.getWindowAncestor(focusOwner);
        return owner == engine.window.getFrame();
    }

    private static boolean handleTextInputShortcut(Component focusOwner, String actionId) {
        if (EditorActionId.UNDO.equals(actionId)) {
            return UiBuilder.undoTextChange(focusOwner);
        }
        if (EditorActionId.REDO.equals(actionId)) {
            return UiBuilder.redoTextChange(focusOwner);
        }
        return false;
    }

    private static boolean invokeComponentAction(Component focusOwner, String actionId) {
        for (Component cursor = focusOwner; cursor != null; cursor = cursor.getParent()) {
            if (!(cursor instanceof JComponent component)) {
                continue;
            }
            Action action = component.getActionMap().get(actionId);
            if (action == null || !action.isEnabled()) {
                continue;
            }
            action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, actionId));
            return true;
        }
        return false;
    }

    static boolean cancelTransientEditorAction(Engine engine) {
        if (engine == null) {
            return false;
        }
        if (engine.mouseCaptured) {
            engine.releaseMouseCapture();
            return true;
        }
        if (engine.addMenuActive) {
            engine.addMenuActive = false;
            return true;
        }
        if (engine.transformMode != Engine.TransformMode.NONE || engine.gizmoDragActive) {
            engine.revertSceneGesture();
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            engine.draggingSelectedObject = false;
            return true;
        }
        if (engine.objectFocusMode || engine.draggingSelectedObject) {
            engine.revertSceneGesture();
            engine.objectFocusMode = false;
            engine.draggingSelectedObject = false;
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            return true;
        }
        return false;
    }

    private static boolean frameCurrentSelection(Engine engine) {
        if (engine == null || engine.cameraController == null) {
            return false;
        }
        Vec3 pivot = EngineViewportOverlay.selectionPivotPosition(engine);
        if (pivot == null) {
            return false;
        }
        engine.cameraController.frameTarget(pivot);
        return true;
    }
}
