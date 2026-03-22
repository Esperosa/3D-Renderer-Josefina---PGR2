package engine.material;

import engine.math.Vec3;

/**
 * Represents základní materiál se společnými vlastnostmi pro různé způsoby vykreslení.
 */
public class Material {

    public enum Domain {
        SURFACE,
        VOLUME,
        PARTICLE,
        CELESTIAL
    }

    public enum ShadingModel {
        PHONG,
        TRANSMISSIVE,
        VOLUMETRIC,
        PARTICLE_FLUID,
        EMISSIVE
    }

    private String name;
    private String presetName;
    private Vec3 baseColor;
    private double opacity;
    private Domain domain;
    private ShadingModel shadingModel;
    private double roughness;
    private double metallic;
    private double transmission;
    private double dispersion;
    private Vec3 emissionColor;
    private double emissionStrength;
    private Vec3 mediumColor;
    private double density;
    private double anisotropy;
    private double thickness;
    private boolean doubleSided;

    public Material() {
        this(new Vec3(0.7, 0.7, 0.7));
    }

    public Material(Vec3 baseColor) {
        this.name = "Material";
        this.presetName = "custom";
        this.baseColor = baseColor == null ? new Vec3(0.7, 0.7, 0.7) : baseColor;
        this.opacity = 1.0;
        this.domain = Domain.SURFACE;
        this.shadingModel = ShadingModel.PHONG;
        this.roughness = 0.55;
        this.metallic = 0.0;
        this.transmission = 0.0;
        this.dispersion = 0.0;
        this.emissionColor = Vec3.ZERO;
        this.emissionStrength = 0.0;
        this.mediumColor = new Vec3(0.85, 0.9, 1.0);
        this.density = 0.0;
        this.anisotropy = 0.0;
        this.thickness = 0.1;
        this.doubleSided = false;
    }

 // Represents přístupové metody.
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = (name == null || name.isBlank()) ? "Material" : name;
    }

    public String getPresetName() {
        return presetName;
    }

    public void setPresetName(String presetName) {
        if (presetName == null || presetName.isBlank()) {
            this.presetName = "custom";
            return;
        }
        this.presetName = presetName.trim();
    }

    public Vec3 getBaseColor() {
        return baseColor;
    }

    public void setBaseColor(Vec3 color) {
        if (color != null) {
            this.baseColor = color;
        }
    }

    public double getOpacity() {
        return opacity;
    }

    public void setOpacity(double o) {
        this.opacity = Math.max(0.0, Math.min(1.0, o));
    }

    public Domain getDomain() {
        return domain;
    }

    public void setDomain(Domain domain) {
        this.domain = domain == null ? Domain.SURFACE : domain;
    }

    public ShadingModel getShadingModel() {
        return shadingModel;
    }

    public void setShadingModel(ShadingModel shadingModel) {
        this.shadingModel = shadingModel == null ? ShadingModel.PHONG : shadingModel;
    }

    public double getRoughness() {
        return roughness;
    }

    public void setRoughness(double roughness) {
        this.roughness = Math.max(0.0, Math.min(1.0, roughness));
    }

    public double getMetallic() {
        return metallic;
    }

    public void setMetallic(double metallic) {
        this.metallic = Math.max(0.0, Math.min(1.0, metallic));
    }

    public double getTransmission() {
        return transmission;
    }

    public void setTransmission(double transmission) {
        this.transmission = Math.max(0.0, Math.min(1.0, transmission));
    }

    public double getDispersion() {
        return dispersion;
    }

    public void setDispersion(double dispersion) {
        this.dispersion = Math.max(0.0, Math.min(1.0, dispersion));
    }

    public Vec3 getEmissionColor() {
        return emissionColor;
    }

    public void setEmissionColor(Vec3 emissionColor) {
        if (emissionColor != null) {
            this.emissionColor = new Vec3(
                    clamp01(emissionColor.x),
                    clamp01(emissionColor.y),
                    clamp01(emissionColor.z)
            );
        }
    }

    public double getEmissionStrength() {
        return emissionStrength;
    }

    public void setEmissionStrength(double emissionStrength) {
        this.emissionStrength = Math.max(0.0, emissionStrength);
    }

    public Vec3 getMediumColor() {
        return mediumColor;
    }

    public void setMediumColor(Vec3 mediumColor) {
        if (mediumColor != null) {
            this.mediumColor = new Vec3(
                    clamp01(mediumColor.x),
                    clamp01(mediumColor.y),
                    clamp01(mediumColor.z)
            );
        }
    }

    public double getDensity() {
        return density;
    }

    public void setDensity(double density) {
        this.density = Math.max(0.0, density);
    }

    public double getAnisotropy() {
        return anisotropy;
    }

    public void setAnisotropy(double anisotropy) {
        this.anisotropy = Math.max(-0.99, Math.min(0.99, anisotropy));
    }

    public double getThickness() {
        return thickness;
    }

    public void setThickness(double thickness) {
        this.thickness = Math.max(0.0, thickness);
    }

    public boolean isDoubleSided() {
        return doubleSided;
    }

    public void setDoubleSided(boolean doubleSided) {
        this.doubleSided = doubleSided;
    }

    public void copyFrom(Material source) {
        if (source == null) {
            return;
        }
        setName(source.getName());
        setPresetName(source.getPresetName());
        setBaseColor(source.getBaseColor());
        setOpacity(source.getOpacity());
        setDomain(source.getDomain());
        setShadingModel(source.getShadingModel());
        setRoughness(source.getRoughness());
        setMetallic(source.getMetallic());
        setTransmission(source.getTransmission());
        setDispersion(source.getDispersion());
        setEmissionColor(source.getEmissionColor());
        setEmissionStrength(source.getEmissionStrength());
        setMediumColor(source.getMediumColor());
        setDensity(source.getDensity());
        setAnisotropy(source.getAnisotropy());
        setThickness(source.getThickness());
        setDoubleSided(source.isDoubleSided());
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}