package engine.core;

import javax.swing.JPanel;
import java.awt.Dimension;

public final class EngineToolbarLayoutTests {

    private EngineToolbarLayoutTests() {
    }

    public static void main(String[] args) {
        testPrimaryRowDistributesExtraWidthToFlexibleGroups();
        System.out.println("EngineToolbarLayoutTests: ALL TESTS PASSED");
    }

    private static void testPrimaryRowDistributesExtraWidthToFlexibleGroups() {
        JPanel navigationGroup = groupWithPreferredWidth(320);
        JPanel systemGroup = groupWithPreferredWidth(260);
        JPanel helpGroup = groupWithPreferredWidth(180);
        JPanel row = EngineToolbarController.toolbarPrimaryRow();

        EngineToolbarController.addPrimaryToolbarGroup(row, navigationGroup, 0, 1.2);
        EngineToolbarController.addPrimaryToolbarGroup(row, systemGroup, 1, 1.0);
        EngineToolbarController.addPrimaryToolbarGroup(row, helpGroup, 2, 0.0);
        EngineToolbarController.applyPrimaryToolbarRowGap(row, 12);

        row.setSize(1440, 72);
        row.doLayout();

        int usedWidth = helpGroup.getX() + helpGroup.getWidth();
        if (usedWidth < row.getWidth() - 2) {
            throw new AssertionError("Primary toolbar row should consume the available horizontal space.");
        }
        if (navigationGroup.getWidth() <= navigationGroup.getPreferredSize().width) {
            throw new AssertionError("Navigation group should expand beyond its preferred width when space is available.");
        }
        if (systemGroup.getWidth() <= systemGroup.getPreferredSize().width) {
            throw new AssertionError("System group should expand beyond its preferred width when space is available.");
        }
        if (navigationGroup.getWidth() <= systemGroup.getWidth()) {
            throw new AssertionError("Navigation group should receive a larger share of extra width than the system group.");
        }
        if (helpGroup.getWidth() > helpGroup.getPreferredSize().width + 1) {
            throw new AssertionError("Help group should stay compact instead of stretching across the row.");
        }
    }

    private static JPanel groupWithPreferredWidth(int width) {
        JPanel group = new JPanel();
        group.setPreferredSize(new Dimension(width, 44));
        return group;
    }
}
