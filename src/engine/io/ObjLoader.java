package engine.io;

import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec2;
import engine.math.Vec3;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses OBJ soubory formátu Wavefront.
 * Čtu si z nich pozice vertexů, normály, UV souřadnice i stěny a čtyřúhelníky převádí přes vějířovou triangulaci.
 */
public class ObjLoader {

    private static final class ScenePartBuilder {
        final String name;
        final String materialName;
        final List<Float> outPositions = new ArrayList<>();
        final List<Float> outNormals = new ArrayList<>();
        final List<Float> outUVs = new ArrayList<>();
        final List<Integer> outIndices = new ArrayList<>();
        final Map<String, Integer> dedup = new HashMap<>();
        boolean hasAnyUV = false;
        boolean hasFullNormals = true;
        boolean hasFaces = false;

        ScenePartBuilder(String name, String materialName) {
            this.name = name;
            this.materialName = materialName;
        }

        void addFace(String[] tokens,
                     List<Vec3> tempPositions,
                     List<Vec3> tempNormals,
                     List<Vec2> tempUVs,
                     ObjLoader loader) {
            if (tokens == null || tokens.length < 3) {
                return;
            }
            hasFaces = true;
            int[] face = new int[tokens.length];
            for (int i = 0; i < tokens.length; i++) {
                int[] faceVertex = loader.parseFaceVertex(tokens[i]);
                int pi = loader.resolveIndex(faceVertex[0], tempPositions.size());
                int ti = faceVertex[1] == -1 ? -1 : loader.resolveIndex(faceVertex[1], tempUVs.size());
                int ni = faceVertex[2] == -1 ? -1 : loader.resolveIndex(faceVertex[2], tempNormals.size());

                String key = pi + "/" + ti + "/" + ni;
                Integer existing = dedup.get(key);
                if (existing == null) {
                    int newIndex = outPositions.size() / 3;
                    dedup.put(key, newIndex);
                    face[i] = newIndex;

                    Vec3 pos = tempPositions.get(pi);
                    outPositions.add((float) pos.x);
                    outPositions.add((float) pos.y);
                    outPositions.add((float) pos.z);

                    if (ti >= 0) {
                        Vec2 uv = tempUVs.get(ti);
                        outUVs.add((float) uv.x);
                        outUVs.add((float) uv.y);
                        hasAnyUV = true;
                    } else {
                        outUVs.add(0.0f);
                        outUVs.add(0.0f);
                    }

                    if (ni >= 0) {
                        Vec3 n = tempNormals.get(ni);
                        outNormals.add((float) n.x);
                        outNormals.add((float) n.y);
                        outNormals.add((float) n.z);
                    } else {
                        outNormals.add(0.0f);
                        outNormals.add(0.0f);
                        outNormals.add(0.0f);
                        hasFullNormals = false;
                    }
                } else {
                    face[i] = existing;
                }
            }

            for (int i = 1; i < face.length - 1; i++) {
                outIndices.add(face[0]);
                outIndices.add(face[i]);
                outIndices.add(face[i + 1]);
            }
        }

        Mesh buildMesh() {
            if (!hasFaces || outPositions.isEmpty() || outIndices.isEmpty()) {
                return null;
            }
            float[] positions = toFloatArray(outPositions);
            float[] normals = toFloatArray(outNormals);
            int[] indices = toIntArray(outIndices);
            Mesh mesh = new Mesh(name, positions, normals, indices);
            if (!hasFullNormals) {
                mesh.computeNormals();
            }
            if (hasAnyUV) {
                mesh.setUVs(toFloatArray(outUVs));
            }
            mesh.computeBounds();
            return mesh;
        }
    }

    public ObjLoader() {}

