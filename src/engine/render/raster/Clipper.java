package engine.render.raster;

/**
 * ořezávám trojúhelníky proti view frustu, minimálně proti near plane.
 * Z jednoho vstupního trojúhelníku si tím vrátí 0, 1 nebo 2 trojúhelníky.
 */
public class Clipper {

    private static final int MAX_CLIPPED_VERTS = 4;
    private static final int MAX_TRI_VERTS = 6;

    private final float[] outPositions;
    private final float[] outNormals;
    private final float[] outWorldPos;
    private int outVertexCount;

    public Clipper() {
        this.outPositions = new float[MAX_TRI_VERTS * 4];
        this.outNormals = new float[MAX_TRI_VERTS * 3];
        this.outWorldPos = new float[MAX_TRI_VERTS * 3];
        this.outVertexCount = 0;
    }

 /**
 * oříznu jeden trojúhelník proti near plane v clip prostoru.
 * Používám k tomu Sutherland-Hodgman algoritmus.
 *
 * @param clipX hodnoty x v clip prostoru pro 3 vrcholy
 * @param clipY hodnoty y v clip prostoru
 * @param clipZ hodnoty z v clip prostoru
 * @param clipW hodnoty w v clip prostoru
 * @param attrs pole atributů po vrcholech k interpolaci, třeba normály, UV a world pozice
 * @return tím vrátí počet výstupních trojúhelníků, tedy 0, 1 nebo 2
 */
    public int clipTriangleNear(float[] clipX, float[] clipY, float[] clipZ, float[] clipW,
                                float[][] attrs) {
        if (clipX == null || clipY == null || clipZ == null || clipW == null
                || clipX.length < 3 || clipY.length < 3 || clipZ.length < 3 || clipW.length < 3) {
            outVertexCount = 0;
            return 0;
        }

        int attrCount = attrs == null ? 0 : attrs.length;
        float[][] inAttrs = attrCount > 0 ? new float[attrCount][MAX_CLIPPED_VERTS] : null;
        float[][] tmpAttrs = attrCount > 0 ? new float[attrCount][MAX_CLIPPED_VERTS] : null;

        float[] inX = new float[MAX_CLIPPED_VERTS];
        float[] inY = new float[MAX_CLIPPED_VERTS];
        float[] inZ = new float[MAX_CLIPPED_VERTS];
        float[] inW = new float[MAX_CLIPPED_VERTS];
        float[] tmpX = new float[MAX_CLIPPED_VERTS];
        float[] tmpY = new float[MAX_CLIPPED_VERTS];
        float[] tmpZ = new float[MAX_CLIPPED_VERTS];
        float[] tmpW = new float[MAX_CLIPPED_VERTS];

        for (int i = 0; i < 3; i++) {
            inX[i] = clipX[i];
            inY[i] = clipY[i];
            inZ[i] = clipZ[i];
            inW[i] = clipW[i];
            if (attrCount > 0) {
                for (int a = 0; a < attrCount; a++) {
                    float[] src = attrs[a];
                    inAttrs[a][i] = (src != null && src.length > i) ? src[i] : 0.0f;
                }
            }
        }

        int inCount = 3;
        int outCount = 0;
        for (int i = 0; i < inCount; i++) {
            int j = (i + 1) % inCount;
            float cx = inX[i];
            float cy = inY[i];
            float cz = inZ[i];
            float cw = inW[i];
            float nx = inX[j];
            float ny = inY[j];
            float nz = inZ[j];
            float nw = inW[j];

            float d0 = cz + cw;
            float d1 = nz + nw;
            boolean inside0 = d0 >= 0.0f;
            boolean inside1 = d1 >= 0.0f;

            if (inside0 && inside1) {
                tmpX[outCount] = nx;
                tmpY[outCount] = ny;
                tmpZ[outCount] = nz;
                tmpW[outCount] = nw;
                copyAttrs(tmpAttrs, outCount, inAttrs, j);
                outCount++;
            } else if (inside0) {
                float t = d0 / (d0 - d1);
                tmpX[outCount] = lerpAttribute(cx, nx, t);
                tmpY[outCount] = lerpAttribute(cy, ny, t);
                tmpZ[outCount] = lerpAttribute(cz, nz, t);
                tmpW[outCount] = lerpAttribute(cw, nw, t);
                lerpAttrs(tmpAttrs, outCount, inAttrs, i, j, t);
                outCount++;
            } else if (inside1) {
                float t = d0 / (d0 - d1);
                tmpX[outCount] = lerpAttribute(cx, nx, t);
                tmpY[outCount] = lerpAttribute(cy, ny, t);
                tmpZ[outCount] = lerpAttribute(cz, nz, t);
                tmpW[outCount] = lerpAttribute(cw, nw, t);
                lerpAttrs(tmpAttrs, outCount, inAttrs, i, j, t);
                outCount++;

                tmpX[outCount] = nx;
                tmpY[outCount] = ny;
                tmpZ[outCount] = nz;
                tmpW[outCount] = nw;
                copyAttrs(tmpAttrs, outCount, inAttrs, j);
                outCount++;
            }
        }

        if (outCount < 3) {
            outVertexCount = 0;
            return 0;
        }

        outVertexCount = 0;
        for (int i = 1; i < outCount - 1; i++) {
            addOutputVertex(tmpX, tmpY, tmpZ, tmpW, tmpAttrs, attrCount, 0);
            addOutputVertex(tmpX, tmpY, tmpZ, tmpW, tmpAttrs, attrCount, i);
            addOutputVertex(tmpX, tmpY, tmpZ, tmpW, tmpAttrs, attrCount, i + 1);
        }

        return outVertexCount / 3;
    }

