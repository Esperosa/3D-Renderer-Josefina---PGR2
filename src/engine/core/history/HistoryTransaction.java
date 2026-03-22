package engine.core.history;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents rozpracovanou transakci historie, do které sbírám více příkazů.
 */
public final class HistoryTransaction {
    private final String label;
    private final ArrayList<EditorCommand> commands;

    public HistoryTransaction(String label) {
        this.label = label == null || label.isBlank() ? "Editorová akce" : label;
        this.commands = new ArrayList<>();
    }

    public String getLabel() {
        return label;
    }

    public void add(EditorCommand command) {
        if (command != null) {
            commands.add(command);
        }
    }

    public boolean isEmpty() {
        return commands.isEmpty();
    }

    public EditorCommand toCommand() {
        if (commands.isEmpty()) {
            return null;
        }
        if (commands.size() == 1) {
            return commands.get(0);
        }
        return new CompositeEditorCommand(label, List.copyOf(commands));
    }
}
