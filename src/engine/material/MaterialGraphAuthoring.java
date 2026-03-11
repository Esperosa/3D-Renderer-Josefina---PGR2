package engine.material;

import engine.math.MathUtil;
import engine.math.Vec3;
import engine.render.Texture;

public final class MaterialGraphAuthoring {

    private MaterialGraphAuthoring() {
    }

    public static void syncGraphDefaultsFromMaterial(PhongMaterial material) {
        if (material == null) {
            return;
        }
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        for (MaterialNodeGraph.Node node : graph.getNodes()) {
            switch (node.getType()) {
                case PRINCIPLED_BSDF -> syncPrincipledDefaults(node, material);
                case GLASS_BSDF -> syncGlassDefaults(node, material);
                case EMISSION_SHADER -> syncEmissionDefaults(node, material);
                case VOLUME_MEDIUM -> syncVolumeDefaults(node, material);
                case TRANSPARENT_BSDF -> syncTransparentDefaults(node, material);
                default -> {
                }
            }
        }
        syncCompatibilityBindings(material);
    }

    public static void syncCompatibilityBindings(PhongMaterial material) {
        if (material == null || !material.hasNodeGraph()) {
            return;
        }
        MaterialNodeGraph graph = material.getNodeGraph();
        boolean graphHasNormalNode = graph.findFirstNode(MaterialNodeGraph.NodeType.NORMAL_MAP) != null;
        NormalBinding binding = resolveNormalBinding(graph);
        if (binding != null) {
            applyTextureMap(material.getNormalMap(), binding.textureMap);
            material.setNormalScale(binding.strength);
        } else if (graphHasNormalNode) {
            clearTextureMap(material.getNormalMap());
            material.setNormalScale(1.0);
        }
    }

    public static boolean hasConnectedNormalPath(PhongMaterial material) {
        if (material == null || !material.hasNodeGraph()) {
            return material != null && material.hasNormalTexture();
        }
        return resolveNormalBinding(material.getNodeGraph()) != null || material.hasNormalTexture();
    }

    private static void syncPrincipledDefaults(MaterialNodeGraph.Node node, PhongMaterial material) {
        node.setColor("base_color", copyColor(material.getDiffuseColor()));
        node.setNumber("roughness", material.getRoughness());
        node.setNumber("metallic", material.getMetallic());
        node.setNumber("specular", material.getSpecularFactor());
        node.setNumber("ior", material.getRefractiveIndex());
        node.setNumber("transmission", material.getTransmission());
        node.setNumber("opacity", material.getOpacity());
        node.setColor("emission", copyColor(material.getEmissionColor()));
        node.setNumber("emission_strength", material.getEmissionStrength());
        node.setNumber("clearcoat", material.getClearcoatFactor());
        node.setNumber("clearcoat_roughness", material.getClearcoatRoughness());
        node.setColor("sheen_color", copyColor(material.getSheenColor()));
        node.setNumber("sheen_roughness", material.getSheenRoughness());
    }

    private static void syncGlassDefaults(MaterialNodeGraph.Node node, PhongMaterial material) {
        node.setColor("color", copyColor(material.getDiffuseColor()));
        node.setNumber("roughness", Math.min(1.0, Math.max(0.0, material.getRoughness())));
        node.setNumber("ior", Math.max(1.0, material.getRefractiveIndex()));
        node.setNumber("opacity", material.getOpacity());
    }

    private static void syncEmissionDefaults(MaterialNodeGraph.Node node, PhongMaterial material) {
        node.setColor("color", copyColor(material.getEmissionColor().lengthSquared() > 1e-8
                ? material.getEmissionColor()
                : material.getDiffuseColor()));
        node.setNumber("strength", Math.max(0.0, material.getEmissionStrength()));
    }

    private static void syncVolumeDefaults(MaterialNodeGraph.Node node, PhongMaterial material) {
        node.setColor("color", copyColor(material.getMediumColor()));
        node.setNumber("density", material.getDensity());
        node.setNumber("anisotropy", material.getAnisotropy());
        node.setNumber("thickness", material.getThickness());
    }

    private static void syncTransparentDefaults(MaterialNodeGraph.Node node, PhongMaterial material) {
        node.setColor("color", copyColor(material.getDiffuseColor()));
        node.setNumber("opacity", material.getOpacity());
    }

    private static NormalBinding resolveNormalBinding(MaterialNodeGraph graph) {
        if (graph == null) {
            return null;
        }
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        if (output == null) {
            return null;
        }
        MaterialNodeGraph.Link surfaceLink = graph.findInputLink(output.getId(), "surface");
        if (surfaceLink == null) {
            return null;
        }
        return resolveSurfaceNormalBinding(graph, surfaceLink.getFromNodeId());
    }

