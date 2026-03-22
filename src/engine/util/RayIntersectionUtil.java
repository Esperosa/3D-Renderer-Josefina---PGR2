package engine.util;

import engine.geometry.Mesh;
import engine.math.Mat4;
import engine.math.Vec3;

public final class RayIntersectionUtil {

    private RayIntersectionUtil() {
    }

    public static double intersectRayMesh(Vec3 origin, Vec3 direction, Mesh mesh, Mat4 model, double maxT) {
        int[] indices = mesh.getIndices();
        float[] positions = mesh.getPositions();
        if (indices == null || positions == null || indices.length < 3 || positions.length < 9) {
            return Double.POSITIVE_INFINITY;
        }

        double bestT = maxT;
        for (int i = 0; i < indices.length; i += 3) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PICK_TRIANGLE_TESTS, 1L);
            int i0 = indices[i] * 3;
            int i1 = indices[i + 1] * 3;
            int i2 = indices[i + 2] * 3;
            if (i0 + 2 >= positions.length || i1 + 2 >= positions.length || i2 + 2 >= positions.length) {
                continue;
            }

            Vec3 a = model.transformPoint(new Vec3(positions[i0], positions[i0 + 1], positions[i0 + 2]));
            Vec3 b = model.transformPoint(new Vec3(positions[i1], positions[i1 + 1], positions[i1 + 2]));
            Vec3 c = model.transformPoint(new Vec3(positions[i2], positions[i2 + 1], positions[i2 + 2]));

            double t = intersectRayTriangle(origin, direction, a, b, c, bestT);
            if (t < bestT) {
                bestT = t;
            }
        }
        return bestT;
    }

    public static double intersectRayTriangle(
            Vec3 origin, Vec3 direction, Vec3 a, Vec3 b, Vec3 c, double maxT) {
        final double eps = 1e-10;
        Vec3 e1 = b.sub(a);
        Vec3 e2 = c.sub(a);
        Vec3 p = direction.cross(e2);
        double det = e1.dot(p);
        if (Math.abs(det) < eps) {
            return Double.POSITIVE_INFINITY;
        }
        double invDet = 1.0 / det;
        Vec3 tvec = origin.sub(a);
        double u = tvec.dot(p) * invDet;
        if (u < 0.0 || u > 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 q = tvec.cross(e1);
        double v = direction.dot(q) * invDet;
        if (v < 0.0 || u + v > 1.0) {
            return Double.POSITIVE_INFINITY;
        }
        double t = e2.dot(q) * invDet;
        if (t <= 0.0 || t >= maxT) {
            return Double.POSITIVE_INFINITY;
        }
        return t;
    }
}
