package engine.core;

import engine.physics.PhysicsWorld;
import engine.scene.Scene;

public final class WorldEnvironmentPresetTests {

    private WorldEnvironmentPresetTests() {
    }

    public static void main(String[] args) {
        testDefaultCloudyPresetLoadsBundledHdrEnvironment();
        testPresetSwitchRebindsHdrEnvironment();
        System.out.println("WorldEnvironmentPresetTests: ALL TESTS PASSED");
    }

    private static void testDefaultCloudyPresetLoadsBundledHdrEnvironment() {
        Engine engine = createEngine();
        if (engine.scene.getEnvironmentMap() == null) {
            throw new AssertionError("Default cloudy preset should load a bundled HDR environment map.");
        }
        if (!"farmland_overcast_1k".equals(engine.scene.getEnvironmentMapKey())) {
            throw new AssertionError("Default cloudy preset should bind the cloudy HDRI asset.");
        }
        if (engine.scene.getEnvironmentExposure() <= 0.0) {
            throw new AssertionError("Bundled HDR environments should provide a positive exposure multiplier.");
        }
    }

    private static void testPresetSwitchRebindsHdrEnvironment() {
        Engine engine = createEngine();
        EngineWorldManager.applyWorldPreset(engine, "Warm Sunset");
        if (engine.scene.getEnvironmentMap() == null) {
            throw new AssertionError("Warm Sunset should keep the scene on a real HDR environment.");
        }
        if (!"plains_sunset_1k".equals(engine.scene.getEnvironmentMapKey())) {
            throw new AssertionError("Warm Sunset should switch the scene to the sunset HDRI.");
        }
        if (engine.scene.getEnvironmentExposure() >= 0.5) {
            throw new AssertionError("Sunset HDRI should use the tuned exposure compensation.");
        }
    }

    private static Engine createEngine() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.physicsWorld = new PhysicsWorld();
        engine.applyWorldLightSettings();
        return engine;
    }
}
