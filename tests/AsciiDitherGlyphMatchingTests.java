import engine.render.post.DitherRenderer;
import engine.render.FrameBuffer;
import engine.util.BitFont;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class AsciiDitherGlyphMatchingTests {

    private static final int BG = 0xFF000000;
    private static final int FG = 0xFFFFFFFF;
    private static final int DARK_FG = 0xFF404040;

    private AsciiDitherGlyphMatchingTests() {
    }

    public static void main(String[] args) throws Exception {
        preservesLeadingSpaceInPalette();
        matchesSlashGlyphByShape();
        stretchesDarkGlyphIntoUsableAsciiContrast();
        appliesToneCountToAsciiLuminance();
        System.out.println("AsciiDitherGlyphMatchingTests: ALL TESTS PASSED");
    }

    private static void preservesLeadingSpaceInPalette() throws Exception {
        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("asciiCharset", " \\/");

        Field charsetField = DitherRenderer.class.getDeclaredField("asciiCharset");
        charsetField.setAccessible(true);
        String actual = (String) charsetField.get(renderer);
        if (!" \\/".equals(actual)) {
            throw new AssertionError("Expected palette with leading space, got " + actual);
        }
    }

    private static void matchesSlashGlyphByShape() throws Exception {
        int cellSize = 8;
        BitFont font = new BitFont(cellSize, cellSize);
        int[] src = new int[cellSize * cellSize];
        int[] dst = new int[cellSize * cellSize];
        int[] expected = new int[cellSize * cellSize];
        font.drawChar(src, cellSize, 0, 0, '/', FG, BG);
        font.drawChar(expected, cellSize, 0, 0, '/', FG, BG);

        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("cellSize", cellSize);
        renderer.setParameter("toneCount", 8);
        renderer.setParameter("asciiCharset", "\\/");
        invokeApplyAscii(renderer, src, dst, cellSize, cellSize);

        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != dst[i]) {
                throw new AssertionError("Slash glyph mismatch at pixel " + i
                        + " expected=0x" + Integer.toHexString(expected[i])
                        + " actual=0x" + Integer.toHexString(dst[i]));
            }
        }
    }

    private static void stretchesDarkGlyphIntoUsableAsciiContrast() throws Exception {
        int cellSize = 8;
        BitFont font = new BitFont(cellSize, cellSize);
        int[] src = new int[cellSize * cellSize];
        int[] dst = new int[cellSize * cellSize];
        int[] expected = new int[cellSize * cellSize];
        font.drawChar(src, cellSize, 0, 0, '/', DARK_FG, BG);
        font.drawChar(expected, cellSize, 0, 0, '/', FG, BG);

        DitherRenderer renderer = new DitherRenderer();
        renderer.setParameter("cellSize", cellSize);
        renderer.setParameter("toneCount", 2);
        renderer.setParameter("asciiCharset", "\\/");
        invokeApplyAscii(renderer, src, dst, cellSize, cellSize);

        for (int i = 0; i < expected.length; i++) {
            if (expected[i] != dst[i]) {
                throw new AssertionError("Dark slash should stretch into white ASCII contrast at pixel " + i
                        + " expected=0x" + Integer.toHexString(expected[i])
                        + " actual=0x" + Integer.toHexString(dst[i]));
            }
        }
    }

    private static void appliesToneCountToAsciiLuminance() throws Exception {
        int width = 8;
        int height = 8;
        int[] src = new int[width * height];
        for (int i = 0; i < src.length; i++) {
            src[i] = 0xFF666666;
        }

        DitherRenderer lowToneRenderer = new DitherRenderer();
        lowToneRenderer.setParameter("toneCount", 2);
        double lowTone = prepareAndReadAsciiLuminance(lowToneRenderer, src, width, height, 0);

        DitherRenderer highToneRenderer = new DitherRenderer();
        highToneRenderer.setParameter("toneCount", 8);
        double highTone = prepareAndReadAsciiLuminance(highToneRenderer, src, width, height, 0);

        if (Math.abs(lowTone - highTone) <= 0.10) {
            throw new AssertionError("ASCII tone count should change prepared luminance. low=" + lowTone + " high=" + highTone);
        }
    }

    private static void invokeApplyAscii(DitherRenderer renderer, int[] src, int[] dst, int width, int height) throws Exception {
        renderer.setParameter("lightAssist", 0.0);
        FrameBuffer fb = new FrameBuffer(width, height, true);
        System.arraycopy(src, 0, fb.getColorBuffer(), 0, src.length);
        for (int i = 0; i < width * height; i++) {
            fb.getDepthBuffer()[i] = 0.5f;
            fb.getObjectIdBuffer()[i] = 1;
            int base = i * 3;
            fb.getNormalBuffer()[base] = 0.0f;
            fb.getNormalBuffer()[base + 1] = 1.0f;
            fb.getNormalBuffer()[base + 2] = 0.0f;
        }

        Method prepare = DitherRenderer.class.getDeclaredMethod("prepareDitherLuminance", FrameBuffer.class, int.class, int.class);
        prepare.setAccessible(true);
        prepare.invoke(renderer, fb, width, height);

        Method applyAscii = DitherRenderer.class.getDeclaredMethod("applyAscii", int[].class, int.class, int.class);
        applyAscii.setAccessible(true);
        applyAscii.invoke(renderer, dst, width, height);
    }

    private static double prepareAndReadAsciiLuminance(DitherRenderer renderer, int[] src, int width, int height, int index) throws Exception {
        renderer.setParameter("lightAssist", 0.0);
        FrameBuffer fb = new FrameBuffer(width, height, true);
        System.arraycopy(src, 0, fb.getColorBuffer(), 0, src.length);
        for (int i = 0; i < width * height; i++) {
            fb.getDepthBuffer()[i] = 0.5f;
            fb.getObjectIdBuffer()[i] = 1;
            int base = i * 3;
            fb.getNormalBuffer()[base] = 0.0f;
            fb.getNormalBuffer()[base + 1] = 1.0f;
            fb.getNormalBuffer()[base + 2] = 0.0f;
        }

        Method prepare = DitherRenderer.class.getDeclaredMethod("prepareDitherLuminance", FrameBuffer.class, int.class, int.class);
        prepare.setAccessible(true);
        prepare.invoke(renderer, fb, width, height);

        Method prepareAscii = DitherRenderer.class.getDeclaredMethod("prepareAsciiLuminance", int.class, int.class);
        prepareAscii.setAccessible(true);
        prepareAscii.invoke(renderer, width, height);

        Field asciiField = DitherRenderer.class.getDeclaredField("asciiLuminanceBuffer");
        asciiField.setAccessible(true);
        double[] buffer = (double[]) asciiField.get(renderer);
        return buffer[index];
    }
}
