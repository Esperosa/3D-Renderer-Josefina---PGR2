final class Vec3 {
    static final Vec3 ZERO = new Vec3(0.0, 0.0, 0.0);

    final double x;
    final double y;
    final double z;

    Vec3(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    Vec3 add(Vec3 o) {
        return new Vec3(x + o.x, y + o.y, z + o.z);
    }

    Vec3 sub(Vec3 o) {
        return new Vec3(x - o.x, y - o.y, z - o.z);
    }

    Vec3 mul(double s) {
        return new Vec3(x * s, y * s, z * s);
    }

    Vec3 hadamard(Vec3 o) {
        return new Vec3(x * o.x, y * o.y, z * o.z);
    }

    double dot(Vec3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x
        );
    }

    double length() {
        return Math.sqrt(dot(this));
    }

    Vec3 normalize() {
        double len = length();
        if (len < 1.0e-9) {
            return new Vec3(0.0, 1.0, 0.0);
        }
        return mul(1.0 / len);
    }

    Vec3 clamp01() {
        return new Vec3(clamp(x), clamp(y), clamp(z));
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return a.mul(1.0 - t).add(b.mul(t));
    }
}
