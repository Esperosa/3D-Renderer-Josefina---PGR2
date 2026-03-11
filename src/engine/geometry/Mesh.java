package engine.geometry;

import engine.math.AABB;
import engine.math.BoundingSphere;
import engine.math.Vec3;

/**
 * Tady držím trojúhelníkovou mesh ve flat polích kvůli rychlému průchodu v inner loopu.
 */
public class Mesh {

    private final String name;

    // Tady držím hlavní vertex data v SOA rozložení kvůli výkonu.
    private final float[] positions;
    private float[] normals;
    private float[] uvs;
    private float[] uv2s;
    private float[] colors;
    private float[] tangents;
    private final int vertexCount;

    // Tady držím indexová data.
    private final int[] indices;
    private final int triangleCount;

    // Tady držím předpočítané bounding volumes.
    private AABB aabb;
    private BoundingSphere bounds;

    // Tady řeším konstrukci meshe.
    public Mesh(String name, float[] positions, float[] normals, int[] indices) {
        if (positions == null || positions.length % 3 != 0) {
            throw new IllegalArgumentException("positions must be non-null and x/y/z interleaved");
        }
        if (indices == null || indices.length % 3 != 0) {
            throw new IllegalArgumentException("indices must be non-null triangles");
        }
        this.name = name == null ? "Mesh" : name;
        this.positions = positions;
        this.normals = normals;
        this.indices = sanitizeIndices(indices, positions.length / 3);
        this.vertexCount = positions.length / 3;
        this.triangleCount = this.indices.length / 3;
        if (this.normals == null || this.normals.length != positions.length) {
            this.normals = new float[positions.length];
            computeNormals();
        }
        computeBounds();
    }

    public void setUVs(float[] uvs) {
        this.uvs = uvs;
    }

    public void setUV2s(float[] uv2s) {
        this.uv2s = uv2s;
    }

    public void setColors(float[] colors) {
        this.colors = colors;
    }

    public void setTangents(float[] tangents) {
        this.tangents = tangents;
    }

    // Tady držím přístupové metody.
    public Vec3 getPosition(int vertexIndex) {
        int base = vertexIndex * 3;
        return new Vec3(positions[base], positions[base + 1], positions[base + 2]);
    }

    public Vec3 getNormal(int vertexIndex) {
        int base = vertexIndex * 3;
        return new Vec3(normals[base], normals[base + 1], normals[base + 2]);
    }

