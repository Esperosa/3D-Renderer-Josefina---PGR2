package engine.render.raster;

import engine.math.Mat4;
import engine.math.MathUtil;
import engine.math.Vec3;
import engine.math.Vec4;

/**
 * Tady převádím vrcholy meshe z objektového prostoru do clip prostoru.
 * Pracuju nad plochými float poli, abych co nejvíc omezil alokace.
 */
public class VertexTransformer {

    private float[] clipPositions;
    private float[] screenPositions;
    private float[] worldPositions;
    private float[] worldNormals;
    private int capacity;

    public VertexTransformer(int maxVertices) {
        int safe = Math.max(1, maxVertices);
        this.capacity = safe;
        this.clipPositions = new float[safe * 4];
        this.screenPositions = new float[safe * 3];
        this.worldPositions = new float[safe * 3];
        this.worldNormals = new float[safe * 3];
    }

    /**
     * Tady transformuju vrcholy jedné entity.
     *
     * @param positions sem předám pozice v objektovém prostoru jako prokládané x,y,z
     * @param normals sem předám normály v objektovém prostoru jako prokládané x,y,z
     * @param vertexCount sem předám počet vrcholů
     * @param modelMatrix sem předám modelovou matici entity ve světovém prostoru
     * @param vpMatrix sem předám view * projection matici
     * @param normalMatrix sem předám horní levou 3x3 inverse-transpose část modelové matice
     * @param screenW sem předám šířku viewportu
     * @param screenH sem předám výšku viewportu
     */
    public void transform(float[] positions, float[] normals, int vertexCount,
                          Mat4 modelMatrix, Mat4 vpMatrix, Mat4 normalMatrix,
                          int screenW, int screenH) {
        if (positions == null || vertexCount <= 0 || modelMatrix == null || vpMatrix == null || normalMatrix == null) {
            return;
        }
        ensureCapacity(vertexCount);

        double width = Math.max(1, screenW) - 1.0;
        double height = Math.max(1, screenH) - 1.0;

        int maxVertices = Math.min(vertexCount, positions.length / 3);
        for (int i = 0; i < maxVertices; i++) {
            int pBase = i * 3;
            Vec3 pos = new Vec3(positions[pBase], positions[pBase + 1], positions[pBase + 2]);
            Vec4 world4 = modelMatrix.transform(new Vec4(pos, 1.0));
            double invWorldW = Math.abs(world4.w) > MathUtil.EPSILON ? 1.0 / world4.w : 1.0;
            double wx = world4.x * invWorldW;
            double wy = world4.y * invWorldW;
            double wz = world4.z * invWorldW;

            int wBase = i * 3;
            worldPositions[wBase] = (float) wx;
            worldPositions[wBase + 1] = (float) wy;
            worldPositions[wBase + 2] = (float) wz;

            Vec4 clip = vpMatrix.transform(new Vec4(wx, wy, wz, 1.0));
            int cBase = i * 4;
            clipPositions[cBase] = (float) clip.x;
            clipPositions[cBase + 1] = (float) clip.y;
            clipPositions[cBase + 2] = (float) clip.z;
            clipPositions[cBase + 3] = (float) clip.w;

            double invW = Math.abs(clip.w) > MathUtil.EPSILON ? 1.0 / clip.w : 0.0;
            double ndcX = clip.x * invW;
            double ndcY = clip.y * invW;
            double ndcZ = clip.z * invW;

            int sBase = i * 3;
            screenPositions[sBase] = (float) ((ndcX * 0.5 + 0.5) * width);
            screenPositions[sBase + 1] = (float) ((1.0 - (ndcY * 0.5 + 0.5)) * height);
            screenPositions[sBase + 2] = (float) (ndcZ * 0.5 + 0.5);

            Vec3 nObj;
            if (normals != null && pBase + 2 < normals.length) {
                nObj = new Vec3(normals[pBase], normals[pBase + 1], normals[pBase + 2]);
            } else {
                nObj = new Vec3(0.0, 1.0, 0.0);
            }
            Vec3 nWorld = normalMatrix.transformDirection(nObj).normalize();
            worldNormals[wBase] = (float) nWorld.x;
            worldNormals[wBase + 1] = (float) nWorld.y;
            worldNormals[wBase + 2] = (float) nWorld.z;
        }
    }

    public float getClipX(int vertexIndex) {
        return clipPositions[vertexIndex * 4];
    }

    public float getClipY(int vertexIndex) {
        return clipPositions[vertexIndex * 4 + 1];
    }

    public float getClipZ(int vertexIndex) {
        return clipPositions[vertexIndex * 4 + 2];
    }

    public float getClipW(int vertexIndex) {
        return clipPositions[vertexIndex * 4 + 3];
    }

    public float getScreenX(int vertexIndex) {
        return screenPositions[vertexIndex * 3];
    }

    public float getScreenY(int vertexIndex) {
        return screenPositions[vertexIndex * 3 + 1];
    }

    public float getScreenDepth(int vertexIndex) {
        return screenPositions[vertexIndex * 3 + 2];
    }

    public void getWorldPos(int vertexIndex, float[] out3) {
        if (out3 == null || out3.length < 3) {
            throw new IllegalArgumentException("out3 must have length >= 3");
        }
        int base = vertexIndex * 3;
        out3[0] = worldPositions[base];
        out3[1] = worldPositions[base + 1];
        out3[2] = worldPositions[base + 2];
    }

    public void getWorldNormal(int vertexIndex, float[] out3) {
        if (out3 == null || out3.length < 3) {
            throw new IllegalArgumentException("out3 must have length >= 3");
        }
        int base = vertexIndex * 3;
        out3[0] = worldNormals[base];
        out3[1] = worldNormals[base + 1];
        out3[2] = worldNormals[base + 2];
    }

    public float[] getClipPositions() {
        return clipPositions;
    }

    public float[] getScreenPositions() {
        return screenPositions;
    }

    public float[] getWorldPositions() {
        return worldPositions;
    }

    public float[] getWorldNormals() {
        return worldNormals;
    }

    private void ensureCapacity(int vertexCount) {
        if (vertexCount <= capacity) {
            return;
        }
        int next = capacity;
        while (next < vertexCount) {
            next *= 2;
        }
        capacity = next;
        clipPositions = new float[capacity * 4];
        screenPositions = new float[capacity * 3];
        worldPositions = new float[capacity * 3];
        worldNormals = new float[capacity * 3];
    }
}
