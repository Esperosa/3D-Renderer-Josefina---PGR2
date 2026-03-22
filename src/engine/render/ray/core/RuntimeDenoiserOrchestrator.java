package engine.render.ray.core;

import engine.render.ray.bvh.*;
public final class RuntimeDenoiserOrchestrator {

    private static final double FRAME_BUDGET_720_MS = 1000.0;
    private static final double FRAME_BUDGET_1080_MS = 2200.0;
    private static final double FRAME_BUDGET_1440_MS = 3200.0;
    private static final double FRAME_BUDGET_2160_MS = 6500.0;

    private static final int PRESET_QUALITY_TILE = 256;
    private static final int PRESET_QUALITY_OVERLAP = 24;
    private static final int PRESET_BALANCED_TILE = 192;
    private static final int PRESET_BALANCED_OVERLAP = 20;
    private static final int PRESET_SPEED_TILE = 160;
    private static final int PRESET_SPEED_OVERLAP = 16;

    private static final double FULL_FRAME_CONF_MAX_INVALID_GUIDE_RATIO = 0.42;
    private static final double FULL_FRAME_CONF_MIN_ENERGY_RATIO = 0.35;
    private static final double FULL_FRAME_CONF_MAX_ENERGY_RATIO = 1.30;
    private static final double FULL_FRAME_CONF_MIN_EFFECTIVE_SPP = 3.0;
    private static final double GUIDE_INVALIDITY_LOW_CONF_BASE_512 = 0.50;
    private static final double GUIDE_INVALIDITY_LOW_CONF_BASE_720 = 0.54;
    private static final double GUIDE_INVALIDITY_LOW_CONF_BASE_1080 = 0.56;
    private static final double GUIDE_INVALIDITY_LOW_CONF_BASE_1440 = 0.58;
    private static final double GUIDE_INVALIDITY_LOW_CONF_BASE_2160 = 0.60;

    private RuntimeDenoiserOrchestrator() {
    }

    public enum ResolutionBucket {
        CLASS_512,
        P720,
        P1080,
        P1440,
        P2160
    }

    public enum RuntimeMode {
        FULL_FRAME,
        TILED
    }

    public enum TilePreset {
        FULL(0, 0),
        QUALITY(PRESET_QUALITY_TILE, PRESET_QUALITY_OVERLAP),
        BALANCED(PRESET_BALANCED_TILE, PRESET_BALANCED_OVERLAP),
        SPEED(PRESET_SPEED_TILE, PRESET_SPEED_OVERLAP);

        private final int tileSize;
        private final int overlap;

        TilePreset(int tileSize, int overlap) {
            this.tileSize = tileSize;
            this.overlap = overlap;
        }

        public int tileSize() {
            return tileSize;
        }

        public int overlap() {
            return overlap;
        }
    }

    public static final class Decision {
        private final ResolutionBucket bucket;
        private final RuntimeMode mode;
        private final TilePreset preset;
        private final int tileSize;
        private final int overlap;
        private final boolean tiledMandatory;
        private final String reason;

        private Decision(ResolutionBucket bucket,
                         RuntimeMode mode,
                         TilePreset preset,
                         int tileSize,
                         int overlap,
                         boolean tiledMandatory,
                         String reason) {
            this.bucket = bucket;
            this.mode = mode;
            this.preset = preset;
            this.tileSize = tileSize;
            this.overlap = overlap;
            this.tiledMandatory = tiledMandatory;
            this.reason = reason == null ? "" : reason;
        }

        public ResolutionBucket bucket() {
            return bucket;
        }

        public RuntimeMode mode() {
            return mode;
        }

        public TilePreset preset() {
            return preset;
        }

        public int tileSize() {
            return tileSize;
        }

        public int overlap() {
            return overlap;
        }

        public boolean tiledMandatory() {
            return tiledMandatory;
        }

        public String reason() {
            return reason;
        }
    }

