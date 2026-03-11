package engine.util;

import engine.math.Vec3;
import engine.ui.UiStrings;
import engine.ui.UiTheme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

public final class UiBuilder {
    private static final String TEXT_UNDO_MANAGER_PROPERTY = "editor.textUndoManager";

    private UiBuilder() {
    }

    public static JPanel addCollapsibleSection(
            JPanel parent, String title, boolean expandedByDefault, Runnable focusRequester) {
        return addCollapsibleSection(parent, title, expandedByDefault, focusRequester, null);
    }

    public static JPanel addCollapsibleSection(
            JPanel parent,
            String title,
            boolean expandedByDefault,
            Runnable focusRequester,
            Consumer<Boolean> onToggle) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(0.0f);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JToggleButton header = new SectionHeaderButton();
        header.setSelected(expandedByDefault);
        header.setAlignmentX(0.0f);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTheme.SECTION_HEADER_HEIGHT));
        UiTheme.styleSectionHeader(header);

        JPanel body = new JPanel();
        body.setLayout(new javax.swing.BoxLayout(body, javax.swing.BoxLayout.Y_AXIS));
        body.setAlignmentX(0.0f);
        body.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        body.setVisible(expandedByDefault);
        UiTheme.styleSectionBody(body);

        Runnable updateHeader = () -> header.setText((header.isSelected() ? "\u25BE  " : "\u25B8  ") + title);
        updateHeader.run();
        header.addActionListener(e -> {
            boolean expanded = header.isSelected();
            body.setVisible(expanded);
            updateHeader.run();
            wrapper.revalidate();
            wrapper.repaint();
            if (onToggle != null) {
                onToggle.accept(expanded);
            }
            requestFocus(focusRequester);
        });

        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(body, BorderLayout.CENTER);
        parent.add(wrapper);
        parent.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        return body;
    }

    public static JCheckBox addBooleanRow(
            JPanel parent, String label, boolean initial, Consumer<Boolean> onChange, Runnable focusRequester) {
        JCheckBox check = new JCheckBox(formatInlineText(label, 30), initial);
        check.setAlignmentX(0.0f);
        check.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        UiTheme.styleCheckBox(check);
        check.addActionListener(e -> {
            if (onChange != null) {
                onChange.accept(check.isSelected());
            }
            requestFocus(focusRequester);
        });
        parent.add(check);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
        return check;
    }

    public static JTextField addNumericRow(
            JPanel parent, String label, String initial, Consumer<String> onCommit) {
        JPanel row = createInputRow(label);

        JTextField field = new JTextField(10);
        styleInspectorField(field);
        field.setText(initial);
        stretchRowControl(field, 30);
        field.addActionListener(e -> commitNumericText(onCommit, field));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitNumericText(onCommit, field);
            }
        });
        row.add(field);

        parent.add(row);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
        return field;
    }

    public static void addColorPickerRow(
            JPanel parent,
            String label,
            Vec3 initialColor,
            Consumer<Vec3> onCommit,
            Component colorDialogParent,
            Runnable focusRequester,
            Runnable keyframeInsertAction,
            Runnable keyframeRemoveAction) {
        JPanel row = createInputRow(label);

        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);
        stretchRowControl(controls, 30);

        Vec3 seed = initialColor == null
                ? new Vec3(1.0, 1.0, 1.0)
                : new Vec3(clamp01(initialColor.x), clamp01(initialColor.y), clamp01(initialColor.z));
        final Vec3[] currentColor = new Vec3[]{seed};

        JButton swatch = new JButton();
        swatch.setText("");
        swatch.setFocusPainted(false);
        swatch.setOpaque(true);
        swatch.setBackground(toAwtColor(seed));
        swatch.setBorder(BorderFactory.createLineBorder(UiTheme.BORDER_STRONG, 1, true));
        swatch.setPreferredSize(new Dimension(24, 24));
        swatch.setMinimumSize(new Dimension(24, 24));
        swatch.setMaximumSize(new Dimension(24, 24));
        controls.add(swatch, BorderLayout.WEST);

        JTextField hexField = new JTextField(9);
        styleInspectorField(hexField);
        hexField.setText(colorToHex(seed));
        hexField.setMinimumSize(new Dimension(110, 28));
        stretchRowControl(hexField, 30);
        controls.add(hexField, BorderLayout.CENTER);

        Consumer<Vec3> commitColor = next -> {
            if (next == null) {
                return;
            }
            Vec3 clamped = new Vec3(clamp01(next.x), clamp01(next.y), clamp01(next.z));
            currentColor[0] = clamped;
            swatch.setBackground(toAwtColor(clamped));
            hexField.setText(colorToHex(clamped));
            if (onCommit != null) {
                onCommit.accept(clamped);
            }
        };

        Runnable applyHexText = () -> {
            Vec3 parsed = parseHexColor(hexField.getText());
            if (parsed != null) {
                commitColor.accept(parsed);
            } else {
                hexField.setText(colorToHex(currentColor[0]));
            }
        };

        hexField.addActionListener(e -> applyHexText.run());
        hexField.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                applyHexText.run();
            }
        });

        swatch.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(colorDialogParent, label, toAwtColor(currentColor[0]));
            if (chosen != null) {
                commitColor.accept(colorFromAwt(chosen));
            }
            requestFocus(focusRequester);
        });
        attachKeyframePopup(
                swatch,
                null,
                keyframeInsertAction,
                keyframeRemoveAction
        );
        attachKeyframePopup(
                hexField,
                applyHexText,
                keyframeInsertAction,
                keyframeRemoveAction
        );

        row.add(controls);
        parent.add(row);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
    }

    public static JTextField addTextRow(
            JPanel parent, String label, String initial, Consumer<String> onCommit) {
        JPanel row = createInputRow(label);

        JTextField field = new JTextField(12);
        styleInspectorField(field);
        field.setText(initial);
        stretchRowControl(field, 30);
        field.addActionListener(e -> commitText(onCommit, field));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitText(onCommit, field);
            }
        });
        row.add(field);

        parent.add(row);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
        return field;
    }

    public static JComboBox<String> addComboRow(
            JPanel parent, String label, String[] values, String selected,
            Consumer<String> onChange, Runnable focusRequester) {
        JPanel row = createInputRow(label);

        JComboBox<String> combo = new JComboBox<>(values);
        UiTheme.styleComboBox(combo);
        combo.setSelectedItem(selected);
        stretchRowControl(combo, 30);
        combo.addActionListener(e -> {
            Object value = combo.getSelectedItem();
            if (onChange != null && value != null) {
                onChange.accept(value.toString());
            }
            requestFocus(focusRequester);
        });
        row.add(combo);

        parent.add(row);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
        return combo;
    }

    public static void styleInspectorField(JTextField field) {
        UiTheme.styleInspectorField(field);
        installTextUndoSupport(field);
    }

    public static void installTextUndoSupport(JTextComponent component) {
        if (component == null || component.getClientProperty(TEXT_UNDO_MANAGER_PROPERTY) instanceof UndoManager) {
            return;
        }
        UndoManager undoManager = new UndoManager();
        component.getDocument().addUndoableEditListener(event -> undoManager.addEdit(event.getEdit()));
        component.putClientProperty(TEXT_UNDO_MANAGER_PROPERTY, undoManager);
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                undoManager.discardAllEdits();
            }

            @Override
            public void focusLost(FocusEvent e) {
                undoManager.discardAllEdits();
            }
        });
    }

    public static boolean undoTextChange(Component component) {
        UndoManager undoManager = textUndoManager(component);
        if (undoManager == null || !undoManager.canUndo()) {
            return false;
        }
        try {
            undoManager.undo();
            return true;
        } catch (CannotUndoException ex) {
            return false;
        }
    }

    public static boolean redoTextChange(Component component) {
        UndoManager undoManager = textUndoManager(component);
        if (undoManager == null || !undoManager.canRedo()) {
            return false;
        }
        try {
            undoManager.redo();
            return true;
        } catch (CannotRedoException ex) {
            return false;
        }
    }

    public static JLabel sectionTitle(String text) {
        return UiTheme.createSectionTitle(formatInlineText(text, 42));
    }

    public static JLabel helperText(String text) {
        return UiTheme.createHelperText(formatInlineText(text, 52));
    }

    public static JPanel panelHeader(String title, String subtitle) {
        JPanel panel = UiTheme.createPanelHeader(title, formatInlineText(subtitle, 58));
        panel.setAlignmentX(0.0f);
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return panel;
    }

    public static JLabel badge(String text) {
        return UiTheme.createBadge(text);
    }

    public static JButton actionButton(String text, Runnable action, Runnable focusRequester) {
        JButton button = new JButton(formatButtonText(text));
        styleSecondaryButton(button);
        button.setAlignmentX(0.0f);
        button.addActionListener(e -> {
            if (action != null) {
                action.run();
            }
            requestFocus(focusRequester);
        });
        return button;
    }

    public static JTextField addTransformField(JPanel parent, String axis, Runnable onCommit) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        JLabel label = new JLabel(axis + ":");
        label.setForeground(UiTheme.TEXT_SECONDARY);
        JTextField field = new JTextField(10);
        styleInspectorField(field);
        field.addActionListener(e -> commitTransformField(field, onCommit));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitTransformField(field, onCommit);
            }
        });
        row.add(label);
        row.add(field);
        row.setAlignmentX(0.0f);
        parent.add(row);
        return field;
    }

    public static double parseOrFallback(String text, double fallback) {
        String normalized = normalizeNumericText(text);
        if (normalized == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static String normalizeNumericText(String text) {
        if (text == null) {
            return null;
        }
        String normalized = text.trim()
                .replace('\u00A0', ' ')
                .replace(" ", "")
                .replace(',', '.');
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.startsWith(".")) {
            normalized = "0" + normalized;
        } else if (normalized.startsWith("+.")) {
            normalized = "+0" + normalized.substring(1);
        } else if (normalized.startsWith("-.")) {
            normalized = "-0" + normalized.substring(1);
        }
        return normalized;
    }

    public static void normalizeNumericField(JTextField field) {
        if (field == null) {
            return;
        }
        String normalized = normalizeNumericText(field.getText());
        if (normalized != null) {
            field.setText(normalized);
        }
    }

    public static JToggleButton createLightToggle(String text, Runnable action, Runnable focusRequester) {
        JToggleButton toggle = createStyledToggleButton(text);
        toggle.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        toggle.addActionListener(e -> {
            if (action != null) {
                action.run();
            }
            requestFocus(focusRequester);
        });
        styleLight(toggle, false);
        return toggle;
    }

    public static JToggleButton createStyledToggleButton(String text) {
        JToggleButton toggle = new StateToggleButton(text);
        toggle.setFocusPainted(false);
        toggle.setOpaque(false);
        toggle.setContentAreaFilled(false);
        toggle.setBorderPainted(false);
        toggle.setRolloverEnabled(true);
        return toggle;
    }

    public static void styleActionButton(JButton button) {
        styleSecondaryButton(button);
    }

    public static void stylePrimaryButton(JButton button) {
        UiTheme.stylePrimaryButton(button);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
    }

    public static void styleSecondaryButton(JButton button) {
        UiTheme.styleSecondaryButton(button);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
    }

    public static void styleGhostButton(JButton button) {
        UiTheme.styleGhostButton(button);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 58));
    }

    private static String formatButtonText(String text) {
        return formatInlineText(text, 24);
    }

    public static void styleLight(JToggleButton light, boolean active) {
        if (light == null) {
            return;
        }
        UiTheme.styleToggle(light, active);
    }

    private static void commitText(Consumer<String> onCommit, JTextField field) {
        if (onCommit != null) {
            onCommit.accept(field.getText().trim());
        }
    }

    private static JPanel createInputRow(String label) {
        JPanel row = new JPanel();
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.Y_AXIS));
        row.setOpaque(false);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        row.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));

        JLabel key = new JLabel(formatInlineText(label, 28));
        key.setAlignmentX(0.0f);
        key.setForeground(UiTheme.TEXT_SECONDARY);
        key.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        key.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        row.add(key);
        return row;
    }

    private static void stretchRowControl(Component component, int height) {
        if (component == null) {
            return;
        }
        Dimension preferred = component.getPreferredSize();
        int safeHeight = Math.max(24, height);
        component.setPreferredSize(new Dimension(Math.max(96, preferred.width), safeHeight));
        component.setMinimumSize(new Dimension(64, safeHeight));
        component.setMaximumSize(new Dimension(Integer.MAX_VALUE, safeHeight));
        if (component instanceof javax.swing.JComponent swingComponent) {
            swingComponent.setAlignmentX(0.0f);
        }
    }

    private static String formatInlineText(String text, int maxCharsPerLine) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() <= maxCharsPerLine || !trimmed.contains(" ")) {
            return trimmed;
        }
        String[] words = trimmed.split("\\s+");
        StringBuilder wrapped = new StringBuilder("<html>");
        StringBuilder line = new StringBuilder();
        int lineCount = 0;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            int projected = line.length() == 0 ? word.length() : line.length() + 1 + word.length();
            if (projected > maxCharsPerLine && line.length() > 0) {
                if (lineCount > 0) {
                    wrapped.append("<br>");
                }
                wrapped.append(line);
                line.setLength(0);
                line.append(word);
                lineCount++;
            } else {
                if (line.length() > 0) {
                    line.append(' ');
                }
                line.append(word);
            }
        }
        if (line.length() > 0) {
            if (lineCount > 0) {
                wrapped.append("<br>");
            }
            wrapped.append(line);
        }
        wrapped.append("</html>");
        return wrapped.toString();
    }

    private static void commitNumericText(Consumer<String> onCommit, JTextField field) {
        normalizeNumericField(field);
        commitText(onCommit, field);
    }

    private static void commitTransformField(JTextField field, Runnable onCommit) {
        normalizeNumericField(field);
        runSafe(onCommit);
    }

    private static void requestFocus(Runnable focusRequester) {
        runSafe(focusRequester);
    }

    private static UndoManager textUndoManager(Component component) {
        if (!(component instanceof JTextComponent textComponent)) {
            return null;
        }
        Object value = textComponent.getClientProperty(TEXT_UNDO_MANAGER_PROPERTY);
        return value instanceof UndoManager undoManager ? undoManager : null;
    }

    private static void runSafe(Runnable action) {
        if (action != null) {
            action.run();
        }
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static Color toAwtColor(Vec3 color) {
        int r = (int) Math.round(clamp01(color.x) * 255.0);
        int g = (int) Math.round(clamp01(color.y) * 255.0);
        int b = (int) Math.round(clamp01(color.z) * 255.0);
        return new Color(r, g, b);
    }

    private static Vec3 colorFromAwt(Color color) {
        if (color == null) {
            return new Vec3(1.0, 1.0, 1.0);
        }
        return new Vec3(color.getRed() / 255.0, color.getGreen() / 255.0, color.getBlue() / 255.0);
    }

    private static String colorToHex(Vec3 color) {
        int r = (int) Math.round(clamp01(color.x) * 255.0);
        int g = (int) Math.round(clamp01(color.y) * 255.0);
        int b = (int) Math.round(clamp01(color.z) * 255.0);
        return String.format("#%02X%02X%02X", r, g, b);
    }

    private static Vec3 parseHexColor(String text) {
        if (text == null) {
            return null;
        }
        String raw = text.trim();
        if (raw.startsWith("#")) {
            raw = raw.substring(1);
        }
        if (raw.length() == 3) {
            raw = "" + raw.charAt(0) + raw.charAt(0)
                    + raw.charAt(1) + raw.charAt(1)
                    + raw.charAt(2) + raw.charAt(2);
        }
        if (raw.length() != 6) {
            return null;
        }
        try {
            int rgb = Integer.parseInt(raw, 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            return new Vec3(r / 255.0, g / 255.0, b / 255.0);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static void attachKeyframePopup(
            Component target,
            Runnable preCommit,
            Runnable insertAction,
            Runnable removeAction) {
        if (target == null || (insertAction == null && removeAction == null)) {
            return;
        }
        MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                if (e == null || !e.isPopupTrigger()) {
                    return;
                }
                JPopupMenu popup = new JPopupMenu();
                JMenuItem insert = new JMenuItem(UiStrings.Timeline.INSERT_KEYFRAME);
                insert.setEnabled(insertAction != null);
                insert.addActionListener(evt -> {
                    runSafe(preCommit);
                    runSafe(insertAction);
                });
                popup.add(insert);
                JMenuItem remove = new JMenuItem(UiStrings.Timeline.REMOVE_KEYFRAME);
                remove.setEnabled(removeAction != null);
                remove.addActionListener(evt -> runSafe(removeAction));
                popup.add(remove);
                popup.show(e.getComponent(), e.getX(), e.getY());
                e.consume();
            }
        };
        target.addMouseListener(listener);
    }

    private static final class SectionHeaderButton extends JToggleButton {
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int arc = 12;
            int x = 0;
            int y = 0;

            ButtonModel model = getModel();
            boolean active = model.isSelected();
            boolean pressed = model.isPressed() && model.isArmed();
            boolean hover = model.isRollover();

            Color fill = UiTheme.SECTION_HEADER_BG;
            Color border = UiTheme.BORDER_STRONG;
            if (active) {
                fill = pressed ? UiTheme.SELECTION_BG : UiTheme.SECTION_HEADER_ACTIVE_BG;
                border = UiTheme.ACCENT_PURPLE;
            } else if (pressed) {
                fill = UiTheme.SECTION_HEADER_HOVER_BG.darker();
                border = UiTheme.BORDER_STRONG;
            } else if (hover) {
                fill = UiTheme.SECTION_HEADER_HOVER_BG;
                border = UiTheme.ACCENT_PURPLE_GLOW;
            }

            g2.setColor(fill);
            g2.fillRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.setColor(border);
            g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
            g2.dispose();

            super.paintComponent(g);
        }
    }

    private static final class StateToggleButton extends JToggleButton {
        StateToggleButton(String text) {
            super(text);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            int arc = 10;

            ButtonModel model = getModel();
            boolean active = model.isSelected();
            boolean pressed = model.isPressed() && model.isArmed();
            boolean hover = model.isRollover();

            Color fill = new Color(35, 46, 58);
            Color border = UiTheme.BORDER_SUBTLE;
            if (active) {
                fill = pressed ? UiTheme.SELECTION_BG : UiTheme.ACCENT_PURPLE_SOFT;
                border = UiTheme.ACCENT_PURPLE;
            } else if (pressed) {
                fill = new Color(31, 41, 53);
                border = UiTheme.BORDER_STRONG;
            } else if (hover) {
                fill = new Color(42, 53, 67);
                border = UiTheme.ACCENT_PURPLE_GLOW;
            }

            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.setColor(border);
            g2.drawRoundRect(0, 0, w - 1, h - 1, arc, arc);
            g2.dispose();

            super.paintComponent(g);
        }
    }
}
