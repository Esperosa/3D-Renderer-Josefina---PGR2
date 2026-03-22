package engine.physics;

import engine.math.Vec3;

/**
 * Represents výsledek fyzikálního raycast dotazu.
 */
public class RaycastResult {

    private boolean hit;
    private double distance;
    private Vec3 point;
    private Vec3 normal;
    private RigidBody body;

    public RaycastResult() {
        this.hit = false;
        this.distance = Double.MAX_VALUE;
        this.point = Vec3.ZERO;
        this.normal = new Vec3(0.0, 1.0, 0.0);
        this.body = null;
    }

    public void set(double distance, Vec3 point, Vec3 normal, RigidBody body) {
        this.hit = true;
        this.distance = distance;
        this.point = point;
        this.normal = normal;
        this.body = body;
    }

    public double getDistance() {
        return distance;
    }

    public Vec3 getPoint() {
        return point;
    }

    public Vec3 getNormal() {
        return normal;
    }

    public RigidBody getBody() {
        return body;
    }

    public boolean isHit() {
        return hit;
    }
}
