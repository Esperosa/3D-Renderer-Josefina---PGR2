package engine.core;

import engine.camera.PerspectiveCamera;
import engine.material.Material;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.Texture;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Transform;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Tady držím klíče časové osy pro objekty, světla, síly a výstupní kameru.
 */
final class SceneTimeline {

    private static final class EntitySample {
        final Vec3 position;
        final Vec3 euler;
        final Vec3 scale;
        final boolean visible;
        final boolean castShadow;
        final String materialName;
        final Vec3 materialAmbient;
        final Vec3 materialDiffuse;
        final Vec3 materialSpecular;
        final double materialShininess;
        final double materialReflectivity;
        final double materialRefractiveIndex;
        final double materialOpacity;
        final Texture materialTexture;
        final boolean materialTextureLinear;
        final PhongMaterial materialSnapshot;

        EntitySample(Vec3 position,
                     Vec3 euler,
                     Vec3 scale,
                     boolean visible,
                     boolean castShadow,
                     String materialName,
                     Vec3 materialAmbient,
                     Vec3 materialDiffuse,
                     Vec3 materialSpecular,
                     double materialShininess,
                     double materialReflectivity,
                     double materialRefractiveIndex,
                     double materialOpacity,
                     Texture materialTexture,
                     boolean materialTextureLinear,
                     PhongMaterial materialSnapshot) {
            this.position = position;
            this.euler = euler;
            this.scale = scale;
            this.visible = visible;
            this.castShadow = castShadow;
            this.materialName = materialName;
            this.materialAmbient = materialAmbient;
            this.materialDiffuse = materialDiffuse;
            this.materialSpecular = materialSpecular;
            this.materialShininess = materialShininess;
            this.materialReflectivity = materialReflectivity;
            this.materialRefractiveIndex = materialRefractiveIndex;
            this.materialOpacity = materialOpacity;
            this.materialTexture = materialTexture;
            this.materialTextureLinear = materialTextureLinear;
            this.materialSnapshot = materialSnapshot;
        }
    }

    private static final class LightSample {
        final Vec3 color;
        final double intensity;
        final boolean enabled;

        final Vec3 direction;
        final Vec3 position;
        final double attenuationConstant;
        final double attenuationLinear;
        final double attenuationQuadratic;

        final Vec3 emissionDirection;
        final double spreadAngleDegrees;
        final double coneAngleDegrees;
        final double softness;

        LightSample(Vec3 color,
                    double intensity,
                    boolean enabled,
                    Vec3 direction,
                    Vec3 position,
                    double attenuationConstant,
                    double attenuationLinear,
                    double attenuationQuadratic,
                    Vec3 emissionDirection,
                    double spreadAngleDegrees,
                    double coneAngleDegrees,
                    double softness) {
            this.color = color;
            this.intensity = intensity;
            this.enabled = enabled;
            this.direction = direction;
            this.position = position;
            this.attenuationConstant = attenuationConstant;
            this.attenuationLinear = attenuationLinear;
            this.attenuationQuadratic = attenuationQuadratic;
            this.emissionDirection = emissionDirection;
            this.spreadAngleDegrees = spreadAngleDegrees;
            this.coneAngleDegrees = coneAngleDegrees;
            this.softness = softness;
        }
    }

    private static final class ForceSample {
        final String name;
        final Engine.ForceFieldType type;
        final Vec3 position;
        final Vec3 direction;
        final double strength;
        final boolean attract;
        final double radius;
        final double turbulenceScale;
        final double seed;

        ForceSample(String name,
                    Engine.ForceFieldType type,
                    Vec3 position,
                    Vec3 direction,
                    double strength,
                    boolean attract,
                    double radius,
                    double turbulenceScale,
                    double seed) {
            this.name = name;
            this.type = type;
            this.position = position;
            this.direction = direction;
            this.strength = strength;
            this.attract = attract;
            this.radius = radius;
            this.turbulenceScale = turbulenceScale;
            this.seed = seed;
        }
    }

    private static final class CameraSample {
        final Vec3 position;
        final Vec3 forward;
        final double fovYDeg;
        final double nearPlane;
        final double farPlane;
        final boolean orthographicProjection;

        CameraSample(Vec3 position,
                     Vec3 forward,
                     double fovYDeg,
                     double nearPlane,
                     double farPlane,
                     boolean orthographicProjection) {
            this.position = position;
            this.forward = forward;
            this.fovYDeg = fovYDeg;
            this.nearPlane = nearPlane;
            this.farPlane = farPlane;
            this.orthographicProjection = orthographicProjection;
        }
    }

    private final IdentityHashMap<Entity, TreeMap<Integer, EntitySample>> entityTracks;
    private final IdentityHashMap<Entity, TreeSet<Integer>> entityReleaseTracks;
    private final IdentityHashMap<Light, TreeMap<Integer, LightSample>> lightTracks;
    private final IdentityHashMap<Engine.ForceField, TreeMap<Integer, ForceSample>> forceTracks;
    private final TreeMap<Integer, CameraSample> cameraTrack;

    SceneTimeline() {
        this.entityTracks = new IdentityHashMap<>();
        this.entityReleaseTracks = new IdentityHashMap<>();
        this.lightTracks = new IdentityHashMap<>();
        this.forceTracks = new IdentityHashMap<>();
        this.cameraTrack = new TreeMap<>();
    }

    SceneTimeline copy() {
        SceneTimeline copy = new SceneTimeline();
        copy.copyFrom(this);
        return copy;
    }

