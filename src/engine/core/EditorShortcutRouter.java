package engine.core;

import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import engine.math.Vec3;
import engine.util.UiBuilder;

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
            if (EditorActionId.CANCEL.equals(actionId)) {
                engine.focusCanvas();
                return cancelTransientEditorAction(engine);
            }
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
        if (context == EditorFocusContext.TIMELINE && handleTimelineShortcut(engine, actionId)) {
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

    private static boolean handleTimelineShortcut(Engine engine, String actionId) {
        if (EditorActionId.TIMELINE_PLAY_PAUSE.equals(actionId)) {
            engine.toggleAnimationPlayback();
            return true;
        }
        if (EditorActionId.TIMELINE_PREVIOUS_FRAME.equals(actionId)) {
            engine.stepTimelineFrame(-1);
            return true;
        }
        if (EditorActionId.TIMELINE_NEXT_FRAME.equals(actionId)) {
            engine.stepTimelineFrame(1);
            return true;
        }
        if (EditorActionId.TIMELINE_ADD_KEY.equals(actionId)) {
            engine.addTimelineKeyForSelection();
            return true;
        }
        if (EditorActionId.TIMELINE_REMOVE_KEY.equals(actionId)) {
            engine.removeTimelineKeyForSelection();
            return true;
        }
        if (EditorActionId.TIMELINE_ADD_ALL_KEYS.equals(actionId)) {
            EngineTimelineController.addKeyForAllAnimatables(engine);
            return true;
        }
        if (EditorActionId.TIMELINE_ADD_RELEASE_KEY.equals(actionId)) {
            engine.addTimelineReleaseKeyForSelection();
            return true;
        }
        return false;
    }

    private boolean belongsToEditorWindow(Component focusOwner) {
        if (engine == null || engine.window == null) {
            return false;
        }
        Window editorWindow = engine.window.getFrame();
        KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Window activeWindow = kfm.getActiveWindow();
        Window focusedWindow = kfm.getFocusedWindow();
        if (focusOwner == null) {
            return activeWindow == editorWindow || focusedWindow == editorWindow;
        }
        if (focusOwner == engine.window.getCanvas()) {
            return true;
        }
        Window owner = SwingUtilities.getWindowAncestor(focusOwner);
        return owner == editorWindow
                || activeWindow == editorWindow
                || focusedWindow == editorWindow;
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
        long now = System.nanoTime();
        if (now <= engine.escapeExitArmedUntilNanos) {
            engine.escapeExitArmedUntilNanos = 0L;
            if (engine.window != null) {
                engine.window.requestClose();
                return true;
            }
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
        engine.escapeExitArmedUntilNanos = now + 2_200_000_000L;
        System.out.println("ESC armed: press Escape again to exit.");
        return true;
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
