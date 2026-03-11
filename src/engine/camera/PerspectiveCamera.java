package engine.camera;

import engine.math.Mat4;
import engine.math.MathUtil;
import engine.math.Vec3;

/**
 * Tady držím perspektivní kameru s nastavitelným FOV a aspect ratio.
 */
public class PerspectiveCamera extends Camera {

    private double fovY;
    private double aspectRatio;

    public PerspectiveCamera(double fovYDeg, double aspect, double near, double far) {
        super(Vec3.ZERO, near, far);
        this.fovY = MathUtil.toRadians(fovYDeg);
        this.aspectRatio = aspect;
        this.projDirty = true;
    }

    // Tady řeším projekční matici.
    @Override
    public Mat4 getProjectionMatrix() {
        if (projDirty) {
            rebuildProjectionMatrix();
        }
        return projectionMatrix;
    }

    @Override
    protected void rebuildProjectionMatrix() {
        projectionMatrix = Mat4.perspective(fovY, aspectRatio, near, far);
        projDirty = false;
    }

    // Tady držím přístupové metody.
    public double getFovY() {
        return fovY;
    }

    public void setFovY(double fovYDeg) {
        this.fovY = MathUtil.toRadians(fovYDeg);
        this.projDirty = true;
    }

    public double getAspectRatio() {
        return aspectRatio;
    }

    public void setAspectRatio(double aspect) {
        this.aspectRatio = aspect;
        this.projDirty = true;
    }
}
