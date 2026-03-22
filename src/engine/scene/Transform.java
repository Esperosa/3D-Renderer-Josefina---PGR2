package engine.scene;

import engine.math.Mat4;
import engine.math.Quaternion;
import engine.math.Vec3;

/**
 * Tady držím pozici, rotaci a scale entity i její lokální matici.
 */
public class Transform {

    private Vec3 position = Vec3.ZERO;
    private Quaternion rotation = new Quaternion();
    private Vec3 scale = Vec3.ONE;
    private Mat4 localMatrix = Mat4.identity();
    private boolean dirty = true;
    private long revision = 1L;
    private Runnable dirtyListener;

    public void setDirtyListener(Runnable dirtyListener) {
        this.dirtyListener = dirtyListener;
    }

    // Tady měním transformaci.
    public void setPosition(Vec3 p) {
        position = p;
        markDirty();
    }

    public void setRotation(Quaternion q) {
        rotation = q == null ? new Quaternion() : q.normalize();
        markDirty();
    }

    public void setScale(Vec3 s) {
        scale = s;
        markDirty();
    }

    public void translate(Vec3 delta) {
        position = position.add(delta);
        markDirty();
    }

    public void rotate(Vec3 axis, double radians) {
        rotation = Quaternion.fromAxisAngle(axis, radians).multiply(rotation).normalize();
        markDirty();
    }

    // Tady držím přístupové metody.
    public Vec3 getPosition() {
        return position;
    }

    public Quaternion getRotation() {
        return rotation;
    }

    public Vec3 getScale() {
        return scale;
    }

    public boolean isDirty() {
        return dirty;
    }

    public long getRevision() {
        return revision;
    }

    // Tady skládám lokální matici.
    public Mat4 getLocalMatrix() {
        if (dirty) {
            rebuildMatrix();
        }
        return localMatrix;
    }

    public void rebuildMatrix() {
        Mat4 t = Mat4.translation(position.x, position.y, position.z);
        Mat4 r = rotation.toMat4();
        Mat4 s = Mat4.scale(scale.x, scale.y, scale.z);
        localMatrix = t.multiply(r).multiply(s);
        dirty = false;
    }

    // Tady držím pohodlné Euler převody.
    public void setEulerAngles(double pitch, double yaw, double roll) {
        rotation = Quaternion.fromEuler(pitch, yaw, roll);
        markDirty();
    }

    public Vec3 getEulerAngles() {
        return rotation.toEuler();
    }

    private void markDirty() {
        dirty = true;
        revision++;
        if (dirtyListener != null) {
            dirtyListener.run();
        }
    }
}
