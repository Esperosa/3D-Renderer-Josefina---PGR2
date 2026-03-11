package engine.scene;

import engine.math.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tady držím scénu se všemi objekty, světly a stavem renderovatelného světa.
 */
public class Scene {

    private final List<Entity> entities = new ArrayList<>();
    private final List<Light> lights = new ArrayList<>();
    private Entity rootEntity;
    private Vec3 ambientColor = new Vec3(0.1, 0.1, 0.1);
    private Vec3 backgroundColor = new Vec3(0.06, 0.07, 0.09);
    private boolean dirty = true;

    // Tady spravuju entity.
    public void addEntity(Entity e) {
        if (e == null) {
            return;
        }
        entities.add(e);
        dirty = true;
    }

    public void removeEntity(Entity e) {
        if (entities.remove(e)) {
            dirty = true;
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
            if (entity.getName().equals(name)) {
                return entity;
            }
        }
        return null;
    }

    public List<Entity> getAllMeshEntities() {
        List<Entity> out = new ArrayList<>();
        for (Entity entity : entities) {
            if (entity.getMesh() != null && entity.isVisible()) {
                out.add(entity);
            }
        }
        return out;
    }

    // Tady držím základní stav scény.
    public Vec3 getAmbientColor() {
        return ambientColor;
    }

    public void setAmbientColor(Vec3 color) {
        this.ambientColor = color;
    }

    public Vec3 getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Vec3 color) {
        this.backgroundColor = color;
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    // Tady aktualizuju odvozený stav scény.
    public void update(double dt) {
        for (Entity entity : entities) {
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
}
