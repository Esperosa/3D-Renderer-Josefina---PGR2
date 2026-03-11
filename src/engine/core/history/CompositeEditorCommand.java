package engine.core.history;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tady seskupuju více dílčích příkazů do jednoho logického kroku.
 */
public final class CompositeEditorCommand implements EditorCommand {
    private final String label;
    private final List<EditorCommand> commands;

    public CompositeEditorCommand(String label, List<EditorCommand> commands) {
        this.label = label == null || label.isBlank() ? "Složená akce" : label;
        this.commands = commands == null ? List.of() : List.copyOf(commands);
    }

    public List<EditorCommand> commands() {
        return Collections.unmodifiableList(commands);
    }

    @Override
    public String getLabel() {
        return label;
    }

    @Override
    public void undo() {
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undo();
        }
    }

    @Override
    public void redo() {
        for (EditorCommand command : commands) {
            command.redo();
        }
    }

    public static CompositeEditorCommand of(String label, EditorCommand... commands) {
        ArrayList<EditorCommand> list = new ArrayList<>();
        if (commands != null) {
            for (EditorCommand command : commands) {
                if (command != null) {
                    list.add(command);
                }
            }
        }
        return new CompositeEditorCommand(label, list);
    }
}
