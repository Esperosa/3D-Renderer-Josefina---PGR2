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

    // Tady měním transformaci.
    public void setPosition(Vec3 p) {
        position = p;
        dirty = true;
    }

    public void setRotation(Quaternion q) {
        rotation = q == null ? new Quaternion() : q.normalize();
        dirty = true;
    }

    public void setScale(Vec3 s) {
        scale = s;
        dirty = true;
    }

    public void translate(Vec3 delta) {
        position = position.add(delta);
        dirty = true;
    }

    public void rotate(Vec3 axis, double radians) {
        rotation = Quaternion.fromAxisAngle(axis, radians).multiply(rotation).normalize();
        dirty = true;
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
        dirty = true;
    }

    public Vec3 getEulerAngles() {
        return rotation.toEuler();
    }
}
