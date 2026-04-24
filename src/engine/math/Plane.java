package engine.math;

/**
 * Represents 3D rovinu určenou normálou a vzdáleností od počátku.
 */
public class Plane {

    private Vec3 normal;
    private double d;

    public Plane(Vec3 normal, double d) {
        this.normal = normal;
        this.d = d;
        normalize();
    }

    public Plane(Vec3 normal, Vec3 point) {
        this.normal = normal;
        this.d = -normal.dot(point);
        normalize();
    }

    public Plane(Vec3 a, Vec3 b, Vec3 c) {
        Vec3 n = b.sub(a).cross(c.sub(a)).normalize();
        this.normal = n;
        this.d = -n.dot(a);
    }

    public double distanceTo(Vec3 point) {
        return normal.dot(point) + d;
    }

    public int classify(Vec3 point) {
        double dist = distanceTo(point);
        if (dist > MathUtil.EPSILON) {
            return 1;
        }
        if (dist < -MathUtil.EPSILON) {
            return -1;
        }
        return 0;
    }

    public double intersectRay(Ray ray) {
        double denom = normal.dot(ray.getDirection());
        if (Math.abs(denom) < MathUtil.EPSILON) {
            return Double.NaN;
        }
        return -(normal.dot(ray.getOrigin()) + d) / denom;
    }

    public Plane normalize() {
        double len = normal.length();
        if (len < MathUtil.EPSILON) {
            return this;
        }
        normal = normal.div(len);
        d /= len;
        return this;
    }

    public Vec3 getNormal() {
        return normal;
    }

    public double getD() {
        return d;
    }

    @Override
    public String toString() {
        return "Plane(normal=" + normal + ", d=" + d + ")";
    }
}
