package engine.tools;

import engine.io.FileUtil;
import engine.io.SimpleJsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tady audituju surové materiálové vlastnosti glTF a GLB před převodem načítačem.
 * Spustím to takto:
 *   java -cp <classpath> engine.tools.GltfFeatureAudit <file.glb>
 */
public final class GltfFeatureAudit {

    private static final int GLB_MAGIC = 0x46546C67;
    private static final int GLB_CHUNK_JSON = 0x4E4F534A;

    private GltfFeatureAudit() {
    }

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("GltfFeatureAudit: provide .gltf/.glb path.");
            return;
        }
        Path path = Path.of(args[0]).toAbsolutePath().normalize();
        String ext = extension(path.getFileName().toString());
        byte[] bytes = FileUtil.readBytes(path.toString());
        String json;
        if ("glb".equals(ext)) {
            json = extractGlbJson(bytes, path.toString());
        } else if ("gltf".equals(ext)) {
            json = new String(bytes, StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Unsupported input: " + path);
        }

        Map<String, Object> root = asObject(new SimpleJsonParser(json).parse());
        List<Map<String, Object>> materials = asObjectList(root.get("materials"));
        List<String> usedExt = asStringList(root.get("extensionsUsed"));
        List<String> requiredExt = asStringList(root.get("extensionsRequired"));
        Map<String, Object> asset = asObject(root.get("asset"));

        int normalTextures = 0;
        int baseColorTextures = 0;
        int metallicRoughnessTextures = 0;
        int emissiveTextures = 0;
        int transmissionMaterials = 0;
        int iorMaterials = 0;
        int clearcoatMaterials = 0;
        int specularMaterials = 0;
        int sheenMaterials = 0;
        int clearcoatTextureUse = 0;
        int clearcoatRoughnessTextureUse = 0;
        int specularTextureUse = 0;
        int specularColorTextureUse = 0;
        int sheenColorTextureUse = 0;
        int sheenRoughnessTextureUse = 0;
        int texCoord1Use = 0;
        int textureTransformUse = 0;
        int doubleSidedCount = 0;
        int alphaBlend = 0;
        int alphaMask = 0;

        for (Map<String, Object> material : materials) {
            if (bool(material.get("doubleSided"))) {
                doubleSidedCount++;
            }
            String alphaMode = string(material.get("alphaMode"), "OPAQUE");
            if ("BLEND".equalsIgnoreCase(alphaMode)) {
                alphaBlend++;
            } else if ("MASK".equalsIgnoreCase(alphaMode)) {
                alphaMask++;
            }

            Map<String, Object> pbr = asObject(material.get("pbrMetallicRoughness"));
            if (hasTextureInfo(pbr.get("baseColorTexture"))) {
                baseColorTextures++;
                Map<String, Object> info = asObject(pbr.get("baseColorTexture"));
                if (intValue(info.get("texCoord"), 0) == 1) {
                    texCoord1Use++;
                }
                if (hasTextureTransform(info)) {
                    textureTransformUse++;
                }
            }
            if (hasTextureInfo(pbr.get("metallicRoughnessTexture"))) {
                metallicRoughnessTextures++;
                if (hasTextureTransform(asObject(pbr.get("metallicRoughnessTexture")))) {
                    textureTransformUse++;
                }
            }
            if (hasTextureInfo(material.get("normalTexture"))) {
                normalTextures++;
                if (hasTextureTransform(asObject(material.get("normalTexture")))) {
                    textureTransformUse++;
                }
            }
            if (hasTextureInfo(material.get("emissiveTexture"))) {
                emissiveTextures++;
                Map<String, Object> info = asObject(material.get("emissiveTexture"));
                if (intValue(info.get("texCoord"), 0) == 1) {
                    texCoord1Use++;
                }
                if (hasTextureTransform(info)) {
                    textureTransformUse++;
                }
            }

            Map<String, Object> extMap = asObject(material.get("extensions"));
            if (extMap.containsKey("KHR_materials_transmission")) {
                transmissionMaterials++;
            }
            if (extMap.containsKey("KHR_materials_ior")) {
                iorMaterials++;
            }
            if (extMap.containsKey("KHR_materials_clearcoat")) {
                clearcoatMaterials++;
                Map<String, Object> clearcoat = asObject(extMap.get("KHR_materials_clearcoat"));
                if (hasTextureInfo(clearcoat.get("clearcoatTexture"))) {
                    clearcoatTextureUse++;
                }
                if (hasTextureInfo(clearcoat.get("clearcoatRoughnessTexture"))) {
                    clearcoatRoughnessTextureUse++;
                }
            }
            if (extMap.containsKey("KHR_materials_specular")) {
                specularMaterials++;
                Map<String, Object> specular = asObject(extMap.get("KHR_materials_specular"));
                if (hasTextureInfo(specular.get("specularTexture"))) {
                    specularTextureUse++;
                }
                if (hasTextureInfo(specular.get("specularColorTexture"))) {
                    specularColorTextureUse++;
                }
            }
            if (extMap.containsKey("KHR_materials_sheen")) {
                sheenMaterials++;
                Map<String, Object> sheen = asObject(extMap.get("KHR_materials_sheen"));
                if (hasTextureInfo(sheen.get("sheenColorTexture"))) {
                    sheenColorTextureUse++;
                }
                if (hasTextureInfo(sheen.get("sheenRoughnessTexture"))) {
                    sheenRoughnessTextureUse++;
                }
            }
        }

        System.out.println("=== GLTF Feature Audit ===");
        System.out.println("file=" + path);
        System.out.println("generator=" + string(asset.get("generator"), "unknown"));
        System.out.println("extensionsUsed=" + usedExt);
        System.out.println("extensionsRequired=" + requiredExt);
        System.out.println("materials=" + materials.size());
        System.out.println("baseColorTextures=" + baseColorTextures);
        System.out.println("normalTextures=" + normalTextures);
        System.out.println("metallicRoughnessTextures=" + metallicRoughnessTextures);
        System.out.println("emissiveTextures=" + emissiveTextures);
        System.out.println("transmissionMaterials=" + transmissionMaterials);
        System.out.println("iorMaterials=" + iorMaterials);
        System.out.println("clearcoatMaterials=" + clearcoatMaterials);
        System.out.println("specularMaterials=" + specularMaterials);
        System.out.println("sheenMaterials=" + sheenMaterials);
        System.out.println("clearcoatTextureUse=" + clearcoatTextureUse);
        System.out.println("clearcoatRoughnessTextureUse=" + clearcoatRoughnessTextureUse);
        System.out.println("specularTextureUse=" + specularTextureUse);
        System.out.println("specularColorTextureUse=" + specularColorTextureUse);
        System.out.println("sheenColorTextureUse=" + sheenColorTextureUse);
        System.out.println("sheenRoughnessTextureUse=" + sheenRoughnessTextureUse);
        System.out.println("doubleSided=" + doubleSidedCount);
        System.out.println("alphaBlend=" + alphaBlend);
        System.out.println("alphaMask=" + alphaMask);
        System.out.println("texCoord1Uses=" + texCoord1Use);
        System.out.println("textureTransformUses=" + textureTransformUse);
    }

    private static boolean hasTextureInfo(Object value) {
        return value instanceof Map && intValue(asObject(value).get("index"), -1) >= 0;
    }

    private static boolean hasTextureTransform(Map<String, Object> info) {
        Map<String, Object> ext = asObject(info.get("extensions"));
        return ext.containsKey("KHR_texture_transform");
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    private static String extractGlbJson(byte[] bytes, String path) {
        if (bytes.length < 20) {
            throw new IllegalArgumentException("Invalid GLB: " + path);
        }
        if (readI32(bytes, 0) != GLB_MAGIC) {
            throw new IllegalArgumentException("Invalid GLB magic: " + path);
        }
        int offset = 12;
        while (offset + 8 <= bytes.length) {
            int chunkLength = readI32(bytes, offset);
            int chunkType = readI32(bytes, offset + 4);
            offset += 8;
            if (chunkType == GLB_CHUNK_JSON) {
                return new String(bytes, offset, chunkLength, StandardCharsets.UTF_8).trim();
            }
            offset += chunkLength;
        }
        throw new IllegalArgumentException("GLB missing JSON chunk: " + path);
    }

    private static int readI32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<>();
    }

    private static List<Map<String, Object>> asObjectList(Object value) {
        List<Object> items = asList(value);
        List<Map<String, Object>> out = new ArrayList<>(items.size());
        for (Object item : items) {
            out.add(asObject(item));
        }
        return out;
    }

    private static List<String> asStringList(Object value) {
        List<Object> items = asList(value);
        List<String> out = new ArrayList<>(items.size());
        for (Object item : items) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<>();
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean && (Boolean) value;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
