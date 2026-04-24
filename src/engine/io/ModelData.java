package engine.io;

import engine.geometry.Mesh;

import java.util.Arrays;

/**
 * Represents mezikontejner pro naparsovaná modelová data před sestavením meshe.
 * Používám ho tam, kde si načítač potřebuje data nasbírat přes více průchodů.
 */
public class ModelData {

    private float[] positions;
    private float[] normals;
    private float[] uvs;
    private float[] colors;
    private int[] indices;

    private int vertexCount;
    private int triangleCount;
    private String name;

    public ModelData() {
        this("Mesh", new float[0], null, null, null, new int[0]);
    }

    public ModelData(String name,
                     float[] positions,
                     float[] normals,
                     float[] uvs,
                     float[] colors,
                     int[] indices) {
        this.name = name == null ? "Mesh" : name;
        this.positions = positions == null ? new float[0] : positions;
        this.normals = normals;
        this.uvs = uvs;
        this.colors = colors;
        this.indices = indices == null ? new int[0] : indices;
        refreshCounts();
    }

 /**
 * převedu dokončená data na instanci meshe.
 *
 * @return tím vrátí nový mesh s pozicemi, normálami, indexy a spočítanými bounds
 */
    public Mesh toMesh() {
        if (positions == null || positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalStateException("ModelData.positions must be non-empty and xyz-interleaved");
        }
        if (indices == null || indices.length == 0 || indices.length % 3 != 0) {
            throw new IllegalStateException("ModelData.indices must be non-empty triangles");
        }
        refreshCounts();

        float[] posCopy = Arrays.copyOf(positions, positions.length);
        float[] nrmCopy = (normals != null && normals.length == posCopy.length)
                ? Arrays.copyOf(normals, normals.length)
                : null;
        int[] idxCopy = Arrays.copyOf(indices, indices.length);

        Mesh mesh = new Mesh(name, posCopy, nrmCopy, idxCopy);

        if (uvs != null && uvs.length == vertexCount * 2) {
            mesh.setUVs(Arrays.copyOf(uvs, uvs.length));
        }
        if (colors != null && colors.length == vertexCount * 3) {
            mesh.setColors(Arrays.copyOf(colors, colors.length));
        }

        mesh.computeBounds();
        return mesh;
    }

    public float[] getPositions() {
        return positions;
    }

    public void setPositions(float[] positions) {
        this.positions = positions == null ? new float[0] : positions;
        refreshCounts();
    }

    public float[] getNormals() {
        return normals;
    }

    public void setNormals(float[] normals) {
        this.normals = normals;
    }

    public float[] getUvs() {
        return uvs;
    }

    public void setUvs(float[] uvs) {
        this.uvs = uvs;
    }

    public float[] getColors() {
        return colors;
    }

    public void setColors(float[] colors) {
        this.colors = colors;
    }

    public int[] getIndices() {
        return indices;
    }

    public void setIndices(int[] indices) {
        this.indices = indices == null ? new int[0] : indices;
        refreshCounts();
    }

    public int getVertexCount() {
        return vertexCount;
    }

    public int getTriangleCount() {
        return triangleCount;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name == null ? "Mesh" : name;
    }

    private void refreshCounts() {
        vertexCount = positions == null ? 0 : positions.length / 3;
        triangleCount = indices == null ? 0 : indices.length / 3;
    }
}