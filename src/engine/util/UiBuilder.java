package engine.util;

import engine.math.Vec3;
import engine.ui.UiStrings;
import engine.ui.UiTheme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonModel;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import javax.swing.undo.CannotRedoException;
import javax.swing.undo.CannotUndoException;
import javax.swing.undo.UndoManager;

public final class UiBuilder {
    private static final String TEXT_UNDO_MANAGER_PROPERTY = "editor.textUndoManager";
    private static final String NUMERIC_INTEGER_PROPERTY = "editor.numericPreferInteger";
    private static final String NUMERIC_DECIMALS_PROPERTY = "editor.numericDecimals";
    private static final int PROPERTY_LABEL_WIDTH = 138;
    private static final Map<String, String> PROPERTY_HINTS = new HashMap<>();

    static {
        PROPERTY_HINTS.put("Viewport", "Renderer používaný pro živý náhled ve viewportu.");
        PROPERTY_HINTS.put("Výstup", "Renderer používaný pro finální export.");
        PROPERTY_HINTS.put("Renderer", "Volí aktivní renderovací režim pro tuto část workflow.");
        PROPERTY_HINTS.put("Engine", "Volba rendereru a hlavních režimů pro daný panel.");
        PROPERTY_HINTS.put("Preview", "Interaktivní chování, fallback a výkon živého viewportu.");
        PROPERTY_HINTS.put("Mode", "Volby závislé na aktivním rendereru nebo režimu.");
        PROPERTY_HINTS.put("Target", "Kam se bude export ukládat a jak se pojmenuje session.");
        PROPERTY_HINTS.put("Frames", "Rozsah snímků a časování výstupu.");
        PROPERTY_HINTS.put("Format", "Formát souboru a kodekové volby výstupu.");
        PROPERTY_HINTS.put("Quality", "Rozlišení, interní scale a výkonnostní limity.");
        PROPERTY_HINTS.put("Run", "Spuštění finálního exportu do aktuální session.");
        PROPERTY_HINTS.put("Navigation", "Předvolby ovládání kamery a způsob pohybu.");
        PROPERTY_HINTS.put("Motion", "Rychlost pohybu, citlivost a playback stavy.");
        PROPERTY_HINTS.put("Transform", "Přesné pozice, rotace a měřítko vybrané položky.");
        PROPERTY_HINTS.put("Operations", "Akce nad vybranou položkou nebo její animací.");
        PROPERTY_HINTS.put("Předvolba", "Rychlá volba připraveného nastavení.");
        PROPERTY_HINTS.put("Počet vláken", "Horní limit paralelních worker vláken pro renderer.");
        PROPERTY_HINTS.put("Velikost tile", "Velikost renderovacích dlaždic. Menší tile snižují latenci, větší zlepšují throughput.");
        PROPERTY_HINTS.put("Cílové vzorky", "Kolik vzorků se má nasbírat pro finální progresivní snímek.");
        PROPERTY_HINTS.put("Vzorky / krok", "Počet vzorků přidaných během jednoho progresivního kroku.");
        PROPERTY_HINTS.put("Měřítko viewportu", "Interní škálování viewport renderu pro výkon a čitelnost.");
        PROPERTY_HINTS.put("Interní měřítko", "Interní škálování renderu před finálním zapsáním do výstupu.");
        PROPERTY_HINTS.put("Cílové FPS viewportu", "Výkonnostní cíl pro interaktivní viewport.");
        PROPERTY_HINTS.put("Min. interaktivní měřítko", "Nejnižší interní scale, kam může viewport spadnout při zátěži.");
        PROPERTY_HINTS.put("Dohled", "Maximální vzdálenost, do které zůstávají objekty viditelné ve viewportu.");
        PROPERTY_HINTS.put("Frustum culling", "Skrývá objekty mimo zorný kužel kamery.");
        PROPERTY_HINTS.put("Backface culling", "Nezobrazuje zadní stěny polygonů orientované od kamery.");
        PROPERTY_HINTS.put("Paralelní raster", "Použije více vláken i v rasterizačních režimech.");
        PROPERTY_HINTS.put("Post AA", "Dodatečné vyhlazení hran po dokončení snímku.");
        PROPERTY_HINTS.put("Progresivní viewport", "U heavy rendererů postupně zpřesňuje obraz místo jednoho těžkého průchodu.");
        PROPERTY_HINTS.put("Fallback režim", "Náhradní renderer používaný při krizovém preview nebo navigačním fallbacku.");
        PROPERTY_HINTS.put("Vzorky / snímek", "Kolik vzorků se přidá za jeden viewport frame.");
        PROPERTY_HINTS.put("Diffuse", "Maximální počet difúzních odrazů světla.");
        PROPERTY_HINTS.put("Glossy", "Maximální počet glossy/specular odrazů.");
        PROPERTY_HINTS.put("Transmission", "Průchod světla skrz materiál nebo limit průchodů v trasovacích režimech.");
        PROPERTY_HINTS.put("Volume", "Maximální počet objemových scattering kroků.");
        PROPERTY_HINTS.put("Transparent", "Limit průchodů přes alfa transparentní povrchy.");
        PROPERTY_HINTS.put("Přímé světlo", "Zapne přímý příspěvek světel bez dalších odrazů.");
        PROPERTY_HINTS.put("Obloha / environment", "Zahrne světlo a barvu prostředí do renderu.");
        PROPERTY_HINTS.put("Denoise", "Potlačí šum v progresivních režimech.");
        PROPERTY_HINTS.put("Radius denoise", "Velikost okolí použitého při odšumění.");
        PROPERTY_HINTS.put("Síla denoise", "Jak silně se má obraz vyhlazovat.");
        PROPERTY_HINTS.put("Tone map", "Mapování HDR hodnot do zobrazitelného rozsahu.");
        PROPERTY_HINTS.put("Clamp direct", "Omezí extrémní přímé světelné hodnoty.");
        PROPERTY_HINTS.put("Clamp indirect", "Omezí extrémní nepřímé odrazy a fireflies.");
        PROPERTY_HINTS.put("Roughness", "Mikrodrsnost povrchu. Vyšší hodnoty rozmazávají odrazy.");
        PROPERTY_HINTS.put("Metallic", "Určuje, zda se materiál chová jako kov.");
        PROPERTY_HINTS.put("IOR", "Index lomu pro sklo a průhledné materiály.");
        PROPERTY_HINTS.put("Opacity", "Výsledná neprůhlednost materiálu.");
        PROPERTY_HINTS.put("Emission Strength", "Síla vlastní emise materiálu.");
        PROPERTY_HINTS.put("Lineární filtrování", "Vyhlazuje vzorkování textury mezi texely.");
        PROPERTY_HINTS.put("Překlopit V", "Obrátí svislý směr UV souřadnic.");
        PROPERTY_HINTS.put("UV sada", "Volí, kterou UV vrstvu textura používá.");
        PROPERTY_HINTS.put("Offset U", "Posun textury po U ose.");
        PROPERTY_HINTS.put("Offset V", "Posun textury po V ose.");
        PROPERTY_HINTS.put("Scale U", "Škálování textury po U ose.");
        PROPERTY_HINTS.put("Scale V", "Škálování textury po V ose.");
        PROPERTY_HINTS.put("Rotation", "Rotace textury nebo mapování.");
        PROPERTY_HINTS.put("Název", "Uživatelské jméno položky zobrazené v outlineru.");
        PROPERTY_HINTS.put("Viditelné ve viewportu", "Zobrazení položky v živém viewportu.");
        PROPERTY_HINTS.put("Viditelné ve výstupu", "Zahrnutí položky do finálního renderu.");
        PROPERTY_HINTS.put("Vrhat stíny", "Určuje, zda objekt ovlivňuje stínování ostatních objektů.");
        PROPERTY_HINTS.put("Statický objekt", "Statické objekty se neúčastní dynamických fyzikálních změn.");
        PROPERTY_HINTS.put("Ambientní barva", "Základní barva světla prostředí.");
        PROPERTY_HINTS.put("Síla", "Celková intenzita daného efektu nebo světla.");
        PROPERTY_HINTS.put("Pozadí", "Barva nebo tón pozadí scény.");
        PROPERTY_HINTS.put("HDRI otočení (yaw)", "Vodorovná rotace environment mapy.");
        PROPERTY_HINTS.put("HDRI náklon (pitch)", "Svislá rotace environment mapy.");
        PROPERTY_HINTS.put("Rychlost pohybu", "Rychlost translace kamery při navigaci.");
        PROPERTY_HINTS.put("Citlivost rozhlížení", "Citlivost pohybu kamery při otáčení.");
        PROPERTY_HINTS.put("Šířka", "Cílová šířka výstupu v pixelech.");
        PROPERTY_HINTS.put("Výška", "Cílová výška výstupu v pixelech.");
        PROPERTY_HINTS.put("Začátek", "První snímek časového rozsahu.");
        PROPERTY_HINTS.put("Konec", "Poslední snímek časového rozsahu.");
        PROPERTY_HINTS.put("FPS", "Snímková frekvence použitá pro časování animace.");
    }

