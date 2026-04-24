import engine.render.FrameBuffer;
import engine.render.post.DitherRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class DitherSmartContrastTests {

    private DitherSmartContrastTests() {
    }

    public static void main(String[] args) throws Exception {
        recoversTextureDetailFromUnlitReference();
        recoversTextureDetailFromScaledUnlitReference();
        stretchesCompressedSceneRange();
        System.out.println("DitherSmartContrastTests: ALL TESTS PASSED");
    }

    private static void recoversTextureDetailFromUnlitReference() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("lightAssist", 0.0);

        FrameBuffer lit = new FrameBuffer(5, 5, true);
        fillGBuffer(lit, 0xFF3A3A3A, 0.58f, 0.0f, 1.0f, 0.0f, 4);

        FrameBuffer detail = new FrameBuffer(5, 5, true);
        fillGBuffer(detail, 0xFF303030, 0.58f, 0.0f, 1.0f, 0.0f, 4);
        detail.getColorBuffer()[2 + 2 * 5] = 0xFFBEBEBE;

        prepare(renderer, lit, detail);
        double center = readPrepared(renderer, 2, 2, 5);
        double side = readPrepared(renderer, 1, 2, 5);
        if (!(center > side + 0.22)) {
            throw new AssertionError("Expected unlit reference to recover center detail. center=" + center + " side=" + side);
        }
    }

    private static void recoversTextureDetailFromScaledUnlitReference() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("lightAssist", 0.0);

        FrameBuffer lit = new FrameBuffer(6, 6, true);
        fillGBuffer(lit, 0xFF3E3E3E, 0.58f, 0.0f, 1.0f, 0.0f, 5);

        FrameBuffer detail = new FrameBuffer(3, 3, true);
        fillGBuffer(detail, 0xFF303030, 0.58f, 0.0f, 1.0f, 0.0f, 5);
        detail.getColorBuffer()[1 + 1 * 3] = 0xFFC8C8C8;

        prepare(renderer, lit, detail);
        double center = readPrepared(renderer, 3, 3, 6);
        double side = readPrepared(renderer, 1, 3, 6);
        if (!(center > side + 0.12)) {
            throw new AssertionError("Expected scaled unlit reference to keep center detail. center=" + center + " side=" + side);
        }
    }

    private static void stretchesCompressedSceneRange() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("lightAssist", 0.0);

        FrameBuffer fb = new FrameBuffer(6, 2, true);
        int[] colors = {
                0xFF414141, 0xFF444444, 0xFF474747, 0xFF4A4A4A, 0xFF4D4D4D, 0xFF505050,
                0xFF414141, 0xFF444444, 0xFF474747, 0xFF4A4A4A, 0xFF4D4D4D, 0xFF505050
        };
        System.arraycopy(colors, 0, fb.getColorBuffer(), 0, colors.length);
        fillGBufferMeta(fb, 0.61f, 0.0f, 1.0f, 0.0f, 6);

        prepare(renderer, fb, fb);
        double preparedMin = Double.POSITIVE_INFINITY;
        double preparedMax = Double.NEGATIVE_INFINITY;
        double rawMin = Double.POSITIVE_INFINITY;
        double rawMax = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < colors.length; i++) {
            double prepared = readPrepared(renderer, i % 6, i / 6, 6);
            preparedMin = Math.min(preparedMin, prepared);
            preparedMax = Math.max(preparedMax, prepared);

            double raw = luminance(colors[i]);
            rawMin = Math.min(rawMin, raw);
            rawMax = Math.max(rawMax, raw);
        }

        double rawRange = rawMax - rawMin;
        double preparedRange = preparedMax - preparedMin;
        if (!(preparedRange > rawRange + 0.20)) {
            throw new AssertionError("Expected smart contrast to widen range. raw=" + rawRange + " prepared=" + preparedRange);
        }
    }

    private static void prepare(DitherRenderer renderer, FrameBuffer lit, FrameBuffer detail) throws Exception {
        Method prepare = DitherRenderer.class.getDeclaredMethod(
                "prepareDitherLuminance", FrameBuffer.class, FrameBuffer.class, int.class, int.class
        );
        prepare.setAccessible(true);
        prepare.invoke(renderer, lit, detail, lit.getWidth(), lit.getHeight());
    }

    private static double readPrepared(DitherRenderer renderer, int x, int y, int width) throws Exception {
        Field field = DitherRenderer.class.getDeclaredField("sourceLuminanceBuffer");
        field.setAccessible(true);
        double[] buffer = (double[]) field.get(renderer);
        return buffer[y * width + x];
    }

    private static void fillGBuffer(FrameBuffer fb,
                                    int color,
                                    float depth,
                                    float nx,
                                    float ny,
                                    float nz,
                                    int objectId) {
        Arrays.fill(fb.getColorBuffer(), color);
        fillGBufferMeta(fb, depth, nx, ny, nz, objectId);
    }

    private static void fillGBufferMeta(FrameBuffer fb,
                                        float depth,
                                        float nx,
                                        float ny,
                                        float nz,
                                        int objectId) {
        Arrays.fill(fb.getDepthBuffer(), depth);
        Arrays.fill(fb.getObjectIdBuffer(), objectId);
        Arrays.fill(fb.getFaceIdBuffer(), 0);
        float[] normal = fb.getNormalBuffer();
        float[] worldPos = fb.getWorldPosBuffer();
        for (int i = 0; i < fb.getWidth() * fb.getHeight(); i++) {
            int base = i * 3;
            normal[base] = nx;
            normal[base + 1] = ny;
            normal[base + 2] = nz;
            worldPos[base] = 0.0f;
            worldPos[base + 1] = 0.0f;
            worldPos[base + 2] = 0.0f;
        }
    }

    private static double luminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }
}
