package engine.core;

import engine.math.Vec3;
import engine.physics.PhysicsWorld;
import engine.scene.Scene;
import engine.ui.UiStrings;

public final class WorldPresetHistoryTests {

    private static final double EPS = 1e-9;

    private WorldPresetHistoryTests() {
    }

    public static void main(String[] args) {
        testWorldPresetStateSurvivesUndoRedoWithoutUi();
        System.out.println("WorldPresetHistoryTests: ALL TESTS PASSED");
    }

    private static void testWorldPresetStateSurvivesUndoRedoWithoutUi() {
        Engine engine = createEngine();
        String defaultPreset = UiStrings.worldPresetKey(UiStrings.World.PRESET_CLOUDY);
        String sunsetPreset = UiStrings.worldPresetKey(UiStrings.World.PRESET_SUNSET);
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        assertEquals(defaultPreset, engine.worldPresetKey, "Vychozi preset prostredi musi zacinat na oblacnem dni");

        engine.applyWorldPreset(UiStrings.World.PRESET_SUNSET);
        assertEquals(sunsetPreset, engine.worldPresetKey, "Použití předvolby musí přepsat uložený preset key");
        assertNear(1.15, engine.worldLightStrength, "Předvolba západu musí nastavit správnou sílu prostředí");
        assertVecNear(new Vec3(0.34, 0.20, 0.12), engine.worldLightColor, "Předvolba západu musí přepsat ambientní barvu");
        assertNear(1.15, engine.scene.getEnvironmentStrength(), "Předvolba západu musí propsat sílu i do scény");

        engine.undoLastAction();
        assertEquals(defaultPreset, engine.worldPresetKey, "Undo musi vratit i ulozeny preset key");
        assertNear(0.92, engine.worldLightStrength, "Undo musi vratit vychozi silu prostredi");
        assertVecNear(new Vec3(0.18, 0.20, 0.23), engine.worldLightColor, "Undo musi vratit vychozi ambientni barvu");
        assertNear(0.92, engine.scene.getEnvironmentStrength(), "Undo musi vratit vychozi silu sceny");

        engine.redoLastAction();
        assertEquals(sunsetPreset, engine.worldPresetKey, "Redo musí obnovit uložený preset key");
        assertNear(1.15, engine.worldLightStrength, "Redo musí obnovit sílu prostředí");
        assertVecNear(new Vec3(0.34, 0.20, 0.12), engine.worldLightColor, "Redo musí obnovit ambientní barvu");
        assertNear(1.15, engine.scene.getEnvironmentStrength(), "Redo musí obnovit sílu scény");
    }

    private static Engine createEngine() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.physicsWorld = new PhysicsWorld();
        engine.applyWorldLightSettings();
        return engine;
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, String message) {
        if (expected.sub(actual).length() > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNear(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
