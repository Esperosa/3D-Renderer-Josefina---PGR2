package engine.render.post;

import engine.camera.Camera;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.raster.RasterRenderer;
import engine.scene.Scene;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Tady držím adaptivní hexagonální mozaikový renderer s buněčně stínovaným vzhledem.
 * Rozkládám v něm obraz na hexagonální buňky, které plním kvantizovanou barvou a volitelným stylizovaným patternem.
 */
public class HexMosaicRenderer implements Renderer {

    private static final double SQRT3 = 1.7320508075688772;
    private static final int WOW_CLASSIC = 0;
    private static final int WOW_PRISM = 1;
    private static final int WOW_NEON = 2;

    private final RasterRenderer baseRenderer;

    private int width = 1;
    private int height = 1;
    private int pixelCount = 1;

    private double cellSize;
    private int quantizationLevels;
    private double outlineStrength;
    private double cellJitter;
    private boolean edgeAware;
    private boolean distanceScaling;
    private boolean debugCells;
    private int wowMode;
    private double wowStrength;

    private int[] sourceColor = new int[1];
    private int[] outputColor = new int[1];
    private int[] pixelCellId = new int[1];
    private byte[] preBoundaryMask = new byte[1];
    private float[] preHexEdgeFactor = new float[1];

    private final HashMap<Long, Integer> cellIndex = new HashMap<>();
    private final List<CellData> cells = new ArrayList<>();
    private boolean mappingDirty = true;

    public HexMosaicRenderer() {
        this.baseRenderer = new RasterRenderer();
        this.cellSize = 6.0;
        this.quantizationLevels = 8;
        this.outlineStrength = 0.42;
        this.cellJitter = 0.0;
        this.edgeAware = false;
        this.distanceScaling = false;
        this.debugCells = false;
        this.wowMode = WOW_CLASSIC;
        this.wowStrength = 0.25;
    }

    @Override
    public void init(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.pixelCount = this.width * this.height;
        ensureBuffers();
        mappingDirty = true;
        baseRenderer.init(this.width, this.height);
        baseRenderer.setParameter("unlitMode", false);
    }

    @Override
    public void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        int fbW = fb.getWidth();
        int fbH = fb.getHeight();
        if (fbW != width || fbH != height) {
            resize(fbW, fbH);
        }

        baseRenderer.render(scene, camera, fb, time);

        int[] dst = fb.getColorBuffer();
        float[] depth = fb.getDepthBuffer();
        System.arraycopy(dst, 0, sourceColor, 0, pixelCount);

        double baseRadius = Math.max(4.0, cellSize);
        if (mappingDirty) {
            rebuildCellMapping(baseRadius);
        }

        for (int i = 0; i < cells.size(); i++) {
            cells.get(i).reset();
        }

        for (int idx = 0; idx < pixelCount; idx++) {
            int id = pixelCellId[idx];
            if (id < 0 || id >= cells.size()) {
                continue;
            }
            cells.get(id).accumulate(sourceColor[idx], depth[idx]);
        }

        for (int i = 0; i < cells.size(); i++) {
            finalizeCell(cells.get(i), time, baseRadius);
        }

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                CellData cell = cells.get(pixelCellId[idx]);
                int color = cell.finalColor;

                double edgeBoost = 0.0;
                boolean boundary = preBoundaryMask[idx] != 0;
                if (boundary) {
                    edgeBoost += 0.25;
                }
                if (distanceScaling) {
                    edgeBoost += hexEdgeStrength(x + 0.5, y + 0.5, cell.centerX, cell.centerY, cell.radius) * 0.85;
                } else {
                    edgeBoost += preHexEdgeFactor[idx] * 0.85;
                }
                if (edgeAware) {
                    edgeBoost += depthEdgeStrength(depth, x, y) * 0.45;
                }

                if (outlineStrength > 0.001 && edgeBoost > 0.001) {
                    double darken = Math.max(0.0, Math.min(1.0, outlineStrength * edgeBoost));
                    color = blend(color, 0xFF050709, darken);
                    if (wowMode == WOW_NEON) {
                        int glow = tintColor(cell.finalColor, 0xFF00C5FF, 0.55 + 0.35 * cell.phase);
                        color = blend(color, glow, Math.min(0.70, wowStrength * 0.55 * edgeBoost));
                    }
                }

