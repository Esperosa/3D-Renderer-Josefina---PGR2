import engine.util.UiBuilder;

public final class NumericInputParsingTests {

    private static final double EPS = 1e-9;

    private NumericInputParsingTests() {
    }

    public static void main(String[] args) {
        assertNear(0.15, UiBuilder.parseOrFallback(",15", -1.0), ",15");
        assertNear(0.15, UiBuilder.parseOrFallback(".15", -1.0), ".15");
        assertNear(-0.15, UiBuilder.parseOrFallback("-,15", -1.0), "-,15");
        assertNear(-0.15, UiBuilder.parseOrFallback("-.15", -1.0), "-.15");
        assertNear(12.5, UiBuilder.parseOrFallback("12,5", -1.0), "12,5");
        String normalized = UiBuilder.normalizeNumericText(" -,15 ");
        if (!"-0.15".equals(normalized)) {
            throw new AssertionError("Expected normalized -0.15, got " + normalized);
        }
        System.out.println("NumericInputParsingTests: ALL TESTS PASSED");
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
}
