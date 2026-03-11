package engine.render.post;

import engine.camera.Camera;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.raster.RasterRenderer;
import engine.scene.Scene;

import java.util.HashMap;
import java.util.Map;

/**
 * Tady držím temporal noise jako čistý 2D postproces nad G-bufferem.
 * Tvar vzniká jen z integer posuvu stabilního 2D zrna mezi snímky.
 * Objekt může kombinovat osu X i Y zároveň, ale bez deformace vzoru.
 */
public class TemporalNoiseRenderer implements Renderer {

    /**
     * Režimy zůstávají jen kvůli kompatibilitě volání.
     * Finální obraz se po zjednodušení řídí vždy stejným modelem.
     */
    public enum NoiseMode {
        OBJECT_MASK,
        FACE_FLOW,
        CAMERA_RELATIVE
    }

    public enum DebugView {
        FINAL,
        NEUTRAL_BASE,
        FLOW_FIELD,
        EDGE_MASK,
        PHASE_MAP,
        DEPTH_LAYER
    }

    private static final long GLOBAL_SEED = 0x4F1BBCDC9A11L;
    private static final long MASK_SEED = 0xC2B2AE3D27D4EB4FL;
    private static final long ANALYSIS_SEED = 0xA24BAED4963EE407L;
    private static final double EPS = 1e-9;
    private static final double DEFAULT_BASE_SPEED = 0.45;
    private static final double SPEED_VARIATION_BOOST = 1.20;
    private static final double REGION_SPEED_DIVERSITY = 0.26;
    private static final double FACE_PHASE_SPREAD = 10.0;
    private static final int DARK_GRAY = 28;
    private static final int BRIGHT_GRAY = 228;
    private static final int[] GRAIN_CELL_SIZE_PRESETS = {1, 2, 4};

    private static final class FaceRegionAccumulator {
        int objectId;
        double sumNx;
        double sumNy;
        double sumNz;
        double sumDepthNear;
        double sumFacing;
        double sumScreenX;
        double sumScreenY;
        double sumPlaneDistance;
        int count;
    }

    private static final class FaceRegionStats {
        int objectId;
        double avgNx;
        double avgNy;
        double avgNz;
        double avgDepthNear;
        double avgFacing;
        double avgScreenX;
        double avgScreenY;
        double avgPlaneDistance;
        int count;
    }

    private static final class SmoothRegionStats {
        boolean valid;
        double avgNx;
        double avgNy;
        double avgNz;
        double avgDepthNear;
        double avgFacing;
        double avgScreenX;
        double avgScreenY;
        double avgWx;
        double avgWy;
        double avgWz;
        int count;
        double weightSum;
    }

    private static final class MotionRegionParams {
        float rawFlowX;
        float rawFlowY;
        float flowX;
        float flowY;
        float speed;
        float phase;
        float depthMetric;
        float facing;
    }

    private final RasterRenderer baseRasterRenderer;

    private FrameBuffer gBufferFrame;
    private NoiseMode mode;
    private DebugView debugView;
    private boolean frustumCulling;
    private double temporalTickRate;
    private double depthNearContribution;
    private double grazingContribution;
    private double minSpeed;
    private double maxSpeed;
    private double edgeBlendStrength;
    private int grainCellSize;
    private int paletteLevels;

    private long analysisSignature;
    private long analysisBuildCount;
    private long analysisReuseCount;
    private boolean lastAnalysisCacheHit;

    private float[] rawFlowX;
    private float[] rawFlowY;
    private float[] flowX;
    private float[] flowY;
    private float[] edgeMask;
    private float[] phaseMap;
    private float[] speedMap;
    private float[] depthMetricMap;
    private float[] facingMap;

    public TemporalNoiseRenderer() {
        this.baseRasterRenderer = new RasterRenderer();
        this.gBufferFrame = null;
        this.mode = NoiseMode.FACE_FLOW;
        this.debugView = DebugView.FINAL;
        this.frustumCulling = true;
        this.temporalTickRate = 6.8;
        this.depthNearContribution = 2.22;
        this.grazingContribution = 1.87;
        this.minSpeed = 0.55;
        this.maxSpeed = 10.61;
        this.edgeBlendStrength = 0.12;
        this.grainCellSize = normalizeGrainCellSizePreset(2);
        this.paletteLevels = 5;
        this.analysisSignature = Long.MIN_VALUE;
        this.analysisBuildCount = 0L;
        this.analysisReuseCount = 0L;
        this.lastAnalysisCacheHit = false;

        // Tady pro temporal mód potřebuju jen G-buffer a vlastní finální syntézu.
        baseRasterRenderer.setParameter("unlitMode", true);
        baseRasterRenderer.setParameter("frustumCulling", true);
        baseRasterRenderer.setParameter("backfaceCulling", false);
    }