    private UiBuilder() {
    }

    public static JPanel addCollapsibleSection(
            JPanel parent, String title, boolean expandedByDefault, Runnable focusRequester) {
        return addCollapsibleSection(parent, title, expandedByDefault, focusRequester, (Consumer<Boolean>) null, null);
    }

    public static JPanel addCollapsibleSection(
            JPanel parent, String title, boolean expandedByDefault, Runnable focusRequester, String tooltip) {
        return addCollapsibleSection(parent, title, expandedByDefault, focusRequester, null, tooltip);
    }

    public static JPanel addCollapsibleSection(
            JPanel parent,
            String title,
            boolean expandedByDefault,
            Runnable focusRequester,
            Consumer<Boolean> onToggle) {
        return addCollapsibleSection(parent, title, expandedByDefault, focusRequester, onToggle, null);
    }

    public static JPanel addCollapsibleSection(
            JPanel parent,
            String title,
            boolean expandedByDefault,
            Runnable focusRequester,
            Consumer<Boolean> onToggle,
            String tooltip) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setAlignmentX(0.0f);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JToggleButton header = new SectionHeaderButton();
        header.setSelected(expandedByDefault);
        header.setAlignmentX(0.0f);
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTheme.SECTION_HEADER_HEIGHT));
        UiTheme.styleSectionHeader(header);
        installTooltip(header, tooltip != null ? tooltip : resolvePropertyHint(title));

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
            requestFocus(focusRequester, header);
        });

        wrapper.add(header, BorderLayout.NORTH);
        wrapper.add(body, BorderLayout.CENTER);
        parent.add(wrapper);
        parent.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        return body;
    }

    public static JCheckBox addBooleanRow(
            JPanel parent, String label, boolean initial, Consumer<Boolean> onChange, Runnable focusRequester) {
        JPanel row = createInputRow(label);

        JCheckBox check = new JCheckBox("", initial);
        check.setAlignmentX(1.0f);
        UiTheme.styleCheckBox(check);
        check.addActionListener(e -> {
            runWithViewportPreserved(check, () -> {
                if (onChange != null) {
                    onChange.accept(check.isSelected());
                }
            });
            requestFocus(focusRequester, check);
        });
        installTooltip(check, resolvePropertyHint(label));
        row.add(wrapRowControl(check), BorderLayout.EAST);

        parent.add(row);
        parent.add(Box.createRigidArea(new Dimension(0, 4)));
        return check;
    }

    public static JTextField addNumericRow(
            JPanel parent, String label, String initial, Consumer<String> onCommit) {
        JPanel row = createInputRow(label);

        JTextField field = new JTextField(10);
        styleInspectorField(field);
        field.setText(initial);
        field.setHorizontalAlignment(SwingConstants.RIGHT);
        field.putClientProperty(NUMERIC_INTEGER_PROPERTY, preferIntegerFormatting(initial));
        field.putClientProperty(NUMERIC_DECIMALS_PROPERTY, initialDecimalPlaces(initial));
        stretchRowControl(field, 30);
        installTooltip(field, resolvePropertyHint(label));
        installTextFieldSelectionBehavior(field);
        installNumericNudgeBehavior(field, onCommit);
        field.addActionListener(e -> commitNumericText(onCommit, field));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitNumericText(onCommit, field);
            }
        });
        row.add(wrapRowControl(field), BorderLayout.EAST);

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
            requestFocus(focusRequester, swatch);
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

        installTooltip(swatch, resolvePropertyHint(label));
        installTooltip(hexField, resolvePropertyHint(label));
        row.add(wrapRowControl(controls), BorderLayout.EAST);
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
        installTooltip(field, resolvePropertyHint(label));
        installTextFieldSelectionBehavior(field);
        field.addActionListener(e -> commitText(onCommit, field));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitText(onCommit, field);
            }
        });
        row.add(wrapRowControl(field), BorderLayout.EAST);

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
        installTooltip(combo, resolvePropertyHint(label));
        combo.addActionListener(e -> {
            runWithViewportPreserved(combo, () -> {
                Object value = combo.getSelectedItem();
                if (onChange != null && value != null) {
                    onChange.accept(value.toString());
                }
            });
            requestFocus(focusRequester, combo);
        });
        row.add(wrapRowControl(combo), BorderLayout.EAST);

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

    public static JLabel infoLine(String text) {
        return UiTheme.createHelperText(formatInlineText(text, 44));
    }

    public static JPanel panelHeader(String title, String subtitle) {
        JPanel panel = UiTheme.createPanelHeader(title, subtitle == null ? null : subtitle.trim());
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
            runWithViewportPreserved(button, action);
            requestFocus(focusRequester, button);
        });
        return button;
    }

    public static JTextField addTransformField(JPanel parent, String axis, Runnable onCommit) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(axis + ":");
        label.setForeground(UiTheme.TEXT_SECONDARY);
        label.setPreferredSize(new Dimension(28, UiTheme.INPUT_HEIGHT));
        label.setMinimumSize(new Dimension(28, UiTheme.INPUT_HEIGHT));
        label.setHorizontalAlignment(SwingConstants.LEFT);
        JTextField field = new JTextField(10);
        styleInspectorField(field);
        field.setHorizontalAlignment(SwingConstants.RIGHT);
        field.putClientProperty(NUMERIC_INTEGER_PROPERTY, Boolean.FALSE);
        field.putClientProperty(NUMERIC_DECIMALS_PROPERTY, 4);
        installTextFieldSelectionBehavior(field);
        installNumericNudgeBehavior(field, text -> runSafe(onCommit));
        field.addActionListener(e -> commitTransformField(field, onCommit));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                commitTransformField(field, onCommit);
            }
        });
        row.add(label, BorderLayout.WEST);
        row.add(wrapRowControl(field), BorderLayout.CENTER);
        row.setAlignmentX(0.0f);
        parent.add(row);
        return field;
    }

    public static JLabel addReadOnlyRow(JPanel parent, String label, String value) {
        JPanel row = createInputRow(label);
        JLabel content = new JLabel(formatInlineText(value, 34));
        content.setForeground(UiTheme.TEXT_PRIMARY);
        content.setHorizontalAlignment(SwingConstants.RIGHT);
        content.setToolTipText(value);
        row.add(wrapRowControl(content), BorderLayout.EAST);
        parent.add(row);
        return content;
    }

    public static double parseOrFallback(String text, double fallback) {
        Double parsed = parseNumericExpression(text);
        if (parsed == null || !Double.isFinite(parsed)) {
            return fallback;
        }
        return parsed;
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
        Double parsed = parseNumericExpression(field.getText());
        if (parsed != null && Double.isFinite(parsed)) {
            field.setText(formatNumericValue(field, parsed));
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
        runWithViewportPreserved(field, () -> {
            if (onCommit != null) {
                onCommit.accept(field.getText().trim());
            }
        });
    }

    private static JPanel createInputRow(String label) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.setOpaque(false);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTheme.INPUT_HEIGHT + 2));
        row.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));

        JLabel key = new JLabel(formatInlineText(label, 28));
        key.setForeground(UiTheme.TEXT_SECONDARY);
        key.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
        key.setPreferredSize(new Dimension(PROPERTY_LABEL_WIDTH, UiTheme.INPUT_HEIGHT));
        key.setMinimumSize(new Dimension(PROPERTY_LABEL_WIDTH, UiTheme.INPUT_HEIGHT));
        key.setHorizontalAlignment(SwingConstants.LEFT);
        installTooltip(key, resolvePropertyHint(label));
        row.add(key, BorderLayout.WEST);
        return row;
    }

    private static JComponent wrapRowControl(Component component) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(component, BorderLayout.CENTER);
        if (component instanceof JComponent swingComponent) {
            holder.setToolTipText(swingComponent.getToolTipText());
        }
        return holder;
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

    private static void requestFocus(Runnable focusRequester, Component source) {
        if (source != null && SwingUtilities.getAncestorOfClass(JScrollPane.class, source) != null) {
            return;
        }
        runSafe(focusRequester);
    }

    private static void installTextFieldSelectionBehavior(JTextField field) {
        if (field == null) {
            return;
        }
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                SwingUtilities.invokeLater(field::selectAll);
            }
        });
    }

    private static void installNumericNudgeBehavior(JTextField field, Consumer<String> onCommit) {
        if (field == null) {
            return;
        }
        field.addMouseWheelListener(e -> {
            if (!field.isFocusOwner() && !field.isShowing()) {
                return;
            }
            nudgeNumericField(field, onCommit, -e.getWheelRotation(), e);
            e.consume();
        });
        field.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_UP || e.getKeyCode() == KeyEvent.VK_DOWN) {
                    int direction = e.getKeyCode() == KeyEvent.VK_UP ? 1 : -1;
                    nudgeNumericField(field, onCommit, direction, e);
                    e.consume();
                }
            }
        });
    }

    private static void nudgeNumericField(JTextField field, Consumer<String> onCommit, int direction, java.awt.event.InputEvent event) {
        Double current = parseNumericExpression(field.getText());
        if (current == null || !Double.isFinite(current) || direction == 0) {
            return;
        }
        double baseStep = baseNumericStep(current);
        if (event != null && event.isShiftDown()) {
            baseStep *= 0.1;
        } else if (event != null && event.isControlDown()) {
            baseStep *= 10.0;
        }
        double next = current + direction * baseStep;
        field.setText(formatNumericValue(field, next));
        commitText(onCommit, field);
    }

    private static double baseNumericStep(double value) {
        double magnitude = Math.abs(value);
        if (magnitude < 1.0) {
            return 0.01;
        }
        if (magnitude < 10.0) {
            return 0.1;
        }
        if (magnitude < 100.0) {
            return 1.0;
        }
        return 10.0;
    }

    private static void installTooltip(JComponent component, String tooltip) {
        if (component == null || tooltip == null || tooltip.isBlank()) {
            return;
        }
        component.setToolTipText("<html><div style='width:220px'>" + tooltip + "</div></html>");
    }

    private static String resolvePropertyHint(String label) {
        if (label == null) {
            return null;
        }
        String trimmed = label.trim();
        String direct = PROPERTY_HINTS.get(trimmed);
        if (direct != null) {
            return direct;
        }
        if (trimmed.startsWith("Směr ")) {
            return "Směrový vektor dané položky v lokálním nebo světovém prostoru.";
        }
        if (trimmed.startsWith("Pozice ")) {
            return "Pozice položky v prostoru scény.";
        }
        if (trimmed.startsWith("Scale ") || trimmed.startsWith("Měřítko")) {
            return "Škálování podél příslušné osy.";
        }
        if (trimmed.startsWith("Rotation ") || trimmed.startsWith("Rotace")) {
            return "Rotace ve stupních.";
        }
        return null;
    }

    private static void runWithViewportPreserved(Component anchor, Runnable action) {
        if (anchor == null) {
            runSafe(action);
            return;
        }
        JScrollPane scrollPane = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, anchor);
        Point viewPosition = scrollPane == null ? null : scrollPane.getViewport().getViewPosition();
        runSafe(action);
        if (scrollPane != null && viewPosition != null) {
            Point target = new Point(viewPosition);
            SwingUtilities.invokeLater(() -> scrollPane.getViewport().setViewPosition(target));
        }
    }

    private static boolean preferIntegerFormatting(String initial) {
        String normalized = normalizeNumericText(initial);
        return normalized != null
                && !normalized.contains(".")
                && !normalized.contains("e")
                && !normalized.contains("E");
    }

    private static int initialDecimalPlaces(String initial) {
        String normalized = normalizeNumericText(initial);
        if (normalized == null) {
            return 4;
        }
        int dot = normalized.indexOf('.');
        if (dot < 0) {
            return 0;
        }
        return Math.max(0, Math.min(6, normalized.length() - dot - 1));
    }

    private static String formatNumericValue(JTextField field, double value) {
        boolean preferInteger = Boolean.TRUE.equals(field.getClientProperty(NUMERIC_INTEGER_PROPERTY));
        Object decimalsValue = field.getClientProperty(NUMERIC_DECIMALS_PROPERTY);
        int decimals = decimalsValue instanceof Integer integer ? integer : 4;
        if (preferInteger && Math.abs(value - Math.rint(value)) < 1e-9) {
            return Long.toString(Math.round(value));
        }
        String pattern = decimals <= 0 ? "0.####" : "0." + "#".repeat(Math.max(1, Math.min(6, decimals)));
        DecimalFormat format = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        format.setGroupingUsed(false);
        return format.format(value);
    }

    private static Double parseNumericExpression(String text) {
        String normalized = normalizeNumericText(text);
        if (normalized == null) {
            return null;
        }
        try {
            NumericExpressionParser parser = new NumericExpressionParser(normalized);
            double value = parser.parse();
            return Double.isFinite(value) ? value : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
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

    private static final class NumericExpressionParser {
        private final String text;
        private int index;

        NumericExpressionParser(String text) {
            this.text = text == null ? "" : text;
            this.index = 0;
        }

        double parse() {
            double value = parseExpression();
            if (index != text.length()) {
                throw new IllegalArgumentException("Unexpected trailing input");
            }
            return value;
        }

        private double parseExpression() {
            double value = parseTerm();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '+') {
                    index++;
                    value += parseTerm();
                } else if (c == '-') {
                    index++;
                    value -= parseTerm();
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseTerm() {
            double value = parseFactor();
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '*') {
                    index++;
                    value *= parseFactor();
                } else if (c == '/') {
                    index++;
                    double divisor = parseFactor();
                    if (Math.abs(divisor) < 1e-12) {
                        throw new IllegalArgumentException("Division by zero");
                    }
                    value /= divisor;
                } else {
                    break;
                }
            }
            return value;
        }

        private double parseFactor() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }
            char c = text.charAt(index);
            if (c == '+') {
                index++;
                return parseFactor();
            }
            if (c == '-') {
                index++;
                return -parseFactor();
            }
            if (c == '(') {
                index++;
                double value = parseExpression();
                if (index >= text.length() || text.charAt(index) != ')') {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                index++;
                return value;
            }
            int start = index;
            while (index < text.length()) {
                char current = text.charAt(index);
                if ((current >= '0' && current <= '9') || current == '.') {
                    index++;
                    continue;
                }
                if ((current == 'e' || current == 'E')
                        && index + 1 < text.length()
                        && ((text.charAt(index + 1) >= '0' && text.charAt(index + 1) <= '9')
                        || text.charAt(index + 1) == '+'
                        || text.charAt(index + 1) == '-')) {
                    index += 2;
                    while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') {
                        index++;
                    }
                    break;
                }
                break;
            }
            if (start == index) {
                throw new IllegalArgumentException("Expected number");
            }
            return Double.parseDouble(text.substring(start, index));
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
                UiTheme.stylePopupMenu(popup);
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
