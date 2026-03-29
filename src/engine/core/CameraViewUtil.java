package engine.core;

import engine.camera.Camera;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
import engine.scene.Entity;

final class CameraViewUtil {

    private CameraViewUtil() {
    }

    static Vec3 outputCameraForward(Entity outputCameraEntity) {
        if (outputCameraEntity == null) {
            return new Vec3(0.0, 0.0, -1.0);
        }
        Vec3 fwd = outputCameraEntity.getTransform().getRotation()
                .rotateVector(new Vec3(0.0, 0.0, -1.0))
                .normalize();
        if (fwd.lengthSquared() < 1e-10) {
            return new Vec3(0.0, 0.0, -1.0);
        }
        return fwd;
    }

    static boolean isViewingThroughOutputCamera(Entity outputCameraEntity, Camera camera) {
        if (outputCameraEntity == null || camera == null) {
            return false;
        }
        Vec3 outPos = outputCameraEntity.getTransform().getPosition();
        Vec3 outFwd = outputCameraForward(outputCameraEntity);
        Vec3 camPos = camera.getPosition();
        Vec3 camFwd = camera.getForward().normalize();

        double posDelta = camPos.sub(outPos).length();
        double dirAlign = camFwd.dot(outFwd);
        return posDelta < 0.18 && dirAlign > 0.965;
    }

    static void syncOutputCameraFromCurrentView(Entity outputCameraEntity, Camera camera) {
        if (outputCameraEntity == null || camera == null) {
            return;
        }
        Vec3 pos = camera.getPosition();
        Vec3 fwd = camera.getForward().normalize();
        double yaw = -Math.atan2(fwd.x, -fwd.z);
        double pitch = Math.asin(Math.max(-1.0, Math.min(1.0, fwd.y)));
        outputCameraEntity.getTransform().setPosition(pos);
        outputCameraEntity.getTransform().setEulerAngles(pitch, yaw, 0.0);
    }

    static Camera buildOutputRenderCamera(
            Entity outputCameraEntity,
            Camera camera,
            PerspectiveCamera perspectiveCamera,
            OrthographicCamera orthographicCamera,
            boolean orthographicProjection,
            int width,
            int height) {
        double aspect = Math.max(0.01, (double) width / (double) height);
        Camera renderCamera;
        if (orthographicProjection) {
            double halfHeight = orthographicCamera != null
                    ? orthographicCamera.getHalfHeight()
                    : 6.0;
            renderCamera = new OrthographicCamera(
                    -halfHeight * aspect, halfHeight * aspect, -halfHeight, halfHeight,
                    camera.getNear(), camera.getFar()
            );
        } else {
            double fovDeg = 70.0;
            if (perspectiveCamera != null) {
                fovDeg = Math.toDegrees(perspectiveCamera.getFovY());
            }
            renderCamera = new PerspectiveCamera(
                    fovDeg,
                    aspect,
                    camera.getNear(),
                    camera.getFar()
            );
        }

        Vec3 outPos = outputCameraEntity != null ? outputCameraEntity.getTransform().getPosition() : camera.getPosition();
        Vec3 outFwd = outputCameraEntity != null
                ? outputCameraForward(outputCameraEntity)
                : camera.getForward().normalize();
        renderCamera.setPosition(outPos);
        renderCamera.lookAt(outPos.add(outFwd));
        return renderCamera;
    }
}
