package engine.physics;

import engine.math.AABB;
import engine.math.Vec3;

/**
 * Tady držím axis-aligned box kolizní tvar.
 */
public class AABBCollider extends Collider {

    private Vec3 halfExtents;

    public AABBCollider(Vec3 halfExtents) {
        this.halfExtents = halfExtents == null ? new Vec3(0.5, 0.5, 0.5) : halfExtents;
    }

    @Override
    public AABB getWorldAABB(Vec3 worldPos) {
        Vec3 c = worldPos.add(offset);
        return new AABB(c.sub(halfExtents), c.add(halfExtents));
    }

    @Override
    public boolean testCollision(Vec3 posA, Collider other, Vec3 posB, CollisionResult result) {
        if (other instanceof AABBCollider) {
            return collideAabbAabb(posA, this, posB, (AABBCollider) other, result);
        }
        if (other instanceof SphereCollider) {
            boolean hit = collideAabbSphere(posA, this, posB, (SphereCollider) other, result);
            if (hit && result != null) {
                result.set(
                        result.getContactNormal(),
                        result.getPenetrationDepth(),
                        result.getContactPoint(),
                        result.getBodyA(),
                        result.getBodyB()
                );
            }
            return hit;
        }
        return false;
    }

    public Vec3 getHalfExtents() {
        return halfExtents;
    }

    public void setHalfExtents(Vec3 he) {
        this.halfExtents = he;
    }

    static boolean collideAabbAabb(Vec3 posA, AABBCollider a, Vec3 posB, AABBCollider b, CollisionResult result) {
        Vec3 ca = posA.add(a.offset);
        Vec3 cb = posB.add(b.offset);
        Vec3 diff = cb.sub(ca);

        double overlapX = (a.halfExtents.x + b.halfExtents.x) - Math.abs(diff.x);
        double overlapY = (a.halfExtents.y + b.halfExtents.y) - Math.abs(diff.y);
        double overlapZ = (a.halfExtents.z + b.halfExtents.z) - Math.abs(diff.z);
        if (overlapX <= 0 || overlapY <= 0 || overlapZ <= 0) {
            return false;
        }

        double depth = overlapX;
        Vec3 normal = new Vec3(Math.signum(diff.x), 0.0, 0.0);
        if (overlapY < depth) {
            depth = overlapY;
            normal = new Vec3(0.0, Math.signum(diff.y), 0.0);
        }
        if (overlapZ < depth) {
            depth = overlapZ;
            normal = new Vec3(0.0, 0.0, Math.signum(diff.z));
        }

        if (result != null) {
            Vec3 point = ca.add(cb).mul(0.5);
            result.set(normal, depth, point, null, null);
        }
        return true;
    }

    static boolean collideAabbSphere(Vec3 posA, AABBCollider a, Vec3 posB, SphereCollider sphere, CollisionResult result) {
        Vec3 boxCenter = posA.add(a.offset);
        Vec3 sphereCenter = posB.add(sphere.getOffset());
        Vec3 min = boxCenter.sub(a.halfExtents);
        Vec3 max = boxCenter.add(a.halfExtents);

        double cx = Math.max(min.x, Math.min(max.x, sphereCenter.x));
        double cy = Math.max(min.y, Math.min(max.y, sphereCenter.y));
        double cz = Math.max(min.z, Math.min(max.z, sphereCenter.z));
        Vec3 closest = new Vec3(cx, cy, cz);

        Vec3 delta = sphereCenter.sub(closest);
        double distSq = delta.lengthSquared();
        double radius = sphere.getRadius();
        if (distSq > radius * radius) {
            return false;
        }

        double dist = Math.sqrt(Math.max(1e-10, distSq));
        Vec3 normal = dist > 1e-6 ? delta.div(dist) : new Vec3(0.0, 1.0, 0.0);
        double depth = radius - dist;
        if (result != null) {
            result.set(normal, depth, closest, null, null);
        }
        return true;
    }
}
