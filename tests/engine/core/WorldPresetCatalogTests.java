package engine.core;

public final class WorldPresetCatalogTests {

    private WorldPresetCatalogTests() {
    }

    public static void main(String[] args) {
        testLegacyPresetAliasesResolveToCurrentDefinitions();
        testLabelsRoundTripBackToPresetKeys();
        System.out.println("WorldPresetCatalogTests: ALL TESTS PASSED");
    }

    private static void testLegacyPresetAliasesResolveToCurrentDefinitions() {
        assertKey(WorldPresetCatalog.CLASSIC_DAY.key(), WorldPresetCatalog.resolve("High Contrast").key(),
                "Legacy contrast preset should map to the classic day definition.");
        assertKey(WorldPresetCatalog.STUDIO_SOFT.key(), WorldPresetCatalog.resolve("Studio Neutral").key(),
                "Legacy studio preset should map to the studio soft definition.");
        assertKey(WorldPresetCatalog.CLOUDY_DAY.key(), WorldPresetCatalog.resolve(null).key(),
                "Null preset should safely fall back to the default cloudy preset.");
    }

    private static void testLabelsRoundTripBackToPresetKeys() {
        for (String label : WorldPresetCatalog.labels()) {
            String key = WorldPresetCatalog.resolveKey(label);
            String roundTripLabel = WorldPresetCatalog.resolveLabel(key);
            if (!label.equals(roundTripLabel)) {
                throw new AssertionError("Preset label should round-trip cleanly. label="
                        + label + " roundTrip=" + roundTripLabel);
            }
        }
    }

    private static void assertKey(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
