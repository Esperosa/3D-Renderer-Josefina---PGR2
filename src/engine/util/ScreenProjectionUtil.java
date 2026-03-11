package engine.util;

import engine.math.Mat4;
import engine.math.Vec3;
import engine.math.Vec4;

public final class ScreenProjectionUtil {

    private ScreenProjectionUtil() {
    }

    public static boolean projectWorldPoint(Vec3 world, Mat4 vp, int width, int height, int[] out) {
        return projectWorldPointWithDepth(world, vp, width, height, out, null);
    }

    public static boolean projectWorldPointWithDepth(
            Vec3 world, Mat4 vp, int width, int height, int[] out, double[] depthOut) {
        Vec4 clip = vp.transform(new Vec4(world, 1.0));
        if (Math.abs(clip.w) < 1e-10 || clip.w <= 1e-6) {
            return false;
        }
        Vec3 ndc = clip.perspectiveDivide();
        if (ndc.x < -1.2 || ndc.x > 1.2 || ndc.y < -1.2 || ndc.y > 1.2 || ndc.z < -0.2 || ndc.z > 1.2) {
            return false;
        }
        out[0] = (int) Math.round((ndc.x * 0.5 + 0.5) * (width - 1));
        out[1] = (int) Math.round((1.0 - (ndc.y * 0.5 + 0.5)) * (height - 1));
        if (depthOut != null && depthOut.length > 0) {
            depthOut[0] = ndc.z * 0.5 + 0.5;
        }
        return true;
    }

    public static boolean projectVertex(
            int vertexIndex,
            float[] positions,
            Mat4 model,
            Mat4 vp,
            int width,
            int height,
            float[] sx,
            float[] sy,
            float[] sz,
            int slot) {
        int p = vertexIndex * 3;
        if (p < 0 || p + 2 >= positions.length) {
            return false;
        }
        Vec3 world = model.transformPoint(new Vec3(positions[p], positions[p + 1], positions[p + 2]));
        Vec4 clip = vp.transform(new Vec4(world, 1.0));
        if (clip.w <= 1e-6 || Math.abs(clip.w) < 1e-10) {
            return false;
        }
        Vec3 ndc = clip.perspectiveDivide();
        if (ndc.x < -1.2 || ndc.x > 1.2 || ndc.y < -1.2 || ndc.y > 1.2) {
            return false;
        }
        sx[slot] = (float) ((ndc.x * 0.5 + 0.5) * (width - 1));
        sy[slot] = (float) ((1.0 - (ndc.y * 0.5 + 0.5)) * (height - 1));
        sz[slot] = (float) (ndc.z * 0.5 + 0.5);
        return true;
    }

    public static void rasterizeSelectionTriangle(
            float x0,
            float y0,
            float z0,
            float x1,
            float y1,
            float z1,
            float x2,
            float y2,
            float z2,
            int width,
            int height,
            float[] sceneDepth,
            float[] selectedDepth,
            byte[] coverage) {
        float area = edgeFunction2D(x0, y0, x1, y1, x2, y2);
        if (Math.abs(area) < 1e-6f) {
            return;
        }

        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));
        float invArea = 1.0f / area;

        for (int y = minY; y <= maxY; y++) {
            float py = y + 0.5f;
            int row = y * width;
            for (int x = minX; x <= maxX; x++) {
                float px = x + 0.5f;
                float w0 = edgeFunction2D(x1, y1, x2, y2, px, py);
                float w1 = edgeFunction2D(x2, y2, x0, y0, px, py);
                float w2 = edgeFunction2D(x0, y0, x1, y1, px, py);

                boolean inside = (w0 >= 0.0f && w1 >= 0.0f && w2 >= 0.0f)
                        || (w0 <= 0.0f && w1 <= 0.0f && w2 <= 0.0f);
                if (!inside) {
                    continue;
                }

                float b0 = w0 * invArea;
                float b1 = w1 * invArea;
                float b2 = w2 * invArea;
                float depth = z0 * b0 + z1 * b1 + z2 * b2;
                if (depth < 0.0f || depth > 1.0f) {
                    continue;
                }

                int idx = row + x;
                if (depth > sceneDepth[idx] + 5e-4f) {
                    continue;
                }
                if (depth < selectedDepth[idx]) {
                    selectedDepth[idx] = depth;
                    coverage[idx] = 1;
                }
            }
        }
    }

    public static float edgeFunction2D(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }
}
