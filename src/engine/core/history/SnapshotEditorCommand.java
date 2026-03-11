package engine.core.history;

/**
 * Tady držím jednoduchý příkaz založený na dvojici undo/redo callbacků.
 */
public final class SnapshotEditorCommand implements EditorCommand {
    private final String label;
    private final Runnable undoAction;
    private final Runnable redoAction;

    public SnapshotEditorCommand(String label, Runnable undoAction, Runnable redoAction) {
        this.label = label == null || label.isBlank() ? "Editorová akce" : label;
        this.undoAction = undoAction == null ? () -> { } : undoAction;
        this.redoAction = redoAction == null ? () -> { } : redoAction;
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public void undo() {
        undoAction.run();
    }

    @Override
    public void redo() {
        redoAction.run();
    }
}
