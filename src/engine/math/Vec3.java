package engine.math;

/**
 * Represents měnitelný 3D vektor pro pozice, normály a barvy.
 */
public class Vec3 {

    public static final Vec3 ZERO = new Vec3(0, 0, 0);
    public static final Vec3 ONE  = new Vec3(1, 1, 1);
    public static final Vec3 UP   = new Vec3(0, 1, 0);

    public double x;
    public double y;
    public double z;

    public Vec3() {
        this(0.0, 0.0, 0.0);
    }

    public Vec3(double x, double y, double z) {
        set(x, y, z);
    }

    public Vec3 set(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vec3 set(Vec3 other) {
        if (other == null) {
            return zero();
        }
        return set(other.x, other.y, other.z);
    }

    public Vec3 zero() {
        return set(0.0, 0.0, 0.0);
    }

    public Vec3 add(Vec3 v) {
        return add(v, new Vec3());
    }

    public Vec3 add(Vec3 v, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(x + v.x, y + v.y, z + v.z);
    }

    public Vec3 addInPlace(Vec3 v) {
        x += v.x;
        y += v.y;
        z += v.z;
        return this;
    }

    public Vec3 sub(Vec3 v) {
        return sub(v, new Vec3());
    }

    public Vec3 sub(Vec3 v, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(x - v.x, y - v.y, z - v.z);
    }

    public Vec3 subInPlace(Vec3 v) {
        x -= v.x;
        y -= v.y;
        z -= v.z;
        return this;
    }

    public Vec3 mul(double s) {
        return mul(s, new Vec3());
    }

    public Vec3 mul(double s, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(x * s, y * s, z * s);
    }

    public Vec3 mulInPlace(double s) {
        x *= s;
        y *= s;
        z *= s;
        return this;
    }

    public Vec3 div(double s) {
        return div(s, new Vec3());
    }

    public Vec3 div(double s, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        if (Math.abs(s) < MathUtil.EPSILON) {
            return out.zero();
        }
        return out.set(x / s, y / s, z / s);
    }

    public Vec3 divInPlace(double s) {
        if (Math.abs(s) < MathUtil.EPSILON) {
            return zero();
        }
        x /= s;
        y /= s;
        z /= s;
        return this;
    }

    public Vec3 negate() {
        return negate(new Vec3());
    }

    public Vec3 negate(Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(-x, -y, -z);
    }

    public Vec3 negateInPlace() {
        x = -x;
        y = -y;
        z = -z;
        return this;
    }

    public Vec3 addScaledInPlace(Vec3 v, double scale) {
        x += v.x * scale;
        y += v.y * scale;
        z += v.z * scale;
        return this;
    }

    public double dot(Vec3 v) {
        return x * v.x + y * v.y + z * v.z;
    }

    public Vec3 cross(Vec3 v) {
        return cross(v, new Vec3());
    }

    public Vec3 cross(Vec3 v, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(
                y * v.z - z * v.y,
                z * v.x - x * v.z,
                x * v.y - y * v.x
        );
    }

    public Vec3 crossInPlace(Vec3 v) {
        return set(
                y * v.z - z * v.y,
                z * v.x - x * v.z,
                x * v.y - y * v.x
        );
    }

    public double length() {
        return Math.sqrt(lengthSquared());
    }

    public double lengthSquared() {
        return dot(this);
    }

    public Vec3 normalize() {
        return normalize(new Vec3());
    }

    public Vec3 normalize(Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        double len = length();
        if (len < MathUtil.EPSILON) {
            return out.zero();
        }
        return out.set(x / len, y / len, z / len);
    }

    public Vec3 normalizeInPlace() {
        double len = length();
        if (len < MathUtil.EPSILON) {
            return zero();
        }
        return divInPlace(len);
    }

    public Vec3 reflect(Vec3 normal) {
        return reflect(normal, new Vec3());
    }

    public Vec3 reflect(Vec3 normal, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        double nnx = normal.x;
        double nny = normal.y;
        double nnz = normal.z;
        double lenSq = nnx * nnx + nny * nny + nnz * nnz;
        if (lenSq < MathUtil.EPSILON) {
            return out.zero();
        }
        double invLen = 1.0 / Math.sqrt(lenSq);
        nnx *= invLen;
        nny *= invLen;
        nnz *= invLen;
        double scale = 2.0 * (x * nnx + y * nny + z * nnz);
        return out.set(x - nnx * scale, y - nny * scale, z - nnz * scale);
    }

    public Vec3 refract(Vec3 normal, double eta) {
        return refract(normal, eta, new Vec3());
    }

    public Vec3 refract(Vec3 normal, double eta, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        double nnx = normal.x;
        double nny = normal.y;
        double nnz = normal.z;
        double normalLenSq = nnx * nnx + nny * nny + nnz * nnz;
        double incidentLenSq = x * x + y * y + z * z;
        if (normalLenSq < MathUtil.EPSILON || incidentLenSq < MathUtil.EPSILON) {
            return out.zero();
        }
        double invNormalLen = 1.0 / Math.sqrt(normalLenSq);
        nnx *= invNormalLen;
        nny *= invNormalLen;
        nnz *= invNormalLen;
        double invIncidentLen = 1.0 / Math.sqrt(incidentLenSq);
        double ix = x * invIncidentLen;
        double iy = y * invIncidentLen;
        double iz = z * invIncidentLen;
        double cosi = MathUtil.clamp(ix * nnx + iy * nny + iz * nnz, -1.0, 1.0);
        double etai = 1.0;
        double etat = eta;
        double rnx = nnx;
        double rny = nny;
        double rnz = nnz;

        if (cosi > 0.0) {
            double tmp = etai;
            etai = etat;
            etat = tmp;
            rnx = -nnx;
            rny = -nny;
            rnz = -nnz;
        } else {
            cosi = -cosi;
        }

        double etaRatio = etai / etat;
        double k = 1.0 - etaRatio * etaRatio * (1.0 - cosi * cosi);
        if (k < 0.0) {
            return out.zero();
        }
        double nScale = etaRatio * cosi - Math.sqrt(k);
        return out.set(
                ix * etaRatio + rnx * nScale,
                iy * etaRatio + rny * nScale,
                iz * etaRatio + rnz * nScale
        );
    }

    public static Vec3 lerp(Vec3 a, Vec3 b, double t) {
        return lerp(a, b, t, new Vec3());
    }

    public static Vec3 lerp(Vec3 a, Vec3 b, double t, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(
                MathUtil.lerp(a.x, b.x, t),
                MathUtil.lerp(a.y, b.y, t),
                MathUtil.lerp(a.z, b.z, t)
        );
    }

    public static Vec3 min(Vec3 a, Vec3 b) {
        return min(a, b, new Vec3());
    }

    public static Vec3 min(Vec3 a, Vec3 b, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(
                Math.min(a.x, b.x),
                Math.min(a.y, b.y),
                Math.min(a.z, b.z)
        );
    }

    public static Vec3 max(Vec3 a, Vec3 b) {
        return max(a, b, new Vec3());
    }

    public static Vec3 max(Vec3 a, Vec3 b, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(
                Math.max(a.x, b.x),
                Math.max(a.y, b.y),
                Math.max(a.z, b.z)
        );
    }

    public int toIntRGB() {
        int r = (int) (MathUtil.clamp01(x) * 255.0 + 0.5);
        int g = (int) (MathUtil.clamp01(y) * 255.0 + 0.5);
        int b = (int) (MathUtil.clamp01(z) * 255.0 + 0.5);
        return (r << 16) | (g << 8) | b;
    }

    public static Vec3 fromIntRGB(int rgb) {
        double r = ((rgb >> 16) & 0xFF) / 255.0;
        double g = ((rgb >> 8) & 0xFF) / 255.0;
        double b = (rgb & 0xFF) / 255.0;
        return new Vec3(r, g, b);
    }

    @Override
    public String toString() {
        return "Vec3(" + x + ", " + y + ", " + z + ")";
    }
}
