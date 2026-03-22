package engine.math;

/**
 * Represents společné matematické pomocníky pro interpolaci, clamp a převody.
 */
public final class MathUtil {

    public static final double EPSILON = 1e-8;
    public static final double PI = Math.PI;
    public static final double TWO_PI = 2.0 * Math.PI;
    public static final double HALF_PI = 0.5 * Math.PI;
    public static final double DEG_TO_RAD = Math.PI / 180.0;
    public static final double RAD_TO_DEG = 180.0 / Math.PI;

    private MathUtil() {}

 // Handles clamp a wrap operace.
    public static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static double clamp01(double value) {
        return clamp(value, 0.0, 1.0);
    }

    public static double saturate(double value) {
        return clamp01(value);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double inverseLerp(double a, double b, double value) {
        if (Math.abs(b - a) < EPSILON) {
            return 0.0;
        }
        return (value - a) / (b - a);
    }

    public static double smoothstep(double edge0, double edge1, double x) {
        double t = clamp01(inverseLerp(edge0, edge1, x));
        return t * t * (3.0 - 2.0 * t);
    }

 // Represents trigonometrické zkratky.
    public static double toRadians(double degrees) {
        return degrees * DEG_TO_RAD;
    }

    public static double toDegrees(double radians) {
        return radians * RAD_TO_DEG;
    }

 // Computes barycentrické hodnoty.
    public static Vec3 barycentric(Vec2 p, Vec2 a, Vec2 b, Vec2 c) {
        double denom = (b.y - c.y) * (a.x - c.x) + (c.x - b.x) * (a.y - c.y);
        if (Math.abs(denom) < EPSILON) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        double u = ((b.y - c.y) * (p.x - c.x) + (c.x - b.x) * (p.y - c.y)) / denom;
        double v = ((c.y - a.y) * (p.x - c.x) + (a.x - c.x) * (p.y - c.y)) / denom;
        double w = 1.0 - u - v;
        return new Vec3(u, v, w);
    }

    public static double barycentricInterpolate(double a, double b, double c, Vec3 bary) {
        return a * bary.x + b * bary.y + c * bary.z;
    }

 // Handles perspektivně správnou interpolaci.
    public static double perspectiveCorrectInterpolate(
            double attr0, double attr1, double attr2,
            double w0, double w1, double w2,
            Vec3 bary) {
        double d = bary.x * w0 + bary.y * w1 + bary.z * w2;
        if (Math.abs(d) < EPSILON) {
            return 0.0;
        }
        double n = bary.x * attr0 * w0 + bary.y * attr1 * w1 + bary.z * attr2 * w2;
        return n / d;
    }

 // Converts mezi prostory přes Mat4.
    public static Vec3 worldToView(Vec3 worldPos, Mat4 viewMatrix) {
        return viewMatrix.transformPoint(worldPos);
    }

    public static Vec4 viewToClip(Vec3 viewPos, Mat4 projMatrix) {
        return projMatrix.transform(new Vec4(viewPos, 1.0));
    }

    public static Vec3 clipToNDC(Vec4 clipPos) {
        return clipPos.perspectiveDivide();
    }

    public static Vec2 ndcToScreen(Vec3 ndc, double width, double height) {
        double x = (ndc.x * 0.5 + 0.5) * (width - 1.0);
        double y = (1.0 - (ndc.y * 0.5 + 0.5)) * (height - 1.0);
        return new Vec2(x, y);
    }

 // Represents jednoduché náhodné pomocníky.
    public static double randomDouble() {
        return Math.random();
    }

    public static double randomInRange(double min, double max) {
        return min + (max - min) * Math.random();
    }
}