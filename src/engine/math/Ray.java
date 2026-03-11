package engine.math;

/**
 * Tady držím paprsek určený počátkem a směrem.
 */
public class Ray {

    private final Vec3 origin;
    private final Vec3 direction;
    private final Vec3 invDirection;

    public Ray(Vec3 origin, Vec3 direction) {
        this.origin = origin;
        this.direction = direction.normalize();
        this.invDirection = new Vec3(
                Math.abs(this.direction.x) < MathUtil.EPSILON ? Double.POSITIVE_INFINITY : 1.0 / this.direction.x,
                Math.abs(this.direction.y) < MathUtil.EPSILON ? Double.POSITIVE_INFINITY : 1.0 / this.direction.y,
                Math.abs(this.direction.z) < MathUtil.EPSILON ? Double.POSITIVE_INFINITY : 1.0 / this.direction.z
        );
    }

    public Vec3 pointAt(double t) {
        return origin.add(direction.mul(t));
    }

    public Vec3 getOrigin() {
        return origin;
    }

    public Vec3 getDirection() {
        return direction;
    }

    public Vec3 getInvDirection() {
        return invDirection;
    }

    @Override
    public String toString() {
        return "Ray(origin=" + origin + ", direction=" + direction + ")";
    }
}
