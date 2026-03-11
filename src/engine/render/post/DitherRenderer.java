package engine.render.post;

import engine.camera.Camera;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.raster.RasterRenderer;
import engine.scene.Scene;
import engine.util.BitFont;

/**
 * Tady držím stylizovaný dithering renderer s více podrežimy.
 * Typ A používám pro dithering po pixelech s mapou podobnou modrému šumu v monochromatické nebo omezené paletě.
 * Typ B používám pro blokový vzorový dithering v buňkách 2x2, 3x3 a 4x4.
 * Typ C používám pro mapování hustoty na znaky ASCII.
 */
public class DitherRenderer implements Renderer {

    /**
     * Tady rozliším aktivní dithering podrežim.
     */
    public enum DitherStyle {
        /** Tady použiju prahování po pixelech s rozložením podobným modrému šumu. */
        BLUE_NOISE,
        /** Tady použiju blokový pattern přes Bayer matici nebo vlastní vzor. */
        PATTERN,
        /** Tady převedu hustotu na znaky ASCII. */
        ASCII
    }

    private static final int BLUE_NOISE_SIZE = 64;

    private final RasterRenderer baseRenderer;
    private DitherStyle style;
    private int toneCount;
    private double contrast;
    private boolean invert;
    private int cellSize;
    private String asciiCharset;
    private float[] blueNoiseMap;
    private int[][] bayerMatrix;
    private BitFont bitFont;
    private int[] sourceCopy;

    public DitherRenderer() {
        this.baseRenderer = new RasterRenderer();
        this.style = DitherStyle.BLUE_NOISE;
        this.toneCount = 2;
        this.contrast = 1.15;
        this.invert = false;
        this.cellSize = 6;
        this.asciiCharset = " .:-=+*#%@";
        this.blueNoiseMap = new float[BLUE_NOISE_SIZE * BLUE_NOISE_SIZE];
        this.bayerMatrix = new int[][]{
                {0, 8, 2, 10},
                {12, 4, 14, 6},
                {3, 11, 1, 9},
                {15, 7, 13, 5}
        };
        this.bitFont = new BitFont(cellSize, cellSize);
        this.sourceCopy = new int[0];

        baseRenderer.setParameter("unlitMode", false);
        baseRenderer.setParameter("frustumCulling", true);
        baseRenderer.setParameter("backfaceCulling", false);
        regenerateBlueNoise();
    }

    @Override
    public void init(int width, int height) {
        baseRenderer.init(width, height);
        ensureBuffers(width * height);
        regenerateBlueNoise();
    }

    @Override
    public void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        if (fb == null) {
            return;
        }

