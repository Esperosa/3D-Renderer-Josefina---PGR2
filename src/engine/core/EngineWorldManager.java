package engine.core;

import engine.math.Vec3;
import engine.physics.RigidBody;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.ui.UiStrings;

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

    private static void applyWorldLightSettings(Engine engine, boolean captureCurrentBases) {
        if (engine.scene == null) {
            return;
        }
        if (captureCurrentBases) {
            captureWorldLightBases(engine);
        }
        double strength = Math.max(0.0, engine.worldLightStrength);
        engine.scene.setAmbientColor(new Vec3(
                Math.max(0.0, engine.worldLightColor.x * strength),
                Math.max(0.0, engine.worldLightColor.y * strength),
                Math.max(0.0, engine.worldLightColor.z * strength)
        ));
        engine.scene.setEnvironmentStrength(strength);
        engine.scene.setBackgroundColor(new Vec3(
                clamp01(engine.worldBackgroundColor.x),
                clamp01(engine.worldBackgroundColor.y),
                clamp01(engine.worldBackgroundColor.z)
        ));
        applyScaledIntensity(engine.sunLight, engine.worldSunBaseIntensity, strength);
        applyScaledIntensity(engine.fillLight, engine.worldFillBaseIntensity, strength);
        applyScaledIntensity(engine.warmWorldLight, engine.worldWarmBaseIntensity, strength);
        applyScaledIntensity(engine.coolWorldLight, engine.worldCoolBaseIntensity, strength);
        engine.worldLightAppliedStrength = strength;
    }

    static void applyWorldPreset(Engine engine, String presetName) {
        if (presetName == null) {
            return;
        }
        String presetKey = UiStrings.worldPresetKey(presetName);
        engine.worldPresetKey = presetKey;
        switch (presetKey) {
            case "Warm Sunset":
                engine.worldLightColor = new Vec3(0.34, 0.20, 0.12);
                engine.worldBackgroundColor = new Vec3(0.18, 0.09, 0.06);
                engine.worldLightStrength = 1.15;
                engine.worldSunBaseIntensity = 1.45 / engine.worldLightStrength;
                engine.worldFillBaseIntensity = 0.28 / engine.worldLightStrength;
                engine.worldWarmBaseIntensity = 0.95 / engine.worldLightStrength;
                engine.worldCoolBaseIntensity = 0.25 / engine.worldLightStrength;
                if (engine.sunLight != null) {
                    engine.sunLight.setColor(new Vec3(1.0, 0.83, 0.65));
                }
                if (engine.fillLight != null) {
                    engine.fillLight.setColor(new Vec3(0.42, 0.50, 0.72));
                }
                if (engine.warmWorldLight != null) {
                    engine.warmWorldLight.setColor(new Vec3(1.0, 0.78, 0.58));
                }
                if (engine.coolWorldLight != null) {
                    engine.coolWorldLight.setColor(new Vec3(0.42, 0.56, 0.86));
                }
                break;
            case "Cool Night":
                engine.worldLightColor = new Vec3(0.10, 0.16, 0.25);
                engine.worldBackgroundColor = new Vec3(0.03, 0.05, 0.11);
                engine.worldLightStrength = 0.78;
                engine.worldSunBaseIntensity = 0.58 / engine.worldLightStrength;
                engine.worldFillBaseIntensity = 0.32 / engine.worldLightStrength;
                engine.worldWarmBaseIntensity = 0.28 / engine.worldLightStrength;
                engine.worldCoolBaseIntensity = 0.78 / engine.worldLightStrength;
                if (engine.sunLight != null) {
                    engine.sunLight.setColor(new Vec3(0.70, 0.80, 1.0));
                }
                if (engine.fillLight != null) {
                    engine.fillLight.setColor(new Vec3(0.34, 0.44, 0.72));
                }
                if (engine.coolWorldLight != null) {
                    engine.coolWorldLight.setColor(new Vec3(0.55, 0.74, 1.0));
                }
                if (engine.warmWorldLight != null) {
                    engine.warmWorldLight.setColor(new Vec3(0.96, 0.72, 0.52));
                }
                break;
            case "High Contrast":
                engine.worldLightColor = new Vec3(0.16, 0.18, 0.19);
                engine.worldBackgroundColor = new Vec3(0.02, 0.03, 0.04);
                engine.worldLightStrength = 0.68;
                engine.worldSunBaseIntensity = 1.8 / engine.worldLightStrength;
                engine.worldFillBaseIntensity = 0.22 / engine.worldLightStrength;
                engine.worldWarmBaseIntensity = 0.52 / engine.worldLightStrength;
                engine.worldCoolBaseIntensity = 0.48 / engine.worldLightStrength;
                if (engine.sunLight != null) {
                    engine.sunLight.setColor(new Vec3(1.0, 0.97, 0.92));
                }
                if (engine.fillLight != null) {
                    engine.fillLight.setColor(new Vec3(0.52, 0.60, 0.78));
                }
                if (engine.warmWorldLight != null) {
                    engine.warmWorldLight.setColor(new Vec3(1.0, 0.82, 0.62));
                }
                if (engine.coolWorldLight != null) {
                    engine.coolWorldLight.setColor(new Vec3(0.54, 0.68, 1.0));
                }
                break;
            case "Studio Neutral":
            default:
                engine.worldLightColor = new Vec3(0.18, 0.20, 0.24);
                engine.worldBackgroundColor = new Vec3(0.06, 0.08, 0.11);
                engine.worldLightStrength = 1.0;
                engine.worldSunBaseIntensity = 1.35;
                engine.worldFillBaseIntensity = 0.42;
                engine.worldWarmBaseIntensity = 0.55;
                engine.worldCoolBaseIntensity = 0.48;
                if (engine.sunLight != null) {
                    engine.sunLight.setColor(new Vec3(1.0, 0.97, 0.92));
                }
                if (engine.fillLight != null) {
                    engine.fillLight.setColor(new Vec3(0.52, 0.60, 0.78));
                }
                if (engine.warmWorldLight != null) {
                    engine.warmWorldLight.setColor(new Vec3(1.0, 0.82, 0.62));
                }
                if (engine.coolWorldLight != null) {
                    engine.coolWorldLight.setColor(new Vec3(0.54, 0.68, 1.0));
                }
                break;
        }
        applyStoredWorldLightSettings(engine);
        engine.rebuildSceneDetailsPanel();
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
        Vec3 pos = engine.spawnPositionFromPointer(3.2).add(new Vec3(0.0, 0.7, 0.0));
        ConeLight light = new ConeLight(pos, new Vec3(1.0, 0.96, 0.88), 1.20);
        light.setDirection(new Vec3(0.0, -1.0, 0.0));
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