    void copyFrom(SceneTimeline source) {
        clear();
        if (source == null) {
            return;
        }
        for (Map.Entry<Entity, TreeMap<Integer, EntitySample>> entry : source.entityTracks.entrySet()) {
            TreeMap<Integer, EntitySample> trackCopy = new TreeMap<>();
            for (Map.Entry<Integer, EntitySample> sampleEntry : entry.getValue().entrySet()) {
                trackCopy.put(sampleEntry.getKey(), copyEntitySample(sampleEntry.getValue()));
            }
            entityTracks.put(entry.getKey(), trackCopy);
        }
        for (Map.Entry<Entity, TreeSet<Integer>> entry : source.entityReleaseTracks.entrySet()) {
            entityReleaseTracks.put(entry.getKey(), new TreeSet<>(entry.getValue()));
        }
        for (Map.Entry<Light, TreeMap<Integer, LightSample>> entry : source.lightTracks.entrySet()) {
            TreeMap<Integer, LightSample> trackCopy = new TreeMap<>();
            for (Map.Entry<Integer, LightSample> sampleEntry : entry.getValue().entrySet()) {
                trackCopy.put(sampleEntry.getKey(), copyLightSample(sampleEntry.getValue()));
            }
            lightTracks.put(entry.getKey(), trackCopy);
        }
        for (Map.Entry<Engine.ForceField, TreeMap<Integer, ForceSample>> entry : source.forceTracks.entrySet()) {
            TreeMap<Integer, ForceSample> trackCopy = new TreeMap<>();
            for (Map.Entry<Integer, ForceSample> sampleEntry : entry.getValue().entrySet()) {
                trackCopy.put(sampleEntry.getKey(), copyForceSample(sampleEntry.getValue()));
            }
            forceTracks.put(entry.getKey(), trackCopy);
        }
        for (Map.Entry<Integer, CameraSample> entry : source.cameraTrack.entrySet()) {
            cameraTrack.put(entry.getKey(), copyCameraSample(entry.getValue()));
        }
    }

    boolean contentEquals(SceneTimeline other) {
        if (other == this) {
            return true;
        }
        if (other == null) {
            return false;
        }
        return timelineSignature().equals(other.timelineSignature());
    }

    void clear() {
        entityTracks.clear();
        entityReleaseTracks.clear();
        lightTracks.clear();
        forceTracks.clear();
        cameraTrack.clear();
    }

