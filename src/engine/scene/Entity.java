package engine.scene;

import engine.geometry.Mesh;
import engine.material.Material;
import engine.math.AABB;
import engine.math.Mat4;
import engine.physics.RigidBody;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Tady držím jednu entitu ve scénovém grafu.
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

    private boolean visible;
    private boolean castShadow;
    private boolean isStatic;

    public Entity(String name) {
        this(name, null, new Material());
    }

    public Entity(String name, Mesh mesh, Material material) {
        this.name = name == null ? "Entity" : name;
        this.transform = new Transform();
        this.worldMatrix = Mat4.identity();
        this.mesh = mesh;
        this.material = material == null ? new Material() : material;
        this.children = new ArrayList<>();
        this.visible = true;
        this.castShadow = true;
        this.isStatic = false;
    }

    // Tady řeším hierarchii entity.
    public void addChild(Entity child) {
        if (child == null || child == this) {
            return;
        }
        if (child.parent != null) {
            child.parent.removeChild(child);
        }
        child.parent = this;
        children.add(child);
    }

    public void removeChild(Entity child) {
        if (child == null) {
            return;
        }
        if (children.remove(child)) {
            child.parent = null;
        }
    }

    public List<Entity> getChildren() {
        return Collections.unmodifiableList(children);
    }

    public Entity getParent() {
        return parent;
    }

    // Tady řeším transformaci entity.
    public Transform getTransform() {
        return transform;
    }

    public Mat4 getWorldMatrix() {
        Mat4 local = transform.getLocalMatrix();
        worldMatrix = (parent == null) ? local : parent.getWorldMatrix().multiply(local);
        return worldMatrix;
    }

    public void computeWorldBounds() {
        if (mesh == null || mesh.getAABB() == null) {
            worldBounds = null;
            return;
        }
        worldBounds = mesh.getAABB().transform(getWorldMatrix());
    }

    // Tady držím přístupové metody.
    public Mesh getMesh() {
        return mesh;
    }

    public void setMesh(Mesh m) {
        this.mesh = m;
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
        this.visible = visible;
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
        return worldBounds;
    }
}