    public void getTriangleIndices(int triangleIndex, int[] out3) {
        int base = triangleIndex * 3;
        out3[0] = indices[base];
        out3[1] = indices[base + 1];
        out3[2] = indices[base + 2];
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getTriangleCount() {
        return triangleCount;
    }

    public AABB getAABB() {
        return aabb;
    }

    public BoundingSphere getBounds() {
        return bounds;
    }

    public String getName() {
        return name;
    }

    public float[] getPositions() {
        return positions;
    }

    public float[] getNormals() {
        return normals;
    }

    public float[] getUVs() {
        return uvs;
    }

    public float[] getUV2s() {
        return uv2s;
    }

    public float[] getColors() {
        return colors;
    }

    public float[] getTangents() {
        return tangents;
    }

    public int[] getIndices() {
        return indices;
    }

    // Tady provádím odvozené výpočty nad meshem.
    public final void computeNormals() {
        if (normals == null || normals.length != positions.length) {
            normals = new float[positions.length];
        } else {
            for (int i = 0; i < normals.length; i++) {
                normals[i] = 0.0f;
            }
        }

        for (int t = 0; t < indices.length; t += 3) {
            int i0 = indices[t];
            int i1 = indices[t + 1];
            int i2 = indices[t + 2];

            Vec3 p0 = getPosition(i0);
            Vec3 p1 = getPosition(i1);
            Vec3 p2 = getPosition(i2);
            Vec3 n = p1.sub(p0).cross(p2.sub(p0)).normalize();

            addNormal(i0, n);
            addNormal(i1, n);
            addNormal(i2, n);
        }

        for (int i = 0; i < vertexCount; i++) {
            int base = i * 3;
            Vec3 n = new Vec3(normals[base], normals[base + 1], normals[base + 2]).normalize();
            normals[base] = (float) n.x;
            normals[base + 1] = (float) n.y;
            normals[base + 2] = (float) n.z;
        }
    }

    private void addNormal(int vertexIndex, Vec3 n) {
        int base = vertexIndex * 3;
        normals[base] += (float) n.x;
        normals[base + 1] += (float) n.y;
        normals[base + 2] += (float) n.z;
    }

    public void computeTangents() {
        if (uvs == null || uvs.length != vertexCount * 2) {
            return;
        }
        tangents = new float[vertexCount * 3];
        for (int t = 0; t < indices.length; t += 3) {
            int i0 = indices[t];
            int i1 = indices[t + 1];
            int i2 = indices[t + 2];

            Vec3 p0 = getPosition(i0);
            Vec3 p1 = getPosition(i1);
            Vec3 p2 = getPosition(i2);

            int uv0i = i0 * 2;
            int uv1i = i1 * 2;
            int uv2i = i2 * 2;
            float du1 = uvs[uv1i] - uvs[uv0i];
            float dv1 = uvs[uv1i + 1] - uvs[uv0i + 1];
            float du2 = uvs[uv2i] - uvs[uv0i];
            float dv2 = uvs[uv2i + 1] - uvs[uv0i + 1];

            double denom = du1 * dv2 - du2 * dv1;
            if (Math.abs(denom) < 1e-8) {
                continue;
            }
            double r = 1.0 / denom;
            Vec3 tangent = p1.sub(p0).mul(dv2).sub(p2.sub(p0).mul(dv1)).mul(r).normalize();

            addTangent(i0, tangent);
            addTangent(i1, tangent);
            addTangent(i2, tangent);
        }
        for (int i = 0; i < vertexCount; i++) {
            int base = i * 3;
            Vec3 tangent = new Vec3(tangents[base], tangents[base + 1], tangents[base + 2]).normalize();
            tangents[base] = (float) tangent.x;
            tangents[base + 1] = (float) tangent.y;
            tangents[base + 2] = (float) tangent.z;
        }
    }

    private void addTangent(int vertexIndex, Vec3 tangent) {
        int base = vertexIndex * 3;
        tangents[base] += (float) tangent.x;
        tangents[base + 1] += (float) tangent.y;
        tangents[base + 2] += (float) tangent.z;
    }

    public final void computeBounds() {
        if (vertexCount == 0) {
            aabb = new AABB(Vec3.ZERO, Vec3.ZERO);
            bounds = new BoundingSphere(Vec3.ZERO, 0.0);
            return;
        }

        Vec3 min = new Vec3(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
        Vec3 max = new Vec3(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY);
        Vec3[] pts = new Vec3[vertexCount];
        for (int i = 0; i < vertexCount; i++) {
            Vec3 p = getPosition(i);
            pts[i] = p;
            min = Vec3.min(min, p);
            max = Vec3.max(max, p);
        }
        aabb = new AABB(min, max);
        bounds = BoundingSphere.fromPoints(pts);
    }

    private static int[] sanitizeIndices(int[] srcIndices, int vertexCount) {
        int valid = 0;
        for (int i = 0; i < srcIndices.length; i += 3) {
            int i0 = srcIndices[i];
            int i1 = srcIndices[i + 1];
            int i2 = srcIndices[i + 2];
            if (isValidIndex(i0, vertexCount) && isValidIndex(i1, vertexCount) && isValidIndex(i2, vertexCount)) {
                valid += 3;
            }
        }
        if (valid == srcIndices.length) {
            return srcIndices;
        }
        int[] sanitized = new int[valid];
        int dst = 0;
        for (int i = 0; i < srcIndices.length; i += 3) {
            int i0 = srcIndices[i];
            int i1 = srcIndices[i + 1];
            int i2 = srcIndices[i + 2];
            if (isValidIndex(i0, vertexCount) && isValidIndex(i1, vertexCount) && isValidIndex(i2, vertexCount)) {
                sanitized[dst++] = i0;
                sanitized[dst++] = i1;
                sanitized[dst++] = i2;
            }
        }
        return sanitized;
    }

    private static boolean isValidIndex(int index, int vertexCount) {
        return index >= 0 && index < vertexCount;
    }
}
