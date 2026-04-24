package engine.core.history;

/**
 * Defines základní editorovou operaci zapisovanou do historie.
 */
public interface EditorCommand {

    String getLabel();

    void undo();

    void redo();

    default boolean canMerge(EditorCommand next) {
        return false;
    }

    default EditorCommand merge(EditorCommand next) {
        return next;
    }
}
