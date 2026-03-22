package engine.core;

import engine.math.Vec3;
import engine.physics.PhysicsWorld;
import engine.scene.DirectionalLight;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.ui.UiStrings;

public final class WorldLightStrengthTests {

    private static final double EPS = 1e-9;

    private WorldLightStrengthTests() {
    }

    public static void main(String[] args) {
        testPresetSwitchResetsAllBuiltInWorldLights();
        testZeroStrengthMutesAllPresetLights();
        testPresetLightLookReturnsAfterStrengthRestore();
        testAnimatedWorldLightsStayDarkAtZeroStrength();
        System.out.println("WorldLightStrengthTests: ALL TESTS PASSED");
    }

    private static void testPresetSwitchResetsAllBuiltInWorldLights() {
        Engine engine = createEngineWithWorldLights();
        engine.applyWorldPreset(UiStrings.World.PRESET_DAY);
        engine.applyWorldPreset(UiStrings.World.PRESET_SUNSET);

        assertNear(1.45, engine.sunLight.getIntensity(), "Přepnutí na západ musí přepsat slunce z kontrastní předvolby");
        assertNear(0.28, engine.fillLight.getIntensity(), "Přepnutí na západ musí přepsat fill z kontrastní předvolby");
        assertNear(0.95, engine.warmWorldLight.getIntensity(), "Přepnutí na západ musí přepsat teplé světlo");
        assertNear(0.25, engine.coolWorldLight.getIntensity(), "Přepnutí na západ musí přepsat chladné světlo");
    }

    private static void testZeroStrengthMutesAllPresetLights() {
        Engine engine = createEngineWithWorldLights();
        engine.applyWorldPreset(UiStrings.World.PRESET_DAY);

        engine.worldLightStrength = 0.0;
        engine.applyWorldLightSettings();

        assertNear(0.0, engine.scene.getAmbientColor().length(), "Síla 0 musí shodit ambient prostředí");
        assertNear(0.0, engine.scene.getEnvironmentStrength(), "Síla 0 musí shodit environment strength");
        assertNear(0.0, engine.sunLight.getIntensity(), "Síla 0 musí umlčet slunce");
        assertNear(0.0, engine.fillLight.getIntensity(), "Síla 0 musí umlčet fill světlo");
        assertNear(0.0, engine.warmWorldLight.getIntensity(), "Síla 0 musí umlčet teplé bodové světlo");
        assertNear(0.0, engine.coolWorldLight.getIntensity(), "Síla 0 musí umlčet chladné bodové světlo");
    }

    private static void testPresetLightLookReturnsAfterStrengthRestore() {
        Engine engine = createEngineWithWorldLights();
        engine.applyWorldPreset(UiStrings.World.PRESET_SUNSET);

        double sun = engine.sunLight.getIntensity();
        double fill = engine.fillLight.getIntensity();
        double warm = engine.warmWorldLight.getIntensity();
        double cool = engine.coolWorldLight.getIntensity();

        engine.worldLightStrength = 0.0;
        engine.applyWorldLightSettings();
        engine.worldLightStrength = 1.15;
        engine.applyWorldLightSettings();

        assertNear(sun, engine.sunLight.getIntensity(), "Po návratu síly musí slunce držet preset look");
        assertNear(fill, engine.fillLight.getIntensity(), "Po návratu síly musí fill držet preset look");
        assertNear(warm, engine.warmWorldLight.getIntensity(), "Po návratu síly musí teplé světlo držet preset look");
        assertNear(cool, engine.coolWorldLight.getIntensity(), "Po návratu síly musí chladné světlo držet preset look");
    }

    private static void testAnimatedWorldLightsStayDarkAtZeroStrength() {
        Engine engine = createEngineWithWorldLights();
        engine.worldLightAnimationEnabled = true;
        engine.worldLightStrength = 0.0;
        engine.applyWorldLightSettings();

        EngineAnimationController.animateScene(engine, 2.4);

        assertNear(0.0, engine.scene.getAmbientColor().length(), "Animace nesmí vrátit ambient přes sílu 0");
        assertNear(0.0, engine.scene.getEnvironmentStrength(), "Animace nesmí vrátit environment přes sílu 0");
        assertNear(0.0, engine.warmWorldLight.getIntensity(), "Animace nesmí vrátit teplé světlo přes sílu 0");
        assertNear(0.0, engine.coolWorldLight.getIntensity(), "Animace nesmí vrátit chladné světlo přes sílu 0");
    }

    private static Engine createEngineWithWorldLights() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.physicsWorld = new PhysicsWorld();
        engine.sunLight = new DirectionalLight(new Vec3(-0.25, -1.0, -0.2), new Vec3(1.0, 0.97, 0.92), 1.35);
        engine.fillLight = new DirectionalLight(new Vec3(0.35, -0.45, 0.82), new Vec3(0.52, 0.60, 0.78), 0.42);
        engine.warmWorldLight = new PointLight(new Vec3(2.8, 2.3, 2.2), new Vec3(1.0, 0.82, 0.62), 0.55);
        engine.coolWorldLight = new PointLight(new Vec3(-2.6, 2.0, -2.7), new Vec3(0.54, 0.68, 1.0), 0.48);
        engine.scene.addLight(engine.sunLight);
        engine.scene.addLight(engine.fillLight);
        engine.scene.addLight(engine.warmWorldLight);
        engine.scene.addLight(engine.coolWorldLight);
        engine.applyWorldLightSettings();
        return engine;
    }

    private static void assertNear(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
