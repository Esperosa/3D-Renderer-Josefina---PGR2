package engine.material;

import engine.math.Vec3;

/**
 * Represents kurátorovanou knihovnu presetů pro současný hybridní Phong a fyzikální materiálový postup.
 * Udržuju tím materiály použitelné v dnešních rendererech a zároveň si nechávám data pro budoucí
 * rozšíření kolem vody, skla, mlhy a node editoru.
 */
public final class MaterialPresets {

    public enum Preset {
        CUSTOM("custom", "Custom"),
        PRINCIPLED("principled", "Principled BSDF"),
        MATTE("matte", "Matte"),
        GLOSSY("glossy", "Glossy"),
        METALLIC("metallic", "Metallic"),
        WATER("water", "Water"),
        GLASS("glass", "Glass"),
        FOG("fog", "Fog"),
        EMISSIVE("emissive", "Emissive");

        private final String id;
        private final String displayName;

        Preset(String id, String displayName) {
            this.id = id;
            this.displayName = displayName;
        }

        public String id() {
            return id;
        }

        public String displayName() {
            return displayName;
        }
    }

    private MaterialPresets() {
    }

    public static String[] presetNames() {
        Preset[] values = Preset.values();
        String[] names = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            names[i] = values[i].displayName();
        }
        return names;
    }

    public static String displayNameForId(String presetId) {
        return fromText(presetId).displayName();
    }

    public static Preset fromText(String raw) {
        if (raw == null || raw.isBlank()) {
            return Preset.CUSTOM;
        }
        String normalized = raw.trim();
        for (Preset preset : Preset.values()) {
            if (preset.id.equalsIgnoreCase(normalized) || preset.displayName.equalsIgnoreCase(normalized)) {
                return preset;
            }
        }
        return Preset.CUSTOM;
    }

    public static void apply(String rawPreset, PhongMaterial material) {
        apply(fromText(rawPreset), material);
    }

    public static void apply(Preset preset, PhongMaterial material) {
        if (material == null || preset == null) {
            return;
        }
        switch (preset) {
            case PRINCIPLED:
                applyPrincipled(material);
                break;
            case MATTE:
                applyMatte(material);
                break;
            case GLOSSY:
                applyGlossy(material);
                break;
            case METALLIC:
                applyMetallic(material);
                break;
            case WATER:
                applyWater(material);
                break;
            case GLASS:
                applyGlass(material);
                break;
            case FOG:
                applyFog(material);
                break;
            case EMISSIVE:
                applyEmissive(material);
                break;
            case CUSTOM:
            default:
                material.setPresetName(Preset.CUSTOM.id());
                MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
                return;
        }
        material.setPresetName(preset.id());
        MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
    }

    private static void applyPrincipled(PhongMaterial material) {
        Vec3 diffuse = material.getDiffuseColor();
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.PHONG);
        material.setAmbientColor(diffuse.mul(0.08));
        material.setSpecularColor(new Vec3(0.55, 0.55, 0.55));
        material.setShininess(PhongMaterial.shininessFromRoughness(0.45));
        material.setReflectivity(0.10);
        material.setRefractiveIndex(1.45);
        material.setTransmission(0.0);
        material.setOpacity(1.0);
        material.setMetallic(0.0);
        material.setRoughness(0.45);
        material.setDensity(0.0);
        material.setAnisotropy(0.0);
        material.setThickness(0.1);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(diffuse);
        material.setClearcoatFactor(0.0);
        material.setClearcoatRoughness(0.0);
        material.setSpecularFactor(1.0);
        material.setSpecularColorFactor(Vec3.ONE);
        material.setSheenColor(Vec3.ZERO);
        material.setSheenRoughness(0.0);
        material.setAlphaMode(PhongMaterial.AlphaMode.OPAQUE);
        material.setAlphaCutoff(0.5);
        material.setDoubleSided(false);
    }

    private static void applyMatte(PhongMaterial material) {
        Vec3 diffuse = material.getDiffuseColor();
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.PHONG);
        material.setAmbientColor(diffuse.mul(0.10));
        material.setSpecularColor(new Vec3(0.15, 0.15, 0.15));
        material.setShininess(12.0);
        material.setReflectivity(0.0);
        material.setTransmission(0.0);
        material.setOpacity(1.0);
        material.setMetallic(0.0);
        material.setRoughness(0.78);
        material.setDensity(0.0);
        material.setAnisotropy(0.0);
        material.setThickness(0.1);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(diffuse);
        material.setDoubleSided(false);
    }

    private static void applyGlossy(PhongMaterial material) {
        Vec3 diffuse = material.getDiffuseColor();
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.PHONG);
        material.setAmbientColor(diffuse.mul(0.08));
        material.setSpecularColor(new Vec3(0.85, 0.85, 0.85));
        material.setShininess(128.0);
        material.setReflectivity(0.28);
        material.setTransmission(0.0);
        material.setOpacity(1.0);
        material.setMetallic(0.0);
        material.setRoughness(0.16);
        material.setDensity(0.0);
        material.setAnisotropy(0.0);
        material.setThickness(0.1);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(diffuse);
        material.setDoubleSided(false);
    }

    private static void applyMetallic(PhongMaterial material) {
        Vec3 diffuse = material.getDiffuseColor();
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.PHONG);
        material.setAmbientColor(diffuse.mul(0.06));
        material.setSpecularColor(new Vec3(0.95, 0.95, 0.95));
        material.setShininess(220.0);
        material.setReflectivity(0.62);
        material.setTransmission(0.0);
        material.setOpacity(1.0);
        material.setMetallic(0.92);
        material.setRoughness(0.18);
        material.setDensity(0.0);
        material.setAnisotropy(0.0);
        material.setThickness(0.2);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(diffuse);
        material.setDoubleSided(false);
    }

    private static void applyWater(PhongMaterial material) {
        Vec3 tint = new Vec3(0.20, 0.56, 0.88);
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.TRANSMISSIVE);
        material.setDiffuseColor(tint);
        material.setAmbientColor(tint.mul(0.06));
        material.setSpecularColor(Vec3.ONE);
        material.setShininess(360.0);
        material.setReflectivity(0.34);
        material.setRefractiveIndex(1.333);
        material.setTransmission(0.94);
        material.setOpacity(0.24);
        material.setMetallic(0.0);
        material.setRoughness(0.03);
        material.setDensity(0.18);
        material.setAnisotropy(0.08);
        material.setThickness(0.65);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(new Vec3(0.48, 0.78, 1.0));
        material.setDoubleSided(false);
    }

    private static void applyGlass(PhongMaterial material) {
        Vec3 tint = new Vec3(0.94, 0.98, 1.0);
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.TRANSMISSIVE);
        material.setDiffuseColor(tint);
        material.setAmbientColor(tint.mul(0.03));
        material.setSpecularColor(Vec3.ONE);
        material.setShininess(420.0);
        material.setReflectivity(0.20);
        material.setRefractiveIndex(1.52);
        material.setTransmission(0.97);
        material.setOpacity(0.14);
        material.setMetallic(0.0);
        material.setRoughness(0.02);
        material.setDensity(0.05);
        material.setAnisotropy(0.0);
        material.setThickness(0.45);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(new Vec3(0.96, 0.98, 1.0));
        material.setDoubleSided(false);
    }

    private static void applyFog(PhongMaterial material) {
        Vec3 tint = new Vec3(0.74, 0.82, 0.90);
        material.setDomain(Material.Domain.VOLUME);
        material.setShadingModel(Material.ShadingModel.VOLUMETRIC);
        material.setDiffuseColor(tint);
        material.setAmbientColor(tint.mul(0.16));
        material.setSpecularColor(Vec3.ZERO);
        material.setShininess(4.0);
        material.setReflectivity(0.0);
        material.setRefractiveIndex(1.0);
        material.setTransmission(0.62);
        material.setOpacity(0.38);
        material.setMetallic(0.0);
        material.setRoughness(1.0);
        material.setDensity(0.70);
        material.setAnisotropy(0.18);
        material.setThickness(2.4);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);
        material.setMediumColor(tint);
        material.setDoubleSided(true);
    }

    private static void applyEmissive(PhongMaterial material) {
        Vec3 tint = material.getDiffuseColor();
        material.setDomain(Material.Domain.SURFACE);
        material.setShadingModel(Material.ShadingModel.EMISSIVE);
        material.setAmbientColor(tint.mul(0.12));
        material.setSpecularColor(new Vec3(0.08, 0.08, 0.08));
        material.setShininess(6.0);
        material.setReflectivity(0.0);
        material.setTransmission(0.0);
        material.setOpacity(1.0);
        material.setMetallic(0.0);
        material.setRoughness(0.92);
        material.setDensity(0.0);
        material.setAnisotropy(0.0);
        material.setThickness(0.1);
        material.setEmissionColor(tint);
        material.setEmissionStrength(2.2);
        material.setMediumColor(tint);
        material.setDoubleSided(false);
    }
}