    private static NormalBinding resolveSurfaceNormalBinding(MaterialNodeGraph graph, int nodeId) {
        MaterialNodeGraph.Node node = graph.getNodeById(nodeId);
        if (node == null) {
            return null;
        }
        return switch (node.getType()) {
            case PRINCIPLED_BSDF, GLASS_BSDF -> resolveNormalMapNode(graph, node);
            case MIX_SHADER -> {
                MaterialNodeGraph.Link a = graph.findInputLink(node.getId(), "shader_a");
                MaterialNodeGraph.Link b = graph.findInputLink(node.getId(), "shader_b");
                NormalBinding first = a == null ? null : resolveSurfaceNormalBinding(graph, a.getFromNodeId());
                yield first != null ? first : b == null ? null : resolveSurfaceNormalBinding(graph, b.getFromNodeId());
            }
            default -> null;
        };
    }

    private static NormalBinding resolveNormalMapNode(MaterialNodeGraph graph, MaterialNodeGraph.Node shaderNode) {
        MaterialNodeGraph.Link normalLink = graph.findInputLink(shaderNode.getId(), "normal");
        if (normalLink == null) {
            return null;
        }
        MaterialNodeGraph.Node normalNode = graph.getNodeById(normalLink.getFromNodeId());
        if (normalNode == null || normalNode.getType() != MaterialNodeGraph.NodeType.NORMAL_MAP) {
            return null;
        }
        MaterialNodeGraph.Link colorLink = graph.findInputLink(normalNode.getId(), "color");
        if (colorLink == null) {
            return null;
        }
        MaterialNodeGraph.Node source = graph.getNodeById(colorLink.getFromNodeId());
        TextureMap textureMap = resolveCompatibleTextureMap(graph, source);
        if (textureMap == null || !textureMap.hasTexture()) {
            return null;
        }
        return new NormalBinding(textureMap, Math.max(0.0, normalNode.getNumber("strength", 1.0)));
    }

    private static TextureMap resolveCompatibleTextureMap(MaterialNodeGraph graph, MaterialNodeGraph.Node sourceNode) {
        if (graph == null || sourceNode == null || sourceNode.getType() != MaterialNodeGraph.NodeType.IMAGE_TEXTURE) {
            return null;
        }
        String filePath = sourceNode.getText("file_path", null);
        if (filePath == null || filePath.isBlank()) {
            return null;
        }
        Texture texture = NodeTextureLibrary.load(filePath);
        if (texture == null) {
            return null;
        }
        TextureMap map = new TextureMap();
        map.setTexture(texture);
        map.setLinear(sourceNode.getNumber("linear", 1.0) >= 0.5);
        map.setFlipV(sourceNode.getNumber("flip_v", 0.0) >= 0.5);
        map.setTexCoord("UV1".equalsIgnoreCase(sourceNode.getEnum("uv_set", "UV0")) ? 1 : 0);
        map.setOffsetU(sourceNode.getNumber("offset_u", 0.0));
        map.setOffsetV(sourceNode.getNumber("offset_v", 0.0));
        map.setScaleU(sourceNode.getNumber("scale_u", 1.0));
        map.setScaleV(sourceNode.getNumber("scale_v", 1.0));
        map.setRotation(sourceNode.getNumber("rotation", 0.0));

        MaterialNodeGraph.Link vectorLink = graph.findInputLink(sourceNode.getId(), "vector");
        if (vectorLink == null) {
            return map;
        }
        if (!imageNodeHasIdentityTransform(sourceNode)) {
            return null;
        }
        MaterialNodeGraph.Node vectorNode = graph.getNodeById(vectorLink.getFromNodeId());
        MappingBinding mapping = resolveMappingBinding(graph, vectorNode, vectorLink.getFromSocket());
        if (mapping == null || !mapping.compatible) {
            return null;
        }
        map.setTexCoord(mapping.texCoord);
        map.setOffsetU(mapping.offsetU);
        map.setOffsetV(mapping.offsetV);
        map.setScaleU(mapping.scaleU);
        map.setScaleV(mapping.scaleV);
        map.setRotation(mapping.rotation);
        return map;
    }

