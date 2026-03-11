package engine.geometry;

import java.util.Arrays;

/**
 * Tady převádím polygonální plochy na trojúhelníky.
 */
public class Triangulator {

    public static int[] triangulate(int[] faceIndices) {
        if (faceIndices == null || faceIndices.length < 3) {
            return new int[0];
        }
        if (faceIndices.length == 3) {
            return Arrays.copyOf(faceIndices, 3);
        }
        int triCount = faceIndices.length - 2;
        int[] out = new int[triCount * 3];
        int outIdx = 0;
        for (int i = 1; i < faceIndices.length - 1; i++) {
            out[outIdx++] = faceIndices[0];
            out[outIdx++] = faceIndices[i];
            out[outIdx++] = faceIndices[i + 1];
        }
        return out;
    }

    public static boolean isTriangulated(int[] indices) {
        return indices != null && indices.length >= 3 && indices.length % 3 == 0;
    }
}
