package engine.camera;

import engine.math.Mat4;
import engine.math.Vec3;

/**
 * Represents ortografickou kameru s nastavitelným projekčním objemem.
 */
public class OrthographicCamera extends Camera {
    private static final double MIN_HALF_HEIGHT = 0.05;

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

    public double getLeft() {
        return left;
    }

    public double getRightExtent() {
        return rightExtent;
    }

    public double getBottom() {
        return bottom;
    }

    public double getTop() {
        return top;
    }

    public double getHalfHeight() {
        return Math.max(MIN_HALF_HEIGHT, Math.max(Math.abs(bottom), Math.abs(top)));
    }

    public double getAspectRatio() {
        double halfHeight = getHalfHeight();
        if (halfHeight <= 1e-9) {
            return 1.0;
        }
        return Math.max(1e-4, (rightExtent - left) / (top - bottom));
    }

    public void setHalfHeight(double halfHeight) {
        setHalfHeightWithAspect(halfHeight, getAspectRatio());
    }

    public void setHalfHeightWithAspect(double halfHeight, double aspectRatio) {
        double safeHalfHeight = Math.max(MIN_HALF_HEIGHT, Math.abs(halfHeight));
        double safeAspect = Math.max(1e-4, Math.abs(aspectRatio));
        double halfWidth = safeHalfHeight * safeAspect;
        setExtents(-halfWidth, halfWidth, -safeHalfHeight, safeHalfHeight);
    }

    public void scaleZoom(double factor) {
        if (!Double.isFinite(factor) || factor <= 0.0) {
            return;
        }
        setHalfHeight(getHalfHeight() * factor);
    }
}