                outputColor[idx] = color;
            }
        }

        System.arraycopy(outputColor, 0, dst, 0, pixelCount);
    }

    @Override
    public void resize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.pixelCount = this.width * this.height;
        ensureBuffers();
        mappingDirty = true;
        baseRenderer.resize(this.width, this.height);
    }

    @Override
    public void setParameter(String key, Object value) {
        if (key == null) {
            return;
        }
        String k = key.trim().toLowerCase();
        if ("cellsize".equals(k) && value instanceof Number) {
            cellSize = Math.max(4.0, Math.min(64.0, ((Number) value).doubleValue()));
            mappingDirty = true;
            return;
        }
        if (("quantizationlevels".equals(k) || "levels".equals(k)) && value instanceof Number) {
            quantizationLevels = Math.max(2, Math.min(32, ((Number) value).intValue()));
            return;
        }
        if ("outlinestrength".equals(k) && value instanceof Number) {
            outlineStrength = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if ("celljitter".equals(k) && value instanceof Number) {
            cellJitter = Math.max(0.0, Math.min(0.49, ((Number) value).doubleValue()));
            mappingDirty = true;
            return;
        }
        if ("edgeaware".equals(k) && value instanceof Boolean) {
            edgeAware = (Boolean) value;
            return;
        }
        if ("distancescaling".equals(k) && value instanceof Boolean) {
            distanceScaling = (Boolean) value;
            return;
        }
        if ("debugcells".equals(k) && value instanceof Boolean) {
            debugCells = (Boolean) value;
            return;
        }
        if ("wowstrength".equals(k) && value instanceof Number) {
            wowStrength = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if ("wowmode".equals(k) && value instanceof Number) {
            wowMode = Math.max(0, Math.min(2, ((Number) value).intValue()));
            return;
        }
        if ("wowmode".equals(k) && value instanceof String) {
            String mode = ((String) value).trim().toLowerCase();
            if ("classic".equals(mode)) {
                wowMode = WOW_CLASSIC;
            } else if ("prism".equals(mode)) {
                wowMode = WOW_PRISM;
            } else if ("neon".equals(mode)) {
                wowMode = WOW_NEON;
            }
            return;
        }
        if ("cyclewowmode".equals(k)) {
            wowMode = (wowMode + 1) % 3;
            return;
        }

        // Tady přepošlu sdílené raster ovladače do základního passu.
        baseRenderer.setParameter(key, value);
    }

    @Override
    public String getName() {
        if (wowMode == WOW_CLASSIC) {
            return "Hex Mosaic (Classic)";
        }
        if (wowMode == WOW_NEON) {
            return "Hex Mosaic (Neon)";
        }
        return "Hex Mosaic (Prism)";
    }

    public String getWowModeName() {
        if (wowMode == WOW_CLASSIC) {
            return "classic";
        }
        if (wowMode == WOW_NEON) {
            return "neon";
        }
        return "prism";
    }

    private void ensureBuffers() {
        if (sourceColor.length != pixelCount) {
            sourceColor = new int[pixelCount];
        }
        if (outputColor.length != pixelCount) {
            outputColor = new int[pixelCount];
        }
        if (pixelCellId.length != pixelCount) {
            pixelCellId = new int[pixelCount];
        }
        if (preBoundaryMask.length != pixelCount) {
            preBoundaryMask = new byte[pixelCount];
        }
        if (preHexEdgeFactor.length != pixelCount) {
            preHexEdgeFactor = new float[pixelCount];
        }
    }

    private long packCell(int row, int col) {
        return (((long) row) << 32) ^ (col & 0xffffffffL);
    }

    private void rebuildCellMapping(double baseRadius) {
        mappingDirty = false;
        cellIndex.clear();
        cells.clear();
        for (int i = 0; i < pixelCount; i++) {
            pixelCellId[i] = -1;
            preBoundaryMask[i] = 0;
            preHexEdgeFactor[i] = 0.0f;
        }

        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                AxialCoord axial = pixelToAxial(x + 0.5, y + 0.5, baseRadius);
                long key = packCell(axial.r, axial.q);
                Integer id = cellIndex.get(key);
                if (id == null) {
                    int created = cells.size();
                    CellData cell = new CellData();
                    cell.row = axial.r;
                    cell.col = axial.q;
                    double[] center = axialToPixel(axial.q, axial.r, baseRadius);
                    cell.centerX = center[0];
                    cell.centerY = center[1];
                    applyCellJitter(cell, key, baseRadius);
                    cells.add(cell);
                    cellIndex.put(key, created);
                    id = created;
                }
                pixelCellId[idx] = id;
            }
        }

        // Tady si předpočítám statickou masku hran a faktor hran hexů, abych se vyhnul těžké matematice v každém snímku.
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                int id = pixelCellId[idx];
                if (id < 0 || id >= cells.size()) {
                    continue;
                }
                CellData cell = cells.get(id);
                preHexEdgeFactor[idx] = (float) hexEdgeStrength(x + 0.5, y + 0.5, cell.centerX, cell.centerY, baseRadius);
                preBoundaryMask[idx] = (byte) (isBoundary(idx, x, y) ? 1 : 0);
            }
        }
    }

    private void applyCellJitter(CellData cell, long key, double radius) {
        if (cellJitter <= 1e-6) {
            return;
        }
        double jitter = cellJitter * radius;
        double jx = hash01(key * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L) * 2.0 - 1.0;
        double jy = hash01(key * 0xC2B2AE3D27D4EB4FL + 0x165667B19E3779F9L) * 2.0 - 1.0;
        cell.centerX += jx * jitter;
        cell.centerY += jy * jitter;
    }

    private void finalizeCell(CellData cell, double time, double baseRadius) {
        if (cell.count <= 0) {
            cell.finalColor = 0xFF000000;
            return;
        }

        if (debugCells) {
            cell.finalColor = debugCellColor(cell.col, cell.row);
            cell.radius = Math.max(3.0, baseRadius);
            long dKey = packCell(cell.row, cell.col);
            cell.phase = hash01(dKey * 0x94D049BB133111EBL + 0xA24BAED4963EE407L);
            return;
        }

        double inv = 1.0 / cell.count;
        double r = (cell.sumR * inv) / 255.0;
        double g = (cell.sumG * inv) / 255.0;
        double b = (cell.sumB * inv) / 255.0;
        double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        double qLum = quantize(lum);
        if (lum > 1e-6) {
            double gain = qLum / lum;
            r = Math.max(0.0, Math.min(1.0, r * gain));
            g = Math.max(0.0, Math.min(1.0, g * gain));
            b = Math.max(0.0, Math.min(1.0, b * gain));
        } else {
            r = qLum;
            g = qLum;
            b = qLum;
        }

        double avgDepth = cell.depthSum * inv;
        double radius = baseRadius;
        if (distanceScaling) {
            radius = baseRadius * (0.72 + avgDepth * 0.95);
        }
        cell.radius = Math.max(3.0, radius);

        long key = packCell(cell.row, cell.col);
        cell.phase = hash01(key * 0x94D049BB133111EBL + 0xA24BAED4963EE407L);

        if (wowMode == WOW_PRISM) {
            double shift = (Math.sin(time * 0.72 + cell.phase * 12.0) * 0.5 + 0.5) * 2.0 - 1.0;
            shift *= 0.22 * wowStrength;
            double[] hsv = rgbToHsv(r, g, b);
            hsv[0] = wrap01(hsv[0] + shift);
            hsv[1] = Math.max(0.0, Math.min(1.0, hsv[1] * (1.0 + 0.30 * wowStrength)));
            hsv[2] = Math.max(0.0, Math.min(1.0, hsv[2] * (0.96 + 0.12 * wowStrength)));
            double[] rgb = hsvToRgb(hsv[0], hsv[1], hsv[2]);
            r = rgb[0];
            g = rgb[1];
            b = rgb[2];
        } else if (wowMode == WOW_NEON) {
            double pulse = 0.68 + 0.32 * Math.sin(time * 1.35 + cell.phase * 20.0);
            double lift = wowStrength * Math.max(0.0, pulse);
            r = Math.max(0.0, Math.min(1.0, r * (0.85 + 0.18 * lift)));
            g = Math.max(0.0, Math.min(1.0, g * (0.95 + 0.24 * lift)));
            b = Math.max(0.0, Math.min(1.0, b * (1.05 + 0.34 * lift)));
        }

        cell.finalColor = packColor(r, g, b);
    }

    private int debugCellColor(int q, int r) {
        long seed = ((long) q * 73856093L) ^ ((long) r * 19349663L) ^ 0x9E3779B97F4A7C15L;
        double h = hash01(seed);
        double s = 0.55 + 0.35 * hash01(seed + 17);
        double v = 0.48 + 0.34 * hash01(seed + 31);
        double[] rgb = hsvToRgb(h, s, v);
        return packColor(rgb[0], rgb[1], rgb[2]);
    }

    private boolean isBoundary(int idx, int x, int y) {
        int cell = pixelCellId[idx];
        if (x > 0 && pixelCellId[idx - 1] != cell) {
            return true;
        }
        if (x + 1 < width && pixelCellId[idx + 1] != cell) {
            return true;
        }
        if (y > 0 && pixelCellId[idx - width] != cell) {
            return true;
        }
        if (y + 1 < height && pixelCellId[idx + width] != cell) {
            return true;
        }
        if (x > 0 && y > 0 && pixelCellId[idx - width - 1] != cell) {
            return true;
        }
        if (x + 1 < width && y > 0 && pixelCellId[idx - width + 1] != cell) {
            return true;
        }
        if (x > 0 && y + 1 < height && pixelCellId[idx + width - 1] != cell) {
            return true;
        }
        return x + 1 < width && y + 1 < height && pixelCellId[idx + width + 1] != cell;
    }

    private double hexEdgeStrength(double px, double py, double cx, double cy, double radius) {
        double r = Math.max(1.0, radius);
        double dx = Math.abs(px - cx);
        double dy = Math.abs(py - cy);
        double d = Math.max(dy / r, (dx * 0.5773502691896257 + dy) / r);
        double edgeStart = 0.86;
        if (d <= edgeStart) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (d - edgeStart) / (1.0 - edgeStart)));
    }

    private AxialCoord pixelToAxial(double px, double py, double radius) {
        double q = ((SQRT3 / 3.0) * px - (1.0 / 3.0) * py) / radius;
        double r = ((2.0 / 3.0) * py) / radius;
        return cubeRound(q, r);
    }

    private AxialCoord cubeRound(double q, double r) {
        double x = q;
        double z = r;
        double y = -x - z;

        int rx = (int) Math.round(x);
        int ry = (int) Math.round(y);
        int rz = (int) Math.round(z);

        double xDiff = Math.abs(rx - x);
        double yDiff = Math.abs(ry - y);
        double zDiff = Math.abs(rz - z);

        if (xDiff > yDiff && xDiff > zDiff) {
            rx = -ry - rz;
        } else if (yDiff > zDiff) {
            ry = -rx - rz;
        } else {
            rz = -rx - ry;
        }
        return new AxialCoord(rx, rz);
    }

    private double[] axialToPixel(int q, int r, double radius) {
        double x = radius * (SQRT3 * q + (SQRT3 / 2.0) * r);
        double y = radius * (1.5 * r);
        return new double[]{x, y};
    }

    private double depthEdgeStrength(float[] depth, int x, int y) {
        int idx = y * width + x;
        float c = depth[idx];
        if (c >= 0.999f) {
            return 0.0;
        }
        float maxDiff = 0.0f;
        if (x > 0) {
            maxDiff = Math.max(maxDiff, Math.abs(c - depth[idx - 1]));
        }
        if (x + 1 < width) {
            maxDiff = Math.max(maxDiff, Math.abs(c - depth[idx + 1]));
        }
        if (y > 0) {
            maxDiff = Math.max(maxDiff, Math.abs(c - depth[idx - width]));
        }
        if (y + 1 < height) {
            maxDiff = Math.max(maxDiff, Math.abs(c - depth[idx + width]));
        }
        return Math.max(0.0, Math.min(1.0, maxDiff * 24.0));
    }

    private double quantize(double v) {
        int levels = Math.max(2, quantizationLevels);
        int q = (int) Math.round(Math.max(0.0, Math.min(1.0, v)) * (levels - 1));
        return q / (double) (levels - 1);
    }

    private int packColor(double r, double g, double b) {
        int ir = (int) (Math.max(0.0, Math.min(1.0, r)) * 255.0 + 0.5);
        int ig = (int) (Math.max(0.0, Math.min(1.0, g)) * 255.0 + 0.5);
        int ib = (int) (Math.max(0.0, Math.min(1.0, b)) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private int blend(int a, int b, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * t + 0.5);
        int rg = (int) (ag + (bg - ag) * t + 0.5);
        int rb = (int) (ab + (bb - ab) * t + 0.5);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }

    private int tintColor(int base, int tint, double strength) {
        return blend(base, tint, Math.max(0.0, Math.min(1.0, strength)));
    }

    private double hash01(long x) {
        x ^= (x >>> 33);
        x *= 0xff51afd7ed558ccdL;
        x ^= (x >>> 33);
        x *= 0xc4ceb9fe1a85ec53L;
        x ^= (x >>> 33);
        long bits = (x >>> 11) & ((1L << 53) - 1);
        return bits * 0x1.0p-53;
    }

    private double wrap01(double v) {
        double out = v - Math.floor(v);
        return out < 0.0 ? out + 1.0 : out;
    }

    private double[] rgbToHsv(double r, double g, double b) {
        double max = Math.max(r, Math.max(g, b));
        double min = Math.min(r, Math.min(g, b));
        double d = max - min;
        double h;
        if (d < 1e-9) {
            h = 0.0;
        } else if (max == r) {
            h = ((g - b) / d + (g < b ? 6.0 : 0.0)) / 6.0;
        } else if (max == g) {
            h = ((b - r) / d + 2.0) / 6.0;
        } else {
            h = ((r - g) / d + 4.0) / 6.0;
        }
        double s = max <= 1e-9 ? 0.0 : (d / max);
        return new double[]{h, s, max};
    }

    private double[] hsvToRgb(double h, double s, double v) {
        if (s <= 1e-9) {
            return new double[]{v, v, v};
        }
        double hh = wrap01(h) * 6.0;
        int i = (int) Math.floor(hh);
        double f = hh - i;
        double p = v * (1.0 - s);
        double q = v * (1.0 - s * f);
        double t = v * (1.0 - s * (1.0 - f));
        switch (i) {
            case 0:
                return new double[]{v, t, p};
            case 1:
                return new double[]{q, v, p};
            case 2:
                return new double[]{p, v, t};
            case 3:
                return new double[]{p, q, v};
            case 4:
                return new double[]{t, p, v};
            default:
                return new double[]{v, p, q};
        }
    }

    private static final class CellData {
        int row;
        int col;
        double centerX;
        double centerY;
        double sumR;
        double sumG;
        double sumB;
        double depthSum;
        int count;
        double radius;
        double phase;
        int finalColor;

        void reset() {
            sumR = 0.0;
            sumG = 0.0;
            sumB = 0.0;
            depthSum = 0.0;
            count = 0;
            finalColor = 0xFF000000;
        }

        void accumulate(int argb, float depth) {
            sumR += (argb >> 16) & 0xFF;
            sumG += (argb >> 8) & 0xFF;
            sumB += argb & 0xFF;
            depthSum += depth;
            count++;
        }
    }

    private static final class AxialCoord {
        final int q;
        final int r;

        AxialCoord(int q, int r) {
            this.q = q;
            this.r = r;
        }
    }
}
