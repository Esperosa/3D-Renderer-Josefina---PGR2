package engine.scene;

import engine.math.MathUtil;
import engine.math.Vec3;

/**
 * Represents jednoduché plošnému světlu podobné světlo vhodné pro software renderer.
 * Implementuju ho jako poziční světlo se směrovým a úhlovým útlumem.
 */
public class AreaLight extends PointLight {

    private Vec3 emissionDirection;
    private double spreadAngleDegrees;
    private double surfaceRadius;
    private int shadowSamples;

    public AreaLight(Vec3 position, Vec3 color, double intensity) {
        super(position, color, intensity);
        this.emissionDirection = new Vec3(0.0, -1.0, 0.0);
        this.spreadAngleDegrees = 120.0;
        this.surfaceRadius = 0.45;
        this.shadowSamples = 10;
    }

    public Vec3 getEmissionDirection() {
        return emissionDirection;
    }

    public void setEmissionDirection(Vec3 direction) {
        Vec3 d = direction == null ? new Vec3(0.0, -1.0, 0.0) : direction;
        if (d.lengthSquared() < 1e-12) {
            d = new Vec3(0.0, -1.0, 0.0);
        }
        this.emissionDirection = d.normalize();
    }

    public double getSpreadAngleDegrees() {
        return spreadAngleDegrees;
    }

    public void setSpreadAngleDegrees(double spreadAngleDegrees) {
        this.spreadAngleDegrees = Math.max(10.0, Math.min(179.0, spreadAngleDegrees));
    }

    public double getSurfaceRadius() {
        return surfaceRadius;
    }

    public void setSurfaceRadius(double surfaceRadius) {
        this.surfaceRadius = Math.max(0.01, Math.min(20.0, surfaceRadius));
    }

    public int getShadowSamples() {
        return shadowSamples;
    }

    public void setShadowSamples(int shadowSamples) {
        this.shadowSamples = Math.max(1, Math.min(64, shadowSamples));
    }

    @Override
    public double angularAttenuation(double surfaceX, double surfaceY, double surfaceZ) {
        Vec3 toSurface = new Vec3(
                surfaceX - getPosition().x,
                surfaceY - getPosition().y,
                surfaceZ - getPosition().z
        );
        double lenSq = toSurface.lengthSquared();
        if (lenSq < 1e-12) {
            return 1.0;
        }
        Vec3 dir = toSurface.mul(1.0 / Math.sqrt(lenSq));
        double cos = dir.dot(emissionDirection);
        double cutoff = Math.cos(Math.toRadians(spreadAngleDegrees * 0.5));
        if (cos <= cutoff) {
            return 0.0;
        }
        return MathUtil.clamp01((cos - cutoff) / Math.max(1e-6, 1.0 - cutoff));
    }
}