    private static MappingBinding resolveMappingBinding(MaterialNodeGraph graph,
                                                       MaterialNodeGraph.Node node,
                                                       String outputSocket) {
        if (node == null) {
            return null;
        }
        if (node.getType() == MaterialNodeGraph.NodeType.TEXTURE_COORDINATE) {
            return textureCoordinateBinding(outputSocket);
        }
        if (node.getType() != MaterialNodeGraph.NodeType.MAPPING) {
            return null;
        }
        MaterialNodeGraph.Link vectorLink = graph.findInputLink(node.getId(), "vector");
        MappingBinding base = vectorLink == null
                ? new MappingBinding(true, 0, 0.0, 0.0, 1.0, 1.0, 0.0)
                : resolveMappingBinding(graph, graph.getNodeById(vectorLink.getFromNodeId()), vectorLink.getFromSocket());
        if (base == null || !base.compatible) {
            return null;
        }
        if (Math.abs(node.getNumber("location_z", 0.0)) > 1e-6
                || Math.abs(node.getNumber("rotation_x", 0.0)) > 1e-6
                || Math.abs(node.getNumber("rotation_y", 0.0)) > 1e-6
                || Math.abs(node.getNumber("scale_z", 1.0) - 1.0) > 1e-6) {
            return new MappingBinding(false, base.texCoord, 0.0, 0.0, 1.0, 1.0, 0.0);
        }
        return new MappingBinding(
                true,
                base.texCoord,
                base.offsetU + node.getNumber("location_x", 0.0),
                base.offsetV + node.getNumber("location_y", 0.0),
                base.scaleU * node.getNumber("scale_x", 1.0),
                base.scaleV * node.getNumber("scale_y", 1.0),
                base.rotation + node.getNumber("rotation_z", 0.0)
        );
    }

    private static MappingBinding textureCoordinateBinding(String outputSocket) {
        String key = normalizeSocketKey(outputSocket, "uv0");
        if ("uv1".equals(key)) {
            return new MappingBinding(true, 1, 0.0, 0.0, 1.0, 1.0, 0.0);
        }
        if ("world".equals(key)) {
            return new MappingBinding(false, 0, 0.0, 0.0, 1.0, 1.0, 0.0);
        }
        return new MappingBinding(true, 0, 0.0, 0.0, 1.0, 1.0, 0.0);
    }

    /**
     * Tady používám stejné ASCII klíče socketů jako v nejteplejší cestě vykreslení.
     * Vlastní normalizací si držím konzistentní chování a nevolám převod na malá písmena závislý na jazykovém prostředí.
     */
    private static String normalizeSocketKey(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        char[] buffer = null;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            char lowered = ch >= 'A' && ch <= 'Z' ? (char) (ch + ('a' - 'A')) : ch;
            if (buffer != null) {
                buffer[i] = lowered;
            } else if (lowered != ch) {
                buffer = trimmed.toCharArray();
                buffer[i] = lowered;
            }
        }
        return buffer == null ? trimmed : new String(buffer);
    }

    private static boolean imageNodeHasIdentityTransform(MaterialNodeGraph.Node node) {
        if (node == null) {
            return true;
        }
        return Math.abs(node.getNumber("offset_u", 0.0)) < 1e-6
                && Math.abs(node.getNumber("offset_v", 0.0)) < 1e-6
                && Math.abs(node.getNumber("rotation", 0.0)) < 1e-6
                && Math.abs(node.getNumber("scale_u", 1.0) - 1.0) < 1e-6
                && Math.abs(node.getNumber("scale_v", 1.0) - 1.0) < 1e-6;
    }

    private static void applyTextureMap(TextureMap target, TextureMap source) {
        if (target == null) {
            return;
        }
        if (source == null) {
            clearTextureMap(target);
            return;
        }
        target.copyFrom(source);
    }

    private static void clearTextureMap(TextureMap map) {
        if (map == null) {
            return;
        }
        map.setTexture(null);
        map.setLinear(true);
        map.setTexCoord(0);
        map.setOffsetU(0.0);
        map.setOffsetV(0.0);
        map.setScaleU(1.0);
        map.setScaleV(1.0);
        map.setRotation(0.0);
        map.setFlipV(true);
    }

    private static Vec3 copyColor(Vec3 color) {
        if (color == null) {
            return new Vec3(0.0, 0.0, 0.0);
        }
        return new Vec3(
                MathUtil.clamp01(color.x),
                MathUtil.clamp01(color.y),
                MathUtil.clamp01(color.z)
        );
    }

    private static final class NormalBinding {
        final TextureMap textureMap;
        final double strength;

        private NormalBinding(TextureMap textureMap, double strength) {
            this.textureMap = textureMap;
            this.strength = strength;
        }
    }

    private static final class MappingBinding {
        final boolean compatible;
        final int texCoord;
        final double offsetU;
        final double offsetV;
        final double scaleU;
        final double scaleV;
        final double rotation;

        private MappingBinding(boolean compatible,
                               int texCoord,
                               double offsetU,
                               double offsetV,
                               double scaleU,
                               double scaleV,
                               double rotation) {
            this.compatible = compatible;
            this.texCoord = texCoord;
            this.offsetU = offsetU;
            this.offsetV = offsetV;
            this.scaleU = scaleU;
            this.scaleV = scaleV;
            this.rotation = rotation;
        }
    }
}
