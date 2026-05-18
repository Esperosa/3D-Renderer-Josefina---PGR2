final class Vec2 {
    final double x;
    final double y;

    Vec2(double x, double y) {
        this.x = x;
        this.y = y;
    }

    Vec2 mul(double s) {
        return new Vec2(x * s, y * s);
    }

    Vec2 add(Vec2 o) {
        return new Vec2(x + o.x, y + o.y);
    }
}
