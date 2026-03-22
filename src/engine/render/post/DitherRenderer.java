package engine.render.post;

import engine.camera.Camera;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.raster.RasterRenderer;
import engine.scene.Scene;
import engine.util.BitFont;

/**
 * Represents stylizovaný dithering renderer s více podrežimy.
 * Typ A používá pro dithering po pixelech s mapou podobnou modrému šumu v monochromatické nebo omezené paletě.
 * Typ B používá pro blokový vzorový dithering v buňkách 2x2, 3x3 a 4x4.
 * Typ C používá pro porovnání obrazového bloku s bitmapami znaků ASCII.
 */
public class DitherRenderer implements Renderer {

 /**
 * Distinguishes aktivní dithering podrežim.
 */
    public enum DitherStyle {
 /** použiju prahování po pixelech s rozložením podobným modrému šumu. */
        BLUE_NOISE,
 /** použiju blokový pattern přes Bayer matici nebo vlastní vzor. */
        PATTERN,
 /** vyberu ASCII glyph podle podobnosti skutečného obrazového bloku. */
        ASCII
    }

    private static final int BLUE_NOISE_SIZE = 64;
    private static final double ASCII_LOW_PERCENTILE = 0.02;
    private static final double ASCII_HIGH_PERCENTILE = 0.98;
    private static final double AUTO_LOW_PERCENTILE = 0.015;
    private static final double AUTO_HIGH_PERCENTILE = 0.985;
    private static final double DETAIL_LOW_PERCENTILE = 0.01;
    private static final double DETAIL_HIGH_PERCENTILE = 0.99;
    private static final double FINAL_LOW_PERCENTILE = 0.03;
    private static final double FINAL_HIGH_PERCENTILE = 0.97;
    private static final double AUTO_TARGET_SPAN = 0.72;
    private static final double DETAIL_CONTRAST_RESPONSE = 0.72;
    private static final double SHADED_CONTRAST_RESPONSE = 0.76;
    private static final double EXTREME_LUMA_WINDOW = 0.26;
    private static final double FINAL_STRETCH_THRESHOLD = 0.86;
    private static final double FINAL_STRETCH_RESPONSE = 0.58;
    private static final double AUTO_CONTRAST_SMOOTHING = 0.22;
    private static final double DETAIL_NEIGHBOR_DEPTH_DELTA = 0.03;
    private static final double DETAIL_RESIDUAL_GATE = 0.045;
    private static final int DETAIL_REFERENCE_FULL_RES_PIXELS = 960 * 540;
    private static final double DETAIL_REFERENCE_SCALE = 0.5;
    private static final float GEOMETRY_DEPTH_LIMIT = 0.9995f;
    private static final double CONTACT_SHADOW_BIAS = 0.0012;
    private static final double CONTACT_SHADOW_MAX_DEPTH_DELTA = 0.045;
    private static final int[] CONTACT_SAMPLE_X = {1, -1, 0, 0, 1, -1, 1, -1, 2, -2, 0, 0};
    private static final int[] CONTACT_SAMPLE_Y = {0, 0, 1, -1, 1, 1, -1, -1, 0, 0, 2, -2};
    private static final int[] DETAIL_SAMPLE_X = {1, -1, 0, 0, 1, -1, 1, -1};
    private static final int[] DETAIL_SAMPLE_Y = {0, 0, 1, -1, 1, 1, -1, -1};

    private final RasterRenderer baseRenderer;
    private DitherStyle style;
    private int toneCount;
    private double contrast;
    private boolean invert;
    private int cellSize;
    private double lightAssist;
    private String asciiCharset;
    private float[] blueNoiseMap;
    private int[][] bayerMatrix;
    private BitFont bitFont;
    private FrameBuffer gBufferFrame;
    private FrameBuffer detailFrame;
    private AsciiCandidate[] asciiCandidates;
    private double[] asciiCellBuffer;
    private double[] asciiLuminanceBuffer;
    private int[] asciiHistogram;
    private double[] sourceLuminanceBuffer;
    private double[] shadedLuminanceBuffer;
    private double[] detailLuminanceBuffer;
    private int[] luminanceHistogram;
    private boolean autoContrastPrimed;
    private double smoothedAutoLow;
    private double smoothedAutoHigh;
    private boolean rasterUnlitMode;
    private boolean rasterFlatShading;
    private boolean rasterModelPreviewMode;

