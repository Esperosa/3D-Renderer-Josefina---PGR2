import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.PhongMaterial;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * vypisuje praktické materiálové a UV schopnosti nalezené v importovaných modelech.
 * Spustím to takto:
 * java -cp <classpath> ExternalMaterialProbe <file1> [file2 ...]
 */
public final class ExternalMaterialProbe {

    private ExternalMaterialProbe() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("ExternalMaterialProbe: provide file path(s).");
            return;
        }

        ModelImporter importer = new ModelImporter();
        for (String path : args) {
            ImportedScene scene = importer.importScene(path);
            int entries = scene.size();
            int triangles = 0;
            int withUv = 0;
            int withUv2 = 0;
            int withTexture = 0;
            int withNormalTexture = 0;
            int withMetallicRoughness = 0;
            int withEmissiveTexture = 0;
            int transmissive = 0;
            int emissive = 0;
            int alphaBlend = 0;
            int doubleSided = 0;
            int usesUv1Maps = 0;
            int usesTextureTransform = 0;
            Set<String> names = new LinkedHashSet<>();

            for (ImportedScene.Entry entry : scene.getEntries()) {
                if (entry == null || entry.getMesh() == null) {
                    continue;
                }
                triangles += entry.getMesh().getTriangleCount();
                if (entry.getMesh().getUVs() != null) {
                    withUv++;
                }
                if (entry.getMesh().getUV2s() != null) {
                    withUv2++;
                }
                PhongMaterial mat = entry.getMaterial();
                if (mat != null) {
                    names.add(mat.getName());
                    if (mat.getDiffuseTexture() != null) {
                        withTexture++;
                    }
                    if (mat.hasNormalTexture()) {
                        withNormalTexture++;
                    }
                    if (mat.hasMetallicRoughnessTexture()) {
                        withMetallicRoughness++;
                    }
                    if (mat.hasEmissiveTexture()) {
                        withEmissiveTexture++;
                    }
                    if (mat.getTransmission() > 0.01 || mat.getAlphaMode() == PhongMaterial.AlphaMode.BLEND) {
                        transmissive++;
                    }
                    if (mat.getEmissionStrength() > 1e-5) {
                        emissive++;
                    }
                    if (mat.getAlphaMode() == PhongMaterial.AlphaMode.BLEND) {
                        alphaBlend++;
                    }
                    if (mat.isDoubleSided()) {
                        doubleSided++;
                    }
                    if (mat.getDiffuseMap().getTexCoord() > 0
                            || mat.getNormalMap().getTexCoord() > 0
                            || mat.getMetallicRoughnessMap().getTexCoord() > 0
                            || mat.getEmissiveMap().getTexCoord() > 0) {
                        usesUv1Maps++;
                    }
                    if (hasTransform(mat.getDiffuseMap())
                            || hasTransform(mat.getNormalMap())
                            || hasTransform(mat.getMetallicRoughnessMap())
                            || hasTransform(mat.getEmissiveMap())) {
                        usesTextureTransform++;
                    }
                }
            }

            System.out.println("=== Material Probe: " + path + " ===");
            System.out.println("entries=" + entries + ", triangles=" + triangles);
            System.out.println("entriesWithUV=" + withUv + "/" + entries);
            System.out.println("entriesWithUV2=" + withUv2 + "/" + entries);
            System.out.println("entriesWithMaterialTexture=" + withTexture + "/" + entries);
            System.out.println("entriesWithNormalTexture=" + withNormalTexture + "/" + entries);
            System.out.println("entriesWithMetallicRoughnessTexture=" + withMetallicRoughness + "/" + entries);
            System.out.println("entriesWithEmissiveTexture=" + withEmissiveTexture + "/" + entries);
            System.out.println("entriesTransmissive=" + transmissive + "/" + entries);
            System.out.println("entriesEmissive=" + emissive + "/" + entries);
            System.out.println("entriesAlphaBlend=" + alphaBlend + "/" + entries);
            System.out.println("entriesDoubleSided=" + doubleSided + "/" + entries);
            System.out.println("entriesUsingUV1TextureMaps=" + usesUv1Maps + "/" + entries);
            System.out.println("entriesUsingTextureTransforms=" + usesTextureTransform + "/" + entries);
            System.out.println("materialNames=" + names.size() + " -> " + names);

            int preview = 0;
            for (ImportedScene.Entry entry : scene.getEntries()) {
                if (entry == null || entry.getMaterial() == null) {
                    continue;
                }
                PhongMaterial m = entry.getMaterial();
                System.out.println("  - " + entry.getName()
                        + " | mat=" + m.getName()
                        + " | diffuse=" + fmtVec(m.getDiffuseColor())
                        + " | spec=" + fmtVec(m.getSpecularColor())
                        + " | shininess=" + fmt(m.getShininess())
                        + " | refl=" + fmt(m.getReflectivity())
                        + " | opacity=" + fmt(m.getOpacity())
                        + " | transmission=" + fmt(m.getTransmission())
                        + " | alphaMode=" + m.getAlphaMode()
                        + " | uvBase=" + m.getDiffuseMap().getTexCoord()
                        + " | uvNormal=" + m.getNormalMap().getTexCoord()
                        + " | tex=" + (m.getDiffuseTexture() != null
                        ? (m.getDiffuseTexture().getWidth() + "x" + m.getDiffuseTexture().getHeight())
                        : "none"));
                preview++;
                if (preview >= 8) {
                    break;
                }
            }
        }
    }

    private static String fmt(double v) {
        return String.format("%.4f", v);
    }

    private static String fmtVec(engine.math.Vec3 v) {
        if (v == null) {
            return "(null)";
        }
        return "(" + fmt(v.x) + ", " + fmt(v.y) + ", " + fmt(v.z) + ")";
    }

    private static boolean hasTransform(engine.material.TextureMap map) {
        if (map == null) {
            return false;
        }
        return Math.abs(map.getOffsetU()) > 1e-9
                || Math.abs(map.getOffsetV()) > 1e-9
                || Math.abs(map.getScaleU() - 1.0) > 1e-9
                || Math.abs(map.getScaleV() - 1.0) > 1e-9
                || Math.abs(map.getRotation()) > 1e-9;
    }
}