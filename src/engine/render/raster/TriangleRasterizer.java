package engine.render.raster;

import engine.render.FrameBuffer;
import engine.util.RuntimeInstrumentation;

/**
 * Tady převádím trojúhelníky na pixely přes barycentrickou rasterizaci.
 * Řeším si v ní depth test, interpolaci atributů i finální výstup fragmentu.
 */
public class TriangleRasterizer {

    private final float[] worldPos = new float[3];
    private final float[] worldNormal = new float[3];
    private final float[] uv0 = new float[2];
    private final float[] uv1 = new float[2];
    private final float[] worldTangent = new float[3];
    private final float[] interpolated = new float[20];

    /**
     * Tady vyrastruju jeden trojúhelník v obraze s perspektivně správnou interpolací všech vertex atributů.
     *
     * @param fb sem předám cílový framebuffer
     * @param sx sem předám obrazové souřadnice x pro 3 vrcholy
     * @param sy sem předám obrazové souřadnice y pro 3 vrcholy
     * @param sz sem předám obrazovou hloubku pro 3 vrcholy kvůli Z-bufferu
     * @param sw sem předám hodnotu 1/clip-w pro 3 vrcholy kvůli perspektivně správné korekci
     * @param attrs sem předám prokládané atributy po vrcholech, třeba normály, UV, barvy a world pozice
     *              Každé float[] má 3 položky, tedy jednu pro každý vrchol a komponentu atributu.
     * @param shader sem předám zpětné volání, kterým si spočítám finální barvu pixelu z interpolovaných atributů
     */
    public void rasterize(FrameBuffer fb,
                          float[] sx, float[] sy, float[] sz, float[] sw,
                          float[][] attrs, FragmentShader shader) {
        rasterize(fb, sx, sy, sz, sw, attrs, shader, 0, 0, fb.getWidth() - 1, fb.getHeight() - 1);
    }

