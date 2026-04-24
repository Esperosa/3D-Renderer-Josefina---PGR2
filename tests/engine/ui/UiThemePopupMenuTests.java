package engine.ui;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

public final class UiThemePopupMenuTests {

    private UiThemePopupMenuTests() {
    }

    public static void main(String[] args) {
        testPopupMenuUsesThemedColorsAndHeavyweightMode();
        testPopupMenuKeepsCzechGlyphsReadableInSubmenus();
        System.out.println("UiThemePopupMenuTests: ALL TESTS PASSED");
    }

    private static void testPopupMenuUsesThemedColorsAndHeavyweightMode() {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem item = new JMenuItem("Přidat světlo");
        popup.add(item);

        UiTheme.stylePopupMenu(popup);

        if (popup.isLightWeightPopupEnabled()) {
            throw new AssertionError("Viewport popup should use heavyweight mode so it renders above AWT canvas consistently.");
        }
        if (!UiTheme.PANEL_INSET.equals(popup.getBackground())) {
            throw new AssertionError("Popup background should use the themed panel inset color.");
        }
        if (!UiTheme.TEXT_PRIMARY.equals(item.getForeground())) {
            throw new AssertionError("Popup items should use the themed primary text color.");
        }
    }

    private static void testPopupMenuKeepsCzechGlyphsReadableInSubmenus() {
        JPopupMenu popup = new JPopupMenu();
        JMenu submenu = new JMenu("Světla");
        JMenuItem child = new JMenuItem("Přidat bodové světlo");
        submenu.add(child);
        popup.add(submenu);

        UiTheme.stylePopupMenu(popup);

        if (submenu.getFont().canDisplayUpTo("Světla") != -1) {
            throw new AssertionError("Menu font should support Czech labels without missing glyphs.");
        }
        if (child.getFont().canDisplayUpTo("Přidat bodové světlo") != -1) {
            throw new AssertionError("Menu item font should support Czech labels without missing glyphs.");
        }
        if (!UiTheme.PANEL_INSET.equals(submenu.getPopupMenu().getBackground())) {
            throw new AssertionError("Submenu popup should inherit the themed popup background.");
        }
    }
}