    public static final class TelemetrySnapshot {
        private final long invocationCount;
        private final long skipCount;
        private final long fallbackCount;
        private final long lowConfidenceCount;
        private final double lastLatencyMs;
        private final double lastEnergyRatio;
        private final double lastInvalidGuideRatio;
        private final double lastEffectiveSpp;
        private final String lastMode;
        private final String lastTilePreset;
        private final String lastReason;

        private TelemetrySnapshot(long invocationCount,
                                  long skipCount,
                                  long fallbackCount,
                                  long lowConfidenceCount,
                                  double lastLatencyMs,
                                  double lastEnergyRatio,
                                  double lastInvalidGuideRatio,
                                  double lastEffectiveSpp,
                                  String lastMode,
                                  String lastTilePreset,
                                  String lastReason) {
            this.invocationCount = invocationCount;
            this.skipCount = skipCount;
            this.fallbackCount = fallbackCount;
            this.lowConfidenceCount = lowConfidenceCount;
            this.lastLatencyMs = lastLatencyMs;
            this.lastEnergyRatio = lastEnergyRatio;
            this.lastInvalidGuideRatio = lastInvalidGuideRatio;
            this.lastEffectiveSpp = lastEffectiveSpp;
            this.lastMode = lastMode;
            this.lastTilePreset = lastTilePreset;
            this.lastReason = lastReason;
        }

        public long invocationCount() {
            return invocationCount;
        }

        public long skipCount() {
            return skipCount;
        }

        public long fallbackCount() {
            return fallbackCount;
        }

        public long lowConfidenceCount() {
            return lowConfidenceCount;
        }

        public double lastLatencyMs() {
            return lastLatencyMs;
        }

        public double lastEnergyRatio() {
            return lastEnergyRatio;
        }

        public double lastInvalidGuideRatio() {
            return lastInvalidGuideRatio;
        }

        public double lastEffectiveSpp() {
            return lastEffectiveSpp;
        }

        public String lastMode() {
            return lastMode;
        }

        public String lastTilePreset() {
            return lastTilePreset;
        }

        public String lastReason() {
            return lastReason;
        }
    }

    public static final class Telemetry {
        private long invocationCount;
        private long skipCount;
        private long fallbackCount;
        private long lowConfidenceCount;
        private double smoothedLatencyMs;
        private double lastEnergyRatio = 1.0;
        private double lastInvalidGuideRatio;
        private double lastEffectiveSpp;
        private String lastMode = RuntimeMode.FULL_FRAME.name();
        private String lastTilePreset = TilePreset.FULL.name();
        private String lastReason = "";

        public double smoothedLatencyMs() {
            return smoothedLatencyMs;
        }

        public void onDecision(Decision decision) {
            if (decision == null) {
                return;
            }
            this.lastMode = decision.mode().name();
            this.lastTilePreset = decision.preset().name();
            this.lastReason = safeReason(decision.reason());
            this.invocationCount++;
        }

        public void onSkip(String reason,
                           double invalidGuideRatio,
                           double effectiveSpp) {
            this.skipCount++;
            this.lastReason = safeReason(reason);
            this.lastInvalidGuideRatio = invalidGuideRatio;
            this.lastEffectiveSpp = effectiveSpp;
        }

        public void onFallback(String reason,
                               double energyRatio,
                               double invalidGuideRatio,
                               double effectiveSpp) {
            this.fallbackCount++;
            this.lastReason = safeReason(reason);
            this.lastEnergyRatio = energyRatio;
            this.lastInvalidGuideRatio = invalidGuideRatio;
            this.lastEffectiveSpp = effectiveSpp;
        }

        public void onLowConfidence(String reason,
                                    double invalidGuideRatio,
                                    double effectiveSpp) {
            this.lowConfidenceCount++;
            this.lastReason = safeReason(reason);
            this.lastInvalidGuideRatio = invalidGuideRatio;
            this.lastEffectiveSpp = effectiveSpp;
        }

