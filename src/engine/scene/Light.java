package engine.scene;

import engine.math.Vec3;

/**
 * Tady definuju základní třídu pro světelné zdroje.
 */
public abstract class Light {

    protected Vec3 color;
    protected double intensity;
    protected boolean enabled;

    protected Light(Vec3 color, double intensity) {
        this.color = color == null ? Vec3.ONE : color;
        this.intensity = intensity;
        this.enabled = true;
    }

    // Tady držím přístupové metody.
    public Vec3 getColor() {
        return color;
    }

    public void setColor(Vec3 c) {
        this.color = c;
    }

    public double getIntensity() {
        return intensity;
    }

    public void setIntensity(double i) {
        this.intensity = i;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean e) {
        this.enabled = e;
    }

    /**
     * Tady spočítám směr ke světlu ze zadaného bodu na povrchu.
     *
     * @param surfacePoint sem předám bod na povrchu ve světovém prostoru
     * @return vrátím jednotkový směr ke světlu
     */
    public abstract Vec3 directionFrom(Vec3 surfacePoint);

    /**
     * Tady spočítám útlum pro zadanou vzdálenost.
     *
     * @param distance sem předám vzdálenost od povrchu ke světlu
     * @return vrátím faktor útlumu v rozsahu 0 až 1
     */
    public abstract double attenuation(double distance);
}
