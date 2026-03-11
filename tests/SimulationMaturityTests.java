import engine.geometry.MeshGenerator;
import engine.material.Material;
import engine.material.MaterialPresets;
import engine.material.PhongMaterial;
import engine.math.AABB;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.sim.galaxy.GalaxySimulation;
import engine.sim.galaxy.GalaxySystemEntity;
import engine.sim.water.WaterEmitter;
import engine.sim.water.WaterEmitterEntity;
import engine.sim.water.WaterSimulation;

public final class SimulationMaturityTests {

    private static final Vec3 GRAVITY = new Vec3(0.0, -9.81, 0.0);

    private SimulationMaturityTests() {
    }

    public static void main(String[] args) {
        testPrincipledPresetCarriesHybridFields();
        testWaterPresetCarriesPhysicalFields();
        testSprayReplayIsDeterministic();
        testSprayReplayMatchesIncrementalStepping();
        testSprayCapacityRemainsBounded();
        testSprayFloorCollisionKeepsParticlesAboveGround();
        testSprayAabbCollisionPushesParticlesOutOfSceneProxy();
        testGalaxyScaffoldIsExplicitlyExperimental();
        System.out.println("SimulationMaturityTests: ALL TESTS PASSED");
    }

    private static void testPrincipledPresetCarriesHybridFields() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.7, 0.6, 0.5), 32.0);
        MaterialPresets.apply(MaterialPresets.Preset.PRINCIPLED, material);

        assertTrue(material.getDomain() == Material.Domain.SURFACE, "Principled preset domain mismatch");
        assertTrue(material.getShadingModel() == Material.ShadingModel.PHONG, "Principled preset shading mismatch");
        assertNear(0.45, material.getRoughness(), 1e-6, "Principled preset roughness mismatch");
        assertNear(1.45, material.getRefractiveIndex(), 1e-6, "Principled preset IOR mismatch");
        assertTrue(material.getTransmission() < 1e-6, "Principled preset transmission should default to opaque");
    }

    private static void testWaterPresetCarriesPhysicalFields() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.5, 0.5, 0.5), 32.0);
        MaterialPresets.apply(MaterialPresets.Preset.WATER, material);

        assertTrue(material.getDomain() == Material.Domain.SURFACE, "Water preset domain mismatch");
        assertTrue(material.getShadingModel() == Material.ShadingModel.TRANSMISSIVE, "Water preset shading mismatch");
        assertNear(1.333, material.getRefractiveIndex(), 1e-6, "Water preset IOR mismatch");
        assertTrue(material.getTransmission() > 0.9, "Water preset transmission too low");
        assertTrue(material.getRoughness() < 0.05, "Water preset roughness too high");
    }

    private static void testSprayReplayIsDeterministic() {
        Scene sceneA = new Scene();
        WaterEmitterEntity emitterA = addDefaultSpray(sceneA, "spray-a", new Vec3(0.0, 2.0, 0.0));
        tuneEmitter(emitterA.getEmitter());

        Scene sceneB = new Scene();
        WaterEmitterEntity emitterB = addDefaultSpray(sceneB, "spray-b", new Vec3(0.0, 2.0, 0.0));
        tuneEmitter(emitterB.getEmitter());

        WaterSimulation simA = new WaterSimulation(1024);
        WaterSimulation simB = new WaterSimulation(1024);
        simA.syncToTime(sceneA, 1.5, GRAVITY, 0.0, true);
        simB.syncToTime(sceneB, 1.5, GRAVITY, 0.0, true);

        assertTrue(simA.stateSignature() == simB.stateSignature(), "Spray replay must be deterministic");
    }

    private static void testSprayReplayMatchesIncrementalStepping() {
        Scene steppedScene = new Scene();
        WaterEmitterEntity steppedEmitter = addDefaultSpray(steppedScene, "spray-stepped", new Vec3(0.0, 2.0, 0.0));
        tuneEmitter(steppedEmitter.getEmitter());

        Scene replayScene = new Scene();
        WaterEmitterEntity replayEmitter = addDefaultSpray(replayScene, "spray-replay", new Vec3(0.0, 2.0, 0.0));
        tuneEmitter(replayEmitter.getEmitter());

        WaterSimulation stepped = new WaterSimulation(1024);
        WaterSimulation replayed = new WaterSimulation(1024);
        double dt = 1.0 / 60.0;
        int steps = 90;

        for (int i = 0; i < steps; i++) {
            stepped.update(steppedScene, dt, GRAVITY, 0.0, true);
        }
        replayed.syncToTime(replayScene, steps * dt, GRAVITY, 0.0, true);

        assertTrue(stepped.stateSignature() == replayed.stateSignature(),
                "Incremental spray stepping must match deterministic replay for the same time");
    }

    private static void testSprayCapacityRemainsBounded() {
        Scene scene = new Scene();
        WaterEmitterEntity emitter = addDefaultSpray(scene, "spray-capacity", new Vec3(0.0, 2.2, 0.0));
        WaterEmitter config = emitter.getEmitter();
        config.setEmitRate(2600.0);
        config.setParticleLifetime(5.0);
        config.setInitialSpeed(5.2);

        WaterSimulation simulation = new WaterSimulation(256);
        for (int i = 0; i < 240; i++) {
            simulation.update(scene, 1.0 / 60.0, GRAVITY, 0.0, true);
        }

        assertTrue(simulation.getActiveParticleCount() > 0, "Spray capacity test should emit particles");
        assertTrue(simulation.getActiveParticleCount() <= simulation.getCapacity(),
                "Spray simulation must stay within configured capacity");
    }

    private static void testSprayFloorCollisionKeepsParticlesAboveGround() {
        Scene scene = new Scene();
        WaterEmitterEntity emitter = addDefaultSpray(scene, "spray-floor", new Vec3(0.0, 2.0, 0.0));
        WaterEmitter config = emitter.getEmitter();
        config.setEmitRate(240.0);
        config.setParticleLifetime(2.2);
        config.setBounce(0.10);
        config.setSurfaceDamping(0.78);

        WaterSimulation simulation = new WaterSimulation(1024);
        simulation.syncToTime(scene, 2.0, GRAVITY, 0.0, true);

        final double[] minBottom = new double[]{Double.POSITIVE_INFINITY};
        simulation.forEachParticle((spray, px, py, pz, vx, vy, vz, lifeRemaining, lifeMax, radius) ->
                minBottom[0] = Math.min(minBottom[0], py - radius));

        assertTrue(Double.isFinite(minBottom[0]), "Floor collision test expected active particles");
        assertTrue(minBottom[0] >= -1e-4, "Spray particles must stay above the floor plane");
    }

    private static void testSprayAabbCollisionPushesParticlesOutOfSceneProxy() {
        Scene scene = new Scene();
        WaterEmitterEntity emitter = addDefaultSpray(scene, "spray-obstacle", new Vec3(0.0, 2.0, 0.0));
        WaterEmitter config = emitter.getEmitter();
        config.setSpreadAngleDegrees(8.0);
        config.setEmitRate(240.0);
        config.setInitialSpeed(5.1);

        Entity obstacle = new Entity(
                "obstacle-box",
                MeshGenerator.cube(1.0),
                new PhongMaterial(new Vec3(0.45, 0.45, 0.48), 24.0)
        );
        obstacle.getTransform().setPosition(new Vec3(0.0, 1.15, 0.0));
        scene.addEntity(obstacle);
        scene.update(0.0);
        obstacle.computeWorldBounds();
        AABB obstacleBounds = obstacle.getWorldBounds();

        WaterSimulation simulation = new WaterSimulation(1024);
        simulation.syncToTime(scene, 0.85, GRAVITY, 0.0, true);

        final boolean[] insideProxy = new boolean[]{false};
        simulation.forEachParticle((spray, px, py, pz, vx, vy, vz, lifeRemaining, lifeMax, radius) -> {
            if (containsExpanded(obstacleBounds, px, py, pz, radius)) {
                insideProxy[0] = true;
            }
        });

        assertTrue(!insideProxy[0], "Spray particles must not remain inside scene collision proxies");
    }

    private static void testGalaxyScaffoldIsExplicitlyExperimental() {
        Scene scene = new Scene();
        GalaxySystemEntity galaxy = new GalaxySystemEntity(null);
        scene.addEntity(galaxy);

        GalaxySimulation simulation = new GalaxySimulation();
        simulation.update(scene, 1.0 / 60.0, 0.5);

        assertTrue(GalaxySimulation.isExperimentalScaffold(),
                "Galaxy simulation must explicitly report experimental scaffold status");
        assertTrue(simulation.getSystems().size() == 1, "Galaxy scaffold should still sync anchor entities");
        assertTrue(simulation.getSystems().get(0) == galaxy, "Galaxy scaffold sync mismatch");
        assertTrue(galaxy.getName().startsWith("experimental-"),
                "Galaxy anchor naming should clearly signal experimental status");
    }

    private static WaterEmitterEntity addDefaultSpray(Scene scene, String name, Vec3 position) {
        WaterEmitterEntity emitter = WaterEmitterEntity.createDefault(name, position);
        scene.addEntity(emitter);
        return emitter;
    }

    private static void tuneEmitter(WaterEmitter emitter) {
        emitter.setEmitRate(220.0);
        emitter.setInitialSpeed(4.9);
        emitter.setSpreadAngleDegrees(10.0);
        emitter.setParticleLifetime(2.4);
        emitter.setParticleRadius(0.055);
        emitter.setDrag(0.10);
        emitter.setBounce(0.16);
        emitter.setGravityScale(1.0);
        emitter.setSurfaceDamping(0.82);
        emitter.setJitter(0.04);
        emitter.setOpacity(0.58);
    }

    private static boolean containsExpanded(AABB bounds, double px, double py, double pz, double radius) {
        if (bounds == null) {
            return false;
        }
        return px >= bounds.getMin().x - radius && px <= bounds.getMax().x + radius
                && py >= bounds.getMin().y - radius && py <= bounds.getMax().y + radius
                && pz >= bounds.getMin().z - radius && pz <= bounds.getMax().z + radius;
    }

    private static void assertNear(double expected, double actual, double eps, String message) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
