package engine.core;

import engine.math.Vec3;
import engine.physics.RigidBody;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Scene;
import java.util.List;

final class EngineWorldManager {

    private static final double WORLD_LIGHT_EPS = 1e-9;

    private EngineWorldManager() {
    }

    static void applyWorldLightSettings(Engine engine) {
        applyWorldLightSettings(engine, true);
    }

    static void applyStoredWorldLightSettings(Engine engine) {
        applyWorldLightSettings(engine, false);
    }

    static void applyWorldPreset(Engine engine, String presetName) {
        if (engine == null) {
            return;
        }
        WorldPresetCatalog.Definition preset = WorldPresetCatalog.resolve(presetName);
        applyPresetState(engine, preset);
        applyStoredWorldLightSettings(engine);
        engine.rebuildSceneDetailsPanel();
    }

    static void applyPresetState(Engine engine, WorldPresetCatalog.Definition preset) {
        if (engine == null || preset == null) {
            return;
        }
        engine.worldPresetKey = preset.key();
        engine.worldLightColor = preset.ambientColor();
        engine.worldBackgroundColor = preset.backgroundColor();
        engine.worldLightStrength = preset.strength();
        engine.worldSunBaseIntensity = preset.sunIntensity() / preset.strength();
        engine.worldFillBaseIntensity = preset.fillIntensity() / preset.strength();
        engine.worldWarmBaseIntensity = preset.warmIntensity() / preset.strength();
        engine.worldCoolBaseIntensity = preset.coolIntensity() / preset.strength();
        if (engine.sunLight != null) {
            engine.sunLight.setColor(preset.sunColor());
        }
        if (engine.fillLight != null) {
            engine.fillLight.setColor(preset.fillColor());
        }
        if (engine.warmWorldLight != null) {
            engine.warmWorldLight.setColor(preset.warmColor());
        }
        if (engine.coolWorldLight != null) {
            engine.coolWorldLight.setColor(preset.coolColor());
        }
    }

    private static void applyWorldLightSettings(Engine engine, boolean captureCurrentBases) {
        if (engine == null) {
            return;
        }
        Scene scene = engine.scene;
        if (scene == null) {
            return;
        }
        if (captureCurrentBases) {
            captureWorldLightBases(engine);
        }

        double strength = Math.max(0.0, engine.worldLightStrength);
        scene.setAmbientColor(new Vec3(
                Math.max(0.0, engine.worldLightColor.x * strength),
                Math.max(0.0, engine.worldLightColor.y * strength),
                Math.max(0.0, engine.worldLightColor.z * strength)
        ));
        scene.setEnvironmentStrength(strength);
        scene.setBackgroundColor(new Vec3(
                clamp01(engine.worldBackgroundColor.x),
                clamp01(engine.worldBackgroundColor.y),
                clamp01(engine.worldBackgroundColor.z)
        ));
        scene.setEnvironmentYawDegrees(engine.worldEnvironmentYawDegrees);
        scene.setEnvironmentPitchDegrees(engine.worldEnvironmentPitchDegrees);

        WorldEnvironmentLibrary.EnvironmentBinding binding = WorldEnvironmentLibrary.resolveForPreset(engine.worldPresetKey);
        if (binding != null) {
            scene.setEnvironmentMapKey(binding.assetKey);
            scene.setEnvironmentMap(binding.map);
            scene.setEnvironmentExposure(binding.exposure);
        }

        applyScaledIntensity(engine.sunLight, engine.worldSunBaseIntensity, strength);
        applyScaledIntensity(engine.fillLight, engine.worldFillBaseIntensity, strength);
        applyScaledIntensity(engine.warmWorldLight, engine.worldWarmBaseIntensity, strength);
        applyScaledIntensity(engine.coolWorldLight, engine.worldCoolBaseIntensity, strength);
        engine.worldLightAppliedStrength = strength;
    }

    static String registerLightName(Engine engine, Light light, String preferredName) {
        if (light == null) {
            return "Light";
        }
        String existing = engine.lightNames.get(light);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String base = preferredName == null || preferredName.isBlank()
                ? "Light-" + (++engine.lightCounter)
                : preferredName;
        engine.lightNames.put(light, base);
        return base;
    }

    static String getLightName(Engine engine, Light light) {
        String existing = engine.lightNames.get(light);
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        return registerLightName(
                engine,
                light,
                light == null ? "Light" : (light.getClass().getSimpleName() + "-" + (++engine.lightCounter))
        );
    }

    static Vec3 spawnInFrontOfCamera(Engine engine, double distance) {
        if (engine.camera == null) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return engine.camera.getPosition().add(engine.camera.getForward().mul(distance));
    }

