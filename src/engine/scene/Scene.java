package engine.scene;

import engine.math.Vec3;
import engine.render.EnvironmentMap;
import engine.util.RuntimeInstrumentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tady držím scénu se všemi objekty, světly a stavem renderovatelného světa.
 */
public class Scene {

    private final List<Entity> entities = new ArrayList<>();
    private final List<Light> lights = new ArrayList<>();
    private final List<Entity> visibleMeshEntitiesCache = new ArrayList<>();
    private final List<Entity> visibleMeshEntitiesView = Collections.unmodifiableList(visibleMeshEntitiesCache);
    private Entity rootEntity;
    private Vec3 ambientColor = new Vec3(0.1, 0.1, 0.1);
    private Vec3 backgroundColor = new Vec3(0.06, 0.07, 0.09);
    private double environmentStrength = 1.0;
    private double environmentExposure = 1.0;
    private double environmentYawDegrees = 0.0;
    private double environmentPitchDegrees = 0.0;
    private String environmentMapKey = "";
    private EnvironmentMap environmentMap;
    private boolean dirty = true;
    private boolean meshEntityCacheDirty = true;
    private long structureVersion = 1L;
    private long spatialVersion = 1L;
    private long meshEntityVersion = 1L;

    // Tady spravuju entity.
    public void addEntity(Entity e) {
        if (e == null) {
            return;
        }
        e.setOwnerScene(this);
        entities.add(e);
        dirty = true;
        meshEntityCacheDirty = true;
        structureVersion++;
    }

    public void removeEntity(Entity e) {
        if (entities.remove(e)) {
            dirty = true;
            meshEntityCacheDirty = true;
            structureVersion++;
            if (e != null) {
                e.setOwnerScene(null);
            }
        }
    }

    public List<Entity> getEntities() {
        return Collections.unmodifiableList(entities);
    }

    // Tady spravuju světla.
    public void addLight(Light l) {
        if (l == null) {
            return;
        }
        lights.add(l);
        dirty = true;
    }

    public void removeLight(Light l) {
        if (lights.remove(l)) {
            dirty = true;
        }
    }

    public List<Light> getLights() {
        return Collections.unmodifiableList(lights);
    }

    // Tady řeším dotazy nad scénou.
    public Entity findByName(String name) {
        for (Entity entity : entities) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (entity.getName().equals(name)) {
                return entity;
            }
        }
        return null;
    }

    public List<Entity> getAllMeshEntities() {
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.GET_ALL_MESH_ENTITIES_CALLS, 1L);
        if (meshEntityCacheDirty) {
            visibleMeshEntitiesCache.clear();
            for (Entity entity : entities) {
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
                if (entity.getMesh() != null && entity.isVisible()) {
                    visibleMeshEntitiesCache.add(entity);
                }
            }
            meshEntityCacheDirty = false;
        }
        return visibleMeshEntitiesView;
    }

    // Tady držím základní stav scény.
    public Vec3 getAmbientColor() {
        return ambientColor;
    }

    public void setAmbientColor(Vec3 color) {
        this.ambientColor = color;
        dirty = true;
    }

    public Vec3 getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Vec3 color) {
        this.backgroundColor = color;
        dirty = true;
    }

    public double getEnvironmentStrength() {
        return environmentStrength;
    }

    public void setEnvironmentStrength(double environmentStrength) {
        this.environmentStrength = environmentStrength;
        dirty = true;
    }

    public double getEnvironmentExposure() {
        return environmentExposure;
    }

    public void setEnvironmentExposure(double environmentExposure) {
        this.environmentExposure = environmentExposure;
        dirty = true;
    }

    public double getEnvironmentYawDegrees() {
        return environmentYawDegrees;
    }

    public void setEnvironmentYawDegrees(double environmentYawDegrees) {
        this.environmentYawDegrees = environmentYawDegrees;
        dirty = true;
    }

    public double getEnvironmentPitchDegrees() {
        return environmentPitchDegrees;
    }

    public void setEnvironmentPitchDegrees(double environmentPitchDegrees) {
        this.environmentPitchDegrees = environmentPitchDegrees;
        dirty = true;
    }

    public String getEnvironmentMapKey() {
        return environmentMapKey;
    }

    public void setEnvironmentMapKey(String environmentMapKey) {
        this.environmentMapKey = environmentMapKey == null ? "" : environmentMapKey;
        dirty = true;
    }

    public EnvironmentMap getEnvironmentMap() {
        return environmentMap;
    }

    public void setEnvironmentMap(EnvironmentMap environmentMap) {
        this.environmentMap = environmentMap;
        dirty = true;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    public boolean hasSpatialChanges() {
        for (Entity entity : entities) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (entity != null && entity.isSpatialDirty()) {
                return true;
            }
        }
        return false;
    }

    public long getStructureVersion() {
        return structureVersion;
    }

    public long getSpatialVersion() {
        return spatialVersion;
    }

    public long getMeshEntityVersion() {
        return meshEntityVersion;
    }

    // Tady aktualizuju odvozený stav scény.
    public void update(double dt) {
        for (Entity entity : entities) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.ENTITIES_VISITED, 1L);
            if (entity == null || !entity.isSpatialDirty()) {
                continue;
            }
            entity.getWorldMatrix();
            entity.computeWorldBounds();
        }
        dirty = false;
    }

    public Entity getRootEntity() {
        return rootEntity;
    }

    public void setRootEntity(Entity rootEntity) {
        this.rootEntity = rootEntity;
    }

    public void markMeshEntityCacheDirty() {
        meshEntityCacheDirty = true;
        dirty = true;
        meshEntityVersion++;
    }

    public void markSpatialDirty() {
        dirty = true;
        meshEntityCacheDirty = true;
        spatialVersion++;
        meshEntityVersion++;
    }
}