 /**
 * Loads mesh z OBJ souboru.
 *
 * @param filePath cestu k .obj souboru
 * @return tím vrátí hotový mesh s pozicemi, normálami a indexy
 * @throws RuntimeException když soubor nepřečtu nebo nenaparsuje
 */
    public Mesh load(String filePath) {
        List<String> lines = FileUtil.readLines(filePath);

        List<Vec3> tempPositions = new ArrayList<>();
        List<Vec3> tempNormals = new ArrayList<>();
        List<Vec2> tempUVs = new ArrayList<>();

        List<Float> outPositions = new ArrayList<>();
        List<Float> outNormals = new ArrayList<>();
        List<Float> outUVs = new ArrayList<>();
        List<Integer> outIndices = new ArrayList<>();

        Map<String, Integer> dedup = new HashMap<>();
        boolean hasAnyUV = false;
        boolean hasFullNormals = true;
        boolean seenAnyFace = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                tempPositions.add(new Vec3(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]),
                        Double.parseDouble(p[3])
                ));
                continue;
            }
            if (line.startsWith("vn ")) {
                String[] p = line.split("\\s+");
                tempNormals.add(new Vec3(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]),
                        Double.parseDouble(p[3])
                ).normalize());
                continue;
            }
            if (line.startsWith("vt ")) {
                String[] p = line.split("\\s+");
                tempUVs.add(new Vec2(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2])
                ));
                continue;
            }
            if (line.startsWith("f ")) {
                seenAnyFace = true;
                String[] tokens = line.substring(2).trim().split("\\s+");
                if (tokens.length < 3) {
                    continue;
                }
                int[] face = new int[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    int[] faceVertex = parseFaceVertex(tokens[i]);
                    int pi = resolveIndex(faceVertex[0], tempPositions.size());
                    int ti = faceVertex[1] == -1 ? -1 : resolveIndex(faceVertex[1], tempUVs.size());
                    int ni = faceVertex[2] == -1 ? -1 : resolveIndex(faceVertex[2], tempNormals.size());

                    String key = pi + "/" + ti + "/" + ni;
                    Integer existing = dedup.get(key);
                    if (existing == null) {
                        int newIndex = outPositions.size() / 3;
                        dedup.put(key, newIndex);
                        face[i] = newIndex;

                        Vec3 pos = tempPositions.get(pi);
                        outPositions.add((float) pos.x);
                        outPositions.add((float) pos.y);
                        outPositions.add((float) pos.z);

                        if (ti >= 0) {
                            Vec2 uv = tempUVs.get(ti);
                            outUVs.add((float) uv.x);
                            outUVs.add((float) uv.y);
                            hasAnyUV = true;
                        } else {
                            outUVs.add(0.0f);
                            outUVs.add(0.0f);
                        }

                        if (ni >= 0) {
                            Vec3 n = tempNormals.get(ni);
                            outNormals.add((float) n.x);
                            outNormals.add((float) n.y);
                            outNormals.add((float) n.z);
                        } else {
                            outNormals.add(0.0f);
                            outNormals.add(0.0f);
                            outNormals.add(0.0f);
                            hasFullNormals = false;
                        }
                    } else {
                        face[i] = existing;
                    }
                }

                for (int i = 1; i < face.length - 1; i++) {
                    outIndices.add(face[0]);
                    outIndices.add(face[i]);
                    outIndices.add(face[i + 1]);
                }
            }
        }

        if (!seenAnyFace || outPositions.isEmpty()) {
            throw new RuntimeException("OBJ has no faces: " + filePath);
        }

        float[] positions = new float[outPositions.size()];
        for (int i = 0; i < outPositions.size(); i++) {
            positions[i] = outPositions.get(i);
        }

        float[] normals = new float[outNormals.size()];
        for (int i = 0; i < outNormals.size(); i++) {
            normals[i] = outNormals.get(i);
        }

        int[] indices = new int[outIndices.size()];
        for (int i = 0; i < outIndices.size(); i++) {
            indices[i] = outIndices.get(i);
        }

        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }

        Mesh mesh = new Mesh(name, positions, normals, indices);
        if (!hasFullNormals) {
            mesh.computeNormals();
        }

        if (hasAnyUV) {
            float[] uvs = new float[outUVs.size()];
            for (int i = 0; i < outUVs.size(); i++) {
                uvs[i] = outUVs.get(i);
            }
            mesh.setUVs(uvs);
        }

        mesh.computeBounds();
        return mesh;
    }

 /**
 * Loads OBJ jako scénu rozdělenou podle objektů, skupin a přiřazených materiálů.
 */
    public ImportedScene loadScene(String filePath) {
        List<String> lines = FileUtil.readLines(filePath);
        List<Vec3> tempPositions = new ArrayList<>();
        List<Vec3> tempNormals = new ArrayList<>();
        List<Vec2> tempUVs = new ArrayList<>();
        Map<String, ScenePartBuilder> parts = new LinkedHashMap<>();
        Map<String, PhongMaterial> materials = ObjMaterialResolver.loadMaterialsForObj(filePath);

        String baseName = baseName(filePath);
        String currentObject = baseName;
        String currentGroup = null;
        String currentMaterial = null;
        boolean seenAnyFace = false;

        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("v ")) {
                String[] p = line.split("\\s+");
                tempPositions.add(new Vec3(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]),
                        Double.parseDouble(p[3])
                ));
                continue;
            }
            if (line.startsWith("vn ")) {
                String[] p = line.split("\\s+");
                tempNormals.add(new Vec3(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2]),
                        Double.parseDouble(p[3])
                ).normalize());
                continue;
            }
            if (line.startsWith("vt ")) {
                String[] p = line.split("\\s+");
                tempUVs.add(new Vec2(
                        Double.parseDouble(p[1]),
                        Double.parseDouble(p[2])
                ));
                continue;
            }
            if (line.startsWith("o ")) {
                String value = line.substring(2).trim();
                if (!value.isBlank()) {
                    currentObject = value;
                }
                continue;
            }
            if (line.startsWith("g ")) {
                String value = line.substring(2).trim();
                currentGroup = value.isBlank() ? null : value;
                continue;
            }
            if (line.startsWith("usemtl ")) {
                String value = line.substring("usemtl ".length()).trim();
                currentMaterial = value.isBlank() ? null : value;
                continue;
            }
            if (!line.startsWith("f ")) {
                continue;
            }
            seenAnyFace = true;
            String[] tokens = line.substring(2).trim().split("\\s+");
            if (tokens.length < 3) {
                continue;
            }
            String partName = composePartName(baseName, currentObject, currentGroup, currentMaterial);
            String key = partName + "\n" + (currentMaterial == null ? "" : currentMaterial);
            ScenePartBuilder builder = parts.get(key);
            if (builder == null) {
                builder = new ScenePartBuilder(partName, currentMaterial);
                parts.put(key, builder);
            }
            builder.addFace(tokens, tempPositions, tempNormals, tempUVs, this);
        }

        ImportedScene out = new ImportedScene();
        if (!seenAnyFace) {
            throw new RuntimeException("OBJ has no faces: " + filePath);
        }
        for (ScenePartBuilder part : parts.values()) {
            Mesh mesh = part.buildMesh();
            if (mesh == null) {
                continue;
            }
            PhongMaterial material = selectMaterialForPart(part.materialName, materials, filePath);
            out.addEntry(part.name, mesh, material == null ? null : material.copy());
        }
        if (out.isEmpty()) {
            throw new RuntimeException("OBJ has no usable mesh parts: " + filePath);
        }
        return out;
    }

 // Represents interní pomocné metody.
    private int[] parseFaceVertex(String token) {
        int[] out = new int[]{-1, -1, -1};
        String[] parts = token.split("/", -1);
        if (parts.length > 0 && !parts[0].isEmpty()) {
            out[0] = Integer.parseInt(parts[0]);
        }
        if (parts.length > 1 && !parts[1].isEmpty()) {
            out[1] = Integer.parseInt(parts[1]);
        }
        if (parts.length > 2 && !parts[2].isEmpty()) {
            out[2] = Integer.parseInt(parts[2]);
        }
        return out;
    }

    private int resolveIndex(int objIndex, int size) {
        if (objIndex > 0) {
            int out = objIndex - 1;
            if (out >= size) {
                throw new RuntimeException("OBJ index out of range: " + objIndex + " (size=" + size + ")");
            }
            return out;
        }
        if (objIndex < 0) {
            int out = size + objIndex;
            if (out < 0 || out >= size) {
                throw new RuntimeException("OBJ negative index out of range: " + objIndex + " (size=" + size + ")");
            }
            return out;
        }
        throw new RuntimeException("OBJ index 0 is invalid");
    }

    private static String composePartName(String baseName, String objectName, String groupName, String materialName) {
        List<String> segments = new ArrayList<>(2);
        appendNameSegment(segments, objectName, baseName);
        appendNameSegment(segments, groupName, null);
        StringBuilder out = new StringBuilder();
        for (String segment : segments) {
            if (segment == null || segment.isBlank()) {
                continue;
            }
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(segment);
        }
        if (out.length() == 0) {
            out.append(baseName);
        }
        if (materialName != null && !materialName.isBlank()) {
            out.append(" [").append(materialName.trim()).append(']');
        }
        return out.toString();
    }

    private static void appendNameSegment(List<String> segments, String value, String fallback) {
        String text = value;
        if (text == null || text.isBlank()) {
            text = fallback;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        String clean = text.trim();
        if (clean.isBlank()) {
            return;
        }
        if (segments.isEmpty() || !segments.get(segments.size() - 1).equals(clean)) {
            segments.add(clean);
        }
    }

    private static PhongMaterial selectMaterialForPart(String materialName,
                                                       Map<String, PhongMaterial> materials,
                                                       String filePath) {
        if (materials != null && materialName != null) {
            PhongMaterial material = materials.get(materialName);
            if (material != null) {
                return material;
            }
        }
        if (materials != null && materials.size() == 1) {
            return materials.values().iterator().next();
        }
        return ObjMaterialResolver.resolveForObj(filePath);
    }

    private static float[] toFloatArray(List<Float> values) {
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    private static String baseName(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}