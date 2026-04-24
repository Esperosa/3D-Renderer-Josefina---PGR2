package engine.math;

/**
 * Represents jednotkový quaternion pro plynulou 3D rotaci bez zámku os.
 */
public class Quaternion {

    public final double x, y, z, w;

    public Quaternion() {
        this(0.0, 0.0, 0.0, 1.0);
    }

    public Quaternion(double x, double y, double z, double w) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

 // Represents základní operace.
    public Quaternion multiply(Quaternion q) {
        return new Quaternion(
                w * q.x + x * q.w + y * q.z - z * q.y,
                w * q.y - x * q.z + y * q.w + z * q.x,
                w * q.z + x * q.y - y * q.x + z * q.w,
                w * q.w - x * q.x - y * q.y - z * q.z
        );
    }

    public Quaternion conjugate() {
        return new Quaternion(-x, -y, -z, w);
    }

    public Quaternion normalize() {
        double len = length();
        if (len < MathUtil.EPSILON) {
            return new Quaternion();
        }
        return new Quaternion(x / len, y / len, z / len, w / len);
    }

    public double length() {
        return Math.sqrt(x * x + y * y + z * z + w * w);
    }

 // Handles převody.
    public Mat4 toMat4() {
        Quaternion q = normalize();
        double xx = q.x * q.x;
        double yy = q.y * q.y;
        double zz = q.z * q.z;
        double xy = q.x * q.y;
        double xz = q.x * q.z;
        double yz = q.y * q.z;
        double wx = q.w * q.x;
        double wy = q.w * q.y;
        double wz = q.w * q.z;

        return new Mat4(
                1.0 - 2.0 * (yy + zz), 2.0 * (xy - wz), 2.0 * (xz + wy), 0.0,
                2.0 * (xy + wz), 1.0 - 2.0 * (xx + zz), 2.0 * (yz - wx), 0.0,
                2.0 * (xz - wy), 2.0 * (yz + wx), 1.0 - 2.0 * (xx + yy), 0.0,
                0.0, 0.0, 0.0, 1.0
        );
    }

    public Mat3 toMat3() {
        return toMat4().toMat3();
    }

    public static Quaternion fromAxisAngle(Vec3 axis, double radians) {
        Vec3 n = axis.normalize();
        double half = radians * 0.5;
        double s = Math.sin(half);
        return new Quaternion(n.x * s, n.y * s, n.z * s, Math.cos(half)).normalize();
    }

    public static Quaternion fromEuler(double pitch, double yaw, double roll) {
        Quaternion qx = fromAxisAngle(new Vec3(1.0, 0.0, 0.0), pitch);
        Quaternion qy = fromAxisAngle(new Vec3(0.0, 1.0, 0.0), yaw);
        Quaternion qz = fromAxisAngle(new Vec3(0.0, 0.0, 1.0), roll);
        return qy.multiply(qx).multiply(qz).normalize();
    }

    public Vec3 toEuler() {
        Quaternion q = normalize();

        double sinrCosp = 2.0 * (q.w * q.x + q.y * q.z);
        double cosrCosp = 1.0 - 2.0 * (q.x * q.x + q.y * q.y);
        double pitch = Math.atan2(sinrCosp, cosrCosp);

        double sinp = 2.0 * (q.w * q.y - q.z * q.x);
        double yaw;
        if (Math.abs(sinp) >= 1.0) {
            yaw = Math.copySign(Math.PI / 2.0, sinp);
        } else {
            yaw = Math.asin(sinp);
        }

        double sinyCosp = 2.0 * (q.w * q.z + q.x * q.y);
        double cosyCosp = 1.0 - 2.0 * (q.y * q.y + q.z * q.z);
        double roll = Math.atan2(sinyCosp, cosyCosp);

        return new Vec3(pitch, yaw, roll);
    }

 // Handles interpolaci.
    public static Quaternion slerp(Quaternion a, Quaternion b, double t) {
        Quaternion qa = a.normalize();
        Quaternion qb = b.normalize();
        double dot = qa.x * qb.x + qa.y * qb.y + qa.z * qb.z + qa.w * qb.w;

        if (dot < 0.0) {
            qb = new Quaternion(-qb.x, -qb.y, -qb.z, -qb.w);
            dot = -dot;
        }

        if (dot > 0.9995) {
            return new Quaternion(
                    MathUtil.lerp(qa.x, qb.x, t),
                    MathUtil.lerp(qa.y, qb.y, t),
                    MathUtil.lerp(qa.z, qb.z, t),
                    MathUtil.lerp(qa.w, qb.w, t)
            ).normalize();
        }

        double theta0 = Math.acos(dot);
        double theta = theta0 * t;
        double sinTheta = Math.sin(theta);
        double sinTheta0 = Math.sin(theta0);

        double s0 = Math.cos(theta) - dot * sinTheta / sinTheta0;
        double s1 = sinTheta / sinTheta0;

        return new Quaternion(
                s0 * qa.x + s1 * qb.x,
                s0 * qa.y + s1 * qb.y,
                s0 * qa.z + s1 * qb.z,
                s0 * qa.w + s1 * qb.w
        );
    }

 // Represents pomocné utility.
    public Vec3 rotateVector(Vec3 v) {
        Quaternion p = new Quaternion(v.x, v.y, v.z, 0.0);
        Quaternion out = multiply(p).multiply(conjugate());
        return new Vec3(out.x, out.y, out.z);
    }

    @Override
    public String toString() {
        return "Quaternion(" + x + ", " + y + ", " + z + ", " + w + ")";
    }
}