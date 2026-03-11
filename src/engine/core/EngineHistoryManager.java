package engine.core;

import engine.core.history.EditorCommand;
import engine.core.history.HistoryTransaction;
import engine.core.history.SnapshotEditorCommand;
import engine.material.MaterialNodeGraph;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Vec3;
import engine.physics.RigidBody;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;

import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

final class EngineHistoryManager {
    private static final IdentityHashMap<Engine, SceneSnapshot> LAST_SCENE_BASELINES = new IdentityHashMap<>();
    private static final IdentityHashMap<Engine, TimelineSnapshot> LAST_TIMELINE_BASELINES = new IdentityHashMap<>();
    private static final IdentityHashMap<Engine, PendingSceneGesture> PENDING_SCENE_GESTURES = new IdentityHashMap<>();

    private EngineHistoryManager() {
    }

    static void resetActionHistoryBaseline(Engine engine) {
        if (engine == null) {
            return;
        }
        engine.undoHistory.clear();
        engine.redoHistory.clear();
        engine.activeHistoryTransaction = null;
        PENDING_SCENE_GESTURES.remove(engine);
        LAST_SCENE_BASELINES.put(engine, captureSceneSnapshot(engine));
        LAST_TIMELINE_BASELINES.put(engine, captureTimelineSnapshot(engine));
    }

    static void updateActionHistory(Engine engine) {
        // Záměrně prázdné. Historie už nesmí vznikat pollingem po framech.
    }

    static boolean isHistoryRestoring(Engine engine) {
        return engine != null && engine.historyRestoring;
    }

    static String getUndoLabel(Engine engine) {
        if (engine == null || engine.undoHistory.isEmpty()) {
            return "";
        }
        EditorCommand command = engine.undoHistory.peekLast();
        return command == null ? "" : command.getLabel();
    }

    static String getRedoLabel(Engine engine) {
        if (engine == null || engine.redoHistory.isEmpty()) {
            return "";
        }
        EditorCommand command = engine.redoHistory.peekLast();
        return command == null ? "" : command.getLabel();
    }

    static void beginTransaction(Engine engine, String label) {
        if (engine == null || engine.historyRestoring) {
            return;
        }
        engine.activeHistoryTransaction = new HistoryTransaction(label);
    }

    static void commitTransaction(Engine engine) {
        if (engine == null || engine.activeHistoryTransaction == null) {
            return;
        }
        EditorCommand command = engine.activeHistoryTransaction.toCommand();
        engine.activeHistoryTransaction = null;
        if (command != null) {
            pushCommand(engine, command);
        }
    }

    static void cancelTransaction(Engine engine) {
        if (engine != null) {
            engine.activeHistoryTransaction = null;
        }
    }

    static void beginSceneGesture(Engine engine, String label) {
        if (engine == null || engine.historyRestoring || PENDING_SCENE_GESTURES.containsKey(engine)) {
            return;
        }
        PENDING_SCENE_GESTURES.put(engine, new PendingSceneGesture(label, captureSceneSnapshot(engine)));
    }

    static void commitSceneGesture(Engine engine) {
        if (engine == null || engine.historyRestoring) {
            return;
        }
        PendingSceneGesture pending = PENDING_SCENE_GESTURES.remove(engine);
        if (pending == null) {
            return;
        }
        SceneSnapshot after = captureSceneSnapshot(engine);
        EditorCommand command = createSceneCommand(engine, pending.label, pending.before, after);
        if (command != null) {
            pushCommand(engine, command);
            LAST_SCENE_BASELINES.put(engine, after);
            LAST_TIMELINE_BASELINES.put(engine, after.timeline);
        }
    }

    static void revertSceneGesture(Engine engine) {
        if (engine == null) {
            return;
        }
        PendingSceneGesture pending = PENDING_SCENE_GESTURES.remove(engine);
        if (pending == null || pending.before == null) {
            return;
        }
        runHistoryCommand(engine, () -> applySceneSnapshot(engine, pending.before));
        syncBaselines(engine);
    }

    static void recordSceneChange(Engine engine, String label, Runnable mutation) {
        recordSceneChange(engine, label, () -> {
            if (mutation != null) {
                mutation.run();
            }
            return null;
        });
    }

    static <T> T recordSceneChange(Engine engine, String label, Supplier<T> mutation) {
        if (mutation == null) {
            return null;
        }
        if (engine == null || engine.historyRestoring) {
            return mutation.get();
        }
        SceneSnapshot before = captureSceneSnapshot(engine);
        T result = mutation.get();
        SceneSnapshot after = captureSceneSnapshot(engine);
        EditorCommand command = createSceneCommand(engine, label, before, after);
        if (command != null) {
            pushCommand(engine, command);
        }
        LAST_SCENE_BASELINES.put(engine, after);
        LAST_TIMELINE_BASELINES.put(engine, after == null ? null : after.timeline);
        return result;
    }