    public void rasterize(FrameBuffer fb,
                          float[] sx, float[] sy, float[] sz, float[] sw,
                          float[][] attrs, FragmentShader shader,
                          int clipMinX, int clipMinY, int clipMaxX, int clipMaxY) {
        int width = fb.getWidth();
        int height = fb.getHeight();
        int[] colorBuffer = fb.getColorBuffer();
        float[] depthBuffer = fb.getDepthBuffer();
        boolean gBufferEnabled = fb.isGBufferEnabled();
        int[] objectIdBuffer = gBufferEnabled ? fb.getObjectIdBuffer() : null;
        int[] faceIdBuffer = gBufferEnabled ? fb.getFaceIdBuffer() : null;
        float[] normalBuffer = gBufferEnabled ? fb.getNormalBuffer() : null;
        float[] worldPosBuffer = gBufferEnabled ? fb.getWorldPosBuffer() : null;

        float minXf = Math.min(sx[0], Math.min(sx[1], sx[2]));
        float maxXf = Math.max(sx[0], Math.max(sx[1], sx[2]));
        float minYf = Math.min(sy[0], Math.min(sy[1], sy[2]));
        float maxYf = Math.max(sy[0], Math.max(sy[1], sy[2]));

        int minX = Math.max(Math.max(0, clipMinX), (int) Math.floor(minXf));
        int maxX = Math.min(Math.min(width - 1, clipMaxX), (int) Math.ceil(maxXf));
        int minY = Math.max(Math.max(0, clipMinY), (int) Math.floor(minYf));
        int maxY = Math.min(Math.min(height - 1, clipMaxY), (int) Math.ceil(maxYf));
        if (minX > maxX || minY > maxY) {
            return;
        }

        float area = edgeFunction(sx[0], sy[0], sx[1], sy[1], sx[2], sy[2]);
        if (Math.abs(area) < 1e-7f) {
            return;
        }

        final boolean measure = RuntimeInstrumentation.isEnabled();
        final int sampleMask = 127;
        final long sampleScale = sampleMask + 1L;
        long rasterFillNs = 0L;
        long depthNs = 0L;
        long framebufferWriteNs = 0L;
        int attrCount = attrs == null ? 0 : Math.min(attrs.length, interpolated.length);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                boolean sample = measure && (((y * width + x) & sampleMask) == 0);
                long rasterStart = sample ? System.nanoTime() : 0L;
                float px = x + 0.5f;
                float py = y + 0.5f;

                float w0 = edgeFunction(sx[1], sy[1], sx[2], sy[2], px, py);
                float w1 = edgeFunction(sx[2], sy[2], sx[0], sy[0], px, py);
                float w2 = edgeFunction(sx[0], sy[0], sx[1], sy[1], px, py);
                if (sample) {
                    rasterFillNs += (System.nanoTime() - rasterStart) * sampleScale;
                }

                if (!((w0 >= 0 && w1 >= 0 && w2 >= 0) || (w0 <= 0 && w1 <= 0 && w2 <= 0))) {
                    continue;
                }

                long depthStart = sample ? System.nanoTime() : 0L;
                float l0 = w0 / area;
                float l1 = w1 / area;
                float l2 = w2 / area;
                float depth = l0 * sz[0] + l1 * sz[1] + l2 * sz[2];
                if (depth < 0.0f || depth > 1.0f) {
                    if (sample) {
                        depthNs += (System.nanoTime() - depthStart) * sampleScale;
                    }
                    continue;
                }
                int idx = y * width + x;
                if (depth >= depthBuffer[idx]) {
                    if (sample) {
                        depthNs += (System.nanoTime() - depthStart) * sampleScale;
                    }
                    continue;
                }

                if (attrCount > 0) {
                    float denom = l0 * sw[0] + l1 * sw[1] + l2 * sw[2];
                    if (Math.abs(denom) < 1e-7f) {
                        if (sample) {
                            depthNs += (System.nanoTime() - depthStart) * sampleScale;
                        }
                        continue;
                    }
                    for (int i = 0; i < attrCount; i++) {
                        interpolated[i] = (l0 * attrs[i][0] * sw[0]
                                + l1 * attrs[i][1] * sw[1]
                                + l2 * attrs[i][2] * sw[2]) / denom;
                    }
                }
                if (sample) {
                    depthNs += (System.nanoTime() - depthStart) * sampleScale;
                }

                worldPos[0] = attrCount > 0 ? interpolated[0] : 0.0f;
                worldPos[1] = attrCount > 1 ? interpolated[1] : 0.0f;
                worldPos[2] = attrCount > 2 ? interpolated[2] : 0.0f;

                worldNormal[0] = attrCount > 3 ? interpolated[3] : 0.0f;
                worldNormal[1] = attrCount > 4 ? interpolated[4] : 1.0f;
                worldNormal[2] = attrCount > 5 ? interpolated[5] : 0.0f;

                float[] uv0Out = null;
                if (attrCount > 7) {
                    uv0[0] = interpolated[6];
                    uv0[1] = interpolated[7];
                    uv0Out = uv0;
                }

                float[] uv1Out = null;
                if (attrCount > 9) {
                    uv1[0] = interpolated[8];
                    uv1[1] = interpolated[9];
                    uv1Out = uv1;
                }

                float[] tangentOut = null;
                if (attrCount > 12) {
                    worldTangent[0] = interpolated[10];
                    worldTangent[1] = interpolated[11];
                    worldTangent[2] = interpolated[12];
                    tangentOut = worldTangent;
                }

                int argb = shader.shade(x, y, depth, worldPos, worldNormal, uv0Out, uv1Out, tangentOut);
                if (((argb >>> 24) & 0xFF) == 0) {
                    continue;
                }
                long writeStart = sample ? System.nanoTime() : 0L;
                depthBuffer[idx] = depth;
                colorBuffer[idx] = argb;

                if (gBufferEnabled) {
                    int objectId = -1;
                    int faceId = -1;
                    if (attrCount > 14) {
                        objectId = Math.round(interpolated[13]);
                        faceId = Math.round(interpolated[14]);
                    }
                    objectIdBuffer[idx] = objectId;
                    faceIdBuffer[idx] = faceId;

                    float nx = worldNormal[0];
                    float ny = worldNormal[1];
                    float nz = worldNormal[2];
                    float nLenSq = nx * nx + ny * ny + nz * nz;
                    if (nLenSq > 1e-12f) {
                        float inv = (float) (1.0 / Math.sqrt(nLenSq));
                        nx *= inv;
                        ny *= inv;
                        nz *= inv;
                    } else {
                        nx = 0.0f;
                        ny = 1.0f;
                        nz = 0.0f;
                    }
                    int vBase = idx * 3;
                    normalBuffer[vBase] = nx;
                    normalBuffer[vBase + 1] = ny;
                    normalBuffer[vBase + 2] = nz;
                    worldPosBuffer[vBase] = worldPos[0];
                    worldPosBuffer[vBase + 1] = worldPos[1];
                    worldPosBuffer[vBase + 2] = worldPos[2];
                }
                if (sample) {
                    framebufferWriteNs += (System.nanoTime() - writeStart) * sampleScale;
                }
            }
        }
        if (measure) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_BASE_RASTER_FILL_NS, rasterFillNs);
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_BASE_DEPTH_TEST_NS, depthNs);
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_BASE_FRAMEBUFFER_WRITE_NS, framebufferWriteNs);
        }
    }

    public static float edgeFunction(float ax, float ay, float bx, float by, float cx, float cy) {
        return (cx - ax) * (by - ay) - (cy - ay) * (bx - ax);
    }
}
