package engine.render.ray.core;

import engine.math.AABB;
import engine.math.Ray;
import engine.math.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds BVH from triangle data and performs ray traversal/intersection queries.
 * Node splitting uses a surface-area-based heuristic.
 */
public class BVHBuilder implements AccelerationStructure {

    private static final double EPS = 1e-9;

    private BVHNode root;
    private double[] allPositions;
    private int[] allIndices;
    private int[] triangleOrder;
    private AABB[] triangleBounds;
    private Vec3[] triangleCentroids;
    private int maxLeafSize;

    public BVHBuilder() {
        this(4);
    }

    public BVHBuilder(int maxLeafSize) {
        this.maxLeafSize = Math.max(1, maxLeafSize);
        this.root = null;
        this.allPositions = new double[0];
        this.allIndices = new int[0];
        this.triangleOrder = new int[0];
        this.triangleBounds = new AABB[0];
        this.triangleCentroids = new Vec3[0];
    }

    @Override
    public void build(float[][] positions, int[][] indices, double[][] modelMats, int meshCount) {
        List<double[]> triangles = new ArrayList<>();
        int safeMeshCount = Math.max(0, meshCount);
        int posCount = positions == null ? 0 : positions.length;
        int meshLimit = Math.min(safeMeshCount, posCount);

        for (int mesh = 0; mesh < meshLimit; mesh++) {
            float[] meshPositions = positions[mesh];
            if (meshPositions == null || meshPositions.length < 9) {
                continue;
            }
            int vertexCount = meshPositions.length / 3;
            if (vertexCount < 3) {
                continue;
            }

            int[] meshIndices = (indices != null && mesh < indices.length) ? indices[mesh] : null;
            if (meshIndices == null || meshIndices.length < 3) {
                int triVertexCount = (vertexCount / 3) * 3;
                meshIndices = new int[triVertexCount];
                for (int i = 0; i < triVertexCount; i++) {
                    meshIndices[i] = i;
                }
            }

            double[] model = (modelMats != null && mesh < modelMats.length) ? modelMats[mesh] : null;
            int triIndexCount = (meshIndices.length / 3) * 3;
            for (int i = 0; i < triIndexCount; i += 3) {
                int i0 = meshIndices[i];
                int i1 = meshIndices[i + 1];
                int i2 = meshIndices[i + 2];
                if (i0 < 0 || i1 < 0 || i2 < 0
                        || i0 >= vertexCount || i1 >= vertexCount || i2 >= vertexCount) {
                    continue;
                }

                Vec3 p0 = transformPoint(model,
                        meshPositions[i0 * 3], meshPositions[i0 * 3 + 1], meshPositions[i0 * 3 + 2]);
                Vec3 p1 = transformPoint(model,
                        meshPositions[i1 * 3], meshPositions[i1 * 3 + 1], meshPositions[i1 * 3 + 2]);
                Vec3 p2 = transformPoint(model,
                        meshPositions[i2 * 3], meshPositions[i2 * 3 + 1], meshPositions[i2 * 3 + 2]);

                triangles.add(new double[]{
                        p0.x, p0.y, p0.z,
                        p1.x, p1.y, p1.z,
                        p2.x, p2.y, p2.z
                });
            }
        }

        int triangleCount = triangles.size();
        allPositions = new double[triangleCount * 9];
        allIndices = new int[triangleCount * 3];
        triangleOrder = new int[triangleCount];
        triangleBounds = new AABB[triangleCount];
        triangleCentroids = new Vec3[triangleCount];

        for (int tri = 0; tri < triangleCount; tri++) {
            double[] t = triangles.get(tri);
            int base = tri * 9;
            System.arraycopy(t, 0, allPositions, base, 9);
            int iBase = tri * 3;
            allIndices[iBase] = iBase;
            allIndices[iBase + 1] = iBase + 1;
            allIndices[iBase + 2] = iBase + 2;
            triangleOrder[tri] = tri;

            double minX = Math.min(t[0], Math.min(t[3], t[6]));
            double minY = Math.min(t[1], Math.min(t[4], t[7]));
            double minZ = Math.min(t[2], Math.min(t[5], t[8]));
            double maxX = Math.max(t[0], Math.max(t[3], t[6]));
            double maxY = Math.max(t[1], Math.max(t[4], t[7]));
            double maxZ = Math.max(t[2], Math.max(t[5], t[8]));

            triangleBounds[tri] = new AABB(
                    new Vec3(minX, minY, minZ),
                    new Vec3(maxX, maxY, maxZ)
            );
            triangleCentroids[tri] = new Vec3(
                    (t[0] + t[3] + t[6]) / 3.0,
                    (t[1] + t[4] + t[7]) / 3.0,
                    (t[2] + t[5] + t[8]) / 3.0
            );
        }

        root = triangleCount == 0 ? null : buildRecursive(0, triangleCount, 0);
    }

