package engine.math;

/**
 * Represents axis-aligned bounding box určený minimem a maximem.
 */
public class AABB {

    private final Vec3 min;
    private final Vec3 max;

    public AABB(Vec3 min, Vec3 max) {
        this.min = min;
        this.max = max;
    }

 // Handles základní dotazy nad boxem.
    public Vec3 center() {
        return min.add(max).mul(0.5);
    }

    public Vec3 extents() {
        return max.sub(min).mul(0.5);
    }

    public double surfaceArea() {
        Vec3 s = max.sub(min);
        return 2.0 * (s.x * s.y + s.x * s.z + s.y * s.z);
    }

    public int longestAxis() {
        Vec3 s = max.sub(min);
        if (s.x >= s.y && s.x >= s.z) {
            return 0;
        }
        if (s.y >= s.x && s.y >= s.z) {
            return 1;
        }
        return 2;
    }

 // Handles testy průniku a zásahu.
    public boolean contains(Vec3 point) {
        return point.x >= min.x && point.x <= max.x
                && point.y >= min.y && point.y <= max.y
                && point.z >= min.z && point.z <= max.z;
    }

    public boolean intersects(AABB other) {
        return min.x <= other.max.x && max.x >= other.min.x
                && min.y <= other.max.y && max.y >= other.min.y
                && min.z <= other.max.z && max.z >= other.min.z;
    }

    public double intersectRay(Ray ray) {
        Vec3 o = ray.getOrigin();
        Vec3 inv = ray.getInvDirection();

        double tx1 = (min.x - o.x) * inv.x;
        double tx2 = (max.x - o.x) * inv.x;
        double tmin = Math.min(tx1, tx2);
        double tmax = Math.max(tx1, tx2);

        double ty1 = (min.y - o.y) * inv.y;
        double ty2 = (max.y - o.y) * inv.y;
        tmin = Math.max(tmin, Math.min(ty1, ty2));
        tmax = Math.min(tmax, Math.max(ty1, ty2));

        double tz1 = (min.z - o.z) * inv.z;
        double tz2 = (max.z - o.z) * inv.z;
        tmin = Math.max(tmin, Math.min(tz1, tz2));
        tmax = Math.min(tmax, Math.max(tz1, tz2));

        if (tmax >= Math.max(0.0, tmin)) {
            return tmin;
        }
        return Double.MAX_VALUE;
    }

 // Builds nové bounding boxy.
    public static AABB merge(AABB a, AABB b) {
        return new AABB(
                Vec3.min(a.min, b.min),
                Vec3.max(a.max, b.max)
        );
    }

    public AABB expand(Vec3 point) {
        return new AABB(Vec3.min(min, point), Vec3.max(max, point));
    }

    public static AABB fromPoints(Vec3[] points) {
        if (points == null || points.length == 0) {
            return new AABB(Vec3.ZERO, Vec3.ZERO);
        }
        Vec3 mn = points[0];
        Vec3 mx = points[0];
        for (int i = 1; i < points.length; i++) {
            mn = Vec3.min(mn, points[i]);
            mx = Vec3.max(mx, points[i]);
        }
        return new AABB(mn, mx);
    }

    public static AABB fromTriangles(double[] positions, int[] indices) {
        if (positions == null || positions.length < 3) {
            return new AABB(Vec3.ZERO, Vec3.ZERO);
        }
        Vec3 mn = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 mx = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);

        if (indices == null || indices.length == 0) {
            for (int i = 0; i < positions.length; i += 3) {
                Vec3 p = new Vec3(positions[i], positions[i + 1], positions[i + 2]);
                mn = Vec3.min(mn, p);
                mx = Vec3.max(mx, p);
            }
        } else {
            for (int index : indices) {
                int base = index * 3;
                Vec3 p = new Vec3(positions[base], positions[base + 1], positions[base + 2]);
                mn = Vec3.min(mn, p);
                mx = Vec3.max(mx, p);
            }
        }
        return new AABB(mn, mx);
    }

    public AABB transform(Mat4 model) {
        Vec3[] corners = new Vec3[]{
                new Vec3(min.x, min.y, min.z),
                new Vec3(max.x, min.y, min.z),
                new Vec3(min.x, max.y, min.z),
                new Vec3(max.x, max.y, min.z),
                new Vec3(min.x, min.y, max.z),
                new Vec3(max.x, min.y, max.z),
                new Vec3(min.x, max.y, max.z),
                new Vec3(max.x, max.y, max.z)
        };
        Vec3 mn = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 mx = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        for (Vec3 corner : corners) {
            Vec3 p = model.transformPoint(corner);
            mn = Vec3.min(mn, p);
            mx = Vec3.max(mx, p);
        }
        return new AABB(mn, mx);
    }

    public Vec3 getMin() {
        return min;
    }

    public Vec3 getMax() {
        return max;
    }

    @Override
    public String toString() {
        return "AABB(min=" + min + ", max=" + max + ")";
    }
}