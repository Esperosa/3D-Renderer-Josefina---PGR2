package engine.scene;

import engine.math.Vec3;

/**
 * Represents nekonečně vzdálené světlo s jednotným směrem, třeba jako slunce.
 */
public class DirectionalLight extends Light {

    private Vec3 direction;

    public DirectionalLight(Vec3 direction, Vec3 color, double intensity) {
        super(color, intensity);
        this.direction = direction == null ? new Vec3(0.0, -1.0, 0.0) : direction.normalize();
    }

 // přepisuju chování světla.
    @Override
    public Vec3 directionFrom(Vec3 surfacePoint) {
        return direction.negate();
    }

    @Override
    public double attenuation(double distance) {
        return 1.0;
    }

 // Represents přístupové metody.
    public Vec3 getDirection() {
        return direction;
    }

    public void setDirection(Vec3 d) {
        this.direction = d.normalize();
    }
}