    public DitherRenderer() {
        this.baseRenderer = new RasterRenderer();
        this.style = DitherStyle.BLUE_NOISE;
        this.toneCount = 2;
        this.contrast = 1.15;
        this.invert = false;
        this.cellSize = 6;
        this.lightAssist = 0.38;
        this.asciiCharset = BitFont.DEFAULT_ASCII_CHARSET;
        this.blueNoiseMap = new float[BLUE_NOISE_SIZE * BLUE_NOISE_SIZE];
        this.bayerMatrix = new int[][]{
                {0, 8, 2, 10},
                {12, 4, 14, 6},
                {3, 11, 1, 9},
                {15, 7, 13, 5}
        };
        this.bitFont = new BitFont(cellSize, cellSize);
        this.gBufferFrame = new FrameBuffer(1, 1, true);
        this.detailFrame = new FrameBuffer(1, 1);
        this.asciiCandidates = new AsciiCandidate[0];
        this.asciiCellBuffer = new double[0];
        this.asciiLuminanceBuffer = new double[0];
        this.asciiHistogram = new int[256];
        this.sourceLuminanceBuffer = new double[0];
        this.shadedLuminanceBuffer = new double[0];
        this.detailLuminanceBuffer = new double[0];
        this.luminanceHistogram = new int[256];
        this.autoContrastPrimed = false;
        this.smoothedAutoLow = 0.0;
        this.smoothedAutoHigh = 1.0;
        this.rasterUnlitMode = false;
        this.rasterFlatShading = false;
        this.rasterModelPreviewMode = false;

        baseRenderer.setParameter("unlitMode", rasterUnlitMode);
        baseRenderer.setParameter("flatShading", rasterFlatShading);
        baseRenderer.setParameter("modelPreviewMode", rasterModelPreviewMode);
        baseRenderer.setParameter("frustumCulling", true);
        baseRenderer.setParameter("backfaceCulling", false);
        regenerateBlueNoise();
        rebuildAsciiCandidates();
    }

    @Override
    public void init(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        baseRenderer.init(w, h);
        gBufferFrame = new FrameBuffer(w, h, true);
        detailFrame = new FrameBuffer(detailReferenceWidth(w, h), detailReferenceHeight(w, h));
        ensureBuffers(w * h);
        resetAutoContrast();
        regenerateBlueNoise();
    }

    @Override
    public void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        if (scene == null || camera == null || fb == null) {
            return;
        }

        int w = fb.getWidth();
        int h = fb.getHeight();
        ensureRenderTargets(w, h);
        renderDetailReference(scene, camera, time);
        baseRenderer.render(scene, camera, gBufferFrame, time);

        float[] srcDepth = gBufferFrame.getDepthBuffer();
        float[] dstDepth = fb.getDepthBuffer();
        if (srcDepth != null && dstDepth != null && srcDepth.length == dstDepth.length) {
            System.arraycopy(srcDepth, 0, dstDepth, 0, srcDepth.length);
        }

        prepareDitherLuminance(gBufferFrame, detailFrame, w, h);
        int[] dst = fb.getColorBuffer();