    public float[] getOutPositions() {
        return outPositions;
    }

    public float[] getOutNormals() {
        return outNormals;
    }

    public float[] getOutWorldPos() {
        return outWorldPos;
    }

    public int getOutVertexCount() {
        return outVertexCount;
    }

    public static float lerpAttribute(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static void copyAttrs(float[][] dst, int dstIndex, float[][] src, int srcIndex) {
        if (dst == null || src == null) {
            return;
        }
        for (int a = 0; a < dst.length; a++) {
            dst[a][dstIndex] = src[a][srcIndex];
        }
    }

    private static void lerpAttrs(float[][] dst, int dstIndex, float[][] src, int i0, int i1, float t) {
        if (dst == null || src == null) {
            return;
        }
        for (int a = 0; a < dst.length; a++) {
            float va = src[a][i0];
            float vb = src[a][i1];
            dst[a][dstIndex] = lerpAttribute(va, vb, t);
        }
    }

    private void addOutputVertex(float[] x, float[] y, float[] z, float[] w,
                                 float[][] attrs, int attrCount, int srcIndex) {
        int outPosBase = outVertexCount * 4;
        outPositions[outPosBase] = x[srcIndex];
        outPositions[outPosBase + 1] = y[srcIndex];
        outPositions[outPosBase + 2] = z[srcIndex];
        outPositions[outPosBase + 3] = w[srcIndex];

        int outBase3 = outVertexCount * 3;
        if (attrCount >= 3) {
            outWorldPos[outBase3] = attrs[0][srcIndex];
            outWorldPos[outBase3 + 1] = attrs[1][srcIndex];
            outWorldPos[outBase3 + 2] = attrs[2][srcIndex];
        } else {
            outWorldPos[outBase3] = 0.0f;
            outWorldPos[outBase3 + 1] = 0.0f;
            outWorldPos[outBase3 + 2] = 0.0f;
        }

        if (attrCount >= 6) {
            outNormals[outBase3] = attrs[3][srcIndex];
            outNormals[outBase3 + 1] = attrs[4][srcIndex];
            outNormals[outBase3 + 2] = attrs[5][srcIndex];
        } else {
            outNormals[outBase3] = 0.0f;
            outNormals[outBase3 + 1] = 0.0f;
            outNormals[outBase3 + 2] = 0.0f;
        }

        outVertexCount++;
    }
}