package engine.physics;

import engine.math.AABB;
import engine.math.Vec3;

/**
 * Represents kulový kolizní tvar.
 */
public class SphereCollider extends Collider {

    private double radius;

    public SphereCollider(double radius) {
        this.radius = Math.max(1e-6, radius);
    }

    @Override
    public AABB getWorldAABB(Vec3 worldPos) {
        Vec3 c = worldPos.add(offset);
        Vec3 e = new Vec3(radius, radius, radius);
        return new AABB(c.sub(e), c.add(e));
    }

    @Override
    public boolean testCollision(Vec3 posA, Collider other, Vec3 posB, CollisionResult result) {
        if (other instanceof SphereCollider) {
            return collideSphereSphere(posA, this, posB, (SphereCollider) other, result);
        }
        if (other instanceof AABBCollider) {
            boolean hit = AABBCollider.collideAabbSphere(posB, (AABBCollider) other, posA, this, result);
            if (hit && result != null) {
                Vec3 n = result.getContactNormal().negate();
                result.set(n, result.getPenetrationDepth(), result.getContactPoint(), null, null);
            }
            return hit;
        }
        return false;
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double r) {
        this.radius = Math.max(1e-6, r);
    }

    private static boolean collideSphereSphere(Vec3 posA, SphereCollider a, Vec3 posB, SphereCollider b, CollisionResult result) {
        Vec3 ca = posA.add(a.offset);
        Vec3 cb = posB.add(b.offset);
        Vec3 delta = cb.sub(ca);
        double distSq = delta.lengthSquared();
        double rr = a.radius + b.radius;
        if (distSq >= rr * rr) {
            return false;
        }

        double dist = Math.sqrt(Math.max(1e-10, distSq));
        Vec3 normal = dist > 1e-6 ? delta.div(dist) : new Vec3(0.0, 1.0, 0.0);
        double depth = rr - dist;
        Vec3 point = ca.add(normal.mul(a.radius - depth * 0.5));
        if (result != null) {
            result.set(normal, depth, point, null, null);
        }
        return true;
    }
}