        switch (style) {
            case PATTERN:
                applyPattern(dst, w, h);
                break;
            case ASCII:
                applyAscii(dst, w, h);
                break;
            case BLUE_NOISE:
            default:
                applyBlueNoise(dst, w, h);
                break;
        }
    }

    @Override
    public void resize(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        baseRenderer.resize(w, h);
        gBufferFrame = new FrameBuffer(w, h, true);
        detailFrame = new FrameBuffer(detailReferenceWidth(w, h), detailReferenceHeight(w, h));
        ensureBuffers(w * h);
        resetAutoContrast();
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
        if ("unlitMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            rasterUnlitMode = (Boolean) value;
            baseRenderer.setParameter(key, value);
            return;
        }
        if ("flatShading".equalsIgnoreCase(key) && value instanceof Boolean) {
            rasterFlatShading = (Boolean) value;
            baseRenderer.setParameter(key, value);
            return;
        }
        if ("modelPreviewMode".equalsIgnoreCase(key) && value instanceof Boolean) {
            rasterModelPreviewMode = (Boolean) value;
            baseRenderer.setParameter(key, value);
            return;
        }
        if ("lightAssist".equalsIgnoreCase(key) && value instanceof Number) {
            lightAssist = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if ("cellSize".equalsIgnoreCase(key) && value instanceof Number) {
            cellSize = Math.max(2, ((Number) value).intValue());
            bitFont = new BitFont(cellSize, cellSize);
            rebuildAsciiCandidates();
            return;
        }
        if ("asciiCharset".equalsIgnoreCase(key) && value instanceof String) {
            asciiCharset = BitFont.sanitizeCharset((String) value);
            rebuildAsciiCandidates();
            return;
        }

 // předám obecné parametry vykreslení a výkonu do základního rasterizéru.
        baseRenderer.setParameter(key, value);
    }

    @Override
    public String getName() {
        return style == DitherStyle.ASCII ? "ASCII" : "Dithering";
    }

    public DitherStyle getStyle() {
        return style;
    }

    private void applyBlueNoise(int[] dst, int w, int h) {
        int mask = BLUE_NOISE_SIZE - 1;
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int nRow = (y & mask) * BLUE_NOISE_SIZE;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                double lum = adjustedLuminance(sourceLuminanceBuffer[idx]);
                float threshold = blueNoiseMap[nRow + (x & mask)];
                double q = quantizeWithThreshold(lum, threshold);
                dst[idx] = gray(q);
            }
        }
    }

    private void applyPattern(int[] dst, int w, int h) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            int by = y & 3;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                double lum = adjustedLuminance(sourceLuminanceBuffer[idx]);
                float threshold = (bayerMatrix[by][x & 3] + 0.5f) / 16.0f;
                double q = quantizeWithThreshold(lum, threshold);
                dst[idx] = gray(q);
            }
        }
    }

    private void applyAscii(int[] dst, int w, int h) {
        int cellW = bitFont.getCharWidth();
        int cellH = bitFont.getCharHeight();
        if (asciiCandidates.length == 0) {
            return;
        }
        prepareAsciiLuminance(w, h);
        ensureAsciiCellBuffer(cellW * cellH);

        for (int i = 0; i < dst.length; i++) {
            dst[i] = 0xFF000000;
        }

        for (int y0 = 0; y0 < h; y0 += cellH) {
            int y1 = Math.min(h, y0 + cellH);
            for (int x0 = 0; x0 < w; x0 += cellW) {
                int x1 = Math.min(w, x0 + cellW);
                double cellEnergy = sampleAsciiCell(w, x0, y0, x1, y1, cellW, cellH);
                AsciiCandidate bestCandidate = asciiCandidates[0];
                double bestError = Double.POSITIVE_INFINITY;
                for (AsciiCandidate candidate : asciiCandidates) {
                    double activeSum = 0.0;
                    for (int activeIndex : candidate.activeIndices) {
                        activeSum += asciiCellBuffer[activeIndex];
                    }

 // ASCII drží jako čistě bílý glyph na černé, takže kontrast dělám výběrem bitmapy.
                    double error = cellEnergy - 2.0 * activeSum + candidate.activeCount;
                    if (error < bestError) {
                        bestError = error;
                        bestCandidate = candidate;
                    }
                }

                bitFont.drawChar(dst, w, x0, y0, bestCandidate.glyph, 0xFFFFFFFF, 0xFF000000);
            }
        }
    }

    private double adjustedLuminance(double lum) {
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
        if (sourceLuminanceBuffer.length != pixelCount) {
            sourceLuminanceBuffer = new double[pixelCount];
        }
        if (shadedLuminanceBuffer.length != pixelCount) {
            shadedLuminanceBuffer = new double[pixelCount];
        }
        if (detailLuminanceBuffer.length != pixelCount) {
            detailLuminanceBuffer = new double[pixelCount];
        }
    }

    private void ensureAsciiCellBuffer(int cellPixelCount) {
        if (asciiCellBuffer.length != cellPixelCount) {
            asciiCellBuffer = new double[cellPixelCount];
        }
    }

    private void ensureAsciiLuminanceBuffer(int pixelCount) {
        if (asciiLuminanceBuffer.length != pixelCount) {
            asciiLuminanceBuffer = new double[pixelCount];
        }
    }

    private double sampleAsciiCell(int stride, int x0, int y0, int x1, int y1, int cellW, int cellH) {
        int srcW = Math.max(1, x1 - x0);
        int srcH = Math.max(1, y1 - y0);
        double energy = 0.0;
        int writeIndex = 0;

        if (srcW == cellW && srcH == cellH) {
            for (int y = y0; y < y1; y++) {
                int row = y * stride;
                for (int x = x0; x < x1; x++) {
                    double lum = asciiLuminanceBuffer[row + x];
                    asciiCellBuffer[writeIndex++] = lum;
                    energy += lum * lum;
                }
            }
            return energy;
        }

 // si blok převzorkuju do stejné mřížky, ve které mám uloženou bitmapu glyphu.
        for (int gy = 0; gy < cellH; gy++) {
            int sampleY = y0 + (int) (((gy + 0.5) * srcH) / cellH);
            if (sampleY >= y1) {
                sampleY = y1 - 1;
            }
            int row = sampleY * stride;
            for (int gx = 0; gx < cellW; gx++) {
                int sampleX = x0 + (int) (((gx + 0.5) * srcW) / cellW);
                if (sampleX >= x1) {
                    sampleX = x1 - 1;
                }
                double lum = asciiLuminanceBuffer[row + sampleX];
                asciiCellBuffer[writeIndex++] = lum;
                energy += lum * lum;
            }
        }
        return energy;
    }

    private void prepareAsciiLuminance(int w, int h) {
        int pixelCount = w * h;
        ensureAsciiLuminanceBuffer(pixelCount);
        for (int i = 0; i < asciiHistogram.length; i++) {
            asciiHistogram[i] = 0;
        }

        for (int i = 0; i < pixelCount; i++) {
            double lum = adjustedLuminance(sourceLuminanceBuffer[i]);
            asciiLuminanceBuffer[i] = lum;
            int bin = (int) Math.round(lum * 255.0);
            if (bin < 0) {
                bin = 0;
            } else if (bin > 255) {
                bin = 255;
            }
            asciiHistogram[bin]++;
        }

        int lowBin = percentileBin(pixelCount, ASCII_LOW_PERCENTILE);
        int highBin = percentileBin(pixelCount, ASCII_HIGH_PERCENTILE);
        if (highBin > lowBin) {
            double low = lowBin / 255.0;
            double high = highBin / 255.0;
            double invRange = 1.0 / Math.max(1e-6, high - low);
            for (int i = 0; i < pixelCount; i++) {
                asciiLuminanceBuffer[i] = clamp01((asciiLuminanceBuffer[i] - low) * invRange);
            }
        }

 // stejné tónové úrovně promítnu i do ASCII, aby mi Počet tónů fungoval napříč všemi dither styly.
        for (int i = 0; i < pixelCount; i++) {
            asciiLuminanceBuffer[i] = quantizeNearest(asciiLuminanceBuffer[i], toneCount);
        }
    }

    private void prepareDitherLuminance(FrameBuffer sourceFb, int w, int h) {
        FrameBuffer detailReference = detailFrame != null
                && detailFrame.getWidth() == w
                && detailFrame.getHeight() == h
                ? detailFrame
                : sourceFb;
        prepareDitherLuminance(sourceFb, detailReference, w, h);
    }

    private void prepareDitherLuminance(FrameBuffer sourceFb, FrameBuffer detailFb, int w, int h) {
        int pixelCount = w * h;
        ensureBuffers(pixelCount);
        int[] color = sourceFb.getColorBuffer();
        float[] depth = sourceFb.getDepthBuffer();
        int[] objectId = sourceFb.getObjectIdBuffer();
        float[] normal = sourceFb.getNormalBuffer();

        boolean useDetailReference = detailFb != null
                && detailFb.getColorBuffer() != null
                && detailFb.getWidth() > 0
                && detailFb.getHeight() > 0;
        if (useDetailReference) {
            sampleDetailReference(detailFb, color, w, h);
        } else {
            for (int i = 0; i < pixelCount; i++) {
                double luminance = rawLuminance(color[i]);
                shadedLuminanceBuffer[i] = luminance;
                detailLuminanceBuffer[i] = luminance;
            }
        }
        if (lightAssist > 1e-6 && depth != null && normal != null) {
            applyLightAssist(w, h, depth, objectId, normal);
        }

        LuminanceRange shadedRange = smoothAutoContrast(measurePercentileRange(
                shadedLuminanceBuffer, depth, AUTO_LOW_PERCENTILE, AUTO_HIGH_PERCENTILE
        ));
        LuminanceRange detailRange = measurePercentileRange(
                detailLuminanceBuffer, depth, DETAIL_LOW_PERCENTILE, DETAIL_HIGH_PERCENTILE
        );
        double shadedSpan = Math.max(1e-6, shadedRange.high - shadedRange.low);
        double rangeDeficit = clamp01((AUTO_TARGET_SPAN - shadedSpan) / AUTO_TARGET_SPAN);
        for (int i = 0; i < pixelCount; i++) {
            detailLuminanceBuffer[i] = expandContrast(detailLuminanceBuffer[i], detailRange, DETAIL_CONTRAST_RESPONSE);
        }

 // si chytře míchám nasvícený obraz a unlit detail tak, aby mi světlo nezahodilo texturu ani malé rozdíly.
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                double shadedNorm = expandContrast(shadedLuminanceBuffer[idx], shadedRange, SHADED_CONTRAST_RESPONSE);
                double detailNorm = detailLuminanceBuffer[idx];
                double extreme = 1.0 - clamp01(Math.min(shadedNorm, 1.0 - shadedNorm) / EXTREME_LUMA_WINDOW);
                double detailGap = clamp01(Math.abs(detailNorm - shadedNorm) * 2.2);
                double detailResidual = detailGap > DETAIL_RESIDUAL_GATE
                        ? estimateDetailResidual(x, y, w, h, depth, objectId)
                        : 0.0;
                double recoveryMix = clamp01((0.12 + 0.24 * rangeDeficit + 0.18 * extreme)
 * (0.35 + 0.65 * detailGap));
                double detailBoost = 0.06 + 0.14 * rangeDeficit + 0.10 * extreme;
                sourceLuminanceBuffer[idx] = clamp01(mix(shadedNorm, detailNorm, recoveryMix)
                        + detailResidual * detailBoost);
            }
        }

        applyFinalContrast(depth);
    }

    private void applyLightAssist(int w, int h, float[] depth, int[] objectId, float[] normal) {
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) {
                int idx = row + x;
                if (depth[idx] >= GEOMETRY_DEPTH_LIMIT) {
                    continue;
                }

                int normalBase = idx * 3;
                double ny = normal[normalBase + 1];
                double baseLum = shadedLuminanceBuffer[idx];
                double contactShadow = estimateContactShadow(x, y, w, h, depth, objectId, normal);
                double skyFacing = clamp01(ny * 0.5 + 0.5);
                double shadowLift = lightAssist
 * (0.05 + 0.23 * skyFacing)
 * Math.pow(1.0 - baseLum, 1.18)
 * (1.0 - contactShadow * 0.55);
                double shadowFactor = 1.0 - lightAssist * 0.24 * contactShadow;
                shadedLuminanceBuffer[idx] = clamp01(baseLum * shadowFactor + shadowLift);
            }
        }
    }

    private double estimateContactShadow(int x,
                                         int y,
                                         int width,
                                         int height,
                                         float[] depth,
                                         int[] objectId,
                                         float[] normal) {
        int idx = y * width + x;
        float currentDepth = depth[idx];
        if (currentDepth >= GEOMETRY_DEPTH_LIMIT) {
            return 0.0;
        }

        int currentObject = objectId == null ? -1 : objectId[idx];
        int normalBase = idx * 3;
        double nx = normal[normalBase];
        double ny = normal[normalBase + 1];
        double nz = normal[normalBase + 2];
        double occlusion = 0.0;
        int hitCount = 0;
        for (int sample = 0; sample < CONTACT_SAMPLE_X.length; sample++) {
            int sx = x + CONTACT_SAMPLE_X[sample];
            int sy = y + CONTACT_SAMPLE_Y[sample];
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                continue;
            }

            int sampleIndex = sy * width + sx;
            float sampleDepth = depth[sampleIndex];
            if (sampleDepth >= GEOMETRY_DEPTH_LIMIT) {
                continue;
            }

            double depthDelta = currentDepth - sampleDepth;
            if (depthDelta <= CONTACT_SHADOW_BIAS || depthDelta >= CONTACT_SHADOW_MAX_DEPTH_DELTA) {
                continue;
            }

            double weight = 1.0 - depthDelta / CONTACT_SHADOW_MAX_DEPTH_DELTA;
            if (objectId != null) {
                int sampleObject = objectId[sampleIndex];
                if (currentObject >= 0 && sampleObject >= 0 && sampleObject != currentObject) {
                    weight *= 1.15;
                }
            }

            int sampleNormalBase = sampleIndex * 3;
            double dot = nx * normal[sampleNormalBase]
                    + ny * normal[sampleNormalBase + 1]
                    + nz * normal[sampleNormalBase + 2];
            weight *= 0.65 + 0.35 * clamp01(0.5 + 0.5 * dot);
            occlusion += weight;
            hitCount++;
        }
        if (hitCount <= 0) {
            return 0.0;
        }
        return clamp01(occlusion / hitCount);
    }

    private double rawLuminance(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    private void ensureRenderTargets(int width, int height) {
        ensureBuffers(width * height);
        if (gBufferFrame == null || gBufferFrame.getWidth() != width || gBufferFrame.getHeight() != height) {
            gBufferFrame = new FrameBuffer(width, height, true);
            baseRenderer.resize(width, height);
            resetAutoContrast();
        }

        int detailWidth = detailReferenceWidth(width, height);
        int detailHeight = detailReferenceHeight(width, height);
        if (detailFrame == null || detailFrame.getWidth() != detailWidth || detailFrame.getHeight() != detailHeight) {
            detailFrame = new FrameBuffer(detailWidth, detailHeight);
            resetAutoContrast();
        }
    }

    private void renderDetailReference(Scene scene, Camera camera, double time) {
        if (detailFrame == null) {
            return;
        }

        baseRenderer.setParameter("unlitMode", true);
        baseRenderer.setParameter("flatShading", false);
        baseRenderer.setParameter("modelPreviewMode", false);
        try {
 // si vykreslí pomocnou unlit referenci, abych z ní vytáhl texturu a lokální detail bez vlivu světla.
            baseRenderer.render(scene, camera, detailFrame, time);
        } finally {
            baseRenderer.setParameter("unlitMode", rasterUnlitMode);
            baseRenderer.setParameter("flatShading", rasterFlatShading);
            baseRenderer.setParameter("modelPreviewMode", rasterModelPreviewMode);
        }
    }

    private void sampleDetailReference(FrameBuffer detailFb, int[] shadedColor, int fullWidth, int fullHeight) {
        int detailWidth = detailFb.getWidth();
        int detailHeight = detailFb.getHeight();
        int[] detailColor = detailFb.getColorBuffer();
        boolean sameResolution = detailWidth == fullWidth && detailHeight == fullHeight && detailColor.length == shadedColor.length;

        if (sameResolution) {
            for (int i = 0; i < shadedColor.length; i++) {
                shadedLuminanceBuffer[i] = rawLuminance(shadedColor[i]);
                detailLuminanceBuffer[i] = rawLuminance(detailColor[i]);
            }
            return;
        }

        for (int y = 0; y < fullHeight; y++) {
            int row = y * fullWidth;
            int sampleY = Math.min(detailHeight - 1, (int) (((y + 0.5) * detailHeight) / Math.max(1, fullHeight)));
            int detailRow = sampleY * detailWidth;
            for (int x = 0; x < fullWidth; x++) {
                int idx = row + x;
                int sampleX = Math.min(detailWidth - 1, (int) (((x + 0.5) * detailWidth) / Math.max(1, fullWidth)));
                shadedLuminanceBuffer[idx] = rawLuminance(shadedColor[idx]);
                detailLuminanceBuffer[idx] = rawLuminance(detailColor[detailRow + sampleX]);
            }
        }
    }

    private void applyFinalContrast(float[] depth) {
        LuminanceRange finalRange = measurePercentileRange(
                sourceLuminanceBuffer, depth, FINAL_LOW_PERCENTILE, FINAL_HIGH_PERCENTILE
        );
        double span = Math.max(1e-6, finalRange.high - finalRange.low);
        double stretchAmount = clamp01((FINAL_STRETCH_THRESHOLD - span) / FINAL_STRETCH_THRESHOLD) * FINAL_STRETCH_RESPONSE;
        if (stretchAmount <= 1e-6) {
            return;
        }

        for (int i = 0; i < sourceLuminanceBuffer.length; i++) {
            if (depth != null && depth[i] >= GEOMETRY_DEPTH_LIMIT) {
                continue;
            }
            sourceLuminanceBuffer[i] = softlyExpandContrast(sourceLuminanceBuffer[i], finalRange, stretchAmount);
        }
    }

    private double estimateDetailResidual(int x,
                                          int y,
                                          int width,
                                          int height,
                                          float[] depth,
                                          int[] objectId) {
        int idx = y * width + x;
        double center = detailLuminanceBuffer[idx];
        float currentDepth = depth == null ? 0.0f : depth[idx];
        int currentObject = objectId == null ? -1 : objectId[idx];
        double sum = 0.0;
        int count = 0;

        for (int sample = 0; sample < DETAIL_SAMPLE_X.length; sample++) {
            int sx = x + DETAIL_SAMPLE_X[sample];
            int sy = y + DETAIL_SAMPLE_Y[sample];
            if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                continue;
            }

            int sampleIndex = sy * width + sx;
            if (depth != null) {
                float sampleDepth = depth[sampleIndex];
                if (sampleDepth >= GEOMETRY_DEPTH_LIMIT) {
                    continue;
                }
                if (Math.abs(sampleDepth - currentDepth) > DETAIL_NEIGHBOR_DEPTH_DELTA) {
                    continue;
                }
            }
            if (objectId != null && currentObject >= 0 && objectId[sampleIndex] >= 0 && objectId[sampleIndex] != currentObject) {
                continue;
            }

            sum += detailLuminanceBuffer[sampleIndex];
            count++;
        }
        if (count <= 0) {
            return 0.0;
        }
        return center - sum / count;
    }

    private LuminanceRange measurePercentileRange(double[] values,
                                                  float[] depth,
                                                  double lowPercentile,
                                                  double highPercentile) {
        for (int i = 0; i < luminanceHistogram.length; i++) {
            luminanceHistogram[i] = 0;
        }

        int samples = 0;
        double minValue = 1.0;
        double maxValue = 0.0;
        for (int i = 0; i < values.length; i++) {
            if (depth != null && depth[i] >= GEOMETRY_DEPTH_LIMIT) {
                continue;
            }
            double value = clamp01(values[i]);
            int bin = (int) Math.round(value * 255.0);
            luminanceHistogram[bin]++;
            samples++;
            minValue = Math.min(minValue, value);
            maxValue = Math.max(maxValue, value);
        }
        if (samples <= 0) {
            for (double value : values) {
                double clamped = clamp01(value);
                int bin = (int) Math.round(clamped * 255.0);
                luminanceHistogram[bin]++;
                minValue = Math.min(minValue, clamped);
                maxValue = Math.max(maxValue, clamped);
            }
            samples = values.length;
        }
        if (samples <= 0) {
            return new LuminanceRange(0.0, 1.0);
        }
        if (maxValue - minValue <= (1.5 / 255.0)) {
            double pad = 0.5 / 255.0;
            return new LuminanceRange(
                    Math.max(0.0, minValue - pad),
                    Math.min(1.0, maxValue + pad)
            );
        }

        int lowBin = percentileBin(luminanceHistogram, samples, lowPercentile);
        int highBin = percentileBin(luminanceHistogram, samples, highPercentile);
        if (highBin <= lowBin) {
            highBin = Math.min(255, lowBin + 1);
        }
        return new LuminanceRange(
                Math.min(lowBin / 255.0, minValue),
                Math.max(highBin / 255.0, maxValue)
        );
    }

    private LuminanceRange smoothAutoContrast(LuminanceRange currentRange) {
        if (!autoContrastPrimed) {
            smoothedAutoLow = currentRange.low;
            smoothedAutoHigh = currentRange.high;
            autoContrastPrimed = true;
        } else {
            smoothedAutoLow = mix(smoothedAutoLow, currentRange.low, AUTO_CONTRAST_SMOOTHING);
            smoothedAutoHigh = mix(smoothedAutoHigh, currentRange.high, AUTO_CONTRAST_SMOOTHING);
        }
        if (smoothedAutoHigh <= smoothedAutoLow + 1e-6) {
            smoothedAutoHigh = smoothedAutoLow + 1e-6;
        }
        return new LuminanceRange(smoothedAutoLow, smoothedAutoHigh);
    }

    private void resetAutoContrast() {
        autoContrastPrimed = false;
        smoothedAutoLow = 0.0;
        smoothedAutoHigh = 1.0;
    }

    private int percentileBin(int pixelCount, double percentile) {
        return percentileBin(asciiHistogram, pixelCount, percentile);
    }

    private int percentileBin(int[] histogram, int sampleCount, double percentile) {
        int target = (int) Math.round(clamp01(percentile) * Math.max(0, sampleCount - 1));
        int sum = 0;
        for (int bin = 0; bin < histogram.length; bin++) {
            sum += histogram[bin];
            if (sum > target) {
                return bin;
            }
        }
        return histogram.length - 1;
    }

    private void rebuildAsciiCandidates() {
        asciiCharset = BitFont.sanitizeCharset(asciiCharset);
        int cellW = bitFont.getCharWidth();
        int cellH = bitFont.getCharHeight();
        ensureAsciiCellBuffer(cellW * cellH);
        asciiCandidates = new AsciiCandidate[asciiCharset.length()];

        for (int i = 0; i < asciiCharset.length(); i++) {
            char glyph = asciiCharset.charAt(i);
            boolean[][] mask = bitFont.getGlyph(glyph);
            int activeCount = 0;
            for (int y = 0; y < cellH; y++) {
                boolean[] row = mask[y];
                for (int x = 0; x < cellW; x++) {
                    if (row[x]) {
                        activeCount++;
                    }
                }
            }

            int[] activeIndices = new int[activeCount];
            int writeIndex = 0;
            for (int y = 0; y < cellH; y++) {
                boolean[] row = mask[y];
                int base = y * cellW;
                for (int x = 0; x < cellW; x++) {
                    if (row[x]) {
                        activeIndices[writeIndex++] = base + x;
                    }
                }
            }
            asciiCandidates[i] = new AsciiCandidate(glyph, activeIndices);
        }
    }

    private double clamp01(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }

    private double mix(double a, double b, double t) {
        double weight = clamp01(t);
        return a + (b - a) * weight;
    }

    private double quantizeNearest(double value, int levels) {
        int toneLevels = Math.max(2, levels);
        int tone = (int) Math.round(clamp01(value) * (toneLevels - 1));
        if (tone < 0) {
            tone = 0;
        } else if (tone >= toneLevels) {
            tone = toneLevels - 1;
        }
        return tone / (double) (toneLevels - 1);
    }

    private double softlyExpandContrast(double value, LuminanceRange range, double amount) {
        return clamp01(mix(value, expandContrast(value, range, amount), amount));
    }

    private double expandContrast(double value, LuminanceRange range, double response) {
        double span = Math.max(1e-6, range.high - range.low);
        double center = (range.low + range.high) * 0.5;
        double targetSpan = span + Math.max(0.0, AUTO_TARGET_SPAN - span) * clamp01(response);
        double gain = targetSpan / span;
        return clamp01(center + (value - center) * gain);
    }

    private int detailReferenceWidth(int width, int height) {
        return scaledDetailDimension(width, width * height);
    }

    private int detailReferenceHeight(int width, int height) {
        return scaledDetailDimension(height, width * height);
    }

    private int scaledDetailDimension(int dimension, int pixelCount) {
        if (pixelCount <= DETAIL_REFERENCE_FULL_RES_PIXELS) {
            return Math.max(1, dimension);
        }
        return Math.max(1, (int) Math.round(dimension * DETAIL_REFERENCE_SCALE));
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

    private static final class AsciiCandidate {
        private final char glyph;
        private final int[] activeIndices;
        private final int activeCount;

        private AsciiCandidate(char glyph, int[] activeIndices) {
            this.glyph = glyph;
            this.activeIndices = activeIndices;
            this.activeCount = activeIndices.length;
        }
    }

    private static final class LuminanceRange {
        private final double low;
        private final double high;

        private LuminanceRange(double low, double high) {
            this.low = low;
            this.high = high;
        }
    }
}