package engine.ui;

import javax.swing.JComboBox;
import javax.swing.JToggleButton;

public final class UiThemeToolbarResponsiveTests {

    private UiThemeToolbarResponsiveTests() {
    }

    public static void main(String[] args) {
        testToolbarMetricsShrinkFontsOnNarrowWidths();
        testToolbarToggleRecalculatesWidthForUpdatedText();
        testToolbarComboUsesResponsiveHeightAndWidth();
        System.out.println("UiThemeToolbarResponsiveTests: ALL TESTS PASSED");
    }

    private static void testToolbarMetricsShrinkFontsOnNarrowWidths() {
        UiTheme.ToolbarMetrics wide = UiTheme.toolbarMetricsForWidth(1920);
        UiTheme.ToolbarMetrics narrow = UiTheme.toolbarMetricsForWidth(1080);

        if (narrow.buttonFontSize() >= wide.buttonFontSize()) {
            throw new AssertionError("Narrow toolbar widths should reduce button font size.");
        }
        if (narrow.buttonHeight() >= wide.buttonHeight()) {
            throw new AssertionError("Narrow toolbar widths should reduce button height.");
        }
        if (narrow.comboMinWidth() >= wide.comboMinWidth()) {
            throw new AssertionError("Narrow toolbar widths should reduce combo minimum width.");
        }
    }

    private static void testToolbarToggleRecalculatesWidthForUpdatedText() {
        UiTheme.ToolbarMetrics metrics = UiTheme.toolbarMetricsForWidth(1600);
        JToggleButton toggle = new JToggleButton("Vlákna");

        UiTheme.styleToolbarToggle(toggle, false, metrics);
        int shortWidth = toggle.getPreferredSize().width;

        toggle.setText("Vlákna x16");
        UiTheme.styleToolbarToggle(toggle, false, metrics);
        int longWidth = toggle.getPreferredSize().width;

        if (longWidth <= shortWidth) {
            throw new AssertionError("Toolbar toggle should expand when its text becomes longer.");
        }
    }

    private static void testToolbarComboUsesResponsiveHeightAndWidth() {
        JComboBox<String> combo = new JComboBox<>(new String[]{
                "Kliknutí: zaměřit a zaostřit",
                "Kliknutí: pouze vybrat"
        });
        UiTheme.ToolbarMetrics metrics = UiTheme.toolbarMetricsForWidth(1200);

        UiTheme.styleToolbarComboBox(combo, metrics);

        if (combo.getPreferredSize().height != metrics.controlHeight()) {
            throw new AssertionError("Toolbar combo should use the responsive control height.");
        }
        if (combo.getPreferredSize().width < metrics.comboMinWidth()) {
            throw new AssertionError("Toolbar combo should respect the responsive minimum width.");
        }
    }
}
