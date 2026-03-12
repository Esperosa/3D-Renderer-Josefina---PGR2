import engine.render.FrameBuffer;
import engine.render.post.DitherRenderer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

public final class DitherLightingAssistTests {

    private DitherLightingAssistTests() {
    }

    public static void main(String[] args) throws Exception {
        liftsShadowedPixelsWithSkyFill();
        darkensContactAreasWithLocalOcclusion();
        System.out.println("DitherLightingAssistTests: ALL TESTS PASSED");
    }

    private static void liftsShadowedPixelsWithSkyFill() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("lightAssist", 0.55);

        FrameBuffer fb = new FrameBuffer(5, 5, true);
        fillGBuffer(fb, 0xFF202020, 0.62f, 0.0f, 1.0f, 0.0f, 1);
        double raw = luminance(0xFF202020);
        double lifted = prepareAndRead(renderer, fb, 2, 2);
        if (!(lifted > raw + 0.04)) {
            throw new AssertionError("Expected sky fill to lift dark pixel. raw=" + raw + " lifted=" + lifted);
        }
    }

    private static void darkensContactAreasWithLocalOcclusion() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("lightAssist", 0.55);

        FrameBuffer flat = new FrameBuffer(5, 5, true);
        fillGBuffer(flat, 0xFF6A6A6A, 0.66f, 0.0f, 1.0f, 0.0f, 3);
        double flatCenter = prepareAndRead(renderer, flat, 2, 2);

        FrameBuffer occluded = new FrameBuffer(5, 5, true);
        fillGBuffer(occluded, 0xFF6A6A6A, 0.66f, 0.0f, 1.0f, 0.0f, 3);
        int[] ring = {
                1 + 2 * 5, 3 + 2 * 5, 2 + 1 * 5, 2 + 3 * 5,
                1 + 1 * 5, 3 + 1 * 5, 1 + 3 * 5, 3 + 3 * 5
        };
        for (int idx : ring) {
            occluded.getDepthBuffer()[idx] = 0.63f;
        }
        double occludedCenter = prepareAndRead(renderer, occluded, 2, 2);
        if (!(occludedCenter < flatCenter - 0.01)) {
            throw new AssertionError("Expected contact shadow to darken center. flat=" + flatCenter
                    + " occluded=" + occludedCenter);
        }
    }

    private static double prepareAndRead(DitherRenderer renderer, FrameBuffer fb, int x, int y) throws Exception {
        Method prepare = DitherRenderer.class.getDeclaredMethod("prepareDitherLuminance", FrameBuffer.class, int.class, int.class);
        prepare.setAccessible(true);
        prepare.invoke(renderer, fb, fb.getWidth(), fb.getHeight());

        Field luminanceField = DitherRenderer.class.getDeclaredField("sourceLuminanceBuffer");
        luminanceField.setAccessible(true);
        double[] buffer = (double[]) luminanceField.get(renderer);
        return buffer[y * fb.getWidth() + x];
    }

    private static void fillGBuffer(FrameBuffer fb,
                                    int color,
                                    float depth,
                                    float nx,
                                    float ny,
                                    float nz,
                                    int objectId) {
        Arrays.fill(fb.getColorBuffer(), color);
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
