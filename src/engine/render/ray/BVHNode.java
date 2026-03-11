package engine.render.ray;

import engine.math.AABB;

/**
 * Tady držím uzel bounding volume hierarchy.
 * Do interních uzlů ukládám AABB a dva potomky.
 * Do listů si odkazuju rozsah trojúhelníků.
 */
public class BVHNode {

    private final AABB bounds;
    private final BVHNode left;
    private final BVHNode right;
    private final int triangleStart;
    private final int triangleCount;

    public BVHNode() {
        this(new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO), null, null, 0, 0);
    }

    public BVHNode(AABB bounds, BVHNode left, BVHNode right) {
        this(bounds, left, right, 0, 0);
    }

    public BVHNode(AABB bounds, int start, int count) {
        this(bounds, null, null, Math.max(0, start), Math.max(0, count));
    }

    private BVHNode(AABB bounds, BVHNode left, BVHNode right, int triangleStart, int triangleCount) {
        this.bounds = bounds == null
                ? new AABB(engine.math.Vec3.ZERO, engine.math.Vec3.ZERO)
                : bounds;
        this.left = left;
        this.right = right;
        this.triangleStart = triangleStart;
        this.triangleCount = triangleCount;
    }

    public boolean isLeaf() {
        return triangleCount > 0;
    }

    // Tady držím přístupové metody.
    public AABB getBounds() {
        return bounds;
    }

    public BVHNode getLeft() {
        return left;
    }

    public BVHNode getRight() {
        return right;
    }

    public int getTriangleStart() {
        return triangleStart;
    }

    public int getTriangleCount() {
        return triangleCount;
    }
}
