package engine.io;

import engine.geometry.Mesh;
import engine.math.Vec3;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Loads STL soubory v binární i textové ASCII podobě.
 */
public class StlLoader {

    public Mesh load(String filePath) {
        byte[] bytes = FileUtil.readBytes(filePath);
        if (bytes.length < 6) {
            throw new RuntimeException("STL file too small: " + filePath);
        }

        Mesh mesh;
        if (isBinaryStl(bytes)) {
            mesh = parseBinary(bytes, filePath);
        } else {
            mesh = parseAscii(bytes, filePath);
        }
        mesh.computeBounds();
        return mesh;
    }

    private boolean isBinaryStl(byte[] bytes) {
        if (bytes.length < 84) {
            return false;
        }
        long triCount = readU32LE(bytes, 80);
        long expected = 84L + triCount * 50L;
        if (expected == bytes.length) {
            return true;
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 256), StandardCharsets.US_ASCII).toLowerCase();
        if (head.startsWith("solid") && head.contains("\nfacet")) {
            return false;
        }
        return triCount > 0;
    }

    private Mesh parseBinary(byte[] bytes, String filePath) {
        long triCountLong = readU32LE(bytes, 80);
        if (triCountLong <= 0L || triCountLong > Integer.MAX_VALUE / 3) {
            throw new RuntimeException("Unsupported STL triangle count: " + triCountLong);
        }
        int triCount = (int) triCountLong;
        int expected = 84 + triCount * 50;
        if (bytes.length < expected) {
            throw new RuntimeException("Corrupt binary STL (truncated): " + filePath);
        }

        float[] positions = new float[triCount * 9];
        float[] normals = new float[triCount * 9];
        int[] indices = new int[triCount * 3];

        int offset = 84;
        for (int t = 0; t < triCount; t++) {
            float nx = readF32LE(bytes, offset);
            float ny = readF32LE(bytes, offset + 4);
            float nz = readF32LE(bytes, offset + 8);
            offset += 12;

            float x0 = readF32LE(bytes, offset);
            float y0 = readF32LE(bytes, offset + 4);
            float z0 = readF32LE(bytes, offset + 8);
            offset += 12;
            float x1 = readF32LE(bytes, offset);
            float y1 = readF32LE(bytes, offset + 4);
            float z1 = readF32LE(bytes, offset + 8);
            offset += 12;
            float x2 = readF32LE(bytes, offset);
            float y2 = readF32LE(bytes, offset + 4);
            float z2 = readF32LE(bytes, offset + 8);
            offset += 12;

            offset += 2; // Skips dvoubajtový atributový blok STL trojúhelníku.

            Vec3 normal = new Vec3(nx, ny, nz);
            if (normal.lengthSquared() < 1e-14) {
                Vec3 p0 = new Vec3(x0, y0, z0);
                Vec3 p1 = new Vec3(x1, y1, z1);
                Vec3 p2 = new Vec3(x2, y2, z2);
                normal = p1.sub(p0).cross(p2.sub(p0)).normalize();
            } else {
                normal = normal.normalize();
            }

            int pv = t * 9;
            positions[pv] = x0;
            positions[pv + 1] = y0;
            positions[pv + 2] = z0;
            positions[pv + 3] = x1;
            positions[pv + 4] = y1;
            positions[pv + 5] = z1;
            positions[pv + 6] = x2;
            positions[pv + 7] = y2;
            positions[pv + 8] = z2;

            for (int k = 0; k < 3; k++) {
                int n = pv + k * 3;
                normals[n] = (float) normal.x;
                normals[n + 1] = (float) normal.y;
                normals[n + 2] = (float) normal.z;
            }

            int iv = t * 3;
            indices[iv] = iv;
            indices[iv + 1] = iv + 1;
            indices[iv + 2] = iv + 2;
        }

        return new Mesh(baseName(filePath), positions, normals, indices);
    }

    private Mesh parseAscii(byte[] bytes, String filePath) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        String[] lines = text.split("\\R");
        List<Float> outPositions = new ArrayList<>();
        List<Float> outNormals = new ArrayList<>();
        List<Integer> outIndices = new ArrayList<>();

        Vec3 currentNormal = new Vec3(0.0, 1.0, 0.0);
        int vertexInFacet = 0;
        int facetStartIndex = 0;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("facet normal")) {
                String[] p = line.split("\\s+");
                if (p.length >= 5) {
                    currentNormal = new Vec3(
                            parseDoubleSafe(p[2], 0.0),
                            parseDoubleSafe(p[3], 1.0),
                            parseDoubleSafe(p[4], 0.0)
                    ).normalize();
                }
                vertexInFacet = 0;
                facetStartIndex = outPositions.size() / 3;
                continue;
            }
            if (line.startsWith("vertex")) {
                String[] p = line.split("\\s+");
                if (p.length < 4) {
                    continue;
                }
                outPositions.add((float) parseDoubleSafe(p[1], 0.0));
                outPositions.add((float) parseDoubleSafe(p[2], 0.0));
                outPositions.add((float) parseDoubleSafe(p[3], 0.0));
                outNormals.add((float) currentNormal.x);
                outNormals.add((float) currentNormal.y);
                outNormals.add((float) currentNormal.z);
                vertexInFacet++;
                if (vertexInFacet == 3) {
                    outIndices.add(facetStartIndex);
                    outIndices.add(facetStartIndex + 1);
                    outIndices.add(facetStartIndex + 2);
                }
            }
        }

        if (outIndices.isEmpty()) {
            throw new RuntimeException("ASCII STL contains no triangles: " + filePath);
        }

        float[] positions = toFloatArray(outPositions);
        float[] normals = toFloatArray(outNormals);
        int[] indices = toIntArray(outIndices);
        return new Mesh(baseName(filePath), positions, normals, indices);
    }

    private static double parseDoubleSafe(String text, double fallback) {
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static float[] toFloatArray(List<Float> source) {
        float[] out = new float[source.size()];
        for (int i = 0; i < source.size(); i++) {
            out[i] = source.get(i);
        }
        return out;
    }

    private static int[] toIntArray(List<Integer> source) {
        int[] out = new int[source.size()];
        for (int i = 0; i < source.size(); i++) {
            out[i] = source.get(i);
        }
        return out;
    }

    private static String baseName(String filePath) {
        String name = Path.of(filePath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static long readU32LE(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 0xFF))
                | (((long) (bytes[offset + 1] & 0xFF)) << 8)
                | (((long) (bytes[offset + 2] & 0xFF)) << 16)
                | (((long) (bytes[offset + 3] & 0xFF)) << 24);
    }

    private static float readF32LE(byte[] bytes, int offset) {
        int raw = (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
        return Float.intBitsToFloat(raw);
    }
}
