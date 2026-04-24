package engine.core;

import engine.ui.UiTheme;

public final class WindowResponsiveLayoutTests {

    private WindowResponsiveLayoutTests() {
    }

    public static void main(String[] args) {
        testRightPanelNarrowsOnTallerAspectRatio();
        testRightPanelClampsOnUltrawideDisplays();
        testTimelineDockGetsMoreRoomOnTallDisplays();
        System.out.println("WindowResponsiveLayoutTests: ALL TESTS PASSED");
    }

    private static void testRightPanelNarrowsOnTallerAspectRatio() {
        int wide16by9 = Window.computeRightPanelWidth(1920, 1080);
        int tall16by10 = Window.computeRightPanelWidth(1920, 1200);

        if (tall16by10 >= wide16by9) {
            throw new AssertionError("16:10 layout should reserve less width for the right panel than 16:9.");
        }
        if (tall16by10 < UiTheme.RIGHT_PANEL_MIN) {
            throw new AssertionError("Right panel width must stay above the minimum responsive width.");
        }
    }

    private static void testRightPanelClampsOnUltrawideDisplays() {
        int ultrawide = Window.computeRightPanelWidth(3440, 1440);
        if (ultrawide != UiTheme.RIGHT_PANEL_MAX) {
            throw new AssertionError("Ultrawide layouts should clamp the right panel at the configured maximum width.");
        }
    }

    private static void testTimelineDockGetsMoreRoomOnTallDisplays() {
        int compact = Window.computeDefaultTimelineDockHeight(760);
        int tall = Window.computeDefaultTimelineDockHeight(1200);

        if (compact < UiTheme.BOTTOM_DOCK_DEFAULT_HEIGHT) {
            throw new AssertionError("Compact layouts must respect the minimum timeline dock height.");
        }
        if (tall <= compact) {
            throw new AssertionError("Taller layouts should provide a larger default timeline dock.");
        }
        if (tall > 180) {
            throw new AssertionError("Timeline dock height should stay within the intended responsive cap.");
        }
    }
}
