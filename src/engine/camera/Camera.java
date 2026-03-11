package engine.camera;

import engine.math.Mat4;
import engine.math.Vec3;

/**
 * Tady definuju abstraktní kameru s view a projekční maticí.
 */
public abstract class Camera {

    protected Vec3 position;
    protected Vec3 forward;
    protected Vec3 up;
    protected Vec3 right;
    protected double near;
    protected double far;
    protected Mat4 viewMatrix;
    protected Mat4 projectionMatrix;
    protected boolean viewDirty;
    protected boolean projDirty;

    protected Camera(Vec3 position, double near, double far) {
        this.position = position;
        this.forward = new Vec3(0.0, 0.0, -1.0);
        this.up = Vec3.UP;
        this.right = forward.cross(up).normalize();
        this.near = near;
        this.far = far;
        this.viewMatrix = Mat4.identity();
        this.projectionMatrix = Mat4.identity();
        this.viewDirty = true;
        this.projDirty = true;
    }

    // Tady řeším view matici.
    public Mat4 getViewMatrix() {
        if (viewDirty) {
            rebuildViewMatrix();
        }
        return viewMatrix;
    }

    protected void rebuildViewMatrix() {
        viewMatrix = Mat4.lookAt(position, position.add(forward), up);
        viewDirty = false;
    }

    // Tady řeším projekční matici.
    public abstract Mat4 getProjectionMatrix();
    protected abstract void rebuildProjectionMatrix();

    // Tady řeším orientaci kamery.
    public void lookAt(Vec3 target) {
        Vec3 dir = target.sub(position).normalize();
        if (dir.lengthSquared() < 1e-10) {
            return;
        }
        forward = dir;
        right = forward.cross(Vec3.UP).normalize();
        if (right.lengthSquared() < 1e-10) {
            right = new Vec3(1.0, 0.0, 0.0);
        }
        up = right.cross(forward).normalize();
        viewDirty = true;
    }

    public Vec3 getPosition() {
        return position;
    }

    public void setPosition(Vec3 p) {
        position = p;
        viewDirty = true;
    }

    public Vec3 getForward() {
        return forward;
    }

    public Vec3 getUp() {
        return up;
    }

    public Vec3 getRight() {
        return right;
    }

    // Tady řeším clipping rozsah.
    public double getNear() {
        return near;
    }

    public double getFar() {
        return far;
    }

    public void setClipping(double near, double far) {
        this.near = near;
        this.far = far;
        this.projDirty = true;
    }
}
