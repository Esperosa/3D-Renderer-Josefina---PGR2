package engine.sim.water;

import engine.math.Vec3;
import engine.scene.Entity;

/**
 * Represents runtime konfiguraci jednoho spray/splash emitoru navázaného na scénovou entitu.
 */
public final class WaterEmitter {

    private final Entity entity;
    private boolean enabled;
    private double emitRate;
    private double initialSpeed;
    private double spreadAngleDegrees;
    private double particleLifetime;
    private double particleRadius;
    private double drag;
    private double bounce;
    private double gravityScale;
    private double surfaceDamping;
    private double jitter;
    private double opacity;
    private Vec3 tint;
    double spawnAccumulator;

    public WaterEmitter(Entity entity) {
        this.entity = entity;
        this.enabled = true;
        this.emitRate = 165.0;
        this.initialSpeed = 4.8;
        this.spreadAngleDegrees = 14.0;
        this.particleLifetime = 2.3;
        this.particleRadius = 0.055;
        this.drag = 0.12;
        this.bounce = 0.24;
        this.gravityScale = 1.0;
        this.surfaceDamping = 0.86;
        this.jitter = 0.06;
        this.opacity = 0.58;
        this.tint = new Vec3(0.26, 0.54, 0.90);
        this.spawnAccumulator = 0.0;
    }

    public Entity getEntity() {
        return entity;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getEmitRate() {
        return emitRate;
    }

    public void setEmitRate(double emitRate) {
        this.emitRate = Math.max(0.0, emitRate);
    }

    public double getInitialSpeed() {
        return initialSpeed;
    }

    public void setInitialSpeed(double initialSpeed) {
        this.initialSpeed = Math.max(0.0, initialSpeed);
    }

    public double getSpreadAngleDegrees() {
        return spreadAngleDegrees;
    }

    public void setSpreadAngleDegrees(double spreadAngleDegrees) {
        this.spreadAngleDegrees = Math.max(0.0, Math.min(88.0, spreadAngleDegrees));
    }

    public double getParticleLifetime() {
        return particleLifetime;
    }

    public void setParticleLifetime(double particleLifetime) {
        this.particleLifetime = Math.max(0.15, particleLifetime);
    }

    public double getParticleRadius() {
        return particleRadius;
    }

    public void setParticleRadius(double particleRadius) {
        this.particleRadius = Math.max(0.004, particleRadius);
    }

    public double getDrag() {
        return drag;
    }

    public void setDrag(double drag) {
        this.drag = Math.max(0.0, drag);
    }

    public double getBounce() {
        return bounce;
    }

    public void setBounce(double bounce) {
        this.bounce = Math.max(0.0, Math.min(1.0, bounce));
    }

    public double getGravityScale() {
        return gravityScale;
    }

    public void setGravityScale(double gravityScale) {
        this.gravityScale = Math.max(0.0, Math.min(4.0, gravityScale));
    }

    public double getSurfaceDamping() {
        return surfaceDamping;
    }

    public void setSurfaceDamping(double surfaceDamping) {
        this.surfaceDamping = Math.max(0.0, Math.min(1.0, surfaceDamping));
    }

    public double getJitter() {
        return jitter;
    }

    public void setJitter(double jitter) {
        this.jitter = Math.max(0.0, Math.min(1.0, jitter));
    }

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double opacity) {
        this.opacity = Math.max(0.02, Math.min(1.0, opacity));
    }

    public Vec3 getTint() {
        return tint;
    }

    public void setTint(Vec3 tint) {
        if (tint == null) {
            return;
        }
        this.tint = new Vec3(
                clamp01(tint.x),
                clamp01(tint.y),
                clamp01(tint.z)
        );
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
