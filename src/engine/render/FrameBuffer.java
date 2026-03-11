package engine.render;

import engine.math.Vec3;

import java.util.Arrays;

/**
 * Tady držím softwarový framebuffer s barvou, hloubkou a volitelnými pomocnými buffery.
 * Všechny buffery ukládám do plochých polí o velikosti width * height, aby se mi k nim přistupovalo co nejlépe přes mezipaměť procesoru.
 */
public class FrameBuffer {

    private int width;
    private int height;
    private int[] colorBuffer;
    private float[] depthBuffer;
    private boolean gBufferEnabled;

    // Tady držím volitelné kanály G-bufferu.
    private int[] objectIdBuffer;
    private int[] faceIdBuffer;
    private float[] normalBuffer;
    private float[] worldPosBuffer;

    public FrameBuffer(int width, int height) {
        this(width, height, false);
    }

    public FrameBuffer(int width, int height, boolean allocateGBuffer) {
        this.gBufferEnabled = allocateGBuffer;
        allocate(width, height);
    }

    // Tady čistím obsah framebufferu.
    public void clear(int clearColor, float clearDepth) {
        Arrays.fill(colorBuffer, clearColor);
        Arrays.fill(depthBuffer, clearDepth);
        if (gBufferEnabled) {
            clearGBuffer();
        }
    }

    public void clearGBuffer() {
        if (!gBufferEnabled) {
            return;
        }
        Arrays.fill(objectIdBuffer, -1);
        Arrays.fill(faceIdBuffer, -1);
        Arrays.fill(normalBuffer, 0.0f);
        Arrays.fill(worldPosBuffer, 0.0f);
    }

    // Tady zapisuju data po pixelech.
    public void setPixel(int x, int y, int argb) {
        if (!inBounds(x, y)) {
            return;
        }
        colorBuffer[index(x, y)] = argb;
    }

    public boolean setPixelIfCloser(int x, int y, float depth, int argb) {
        if (!inBounds(x, y)) {
            return false;
        }
        int idx = index(x, y);
        if (depth < depthBuffer[idx]) {
            depthBuffer[idx] = depth;
            colorBuffer[idx] = argb;
            return true;
        }
        return false;
    }

    // Tady zapisuju data do G-bufferu.
    public void setObjectId(int x, int y, int id) {
        if (gBufferEnabled && inBounds(x, y)) {
            objectIdBuffer[index(x, y)] = id;
        }
    }

    public void setFaceId(int x, int y, int id) {
        if (gBufferEnabled && inBounds(x, y)) {
            faceIdBuffer[index(x, y)] = id;
        }
    }

    public void setNormal(int x, int y, float nx, float ny, float nz) {
        if (!gBufferEnabled || !inBounds(x, y)) {
            return;
        }
        int base = index(x, y) * 3;
        normalBuffer[base] = nx;
        normalBuffer[base + 1] = ny;
        normalBuffer[base + 2] = nz;
    }

    public void setWorldPos(int x, int y, float wx, float wy, float wz) {
        if (!gBufferEnabled || !inBounds(x, y)) {
            return;
        }
        int base = index(x, y) * 3;
        worldPosBuffer[base] = wx;
        worldPosBuffer[base + 1] = wy;
        worldPosBuffer[base + 2] = wz;
    }

    // Tady držím čtecí metody.
    public int getColor(int x, int y) {
        if (!inBounds(x, y)) {
            return 0;
        }
        return colorBuffer[index(x, y)];
    }

    public float getDepth(int x, int y) {
        if (!inBounds(x, y)) {
            return 1.0f;
        }
        return depthBuffer[index(x, y)];
    }

    public int getObjectId(int x, int y) {
        if (!gBufferEnabled || !inBounds(x, y)) {
            return -1;
        }
        return objectIdBuffer[index(x, y)];
    }

    public Vec3 getNormal(int x, int y) {
        if (!gBufferEnabled || !inBounds(x, y)) {
            return Vec3.ZERO;
        }
        int base = index(x, y) * 3;
        return new Vec3(normalBuffer[base], normalBuffer[base + 1], normalBuffer[base + 2]);
    }

    public Vec3 getWorldPos(int x, int y) {
        if (!gBufferEnabled || !inBounds(x, y)) {
            return Vec3.ZERO;
        }
        int base = index(x, y) * 3;
        return new Vec3(worldPosBuffer[base], worldPosBuffer[base + 1], worldPosBuffer[base + 2]);
    }

    // Tady držím přístupové metody.
    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int[] getColorBuffer() {
        return colorBuffer;
    }

    public float[] getDepthBuffer() {
        return depthBuffer;
    }

    public boolean isGBufferEnabled() {
        return gBufferEnabled;
    }

    public int[] getObjectIdBuffer() {
        return objectIdBuffer;
    }

    public int[] getFaceIdBuffer() {
        return faceIdBuffer;
    }

    public float[] getNormalBuffer() {
        return normalBuffer;
    }

    public float[] getWorldPosBuffer() {
        return worldPosBuffer;
    }

    // Tady řeším resize.
    public void resize(int newWidth, int newHeight) {
        allocate(newWidth, newHeight);
    }

    private void allocate(int newWidth, int newHeight) {
        if (newWidth <= 0 || newHeight <= 0) {
            throw new IllegalArgumentException("Framebuffer size must be positive");
        }
        this.width = newWidth;
        this.height = newHeight;
        int pixelCount = width * height;
        colorBuffer = new int[pixelCount];
        depthBuffer = new float[pixelCount];

        if (gBufferEnabled) {
            objectIdBuffer = new int[pixelCount];
            faceIdBuffer = new int[pixelCount];
            normalBuffer = new float[pixelCount * 3];
            worldPosBuffer = new float[pixelCount * 3];
        } else {
            objectIdBuffer = null;
            faceIdBuffer = null;
            normalBuffer = null;
            worldPosBuffer = null;
        }

        clear(0xFF000000, 1.0f);
    }

    private boolean inBounds(int x, int y) {
        return x >= 0 && y >= 0 && x < width && y < height;
    }

    private int index(int x, int y) {
        return y * width + x;
    }
}
