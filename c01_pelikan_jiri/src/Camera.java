final class Camera {
    Vec3 position = new Vec3(0.0, 1.2, -6.5);
    double yaw = 0.0;
    double pitch = -0.08;

    Vec3 toView(Vec3 world) {
        Vec3 p = world.sub(position);
        p = Transform.rotateY(p, -yaw);
        return Transform.rotateX(p, -pitch);
    }

    Vec3 forwardFlat() {
        return new Vec3(Math.sin(yaw), 0.0, Math.cos(yaw)).normalize();
    }

    Vec3 rightFlat() {
        return new Vec3(Math.cos(yaw), 0.0, -Math.sin(yaw)).normalize();
    }
}