    static void recordTimelineChange(Engine engine, String label, Runnable mutation) {
        recordTimelineChange(engine, label, () -> {
            if (mutation != null) {
                mutation.run();
            }
            return null;
        });
    }

    static <T> T recordTimelineChange(Engine engine, String label, Supplier<T> mutation) {
        if (mutation == null) {
            return null;
        }
        if (engine == null || engine.historyRestoring) {
            return mutation.get();
        }
        TimelineSnapshot before = captureTimelineSnapshot(engine);
        T result = mutation.get();
        TimelineSnapshot after = captureTimelineSnapshot(engine);
        EditorCommand command = createTimelineCommand(engine, label, before, after);
        if (command != null) {
            pushCommand(engine, command);
        }
        LAST_TIMELINE_BASELINES.put(engine, after);
        SceneSnapshot baseline = LAST_SCENE_BASELINES.get(engine);
        if (baseline != null) {
            baseline.timeline = after;
        }
        return result;
    }

    static PhongMaterial captureMaterialState(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getMaterial() instanceof PhongMaterial phong) {
            return phong.copy();
        }
        Vec3 base = entity.getMaterial() == null ? new Vec3(0.7, 0.7, 0.7) : entity.getMaterial().getBaseColor();
        PhongMaterial fallback = new PhongMaterial(base, 32.0);
        if (entity.getMaterial() != null) {
            fallback.copyFrom(entity.getMaterial());
        }
        return fallback;
    }

    static boolean materialStatesEqual(PhongMaterial a, PhongMaterial b) {
        return materialSignature(a).equals(materialSignature(b));
    }

    static void pushMaterialSnapshotCommand(Engine engine,
                                            String label,
                                            Entity entity,
                                            PhongMaterial before,
                                            PhongMaterial after) {
        if (engine == null || engine.historyRestoring || entity == null || before == null || after == null) {
            return;
        }
        if (materialStatesEqual(before, after)) {
            return;
        }
        EditorCommand command = new SnapshotEditorCommand(
                safeLabel(label, "Úprava materiálu"),
                () -> applyMaterialSnapshot(engine, entity, before),
                () -> applyMaterialSnapshot(engine, entity, after)
        );
        pushCommand(engine, command);
    }

    static void undoLastAction(Engine engine) {
        if (engine == null || engine.undoHistory.isEmpty()) {
            System.out.println("Undo: žádné akce.");
            return;
        }
        EditorCommand command = engine.undoHistory.removeLast();
        runHistoryCommand(engine, command::undo);
        engine.redoHistory.addLast(command);
        trimHistory(engine.redoHistory);
        syncBaselines(engine);
        System.out.println("Undo: " + command.getLabel());
    }

    static void redoLastAction(Engine engine) {
        if (engine == null || engine.redoHistory.isEmpty()) {
            System.out.println("Redo: žádné akce.");
            return;
        }
        EditorCommand command = engine.redoHistory.removeLast();
        runHistoryCommand(engine, command::redo);
        engine.undoHistory.addLast(command);
        trimHistory(engine.undoHistory);
        syncBaselines(engine);
        System.out.println("Redo: " + command.getLabel());
    }

    private static void pushCommand(Engine engine, EditorCommand command) {
        if (engine == null || command == null) {
            return;
        }
        if (engine.activeHistoryTransaction != null) {
            engine.activeHistoryTransaction.add(command);
            return;
        }
        if (!engine.undoHistory.isEmpty()) {
            EditorCommand previous = engine.undoHistory.peekLast();
            if (previous != null && previous.canMerge(command)) {
                engine.undoHistory.removeLast();
                engine.undoHistory.addLast(previous.merge(command));
                engine.redoHistory.clear();
                return;
            }
        }
        engine.undoHistory.addLast(command);
        trimHistory(engine.undoHistory);
        engine.redoHistory.clear();
    }

    private static void trimHistory(Deque<EditorCommand> history) {
        while (history.size() > Engine.ACTION_HISTORY_LIMIT) {
            history.removeFirst();
        }
    }

    private static void runHistoryCommand(Engine engine, Runnable action) {
        if (engine == null || action == null) {
            return;
        }
        engine.historyRestoring = true;
        try {
            action.run();
        } finally {
            engine.historyRestoring = false;
        }
    }

    private static void syncBaselines(Engine engine) {
        LAST_SCENE_BASELINES.put(engine, captureSceneSnapshot(engine));
        LAST_TIMELINE_BASELINES.put(engine, captureTimelineSnapshot(engine));
        PENDING_SCENE_GESTURES.remove(engine);
    }

    private static EditorCommand createSceneCommand(Engine engine,
                                                    String label,
                                                    SceneSnapshot before,
                                                    SceneSnapshot after) {
        if (before == null || after == null || sceneSnapshotsEqual(before, after)) {
            return null;
        }
        return new SnapshotEditorCommand(
                safeLabel(label, "Úprava scény"),
                () -> applySceneSnapshot(engine, before),
                () -> applySceneSnapshot(engine, after)
        );
    }

    private static EditorCommand createTimelineCommand(Engine engine,
                                                       String label,
                                                       TimelineSnapshot before,
                                                       TimelineSnapshot after) {
        if (before == null || after == null || timelineSnapshotsEqual(before, after)) {
            return null;
        }
        return new SnapshotEditorCommand(
                safeLabel(label, "Úprava časové osy"),
                () -> applyTimelineSnapshot(engine, before),
                () -> applyTimelineSnapshot(engine, after)
        );
    }

    private static String safeLabel(String label, String fallback) {
        return label == null || label.isBlank() ? fallback : label;
    }

    private static SceneSnapshot captureSceneSnapshot(Engine engine) {
        if (engine == null || engine.scene == null) {
            return null;
        }
        SceneSnapshot snapshot = new SceneSnapshot();
        snapshot.entities = new ArrayList<>();
        snapshot.lights = new ArrayList<>();
        snapshot.forceFields = new ArrayList<>();
        snapshot.worldLightColor = copyVec3(engine.worldLightColor);
        snapshot.worldBackgroundColor = copyVec3(engine.worldBackgroundColor);
        snapshot.worldLightStrength = engine.worldLightStrength;
        snapshot.worldLightAnimationEnabled = engine.worldLightAnimationEnabled;
        snapshot.spawnCounter = engine.spawnCounter;
        snapshot.lightCounter = engine.lightCounter;
        snapshot.forceFieldCounter = engine.forceFieldCounter;
        snapshot.rootEntity = engine.scene.getRootEntity();
        snapshot.demoEntity = engine.demoEntity;
        snapshot.floorEntity = engine.floorEntity;
        snapshot.outputCameraEntity = engine.outputCameraEntity;
        snapshot.sunLight = engine.sunLight;
        snapshot.fillLight = engine.fillLight;
        snapshot.warmWorldLight = engine.warmWorldLight;
        snapshot.coolWorldLight = engine.coolWorldLight;
        snapshot.selectedEntity = engine.selectedEntity;
        snapshot.selectedLight = engine.selectedLight;
        snapshot.selectedForceField = engine.selectedForceField;
        snapshot.timeline = captureTimelineSnapshot(engine);

        Set<RigidBody> physicsBodies = new HashSet<>();
        if (engine.physicsWorld != null) {
            physicsBodies.addAll(engine.physicsWorld.getBodies());
        }

        for (Entity entity : engine.scene.getEntities()) {
            SceneEntityState state = new SceneEntityState();
            state.entity = entity;
            state.name = entity.getName();
            state.position = copyVec3(entity.getTransform().getPosition());
            state.euler = copyVec3(entity.getTransform().getEulerAngles());
            state.scale = copyVec3(entity.getTransform().getScale());
            state.visible = entity.isVisible();
            state.castShadow = entity.isCastShadow();
            state.staticEntity = entity.isStatic();
            state.rigidBody = entity.getRigidBody();
            state.rigidBodyInPhysics = state.rigidBody != null && physicsBodies.contains(state.rigidBody);
            state.rigidBodyVelocity = state.rigidBody == null ? Vec3.ZERO : copyVec3(state.rigidBody.getVelocity());
            Engine.SceneItemState sceneState = engine.sceneItemStates.get(entity);
            state.visibleInView = sceneState == null || sceneState.visibleInView;
            state.visibleInOutput = sceneState == null || sceneState.visibleInOutput;
            snapshot.entities.add(state);
        }

        for (Light light : engine.scene.getLights()) {
            SceneLightState state = new SceneLightState();
            state.light = light;
            state.name = engine.lightNames.get(light);
            state.color = copyVec3(light.getColor());
            state.intensity = light.getIntensity();
            state.enabled = light.isEnabled();
            Engine.SceneItemState sceneState = engine.sceneItemStates.get(light);
            state.visibleInView = sceneState == null || sceneState.visibleInView;
            state.visibleInOutput = sceneState == null || sceneState.visibleInOutput;
            if (light instanceof DirectionalLight directional) {
                state.direction = copyVec3(directional.getDirection());
            }
            if (light instanceof PointLight point) {
                state.position = copyVec3(point.getPosition());
                state.attenuationConstant = point.getConstant();
                state.attenuationLinear = point.getLinear();
                state.attenuationQuadratic = point.getQuadratic();
                if (light instanceof AreaLight area) {
                    state.emissionDirection = copyVec3(area.getEmissionDirection());
                    state.spreadAngleDegrees = area.getSpreadAngleDegrees();
                } else if (light instanceof ConeLight cone) {
                    state.direction = copyVec3(cone.getDirection());
                    state.coneAngleDegrees = cone.getConeAngleDegrees();
                    state.softness = cone.getSoftness();
                }
            }
            snapshot.lights.add(state);
        }

        for (Engine.ForceField field : engine.forceFields) {
            SceneForceFieldState state = new SceneForceFieldState();
            state.field = field;
            state.name = field.name;
            state.type = field.type;
            state.position = copyVec3(field.position);
            state.direction = copyVec3(field.direction);
            state.strength = field.strength;
            state.attract = field.attract;
            state.radius = field.radius;
            state.turbulenceScale = field.turbulenceScale;
            state.seed = field.seed;
            Engine.SceneItemState sceneState = engine.sceneItemStates.get(field);
            state.visibleInView = sceneState == null || sceneState.visibleInView;
            state.visibleInOutput = sceneState == null || sceneState.visibleInOutput;
            snapshot.forceFields.add(state);
        }
        return snapshot;
    }

    private static void applySceneSnapshot(Engine engine, SceneSnapshot snapshot) {
        if (engine == null || snapshot == null || engine.scene == null) {
            return;
        }
        engine.worldLightColor = copyVec3(snapshot.worldLightColor);
        engine.worldBackgroundColor = copyVec3(snapshot.worldBackgroundColor);
        engine.worldLightStrength = snapshot.worldLightStrength;
        engine.worldLightAnimationEnabled = snapshot.worldLightAnimationEnabled;
        engine.spawnCounter = snapshot.spawnCounter;
        engine.lightCounter = snapshot.lightCounter;
        engine.forceFieldCounter = snapshot.forceFieldCounter;
        engine.scene.setRootEntity(snapshot.rootEntity);

        List<Entity> currentEntities = new ArrayList<>(engine.scene.getEntities());
        for (Entity entity : currentEntities) {
            engine.scene.removeEntity(entity);
        }
        for (SceneEntityState state : snapshot.entities) {
            if (state.entity == null) {
                continue;
            }
            engine.scene.addEntity(state.entity);
            state.entity.setName(state.name == null ? state.entity.getName() : state.name);
            state.entity.getTransform().setPosition(copyVec3(state.position));
            state.entity.getTransform().setEulerAngles(state.euler.x, state.euler.y, state.euler.z);
            state.entity.getTransform().setScale(copyVec3(state.scale));
            state.entity.setVisible(state.visible);
            state.entity.setCastShadow(state.castShadow);
            state.entity.setStatic(state.staticEntity);
            state.entity.setRigidBody(state.rigidBody);
            if (state.rigidBody != null) {
                state.rigidBody.setVelocity(copyVec3(state.rigidBodyVelocity));
            }
        }

        List<Light> currentLights = new ArrayList<>(engine.scene.getLights());
        for (Light light : currentLights) {
            engine.scene.removeLight(light);
        }
        engine.lightNames.clear();
        for (SceneLightState state : snapshot.lights) {
            if (state.light == null) {
                continue;
            }
            engine.scene.addLight(state.light);
            state.light.setColor(copyVec3(state.color));
            state.light.setIntensity(state.intensity);
            state.light.setEnabled(state.enabled);
            if (state.name != null && !state.name.isBlank()) {
                engine.lightNames.put(state.light, state.name);
            }
            if (state.light instanceof DirectionalLight directional && state.direction != null) {
                directional.setDirection(copyVec3(state.direction));
            }
            if (state.light instanceof PointLight point) {
                point.setPosition(copyVec3(state.position));
                point.setAttenuation(state.attenuationConstant, state.attenuationLinear, state.attenuationQuadratic);
                if (state.light instanceof AreaLight area) {
                    area.setEmissionDirection(copyVec3(state.emissionDirection));
                    area.setSpreadAngleDegrees(state.spreadAngleDegrees);
                } else if (state.light instanceof ConeLight cone) {
                    cone.setDirection(copyVec3(state.direction));
                    cone.setConeAngleDegrees(state.coneAngleDegrees);
                    cone.setSoftness(state.softness);
                }
            }
        }

        engine.forceFields.clear();
        for (SceneForceFieldState state : snapshot.forceFields) {
            if (state.field == null) {
                continue;
            }
            state.field.name = state.name;
            state.field.type = state.type;
            state.field.position = copyVec3(state.position);
            state.field.direction = copyVec3(state.direction);
            state.field.strength = state.strength;
            state.field.attract = state.attract;
            state.field.radius = state.radius;
            state.field.turbulenceScale = state.turbulenceScale;
            state.field.seed = state.seed;
            engine.forceFields.add(state.field);
        }

        if (engine.physicsWorld != null) {
            List<RigidBody> existingBodies = new ArrayList<>(engine.physicsWorld.getBodies());
            for (RigidBody body : existingBodies) {
                engine.physicsWorld.removeBody(body);
            }
            for (SceneEntityState state : snapshot.entities) {
                if (state.rigidBody != null && state.rigidBodyInPhysics) {
                    engine.physicsWorld.addBody(state.rigidBody);
                    state.rigidBody.setVelocity(copyVec3(state.rigidBodyVelocity));
                }
            }
        }

        engine.sceneItemStates.clear();
        for (SceneEntityState state : snapshot.entities) {
            Engine.SceneItemState itemState = new Engine.SceneItemState();
            itemState.visibleInView = state.visibleInView;
            itemState.visibleInOutput = state.visibleInOutput;
            engine.sceneItemStates.put(state.entity, itemState);
        }
        for (SceneLightState state : snapshot.lights) {
            Engine.SceneItemState itemState = new Engine.SceneItemState();
            itemState.visibleInView = state.visibleInView;
            itemState.visibleInOutput = state.visibleInOutput;
            engine.sceneItemStates.put(state.light, itemState);
        }
        for (SceneForceFieldState state : snapshot.forceFields) {
            Engine.SceneItemState itemState = new Engine.SceneItemState();
            itemState.visibleInView = state.visibleInView;
            itemState.visibleInOutput = state.visibleInOutput;
            engine.sceneItemStates.put(state.field, itemState);
        }

        engine.demoEntity = snapshot.demoEntity;
        engine.floorEntity = snapshot.floorEntity;
        engine.outputCameraEntity = snapshot.outputCameraEntity;
        engine.sunLight = snapshot.sunLight;
        engine.fillLight = snapshot.fillLight;
        engine.warmWorldLight = snapshot.warmWorldLight;
        engine.coolWorldLight = snapshot.coolWorldLight;

        engine.selectedEntity = snapshot.selectedEntity != null && engine.scene.getEntities().contains(snapshot.selectedEntity)
                ? snapshot.selectedEntity
                : null;
        engine.selectedLight = snapshot.selectedLight != null && engine.scene.getLights().contains(snapshot.selectedLight)
                ? snapshot.selectedLight
                : null;
        engine.selectedForceField = snapshot.selectedForceField != null && engine.forceFields.contains(snapshot.selectedForceField)
                ? snapshot.selectedForceField
                : null;
        engine.objectFocusMode = false;
        engine.draggingSelectedObject = false;
        engine.transformMode = Engine.TransformMode.NONE;
        engine.axisConstraint = Engine.AxisConstraint.NONE;
        engine.gizmoDragActive = false;
        engine.lastSelectionClickEntity = null;
        engine.lastSelectionClickNanos = 0L;

        applyTimelineSnapshot(engine, snapshot.timeline);
        engine.afterHistorySnapshotApplied();
    }

    private static TimelineSnapshot captureTimelineSnapshot(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return null;
        }
        TimelineSnapshot snapshot = new TimelineSnapshot();
        snapshot.timeline = engine.sceneTimeline.copy();
        snapshot.timelineEnabled = engine.timelineEnabled;
        snapshot.timelineStartFrame = engine.timelineStartFrame;
        snapshot.timelineEndFrame = engine.timelineEndFrame;
        snapshot.timelineCurrentFrame = engine.timelineCurrentFrame;
        snapshot.timelineFps = engine.timelineFps;
        snapshot.timelineFrameCursor = engine.timelineFrameCursor;
        return snapshot;
    }

    private static void applyTimelineSnapshot(Engine engine, TimelineSnapshot snapshot) {
        if (engine == null || snapshot == null || engine.sceneTimeline == null) {
            return;
        }
        engine.sceneTimeline.copyFrom(snapshot.timeline);
        engine.timelineEnabled = snapshot.timelineEnabled;
        engine.timelineStartFrame = snapshot.timelineStartFrame;
        engine.timelineEndFrame = snapshot.timelineEndFrame;
        engine.timelineCurrentFrame = snapshot.timelineCurrentFrame;
        engine.timelineFps = snapshot.timelineFps;
        engine.timelineFrameCursor = snapshot.timelineFrameCursor;
        if (engine.timelineEnabled && engine.sceneTimeline.hasAnyKeyframes()) {
            engine.sceneTimeline.applyAtFrame(engine, engine.timelineCurrentFrame);
        }
        EngineTimelineController.refreshUi(engine);
        engine.refreshObjectInspectorValues();
    }

    private static void applyMaterialSnapshot(Engine engine, Entity entity, PhongMaterial snapshot) {
        if (engine == null || entity == null || snapshot == null) {
            return;
        }
        entity.setMaterial(snapshot.copy());
        engine.refreshObjectInspectorValues();
        engine.rebuildSceneDetailsPanel();
        engine.rebuildMaterialDock();
        if (engine.window != null && engine.window.getCanvas() != null) {
            engine.window.getCanvas().repaint();
        }
    }

    private static boolean sceneSnapshotsEqual(SceneSnapshot a, SceneSnapshot b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (!vecEquals(a.worldLightColor, b.worldLightColor)
                || !vecEquals(a.worldBackgroundColor, b.worldBackgroundColor)
                || !nearEqual(a.worldLightStrength, b.worldLightStrength)
                || a.worldLightAnimationEnabled != b.worldLightAnimationEnabled
                || a.spawnCounter != b.spawnCounter
                || a.lightCounter != b.lightCounter
                || a.forceFieldCounter != b.forceFieldCounter
                || a.rootEntity != b.rootEntity
                || a.demoEntity != b.demoEntity
                || a.floorEntity != b.floorEntity
                || a.outputCameraEntity != b.outputCameraEntity
                || a.sunLight != b.sunLight
                || a.fillLight != b.fillLight
                || a.warmWorldLight != b.warmWorldLight
                || a.coolWorldLight != b.coolWorldLight
                || a.selectedEntity != b.selectedEntity
                || a.selectedLight != b.selectedLight
                || a.selectedForceField != b.selectedForceField
                || !timelineSnapshotsEqual(a.timeline, b.timeline)) {
            return false;
        }
        if (a.entities.size() != b.entities.size()
                || a.lights.size() != b.lights.size()
                || a.forceFields.size() != b.forceFields.size()) {
            return false;
        }

        for (int i = 0; i < a.entities.size(); i++) {
            SceneEntityState left = a.entities.get(i);
            SceneEntityState right = b.entities.get(i);
            boolean ignoreOutputCameraPose = left.entity != null
                    && right.entity != null
                    && left.entity == a.outputCameraEntity
                    && right.entity == b.outputCameraEntity;
            if (left.entity != right.entity
                    || !stringEquals(left.name, right.name)
                    || (!ignoreOutputCameraPose && !vecEquals(left.position, right.position))
                    || (!ignoreOutputCameraPose && !vecEquals(left.euler, right.euler))
                    || (!ignoreOutputCameraPose && !vecEquals(left.scale, right.scale))
                    || left.visible != right.visible
                    || left.castShadow != right.castShadow
                    || left.staticEntity != right.staticEntity
                    || left.rigidBody != right.rigidBody
                    || left.rigidBodyInPhysics != right.rigidBodyInPhysics
                    || !vecEquals(left.rigidBodyVelocity, right.rigidBodyVelocity)
                    || left.visibleInView != right.visibleInView
                    || left.visibleInOutput != right.visibleInOutput) {
                return false;
            }
        }

        for (int i = 0; i < a.lights.size(); i++) {
            SceneLightState left = a.lights.get(i);
            SceneLightState right = b.lights.get(i);
            if (left.light != right.light
                    || !stringEquals(left.name, right.name)
                    || !vecEquals(left.color, right.color)
                    || !nearEqual(left.intensity, right.intensity)
                    || left.enabled != right.enabled
                    || left.visibleInView != right.visibleInView
                    || left.visibleInOutput != right.visibleInOutput
                    || !vecEquals(left.direction, right.direction)
                    || !vecEquals(left.position, right.position)
                    || !vecEquals(left.emissionDirection, right.emissionDirection)
                    || !nearEqual(left.spreadAngleDegrees, right.spreadAngleDegrees)
                    || !nearEqual(left.coneAngleDegrees, right.coneAngleDegrees)
                    || !nearEqual(left.softness, right.softness)
                    || !nearEqual(left.attenuationConstant, right.attenuationConstant)
                    || !nearEqual(left.attenuationLinear, right.attenuationLinear)
                    || !nearEqual(left.attenuationQuadratic, right.attenuationQuadratic)) {
                return false;
            }
        }

        for (int i = 0; i < a.forceFields.size(); i++) {
            SceneForceFieldState left = a.forceFields.get(i);
            SceneForceFieldState right = b.forceFields.get(i);
            if (left.field != right.field
                    || !stringEquals(left.name, right.name)
                    || left.type != right.type
                    || !vecEquals(left.position, right.position)
                    || !vecEquals(left.direction, right.direction)
                    || !nearEqual(left.strength, right.strength)
                    || left.attract != right.attract
                    || !nearEqual(left.radius, right.radius)
                    || !nearEqual(left.turbulenceScale, right.turbulenceScale)
                    || !nearEqual(left.seed, right.seed)
                    || left.visibleInView != right.visibleInView
                    || left.visibleInOutput != right.visibleInOutput) {
                return false;
            }
        }
        return true;
    }

    private static boolean timelineSnapshotsEqual(TimelineSnapshot a, TimelineSnapshot b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return a.timelineEnabled == b.timelineEnabled
                && a.timelineStartFrame == b.timelineStartFrame
                && a.timelineEndFrame == b.timelineEndFrame
                && a.timelineCurrentFrame == b.timelineCurrentFrame
                && nearEqual(a.timelineFps, b.timelineFps)
                && nearEqual(a.timelineFrameCursor, b.timelineFrameCursor)
                && ((a.timeline == null && b.timeline == null)
                || (a.timeline != null && a.timeline.contentEquals(b.timeline)));
    }

    private static String materialSignature(PhongMaterial material) {
        if (material == null) {
            return "<null>";
        }
        StringBuilder builder = new StringBuilder(512);
        builder.append(material.getName()).append('|')
                .append(material.getPresetName()).append('|')
                .append(vecSignature(material.getBaseColor())).append('|')
                .append(material.getOpacity()).append('|')
                .append(material.getDomain()).append('|')
                .append(material.getShadingModel()).append('|')
                .append(material.getRoughness()).append('|')
                .append(material.getMetallic()).append('|')
                .append(material.getTransmission()).append('|')
                .append(vecSignature(material.getEmissionColor())).append('|')
                .append(material.getEmissionStrength()).append('|')
                .append(vecSignature(material.getMediumColor())).append('|')
                .append(material.getDensity()).append('|')
                .append(material.getAnisotropy()).append('|')
                .append(material.getThickness()).append('|')
                .append(material.isDoubleSided()).append('|')
                .append(vecSignature(material.getAmbientColor())).append('|')
                .append(vecSignature(material.getDiffuseColor())).append('|')
                .append(vecSignature(material.getSpecularColor())).append('|')
                .append(material.getShininess()).append('|')
                .append(material.getReflectivity()).append('|')
                .append(material.getRefractiveIndex()).append('|')
                .append(textureMapSignature(material.getDiffuseMap())).append('|')
                .append(textureMapSignature(material.getNormalMap())).append('|')
                .append(textureMapSignature(material.getMetallicRoughnessMap())).append('|')
                .append(textureMapSignature(material.getEmissiveMap())).append('|')
                .append(material.getNormalScale()).append('|')
                .append(material.getClearcoatFactor()).append('|')
                .append(material.getClearcoatRoughness()).append('|')
                .append(material.getSpecularFactor()).append('|')
                .append(vecSignature(material.getSpecularColorFactor())).append('|')
                .append(vecSignature(material.getSheenColor())).append('|')
                .append(material.getSheenRoughness()).append('|')
                .append(material.getAlphaMode()).append('|')
                .append(material.getAlphaCutoff()).append('|');
        MaterialNodeGraph graph = material.getNodeGraph();
        if (graph == null) {
            builder.append("graph:none");
            return builder.toString();
        }
        builder.append("graph:")
                .append(graph.getNextNodeId()).append('|')
                .append(graph.getSelectedNodeId()).append('|')
                .append(graph.getViewOffsetX()).append('|')
                .append(graph.getViewOffsetY()).append('|')
                .append(graph.getZoom());
        for (MaterialNodeGraph.Node node : graph.getNodes()) {
            builder.append("|node:")
                    .append(node.getId()).append(':')
                    .append(node.getType()).append(':')
                    .append(node.getX()).append(':')
                    .append(node.getY()).append(':')
                    .append(numberMapSignature(node.numberEntries())).append(':')
                    .append(colorMapSignature(node.colorEntries())).append(':')
                    .append(stringMapSignature(node.enumEntries())).append(':')
                    .append(stringMapSignature(node.textEntries()));
        }
        for (MaterialNodeGraph.Link link : graph.getLinks()) {
            builder.append("|link:")
                    .append(link.getFromNodeId()).append(':')
                    .append(link.getFromSocket()).append(':')
                    .append(link.getToNodeId()).append(':')
                    .append(link.getToSocket());
        }
        return builder.toString();
    }

    private static String colorMapSignature(Map<String, Vec3> colors) {
        if (colors == null || colors.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Vec3> entry : new TreeMap<>(colors).entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(vecSignature(entry.getValue()));
        }
        builder.append('}');
        return builder.toString();
    }

    private static String numberMapSignature(Map<String, Double> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Double> entry : new TreeMap<>(numbers).entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        builder.append('}');
        return builder.toString();
    }

    private static String stringMapSignature(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return "{}";
        }
        StringBuilder builder = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : new TreeMap<>(values).entrySet()) {
            if (!first) {
                builder.append(',');
            }
            first = false;
            builder.append(entry.getKey()).append('=').append(entry.getValue());
        }
        builder.append('}');
        return builder.toString();
    }

    private static String textureMapSignature(TextureMap map) {
        if (map == null) {
            return "<null>";
        }
        Object texture = map.getTexture();
        return String.valueOf(System.identityHashCode(texture))
                + '|'
                + map.isLinear()
                + '|'
                + map.getTexCoord()
                + '|'
                + map.getOffsetU()
                + '|'
                + map.getOffsetV()
                + '|'
                + map.getScaleU()
                + '|'
                + map.getScaleV()
                + '|'
                + map.getRotation()
                + '|'
                + map.isFlipV();
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

    private static boolean vecEquals(Vec3 a, Vec3 b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        return nearEqual(a.x, b.x) && nearEqual(a.y, b.y) && nearEqual(a.z, b.z);
    }

    private static boolean nearEqual(double a, double b) {
        return Math.abs(a - b) <= Engine.HISTORY_EPS;
    }

    private static boolean stringEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
    }

    private static final class PendingSceneGesture {
        final String label;
        final SceneSnapshot before;

        private PendingSceneGesture(String label, SceneSnapshot before) {
            this.label = safeLabel(label, "Transformace");
            this.before = before;
        }
    }

    private static final class SceneEntityState {
        Entity entity;
        String name;
        Vec3 position;
        Vec3 euler;
        Vec3 scale;
        boolean visible;
        boolean castShadow;
        boolean staticEntity;
        RigidBody rigidBody;
        boolean rigidBodyInPhysics;
        Vec3 rigidBodyVelocity;
        boolean visibleInView;
        boolean visibleInOutput;
    }

    private static final class SceneLightState {
        Light light;
        String name;
        Vec3 color;
        double intensity;
        boolean enabled;
        boolean visibleInView;
        boolean visibleInOutput;
        Vec3 direction;
        Vec3 position;
        Vec3 emissionDirection;
        double spreadAngleDegrees;
        double coneAngleDegrees;
        double softness;
        double attenuationConstant;
        double attenuationLinear;
        double attenuationQuadratic;
    }

    private static final class SceneForceFieldState {
        Engine.ForceField field;
        String name;
        Engine.ForceFieldType type;
        Vec3 position;
        Vec3 direction;
        double strength;
        boolean attract;
        double radius;
        double turbulenceScale;
        double seed;
        boolean visibleInView;
        boolean visibleInOutput;
    }

    private static final class TimelineSnapshot {
        SceneTimeline timeline;
        boolean timelineEnabled;
        int timelineStartFrame;
        int timelineEndFrame;
        int timelineCurrentFrame;
        double timelineFps;
        double timelineFrameCursor;
    }

    private static final class SceneSnapshot {
        List<SceneEntityState> entities;
        List<SceneLightState> lights;
        List<SceneForceFieldState> forceFields;
        Vec3 worldLightColor;
        Vec3 worldBackgroundColor;
        double worldLightStrength;
        boolean worldLightAnimationEnabled;
        int spawnCounter;
        int lightCounter;
        int forceFieldCounter;
        Entity rootEntity;
        Entity demoEntity;
        Entity floorEntity;
        Entity outputCameraEntity;
        DirectionalLight sunLight;
        DirectionalLight fillLight;
        PointLight warmWorldLight;
        PointLight coolWorldLight;
        Entity selectedEntity;
        Light selectedLight;
        Engine.ForceField selectedForceField;
        TimelineSnapshot timeline;
    }
}
