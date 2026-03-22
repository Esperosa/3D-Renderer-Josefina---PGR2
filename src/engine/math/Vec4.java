package engine.math;

/**
 * Represents měnitelný 4D vektor pro homogenní souřadnice a RGBA barvy.
 */
public class Vec4 {

    public double x;
    public double y;
    public double z;
    public double w;

    public Vec4() {
        this(0.0, 0.0, 0.0, 0.0);
    }

    public Vec4(double x, double y, double z, double w) {
        set(x, y, z, w);
    }

 /**
 * Creates Vec4 z Vec3 a samostatné složky w.
 */
    public Vec4(Vec3 v, double w) {
        this(v.x, v.y, v.z, w);
    }

    public Vec4 set(double x, double y, double z, double w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        return this;
    }

    public Vec4 set(Vec4 other) {
        if (other == null) {
            return set(0.0, 0.0, 0.0, 0.0);
        }
        return set(other.x, other.y, other.z, other.w);
    }

    public Vec4 add(Vec4 v) {
        return add(v, new Vec4());
    }

    public Vec4 add(Vec4 v, Vec4 out) {
        if (out == null) {
            out = new Vec4();
        }
        return out.set(x + v.x, y + v.y, z + v.z, w + v.w);
    }

    public Vec4 addInPlace(Vec4 v) {
        x += v.x;
        y += v.y;
        z += v.z;
        w += v.w;
        return this;
    }

    public Vec4 sub(Vec4 v) {
        return sub(v, new Vec4());
    }

    public Vec4 sub(Vec4 v, Vec4 out) {
        if (out == null) {
            out = new Vec4();
        }
        return out.set(x - v.x, y - v.y, z - v.z, w - v.w);
    }

    public Vec4 subInPlace(Vec4 v) {
        x -= v.x;
        y -= v.y;
        z -= v.z;
        w -= v.w;
        return this;
    }

    public Vec4 mul(double s) {
        return mul(s, new Vec4());
    }

    public Vec4 mul(double s, Vec4 out) {
        if (out == null) {
            out = new Vec4();
        }
        return out.set(x * s, y * s, z * s, w * s);
    }

    public Vec4 mulInPlace(double s) {
        x *= s;
        y *= s;
        z *= s;
        w *= s;
        return this;
    }

    public double dot(Vec4 v) {
        return x * v.x + y * v.y + z * v.z + w * v.w;
    }

    public Vec3 perspectiveDivide() {
        return perspectiveDivide(new Vec3());
    }

    public Vec3 perspectiveDivide(Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        if (Math.abs(w) < MathUtil.EPSILON) {
            return out.zero();
        }
        return out.set(x / w, y / w, z / w);
    }

    public Vec3 toVec3() {
        return toVec3(new Vec3());
    }

    public Vec3 toVec3(Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(x, y, z);
    }

    public static Vec4 lerp(Vec4 a, Vec4 b, double t) {
        return lerp(a, b, t, new Vec4());
    }

    public static Vec4 lerp(Vec4 a, Vec4 b, double t, Vec4 out) {
        if (out == null) {
            out = new Vec4();
        }
        return out.set(
                MathUtil.lerp(a.x, b.x, t),
                MathUtil.lerp(a.y, b.y, t),
                MathUtil.lerp(a.z, b.z, t),
                MathUtil.lerp(a.w, b.w, t)
        );
    }

    @Override
    public String toString() {
        return "Vec4(" + x + ", " + y + ", " + z + ", " + w + ")";
    }
}