        baseRenderer.render(scene, camera, fb, time);

        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] dst = fb.getColorBuffer();
        ensureBuffers(w * h);
        System.arraycopy(dst, 0, sourceCopy, 0, w * h);

        switch (style) {
            case PATTERN:
                applyPattern(sourceCopy, dst, w, h);
                break;
            case ASCII:
                applyAscii(sourceCopy, dst, w, h);
                break;
            case BLUE_NOISE:
            default:
                applyBlueNoise(sourceCopy, dst, w, h);
                break;
        }
    }

    @Override
    public void resize(int width, int height) {
        baseRenderer.resize(width, height);
        ensureBuffers(width * height);
    }

    @Override
    public void setParameter(String key, Object value) {
        if ("style".equalsIgnoreCase(key)) {
            if (value instanceof DitherStyle) {
                style = (DitherStyle) value;
                return;
            }
            if (value instanceof String) {
                style = parseStyle((String) value);
                return;
            }
        }
        if ("toneCount".equalsIgnoreCase(key) && value instanceof Number) {
            toneCount = Math.max(2, ((Number) value).intValue());
            return;
        }
        if ("contrast".equalsIgnoreCase(key) && value instanceof Number) {
            contrast = Math.max(0.1, Math.min(4.0, ((Number) value).doubleValue()));
            return;
        }
        if ("invert".equalsIgnoreCase(key) && value instanceof Boolean) {
            invert = (Boolean) value;
            return;
        }
        if ("cellSize".equalsIgnoreCase(key) && value instanceof Number) {
            cellSize = Math.max(2, ((Number) value).intValue());
            bitFont = new BitFont(cellSize, cellSize);
            return;
        }
        if ("asciiCharset".equalsIgnoreCase(key) && value instanceof String) {
            String s = ((String) value).trim();
            if (!s.isEmpty()) {
                asciiCharset = s;
            }
            return;
        }

        // Tady předám obecné parametry vykreslení a výkonu do základního rasterizéru.
        baseRenderer.setParameter(key, value);
    }

    @Override
    public String getName() {
        return style == DitherStyle.ASCII ? "ASCII" : "Dithering";
    }

    public DitherStyle getStyle() {
        return style;
    }

    private void applyBlueNoise(int[] src, int[] dst, int w, int h) {
        int mask = BLUE_NOISE_SIZE - 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int nRow = (y & mask) * BLUE_NOISE_SIZE;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                double lum = adjustedLuminance(src[idx]);
                float threshold = blueNoiseMap[nRow + (x & mask)];
                double q = quantizeWithThreshold(lum, threshold);
                dst[idx] = gray(q);
            }
        }
    }

    private void applyPattern(int[] src, int[] dst, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int by = y & 3;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                double lum = adjustedLuminance(src[idx]);
                float threshold = (bayerMatrix[by][x & 3] + 0.5f) / 16.0f;
                double q = quantizeWithThreshold(lum, threshold);
                dst[idx] = gray(q);
            }
        }
    }

    private void applyAscii(int[] src, int[] dst, int w, int h) {
        int cellW = bitFont.getCharWidth();
        int cellH = bitFont.getCharHeight();
        int chars = asciiCharset.length();
        if (chars <= 0) {
            return;
        }

        for (int i = 0; i < dst.length; i++) {
            dst[i] = 0xFF000000;
        }

        for (int y0 = 0; y0 < h; y0 += cellH) {
            int y1 = Math.min(h, y0 + cellH);
            for (int x0 = 0; x0 < w; x0 += cellW) {
                int x1 = Math.min(w, x0 + cellW);
                double sum = 0.0;
                int count = 0;
                for (int y = y0; y < y1; y++) {
                    int row = y * w;
                    for (int x = x0; x < x1; x++) {
                        sum += adjustedLuminance(src[row + x]);
                        count++;
                    }
                }
                if (count == 0) {
                    continue;
                }

                double avg = sum / count;
                int charsetIndex = (int) Math.round(avg * (chars - 1));
                if (charsetIndex < 0) {
                    charsetIndex = 0;
                } else if (charsetIndex >= chars) {
                    charsetIndex = chars - 1;
                }
                char glyph = asciiCharset.charAt(charsetIndex);
                double quantized = quantizeNearest(avg);
                int fg = gray(quantized);
                bitFont.drawChar(dst, w, x0, y0, glyph, fg, 0xFF000000);
            }
        }
    }

    private double adjustedLuminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        double lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
        lum = (lum - 0.5) * contrast + 0.5;
        if (invert) {
            lum = 1.0 - lum;
        }
        if (lum < 0.0) {
            return 0.0;
        }
        if (lum > 1.0) {
            return 1.0;
        }
        return lum;
    }

    private double quantizeWithThreshold(double value, float threshold) {
        int levels = Math.max(2, toneCount);
        double scaled = value * (levels - 1);
        int base = (int) Math.floor(scaled);
        if (base < 0) {
            base = 0;
        } else if (base >= levels) {
            base = levels - 1;
        }
        double frac = scaled - base;
        int tone = base + (frac > threshold ? 1 : 0);
        if (tone < 0) {
            tone = 0;
        } else if (tone >= levels) {
            tone = levels - 1;
        }
        return tone / (double) (levels - 1);
    }

    private double quantizeNearest(double value) {
        int levels = Math.max(2, toneCount);
        int tone = (int) Math.round(value * (levels - 1));
        if (tone < 0) {
            tone = 0;
        } else if (tone >= levels) {
            tone = levels - 1;
        }
        return tone / (double) (levels - 1);
    }

    private int gray(double value) {
        int v = (int) Math.round(value * 255.0);
        if (v < 0) {
            v = 0;
        } else if (v > 255) {
            v = 255;
        }
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    private void ensureBuffers(int pixelCount) {
        if (sourceCopy.length != pixelCount) {
            sourceCopy = new int[pixelCount];
        }
    }

    private DitherStyle parseStyle(String name) {
        if (name == null) {
            return style;
        }
        String n = name.trim().toUpperCase();
        if ("ASCII".equals(n)) {
            return DitherStyle.ASCII;
        }
        if ("PATTERN".equals(n)) {
            return DitherStyle.PATTERN;
        }
        return DitherStyle.BLUE_NOISE;
    }

    private void regenerateBlueNoise() {
        for (int y = 0; y < BLUE_NOISE_SIZE; y++) {
            int row = y * BLUE_NOISE_SIZE;
            for (int x = 0; x < BLUE_NOISE_SIZE; x++) {
                int h = hash(x, y);
                float rnd = (h & 0xFFFF) / 65535.0f;
                // Add mild directional decorrelation to avoid visible grids.
                float warp = (((x * 17 + y * 29) & 63) / 63.0f) * 0.35f;
                blueNoiseMap[row + x] = clamp01(rnd * 0.65f + warp);
            }
        }
    }

    private int hash(int x, int y) {
        int h = x * 0x9E3779B9 ^ y * 0x85EBCA6B;
        h ^= (h >>> 16);
        h *= 0x7FEB352D;
        h ^= (h >>> 15);
        h *= 0x846CA68B;
        h ^= (h >>> 16);
        return h;
    }

    private float clamp01(float v) {
        if (v < 0.0f) {
            return 0.0f;
        }
        if (v > 1.0f) {
            return 1.0f;
        }
        return v;
    }
}
