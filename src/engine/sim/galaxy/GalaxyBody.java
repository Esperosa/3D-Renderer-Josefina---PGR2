package engine.sim.galaxy;

import engine.math.Vec3;

/**
 * Represents experimentální datový scaffold pro budoucí reprezentaci galaxy tělesa.
 */
public final class GalaxyBody {

    private Vec3 position;
    private Vec3 velocity;
    private double mass;
    private double radius;
    private Vec3 albedo;

    public GalaxyBody() {
        this.position = Vec3.ZERO;
        this.velocity = Vec3.ZERO;
        this.mass = 1.0;
        this.radius = 1.0;
        this.albedo = Vec3.ONE;
    }

    public Vec3 getPosition() {
        return position;
    }

    public void setPosition(Vec3 position) {
        if (position != null) {
            this.position = position;
        }
    }

    public Vec3 getVelocity() {
        return velocity;
    }

    public void setVelocity(Vec3 velocity) {
        if (velocity != null) {
            this.velocity = velocity;
        }
    }

    public double getMass() {
        return mass;
    }

    public void setMass(double mass) {
        this.mass = Math.max(1e-6, mass);
    }

    public double getRadius() {
        return radius;
    }

    public void setRadius(double radius) {
        this.radius = Math.max(0.0, radius);
    }

    public Vec3 getAlbedo() {
        return albedo;
    }

    public void setAlbedo(Vec3 albedo) {
        if (albedo != null) {
            this.albedo = albedo;
        }
    }
}
