package engine.util;

import java.util.Arrays;

import engine.camera.Camera;
import engine.geometry.Mesh;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.Entity;
import engine.ui.UiTheme;

public final class SelectionOverlayUtil {
    private static final int EDGE_GAP_CLOSE_PASSES = 2;
    private static final int[] N8_DX = new int[]{-1, 0, 1, -1, 1, -1, 0, 1};
    private static final int[] N8_DY = new int[]{-1, -1, -1, 0, 0, 1, 1, 1};

    private static int[] floodQueueCache = new int[0];
    private static int cachedQueuePixelCount = 0;

    private static byte[] cachedOutlineStroke = new byte[0];
    private static int cachedOutlineWidth = 0;
    private static int cachedOutlineHeight = 0;
    private static Entity cachedSelectionEntity = null;
    private static long cachedSelectionWorldVersion = Long.MIN_VALUE;
    private static int cachedSelectionMeshIdentity = 0;
    private static long cachedCameraSignature = Long.MIN_VALUE;

    private SelectionOverlayUtil() {
    }

    public static void computeSelectionCoveragePass(
            Entity selectedEntity,
            Camera camera,
            int viewportWidth,
            int viewportHeight) {
        if (selectedEntity == null || selectedEntity.getMesh() == null || camera == null) {
            cachedOutlineWidth = 0;
            cachedOutlineHeight = 0;
            return;
        }

        if (viewportWidth <= 0 || viewportHeight <= 0) {
            cachedOutlineWidth = 0;
            cachedOutlineHeight = 0;
            return;
        }

        long cameraSignature = computeCameraSignature(camera, viewportWidth, viewportHeight);
        long worldVersion = selectedEntity.getWorldTransformVersion();
        int meshIdentity = System.identityHashCode(selectedEntity.getMesh());
        if (selectedEntity == cachedSelectionEntity
                && cachedSelectionWorldVersion == worldVersion
                && cachedSelectionMeshIdentity == meshIdentity
                && cachedCameraSignature == cameraSignature
                && cachedOutlineWidth == viewportWidth
                && cachedOutlineHeight == viewportHeight
                && cachedOutlineStroke.length >= viewportWidth * viewportHeight) {
            return;
        }

        int pixelCount = viewportWidth * viewportHeight;
        if (cachedOutlineStroke.length < pixelCount) {
            cachedOutlineStroke = new byte[pixelCount];
        }
        cachedOutlineWidth = viewportWidth;
        cachedOutlineHeight = viewportHeight;
        Arrays.fill(cachedOutlineStroke, 0, pixelCount, (byte) 0);

        Mesh mesh = selectedEntity.getMesh();
        int[] indices = mesh.getIndices();
        float[] positions = mesh.getPositions();
        if (indices == null || positions == null || indices.length < 3 || positions.length < 9) {
            cachedOutlineWidth = 0;
            cachedOutlineHeight = 0;
            return;
        }

        Mat4 model = selectedEntity.getWorldMatrix();
        Mat4 vp = camera.getProjectionMatrix().multiply(camera.getViewMatrix());

        ensureQueueCapacity(pixelCount);

        byte[] tempCoverage = new byte[pixelCount];
        Arrays.fill(tempCoverage, 0, pixelCount, (byte) 0);

        for (int i = 0; i < indices.length; i += 3) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.SELECTION_OVERLAY_TRIANGLES, 1L);
            int i0 = indices[i];
            int i1 = indices[i + 1];
            int i2 = indices[i + 2];
            ScreenProjectionUtil.rasterizeSelectionTriangleNoDepthTest(
                    i0,
                    i1,
                    i2,
                    positions,
                    model,
                    vp,
                    viewportWidth,
                    viewportHeight,
                    tempCoverage
            );
        }

        byte[] edgeMask = new byte[pixelCount];
        for (int y = 0; y < viewportHeight; y++) {
            int row = y * viewportWidth;
            for (int x = 0; x < viewportWidth; x++) {
                int idx = row + x;
                if (tempCoverage[idx] == 0) {
                    continue;
                }
                if (hasSilhouetteNeighbor(tempCoverage, x, y, viewportWidth, viewportHeight)) {
                    edgeMask[idx] = 1;
                }
            }
        }

        closeEdgeGaps(edgeMask, tempCoverage, viewportWidth, viewportHeight, EDGE_GAP_CLOSE_PASSES);

        byte[] exteriorMask = new byte[pixelCount];
        Arrays.fill(exteriorMask, 0, pixelCount, (byte) 0);
        markExteriorBackground(tempCoverage, exteriorMask, viewportWidth, viewportHeight);

        Arrays.fill(cachedOutlineStroke, 0, pixelCount, (byte) 0);
        for (int y = 0; y < viewportHeight; y++) {
            int row = y * viewportWidth;
            for (int x = 0; x < viewportWidth; x++) {
                int idx = row + x;
                if (edgeMask[idx] == 0) {
                    continue;
                }

                for (int n = 0; n < N8_DX.length; n++) {
                    markOutsideStroke(cachedOutlineStroke, tempCoverage, exteriorMask, x + N8_DX[n], y + N8_DY[n], viewportWidth, viewportHeight);
                }
            }
        }

        cachedSelectionEntity = selectedEntity;
        cachedSelectionWorldVersion = worldVersion;
        cachedSelectionMeshIdentity = meshIdentity;
        cachedCameraSignature = cameraSignature;
    }

    public static void drawCachedSelectionOutline(FrameBuffer fb) {
        if (fb == null || cachedOutlineWidth <= 0 || cachedOutlineHeight <= 0) {
            return;
        }

        if (fb.getWidth() != cachedOutlineWidth || fb.getHeight() != cachedOutlineHeight) {
            return;
        }

        final int outline = 0xFF000000
            | (UiTheme.ACCENT_PURPLE.getRed() << 16)
            | (UiTheme.ACCENT_PURPLE.getGreen() << 8)
            | UiTheme.ACCENT_PURPLE.getBlue();
        int[] color = fb.getColorBuffer();
        int pixelCount = cachedOutlineWidth * cachedOutlineHeight;
        for (int i = 0; i < pixelCount; i++) {
            if (cachedOutlineStroke[i] != 0) {
                color[i] = outline;
            }
        }
    }

    private static void ensureQueueCapacity(int pixelCount) {
        if (pixelCount <= cachedQueuePixelCount) {
            return;
        }
        floodQueueCache = new int[pixelCount];
        cachedQueuePixelCount = pixelCount;
    }

    private static long computeCameraSignature(Camera camera, int viewportWidth, int viewportHeight) {
        if (camera == null) {
            return 0L;
        }
        long hash = 0x9E3779B97F4A7C15L;
        hash = mix(hash, viewportWidth);
        hash = mix(hash, viewportHeight);
        hash = mix(hash, Double.doubleToLongBits(camera.getNear()));
        hash = mix(hash, Double.doubleToLongBits(camera.getFar()));
        hash = mix(hash, camera.getPosition());
        hash = mix(hash, camera.getForward());
        hash = mix(hash, camera.getUp());
        hash = mix(hash, camera.getRight());
        return hash;
    }

    private static long mix(long hash, Vec3 value) {
        if (value == null) {
            return mix(hash, 0L);
        }
        hash = mix(hash, Double.doubleToLongBits(value.x));
        hash = mix(hash, Double.doubleToLongBits(value.y));
        return mix(hash, Double.doubleToLongBits(value.z));
    }

    private static long mix(long hash, long value) {
        long mixed = hash ^ value;
        mixed *= 0x100000001B3L;
        return mixed;
    }

    private static void markOutsideStroke(
            byte[] strokeMask,
            byte[] coverage,
            byte[] exteriorMask,
            int x,
            int y,
            int width,
            int height) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            return;
        }
        int idx = y * width + x;
        if (coverage[idx] != 0 || exteriorMask[idx] == 0) {
            return;
        }
        strokeMask[idx] = 1;

        for (int n = 0; n < N8_DX.length; n++) {
            int nx = x + N8_DX[n];
            int ny = y + N8_DY[n];
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                continue;
            }
            int nIdx = ny * width + nx;
            if (coverage[nIdx] == 0 && exteriorMask[nIdx] != 0) {
                strokeMask[nIdx] = 1;
            }
        }
    }

    private static boolean hasSilhouetteNeighbor(byte[] coverage, int x, int y, int width, int height) {
        for (int n = 0; n < N8_DX.length; n++) {
            if (isSilhouetteNeighbor(coverage, x + N8_DX[n], y + N8_DY[n], width, height)) {
                return true;
            }
        }
        return false;
    }

    private static void closeEdgeGaps(byte[] edgeMask, byte[] coverage, int width, int height, int passes) {
        if (passes <= 0) {
            return;
        }
        byte[] scratch = new byte[edgeMask.length];
        for (int pass = 0; pass < passes; pass++) {
            System.arraycopy(edgeMask, 0, scratch, 0, edgeMask.length);
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = row + x;
                    if (coverage[idx] == 0 || edgeMask[idx] != 0) {
                        continue;
                    }

                    int neighboringEdges = 0;
                    boolean touchesBackground = false;
                    for (int n = 0; n < N8_DX.length; n++) {
                        int nx = x + N8_DX[n];
                        int ny = y + N8_DY[n];
                        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
                            touchesBackground = true;
                            continue;
                        }
                        int nIdx = ny * width + nx;
                        if (coverage[nIdx] == 0) {
                            touchesBackground = true;
                        }
                        if (edgeMask[nIdx] != 0) {
                            neighboringEdges++;
                        }
                    }

                    if (touchesBackground && neighboringEdges >= 2) {
                        scratch[idx] = 1;
                    }
                }
            }
            System.arraycopy(scratch, 0, edgeMask, 0, edgeMask.length);
        }
    }

    private static void markExteriorBackground(byte[] coverage, byte[] exteriorMask, int width, int height) {
        int[] queue = floodQueueCache;
        int head = 0;
        int tail = 0;

        for (int x = 0; x < width; x++) {
            tail = enqueueExterior(coverage, exteriorMask, queue, tail, x);
            tail = enqueueExterior(coverage, exteriorMask, queue, tail, (height - 1) * width + x);
        }
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            tail = enqueueExterior(coverage, exteriorMask, queue, tail, row);
            tail = enqueueExterior(coverage, exteriorMask, queue, tail, row + width - 1);
        }

        while (head < tail) {
            int idx = queue[head++];
            int x = idx % width;
            int y = idx / width;
            if (x > 0) {
                tail = enqueueExterior(coverage, exteriorMask, queue, tail, idx - 1);
            }
            if (x < width - 1) {
                tail = enqueueExterior(coverage, exteriorMask, queue, tail, idx + 1);
            }
            if (y > 0) {
                tail = enqueueExterior(coverage, exteriorMask, queue, tail, idx - width);
            }
            if (y < height - 1) {
                tail = enqueueExterior(coverage, exteriorMask, queue, tail, idx + width);
            }
        }
    }

    private static int enqueueExterior(byte[] coverage, byte[] exteriorMask, int[] queue, int tail, int idx) {
        if (idx < 0 || idx >= coverage.length) {
            return tail;
        }
        if (coverage[idx] != 0 || exteriorMask[idx] != 0) {
            return tail;
        }
        exteriorMask[idx] = 1;
        queue[tail] = idx;
        return tail + 1;
    }

    private static boolean isSilhouetteNeighbor(byte[] coverage, int nx, int ny, int width, int height) {
        if (nx < 0 || nx >= width || ny < 0 || ny >= height) {
            return true;
        }
        int neighborIdx = ny * width + nx;
        return coverage[neighborIdx] == 0;
    }
}
