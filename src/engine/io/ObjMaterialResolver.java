package engine.io;

import engine.material.MaterialGraphAuthoring;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.Texture;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Handles materiály OBJ a MTL.
 * Načítám si pojmenované materiály z navázaných MTL souborů a rozvazuju texturové mapy.
 */
final class ObjMaterialResolver {

    private static final class ObjMaterialDef {
        String name;
        Vec3 kd = new Vec3(0.7, 0.7, 0.7);
        Vec3 ks = new Vec3(0.5, 0.5, 0.5);
        Vec3 ka = new Vec3(0.07, 0.07, 0.07);
        double ns = 32.0;
        double ni = 1.0;
        double opacity = 1.0;
        String mapKd;
    }

    private ObjMaterialResolver() {
    }

    static PhongMaterial resolveForObj(String objPath) {
        Map<String, PhongMaterial> materials = loadMaterialsForObj(objPath);
        if (materials.isEmpty()) {
            return null;
        }
        List<String> referenced = collectReferencedMaterialNames(objPath);
        for (String name : referenced) {
            PhongMaterial material = materials.get(name);
            if (material != null && material.getDiffuseTexture() != null) {
                return material.copy();
            }
        }
        for (String name : referenced) {
            PhongMaterial material = materials.get(name);
            if (material != null) {
                return material.copy();
            }
        }
        for (PhongMaterial material : materials.values()) {
            if (material.getDiffuseTexture() != null) {
                return material.copy();
            }
        }
        return materials.values().iterator().next().copy();
    }

    static Map<String, PhongMaterial> loadMaterialsForObj(String objPath) {
        LinkedHashMap<String, PhongMaterial> out = new LinkedHashMap<>();
        if (objPath == null || objPath.isBlank() || !FileUtil.exists(objPath)) {
            return out;
        }
        Path obj = Path.of(objPath).toAbsolutePath().normalize();
        Path objDir = obj.getParent();
        List<String> objLines = FileUtil.readLines(obj.toString());
        List<Path> mtlPaths = resolveMtllibPaths(objDir, objLines);
        for (Path mtlPath : mtlPaths) {
            Map<String, ObjMaterialDef> parsed = parseMtl(mtlPath.toString());
            if (parsed.isEmpty()) {
                continue;
            }
            Path mtlDir = mtlPath.getParent();
            for (ObjMaterialDef def : parsed.values()) {
                PhongMaterial material = toPhong(def, mtlDir);
                if (material != null) {
                    out.put(def.name, material);
                }
            }
        }
        return out;
    }

    private static Map<String, ObjMaterialDef> parseMtl(String mtlPath) {
        Map<String, ObjMaterialDef> out = new LinkedHashMap<>();
        List<String> lines = FileUtil.readLines(mtlPath);
        ObjMaterialDef current = null;
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }

            if (line.startsWith("newmtl ")) {
                String name = line.substring("newmtl ".length()).trim();
                if (name.isBlank()) {
                    continue;
                }
                current = new ObjMaterialDef();
                current.name = name;
                out.put(name, current);
                continue;
            }

            if (current == null) {
                continue;
            }

