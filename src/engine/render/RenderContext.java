package engine.render;

import engine.camera.Camera;
import engine.camera.Frustum;
import engine.math.Mat3;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.scene.Light;
import engine.scene.Scene;

import java.util.List;

/**
 * Tady držím render context s konstantami sdílenými v rámci jednoho snímku.
 */
public class RenderContext {

    private Mat4 viewMatrix;
    private Mat4 projectionMatrix;
    private Mat4 viewProjectionMatrix;
    private Mat4 inverseViewMatrix;
    private Mat3 normalMatrix;

    private Vec3 cameraPosition;
    private Vec3 cameraForward;

    private int screenWidth;
    private int screenHeight;
    private double time;
    private double deltaTime;

    private final Frustum frustum;

    private Light[] lights;

    public RenderContext() {
        this.viewMatrix = Mat4.identity();
        this.projectionMatrix = Mat4.identity();
        this.viewProjectionMatrix = Mat4.identity();
        this.inverseViewMatrix = Mat4.identity();
        this.normalMatrix = Mat3.identity();
        this.cameraPosition = Vec3.ZERO;
        this.cameraForward = new Vec3(0.0, 0.0, -1.0);
        this.screenWidth = 1;
        this.screenHeight = 1;
        this.time = 0.0;
        this.deltaTime = 0.0;
        this.frustum = new Frustum();
        this.lights = new Light[0];
    }

    public void update(Camera camera, Scene scene, FrameBuffer fb, double time, double dt) {
        if (camera == null || fb == null) {
            return;
        }

        this.viewMatrix = camera.getViewMatrix();
        this.projectionMatrix = camera.getProjectionMatrix();
        this.viewProjectionMatrix = projectionMatrix.multiply(viewMatrix);

        try {
            this.inverseViewMatrix = viewMatrix.inverse();
        } catch (RuntimeException ex) {
            this.inverseViewMatrix = Mat4.identity();
        }

        try {
            this.normalMatrix = viewMatrix.toMat3().inverse().transpose();
        } catch (RuntimeException ex) {
            this.normalMatrix = Mat3.identity();
        }

        this.cameraPosition = camera.getPosition();
        this.cameraForward = camera.getForward();
        this.screenWidth = Math.max(1, fb.getWidth());
        this.screenHeight = Math.max(1, fb.getHeight());
        this.time = time;
        this.deltaTime = dt;
        this.frustum.extractFromMatrix(viewProjectionMatrix);

        if (scene == null) {
            this.lights = new Light[0];
        } else {
            List<Light> sceneLights = scene.getLights();
            this.lights = sceneLights.toArray(new Light[0]);
        }
    }

    public Mat4 getViewMatrix() {
        return viewMatrix;
    }

    public Mat4 getProjectionMatrix() {
        return projectionMatrix;
    }

    public Mat4 getViewProjectionMatrix() {
        return viewProjectionMatrix;
    }

    public Mat4 getInverseViewMatrix() {
        return inverseViewMatrix;
    }

    public Mat3 getNormalMatrix() {
        return normalMatrix;
    }

    public Vec3 getCameraPosition() {
        return cameraPosition;
    }

    public Vec3 getCameraForward() {
        return cameraForward;
    }

    public int getScreenWidth() {
        return screenWidth;
    }

    public int getScreenHeight() {
        return screenHeight;
    }

    public double getTime() {
        return time;
    }

    public double getDeltaTime() {
        return deltaTime;
    }

    public Frustum getFrustum() {
        return frustum;
    }

    public Light[] getLights() {
        return lights;
    }
}
