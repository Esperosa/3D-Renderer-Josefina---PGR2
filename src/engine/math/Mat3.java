package engine.math;

/**
 * Tady držím matici 3x3 uloženou po řádcích.
 * Používám ji pro transformace normál a menší 2D operace.
 */
public class Mat3 {

    private final double[] m = new double[9];

    public Mat3() {
        this(
                1, 0, 0,
                0, 1, 0,
                0, 0, 1
        );
    }

    public Mat3(double... values) {
        if (values.length != 9) {
            throw new IllegalArgumentException("Mat3 expects 9 values");
        }
        System.arraycopy(values, 0, m, 0, 9);
    }

    public Mat3 multiply(Mat3 other) {
        double[] r = new double[9];
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                double sum = 0.0;
                for (int k = 0; k < 3; k++) {
                    sum += get(row, k) * other.get(k, col);
                }
                r[row * 3 + col] = sum;
            }
        }
        return new Mat3(r);
    }

    public Vec3 transform(Vec3 v) {
        return transform(v, new Vec3());
    }

    public Vec3 transform(Vec3 v, Vec3 out) {
        if (out == null) {
            out = new Vec3();
        }
        double x = m[0] * v.x + m[1] * v.y + m[2] * v.z;
        double y = m[3] * v.x + m[4] * v.y + m[5] * v.z;
        double z = m[6] * v.x + m[7] * v.y + m[8] * v.z;
        return out.set(x, y, z);
    }

    public Mat3 transpose() {
        return new Mat3(
                m[0], m[3], m[6],
                m[1], m[4], m[7],
                m[2], m[5], m[8]
        );
    }

    public double determinant() {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
                - m[1] * (m[3] * m[8] - m[5] * m[6])
                + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    public Mat3 inverse() {
        double det = determinant();
        if (Math.abs(det) < MathUtil.EPSILON) {
            throw new IllegalStateException("Mat3 is singular");
        }
        double invDet = 1.0 / det;
        return new Mat3(
                (m[4] * m[8] - m[5] * m[7]) * invDet,
                (m[2] * m[7] - m[1] * m[8]) * invDet,
                (m[1] * m[5] - m[2] * m[4]) * invDet,
                (m[5] * m[6] - m[3] * m[8]) * invDet,
                (m[0] * m[8] - m[2] * m[6]) * invDet,
                (m[2] * m[3] - m[0] * m[5]) * invDet,
                (m[3] * m[7] - m[4] * m[6]) * invDet,
                (m[1] * m[6] - m[0] * m[7]) * invDet,
                (m[0] * m[4] - m[1] * m[3]) * invDet
        );
    }

    public static Mat3 identity() {
        return new Mat3();
    }

    public static Mat3 rotationX(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat3(
                1, 0, 0,
                0, c, -s,
                0, s, c
        );
    }

    public static Mat3 rotationY(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat3(
                c, 0, s,
                0, 1, 0,
                -s, 0, c
        );
    }

    public static Mat3 rotationZ(double radians) {
        double c = Math.cos(radians);
        double s = Math.sin(radians);
        return new Mat3(
                c, -s, 0,
                s, c, 0,
                0, 0, 1
        );
    }

    public static Mat3 scale(double sx, double sy, double sz) {
        return new Mat3(
                sx, 0, 0,
                0, sy, 0,
                0, 0, sz
        );
    }

    public double get(int row, int col) {
        return m[row * 3 + col];
    }

    @Override
    public String toString() {
        return "Mat3["
                + m[0] + ", " + m[1] + ", " + m[2] + "; "
                + m[3] + ", " + m[4] + ", " + m[5] + "; "
                + m[6] + ", " + m[7] + ", " + m[8] + "]";
    }
}
