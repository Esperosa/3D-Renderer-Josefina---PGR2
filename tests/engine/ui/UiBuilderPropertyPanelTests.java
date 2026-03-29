package engine.ui;

import engine.util.UiBuilder;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Component;

public final class UiBuilderPropertyPanelTests {

    private UiBuilderPropertyPanelTests() {
    }

    public static void main(String[] args) {
        testParseOrFallbackSupportsSimpleExpressions();
        testNormalizeNumericFieldFormatsCanonicalValue();
        testPanelHeaderUsesCompactHintBadge();
        System.out.println("UiBuilderPropertyPanelTests: ALL TESTS PASSED");
    }

    private static void testParseOrFallbackSupportsSimpleExpressions() {
        double value = UiBuilder.parseOrFallback("2*(3+4)-5/2", -1.0);
        if (Math.abs(value - 11.5) > 1e-9) {
            throw new AssertionError("Numeric parser should support simple arithmetic expressions.");
        }
    }

    private static void testNormalizeNumericFieldFormatsCanonicalValue() {
        JTextField integerField = new JTextField("2*(3+4)");
        integerField.putClientProperty("editor.numericPreferInteger", Boolean.TRUE);
        integerField.putClientProperty("editor.numericDecimals", 0);
        UiBuilder.normalizeNumericField(integerField);
        if (!"14".equals(integerField.getText())) {
            throw new AssertionError("Integer numeric field should normalize expressions to compact integer form.");
        }

        JTextField decimalField = new JTextField("1,5/2");
        decimalField.putClientProperty("editor.numericPreferInteger", Boolean.FALSE);
        decimalField.putClientProperty("editor.numericDecimals", 4);
        UiBuilder.normalizeNumericField(decimalField);
        if (!"0.75".equals(decimalField.getText())) {
            throw new AssertionError("Decimal numeric field should normalize locale input to canonical decimal text.");
        }
    }

    private static void testPanelHeaderUsesCompactHintBadge() {
        JPanel header = UiBuilder.panelHeader("Render", "Detailní vysvětlení patří jen do tooltipu.");
        JLabel infoBadge = findLabel(header, "i");
        if (infoBadge == null) {
            throw new AssertionError("Panel header should expose a compact info badge when subtitle text is provided.");
        }
        if (!(infoBadge.getToolTipText() != null && infoBadge.getToolTipText().contains("Detailní vysvětlení"))) {
            throw new AssertionError("Info badge should carry the full subtitle in tooltip form.");
        }
    }

    private static JLabel findLabel(JComponent component, String text) {
        for (Component child : component.getComponents()) {
            if (child instanceof JLabel label && text.equals(label.getText())) {
                return label;
            }
            if (child instanceof JComponent nested) {
                JLabel nestedFound = findLabel(nested, text);
                if (nestedFound != null) {
                    return nestedFound;
                }
            }
        }
        return null;
    }
}