        public void onDenoiseLatency(double latencyMs,
                                     double energyRatio,
                                     double invalidGuideRatio,
                                     double effectiveSpp) {
            if (Double.isFinite(latencyMs) && latencyMs >= 0.0) {
                if (smoothedLatencyMs <= 0.0 || !Double.isFinite(smoothedLatencyMs)) {
                    smoothedLatencyMs = latencyMs;
                } else {
                    smoothedLatencyMs = smoothedLatencyMs * 0.80 + latencyMs * 0.20;
                }
            }
            this.lastEnergyRatio = energyRatio;
            this.lastInvalidGuideRatio = invalidGuideRatio;
            this.lastEffectiveSpp = effectiveSpp;
        }

        public TelemetrySnapshot snapshot() {
            return new TelemetrySnapshot(
                    invocationCount,
                    skipCount,
                    fallbackCount,
                    lowConfidenceCount,
                    smoothedLatencyMs,
                    lastEnergyRatio,
                    lastInvalidGuideRatio,
                    lastEffectiveSpp,
                    lastMode,
                    lastTilePreset,
                    lastReason
            );
        }

        private static String safeReason(String reason) {
            return reason == null ? "" : reason;
        }
    }

    public static ResolutionBucket resolveBucket(int width, int height) {
        int w = Math.max(1, width);
        int h = Math.max(1, height);
        int maxDim = Math.max(w, h);
        if (maxDim <= 640) {
            return ResolutionBucket.CLASS_512;
        }
        if (maxDim <= 1280) {
            return ResolutionBucket.P720;
        }
        if (maxDim <= 1920) {
            return ResolutionBucket.P1080;
        }
        if (maxDim <= 2560) {
            return ResolutionBucket.P1440;
        }
        return ResolutionBucket.P2160;
    }

    public static Decision decide(int width,
                                  int height,
                                  double smoothedLatencyMs,
                                  boolean preferFastProfile) {
        return decide(
                width,
                height,
                smoothedLatencyMs,
                preferFastProfile,
                0.0,
                1.0,
                0.0
        );
    }

    public static Decision decide(int width,
                                  int height,
                                  double smoothedLatencyMs,
                                  boolean preferFastProfile,
                                  double lastInvalidGuideRatio,
                                  double lastEnergyRatio,
                                  double lastEffectiveSpp) {
        ResolutionBucket bucket = resolveBucket(width, height);
        int fullTileSize = Math.max(8, Math.max(width, height));
        boolean fullFrameConfidenceOk = isFullFrameConfidenceOk(
                lastInvalidGuideRatio,
                lastEnergyRatio,
                lastEffectiveSpp
        );

        return switch (bucket) {
            case CLASS_512 -> fullFrame(bucket, fullTileSize, "mode:full_frame:bucket_512_default");
            case P720 -> {
                if (isOverBudget(smoothedLatencyMs, FRAME_BUDGET_720_MS)) {
                    TilePreset preset = pickPreset(smoothedLatencyMs, FRAME_BUDGET_720_MS, preferFastProfile);
                    yield tiled(bucket, preset, false, "mode:tiled:720_budget_guard");
                }
                if (!fullFrameConfidenceOk) {
                    yield tiled(bucket, TilePreset.BALANCED, false, "mode:tiled:720_confidence_guard");
                }
                yield fullFrame(bucket, fullTileSize, "mode:full_frame:720_default");
            }
            case P1080 -> {
                if (!fullFrameConfidenceOk) {
                    yield tiled(bucket, TilePreset.BALANCED, false, "mode:tiled:1080_confidence_guard");
                }
                if (isOverBudget(smoothedLatencyMs, FRAME_BUDGET_1080_MS)) {
                    TilePreset preset = pickPreset(smoothedLatencyMs, FRAME_BUDGET_1080_MS, preferFastProfile);
                    yield tiled(bucket, preset, false, "mode:tiled:1080_budget_guard");
                }
                yield fullFrame(bucket, fullTileSize, "mode:full_frame:1080_confidence_budget_ok");
            }
            case P1440 -> {
                TilePreset preset = TilePreset.QUALITY;
                if (preferFastProfile || isOverBudget(smoothedLatencyMs, FRAME_BUDGET_1440_MS * 1.15)) {
                    preset = TilePreset.BALANCED;
                }
                if (isOverBudget(smoothedLatencyMs, FRAME_BUDGET_1440_MS * 1.45)) {
                    preset = TilePreset.SPEED;
                }
                yield tiled(bucket, preset, false, "mode:tiled:1440_default");
            }
            case P2160 -> {
                TilePreset preset = isOverBudget(smoothedLatencyMs, FRAME_BUDGET_2160_MS * 0.92)
                        ? TilePreset.BALANCED
                        : TilePreset.QUALITY;
                if (preferFastProfile) {
                    preset = TilePreset.BALANCED;
                }
                yield tiled(bucket, preset, true, "mode:tiled:2160_mandatory");
            }
        };
    }