    static void addPointLight(Engine engine) {
        if (engine.scene == null) {
            return;
        }
        Vec3 pos = engine.spawnPositionFromPointer(3.0).add(new Vec3(0.0, 0.5, 0.0));
        PointLight light = new PointLight(pos, new Vec3(1.0, 0.93, 0.82), 0.90);
        light.setAttenuation(1.0, 0.08, 0.020);
        engine.scene.addLight(light);
        registerLightName(engine, light, "Point Light " + (++engine.lightCounter));
        engine.stateFor(light);
        engine.setCurrentLightSelection(light);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void addAreaLight(Engine engine) {
        if (engine.scene == null) {
            return;
        }
        Vec3 pos = engine.spawnPositionFromPointer(3.4).add(new Vec3(0.0, 0.6, 0.0));
        AreaLight light = new AreaLight(pos, new Vec3(0.86, 0.94, 1.0), 0.80);
        light.setEmissionDirection(new Vec3(0.0, -1.0, -0.15));
        light.setSpreadAngleDegrees(120.0);
        light.setAttenuation(1.0, 0.06, 0.014);
        engine.scene.addLight(light);
        registerLightName(engine, light, "Area Light " + (++engine.lightCounter));
        engine.stateFor(light);
        engine.setCurrentLightSelection(light);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void addConeLight(Engine engine) {
        if (engine.scene == null) {
            return;
        }
        Vec3 pos = resolveSunLikeSpawnPosition(engine);
        ConeLight light = new ConeLight(pos, new Vec3(1.0, 0.96, 0.88), 1.20);
        light.setDirection(resolveSunLikeTargetDirection(engine, pos));
        light.setConeAngleDegrees(34.0);
        light.setSoftness(0.35);
        light.setAttenuation(1.0, 0.09, 0.028);
        engine.scene.addLight(light);
        registerLightName(engine, light, "Cone Light " + (++engine.lightCounter));
        engine.stateFor(light);
        engine.setCurrentLightSelection(light);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    private static Vec3 resolveSunLikeSpawnPosition(Engine engine) {
        if (engine == null || engine.camera == null) {
            return new Vec3(0.0, 2.0, 0.0);
        }
        return engine.camera.getPosition().add(new Vec3(0.0, 2.0, 0.0));
    }

    private static Vec3 resolveSunLikeTargetDirection(Engine engine, Vec3 lightPosition) {
        Vec3 fallback = engine != null && engine.camera != null
                ? engine.camera.getForward().normalize()
                : new Vec3(0.0, -1.0, 0.0);
        Vec3 target = resolveJosefinaTargetCenter(engine);
        if (target == null) {
            return fallback;
        }
        Vec3 direction = target.sub(lightPosition);
        if (direction.lengthSquared() <= 1e-10) {
            return fallback;
        }
        return direction.normalize();
    }

    private static Vec3 resolveJosefinaTargetCenter(Engine engine) {
        if (engine == null || engine.scene == null) {
            return null;
        }
        Entity target = engine.demoEntity;
        if (target == null) {
            target = engine.scene.findByName(EngineSceneBootstrap.STARTUP_MODEL_DISPLAY_NAME);
        }
        if (target == null) {
            return null;
        }
        target.computeWorldBounds();
        if (target.getWorldBounds() != null) {
            return target.getWorldBounds().center();
        }
        if (target.getTransform() != null) {
            return target.getTransform().getPosition();
        }
        return null;
    }

    private static Engine.ForceField newForceField(Engine engine, Engine.ForceFieldType type, String prefix) {
        Engine.ForceField field = new Engine.ForceField();
        field.type = type;
        field.name = prefix + " " + (++engine.forceFieldCounter);
        field.position = engine.spawnPositionFromPointer(3.0);
        field.direction = new Vec3(0.0, -1.0, 0.0);
        field.strength = 12.0;
        field.attract = true;
        field.radius = 8.0;
        field.turbulenceScale = 1.4;
        field.seed = engine.random.nextDouble() * 997.0;
        return field;
    }

    static void addVectorForceField(Engine engine) {
        Engine.ForceField field = newForceField(engine, Engine.ForceFieldType.VECTOR, "Vector Force");
        field.strength = 14.0;
        engine.forceFields.add(field);
        engine.stateFor(field);
        engine.setCurrentForceFieldSelection(field);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void addPointForceField(Engine engine, boolean attract) {
        Engine.ForceField field = newForceField(engine, Engine.ForceFieldType.POINT, attract ? "Attractor" : "Repulsor");
        field.attract = attract;
        field.strength = 18.0;
        field.radius = 11.0;
        engine.forceFields.add(field);
        engine.stateFor(field);
        engine.setCurrentForceFieldSelection(field);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void addTurbulenceForceField(Engine engine) {
        Engine.ForceField field = newForceField(engine, Engine.ForceFieldType.TURBULENCE, "Turbulence");
        field.strength = 9.0;
        field.radius = 12.0;
        field.turbulenceScale = 1.8;
        engine.forceFields.add(field);
        engine.stateFor(field);
        engine.setCurrentForceFieldSelection(field);
        engine.refreshSceneOutliner();
        engine.syncOutlinerSelectionToCurrentSelection();
        engine.rebuildSceneDetailsPanel();
    }

    static void applyForceFields(Engine engine, double elapsedSeconds) {
        if (!engine.animationPlaybackEnabled || engine.physicsWorld == null || engine.forceFields.isEmpty()) {
            return;
        }
        List<RigidBody> bodies = engine.physicsWorld.getBodies();
        for (RigidBody body : bodies) {
            if (body == null || body.getType() != RigidBody.BodyType.DYNAMIC || body.getInverseMass() <= 0.0) {
                continue;
            }
            Vec3 bodyPos = body.getPosition();
            double mass = body.getMass();
            Vec3 sum = Vec3.ZERO;
            for (Engine.ForceField field : engine.forceFields) {
                Engine.SceneItemState state = engine.stateFor(field);
                if (!state.visibleInView) {
                    continue;
                }
                Vec3 contribution = forceContribution(field, bodyPos, mass, elapsedSeconds);
                sum = sum.add(contribution);
            }
            if (sum.lengthSquared() > 1e-12) {
                body.applyForce(sum);
            }
        }
    }

    static Vec3 forceContribution(Engine.ForceField field, Vec3 bodyPos, double mass, double elapsedSeconds) {
        if (field == null || bodyPos == null) {
            return Vec3.ZERO;
        }
        switch (field.type) {
            case VECTOR: {
                Vec3 dir = field.direction == null ? Vec3.ZERO : field.direction.normalize();
                if (dir.lengthSquared() < 1e-10) {
                    return Vec3.ZERO;
                }
                return dir.mul(field.strength * mass);
            }
            case POINT: {
                Vec3 delta = field.position.sub(bodyPos);
                double distSq = delta.lengthSquared();
                if (distSq < 1e-10) {
                    return Vec3.ZERO;
                }
                double dist = Math.sqrt(distSq);
                if (field.radius > 0.0 && dist > field.radius) {
                    return Vec3.ZERO;
                }
                Vec3 dir = delta.mul(1.0 / dist);
                if (!field.attract) {
                    dir = dir.negate();
                }
                double radial = field.radius > 1e-6
                        ? Math.max(0.0, 1.0 - dist / field.radius)
                        : 1.0 / (1.0 + 0.06 * distSq);
                double falloff = 0.15 + 0.85 * radial * radial;
                return dir.mul(field.strength * falloff * mass);
            }
            case TURBULENCE: {
                Vec3 rel = bodyPos.sub(field.position);
                double dist = rel.length();
                if (field.radius > 1e-6 && dist > field.radius) {
                    return Vec3.ZERO;
                }
                double s = Math.max(0.05, field.turbulenceScale);
                double t = elapsedSeconds * (0.45 + 0.15 * s) + field.seed;
                double nx = Math.sin((bodyPos.x + field.seed) * s * 0.90 + t * 1.70)
                        + Math.cos((bodyPos.z - field.seed) * s * 0.70 - t * 1.10);
                double ny = Math.cos((bodyPos.y + 3.0 * field.seed) * s * 0.85 + t * 1.40)
                        - Math.sin((bodyPos.x + bodyPos.z) * s * 0.65 + t * 1.30);
                double nz = Math.sin((bodyPos.z + field.seed * 0.5) * s * 0.95 + t * 1.20)
                        + Math.cos((bodyPos.y - bodyPos.x) * s * 0.55 - t * 1.60);
                Vec3 noise = new Vec3(nx, ny, nz);
                double lenSq = noise.lengthSquared();
                if (lenSq < 1e-12) {
                    return Vec3.ZERO;
                }
                double radial = field.radius > 1e-6 ? Math.max(0.2, 1.0 - dist / field.radius) : 1.0;
                return noise.normalize().mul(field.strength * radial * mass);
            }
            default:
                return Vec3.ZERO;
        }
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static void captureWorldLightBases(Engine engine) {
        double referenceStrength = Math.max(0.0, engine.worldLightAppliedStrength);
        engine.worldSunBaseIntensity = captureBaseIntensity(engine.sunLight, referenceStrength, engine.worldSunBaseIntensity);
        engine.worldFillBaseIntensity = captureBaseIntensity(engine.fillLight, referenceStrength, engine.worldFillBaseIntensity);
        engine.worldWarmBaseIntensity = captureBaseIntensity(engine.warmWorldLight, referenceStrength, engine.worldWarmBaseIntensity);
        engine.worldCoolBaseIntensity = captureBaseIntensity(engine.coolWorldLight, referenceStrength, engine.worldCoolBaseIntensity);
    }

    private static double captureBaseIntensity(Light light, double referenceStrength, double fallback) {
        if (light == null) {
            return fallback;
        }
        double actualIntensity = Math.max(0.0, light.getIntensity());
        if (referenceStrength > WORLD_LIGHT_EPS) {
            return actualIntensity / referenceStrength;
        }
        if (actualIntensity > WORLD_LIGHT_EPS) {
            return actualIntensity;
        }
        return fallback;
    }

    private static void applyScaledIntensity(Light light, double baseIntensity, double strength) {
        if (light == null) {
            return;
        }
        light.setIntensity(Math.max(0.0, baseIntensity * strength));
    }
}