    void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        entityTracks.remove(entity);
        entityReleaseTracks.remove(entity);
    }

    void removeLight(Light light) {
        if (light == null) {
            return;
        }
        lightTracks.remove(light);
    }

    void removeForceField(Engine.ForceField field) {
        if (field == null) {
            return;
        }
        forceTracks.remove(field);
    }

    boolean addOrReplaceEntityKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        int safeFrame = Math.max(0, frame);
        TreeMap<Integer, EntitySample> track = entityTracks.computeIfAbsent(entity, k -> new TreeMap<>());
        track.put(safeFrame, captureEntity(entity));
        return true;
    }

    boolean addOrReplaceEntityReleaseKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        int safeFrame = Math.max(0, frame);
        TreeSet<Integer> track = entityReleaseTracks.computeIfAbsent(entity, k -> new TreeSet<>());
        return track.add(safeFrame);
    }

    boolean addOrReplaceLightKey(Light light, int frame) {
        if (light == null) {
            return false;
        }
        int safeFrame = Math.max(0, frame);
        TreeMap<Integer, LightSample> track = lightTracks.computeIfAbsent(light, k -> new TreeMap<>());
        track.put(safeFrame, captureLight(light));
        return true;
    }

    boolean addOrReplaceForceKey(Engine.ForceField field, int frame) {
        if (field == null) {
            return false;
        }
        int safeFrame = Math.max(0, frame);
        TreeMap<Integer, ForceSample> track = forceTracks.computeIfAbsent(field, k -> new TreeMap<>());
        track.put(safeFrame, captureForce(field));
        return true;
    }

    boolean addOrReplaceCameraKey(Engine engine, int frame) {
        if (engine == null) {
            return false;
        }
        int safeFrame = Math.max(0, frame);
        cameraTrack.put(safeFrame, captureCamera(engine));
        return true;
    }

    boolean removeEntityKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        TreeMap<Integer, EntitySample> track = entityTracks.get(entity);
        if (track == null) {
            return false;
        }
        EntitySample removed = track.remove(Math.max(0, frame));
        if (track.isEmpty()) {
            entityTracks.remove(entity);
        }
        return removed != null;
    }

    boolean removeEntityReleaseKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        TreeSet<Integer> track = entityReleaseTracks.get(entity);
        if (track == null) {
            return false;
        }
        boolean removed = track.remove(Math.max(0, frame));
        if (track.isEmpty()) {
            entityReleaseTracks.remove(entity);
        }
        return removed;
    }

    boolean removeLightKey(Light light, int frame) {
        if (light == null) {
            return false;
        }
        TreeMap<Integer, LightSample> track = lightTracks.get(light);
        if (track == null) {
            return false;
        }
        LightSample removed = track.remove(Math.max(0, frame));
        if (track.isEmpty()) {
            lightTracks.remove(light);
        }
        return removed != null;
    }

    boolean removeForceKey(Engine.ForceField field, int frame) {
        if (field == null) {
            return false;
        }
        TreeMap<Integer, ForceSample> track = forceTracks.get(field);
        if (track == null) {
            return false;
        }
        ForceSample removed = track.remove(Math.max(0, frame));
        if (track.isEmpty()) {
            forceTracks.remove(field);
        }
        return removed != null;
    }

    boolean removeCameraKey(int frame) {
        CameraSample removed = cameraTrack.remove(Math.max(0, frame));
        return removed != null;
    }

    boolean hasEntityKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        TreeMap<Integer, EntitySample> track = entityTracks.get(entity);
        return track != null && track.containsKey(Math.max(0, frame));
    }

    boolean hasEntityReleaseKey(Entity entity, int frame) {
        if (entity == null) {
            return false;
        }
        TreeSet<Integer> track = entityReleaseTracks.get(entity);
        return track != null && track.contains(Math.max(0, frame));
    }

    boolean hasLightKey(Light light, int frame) {
        if (light == null) {
            return false;
        }
        TreeMap<Integer, LightSample> track = lightTracks.get(light);
        return track != null && track.containsKey(Math.max(0, frame));
    }

    boolean hasForceKey(Engine.ForceField field, int frame) {
        if (field == null) {
            return false;
        }
        TreeMap<Integer, ForceSample> track = forceTracks.get(field);
        return track != null && track.containsKey(Math.max(0, frame));
    }

    boolean hasCameraKey(int frame) {
        return cameraTrack.containsKey(Math.max(0, frame));
    }

    int keyCount(Entity entity) {
        if (entity == null) {
            return 0;
        }
        TreeMap<Integer, EntitySample> track = entityTracks.get(entity);
        return track == null ? 0 : track.size();
    }

    int releaseKeyCount(Entity entity) {
        if (entity == null) {
            return 0;
        }
        TreeSet<Integer> track = entityReleaseTracks.get(entity);
        return track == null ? 0 : track.size();
    }

    int keyCount(Light light) {
        if (light == null) {
            return 0;
        }
        TreeMap<Integer, LightSample> track = lightTracks.get(light);
        return track == null ? 0 : track.size();
    }

    int keyCount(Engine.ForceField field) {
        if (field == null) {
            return 0;
        }
        TreeMap<Integer, ForceSample> track = forceTracks.get(field);
        return track == null ? 0 : track.size();
    }

    int cameraKeyCount() {
        return cameraTrack.size();
    }

    int totalEntityKeys() {
        int total = 0;
        for (TreeMap<Integer, EntitySample> track : entityTracks.values()) {
            total += track.size();
        }
        return total;
    }

    int totalEntityReleaseKeys() {
        int total = 0;
        for (TreeSet<Integer> track : entityReleaseTracks.values()) {
            total += track.size();
        }
        return total;
    }

    int totalLightKeys() {
        int total = 0;
        for (TreeMap<Integer, LightSample> track : lightTracks.values()) {
            total += track.size();
        }
        return total;
    }

    int totalForceKeys() {
        int total = 0;
        for (TreeMap<Integer, ForceSample> track : forceTracks.values()) {
            total += track.size();
        }
        return total;
    }

    int totalKeyCount() {
        return totalEntityKeys() + totalEntityReleaseKeys() + totalLightKeys() + totalForceKeys() + cameraKeyCount();
    }

    boolean hasAnyKeyframes() {
        return totalKeyCount() > 0;
    }

    boolean hasAnyCameraKeys() {
        return !cameraTrack.isEmpty();
    }

    void applyAtFrame(Engine engine, int frame) {
        if (engine == null) {
            return;
        }
        int safeFrame = Math.max(0, frame);

        for (Map.Entry<Entity, TreeMap<Integer, EntitySample>> entry : entityTracks.entrySet()) {
            Entity entity = entry.getKey();
            TreeMap<Integer, EntitySample> track = entry.getValue();
            if (entity == null || track == null || track.isEmpty()) {
                continue;
            }
            EntitySample sample = evaluateEntity(track, safeFrame);
            if (sample != null) {
                boolean releaseActive = isEntityReleaseActive(track, entityReleaseTracks.get(entity), safeFrame);
                if (releaseActive) {
                    applyEntityMaterialState(entity, sample);
                    applyEntityVisualState(entity, sample);
                } else {
                    applyEntity(entity, sample);
                }
                Engine.SceneItemState state = engine.stateFor(entity);
                state.visibleInView = sample.visible;
                state.visibleInOutput = sample.visible;
            }
        }

        for (Map.Entry<Light, TreeMap<Integer, LightSample>> entry : lightTracks.entrySet()) {
            Light light = entry.getKey();
            TreeMap<Integer, LightSample> track = entry.getValue();
            if (light == null || track == null || track.isEmpty()) {
                continue;
            }
            LightSample sample = evaluateLight(track, safeFrame);
            if (sample != null) {
                applyLight(light, sample);
                Engine.SceneItemState state = engine.stateFor(light);
                state.visibleInView = sample.enabled;
                state.visibleInOutput = sample.enabled;
            }
        }

        for (Map.Entry<Engine.ForceField, TreeMap<Integer, ForceSample>> entry : forceTracks.entrySet()) {
            Engine.ForceField field = entry.getKey();
            TreeMap<Integer, ForceSample> track = entry.getValue();
            if (field == null || track == null || track.isEmpty()) {
                continue;
            }
            ForceSample sample = evaluateForce(track, safeFrame);
            if (sample != null) {
                applyForce(field, sample);
            }
        }

        if (!cameraTrack.isEmpty()) {
            CameraSample sample = evaluateCamera(cameraTrack, safeFrame);
            if (sample != null) {
                applyCamera(engine, sample);
            }
        }
    }

    NavigableMap<Integer, Integer> buildGlobalFrameUsage() {
        TreeMap<Integer, Integer> counts = new TreeMap<>();
        accumulateFrames(counts, entityTracks);
        accumulateFramesFromSets(counts, entityReleaseTracks);
        accumulateFrames(counts, lightTracks);
        accumulateFrames(counts, forceTracks);
        for (Integer frame : cameraTrack.keySet()) {
            counts.merge(frame, 1, Integer::sum);
        }
        return counts;
    }

    Set<Integer> selectedFramesFor(Entity selectedEntity,
                                   Light selectedLight,
                                   Engine.ForceField selectedForce,
                                   Entity outputCameraEntity) {
        if (selectedEntity != null && selectedEntity == outputCameraEntity) {
            LinkedHashSet<Integer> out = new LinkedHashSet<>(cameraTrack.keySet());
            TreeMap<Integer, EntitySample> entityTrack = entityTracks.get(selectedEntity);
            if (entityTrack != null) {
                out.addAll(entityTrack.keySet());
            }
            TreeSet<Integer> releaseTrack = entityReleaseTracks.get(selectedEntity);
            if (releaseTrack != null) {
                out.addAll(releaseTrack);
            }
            return out;
        }
        if (selectedEntity != null) {
            LinkedHashSet<Integer> out = new LinkedHashSet<>();
            TreeMap<Integer, EntitySample> track = entityTracks.get(selectedEntity);
            if (track != null) {
                out.addAll(track.keySet());
            }
            TreeSet<Integer> releaseTrack = entityReleaseTracks.get(selectedEntity);
            if (releaseTrack != null) {
                out.addAll(releaseTrack);
            }
            return out.isEmpty() ? Collections.emptySet() : out;
        }
        if (selectedLight != null) {
            TreeMap<Integer, LightSample> track = lightTracks.get(selectedLight);
            return track == null ? Collections.emptySet() : new LinkedHashSet<>(track.keySet());
        }
        if (selectedForce != null) {
            TreeMap<Integer, ForceSample> track = forceTracks.get(selectedForce);
            return track == null ? Collections.emptySet() : new LinkedHashSet<>(track.keySet());
        }
        return new LinkedHashSet<>(cameraTrack.keySet());
    }

    private static void accumulateFrames(TreeMap<Integer, Integer> counts,
                                         IdentityHashMap<?, ? extends TreeMap<Integer, ?>> tracks) {
        for (TreeMap<Integer, ?> track : tracks.values()) {
            if (track == null) {
                continue;
            }
            for (Integer frame : track.keySet()) {
                counts.merge(frame, 1, Integer::sum);
            }
        }
    }

    private static void accumulateFramesFromSets(TreeMap<Integer, Integer> counts,
                                                 IdentityHashMap<?, ? extends Set<Integer>> tracks) {
        for (Set<Integer> track : tracks.values()) {
            if (track == null) {
                continue;
            }
            for (Integer frame : track) {
                counts.merge(frame, 1, Integer::sum);
            }
        }
    }

    private static EntitySample captureEntity(Entity entity) {
        Transform t = entity.getTransform();
        PhongMaterial material = toPhongMaterial(entity.getMaterial());
        return new EntitySample(
                t.getPosition(),
                t.getEulerAngles(),
                t.getScale(),
                entity.isVisible(),
                entity.isCastShadow(),
                material.getName(),
                material.getAmbientColor(),
                material.getDiffuseColor(),
                material.getSpecularColor(),
                material.getShininess(),
                material.getReflectivity(),
                material.getRefractiveIndex(),
                material.getOpacity(),
                material.getDiffuseTexture(),
                material.isTextureFilteringLinear(),
                material.copy()
        );
    }

    private static LightSample captureLight(Light light) {
        Vec3 direction = new Vec3(0.0, -1.0, 0.0);
        Vec3 position = Vec3.ZERO;
        double constant = 1.0;
        double linear = 0.09;
        double quadratic = 0.032;
        Vec3 emissionDirection = new Vec3(0.0, -1.0, 0.0);
        double spread = 120.0;
        double cone = 38.0;
        double softness = 0.25;

        if (light instanceof DirectionalLight) {
            direction = ((DirectionalLight) light).getDirection();
        } else if (light instanceof PointLight) {
            PointLight pl = (PointLight) light;
            position = pl.getPosition();
            constant = pl.getConstant();
            linear = pl.getLinear();
            quadratic = pl.getQuadratic();
            if (pl instanceof AreaLight) {
                AreaLight al = (AreaLight) pl;
                emissionDirection = al.getEmissionDirection();
                spread = al.getSpreadAngleDegrees();
            }
            if (pl instanceof ConeLight) {
                ConeLight cl = (ConeLight) pl;
                direction = cl.getDirection();
                cone = cl.getConeAngleDegrees();
                softness = cl.getSoftness();
            }
        }

        return new LightSample(
                light.getColor(),
                light.getIntensity(),
                light.isEnabled(),
                direction,
                position,
                constant,
                linear,
                quadratic,
                emissionDirection,
                spread,
                cone,
                softness
        );
    }

    private static ForceSample captureForce(Engine.ForceField field) {
        return new ForceSample(
                field.name,
                field.type,
                field.position,
                field.direction,
                field.strength,
                field.attract,
                field.radius,
                field.turbulenceScale,
                field.seed
        );
    }

    private static CameraSample captureCamera(Engine engine) {
        Vec3 pos;
        Vec3 forward;
        if (engine.outputCameraEntity != null) {
            pos = engine.outputCameraEntity.getTransform().getPosition();
            forward = engine.outputCameraForward();
        } else if (engine.camera != null) {
            pos = engine.camera.getPosition();
            forward = engine.camera.getForward().normalize();
        } else {
            pos = Vec3.ZERO;
            forward = new Vec3(0.0, 0.0, -1.0);
        }

        double fov = 70.0;
        if (engine.perspectiveCamera != null) {
            fov = Math.toDegrees(engine.perspectiveCamera.getFovY());
        } else if (engine.camera instanceof PerspectiveCamera) {
            fov = Math.toDegrees(((PerspectiveCamera) engine.camera).getFovY());
        }
        double near = engine.camera != null ? engine.camera.getNear() : 0.1;
        double far = engine.camera != null ? engine.camera.getFar() : 300.0;
        return new CameraSample(pos, forward, fov, near, far, engine.orthographicProjection);
    }

    private static EntitySample copyEntitySample(EntitySample sample) {
        if (sample == null) {
            return null;
        }
        return new EntitySample(
                copyVec3(sample.position),
                copyVec3(sample.euler),
                copyVec3(sample.scale),
                sample.visible,
                sample.castShadow,
                sample.materialName,
                copyVec3(sample.materialAmbient),
                copyVec3(sample.materialDiffuse),
                copyVec3(sample.materialSpecular),
                sample.materialShininess,
                sample.materialReflectivity,
                sample.materialRefractiveIndex,
                sample.materialOpacity,
                sample.materialTexture,
                sample.materialTextureLinear,
                sample.materialSnapshot == null ? null : sample.materialSnapshot.copy()
        );
    }

    private static LightSample copyLightSample(LightSample sample) {
        if (sample == null) {
            return null;
        }
        return new LightSample(
                copyVec3(sample.color),
                sample.intensity,
                sample.enabled,
                copyVec3(sample.direction),
                copyVec3(sample.position),
                sample.attenuationConstant,
                sample.attenuationLinear,
                sample.attenuationQuadratic,
                copyVec3(sample.emissionDirection),
                sample.spreadAngleDegrees,
                sample.coneAngleDegrees,
                sample.softness
        );
    }

    private static ForceSample copyForceSample(ForceSample sample) {
        if (sample == null) {
            return null;
        }
        return new ForceSample(
                sample.name,
                sample.type,
                copyVec3(sample.position),
                copyVec3(sample.direction),
                sample.strength,
                sample.attract,
                sample.radius,
                sample.turbulenceScale,
                sample.seed
        );
    }

    private static CameraSample copyCameraSample(CameraSample sample) {
        if (sample == null) {
            return null;
        }
        return new CameraSample(
                copyVec3(sample.position),
                copyVec3(sample.forward),
                sample.fovYDeg,
                sample.nearPlane,
                sample.farPlane,
                sample.orthographicProjection
        );
    }

    private String timelineSignature() {
        StringBuilder builder = new StringBuilder(2048);
        appendEntityTracks(builder);
        appendReleaseTracks(builder);
        appendLightTracks(builder);
        appendForceTracks(builder);
        appendCameraTrack(builder);
        return builder.toString();
    }

    private void appendEntityTracks(StringBuilder builder) {
        builder.append("entities[");
        for (Map.Entry<Entity, TreeMap<Integer, EntitySample>> entry : entityTracks.entrySet()) {
            builder.append(System.identityHashCode(entry.getKey())).append('{');
            for (Map.Entry<Integer, EntitySample> sampleEntry : entry.getValue().entrySet()) {
                EntitySample sample = sampleEntry.getValue();
                builder.append(sampleEntry.getKey()).append('=')
                        .append(vecSignature(sample.position)).append('|')
                        .append(vecSignature(sample.euler)).append('|')
                        .append(vecSignature(sample.scale)).append('|')
                        .append(sample.visible).append('|')
                        .append(sample.castShadow).append('|')
                        .append(sample.materialName).append('|')
                        .append(vecSignature(sample.materialAmbient)).append('|')
                        .append(vecSignature(sample.materialDiffuse)).append('|')
                        .append(vecSignature(sample.materialSpecular)).append('|')
                        .append(sample.materialShininess).append('|')
                        .append(sample.materialReflectivity).append('|')
                        .append(sample.materialRefractiveIndex).append('|')
                        .append(sample.materialOpacity).append('|')
                        .append(System.identityHashCode(sample.materialTexture)).append('|')
                        .append(sample.materialTextureLinear).append('|')
                        .append(materialSignature(sample.materialSnapshot))
                        .append(';');
            }
            builder.append('}');
        }
        builder.append(']');
    }

    private void appendReleaseTracks(StringBuilder builder) {
        builder.append("release[");
        for (Map.Entry<Entity, TreeSet<Integer>> entry : entityReleaseTracks.entrySet()) {
            builder.append(System.identityHashCode(entry.getKey())).append('=').append(entry.getValue()).append(';');
        }
        builder.append(']');
    }

    private void appendLightTracks(StringBuilder builder) {
        builder.append("lights[");
        for (Map.Entry<Light, TreeMap<Integer, LightSample>> entry : lightTracks.entrySet()) {
            builder.append(System.identityHashCode(entry.getKey())).append('{');
            for (Map.Entry<Integer, LightSample> sampleEntry : entry.getValue().entrySet()) {
                LightSample sample = sampleEntry.getValue();
                builder.append(sampleEntry.getKey()).append('=')
                        .append(vecSignature(sample.color)).append('|')
                        .append(sample.intensity).append('|')
                        .append(sample.enabled).append('|')
                        .append(vecSignature(sample.direction)).append('|')
                        .append(vecSignature(sample.position)).append('|')
                        .append(sample.attenuationConstant).append('|')
                        .append(sample.attenuationLinear).append('|')
                        .append(sample.attenuationQuadratic).append('|')
                        .append(vecSignature(sample.emissionDirection)).append('|')
                        .append(sample.spreadAngleDegrees).append('|')
                        .append(sample.coneAngleDegrees).append('|')
                        .append(sample.softness)
                        .append(';');
            }
            builder.append('}');
        }
        builder.append(']');
    }

    private void appendForceTracks(StringBuilder builder) {
        builder.append("forces[");
        for (Map.Entry<Engine.ForceField, TreeMap<Integer, ForceSample>> entry : forceTracks.entrySet()) {
            builder.append(System.identityHashCode(entry.getKey())).append('{');
            for (Map.Entry<Integer, ForceSample> sampleEntry : entry.getValue().entrySet()) {
                ForceSample sample = sampleEntry.getValue();
                builder.append(sampleEntry.getKey()).append('=')
                        .append(sample.name).append('|')
                        .append(sample.type).append('|')
                        .append(vecSignature(sample.position)).append('|')
                        .append(vecSignature(sample.direction)).append('|')
                        .append(sample.strength).append('|')
                        .append(sample.attract).append('|')
                        .append(sample.radius).append('|')
                        .append(sample.turbulenceScale).append('|')
                        .append(sample.seed)
                        .append(';');
            }
            builder.append('}');
        }
        builder.append(']');
    }

    private void appendCameraTrack(StringBuilder builder) {
        builder.append("camera[");
        for (Map.Entry<Integer, CameraSample> entry : cameraTrack.entrySet()) {
            CameraSample sample = entry.getValue();
            builder.append(entry.getKey()).append('=')
                    .append(vecSignature(sample.position)).append('|')
                    .append(vecSignature(sample.forward)).append('|')
                    .append(sample.fovYDeg).append('|')
                    .append(sample.nearPlane).append('|')
                    .append(sample.farPlane).append('|')
                    .append(sample.orthographicProjection)
                    .append(';');
        }
        builder.append(']');
    }

    private static String materialSignature(PhongMaterial material) {
        if (material == null) {
            return "<null>";
        }
        return material.getName()
                + '|'
                + vecSignature(material.getAmbientColor())
                + '|'
                + vecSignature(material.getDiffuseColor())
                + '|'
                + vecSignature(material.getSpecularColor())
                + '|'
                + material.getShininess()
                + '|'
                + material.getReflectivity()
                + '|'
                + material.getRefractiveIndex()
                + '|'
                + material.getOpacity()
                + '|'
                + material.getRoughness()
                + '|'
                + material.getMetallic()
                + '|'
                + material.getTransmission()
                + '|'
                + vecSignature(material.getEmissionColor())
                + '|'
                + material.getEmissionStrength()
                + '|'
                + vecSignature(material.getMediumColor())
                + '|'
                + material.getDensity()
                + '|'
                + material.getAnisotropy()
                + '|'
                + material.getThickness();
    }

    private static String vecSignature(Vec3 value) {
        if (value == null) {
            return "0,0,0";
        }
        return value.x + "," + value.y + "," + value.z;
    }

    private static Vec3 copyVec3(Vec3 value) {
        if (value == null) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        return new Vec3(value.x, value.y, value.z);
    }

    private static void applyEntity(Entity entity, EntitySample sample) {
        Transform t = entity.getTransform();
        t.setPosition(sample.position);
        t.setEulerAngles(sample.euler.x, sample.euler.y, sample.euler.z);
        t.setScale(sample.scale);
        applyEntityMaterialState(entity, sample);
        applyEntityVisualState(entity, sample);
        if (entity.getRigidBody() != null) {
            entity.getRigidBody().setVelocity(Vec3.ZERO);
        }
    }

    private static void applyEntityVisualState(Entity entity, EntitySample sample) {
        entity.setVisible(sample.visible);
        entity.setCastShadow(sample.castShadow);
    }

    private static void applyEntityMaterialState(Entity entity, EntitySample sample) {
        if (sample.materialSnapshot != null) {
            entity.setMaterial(sample.materialSnapshot.copy());
            return;
        }
        PhongMaterial mat = toPhongMaterial(entity.getMaterial());
        mat.setName(sample.materialName == null ? "Material" : sample.materialName);
        Vec3 diffuse = sample.materialDiffuse == null ? new Vec3(0.7, 0.7, 0.7) : sample.materialDiffuse;
        Vec3 ambient = sample.materialAmbient == null ? diffuse.mul(0.1) : sample.materialAmbient;
        Vec3 spec = sample.materialSpecular == null ? Vec3.ONE : sample.materialSpecular;
        mat.setAmbientColor(ambient);
        mat.setDiffuseColor(diffuse);
        mat.setSpecularColor(spec);
        mat.setShininess(sample.materialShininess);
        mat.setReflectivity(sample.materialReflectivity);
        mat.setRefractiveIndex(sample.materialRefractiveIndex);
        mat.setOpacity(sample.materialOpacity);
        mat.setDiffuseTexture(sample.materialTexture);
        mat.setTextureFilteringLinear(sample.materialTextureLinear);
        entity.setMaterial(mat);
    }

    private static boolean isEntityReleaseActive(TreeMap<Integer, EntitySample> keyTrack,
                                                 TreeSet<Integer> releaseTrack,
                                                 int frame) {
        if (releaseTrack == null || releaseTrack.isEmpty()) {
            return false;
        }
        Integer releaseFrame = releaseTrack.floor(frame);
        if (releaseFrame == null) {
            return false;
        }
        Integer keyFrame = keyTrack == null ? null : keyTrack.floorKey(frame);
        return keyFrame == null || releaseFrame >= keyFrame;
    }

    private static PhongMaterial toPhongMaterial(Material material) {
        if (material instanceof PhongMaterial) {
            return (PhongMaterial) material;
        }
        Vec3 base = material != null ? material.getBaseColor() : new Vec3(0.7, 0.7, 0.7);
        PhongMaterial out = new PhongMaterial(base, 32.0);
        if (material != null) {
            out.copyFrom(material);
        }
        return out;
    }

    private static void applyLight(Light light, LightSample sample) {
        light.setColor(sample.color);
        light.setIntensity(sample.intensity);
        light.setEnabled(sample.enabled);

        if (light instanceof DirectionalLight) {
            ((DirectionalLight) light).setDirection(safeNormalize(sample.direction, new Vec3(0.0, -1.0, 0.0)));
        } else if (light instanceof PointLight) {
            PointLight pl = (PointLight) light;
            pl.setPosition(sample.position);
            pl.setAttenuation(sample.attenuationConstant, sample.attenuationLinear, sample.attenuationQuadratic);
            if (pl instanceof AreaLight) {
                AreaLight al = (AreaLight) pl;
                al.setEmissionDirection(safeNormalize(sample.emissionDirection, new Vec3(0.0, -1.0, 0.0)));
                al.setSpreadAngleDegrees(sample.spreadAngleDegrees);
            }
            if (pl instanceof ConeLight) {
                ConeLight cl = (ConeLight) pl;
                cl.setDirection(safeNormalize(sample.direction, new Vec3(0.0, -1.0, 0.0)));
                cl.setConeAngleDegrees(sample.coneAngleDegrees);
                cl.setSoftness(sample.softness);
            }
        }
    }

    private static void applyForce(Engine.ForceField field, ForceSample sample) {
        field.name = sample.name;
        field.type = sample.type;
        field.position = sample.position;
        field.direction = sample.direction;
        field.strength = sample.strength;
        field.attract = sample.attract;
        field.radius = sample.radius;
        field.turbulenceScale = sample.turbulenceScale;
        field.seed = sample.seed;
    }

    private static void applyCamera(Engine engine, CameraSample sample) {
        if (engine.outputCameraEntity == null && engine.scene != null) {
            engine.outputCameraEntity = engine.createOutputCameraEntity();
            engine.scene.addEntity(engine.outputCameraEntity);
        }
        if (engine.outputCameraEntity != null) {
            double yaw = -Math.atan2(sample.forward.x, -sample.forward.z);
            double pitch = Math.asin(clamp(sample.forward.y, -1.0, 1.0));
            engine.outputCameraEntity.getTransform().setPosition(sample.position);
            engine.outputCameraEntity.getTransform().setEulerAngles(pitch, yaw, 0.0);
        }

        if (engine.perspectiveCamera != null) {
            engine.perspectiveCamera.setFovY(sample.fovYDeg);
            engine.perspectiveCamera.setClipping(sample.nearPlane, sample.farPlane);
        }
        if (engine.orthographicCamera != null) {
            engine.orthographicCamera.setClipping(sample.nearPlane, sample.farPlane);
        }

        if (engine.orthographicProjection != sample.orthographicProjection) {
            EngineCameraRuntime.toggleProjectionCamera(engine);
        }

        boolean viewThroughOutput = engine.camera != null
                && CameraViewUtil.isViewingThroughOutputCamera(engine.outputCameraEntity, engine.camera);
        if (viewThroughOutput || engine.selectedEntity == engine.outputCameraEntity) {
            EngineCameraRuntime.applyCameraPose(engine, sample.position, sample.forward);
            EngineCameraRuntime.rememberCurrentBlendPose(engine);
            EngineCameraRuntime.rememberCurrentFpsPose(engine);
        }
    }

    private static EntitySample evaluateEntity(TreeMap<Integer, EntitySample> track, int frame) {
        EntitySample exact = track.get(frame);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, EntitySample> floor = track.floorEntry(frame);
        Map.Entry<Integer, EntitySample> ceil = track.ceilingEntry(frame);
        if (floor == null && ceil == null) {
            return null;
        }
        if (floor == null) {
            return ceil.getValue();
        }
        if (ceil == null) {
            return floor.getValue();
        }
        if (floor.getKey().intValue() == ceil.getKey().intValue()) {
            return floor.getValue();
        }
        double t = blendFactor(frame, floor.getKey(), ceil.getKey());
        EntitySample a = floor.getValue();
        EntitySample b = ceil.getValue();
        boolean boolFromA = a.visible;
        return new EntitySample(
                lerp(a.position, b.position, t),
                lerpEuler(a.euler, b.euler, t),
                lerp(a.scale, b.scale, t),
                boolFromA,
                a.castShadow,
                a.materialName,
                lerp(a.materialAmbient, b.materialAmbient, t),
                lerp(a.materialDiffuse, b.materialDiffuse, t),
                lerp(a.materialSpecular, b.materialSpecular, t),
                lerp(a.materialShininess, b.materialShininess, t),
                lerp(a.materialReflectivity, b.materialReflectivity, t),
                lerp(a.materialRefractiveIndex, b.materialRefractiveIndex, t),
                lerp(a.materialOpacity, b.materialOpacity, t),
                a.materialTexture,
                a.materialTextureLinear,
                blendMaterial(a.materialSnapshot, b.materialSnapshot, t)
        );
    }

    private static PhongMaterial blendMaterial(PhongMaterial a, PhongMaterial b, double t) {
        if (a == null && b == null) {
            return null;
        }
        if (a == null) {
            return b.copy();
        }
        if (b == null) {
            return a.copy();
        }
        PhongMaterial out = (t < 0.5 ? a.copy() : b.copy());
        out.setAmbientColor(lerp(a.getAmbientColor(), b.getAmbientColor(), t));
        out.setDiffuseColor(lerp(a.getDiffuseColor(), b.getDiffuseColor(), t));
        out.setSpecularColor(lerp(a.getSpecularColor(), b.getSpecularColor(), t));
        out.setShininess(lerp(a.getShininess(), b.getShininess(), t));
        out.setReflectivity(lerp(a.getReflectivity(), b.getReflectivity(), t));
        out.setRefractiveIndex(lerp(a.getRefractiveIndex(), b.getRefractiveIndex(), t));
        out.setOpacity(lerp(a.getOpacity(), b.getOpacity(), t));
        out.setRoughness(lerp(a.getRoughness(), b.getRoughness(), t));
        out.setMetallic(lerp(a.getMetallic(), b.getMetallic(), t));
        out.setTransmission(lerp(a.getTransmission(), b.getTransmission(), t));
        out.setEmissionColor(lerp(a.getEmissionColor(), b.getEmissionColor(), t));
        out.setEmissionStrength(lerp(a.getEmissionStrength(), b.getEmissionStrength(), t));
        out.setMediumColor(lerp(a.getMediumColor(), b.getMediumColor(), t));
        out.setDensity(lerp(a.getDensity(), b.getDensity(), t));
        out.setAnisotropy(lerp(a.getAnisotropy(), b.getAnisotropy(), t));
        out.setThickness(lerp(a.getThickness(), b.getThickness(), t));
        out.setNormalScale(lerp(a.getNormalScale(), b.getNormalScale(), t));
        out.setClearcoatFactor(lerp(a.getClearcoatFactor(), b.getClearcoatFactor(), t));
        out.setClearcoatRoughness(lerp(a.getClearcoatRoughness(), b.getClearcoatRoughness(), t));
        out.setSpecularFactor(lerp(a.getSpecularFactor(), b.getSpecularFactor(), t));
        out.setSpecularColorFactor(lerp(a.getSpecularColorFactor(), b.getSpecularColorFactor(), t));
        out.setSheenColor(lerp(a.getSheenColor(), b.getSheenColor(), t));
        out.setSheenRoughness(lerp(a.getSheenRoughness(), b.getSheenRoughness(), t));
        return out;
    }

    private static LightSample evaluateLight(TreeMap<Integer, LightSample> track, int frame) {
        LightSample exact = track.get(frame);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, LightSample> floor = track.floorEntry(frame);
        Map.Entry<Integer, LightSample> ceil = track.ceilingEntry(frame);
        if (floor == null && ceil == null) {
            return null;
        }
        if (floor == null) {
            return ceil.getValue();
        }
        if (ceil == null) {
            return floor.getValue();
        }
        if (floor.getKey().intValue() == ceil.getKey().intValue()) {
            return floor.getValue();
        }
        double t = blendFactor(frame, floor.getKey(), ceil.getKey());
        LightSample a = floor.getValue();
        LightSample b = ceil.getValue();
        return new LightSample(
                lerp(a.color, b.color, t),
                lerp(a.intensity, b.intensity, t),
                a.enabled,
                safeNormalize(lerp(a.direction, b.direction, t), a.direction),
                lerp(a.position, b.position, t),
                lerp(a.attenuationConstant, b.attenuationConstant, t),
                lerp(a.attenuationLinear, b.attenuationLinear, t),
                lerp(a.attenuationQuadratic, b.attenuationQuadratic, t),
                safeNormalize(lerp(a.emissionDirection, b.emissionDirection, t), a.emissionDirection),
                lerp(a.spreadAngleDegrees, b.spreadAngleDegrees, t),
                lerp(a.coneAngleDegrees, b.coneAngleDegrees, t),
                lerp(a.softness, b.softness, t)
        );
    }

    private static ForceSample evaluateForce(TreeMap<Integer, ForceSample> track, int frame) {
        ForceSample exact = track.get(frame);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, ForceSample> floor = track.floorEntry(frame);
        Map.Entry<Integer, ForceSample> ceil = track.ceilingEntry(frame);
        if (floor == null && ceil == null) {
            return null;
        }
        if (floor == null) {
            return ceil.getValue();
        }
        if (ceil == null) {
            return floor.getValue();
        }
        if (floor.getKey().intValue() == ceil.getKey().intValue()) {
            return floor.getValue();
        }
        double t = blendFactor(frame, floor.getKey(), ceil.getKey());
        ForceSample a = floor.getValue();
        ForceSample b = ceil.getValue();
        return new ForceSample(
                a.name,
                a.type,
                lerp(a.position, b.position, t),
                lerp(a.direction, b.direction, t),
                lerp(a.strength, b.strength, t),
                a.attract,
                lerp(a.radius, b.radius, t),
                lerp(a.turbulenceScale, b.turbulenceScale, t),
                lerp(a.seed, b.seed, t)
        );
    }

    private static CameraSample evaluateCamera(TreeMap<Integer, CameraSample> track, int frame) {
        CameraSample exact = track.get(frame);
        if (exact != null) {
            return exact;
        }
        Map.Entry<Integer, CameraSample> floor = track.floorEntry(frame);
        Map.Entry<Integer, CameraSample> ceil = track.ceilingEntry(frame);
        if (floor == null && ceil == null) {
            return null;
        }
        if (floor == null) {
            return ceil.getValue();
        }
        if (ceil == null) {
            return floor.getValue();
        }
        if (floor.getKey().intValue() == ceil.getKey().intValue()) {
            return floor.getValue();
        }
        double t = blendFactor(frame, floor.getKey(), ceil.getKey());
        CameraSample a = floor.getValue();
        CameraSample b = ceil.getValue();
        return new CameraSample(
                lerp(a.position, b.position, t),
                safeNormalize(lerp(a.forward, b.forward, t), a.forward),
                lerp(a.fovYDeg, b.fovYDeg, t),
                lerp(a.nearPlane, b.nearPlane, t),
                lerp(a.farPlane, b.farPlane, t),
                a.orthographicProjection
        );
    }

    private static double blendFactor(int frame, int f0, int f1) {
        return (double) (frame - f0) / (double) Math.max(1, (f1 - f0));
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                lerp(a.x, b.x, t),
                lerp(a.y, b.y, t),
                lerp(a.z, b.z, t)
        );
    }

    private static Vec3 lerpEuler(Vec3 a, Vec3 b, double t) {
        return new Vec3(
                lerpAngle(a.x, b.x, t),
                lerpAngle(a.y, b.y, t),
                lerpAngle(a.z, b.z, t)
        );
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double lerpAngle(double a, double b, double t) {
        double delta = wrapPi(b - a);
        return a + delta * t;
    }

    private static double wrapPi(double angle) {
        double out = angle;
        while (out > Math.PI) {
            out -= Math.PI * 2.0;
        }
        while (out < -Math.PI) {
            out += Math.PI * 2.0;
        }
        return out;
    }

    private static Vec3 safeNormalize(Vec3 v, Vec3 fallback) {
        Vec3 in = v == null ? fallback : v;
        if (in == null || in.lengthSquared() < 1e-12) {
            return fallback == null ? new Vec3(0.0, 0.0, -1.0) : fallback.normalize();
        }
        return in.normalize();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