    public static Decision applyOverrides(Decision base,
                                          int width,
                                          int height,
                                          String modeOverride,
                                          String presetOverride) {
        if (base == null) {
            return decide(width, height, 0.0, false);
        }
        String mode = normalize(modeOverride);
        if ("FULL".equals(mode)) {
            if (base.bucket() == ResolutionBucket.P2160) {
                return tiled(base.bucket(), parsePreset(presetOverride, TilePreset.QUALITY), true,
                        "mode:tiled:2160_mandatory_override_block_full");
            }
            return fullFrame(base.bucket(), Math.max(8, Math.max(width, height)), "mode:full_frame:override");
        }
        if ("TILED".equals(mode)) {
            TilePreset preset = parsePreset(presetOverride, base.preset() == TilePreset.FULL ? TilePreset.BALANCED : base.preset());
            return tiled(base.bucket(), preset, base.bucket() == ResolutionBucket.P2160 || base.tiledMandatory(),
                    "mode:tiled:override");
        }
        if (base.mode() == RuntimeMode.TILED && presetOverride != null && !presetOverride.isBlank()) {
            TilePreset preset = parsePreset(presetOverride, base.preset());
            return tiled(base.bucket(), preset, base.tiledMandatory(), "mode:tiled:preset_override");
        }
        return base;
    }

