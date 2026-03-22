package engine.camera;

import engine.math.Mat4;
import engine.math.Vec3;

/**
 * Represents ortografickou kameru s nastavitelným projekčním objemem.
 */
public class OrthographicCamera extends Camera {

    private double left;
    private double rightExtent;
    private double bottom;
    private double top;

    public OrthographicCamera(double left, double right, double bottom, double top, double near, double far) {
        super(Vec3.ZERO, near, far);
        this.left = left;
        this.rightExtent = right;
        this.bottom = bottom;
        this.top = top;
        this.projDirty = true;
    }

 // Handles projekční matici.
    @Override
    public Mat4 getProjectionMatrix() {
        if (projDirty) {
            rebuildProjectionMatrix();
        }
        return projectionMatrix;
    }

    @Override
    protected void rebuildProjectionMatrix() {
        projectionMatrix = Mat4.orthographic(left, rightExtent, bottom, top, near, far);
        projDirty = false;
    }

 // Represents přístupové metody.
    public void setExtents(double left, double right, double bottom, double top) {
        this.left = left;
        this.rightExtent = right;
        this.bottom = bottom;
        this.top = top;
        this.projDirty = true;
    }
}