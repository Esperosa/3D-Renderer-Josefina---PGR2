final class Transform {
    Vec3 position = Vec3.ZERO;
    Vec3 rotation = Vec3.ZERO;
    double scale = 1.0;

    Vec3 applyPoint(Vec3 p) {
        return rotate(p.mul(scale)).add(position);
    }

    Vec3 applyDirection(Vec3 p) {
        return rotate(p).normalize();
    }

    private Vec3 rotate(Vec3 p) {
        Vec3 out = rotateX(p, rotation.x);
        out = rotateY(out, rotation.y);
        return rotateZ(out, rotation.z);
    }

    static Vec3 rotateX(Vec3 p, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c);
    }

    static Vec3 rotateY(Vec3 p, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c);
    }

    static Vec3 rotateZ(Vec3 p, double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z);
    }
}