            if (line.startsWith("Kd ")) {
                current.kd = parseVec3(line.substring(3), current.kd);
            } else if (line.startsWith("Ks ")) {
                current.ks = parseVec3(line.substring(3), current.ks);
            } else if (line.startsWith("Ka ")) {
                current.ka = parseVec3(line.substring(3), current.ka);
            } else if (line.startsWith("Ns ")) {
                current.ns = parseDouble(line.substring(3), current.ns);
            } else if (line.startsWith("Ni ")) {
                current.ni = parseDouble(line.substring(3), current.ni);
            } else if (line.startsWith("d ")) {
                current.opacity = clamp01(parseDouble(line.substring(2), current.opacity));
            } else if (line.startsWith("Tr ")) {
                double tr = clamp01(parseDouble(line.substring(3), 1.0 - current.opacity));
                current.opacity = clamp01(1.0 - tr);
            } else if (line.toLowerCase(Locale.ROOT).startsWith("map_kd ")) {
                current.mapKd = line.substring("map_Kd ".length()).trim();
            }
        }
        return out;
    }

    private static PhongMaterial toPhong(ObjMaterialDef def, Path baseDir) {
        if (def == null) {
            return null;
        }
        PhongMaterial mat = new PhongMaterial(def.kd, Math.max(1.0, def.ns));
        mat.setName(def.name == null ? "OBJMaterial" : def.name);
        mat.setAmbientColor(def.ka);
        mat.setDiffuseColor(def.kd);
        mat.setSpecularColor(def.ks);
        mat.setShininess(Math.max(1.0, def.ns));
        mat.setRoughness(PhongMaterial.roughnessFromShininess(def.ns));
        double refl = clamp01((def.ks.x + def.ks.y + def.ks.z) / 3.0);
        mat.setReflectivity(refl * 0.6);
        mat.setRefractiveIndex(Math.max(1.0, def.ni));
        mat.setOpacity(clamp01(def.opacity));
        double transmission = clamp01((1.0 - def.opacity) * 1.15);
        if (def.ni > 1.05 && def.opacity < 0.98) {
            transmission = Math.max(transmission, 0.35);
        }
        mat.setTransmission(transmission);
        if (transmission > 0.1) {
            mat.setShadingModel(engine.material.Material.ShadingModel.TRANSMISSIVE);
        }
        mat.setMediumColor(def.kd.mul(0.65).add(new Vec3(0.18, 0.18, 0.18)));
        mat.setDensity(Math.max(0.0, 1.0 - def.opacity));

        if (def.mapKd != null && !def.mapKd.isBlank()) {
            Path tex = resolveExistingReference(baseDir, def.mapKd);
            if (tex != null && FileUtil.exists(tex.toString())) {
                try {
                    mat.setDiffuseTexture(Texture.load(tex.toString()));
                } catch (RuntimeException ignored) {
 // Represents materiál validní i tehdy, když načtení textury selže.
                }
            }
        }
        mat.setNodeGraph(MaterialGraphAuthoring.createAuthoringGraphFromMaterial(mat));
        MaterialGraphAuthoring.syncCompatibilityBindings(mat);
        return mat;
    }

    private static List<String> collectReferencedMaterialNames(String objPath) {
        List<String> out = new ArrayList<>();
        if (objPath == null || objPath.isBlank() || !FileUtil.exists(objPath)) {
            return out;
        }
        for (String raw : FileUtil.readLines(objPath)) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (!line.startsWith("usemtl ")) {
                continue;
            }
            String name = line.substring("usemtl ".length()).trim();
            if (!name.isBlank()) {
                out.add(name);
            }
        }
        return out;
    }

    private static List<Path> resolveMtllibPaths(Path baseDir, List<String> objLines) {
        LinkedHashSet<Path> out = new LinkedHashSet<>();
        for (String raw : objLines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (!line.startsWith("mtllib ")) {
                continue;
            }
            String tail = line.substring("mtllib ".length()).trim();
            if (tail.isBlank()) {
                continue;
            }
            Path fullRef = resolveExistingReference(baseDir, tail);
            if (fullRef != null) {
                out.add(fullRef);
                continue;
            }
            List<String> tokens = tokenize(tail);
            for (String token : tokens) {
                Path candidate = resolveExistingReference(baseDir, token);
                if (candidate != null) {
                    out.add(candidate);
                }
            }
        }
        return new ArrayList<>(out);
    }

    private static Vec3 parseVec3(String tail, Vec3 fallback) {
        if (tail == null) {
            return fallback;
        }
        String[] parts = tail.trim().split("\\s+");
        if (parts.length < 3) {
            return fallback;
        }
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            return new Vec3(x, y, z);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static double parseDouble(String tail, double fallback) {
        if (tail == null) {
            return fallback;
        }
        String[] parts = tail.trim().split("\\s+");
        if (parts.length == 0) {
            return fallback;
        }
        try {
            return Double.parseDouble(parts[0]);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static Path resolveExistingReference(Path baseDir, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        String trimmed = trimQuotes(raw.trim());
        if (!trimmed.isBlank()) {
            candidates.add(trimmed);
        }
        String extracted = extractMapPath(raw.trim());
        if (extracted != null && !extracted.isBlank()) {
            candidates.add(trimQuotes(extracted.trim()));
        }
        List<String> tokens = tokenize(raw.trim());
        if (!tokens.isEmpty()) {
            candidates.add(trimQuotes(tokens.get(tokens.size() - 1)));
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                Path path = baseDir == null ? Path.of(candidate) : baseDir.resolve(candidate).normalize();
                if (FileUtil.exists(path.toString())) {
                    return path;
                }
            } catch (InvalidPathException ignored) {
 // Skips poškozený kandidátní text a zkusím realističtější náhradní řešení.
            }
        }
        return null;
    }

    private static String extractMapPath(String raw) {
        List<String> tokens = tokenize(raw);
        if (tokens.isEmpty()) {
            return null;
        }
        int i = 0;
        while (i < tokens.size()) {
            String token = tokens.get(i);
            if (!token.startsWith("-")) {
                return joinTokens(tokens, i);
            }
            i += skipMapOption(tokens, i);
        }
        return tokens.get(tokens.size() - 1);
    }

    private static int skipMapOption(List<String> tokens, int optionIndex) {
        String option = tokens.get(optionIndex).toLowerCase(Locale.ROOT);
        if ("-o".equals(option) || "-s".equals(option) || "-t".equals(option)) {
            int consumed = 1;
            while (consumed <= 3 && optionIndex + consumed < tokens.size()
                    && isNumericToken(tokens.get(optionIndex + consumed))) {
                consumed++;
            }
            return consumed;
        }
        if ("-mm".equals(option)) {
            int consumed = 1;
            while (consumed <= 2 && optionIndex + consumed < tokens.size()
                    && isNumericToken(tokens.get(optionIndex + consumed))) {
                consumed++;
            }
            return consumed;
        }
        if ("-clamp".equals(option)
                || "-blendu".equals(option)
                || "-blendv".equals(option)
                || "-bm".equals(option)
                || "-boost".equals(option)
                || "-texres".equals(option)
                || "-imfchan".equals(option)
                || "-type".equals(option)
                || "-colorspace".equals(option)) {
            return Math.min(2, tokens.size() - optionIndex);
        }
        return 1;
    }

    private static boolean isNumericToken(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static List<String> tokenize(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        StringBuilder token = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (quote != 0) {
                if (ch == quote) {
                    quote = 0;
                } else {
                    token.append(ch);
                }
                continue;
            }
            if (ch == '"' || ch == '\'') {
                quote = ch;
                continue;
            }
            if (Character.isWhitespace(ch)) {
                if (token.length() > 0) {
                    out.add(token.toString());
                    token.setLength(0);
                }
                continue;
            }
            token.append(ch);
        }
        if (token.length() > 0) {
            out.add(token.toString());
        }
        return out;
    }

    private static String joinTokens(List<String> tokens, int startIndex) {
        StringBuilder out = new StringBuilder();
        for (int i = startIndex; i < tokens.size(); i++) {
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(tokens.get(i));
        }
        return out.toString();
    }

    private static String trimQuotes(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }
}
