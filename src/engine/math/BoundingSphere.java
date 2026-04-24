package engine.math;

/**
 * Represents bounding sphere danou středem a poloměrem.
 * Používám ji jako lehký bounding volume pro rychlé vyřazovací testy.
 */
public class BoundingSphere {

    private final Vec3 center;
    private final double radius;

    public BoundingSphere(Vec3 center, double radius) {
        this.center = center;
        this.radius = Math.max(0.0, radius);
    }

 // Represents testy průniku a obsahu.
    public boolean contains(Vec3 point) {
        return point.sub(center).lengthSquared() <= radius * radius;
    }

    public boolean intersects(BoundingSphere other) {
        double rr = radius + other.radius;
        return other.center.sub(center).lengthSquared() <= rr * rr;
    }

    public double intersectRay(Ray ray) {
        Vec3 oc = ray.getOrigin().sub(center);
        double a = ray.getDirection().dot(ray.getDirection());
        double b = 2.0 * oc.dot(ray.getDirection());
        double c = oc.dot(oc) - radius * radius;
        double disc = b * b - 4.0 * a * c;
        if (disc < 0.0) {
            return Double.MAX_VALUE;
        }
        double sqrt = Math.sqrt(disc);
        double t0 = (-b - sqrt) / (2.0 * a);
        double t1 = (-b + sqrt) / (2.0 * a);
        if (t0 >= 0.0) {
            return t0;
        }
        if (t1 >= 0.0) {
            return t1;
        }
        return Double.MAX_VALUE;
    }

 // Handles konstrukci a převody.
    public static BoundingSphere fromAABB(AABB aabb) {
        Vec3 center = aabb.center();
        double radius = aabb.getMax().sub(center).length();
        return new BoundingSphere(center, radius);
    }

    public static BoundingSphere fromPoints(Vec3[] points) {
        if (points == null || points.length == 0) {
            return new BoundingSphere(Vec3.ZERO, 0.0);
        }

        Vec3 centroid = Vec3.ZERO;
        for (Vec3 p : points) {
            centroid = centroid.add(p);
        }
        centroid = centroid.div(points.length);

        double maxDistSq = 0.0;
        for (Vec3 p : points) {
            maxDistSq = Math.max(maxDistSq, p.sub(centroid).lengthSquared());
        }
        return new BoundingSphere(centroid, Math.sqrt(maxDistSq));
    }

    public BoundingSphere transform(Vec3 translation, double uniformScale) {
        return new BoundingSphere(
                center.add(translation),
                radius * Math.abs(uniformScale)
        );
    }

    public Vec3 getCenter() {
        return center;
    }

    public double getRadius() {
        return radius;
    }

    @Override
    public String toString() {
        return "BoundingSphere(center=" + center + ", radius=" + radius + ")";
    }
}