package engine.material;

import engine.math.Vec3;

/**
 * Tady držím parametry materiálu pro wireframe renderer.
 * Řídím si sem základní barvu, profil vzdálenosti a zvýraznění hran.
 */
public class WireframeMaterial extends Material {

    private double minBrightness;
    private double maxBrightness;
    private double distanceNear;
    private double distanceFar;
    private double silhouetteBoost;
    private boolean dashEnabled;
    private double dashLength;

    public WireframeMaterial(Vec3 baseColor) {
        super(baseColor);
        this.minBrightness = 0.1;
        this.maxBrightness = 1.0;
        this.distanceNear = 1.0;
        this.distanceFar = 15.0;
        this.silhouetteBoost = 1.0;
        this.dashEnabled = false;
        this.dashLength = 8.0;
    }

    // Tady držím přístupové metody.
    public double getMinBrightness() {
        return minBrightness;
    }

    public void setMinBrightness(double v) {
        this.minBrightness = v;
    }

    public double getMaxBrightness() {
        return maxBrightness;
    }

    public void setMaxBrightness(double v) {
        this.maxBrightness = v;
    }

    public void setDistanceRange(double near, double far) {
        this.distanceNear = near;
        this.distanceFar = Math.max(near + 1e-6, far);
    }

    public double getDistanceNear() {
        return distanceNear;
    }

    public double getDistanceFar() {
        return distanceFar;
    }

    public double getSilhouetteBoost() {
        return silhouetteBoost;
    }

    public void setSilhouetteBoost(double v) {
        this.silhouetteBoost = v;
    }

    public boolean isDashEnabled() {
        return dashEnabled;
    }

    public void setDashEnabled(boolean enabled) {
        this.dashEnabled = enabled;
    }

    public double getDashLength() {
        return dashLength;
    }

    public void setDashLength(double len) {
        this.dashLength = Math.max(1.0, len);
    }
}