    @Override
    public boolean intersect(Ray ray, double tMin, double tMax, HitRecord record) {
        if (ray == null || root == null) {
            return false;
        }
        HitRecord target = record != null ? record : new HitRecord();
        if (record != null) {
            record.clear();
        }

        boolean hit = false;
        double closest = tMax;
        ArrayDeque<BVHNode> stack = new ArrayDeque<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            BVHNode node = stack.pop();
            if (node == null || !intersectsAabb(node.getBounds(), ray, tMin, closest)) {
                continue;
            }
            if (node.isLeaf()) {
                int start = node.getTriangleStart();
                int end = start + node.getTriangleCount();
                for (int i = start; i < end; i++) {
                    int triIndex = triangleOrder[i];
                    if (intersectTriangle(ray, triIndex, tMin, closest, target)) {
                        closest = target.getT();
                        hit = true;
                    }
                }
                continue;
            }

            BVHNode left = node.getLeft();
            BVHNode right = node.getRight();
            if (left == null && right == null) {
                continue;
            }
            if (left == null) {
                stack.push(right);
                continue;
            }
            if (right == null) {
                stack.push(left);
                continue;
            }

            double leftEntry = aabbEntry(left.getBounds(), ray, tMin, closest);
            double rightEntry = aabbEntry(right.getBounds(), ray, tMin, closest);
            if (leftEntry < rightEntry) {
                if (rightEntry < Double.POSITIVE_INFINITY) {
                    stack.push(right);
                }
                if (leftEntry < Double.POSITIVE_INFINITY) {
                    stack.push(left);
                }
            } else {
                if (leftEntry < Double.POSITIVE_INFINITY) {
                    stack.push(left);
                }
                if (rightEntry < Double.POSITIVE_INFINITY) {
                    stack.push(right);
                }
            }
        }
        return hit;
    }

    @Override
    public boolean intersectAny(Ray ray, double tMin, double tMax) {
        if (ray == null || root == null) {
            return false;
        }
        ArrayDeque<BVHNode> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            BVHNode node = stack.pop();
            if (node == null || !intersectsAabb(node.getBounds(), ray, tMin, tMax)) {
                continue;
            }
            if (node.isLeaf()) {
                int start = node.getTriangleStart();
                int end = start + node.getTriangleCount();
                for (int i = start; i < end; i++) {
                    int triIndex = triangleOrder[i];
                    if (intersectTriangle(ray, triIndex, tMin, tMax, null)) {
                        return true;
                    }
                }
                continue;
            }
            if (node.getLeft() != null) {
                stack.push(node.getLeft());
            }
            if (node.getRight() != null) {
                stack.push(node.getRight());
            }
        }
        return false;
    }

 // Internal helpers.
    private BVHNode buildRecursive(int start, int end, int depth) {
        if (start >= end) {
            return null;
        }
        AABB bounds = rangeBounds(start, end);
        int count = end - start;
        if (count <= maxLeafSize) {
            return new BVHNode(bounds, start, count);
        }

        int mid = -1;
        SahBvhUtil.Split split = SahBvhUtil.findBestSplit(
                triangleOrder,
                start,
                end,
                8,
                this::centroidAxis,
                (tri, out) -> {
                    AABB triBounds = triangleBounds[tri];
                    Vec3 min = triBounds.getMin();
                    Vec3 max = triBounds.getMax();
                    out.set(min.x, min.y, min.z, max.x, max.y, max.z);
                }
        );
        if (split.isValid()) {
            mid = SahBvhUtil.partitionByAxis(triangleOrder, start, end, split.axis, split.position, this::centroidAxis);
        }
        if (mid <= start || mid >= end) {
            int axis = bounds.longestAxis();
            sortTriangleOrderByAxis(start, end, axis);
            mid = start + count / 2;
            if (mid <= start || mid >= end) {
                return new BVHNode(bounds, start, count);
            }
        }

        BVHNode left = buildRecursive(start, mid, depth + 1);
        BVHNode right = buildRecursive(mid, end, depth + 1);
        if (left == null || right == null) {
            return new BVHNode(bounds, start, count);
        }
        return new BVHNode(bounds, left, right);
    }

    private boolean intersectTriangle(Ray ray, int triIndex, double tMin, double tMax, HitRecord record) {
        int base = triIndex * 9;
        if (base < 0 || base + 8 >= allPositions.length) {
            return false;
        }

        double v0x = allPositions[base];
        double v0y = allPositions[base + 1];
        double v0z = allPositions[base + 2];
        double v1x = allPositions[base + 3];
        double v1y = allPositions[base + 4];
        double v1z = allPositions[base + 5];
        double v2x = allPositions[base + 6];
        double v2y = allPositions[base + 7];
        double v2z = allPositions[base + 8];

        double e1x = v1x - v0x;
        double e1y = v1y - v0y;
        double e1z = v1z - v0z;
        double e2x = v2x - v0x;
        double e2y = v2y - v0y;
        double e2z = v2z - v0z;

        Vec3 dir = ray.getDirection();
        double px = dir.y * e2z - dir.z * e2y;
        double py = dir.z * e2x - dir.x * e2z;
        double pz = dir.x * e2y - dir.y * e2x;

        double det = e1x * px + e1y * py + e1z * pz;
        if (Math.abs(det) < EPS) {
            return false;
        }
        double invDet = 1.0 / det;

        Vec3 origin = ray.getOrigin();
        double tx = origin.x - v0x;
        double ty = origin.y - v0y;
        double tz = origin.z - v0z;

        double u = (tx * px + ty * py + tz * pz) * invDet;
        if (u < 0.0 || u > 1.0) {
            return false;
        }

        double qx = ty * e1z - tz * e1y;
        double qy = tz * e1x - tx * e1z;
        double qz = tx * e1y - ty * e1x;
        double v = (dir.x * qx + dir.y * qy + dir.z * qz) * invDet;
        if (v < 0.0 || u + v > 1.0) {
            return false;
        }

        double t = (e2x * qx + e2y * qy + e2z * qz) * invDet;
        if (t < tMin || t > tMax) {
            return false;
        }

        if (record != null) {
            Vec3 point = ray.pointAt(t);
            Vec3 outward = new Vec3(
                    e1y * e2z - e1z * e2y,
                    e1z * e2x - e1x * e2z,
                    e1x * e2y - e1y * e2x
            ).normalize();
            record.set(t, point, outward, true);
            record.setFaceNormal(ray, outward);
            record.setUv(new Vec3(u, v, 0.0));
            record.setTriangleIndex(triIndex);
            record.setEntityId(-1);
        }
        return true;
    }

    private AABB rangeBounds(int start, int end) {
        if (start >= end) {
            return new AABB(Vec3.ZERO, Vec3.ZERO);
        }
        AABB bounds = triangleBounds[triangleOrder[start]];
        for (int i = start + 1; i < end; i++) {
            bounds = AABB.merge(bounds, triangleBounds[triangleOrder[i]]);
        }
        return bounds;
    }

    private void sortTriangleOrderByAxis(int start, int end, int axis) {
        SahBvhUtil.sortByAxis(triangleOrder, start, end - 1, axis, this::centroidAxis);
    }

    private double centroidAxis(int triIndex, int axis) {
        Vec3 c = triangleCentroids[triIndex];
        if (axis == 0) {
            return c.x;
        }
        if (axis == 1) {
            return c.y;
        }
        return c.z;
    }

    private boolean intersectsAabb(AABB box, Ray ray, double tMin, double tMax) {
        return aabbEntry(box, ray, tMin, tMax) < Double.POSITIVE_INFINITY;
    }

    private double aabbEntry(AABB box, Ray ray, double tMin, double tMax) {
        if (box == null || ray == null) {
            return Double.POSITIVE_INFINITY;
        }
        Vec3 o = ray.getOrigin();
        Vec3 inv = ray.getInvDirection();
        Vec3 bMin = box.getMin();
        Vec3 bMax = box.getMax();

        double tx1 = (bMin.x - o.x) * inv.x;
        double tx2 = (bMax.x - o.x) * inv.x;
        double near = Math.max(tMin, Math.min(tx1, tx2));
        double far = Math.min(tMax, Math.max(tx1, tx2));

        double ty1 = (bMin.y - o.y) * inv.y;
        double ty2 = (bMax.y - o.y) * inv.y;
        near = Math.max(near, Math.min(ty1, ty2));
        far = Math.min(far, Math.max(ty1, ty2));

        double tz1 = (bMin.z - o.z) * inv.z;
        double tz2 = (bMax.z - o.z) * inv.z;
        near = Math.max(near, Math.min(tz1, tz2));
        far = Math.min(far, Math.max(tz1, tz2));

        return far >= near ? near : Double.POSITIVE_INFINITY;
    }

    private Vec3 transformPoint(double[] m, double x, double y, double z) {
        if (m == null || m.length < 16) {
            return new Vec3(x, y, z);
        }
        double tx = m[0] * x + m[1] * y + m[2] * z + m[3];
        double ty = m[4] * x + m[5] * y + m[6] * z + m[7];
        double tz = m[8] * x + m[9] * y + m[10] * z + m[11];
        double tw = m[12] * x + m[13] * y + m[14] * z + m[15];
        if (Math.abs(tw) > EPS && Math.abs(tw - 1.0) > EPS) {
            tx /= tw;
            ty /= tw;
            tz /= tw;
        }
        return new Vec3(tx, ty, tz);
    }

    public BVHNode getRoot() {
        return root;
    }

    public int getTriangleCount() {
        return triangleBounds.length;
    }

    public int getMaxLeafSize() {
        return maxLeafSize;
    }

    public void setMaxLeafSize(int maxLeafSize) {
        this.maxLeafSize = Math.max(1, maxLeafSize);
    }
}
