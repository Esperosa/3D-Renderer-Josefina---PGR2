package engine.math;

/**
 * Represents neměnný 2D vektor pro UV souřadnice a obrazové pozice.
 */
public class Vec2 {

    public final double x, y;

    public Vec2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public Vec2 add(Vec2 v) {
        return new Vec2(x + v.x, y + v.y);
    }

    public Vec2 sub(Vec2 v) {
        return new Vec2(x - v.x, y - v.y);
    }

    public Vec2 mul(double s) {
        return new Vec2(x * s, y * s);
    }

    public double dot(Vec2 v) {
        return x * v.x + y * v.y;
    }

    public double length() {
        return Math.sqrt(dot(this));
    }

    public Vec2 normalize() {
        double len = length();
        if (len < MathUtil.EPSILON) {
            return new Vec2(0.0, 0.0);
        }
        return new Vec2(x / len, y / len);
    }

    public static Vec2 lerp(Vec2 a, Vec2 b, double t) {
        return new Vec2(
                MathUtil.lerp(a.x, b.x, t),
                MathUtil.lerp(a.y, b.y, t)
        );
    }

    @Override
    public String toString() {
        return "Vec2(" + x + ", " + y + ")";
    }
}
