package engine.geometry;

import engine.math.AABB;
import engine.math.BoundingSphere;

/**
 * Computes bounding volumes z mesh dat.
 */
public final class BoundingVolumeBuilder {

    private BoundingVolumeBuilder() {}

    public static AABB computeAABB(float[] positions, int vertexCount) {
        if (positions == null || positions.length < 3 || vertexCount <= 0) {
            return new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO);
        }
        int count = Math.min(vertexCount, positions.length / 3);
        if (count <= 0) {
            return new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO);
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        for (int i = 0; i < count; i++) {
            int base = i * 3;
            double x = positions[base];
            double y = positions[base + 1];
            double z = positions[base + 2];
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        return new AABB(
                new engine.math.Vec3(minX, minY, minZ),
                new engine.math.Vec3(maxX, maxY, maxZ)
        );
    }

    public static BoundingSphere computeBoundingSphere(float[] positions, int vertexCount) {
        if (positions == null || positions.length < 3 || vertexCount <= 0) {
            return new BoundingSphere(engine.math.Vec3.ZERO, 0.0);
        }
        int count = Math.min(vertexCount, positions.length / 3);
        if (count <= 0) {
            return new BoundingSphere(engine.math.Vec3.ZERO, 0.0);
        }

        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (int i = 0; i < count; i++) {
            int base = i * 3;
            cx += positions[base];
            cy += positions[base + 1];
            cz += positions[base + 2];
        }
        cx /= count;
        cy /= count;
        cz /= count;

        double maxDistSq = 0.0;
        for (int i = 0; i < count; i++) {
            int base = i * 3;
            double dx = positions[base] - cx;
            double dy = positions[base + 1] - cy;
            double dz = positions[base + 2] - cz;
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 > maxDistSq) {
                maxDistSq = d2;
            }
        }

        return new BoundingSphere(
                new engine.math.Vec3(cx, cy, cz),
                Math.sqrt(maxDistSq)
        );
    }

    public static AABB computeTriangleAABB(float[] positions, int[] indices, int triIndex) {
        if (positions == null || indices == null || triIndex < 0) {
            return new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO);
        }
        int base = triIndex * 3;
        if (base + 2 >= indices.length) {
            return new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO);
        }

        int i0 = indices[base];
        int i1 = indices[base + 1];
        int i2 = indices[base + 2];
        int maxVertex = positions.length / 3;
        if (i0 < 0 || i1 < 0 || i2 < 0 || i0 >= maxVertex || i1 >= maxVertex || i2 >= maxVertex) {
            return new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO);
        }

        engine.math.Vec3 p0 = read(positions, i0);
        engine.math.Vec3 p1 = read(positions, i1);
        engine.math.Vec3 p2 = read(positions, i2);
        engine.math.Vec3 min = engine.math.Vec3.min(engine.math.Vec3.min(p0, p1), p2);
        engine.math.Vec3 max = engine.math.Vec3.max(engine.math.Vec3.max(p0, p1), p2);
        return new AABB(min, max);
    }

    private static engine.math.Vec3 read(float[] positions, int index) {
        int base = index * 3;
        return new engine.math.Vec3(
                positions[base],
                positions[base + 1],
                positions[base + 2]
        );
    }
}
