package engine.scene;

import engine.geometry.Mesh;
import engine.material.Material;
import engine.math.AABB;
import engine.math.Mat4;
import engine.physics.RigidBody;
import engine.util.RuntimeInstrumentation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents jednu entitu ve scénovém grafu.
 */
public class Entity {

    private String name;
    private final Transform transform;
    private Mat4 worldMatrix;
    private Mesh mesh;
    private Material material;
    private AABB worldBounds;
    private RigidBody rigidBody;
    private Entity parent;
    private final List<Entity> children;
    private Scene ownerScene;

    private boolean visible;
    private boolean castShadow;
    private boolean isStatic;
    private boolean worldMatrixDirty;
    private boolean worldBoundsDirty;
    private long worldTransformVersion;
    private long cachedTransformRevision;
    private long cachedParentWorldVersion;

    public Entity(String name) {
        this(name, null, new Material());
    }

    public Entity(String name, Mesh mesh, Material material) {
        this.name = name == null ? "Entity" : name;
        this.transform = new Transform();
        this.transform.setDirtyListener(this::markSpatialDirty);
        this.worldMatrix = Mat4.identity();
        this.mesh = mesh;
        this.material = material == null ? new Material() : material;
        this.children = new ArrayList<>();
        this.visible = true;
        this.castShadow = true;
        this.isStatic = false;
        this.worldMatrixDirty = true;
        this.worldBoundsDirty = true;
        this.worldTransformVersion = 1L;
        this.cachedTransformRevision = Long.MIN_VALUE;
        this.cachedParentWorldVersion = Long.MIN_VALUE;
    }

 // Handles hierarchii entity.
    public void addChild(Entity child) {
        if (child == null || child == this) {
            return;
        }
        if (child.parent != null) {
            child.parent.removeChild(child);
        }
        child.parent = this;
        children.add(child);
        child.markSpatialDirty();
    }

    public void removeChild(Entity child) {
        if (child == null) {
            return;
        }
        if (children.remove(child)) {
            child.parent = null;
            child.markSpatialDirty();
        }
    }

    public List<Entity> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public Entity getParent() {
        return parent;
    }

 // Handles transformaci entity.
    public Transform getTransform() {
        return transform;
    }

    public Mat4 getWorldMatrix() {
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WORLD_MATRIX_CALLS, 1L);
        long parentVersion = parent == null ? Long.MIN_VALUE : parent.getWorldTransformVersion();
        long transformRevision = transform.getRevision();
        if (worldMatrixDirty
                || cachedTransformRevision != transformRevision
                || cachedParentWorldVersion != parentVersion) {
            Mat4 local = transform.getLocalMatrix();
            worldMatrix = (parent == null) ? local : parent.getWorldMatrix().multiply(local);
            cachedTransformRevision = transformRevision;
            cachedParentWorldVersion = parentVersion;
            worldMatrixDirty = false;
            worldBoundsDirty = true;
            worldTransformVersion++;
        }
        return worldMatrix;
    }

    public void computeWorldBounds() {
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.BOUNDS_RECOMPUTES, 1L);
        if (mesh == null || mesh.getAABB() == null) {
            worldBounds = null;
            worldBoundsDirty = false;
            return;
        }
        if (worldBoundsDirty || worldBounds == null) {
            worldBounds = mesh.getAABB().transform(getWorldMatrix());
            worldBoundsDirty = false;
        }
    }

 // Represents přístupové metody.
    public Mesh getMesh() {
        return mesh;
    }

    public void setMesh(Mesh m) {
        this.mesh = m;
        markSpatialDirty();
        if (ownerScene != null) {
            ownerScene.markMeshEntityCacheDirty();
        }
    }

    public Material getMaterial() {
        return material;
    }

    public void setMaterial(Material m) {
        this.material = m;
    }

    public RigidBody getRigidBody() {
        return rigidBody;
    }

    public void setRigidBody(RigidBody rb) {
        this.rigidBody = rb;
    }

    public String getName() {
        return name;
    }

    public void setName(String newName) {
        this.name = newName;
    }

    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        if (this.visible == visible) {
            return;
        }
        this.visible = visible;
        if (ownerScene != null) {
            ownerScene.markMeshEntityCacheDirty();
        }
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    public void setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
    }

    public boolean isStatic() {
        return isStatic;
    }

    public void setStatic(boolean aStatic) {
        isStatic = aStatic;
    }

    public AABB getWorldBounds() {
        if (worldBoundsDirty) {
            computeWorldBounds();
        }
        return worldBounds;
    }

    public boolean isSpatialDirty() {
        if (worldMatrixDirty || worldBoundsDirty || transform.isDirty()) {
            return true;
        }
        if (parent != null && cachedParentWorldVersion != parent.getWorldTransformVersion()) {
            return true;
        }
        return cachedTransformRevision != transform.getRevision();
    }

    public long getWorldTransformVersion() {
        return worldTransformVersion;
    }

    void setOwnerScene(Scene ownerScene) {
        this.ownerScene = ownerScene;
    }

    void markSpatialDirty() {
        worldMatrixDirty = true;
        worldBoundsDirty = true;
        if (ownerScene != null) {
            ownerScene.markSpatialDirty();
        }
        for (Entity child : children) {
            if (child != null) {
                child.markSpatialDirty();
            }
        }
    }
}