    /**
     * Zacvakne velikost zrna na podporovaný preset.
     * Zachovávám jen 1x1, 2x2 a 4x4, aby zrno zůstalo předvídatelné.
     */
    public static int normalizeGrainCellSizePreset(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value <= 3) {
            return 2;
        }
        return 4;
    }

    /**
     * Tady vrátím další preset velikosti zrna v cyklu 1x1 -> 2x2 -> 4x4 -> 1x1.
     */
    public static int nextGrainCellSizePreset(int current) {
        int normalized = normalizeGrainCellSizePreset(current);
        if (normalized == 1) {
            return 2;
        }
        if (normalized == 2) {
            return 4;
        }
        return 1;
    }

    /**
     * Tady vrátím uživatelský popisek presetu velikosti zrna.
     */
    public static String grainCellSizePresetLabel(int value) {
        int normalized = normalizeGrainCellSizePreset(value);
        return normalized + "x" + normalized;
    }

    /**
     * Tady vrátím všechny dostupné popisky presetů velikosti zrna pro UI.
     */
    public static String[] grainCellSizePresetLabels() {
        String[] labels = new String[GRAIN_CELL_SIZE_PRESETS.length];
        for (int i = 0; i < GRAIN_CELL_SIZE_PRESETS.length; i++) {
            labels[i] = grainCellSizePresetLabel(GRAIN_CELL_SIZE_PRESETS[i]);
        }
        return labels;
    }

    /**
     * Tady převedu textový popisek z UI zpět na preset velikosti zrna.
     */
    public static int grainCellSizePresetFromLabel(String label) {
        if (label == null) {
            return 2;
        }
        String trimmed = label.trim();
        for (int preset : GRAIN_CELL_SIZE_PRESETS) {
            if (grainCellSizePresetLabel(preset).equalsIgnoreCase(trimmed)) {
                return preset;
            }
        }
        return 2;
    }

    @Override
    public void init(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        baseRasterRenderer.init(w, h);
        gBufferFrame = new FrameBuffer(w, h, true);
        ensureFieldBuffers(w * h);
        analysisSignature = Long.MIN_VALUE;
    }

    @Override
    public void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        if (scene == null || camera == null || fb == null) {
            return;
        }
        ensureBuffers(fb.getWidth(), fb.getHeight());

        baseRasterRenderer.render(scene, camera, gBufferFrame, time);

        int width = fb.getWidth();
        int height = fb.getHeight();
        int pixelCount = width * height;
        int[] outColor = fb.getColorBuffer();
        float[] outDepth = fb.getDepthBuffer();
        int[] objectId = gBufferFrame.getObjectIdBuffer();
        int[] faceId = gBufferFrame.getFaceIdBuffer();
        float[] depth = gBufferFrame.getDepthBuffer();
        float[] normal = gBufferFrame.getNormalBuffer();
        float[] worldPos = gBufferFrame.getWorldPosBuffer();

        System.arraycopy(depth, 0, outDepth, 0, pixelCount);
        long signature = computeAnalysisSignature(camera, width, height, objectId, faceId, depth, normal, worldPos);
        if (signature != analysisSignature) {
            buildTemporalField(camera, width, height, objectId, faceId, depth, normal, worldPos);
            analysisSignature = signature;
            analysisBuildCount++;
            lastAnalysisCacheHit = false;
        } else {
            analysisReuseCount++;
            lastAnalysisCacheHit = true;
        }

        if (debugView != DebugView.FINAL) {
            for (int y = 0; y < height; y++) {
                int row = y * width;
                for (int x = 0; x < width; x++) {
                    int idx = row + x;
                    int gridX = pixelToCell(x);
                    int gridY = pixelToCell(y);
                    outColor[idx] = debugColorFor(idx, gridX, gridY);
                }
            }
            return;
        }

        int cellSize = Math.max(1, grainCellSize);
        for (int cellY = 0; cellY < height; cellY += cellSize) {
            for (int cellX = 0; cellX < width; cellX += cellSize) {
                int cellMaxY = Math.min(height, cellY + cellSize);
                int cellMaxX = Math.min(width, cellX + cellSize);
                renderCell(cellX, cellMaxX, cellY, cellMaxY, width, objectId, depth, outColor, time);
            }
        }
    }

    @Override
    public void resize(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        baseRasterRenderer.resize(w, h);
        gBufferFrame = new FrameBuffer(w, h, true);
        ensureFieldBuffers(w * h);
        analysisSignature = Long.MIN_VALUE;
    }

    @Override
    public void setParameter(String key, Object value) {
        if ("mode".equalsIgnoreCase(key)) {
            if (value instanceof NoiseMode noiseMode) {
                mode = noiseMode;
                return;
            }
            if (value instanceof String text) {
                mode = parseMode(text);
                return;
            }
        }
        if ("debugView".equalsIgnoreCase(key)) {
            if (value instanceof DebugView view) {
                debugView = view;
                return;
            }
            if (value instanceof String text) {
                debugView = parseDebugView(text);
                return;
            }
        }
        if ("temporalTickRate".equalsIgnoreCase(key) && value instanceof Number number) {
            temporalTickRate = Math.max(0.1, Math.min(20.0, number.doubleValue()));
            return;
        }
        if ("depthNearContribution".equalsIgnoreCase(key) && value instanceof Number number) {
            depthNearContribution = Math.max(0.0, Math.min(4.0, number.doubleValue()));
            return;
        }
        if ("grazingContribution".equalsIgnoreCase(key) && value instanceof Number number) {
            grazingContribution = Math.max(0.0, Math.min(4.0, number.doubleValue()));
            return;
        }
        if ("minSpeed".equalsIgnoreCase(key) && value instanceof Number number) {
            minSpeed = Math.max(0.1, Math.min(12.0, number.doubleValue()));
            return;
        }
        if ("maxSpeed".equalsIgnoreCase(key) && value instanceof Number number) {
            maxSpeed = Math.max(0.1, Math.min(12.0, number.doubleValue()));
            return;
        }
        if ("edgeBlendStrength".equalsIgnoreCase(key) && value instanceof Number number) {
            edgeBlendStrength = Math.max(0.0, Math.min(0.25, number.doubleValue()));
            return;
        }
        if ("grainCellSize".equalsIgnoreCase(key) && value instanceof Number number) {
            grainCellSize = normalizeGrainCellSizePreset((int) Math.round(number.doubleValue()));
            return;
        }
        if ("paletteLevels".equalsIgnoreCase(key) && value instanceof Number number) {
            paletteLevels = Math.max(2, Math.min(8, (int) Math.round(number.doubleValue())));
            return;
        }
        if ("frustumCulling".equalsIgnoreCase(key) && value instanceof Boolean enabled) {
            frustumCulling = enabled;
            baseRasterRenderer.setParameter("frustumCulling", enabled);
            return;
        }

        baseRasterRenderer.setParameter(key, value);
    }

    @Override
    public String getName() {
        return debugView == DebugView.FINAL
                ? "Temporal Noise"
                : "Temporal Noise [" + debugView + "]";
    }

    public NoiseMode getMode() {
        return mode;
    }

    /**
     * Tady vrátím počet přestaveb semistatické analýzy od vytvoření rendereru.
     */
    public long getAnalysisBuildCount() {
        return analysisBuildCount;
    }

    /**
     * Tady vrátím počet snímků, které znovu použily uloženou analýzu.
     */
    public long getAnalysisReuseCount() {
        return analysisReuseCount;
    }

    /**
     * Tady řeknu, jestli poslední vykreslení jen znovu použilo uloženou analýzu.
     */
    public boolean wasLastAnalysisCacheHit() {
        return lastAnalysisCacheHit;
    }

    /**
     * Tady vrátím kopii osového směru X pro testy a diagnostiku.
     */
    public float[] copyFlowXBuffer() {
        return flowX == null ? new float[0] : flowX.clone();
    }

    /**
     * Tady vrátím kopii osového směru Y pro testy a diagnostiku.
     */
    public float[] copyFlowYBuffer() {
        return flowY == null ? new float[0] : flowY.clone();
    }

    /**
     * Tady vrátím kopii rychlostí regionů v pixelech za sekundu.
     */
    public float[] copySpeedBuffer() {
        return speedMap == null ? new float[0] : speedMap.clone();
    }

    /**
     * Tady vrátím kopii fázových posunů regionů.
     */
    public float[] copyPhaseBuffer() {
        return phaseMap == null ? new float[0] : phaseMap.clone();
    }

    /**
     * Tady vrátím kopii masky hran.
     */
    public float[] copyEdgeMaskBuffer() {
        return edgeMask == null ? new float[0] : edgeMask.clone();
    }

    /**
     * Tady vrátím horní mez počtu náhodných vzorkovacích operací na pixel.
     */
    public int getFinalNoiseSamplesPerPixelUpperBound() {
        return 4;
    }

    /**
     * Tady vrátím celočíselný posun X, který renderer použije pro daný pixel a čas.
     */
    public int computeShiftXAt(int index, double time) {
        if (flowX == null || speedMap == null || phaseMap == null || index < 0 || index >= flowX.length) {
            return 0;
        }
        return unpackShiftX(computePackedShift(time, speedMap[index], phaseMap[index], rawFlowX[index], rawFlowY[index]));
    }

    /**
     * Tady vrátím celočíselný posun Y, který renderer použije pro daný pixel a čas.
     */
    public int computeShiftYAt(int index, double time) {
        if (flowY == null || speedMap == null || phaseMap == null || index < 0 || index >= flowY.length) {
            return 0;
        }
        return unpackShiftY(computePackedShift(time, speedMap[index], phaseMap[index], rawFlowX[index], rawFlowY[index]));
    }

    /**
     * Finální syntézou vždy vykreslím celou buňku zrna jednou hodnotou.
     * Tím zůstává velikost zrna přesně stejná i na hranách a u hrubších presetů nevznikají subpixely.
     */
    private void renderCell(int cellX,
                            int cellMaxX,
                            int cellY,
                            int cellMaxY,
                            int width,
                            int[] objectId,
                            float[] depth,
                            int[] outColor,
                            double time) {
        int representative = -1;
        int objectCount = 0;
        int cellPixelCount = (cellMaxX - cellX) * (cellMaxY - cellY);
        double bestScore = Double.POSITIVE_INFINITY;
        double cellCenterX = cellX + (cellMaxX - cellX) * 0.5;
        double cellCenterY = cellY + (cellMaxY - cellY) * 0.5;
        int gridX = pixelToCell(cellX);
        int gridY = pixelToCell(cellY);

        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                int idx = row + px;
                if (!isObjectPixel(objectId, depth, idx)) {
                    continue;
                }
                objectCount++;
                double dx = (px + 0.5) - cellCenterX;
                double dy = (py + 0.5) - cellCenterY;
                double score = edgeMask[idx] * 3.5 + dx * dx + dy * dy;
                if (score < bestScore) {
                    bestScore = score;
                    representative = idx;
                }
            }
        }

        double signal = sampleNoiseSignal(gridX, gridY);
        if (representative >= 0) {
            long packedShift = computePackedShift(
                    time,
                    speedMap[representative],
                    phaseMap[representative],
                    rawFlowX[representative],
                    rawFlowY[representative]
            );
            int shiftX = unpackShiftX(packedShift);
            int shiftY = unpackShiftY(packedShift);
            double objectSignal = sampleNoiseSignal(gridX + shiftX, gridY + shiftY);
            double objectCoverage = objectCount / (double) Math.max(1, cellPixelCount);
            if (objectCoverage < 1.0 || edgeMask[representative] > 0.55f) {
                double blend = clamp01(Math.max(edgeMask[representative] * edgeBlendStrength, 1.0 - objectCoverage));
                signal = lerp(objectSignal, signal, blend);
            } else {
                signal = objectSignal;
            }
        }

        int gray = quantizeSignalToGray(signal);
        int packedGray = packGrayByte(gray);
        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                outColor[row + px] = packedGray;
            }
        }
    }

    private void ensureBuffers(int width, int height) {
        if (gBufferFrame == null || gBufferFrame.getWidth() != width || gBufferFrame.getHeight() != height) {
            gBufferFrame = new FrameBuffer(width, height, true);
            baseRasterRenderer.resize(width, height);
            analysisSignature = Long.MIN_VALUE;
        }
        ensureFieldBuffers(width * height);
    }

    private void ensureFieldBuffers(int pixelCount) {
        if (rawFlowX != null && rawFlowX.length == pixelCount) {
            return;
        }
        rawFlowX = new float[pixelCount];
        rawFlowY = new float[pixelCount];
        flowX = new float[pixelCount];
        flowY = new float[pixelCount];
        edgeMask = new float[pixelCount];
        phaseMap = new float[pixelCount];
        speedMap = new float[pixelCount];
        depthMetricMap = new float[pixelCount];
        facingMap = new float[pixelCount];
    }

    private NoiseMode parseMode(String raw) {
        if (raw == null) {
            return mode;
        }
        String value = raw.trim().toUpperCase();
        if ("CAMERA_RELATIVE".equals(value) || "CAMERA".equals(value)) {
            return NoiseMode.CAMERA_RELATIVE;
        }
        if ("OBJECT_MASK".equals(value) || "OBJECT".equals(value)) {
            return NoiseMode.OBJECT_MASK;
        }
        return NoiseMode.FACE_FLOW;
    }

    private DebugView parseDebugView(String raw) {
        if (raw == null) {
            return debugView;
        }
        String value = raw.trim().toUpperCase();
        for (DebugView candidate : DebugView.values()) {
            if (candidate.name().equals(value)) {
                return candidate;
            }
        }
        return DebugView.FINAL;
    }

    private long computeAnalysisSignature(Camera camera,
                                          int width,
                                          int height,
                                          int[] objectId,
                                          int[] faceId,
                                          float[] depth,
                                          float[] normal,
                                          float[] worldPos) {
        long hash = ANALYSIS_SEED;
        hash = mixHash(hash, width);
        hash = mixHash(hash, height);
        hash = mixHash(hash, Double.doubleToLongBits(camera.getPosition().x));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getPosition().y));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getPosition().z));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getForward().x));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getForward().y));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getForward().z));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getRight().x));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getRight().y));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getRight().z));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getUp().x));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getUp().y));
        hash = mixHash(hash, Double.doubleToLongBits(camera.getUp().z));
        hash = mixHash(hash, Double.doubleToLongBits(depthNearContribution));
        hash = mixHash(hash, Double.doubleToLongBits(grazingContribution));
        hash = mixHash(hash, Double.doubleToLongBits(minSpeed));
        hash = mixHash(hash, Double.doubleToLongBits(maxSpeed));
        hash = mixHash(hash, Double.doubleToLongBits(edgeBlendStrength));
        hash = mixHash(hash, frustumCulling ? 1L : 0L);

        int pixelCount = width * height;
        int stride = Math.max(1, pixelCount / 2048);
        for (int idx = 0; idx < pixelCount; idx += stride) {
            hash = mixHash(hash, objectId[idx]);
            hash = mixHash(hash, faceId[idx]);
            hash = mixHash(hash, Float.floatToIntBits(depth[idx]));
            int base = idx * 3;
            hash = mixHash(hash, Float.floatToIntBits(normal[base]));
            hash = mixHash(hash, Float.floatToIntBits(normal[base + 1]));
            hash = mixHash(hash, Float.floatToIntBits(normal[base + 2]));
            hash = mixHash(hash, Float.floatToIntBits(worldPos[base]));
            hash = mixHash(hash, Float.floatToIntBits(worldPos[base + 1]));
            hash = mixHash(hash, Float.floatToIntBits(worldPos[base + 2]));
        }
        return hash;
    }

    private long mixHash(long hash, long value) {
        return mix64(hash ^ value ^ 0x9E3779B97F4A7C15L);
    }

    private void buildTemporalField(Camera camera,
                                    int width,
                                    int height,
                                    int[] objectId,
                                    int[] faceId,
                                    float[] depth,
                                    float[] normal,
                                    float[] worldPos) {
        computeRawField(camera, width, height, objectId, faceId, depth, normal, worldPos);
        smoothCellMotionField(width, height, objectId, faceId, depth, normal, worldPos);
        enforcePlanarRegionConsistency(camera, width, height, objectId, faceId, depth, normal, worldPos);
        normalizeUniformCells(width, height, objectId, faceId, depth, normal, worldPos);
        buildEdgeMask(width, height, objectId, depth, normal);
    }

    private void computeRawField(Camera camera,
                                 int width,
                                 int height,
                                 int[] objectId,
                                 int[] faceId,
                                 float[] depth,
                                 float[] normal,
                                 float[] worldPos) {
        Map<Long, FaceRegionStats> faceStats = buildFaceRegionStats(camera, width, objectId, faceId, depth, normal, worldPos);
        Map<Long, FaceRegionStats> planarStats = buildPlanarRegionStats(faceStats);
        Map<Long, MotionRegionParams> motionCache = new HashMap<>(Math.max(64, planarStats.size() * 2));

        double camFx = camera.getForward().x;
        double camFy = camera.getForward().y;
        double camFz = camera.getForward().z;
        double camRx = camera.getRight().x;
        double camRy = camera.getRight().y;
        double camRz = camera.getRight().z;
        double camUx = camera.getUp().x;
        double camUy = camera.getUp().y;
        double camUz = camera.getUp().z;
        double camPx = camera.getPosition().x;
        double camPy = camera.getPosition().y;
        double camPz = camera.getPosition().z;
        int cellSize = Math.max(1, grainCellSize);

        for (int cellY = 0; cellY < height; cellY += cellSize) {
            int cellMaxY = Math.min(height, cellY + cellSize);
            for (int cellX = 0; cellX < width; cellX += cellSize) {
                int cellMaxX = Math.min(width, cellX + cellSize);
                int representative = -1;
                double bestScore = Double.POSITIVE_INFINITY;
                double cellCenterX = cellX + (cellMaxX - cellX) * 0.5;
                double cellCenterY = cellY + (cellMaxY - cellY) * 0.5;

                for (int py = cellY; py < cellMaxY; py++) {
                    int row = py * width;
                    for (int px = cellX; px < cellMaxX; px++) {
                        int idx = row + px;
                        if (!isObjectPixel(objectId, depth, idx)) {
                            continue;
                        }
                        double dx = (px + 0.5) - cellCenterX;
                        double dy = (py + 0.5) - cellCenterY;
                        double score = dx * dx + dy * dy;
                        if (score < bestScore) {
                            bestScore = score;
                            representative = idx;
                        }
                    }
                }

                if (representative < 0) {
                    fillCellField(cellX, cellMaxX, cellY, cellMaxY, width, objectId, depth, null);
                    continue;
                }

                if (cellRequiresPixelFallback(cellX, cellMaxX, cellY, cellMaxY, width,
                        representative, objectId, faceId, depth, normal, worldPos)) {
                    fillMixedCellField(camera, cellX, cellMaxX, cellY, cellMaxY, width, height,
                            objectId, faceId, depth, normal, worldPos, faceStats, planarStats, motionCache,
                            camFx, camFy, camFz, camRx, camRy, camRz, camUx, camUy, camUz, camPx, camPy, camPz);
                    continue;
                }

                long key = faceKey(objectId[representative], faceId[representative]);
                FaceRegionStats face = faceStats.get(key);
                if (face == null) {
                    face = new FaceRegionStats();
                    face.objectId = objectId[representative];
                    face.avgNx = 0.0;
                    face.avgNy = 0.0;
                    face.avgNz = -1.0;
                    face.avgDepthNear = clamp01(1.0 - depth[representative]);
                    face.avgFacing = 1.0;
                    face.avgScreenX = cellCenterX;
                    face.avgScreenY = cellCenterY;
                    face.avgPlaneDistance = 0.0;
                }

                int base = representative * 3;
                double centerNx = normal[base];
                double centerNy = normal[base + 1];
                double centerNz = normal[base + 2];
                double invCenterN = invLength(centerNx, centerNy, centerNz);
                if (invCenterN < EPS) {
                    centerNx = -camFx;
                    centerNy = -camFy;
                    centerNz = -camFz;
                    invCenterN = 1.0;
                }
                centerNx *= invCenterN;
                centerNy *= invCenterN;
                centerNz *= invCenterN;

                boolean flatFace = centerNx * face.avgNx + centerNy * face.avgNy + centerNz * face.avgNz >= 0.9995;
                SmoothRegionStats smooth = flatFace
                        ? null
                        : sampleSmoothRegion(camera, cellX, cellY, cellMaxX, cellMaxY, width, height,
                        representative, objectId[representative], objectId, depth, normal, worldPos);
                FaceRegionStats planar = null;
                if (flatFace) {
                    planar = planarStats.get(planeKey(face.objectId, face.avgNx, face.avgNy, face.avgNz, face.avgPlaneDistance));
                }
                FaceRegionStats baseRegion = planar != null ? planar : face;

                double regionNx = smooth != null && smooth.valid ? smooth.avgNx : baseRegion.avgNx;
                double regionNy = smooth != null && smooth.valid ? smooth.avgNy : baseRegion.avgNy;
                double regionNz = smooth != null && smooth.valid ? smooth.avgNz : baseRegion.avgNz;
                double depthNear = smooth != null && smooth.valid ? smooth.avgDepthNear : baseRegion.avgDepthNear;
                double facing = smooth != null && smooth.valid ? smooth.avgFacing : baseRegion.avgFacing;
                double regionScreenX = smooth != null && smooth.valid ? smooth.avgScreenX : baseRegion.avgScreenX;
                double regionScreenY = smooth != null && smooth.valid ? smooth.avgScreenY : baseRegion.avgScreenY;
                boolean smoothRegion = smooth != null && smooth.valid;
                long regionKey = smoothRegion
                        ? smoothKey(objectId[representative], regionNx, regionNy, regionNz, depthNear)
                        : (planar != null
                        ? planeKey(baseRegion.objectId, baseRegion.avgNx, baseRegion.avgNy, baseRegion.avgNz, baseRegion.avgPlaneDistance)
                        : faceKey(objectId[representative], faceId[representative]));
                MotionRegionParams params = motionCache.get(regionKey);
                if (params == null) {
                    double regionWx = smoothRegion ? smooth.avgWx : worldPos[base];
                    double regionWy = smoothRegion ? smooth.avgWy : worldPos[base + 1];
                    double regionWz = smoothRegion ? smooth.avgWz : worldPos[base + 2];
                    double vx = camPx - regionWx;
                    double vy = camPy - regionWy;
                    double vz = camPz - regionWz;
                    double invV = invLength(vx, vy, vz);
                    if (invV < EPS) {
                        vx = -camFx;
                        vy = -camFy;
                        vz = -camFz;
                        invV = 1.0;
                    }
                    facing = clamp01(Math.abs((vx * invV) * regionNx + (vy * invV) * regionNy + (vz * invV) * regionNz));
                    params = buildMotionRegionParams(
                            objectId[representative],
                            regionNx,
                            regionNy,
                            regionNz,
                            depthNear,
                            facing,
                            regionScreenX,
                            regionScreenY,
                            width,
                            height,
                            camRx,
                            camRy,
                            camRz,
                            camUx,
                            camUy,
                            camUz,
                            smoothRegion
                    );
                    motionCache.put(regionKey, params);
                }

                fillCellField(cellX, cellMaxX, cellY, cellMaxY, width, objectId, depth, params);
            }
        }
    }

    /**
     * Společnou pohybovou buňku používám jen tehdy, když se v ní potkává
     * buď stejná plocha, nebo hladký či coplanární soused téhož objektu.
     */
    private boolean cellRequiresPixelFallback(int cellX,
                                              int cellMaxX,
                                              int cellY,
                                              int cellMaxY,
                                              int width,
                                              int representative,
                                              int[] objectId,
                                              int[] faceId,
                                              float[] depth,
                                              float[] normal,
                                              float[] worldPos) {
        int repObjectId = objectId[representative];
        int repFaceId = faceId[representative];
        int repBase = representative * 3;
        double repNx = normal[repBase];
        double repNy = normal[repBase + 1];
        double repNz = normal[repBase + 2];
        double invRepN = invLength(repNx, repNy, repNz);
        if (invRepN < EPS) {
            return true;
        }
        repNx *= invRepN;
        repNy *= invRepN;
        repNz *= invRepN;
        double repPlane = repNx * worldPos[repBase] + repNy * worldPos[repBase + 1] + repNz * worldPos[repBase + 2];
        double repDepth = depth[representative];

        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                int idx = row + px;
                if (!isObjectPixel(objectId, depth, idx)) {
                    continue;
                }
                if (objectId[idx] != repObjectId) {
                    return true;
                }
                if (faceId[idx] == repFaceId) {
                    continue;
                }
                int base = idx * 3;
                double nx = normal[base];
                double ny = normal[base + 1];
                double nz = normal[base + 2];
                double invN = invLength(nx, ny, nz);
                if (invN < EPS) {
                    return true;
                }
                nx *= invN;
                ny *= invN;
                nz *= invN;
                double dot = nx * repNx + ny * repNy + nz * repNz;
                double planeDistance = Math.abs(repNx * worldPos[base] + repNy * worldPos[base + 1] + repNz * worldPos[base + 2] - repPlane);
                boolean smoothCompatible = dot >= 0.97 && Math.abs(depth[idx] - repDepth) <= 0.015f;
                boolean planarCompatible = dot >= 0.9995 && planeDistance <= 0.025;
                if (!smoothCompatible && !planarCompatible) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Smíšená buňka nesmí jedné ploše vnutit pohybový model sousední plochy.
     * Na hranách proto padáme zpět na přesnější per-pixel výpočet.
     */
    private void fillMixedCellField(Camera camera,
                                    int cellX,
                                    int cellMaxX,
                                    int cellY,
                                    int cellMaxY,
                                    int width,
                                    int height,
                                    int[] objectId,
                                    int[] faceId,
                                    float[] depth,
                                    float[] normal,
                                    float[] worldPos,
                                    Map<Long, FaceRegionStats> faceStats,
                                    Map<Long, FaceRegionStats> planarStats,
                                    Map<Long, MotionRegionParams> motionCache,
                                    double camFx,
                                    double camFy,
                                    double camFz,
                                    double camRx,
                                    double camRy,
                                    double camRz,
                                    double camUx,
                                    double camUy,
                                    double camUz,
                                    double camPx,
                                    double camPy,
                                    double camPz) {
        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                int idx = row + px;
                MotionRegionParams params = resolvePixelMotionParams(px, py, idx, width, height, objectId, faceId,
                        depth, normal, worldPos, faceStats, planarStats, motionCache,
                        camFx, camFy, camFz, camRx, camRy, camRz, camUx, camUy, camUz, camPx, camPy, camPz);
                if (params == null) {
                    rawFlowX[idx] = 0.0f;
                    rawFlowY[idx] = 0.0f;
                    flowX[idx] = 0.0f;
                    flowY[idx] = 0.0f;
                    speedMap[idx] = 0.0f;
                    depthMetricMap[idx] = 0.0f;
                    facingMap[idx] = 0.0f;
                    phaseMap[idx] = 0.0f;
                } else {
                    rawFlowX[idx] = params.rawFlowX;
                    rawFlowY[idx] = params.rawFlowY;
                    flowX[idx] = params.flowX;
                    flowY[idx] = params.flowY;
                    speedMap[idx] = params.speed;
                    depthMetricMap[idx] = params.depthMetric;
                    facingMap[idx] = params.facing;
                    phaseMap[idx] = params.phase;
                }
            }
        }
    }

    private MotionRegionParams resolvePixelMotionParams(int x,
                                                        int y,
                                                        int idx,
                                                        int width,
                                                        int height,
                                                        int[] objectId,
                                                        int[] faceId,
                                                        float[] depth,
                                                        float[] normal,
                                                        float[] worldPos,
                                                        Map<Long, FaceRegionStats> faceStats,
                                                        Map<Long, FaceRegionStats> planarStats,
                                                        Map<Long, MotionRegionParams> motionCache,
                                                        double camFx,
                                                        double camFy,
                                                        double camFz,
                                                        double camRx,
                                                        double camRy,
                                                        double camRz,
                                                        double camUx,
                                                        double camUy,
                                                        double camUz,
                                                        double camPx,
                                                        double camPy,
                                                        double camPz) {
        if (!isObjectPixel(objectId, depth, idx)) {
            return null;
        }

        long key = faceKey(objectId[idx], faceId[idx]);
        FaceRegionStats face = faceStats.get(key);
        if (face == null) {
            face = new FaceRegionStats();
            face.objectId = objectId[idx];
            face.avgNx = 0.0;
            face.avgNy = 0.0;
            face.avgNz = -1.0;
            face.avgDepthNear = clamp01(1.0 - depth[idx]);
            face.avgFacing = 1.0;
            face.avgScreenX = x + 0.5;
            face.avgScreenY = y + 0.5;
            face.avgPlaneDistance = 0.0;
        }

        int base = idx * 3;
        FaceRegionStats planar = planarStats.get(planeKey(face.objectId, face.avgNx, face.avgNy, face.avgNz, face.avgPlaneDistance));
        FaceRegionStats baseRegion = planar != null ? planar : face;

        double regionNx = baseRegion.avgNx;
        double regionNy = baseRegion.avgNy;
        double regionNz = baseRegion.avgNz;
        double depthNear = baseRegion.avgDepthNear;
        double facing = baseRegion.avgFacing;
        double regionScreenX = baseRegion.avgScreenX;
        double regionScreenY = baseRegion.avgScreenY;
        boolean smoothRegion = false;
        long regionKey = planar != null
                ? planeKey(baseRegion.objectId, baseRegion.avgNx, baseRegion.avgNy, baseRegion.avgNz, baseRegion.avgPlaneDistance)
                : faceKey(objectId[idx], faceId[idx]);
        MotionRegionParams params = motionCache.get(regionKey);
        if (params != null) {
            return params;
        }

        double regionWx = worldPos[base];
        double regionWy = worldPos[base + 1];
        double regionWz = worldPos[base + 2];
        double vx = camPx - regionWx;
        double vy = camPy - regionWy;
        double vz = camPz - regionWz;
        double invV = invLength(vx, vy, vz);
        if (invV < EPS) {
            vx = -camFx;
            vy = -camFy;
            vz = -camFz;
            invV = 1.0;
        }
        facing = clamp01(Math.abs((vx * invV) * regionNx + (vy * invV) * regionNy + (vz * invV) * regionNz));
        params = buildMotionRegionParams(
                objectId[idx],
                regionNx,
                regionNy,
                regionNz,
                depthNear,
                facing,
                regionScreenX,
                regionScreenY,
                width,
                height,
                camRx,
                camRy,
                camRz,
                camUx,
                camUy,
                camUz,
                smoothRegion
        );
        motionCache.put(regionKey, params);
        return params;
    }

    /**
     * Tady převedu spojitý regionální směr na stabilní pohyb po pevné mřížce.
     * Tady výsledek sdílím pro celý pohybový region, aby se mi zrno nelámalo pixel po pixelu.
     */
    private MotionRegionParams buildMotionRegionParams(int objectId,
                                                       double regionNx,
                                                       double regionNy,
                                                       double regionNz,
                                                       double depthNear,
                                                       double facing,
                                                       double regionScreenX,
                                                       double regionScreenY,
                                                       int width,
                                                       int height,
                                                       double camRx,
                                                       double camRy,
                                                       double camRz,
                                                       double camUx,
                                                       double camUy,
                                                       double camUz,
                                                       boolean smoothRegion) {
        MotionRegionParams params = new MotionRegionParams();
        double clampedFacing = clamp01(facing);
        double grazing = 1.0 - clampedFacing;
        double normalScreenX = regionNx * camRx + regionNy * camRy + regionNz * camRz;
        double normalScreenY = -(regionNx * camUx + regionNy * camUy + regionNz * camUz);
        double dir2x = -normalScreenY;
        double dir2y = normalScreenX;
        double dirLen = Math.hypot(dir2x, dir2y);
        if (dirLen < EPS) {
            dir2x = 1.0;
            dir2y = 0.0;
            dirLen = 1.0;
        }
        dir2x /= dirLen;
        dir2y /= dirLen;

        double screenBiasX = normalizedScreenOffset(regionScreenX, width);
        double screenBiasY = normalizedScreenOffset(regionScreenY, height);
        double orientationContrast = clamp01(Math.abs(Math.abs(normalScreenX) - Math.abs(normalScreenY)));
        double perspectiveContrast = 0.5 * (Math.abs(screenBiasX) + Math.abs(screenBiasY));
        double axisAmbiguity = 1.0 - Math.abs(Math.abs(dir2x) - Math.abs(dir2y));
        double signBias = (smoothRegion ? 0.16 : 0.08) * axisAmbiguity;
        double desiredX = dir2x + screenBiasX * signBias;
        double desiredY = dir2y + screenBiasY * signBias;
        double dominant = Math.max(Math.abs(desiredX), Math.abs(desiredY));
        if (dominant < EPS) {
            desiredX = 1.0;
            desiredY = 0.0;
            dominant = 1.0;
        }

        double weightX = quantizeAxisWeight(Math.abs(desiredX) / dominant);
        double weightY = quantizeAxisWeight(Math.abs(desiredY) / dominant);
        if (weightX <= EPS && weightY <= EPS) {
            if (Math.abs(desiredX) >= Math.abs(desiredY)) {
                weightX = 1.0;
            } else {
                weightY = 1.0;
            }
        }

        double speedPixelsPerSecond = DEFAULT_BASE_SPEED
                + (depthNearContribution * depthNear * 0.90
                + grazingContribution * grazing * 1.02
                + orientationContrast * 0.46
                + perspectiveContrast * (smoothRegion ? 0.18 : 0.28)) * SPEED_VARIATION_BOOST;
        double regionSpeedBias = computeRegionSpeedBias(
                objectId,
                regionNx,
                regionNy,
                regionNz,
                depthNear,
                screenBiasX,
                screenBiasY,
                axisAmbiguity,
                orientationContrast
        );
        speedPixelsPerSecond += regionSpeedBias;
        speedPixelsPerSecond = quantizeSpeed(clamp(speedPixelsPerSecond, minSpeed, maxSpeed));

        params.rawFlowX = weightX <= EPS ? 0.0f : (float) (Math.signum(desiredX) * weightX);
        params.rawFlowY = weightY <= EPS ? 0.0f : (float) (Math.signum(desiredY) * weightY);
        params.flowX = weightX <= EPS ? 0.0f : (Math.signum(desiredX) >= 0.0 ? 1.0f : -1.0f);
        params.flowY = weightY <= EPS ? 0.0f : (Math.signum(desiredY) >= 0.0 ? 1.0f : -1.0f);
        params.speed = (float) speedPixelsPerSecond;
        params.depthMetric = (float) depthNear;
        params.facing = (float) clampedFacing;
        double phase = computeRegionPhase(
                objectId,
                Math.round(params.flowX),
                Math.round(params.flowY),
                regionNx,
                regionNy,
                regionNz
        );
        if (smoothRegion) {
            double screenPhase = screenBiasX * Math.signum(params.rawFlowX) * 2.1
                    + screenBiasY * Math.signum(params.rawFlowY) * 2.1;
            phase += screenPhase + depthNear * 1.15 + grazing * 0.65;
        }
        params.phase = (float) phase;
        return params;
    }

    /**
     * Když dvě různé plochy skončí na stejné ose pohybu,
     * přidá se jim lehce odlišná rychlost odvozená z regionu.
     * Tím držím bias deterministický a sdílený pro celý region, takže mi nerozbije velké sjednocené plochy.
     */
    private double computeRegionSpeedBias(int objectId,
                                          double regionNx,
                                          double regionNy,
                                          double regionNz,
                                          double depthNear,
                                          double screenBiasX,
                                          double screenBiasY,
                                          double axisAmbiguity,
                                          double orientationContrast) {
        int qx = quantizeSigned(regionNx, 8.0);
        int qy = quantizeSigned(regionNy, 8.0);
        int qz = quantizeSigned(regionNz, 8.0);
        int qd = (int) Math.round(clamp01(depthNear) * 12.0);
        int qsx = quantizeSigned(screenBiasX, 6.0);
        int qsy = quantizeSigned(screenBiasY, 6.0);
        long key = (((long) objectId) << 32)
                ^ (((long) qx & 0xFFL) << 24)
                ^ (((long) qy & 0xFFL) << 16)
                ^ (((long) qz & 0xFFL) << 8)
                ^ (((long) qd & 0xFFL) << 40)
                ^ (((long) qsx & 0xFFL) << 48)
                ^ (((long) qsy & 0xFFL) << 56);
        double variant = hash01(key) * 2.0 - 1.0;
        double diversity = REGION_SPEED_DIVERSITY
                * (0.55 + axisAmbiguity * 0.65 + (1.0 - orientationContrast) * 0.35);
        return variant * (maxSpeed - minSpeed) * diversity;
    }

    /**
     * Tady zapíšu hotové regionální parametry do celé buňky zrna.
     * Díky tomu se mi pohybový model uvnitř buňky nerozpadá na menší ostrůvky.
     */
    private void fillCellField(int cellX,
                               int cellMaxX,
                               int cellY,
                               int cellMaxY,
                               int width,
                               int[] objectId,
                               float[] depth,
                               MotionRegionParams params) {
        float rawX = params == null ? 0.0f : params.rawFlowX;
        float rawY = params == null ? 0.0f : params.rawFlowY;
        float flowValueX = params == null ? 0.0f : params.flowX;
        float flowValueY = params == null ? 0.0f : params.flowY;
        float speed = params == null ? 0.0f : params.speed;
        float depthMetric = params == null ? 0.0f : params.depthMetric;
        float facing = params == null ? 0.0f : params.facing;
        float phase = params == null ? 0.0f : params.phase;
        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                int idx = row + px;
                if (params == null || !isObjectPixel(objectId, depth, idx)) {
                    rawFlowX[idx] = 0.0f;
                    rawFlowY[idx] = 0.0f;
                    flowX[idx] = 0.0f;
                    flowY[idx] = 0.0f;
                    speedMap[idx] = 0.0f;
                    depthMetricMap[idx] = 0.0f;
                    facingMap[idx] = 0.0f;
                    phaseMap[idx] = 0.0f;
                } else {
                    rawFlowX[idx] = rawX;
                    rawFlowY[idx] = rawY;
                    flowX[idx] = flowValueX;
                    flowY[idx] = flowValueY;
                    speedMap[idx] = speed;
                    depthMetricMap[idx] = depthMetric;
                    facingMap[idx] = facing;
                    phaseMap[idx] = phase;
                }
            }
        }
    }

    /**
     * Tady vyhlazuju pohybové parametry mezi sousedními hladkými buňkami téhož objektu.
     * Tím mi mizí zbytkové malé plošky na kulatých tvarech, ale ostré hrany nechávám oddělené.
     */
    private void smoothCellMotionField(int width,
                                       int height,
                                       int[] objectId,
                                       int[] faceId,
                                       float[] depth,
                                       float[] normal,
                                       float[] worldPos) {
        int cellSize = Math.max(1, grainCellSize);
        int cellCols = (width + cellSize - 1) / cellSize;
        int cellRows = (height + cellSize - 1) / cellSize;
        int cellCount = cellCols * cellRows;
        float[] nextRawX = new float[cellCount];
        float[] nextRawY = new float[cellCount];
        float[] nextFlowX = new float[cellCount];
        float[] nextFlowY = new float[cellCount];
        float[] nextSpeed = new float[cellCount];
        float[] nextPhase = new float[cellCount];
        float[] nextDepth = new float[cellCount];
        float[] nextFacing = new float[cellCount];
        boolean[] validCell = new boolean[cellCount];

        for (int cellRow = 0; cellRow < cellRows; cellRow++) {
            int cellY = cellRow * cellSize;
            int cellMaxY = Math.min(height, cellY + cellSize);
            for (int cellCol = 0; cellCol < cellCols; cellCol++) {
                int cellX = cellCol * cellSize;
                int cellMaxX = Math.min(width, cellX + cellSize);
                int representative = findObjectRepresentativeInCell(cellX, cellMaxX, cellY, cellMaxY, width, objectId, depth);
                int cellIndex = cellRow * cellCols + cellCol;
                if (representative < 0) {
                    continue;
                }
                if (cellRequiresPixelFallback(cellX, cellMaxX, cellY, cellMaxY, width,
                        representative, objectId, faceId, depth, normal, worldPos)) {
                    nextRawX[cellIndex] = rawFlowX[representative];
                    nextRawY[cellIndex] = rawFlowY[representative];
                    nextFlowX[cellIndex] = flowX[representative];
                    nextFlowY[cellIndex] = flowY[representative];
                    nextSpeed[cellIndex] = speedMap[representative];
                    nextPhase[cellIndex] = phaseMap[representative];
                    nextDepth[cellIndex] = depthMetricMap[representative];
                    nextFacing[cellIndex] = facingMap[representative];
                    validCell[cellIndex] = true;
                    continue;
                }
                int repObjectId = objectId[representative];
                int repBase = representative * 3;
                double repNx = normal[repBase];
                double repNy = normal[repBase + 1];
                double repNz = normal[repBase + 2];
                double invRepN = invLength(repNx, repNy, repNz);
                if (invRepN < EPS) {
                    continue;
                }
                repNx *= invRepN;
                repNy *= invRepN;
                repNz *= invRepN;
                double repDepth = depth[representative];
                if (!cellLooksCurved(cellRow, cellCol, cellRows, cellCols, cellSize, width, height,
                        representative, repObjectId, repNx, repNy, repNz, repDepth, objectId, faceId, depth, normal, worldPos)) {
                    nextRawX[cellIndex] = rawFlowX[representative];
                    nextRawY[cellIndex] = rawFlowY[representative];
                    nextFlowX[cellIndex] = flowX[representative];
                    nextFlowY[cellIndex] = flowY[representative];
                    nextSpeed[cellIndex] = speedMap[representative];
                    nextPhase[cellIndex] = phaseMap[representative];
                    nextDepth[cellIndex] = depthMetricMap[representative];
                    nextFacing[cellIndex] = facingMap[representative];
                    validCell[cellIndex] = true;
                    continue;
                }
                double sumWeight = 0.0;
                double sumRawX = 0.0;
                double sumRawY = 0.0;
                double sumSpeed = 0.0;
                double sumDepth = 0.0;
                double sumFacing = 0.0;
                double sumPhase = 0.0;

                for (int oy = -1; oy <= 1; oy++) {
                    int neighborRow = cellRow + oy;
                    if (neighborRow < 0 || neighborRow >= cellRows) {
                        continue;
                    }
                    for (int ox = -1; ox <= 1; ox++) {
                        int neighborCol = cellCol + ox;
                        if (neighborCol < 0 || neighborCol >= cellCols) {
                            continue;
                        }
                        int neighborCellX = neighborCol * cellSize;
                        int neighborCellY = neighborRow * cellSize;
                        int neighborCellMaxX = Math.min(width, neighborCellX + cellSize);
                        int neighborCellMaxY = Math.min(height, neighborCellY + cellSize);
                        int neighborIndex = findObjectRepresentativeInCell(
                                neighborCellX, neighborCellMaxX, neighborCellY, neighborCellMaxY, width, objectId, depth);
                        if (neighborIndex < 0 || objectId[neighborIndex] != repObjectId) {
                            continue;
                        }
                        if (cellRequiresPixelFallback(neighborCellX, neighborCellMaxX, neighborCellY, neighborCellMaxY, width,
                                neighborIndex, objectId, faceId, depth, normal, worldPos)) {
                            continue;
                        }

                        int base = neighborIndex * 3;
                        double nx = normal[base];
                        double ny = normal[base + 1];
                        double nz = normal[base + 2];
                        double invN = invLength(nx, ny, nz);
                        if (invN < EPS) {
                            continue;
                        }
                        nx *= invN;
                        ny *= invN;
                        nz *= invN;
                        double normalDot = nx * repNx + ny * repNy + nz * repNz;
                        if (normalDot < 0.965 || Math.abs(depth[neighborIndex] - repDepth) > 0.020f) {
                            continue;
                        }

                        double spatialWeight = 1.0 / (1.0 + ox * ox + oy * oy);
                        double normalWeight = 0.25 + 0.75 * clamp01((normalDot - 0.965) / 0.035);
                        double depthWeight = 0.35 + 0.65 * (1.0 - clamp01(Math.abs(depth[neighborIndex] - repDepth) / 0.020));
                        double weight = spatialWeight * normalWeight * depthWeight;
                        sumWeight += weight;
                        sumRawX += rawFlowX[neighborIndex] * weight;
                        sumRawY += rawFlowY[neighborIndex] * weight;
                        sumSpeed += speedMap[neighborIndex] * weight;
                        sumDepth += depthMetricMap[neighborIndex] * weight;
                        sumFacing += facingMap[neighborIndex] * weight;
                        sumPhase += phaseMap[neighborIndex] * weight;
                    }
                }

                if (sumWeight <= EPS) {
                    nextRawX[cellIndex] = rawFlowX[representative];
                    nextRawY[cellIndex] = rawFlowY[representative];
                    nextFlowX[cellIndex] = flowX[representative];
                    nextFlowY[cellIndex] = flowY[representative];
                    nextSpeed[cellIndex] = speedMap[representative];
                    nextPhase[cellIndex] = phaseMap[representative];
                    nextDepth[cellIndex] = depthMetricMap[representative];
                    nextFacing[cellIndex] = facingMap[representative];
                    validCell[cellIndex] = true;
                    continue;
                }

                double avgRawX = sumRawX / sumWeight;
                double avgRawY = sumRawY / sumWeight;
                nextRawX[cellIndex] = (float) avgRawX;
                nextRawY[cellIndex] = (float) avgRawY;
                nextFlowX[cellIndex] = Math.abs(avgRawX) <= EPS ? 0.0f : (avgRawX >= 0.0 ? 1.0f : -1.0f);
                nextFlowY[cellIndex] = Math.abs(avgRawY) <= EPS ? 0.0f : (avgRawY >= 0.0 ? 1.0f : -1.0f);
                nextSpeed[cellIndex] = (float) (sumSpeed / sumWeight);
                nextPhase[cellIndex] = (float) (sumPhase / sumWeight);
                nextDepth[cellIndex] = (float) (sumDepth / sumWeight);
                nextFacing[cellIndex] = (float) (sumFacing / sumWeight);
                validCell[cellIndex] = true;
            }
        }

        for (int cellRow = 0; cellRow < cellRows; cellRow++) {
            int cellY = cellRow * cellSize;
            int cellMaxY = Math.min(height, cellY + cellSize);
            for (int cellCol = 0; cellCol < cellCols; cellCol++) {
                int cellIndex = cellRow * cellCols + cellCol;
                if (!validCell[cellIndex]) {
                    continue;
                }
                int cellX = cellCol * cellSize;
                int cellMaxX = Math.min(width, cellX + cellSize);
                for (int py = cellY; py < cellMaxY; py++) {
                    int row = py * width;
                    for (int px = cellX; px < cellMaxX; px++) {
                        int idx = row + px;
                        if (!isObjectPixel(objectId, depth, idx)) {
                            continue;
                        }
                        rawFlowX[idx] = nextRawX[cellIndex];
                        rawFlowY[idx] = nextRawY[cellIndex];
                        flowX[idx] = nextFlowX[cellIndex];
                        flowY[idx] = nextFlowY[cellIndex];
                        speedMap[idx] = nextSpeed[cellIndex];
                        phaseMap[idx] = nextPhase[cellIndex];
                        depthMetricMap[idx] = nextDepth[cellIndex];
                        facingMap[idx] = nextFacing[cellIndex];
                    }
                }
            }
        }
    }

    private int findObjectRepresentativeInCell(int cellX,
                                               int cellMaxX,
                                               int cellY,
                                               int cellMaxY,
                                               int width,
                                               int[] objectId,
                                               float[] depth) {
        double cellCenterX = cellX + (cellMaxX - cellX) * 0.5;
        double cellCenterY = cellY + (cellMaxY - cellY) * 0.5;
        int representative = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int py = cellY; py < cellMaxY; py++) {
            int row = py * width;
            for (int px = cellX; px < cellMaxX; px++) {
                int idx = row + px;
                if (!isObjectPixel(objectId, depth, idx)) {
                    continue;
                }
                double dx = (px + 0.5) - cellCenterX;
                double dy = (py + 0.5) - cellCenterY;
                double score = dx * dx + dy * dy;
                if (score < bestScore) {
                    bestScore = score;
                    representative = idx;
                }
            }
        }
        return representative;
    }

    /**
     * Tady chci, aby coplanární regiony měly i po všech pomocných hladkých úpravách stále jeden společný pohybový model.
     * Touto pojistkou vracím velké rovinné stěny zpět na jednotný směr, rychlost i fázi.
     */
    private void enforcePlanarRegionConsistency(Camera camera,
                                                int width,
                                                int height,
                                                int[] objectId,
                                                int[] faceId,
                                                float[] depth,
                                                float[] normal,
                                                float[] worldPos) {
        Map<Long, FaceRegionStats> faceStats = buildFaceRegionStats(camera, width, objectId, faceId, depth, normal, worldPos);
        Map<Long, FaceRegionStats> planarStats = buildPlanarRegionStats(faceStats);
        Map<Long, MotionRegionParams> motionCache = new HashMap<>(Math.max(32, planarStats.size() * 2));
        double camRx = camera.getRight().x;
        double camRy = camera.getRight().y;
        double camRz = camera.getRight().z;
        double camUx = camera.getUp().x;
        double camUy = camera.getUp().y;
        double camUz = camera.getUp().z;

        for (int idx = 0; idx < objectId.length; idx++) {
            if (!isObjectPixel(objectId, depth, idx)) {
                continue;
            }
            FaceRegionStats face = faceStats.get(faceKey(objectId[idx], faceId[idx]));
            if (face == null) {
                continue;
            }
            FaceRegionStats planar = planarStats.get(planeKey(face.objectId, face.avgNx, face.avgNy, face.avgNz, face.avgPlaneDistance));
            if (planar == null || planar.count < face.count * 1.5) {
                continue;
            }
            long regionKey = planeKey(planar.objectId, planar.avgNx, planar.avgNy, planar.avgNz, planar.avgPlaneDistance);
            MotionRegionParams params = motionCache.get(regionKey);
            if (params == null) {
                params = buildMotionRegionParams(
                        planar.objectId,
                        planar.avgNx,
                        planar.avgNy,
                        planar.avgNz,
                        planar.avgDepthNear,
                        planar.avgFacing,
                        planar.avgScreenX,
                        planar.avgScreenY,
                        width,
                        height,
                        camRx,
                        camRy,
                        camRz,
                        camUx,
                        camUy,
                        camUz,
                        false
                );
                motionCache.put(regionKey, params);
            }
            rawFlowX[idx] = params.rawFlowX;
            rawFlowY[idx] = params.rawFlowY;
            flowX[idx] = params.flowX;
            flowY[idx] = params.flowY;
            speedMap[idx] = params.speed;
            phaseMap[idx] = params.phase;
            depthMetricMap[idx] = params.depthMetric;
            facingMap[idx] = params.facing;
        }
    }

    /**
     * Tady po všech analytických úpravách znovu zarovnám čisté buňky zrna na jeden sdílený pohybový model.
     * Tím odstraním zbytky sub-cell nekonzistence, ale konfliktní buňky nechám nedotčené.
     */
    private void normalizeUniformCells(int width,
                                       int height,
                                       int[] objectId,
                                       int[] faceId,
                                       float[] depth,
                                       float[] normal,
                                       float[] worldPos) {
        int cellSize = Math.max(1, grainCellSize);
        for (int cellY = 0; cellY < height; cellY += cellSize) {
            int cellMaxY = Math.min(height, cellY + cellSize);
            for (int cellX = 0; cellX < width; cellX += cellSize) {
                int cellMaxX = Math.min(width, cellX + cellSize);
                int representative = findObjectRepresentativeInCell(cellX, cellMaxX, cellY, cellMaxY, width, objectId, depth);
                if (representative < 0) {
                    continue;
                }
                if (cellRequiresPixelFallback(cellX, cellMaxX, cellY, cellMaxY, width,
                        representative, objectId, faceId, depth, normal, worldPos)) {
                    continue;
                }
                float rawX = rawFlowX[representative];
                float rawY = rawFlowY[representative];
                float flowValueX = flowX[representative];
                float flowValueY = flowY[representative];
                float speed = speedMap[representative];
                float phase = phaseMap[representative];
                float depthMetric = depthMetricMap[representative];
                float facing = facingMap[representative];
                for (int py = cellY; py < cellMaxY; py++) {
                    int row = py * width;
                    for (int px = cellX; px < cellMaxX; px++) {
                        int idx = row + px;
                        if (!isObjectPixel(objectId, depth, idx)) {
                            continue;
                        }
                        rawFlowX[idx] = rawX;
                        rawFlowY[idx] = rawY;
                        flowX[idx] = flowValueX;
                        flowY[idx] = flowValueY;
                        speedMap[idx] = speed;
                        phaseMap[idx] = phase;
                        depthMetricMap[idx] = depthMetric;
                        facingMap[idx] = facing;
                    }
                }
            }
        }
    }

    /**
     * Tady chci vyhlazení použít jen na skutečně zakřivené hladké oblasti.
     * Rovné plochy a ostré hrany tím pádem držím striktně na jejich původním pohybovém modelu.
     */
    private boolean cellLooksCurved(int cellRow,
                                    int cellCol,
                                    int cellRows,
                                    int cellCols,
                                    int cellSize,
                                    int width,
                                    int height,
                                    int representative,
                                    int repObjectId,
                                    double repNx,
                                    double repNy,
                                    double repNz,
                                    double repDepth,
                                    int[] objectId,
                                    int[] faceId,
                                    float[] depth,
                                    float[] normal,
                                    float[] worldPos) {
        boolean foundCurvature = false;
        int repBase = representative * 3;
        double repPlane = repNx * worldPos[repBase] + repNy * worldPos[repBase + 1] + repNz * worldPos[repBase + 2];
        for (int oy = -1; oy <= 1; oy++) {
            int neighborRow = cellRow + oy;
            if (neighborRow < 0 || neighborRow >= cellRows) {
                continue;
            }
            for (int ox = -1; ox <= 1; ox++) {
                int neighborCol = cellCol + ox;
                if (neighborCol < 0 || neighborCol >= cellCols || (ox == 0 && oy == 0)) {
                    continue;
                }
                int neighborCellX = neighborCol * cellSize;
                int neighborCellY = neighborRow * cellSize;
                int neighborCellMaxX = Math.min(width, neighborCellX + cellSize);
                int neighborCellMaxY = Math.min(height, neighborCellY + cellSize);
                int neighborIndex = findObjectRepresentativeInCell(
                        neighborCellX, neighborCellMaxX, neighborCellY, neighborCellMaxY, width, objectId, depth);
                if (neighborIndex < 0 || objectId[neighborIndex] != repObjectId) {
                    continue;
                }
                if (cellRequiresPixelFallback(neighborCellX, neighborCellMaxX, neighborCellY, neighborCellMaxY, width,
                        neighborIndex, objectId, faceId, depth, normal, worldPos)) {
                    continue;
                }
                int base = neighborIndex * 3;
                double nx = normal[base];
                double ny = normal[base + 1];
                double nz = normal[base + 2];
                double invN = invLength(nx, ny, nz);
                if (invN < EPS) {
                    continue;
                }
                nx *= invN;
                ny *= invN;
                nz *= invN;
                double normalDot = nx * repNx + ny * repNy + nz * repNz;
                double planeDistance = Math.abs(repNx * worldPos[base] + repNy * worldPos[base + 1] + repNz * worldPos[base + 2] - repPlane);
                boolean smoothCompatible = normalDot >= 0.97 && Math.abs(depth[neighborIndex] - repDepth) <= 0.020f;
                boolean planarCompatible = normalDot >= 0.9995 && planeDistance <= 0.025;
                if (smoothCompatible && !planarCompatible) {
                    foundCurvature = true;
                    break;
                }
            }
            if (foundCurvature) {
                break;
            }
        }
        return foundCurvature;
    }

    private Map<Long, FaceRegionStats> buildFaceRegionStats(Camera camera,
                                                            int width,
                                                            int[] objectId,
                                                            int[] faceId,
                                                            float[] depth,
                                                            float[] normal,
                                                            float[] worldPos) {
        Map<Long, FaceRegionAccumulator> accumulators = new HashMap<>();
        double camPx = camera.getPosition().x;
        double camPy = camera.getPosition().y;
        double camPz = camera.getPosition().z;
        double camFx = camera.getForward().x;
        double camFy = camera.getForward().y;
        double camFz = camera.getForward().z;

        for (int idx = 0; idx < objectId.length; idx++) {
            if (!isObjectPixel(objectId, depth, idx)) {
                continue;
            }
            long key = faceKey(objectId[idx], faceId[idx]);
            FaceRegionAccumulator acc = accumulators.computeIfAbsent(key, ignored -> new FaceRegionAccumulator());
            acc.objectId = objectId[idx];
            int base = idx * 3;
            double nx = normal[base];
            double ny = normal[base + 1];
            double nz = normal[base + 2];
            double invN = invLength(nx, ny, nz);
            if (invN < EPS) {
                nx = -camFx;
                ny = -camFy;
                nz = -camFz;
                invN = 1.0;
            }
            nx *= invN;
            ny *= invN;
            nz *= invN;

            double wx = worldPos[base];
            double wy = worldPos[base + 1];
            double wz = worldPos[base + 2];
            double vx = camPx - wx;
            double vy = camPy - wy;
            double vz = camPz - wz;
            double invV = invLength(vx, vy, vz);
            if (invV < EPS) {
                vx = -camFx;
                vy = -camFy;
                vz = -camFz;
                invV = 1.0;
            }
            vx *= invV;
            vy *= invV;
            vz *= invV;

            acc.sumNx += nx;
            acc.sumNy += ny;
            acc.sumNz += nz;
            acc.sumDepthNear += clamp01(1.0 - depth[idx]);
            acc.sumFacing += clamp01(Math.abs(nx * vx + ny * vy + nz * vz));
            acc.sumScreenX += (idx % width) + 0.5;
            acc.sumScreenY += (idx / width) + 0.5;
            acc.sumPlaneDistance += nx * wx + ny * wy + nz * wz;
            acc.count++;
        }

        return resolveRegionStats(accumulators);
    }

    private Map<Long, FaceRegionStats> buildPlanarRegionStats(Map<Long, FaceRegionStats> faceStats) {
        Map<Long, FaceRegionAccumulator> accumulators = new HashMap<>(Math.max(4, faceStats.size() * 2));
        for (FaceRegionStats face : faceStats.values()) {
            long key = planeKey(face.objectId, face.avgNx, face.avgNy, face.avgNz, face.avgPlaneDistance);
            FaceRegionAccumulator acc = accumulators.computeIfAbsent(key, ignored -> new FaceRegionAccumulator());
            acc.objectId = face.objectId;
            acc.sumNx += face.avgNx * face.count;
            acc.sumNy += face.avgNy * face.count;
            acc.sumNz += face.avgNz * face.count;
            acc.sumDepthNear += face.avgDepthNear * face.count;
            acc.sumFacing += face.avgFacing * face.count;
            acc.sumScreenX += face.avgScreenX * face.count;
            acc.sumScreenY += face.avgScreenY * face.count;
            acc.sumPlaneDistance += face.avgPlaneDistance * face.count;
            acc.count += face.count;
        }
        return resolveRegionStats(accumulators);
    }

    private Map<Long, FaceRegionStats> resolveRegionStats(Map<Long, FaceRegionAccumulator> accumulators) {
        Map<Long, FaceRegionStats> resolved = new HashMap<>(Math.max(4, accumulators.size() * 2));
        for (Map.Entry<Long, FaceRegionAccumulator> entry : accumulators.entrySet()) {
            FaceRegionAccumulator acc = entry.getValue();
            FaceRegionStats stats = new FaceRegionStats();
            stats.objectId = acc.objectId;
            double invNormal = invLength(acc.sumNx, acc.sumNy, acc.sumNz);
            if (invNormal < EPS) {
                stats.avgNx = 0.0;
                stats.avgNy = 0.0;
                stats.avgNz = -1.0;
            } else {
                stats.avgNx = acc.sumNx * invNormal;
                stats.avgNy = acc.sumNy * invNormal;
                stats.avgNz = acc.sumNz * invNormal;
            }
            double invCount = acc.count <= 0 ? 0.0 : 1.0 / acc.count;
            stats.avgDepthNear = acc.sumDepthNear * invCount;
            stats.avgFacing = acc.sumFacing * invCount;
            stats.avgScreenX = acc.sumScreenX * invCount;
            stats.avgScreenY = acc.sumScreenY * invCount;
            stats.avgPlaneDistance = acc.sumPlaneDistance * invCount;
            stats.count = acc.count;
            resolved.put(entry.getKey(), stats);
        }
        return resolved;
    }

    private SmoothRegionStats sampleSmoothRegion(Camera camera,
                                                 int cellX,
                                                 int cellY,
                                                 int cellMaxX,
                                                 int cellMaxY,
                                                 int width,
                                                 int height,
                                                 int centerIndex,
                                                 int centerObjectId,
                                                 int[] objectId,
                                                 float[] depth,
                                                 float[] normal,
                                                 float[] worldPos) {
        int centerBase = centerIndex * 3;
        double centerNx = normal[centerBase];
        double centerNy = normal[centerBase + 1];
        double centerNz = normal[centerBase + 2];
        double invCenterN = invLength(centerNx, centerNy, centerNz);
        if (invCenterN < EPS) {
            return null;
        }
        centerNx *= invCenterN;
        centerNy *= invCenterN;
        centerNz *= invCenterN;

        double camPx = camera.getPosition().x;
        double camPy = camera.getPosition().y;
        double camPz = camera.getPosition().z;
        double centerDepth = depth[centerIndex];
        int centerX = (cellX + cellMaxX - 1) / 2;
        int centerY = (cellY + cellMaxY - 1) / 2;

        SmoothRegionStats stats = new SmoothRegionStats();
        for (int oy = -2; oy <= 2; oy++) {
            int sampleY = centerY + oy;
            if (sampleY < 0 || sampleY >= height) {
                continue;
            }
            for (int ox = -2; ox <= 2; ox++) {
                int sampleX = centerX + ox;
                if (sampleX < 0 || sampleX >= width) {
                    continue;
                }
                int neighborIndex = sampleY * width + sampleX;
                if (objectId[neighborIndex] != centerObjectId) {
                    continue;
                }
                double depthDelta = Math.abs(centerDepth - depth[neighborIndex]);
                if (depthDelta > 0.015f) {
                    continue;
                }

                int base = neighborIndex * 3;
                double neighborNx = normal[base];
                double neighborNy = normal[base + 1];
                double neighborNz = normal[base + 2];
                double invNeighborN = invLength(neighborNx, neighborNy, neighborNz);
                if (invNeighborN < EPS) {
                    continue;
                }
                neighborNx *= invNeighborN;
                neighborNy *= invNeighborN;
                neighborNz *= invNeighborN;
                double normalDot = neighborNx * centerNx + neighborNy * centerNy + neighborNz * centerNz;
                if (normalDot < 0.97) {
                    continue;
                }

                double wx = worldPos[base];
                double wy = worldPos[base + 1];
                double wz = worldPos[base + 2];
                double spatialWeight = 1.0 / (1.0 + 0.65 * (ox * ox + oy * oy));
                double normalWeight = clamp01((normalDot - 0.97) / 0.03);
                double depthWeight = 1.0 - clamp01(depthDelta / 0.015);
                double weight = spatialWeight * (0.25 + 0.75 * normalWeight) * (0.35 + 0.65 * depthWeight);
                if (weight <= EPS) {
                    continue;
                }
                stats.avgNx += neighborNx * weight;
                stats.avgNy += neighborNy * weight;
                stats.avgNz += neighborNz * weight;
                stats.avgDepthNear += clamp01(1.0 - depth[neighborIndex]) * weight;
                stats.avgScreenX += (sampleX + 0.5) * weight;
                stats.avgScreenY += (sampleY + 0.5) * weight;
                stats.avgWx += wx * weight;
                stats.avgWy += wy * weight;
                stats.avgWz += wz * weight;
                stats.count++;
                stats.weightSum += weight;
            }
        }

        if (stats.count < 6 || stats.weightSum <= EPS) {
            return null;
        }

        double invNormal = invLength(stats.avgNx, stats.avgNy, stats.avgNz);
        if (invNormal < EPS) {
            return null;
        }
        stats.avgNx *= invNormal;
        stats.avgNy *= invNormal;
        stats.avgNz *= invNormal;
        double invCount = 1.0 / stats.weightSum;
        stats.avgDepthNear *= invCount;
        stats.avgScreenX *= invCount;
        stats.avgScreenY *= invCount;
        stats.avgWx *= invCount;
        stats.avgWy *= invCount;
        stats.avgWz *= invCount;
        double vx = camPx - stats.avgWx;
        double vy = camPy - stats.avgWy;
        double vz = camPz - stats.avgWz;
        double invV = invLength(vx, vy, vz);
        if (invV < EPS) {
            stats.avgFacing = 1.0;
        } else {
            stats.avgFacing = clamp01(Math.abs((vx * invV) * stats.avgNx + (vy * invV) * stats.avgNy + (vz * invV) * stats.avgNz));
        }
        stats.valid = true;
        return stats;
    }

    /**
     * Fázi držím regionálně, ne trianglově.
     * Tím proto držím stejné časování i na coplanární ploše složené z více trojúhelníků.
     */
    private double computeRegionPhase(int objectId,
                                      int axisX,
                                      int axisY,
                                      double nx,
                                      double ny,
                                      double nz) {
        int qx = quantizeSigned(nx, 6.0);
        int qy = quantizeSigned(ny, 6.0);
        int qz = quantizeSigned(nz, 6.0);
        int qax = axisX + 1;
        int qay = axisY + 1;
        long key = (((long) objectId) << 32)
                ^ (((long) qx & 0xFFL) << 24)
                ^ (((long) qy & 0xFFL) << 16)
                ^ (((long) qz & 0xFFL) << 8)
                ^ ((long) qax << 40)
                ^ ((long) qay << 44);
        return hash01(key) * (FACE_PHASE_SPREAD + 1.5);
    }

    private int quantizeSigned(double value, double scale) {
        return (int) Math.round(clamp(value, -1.0, 1.0) * scale);
    }

    private long faceKey(int objectId, int faceId) {
        return (((long) objectId) << 32) ^ (faceId & 0xFFFFFFFFL);
    }

    private long planeKey(int objectId, double nx, double ny, double nz, double planeDistance) {
        int qx = quantizeSigned(nx, 10.0);
        int qy = quantizeSigned(ny, 10.0);
        int qz = quantizeSigned(nz, 10.0);
        int qd = (int) Math.round(planeDistance * 24.0);
        return (((long) objectId) << 32)
                ^ (((long) qx & 0xFFL) << 24)
                ^ (((long) qy & 0xFFL) << 16)
                ^ (((long) qz & 0xFFL) << 8)
                ^ (qd & 0xFFFFFFFFL);
    }

    private long smoothKey(int objectId, double nx, double ny, double nz, double depthNear) {
        int qx = quantizeSigned(nx, 8.0);
        int qy = quantizeSigned(ny, 8.0);
        int qz = quantizeSigned(nz, 8.0);
        int qd = (int) Math.round(clamp01(depthNear) * 10.0);
        return (((long) objectId) << 32)
                ^ (((long) qx & 0xFFL) << 24)
                ^ (((long) qy & 0xFFL) << 16)
                ^ (((long) qz & 0xFFL) << 8)
                ^ (qd & 0xFFL);
    }

    private void buildEdgeMask(int width,
                               int height,
                               int[] objectId,
                               float[] depth,
                               float[] normal) {
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                boolean objectPixel = isObjectPixel(objectId, depth, idx);
                double maxEdge = 0.0;

                if (x > 0) {
                    maxEdge = Math.max(maxEdge, neighborEdge(idx, idx - 1, objectPixel, objectId, depth, normal));
                }
                if (x + 1 < width) {
                    maxEdge = Math.max(maxEdge, neighborEdge(idx, idx + 1, objectPixel, objectId, depth, normal));
                }
                if (y > 0) {
                    maxEdge = Math.max(maxEdge, neighborEdge(idx, idx - width, objectPixel, objectId, depth, normal));
                }
                if (y + 1 < height) {
                    maxEdge = Math.max(maxEdge, neighborEdge(idx, idx + width, objectPixel, objectId, depth, normal));
                }
                edgeMask[idx] = (float) clamp01(maxEdge);
            }
        }
    }

    private double neighborEdge(int idx,
                                int neighborIdx,
                                boolean centerObject,
                                int[] objectId,
                                float[] depth,
                                float[] normal) {
        boolean neighborObject = isObjectPixel(objectId, depth, neighborIdx);
        double boundary = centerObject != neighborObject ? 1.0 : 0.0;
        double depthTerm = clamp01(Math.abs(depth[idx] - depth[neighborIdx]) * 28.0);
        double flowDx = rawFlowX[idx] - rawFlowX[neighborIdx];
        double flowDy = rawFlowY[idx] - rawFlowY[neighborIdx];
        double flowTerm = clamp01(Math.sqrt(flowDx * flowDx + flowDy * flowDy) * 0.18);
        double normalTerm = 0.0;
        if (centerObject && neighborObject) {
            int baseA = idx * 3;
            int baseB = neighborIdx * 3;
            double dot = normal[baseA] * normal[baseB]
                    + normal[baseA + 1] * normal[baseB + 1]
                    + normal[baseA + 2] * normal[baseB + 2];
            normalTerm = clamp01(1.0 - clamp11(dot));
            if (dot >= 0.97 && Math.abs(depth[idx] - depth[neighborIdx]) <= 0.015f) {
                flowTerm *= 0.18;
            }
        }
        return clamp01(Math.max(boundary, normalTerm * 0.78 + depthTerm * 0.62 + flowTerm));
    }

    private boolean isObjectPixel(int[] objectId, float[] depth, int idx) {
        return objectId[idx] >= 0 && depth[idx] < 1.0f;
    }

    private int debugColorFor(int idx, int gridX, int gridY) {
        return switch (debugView) {
            case NEUTRAL_BASE -> packGray(sampleNoiseSignal(gridX, gridY));
            case FLOW_FIELD -> packFlowDebug(flowX[idx], flowY[idx], 1.0f, edgeMask[idx]);
            case EDGE_MASK -> packGray(edgeMask[idx]);
            case PHASE_MAP -> packGray(fract(phaseMap[idx] / Math.max(EPS, FACE_PHASE_SPREAD + 1.5)));
            case DEPTH_LAYER -> packGray(depthMetricMap[idx]);
            case FINAL -> packGray(sampleNoiseSignal(gridX, gridY));
        };
    }

    /**
     * Tady čtu stabilní náhodné pole po celé buňce.
     * Posun v čase mi vzniká jen celočíselným posunem indexu, ne deformací nebo překreslením zrna.
     */
    private double sampleNoiseSignal(int sampleX, int sampleY) {
        double a = random01(sampleX, sampleY, 0L, GLOBAL_SEED);
        double b = random01(sampleX + 17, sampleY - 11, 0L, MASK_SEED);
        return 0.72 * a + 0.28 * b;
    }

    private int computeShift(double time, float speedPixelsPerSecond, float phaseOffsetRegion) {
        return floorInt(time * temporalTickRate * speedPixelsPerSecond + phaseOffsetRegion);
    }

    private int pixelToCell(int pixelCoordinate) {
        return Math.floorDiv(pixelCoordinate, Math.max(1, grainCellSize));
    }

    /**
     * Tady každé ose dávám vlastní diskrétní intenzitu pohybu.
     * Tím mi zrno zůstává pevné, ale region může běžet po X i Y zároveň.
     */
    private double quantizeAxisWeight(double value) {
        double clamped = clamp01(value);
        if (clamped < 0.10) {
            return 0.0;
        }
        return Math.round(clamped * 8.0) / 8.0;
    }

    private double quantizeSpeed(double speed) {
        double clamped = clamp(speed, minSpeed, maxSpeed);
        double span = Math.max(EPS, maxSpeed - minSpeed);
        double normalized = (clamped - minSpeed) / span;
        double quantized = Math.round(normalized * 24.0) / 24.0;
        return minSpeed + quantized * span;
    }

    private double normalizedScreenOffset(double screenCoordinate, int size) {
        if (size <= 1) {
            return 0.0;
        }
        double normalized = (screenCoordinate / size) * 2.0 - 1.0;
        return clamp(normalized, -1.0, 1.0);
    }

    /**
     * Tady obě osy používají společný digitální krok.
     * Kombinovaný pohyb mi tak nevypadá jako dvě nezávislé sekvence nad sebou.
     */
    private long computePackedShift(double time,
                                    float speedPixelsPerSecond,
                                    float phaseOffsetRegion,
                                    float axisComponentX,
                                    float axisComponentY) {
        double weightX = Math.abs(axisComponentX);
        double weightY = Math.abs(axisComponentY);
        if (weightX <= EPS && weightY <= EPS) {
            return 0L;
        }
        int stepCount = computeShift(time, speedPixelsPerSecond, phaseOffsetRegion);
        double dominant = Math.max(weightX, weightY);
        if (dominant < EPS) {
            dominant = 1.0;
        }
        int shiftX = axisComponentX == 0.0f
                ? 0
                : (axisComponentX >= 0.0f ? 1 : -1) * Math.max(0, (int) Math.round(stepCount * (weightX / dominant)));
        int shiftY = axisComponentY == 0.0f
                ? 0
                : (axisComponentY >= 0.0f ? 1 : -1) * Math.max(0, (int) Math.round(stepCount * (weightY / dominant)));
        return (((long) shiftX) << 32) ^ (shiftY & 0xFFFFFFFFL);
    }

    private int unpackShiftX(long packedShift) {
        return (int) (packedShift >> 32);
    }

    private int unpackShiftY(long packedShift) {
        return (int) packedShift;
    }

    /**
     * Tady držím vysoký kontrast, ale nenechám obraz spadnout jen do čisté binární mapy.
     * Pět úrovní mi dává výrazný šum a zároveň lépe ukazuje posun regionů.
     */
    private int quantizeSignalToGray(double signal) {
        double contrasted = clamp01(0.5 + (signal - 0.5) * 1.82);
        int levels = Math.max(2, Math.min(8, paletteLevels));
        double quantized = Math.round(contrasted * (levels - 1)) / (double) (levels - 1);
        return (int) Math.round(lerp(DARK_GRAY, BRIGHT_GRAY, quantized));
    }

    private double hash01(long key) {
        long mixed = mix64(key ^ ANALYSIS_SEED);
        return ((mixed >>> 11) & 0xFFFFFFL) / (double) 0xFFFFFFL;
    }

    private double random01(int x, int y, long tick, long seed) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) y * 0xC2B2AE3D27D4EB4FL;
        h ^= tick * 0x165667B19E3779F9L;
        h = mix64(h);
        return ((h >>> 11) & 0xFFFFFFL) / (double) 0xFFFFFFL;
    }

    private long mix64(long value) {
        long h = value;
        h ^= (h >>> 33);
        h *= 0xFF51AFD7ED558CCDL;
        h ^= (h >>> 33);
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= (h >>> 33);
        return h;
    }

    private int packGray(double value) {
        int g = (int) (clamp01(value) * 255.0 + 0.5);
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    private int packGrayByte(int gray) {
        int g = Math.max(0, Math.min(255, gray));
        return 0xFF000000 | (g << 16) | (g << 8) | g;
    }

    private int packFlowDebug(double fx, double fy, double coherence, double edge) {
        double angle = Math.atan2(fy, fx);
        double hue = fract(angle / (Math.PI * 2.0) + 0.5);
        double saturation = 0.40 + 0.45 * (1.0 - clamp01(edge));
        double value = 0.35 + 0.55 * clamp01(coherence);
        return hsvToRgb(hue, saturation, value);
    }

    private int hsvToRgb(double h, double s, double v) {
        double hue = fract(h) * 6.0;
        double sat = clamp01(s);
        double val = clamp01(v);
        int sector = (int) Math.floor(hue);
        double f = hue - sector;
        double p = val * (1.0 - sat);
        double q = val * (1.0 - sat * f);
        double t = val * (1.0 - sat * (1.0 - f));
        double r;
        double g;
        double b;
        switch (sector % 6) {
            case 0 -> {
                r = val;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = val;
                b = p;
            }
            case 2 -> {
                r = p;
                g = val;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = val;
            }
            case 4 -> {
                r = t;
                g = p;
                b = val;
            }
            default -> {
                r = val;
                g = p;
                b = q;
            }
        }
        int ir = (int) (clamp01(r) * 255.0 + 0.5);
        int ig = (int) (clamp01(g) * 255.0 + 0.5);
        int ib = (int) (clamp01(b) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private int floorInt(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    private double invLength(double x, double y, double z) {
        double lenSq = x * x + y * y + z * z;
        return lenSq <= EPS ? 0.0 : 1.0 / Math.sqrt(lenSq);
    }

    private double clamp11(double value) {
        if (value < -1.0) {
            return -1.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
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

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double lerp(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    private double fract(double value) {
        return value - Math.floor(value);
    }
}
