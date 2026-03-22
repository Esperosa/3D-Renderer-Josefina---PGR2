package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.Camera;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.util.RuntimeInstrumentation;

final class PreviewCameraResetSupport {

    private static final double SOFT_TRANSLATION_MAX = 0.14;
    private static final double SOFT_ROTATION_DOT_MIN = Math.cos(Math.toRadians(5.0));

    enum ResetKind {
        HARD,
        SOFT
    }

    static final class Snapshot {
        final int width;
        final int height;
        final int cameraClassId;
        final long projectionSignature;
        final long fullSignature;
        final double px;
        final double py;
        final double pz;
        final double fx;
        final double fy;
        final double fz;
        final double ux;
        final double uy;
        final double uz;

        Snapshot(int width,
                 int height,
                 int cameraClassId,
                 long projectionSignature,
                 long fullSignature,
                 Vec3 position,
                 Vec3 forward,
                 Vec3 up) {
            this.width = width;
            this.height = height;
            this.cameraClassId = cameraClassId;
            this.projectionSignature = projectionSignature;
            this.fullSignature = fullSignature;
            this.px = position.x;
            this.py = position.y;
            this.pz = position.z;
            this.fx = forward.x;
            this.fy = forward.y;
            this.fz = forward.z;
            this.ux = up.x;
            this.uy = up.y;
            this.uz = up.z;
        }
    }

    static final class MotionDelta {
        static final MotionDelta NONE = new MotionDelta(0.0, 0.0);

        final double translationDistance;
        final double rotationDegrees;

        MotionDelta(double translationDistance, double rotationDegrees) {
            this.translationDistance = Math.max(0.0, translationDistance);
            this.rotationDegrees = Math.max(0.0, rotationDegrees);
        }
    }

    private PreviewCameraResetSupport() {
    }

    static Snapshot capture(Camera camera, int width, int height) {
        long extractStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int cameraClassId = System.identityHashCode(camera.getClass());
        Vec3 position = camera.getPosition();
        Vec3 forward = camera.getForward();
        Vec3 up = camera.getUp();
        Vec3 right = camera.getRight();
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_EXTRACT_NS,
                    System.nanoTime() - extractStart);
        }

        long projectionStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        long projectionSignature = computeProjectionSignature(camera, width, height, cameraClassId);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_PROJECTION_NS,
                    System.nanoTime() - projectionStart);
        }
        long hashStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        long fullSignature = projectionSignature;
        fullSignature = mixHash(fullSignature, quantizedBits(position.x, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(position.y, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(position.z, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(forward.x, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(forward.y, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(forward.z, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(up.x, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(up.y, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(up.z, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(right.x, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(right.y, 1e-4));
        fullSignature = mixHash(fullSignature, quantizedBits(right.z, 1e-4));
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_HASH_NS,
                    System.nanoTime() - hashStart);
        }
        return new Snapshot(width, height, cameraClassId, projectionSignature, fullSignature, position, forward, up);
    }

    static ResetKind classify(Snapshot previous, Snapshot current, boolean allowSoftReset) {
        if (previous == null || current == null) {
            return ResetKind.HARD;
        }
        if (previous.width != current.width
                || previous.height != current.height
                || previous.cameraClassId != current.cameraClassId
                || previous.projectionSignature != current.projectionSignature) {
            return ResetKind.HARD;
        }
        if (!allowSoftReset) {
            return ResetKind.HARD;
        }

        MotionDelta delta = measure(previous, current);
        if (delta.translationDistance > SOFT_TRANSLATION_MAX) {
            return ResetKind.HARD;
        }

        if (delta.rotationDegrees > Math.toDegrees(Math.acos(SOFT_ROTATION_DOT_MIN))) {
            return ResetKind.HARD;
        }
        return ResetKind.SOFT;
    }

    static MotionDelta measure(Snapshot previous, Snapshot current) {
        if (previous == null || current == null) {
            return MotionDelta.NONE;
        }
        double dx = current.px - previous.px;
        double dy = current.py - previous.py;
        double dz = current.pz - previous.pz;
        double translationDistance = Math.sqrt(dx * dx + dy * dy + dz * dz);

        double forwardDot = clampUnit(previous.fx * current.fx + previous.fy * current.fy + previous.fz * current.fz);
        double upDot = clampUnit(previous.ux * current.ux + previous.uy * current.uy + previous.uz * current.uz);
        double forwardDegrees = Math.toDegrees(Math.acos(forwardDot));
        double upDegrees = Math.toDegrees(Math.acos(upDot));
        return new MotionDelta(translationDistance, Math.max(forwardDegrees, upDegrees));
    }

    private static long computeProjectionSignature(Camera camera, int width, int height, int cameraClassId) {
        long h = 0xcbf29ce484222325L;
        h = mixHash(h, width);
        h = mixHash(h, height);
        h = mixHash(h, cameraClassId);
        h = mixHash(h, quantizedBits(camera.getNear(), 1e-6));
        h = mixHash(h, quantizedBits(camera.getFar(), 1e-4));
        Mat4 projection = camera.getProjectionMatrix();
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                h = mixHash(h, quantizedBits(projection.get(row, col), 1e-4));
            }
        }
        return h;
    }

    private static double clampUnit(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static long quantizedBits(double value, double quantum) {
        double q = quantum <= 0.0 ? value : Math.rint(value / quantum) * quantum;
        return Double.doubleToLongBits(q);
    }

    private static long mixHash(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }
}

