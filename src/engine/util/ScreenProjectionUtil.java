package engine.util;

import java.util.ArrayList;
import java.util.List;

import engine.math.Mat4;
import engine.math.Vec3;
import engine.math.Vec4;

public final class ScreenProjectionUtil {
    private static final double CLIP_EPS = 1e-6;

    private interface ClipDistance {
        double eval(ClipVertex v);
    }

    private static final class ClipVertex {
        double x;
        double y;
        double z;
        double w;

        ClipVertex(double x, double y, double z, double w) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.w = w;
        }
    }

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
        double invW = 1.0 / clip.w;
        Vec3 ndc = new Vec3(clip.x * invW, clip.y * invW, clip.z * invW);
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
        double invW = 1.0 / clip.w;
        Vec3 ndc = new Vec3(clip.x * invW, clip.y * invW, clip.z * invW);
        if (!Double.isFinite(ndc.x) || !Double.isFinite(ndc.y) || !Double.isFinite(ndc.z)) {
            return false;
        }
        if (Math.abs(ndc.x) > 4.0 || Math.abs(ndc.y) > 4.0) {
            return false;
        }
        sx[slot] = (float) ((ndc.x * 0.5 + 0.5) * (width - 1));
        sy[slot] = (float) ((1.0 - (ndc.y * 0.5 + 0.5)) * (height - 1));
        sz[slot] = (float) (ndc.z * 0.5 + 0.5);
        return true;
    }

    public static void rasterizeSelectionTriangleNoDepthTest(
            int i0,
            int i1,
            int i2,
            float[] positions,
            Mat4 model,
            Mat4 vp,
            int width,
            int height,
            byte[] coverage) {
        ClipVertex a = toClipVertex(i0, positions, model, vp);
        ClipVertex b = toClipVertex(i1, positions, model, vp);
        ClipVertex c = toClipVertex(i2, positions, model, vp);
        if (a == null || b == null || c == null) {
            return;
        }

        List<ClipVertex> poly = new ArrayList<>(3);
        poly.add(a);
        poly.add(b);
        poly.add(c);

        poly = clipAgainstPlane(poly, v -> v.x + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.x - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.y + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.y - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.z + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.z - CLIP_EPS);

        if (poly.size() < 3) {
            return;
        }

        float[] sx = new float[3];
        float[] sy = new float[3];
        float[] sz = new float[3];
        ClipVertex v0 = poly.get(0);
        for (int i = 1; i < poly.size() - 1; i++) {
            ClipVertex v1 = poly.get(i);
            ClipVertex v2 = poly.get(i + 1);
            if (!toScreen(v0, width, height, sx, sy, sz, 0)) {
                continue;
            }
            if (!toScreen(v1, width, height, sx, sy, sz, 1)) {
                continue;
            }
            if (!toScreen(v2, width, height, sx, sy, sz, 2)) {
                continue;
            }

            rasterizeSelectionTriangleScreenSpace(
                    sx[0], sy[0],
                    sx[1], sy[1],
                    sx[2], sy[2],
                    width, height,
                    coverage
            );
        }
    }

    private static void rasterizeSelectionTriangleScreenSpace(
            float x0,
            float y0,
            float x1,
            float y1,
            float x2,
            float y2,
            int width,
            int height,
            byte[] coverage) {
        float area = edgeFunction2D(x0, y0, x1, y1, x2, y2);
        if (Math.abs(area) < 1e-6f) {
            return;
        }

        int minX = Math.max(0, (int) Math.floor(Math.min(x0, Math.min(x1, x2))));
        int maxX = Math.min(width - 1, (int) Math.ceil(Math.max(x0, Math.max(x1, x2))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y0, Math.min(y1, y2))));
        int maxY = Math.min(height - 1, (int) Math.ceil(Math.max(y0, Math.max(y1, y2))));

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

                int idx = row + x;
                coverage[idx] = 1;
            }
        }
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
                coverage[idx] = 1;
            }
        }
    }

    public static void rasterizeSelectionTriangleClipped(
            int i0,
            int i1,
            int i2,
            float[] positions,
            Mat4 model,
            Mat4 vp,
            int width,
            int height,
            float[] sceneDepth,
            byte[] coverage) {
        ClipVertex a = toClipVertex(i0, positions, model, vp);
        ClipVertex b = toClipVertex(i1, positions, model, vp);
        ClipVertex c = toClipVertex(i2, positions, model, vp);
        if (a == null || b == null || c == null) {
            return;
        }

        List<ClipVertex> poly = new ArrayList<>(3);
        poly.add(a);
        poly.add(b);
        poly.add(c);

        poly = clipAgainstPlane(poly, v -> v.x + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.x - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.y + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.y - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.z + v.w - CLIP_EPS);
        poly = clipAgainstPlane(poly, v -> v.w - v.z - CLIP_EPS);

        if (poly.size() < 3) {
            return;
        }

        float[] sx = new float[3];
        float[] sy = new float[3];
        float[] sz = new float[3];
        ClipVertex v0 = poly.get(0);
        for (int i = 1; i < poly.size() - 1; i++) {
            ClipVertex v1 = poly.get(i);
            ClipVertex v2 = poly.get(i + 1);
            if (!toScreen(v0, width, height, sx, sy, sz, 0)) {
                continue;
            }
            if (!toScreen(v1, width, height, sx, sy, sz, 1)) {
                continue;
            }
            if (!toScreen(v2, width, height, sx, sy, sz, 2)) {
                continue;
            }

            rasterizeSelectionTriangle(
                    sx[0], sy[0], sz[0],
                    sx[1], sy[1], sz[1],
                    sx[2], sy[2], sz[2],
                    width, height,
                    sceneDepth,
                    coverage
            );
        }
    }

    private static ClipVertex toClipVertex(int vertexIndex, float[] positions, Mat4 model, Mat4 vp) {
        int p = vertexIndex * 3;
        if (p < 0 || p + 2 >= positions.length) {
            return null;
        }
        Vec3 world = model.transformPoint(new Vec3(positions[p], positions[p + 1], positions[p + 2]));
        Vec4 clip = vp.transform(new Vec4(world, 1.0));
        if (!Double.isFinite(clip.x) || !Double.isFinite(clip.y) || !Double.isFinite(clip.z) || !Double.isFinite(clip.w)) {
            return null;
        }
        return new ClipVertex(clip.x, clip.y, clip.z, clip.w);
    }

    private static boolean toScreen(
            ClipVertex clip,
            int width,
            int height,
            float[] sx,
            float[] sy,
            float[] sz,
            int slot) {
        if (Math.abs(clip.w) < CLIP_EPS) {
            return false;
        }
        double invW = 1.0 / clip.w;
        double ndcX = clip.x * invW;
        double ndcY = clip.y * invW;
        double ndcZ = clip.z * invW;
        if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY) || !Double.isFinite(ndcZ)) {
            return false;
        }
        sx[slot] = (float) ((ndcX * 0.5 + 0.5) * (width - 1));
        sy[slot] = (float) ((1.0 - (ndcY * 0.5 + 0.5)) * (height - 1));
        sz[slot] = (float) (ndcZ * 0.5 + 0.5);
        return true;
    }

    private static List<ClipVertex> clipAgainstPlane(List<ClipVertex> input, ClipDistance distance) {
        if (input.isEmpty()) {
            return input;
        }
        List<ClipVertex> output = new ArrayList<>(Math.max(3, input.size() + 2));
        for (int i = 0; i < input.size(); i++) {
            ClipVertex current = input.get(i);
            ClipVertex next = input.get((i + 1) % input.size());

            double dCurrent = distance.eval(current);
            double dNext = distance.eval(next);
            boolean currentInside = dCurrent >= 0.0;
            boolean nextInside = dNext >= 0.0;

            if (currentInside && nextInside) {
                output.add(next);
            } else if (currentInside) {
                output.add(intersectPlane(current, next, dCurrent, dNext));
            } else if (nextInside) {
                output.add(intersectPlane(current, next, dCurrent, dNext));
                output.add(next);
            }
        }
        return output;
    }

    private static ClipVertex intersectPlane(ClipVertex a, ClipVertex b, double da, double db) {
        double denom = da - db;
        double t = Math.abs(denom) < CLIP_EPS ? 0.0 : da / denom;
        if (t < 0.0) {
            t = 0.0;
        } else if (t > 1.0) {
            t = 1.0;
        }
        return new ClipVertex(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t,
                a.z + (b.z - a.z) * t,
                a.w + (b.w - a.w) * t
        );
    }

    public static float edgeFunction2D(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }
}
