package engine.material;

import engine.render.Texture;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class MaterialTextureSetImporter {

    public enum Role {
        BASE_COLOR,
        ROUGHNESS,
        METALLIC,
        METALLIC_ROUGHNESS,
        NORMAL,
        EMISSIVE,
        OPACITY,
        AO
    }

    public static final class ImportResult {
        private final boolean success;
        private final Map<Role, Path> detectedFiles;
        private final List<String> notes;

        private ImportResult(boolean success, Map<Role, Path> detectedFiles, List<String> notes) {
            this.success = success;
            this.detectedFiles = detectedFiles;
            this.notes = notes;
        }

        public boolean success() {
            return success;
        }

        public Map<Role, Path> detectedFiles() {
            return detectedFiles;
        }

        public List<String> notes() {
            return notes;
        }
    }

    private MaterialTextureSetImporter() {
    }

    public static ImportResult importFiles(PhongMaterial material, List<Path> files) {
        Map<Role, Path> detected = detectRoles(files);
        List<String> notes = new ArrayList<>();
        if (material == null) {
            notes.add("Chybí cílový materiál.");
            return new ImportResult(false, detected, notes);
        }
        if (detected.isEmpty()) {
            notes.add("Nepodařilo se rozpoznat žádné PBR mapy.");
            return new ImportResult(false, detected, notes);
        }

        MaterialNodeGraph graph = buildAuthoringGraph(material, detected, notes);
        material.setNodeGraph(graph);
        material.setPresetName(MaterialPresets.Preset.CUSTOM.id());
        material.setName(material.getName());
        if (detected.containsKey(Role.OPACITY)) {
            material.setAlphaMode(PhongMaterial.AlphaMode.BLEND);
            material.setOpacity(1.0);
        }
        if (detected.containsKey(Role.EMISSIVE)) {
            material.setEmissionStrength(Math.max(1.0, material.getEmissionStrength()));
        }
        if (detected.containsKey(Role.NORMAL)) {
            material.setNormalScale(1.0);
        }
        applyLegacyCompatibilityMaps(material, detected);
        MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
        return new ImportResult(true, detected, notes);
    }

    public static Map<Role, Path> detectRoles(List<Path> files) {
        Map<Role, Path> detected = new EnumMap<>(Role.class);
        if (files == null) {
            return detected;
        }
        for (Path path : files) {
            if (path == null) {
                continue;
            }
            Role role = detectRole(path);
            if (role != null && !detected.containsKey(role)) {
                detected.put(role, path.toAbsolutePath().normalize());
            }
        }
        return detected;
    }

    private static MaterialNodeGraph buildAuthoringGraph(PhongMaterial material,
                                                         Map<Role, Path> detected,
                                                         List<String> notes) {
        MaterialNodeGraph graph = new MaterialNodeGraph();
        MaterialNodeGraph.Node texCoord = graph.addNode(MaterialNodeGraph.NodeType.TEXTURE_COORDINATE, 36.0, 76.0);
        MaterialNodeGraph.Node mapping = graph.addNode(MaterialNodeGraph.NodeType.MAPPING, 236.0, 76.0);
        MaterialNodeGraph.Node principled = graph.addNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF, 676.0, 170.0);
        MaterialNodeGraph.Node volume = graph.addNode(MaterialNodeGraph.NodeType.VOLUME_MEDIUM, 676.0, 500.0);
        MaterialNodeGraph.Node output = graph.addNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL, 1040.0, 280.0);
        graph.connect(texCoord.getId(), "uv0", mapping.getId(), "vector");
        graph.connect(principled.getId(), "bsdf", output.getId(), "surface");
        graph.connect(volume.getId(), "volume", output.getId(), "volume");

        int textureY = 44;
        MaterialNodeGraph.Node base = addImageNode(graph, "Base Color", detected.get(Role.BASE_COLOR), true, 436.0, textureY);
        if (base != null) {
            graph.connect(mapping.getId(), "vector", base.getId(), "vector");
            graph.connect(base.getId(), "color", principled.getId(), "base_color");
        }
        textureY += 132;

        MaterialNodeGraph.Node roughness = addImageNode(graph, "Roughness", detected.get(Role.ROUGHNESS), false, 436.0, textureY);
        if (roughness != null) {
            graph.connect(mapping.getId(), "vector", roughness.getId(), "vector");
            graph.connect(roughness.getId(), "red", principled.getId(), "roughness");
        }

        MaterialNodeGraph.Node metallic = addImageNode(graph, "Metallic", detected.get(Role.METALLIC), false, 436.0, textureY + 124.0);
        if (metallic != null) {
            graph.connect(mapping.getId(), "vector", metallic.getId(), "vector");
            graph.connect(metallic.getId(), "red", principled.getId(), "metallic");
        }

        MaterialNodeGraph.Node combinedMr = addImageNode(graph, "Metallic Roughness", detected.get(Role.METALLIC_ROUGHNESS), false, 436.0, textureY + 248.0);
        if (combinedMr != null) {
            graph.connect(mapping.getId(), "vector", combinedMr.getId(), "vector");
            graph.connect(combinedMr.getId(), "green", principled.getId(), "roughness");
            graph.connect(combinedMr.getId(), "blue", principled.getId(), "metallic");
        }
        textureY += 380;

        MaterialNodeGraph.Node emissive = addImageNode(graph, "Emissive", detected.get(Role.EMISSIVE), true, 436.0, textureY);
        if (emissive != null) {
            graph.connect(mapping.getId(), "vector", emissive.getId(), "vector");
            graph.connect(emissive.getId(), "color", principled.getId(), "emission");
        }

        MaterialNodeGraph.Node opacity = addImageNode(graph, "Opacity", detected.get(Role.OPACITY), false, 436.0, textureY + 124.0);
        if (opacity != null) {
            graph.connect(mapping.getId(), "vector", opacity.getId(), "vector");
            graph.connect(opacity.getId(), "red", principled.getId(), "opacity");
        }

        MaterialNodeGraph.Node normalTexture = addImageNode(graph, "Normal", detected.get(Role.NORMAL), false, 436.0, textureY + 248.0);
        if (normalTexture != null) {
            MaterialNodeGraph.Node normalMap = graph.addNode(MaterialNodeGraph.NodeType.NORMAL_MAP, 676.0, textureY + 248.0);
            graph.connect(mapping.getId(), "vector", normalTexture.getId(), "vector");
            graph.connect(normalTexture.getId(), "color", normalMap.getId(), "color");
            graph.connect(normalMap.getId(), "normal", principled.getId(), "normal");
        }

        if (detected.containsKey(Role.AO)) {
            notes.add("AO mapa byla rozpoznána, ale v této iteraci se nepřipojuje automaticky.");
        }
        graph.setSelectedNodeId(principled.getId());
        return graph;
    }

    private static MaterialNodeGraph.Node addImageNode(MaterialNodeGraph graph,
                                                       String label,
                                                       Path path,
                                                       boolean srgb,
                                                       double x,
                                                       double y) {
        if (graph == null || path == null) {
            return null;
        }
        MaterialNodeGraph.Node node = graph.addNode(MaterialNodeGraph.NodeType.IMAGE_TEXTURE, x, y);
        node.setText("file_path", path.toAbsolutePath().normalize().toString());
        node.setEnum("color_space", srgb
                ? MaterialNodeGraph.TextureColorSpace.SRGB.name()
                : MaterialNodeGraph.TextureColorSpace.DATA.name());
        node.setText("label", label);
        return node;
    }

    private static void applyLegacyCompatibilityMaps(PhongMaterial material, Map<Role, Path> detected) {
        if (material == null) {
            return;
        }
        applyTextureMap(material.getDiffuseMap(), detected.get(Role.BASE_COLOR));
        applyTextureMap(material.getMetallicRoughnessMap(), detected.get(Role.METALLIC_ROUGHNESS));
        applyTextureMap(material.getEmissiveMap(), detected.get(Role.EMISSIVE));
    }

    private static void applyTextureMap(TextureMap map, Path path) {
        if (map == null) {
            return;
        }
        if (path == null) {
            map.setTexture(null);
            return;
        }
        Texture texture = NodeTextureLibrary.load(path.toAbsolutePath().normalize().toString());
        map.setTexture(texture);
        map.setLinear(true);
        map.setTexCoord(0);
        map.setOffsetU(0.0);
        map.setOffsetV(0.0);
        map.setScaleU(1.0);
        map.setScaleV(1.0);
        map.setRotation(0.0);
        map.setFlipV(false);
    }

    private static Role detectRole(Path path) {
        String name = path.getFileName() == null
                ? path.toString().toLowerCase(Locale.ROOT)
                : path.getFileName().toString().toLowerCase(Locale.ROOT);
        String normalized = name.replace("-", "").replace("_", "").replace(" ", "");
        if (containsAny(normalized, "metallicroughness", "metalrough", "roughmetal")
                || containsDelimitedToken(name, "orm")) {
            return Role.METALLIC_ROUGHNESS;
        }
        if (containsAny(normalized, "basecolor", "albedo", "diffuse", "basecolour")) {
            return Role.BASE_COLOR;
        }
        if (containsAny(normalized, "roughness", "rough")) {
            return Role.ROUGHNESS;
        }
        if (containsAny(normalized, "metallic", "metalness")) {
            return Role.METALLIC;
        }
        if (containsAny(normalized, "normal", "normalgl", "normaldx", "nrm", "nor")) {
            return Role.NORMAL;
        }
        if (containsAny(normalized, "emissive", "emission", "emit")) {
            return Role.EMISSIVE;
        }
        if (containsAny(normalized, "opacity", "alpha", "transparency")) {
            return Role.OPACITY;
        }
        if (containsAny(normalized, "ambientocclusion", "occlusion", "ao")) {
            return Role.AO;
        }
        return null;
    }

    private static boolean containsDelimitedToken(String text, String token) {
        if (text == null || token == null || token.isBlank()) {
            return false;
        }
        String[] parts = text.split("[\\s._-]+");
        for (String part : parts) {
            if (token.equals(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAny(String text, String... tokens) {
        if (text == null || tokens == null) {
            return false;
        }
        for (String token : tokens) {
            if (token != null && !token.isBlank() && text.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
