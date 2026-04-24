package engine.scene;

import engine.math.Vec3;

/**
 * Represents polohové světlo s útlumem podle vzdálenosti.
 */
public class PointLight extends Light {

    private Vec3 position;
    private double constant;
    private double linear;
    private double quadratic;

    public PointLight(Vec3 position, Vec3 color, double intensity) {
        super(color, intensity);
        this.position = position == null ? Vec3.ZERO : position;
        this.constant = 1.0;
        this.linear = 0.09;
        this.quadratic = 0.032;
    }

 // přepisuju chování světla.
    @Override
    public Vec3 directionFrom(Vec3 surfacePoint) {
        return position.sub(surfacePoint).normalize();
    }

    @Override
    public double attenuation(double distance) {
        return 1.0 / (constant + linear * distance + quadratic * distance * distance);
    }

 // Represents přístupové metody.
    public Vec3 getPosition() {
        return position;
    }

    public void setPosition(Vec3 p) {
        this.position = p;
    }

    public void setAttenuation(double constant, double linear, double quadratic) {
        this.constant = constant;
        this.linear = linear;
        this.quadratic = quadratic;
    }

    public double getConstant() {
        return constant;
    }

    public double getLinear() {
        return linear;
    }

    public double getQuadratic() {
        return quadratic;
    }

 /**
 * Represents doplňkový úhlový útlum pro kuželová a plošnému světlu podobná odvození.
 * Základní bodové světlo nechávám všesměrové.
 */
    public double angularAttenuation(double surfaceX, double surfaceY, double surfaceZ) {
        return 1.0;
    }

    public double angularAttenuation(Vec3 surfacePoint) {
        if (surfacePoint == null) {
            return 1.0;
        }
        return angularAttenuation(surfacePoint.x, surfacePoint.y, surfacePoint.z);
    }
}