    public static double estimateMeanRelativeNoise(double[] accumLuma,
                                                   double[] accumLumaSq,
                                                   int[] sampleCounts,
                                                   long fallbackAccumulatedSamples,
                                                   int count) {
        if (accumLuma == null || accumLumaSq == null) {
            return 1.0;
        }
        int limit = Math.min(Math.max(0, count), Math.min(accumLuma.length, accumLumaSq.length));
        if (limit <= 0) {
            return 1.0;
        }
        double sumNoise = 0.0;
        int measured = 0;
        for (int i = 0; i < limit; i++) {
            int sampleCount = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, fallbackAccumulatedSamples);
            double noise = DenoiseSupport.relativeNoise(accumLuma[i], accumLumaSq[i], sampleCount);
            if (Double.isFinite(noise)) {
                sumNoise += noise;
                measured++;
            }
        }
        if (measured <= 0) {
            return 1.0;
        }
        return sumNoise / (double) measured;
    }

    public static double estimateEffectiveSpp(int[] sampleCounts,
                                              int count,
                                              long fallbackAccumulatedSamples) {
        int safeCount = Math.max(1, count);
        if (sampleCounts == null || sampleCounts.length == 0) {
            return Math.max(0.0, fallbackAccumulatedSamples);
        }
        int limit = Math.min(safeCount, sampleCounts.length);
        if (limit <= 0) {
            return Math.max(0.0, fallbackAccumulatedSamples);
        }
        long sum = 0L;
        for (int i = 0; i < limit; i++) {
            sum += Math.max(0, sampleCounts[i]);
        }
        return sum / (double) limit;
    }

    public static double invalidGuideRatio(float[] guideDepth,
                                           float[] guideNormal,
                                           int pixelCount) {
        return invalidGuideRatio(guideDepth, guideNormal, null, null, pixelCount, 0, 0);
    }

    public static double invalidGuideRatio(float[] guideDepth,
                                           float[] guideNormal,
                                           float[] guideAlbedo,
                                           float[] guideRoughness,
                                           int pixelCount,
                                           int width,
                                           int height) {
        int count = Math.max(0, pixelCount);
        if (count <= 0 || guideDepth == null || guideNormal == null) {
            return 1.0;
        }
        int depthLimit = Math.min(count, guideDepth.length);
        int normalLimit = Math.min(count * 3, guideNormal.length);
        if (depthLimit <= 0 || normalLimit < 3) {
            return 1.0;
        }
        int invalid = 0;
        int hardInvalid = 0;
        int safeWidth = Math.max(1, width);
        int safeHeight = Math.max(1, height);
        if (safeWidth * safeHeight < depthLimit) {
            safeWidth = depthLimit;
            safeHeight = 1;
        }
        for (int i = 0; i < depthLimit; i++) {
            boolean depthInvalid = !Float.isFinite(guideDepth[i]);
            int base = i * 3;
            if (base + 2 >= normalLimit) {
                invalid++;
                hardInvalid++;
                continue;
            }
            float nx = guideNormal[base];
            float ny = guideNormal[base + 1];
            float nz = guideNormal[base + 2];
            boolean normalInvalid = nx == 0.0f && ny == 0.0f && nz == 0.0f;
            if (depthInvalid && normalInvalid) {
                invalid++;
                if (!isLikelyLegitimateBoundary(
                        i,
                        safeWidth,
                        safeHeight,
                        guideDepth,
                        guideNormal,
                        guideAlbedo,
                        guideRoughness,
                        depthLimit,
                        normalLimit
                )) {
                    hardInvalid++;
                }
            }
        }
        if (invalid <= 0) {
            return 0.0;
        }
        return hardInvalid / (double) depthLimit;
    }

    public static boolean isGuideInvalidityLowConfidence(double invalidGuideRatio,
                                                         int width,
                                                         int height,
                                                         RuntimeMode mode,
                                                         double effectiveSpp,
                                                         double meanNoise,
                                                         double previousInvalidGuideRatio,
                                                         String previousReason) {
        ResolutionBucket bucket = resolveBucket(width, height);
        double threshold = switch (bucket) {
            case CLASS_512 -> GUIDE_INVALIDITY_LOW_CONF_BASE_512;
            case P720 -> GUIDE_INVALIDITY_LOW_CONF_BASE_720;
            case P1080 -> GUIDE_INVALIDITY_LOW_CONF_BASE_1080;
            case P1440 -> GUIDE_INVALIDITY_LOW_CONF_BASE_1440;
            case P2160 -> GUIDE_INVALIDITY_LOW_CONF_BASE_2160;
        };

        if (mode == RuntimeMode.TILED) {
            threshold += 0.015;
        }
        if (Double.isFinite(effectiveSpp) && effectiveSpp >= 6.0) {
            threshold += 0.020;
        }
        if (Double.isFinite(effectiveSpp) && effectiveSpp >= 8.0) {
            threshold += 0.015;
        }
        if (Double.isFinite(meanNoise) && meanNoise <= 0.30) {
            threshold += 0.015;
        }

        boolean previousGuideInvalidity = previousReason != null
                && previousReason.toLowerCase().contains("guide_invalidity");
        if (previousGuideInvalidity
                && Double.isFinite(previousInvalidGuideRatio)
                && previousInvalidGuideRatio >= threshold - 0.02) {
            threshold += 0.020;
        }

        threshold = Math.min(0.78, Math.max(0.45, threshold));
        return Double.isFinite(invalidGuideRatio) && invalidGuideRatio > threshold;
    }

    public static boolean hasNonFinite(double[] values, int count) {
        if (values == null) {
            return true;
        }
        int limit = Math.min(Math.max(0, count), values.length);
        for (int i = 0; i < limit; i++) {
            if (!Double.isFinite(values[i])) {
                return true;
            }
        }
        return false;
    }

    public static double meanLuminance(double[] r,
                                       double[] g,
                                       double[] b,
                                       int count) {
        if (r == null || g == null || b == null) {
            return 0.0;
        }
        int limit = Math.min(Math.max(0, count), Math.min(r.length, Math.min(g.length, b.length)));
        if (limit <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < limit; i++) {
            sum += DenoiseSupport.luminance(r[i], g[i], b[i]);
        }
        return sum / (double) limit;
    }

    public static double meanAccumulatedLuminance(double[] accumR,
                                                  double[] accumG,
                                                  double[] accumB,
                                                  int[] sampleCounts,
                                                  long fallbackAccumulatedSamples,
                                                  int count) {
        if (accumR == null || accumG == null || accumB == null) {
            return 0.0;
        }
        int limit = Math.min(Math.max(0, count), Math.min(accumR.length, Math.min(accumG.length, accumB.length)));
        if (limit <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int i = 0; i < limit; i++) {
            int sampleCount = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, fallbackAccumulatedSamples);
            double invSamples = AdaptiveSamplingSupport.inverseSampleCount(sampleCount, fallbackAccumulatedSamples);
            sum += DenoiseSupport.luminance(
                    accumR[i] * invSamples,
                    accumG[i] * invSamples,
                    accumB[i] * invSamples
            );
        }
        return sum / (double) limit;
    }

    private static Decision fullFrame(ResolutionBucket bucket, int fullTileSize, String reason) {
        return new Decision(bucket, RuntimeMode.FULL_FRAME, TilePreset.FULL, fullTileSize, 0, false, reason);
    }

    private static Decision tiled(ResolutionBucket bucket, TilePreset preset, boolean tiledMandatory, String reason) {
        return new Decision(bucket, RuntimeMode.TILED, preset, preset.tileSize(), preset.overlap(), tiledMandatory, reason);
    }

    private static boolean isOverBudget(double latencyMs, double budgetMs) {
        return Double.isFinite(latencyMs) && latencyMs > 0.0 && latencyMs > budgetMs;
    }

    private static TilePreset pickPreset(double latencyMs, double budgetMs, boolean preferFastProfile) {
        if (preferFastProfile) {
            return TilePreset.SPEED;
        }
        if (!Double.isFinite(latencyMs) || latencyMs <= 0.0) {
            return TilePreset.BALANCED;
        }
        if (latencyMs > budgetMs * 1.35) {
            return TilePreset.SPEED;
        }
        if (latencyMs > budgetMs * 1.05) {
            return TilePreset.BALANCED;
        }
        return TilePreset.QUALITY;
    }

    private static TilePreset parsePreset(String raw, TilePreset fallback) {
        String preset = normalize(raw);
        if ("QUALITY".equals(preset)) {
            return TilePreset.QUALITY;
        }
        if ("BALANCED".equals(preset)) {
            return TilePreset.BALANCED;
        }
        if ("SPEED".equals(preset) || "FAST".equals(preset)) {
            return TilePreset.SPEED;
        }
        return fallback == null ? TilePreset.BALANCED : fallback;
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase();
    }

    private static boolean isFullFrameConfidenceOk(double invalidGuideRatio,
                                                   double energyRatio,
                                                   double effectiveSpp) {
        if (Double.isFinite(invalidGuideRatio) && invalidGuideRatio > FULL_FRAME_CONF_MAX_INVALID_GUIDE_RATIO) {
            return false;
        }
        if (Double.isFinite(energyRatio)
                && (energyRatio < FULL_FRAME_CONF_MIN_ENERGY_RATIO || energyRatio > FULL_FRAME_CONF_MAX_ENERGY_RATIO)) {
            return false;
        }
        return !Double.isFinite(effectiveSpp) || effectiveSpp >= FULL_FRAME_CONF_MIN_EFFECTIVE_SPP;
    }

    private static boolean isLikelyLegitimateBoundary(int index,
                                                      int width,
                                                      int height,
                                                      float[] guideDepth,
                                                      float[] guideNormal,
                                                      float[] guideAlbedo,
                                                      float[] guideRoughness,
                                                      int depthLimit,
                                                      int normalLimit) {
        if (index < 0 || index >= depthLimit) {
            return false;
        }
        int x = index % width;
        int y = index / width;
        int validNeighbors = 0;
        int invalidNeighbors = 0;
        double minDepth = Double.POSITIVE_INFINITY;
        double maxDepth = Double.NEGATIVE_INFINITY;
        double roughnessMin = Double.POSITIVE_INFINITY;
        double roughnessMax = Double.NEGATIVE_INFINITY;
        double albedoMin = Double.POSITIVE_INFINITY;
        double albedoMax = Double.NEGATIVE_INFINITY;

        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                int sx = x + ox;
                int sy = y + oy;
                if (sx < 0 || sx >= width || sy < 0 || sy >= height) {
                    continue;
                }
                int sIndex = sy * width + sx;
                if (sIndex < 0 || sIndex >= depthLimit) {
                    continue;
                }
                int base = sIndex * 3;
                if (base + 2 >= normalLimit) {
                    continue;
                }
                float sd = guideDepth[sIndex];
                float snx = guideNormal[base];
                float sny = guideNormal[base + 1];
                float snz = guideNormal[base + 2];
                boolean sDepthValid = Float.isFinite(sd);
                boolean sNormalValid = snx != 0.0f || sny != 0.0f || snz != 0.0f;
                boolean sValid = sDepthValid || sNormalValid;
                if (!sValid) {
                    invalidNeighbors++;
                    continue;
                }

                validNeighbors++;
                if (sDepthValid) {
                    minDepth = Math.min(minDepth, sd);
                    maxDepth = Math.max(maxDepth, sd);
                }
                if (guideRoughness != null && sIndex < guideRoughness.length) {
                    double rough = DenoiseSupport.clamp01(guideRoughness[sIndex]);
                    roughnessMin = Math.min(roughnessMin, rough);
                    roughnessMax = Math.max(roughnessMax, rough);
                }
                if (guideAlbedo != null && base + 2 < guideAlbedo.length) {
                    double ar = guideAlbedo[base];
                    double ag = guideAlbedo[base + 1];
                    double ab = guideAlbedo[base + 2];
                    double albedoLuma = DenoiseSupport.luminance(ar, ag, ab);
                    albedoMin = Math.min(albedoMin, albedoLuma);
                    albedoMax = Math.max(albedoMax, albedoLuma);
                }
            }
        }

        if (validNeighbors < 2 || invalidNeighbors <= 0) {
            return false;
        }

        boolean depthEdge = Double.isFinite(minDepth)
                && Double.isFinite(maxDepth)
                && (maxDepth - minDepth) > 0.075;
        boolean roughnessTransition = Double.isFinite(roughnessMin)
                && Double.isFinite(roughnessMax)
                && (roughnessMax - roughnessMin) > 0.12;
        boolean albedoTransition = Double.isFinite(albedoMin)
                && Double.isFinite(albedoMax)
                && (albedoMax - albedoMin) > 0.08;

        return depthEdge || roughnessTransition || albedoTransition;
    }
}

