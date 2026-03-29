package engine.ui;

import engine.io.FileUtil;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicMenuUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public final class UiTheme {

    public static final Color APP_BG = new Color(15, 19, 25);
    public static final Color TOOLBAR_BG = new Color(18, 24, 31);
    public static final Color TOOLBAR_GROUP_BG = new Color(26, 34, 44);
    public static final Color TOOLBAR_GROUP_BORDER = new Color(58, 71, 86);
    public static final Color PANEL_BG = new Color(19, 24, 31);
    public static final Color PANEL_ELEVATED = new Color(25, 31, 39);
    public static final Color PANEL_INSET = new Color(15, 20, 27);
    public static final Color VIEWPORT_BG = new Color(11, 14, 18);
    public static final Color SECTION_HEADER_BG = new Color(31, 39, 50);
    public static final Color SECTION_HEADER_HOVER_BG = new Color(38, 48, 62);
    public static final Color SECTION_HEADER_ACTIVE_BG = new Color(60, 49, 102);
    public static final Color SECTION_BODY_BG = new Color(23, 29, 37);
    public static final Color SECTION_CARD_BG = new Color(24, 31, 40);
    public static final Color TAB_BG = new Color(23, 28, 36);
    public static final Color TAB_SELECTED_BG = new Color(42, 36, 62);
    public static final Color TAB_HOVER_BG = new Color(31, 40, 51);
    public static final Color BORDER_SUBTLE = new Color(54, 65, 78);
    public static final Color BORDER_STRONG = new Color(82, 98, 116);
    public static final Color ACCENT = new Color(244, 142, 61);
    public static final Color ACCENT_SOFT = new Color(105, 63, 28);
    public static final Color ACCENT_GLOW = new Color(255, 188, 119);
    public static final Color ACCENT_BLUE = ACCENT;
    public static final Color ACCENT_PURPLE = new Color(0x74, 0x44, 0xFF);
    public static final Color ACCENT_PURPLE_SOFT = new Color(61, 44, 123);
    public static final Color ACCENT_PURPLE_GLOW = new Color(174, 154, 255);
    public static final Color SELECTION_BG = new Color(73, 55, 135);
    public static final Color SELECTION_BG_SOFT = new Color(58, 44, 106);
    public static final Color TEXT_PRIMARY = new Color(233, 239, 246);
    public static final Color TEXT_SECONDARY = new Color(193, 204, 216);
    public static final Color TEXT_MUTED = new Color(144, 157, 171);
    public static final Color TEXT_HINT = new Color(119, 132, 146);
    public static final Color SUCCESS = new Color(109, 189, 148);
    public static final Color WARNING = new Color(255, 188, 119);
    public static final Color ERROR = new Color(226, 104, 104);
    public static final Color INFO = ACCENT_GLOW;

    public static final int SPACE_1 = 4;
    public static final int SPACE_2 = 8;
    public static final int SPACE_3 = 12;
    public static final int SPACE_4 = 16;
    public static final int SPACE_5 = 20;
    public static final int BUTTON_HEIGHT = 34;
    public static final int COMPACT_BUTTON_HEIGHT = 30;
    public static final int INPUT_HEIGHT = 32;
    public static final int SECTION_HEADER_HEIGHT = 38;
    public static final int TAB_HEIGHT = 42;
    public static final int RIGHT_PANEL_MIN = 340;
    public static final int RIGHT_PANEL_MAX = 560;
    public static final int MATERIAL_WORKSPACE_INSPECTOR_WIDTH = 460;
    public static final int MATERIAL_WORKSPACE_INSPECTOR_MIN_WIDTH = 420;
    public static final int BOTTOM_DOCK_DEFAULT_HEIGHT = 72;
    public static final int BOTTOM_DOCK_MIN_HEIGHT = 72;
    private static final int DEFAULT_COMBO_MIN_WIDTH = 140;
    private static final int DEFAULT_COMBO_HORIZONTAL_PADDING = 8;
    private static final int DEFAULT_COMBO_ITEM_VERTICAL_PADDING = 4;
    private static final int TOOLBAR_COMBO_MIN_WIDTH = 120;
    private static final int TOOLBAR_BUTTON_MIN_WIDTH = 52;
    private static final Font POPUP_ITEM_FONT = new Font(Font.DIALOG, Font.PLAIN, 13);
    private static final Font POPUP_MENU_FONT = new Font(Font.DIALOG, Font.BOLD, 13);

    private static Icon brandIcon16;

    public record ToolbarMetrics(
            float buttonFontSize,
            float badgeFontSize,
            float comboFontSize,
            int buttonHeight,
            int controlHeight,
            int horizontalPadding,
            int verticalPadding,
            int groupHGap,
            int groupVGap,
            int rowHGap,
            int comboMinWidth,
            int badgeHorizontalPadding,
            int badgeVerticalPadding) {
    }

    private UiTheme() {
    }

    public static Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(SPACE_3, SPACE_3, SPACE_3, SPACE_3)
        );
    }

    public static Border fieldBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        );
    }

    public static Border accentBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(153, 102, 49), 1, true),
                BorderFactory.createEmptyBorder(5, 8, 5, 8)
        );
    }

    public static void styleToolbarPanel(JPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setOpaque(true);
        panel.setBackground(TOOLBAR_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
    }

    public static void styleToolbarGroup(JPanel panel) {
        if (panel == null) {
            return;
        }
        panel.setOpaque(true);
        panel.setBackground(TOOLBAR_GROUP_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TOOLBAR_GROUP_BORDER, 1, true),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)
        ));
    }

    public static ToolbarMetrics toolbarMetricsForWidth(int availableWidth) {
        int safeWidth = Math.max(980, availableWidth);
        double t = clamp01((safeWidth - 1080.0) / 920.0);
        return new ToolbarMetrics(
                lerp(10.0f, 12.5f, t),
                lerp(10.0f, 11.5f, t),
                lerp(10.0f, 12.0f, t),
                lerp(28, 34, t),
                lerp(28, 32, t),
                lerp(6, 10, t),
                lerp(5, 8, t),
                lerp(4, 6, t),
                lerp(3, 4, t),
                lerp(6, 10, t),
                lerp(200, 320, t),
                lerp(6, 8, t),
                lerp(2, 3, t)
        );
    }

    public static void styleScrollPane(JScrollPane scroll, Color viewportBackground) {
        if (scroll == null) {
            return;
        }
        Color bg = viewportBackground == null ? PANEL_BG : viewportBackground;
        scroll.setBorder(BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true));
        scroll.getViewport().setBackground(bg);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        scroll.getHorizontalScrollBar().setUnitIncrement(16);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUi());
        scroll.getHorizontalScrollBar().setUI(new FlatScrollBarUi());
    }

    public static void stylePopupMenu(JPopupMenu popup) {
        if (popup == null) {
            return;
        }
        installPopupUiDefaults();
        popup.setOpaque(true);
        popup.setBackground(PANEL_INSET);
        popup.setForeground(TEXT_PRIMARY);
        popup.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
        ));
        popup.setLightWeightPopupEnabled(false);
        popup.setUI(new BasicPopupMenuUI());
        for (Component component : popup.getComponents()) {
            stylePopupComponent(component);
        }
    }

    public static void styleMenuItem(JMenuItem item) {
        if (item == null) {
            return;
        }
        boolean enabled = item.isEnabled();
        item.setOpaque(true);
        item.setFocusable(false);
        item.setBackground(PANEL_ELEVATED);
        item.setForeground(enabled ? TEXT_PRIMARY : TEXT_HINT);
        item.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 14));
        item.setIconTextGap(8);
        item.setFont(item instanceof JMenu ? POPUP_MENU_FONT : POPUP_ITEM_FONT);
        if (item instanceof JMenu menu) {
            menu.setUI(new BasicMenuUI());
            stylePopupMenu(menu.getPopupMenu());
        } else {
            item.setUI(new BasicMenuItemUI());
        }
    }

    private static void stylePopupComponent(Component component) {
        if (component instanceof JMenuItem item) {
            styleMenuItem(item);
            return;
        }
        if (component instanceof JSeparator separator) {
            separator.setForeground(BORDER_SUBTLE);
            separator.setBackground(PANEL_INSET);
        }
    }

    private static void installPopupUiDefaults() {
        Border popupBorder = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(6, 6, 6, 6)
        );
        UIManager.put("PopupMenu.border", popupBorder);
        UIManager.put("PopupMenu.background", PANEL_INSET);
        UIManager.put("PopupMenu.foreground", TEXT_PRIMARY);
        UIManager.put("Menu.font", new FontUIResource(POPUP_MENU_FONT));
        UIManager.put("Menu.foreground", TEXT_PRIMARY);
        UIManager.put("Menu.background", PANEL_ELEVATED);
        UIManager.put("Menu.selectionForeground", TEXT_PRIMARY);
        UIManager.put("Menu.selectionBackground", SELECTION_BG_SOFT);
        UIManager.put("MenuItem.font", new FontUIResource(POPUP_ITEM_FONT));
        UIManager.put("MenuItem.foreground", TEXT_PRIMARY);
        UIManager.put("MenuItem.background", PANEL_ELEVATED);
        UIManager.put("MenuItem.selectionForeground", TEXT_PRIMARY);
        UIManager.put("MenuItem.selectionBackground", SELECTION_BG_SOFT);
    }

    public static void styleInspectorField(JTextField field) {
        if (field == null) {
            return;
        }
        field.setBackground(PANEL_ELEVATED);
        field.setForeground(TEXT_PRIMARY);
        field.setCaretColor(ACCENT_GLOW);
        field.setSelectionColor(SELECTION_BG_SOFT);
        field.setSelectedTextColor(TEXT_PRIMARY);
        field.setBorder(fieldBorder());
        Dimension preferred = field.getPreferredSize();
        field.setPreferredSize(new Dimension(Math.max(120, preferred.width), INPUT_HEIGHT));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, INPUT_HEIGHT));
    }

    public static void styleComboBox(JComboBox<?> combo) {
        if (combo == null) {
            return;
        }
        styleComboBoxBase(combo, combo.getFont(), DEFAULT_COMBO_HORIZONTAL_PADDING);
        applyComboBoxSizing(combo, DEFAULT_COMBO_MIN_WIDTH, DEFAULT_COMBO_MIN_WIDTH, INPUT_HEIGHT);
    }

    public static void styleToolbarComboBox(JComboBox<?> combo, ToolbarMetrics metrics) {
        if (combo == null || metrics == null) {
            return;
        }
        Font comboFont = deriveToolbarFont(combo.getFont(), Font.BOLD, metrics.comboFontSize());
        styleComboBoxBase(combo, comboFont, metrics.horizontalPadding());
        applyComboBoxSizing(combo, metrics.comboMinWidth(), TOOLBAR_COMBO_MIN_WIDTH, metrics.controlHeight());
    }

    public static void styleCheckBox(JCheckBox checkBox) {
        if (checkBox == null) {
            return;
        }
        checkBox.setOpaque(false);
        checkBox.setForeground(TEXT_SECONDARY);
        checkBox.setFocusPainted(false);
        checkBox.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
        checkBox.setIconTextGap(8);
        Icon icon = ThemedCheckBoxIcon.INSTANCE;
        checkBox.setIcon(icon);
        checkBox.setSelectedIcon(icon);
        checkBox.setDisabledIcon(icon);
        checkBox.setDisabledSelectedIcon(icon);
        checkBox.setPressedIcon(icon);
        checkBox.setRolloverIcon(icon);
        checkBox.setRolloverSelectedIcon(icon);
    }

    public static void styleSectionHeader(JToggleButton header) {
        if (header == null) {
            return;
        }
        header.setFocusPainted(false);
        header.setOpaque(false);
        header.setContentAreaFilled(false);
        header.setBorderPainted(false);
        header.setRolloverEnabled(true);
        header.setForeground(TEXT_PRIMARY);
        header.setBackground(SECTION_HEADER_BG);
        header.setHorizontalAlignment(JToggleButton.LEFT);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_STRONG, 1, true),
                BorderFactory.createEmptyBorder(7, 10, 7, 10)
        ));
    }

    public static void styleSectionBody(JPanel body) {
        if (body == null) {
            return;
        }
        body.setOpaque(true);
        body.setBackground(SECTION_BODY_BG);
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(10, 10, 8, 10)
        ));
    }

    public static void stylePrimaryButton(AbstractButton button) {
        styleButton(button, ACCENT, new Color(94, 57, 24), TEXT_PRIMARY);
    }

    public static void styleSecondaryButton(AbstractButton button) {
        styleButton(button, new Color(43, 57, 74), BORDER_STRONG, TEXT_PRIMARY);
    }

    public static void styleGhostButton(AbstractButton button) {
        styleButton(button, PANEL_ELEVATED, BORDER_SUBTLE, TEXT_SECONDARY);
    }

    public static void styleToolbarToggle(AbstractButton button, boolean active, ToolbarMetrics metrics) {
        if (button == null || metrics == null) {
            return;
        }
        if (active) {
            styleToolbarButton(button, ACCENT_PURPLE_SOFT, ACCENT_PURPLE, TEXT_PRIMARY, metrics);
        } else {
            styleToolbarButton(button, new Color(35, 46, 58), BORDER_SUBTLE, TEXT_SECONDARY, metrics);
        }
        button.setSelected(active);
    }

    public static void styleToolbarGhostButton(AbstractButton button, ToolbarMetrics metrics) {
        if (button == null || metrics == null) {
            return;
        }
        styleToolbarButton(button, PANEL_ELEVATED, BORDER_SUBTLE, TEXT_SECONDARY, metrics);
    }

    public static void styleToolbarBadge(JLabel label, ToolbarMetrics metrics) {
        if (label == null || metrics == null) {
            return;
        }
        label.setOpaque(true);
        label.setBackground(new Color(48, 39, 31));
        label.setForeground(ACCENT_GLOW);
        label.setFont(deriveToolbarFont(label.getFont(), Font.BOLD, metrics.badgeFontSize()));
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(119, 80, 41), 1, true),
                BorderFactory.createEmptyBorder(
                        metrics.badgeVerticalPadding(),
                        metrics.badgeHorizontalPadding(),
                        metrics.badgeVerticalPadding(),
                        metrics.badgeHorizontalPadding())
        ));
    }

    private static void styleButton(AbstractButton button, Color background, Color border, Color foreground) {
        if (button == null) {
            return;
        }
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        Dimension preferred = button.getPreferredSize();
        button.setPreferredSize(new Dimension(preferred.width, BUTTON_HEIGHT));
    }

    private static void styleToolbarButton(
            AbstractButton button,
            Color background,
            Color border,
            Color foreground,
            ToolbarMetrics metrics) {
        button.setFocusPainted(false);
        button.setOpaque(true);
        button.setBackground(background);
        button.setForeground(foreground);
        button.setFont(deriveToolbarFont(button.getFont(), Font.BOLD, metrics.buttonFontSize()));
        button.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border, 1, true),
                BorderFactory.createEmptyBorder(
                        metrics.verticalPadding(),
                        metrics.horizontalPadding(),
                        metrics.verticalPadding(),
                        metrics.horizontalPadding())
        ));
        button.setPreferredSize(null);
        button.setMinimumSize(null);
        Dimension preferred = button.getPreferredSize();
        button.setPreferredSize(new Dimension(Math.max(TOOLBAR_BUTTON_MIN_WIDTH, preferred.width), metrics.buttonHeight()));
        button.setMinimumSize(new Dimension(TOOLBAR_BUTTON_MIN_WIDTH, metrics.buttonHeight()));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, metrics.buttonHeight()));
    }

    private static void styleComboBoxBase(JComboBox<?> combo, Font font, int horizontalPadding) {
        combo.setFocusable(false);
        combo.setBackground(PANEL_ELEVATED);
        combo.setForeground(TEXT_PRIMARY);
        combo.setBorder(BorderFactory.createLineBorder(BORDER_STRONG, 1, true));
        if (font != null) {
            combo.setFont(font);
        }
        combo.setRenderer(createComboBoxRenderer(font, horizontalPadding));
    }

    private static DefaultListCellRenderer createComboBoxRenderer(Font font, int horizontalPadding) {
        return new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list,
                                                          Object value,
                                                          int index,
                                                          boolean isSelected,
                                                          boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setOpaque(true);
                if (font != null) {
                    setFont(font);
                }
                setBackground(isSelected ? SELECTION_BG_SOFT : PANEL_ELEVATED);
                setForeground(isSelected ? TEXT_PRIMARY : TEXT_SECONDARY);
                setBorder(BorderFactory.createEmptyBorder(
                        DEFAULT_COMBO_ITEM_VERTICAL_PADDING,
                        horizontalPadding,
                        DEFAULT_COMBO_ITEM_VERTICAL_PADDING,
                        horizontalPadding));
                return this;
            }
        };
    }

    private static void applyComboBoxSizing(JComboBox<?> combo, int preferredMinWidth, int minWidth, int height) {
        combo.setPreferredSize(null);
        combo.setMinimumSize(null);
        Dimension preferred = combo.getPreferredSize();
        combo.setPreferredSize(new Dimension(Math.max(preferredMinWidth, preferred.width), height));
        combo.setMinimumSize(new Dimension(minWidth, height));
        combo.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
    }

    public static void styleToggle(AbstractButton button, boolean active) {
        if (button == null) {
            return;
        }
        if (active) {
            styleButton(button, ACCENT_PURPLE_SOFT, ACCENT_PURPLE, TEXT_PRIMARY);
        } else {
            styleButton(button, new Color(35, 46, 58), BORDER_SUBTLE, TEXT_SECONDARY);
        }
        button.setSelected(active);
    }

    public static JLabel createSectionTitle(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setAlignmentX(0.0f);
        label.setForeground(TEXT_SECONDARY);
        label.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return label;
    }

    public static JLabel createHelperText(String text) {
        JLabel label = new JLabel(text == null ? "" : "<html>" + text + "</html>");
        label.setAlignmentX(0.0f);
        label.setForeground(TEXT_MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 2));
        label.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return label;
    }

    public static JLabel createBadge(String text) {
        JLabel label = new JLabel(text == null ? "" : text);
        label.setOpaque(true);
        label.setBackground(new Color(48, 39, 31));
        label.setForeground(ACCENT_GLOW);
        label.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(119, 80, 41), 1, true),
                BorderFactory.createEmptyBorder(3, 8, 3, 8)
        ));
        return label;
    }

    public static JPanel createPanelHeader(String title, String subtitle) {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setOpaque(true);
        panel.setBackground(SECTION_CARD_BG);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(10, 12, 10, 12)
        ));
        JLabel titleLabel = new JLabel(title == null ? "" : title);
        titleLabel.setForeground(TEXT_PRIMARY);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 14f));
        panel.add(titleLabel, BorderLayout.WEST);
        if (subtitle != null && !subtitle.isBlank()) {
            JLabel hint = new JLabel("i");
            hint.setOpaque(true);
            hint.setHorizontalAlignment(JLabel.CENTER);
            hint.setPreferredSize(new Dimension(18, 18));
            hint.setBackground(new Color(41, 50, 61));
            hint.setForeground(TEXT_MUTED);
            hint.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(BORDER_SUBTLE, 1, true),
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)
            ));
            hint.setToolTipText("<html><div style='width:220px'>" + subtitle + "</div></html>");
            titleLabel.setToolTipText(hint.getToolTipText());
            panel.add(hint, BorderLayout.EAST);
        }
        return panel;
    }

    public static void installTabbedPaneTheme(javax.swing.JTabbedPane tabbedPane) {
        if (tabbedPane == null) {
            return;
        }
        tabbedPane.setOpaque(true);
        tabbedPane.setBackground(TAB_BG);
        tabbedPane.setForeground(TEXT_SECONDARY);
        tabbedPane.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        tabbedPane.setUI(new PropertiesTabbedPaneUi());
    }

    public static Icon brandIcon(int size) {
        if (size <= 16 && brandIcon16 != null) {
            return brandIcon16;
        }
        BufferedImage image = loadBrandImage();
        if (image == null) {
            return UIManager.getIcon("OptionPane.informationIcon");
        }
        Image scaled = image.getScaledInstance(Math.max(12, size), Math.max(12, size), Image.SCALE_SMOOTH);
        ImageIcon icon = new ImageIcon(scaled);
        if (size <= 16) {
            brandIcon16 = icon;
        }
        return icon;
    }

    private static BufferedImage loadBrandImage() {
        String path = "assets/icons/IcoUni.png";
        if (!FileUtil.exists(path)) {
            return null;
        }
        try {
            return ImageIO.read(new File(path));
        } catch (IOException ex) {
            return null;
        }
    }

    private static final class FlatScrollBarUi extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(74, 87, 104);
            trackColor = new Color(24, 30, 38);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }
    }

    private static Font deriveToolbarFont(Font baseFont, int style, float size) {
        Font seed = baseFont == null ? new Font(Font.DIALOG, style, Math.round(size)) : baseFont;
        return seed.deriveFont(style, size);
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static float lerp(float min, float max, double t) {
        return (float) (min + (max - min) * t);
    }

    private static int lerp(int min, int max, double t) {
        return (int) Math.round(min + (max - min) * t);
    }

    private static final class PropertiesTabbedPaneUi extends BasicTabbedPaneUI {
        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabInsets = new Insets(8, 8, 8, 8);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            contentBorderInsets = new Insets(0, 0, 0, 0);
            tabAreaInsets = new Insets(8, 6, 8, 4);
        }

        @Override
        protected int calculateTabHeight(int tabPlacement, int tabIndex, int fontHeight) {
            return Math.max(TAB_HEIGHT, super.calculateTabHeight(tabPlacement, tabIndex, fontHeight));
        }

        @Override
        protected int calculateTabWidth(int tabPlacement, int tabIndex, FontMetrics metrics) {
            String title = tabPane.getTitleAt(tabIndex);
            Icon icon = getIconForTab(tabIndex);
            if ((title == null || title.isBlank()) && icon != null) {
                return 42;
            }
            return Math.max(112, super.calculateTabWidth(tabPlacement, tabIndex, metrics) + 6);
        }

        @Override
        protected void paintTabBackground(Graphics g, int tabPlacement, int tabIndex,
                                          int x, int y, int w, int h, boolean isSelected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isSelected ? TAB_SELECTED_BG : TAB_BG);
            g2.fillRoundRect(x + 3, y + 2, w - 6, h - 4, 12, 12);
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex,
                                      int x, int y, int w, int h, boolean isSelected) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(isSelected ? ACCENT_PURPLE : BORDER_SUBTLE);
            g2.setStroke(new BasicStroke(isSelected ? 1.4f : 1.0f));
            g2.drawRoundRect(x + 3, y + 2, w - 7, h - 5, 12, 12);
            g2.dispose();
        }

        @Override
        protected void paintText(Graphics g, int tabPlacement, Font font, FontMetrics metrics,
                                 int tabIndex, String title, Rectangle textRect, boolean isSelected) {
            g.setFont(font);
            g.setColor(isSelected ? TEXT_PRIMARY : TEXT_SECONDARY);
            super.paintText(g, tabPlacement, font, metrics, tabIndex, title, textRect, isSelected);
        }

        @Override
        protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
 // pro pravý properties panel nechávám vlastní border, aby se mi neslil s viewportem.
        }
    }

    private static final class ThemedCheckBoxIcon implements Icon {
        private static final ThemedCheckBoxIcon INSTANCE = new ThemedCheckBoxIcon();
        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            boolean enabled = c == null || c.isEnabled();
            boolean selected = c instanceof AbstractButton button && button.getModel().isSelected();
            Color fill = selected
                    ? (enabled ? SELECTION_BG_SOFT : new Color(56, 51, 71))
                    : PANEL_ELEVATED;
            Color border = selected
                    ? (enabled ? ACCENT_PURPLE : BORDER_STRONG)
                    : BORDER_STRONG;

            g2.setColor(fill);
            g2.fillRoundRect(x, y, SIZE, SIZE, 6, 6);
            g2.setColor(border);
            g2.drawRoundRect(x, y, SIZE - 1, SIZE - 1, 6, 6);

            if (selected) {
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.setColor(enabled ? TEXT_PRIMARY : TEXT_MUTED);
                g2.drawLine(x + 4, y + 8, x + 7, y + 11);
                g2.drawLine(x + 7, y + 11, x + 12, y + 5);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
