package engine.math;

/**
 * Tady držím matici 4x4 uloženou po řádcích.
 * Používám ji jako základní typ pro model, view a projekční transformace.
 */
public class Mat4 {

    private final double[] m = new double[16];

    public Mat4() {
        setIdentity();
    }

    public Mat4(double... values) {
        set(values);
    }

    public final Mat4 setIdentity() {
        return set(
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        );
    }

    public final Mat4 set(double... values) {
        if (values.length != 16) {
            throw new IllegalArgumentException("Mat4 expects 16 values");
        }
        System.arraycopy(values, 0, m, 0, 16);
        return this;
    }

    public final Mat4 set(Mat4 other) {
        if (other == null) {
            return setIdentity();
        }
        System.arraycopy(other.m, 0, m, 0, 16);
        return this;
    }

    // Tady držím aritmetické operace.
    public Mat4 multiply(Mat4 other) {
        return multiply(other, new Mat4());
    }

    public Mat4 multiply(Mat4 other, Mat4 out) {
        if (out == null) {
            out = new Mat4();
        }
        double a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
        double a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
        double a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
        double a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];
        double b00 = other.m[0], b01 = other.m[1], b02 = other.m[2], b03 = other.m[3];
        double b10 = other.m[4], b11 = other.m[5], b12 = other.m[6], b13 = other.m[7];
        double b20 = other.m[8], b21 = other.m[9], b22 = other.m[10], b23 = other.m[11];
        double b30 = other.m[12], b31 = other.m[13], b32 = other.m[14], b33 = other.m[15];
        return out.set(
                a00 * b00 + a01 * b10 + a02 * b20 + a03 * b30,
                a00 * b01 + a01 * b11 + a02 * b21 + a03 * b31,
                a00 * b02 + a01 * b12 + a02 * b22 + a03 * b32,
                a00 * b03 + a01 * b13 + a02 * b23 + a03 * b33,
                a10 * b00 + a11 * b10 + a12 * b20 + a13 * b30,
                a10 * b01 + a11 * b11 + a12 * b21 + a13 * b31,
                a10 * b02 + a11 * b12 + a12 * b22 + a13 * b32,
                a10 * b03 + a11 * b13 + a12 * b23 + a13 * b33,
                a20 * b00 + a21 * b10 + a22 * b20 + a23 * b30,
                a20 * b01 + a21 * b11 + a22 * b21 + a23 * b31,
                a20 * b02 + a21 * b12 + a22 * b22 + a23 * b32,
                a20 * b03 + a21 * b13 + a22 * b23 + a23 * b33,
                a30 * b00 + a31 * b10 + a32 * b20 + a33 * b30,
                a30 * b01 + a31 * b11 + a32 * b21 + a33 * b31,
                a30 * b02 + a31 * b12 + a32 * b22 + a33 * b32,
                a30 * b03 + a31 * b13 + a32 * b23 + a33 * b33
        );
    }

    public Vec4 transform(Vec4 v) {
        return transform(v, new Vec4());
    }

    public Vec4 transform(Vec4 v, Vec4 out) {
        if (out == null) {
            out = new Vec4();
        }
        double x = m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3] * v.w;
        double y = m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7] * v.w;
        double z = m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11] * v.w;
        double w = m[12] * v.x + m[13] * v.y + m[14] * v.z + m[15] * v.w;
        return out.set(x, y, z, w);
    }

    public Vec3 transformPoint(Vec3 p) {
        return transformPoint(p, new Vec3());
    }

    public Vec3 transformPoint(Vec3 p, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        double x = m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3];
        double y = m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7];
        double z = m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11];
        double w = m[12] * p.x + m[13] * p.y + m[14] * p.z + m[15];
        if (Math.abs(w) < MathUtil.EPSILON) {
            return out.zero();
        }
        return out.set(x / w, y / w, z / w);
    }

    public Vec3 transformDirection(Vec3 d) {
        return transformDirection(d, new Vec3());
    }

    public Vec3 transformDirection(Vec3 d, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        return out.set(
                m[0] * d.x + m[1] * d.y + m[2] * d.z,
                m[4] * d.x + m[5] * d.y + m[6] * d.z,
                m[8] * d.x + m[9] * d.y + m[10] * d.z
        );
    }

    public Mat4 transpose() {
        return transpose(new Mat4());
    }

    public Mat4 transpose(Mat4 out) {
        if (out == null) {
            out = new Mat4();
        }
        return out.set(
                m[0], m[4], m[8], m[12],
                m[1], m[5], m[9], m[13],
                m[2], m[6], m[10], m[14],
                m[3], m[7], m[11], m[15]
        );
    }

    public Mat4 inverse() {
        return inverse(new Mat4());
    }

    public Mat4 inverse(Mat4 out) {
        if (out == null) {
            out = new Mat4();
        }
        double[][] a = new double[4][8];
        for (int r = 0; r < 4; r++) {
            for (int c = 0; c < 4; c++) {
                a[r][c] = get(r, c);
            }
            a[r][r + 4] = 1.0;
        }

        for (int col = 0; col < 4; col++) {
            int pivot = col;
            double maxAbs = Math.abs(a[col][col]);
            for (int r = col + 1; r < 4; r++) {
                double abs = Math.abs(a[r][col]);
                if (abs > maxAbs) {
                    maxAbs = abs;
                    pivot = r;
                }
            }
            if (maxAbs < MathUtil.EPSILON) {
                throw new IllegalStateException("Mat4 is singular");
            }
            if (pivot != col) {
                double[] tmp = a[col];
                a[col] = a[pivot];
                a[pivot] = tmp;
            }

            double pivotValue = a[col][col];
            for (int c = 0; c < 8; c++) {
                a[col][c] /= pivotValue;
            }

            for (int r = 0; r < 4; r++) {
                if (r == col) {
                    continue;
                }
                double factor = a[r][col];
                for (int c = 0; c < 8; c++) {
                    a[r][c] -= factor * a[col][c];
                }
            }
        }

        return out.set(
                a[0][4], a[0][5], a[0][6], a[0][7],
                a[1][4], a[1][5], a[1][6], a[1][7],
                a[2][4], a[2][5], a[2][6], a[2][7],
                a[3][4], a[3][5], a[3][6], a[3][7]
        );
    }

    // Tady skládám modelové transformace.
    public static Mat4 identity() {
        return new Mat4();
    }

    public static Mat4 translation(double tx, double ty, double tz) {
        return new Mat4(
                1, 0, 0, tx,
                0, 1, 0, ty,
                0, 0, 1, tz,
                0, 0, 0, 1
        );
    }

    public static Mat4 rotationX(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat4(
                1, 0, 0, 0,
                0, c, -s, 0,
                0, s, c, 0,
                0, 0, 0, 1
        );
    }

    public static Mat4 rotationY(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat4(
                c, 0, s, 0,
                0, 1, 0, 0,
                -s, 0, c, 0,
                0, 0, 0, 1
        );
    }

    public static Mat4 rotationZ(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat4(
                c, -s, 0, 0,
                s, c, 0, 0,
                0, 0, 1, 0,
                0, 0, 0, 1
        );
    }

    public static Mat4 rotation(Vec3 axis, double radians) {
        Vec3 n = axis.normalize();
        double x = n.x;
        double y = n.y;
        double z = n.z;
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        double t = 1.0 - c;
        return new Mat4(
                t * x * x + c, t * x * y - s * z, t * x * z + s * y, 0,
                t * y * x + s * z, t * y * y + c, t * y * z - s * x, 0,
                t * z * x - s * y, t * z * y + s * x, t * z * z + c, 0,
                0, 0, 0, 1
        );
    }

    public static Mat4 scale(double sx, double sy, double sz) {
        return new Mat4(
                sx, 0, 0, 0,
                0, sy, 0, 0,
                0, 0, sz, 0,
                0, 0, 0, 1
        );
    }

    // Tady skládám view a projekční transformace.
    public static Mat4 lookAt(Vec3 eye, Vec3 target, Vec3 up) {
        Vec3 f = target.sub(eye).normalize();
        Vec3 s = f.cross(up).normalize();
        if (s.lengthSquared() < MathUtil.EPSILON) {
            s = new Vec3(1.0, 0.0, 0.0);
        }
        Vec3 u = s.cross(f).normalize();

        return new Mat4(
                s.x, s.y, s.z, -s.dot(eye),
                u.x, u.y, u.z, -u.dot(eye),
                -f.x, -f.y, -f.z, f.dot(eye),
                0, 0, 0, 1
        );
    }

    public static Mat4 perspective(double fovY, double aspect, double near, double far) {
        double f = 1.0 / Math.tan(fovY * 0.5);
        return new Mat4(
                f / aspect, 0, 0, 0,
                0, f, 0, 0,
                0, 0, (far + near) / (near - far), (2.0 * far * near) / (near - far),
                0, 0, -1, 0
        );
    }

    public static Mat4 orthographic(double left, double right, double bottom, double top, double near, double far) {
        return new Mat4(
                2.0 / (right - left), 0, 0, -(right + left) / (right - left),
                0, 2.0 / (top - bottom), 0, -(top + bottom) / (top - bottom),
                0, 0, -2.0 / (far - near), -(far + near) / (far - near),
                0, 0, 0, 1
        );
    }

    public static Mat4 viewport(double width, double height) {
        return new Mat4(
                width * 0.5, 0, 0, (width - 1.0) * 0.5,
                0, -height * 0.5, 0, (height - 1.0) * 0.5,
                0, 0, 0.5, 0.5,
                0, 0, 0, 1
        );
    }

    // Tady držím pomocné utility.
    public Mat3 toMat3() {
        return new Mat3(
                m[0], m[1], m[2],
                m[4], m[5], m[6],
                m[8], m[9], m[10]
        );
    }

    public double get(int row, int col) {
        return m[row * 4 + col];
    }

    @Override
    public String toString() {
        return "Mat4["
                + m[0] + ", " + m[1] + ", " + m[2] + ", " + m[3] + "; "
                + m[4] + ", " + m[5] + ", " + m[6] + ", " + m[7] + "; "
                + m[8] + ", " + m[9] + ", " + m[10] + ", " + m[11] + "; "
                + m[12] + ", " + m[13] + ", " + m[14] + ", " + m[15] + "]";
    }
}
