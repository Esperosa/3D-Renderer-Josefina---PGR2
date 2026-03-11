package engine.physics;

import engine.math.Vec3;

/**
 * Tady držím výstup úzké fáze kolizního testu.
 * Ukládám si sem normálu kontaktu, hloubku průniku a zúčastněná těla.
 */
public class CollisionResult {

    private Vec3 contactNormal;
    private double penetrationDepth;
    private Vec3 contactPoint;
    private RigidBody bodyA;
    private RigidBody bodyB;

    public CollisionResult() {
        this.contactNormal = new Vec3(0.0, 1.0, 0.0);
        this.penetrationDepth = 0.0;
        this.contactPoint = Vec3.ZERO;
    }

    public void set(Vec3 normal, double depth, Vec3 point, RigidBody a, RigidBody b) {
        this.contactNormal = normal.normalize();
        this.penetrationDepth = depth;
        this.contactPoint = point;
        this.bodyA = a;
        this.bodyB = b;
    }

    public Vec3 getContactNormal() {
        return contactNormal;
    }

    public double getPenetrationDepth() {
        return penetrationDepth;
    }

    public Vec3 getContactPoint() {
        return contactPoint;
    }

    public RigidBody getBodyA() {
        return bodyA;
    }

    public RigidBody getBodyB() {
        return bodyB;
    }
}
