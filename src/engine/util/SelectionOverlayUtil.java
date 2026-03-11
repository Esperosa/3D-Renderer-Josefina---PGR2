package engine.util;

import engine.camera.Camera;
import engine.geometry.Mesh;
import engine.math.Mat4;
import engine.render.FrameBuffer;
import engine.scene.Entity;

import java.util.Arrays;

public final class SelectionOverlayUtil {

    private SelectionOverlayUtil() {
    }

    public static void drawSelectedOutline(FrameBuffer fb, Entity selectedEntity, Camera camera) {
        if (selectedEntity == null || selectedEntity.getMesh() == null || camera == null) {
            return;
        }

        Mesh mesh = selectedEntity.getMesh();
        int[] indices = mesh.getIndices();
        float[] positions = mesh.getPositions();
        if (indices == null || positions == null || indices.length < 3 || positions.length < 9) {
            return;
        }

        int width = fb.getWidth();
        int height = fb.getHeight();
        int pixelCount = width * height;
        byte[] coverage = new byte[pixelCount];
        byte[] edgeMask = new byte[pixelCount];
        float[] selectedDepth = new float[pixelCount];
        Arrays.fill(selectedDepth, Float.POSITIVE_INFINITY);

        Mat4 model = selectedEntity.getWorldMatrix();
        Mat4 vp = camera.getProjectionMatrix().multiply(camera.getViewMatrix());
        float[] sx = new float[3];
        float[] sy = new float[3];
        float[] sz = new float[3];
        float[] sceneDepth = fb.getDepthBuffer();

        for (int i = 0; i < indices.length; i += 3) {
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            if (!ScreenProjectionUtil.projectVertex(i0, positions, model, vp, width, height, sx, sy, sz, 0)) {
                continue;
            }
            if (!ScreenProjectionUtil.projectVertex(i1, positions, model, vp, width, height, sx, sy, sz, 1)) {
                continue;
            }
            if (!ScreenProjectionUtil.projectVertex(i2, positions, model, vp, width, height, sx, sy, sz, 2)) {
                continue;
            }

            ScreenProjectionUtil.rasterizeSelectionTriangle(
                    sx[0], sy[0], sz[0],
                    sx[1], sy[1], sz[1],
                    sx[2], sy[2], sz[2],
                    width, height,
                    sceneDepth, selectedDepth, coverage
            );
        }

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                if (coverage[idx] == 0) {
                    continue;
                }
                if (coverage[idx - 1] == 0 || coverage[idx + 1] == 0
                        || coverage[idx - width] == 0 || coverage[idx + width] == 0) {
                    edgeMask[idx] = 1;
                }
            }
        }

        final int outer = 0xFF10D7F0;
        final int inner = 0xFF07121A;
        int[] color = fb.getColorBuffer();
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                if (edgeMask[idx] == 0) {
                    continue;
                }
                for (int oy = -1; oy <= 1; oy++) {
                    int yy = y + oy;
                    int outRow = yy * width;
                    for (int ox = -1; ox <= 1; ox++) {
                        color[outRow + (x + ox)] = outer;
                    }
                }
            }
        }
        for (int i = 0; i < pixelCount; i++) {
            if (edgeMask[i] != 0) {
                color[i] = inner;
            }
        }
    }
}
