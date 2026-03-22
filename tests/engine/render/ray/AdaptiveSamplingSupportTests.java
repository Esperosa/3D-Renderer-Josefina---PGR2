package engine.render.ray;

public final class AdaptiveSamplingSupportTests {

    private AdaptiveSamplingSupportTests() {
    }

    public static void main(String[] args) {
        testSkipRequiresMinimumSamples();
        testLowNoisePixelCanConverge();
        testInverseSampleCountFallsBackToGlobalHistory();
        System.out.println("AdaptiveSamplingSupportTests: ALL TESTS PASSED");
    }

    private static void testSkipRequiresMinimumSamples() {
        boolean shouldSkip = AdaptiveSamplingSupport.shouldSkipPixel(3, 8, 0.05, 0.6, 0.12);
        if (shouldSkip) {
            throw new AssertionError("Adaptive sampling should not skip before the minimum sample budget is reached.");
        }
    }

    private static void testLowNoisePixelCanConverge() {
        int sampleCount = 12;
        double luma = 2.4;
        double lumaSq = (2.4 * 2.4) / sampleCount;
        boolean shouldSkip = AdaptiveSamplingSupport.shouldSkipPixel(sampleCount, 8, 0.05, luma, lumaSq);
        if (!shouldSkip) {
            throw new AssertionError("Stable pixels should become skippable once noise drops below the threshold.");
        }
    }

    private static void testInverseSampleCountFallsBackToGlobalHistory() {
        double fallbackInv = AdaptiveSamplingSupport.inverseSampleCount(0, 16L);
        double localInv = AdaptiveSamplingSupport.inverseSampleCount(4, 16L);
        assertNear(1.0 / 16.0, fallbackInv, 1e-12, "Fallback inverse sample count should use global history.");
        assertNear(0.25, localInv, 1e-12, "Local inverse sample count should prefer the per-pixel history.");
    }

    private static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
