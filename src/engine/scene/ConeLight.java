package engine.scene;

import engine.math.MathUtil;
import engine.math.Vec3;

/**
 * Represents kuželové světlo podobné spot lightu.
 */
public class ConeLight extends PointLight {

    private Vec3 direction;
    private double coneAngleDegrees;
    private double softness;

    public ConeLight(Vec3 position, Vec3 color, double intensity) {
        super(position, color, intensity);
        this.direction = new Vec3(0.0, -1.0, 0.0);
        this.coneAngleDegrees = 38.0;
        this.softness = 0.25;
    }

    public Vec3 getDirection() {
        return direction;
    }

    public void setDirection(Vec3 direction) {
        Vec3 d = direction == null ? new Vec3(0.0, -1.0, 0.0) : direction;
        if (d.lengthSquared() < 1e-12) {
            d = new Vec3(0.0, -1.0, 0.0);
        }
        this.direction = d.normalize();
    }

    public double getConeAngleDegrees() {
        return coneAngleDegrees;
    }

    public void setConeAngleDegrees(double coneAngleDegrees) {
        this.coneAngleDegrees = Math.max(4.0, Math.min(179.0, coneAngleDegrees));
    }

    public double getSoftness() {
        return softness;
    }

    public void setSoftness(double softness) {
        this.softness = MathUtil.clamp01(softness);
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
        Vec3 lightToSurface = toSurface.mul(1.0 / Math.sqrt(lenSq));
        double cos = lightToSurface.dot(direction);

        double outerCutoff = Math.cos(Math.toRadians(coneAngleDegrees * 0.5));
        if (cos <= outerCutoff) {
            return 0.0;
        }
        double innerAngle = coneAngleDegrees * (1.0 - 0.65 * softness);
        double innerCutoff = Math.cos(Math.toRadians(innerAngle * 0.5));
        if (cos >= innerCutoff) {
            return 1.0;
        }
        double t = (cos - outerCutoff) / Math.max(1e-6, innerCutoff - outerCutoff);
        return MathUtil.clamp01(t);
    }
}
