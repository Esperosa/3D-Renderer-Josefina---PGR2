package engine.render.ray.core;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

import engine.camera.Camera;
import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.Material;
import engine.material.MaterialGraphEvaluator;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Mat3;
import engine.math.Mat4;
import engine.math.Quaternion;
import engine.math.Vec3;
import engine.render.EnvironmentMap;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.Texture;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.scene.Transform;
import engine.util.HardwareTelemetrySampler;
import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;

/**
 * Progressive CPU path tracer with BVH acceleration and tiled multithreading.
 */
public class PathTracerRenderer implements Renderer {

    static final double RAY_EPS = 1e-4;
    static final double INF_T = 1e30;
    private static final double PI = Math.PI;
    private static final double INV_PI = 1.0 / PI;
    private static final int REFERENCE_PRIMARY_ENV_SAMPLES_BASE = 2;
    private static final int REFERENCE_PRIMARY_ENV_SAMPLES_MAX = 4;
    private static final int REFERENCE_PRIMARY_EMISSIVE_SAMPLES_BASE = 2;
    private static final int REFERENCE_PRIMARY_EMISSIVE_SAMPLES_MAX = 4;
    private static final double REFERENCE_GLOSSY_SAMPLE_THRESHOLD = 0.60;
    private static final double REFERENCE_ULTRA_GLOSSY_SAMPLE_THRESHOLD = 0.85;
    private static final double REFERENCE_SUPER_GLOSSY_SAMPLE_THRESHOLD = 0.95;
    private static final int AUTO_TILES_PER_WORKER_MIN = 4;
    private static final int AUTO_TILES_PER_WORKER_MAX = 16;
    private static final double AUTO_TARGET_FRAME_MS_MIN = 10.0;
    private static final double AUTO_TARGET_FRAME_MS_MAX = 45.0;
    private static final double AUTO_FRAME_EWMA_ALPHA = 0.18;
    private static final int AUTO_SLOW_STREAK_TRIGGER = 4;
    private static final int AUTO_FAST_STREAK_TRIGGER = 8;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_DEFAULT = 30;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_MIN = 1;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_MAX = 60;
    private static final int AUTO_HW_SAMPLE_INTERVAL = 6;
    private static final int TILE_CLAIM_CHUNK = 4;
    private static final double AUTO_PROCESS_CPU_LOW = 0.62;
    private static final double AUTO_PROCESS_CPU_HIGH = 0.95;
    private static final double AUTO_HW_EWMA_ALPHA = 0.24;
    private static final long STILL_TIER1_MIN_SAMPLES = 12L;
    private static final long STILL_TIER2_MIN_SAMPLES = 28L;
    private static final long STILL_TIER3_MIN_SAMPLES = 48L;
    private static final long STILL_TIER4_MIN_SAMPLES = 72L;
    private static final long STILL_TIER5_MIN_SAMPLES = 100L;
    private static final int PREVIEW_STILL_WARMUP_FRAMES = 2;
    private static final int PREVIEW_SMOOTH_ADJUST_INTERVAL_FRAMES = 30;
    private static final int PREVIEW_SMOOTH_BOOST_HOLD_FRAMES = 90;
    private static final long ADVANCED_OPTICS_UNLOCK_SAMPLES = 50L;
    private static final long ADVANCED_OPTICS_FULL_SAMPLES = 120L;
    private static final double DISPERSION_IOR_SPREAD = 0.06;
    private static final double CAUSTIC_BOOST_MAX = 1.45;
    private static final double AREA_LIGHT_RADIUS_MIN = 0.01;
    private static final double LEGACY_PREVIEW_CLAMP_DIRECT = 2.8;
    private static final double LEGACY_PREVIEW_CLAMP_INDIRECT = 2.2;
    private static final double GGX_MULTISCATTER_STRENGTH = 0.55;
    private static final double CONDUCTOR_K_MAX = 6.0;
    private static final double SUBSURFACE_DIRECT_STRENGTH = 0.42;
    private static final double CAMERA_MOTION_BLUR_SHUTTER_MAX = 1.0;
    private static final double GLOBAL_VOLUME_DENSITY_MAX = 4.0;
    private static final double GLOBAL_VOLUME_DISTANCE_MAX = 5000.0;
    static final int SPECTRAL_BAND_COUNT = 14;
    private static final double SPECTRAL_LAMBDA_MIN_NM = 390.0;
    private static final double SPECTRAL_LAMBDA_MAX_NM = 720.0;
    private static final double[] SPECTRAL_BAND_LAMBDAS = new double[SPECTRAL_BAND_COUNT];
    private static final double[] SPECTRAL_BASIS_R = new double[SPECTRAL_BAND_COUNT];
    private static final double[] SPECTRAL_BASIS_G = new double[SPECTRAL_BAND_COUNT];
    private static final double[] SPECTRAL_BASIS_B = new double[SPECTRAL_BAND_COUNT];
    private static final double[][] SPECTRAL_BASIS_INV = new double[3][3];

        private record OpticalPreset(String key,
                     boolean conductor,
                     double eta,
                     double kR,
                     double kG,
                     double kB) {
        }

        private static final OpticalPreset[] OPTICAL_PRESETS = {
            new OpticalPreset("BK7", false, 1.5168, 0.0, 0.0, 0.0),
            new OpticalPreset("BOROSILICATE", false, 1.5168, 0.0, 0.0, 0.0),
            new OpticalPreset("FUSED_SILICA", false, 1.4585, 0.0, 0.0, 0.0),
            new OpticalPreset("SILICA", false, 1.4585, 0.0, 0.0, 0.0),
            new OpticalPreset("FLINT", false, 1.6200, 0.0, 0.0, 0.0),
            new OpticalPreset("ALUMINUM", true, 1.05, 7.40, 6.20, 5.30),
            new OpticalPreset("AL", true, 1.05, 7.40, 6.20, 5.30),
            new OpticalPreset("COPPER", true, 0.90, 3.10, 2.70, 2.40),
            new OpticalPreset("CU", true, 0.90, 3.10, 2.70, 2.40),
            new OpticalPreset("GOLD", true, 0.47, 3.14, 2.37, 1.94),
            new OpticalPreset("AU", true, 0.47, 3.14, 2.37, 1.94)
        };

    static {
        double step = (SPECTRAL_LAMBDA_MAX_NM - SPECTRAL_LAMBDA_MIN_NM) / Math.max(1.0, (double) (SPECTRAL_BAND_COUNT - 1));
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            double lambda = SPECTRAL_LAMBDA_MIN_NM + step * i;
            SPECTRAL_BAND_LAMBDAS[i] = lambda;
            SPECTRAL_BASIS_R[i] = spectralGaussian(lambda, 610.0, 45.0);
            SPECTRAL_BASIS_G[i] = spectralGaussian(lambda, 545.0, 38.0);
            SPECTRAL_BASIS_B[i] = spectralGaussian(lambda, 455.0, 30.0);
        }

        double m00 = spectralDot(SPECTRAL_BASIS_R, SPECTRAL_BASIS_R);
        double m01 = spectralDot(SPECTRAL_BASIS_R, SPECTRAL_BASIS_G);
        double m02 = spectralDot(SPECTRAL_BASIS_R, SPECTRAL_BASIS_B);
        double m10 = m01;
        double m11 = spectralDot(SPECTRAL_BASIS_G, SPECTRAL_BASIS_G);
        double m12 = spectralDot(SPECTRAL_BASIS_G, SPECTRAL_BASIS_B);
        double m20 = m02;
        double m21 = m12;
        double m22 = spectralDot(SPECTRAL_BASIS_B, SPECTRAL_BASIS_B);
        invert3x3(
                m00, m01, m02,
                m10, m11, m12,
                m20, m21, m22,
                SPECTRAL_BASIS_INV
        );
    }

    private int width = 1;
    private int height = 1;
    private int workerCount = ThreadPool.recommendedWorkerCount();
    private boolean autoHardwareScheduling = true;
    private boolean workerCountPinned = false;
    private int autoWorkerCap = ThreadPool.recommendedWorkerCount();
    private int autoTilesPerWorker = 8;
    private double autoTargetFrameMs = 20.0;
    private double autoSmoothedFrameMs = 0.0;
    private double autoSmoothedTileMs = 0.0;
    private boolean autoHardwareTelemetryEnabled = true;
    private boolean autoTileCostModelEnabled = true;
    private double autoSmoothedProcessCpu = Double.NaN;
    private double autoSmoothedSystemCpu = Double.NaN;
    private int autoHardwareSampleCountdown = AUTO_HW_SAMPLE_INTERVAL;
    private int autoSlowFrameStreak = 0;
    private int autoFastFrameStreak = 0;
    private int autoSchedulingIntervalFrames = AUTO_SCHEDULING_INTERVAL_FRAMES_DEFAULT;
    private final AutoSchedulingBatch autoSchedulingBatch = new AutoSchedulingBatch(AUTO_SCHEDULING_INTERVAL_FRAMES_DEFAULT);
    private int samplesPerFrame = 1;
    private int maxBounces = 4;
    private int tileSize = 24;
    private boolean tileSizePinned = false;
    private int leafSize = 8;
    private double exposure = 1.26;
    private boolean directLighting = true;
    private boolean skyEnabled = true;
    private boolean denoiseEnabled = true;
    private boolean guideCaptureEnabled = false;
    private boolean adaptiveSamplingEnabled = true;
    private int adaptiveMinSamples = ProgressiveRenderDefaults.PATH_VIEWPORT_ADAPTIVE_MIN_SAMPLES;
    private double adaptiveThreshold = ProgressiveRenderDefaults.PATH_VIEWPORT_ADAPTIVE_THRESHOLD;
    private int denoiseRadius = ProgressiveRenderDefaults.PATH_VIEWPORT_DENOISE_RADIUS;
    private double denoiseStrength = ProgressiveRenderDefaults.PATH_VIEWPORT_DENOISE_STRENGTH;
    private String materialProfile = "AUTO";
    private boolean denoiseFastMode = false;
    private int denoiseTileOverlap = 16;
    private String denoiseRuntimeModeOverride = "AUTO";
    private String denoiseTilePresetOverride = "BALANCED";
    private String denoiseRuntimePackageRootOverride = "";
    private boolean denoiseRuntimePackageRequired = false;
    private DenoiserRuntimePackageBridge.PackageStatus denoiseRuntimePackageStatus;
    private double clampDirect = ProgressiveRenderDefaults.PATH_VIEWPORT_CLAMP_DIRECT;
    private double clampIndirect = ProgressiveRenderDefaults.PATH_VIEWPORT_CLAMP_INDIRECT;
    private boolean referenceMode = false;
    private boolean historyFireflyClampEnabled = true;
    private boolean referenceClampEnabled = false;
    private boolean sampleDrivenFeaturesEnabled = true;
    private double sampleDrivenFeatureFloor = 0.12;
    private double sampleDrivenFeatureWeight = 1.0;
    private boolean cameraDofEnabled = false;
    private double cameraAperture = 0.0;
    private double cameraFocusDistance = 6.0;
    private boolean cameraMotionBlurEnabled = false;
    private double cameraShutterFraction = 0.5;
    private boolean globalVolumeEnabled = false;
    private double globalVolumeDensity = 0.0;
    private double globalVolumeAnisotropy = 0.0;
    private double globalVolumeAlbedoR = 0.92;
    private double globalVolumeAlbedoG = 0.94;
    private double globalVolumeAlbedoB = 0.98;
    private double globalVolumeEmissionR = 0.0;
    private double globalVolumeEmissionG = 0.0;
    private double globalVolumeEmissionB = 0.0;
    private double globalVolumeMaxDistance = 80.0;
    private int toneMapMode = ToneMapSupport.MODE_EXPOSURE;
    private final RuntimeDenoiserOrchestrator.Telemetry denoiserTelemetry = new RuntimeDenoiserOrchestrator.Telemetry();

    private ThreadPool threadPool;

    private double[] accumR = new double[1];
    private double[] accumG = new double[1];
    private double[] accumB = new double[1];
    private double[] accumLuma = new double[1];
    private double[] accumLumaSq = new double[1];
    private int[] sampleCounts = new int[1];
    private double[] denoiseR = new double[1];
    private double[] denoiseG = new double[1];
    private double[] denoiseB = new double[1];
    private double[] denoiseNoise = new double[1];
    private double[] smartSectionBlendScale = new double[1];
    private double[] smartSectionDetailScale = new double[1];
    private double[] smartSectionScratch = new double[1];
    private double[] denoiseScratchR = new double[1];
    private double[] denoiseScratchG = new double[1];
    private double[] denoiseScratchB = new double[1];
    private float[] guideDepth = new float[1];
    private float[] guideNormal = new float[3];
    private float[] guideAlbedo = new float[3];
    private float[] guideRoughness = new float[1];
    private double[] temporalHistoryR = new double[1];
    private double[] temporalHistoryG = new double[1];
    private double[] temporalHistoryB = new double[1];
    private float[] temporalHistoryDepth = new float[1];
    private float[] temporalHistoryNormal = new float[3];
    private float[] temporalHistoryAlbedo = new float[3];
    private boolean temporalHistoryValid = false;
    private long accumulatedSamples = 0;
    private boolean guidesReady = false;

    private Triangle[] triangles = new Triangle[0];
    private int triangleCount = 0;
    private int[] triangleOrder = new int[0];
    private double[] centroidX = new double[0];
    private double[] centroidY = new double[0];
    private double[] centroidZ = new double[0];
    private PathTracerBVHNode bvhRoot;

    private DirLightCache[] dirLights = new DirLightCache[0];
    private PointLightCache[] pointLights = new PointLightCache[0];
    private EmissiveTriangleLight[] emissiveLights = new EmissiveTriangleLight[0];
    private double emissiveLightPowerSum = 0.0;
    private double ambientR = 0.1;
    private double ambientG = 0.1;
    private double ambientB = 0.1;
    private double backgroundR = 0.06;
    private double backgroundG = 0.07;
    private double backgroundB = 0.09;
    private double environmentStrength = 1.0;
    private double environmentExposure = 1.0;
    private double environmentYawDegrees = 0.0;
    private double environmentPitchDegrees = 0.0;
    private double environmentYawCos = 1.0;
    private double environmentYawSin = 0.0;
    private double environmentPitchCos = 1.0;
    private double environmentPitchSin = 0.0;
    private EnvironmentMap environmentMap;
    private double environmentSkySampleWeight = 0.64;
    private double environmentGroundSampleWeight = 0.18;
    private double environmentUniformSampleWeight = 0.18;

    private long geometrySignature = Long.MIN_VALUE;
    private long lightingSignature = Long.MIN_VALUE;
    private long cameraSignature = Long.MIN_VALUE;
    private PreviewCameraResetSupport.Snapshot previousCameraSnapshot;
    private boolean previewQualityLadderEnabled = false;
    private boolean previewMotionActive = false;
    private int previewMotionSecondaryCadence = 1;
    private int previewMotionDenoiseCadence = 1;
    private int previewMotionSamplesPerFrameLimit = 0;
    private int previewMotionBounceLimit = 0;
    private int previewMotionTileSubsetCadence = 1;
    private boolean previewMotionDominantContributionOnly = false;
    private int previewMotionMaxLocalLights = -1;
    private int previewMotionMaxShadowedLocalLights = -1;
    private double previewMotionLocalLightImportanceThreshold = 0.0;
    private double previewMotionThroughputTermination = 0.0;
    private double previewMotionRoughnessSecondarySkip = 0.0;
    private long previewFrameSequence = 0L;
    private PreviewPhase previewPhase = PreviewPhase.STILL_STEADY;
    private int previewPhaseFrameSequence = 0;
    private int motionTileCursor = 0;
    private int[] motionTileEpoch = new int[0];
    private int motionTileLayoutCols = -1;
    private int motionTileLayoutRows = -1;
    private int motionTileEpochCounter = 0;
    private int previewSmoothFrameCounter = 0;
    private int previewSmoothDesyncCounter = 0;
    private int previewSmoothBoostLevel = 0;
    private int previewSmoothBoostHoldFrames = 0;
    private double nextTemporalBlendScale = 1.0;
    private int nextTemporalBlendFrames = 0;
    private String activePreviewQualityTier = "PT_PROGRESSIVE_STILL_T0";
    private String activeMotionQualityTier = "PT_MOVING_REDUCED";
    private int activeStillEnvironmentSamples = 0;
    private int activeStillEmissiveSamples = 0;
    private int activeStillMaxBounces = 0;
    private boolean activeStillTransmissionEnabled = true;
    private boolean activeStillDirectLightingEnabled = true;
    private boolean[] temporalUpdateMask = new boolean[1];
    private boolean[] tileRenderPlanMask = new boolean[1];
    private final LongAdder frameTileCostNanos = new LongAdder();
    private final LongAdder frameTileCostSamples = new LongAdder();
    private final LongAccumulator frameTileCostMinNanos = new LongAccumulator(Long::min, Long.MAX_VALUE);
    private final LongAccumulator frameTileCostMaxNanos = new LongAccumulator(Long::max, Long.MIN_VALUE);
    private final PathTracerTransport transport = new PathTracerTransport(this);

    private enum PreviewPhase {
        STILL_STEADY,
        MOTION_ENTER,
        MOTION_STEADY,
        MOTION_EXIT_RESYNC,
        STILL_WARMUP
    }

    private record PreviewStillTierPlan(String name,
                                        int environmentSamples,
                                        int emissiveSamples,
                                        int maxBounces,
                                        boolean transmissionEnabled,
                                        boolean directLightingEnabled) {
    }

    @Override
    public synchronized void init(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        allocateAccumulation(this.width, this.height);
        rebuildThreadPool();
        resetAccumulation();
    }

    @Override
    public synchronized void render(Scene scene, Camera camera, FrameBuffer fb, double time) {
        long frameStartNanos = System.nanoTime();
        int fbWidth = fb.getWidth();
        int fbHeight = fb.getHeight();
        if (fbWidth != width || fbHeight != height) {
            resize(fbWidth, fbHeight);
        }

        long gSig = computeGeometrySignature(scene);
        if (gSig != geometrySignature) {
            geometrySignature = gSig;
            rebuildGeometry(scene);
            resetAccumulation();
        }

        long lSig = computeLightingSignature(scene);
        if (lSig != lightingSignature) {
            lightingSignature = lSig;
            rebuildLightCache(scene);
            resetAccumulation();
        }

        PreviewCameraResetSupport.Snapshot motionBlurSourceSnapshot = previousCameraSnapshot;
        PreviewCameraResetSupport.Snapshot currentCameraSnapshot =
                PreviewCameraResetSupport.capture(camera, fbWidth, fbHeight);
        long cSig = currentCameraSnapshot.fullSignature;
        if (cSig != cameraSignature) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.CAMERA_SIGNATURE_CHANGES, 1L);
            cameraSignature = cSig;
            applyCameraReset(PreviewCameraResetSupport.classify(
                    previousCameraSnapshot,
                    currentCameraSnapshot,
                    previewQualityLadderEnabled && previewMotionActive && !referenceMode));
        }
        previousCameraSnapshot = currentCameraSnapshot;

        if (triangleCount == 0 || bvhRoot == null) {
            renderEnvironmentOnly(camera, fb);
            return;
        }

        Arrays.fill(fb.getDepthBuffer(), 1.0f);
        int[] outColor = fb.getColorBuffer();
        if (!ensureRuntimeBuffers(fbWidth, fbHeight, outColor)) {
            fillBackground(fb);
            return;
        }

        final long frameSequence = ++previewFrameSequence;
        updatePreviewPhaseState();
        final boolean fullFrameCoverage = shouldRenderFullFrameForCurrentPhase();
        final int effectiveSamplesPerFrame = resolveEffectiveSamplesPerFrame();
        final long sampleTarget = accumulatedSamples + (fullFrameCoverage ? effectiveSamplesPerFrame : 0L);
        sampleDrivenFeatureWeight = resolveSampleDrivenFeatureWeight(sampleTarget);
        applyPreviewStillTierPlan(sampleTarget);
        final int effectiveMaxBounces = resolveEffectiveMaxBounces(frameSequence);
        if (previewQualityLadderEnabled && previewMotionActive && !referenceMode) {
            activeMotionQualityTier = resolvePreviewMotionTierName(effectiveMaxBounces);
        }
        final boolean runFullDenoise = resolveRunFullDenoise(frameSequence);
        if (effectiveMaxBounces < maxBounces) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_SECONDARY_REDUCED_FRAMES, 1L);
        }
        final double invSamples = 1.0 / Math.max(1L, sampleTarget);
        final boolean captureGuides = (denoiseEnabled || guideCaptureEnabled) && !guidesReady;
        final double temporalBlendScale = consumeTemporalBlendScale();

        if (autoHardwareScheduling && !workerCountPinned) {
            int recommended = ThreadPool.recommendedWorkerCount();
            if (recommended != workerCount) {
                workerCount = recommended;
                rebuildThreadPool();
            }
        }

        CameraState cam = buildCameraState(camera, fbWidth, fbHeight, motionBlurSourceSnapshot, currentCameraSnapshot);
        int tileW = resolveRenderTileSize(fbWidth, fbHeight);
        int tileH = tileW;
        int tileCols = (fbWidth + tileW - 1) / tileW;
        int tileRows = (fbHeight + tileH - 1) / tileH;
        int tileCount = tileCols * tileRows;
        int activeWorkers = resolveActiveWorkerCount(tileCount);
        boolean[] tileRenderPlan = resolveTileRenderPlan(tileCount, tileCols, tileRows, fullFrameCoverage);
        recordTileTemporalDesync(fullFrameCoverage, tileCount);
        frameTileCostNanos.reset();
        frameTileCostSamples.reset();
        frameTileCostMinNanos.reset();
        frameTileCostMaxNanos.reset();
        PreviewPathMetrics previewPathMetrics = RuntimeInstrumentation.isEnabled() ? new PreviewPathMetrics() : null;
        long traceStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.RT_OR_PT_RENDER);

        if (activeWorkers <= 1 || threadPool == null || tileCount <= 1) {
            TraceContext ctx = new TraceContext();
            SplitMix64 rng = new SplitMix64(seedForWorker(0, sampleTarget));
            for (int tile = 0; tile < tileCount; tile++) {
                if (!tileRenderPlan[tile]) {
                    continue;
                }
                long tileStartNanos = System.nanoTime();
                renderTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, cam, outColor,
                        captureGuides, effectiveSamplesPerFrame, effectiveMaxBounces, rng, ctx, previewPathMetrics);
                recordTileCost(frameTileCostNanos, frameTileCostSamples, frameTileCostMinNanos, frameTileCostMaxNanos, tileStartNanos);
            }
        } else {
            AtomicInteger tileCursor = new AtomicInteger(0);
            Runnable[] tasks = new Runnable[activeWorkers];
            for (int w = 0; w < activeWorkers; w++) {
                final int workerIndex = w;
                tasks[w] = () -> {
                    TraceContext ctx = new TraceContext();
                    SplitMix64 rng = new SplitMix64(seedForWorker(workerIndex, sampleTarget));
                    int chunkStart;
                    while ((chunkStart = tileCursor.getAndAdd(TILE_CLAIM_CHUNK)) < tileCount) {
                        int chunkEnd = Math.min(tileCount, chunkStart + TILE_CLAIM_CHUNK);
                        for (int tile = chunkStart; tile < chunkEnd; tile++) {
                            if (!tileRenderPlan[tile]) {
                                continue;
                            }
                            long tileStartNanos = System.nanoTime();
                            renderTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, cam, outColor,
                                    captureGuides, effectiveSamplesPerFrame, effectiveMaxBounces, rng, ctx, previewPathMetrics);
                            recordTileCost(frameTileCostNanos, frameTileCostSamples, frameTileCostMinNanos, frameTileCostMaxNanos, tileStartNanos);
                        }
                    }
                };
            }
            threadPool.submitAndWait(tasks);
        }
        if (fullFrameCoverage) {
            accumulatedSamples = sampleTarget;
        }
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.RT_OR_PT_RENDER, traceStage);
        flushPreviewPathMetrics(previewPathMetrics);

        ensureTemporalUpdateMask(accumR.length);
        Arrays.fill(temporalUpdateMask, false);
        writeTilePlanMask(tileRenderPlan, tileCols, tileW, tileH, fbWidth, fbHeight, temporalUpdateMask);
        boolean[] frameTemporalMask = (previewPhase == PreviewPhase.MOTION_STEADY && !fullFrameCoverage)
                ? temporalUpdateMask
                : null;
        advancePreviewPhaseAfterFrame();

        if (captureGuides) {
            guidesReady = true;
        }

        if (denoiseEnabled) {
            applyDenoiseAndResolve(outColor, invSamples, temporalBlendScale, runFullDenoise, frameTemporalMask);
        }

        if (autoHardwareScheduling) {
            double frameMs = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
            scheduleAutoTuning(tileCount, frameMs, frameTileCostNanos, frameTileCostSamples, frameTileCostMinNanos, frameTileCostMaxNanos);
        }
    }

    @Override
    public synchronized void resize(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        allocateAccumulation(this.width, this.height);
        resetAccumulation();
    }

    @Override
    public synchronized void setParameter(String key, Object value) {
        if (key == null) {
            return;
        }
        String k = key.trim().toLowerCase();
        if (("autohardware".equals(k) || "autoscheduling".equals(k)) && value instanceof Boolean) {
            autoHardwareScheduling = (Boolean) value;
            autoSchedulingBatch.reset();
            return;
        }
        if (("autoscheduletargetms".equals(k) || "autoschedulingtargetms".equals(k)) && value instanceof Number) {
            autoTargetFrameMs = clamp(((Number) value).doubleValue(), AUTO_TARGET_FRAME_MS_MIN, AUTO_TARGET_FRAME_MS_MAX);
            return;
        }
        if (("autoscheduleintervalframes".equals(k) || "autoschedulingintervalframes".equals(k)) && value instanceof Number) {
            autoSchedulingIntervalFrames = Math.max(
                    AUTO_SCHEDULING_INTERVAL_FRAMES_MIN,
                    Math.min(AUTO_SCHEDULING_INTERVAL_FRAMES_MAX, ((Number) value).intValue()));
            autoSchedulingBatch.setIntervalFrames(autoSchedulingIntervalFrames);
            return;
        }
        if (("autohwtelemetry".equals(k) || "autoschedulehwtelemetry".equals(k)) && value instanceof Boolean) {
            autoHardwareTelemetryEnabled = (Boolean) value;
            if (!autoHardwareTelemetryEnabled) {
                autoSmoothedProcessCpu = Double.NaN;
                autoSmoothedSystemCpu = Double.NaN;
            }
            return;
        }
        if (("autotilecost".equals(k) || "autotilecostmodel".equals(k)) && value instanceof Boolean) {
            autoTileCostModelEnabled = (Boolean) value;
            if (!autoTileCostModelEnabled) {
                autoSmoothedTileMs = 0.0;
            }
            return;
        }
        if (("workercountauto".equals(k) || "autoworkers".equals(k)) && value instanceof Boolean) {
            boolean auto = (Boolean) value;
            workerCountPinned = !auto;
            if (auto) {
                int recommended = ThreadPool.recommendedWorkerCount();
                if (recommended != workerCount) {
                    workerCount = recommended;
                    rebuildThreadPool();
                }
                autoWorkerCap = workerCount;
            }
            return;
        }
        if (("tilesizeauto".equals(k) || "autotilesize".equals(k)) && value instanceof Boolean) {
            tileSizePinned = !((Boolean) value);
            return;
        }
        if (("workercount".equals(k) || "threads".equals(k)) && value instanceof Number) {
            int next = Math.max(1, Math.min(ThreadPool.recommendedWorkerCount(), ((Number) value).intValue()));
            if (next != workerCount) {
                workerCount = next;
                workerCountPinned = true;
                autoWorkerCap = next;
                rebuildThreadPool();
            }
            return;
        }
        if (("previewqualityladder".equals(k) || "previewladder".equals(k)) && value instanceof Boolean) {
            previewQualityLadderEnabled = (Boolean) value;
            return;
        }
        if (("previewmotionactive".equals(k) || "motionactive".equals(k)) && value instanceof Boolean) {
            onPreviewMotionStateChanged((Boolean) value);
            return;
        }
        if (("previewmotionsecondarycadence".equals(k) || "motionsecondarycadence".equals(k)) && value instanceof Number) {
            previewMotionSecondaryCadence = Math.max(1, Math.min(8, ((Number) value).intValue()));
            return;
        }
        if (("previewmotiondenoisecadence".equals(k) || "motiondenoisecadence".equals(k)) && value instanceof Number) {
            previewMotionDenoiseCadence = Math.max(1, Math.min(8, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionsamplesperframe".equals(k) || "motionsamplesperframe".equals(k)) && value instanceof Number) {
            previewMotionSamplesPerFrameLimit = Math.max(0, Math.min(64, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionmaxdepth".equals(k) || "motionmaxdepth".equals(k) || "previewmotionmaxbounces".equals(k)) && value instanceof Number) {
            previewMotionBounceLimit = Math.max(0, Math.min(32, ((Number) value).intValue()));
            return;
        }
        if (("previewmotiontilesubsetcadence".equals(k) || "motiontilesubsetcadence".equals(k)) && value instanceof Number) {
            previewMotionTileSubsetCadence = Math.max(1, Math.min(16, ((Number) value).intValue()));
            return;
        }
        if (("previewmotiondominantcontributiononly".equals(k) || "motiondominantcontributiononly".equals(k)) && value instanceof Boolean) {
            previewMotionDominantContributionOnly = (Boolean) value;
            return;
        }
        if (("previewmotionmaxlocallights".equals(k) || "motionmaxlocallights".equals(k)) && value instanceof Number) {
            previewMotionMaxLocalLights = Math.max(-1, Math.min(16, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionmaxshadowedlocallights".equals(k) || "motionmaxshadowedlocallights".equals(k)) && value instanceof Number) {
            previewMotionMaxShadowedLocalLights = Math.max(-1, Math.min(16, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionlocallightimportancethreshold".equals(k) || "motionlocallightimportancethreshold".equals(k)) && value instanceof Number) {
            previewMotionLocalLightImportanceThreshold = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if (("previewmotionthroughputtermination".equals(k) || "motionthroughputtermination".equals(k)) && value instanceof Number) {
            previewMotionThroughputTermination = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if (("previewmotionroughnesssecondaryskip".equals(k) || "motionroughnesssecondaryskip".equals(k)) && value instanceof Number) {
            previewMotionRoughnessSecondarySkip = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if (("samplesperframe".equals(k) || "spp".equals(k)) && value instanceof Number) {
            int next = Math.max(1, Math.min(64, ((Number) value).intValue()));
            if (next != samplesPerFrame) {
                samplesPerFrame = next;
                resetAccumulation();
            }
            return;
        }
        if (("maxdepth".equals(k) || "maxbounces".equals(k) || "depth".equals(k)) && value instanceof Number) {
            int next = Math.max(1, Math.min(32, ((Number) value).intValue()));
            if (next != maxBounces) {
                maxBounces = next;
                resetAccumulation();
            }
            return;
        }
        if ("tilesize".equals(k) && value instanceof Number) {
            tileSize = Math.max(8, Math.min(4096, ((Number) value).intValue()));
            tileSizePinned = true;
            return;
        }
        if (("denoisetileoverlap".equals(k) || "tileoverlap".equals(k)) && value instanceof Number) {
            denoiseTileOverlap = Math.max(0, Math.min(512, ((Number) value).intValue()));
            return;
        }
        if ("denoiseruntimemode".equals(k) && value != null) {
            denoiseRuntimeModeOverride = String.valueOf(value).trim().toUpperCase();
            return;
        }
        if ("denoisetilepreset".equals(k) && value != null) {
            denoiseTilePresetOverride = String.valueOf(value).trim().toUpperCase();
            return;
        }
        if (("denoiseruntimepackageroot".equals(k) || "denoisepackageroot".equals(k)) && value != null) {
            denoiseRuntimePackageRootOverride = String.valueOf(value).trim();
            denoiseRuntimePackageStatus = null;
            return;
        }
        if (("denoiseruntimepackagerequired".equals(k) || "denoisepackagerequired".equals(k)) && value instanceof Boolean) {
            denoiseRuntimePackageRequired = (Boolean) value;
            denoiseRuntimePackageStatus = null;
            return;
        }
        if ("leafsize".equals(k) && value instanceof Number) {
            int next = Math.max(2, Math.min(32, ((Number) value).intValue()));
            if (next != leafSize) {
                leafSize = next;
                rebuildBvh();
                resetAccumulation();
            }
            return;
        }
        if ("exposure".equals(k) && value instanceof Number) {
            exposure = Math.max(0.05, Math.min(10.0, ((Number) value).doubleValue()));
            return;
        }
        if ("materialprofile".equals(k) && value != null) {
            materialProfile = String.valueOf(value).trim().toUpperCase();
            resetAccumulation();
            return;
        }
        if (("sampledrivenfeatures".equals(k) || "autosamplesfeatures".equals(k) || "sampledriven".equals(k)) && value instanceof Boolean) {
            sampleDrivenFeaturesEnabled = (Boolean) value;
            return;
        }
        if (("sampledrivenfeaturefloor".equals(k) || "autosamplesfeaturefloor".equals(k)) && value instanceof Number) {
            sampleDrivenFeatureFloor = clamp01(((Number) value).doubleValue());
            return;
        }
        if (("cameradof".equals(k) || "dof".equals(k) || "depthoffield".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != cameraDofEnabled) {
                cameraDofEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if (("cameraaperture".equals(k) || "aperture".equals(k)) && value instanceof Number) {
            double next = Math.max(0.0, Math.min(2.0, ((Number) value).doubleValue()));
            if (Math.abs(next - cameraAperture) > 1e-9) {
                cameraAperture = next;
                resetAccumulation();
            }
            return;
        }
        if (("camerafocusdistance".equals(k) || "focusdistance".equals(k) || "focusdist".equals(k)) && value instanceof Number) {
            double next = Math.max(0.05, Math.min(5000.0, ((Number) value).doubleValue()));
            if (Math.abs(next - cameraFocusDistance) > 1e-9) {
                cameraFocusDistance = next;
                resetAccumulation();
            }
            return;
        }
        if (("cameramotionblur".equals(k) || "motionblur".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != cameraMotionBlurEnabled) {
                cameraMotionBlurEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if (("camerashutterfraction".equals(k) || "shutterfraction".equals(k) || "shutter".equals(k)) && value instanceof Number) {
            double next = Math.max(0.0, Math.min(CAMERA_MOTION_BLUR_SHUTTER_MAX, ((Number) value).doubleValue()));
            if (Math.abs(next - cameraShutterFraction) > 1e-9) {
                cameraShutterFraction = next;
                resetAccumulation();
            }
            return;
        }
        if (("globalvolume".equals(k) || "volumetric".equals(k) || "volumetricenabled".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != globalVolumeEnabled) {
                globalVolumeEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if (("globalvolumedensity".equals(k) || "volumetricdensity".equals(k)) && value instanceof Number) {
            double next = Math.max(0.0, Math.min(GLOBAL_VOLUME_DENSITY_MAX, ((Number) value).doubleValue()));
            if (Math.abs(next - globalVolumeDensity) > 1e-9) {
                globalVolumeDensity = next;
                resetAccumulation();
            }
            return;
        }
        if (("globalvolumeanisotropy".equals(k) || "volumetricanisotropy".equals(k)) && value instanceof Number) {
            double next = Math.max(-0.99, Math.min(0.99, ((Number) value).doubleValue()));
            if (Math.abs(next - globalVolumeAnisotropy) > 1e-9) {
                globalVolumeAnisotropy = next;
                resetAccumulation();
            }
            return;
        }
        if (("globalvolumealbedor".equals(k) || "volumetricalbedor".equals(k)) && value instanceof Number) {
            globalVolumeAlbedoR = clamp01(((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumealbedog".equals(k) || "volumetricalbedog".equals(k)) && value instanceof Number) {
            globalVolumeAlbedoG = clamp01(((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumealbedob".equals(k) || "volumetricalbedob".equals(k)) && value instanceof Number) {
            globalVolumeAlbedoB = clamp01(((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumeemissionr".equals(k) || "volumetricemissionr".equals(k)) && value instanceof Number) {
            globalVolumeEmissionR = Math.max(0.0, ((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumeemissiong".equals(k) || "volumetricemissiong".equals(k)) && value instanceof Number) {
            globalVolumeEmissionG = Math.max(0.0, ((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumeemissionb".equals(k) || "volumetricemissionb".equals(k)) && value instanceof Number) {
            globalVolumeEmissionB = Math.max(0.0, ((Number) value).doubleValue());
            resetAccumulation();
            return;
        }
        if (("globalvolumemaxdistance".equals(k) || "volumetricmaxdistance".equals(k)) && value instanceof Number) {
            double next = Math.max(0.5, Math.min(GLOBAL_VOLUME_DISTANCE_MAX, ((Number) value).doubleValue()));
            if (Math.abs(next - globalVolumeMaxDistance) > 1e-9) {
                globalVolumeMaxDistance = next;
                resetAccumulation();
            }
            return;
        }
        if ("directlighting".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != directLighting) {
                directLighting = next;
                resetAccumulation();
            }
            return;
        }
        if ("sky".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != skyEnabled) {
                skyEnabled = next;
                rebuildEnvironmentSamplingCache();
                resetAccumulation();
            }
            return;
        }
        if (("denoise".equals(k) || "denoiseenabled".equals(k)) && value instanceof Boolean) {
            denoiseEnabled = (Boolean) value;
            return;
        }
        if (("adaptive".equals(k) || "adaptivesampling".equals(k) || "adaptiveenabled".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != adaptiveSamplingEnabled) {
                adaptiveSamplingEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if ("adaptiveminsamples".equals(k) && value instanceof Number) {
            int next = Math.max(1, Math.min(4096, ((Number) value).intValue()));
            if (next != adaptiveMinSamples) {
                adaptiveMinSamples = next;
                resetAccumulation();
            }
            return;
        }
        if (("adaptivethreshold".equals(k) || "adaptivenoisethreshold".equals(k)) && value instanceof Number) {
            double next = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            if (Math.abs(next - adaptiveThreshold) > 1e-9) {
                adaptiveThreshold = next;
                resetAccumulation();
            }
            return;
        }
        if ("denoiseradius".equals(k) && value instanceof Number) {
            denoiseRadius = Math.max(1, Math.min(4, ((Number) value).intValue()));
            return;
        }
        if ("denoisestrength".equals(k) && value instanceof Number) {
            denoiseStrength = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if ("denoisefastmode".equals(k) || "denoiselightmode".equals(k) || "denoiseprofile".equals(k)) {
            denoiseFastMode = parseFastDenoiseMode(value, denoiseFastMode);
            return;
        }
        if ("tonemap".equals(k) || "tonemapmode".equals(k)) {
            toneMapMode = ToneMapSupport.parseMode(value, toneMapMode);
            return;
        }
        if (("clampdirect".equals(k) || "fireflyclampdirect".equals(k)) && value instanceof Number) {
            clampDirect = Math.max(0.0, ((Number) value).doubleValue());
            return;
        }
        if (("clampindirect".equals(k) || "fireflyclampindirect".equals(k)) && value instanceof Number) {
            clampIndirect = Math.max(0.0, ((Number) value).doubleValue());
            return;
        }
        if (("referenceclamp".equals(k) || "referenceclampenabled".equals(k)) && value instanceof Boolean) {
            referenceClampEnabled = (Boolean) value;
            return;
        }
        if (("referencemode".equals(k) || "reference".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != referenceMode) {
                referenceMode = next;
                resetAccumulation();
            }
            return;
        }
        if (("historyfireflyclamp".equals(k) || "previewfireflyclamp".equals(k)) && value instanceof Boolean) {
            historyFireflyClampEnabled = (Boolean) value;
            return;
        }
        if ("reset".equals(k) || "resetaccumulation".equals(k)) {
            resetAccumulation();
            return;
        }
        if ("shutdown".equals(k) && value instanceof Boolean && (Boolean) value) {
            shutdownPool();
            return;
        }
        if (("guidecapture".equals(k) || "guidecaptureenabled".equals(k)) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != guideCaptureEnabled) {
                guideCaptureEnabled = next;
                if (!guideCaptureEnabled) {
                    guidesReady = false;
                }
            }
        }
    }

    @Override
    public String getName() {
        return "Path Tracer (CPU)";
    }

    public synchronized int getWorkerCount() {
        return workerCount;
    }

    public synchronized long getAccumulatedSamples() {
        return accumulatedSamples;
    }

    public synchronized int getSamplesPerFrame() {
        return samplesPerFrame;
    }

    public synchronized String getActivePreviewQualityTier() {
        if (referenceMode) {
            return "PT_REFERENCE";
        }
        return previewMotionActive ? activeMotionQualityTier : activePreviewQualityTier;
    }

    public RuntimeDenoiserOrchestrator.TelemetrySnapshot getDenoiserTelemetrySnapshot() {
        return denoiserTelemetry.snapshot();
    }

    public String getDenoiserRuntimePackageStatusSummary() {
        DenoiserRuntimePackageBridge.PackageStatus status = resolveDenoiserRuntimePackageStatus();
        return status.summary();
    }

    public Path getDenoiserRuntimePackageRoot() {
        return resolveDenoiserRuntimePackageStatus().root();
    }

    public int getRenderWidth() {
        return width;
    }

    public int getRenderHeight() {
        return height;
    }

    public boolean isGuidesReady() {
        return guidesReady;
    }

    public RenderSnapshot captureSnapshot() {
        int count = safePixelCount(width, height);
        float[] beauty = new float[count * 3];
        float[] albedo = new float[count * 3];
        float[] normal = new float[count * 3];
        double invSamples = 1.0 / Math.max(1L, accumulatedSamples);
        for (int idx = 0; idx < count; idx++) {
            int base = idx * 3;
            beauty[base] = (float) (accumR[idx] * invSamples);
            beauty[base + 1] = (float) (accumG[idx] * invSamples);
            beauty[base + 2] = (float) (accumB[idx] * invSamples);
            albedo[base] = guideAlbedo[base];
            albedo[base + 1] = guideAlbedo[base + 1];
            albedo[base + 2] = guideAlbedo[base + 2];
            normal[base] = guideNormal[base];
            normal[base + 1] = guideNormal[base + 1];
            normal[base + 2] = guideNormal[base + 2];
        }
        return new RenderSnapshot(width, height, accumulatedSamples, guidesReady, beauty, albedo, normal);
    }

    public static final class RenderSnapshot {
        public final int width;
        public final int height;
        public final long sampleCount;
        public final boolean guidesReady;
        public final float[] beauty;
        public final float[] albedo;
        public final float[] normal;

        private RenderSnapshot(int width,
                               int height,
                               long sampleCount,
                               boolean guidesReady,
                               float[] beauty,
                               float[] albedo,
                               float[] normal) {
            this.width = width;
            this.height = height;
            this.sampleCount = sampleCount;
            this.guidesReady = guidesReady;
            this.beauty = beauty;
            this.albedo = albedo;
            this.normal = normal;
        }
    }

    private void renderTile(int tileIndex,
                            int tileCols,
                            int tileW,
                            int tileH,
                            int fbWidth,
                            int fbHeight,
                            CameraState camera,
                            int[] outColor,
                            boolean captureGuides,
                            int effectiveSamplesPerFrame,
                            int effectiveMaxBounces,
                            SplitMix64 rng,
                            TraceContext ctx,
                            PreviewPathMetrics metrics) {
        int tileX = tileIndex % tileCols;
        int tileY = tileIndex / tileCols;
        int x0 = tileX * tileW;
        int y0 = tileY * tileH;
        int x1 = Math.min(fbWidth, x0 + tileW);
        int y1 = Math.min(fbHeight, y0 + tileH);

        for (int y = y0; y < y1; y++) {
            int row = y * fbWidth;
            for (int x = x0; x < x1; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= accumR.length || idx >= outColor.length || idx >= sampleCounts.length) {
                    continue;
                }

                int pixelSampleCount = sampleCounts[idx];
                if (adaptiveSamplingEnabled
                        && !captureGuides
                        && AdaptiveSamplingSupport.shouldSkipPixel(
                        pixelSampleCount,
                        adaptiveMinSamples,
                        adaptiveThreshold,
                        accumLuma[idx],
                        accumLumaSq[idx])) {
                    writeResolvedPixel(outColor, idx);
                    continue;
                }

                double batchR = 0.0;
                double batchG = 0.0;
                double batchB = 0.0;
                double batchLuma = 0.0;
                double batchLumaSq = 0.0;
                boolean needsGuideCapture = captureGuides;
                int samplesTaken = 0;

                for (int s = 0; s < effectiveSamplesPerFrame; s++) {
                    if (needsGuideCapture && s == 0) {
                        generatePrimaryRay(camera, x, y, 0.5, 0.5, ctx);
                        beginPrimaryGuideCapture(ctx);
                    } else {
                        generatePrimaryRay(camera, x, y, rng, ctx);
                    }
                    if (metrics != null && ((x & 7) == 0) && ((y & 7) == 0)) {
                        ctx.pathMetrics = metrics;
                        ctx.pathMetricScale = 64;
                    } else {
                        ctx.pathMetrics = null;
                        ctx.pathMetricScale = 0;
                    }
                    tracePath(ctx, rng, effectiveMaxBounces);
                    if (needsGuideCapture) {
                        storePrimaryGuide(idx, ctx);
                        needsGuideCapture = false;
                    }
                    double sampleR = ctx.outR;
                    double sampleG = ctx.outG;
                    double sampleB = ctx.outB;
                    if (historyFireflyClampEnabled) {
                        double referenceLuma = DenoiseSupport.referenceLuminance(
                                accumLuma[idx],
                                pixelSampleCount,
                                batchLuma,
                                s);
                        double referenceLumaStdDev = DenoiseSupport.referenceLuminanceStdDev(
                                accumLuma[idx],
                                accumLumaSq[idx],
                                pixelSampleCount,
                                batchLuma,
                                batchLumaSq,
                                s);
                        long referenceSamples = pixelSampleCount + s;
                        double fireflyScale = DenoiseSupport.fireflyScale(
                                sampleR,
                                sampleG,
                                sampleB,
                                referenceLuma,
                                referenceLumaStdDev,
                                referenceSamples);
                        if (fireflyScale < 1.0) {
                            sampleR *= fireflyScale;
                            sampleG *= fireflyScale;
                            sampleB *= fireflyScale;
                        }
                    }

                    double sampleLuma = DenoiseSupport.luminance(sampleR, sampleG, sampleB);
                    batchR += sampleR;
                    batchG += sampleG;
                    batchB += sampleB;
                    batchLuma += sampleLuma;
                    batchLumaSq += sampleLuma * sampleLuma;
                    samplesTaken++;
                }

                if (samplesTaken > 0) {
                    accumR[idx] += batchR;
                    accumG[idx] += batchG;
                    accumB[idx] += batchB;
                    accumLuma[idx] += batchLuma;
                    accumLumaSq[idx] += batchLumaSq;
                    sampleCounts[idx] = pixelSampleCount + samplesTaken;
                }

                writeResolvedPixel(outColor, idx);
            }
        }
    }

    private void writeResolvedPixel(int[] outColor, int idx) {
        if (outColor == null || idx < 0 || idx >= outColor.length || idx >= accumR.length) {
            return;
        }
        double invSamples = AdaptiveSamplingSupport.inverseSampleCount(
            AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, idx, 0L),
            0L);
        outColor[idx] = packColor(
                toneMap(accumR[idx] * invSamples),
                toneMap(accumG[idx] * invSamples),
                toneMap(accumB[idx] * invSamples)
        );
    }

        private void flushPreviewPathMetrics(PreviewPathMetrics metrics) {
        if (metrics == null || !RuntimeInstrumentation.isEnabled()) {
            return;
        }
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_PATH_BOUNCE_NS,
            metrics.pathBounceNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_DIRECT_LIGHT_NS,
            metrics.directLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_SHADOW_QUERY_NS,
            metrics.shadowQueryNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_DIRECTIONAL_LIGHT_NS,
            metrics.directionalLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_POINT_LIGHT_NS,
            metrics.pointLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_ENVIRONMENT_LIGHTING_NS,
            metrics.environmentLightingNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_EMISSIVE_LIGHTING_NS,
            metrics.emissiveLightingNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_CANDIDATES,
            metrics.localLightCandidates.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_SHADED,
            metrics.localLightShaded.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_LOCAL_LIGHT_SHADOWED,
            metrics.localLightShadowed.sum());
        }

    private void tracePath(TraceContext ctx, SplitMix64 rng, int effectiveMaxBounces) {
        if (referenceMode) {
            transport.traceReferencePath(ctx, rng);
            return;
        }
        transport.tracePreviewPath(ctx, rng, effectiveMaxBounces);
    }

    void tracePreviewPathInternal(TraceContext ctx, SplitMix64 rng, int effectiveMaxBounces) {
        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;

        ctx.previewLastEventDelta = true;
        ctx.previewLastBsdfPdf = 0.0;
        ctx.previewLastSurfaceValid = false;

        double throughputR = 1.0;
        double throughputG = 1.0;
        double throughputB = 1.0;

        double radianceR = 0.0;
        double radianceG = 0.0;
        double radianceB = 0.0;
        boolean fullStillTierActive = isPreviewStillFullTierActive();
        boolean spectral14Active = fullStillTierActive;
        if (spectral14Active) {
            int heroBand = Math.min(SPECTRAL_BAND_COUNT - 1, (int) (rng.nextDouble() * SPECTRAL_BAND_COUNT));
            ctx.spectralHeroBand = heroBand;
            ctx.spectralCompanionBand = (heroBand + (SPECTRAL_BAND_COUNT / 2)) % SPECTRAL_BAND_COUNT;
        } else {
            ctx.spectralHeroBand = -1;
            ctx.spectralCompanionBand = -1;
        }
        double advancedOpticsWeight = resolveAdvancedOpticsWeight(fullStillTierActive);
        double causticCarry = 0.0;
        boolean directLightingActive = directLighting
            && (previewMotionActive || referenceMode || activeStillDirectLightingEnabled);
        boolean transmissionEnabled = previewMotionActive || referenceMode || activeStillTransmissionEnabled;
        int environmentSampleCount = previewMotionActive || referenceMode
            ? 1
            : Math.max(0, activeStillEnvironmentSamples);
        int emissiveSampleCount = previewMotionActive || referenceMode
            ? 1
            : Math.max(0, activeStillEmissiveSamples);
        boolean dominantContributionOnly = previewMotionActive && previewMotionDominantContributionOnly;
        double throughputTermination = previewMotionActive ? previewMotionThroughputTermination : 0.0;
        double roughnessSecondarySkip = previewMotionActive ? previewMotionRoughnessSecondarySkip : 0.0;
        int maxLocalLights = previewMotionActive ? previewMotionMaxLocalLights : -1;
        int maxShadowedLocalLights = previewMotionActive ? previewMotionMaxShadowedLocalLights : -1;
        double localLightImportanceThreshold = previewMotionActive ? previewMotionLocalLightImportanceThreshold : 0.0;
        double featureWeight = sampleDrivenFeatureWeight;
        boolean globalVolumeActive = globalVolumeEnabled && globalVolumeDensity * featureWeight > 1e-6;
        double volumeDensity = globalVolumeActive ? Math.max(1e-6, globalVolumeDensity * featureWeight) : 0.0;
        double volumeAlbedoR = clamp01(globalVolumeAlbedoR * (0.75 + 0.25 * featureWeight));
        double volumeAlbedoG = clamp01(globalVolumeAlbedoG * (0.75 + 0.25 * featureWeight));
        double volumeAlbedoB = clamp01(globalVolumeAlbedoB * (0.75 + 0.25 * featureWeight));
        double volumeEmissionR = Math.max(0.0, globalVolumeEmissionR * featureWeight);
        double volumeEmissionG = Math.max(0.0, globalVolumeEmissionG * featureWeight);
        double volumeEmissionB = Math.max(0.0, globalVolumeEmissionB * featureWeight);
        if (dominantContributionOnly) {
            environmentSampleCount = 0;
            emissiveSampleCount = 0;
            transmissionEnabled = false;
        }

        for (int bounce = 0; bounce < effectiveMaxBounces; bounce++) {
            PreviewPathMetrics metrics = ctx.pathMetrics;
            long metricScale = ctx.pathMetricScale > 0 ? ctx.pathMetricScale : 1L;
            long bounceStart = metrics == null ? 0L : System.nanoTime();
            boolean hasSurfaceHit = intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx);
            if (globalVolumeActive) {
                double mediumMaxDistance = hasSurfaceHit ? ctx.hit.t : globalVolumeMaxDistance;
                if (mediumMaxDistance > RAY_EPS) {
                    double sampleDistance = -Math.log(Math.max(1e-9, 1.0 - rng.nextDouble())) / volumeDensity;
                    if (sampleDistance < mediumMaxDistance) {
                        double tr = Math.exp(-volumeDensity * sampleDistance);
                        double oneMinusTr = 1.0 - tr;
                        radianceR += throughputR * volumeEmissionR * oneMinusTr;
                        radianceG += throughputG * volumeEmissionG * oneMinusTr;
                        radianceB += throughputB * volumeEmissionB * oneMinusTr;
                        throughputR *= tr;
                        throughputG *= tr;
                        throughputB *= tr;

                        double scatterX = ox + dx * sampleDistance;
                        double scatterY = oy + dy * sampleDistance;
                        double scatterZ = oz + dz * sampleDistance;
                        throughputR *= volumeAlbedoR;
                        throughputG *= volumeAlbedoG;
                        throughputB *= volumeAlbedoB;

                        double phasePdf = sampleHenyeyGreensteinDirection(
                                dx,
                                dy,
                                dz,
                                globalVolumeAnisotropy,
                                rng,
                                ctx);
                        ox = scatterX + ctx.sampleDx * RAY_EPS;
                        oy = scatterY + ctx.sampleDy * RAY_EPS;
                        oz = scatterZ + ctx.sampleDz * RAY_EPS;
                        dx = ctx.sampleDx;
                        dy = ctx.sampleDy;
                        dz = ctx.sampleDz;
                        ctx.previewLastEventDelta = false;
                        ctx.previewLastBsdfPdf = phasePdf;
                        ctx.previewLastSurfaceValid = false;
                        if (metrics != null) {
                            metrics.pathBounceNanos.add((System.nanoTime() - bounceStart) * metricScale);
                        }
                        continue;
                    }
                    double segTr = Math.exp(-volumeDensity * mediumMaxDistance);
                    double segOneMinusTr = 1.0 - segTr;
                    radianceR += throughputR * volumeEmissionR * segOneMinusTr;
                    radianceG += throughputG * volumeEmissionG * segOneMinusTr;
                    radianceB += throughputB * volumeEmissionB * segOneMinusTr;
                    throughputR *= segTr;
                    throughputG *= segTr;
                    throughputB *= segTr;
                }
            }

            if (!hasSurfaceHit) {
                markPrimaryGuideMiss(ctx);
                if (skyEnabled) {
                    long envStart = metrics == null ? 0L : System.nanoTime();
                    sampleEnvironment(dx, dy, dz, ctx);
                    double envR = ctx.envR;
                    double envG = ctx.envG;
                    double envB = ctx.envB;
                    if (ctx.spectralHeroBand >= 0) {
                        spectralHeroProjectRgb(envR, envG, envB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
                        envR = ctx.spectralRgb0[0];
                        envG = ctx.spectralRgb0[1];
                        envB = ctx.spectralRgb0[2];
                    }
                    double envContributionR = throughputR * envR;
                    double envContributionG = throughputG * envG;
                    double envContributionB = throughputB * envB;
                    if (directLightingActive && bounce > 0 && !ctx.previewLastEventDelta) {
                        double lightPdf = referenceEnvironmentBackgroundPdf(dx, dy, dz);
                        double misWeight = PathTracingSamplingSupport.powerHeuristic(ctx.previewLastBsdfPdf, lightPdf);
                        envContributionR *= misWeight;
                        envContributionG *= misWeight;
                        envContributionB *= misWeight;
                    }
                        double envScale = fullStillTierActive
                            ? PathSampleRegularizer.contributionScale(
                            envContributionR,
                            envContributionG,
                            envContributionB,
                            PathSampleRegularizer.ContributionKind.ENVIRONMENT,
                            bounce,
                            DenoiseSupport.luminance(ctx.primaryGuideBaseR, ctx.primaryGuideBaseG, ctx.primaryGuideBaseB),
                            ctx.primaryGuideRoughness,
                            ctx.primaryGuideSpecularity,
                            ctx.primaryGuideEmissionLuma)
                            : 1.0;
                    double envClamp = contributionClampScale(envContributionR, envContributionG, envContributionB, bounce == 0);
                    envScale *= envClamp;
                    radianceR += envContributionR * envScale;
                    radianceG += envContributionG * envScale;
                    radianceB += envContributionB * envScale;
                    if (metrics != null) {
                        metrics.environmentLightingNanos.add((System.nanoTime() - envStart) * metricScale);
                    }
                }
                if (metrics != null) {
                    metrics.pathBounceNanos.add((System.nanoTime() - bounceStart) * metricScale);
                }
                break;
            }

            Triangle tri = ctx.hit.triangle;
            sampleSurface(tri, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (ctx.surface.discard) {
                advancePrimaryGuide(ctx);
                ox = ctx.hit.px + dx * RAY_EPS;
                oy = ctx.hit.py + dy * RAY_EPS;
                oz = ctx.hit.pz + dz * RAY_EPS;
                continue;
            }

            capturePrimaryGuideSurface(ctx);

            double nx = ctx.surface.nx;
            double ny = ctx.surface.ny;
            double nz = ctx.surface.nz;
            double baseR = ctx.surface.baseR;
            double baseG = ctx.surface.baseG;
            double baseB = ctx.surface.baseB;
            double ndotv = Math.max(0.0, -(dx * nx + dy * ny + dz * nz));
            double fresnel = schlickFresnel(ndotv, ctx.surface.refractiveIndex);
                double effectiveRoughness = fullStillTierActive
                    ? PathSampleRegularizer.regularizeRoughness(
                    ctx.surface.roughness,
                    bounce,
                    ctx.primaryGuideRoughness,
                    ctx.primaryGuideSpecularity)
                    : clamp01(ctx.surface.roughness);
                double transmissionProbability = transmissionEnabled
                    ? clamp01(ctx.surface.transmission * (1.0 - fresnel))
                    : 0.0;
            double clearcoatProbability = clamp01(ctx.surface.clearcoatFactor);
            double baseSpecProbability = clamp01(Math.max(ctx.surface.reflectivity, fresnel));
            double specProbability = baseSpecProbability * (1.0 - clearcoatProbability);
            double diffuseProbability = clamp01(1.0 - transmissionProbability - clearcoatProbability - specProbability);
            double ambientWeight = fullStillTierActive ? 0.0 : 1.0;
            double ambientLightR = baseR * ambientR * diffuseProbability * ambientWeight;
            double ambientLightG = baseG * ambientG * diffuseProbability * ambientWeight;
            double ambientLightB = baseB * ambientB * diffuseProbability * ambientWeight;
            double primaryBaseLuma = DenoiseSupport.luminance(ctx.primaryGuideBaseR, ctx.primaryGuideBaseG, ctx.primaryGuideBaseB);
            double specLightingWeight = fullStillTierActive ? specProbability : Math.max(0.08, specProbability);

            double emissionContributionR = throughputR * ctx.surface.emissionR;
            double emissionContributionG = throughputG * ctx.surface.emissionG;
            double emissionContributionB = throughputB * ctx.surface.emissionB;
            if (ctx.spectralHeroBand >= 0) {
                spectralHeroProjectRgb(
                        emissionContributionR,
                        emissionContributionG,
                        emissionContributionB,
                        ctx.spectralHeroBand,
                    ctx.spectralCompanionBand,
                        ctx.spectralScratch0,
                        ctx.spectralRgb0
                );
                emissionContributionR = ctx.spectralRgb0[0];
                emissionContributionG = ctx.spectralRgb0[1];
                emissionContributionB = ctx.spectralRgb0[2];
            }
            if (directLightingActive && bounce > 0 && !ctx.previewLastEventDelta) {
                double lightPdf = previewEmissiveLightPdf(ctx.hit, dx, dy, dz, ctx);
                double misWeight = PathTracingSamplingSupport.powerHeuristic(ctx.previewLastBsdfPdf, lightPdf);
                emissionContributionR *= misWeight;
                emissionContributionG *= misWeight;
                emissionContributionB *= misWeight;
            }
                double emissionScale = fullStillTierActive
                    ? PathSampleRegularizer.contributionScale(
                    emissionContributionR,
                    emissionContributionG,
                    emissionContributionB,
                    PathSampleRegularizer.ContributionKind.EMISSION,
                    bounce,
                    primaryBaseLuma,
                    ctx.primaryGuideRoughness,
                    ctx.primaryGuideSpecularity,
                    ctx.primaryGuideEmissionLuma)
                    : 1.0;
                double emissionClamp = contributionClampScale(emissionContributionR, emissionContributionG, emissionContributionB, bounce == 0);
                emissionScale *= emissionClamp;
                radianceR += emissionContributionR * emissionScale;
                radianceG += emissionContributionG * emissionScale;
                radianceB += emissionContributionB * emissionScale;
                double ambientContributionR = throughputR * ambientLightR;
                double ambientContributionG = throughputG * ambientLightG;
                double ambientContributionB = throughputB * ambientLightB;
                double ambientClamp = contributionClampScale(ambientContributionR, ambientContributionG, ambientContributionB, bounce == 0);
                radianceR += ambientContributionR * ambientClamp;
                radianceG += ambientContributionG * ambientClamp;
                radianceB += ambientContributionB * ambientClamp;
            populateReferenceSurfaceLobes(ctx.surface, dx, dy, dz, ctx.referenceLobes);

            if (directLightingActive) {
                long directStart = metrics == null ? 0L : System.nanoTime();
                long shadowQueryNanos = 0L;
                long directionalLightNanos = 0L;
                long pointLightNanos = 0L;
                long environmentLightingNanos = 0L;
                long emissiveLightingNanos = 0L;
                long localLightCandidates = 0L;
                long localLightShaded = 0L;
                long localLightShadowed = 0L;
                ctx.lightR = 0.0;
                ctx.lightG = 0.0;
                ctx.lightB = 0.0;

                double vx = -dx;
                double vy = -dy;
                double vz = -dz;
                double nDotV = Math.max(1e-6, nx * vx + ny * vy + nz * vz);
                double roughnessForG = clamp01(effectiveRoughness);
                double alpha = roughnessToAlpha(effectiveRoughness);
                double alphaSq = alpha * alpha;
                double f0R = clamp01(ctx.surface.specR);
                double f0G = clamp01(ctx.surface.specG);
                double f0B = clamp01(ctx.surface.specB);
                boolean advancedPreviewOptics = fullStillTierActive;
                double metallic = clamp01(ctx.surface.metallic);
                if (ctx.surface.presetConductor) {
                    metallic = Math.max(0.92, metallic);
                }
                double conductorEta = ctx.surface.presetConductor
                    ? ctx.surface.presetEta
                    : Math.max(1.0, ctx.surface.refractiveIndex);
                double conductorKR = ctx.surface.presetConductor
                    ? Math.max(0.02, ctx.surface.presetKR)
                    : resolveConductorExtinction(f0R, metallic);
                double conductorKG = ctx.surface.presetConductor
                    ? Math.max(0.02, ctx.surface.presetKG)
                    : resolveConductorExtinction(f0G, metallic);
                double conductorKB = ctx.surface.presetConductor
                    ? Math.max(0.02, ctx.surface.presetKB)
                    : resolveConductorExtinction(f0B, metallic);
                double specularEnergyComp = advancedPreviewOptics
                    ? ggxMultipleScatterCompensation(effectiveRoughness, average3(f0R, f0G, f0B))
                    : 1.0;
                double coatRoughness = clamp01(ctx.surface.clearcoatRoughness);
                double coatAlpha = roughnessToAlpha(ctx.surface.clearcoatRoughness);
                double coatAlphaSq = coatAlpha * coatAlpha;

                for (DirLightCache light : dirLights) {
                    long lightStart = metrics == null ? 0L : System.nanoTime();
                    double nDotL = nx * light.lx + ny * light.ly + nz * light.lz;
                    if (nDotL <= 0.0) {
                        if (metrics != null) {
                            directionalLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                    prepareShadowRay(ctx.hit, ctx.surface, light.lx, light.ly, light.lz, ctx);
                    long shadowStart = metrics == null ? 0L : System.nanoTime();
                    if (intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            light.lx, light.ly, light.lz,
                            ctx.shadowTMin, INF_T, ctx)) {
                        if (metrics != null) {
                            shadowQueryNanos += System.nanoTime() - shadowStart;
                            directionalLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                    if (metrics != null) {
                        shadowQueryNanos += System.nanoTime() - shadowStart;
                    }
                    double hx = light.lx + vx;
                    double hy = light.ly + vy;
                    double hz = light.lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double specularTerm = 0.0;
                    double clearcoatTerm = 0.0;
                    double fresnelR = 0.0;
                    double fresnelG = 0.0;
                    double fresnelB = 0.0;
                    double clearcoatFresnel = 0.0;
                    double sheenTerm = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                        specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG) * specularEnergyComp;
                        if (advancedPreviewOptics) {
                            spectralLayeredFresnelRgb(
                                    vDotH,
                                    average3(f0R, f0G, f0B),
                                    metallic,
                                    conductorEta,
                                    conductorKR,
                                    conductorKG,
                                    conductorKB,
                                    ctx.spectralScratch0,
                                    ctx.spectralRgb0
                            );
                            fresnelR = ctx.spectralRgb0[0];
                            fresnelG = ctx.spectralRgb0[1];
                            fresnelB = ctx.spectralRgb0[2];
                        } else {
                            fresnelR = schlickFresnelColor(vDotH, f0R);
                            fresnelG = schlickFresnelColor(vDotH, f0G);
                            fresnelB = schlickFresnelColor(vDotH, f0B);
                        }
                        if (clearcoatProbability > 1e-6) {
                            clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                            clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                        }
                        sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                    }
                        ctx.lightR += light.r * nDotL * (baseR * diffuseProbability * INV_PI
                            + specularTerm * fresnelR * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenR * INV_PI * sheenTerm);
                        ctx.lightG += light.g * nDotL * (baseG * diffuseProbability * INV_PI
                            + specularTerm * fresnelG * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenG * INV_PI * sheenTerm);
                        ctx.lightB += light.b * nDotL * (baseB * diffuseProbability * INV_PI
                            + specularTerm * fresnelB * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenB * INV_PI * sheenTerm);
                    if (metrics != null) {
                        directionalLightNanos += System.nanoTime() - lightStart;
                    }
                }

                int localLightsShadedCount = 0;
                int shadowedLocalLights = 0;
                for (PointLightCache light : pointLights) {
                    if (maxLocalLights >= 0 && localLightsShadedCount >= maxLocalLights) {
                        break;
                    }
                    long lightStart = metrics == null ? 0L : System.nanoTime();
                    if (light.light instanceof AreaLight areaLight) {
                        int areaSamples = resolveAreaShadowSamples(areaLight, previewMotionActive);
                        double accumAreaR = 0.0;
                        double accumAreaG = 0.0;
                        double accumAreaB = 0.0;

                        double centerLx = light.px - ctx.hit.px;
                        double centerLy = light.py - ctx.hit.py;
                        double centerLz = light.pz - ctx.hit.pz;
                        double centerDistSq = centerLx * centerLx + centerLy * centerLy + centerLz * centerLz;
                        if (centerDistSq < 1e-12) {
                            if (metrics != null) {
                                pointLightNanos += System.nanoTime() - lightStart;
                            }
                            continue;
                        }
                        double centerDist = Math.sqrt(centerDistSq);
                        double invCenterDist = 1.0 / centerDist;
                        centerLx *= invCenterDist;
                        centerLy *= invCenterDist;
                        centerLz *= invCenterDist;
                        double centerNDotL = nx * centerLx + ny * centerLy + nz * centerLz;
                        if (centerNDotL <= 0.0) {
                            if (metrics != null) {
                                pointLightNanos += System.nanoTime() - lightStart;
                            }
                            continue;
                        }
                        double centerAtt = areaLight.attenuation(centerDist)
 * areaLight.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                        if (centerAtt <= 0.0) {
                            if (metrics != null) {
                                pointLightNanos += System.nanoTime() - lightStart;
                            }
                            continue;
                        }

                        localLightCandidates++;
                        double centerImportance = DenoiseSupport.luminance(light.r, light.g, light.b) * centerAtt * centerNDotL;
                        if (centerImportance < localLightImportanceThreshold) {
                            if (metrics != null) {
                                pointLightNanos += System.nanoTime() - lightStart;
                            }
                            continue;
                        }

                        boolean allowShadow = maxShadowedLocalLights < 0 || shadowedLocalLights < maxShadowedLocalLights;
                        for (int sampleIndex = 0; sampleIndex < areaSamples; sampleIndex++) {
                            sampleAreaLightPosition(areaLight, rng, ctx.tmpVec0, ctx.tmpVec1, ctx.tmpVec2);
                            double sampleLx = ctx.tmpVec0.x - ctx.hit.px;
                            double sampleLy = ctx.tmpVec0.y - ctx.hit.py;
                            double sampleLz = ctx.tmpVec0.z - ctx.hit.pz;
                            double sampleDistSq = sampleLx * sampleLx + sampleLy * sampleLy + sampleLz * sampleLz;
                            if (sampleDistSq < 1e-12) {
                                continue;
                            }
                            double sampleDist = Math.sqrt(sampleDistSq);
                            double invSampleDist = 1.0 / sampleDist;
                            sampleLx *= invSampleDist;
                            sampleLy *= invSampleDist;
                            sampleLz *= invSampleDist;

                            double sampleNDotL = nx * sampleLx + ny * sampleLy + nz * sampleLz;
                            if (sampleNDotL <= 0.0) {
                                continue;
                            }
                            double sampleAtt = areaLight.attenuation(sampleDist)
 * areaLight.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                            if (sampleAtt <= 0.0) {
                                continue;
                            }

                            if (allowShadow) {
                                prepareShadowRay(ctx.hit, ctx.surface, sampleLx, sampleLy, sampleLz, ctx);
                                long shadowStart = metrics == null ? 0L : System.nanoTime();
                                if (intersectAny(
                                        ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                                        sampleLx, sampleLy, sampleLz,
                                        ctx.shadowTMin, Math.max(ctx.shadowTMin, sampleDist - RAY_EPS), ctx)) {
                                    if (metrics != null) {
                                        shadowQueryNanos += System.nanoTime() - shadowStart;
                                    }
                                    continue;
                                }
                                if (metrics != null) {
                                    shadowQueryNanos += System.nanoTime() - shadowStart;
                                }
                            }

                            double hx = sampleLx + vx;
                            double hy = sampleLy + vy;
                            double hz = sampleLz + vz;
                            double hLenSq = hx * hx + hy * hy + hz * hz;
                            double specularTerm = 0.0;
                            double clearcoatTerm = 0.0;
                            double fresnelR = 0.0;
                            double fresnelG = 0.0;
                            double fresnelB = 0.0;
                            double clearcoatFresnel = 0.0;
                            double sheenTerm = 0.0;
                            if (hLenSq > 1e-14) {
                                double invH = 1.0 / Math.sqrt(hLenSq);
                                hx *= invH;
                                hy *= invH;
                                hz *= invH;
                                double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                                double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                                specularTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, alphaSq, roughnessForG) * specularEnergyComp;
                                if (advancedPreviewOptics) {
                                    spectralLayeredFresnelRgb(
                                            vDotH,
                                            average3(f0R, f0G, f0B),
                                            metallic,
                                            conductorEta,
                                            conductorKR,
                                            conductorKG,
                                            conductorKB,
                                            ctx.spectralScratch0,
                                            ctx.spectralRgb0
                                    );
                                    fresnelR = ctx.spectralRgb0[0];
                                    fresnelG = ctx.spectralRgb0[1];
                                    fresnelB = ctx.spectralRgb0[2];
                                } else {
                                    fresnelR = schlickFresnelColor(vDotH, f0R);
                                    fresnelG = schlickFresnelColor(vDotH, f0G);
                                    fresnelB = schlickFresnelColor(vDotH, f0B);
                                }
                                if (clearcoatProbability > 1e-6) {
                                    clearcoatTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, coatAlphaSq, coatRoughness);
                                    clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                                }
                                sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                            }
                                accumAreaR += light.r * sampleAtt * sampleNDotL * (baseR * diffuseProbability * INV_PI
                                    + specularTerm * fresnelR * specLightingWeight
                                    + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                                    + ctx.surface.sheenR * INV_PI * sheenTerm);
                                accumAreaG += light.g * sampleAtt * sampleNDotL * (baseG * diffuseProbability * INV_PI
                                    + specularTerm * fresnelG * specLightingWeight
                                    + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                                    + ctx.surface.sheenG * INV_PI * sheenTerm);
                                accumAreaB += light.b * sampleAtt * sampleNDotL * (baseB * diffuseProbability * INV_PI
                                    + specularTerm * fresnelB * specLightingWeight
                                    + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                                    + ctx.surface.sheenB * INV_PI * sheenTerm);
                        }

                        if (allowShadow) {
                            localLightShadowed++;
                            shadowedLocalLights++;
                        }
                        if (areaSamples > 0) {
                            double invAreaSamples = 1.0 / areaSamples;
                            accumAreaR *= invAreaSamples;
                            accumAreaG *= invAreaSamples;
                            accumAreaB *= invAreaSamples;
                        }
                        if ((accumAreaR + accumAreaG + accumAreaB) > 1e-12) {
                            ctx.lightR += accumAreaR;
                            ctx.lightG += accumAreaG;
                            ctx.lightB += accumAreaB;
                            localLightsShadedCount++;
                            localLightShaded++;
                        }
                        if (metrics != null) {
                            pointLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }

                    double lx = light.px - ctx.hit.px;
                    double ly = light.py - ctx.hit.py;
                    double lz = light.pz - ctx.hit.pz;
                    double distSq = lx * lx + ly * ly + lz * lz;
                    if (distSq < 1e-12) {
                        if (metrics != null) {
                            pointLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                    double dist = Math.sqrt(distSq);
                    double invDist = 1.0 / dist;
                    lx *= invDist;
                    ly *= invDist;
                    lz *= invDist;

                    double nDotL = nx * lx + ny * ly + nz * lz;
                    if (nDotL <= 0.0) {
                        if (metrics != null) {
                            pointLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }

                    double att = light.light.attenuation(dist)
 * light.light.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                    if (att <= 0.0) {
                        if (metrics != null) {
                            pointLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                    localLightCandidates++;
                    double importance = DenoiseSupport.luminance(light.r, light.g, light.b) * att * nDotL;
                    if (importance < localLightImportanceThreshold) {
                        if (metrics != null) {
                            pointLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }

                    boolean allowShadow = maxShadowedLocalLights < 0 || shadowedLocalLights < maxShadowedLocalLights;
                    if (allowShadow) {
                        prepareShadowRay(ctx.hit, ctx.surface, lx, ly, lz, ctx);
                        long shadowStart = metrics == null ? 0L : System.nanoTime();
                        if (intersectAny(
                                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                                lx, ly, lz,
                                ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
                            if (metrics != null) {
                                shadowQueryNanos += System.nanoTime() - shadowStart;
                                pointLightNanos += System.nanoTime() - lightStart;
                            }
                            localLightShadowed++;
                            shadowedLocalLights++;
                            continue;
                        }
                        if (metrics != null) {
                            shadowQueryNanos += System.nanoTime() - shadowStart;
                        }
                        localLightShadowed++;
                        shadowedLocalLights++;
                    }
                    double hx = lx + vx;
                    double hy = ly + vy;
                    double hz = lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double specularTerm = 0.0;
                    double clearcoatTerm = 0.0;
                    double fresnelR = 0.0;
                    double fresnelG = 0.0;
                    double fresnelB = 0.0;
                    double clearcoatFresnel = 0.0;
                    double sheenTerm = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                        specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG) * specularEnergyComp;
                        if (advancedPreviewOptics) {
                            spectralLayeredFresnelRgb(
                                    vDotH,
                                    average3(f0R, f0G, f0B),
                                    metallic,
                                    conductorEta,
                                    conductorKR,
                                    conductorKG,
                                    conductorKB,
                                    ctx.spectralScratch0,
                                    ctx.spectralRgb0
                            );
                            fresnelR = ctx.spectralRgb0[0];
                            fresnelG = ctx.spectralRgb0[1];
                            fresnelB = ctx.spectralRgb0[2];
                        } else {
                            fresnelR = schlickFresnelColor(vDotH, f0R);
                            fresnelG = schlickFresnelColor(vDotH, f0G);
                            fresnelB = schlickFresnelColor(vDotH, f0B);
                        }
                        if (clearcoatProbability > 1e-6) {
                            clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                            clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                        }
                        sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                    }
                        ctx.lightR += light.r * att * nDotL * (baseR * diffuseProbability * INV_PI
                            + specularTerm * fresnelR * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenR * INV_PI * sheenTerm);
                        ctx.lightG += light.g * att * nDotL * (baseG * diffuseProbability * INV_PI
                            + specularTerm * fresnelG * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenG * INV_PI * sheenTerm);
                        ctx.lightB += light.b * att * nDotL * (baseB * diffuseProbability * INV_PI
                            + specularTerm * fresnelB * specLightingWeight
                            + clearcoatTerm * clearcoatFresnel * clearcoatProbability
                            + ctx.surface.sheenB * INV_PI * sheenTerm);
                    localLightsShadedCount++;
                    localLightShaded++;
                    if (metrics != null) {
                        pointLightNanos += System.nanoTime() - lightStart;
                    }
                }

                double envBaseR = ctx.lightR;
                double envBaseG = ctx.lightG;
                double envBaseB = ctx.lightB;
                long envLightingStart = metrics == null ? 0L : System.nanoTime();
                for (int sample = 0; sample < environmentSampleCount; sample++) {
                    samplePreviewEnvironmentLighting(ctx.hit, ctx.surface, ctx.referenceLobes, rng, ctx);
                }
                if (metrics != null) {
                    environmentLightingNanos += System.nanoTime() - envLightingStart;
                }
                if (environmentSampleCount > 1) {
                    double invSamples = 1.0 / environmentSampleCount;
                    ctx.lightR = envBaseR + (ctx.lightR - envBaseR) * invSamples;
                    ctx.lightG = envBaseG + (ctx.lightG - envBaseG) * invSamples;
                    ctx.lightB = envBaseB + (ctx.lightB - envBaseB) * invSamples;
                }

                double emissiveBaseR = ctx.lightR;
                double emissiveBaseG = ctx.lightG;
                double emissiveBaseB = ctx.lightB;
                long emissiveLightingStart = metrics == null ? 0L : System.nanoTime();
                for (int sample = 0; sample < emissiveSampleCount; sample++) {
                    samplePreviewEmissiveLighting(ctx.hit, ctx.surface, ctx.referenceLobes, rng, ctx);
                }
                if (metrics != null) {
                    emissiveLightingNanos += System.nanoTime() - emissiveLightingStart;
                }
                if (emissiveSampleCount > 1) {
                    double invSamples = 1.0 / emissiveSampleCount;
                    ctx.lightR = emissiveBaseR + (ctx.lightR - emissiveBaseR) * invSamples;
                    ctx.lightG = emissiveBaseG + (ctx.lightG - emissiveBaseG) * invSamples;
                    ctx.lightB = emissiveBaseB + (ctx.lightB - emissiveBaseB) * invSamples;
                }

                double directContributionR = throughputR * ctx.lightR;
                double directContributionG = throughputG * ctx.lightG;
                double directContributionB = throughputB * ctx.lightB;
                double causticPathGuide = resolveCausticPathGuidance(bounce, ctx.previewLastEventDelta, ctx.previewLastBsdfPdf);
                double causticBoost = resolveCausticBoost(advancedOpticsWeight, causticCarry, causticPathGuide, ctx.surface);
                directContributionR *= causticBoost;
                directContributionG *= causticBoost;
                directContributionB *= causticBoost;
                double directScale = fullStillTierActive
                    ? PathSampleRegularizer.contributionScale(
                    directContributionR,
                    directContributionG,
                    directContributionB,
                    PathSampleRegularizer.ContributionKind.DIRECT,
                    bounce,
                    primaryBaseLuma,
                    ctx.primaryGuideRoughness,
                    ctx.primaryGuideSpecularity,
                    ctx.primaryGuideEmissionLuma)
                    : 1.0;
                double directClamp = contributionClampScale(directContributionR, directContributionG, directContributionB, bounce == 0);
                directScale *= directClamp;
                radianceR += directContributionR * directScale;
                radianceG += directContributionG * directScale;
                radianceB += directContributionB * directScale;
                if (metrics != null) {
                    metrics.directLightNanos.add((System.nanoTime() - directStart) * metricScale);
                    metrics.shadowQueryNanos.add(shadowQueryNanos * metricScale);
                    metrics.directionalLightNanos.add(directionalLightNanos * metricScale);
                    metrics.pointLightNanos.add(pointLightNanos * metricScale);
                    metrics.environmentLightingNanos.add(environmentLightingNanos * metricScale);
                    metrics.emissiveLightingNanos.add(emissiveLightingNanos * metricScale);
                    metrics.localLightCandidates.add(localLightCandidates * metricScale);
                    metrics.localLightShaded.add(localLightShaded * metricScale);
                    metrics.localLightShadowed.add(localLightShadowed * metricScale);
                }
            }

            double throughputLuma = DenoiseSupport.luminance(throughputR, throughputG, throughputB);
            if (throughputTermination > 0.0 && throughputLuma <= throughputTermination) {
                if (metrics != null) {
                    metrics.pathBounceNanos.add((System.nanoTime() - bounceStart) * metricScale);
                }
                break;
            }
            if (dominantContributionOnly && roughnessSecondarySkip > 0.0 && effectiveRoughness >= roughnessSecondarySkip) {
                if (metrics != null) {
                    metrics.pathBounceNanos.add((System.nanoTime() - bounceStart) * metricScale);
                }
                break;
            }

            double carryDecay = ctx.previewLastEventDelta ? 0.74 : 0.48;
            causticCarry *= carryDecay;

            double nextDx;
            double nextDy;
            double nextDz;

            double branchPick = rng.nextDouble();
            double transmissionThreshold = transmissionProbability;
            double clearcoatThreshold = transmissionThreshold + clearcoatProbability;
            double specularThreshold = clearcoatThreshold + specProbability;
            if (branchPick < transmissionThreshold && transmissionProbability > 1e-6) {
                Vec3 microNormal = ctx.tmpVec1.set(nx, ny, nz);
                if (effectiveRoughness > 1e-4) {
                    sampleGgxHalfVector(nx, ny, nz, Math.max(0.02, effectiveRoughness), rng, ctx);
                    microNormal.set(ctx.sampleDx, ctx.sampleDy, ctx.sampleDz);
                }
                double transmissionIor = resolveTransmissionIor(
                        ctx.surface.refractiveIndex,
                        ctx.surface.dispersion,
                        advancedOpticsWeight,
                        rng.nextDouble());
                Vec3 refracted = ctx.tmpVec0.set(dx, dy, dz)
                        .refract(microNormal, transmissionIor, ctx.tmpVec0);
                if (refracted.lengthSquared() < 1e-10) {
                    branchPick = transmissionThreshold + 1e-3;
                } else {
                    nextDx = refracted.x;
                    nextDy = refracted.y;
                    nextDz = refracted.z;
                    double branchRatio = fullStillTierActive
                            ? PathSampleRegularizer.boundedSelectionRatio(transmissionThreshold)
                            : (transmissionThreshold / Math.max(1e-6, transmissionThreshold));
                        double factorR;
                        double factorG;
                        double factorB;
                        if (spectral14Active) {
                        spectralTransmissionFactorsRgb(
                            ctx.surface,
                            ndotv,
                            nextDx,
                            nextDy,
                            nextDz,
                            advancedOpticsWeight,
                            ctx.spectralScratch0,
                            ctx.spectralRgb0
                        );
                        factorR = Math.max(0.05, ctx.spectralRgb0[0]);
                        factorG = Math.max(0.05, ctx.spectralRgb0[1]);
                        factorB = Math.max(0.05, ctx.spectralRgb0[2]);
                        } else {
                        double fresnelR = 1.0 - schlickFresnel(ndotv,
                            dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, -1.0));
                        double fresnelG = 1.0 - schlickFresnel(ndotv,
                            dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, 0.0));
                        double fresnelB = 1.0 - schlickFresnel(ndotv,
                            dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, 1.0));
                        double mediumTrR = mediumDirectionalTransmittance(ctx.surface, nextDx, nextDy, nextDz, ctx.surface.mediumR);
                        double mediumTrG = mediumDirectionalTransmittance(ctx.surface, nextDx, nextDy, nextDz, ctx.surface.mediumG);
                        double mediumTrB = mediumDirectionalTransmittance(ctx.surface, nextDx, nextDy, nextDz, ctx.surface.mediumB);
                        factorR = mediumTrR * Math.max(0.05, fresnelR);
                        factorG = mediumTrG * Math.max(0.05, fresnelG);
                        factorB = mediumTrB * Math.max(0.05, fresnelB);
                        }
                        throughputR *= factorR * branchRatio;
                        throughputG *= factorG * branchRatio;
                        throughputB *= factorB * branchRatio;
                    double causticPathGuide = resolveCausticPathGuidance(bounce, ctx.previewLastEventDelta, ctx.previewLastBsdfPdf);
                    causticCarry = Math.max(causticCarry, resolveCausticCarry(ctx.surface, advancedOpticsWeight, causticPathGuide));
                    ctx.previewLastEventDelta = true;
                    ctx.previewLastBsdfPdf = 0.0;
                    ox = ctx.hit.px + nextDx * RAY_EPS;
                    oy = ctx.hit.py + nextDy * RAY_EPS;
                    oz = ctx.hit.pz + nextDz * RAY_EPS;
                    dx = nextDx;
                    dy = nextDy;
                    dz = nextDz;
                    ctx.previewLastSurfacePx = ctx.hit.px;
                    ctx.previewLastSurfacePy = ctx.hit.py;
                    ctx.previewLastSurfacePz = ctx.hit.pz;
                    ctx.previewLastSurfaceValid = true;
                    if (bounce >= 2) {
                        double rr = Math.max(throughputR, Math.max(throughputG, throughputB));
                        rr = Math.max(0.05, Math.min(0.98, rr));
                        if (rng.nextDouble() > rr) {
                            break;
                        }
                        double inv = 1.0 / rr;
                        throughputR *= inv;
                        throughputG *= inv;
                        throughputB *= inv;
                    }
                    continue;
                }
            }

            if (branchPick < clearcoatThreshold && clearcoatProbability > 1e-6) {
                sampleGgxHalfVector(nx, ny, nz, roughnessToAlpha(ctx.surface.clearcoatRoughness), rng, ctx);
                double hx = ctx.sampleDx;
                double hy = ctx.sampleDy;
                double hz = ctx.sampleDz;
                double vDotH = Math.max(0.0, (-dx * hx + -dy * hy + -dz * hz));
                if (vDotH <= 1e-6) {
                    branchPick = clearcoatThreshold + 1e-3;
                } else {
                    nextDx = dx + 2.0 * vDotH * hx;
                    nextDy = dy + 2.0 * vDotH * hy;
                    nextDz = dz + 2.0 * vDotH * hz;
                    double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
                    nextDx *= invLen;
                    nextDy *= invLen;
                    nextDz *= invLen;
                        double invBranchPdf = fullStillTierActive
                            ? PathSampleRegularizer.boundedInverseProbability(clearcoatProbability)
                            : (1.0 / Math.max(1e-6, clearcoatProbability));
                    double coatScale = Math.max(0.04, clearcoatProbability);
                    throughputR *= coatScale * invBranchPdf;
                    throughputG *= coatScale * invBranchPdf;
                    throughputB *= coatScale * invBranchPdf;
                    ctx.previewLastEventDelta = ctx.surface.clearcoatRoughness <= 0.025;
                    ctx.previewLastBsdfPdf = referenceBsdfPdf(ctx.surface, ctx.referenceLobes, nextDx, nextDy, nextDz);
                    ox = ctx.hit.px + nextDx * RAY_EPS;
                    oy = ctx.hit.py + nextDy * RAY_EPS;
                    oz = ctx.hit.pz + nextDz * RAY_EPS;
                    dx = nextDx;
                    dy = nextDy;
                    dz = nextDz;
                    ctx.previewLastSurfacePx = ctx.hit.px;
                    ctx.previewLastSurfacePy = ctx.hit.py;
                    ctx.previewLastSurfacePz = ctx.hit.pz;
                    ctx.previewLastSurfaceValid = true;
                    if (bounce >= 2) {
                        double rr = Math.max(throughputR, Math.max(throughputG, throughputB));
                        rr = Math.max(0.05, Math.min(0.98, rr));
                        if (rng.nextDouble() > rr) {
                            break;
                        }
                        double inv = 1.0 / rr;
                        throughputR *= inv;
                        throughputG *= inv;
                        throughputB *= inv;
                    }
                    continue;
                }
            }

            if (branchPick < specularThreshold && specProbability > 1e-6) {
                if (fullStillTierActive) {
                    double hAlpha = roughnessToAlpha(effectiveRoughness);
                    sampleGgxHalfVector(nx, ny, nz, hAlpha, rng, ctx);
                    double hx = ctx.sampleDx;
                    double hy = ctx.sampleDy;
                    double hz = ctx.sampleDz;
                    double vDotH = (-dx) * hx + (-dy) * hy + (-dz) * hz;
                    if (vDotH <= 1e-6) {
                        double rDotN = dx * nx + dy * ny + dz * nz;
                        nextDx = dx - 2.0 * rDotN * nx;
                        nextDy = dy - 2.0 * rDotN * ny;
                        nextDz = dz - 2.0 * rDotN * nz;
                    } else {
                        nextDx = dx + 2.0 * vDotH * hx;
                        nextDy = dy + 2.0 * vDotH * hy;
                        nextDz = dz + 2.0 * vDotH * hz;
                    }
                    double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
                    nextDx *= invLen;
                    nextDy *= invLen;
                    nextDz *= invLen;

                    double invBranchPdf = PathSampleRegularizer.boundedInverseProbability(specProbability);
                    throughputR *= Math.max(0.02, ctx.surface.specR) * invBranchPdf;
                    throughputG *= Math.max(0.02, ctx.surface.specG) * invBranchPdf;
                    throughputB *= Math.max(0.02, ctx.surface.specB) * invBranchPdf;
                    ctx.previewLastEventDelta = effectiveRoughness <= 0.025;
                } else {
                    double rDotN = dx * nx + dy * ny + dz * nz;
                    nextDx = dx - 2.0 * rDotN * nx;
                    nextDy = dy - 2.0 * rDotN * ny;
                    nextDz = dz - 2.0 * rDotN * nz;

                    if (ctx.surface.roughness > 1e-4) {
                        sampleCosineHemisphere(nextDx, nextDy, nextDz, rng, ctx);
                        nextDx = lerp(nextDx, ctx.sampleDx, ctx.surface.roughness);
                        nextDy = lerp(nextDy, ctx.sampleDy, ctx.surface.roughness);
                        nextDz = lerp(nextDz, ctx.sampleDz, ctx.surface.roughness);
                    }
                    double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
                    nextDx *= invLen;
                    nextDy *= invLen;
                    nextDz *= invLen;

                    double invBranchPdf = 1.0 / Math.max(1e-6, specProbability);
                    throughputR *= Math.max(0.02, ctx.surface.specR) * invBranchPdf;
                    throughputG *= Math.max(0.02, ctx.surface.specG) * invBranchPdf;
                    throughputB *= Math.max(0.02, ctx.surface.specB) * invBranchPdf;
                    ctx.previewLastEventDelta = ctx.surface.roughness <= 0.025;
                }
            } else {
                sampleCosineHemisphere(nx, ny, nz, rng, ctx);
                nextDx = ctx.sampleDx;
                nextDy = ctx.sampleDy;
                nextDz = ctx.sampleDz;
                double invBranchPdf = fullStillTierActive
                    ? PathSampleRegularizer.boundedInverseProbability(diffuseProbability)
                    : (1.0 / Math.max(1e-6, diffuseProbability));
                throughputR *= baseR * invBranchPdf;
                throughputG *= baseG * invBranchPdf;
                throughputB *= baseB * invBranchPdf;
                ctx.previewLastEventDelta = false;
            }

            double throughputClamp = throughputClampScale(throughputR, throughputG, throughputB, bounce);
            if (throughputClamp < 1.0) {
                throughputR *= throughputClamp;
                throughputG *= throughputClamp;
                throughputB *= throughputClamp;
            }

            ctx.previewLastBsdfPdf = referenceBsdfPdf(ctx.surface, ctx.referenceLobes, nextDx, nextDy, nextDz);

            if (bounce >= 2) {
                double rr = Math.max(throughputR, Math.max(throughputG, throughputB));
                rr = Math.max(0.05, Math.min(0.98, rr));
                if (rng.nextDouble() > rr) {
                    break;
                }
                double inv = 1.0 / rr;
                throughputR *= inv;
                throughputG *= inv;
                throughputB *= inv;
            }

            ox = ctx.hit.px + nx * RAY_EPS;
            oy = ctx.hit.py + ny * RAY_EPS;
            oz = ctx.hit.pz + nz * RAY_EPS;
            dx = nextDx;
            dy = nextDy;
            dz = nextDz;
            ctx.previewLastSurfacePx = ctx.hit.px;
            ctx.previewLastSurfacePy = ctx.hit.py;
            ctx.previewLastSurfacePz = ctx.hit.pz;
            ctx.previewLastSurfaceValid = true;

            if (metrics != null) {
                metrics.pathBounceNanos.add((System.nanoTime() - bounceStart) * metricScale);
            }
        }

        markPrimaryGuideMiss(ctx);

        ctx.outR = radianceR;
        ctx.outG = radianceG;
        ctx.outB = radianceB;
    }

    double sampleDrivenFeatureWeight() {
        return sampleDrivenFeatureWeight;
    }

    boolean isGlobalVolumeEnabled() {
        return globalVolumeEnabled;
    }

    double globalVolumeDensity() {
        return globalVolumeDensity;
    }

    double globalVolumeAlbedoR() {
        return globalVolumeAlbedoR;
    }

    double globalVolumeAlbedoG() {
        return globalVolumeAlbedoG;
    }

    double globalVolumeAlbedoB() {
        return globalVolumeAlbedoB;
    }

    double globalVolumeEmissionR() {
        return globalVolumeEmissionR;
    }

    double globalVolumeEmissionG() {
        return globalVolumeEmissionG;
    }

    double globalVolumeEmissionB() {
        return globalVolumeEmissionB;
    }

    double globalVolumeMaxDistance() {
        return globalVolumeMaxDistance;
    }

    double globalVolumeAnisotropy() {
        return globalVolumeAnisotropy;
    }

    int spectralBandCount() {
        return SPECTRAL_BAND_COUNT;
    }

    int maxBounces() {
        return maxBounces;
    }

    double rayEps() {
        return RAY_EPS;
    }

    double infT() {
        return INF_T;
    }

    boolean isDirectLightingEnabled() {
        return directLighting;
    }

    void accumulateReferenceMiss(ReferencePathState path, int bounce, TraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            return;
        }
        sampleEnvironment(path.dx, path.dy, path.dz, ctx);
        double envR = ctx.envR;
        double envG = ctx.envG;
        double envB = ctx.envB;
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(envR, envG, envB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            envR = ctx.spectralRgb0[0];
            envG = ctx.spectralRgb0[1];
            envB = ctx.spectralRgb0[2];
        }
        if (directLighting && bounce > 0 && !path.lastEventDelta) {
            double lightPdf = referenceEnvironmentBackgroundPdf(path.dx, path.dy, path.dz);
            double misWeight = PathTracingSamplingSupport.powerHeuristic(path.lastBsdfPdf, lightPdf);
            envR *= misWeight;
            envG *= misWeight;
            envB *= misWeight;
        }
        envR *= path.throughputR;
        envG *= path.throughputG;
        envB *= path.throughputB;
        double envScale = contributionClampScale(envR, envG, envB, bounce == 0);
        path.radianceR += envR * envScale;
        path.radianceG += envG * envScale;
        path.radianceB += envB * envScale;
    }

    void populateReferenceSurfaceLobes(SurfaceState surface,
                                               double dx,
                                               double dy,
                                               double dz,
                                               ReferenceSurfaceLobes out) {
        out.viewX = -dx;
        out.viewY = -dy;
        out.viewZ = -dz;
        out.metallic = clamp01(surface.metallic);
        if (surface.presetConductor) {
            out.metallic = Math.max(0.92, out.metallic);
        }

        double ndotv = Math.max(0.0, surface.nx * out.viewX + surface.ny * out.viewY + surface.nz * out.viewZ);
        double fresnel = schlickFresnel(ndotv, surface.refractiveIndex);
        double clearcoatFactor = clamp01(surface.clearcoatFactor);
        double clearcoatAttenuation = 1.0 - clearcoatFactor * 0.25;
        double specularScale = clamp01(Math.max(surface.reflectivity, fresnel)) * clearcoatAttenuation;
        double diffuseScale = clamp01((1.0 - surface.transmission) * (1.0 - specularScale)) * clearcoatAttenuation;

        out.transmissionWeight = clamp01(surface.transmission * (1.0 - fresnel));
        out.specularRoughness = clamp01(surface.roughness);
        out.specularAlpha = roughnessToAlpha(surface.roughness);
        out.specularAlphaSq = out.specularAlpha * out.specularAlpha;
        out.specularEnergyComp = ggxMultipleScatterCompensation(
            out.specularRoughness,
            average3(surface.specR, surface.specG, surface.specB));
        out.conductorEta = surface.presetConductor ? surface.presetEta : Math.max(1.0, surface.refractiveIndex);
        out.conductorKR = surface.presetConductor
            ? Math.max(0.02, surface.presetKR)
            : resolveConductorExtinction(surface.specR, out.metallic);
        out.conductorKG = surface.presetConductor
            ? Math.max(0.02, surface.presetKG)
            : resolveConductorExtinction(surface.specG, out.metallic);
        out.conductorKB = surface.presetConductor
            ? Math.max(0.02, surface.presetKB)
            : resolveConductorExtinction(surface.specB, out.metallic);
        out.clearcoatRoughness = clamp01(surface.clearcoatRoughness);
        out.clearcoatAlpha = roughnessToAlpha(surface.clearcoatRoughness);
        out.clearcoatAlphaSq = out.clearcoatAlpha * out.clearcoatAlpha;
        out.clearcoatF0 = 0.04;

        out.diffuseR = surface.baseR * diffuseScale;
        out.diffuseG = surface.baseG * diffuseScale;
        out.diffuseB = surface.baseB * diffuseScale;
        out.specularR = surface.specR * specularScale;
        out.specularG = surface.specG * specularScale;
        out.specularB = surface.specB * specularScale;
        out.sheenR = surface.sheenR;
        out.sheenG = surface.sheenG;
        out.sheenB = surface.sheenB;

        out.diffuseBrdfR = out.diffuseR * INV_PI;
        out.diffuseBrdfG = out.diffuseG * INV_PI;
        out.diffuseBrdfB = out.diffuseB * INV_PI;
        out.sheenBrdfR = out.sheenR * INV_PI;
        out.sheenBrdfG = out.sheenG * INV_PI;
        out.sheenBrdfB = out.sheenB * INV_PI;

        out.mediumTrR = referenceMediumTransmittance(surface.mediumR, surface.density, surface.thickness);
        out.mediumTrG = referenceMediumTransmittance(surface.mediumG, surface.density, surface.thickness);
        out.mediumTrB = referenceMediumTransmittance(surface.mediumB, surface.density, surface.thickness);

        out.diffuseWeight = DenoiseSupport.luminance(out.diffuseR, out.diffuseG, out.diffuseB);
        out.specularWeight = DenoiseSupport.luminance(out.specularR, out.specularG, out.specularB);
        out.clearcoatWeight = clearcoatFactor * 0.25;
        out.sheenWeight = DenoiseSupport.luminance(out.sheenR, out.sheenG, out.sheenB) * 0.35;
        out.transmissionWeightRgb = out.transmissionWeight * average3(out.mediumTrR, out.mediumTrG, out.mediumTrB);
        out.totalWeight = out.diffuseWeight
            + out.specularWeight
            + out.clearcoatWeight
            + out.sheenWeight
            + out.transmissionWeightRgb;
    }

    void accumulateReferenceEmission(ReferencePathState path, Hit hit, SurfaceState surface, int bounce, TraceContext ctx) {
        double emissionR = path.throughputR * surface.emissionR;
        double emissionG = path.throughputG * surface.emissionG;
        double emissionB = path.throughputB * surface.emissionB;
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(emissionR, emissionG, emissionB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            emissionR = ctx.spectralRgb0[0];
            emissionG = ctx.spectralRgb0[1];
            emissionB = ctx.spectralRgb0[2];
        }
        double misWeight = 1.0;
        if (directLighting && bounce > 0 && !path.lastEventDelta) {
            double lightPdf = referenceEmissiveLightPdf(path, hit, path.dx, path.dy, path.dz);
            misWeight = PathTracingSamplingSupport.powerHeuristic(path.lastBsdfPdf, lightPdf);
            if (misWeight <= 1e-6) {
                return;
            }
        }
        double emissionScale = contributionClampScale(emissionR, emissionG, emissionB, bounce == 0);
        path.radianceR += emissionR * emissionScale * misWeight;
        path.radianceG += emissionG * emissionScale * misWeight;
        path.radianceB += emissionB * emissionScale * misWeight;
    }

    void accumulateReferenceDirectLighting(ReferencePathState path,
                                                   int bounce,
                                                   Hit hit,
                                                   SurfaceState surface,
                                                   ReferenceSurfaceLobes lobes,
                                                   SplitMix64 rng,
                                                   TraceContext ctx) {
        ctx.lightR = 0.0;
        ctx.lightG = 0.0;
        ctx.lightB = 0.0;

        accumulateReferenceDirectionalLights(hit, surface, lobes, ctx);
        accumulateReferencePointLights(hit, surface, lobes, rng, ctx);
        int envSamples = resolveReferenceEnvironmentSamples(bounce, surface, lobes);
        if (envSamples > 0) {
            double baseR = ctx.lightR;
            double baseG = ctx.lightG;
            double baseB = ctx.lightB;
            for (int i = 0; i < envSamples; i++) {
                sampleReferenceEnvironmentLighting(hit, surface, lobes, rng, ctx);
            }
            double invSamples = 1.0 / envSamples;
            ctx.lightR = baseR + (ctx.lightR - baseR) * invSamples;
            ctx.lightG = baseG + (ctx.lightG - baseG) * invSamples;
            ctx.lightB = baseB + (ctx.lightB - baseB) * invSamples;
        }
        int emissiveSamples = resolveReferenceEmissiveSamples(bounce, surface, lobes);
        if (emissiveSamples > 0) {
            double baseR = ctx.lightR;
            double baseG = ctx.lightG;
            double baseB = ctx.lightB;
            for (int i = 0; i < emissiveSamples; i++) {
                sampleReferenceEmissiveLighting(hit, surface, lobes, rng, ctx);
            }
            double invSamples = 1.0 / emissiveSamples;
            ctx.lightR = baseR + (ctx.lightR - baseR) * invSamples;
            ctx.lightG = baseG + (ctx.lightG - baseG) * invSamples;
            ctx.lightB = baseB + (ctx.lightB - baseB) * invSamples;
        }

        double directR = path.throughputR * ctx.lightR;
        double directG = path.throughputG * ctx.lightG;
        double directB = path.throughputB * ctx.lightB;
        double causticPathGuide = resolveCausticPathGuidance(bounce, path.lastEventDelta, path.lastBsdfPdf);
        double causticBoost = resolveCausticBoost(1.0, path.causticCarry, causticPathGuide, surface);
        directR *= causticBoost;
        directG *= causticBoost;
        directB *= causticBoost;
        double directScale = contributionClampScale(directR, directG, directB, bounce == 0);
        path.radianceR += directR * directScale;
        path.radianceG += directG * directScale;
        path.radianceB += directB * directScale;
    }

    private void accumulateReferenceDirectionalLights(Hit hit,
                                                      SurfaceState surface,
                                                      ReferenceSurfaceLobes lobes,
                                                      TraceContext ctx) {
        for (DirLightCache light : dirLights) {
            double nDotL = surface.nx * light.lx + surface.ny * light.ly + surface.nz * light.lz;
            if (nDotL <= 0.0) {
                continue;
            }
            prepareShadowRay(hit, surface, light.lx, light.ly, light.lz, ctx);
            if (intersectAny(
                    ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                    light.lx, light.ly, light.lz,
                    ctx.shadowTMin, INF_T, ctx)) {
                continue;
            }
            accumulateReferenceBsdfLight(surface, lobes, light.lx, light.ly, light.lz, light.r, light.g, light.b, nDotL, ctx);
        }
    }

    private void accumulateReferencePointLights(Hit hit,
                                                SurfaceState surface,
                                                ReferenceSurfaceLobes lobes,
                                                SplitMix64 rng,
                                                TraceContext ctx) {
        for (PointLightCache light : pointLights) {
            if (light.light instanceof AreaLight areaLight) {
                int areaSamples = resolveAreaShadowSamples(areaLight, false);
                double invAreaSamples = 1.0 / areaSamples;
                for (int sampleIndex = 0; sampleIndex < areaSamples; sampleIndex++) {
                    sampleAreaLightPosition(areaLight, rng, ctx.tmpVec0, ctx.tmpVec1, ctx.tmpVec2);
                    double lx = ctx.tmpVec0.x - hit.px;
                    double ly = ctx.tmpVec0.y - hit.py;
                    double lz = ctx.tmpVec0.z - hit.pz;
                    double distSq = lx * lx + ly * ly + lz * lz;
                    if (distSq < 1e-12) {
                        continue;
                    }

                    double dist = Math.sqrt(distSq);
                    double invDist = 1.0 / dist;
                    lx *= invDist;
                    ly *= invDist;
                    lz *= invDist;

                    double nDotL = surface.nx * lx + surface.ny * ly + surface.nz * lz;
                    if (nDotL <= 0.0) {
                        continue;
                    }

                    prepareShadowRay(hit, surface, lx, ly, lz, ctx);
                    if (intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            lx, ly, lz,
                            ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
                        continue;
                    }

                    double attenuation = areaLight.attenuation(dist)
 * areaLight.angularAttenuation(hit.px, hit.py, hit.pz);
                    if (attenuation <= 0.0) {
                        continue;
                    }

                    accumulateReferenceBsdfLight(
                            surface,
                            lobes,
                            lx,
                            ly,
                            lz,
                            light.r * attenuation * invAreaSamples,
                            light.g * attenuation * invAreaSamples,
                            light.b * attenuation * invAreaSamples,
                            nDotL,
                            ctx);
                }
                continue;
            }

            double lx = light.px - hit.px;
            double ly = light.py - hit.py;
            double lz = light.pz - hit.pz;
            double distSq = lx * lx + ly * ly + lz * lz;
            if (distSq < 1e-12) {
                continue;
            }

            double dist = Math.sqrt(distSq);
            double invDist = 1.0 / dist;
            lx *= invDist;
            ly *= invDist;
            lz *= invDist;

            double nDotL = surface.nx * lx + surface.ny * ly + surface.nz * lz;
            if (nDotL <= 0.0) {
                continue;
            }

            prepareShadowRay(hit, surface, lx, ly, lz, ctx);
            if (intersectAny(
                    ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                    lx, ly, lz,
                    ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
                continue;
            }

            double attenuation = light.light.attenuation(dist)
 * light.light.angularAttenuation(hit.px, hit.py, hit.pz);
            if (attenuation <= 0.0) {
                continue;
            }

            accumulateReferenceBsdfLight(
                    surface,
                    lobes,
                    lx,
                    ly,
                    lz,
                    light.r * attenuation,
                    light.g * attenuation,
                    light.b * attenuation,
                    nDotL,
                    ctx);
        }
    }

    private void sampleReferenceEnvironmentLighting(Hit hit,
                                                    SurfaceState surface,
                                                    ReferenceSurfaceLobes lobes,
                                                    SplitMix64 rng,
                                                    TraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            return;
        }

        double lightPdf = sampleEnvironmentBackgroundDirection(rng, ctx);
        double wiX = ctx.sampleDx;
        double wiY = ctx.sampleDy;
        double wiZ = ctx.sampleDz;
        if (lightPdf <= 1e-8) {
            return;
        }

        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return;
        }

        prepareShadowRay(hit, surface, wiX, wiY, wiZ, ctx);
        if (intersectAny(
                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                wiX, wiY, wiZ,
                ctx.shadowTMin, INF_T, ctx)) {
            return;
        }

        sampleEnvironmentBackground(wiX, wiY, wiZ, ctx);
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(ctx.envR, ctx.envG, ctx.envB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            ctx.envR = ctx.spectralRgb0[0];
            ctx.envG = ctx.spectralRgb0[1];
            ctx.envB = ctx.spectralRgb0[2];
        }
        if (DenoiseSupport.luminance(ctx.envR, ctx.envG, ctx.envB) <= 1e-8) {
            return;
        }

        double bsdfPdf = referenceBsdfPdf(surface, lobes, wiX, wiY, wiZ);
        double misWeight = PathTracingSamplingSupport.powerHeuristic(lightPdf, bsdfPdf);
        if (misWeight <= 1e-6) {
            return;
        }

        double lightScale = misWeight / lightPdf;
        accumulateReferenceBsdfLight(
                surface,
                lobes,
                wiX,
                wiY,
                wiZ,
                ctx.envR * lightScale,
                ctx.envG * lightScale,
                ctx.envB * lightScale,
                nDotL,
                ctx);
    }

    private void samplePreviewEnvironmentLighting(Hit hit,
                                                  SurfaceState surface,
                                                  ReferenceSurfaceLobes lobes,
                                                  SplitMix64 rng,
                                                  TraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            return;
        }

 // Získám náhodný směr ze kterého přijde světlo z prostředí.
 // Pro HDRI mapu to použije importance sampling, takže preferuje jasné pixely.
        double lightPdf = sampleEnvironmentBackgroundDirection(rng, ctx);
        double wiX = ctx.sampleDx;
        double wiY = ctx.sampleDy;
        double wiZ = ctx.sampleDz;
        if (lightPdf <= 1e-8) {
            return;
        }

        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return;
        }

 // Zkontroluju jestli není směr k vybranému světlu zablokovaný.
        prepareShadowRay(hit, surface, wiX, wiY, wiZ, ctx);
        if (intersectAny(
                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                wiX, wiY, wiZ,
                ctx.shadowTMin, INF_T, ctx)) {
            return;
        }

 // Získám barvu světla z prostředí pro daný směr.
        sampleEnvironmentBackground(wiX, wiY, wiZ, ctx);
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(ctx.envR, ctx.envG, ctx.envB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            ctx.envR = ctx.spectralRgb0[0];
            ctx.envG = ctx.spectralRgb0[1];
            ctx.envB = ctx.spectralRgb0[2];
        }
        if (DenoiseSupport.luminance(ctx.envR, ctx.envG, ctx.envB) <= 1e-8) {
            return;
        }

        double bsdfPdf = referenceBsdfPdf(surface, lobes, wiX, wiY, wiZ);
        double misWeight = PathTracingSamplingSupport.powerHeuristic(lightPdf, bsdfPdf);
        if (misWeight <= 1e-6) {
            return;
        }

 // Vynásobím světlo BRDF funkcí a pak vydělím PDF, abych získal správný příspěvek.
        double lightScale = misWeight / lightPdf;
        accumulatePreviewBsdfLight(
                surface,
                -ctx.rayDx, -ctx.rayDy, -ctx.rayDz,
                wiX, wiY, wiZ,
                ctx.envR * lightScale,
                ctx.envG * lightScale,
                ctx.envB * lightScale,
                nDotL,
                ctx);
    }

    private void accumulateReferenceBsdfLight(SurfaceState surface,
                                              ReferenceSurfaceLobes lobes,
                                              double lightX,
                                              double lightY,
                                              double lightZ,
                                              double lightR,
                                              double lightG,
                                              double lightB,
                                              double nDotL,
                                              TraceContext ctx) {
        double nDotV = Math.max(1e-6, surface.nx * lobes.viewX + surface.ny * lobes.viewY + surface.nz * lobes.viewZ);
        double hx = lightX + lobes.viewX;
        double hy = lightY + lobes.viewY;
        double hz = lightZ + lobes.viewZ;
        double hLenSq = hx * hx + hy * hy + hz * hz;
        double specularTerm = 0.0;
        double fresnelR = 0.0;
        double fresnelG = 0.0;
        double fresnelB = 0.0;
        if (hLenSq > 1e-12) {
            double invH = 1.0 / Math.sqrt(hLenSq);
            hx *= invH;
            hy *= invH;
            hz *= invH;
            double nDotH = Math.max(0.0, surface.nx * hx + surface.ny * hy + surface.nz * hz);
            double vDotH = Math.max(0.0, lobes.viewX * hx + lobes.viewY * hy + lobes.viewZ * hz);
            specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, lobes.specularAlphaSq, lobes.specularRoughness)
 * lobes.specularEnergyComp;
                if (isPreviewStillFullTierActive()) {
                    spectralLayeredFresnelRgb(
                            vDotH,
                            average3(lobes.specularR, lobes.specularG, lobes.specularB),
                            lobes.metallic,
                            lobes.conductorEta,
                            lobes.conductorKR,
                            lobes.conductorKG,
                            lobes.conductorKB,
                            ctx.spectralScratch0,
                            ctx.spectralRgb0
                    );
                    fresnelR = ctx.spectralRgb0[0];
                    fresnelG = ctx.spectralRgb0[1];
                    fresnelB = ctx.spectralRgb0[2];
                } else {
                    fresnelR = layeredSpecularFresnel(vDotH, lobes.specularR, lobes.metallic, lobes.conductorEta, lobes.conductorKR);
                    fresnelG = layeredSpecularFresnel(vDotH, lobes.specularG, lobes.metallic, lobes.conductorEta, lobes.conductorKG);
                    fresnelB = layeredSpecularFresnel(vDotH, lobes.specularB, lobes.metallic, lobes.conductorEta, lobes.conductorKB);
                }
        }
        double clearcoatTerm = 0.0;
        double clearcoatFresnel = 0.0;
        if (hLenSq > 1e-12 && lobes.clearcoatWeight > 1e-8) {
            double vDotH = Math.max(0.0, lobes.viewX * hx + lobes.viewY * hy + lobes.viewZ * hz);
                clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, Math.max(0.0, surface.nx * hx + surface.ny * hy + surface.nz * hz),
                    lobes.clearcoatAlphaSq, lobes.clearcoatRoughness);
            clearcoatFresnel = schlickFresnelColor(vDotH, lobes.clearcoatF0);
        }

        double sheenTerm = 0.0;
        if (hLenSq > 1e-12 && lobes.sheenWeight > 1e-8) {
            double vDotH = Math.max(0.0, lobes.viewX * hx + lobes.viewY * hy + lobes.viewZ * hz);
            sheenTerm = sheenLobeTerm(vDotH, surface.sheenRoughness);
        }

        double bsdfR = lobes.diffuseBrdfR
            + specularTerm * fresnelR
            + clearcoatTerm * clearcoatFresnel
            + lobes.sheenBrdfR * sheenTerm;
        double bsdfG = lobes.diffuseBrdfG
            + specularTerm * fresnelG
            + clearcoatTerm * clearcoatFresnel
            + lobes.sheenBrdfG * sheenTerm;
        double bsdfB = lobes.diffuseBrdfB
            + specularTerm * fresnelB
            + clearcoatTerm * clearcoatFresnel
            + lobes.sheenBrdfB * sheenTerm;
        double sssWeight = estimateSubsurfaceContribution(surface, lobes.viewX, lobes.viewY, lobes.viewZ, lightX, lightY, lightZ);
        if (sssWeight > 1e-6) {
            double sssR = mix(surface.baseR, surface.mediumR, 0.55) * sssWeight * INV_PI;
            double sssG = mix(surface.baseG, surface.mediumG, 0.55) * sssWeight * INV_PI;
            double sssB = mix(surface.baseB, surface.mediumB, 0.55) * sssWeight * INV_PI;
            bsdfR += sssR;
            bsdfG += sssG;
            bsdfB += sssB;
        }
        ctx.lightR += lightR * nDotL * bsdfR;
        ctx.lightG += lightG * nDotL * bsdfG;
        ctx.lightB += lightB * nDotL * bsdfB;
    }

    private void accumulatePreviewBsdfLight(SurfaceState surface,
                                            double viewX, double viewY, double viewZ,
                                            double lightX, double lightY, double lightZ,
                                            double lightR, double lightG, double lightB,
                                            double nDotL,
                                            TraceContext ctx) {
        boolean advancedPreviewOptics = isPreviewStillFullTierActive();

        double ndotv = Math.max(0.0, surface.nx * viewX + surface.ny * viewY + surface.nz * viewZ);
        double fresnel = schlickFresnel(ndotv, surface.refractiveIndex);
        double clearcoatFactor = clamp01(surface.clearcoatFactor);
        double clearcoatAttenuation = 1.0 - clearcoatFactor * 0.25;
        double specularScale = clamp01(Math.max(surface.reflectivity, fresnel)) * clearcoatAttenuation;
        double diffuseScale = clamp01((1.0 - surface.transmission) * (1.0 - specularScale)) * clearcoatAttenuation;

        double diffuseR = surface.baseR * diffuseScale;
        double diffuseG = surface.baseG * diffuseScale;
        double diffuseB = surface.baseB * diffuseScale;

        double hx = lightX + viewX;
        double hy = lightY + viewY;
        double hz = lightZ + viewZ;
        double hLenSq = hx * hx + hy * hy + hz * hz;
        double specularTerm = 0.0;
        double fresnelR = 0.0;
        double fresnelG = 0.0;
        double fresnelB = 0.0;
        double clearcoatTerm = 0.0;
        double clearcoatFresnel = 0.0;
        double sheenTerm = 0.0;
        if (hLenSq > 1e-12) {
            double invH = 1.0 / Math.sqrt(hLenSq);
            hx *= invH;
            hy *= invH;
            hz *= invH;
            double nDotH = Math.max(0.0, surface.nx * hx + surface.ny * hy + surface.nz * hz);
            double vDotH = Math.max(0.0, viewX * hx + viewY * hy + viewZ * hz);
            double roughnessForG = clamp01(surface.roughness);
            double alpha = roughnessToAlpha(surface.roughness);
            double alphaSq = alpha * alpha;
            double specularEnergyComp = advancedPreviewOptics
                    ? ggxMultipleScatterCompensation(surface.roughness, average3(surface.specR, surface.specG, surface.specB))
                    : 1.0;
            double conductorEta = surface.presetConductor ? surface.presetEta : Math.max(1.0, surface.refractiveIndex);
            double metallic = clamp01(surface.metallic);
            if (surface.presetConductor) {
                metallic = Math.max(0.92, metallic);
            }
            specularTerm = ggxSpecularTerm(ndotv, nDotL, nDotH, alphaSq, roughnessForG) * specularEnergyComp;
            if (advancedPreviewOptics) {
                double conductorKR = surface.presetConductor ? Math.max(0.02, surface.presetKR) : resolveConductorExtinction(surface.specR, metallic);
                double conductorKG = surface.presetConductor ? Math.max(0.02, surface.presetKG) : resolveConductorExtinction(surface.specG, metallic);
                double conductorKB = surface.presetConductor ? Math.max(0.02, surface.presetKB) : resolveConductorExtinction(surface.specB, metallic);
                spectralLayeredFresnelRgb(
                        vDotH,
                        average3(surface.specR, surface.specG, surface.specB),
                        metallic,
                        conductorEta,
                        conductorKR,
                        conductorKG,
                        conductorKB,
                        ctx.spectralScratch0,
                        ctx.spectralRgb0
                );
                fresnelR = ctx.spectralRgb0[0];
                fresnelG = ctx.spectralRgb0[1];
                fresnelB = ctx.spectralRgb0[2];
            } else {
                fresnelR = schlickFresnelColor(vDotH, surface.specR);
                fresnelG = schlickFresnelColor(vDotH, surface.specG);
                fresnelB = schlickFresnelColor(vDotH, surface.specB);
            }
            if (clearcoatFactor > 1e-6) {
                double coatRoughness = clamp01(surface.clearcoatRoughness);
                double coatAlpha = roughnessToAlpha(surface.clearcoatRoughness);
                double coatAlphaSq = coatAlpha * coatAlpha;
                clearcoatTerm = ggxSpecularTerm(ndotv, nDotL, nDotH, coatAlphaSq, coatRoughness);
                clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
            }
            if (surface.sheenR + surface.sheenG + surface.sheenB > 1e-6) {
                sheenTerm = sheenLobeTerm(vDotH, surface.sheenRoughness);
            }
        }

        ctx.lightR += lightR * nDotL * (diffuseR * INV_PI
                + specularTerm * fresnelR
                + clearcoatTerm * clearcoatFresnel
                + surface.sheenR * INV_PI * sheenTerm);
        ctx.lightG += lightG * nDotL * (diffuseG * INV_PI
                + specularTerm * fresnelG
                + clearcoatTerm * clearcoatFresnel
                + surface.sheenG * INV_PI * sheenTerm);
        ctx.lightB += lightB * nDotL * (diffuseB * INV_PI
                + specularTerm * fresnelB
                + clearcoatTerm * clearcoatFresnel
                + surface.sheenB * INV_PI * sheenTerm);

        double sssWeight = advancedPreviewOptics
            ? estimateSubsurfaceContribution(surface, viewX, viewY, viewZ, lightX, lightY, lightZ)
            : 0.0;
        if (sssWeight > 1e-6) {
            double sssR = mix(surface.baseR, surface.mediumR, 0.55) * sssWeight * INV_PI;
            double sssG = mix(surface.baseG, surface.mediumG, 0.55) * sssWeight * INV_PI;
            double sssB = mix(surface.baseB, surface.mediumB, 0.55) * sssWeight * INV_PI;
            ctx.lightR += lightR * nDotL * sssR;
            ctx.lightG += lightG * nDotL * sssG;
            ctx.lightB += lightB * nDotL * sssB;
        }
    }

    private void sampleReferenceEmissiveLighting(Hit hit,
                                                 SurfaceState surface,
                                                 ReferenceSurfaceLobes lobes,
                                                 SplitMix64 rng,
                                                 TraceContext ctx) {
        if (emissiveLights.length == 0 || emissiveLightPowerSum <= 1e-8) {
            return;
        }

        EmissiveTriangleLight light = sampleEmissiveLight(rng.nextDouble() * emissiveLightPowerSum);
        if (light == null || light.triangle == null) {
            return;
        }

        double baryU = rng.nextDouble();
        double baryV = rng.nextDouble();
        double sqrtU = Math.sqrt(baryU);
        double w0 = 1.0 - sqrtU;
        double w1 = baryV * sqrtU;
        double w2 = 1.0 - w0 - w1;

        Triangle tri = light.triangle;
        double sampleX = tri.ax * w0 + tri.bx * w1 + tri.cx * w2;
        double sampleY = tri.ay * w0 + tri.by * w1 + tri.cy * w2;
        double sampleZ = tri.az * w0 + tri.bz * w1 + tri.cz * w2;

        double toLightX = sampleX - hit.px;
        double toLightY = sampleY - hit.py;
        double toLightZ = sampleZ - hit.pz;
        double distSq = toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ;
        if (distSq <= 1e-10) {
            return;
        }

        double dist = Math.sqrt(distSq);
        double invDist = 1.0 / dist;
        double wiX = toLightX * invDist;
        double wiY = toLightY * invDist;
        double wiZ = toLightZ * invDist;

        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return;
        }

        double lightFacing = -(tri.faceNx * wiX + tri.faceNy * wiY + tri.faceNz * wiZ);
        if (light.doubleSided) {
            lightFacing = Math.abs(lightFacing);
        }
        if (lightFacing <= 1e-6) {
            return;
        }

        prepareShadowRay(hit, surface, wiX, wiY, wiZ, ctx);
        if (intersectAny(
                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                wiX, wiY, wiZ,
                ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
            return;
        }

        ctx.tempHit.t = dist;
        ctx.tempHit.u = w1;
        ctx.tempHit.v = w2;
        ctx.tempHit.px = sampleX;
        ctx.tempHit.py = sampleY;
        ctx.tempHit.pz = sampleZ;
        ctx.tempHit.triangle = tri;
        sampleSurface(tri, ctx.tempHit, wiX, wiY, wiZ, ctx.shadowSurface, ctx);
        if (ctx.shadowSurface.discard) {
            return;
        }

        double emissionR = Math.max(0.0, ctx.shadowSurface.emissionR);
        double emissionG = Math.max(0.0, ctx.shadowSurface.emissionG);
        double emissionB = Math.max(0.0, ctx.shadowSurface.emissionB);
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(emissionR, emissionG, emissionB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            emissionR = ctx.spectralRgb0[0];
            emissionG = ctx.spectralRgb0[1];
            emissionB = ctx.spectralRgb0[2];
        }
        if (DenoiseSupport.luminance(emissionR, emissionG, emissionB) <= 1e-8) {
            return;
        }

        double pickPdf = light.power / Math.max(1e-8, emissiveLightPowerSum);
        double pdfArea = pickPdf / Math.max(1e-10, tri.area);
        double pdfSolid = pdfArea * distSq / Math.max(1e-6, lightFacing);
        if (pdfSolid <= 1e-8) {
            return;
        }

        double bsdfPdf = referenceBsdfPdf(surface, lobes, wiX, wiY, wiZ);
        double misWeight = PathTracingSamplingSupport.powerHeuristic(pdfSolid, bsdfPdf);
        if (misWeight <= 1e-6) {
            return;
        }

        accumulateReferenceBsdfLight(
                surface,
                lobes,
                wiX,
                wiY,
                wiZ,
                emissionR * misWeight / pdfSolid,
                emissionG * misWeight / pdfSolid,
                emissionB * misWeight / pdfSolid,
                nDotL,
                ctx);
    }

    private void samplePreviewEmissiveLighting(Hit hit,
                                               SurfaceState surface,
                                               ReferenceSurfaceLobes lobes,
                                               SplitMix64 rng,
                                               TraceContext ctx) {
        if (emissiveLights.length == 0 || emissiveLightPowerSum <= 1e-8) {
            return;
        }

        EmissiveTriangleLight light = sampleEmissiveLight(rng.nextDouble() * emissiveLightPowerSum);
        if (light == null || light.triangle == null) {
            return;
        }

        double baryU = rng.nextDouble();
        double baryV = rng.nextDouble();
        double sqrtU = Math.sqrt(baryU);
        double w0 = 1.0 - sqrtU;
        double w1 = baryV * sqrtU;
        double w2 = 1.0 - w0 - w1;

        Triangle tri = light.triangle;
        double sampleX = tri.ax * w0 + tri.bx * w1 + tri.cx * w2;
        double sampleY = tri.ay * w0 + tri.by * w1 + tri.cy * w2;
        double sampleZ = tri.az * w0 + tri.bz * w1 + tri.cz * w2;

        double toLightX = sampleX - hit.px;
        double toLightY = sampleY - hit.py;
        double toLightZ = sampleZ - hit.pz;
        double distSq = toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ;
        if (distSq <= 1e-10) {
            return;
        }

        double dist = Math.sqrt(distSq);
        double invDist = 1.0 / dist;
        double wiX = toLightX * invDist;
        double wiY = toLightY * invDist;
        double wiZ = toLightZ * invDist;

        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return;
        }

        double lightFacing = -(tri.faceNx * wiX + tri.faceNy * wiY + tri.faceNz * wiZ);
        if (light.doubleSided) {
            lightFacing = Math.abs(lightFacing);
        }
        if (lightFacing <= 1e-6) {
            return;
        }

        prepareShadowRay(hit, surface, wiX, wiY, wiZ, ctx);
        if (intersectAny(
                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                wiX, wiY, wiZ,
                ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
            return;
        }

        ctx.tempHit.t = dist;
        ctx.tempHit.u = w1;
        ctx.tempHit.v = w2;
        ctx.tempHit.px = sampleX;
        ctx.tempHit.py = sampleY;
        ctx.tempHit.pz = sampleZ;
        ctx.tempHit.triangle = tri;
        sampleSurface(tri, ctx.tempHit, wiX, wiY, wiZ, ctx.shadowSurface, ctx);
        if (ctx.shadowSurface.discard) {
            return;
        }

        double emissionR = Math.max(0.0, ctx.shadowSurface.emissionR);
        double emissionG = Math.max(0.0, ctx.shadowSurface.emissionG);
        double emissionB = Math.max(0.0, ctx.shadowSurface.emissionB);
        if (ctx.spectralHeroBand >= 0) {
            spectralHeroProjectRgb(emissionR, emissionG, emissionB, ctx.spectralHeroBand, ctx.spectralCompanionBand, ctx.spectralScratch0, ctx.spectralRgb0);
            emissionR = ctx.spectralRgb0[0];
            emissionG = ctx.spectralRgb0[1];
            emissionB = ctx.spectralRgb0[2];
        }
        if (DenoiseSupport.luminance(emissionR, emissionG, emissionB) <= 1e-8) {
            return;
        }

        double pickPdf = light.power / Math.max(1e-8, emissiveLightPowerSum);
        double pdfArea = pickPdf / Math.max(1e-10, tri.area);
        double pdfSolid = pdfArea * distSq / Math.max(1e-6, lightFacing);
        if (pdfSolid <= 1e-8) {
            return;
        }

        double bsdfPdf = referenceBsdfPdf(surface, lobes, wiX, wiY, wiZ);
        double misWeight = PathTracingSamplingSupport.powerHeuristic(pdfSolid, bsdfPdf);
        if (misWeight <= 1e-6) {
            return;
        }

        accumulatePreviewBsdfLight(
                surface,
                -ctx.rayDx, -ctx.rayDy, -ctx.rayDz,
                wiX, wiY, wiZ,
                emissionR * misWeight / pdfSolid,
                emissionG * misWeight / pdfSolid,
                emissionB * misWeight / pdfSolid,
                nDotL,
                ctx);
    }

    private double previewEmissiveLightPdf(Hit hit, double wiX, double wiY, double wiZ, TraceContext ctx) {
        if (hit == null
                || hit.triangle == null
                || !ctx.previewLastSurfaceValid
                || emissiveLightPowerSum <= 1e-8) {
            return 0.0;
        }

        Triangle tri = hit.triangle;
        if (tri.emissiveLightPower <= 1e-8 || tri.area <= 1e-10) {
            return 0.0;
        }

        double lightFacing = -(tri.faceNx * wiX + tri.faceNy * wiY + tri.faceNz * wiZ);
        if (tri.material != null && tri.material.isDoubleSided()) {
            lightFacing = Math.abs(lightFacing);
        }
        if (lightFacing <= 1e-6) {
            return 0.0;
        }

        double toLightX = hit.px - ctx.previewLastSurfacePx;
        double toLightY = hit.py - ctx.previewLastSurfacePy;
        double toLightZ = hit.pz - ctx.previewLastSurfacePz;
        double distSq = toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ;
        if (distSq <= 1e-10) {
            return 0.0;
        }

        double pickPdf = tri.emissiveLightPower / Math.max(1e-8, emissiveLightPowerSum);
        double pdfArea = pickPdf / Math.max(1e-10, tri.area);
        return pdfArea * distSq / Math.max(1e-6, lightFacing);
    }

    private double referenceBsdfPdf(SurfaceState surface,
                                    ReferenceSurfaceLobes lobes,
                                    double wiX,
                                    double wiY,
                                    double wiZ) {
        if (lobes.totalWeight <= 1e-8) {
            return 0.0;
        }

        double pdf = 0.0;
        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (lobes.diffuseWeight > 1e-8 && nDotL > 1e-6) {
            pdf += (lobes.diffuseWeight / lobes.totalWeight)
 * PathTracingSamplingSupport.cosineHemispherePdf(nDotL);
        }
        if (lobes.sheenWeight > 1e-8 && nDotL > 1e-6) {
            pdf += (lobes.sheenWeight / lobes.totalWeight)
 * PathTracingSamplingSupport.cosineHemispherePdf(nDotL);
        }
        if (lobes.specularWeight > 1e-8) {
            pdf += (lobes.specularWeight / lobes.totalWeight)
 * referenceSpecularPdf(surface, lobes, wiX, wiY, wiZ);
        }
        if (lobes.clearcoatWeight > 1e-8) {
            pdf += (lobes.clearcoatWeight / lobes.totalWeight)
 * referenceClearcoatPdf(surface, lobes, wiX, wiY, wiZ);
        }
        return pdf;
    }

    private double referenceClearcoatPdf(SurfaceState surface,
                                         ReferenceSurfaceLobes lobes,
                                         double wiX,
                                         double wiY,
                                         double wiZ) {
        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return 0.0;
        }
        double hx = wiX + lobes.viewX;
        double hy = wiY + lobes.viewY;
        double hz = wiZ + lobes.viewZ;
        double hLenSq = hx * hx + hy * hy + hz * hz;
        if (hLenSq <= 1e-12) {
            return 0.0;
        }
        double invH = 1.0 / Math.sqrt(hLenSq);
        hx *= invH;
        hy *= invH;
        hz *= invH;
        double nDotH = Math.max(0.0, surface.nx * hx + surface.ny * hy + surface.nz * hz);
        double vDotH = Math.max(0.0, lobes.viewX * hx + lobes.viewY * hy + lobes.viewZ * hz);
        if (nDotH <= 1e-6 || vDotH <= 1e-6) {
            return 0.0;
        }
        double d = ggxDistribution(nDotH, lobes.clearcoatAlphaSq);
        return d * nDotH / Math.max(1e-8, 4.0 * vDotH);
    }

    private double referenceSpecularPdf(SurfaceState surface,
                                        ReferenceSurfaceLobes lobes,
                                        double wiX,
                                        double wiY,
                                        double wiZ) {
        double nDotL = surface.nx * wiX + surface.ny * wiY + surface.nz * wiZ;
        if (nDotL <= 1e-6) {
            return 0.0;
        }
        double hx = wiX + lobes.viewX;
        double hy = wiY + lobes.viewY;
        double hz = wiZ + lobes.viewZ;
        double hLenSq = hx * hx + hy * hy + hz * hz;
        if (hLenSq <= 1e-12) {
            return 0.0;
        }
        double invH = 1.0 / Math.sqrt(hLenSq);
        hx *= invH;
        hy *= invH;
        hz *= invH;
        double nDotH = Math.max(0.0, surface.nx * hx + surface.ny * hy + surface.nz * hz);
        double vDotH = Math.max(0.0, lobes.viewX * hx + lobes.viewY * hy + lobes.viewZ * hz);
        if (nDotH <= 1e-6 || vDotH <= 1e-6) {
            return 0.0;
        }
        double d = ggxDistribution(nDotH, lobes.specularAlphaSq);
        return d * nDotH / Math.max(1e-8, 4.0 * vDotH);
    }

    private double referenceEmissiveLightPdf(ReferencePathState path,
                                             Hit hit,
                                             double wiX,
                                             double wiY,
                                             double wiZ) {
        if (path == null
                || !path.lastSurfaceValid
                || hit == null
                || hit.triangle == null
                || emissiveLightPowerSum <= 1e-8) {
            return 0.0;
        }

        Triangle tri = hit.triangle;
        if (tri.emissiveLightPower <= 1e-8 || tri.area <= 1e-10) {
            return 0.0;
        }

        double lightFacing = -(tri.faceNx * wiX + tri.faceNy * wiY + tri.faceNz * wiZ);
        if (tri.material != null && tri.material.isDoubleSided()) {
            lightFacing = Math.abs(lightFacing);
        }
        if (lightFacing <= 1e-6) {
            return 0.0;
        }

        double toLightX = hit.px - path.lastSurfacePx;
        double toLightY = hit.py - path.lastSurfacePy;
        double toLightZ = hit.pz - path.lastSurfacePz;
        double distSq = toLightX * toLightX + toLightY * toLightY + toLightZ * toLightZ;
        if (distSq <= 1e-10) {
            return 0.0;
        }

        double pickPdf = tri.emissiveLightPower / Math.max(1e-8, emissiveLightPowerSum);
        double pdfArea = pickPdf / Math.max(1e-10, tri.area);
        return pdfArea * distSq / Math.max(1e-6, lightFacing);
    }

    boolean sampleReferenceNextBounce(SurfaceState surface,
                                              ReferencePathState path,
                                              ReferenceSurfaceLobes lobes,
                                              SplitMix64 rng,
                                              ReferenceBounceSample out,
                                              TraceContext ctx) {
        if (lobes.totalWeight <= 1e-8) {
            return false;
        }

        double branchPick = rng.nextDouble() * lobes.totalWeight;
        if (branchPick < lobes.transmissionWeightRgb && lobes.transmissionWeightRgb > 1e-8) {
            if (trySampleReferenceTransmissionBounce(surface, path, lobes, rng, out, ctx)) {
                return true;
            }
            double surfaceWeight = lobes.diffuseWeight
                    + lobes.specularWeight
                    + lobes.clearcoatWeight
                    + lobes.sheenWeight;
            if (surfaceWeight <= 1e-8) {
                return false;
            }
            branchPick = rng.nextDouble() * surfaceWeight;
        } else {
            branchPick = Math.max(0.0, branchPick - lobes.transmissionWeightRgb);
        }

        if (branchPick < lobes.clearcoatWeight && lobes.clearcoatWeight > 1e-8) {
            sampleReferenceClearcoatBounce(surface, path, lobes, rng, out, ctx);
            return true;
        }
        branchPick = Math.max(0.0, branchPick - lobes.clearcoatWeight);

        if (branchPick < lobes.specularWeight && lobes.specularWeight > 1e-8) {
            sampleReferenceSpecularBounce(surface, path, lobes, rng, out, ctx);
            return true;
        }
        branchPick = Math.max(0.0, branchPick - lobes.specularWeight);
        if (branchPick < lobes.sheenWeight && lobes.sheenWeight > 1e-8) {
            sampleReferenceSheenBounce(surface, path, lobes, rng, out, ctx);
            return true;
        }
        if (lobes.diffuseWeight <= 1e-8) {
            return false;
        }
        sampleReferenceDiffuseBounce(surface, path, lobes, rng, out, ctx);
        return true;
    }

    private void sampleReferenceClearcoatBounce(SurfaceState surface,
                                                ReferencePathState path,
                                                ReferenceSurfaceLobes lobes,
                                                SplitMix64 rng,
                                                ReferenceBounceSample out,
                                                TraceContext ctx) {
        sampleGgxHalfVector(surface.nx, surface.ny, surface.nz, lobes.clearcoatAlpha, rng, ctx);
        double hx = ctx.sampleDx;
        double hy = ctx.sampleDy;
        double hz = ctx.sampleDz;

        double viewX = lobes.viewX;
        double viewY = lobes.viewY;
        double viewZ = lobes.viewZ;
        double vDotH = viewX * hx + viewY * hy + viewZ * hz;
        if (vDotH <= 1e-6) {
            out.nextDx = surface.nx;
            out.nextDy = surface.ny;
            out.nextDz = surface.nz;
            out.nextEventDelta = false;
            out.bsdfPdf = 0.0;
            return;
        }

        double nextX = -viewX + 2.0 * vDotH * hx;
        double nextY = -viewY + 2.0 * vDotH * hy;
        double nextZ = -viewZ + 2.0 * vDotH * hz;
        double nextLenSq = nextX * nextX + nextY * nextY + nextZ * nextZ;
        if (nextLenSq > 1e-12) {
            double inv = 1.0 / Math.sqrt(nextLenSq);
            nextX *= inv;
            nextY *= inv;
            nextZ *= inv;
        }

        out.nextDx = nextX;
        out.nextDy = nextY;
        out.nextDz = nextZ;
        out.nextEventDelta = surface.clearcoatRoughness <= 0.025;

        double nDotNext = Math.max(1e-6, surface.nx * out.nextDx + surface.ny * out.nextDy + surface.nz * out.nextDz);
        double branchPdf = lobes.clearcoatWeight / lobes.totalWeight;
        double specPdf = referenceClearcoatPdf(surface, lobes, out.nextDx, out.nextDy, out.nextDz);
        out.bsdfPdf = Math.max(0.0, branchPdf * specPdf);

        if (specPdf <= 1e-12) {
            path.throughputR = 0.0;
            path.throughputG = 0.0;
            path.throughputB = 0.0;
            return;
        }

        double nDotV = Math.max(1e-6, surface.nx * viewX + surface.ny * viewY + surface.nz * viewZ);
        double nDotH = Math.max(1e-6, surface.nx * hx + surface.ny * hy + surface.nz * hz);
        double specularTerm = ggxSpecularTerm(nDotV, nDotNext, nDotH, lobes.clearcoatAlphaSq, lobes.clearcoatRoughness);
        double fresnel = schlickFresnelColor(vDotH, lobes.clearcoatF0);

        double scale = nDotNext / Math.max(1e-8, branchPdf * specPdf);
        double value = Math.max(0.0, specularTerm * fresnel) * scale;
        path.throughputR *= value;
        path.throughputG *= value;
        path.throughputB *= value;
    }

    private boolean trySampleReferenceTransmissionBounce(SurfaceState surface,
                                                         ReferencePathState path,
                                                         ReferenceSurfaceLobes lobes,
                                                         SplitMix64 rng,
                                                         ReferenceBounceSample out,
                                                         TraceContext ctx) {
        sampleGgxHalfVector(surface.nx, surface.ny, surface.nz, lobes.specularAlpha, rng, ctx);
        Vec3 microNormal = ctx.tmpVec1.set(ctx.sampleDx, ctx.sampleDy, ctx.sampleDz);
        double transmissionIor = resolveTransmissionIor(surface.refractiveIndex, surface.dispersion, 1.0, rng.nextDouble());
        Vec3 refracted = ctx.tmpVec0.set(path.dx, path.dy, path.dz)
            .refract(microNormal, transmissionIor, ctx.tmpVec0);
        if (refracted.lengthSquared() < 1e-10) {
            return false;
        }

        out.nextDx = refracted.x;
        out.nextDy = refracted.y;
        out.nextDz = refracted.z;
        out.nextEventDelta = true;
        out.bsdfPdf = 0.0;

        double viewX = lobes.viewX;
        double viewY = lobes.viewY;
        double viewZ = lobes.viewZ;
        double nDotV = Math.max(1e-6, Math.abs(surface.nx * viewX + surface.ny * viewY + surface.nz * viewZ));
        double nDotL = Math.max(1e-6, Math.abs(surface.nx * out.nextDx + surface.ny * out.nextDy + surface.nz * out.nextDz));
        double nDotH = Math.max(1e-6, Math.abs(surface.nx * microNormal.x + surface.ny * microNormal.y + surface.nz * microNormal.z));
        double vDotH = Math.max(1e-6, Math.abs(viewX * microNormal.x + viewY * microNormal.y + viewZ * microNormal.z));
        double lDotH = Math.max(1e-6, Math.abs(out.nextDx * microNormal.x + out.nextDy * microNormal.y + out.nextDz * microNormal.z));

        double eta = (surface.nx * viewX + surface.ny * viewY + surface.nz * viewZ) > 0.0
            ? 1.0 / surface.refractiveIndex
            : surface.refractiveIndex;
        double denom = eta * vDotH + lDotH;
        double d = ggxDistribution(nDotH, lobes.specularAlphaSq);
        double g = ggxSmithG(nDotV, nDotL, lobes.specularRoughness);
        double transmissionFresnel = 1.0 - schlickFresnel(vDotH, surface.refractiveIndex);
        double specularTerm = (d * g * eta * eta * vDotH * lDotH)
            / Math.max(1e-8, nDotV * nDotL * denom * denom);
        specularTerm *= transmissionFresnel;

        double pdf = d * nDotH * lDotH / Math.max(1e-8, denom * denom);
        double branchPdf = lobes.transmissionWeightRgb / lobes.totalWeight;
        out.bsdfPdf = Math.max(0.0, branchPdf * pdf);

        double scale = nDotL / Math.max(1e-8, branchPdf * pdf);
        boolean spectral14Active = isPreviewStillFullTierActive();
        double factorR;
        double factorG;
        double factorB;
        if (spectral14Active) {
            spectralTransmissionFactorsRgb(
                    surface,
                    nDotV,
                    out.nextDx,
                    out.nextDy,
                    out.nextDz,
                    1.0,
                    ctx.spectralScratch0,
                    ctx.spectralRgb0
            );
            factorR = Math.max(0.05, ctx.spectralRgb0[0]);
            factorG = Math.max(0.05, ctx.spectralRgb0[1]);
            factorB = Math.max(0.05, ctx.spectralRgb0[2]);
        } else {
            double transmissionR = Math.max(0.05, 1.0 - schlickFresnel(nDotV,
                dispersedIor(surface.refractiveIndex, surface.dispersion, 1.0, -1.0)));
            double transmissionG = Math.max(0.05, 1.0 - schlickFresnel(nDotV,
                dispersedIor(surface.refractiveIndex, surface.dispersion, 1.0, 0.0)));
            double transmissionB = Math.max(0.05, 1.0 - schlickFresnel(nDotV,
                dispersedIor(surface.refractiveIndex, surface.dispersion, 1.0, 1.0)));
            double mediumTrR = mediumDirectionalTransmittance(surface, out.nextDx, out.nextDy, out.nextDz, surface.mediumR);
            double mediumTrG = mediumDirectionalTransmittance(surface, out.nextDx, out.nextDy, out.nextDz, surface.mediumG);
            double mediumTrB = mediumDirectionalTransmittance(surface, out.nextDx, out.nextDy, out.nextDz, surface.mediumB);
            factorR = mediumTrR * transmissionR;
            factorG = mediumTrG * transmissionG;
            factorB = mediumTrB * transmissionB;
        }
        path.throughputR *= Math.max(0.0, specularTerm * factorR) * scale;
        path.throughputG *= Math.max(0.0, specularTerm * factorG) * scale;
        path.throughputB *= Math.max(0.0, specularTerm * factorB) * scale;
        double causticPathGuide = resolveCausticPathGuidance(1, path.lastEventDelta, path.lastBsdfPdf);
        path.causticCarry = Math.max(path.causticCarry, resolveCausticCarry(surface, 1.0, causticPathGuide));
        return true;
    }

    private void sampleReferenceSpecularBounce(SurfaceState surface,
                                               ReferencePathState path,
                                               ReferenceSurfaceLobes lobes,
                                               SplitMix64 rng,
                                               ReferenceBounceSample out,
                                               TraceContext ctx) {
        sampleGgxHalfVector(surface.nx, surface.ny, surface.nz, lobes.specularAlpha, rng, ctx);
        double hx = ctx.sampleDx;
        double hy = ctx.sampleDy;
        double hz = ctx.sampleDz;

        double viewX = lobes.viewX;
        double viewY = lobes.viewY;
        double viewZ = lobes.viewZ;
        double vDotH = viewX * hx + viewY * hy + viewZ * hz;
        if (vDotH <= 1e-6) {
            out.nextDx = surface.nx;
            out.nextDy = surface.ny;
            out.nextDz = surface.nz;
            out.nextEventDelta = false;
            out.bsdfPdf = 0.0;
            return;
        }

        double nextX = -viewX + 2.0 * vDotH * hx;
        double nextY = -viewY + 2.0 * vDotH * hy;
        double nextZ = -viewZ + 2.0 * vDotH * hz;
        double nextLenSq = nextX * nextX + nextY * nextY + nextZ * nextZ;
        if (nextLenSq > 1e-12) {
            double inv = 1.0 / Math.sqrt(nextLenSq);
            nextX *= inv;
            nextY *= inv;
            nextZ *= inv;
        }

        out.nextDx = nextX;
        out.nextDy = nextY;
        out.nextDz = nextZ;
        out.nextEventDelta = surface.roughness <= 0.025;

        double nDotNext = Math.max(1e-6, surface.nx * out.nextDx + surface.ny * out.nextDy + surface.nz * out.nextDz);
        double branchPdf = lobes.specularWeight / lobes.totalWeight;
        double specPdf = referenceSpecularPdf(surface, lobes, out.nextDx, out.nextDy, out.nextDz);
        out.bsdfPdf = Math.max(0.0, branchPdf * specPdf);

        if (specPdf <= 1e-12) {
            path.throughputR = 0.0;
            path.throughputG = 0.0;
            path.throughputB = 0.0;
            return;
        }

        double nDotV = Math.max(1e-6, surface.nx * viewX + surface.ny * viewY + surface.nz * viewZ);
        double nDotH = Math.max(1e-6, surface.nx * hx + surface.ny * hy + surface.nz * hz);
        double specularTerm = ggxSpecularTerm(nDotV, nDotNext, nDotH, lobes.specularAlphaSq, lobes.specularRoughness)
 * lobes.specularEnergyComp;
        double fresnelR;
        double fresnelG;
        double fresnelB;
        if (isPreviewStillFullTierActive()) {
            spectralLayeredFresnelRgb(
                vDotH,
                average3(lobes.specularR, lobes.specularG, lobes.specularB),
                lobes.metallic,
                lobes.conductorEta,
                lobes.conductorKR,
                lobes.conductorKG,
                lobes.conductorKB,
                ctx.spectralScratch0,
                ctx.spectralRgb0
            );
            fresnelR = ctx.spectralRgb0[0];
            fresnelG = ctx.spectralRgb0[1];
            fresnelB = ctx.spectralRgb0[2];
        } else {
            fresnelR = layeredSpecularFresnel(vDotH, lobes.specularR, lobes.metallic, lobes.conductorEta, lobes.conductorKR);
            fresnelG = layeredSpecularFresnel(vDotH, lobes.specularG, lobes.metallic, lobes.conductorEta, lobes.conductorKG);
            fresnelB = layeredSpecularFresnel(vDotH, lobes.specularB, lobes.metallic, lobes.conductorEta, lobes.conductorKB);
        }

        double scale = nDotNext / Math.max(1e-8, branchPdf * specPdf);
        path.throughputR *= Math.max(0.0, specularTerm * fresnelR) * scale;
        path.throughputG *= Math.max(0.0, specularTerm * fresnelG) * scale;
        path.throughputB *= Math.max(0.0, specularTerm * fresnelB) * scale;
    }

    private void sampleReferenceDiffuseBounce(SurfaceState surface,
                                              ReferencePathState path,
                                              ReferenceSurfaceLobes lobes,
                                              SplitMix64 rng,
                                              ReferenceBounceSample out,
                                              TraceContext ctx) {
        sampleCosineHemisphere(surface.nx, surface.ny, surface.nz, rng, ctx);
        out.nextDx = ctx.sampleDx;
        out.nextDy = ctx.sampleDy;
        out.nextDz = ctx.sampleDz;
        out.nextEventDelta = false;

        double branchPdf = lobes.diffuseWeight / lobes.totalWeight;
        double nDotNext = Math.max(0.0, surface.nx * out.nextDx + surface.ny * out.nextDy + surface.nz * out.nextDz);
        out.bsdfPdf = Math.max(0.0, branchPdf * PathTracingSamplingSupport.cosineHemispherePdf(nDotNext));
        path.throughputR *= Math.max(0.0, lobes.diffuseR) / Math.max(1e-8, branchPdf);
        path.throughputG *= Math.max(0.0, lobes.diffuseG) / Math.max(1e-8, branchPdf);
        path.throughputB *= Math.max(0.0, lobes.diffuseB) / Math.max(1e-8, branchPdf);
    }

    private void sampleReferenceSheenBounce(SurfaceState surface,
                                            ReferencePathState path,
                                            ReferenceSurfaceLobes lobes,
                                            SplitMix64 rng,
                                            ReferenceBounceSample out,
                                            TraceContext ctx) {
        sampleCosineHemisphere(surface.nx, surface.ny, surface.nz, rng, ctx);
        out.nextDx = ctx.sampleDx;
        out.nextDy = ctx.sampleDy;
        out.nextDz = ctx.sampleDz;
        out.nextEventDelta = false;

        double branchPdf = lobes.sheenWeight / lobes.totalWeight;
        double nDotNext = Math.max(0.0, surface.nx * out.nextDx + surface.ny * out.nextDy + surface.nz * out.nextDz);
        out.bsdfPdf = Math.max(0.0, branchPdf * PathTracingSamplingSupport.cosineHemispherePdf(nDotNext));
        path.throughputR *= Math.max(0.0, lobes.sheenR) / Math.max(1e-8, branchPdf);
        path.throughputG *= Math.max(0.0, lobes.sheenG) / Math.max(1e-8, branchPdf);
        path.throughputB *= Math.max(0.0, lobes.sheenB) / Math.max(1e-8, branchPdf);
    }

    boolean survivesReferenceRussianRoulette(int bounce, ReferencePathState path, SplitMix64 rng) {
        if (bounce < 2) {
            return true;
        }
        double rr = Math.max(path.throughputR, Math.max(path.throughputG, path.throughputB));
        rr = Math.max(0.05, Math.min(0.98, rr));
        if (rng.nextDouble() > rr) {
            return false;
        }
        double inv = 1.0 / rr;
        path.throughputR *= inv;
        path.throughputG *= inv;
        path.throughputB *= inv;
        return true;
    }

    void advanceReferencePathState(ReferencePathState path,
                                           Hit hit,
                                           SurfaceState surface,
                                           ReferenceBounceSample bounce) {
        double originSign = (surface.geomNx * bounce.nextDx + surface.geomNy * bounce.nextDy + surface.geomNz * bounce.nextDz) >= 0.0
                ? 1.0
                : -1.0;
        path.ox = hit.px + surface.geomNx * originSign * RAY_EPS;
        path.oy = hit.py + surface.geomNy * originSign * RAY_EPS;
        path.oz = hit.pz + surface.geomNz * originSign * RAY_EPS;
        path.dx = bounce.nextDx;
        path.dy = bounce.nextDy;
        path.dz = bounce.nextDz;
        path.lastEventDelta = bounce.nextEventDelta;
        path.lastBsdfPdf = bounce.bsdfPdf;
        path.lastSurfacePx = hit.px;
        path.lastSurfacePy = hit.py;
        path.lastSurfacePz = hit.pz;
        path.lastSurfaceValid = true;
        path.causticCarry *= bounce.nextEventDelta ? 0.62 : 0.38;
    }

    private EmissiveTriangleLight sampleEmissiveLight(double sample) {
        if (emissiveLights.length == 0) {
            return null;
        }
        int lo = 0;
        int hi = emissiveLights.length - 1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (sample <= emissiveLights[mid].cdf) {
                hi = mid;
            } else {
                lo = mid + 1;
            }
        }
        return emissiveLights[Math.max(0, Math.min(emissiveLights.length - 1, lo))];
    }

    private void sampleGgxHalfVector(double nx,
                                     double ny,
                                     double nz,
                                     double alpha,
                                     SplitMix64 rng,
                                     TraceContext ctx) {
        Vec3 normal = ctx.tmpVec0.set(nx, ny, nz).normalizeInPlace();
        Vec3 tangent = buildFallbackTangent(normal, ctx.tmpVec1, ctx.tmpVec2);
        Vec3 bitangent = normal.cross(tangent, ctx.tmpVec3).normalizeInPlace();

        double u1 = rng.nextDouble();
        double u2 = rng.nextDouble();
        double alphaSq = alpha * alpha;
        double tanThetaSq = alphaSq * u1 / Math.max(1e-8, 1.0 - u1);
        double cosTheta = 1.0 / Math.sqrt(1.0 + tanThetaSq);
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        double phi = 2.0 * PI * u2;
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);

        double hx = tangent.x * (cosPhi * sinTheta)
                + bitangent.x * (sinPhi * sinTheta)
                + normal.x * cosTheta;
        double hy = tangent.y * (cosPhi * sinTheta)
                + bitangent.y * (sinPhi * sinTheta)
                + normal.y * cosTheta;
        double hz = tangent.z * (cosPhi * sinTheta)
                + bitangent.z * (sinPhi * sinTheta)
                + normal.z * cosTheta;

        double lenSq = hx * hx + hy * hy + hz * hz;
        if (lenSq < 1e-12) {
            ctx.sampleDx = normal.x;
            ctx.sampleDy = normal.y;
            ctx.sampleDz = normal.z;
            return;
        }
        double inv = 1.0 / Math.sqrt(lenSq);
        ctx.sampleDx = hx * inv;
        ctx.sampleDy = hy * inv;
        ctx.sampleDz = hz * inv;
    }

    private static double ggxSpecularTerm(double nDotV,
                                          double nDotL,
                                          double nDotH,
                                          double alphaSq,
                                          double roughness) {
        if (nDotV <= 1e-6 || nDotL <= 1e-6 || nDotH <= 1e-6) {
            return 0.0;
        }
        double d = ggxDistribution(nDotH, alphaSq);
        double g = ggxSmithG(nDotV, nDotL, roughness);
        return d * g / Math.max(1e-8, 4.0 * nDotV * nDotL);
    }

    private static double ggxDistribution(double nDotH, double alphaSq) {
        double denom = nDotH * nDotH * (alphaSq - 1.0) + 1.0;
        return alphaSq / Math.max(1e-8, Math.PI * denom * denom);
    }

    private static double ggxSmithG(double nDotV, double nDotL, double roughness) {
        double k = (roughness + 1.0);
        k = (k * k) / 8.0;
        double gv = nDotV / Math.max(1e-8, nDotV * (1.0 - k) + k);
        double gl = nDotL / Math.max(1e-8, nDotL * (1.0 - k) + k);
        return gv * gl;
    }

    private static double roughnessToAlpha(double roughness) {
        double r = clamp01(roughness);
        return Math.max(0.02, r * r);
    }

    private static double schlickFresnelColor(double cosTheta, double f0) {
        double m = 1.0 - clamp01(cosTheta);
        double m2 = m * m;
        double m5 = m2 * m2 * m;
        return clamp01(f0 + (1.0 - f0) * m5);
    }

    private static double sheenLobeTerm(double vDotH, double sheenRoughness) {
        double exponent = 5.0 + Math.max(0.0, sheenRoughness) * 5.0;
        return Math.pow(Math.max(0.0, 1.0 - vDotH), exponent);
    }

    private static double referenceMediumTransmittance(double mediumChannel, double density, double thickness) {
        double opticalDepth = Math.max(0.0, density) * Math.max(0.02, thickness);
        double absorption = Math.max(0.0, 1.0 - clamp01(mediumChannel));
        return Math.exp(-opticalDepth * absorption);
    }

    private static double resolveConductorExtinction(double specularChannel, double metallic) {
        double metallicWeight = clamp01(metallic);
        double channel = Math.sqrt(clamp01(specularChannel));
        return Math.max(0.02, 0.06 + CONDUCTOR_K_MAX * metallicWeight * channel);
    }

    private static double spectralGaussian(double lambdaNm, double centerNm, double sigmaNm) {
        double x = (lambdaNm - centerNm) / Math.max(1e-6, sigmaNm);
        return Math.exp(-0.5 * x * x);
    }

    private static double spectralDot(double[] a, double[] b) {
        double sum = 0.0;
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    private static void invert3x3(double m00, double m01, double m02,
                                  double m10, double m11, double m12,
                                  double m20, double m21, double m22,
                                  double[][] out) {
        double c00 = m11 * m22 - m12 * m21;
        double c01 = m02 * m21 - m01 * m22;
        double c02 = m01 * m12 - m02 * m11;
        double c10 = m12 * m20 - m10 * m22;
        double c11 = m00 * m22 - m02 * m20;
        double c12 = m02 * m10 - m00 * m12;
        double c20 = m10 * m21 - m11 * m20;
        double c21 = m01 * m20 - m00 * m21;
        double c22 = m00 * m11 - m01 * m10;
        double det = m00 * c00 + m01 * c10 + m02 * c20;
        double invDet = 1.0 / Math.max(1e-10, Math.abs(det));
        if (det < 0.0) {
            invDet = -invDet;
        }
        out[0][0] = c00 * invDet;
        out[0][1] = c01 * invDet;
        out[0][2] = c02 * invDet;
        out[1][0] = c10 * invDet;
        out[1][1] = c11 * invDet;
        out[1][2] = c12 * invDet;
        out[2][0] = c20 * invDet;
        out[2][1] = c21 * invDet;
        out[2][2] = c22 * invDet;
    }

    private static void reconstructSpectrumFromRgb(double r, double g, double b, double[] outSpectrum) {
        double rr = Math.max(0.0, r);
        double gg = Math.max(0.0, g);
        double bb = Math.max(0.0, b);
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            outSpectrum[i] = rr * SPECTRAL_BASIS_R[i]
                    + gg * SPECTRAL_BASIS_G[i]
                    + bb * SPECTRAL_BASIS_B[i];
        }
    }

    private static void collapseSpectrumToRgb(double[] spectrum, double[] outRgb3) {
        double pR = 0.0;
        double pG = 0.0;
        double pB = 0.0;
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            double s = spectrum[i];
            pR += s * SPECTRAL_BASIS_R[i];
            pG += s * SPECTRAL_BASIS_G[i];
            pB += s * SPECTRAL_BASIS_B[i];
        }
        double r = SPECTRAL_BASIS_INV[0][0] * pR + SPECTRAL_BASIS_INV[0][1] * pG + SPECTRAL_BASIS_INV[0][2] * pB;
        double g = SPECTRAL_BASIS_INV[1][0] * pR + SPECTRAL_BASIS_INV[1][1] * pG + SPECTRAL_BASIS_INV[1][2] * pB;
        double b = SPECTRAL_BASIS_INV[2][0] * pR + SPECTRAL_BASIS_INV[2][1] * pG + SPECTRAL_BASIS_INV[2][2] * pB;
        outRgb3[0] = Math.max(0.0, r);
        outRgb3[1] = Math.max(0.0, g);
        outRgb3[2] = Math.max(0.0, b);
    }

    private static double spectralDielectricIor(double baseIor,
                                                double dispersion,
                                                double opticsWeight,
                                                double wavelengthNm) {
        double spread = DISPERSION_IOR_SPREAD
 * clamp01(dispersion)
 * clamp01(opticsWeight)
 * Math.max(0.25, baseIor - 1.0);
        double bias = (550.0 - wavelengthNm) / 165.0;
        return Math.max(1.0, baseIor + spread * bias);
    }

    private static void spectralLayeredFresnelRgb(double cosTheta,
                                                  double dielectricF0,
                                                  double metallic,
                                                  double conductorEta,
                                                  double conductorKR,
                                                  double conductorKG,
                                                  double conductorKB,
                                                  double[] spectrumScratch,
                                                  double[] rgbOut) {
        reconstructSpectrumFromRgb(conductorKR, conductorKG, conductorKB, spectrumScratch);
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            double kBand = Math.max(0.02, spectrumScratch[i]);
            double dielectric = schlickFresnelColor(cosTheta, clamp01(dielectricF0));
            double conductor = fresnelConductor(cosTheta, Math.max(1.0, conductorEta), kBand);
            spectrumScratch[i] = mix(dielectric, conductor, clamp01(metallic));
        }
        collapseSpectrumToRgb(spectrumScratch, rgbOut);
    }

    private static void spectralHeroProjectRgb(double r,
                                               double g,
                                               double b,
                                               int heroBand,
                                               int companionBand,
                                               double[] spectrumScratch,
                                               double[] rgbOut) {
        if (heroBand < 0 || heroBand >= SPECTRAL_BAND_COUNT) {
            rgbOut[0] = Math.max(0.0, r);
            rgbOut[1] = Math.max(0.0, g);
            rgbOut[2] = Math.max(0.0, b);
            return;
        }
        reconstructSpectrumFromRgb(r, g, b, spectrumScratch);
        double s = Math.max(0.0, spectrumScratch[heroBand]);
        double br = SPECTRAL_BASIS_R[heroBand];
        double bg = SPECTRAL_BASIS_G[heroBand];
        double bb = SPECTRAL_BASIS_B[heroBand];
        double norm = Math.max(1e-6, br * br + bg * bg + bb * bb);
        double scale = Math.min(8.0, SPECTRAL_BAND_COUNT / norm);
        double outR = s * br * scale;
        double outG = s * bg * scale;
        double outB = s * bb * scale;

        if (companionBand >= 0 && companionBand < SPECTRAL_BAND_COUNT && companionBand != heroBand) {
            double s2 = Math.max(0.0, spectrumScratch[companionBand]);
            double br2 = SPECTRAL_BASIS_R[companionBand];
            double bg2 = SPECTRAL_BASIS_G[companionBand];
            double bb2 = SPECTRAL_BASIS_B[companionBand];
            double norm2 = Math.max(1e-6, br2 * br2 + bg2 * bg2 + bb2 * bb2);
            double scale2 = Math.min(8.0, SPECTRAL_BAND_COUNT / norm2);
            outR = 0.5 * (outR + s2 * br2 * scale2);
            outG = 0.5 * (outG + s2 * bg2 * scale2);
            outB = 0.5 * (outB + s2 * bb2 * scale2);
        }

        rgbOut[0] = Math.max(0.0, outR);
        rgbOut[1] = Math.max(0.0, outG);
        rgbOut[2] = Math.max(0.0, outB);
    }

    private static void spectralTransmissionFactorsRgb(SurfaceState surface,
                                                       double ndotv,
                                                       double dirX,
                                                       double dirY,
                                                       double dirZ,
                                                       double opticsWeight,
                                                       double[] spectrumScratch,
                                                       double[] rgbOut) {
        reconstructSpectrumFromRgb(surface.mediumR, surface.mediumG, surface.mediumB, spectrumScratch);
        for (int i = 0; i < SPECTRAL_BAND_COUNT; i++) {
            double lambda = SPECTRAL_BAND_LAMBDAS[i];
            double ior = spectralDielectricIor(surface.refractiveIndex, surface.dispersion, opticsWeight, lambda);
            double fresnelTransmission = Math.max(0.05, 1.0 - schlickFresnel(ndotv, ior));
            double mediumChannel = clamp01(spectrumScratch[i]);
            double mediumTr = mediumDirectionalTransmittance(surface, dirX, dirY, dirZ, mediumChannel);
            spectrumScratch[i] = fresnelTransmission * mediumTr;
        }
        collapseSpectrumToRgb(spectrumScratch, rgbOut);
    }

    private static String normalizeOpticalPresetToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String upper = value.toUpperCase();
        StringBuilder builder = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char ch = upper.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || ch == '_') {
                builder.append(ch);
            } else if (ch == '-' || ch == ' ' || ch == '/') {
                builder.append('_');
            }
        }
        return builder.toString();
    }

    private static OpticalPreset resolveOpticalPreset(PhongMaterial material) {
        if (material == null) {
            return null;
        }
        String presetToken = normalizeOpticalPresetToken(material.getPresetName());
        String nameToken = normalizeOpticalPresetToken(material.getName());
        for (OpticalPreset preset : OPTICAL_PRESETS) {
            if (presetToken.contains(preset.key) || nameToken.contains(preset.key)) {
                return preset;
            }
        }
        return null;
    }

    private static double fresnelConductor(double cosTheta, double eta, double k) {
        double c = clamp01(cosTheta);
        double c2 = c * c;
        double eta2 = eta * eta;
        double k2 = k * k;

        double t0 = eta2 - k2 - c2;
        double a2pb2 = Math.sqrt(Math.max(1e-8, t0 * t0 + 4.0 * eta2 * k2));
        double a = Math.sqrt(Math.max(1e-8, 0.5 * (a2pb2 + t0)));
        double t1 = a2pb2 + c2;
        double t2 = 2.0 * c * a;
        double rs = (t1 - t2) / Math.max(1e-8, t1 + t2);

        double t3 = c2 * a2pb2 + c2 * c2;
        double t4 = t2 * c2;
        double rp = rs * (t3 - t4) / Math.max(1e-8, t3 + t4);
        return clamp01(0.5 * (rs + rp));
    }

    private static double layeredSpecularFresnel(double cosTheta,
                                                 double dielectricF0,
                                                 double metallic,
                                                 double conductorEta,
                                                 double conductorK) {
        double dielectric = schlickFresnelColor(cosTheta, clamp01(dielectricF0));
        double conductor = fresnelConductor(cosTheta, Math.max(1.0, conductorEta), Math.max(0.02, conductorK));
        return mix(dielectric, conductor, clamp01(metallic));
    }

    private static double ggxMultipleScatterCompensation(double roughness, double avgF0) {
        double r = clamp01(roughness);
        double f = clamp01(avgF0);
        return 1.0 + GGX_MULTISCATTER_STRENGTH * r * (0.35 + 0.65 * f);
    }

    private static double henyeyGreensteinPhase(double g, double cosTheta) {
        double clampedG = Math.max(-0.99, Math.min(0.99, g));
        double c = Math.max(-1.0, Math.min(1.0, cosTheta));
        double denom = 1.0 + clampedG * clampedG - 2.0 * clampedG * c;
        return (1.0 - clampedG * clampedG)
                / Math.max(1e-6, 4.0 * PI * denom * Math.sqrt(Math.max(1e-8, denom)));
    }

    static double sampleHenyeyGreensteinDirection(double inDx,
                                                          double inDy,
                                                          double inDz,
                                                          double g,
                                                          SplitMix64 rng,
                                                          TraceContext ctx) {
        double clampedG = Math.max(-0.99, Math.min(0.99, g));
        double u1 = rng.nextDouble();
        double u2 = rng.nextDouble();
        double cosTheta;
        if (Math.abs(clampedG) < 1e-4) {
            cosTheta = 1.0 - 2.0 * u1;
        } else {
            double term = (1.0 - clampedG * clampedG)
                    / Math.max(1e-6, 1.0 - clampedG + 2.0 * clampedG * u1);
            cosTheta = (1.0 + clampedG * clampedG - term * term) / (2.0 * clampedG);
            cosTheta = Math.max(-1.0, Math.min(1.0, cosTheta));
        }
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        double phi = 2.0 * PI * u2;
        double cosPhi = Math.cos(phi);
        double sinPhi = Math.sin(phi);

        double wLenSq = inDx * inDx + inDy * inDy + inDz * inDz;
        double wx = inDx;
        double wy = inDy;
        double wz = inDz;
        if (wLenSq < 1e-12) {
            wx = 0.0;
            wy = 0.0;
            wz = 1.0;
        } else {
            double invW = 1.0 / Math.sqrt(wLenSq);
            wx *= invW;
            wy *= invW;
            wz *= invW;
        }

        double ax = Math.abs(wx) < 0.6 ? 1.0 : 0.0;
        double ay = Math.abs(wx) < 0.6 ? 0.0 : 1.0;
        double az = 0.0;
        double ux = ay * wz - az * wy;
        double uy = az * wx - ax * wz;
        double uz = ax * wy - ay * wx;
        double uLenSq = ux * ux + uy * uy + uz * uz;
        if (uLenSq < 1e-12) {
            ux = 0.0;
            uy = 1.0;
            uz = 0.0;
            uLenSq = 1.0;
        }
        double invU = 1.0 / Math.sqrt(uLenSq);
        ux *= invU;
        uy *= invU;
        uz *= invU;
        double vx = wy * uz - wz * uy;
        double vy = wz * ux - wx * uz;
        double vz = wx * uy - wy * ux;

        double outX = ux * (sinTheta * cosPhi) + vx * (sinTheta * sinPhi) + wx * cosTheta;
        double outY = uy * (sinTheta * cosPhi) + vy * (sinTheta * sinPhi) + wy * cosTheta;
        double outZ = uz * (sinTheta * cosPhi) + vz * (sinTheta * sinPhi) + wz * cosTheta;
        double outLenSq = outX * outX + outY * outY + outZ * outZ;
        if (outLenSq < 1e-12) {
            ctx.sampleDx = wx;
            ctx.sampleDy = wy;
            ctx.sampleDz = wz;
        } else {
            double invOut = 1.0 / Math.sqrt(outLenSq);
            ctx.sampleDx = outX * invOut;
            ctx.sampleDy = outY * invOut;
            ctx.sampleDz = outZ * invOut;
        }
        return henyeyGreensteinPhase(clampedG, cosTheta);
    }

    private static double mediumPathLength(double thickness, double cosTheta, double roughness) {
        double base = Math.max(0.02, thickness);
        double stretch = 1.0 / Math.max(0.08, Math.abs(cosTheta));
        double microPath = 1.0 + clamp01(roughness) * 0.85;
        return base * stretch * microPath;
    }

    private static double mediumDirectionalTransmittance(SurfaceState surface,
                                                         double dirX,
                                                         double dirY,
                                                         double dirZ,
                                                         double mediumChannel) {
        double cosTheta = surface.nx * dirX + surface.ny * dirY + surface.nz * dirZ;
        double travel = mediumPathLength(surface.thickness, cosTheta, surface.roughness);
        return referenceMediumTransmittance(mediumChannel, surface.density, travel);
    }

    private static double estimateSubsurfaceContribution(SurfaceState surface,
                                                         double viewX,
                                                         double viewY,
                                                         double viewZ,
                                                         double lightX,
                                                         double lightY,
                                                         double lightZ) {
        double mediumScatter = 1.0 - referenceMediumTransmittance(0.0, surface.density, surface.thickness);
        double pathScatter = 1.0 - Math.exp(-Math.max(0.0, surface.density) * Math.max(0.02, surface.thickness) * 2.0);
        double cosTheta = Math.max(-1.0, Math.min(1.0, viewX * lightX + viewY * lightY + viewZ * lightZ));
        double phase = henyeyGreensteinPhase(surface.anisotropy, cosTheta);
        double transmissionAssist = 0.35 + 0.65 * clamp01(surface.transmission);
        return clamp01(surface.subsurface)
 * SUBSURFACE_DIRECT_STRENGTH
 * transmissionAssist
 * (0.45 * mediumScatter + 0.55 * pathScatter)
 * (phase * 4.0 * PI);
    }

    private double contributionClampScale(double contributionR, double contributionG, double contributionB, boolean directPath) {
        if (referenceMode && !referenceClampEnabled) {
            return 1.0;
        }
        double clamp = directPath ? clampDirect : clampIndirect;
        if (!referenceMode && !isPreviewStillFullTierActive()) {
            clamp = directPath ? LEGACY_PREVIEW_CLAMP_DIRECT : LEGACY_PREVIEW_CLAMP_INDIRECT;
        }
        if (clamp <= 0.0) {
            return 1.0;
        }
        double luma = DenoiseSupport.luminance(contributionR, contributionG, contributionB);
        if (luma <= clamp || luma <= 1e-8) {
            return 1.0;
        }
        return clamp / luma;
    }

    double throughputClampScale(double throughputR, double throughputG, double throughputB, int bounce) {
        if (referenceMode && !referenceClampEnabled) {
            return 1.0;
        }
        double clamp = bounce <= 0 ? clampDirect : clampIndirect;
        if (!referenceMode && !isPreviewStillFullTierActive()) {
            clamp = bounce <= 0 ? LEGACY_PREVIEW_CLAMP_DIRECT : LEGACY_PREVIEW_CLAMP_INDIRECT;
        }
        if (clamp <= 0.0) {
            return 1.0;
        }
        double luma = DenoiseSupport.luminance(throughputR, throughputG, throughputB);
        if (luma <= clamp || luma <= 1e-8) {
            return 1.0;
        }
        return clamp / luma;
    }

    private static double average3(double a, double b, double c) {
        return (a + b + c) / 3.0;
    }

    void sampleSurface(Triangle tri, Hit hit, double dx, double dy, double dz, SurfaceState out, TraceContext ctx) {
        double w0 = 1.0 - hit.u - hit.v;
        double geomNx = tri.faceNx;
        double geomNy = tri.faceNy;
        double geomNz = tri.faceNz;
        if ((geomNx * dx + geomNy * dy + geomNz * dz) > 0.0) {
            geomNx = -geomNx;
            geomNy = -geomNy;
            geomNz = -geomNz;
        }
        double nx;
        double ny;
        double nz;
        if (tri.flatNormal) {
            nx = geomNx;
            ny = geomNy;
            nz = geomNz;
        } else {
            nx = tri.n0x * w0 + tri.n1x * hit.u + tri.n2x * hit.v;
            ny = tri.n0y * w0 + tri.n1y * hit.u + tri.n2y * hit.v;
            nz = tri.n0z * w0 + tri.n1z * hit.u + tri.n2z * hit.v;
            double nLenSq = nx * nx + ny * ny + nz * nz;
            if (nLenSq < 1e-14) {
                nx = geomNx;
                ny = geomNy;
                nz = geomNz;
            } else {
                double inv = 1.0 / Math.sqrt(nLenSq);
                nx *= inv;
                ny *= inv;
                nz *= inv;
            }
        }
        if ((nx * dx + ny * dy + nz * dz) > 0.0) {
            nx = -nx;
            ny = -ny;
            nz = -nz;
        }

        PhongMaterial material = toPhongMaterial(tri.material);
        OpticalPreset opticalPreset = resolveOpticalPreset(material);
        double uv0U = tri.hasUV ? tri.u0 * w0 + tri.u1 * hit.u + tri.u2 * hit.v : 0.0;
        double uv0V = tri.hasUV ? tri.v0 * w0 + tri.v1 * hit.u + tri.v2 * hit.v : 0.0;
        double uv1U = tri.hasUV2 ? tri.u0b * w0 + tri.u1b * hit.u + tri.u2b * hit.v : 0.0;
        double uv1V = tri.hasUV2 ? tri.v0b * w0 + tri.v1b * hit.u + tri.v2b * hit.v : 0.0;
        MaterialGraphEvaluator.Result graphResult = material.hasNodeGraph()
                ? MaterialGraphEvaluator.evaluateTriangleShared(
                material,
                hit.px,
                hit.py,
                hit.pz,
                tri.hasUV,
                uv0U,
                uv0V,
                tri.hasUV2,
                uv1U,
                uv1V
        ) : null;
        MaterialGraphEvaluator.Result graph = (graphResult != null && graphResult.graphApplied)
            ? graphResult
            : null;
        out.discard = false;
        out.nx = nx;
        out.ny = ny;
        out.nz = nz;
        out.geomNx = geomNx;
        out.geomNy = geomNy;
        out.geomNz = geomNz;
        out.baseR = tri.baseR;
        out.baseG = tri.baseG;
        out.baseB = tri.baseB;
        if (graph != null) {
            out.baseR = graph.baseColor.x;
            out.baseG = graph.baseColor.y;
            out.baseB = graph.baseColor.z;
        }
        if (tri.floorGrid) {
            double cell = 2.0;
            int gx = (int) Math.floor(hit.px / cell);
            int gz = (int) Math.floor(hit.pz / cell);
            boolean even = ((gx + gz) & 1) == 0;
            if (even) {
                out.baseR = 0.56;
                out.baseG = 0.58;
                out.baseB = 0.61;
            } else {
                out.baseR = 0.36;
                out.baseG = 0.39;
                out.baseB = 0.43;
            }
        }

        double opacity = material.getOpacity();
        if (graph != null) {
            opacity = graph.opacity;
        }
        out.opacity = clamp01(opacity);
        if (graph == null) {
            TextureMap diffuseMap = material.getDiffuseMap();
            if (canSampleTextureMap(diffuseMap, tri)) {
                int baseTexel = sampleTextureMap(diffuseMap, tri, w0, hit.u, hit.v);
                out.baseR *= ((baseTexel >> 16) & 0xFF) / 255.0;
                out.baseG *= ((baseTexel >> 8) & 0xFF) / 255.0;
                out.baseB *= (baseTexel & 0xFF) / 255.0;
                out.opacity *= ((baseTexel >>> 24) & 0xFF) / 255.0;
            }
        }
        if (material.getAlphaMode() == PhongMaterial.AlphaMode.MASK
                && out.opacity < material.getAlphaCutoff()) {
            out.discard = true;
            return;
        }

        double roughness = material.getRoughness();
        double metallic = material.getMetallic();
        if (graph != null) {
            roughness = graph.roughness;
            metallic = graph.metallic;
        }
        roughness = clamp01(roughness);
        metallic = clamp01(metallic);
        out.metallic = metallic;
        if (graph == null) {
            TextureMap metallicRoughnessMap = material.getMetallicRoughnessMap();
            if (canSampleTextureMap(metallicRoughnessMap, tri)) {
                int mrTexel = sampleTextureMap(metallicRoughnessMap, tri, w0, hit.u, hit.v);
                roughness *= ((mrTexel >> 8) & 0xFF) / 255.0;
                metallic *= (mrTexel & 0xFF) / 255.0;
            }
        }
        out.roughness = clamp01(roughness);
        double clearcoatFactor = material.getClearcoatFactor();
        double clearcoatRoughness = material.getClearcoatRoughness();
        double specular = material.getSpecularFactor();
        if (graph != null) {
            clearcoatFactor = graph.clearcoatFactor;
            clearcoatRoughness = graph.clearcoatRoughness;
            specular = graph.specularFactor;
        }
        double clearcoatBoost = clearcoatFactor * (1.0 - clearcoatRoughness * 0.65);
        Vec3 specularColorFactor = material.getSpecularColorFactor();
        double baseF0 = 0.08 * specular;
        double dielectricSpecR = material.getSpecularColor().x * baseF0 * specularColorFactor.x;
        double dielectricSpecG = material.getSpecularColor().y * baseF0 * specularColorFactor.y;
        double dielectricSpecB = material.getSpecularColor().z * baseF0 * specularColorFactor.z;
        out.specR = clamp01(mix(dielectricSpecR, out.baseR, metallic)
                + clearcoatBoost * 0.28);
        out.specG = clamp01(mix(dielectricSpecG, out.baseG, metallic)
                + clearcoatBoost * 0.28);
        out.specB = clamp01(mix(dielectricSpecB, out.baseB, metallic)
                + clearcoatBoost * 0.28);
        double maxBase = Math.max(out.baseR, Math.max(out.baseG, out.baseB));
        double dielectricReflectivity = 0.08 * specular + clearcoatBoost * 0.22;
        double metallicReflectivity = metallic * mix(0.35, 0.85, maxBase);
        out.reflectivity = clamp01(Math.max(material.getReflectivity(), dielectricReflectivity + metallicReflectivity));
        double transmission = material.getTransmission();
        if (graph != null) {
            transmission = graph.transmission;
        }
        out.transmission = clamp01(Math.max(transmission,
                material.getAlphaMode() == PhongMaterial.AlphaMode.BLEND ? 1.0 - out.opacity : transmission));
        applyMaterialProfileToSurface(out);
        double refractiveIndex = material.getRefractiveIndex();
        Vec3 emissionColor = material.getEmissionColor();
        double emissionStrength = material.getEmissionStrength();
        Vec3 mediumColor = material.getMediumColor();
        double density = material.getDensity();
        double thickness = material.getThickness();
        Vec3 sheenColor = material.getSheenColor();
        double sheenRoughness = material.getSheenRoughness();
        if (graph != null) {
            refractiveIndex = graph.refractiveIndex;
            emissionColor = graph.emissionColor;
            emissionStrength = graph.emissionStrength;
            mediumColor = graph.mediumColor;
            density = graph.density;
            thickness = graph.thickness;
            sheenColor = graph.sheenColor;
            sheenRoughness = graph.sheenRoughness;
        }
        if (opticalPreset != null && !opticalPreset.conductor()) {
            refractiveIndex = opticalPreset.eta();
        }
        out.refractiveIndex = Math.max(1.0, refractiveIndex);
        out.presetConductor = opticalPreset != null && opticalPreset.conductor();
        out.presetEta = opticalPreset != null ? Math.max(1.0, opticalPreset.eta()) : out.refractiveIndex;
        out.presetKR = opticalPreset != null ? Math.max(0.0, opticalPreset.kR()) : 0.0;
        out.presetKG = opticalPreset != null ? Math.max(0.0, opticalPreset.kG()) : 0.0;
        out.presetKB = opticalPreset != null ? Math.max(0.0, opticalPreset.kB()) : 0.0;
        out.dispersion = clamp01(graph != null ? graph.dispersion : material.getDispersion());
        out.emissionR = emissionColor.x * emissionStrength;
        out.emissionG = emissionColor.y * emissionStrength;
        out.emissionB = emissionColor.z * emissionStrength;
        if (graph == null) {
            TextureMap emissiveMap = material.getEmissiveMap();
            if (canSampleTextureMap(emissiveMap, tri)) {
                int emissiveTexel = sampleTextureMap(emissiveMap, tri, w0, hit.u, hit.v);
                out.emissionR *= ((emissiveTexel >> 16) & 0xFF) / 255.0;
                out.emissionG *= ((emissiveTexel >> 8) & 0xFF) / 255.0;
                out.emissionB *= (emissiveTexel & 0xFF) / 255.0;
            }
        }
        out.mediumR = mediumColor.x;
        out.mediumG = mediumColor.y;
        out.mediumB = mediumColor.z;
        out.density = density;
        out.thickness = Math.max(0.02, thickness);
        double anisotropy = material.getAnisotropy();
        if (graph != null) {
            anisotropy = graph.anisotropy;
        }
        out.anisotropy = Math.max(-0.99, Math.min(0.99, anisotropy));
        double volumetricStrength = 1.0 - Math.exp(-Math.max(0.0, out.density) * Math.max(0.02, out.thickness));
        out.subsurface = clamp01((0.65 * out.transmission + 0.35 * (1.0 - out.metallic)) * volumetricStrength);
        out.sheenR = sheenColor.x;
        out.sheenG = sheenColor.y;
        out.sheenB = sheenColor.z;
        out.sheenRoughness = sheenRoughness;
        out.clearcoatFactor = clearcoatFactor;
        out.clearcoatRoughness = clearcoatRoughness;

        Vec3 mappedNormal = applyNormalMap(material, tri, w0, hit.u, hit.v, out.nx, out.ny, out.nz, ctx);
        out.nx = mappedNormal.x;
        out.ny = mappedNormal.y;
        out.nz = mappedNormal.z;
    }

    private void applyMaterialProfileToSurface(SurfaceState out) {
        if (out == null || materialProfile == null || materialProfile.isEmpty()) {
            return;
        }
        switch (materialProfile) {
            case "PT" -> {
 // Keep PT profile physically neutral by default.
            }
            case "RT" -> {
                out.roughness = clamp01(out.roughness * 0.92);
                out.reflectivity = clamp01(out.reflectivity * 1.08);
                out.transmission = clamp01(out.transmission * 0.94);
            }
            case "DITHER" -> {
                out.roughness = clamp01(out.roughness * 1.18 + 0.04);
                out.reflectivity = clamp01(out.reflectivity * 0.78);
                out.transmission = clamp01(out.transmission * 0.54);
            }
            default -> {
 // PHONG/AUTO/default: keep extracted values unchanged.
            }
        }
    }

    private void computeShadowTerminatorPoint(Hit hit,
                                              Triangle tri,
                                              SurfaceState surface,
                                              TraceContext ctx) {
        if (ctx == null) {
            return;
        }
        if (hit == null || surface == null || tri == null) {
            ctx.shadowBaseX = hit != null ? hit.px : 0.0;
            ctx.shadowBaseY = hit != null ? hit.py : 0.0;
            ctx.shadowBaseZ = hit != null ? hit.pz : 0.0;
            return;
        }
        if (tri.flatNormal) {
            ctx.shadowBaseX = hit.px;
            ctx.shadowBaseY = hit.py;
            ctx.shadowBaseZ = hit.pz;
            return;
        }

        double w0 = 1.0 - hit.u - hit.v;

        double geomNx = surface.geomNx;
        double geomNy = surface.geomNy;
        double geomNz = surface.geomNz;

        double n0x = tri.n0x;
        double n0y = tri.n0y;
        double n0z = tri.n0z;
        double n1x = tri.n1x;
        double n1y = tri.n1y;
        double n1z = tri.n1z;
        double n2x = tri.n2x;
        double n2y = tri.n2y;
        double n2z = tri.n2z;

        if (n0x * geomNx + n0y * geomNy + n0z * geomNz < 0.0) {
            n0x = -n0x;
            n0y = -n0y;
            n0z = -n0z;
        }
        if (n1x * geomNx + n1y * geomNy + n1z * geomNz < 0.0) {
            n1x = -n1x;
            n1y = -n1y;
            n1z = -n1z;
        }
        if (n2x * geomNx + n2y * geomNy + n2z * geomNz < 0.0) {
            n2x = -n2x;
            n2y = -n2y;
            n2z = -n2z;
        }

        double t0x = hit.px - tri.ax;
        double t0y = hit.py - tri.ay;
        double t0z = hit.pz - tri.az;

        double t1x = hit.px - tri.bx;
        double t1y = hit.py - tri.by;
        double t1z = hit.pz - tri.bz;

        double t2x = hit.px - tri.cx;
        double t2y = hit.py - tri.cy;
        double t2z = hit.pz - tri.cz;

        double d0 = Math.min(0.0, t0x * n0x + t0y * n0y + t0z * n0z);
        double d1 = Math.min(0.0, t1x * n1x + t1y * n1y + t1z * n1z);
        double d2 = Math.min(0.0, t2x * n2x + t2y * n2y + t2z * n2z);

        double q0x = t0x - d0 * n0x;
        double q0y = t0y - d0 * n0y;
        double q0z = t0z - d0 * n0z;

        double q1x = t1x - d1 * n1x;
        double q1y = t1y - d1 * n1y;
        double q1z = t1z - d1 * n1z;

        double q2x = t2x - d2 * n2x;
        double q2y = t2y - d2 * n2y;
        double q2z = t2z - d2 * n2z;

        double shadowBaseX = hit.px + w0 * q0x + hit.u * q1x + hit.v * q2x;
        double shadowBaseY = hit.py + w0 * q0y + hit.u * q1y + hit.v * q2y;
        double shadowBaseZ = hit.pz + w0 * q0z + hit.u * q1z + hit.v * q2z;
        if (!Double.isFinite(shadowBaseX)
                || !Double.isFinite(shadowBaseY)
                || !Double.isFinite(shadowBaseZ)) {
            ctx.shadowBaseX = hit.px;
            ctx.shadowBaseY = hit.py;
            ctx.shadowBaseZ = hit.pz;
            return;
        }

        ctx.shadowBaseX = shadowBaseX;
        ctx.shadowBaseY = shadowBaseY;
        ctx.shadowBaseZ = shadowBaseZ;
    }

    private void prepareShadowRay(Hit hit, SurfaceState surface, double lx, double ly, double lz, TraceContext ctx) {
        Triangle tri = hit.triangle;

        computeShadowTerminatorPoint(hit, tri, surface, ctx);

        double geomNx = surface.geomNx;
        double geomNy = surface.geomNy;
        double geomNz = surface.geomNz;
        double sign = (geomNx * lx + geomNy * ly + geomNz * lz) >= 0.0 ? 1.0 : -1.0;
        double eps = RAY_EPS;
        double originEps = tri == null ? Math.max(RAY_EPS, 1e-3) : eps;

        ctx.shadowOx = ctx.shadowBaseX + geomNx * sign * originEps;
        ctx.shadowOy = ctx.shadowBaseY + geomNy * sign * originEps;
        ctx.shadowOz = ctx.shadowBaseZ + geomNz * sign * originEps;
        ctx.shadowTMin = eps;
    }

    private Vec3 applyNormalMap(PhongMaterial material,
                                Triangle tri,
                                double w0,
                                double u,
                                double v,
                                double nx,
                                double ny,
                                double nz,
                                TraceContext ctx) {
        if (material == null || !canSampleTextureMap(material.getNormalMap(), tri)) {
            return ctx.tmpVec0.set(nx, ny, nz).normalizeInPlace();
        }
        int texel = sampleTextureMap(material.getNormalMap(), tri, w0, u, v);
        Vec3 normal = ctx.tmpVec0.set(nx, ny, nz).normalizeInPlace();
        Vec3 tangent = ctx.tmpVec1.set(tri.tangentX, tri.tangentY, tri.tangentZ).normalizeInPlace();
        if (tangent.lengthSquared() < 1e-10) {
            tangent = buildFallbackTangent(normal, tangent, ctx.tmpVec3);
        }
        Vec3 bitangent = normal.cross(tangent, ctx.tmpVec2).normalizeInPlace();
        if (bitangent.lengthSquared() < 1e-10) {
            buildFallbackTangent(normal, ctx.tmpVec4, ctx.tmpVec3);
            bitangent = ctx.tmpVec4.cross(normal, bitangent).normalizeInPlace();
        }
        tangent = bitangent.cross(normal, tangent).normalizeInPlace();
        double tx = (((texel >> 16) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double ty = (((texel >> 8) & 0xFF) / 255.0 * 2.0 - 1.0) * material.getNormalScale();
        double tz = (texel & 0xFF) / 255.0 * 2.0 - 1.0;
        return ctx.tmpVec5.set(tangent)
                .mulInPlace(tx)
                .addScaledInPlace(bitangent, ty)
                .addScaledInPlace(normal, tz)
                .normalizeInPlace();
    }

    private boolean canSampleTextureMap(TextureMap map, Triangle tri) {
        if (map == null || !map.hasTexture() || tri == null) {
            return false;
        }
        return map.getTexCoord() > 0 ? tri.hasUV2 : tri.hasUV;
    }

    private int sampleTextureMap(TextureMap map, Triangle tri, double w0, double u, double v) {
        boolean useUv1 = map.getTexCoord() > 0 && tri.hasUV2;
        double uvU = useUv1
                ? tri.u0b * w0 + tri.u1b * u + tri.u2b * v
                : tri.u0 * w0 + tri.u1 * u + tri.u2 * v;
        double uvV = useUv1
                ? tri.v0b * w0 + tri.v1b * u + tri.v2b * v
                : tri.v0 * w0 + tri.v1 * u + tri.v2 * v;
        double scaledU = uvU * map.getScaleU();
        double scaledV = uvV * map.getScaleV();
        double sin = Math.sin(map.getRotation());
        double cos = Math.cos(map.getRotation());
        double rotatedU = scaledU * cos - scaledV * sin + map.getOffsetU();
        double rotatedV = scaledU * sin + scaledV * cos + map.getOffsetV();
        Texture texture = map.getTexture();
        return map.isLinear()
                ? texture.sampleBilinear(rotatedU, rotatedV, map.isFlipV())
                : texture.sampleNearest(rotatedU, rotatedV, map.isFlipV());
    }

    private static Vec3 computeTriangleTangent(Vec3 a,
                                               Vec3 b,
                                               Vec3 c,
                                               double u0,
                                               double v0,
                                               double u1,
                                               double v1,
                                               double u2,
                                               double v2,
                                               Vec3 fallbackNormal) {
        double du1 = u1 - u0;
        double dv1 = v1 - v0;
        double du2 = u2 - u0;
        double dv2 = v2 - v0;
        double denom = du1 * dv2 - du2 * dv1;
        if (Math.abs(denom) < 1e-8) {
            return buildFallbackTangent(fallbackNormal);
        }
        double r = 1.0 / denom;
        Vec3 tangent = b.sub(a).mul(dv2).sub(c.sub(a).mul(dv1)).mul(r).normalize();
        if (tangent.lengthSquared() < 1e-10) {
            return buildFallbackTangent(fallbackNormal);
        }
        return tangent;
    }

    private static Vec3 buildFallbackTangent(Vec3 normal) {
        return buildFallbackTangent(normal, new Vec3(), new Vec3());
    }

    private static Vec3 buildFallbackTangent(Vec3 normal, Vec3 out, Vec3 axisScratch) {
        Vec3 axis = axisScratch == null ? new Vec3() : axisScratch;
        if (Math.abs(normal.y) < 0.92) {
            axis.set(Vec3.UP);
        } else {
            axis.set(1.0, 0.0, 0.0);
        }
        axis.cross(normal, out).normalizeInPlace();
        if (out.lengthSquared() < 1e-10) {
            axis.set(0.0, 0.0, 1.0);
            axis.cross(normal, out).normalizeInPlace();
        }
        if (out.lengthSquared() < 1e-10) {
            out.set(1.0, 0.0, 0.0);
        }
        return out;
    }

    private static double schlickFresnel(double cosTheta, double ior) {
        double r0 = (1.0 - ior) / (1.0 + ior);
        r0 *= r0;
        double m = 1.0 - clamp01(cosTheta);
        return clamp01(r0 + (1.0 - r0) * m * m * m * m * m);
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    boolean isPreviewStillFullTierActive() {
        return previewQualityLadderEnabled
                && !previewMotionActive
                && !referenceMode
                && accumulatedSamples >= STILL_TIER5_MIN_SAMPLES
                && "PT_PROGRESSIVE_STILL_T5_REFERENCE_READY".equals(activePreviewQualityTier);
    }

    private double resolveAdvancedOpticsWeight(boolean fullStillTierActive) {
        if (referenceMode) {
            return 1.0;
        }
        if (previewMotionActive) {
            return 0.0;
        }
        if (!fullStillTierActive) {
            return 0.0;
        }
        if (accumulatedSamples <= ADVANCED_OPTICS_UNLOCK_SAMPLES) {
            return 0.0;
        }
        double t = (accumulatedSamples - ADVANCED_OPTICS_UNLOCK_SAMPLES)
                / (double) Math.max(1L, ADVANCED_OPTICS_FULL_SAMPLES - ADVANCED_OPTICS_UNLOCK_SAMPLES);
        return clamp01(t);
    }

    private static double dispersedIor(double baseIor, double dispersion, double opticsWeight, double channelBias) {
        double spread = DISPERSION_IOR_SPREAD
 * clamp01(dispersion)
 * clamp01(opticsWeight)
 * Math.max(0.25, baseIor - 1.0);
        return Math.max(1.0, baseIor + spread * channelBias);
    }

    private static double resolveTransmissionIor(double baseIor,
                                                 double dispersion,
                                                 double opticsWeight,
                                                 double wavelengthSample) {
        double pick = clamp01(wavelengthSample);
        double bias = pick < (1.0 / 3.0) ? -1.0 : (pick < (2.0 / 3.0) ? 0.0 : 1.0);
        return dispersedIor(baseIor, dispersion, opticsWeight, bias);
    }

    private static double resolveCausticPathGuidance(int bounceDepth,
                                                     boolean previousDeltaEvent,
                                                     double previousBsdfPdf) {
        if (bounceDepth <= 0) {
            return 0.0;
        }
        double depthGate = clamp01((bounceDepth + 0.25) / 2.5);
        double eventGuide = previousDeltaEvent ? 1.0 : 0.30;
        double pdfGuide = previousDeltaEvent
                ? 1.0
                : clamp01(Math.sqrt(Math.max(0.0, previousBsdfPdf)) * 2.2);
        return clamp01((0.35 + 0.65 * depthGate) * (0.65 * eventGuide + 0.35 * pdfGuide));
    }

    private static double resolveCausticCarry(SurfaceState surface, double opticsWeight, double pathGuide) {
        if (surface == null || opticsWeight <= 0.0) {
            return 0.0;
        }
        double transmission = clamp01(surface.transmission);
        double focus = 1.0 - clamp01(surface.roughness * 1.35);
        double iorFocus = clamp01((surface.refractiveIndex - 1.0) / 1.2);
        double dispersionFocus = 0.8 + 0.4 * clamp01(surface.dispersion);
        double guidance = 0.35 + 0.65 * clamp01(pathGuide);
        return clamp01(transmission * focus * iorFocus * dispersionFocus * guidance * clamp01(opticsWeight));
    }

    private static double resolveCausticBoost(double opticsWeight,
                                              double causticCarry,
                                              double pathGuide,
                                              SurfaceState surface) {
        if (surface == null || opticsWeight <= 0.0 || causticCarry <= 1e-6) {
            return 1.0;
        }
        double receiverFactor = 1.0 - clamp01(surface.roughness * 0.82);
        double guidedCarry = Math.pow(clamp01(causticCarry), 0.75) * (0.45 + 0.55 * clamp01(pathGuide));
        double boost = 1.0 + CAUSTIC_BOOST_MAX * guidedCarry * receiverFactor * clamp01(opticsWeight);
        return Math.max(1.0, boost);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    boolean intersectClosest(double ox, double oy, double oz,
                                     double dx, double dy, double dz,
                                     double tMin, double tMax,
                                     Hit outHit, TraceContext ctx) {
        if (bvhRoot == null) {
            return false;
        }

        double invDx = Math.abs(dx) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDy = Math.abs(dy) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        double invDz = Math.abs(dz) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dz;

        int sp = 0;
        ctx.nodeStack[sp++] = bvhRoot;
        boolean hitAnything = false;
        double closest = tMax;

        while (sp > 0) {
            PathTracerBVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, closest)) {
                continue;
            }

            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    Triangle tri = triangles[triangleOrder[i]];
                    if (intersectTriangle(tri, ox, oy, oz, dx, dy, dz, tMin, closest, ctx.tempHit)) {
                        hitAnything = true;
                        closest = ctx.tempHit.t;
                        outHit.copyFrom(ctx.tempHit);
                    }
                }
                continue;
            }

            PathTracerBVHNode left = node.left;
            PathTracerBVHNode right = node.right;
            double tLeft = left != null ? aabbEntry(left, ox, oy, oz, invDx, invDy, invDz, tMin, closest) : INF_T;
            double tRight = right != null ? aabbEntry(right, ox, oy, oz, invDx, invDy, invDz, tMin, closest) : INF_T;

            if (tLeft < tRight) {
                if (tRight < INF_T) {
                    ctx.nodeStack[sp++] = right;
                }
                if (tLeft < INF_T) {
                    ctx.nodeStack[sp++] = left;
                }
            } else {
                if (tLeft < INF_T) {
                    ctx.nodeStack[sp++] = left;
                }
                if (tRight < INF_T) {
                    ctx.nodeStack[sp++] = right;
                }
            }
        }

        return hitAnything;
    }

    private boolean intersectAny(double ox, double oy, double oz,
                                 double dx, double dy, double dz,
                                 double tMin, double tMax,
                                 TraceContext ctx) {
        if (bvhRoot == null) {
            return false;
        }

        double invDx = Math.abs(dx) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDy = Math.abs(dy) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        double invDz = Math.abs(dz) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dz;

        int sp = 0;
        ctx.nodeStack[sp++] = bvhRoot;

        while (sp > 0) {
            PathTracerBVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, tMax)) {
                continue;
            }
            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    Triangle tri = triangles[triangleOrder[i]];
                    if (intersectTriangle(tri, ox, oy, oz, dx, dy, dz, tMin, tMax, ctx.tempHit)
                            && shadowOccludes(tri, ctx.tempHit, dx, dy, dz, ctx)) {
                        return true;
                    }
                }
                continue;
            }
            if (node.left != null) {
                ctx.nodeStack[sp++] = node.left;
            }
            if (node.right != null) {
                ctx.nodeStack[sp++] = node.right;
            }
        }
        return false;
    }

    private boolean shadowOccludes(Triangle tri, Hit hit, double dx, double dy, double dz, TraceContext ctx) {
        sampleSurface(tri, hit, dx, dy, dz, ctx.shadowSurface, ctx);
        if (ctx.shadowSurface.discard) {
            return false;
        }
        double opaqueOcclusion = clamp01(ctx.shadowSurface.opacity * (1.0 - ctx.shadowSurface.transmission));
        double glassOcclusion = 0.0;
        if (ctx.shadowSurface.transmission > 0.55 && ctx.shadowSurface.refractiveIndex > 1.01) {
            double iorFocus = clamp01((ctx.shadowSurface.refractiveIndex - 1.0) / 1.2);
            glassOcclusion = clamp01(ctx.shadowSurface.opacity
 * (0.08 + 0.18 * iorFocus + 0.32 * clamp01(ctx.shadowSurface.reflectivity)));
        }
        double occlusion = Math.max(opaqueOcclusion, glassOcclusion);
        return occlusion > 0.05;
    }

    private boolean intersectTriangle(Triangle tri,
                                      double ox, double oy, double oz,
                                      double dx, double dy, double dz,
                                      double tMin, double tMax,
                                      Hit out) {
        double pvx = dy * tri.e2z - dz * tri.e2y;
        double pvy = dz * tri.e2x - dx * tri.e2z;
        double pvz = dx * tri.e2y - dy * tri.e2x;

        double det = tri.e1x * pvx + tri.e1y * pvy + tri.e1z * pvz;
        if (Math.abs(det) < 1e-12) {
            return false;
        }
        double invDet = 1.0 / det;

        double tvx = ox - tri.ax;
        double tvy = oy - tri.ay;
        double tvz = oz - tri.az;
        double u = (tvx * pvx + tvy * pvy + tvz * pvz) * invDet;
        if (u < 0.0 || u > 1.0) {
            return false;
        }

        double qvx = tvy * tri.e1z - tvz * tri.e1y;
        double qvy = tvz * tri.e1x - tvx * tri.e1z;
        double qvz = tvx * tri.e1y - tvy * tri.e1x;
        double v = (dx * qvx + dy * qvy + dz * qvz) * invDet;
        if (v < 0.0 || (u + v) > 1.0) {
            return false;
        }

        double t = (tri.e2x * qvx + tri.e2y * qvy + tri.e2z * qvz) * invDet;
        if (t <= tMin || t >= tMax) {
            return false;
        }

        out.t = t;
        out.u = u;
        out.v = v;
        out.px = ox + dx * t;
        out.py = oy + dy * t;
        out.pz = oz + dz * t;
        out.triangle = tri;
        return true;
    }

    private boolean intersectsAabb(PathTracerBVHNode node,
                                   double ox, double oy, double oz,
                                   double invDx, double invDy, double invDz,
                                   double tMin, double tMax) {
        double t0 = (node.minX - ox) * invDx;
        double t1 = (node.maxX - ox) * invDx;
        double near = Math.min(t0, t1);
        double far = Math.max(t0, t1);

        t0 = (node.minY - oy) * invDy;
        t1 = (node.maxY - oy) * invDy;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        t0 = (node.minZ - oz) * invDz;
        t1 = (node.maxZ - oz) * invDz;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        return far >= Math.max(tMin, near) && near <= tMax;
    }

    private double aabbEntry(PathTracerBVHNode node,
                             double ox, double oy, double oz,
                             double invDx, double invDy, double invDz,
                             double tMin, double tMax) {
        double t0 = (node.minX - ox) * invDx;
        double t1 = (node.maxX - ox) * invDx;
        double near = Math.min(t0, t1);
        double far = Math.max(t0, t1);

        t0 = (node.minY - oy) * invDy;
        t1 = (node.maxY - oy) * invDy;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        t0 = (node.minZ - oz) * invDz;
        t1 = (node.maxZ - oz) * invDz;
        near = Math.max(near, Math.min(t0, t1));
        far = Math.min(far, Math.max(t0, t1));

        if (far >= Math.max(tMin, near) && near <= tMax) {
            return near;
        }
        return INF_T;
    }

    private void generatePrimaryRay(CameraState camera, int px, int py, SplitMix64 rng, TraceContext ctx) {
        generatePrimaryRay(camera, px, py, rng.nextDouble(), rng.nextDouble(), rng.nextDouble(), ctx);
    }

    private void generatePrimaryRay(CameraState camera, int px, int py, double sampleX, double sampleY, TraceContext ctx) {
        generatePrimaryRay(camera, px, py, sampleX, sampleY, 0.5, ctx);
    }

    private void generatePrimaryRay(CameraState camera,
                                    int px,
                                    int py,
                                    double sampleX,
                                    double sampleY,
                                    double sampleTime,
                                    TraceContext ctx) {
        resetPrimaryGuideState(ctx);
        double ndcX = ((px + sampleX) / camera.width) * 2.0 - 1.0;
        double ndcY = 1.0 - ((py + sampleY) / camera.height) * 2.0;

        double shutterT = camera.motionBlurEnabled
            ? lerp(camera.shutterOpen, camera.shutterClose, clamp01(sampleTime))
            : 0.0;
        double camPx = camera.hasMotionSource ? lerp(camera.motionPx, camera.px, shutterT) : camera.px;
        double camPy = camera.hasMotionSource ? lerp(camera.motionPy, camera.py, shutterT) : camera.py;
        double camPz = camera.hasMotionSource ? lerp(camera.motionPz, camera.pz, shutterT) : camera.pz;

        double fwdX = camera.hasMotionSource ? lerp(camera.motionFx, camera.fx, shutterT) : camera.fx;
        double fwdY = camera.hasMotionSource ? lerp(camera.motionFy, camera.fy, shutterT) : camera.fy;
        double fwdZ = camera.hasMotionSource ? lerp(camera.motionFz, camera.fz, shutterT) : camera.fz;
        double fwdLenSq = fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ;
        if (fwdLenSq < 1e-12) {
            fwdX = camera.fx;
            fwdY = camera.fy;
            fwdZ = camera.fz;
            fwdLenSq = fwdX * fwdX + fwdY * fwdY + fwdZ * fwdZ;
        }
        double invFwd = 1.0 / Math.sqrt(Math.max(1e-12, fwdLenSq));
        fwdX *= invFwd;
        fwdY *= invFwd;
        fwdZ *= invFwd;

        double upX = camera.hasMotionSource ? lerp(camera.motionUx, camera.ux, shutterT) : camera.ux;
        double upY = camera.hasMotionSource ? lerp(camera.motionUy, camera.uy, shutterT) : camera.uy;
        double upZ = camera.hasMotionSource ? lerp(camera.motionUz, camera.uz, shutterT) : camera.uz;
        double rightX = fwdY * upZ - fwdZ * upY;
        double rightY = fwdZ * upX - fwdX * upZ;
        double rightZ = fwdX * upY - fwdY * upX;
        double rightLenSq = rightX * rightX + rightY * rightY + rightZ * rightZ;
        if (rightLenSq < 1e-12) {
            rightX = camera.rx;
            rightY = camera.ry;
            rightZ = camera.rz;
            rightLenSq = rightX * rightX + rightY * rightY + rightZ * rightZ;
        }
        double invRight = 1.0 / Math.sqrt(Math.max(1e-12, rightLenSq));
        rightX *= invRight;
        rightY *= invRight;
        rightZ *= invRight;

        upX = rightY * fwdZ - rightZ * fwdY;
        upY = rightZ * fwdX - rightX * fwdZ;
        upZ = rightX * fwdY - rightY * fwdX;
        double upLenSq = upX * upX + upY * upY + upZ * upZ;
        if (upLenSq < 1e-12) {
            upX = camera.ux;
            upY = camera.uy;
            upZ = camera.uz;
            upLenSq = upX * upX + upY * upY + upZ * upZ;
        }
        double invUp = 1.0 / Math.sqrt(Math.max(1e-12, upLenSq));
        upX *= invUp;
        upY *= invUp;
        upZ *= invUp;

        if (camera.perspective) {
            double sx = ndcX * camera.aspect * camera.tanHalfFov;
            double sy = ndcY * camera.tanHalfFov;
            double dx = fwdX + rightX * sx + upX * sy;
            double dy = fwdY + rightY * sx + upY * sy;
            double dz = fwdZ + rightZ * sx + upZ * sy;
            double invLen = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);
            dx *= invLen;
            dy *= invLen;
            dz *= invLen;

            double rayOx = camPx;
            double rayOy = camPy;
            double rayOz = camPz;
            if (camera.dofEnabled && camera.lensRadius > 1e-8 && camera.focusDistance > 1e-6) {
                double diskU = Math.sqrt(Math.max(0.0, sampleX));
                double diskPhi = 2.0 * PI * sampleY;
                double lensU = Math.cos(diskPhi) * diskU * camera.lensRadius;
                double lensV = Math.sin(diskPhi) * diskU * camera.lensRadius;
                rayOx += rightX * lensU + upX * lensV;
                rayOy += rightY * lensU + upY * lensV;
                rayOz += rightZ * lensU + upZ * lensV;

                double focusDenom = Math.max(1e-5, dx * fwdX + dy * fwdY + dz * fwdZ);
                double focusT = camera.focusDistance / focusDenom;
                double focusX = camPx + dx * focusT;
                double focusY = camPy + dy * focusT;
                double focusZ = camPz + dz * focusT;
                dx = focusX - rayOx;
                dy = focusY - rayOy;
                dz = focusZ - rayOz;
                double invFocusLen = 1.0 / Math.sqrt(Math.max(1e-12, dx * dx + dy * dy + dz * dz));
                dx *= invFocusLen;
                dy *= invFocusLen;
                dz *= invFocusLen;
            }

            ctx.rayOx = rayOx;
            ctx.rayOy = rayOy;
            ctx.rayOz = rayOz;
            ctx.rayDx = dx;
            ctx.rayDy = dy;
            ctx.rayDz = dz;
            return;
        }

        double camX = (ndcX - camera.orthoM03) / camera.orthoM00;
        double camY = (ndcY - camera.orthoM13) / camera.orthoM11;
        ctx.rayOx = camPx + rightX * camX + upX * camY + fwdX * camera.near;
        ctx.rayOy = camPy + rightY * camX + upY * camY + fwdY * camera.near;
        ctx.rayOz = camPz + rightZ * camX + upZ * camY + fwdZ * camera.near;
        ctx.rayDx = fwdX;
        ctx.rayDy = fwdY;
        ctx.rayDz = fwdZ;
    }

    private void beginPrimaryGuideCapture(TraceContext ctx) {
        resetPrimaryGuideState(ctx);
        ctx.primaryGuidePending = true;
    }

    private void resetPrimaryGuideState(TraceContext ctx) {
        ctx.primaryGuidePending = true;
        ctx.primaryGuideCaptured = false;
        ctx.primaryGuideHasHit = false;
        ctx.primaryGuideTravel = 0.0;
        ctx.primaryGuideDepth = Double.POSITIVE_INFINITY;
        ctx.primaryGuideNx = 0.0;
        ctx.primaryGuideNy = 0.0;
        ctx.primaryGuideNz = 0.0;
        ctx.primaryGuideBaseR = 0.0;
        ctx.primaryGuideBaseG = 0.0;
        ctx.primaryGuideBaseB = 0.0;
        ctx.primaryGuideRoughness = 1.0;
        ctx.primaryGuideSpecularity = 0.0;
        ctx.primaryGuideEmissionLuma = 0.0;
    }

    void advancePrimaryGuide(TraceContext ctx) {
        if (ctx.primaryGuidePending && !ctx.primaryGuideCaptured) {
            ctx.primaryGuideTravel += ctx.hit.t;
        }
    }

    private void capturePrimaryGuideSurface(TraceContext ctx) {
        if (!ctx.primaryGuidePending || ctx.primaryGuideCaptured) {
            return;
        }
        ctx.primaryGuideCaptured = true;
        ctx.primaryGuideHasHit = true;
        ctx.primaryGuideDepth = ctx.primaryGuideTravel + ctx.hit.t;
        ctx.primaryGuideNx = ctx.surface.nx;
        ctx.primaryGuideNy = ctx.surface.ny;
        ctx.primaryGuideNz = ctx.surface.nz;
        ctx.primaryGuideBaseR = clamp01(ctx.surface.baseR);
        ctx.primaryGuideBaseG = clamp01(ctx.surface.baseG);
        ctx.primaryGuideBaseB = clamp01(ctx.surface.baseB);
        ctx.primaryGuideRoughness = clamp01(ctx.surface.roughness);
        ctx.primaryGuideSpecularity = clamp01(Math.max(ctx.surface.reflectivity,
                Math.max(ctx.surface.specR, Math.max(ctx.surface.specG, ctx.surface.specB))));
        ctx.primaryGuideEmissionLuma = DenoiseSupport.luminance(
                ctx.surface.emissionR,
                ctx.surface.emissionG,
                ctx.surface.emissionB);
    }

    void markPrimaryGuideMiss(TraceContext ctx) {
        if (ctx.primaryGuidePending && !ctx.primaryGuideCaptured) {
            ctx.primaryGuideCaptured = true;
            ctx.primaryGuideHasHit = false;
        }
    }

    private void storePrimaryGuide(int idx, TraceContext ctx) {
        if (ctx.primaryGuideCaptured && ctx.primaryGuideHasHit) {
            guideDepth[idx] = (float) ctx.primaryGuideDepth;
            int base = idx * 3;
            guideNormal[base] = (float) ctx.primaryGuideNx;
            guideNormal[base + 1] = (float) ctx.primaryGuideNy;
            guideNormal[base + 2] = (float) ctx.primaryGuideNz;
            guideAlbedo[base] = (float) ctx.primaryGuideBaseR;
            guideAlbedo[base + 1] = (float) ctx.primaryGuideBaseG;
            guideAlbedo[base + 2] = (float) ctx.primaryGuideBaseB;
            guideRoughness[idx] = (float) ctx.primaryGuideRoughness;
        } else {
            clearPrimaryGuide(idx);
        }
        ctx.primaryGuidePending = false;
    }

    private void clearPrimaryGuide(int idx) {
        guideDepth[idx] = Float.POSITIVE_INFINITY;
        int base = idx * 3;
        guideNormal[base] = 0.0f;
        guideNormal[base + 1] = 0.0f;
        guideNormal[base + 2] = 0.0f;
        guideAlbedo[base] = 0.0f;
        guideAlbedo[base + 1] = 0.0f;
        guideAlbedo[base + 2] = 0.0f;
        guideRoughness[idx] = 1.0f;
    }

    private CameraState buildCameraState(Camera camera,
                                         int width,
                                         int height,
                                         PreviewCameraResetSupport.Snapshot motionSource,
                                         PreviewCameraResetSupport.Snapshot currentSnapshot) {
        CameraState s = new CameraState();
        s.width = width;
        s.height = height;
        s.px = camera.getPosition().x;
        s.py = camera.getPosition().y;
        s.pz = camera.getPosition().z;

        Vec3 f = camera.getForward().normalize();
        Vec3 r = camera.getRight().normalize();
        Vec3 u = camera.getUp().normalize();
        s.fx = f.x;
        s.fy = f.y;
        s.fz = f.z;
        s.rx = r.x;
        s.ry = r.y;
        s.rz = r.z;
        s.ux = u.x;
        s.uy = u.y;
        s.uz = u.z;
        s.near = camera.getNear();
        double featureWeight = sampleDrivenFeatureWeight;
        s.dofEnabled = cameraDofEnabled && featureWeight > 1e-3;
        s.lensRadius = Math.max(0.0, cameraAperture) * 0.5 * featureWeight;
        s.focusDistance = Math.max(0.05, cameraFocusDistance);
        s.motionBlurEnabled = cameraMotionBlurEnabled && (cameraShutterFraction * featureWeight) > 1e-4;

        if (s.motionBlurEnabled && motionSource != null && currentSnapshot != null) {
            s.hasMotionSource = true;
            s.motionPx = motionSource.px;
            s.motionPy = motionSource.py;
            s.motionPz = motionSource.pz;
            s.motionFx = motionSource.fx;
            s.motionFy = motionSource.fy;
            s.motionFz = motionSource.fz;
            s.motionUx = motionSource.ux;
            s.motionUy = motionSource.uy;
            s.motionUz = motionSource.uz;
            s.shutterOpen = 0.0;
            s.shutterClose = clamp01(cameraShutterFraction * featureWeight);
        }

        if (camera instanceof PerspectiveCamera pc) {
            s.perspective = true;
            s.aspect = pc.getAspectRatio();
            s.tanHalfFov = Math.tan(pc.getFovY() * 0.5);
            s.orthoM00 = 1.0;
            s.orthoM11 = 1.0;
            s.orthoM03 = 0.0;
            s.orthoM13 = 0.0;
            return s;
        }

        Mat4 proj = camera.getProjectionMatrix();
        s.perspective = false;
        s.aspect = (double) width / Math.max(1.0, (double) height);
        s.tanHalfFov = Math.tan(Math.toRadians(35.0));
        s.orthoM00 = Math.abs(proj.get(0, 0)) < 1e-12 ? 1.0 : proj.get(0, 0);
        s.orthoM11 = Math.abs(proj.get(1, 1)) < 1e-12 ? 1.0 : proj.get(1, 1);
        s.orthoM03 = proj.get(0, 3);
        s.orthoM13 = proj.get(1, 3);
        return s;
    }

    private void rebuildGeometry(Scene scene) {
        List<Triangle> built = new ArrayList<>();
        for (Entity entity : scene.getAllMeshEntities()) {
            Mesh mesh = entity.getMesh();
            if (mesh == null) {
                continue;
            }
            float[] positions = mesh.getPositions();
            int[] indices = mesh.getIndices();
            if (positions == null || indices == null || indices.length < 3) {
                continue;
            }

            float[] normals = mesh.getNormals();
            float[] uvs = mesh.getUVs();
            float[] uv2s = mesh.getUV2s();
            PhongMaterial material = toPhongMaterial(entity.getMaterial());
            Mat4 model = entity.getWorldMatrix();
            Mat3 normalMatrix;
            try {
                normalMatrix = model.inverse().transpose().toMat3();
            } catch (IllegalStateException ex) {
                normalMatrix = Mat3.identity();
            }

            double baseR = material.getDiffuseColor().x;
            double baseG = material.getDiffuseColor().y;
            double baseB = material.getDiffuseColor().z;
            boolean floorGrid = "floor-grid".equals(material.getName());

            for (int i = 0; i < indices.length; i += 3) {
                int i0 = indices[i];
                int i1 = indices[i + 1];
                int i2 = indices[i + 2];

                int p0 = i0 * 3;
                int p1 = i1 * 3;
                int p2 = i2 * 3;

                Vec3 a = model.transformPoint(new Vec3(positions[p0], positions[p0 + 1], positions[p0 + 2]));
                Vec3 b = model.transformPoint(new Vec3(positions[p1], positions[p1 + 1], positions[p1 + 2]));
                Vec3 c = model.transformPoint(new Vec3(positions[p2], positions[p2 + 1], positions[p2 + 2]));

                Vec3 na;
                Vec3 nb;
                Vec3 nc;
                if (normals != null && normals.length >= p2 + 3) {
                    na = normalMatrix.transform(new Vec3(normals[p0], normals[p0 + 1], normals[p0 + 2])).normalize();
                    nb = normalMatrix.transform(new Vec3(normals[p1], normals[p1 + 1], normals[p1 + 2])).normalize();
                    nc = normalMatrix.transform(new Vec3(normals[p2], normals[p2 + 1], normals[p2 + 2])).normalize();
                } else {
                    Vec3 fn = b.sub(a).cross(c.sub(a)).normalize();
                    na = fn;
                    nb = fn;
                    nc = fn;
                }

                double u0 = 0.0;
                double v0 = 0.0;
                double u1 = 0.0;
                double v1 = 0.0;
                double u2 = 0.0;
                double v2 = 0.0;
                boolean hasUV = false;
                if (uvs != null) {
                    int uv0 = i0 * 2;
                    int uv1 = i1 * 2;
                    int uv2 = i2 * 2;
                    if (uv2 + 1 < uvs.length) {
                        u0 = uvs[uv0];
                        v0 = uvs[uv0 + 1];
                        u1 = uvs[uv1];
                        v1 = uvs[uv1 + 1];
                        u2 = uvs[uv2];
                        v2 = uvs[uv2 + 1];
                        hasUV = true;
                    }
                }

                double u0b = u0;
                double v0b = v0;
                double u1b = u1;
                double v1b = v1;
                double u2b = u2;
                double v2b = v2;
                boolean hasUV2 = false;
                if (uv2s != null) {
                    int uv0i = i0 * 2;
                    int uv1i = i1 * 2;
                    int uv2i = i2 * 2;
                    if (uv2i + 1 < uv2s.length) {
                        u0b = uv2s[uv0i];
                        v0b = uv2s[uv0i + 1];
                        u1b = uv2s[uv1i];
                        v1b = uv2s[uv1i + 1];
                        u2b = uv2s[uv2i];
                        v2b = uv2s[uv2i + 1];
                        hasUV2 = true;
                    }
                }

                boolean tangentUsesUv1 = material.getNormalMap().getTexCoord() > 0 && hasUV2;
                Vec3 tangent = computeTriangleTangent(
                        a, b, c,
                        tangentUsesUv1 ? u0b : u0,
                        tangentUsesUv1 ? v0b : v0,
                        tangentUsesUv1 ? u1b : u1,
                        tangentUsesUv1 ? v1b : v1,
                        tangentUsesUv1 ? u2b : u2,
                        tangentUsesUv1 ? v2b : v2,
                        na
                );

                built.add(new Triangle(
                        a.x, a.y, a.z,
                        b.x, b.y, b.z,
                        c.x, c.y, c.z,
                        na.x, na.y, na.z,
                        nb.x, nb.y, nb.z,
                        nc.x, nc.y, nc.z,
                        u0, v0, u1, v1, u2, v2, hasUV,
                        u0b, v0b, u1b, v1b, u2b, v2b, hasUV2,
                        tangent.x, tangent.y, tangent.z,
                        baseR, baseG, baseB,
                        floorGrid,
                        material
                ));
            }
        }

        triangles = built.toArray(Triangle[]::new);
        triangleCount = triangles.length;
        centroidX = new double[triangleCount];
        centroidY = new double[triangleCount];
        centroidZ = new double[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            centroidX[i] = triangles[i].centroidX;
            centroidY[i] = triangles[i].centroidY;
            centroidZ[i] = triangles[i].centroidZ;
        }
        rebuildEmissiveLightCache();
        rebuildBvh();
    }

    private void rebuildEmissiveLightCache() {
        if (triangleCount == 0) {
            emissiveLights = new EmissiveTriangleLight[0];
            emissiveLightPowerSum = 0.0;
            return;
        }

        for (Triangle tri : triangles) {
            if (tri != null) {
                tri.emissiveLightPower = 0.0;
            }
        }

        List<EmissiveTriangleLight> lights = new ArrayList<>();
        double cumulativePower = 0.0;
        for (Triangle tri : triangles) {
            if (tri == null || tri.material == null || tri.area <= 1e-10) {
                continue;
            }
            Vec3 emissionColor = tri.material.getEmissionColor();
            if (emissionColor == null) {
                continue;
            }
            double emissionStrength = Math.max(0.0, tri.material.getEmissionStrength());
            double emissionR = Math.max(0.0, emissionColor.x * emissionStrength);
            double emissionG = Math.max(0.0, emissionColor.y * emissionStrength);
            double emissionB = Math.max(0.0, emissionColor.z * emissionStrength);
            double emissionLuma = DenoiseSupport.luminance(emissionR, emissionG, emissionB);
            if (emissionLuma <= 1e-6) {
                continue;
            }
            double power = tri.area * emissionLuma * (tri.material.isDoubleSided() ? 2.0 : 1.0);
            if (power <= 1e-8) {
                continue;
            }
            tri.emissiveLightPower = power;
            cumulativePower += power;
            lights.add(new EmissiveTriangleLight(tri, power, cumulativePower, tri.material.isDoubleSided()));
        }
        emissiveLights = lights.toArray(EmissiveTriangleLight[]::new);
        emissiveLightPowerSum = cumulativePower;
    }

    private void rebuildBvh() {
        if (triangleCount == 0) {
            triangleOrder = new int[0];
            bvhRoot = null;
            return;
        }
        triangleOrder = new int[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            triangleOrder[i] = i;
        }
        bvhRoot = buildNode(0, triangleCount);
    }

    private PathTracerBVHNode buildNode(int start, int end) {
        PathTracerBVHNode node = new PathTracerBVHNode();
        node.start = start;
        node.end = end;

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;

        double cMinX = Double.POSITIVE_INFINITY;
        double cMinY = Double.POSITIVE_INFINITY;
        double cMinZ = Double.POSITIVE_INFINITY;
        double cMaxX = Double.NEGATIVE_INFINITY;
        double cMaxY = Double.NEGATIVE_INFINITY;
        double cMaxZ = Double.NEGATIVE_INFINITY;

        for (int i = start; i < end; i++) {
            Triangle tri = triangles[triangleOrder[i]];
            minX = Math.min(minX, tri.minX);
            minY = Math.min(minY, tri.minY);
            minZ = Math.min(minZ, tri.minZ);
            maxX = Math.max(maxX, tri.maxX);
            maxY = Math.max(maxY, tri.maxY);
            maxZ = Math.max(maxZ, tri.maxZ);
            cMinX = Math.min(cMinX, tri.centroidX);
            cMinY = Math.min(cMinY, tri.centroidY);
            cMinZ = Math.min(cMinZ, tri.centroidZ);
            cMaxX = Math.max(cMaxX, tri.centroidX);
            cMaxY = Math.max(cMaxY, tri.centroidY);
            cMaxZ = Math.max(cMaxZ, tri.centroidZ);
        }

        node.minX = minX;
        node.minY = minY;
        node.minZ = minZ;
        node.maxX = maxX;
        node.maxY = maxY;
        node.maxZ = maxZ;

        int count = end - start;
        if (count <= leafSize) {
            return node;
        }

        SahBvhUtil.Split split = SahBvhUtil.findBestSplit(
                triangleOrder,
                start,
                end,
                8,
                this::centroidAxis,
                (triIndex, out) -> {
                    Triangle tri = triangles[triIndex];
                    out.set(tri.minX, tri.minY, tri.minZ, tri.maxX, tri.maxY, tri.maxZ);
                }
        );

        int mid = -1;
        if (split.isValid()) {
            mid = SahBvhUtil.partitionByAxis(triangleOrder, start, end, split.axis, split.position, this::centroidAxis);
        }
        if (mid <= start || mid >= end) {
            double ex = cMaxX - cMinX;
            double ey = cMaxY - cMinY;
            double ez = cMaxZ - cMinZ;
            int axis = 0;
            if (ey > ex && ey >= ez) {
                axis = 1;
            } else if (ez > ex && ez > ey) {
                axis = 2;
            }
            double extent = axis == 0 ? ex : (axis == 1 ? ey : ez);
            if (extent < 1e-10) {
                return node;
            }
            sortRangeByAxis(start, end - 1, axis);
            mid = (start + end) >>> 1;
        }
        if (mid <= start || mid >= end) {
            return node;
        }
        node.left = buildNode(start, mid);
        node.right = buildNode(mid, end);
        return node;
    }

    private void sortRangeByAxis(int lo, int hi, int axis) {
        SahBvhUtil.sortByAxis(triangleOrder, lo, hi, axis, this::centroidAxis);
    }

    private double centroidAxis(int triIndex, int axis) {
        if (axis == 0) {
            return centroidX[triIndex];
        }
        if (axis == 1) {
            return centroidY[triIndex];
        }
        return centroidZ[triIndex];
    }

    private void rebuildLightCache(Scene scene) {
        Vec3 ambient = scene.getAmbientColor();
        Vec3 background = scene.getBackgroundColor();
        if (ambient != null) {
            ambientR = ambient.x;
            ambientG = ambient.y;
            ambientB = ambient.z;
        }
        if (background != null) {
            backgroundR = background.x;
            backgroundG = background.y;
            backgroundB = background.z;
        }
        environmentStrength = Math.max(0.0, scene.getEnvironmentStrength());
        environmentExposure = Math.max(0.0, scene.getEnvironmentExposure());
        environmentYawDegrees = scene.getEnvironmentYawDegrees();
        environmentPitchDegrees = scene.getEnvironmentPitchDegrees();
        rebuildEnvironmentRotationCache();
        environmentMap = scene.getEnvironmentMap();

        List<DirLightCache> dir = new ArrayList<>();
        List<PointLightCache> points = new ArrayList<>();
        for (Light light : scene.getLights()) {
            if (light == null || !light.isEnabled()) {
                continue;
            }
            double lr = light.getColor().x * light.getIntensity();
            double lg = light.getColor().y * light.getIntensity();
            double lb = light.getColor().z * light.getIntensity();

            if (light instanceof DirectionalLight directionalLight) {
                Vec3 d = directionalLight.getDirection();
                double lx = -d.x;
                double ly = -d.y;
                double lz = -d.z;
                double lenSq = lx * lx + ly * ly + lz * lz;
                if (lenSq < 1e-14) {
                    continue;
                }
                double invLen = 1.0 / Math.sqrt(lenSq);
                dir.add(new DirLightCache(lx * invLen, ly * invLen, lz * invLen, lr, lg, lb));
            } else if (light instanceof PointLight pl) {
                Vec3 p = pl.getPosition();
                points.add(new PointLightCache(pl, p.x, p.y, p.z, lr, lg, lb));
            }
        }
        dirLights = dir.toArray(DirLightCache[]::new);
        pointLights = points.toArray(PointLightCache[]::new);
        rebuildEnvironmentSamplingCache();
    }

    private void rebuildEnvironmentSamplingCache() {
        double uniformWeight = 0.18;
        if (environmentMap != null) {
            environmentSkySampleWeight = 0.0;
            environmentGroundSampleWeight = 0.0;
            environmentUniformSampleWeight = 1.0;
            return;
        }
        if (!hasVisibleEnvironment()) {
            environmentSkySampleWeight = 1.0 - uniformWeight;
            environmentGroundSampleWeight = 0.0;
            environmentUniformSampleWeight = uniformWeight;
            return;
        }

        double zenithR = clamp01(backgroundR * 1.35 + 0.05);
        double zenithG = clamp01(backgroundG * 1.45 + 0.08);
        double zenithB = clamp01(backgroundB * 1.70 + 0.16);
        double horizonR = clamp01(backgroundR * 0.95 + 0.18);
        double horizonG = clamp01(backgroundG * 1.00 + 0.16);
        double horizonB = clamp01(backgroundB * 1.05 + 0.12);
        double groundR = clamp01(backgroundR * 0.32 + 0.015);
        double groundG = clamp01(backgroundG * 0.34 + 0.017);
        double groundB = clamp01(backgroundB * 0.36 + 0.020);

        double skyLuma = DenoiseSupport.luminance(
                0.45 * zenithR + 0.55 * horizonR,
                0.45 * zenithG + 0.55 * horizonG,
                0.45 * zenithB + 0.55 * horizonB);
        double groundLuma = DenoiseSupport.luminance(groundR, groundG, groundB);
        double totalLuma = skyLuma + groundLuma;
        if (totalLuma <= 1e-8) {
            environmentSkySampleWeight = 1.0 - uniformWeight;
            environmentGroundSampleWeight = 0.0;
            environmentUniformSampleWeight = uniformWeight;
            return;
        }

        double directionalWeight = 1.0 - uniformWeight;
        environmentSkySampleWeight = directionalWeight * skyLuma / totalLuma;
        environmentGroundSampleWeight = directionalWeight * groundLuma / totalLuma;
        environmentUniformSampleWeight = 1.0 - environmentSkySampleWeight - environmentGroundSampleWeight;
    }

    private long computeGeometrySignature(Scene scene) {
        long h = 0xcbf29ce484222325L;
        for (Entity e : scene.getAllMeshEntities()) {
            Mesh mesh = e.getMesh();
            if (mesh == null) {
                continue;
            }
            h = mixHash(h, System.identityHashCode(mesh));
            h = mixMaterialSignature(h, e.getMaterial());
            h = mixHash(h, e.isVisible() ? 1 : 0);

            Transform t = e.getTransform();
            Vec3 p = t.getPosition();
            Vec3 s = t.getScale();
            Quaternion q = t.getRotation();

            h = mixHash(h, quantizedBits(p.x, 1e-4));
            h = mixHash(h, quantizedBits(p.y, 1e-4));
            h = mixHash(h, quantizedBits(p.z, 1e-4));
            h = mixHash(h, quantizedBits(s.x, 1e-4));
            h = mixHash(h, quantizedBits(s.y, 1e-4));
            h = mixHash(h, quantizedBits(s.z, 1e-4));
            h = mixHash(h, quantizedBits(q.x, 1e-4));
            h = mixHash(h, quantizedBits(q.y, 1e-4));
            h = mixHash(h, quantizedBits(q.z, 1e-4));
            h = mixHash(h, quantizedBits(q.w, 1e-4));
        }
        return h;
    }

    private long mixMaterialSignature(long hash, Material baseMaterial) {
        if (baseMaterial == null) {
            return mixHash(hash, 0);
        }
        PhongMaterial material = toPhongMaterial(baseMaterial);
        hash = mixHash(hash, System.identityHashCode(baseMaterial));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().x));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().y));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDiffuseColor().z));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().x));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().y));
        hash = mixHash(hash, Double.doubleToLongBits(material.getSpecularColor().z));
        hash = mixHash(hash, Double.doubleToLongBits(material.getOpacity()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getRoughness()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getMetallic()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getTransmission()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getRefractiveIndex()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getDispersion()));
        hash = mixHash(hash, Double.doubleToLongBits(material.getEmissionStrength()));
        hash = mixHash(hash, material.isDoubleSided() ? 1 : 0);
        hash = mixHash(hash, material.getAlphaMode().ordinal());
        hash = mixTextureMapSignature(hash, material.getDiffuseMap());
        hash = mixTextureMapSignature(hash, material.getNormalMap());
        hash = mixTextureMapSignature(hash, material.getMetallicRoughnessMap());
        hash = mixTextureMapSignature(hash, material.getEmissiveMap());
        hash = mixHash(hash, material.hasNodeGraph() ? material.getNodeGraph().signature() : 0L);
        return hash;
    }

    private long mixTextureMapSignature(long hash, TextureMap map) {
        if (map == null) {
            return mixHash(hash, 0);
        }
        hash = mixHash(hash, System.identityHashCode(map.getTexture()));
        hash = mixHash(hash, map.isLinear() ? 1 : 0);
        hash = mixHash(hash, map.getTexCoord());
        hash = mixHash(hash, Double.doubleToLongBits(map.getOffsetU()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getOffsetV()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getScaleU()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getScaleV()));
        hash = mixHash(hash, Double.doubleToLongBits(map.getRotation()));
        return hash;
    }

    private long computeLightingSignature(Scene scene) {
        long h = 0xcbf29ce484222325L;
        Vec3 ambient = scene.getAmbientColor();
        Vec3 background = scene.getBackgroundColor();
        if (ambient != null) {
            h = mixHash(h, Double.doubleToLongBits(ambient.x));
            h = mixHash(h, Double.doubleToLongBits(ambient.y));
            h = mixHash(h, Double.doubleToLongBits(ambient.z));
        }
        if (background != null) {
            h = mixHash(h, Double.doubleToLongBits(background.x));
            h = mixHash(h, Double.doubleToLongBits(background.y));
            h = mixHash(h, Double.doubleToLongBits(background.z));
        }
        h = mixHash(h, Double.doubleToLongBits(scene.getEnvironmentStrength()));
        h = mixHash(h, Double.doubleToLongBits(scene.getEnvironmentExposure()));
        h = mixHash(h, stringSignature(scene.getEnvironmentMapKey()));
        for (Light light : scene.getLights()) {
            if (light == null) {
                continue;
            }
            h = mixHash(h, light.isEnabled() ? 1 : 0);
            h = mixHash(h, System.identityHashCode(light.getClass()));
            h = mixHash(h, Double.doubleToLongBits(light.getIntensity()));
            Vec3 c = light.getColor();
            h = mixHash(h, Double.doubleToLongBits(c.x));
            h = mixHash(h, Double.doubleToLongBits(c.y));
            h = mixHash(h, Double.doubleToLongBits(c.z));
            if (light instanceof DirectionalLight directionalLight) {
                Vec3 d = directionalLight.getDirection();
                h = mixHash(h, Double.doubleToLongBits(d.x));
                h = mixHash(h, Double.doubleToLongBits(d.y));
                h = mixHash(h, Double.doubleToLongBits(d.z));
            } else if (light instanceof PointLight pl) {
                Vec3 p = pl.getPosition();
                h = mixHash(h, Double.doubleToLongBits(p.x));
                h = mixHash(h, Double.doubleToLongBits(p.y));
                h = mixHash(h, Double.doubleToLongBits(p.z));
                h = mixHash(h, Double.doubleToLongBits(pl.getConstant()));
                h = mixHash(h, Double.doubleToLongBits(pl.getLinear()));
                h = mixHash(h, Double.doubleToLongBits(pl.getQuadratic()));
                if (pl instanceof ConeLight cl) {
                    Vec3 d = cl.getDirection();
                    h = mixHash(h, Double.doubleToLongBits(d.x));
                    h = mixHash(h, Double.doubleToLongBits(d.y));
                    h = mixHash(h, Double.doubleToLongBits(d.z));
                    h = mixHash(h, Double.doubleToLongBits(cl.getConeAngleDegrees()));
                    h = mixHash(h, Double.doubleToLongBits(cl.getSoftness()));
                } else if (pl instanceof AreaLight al) {
                    Vec3 d = al.getEmissionDirection();
                    h = mixHash(h, Double.doubleToLongBits(d.x));
                    h = mixHash(h, Double.doubleToLongBits(d.y));
                    h = mixHash(h, Double.doubleToLongBits(d.z));
                    h = mixHash(h, Double.doubleToLongBits(al.getSpreadAngleDegrees()));
                    h = mixHash(h, Double.doubleToLongBits(al.getSurfaceRadius()));
                    h = mixHash(h, al.getShadowSamples());
                }
            }
        }
        return h;
    }

    private long stringSignature(String value) {
        if (value == null || value.isEmpty()) {
            return 0L;
        }
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < value.length(); i++) {
            hash = mixHash(hash, value.charAt(i));
        }
        return hash;
    }

    private void applyCameraReset(PreviewCameraResetSupport.ResetKind resetKind) {
        if (resetKind == PreviewCameraResetSupport.ResetKind.SOFT) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.CAMERA_RESETS_SOFT, 1L);
            if (previewMotionActive) {
                softResetAccumulationInvalidateHistory();
                nextTemporalBlendScale = 1.0;
                nextTemporalBlendFrames = 0;
                return;
            }
            softResetAccumulationPreserveHistory();
            if (temporalHistoryValid) {
                nextTemporalBlendScale = 0.24;
                nextTemporalBlendFrames = 1;
            }
            return;
        }
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.CAMERA_RESETS_HARD, 1L);
        nextTemporalBlendScale = 1.0;
        nextTemporalBlendFrames = 0;
        resetAccumulation();
    }

    private PreviewStillTierPlan resolvePreviewStillTierPlan(long completedSamples) {
        int clampedMaxBounces = Math.max(1, maxBounces);
        if (!previewQualityLadderEnabled || previewMotionActive || referenceMode) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_UNRESTRICTED", 1, 1, clampedMaxBounces, true, true);
        }
        if (completedSamples < STILL_TIER1_MIN_SAMPLES) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T0_BASE", 1, 0, Math.min(2, clampedMaxBounces), true, true);
        }
        if (completedSamples < STILL_TIER2_MIN_SAMPLES) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T1_REFLECTIONS", 1, 1, Math.min(3, clampedMaxBounces), true, true);
        }
        if (completedSamples < STILL_TIER3_MIN_SAMPLES) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T2_TRANSMISSION", 1, 1, Math.min(3, clampedMaxBounces), true, true);
        }
        if (completedSamples < STILL_TIER4_MIN_SAMPLES) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T3_LOCAL_LIGHTS", 2, 1, Math.min(4, clampedMaxBounces), true, true);
        }
        if (completedSamples < STILL_TIER5_MIN_SAMPLES) {
            return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T4_DEEP_POLISH", 2, 2, clampedMaxBounces, true, true);
        }
        return new PreviewStillTierPlan("PT_PROGRESSIVE_STILL_T5_REFERENCE_READY", 3, 3, clampedMaxBounces, true, true);
    }

    private void applyPreviewStillTierPlan(long completedSamples) {
        PreviewStillTierPlan plan = resolvePreviewStillTierPlan(completedSamples);
        activePreviewQualityTier = plan.name();
        activeStillEnvironmentSamples = Math.max(0, plan.environmentSamples());
        activeStillEmissiveSamples = Math.max(0, plan.emissiveSamples());
        activeStillMaxBounces = Math.max(1, plan.maxBounces());
        activeStillTransmissionEnabled = plan.transmissionEnabled();
        activeStillDirectLightingEnabled = plan.directLightingEnabled();
    }

    private int resolveEffectiveSamplesPerFrame() {
        int effective = samplesPerFrame;
        if (previewQualityLadderEnabled && previewMotionActive && previewMotionSamplesPerFrameLimit > 0) {
            effective = Math.min(effective, previewMotionSamplesPerFrameLimit);
        }
        return Math.max(1, effective);
    }

    private int resolveEffectiveMaxBounces(long frameSequence) {
        int effective = maxBounces;
        if (!previewQualityLadderEnabled || !previewMotionActive || referenceMode) {
            if (previewQualityLadderEnabled && !previewMotionActive && activeStillMaxBounces > 0) {
                effective = Math.min(effective, activeStillMaxBounces);
            }
            return Math.max(1, effective);
        }
        if (previewMotionBounceLimit > 0) {
            effective = Math.min(effective, previewMotionBounceLimit);
        }
        int cadence = Math.max(1, previewMotionSecondaryCadence);
        if (effective > 1 && cadence > 1 && frameSequence % cadence != 0L) {
            effective = 1;
        }
        return Math.max(1, effective);
    }

    private boolean[] resolveTileRenderPlan(int tileCount, int tileCols, int tileRows, boolean fullFrameCoverage) {
        ensureTileRenderPlanMask(tileCount);
        boolean[] plan = tileRenderPlanMask;
        if (tileCount <= 0) {
            return plan;
        }
        Arrays.fill(plan, 0, tileCount, false);
        if (fullFrameCoverage || !previewQualityLadderEnabled || !previewMotionActive || referenceMode) {
            Arrays.fill(plan, 0, tileCount, true);
            ensureMotionTileLayout(tileCount, tileCols, tileRows);
            int nextEpoch = ++motionTileEpochCounter;
            for (int tile = 0; tile < tileCount; tile++) {
                motionTileEpoch[tile] = nextEpoch;
            }
            return plan;
        }

        ensureMotionTileLayout(tileCount, tileCols, tileRows);
        int cadence = Math.max(1, previewMotionTileSubsetCadence);
        int tilesPerFrame = Math.max(1, (tileCount + cadence - 1) / cadence);
        if (previewSmoothBoostLevel > 0 && cadence > 1) {
            int boosted = tilesPerFrame + Math.max(1, tilesPerFrame / 4);
            tilesPerFrame = Math.min(tileCount, boosted);
        }
        int primaryStart = Math.floorMod(motionTileCursor, tileCount);
        int epoch = ++motionTileEpochCounter;
        int selected = 0;

        for (int i = 0; i < tilesPerFrame; i++) {
            int tile = (primaryStart + i) % tileCount;
            if (!plan[tile]) {
                plan[tile] = true;
                motionTileEpoch[tile] = epoch;
                selected++;
            }
        }
        if (selected == 0) {
            int fallbackTile = primaryStart;
            plan[fallbackTile] = true;
            motionTileEpoch[fallbackTile] = epoch;
        }
        motionTileCursor = (primaryStart + tilesPerFrame) % tileCount;
        return plan;
    }

    private void ensureTileRenderPlanMask(int count) {
        int required = Math.max(1, count);
        if (tileRenderPlanMask.length != required) {
            tileRenderPlanMask = new boolean[required];
        }
    }

    private void writeTilePlanMask(boolean[] tileRenderPlan,
                                   int tileCols,
                                   int tileW,
                                   int tileH,
                                   int fbWidth,
                                   int fbHeight,
                                   boolean[] mask) {
        if (tileRenderPlan == null || tileCols <= 0 || tileW <= 0 || tileH <= 0 || mask == null) {
            return;
        }
        int count = Math.min(mask.length, safePixelCount(fbWidth, fbHeight));
        for (int tile = 0; tile < tileRenderPlan.length; tile++) {
            if (!tileRenderPlan[tile]) {
                continue;
            }
            int tx = tile % tileCols;
            int ty = tile / tileCols;
            int x0 = tx * tileW;
            int y0 = ty * tileH;
            int x1 = Math.min(fbWidth, x0 + tileW);
            int y1 = Math.min(fbHeight, y0 + tileH);
            for (int y = y0; y < y1; y++) {
                int base = y * fbWidth;
                for (int x = x0; x < x1; x++) {
                    int idx = base + x;
                    if (idx >= 0 && idx < count) {
                        mask[idx] = true;
                    }
                }
            }
        }
    }

    private void ensureMotionTileLayout(int tileCount, int tileCols, int tileRows) {
        if (tileCount <= 0) {
            motionTileEpoch = new int[0];
            motionTileLayoutCols = tileCols;
            motionTileLayoutRows = tileRows;
            motionTileCursor = 0;
            previewSmoothFrameCounter = 0;
            previewSmoothDesyncCounter = 0;
            previewSmoothBoostLevel = 0;
            previewSmoothBoostHoldFrames = 0;
            return;
        }
        if (motionTileEpoch.length != tileCount
                || motionTileLayoutCols != tileCols
                || motionTileLayoutRows != tileRows) {
            motionTileEpoch = new int[tileCount];
            Arrays.fill(motionTileEpoch, -1);
            motionTileLayoutCols = tileCols;
            motionTileLayoutRows = tileRows;
            motionTileCursor = 0;
            previewSmoothFrameCounter = 0;
            previewSmoothDesyncCounter = 0;
            previewSmoothBoostLevel = 0;
            previewSmoothBoostHoldFrames = 0;
        }
    }

    private void ensureTemporalUpdateMask(int count) {
        int required = Math.max(1, count);
        if (temporalUpdateMask.length != required) {
            temporalUpdateMask = new boolean[required];
        }
    }

    private void updatePreviewPhaseState() {
        if (!previewQualityLadderEnabled || referenceMode) {
            previewPhase = PreviewPhase.STILL_STEADY;
            previewPhaseFrameSequence = 0;
            return;
        }
        if (previewMotionActive) {
            if (!isMotionPhase(previewPhase)) {
                previewPhase = PreviewPhase.MOTION_ENTER;
                previewPhaseFrameSequence = 0;
            }
        } else if (isMotionPhase(previewPhase)) {
            previewPhase = PreviewPhase.MOTION_EXIT_RESYNC;
            previewPhaseFrameSequence = 0;
        }
    }

    private void advancePreviewPhaseAfterFrame() {
        previewPhaseFrameSequence++;
        if (previewPhase == PreviewPhase.MOTION_ENTER && previewPhaseFrameSequence >= 1) {
            previewPhase = PreviewPhase.MOTION_STEADY;
            previewPhaseFrameSequence = 0;
            return;
        }
        if (previewPhase == PreviewPhase.MOTION_EXIT_RESYNC && previewPhaseFrameSequence >= 1) {
            previewPhase = PreviewPhase.STILL_WARMUP;
            previewPhaseFrameSequence = 0;
            return;
        }
        if (previewPhase == PreviewPhase.STILL_WARMUP && previewPhaseFrameSequence >= PREVIEW_STILL_WARMUP_FRAMES) {
            previewPhase = PreviewPhase.STILL_STEADY;
            previewPhaseFrameSequence = 0;
        }
    }

    private boolean isMotionPhase(PreviewPhase phase) {
        return phase == PreviewPhase.MOTION_ENTER || phase == PreviewPhase.MOTION_STEADY;
    }

    private boolean shouldRenderFullFrameForCurrentPhase() {
        if (!previewQualityLadderEnabled || referenceMode) {
            return true;
        }
        if (previewPhase == PreviewPhase.MOTION_STEADY) {
 // Keep all tiles phase-aligned during motion to prevent per-tile temporal drift.
            return true;
        }
        return true;
    }

    private String resolvePreviewMotionTierName(int effectiveMaxBounces) {
        if (!previewMotionActive || referenceMode) {
            return "PT_PROGRESSIVE_STILL_UNRESTRICTED";
        }
        int subsetCadence = Math.max(1, previewMotionTileSubsetCadence);
        if (previewMotionDominantContributionOnly
                && subsetCadence >= 4
                && effectiveMaxBounces <= 1
                && previewMotionDenoiseCadence >= 8) {
            return "PT_MOVING_EMERGENCY_ULTRA_REDUCED";
        }
        if (previewMotionDominantContributionOnly
                || subsetCadence >= 2
                || effectiveMaxBounces <= 1
                || previewMotionDenoiseCadence >= 5) {
            return "PT_MOVING_EMERGENCY_REDUCED";
        }
        return "PT_MOVING_REDUCED";
    }

    private boolean resolveRunFullDenoise(long frameSequence) {
        if (!previewQualityLadderEnabled || !previewMotionActive || referenceMode) {
            if (previewQualityLadderEnabled && !previewMotionActive && activeStillEnvironmentSamples <= 0) {
                return temporalHistoryValid ? frameSequence % 2L == 0L : true;
            }
            return true;
        }
        int cadence = Math.max(1, previewMotionDenoiseCadence);
        if (cadence <= 1) {
            return true;
        }
        return frameSequence % cadence == 0L;
    }

    private double consumeTemporalBlendScale() {
        if (nextTemporalBlendFrames <= 0) {
            return 1.0;
        }
        double scale = nextTemporalBlendScale;
        nextTemporalBlendFrames--;
        if (nextTemporalBlendFrames <= 0) {
            nextTemporalBlendScale = 1.0;
        }
        return scale;
    }

    private void sampleEnvironment(double dx, double dy, double dz, TraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            ctx.envR = 0.0;
            ctx.envG = 0.0;
            ctx.envB = 0.0;
            return;
        }
        sampleEnvironmentBackground(dx, dy, dz, ctx);
        if (environmentMap == null) {
            addEnvironmentSunGlow(dx, dy, dz, ctx);
        }
        ctx.envR = Math.max(0.0, ctx.envR);
        ctx.envG = Math.max(0.0, ctx.envG);
        ctx.envB = Math.max(0.0, ctx.envB);
    }

    private void sampleEnvironmentBackground(double dx, double dy, double dz, TraceContext ctx) {
        double envScale = effectiveEnvironmentScale();
        if (environmentMap != null) {
            EnvironmentMap.Sample sample = ctx.environmentSample;
            sample.r = 0.0;
            sample.g = 0.0;
            sample.b = 0.0;
            rotateToEnvironment(dx, dy, dz, ctx);
            environmentMap.sample(ctx.environmentDirX, ctx.environmentDirY, ctx.environmentDirZ, sample);
            ctx.envR = sample.r * envScale;
            ctx.envG = sample.g * envScale;
            ctx.envB = sample.b * envScale;
            return;
        }

        double up = clamp01(dy * 0.5 + 0.5);
        double horizon = 1.0 - Math.abs(dy);
        double zenithR = clamp01(backgroundR * 1.35 + 0.05);
        double zenithG = clamp01(backgroundG * 1.45 + 0.08);
        double zenithB = clamp01(backgroundB * 1.70 + 0.16);
        double horizonR = clamp01(backgroundR * 0.95 + 0.18);
        double horizonG = clamp01(backgroundG * 1.00 + 0.16);
        double horizonB = clamp01(backgroundB * 1.05 + 0.12);
        double groundR = clamp01(backgroundR * 0.32 + 0.015);
        double groundG = clamp01(backgroundG * 0.34 + 0.017);
        double groundB = clamp01(backgroundB * 0.36 + 0.020);

        double skyBlend = Math.pow(up, 0.70);
        double skyR = mix(horizonR, zenithR, skyBlend);
        double skyG = mix(horizonG, zenithG, skyBlend);
        double skyB = mix(horizonB, zenithB, skyBlend);
        double groundMix = clamp01(-dy * 0.75);
        ctx.envR = mix(skyR, groundR, groundMix);
        ctx.envG = mix(skyG, groundG, groundMix);
        ctx.envB = mix(skyB, groundB, groundMix);

        double haze = Math.pow(Math.max(0.0, horizon), 4.0) * 0.035;
        ctx.envR = mix(ctx.envR, horizonR, haze);
        ctx.envG = mix(ctx.envG, horizonG, haze);
        ctx.envB = mix(ctx.envB, horizonB, haze);
        ctx.envR *= envScale;
        ctx.envG *= envScale;
        ctx.envB *= envScale;
    }

    private void addEnvironmentSunGlow(double dx, double dy, double dz, TraceContext ctx) {
        double glowR = 0.0;
        double glowG = 0.0;
        double glowB = 0.0;
        for (DirLightCache light : dirLights) {
            double sun = Math.max(0.0, dx * light.lx + dy * light.ly + dz * light.lz);
            if (sun <= 1e-6) {
                continue;
            }
            double glow = Math.pow(sun, 220.0) * 6.5 + Math.pow(sun, 32.0) * 0.25;
            glowR += light.r * glow;
            glowG += light.g * glow;
            glowB += light.b * glow;
        }
        ctx.envR += glowR;
        ctx.envG += glowG;
        ctx.envB += glowB;
    }

    private double sampleEnvironmentBackgroundDirection(SplitMix64 rng, TraceContext ctx) {
        if (environmentMap != null) {
            EnvironmentMap.Sample sample = ctx.environmentSample;
            sample.r = 0.0;
            sample.g = 0.0;
            sample.b = 0.0;
            environmentMap.importanceSample(rng.nextDouble(), rng.nextDouble(), sample);
            rotateFromEnvironment(sample.dx, sample.dy, sample.dz, ctx);
            return sample.pdf;
        }

        double pick = rng.nextDouble();
        if (pick < environmentSkySampleWeight) {
            sampleCosineHemisphere(0.0, 1.0, 0.0, rng, ctx);
        } else if (pick < environmentSkySampleWeight + environmentGroundSampleWeight) {
            sampleCosineHemisphere(0.0, -1.0, 0.0, rng, ctx);
        } else {
            sampleUniformSphere(rng, ctx);
        }
        return referenceEnvironmentBackgroundPdf(ctx.sampleDx, ctx.sampleDy, ctx.sampleDz);
    }

    private double referenceEnvironmentBackgroundPdf(double dx, double dy, double dz) {
        if (!hasVisibleEnvironment()) {
            return 0.0;
        }
        if (environmentMap != null) {
            double ry = dy * environmentPitchCos - dz * environmentPitchSin;
            double rz = dy * environmentPitchSin + dz * environmentPitchCos;
            double rx = dx * environmentYawCos + rz * environmentYawSin;
            double rz2 = -dx * environmentYawSin + rz * environmentYawCos;
            return environmentMap.pdfForDirection(rx, ry, rz2);
        }
        double skyPdf = environmentSkySampleWeight
 * PathTracingSamplingSupport.cosineHemispherePdf(Math.max(0.0, dy));
        double groundPdf = environmentGroundSampleWeight
 * PathTracingSamplingSupport.cosineHemispherePdf(Math.max(0.0, -dy));
        double uniformPdf = environmentUniformSampleWeight * PathTracingSamplingSupport.uniformSpherePdf();
        return skyPdf + groundPdf + uniformPdf;
    }

    private void renderEnvironmentOnly(Camera camera, FrameBuffer fb) {
        Arrays.fill(fb.getDepthBuffer(), 1.0f);
        if (!hasVisibleEnvironment()) {
            Arrays.fill(fb.getColorBuffer(), packColor(0.0, 0.0, 0.0));
            return;
        }

        sampleDrivenFeatureWeight = resolveSampleDrivenFeatureWeight(accumulatedSamples);

        CameraState cam = buildCameraState(camera, fb.getWidth(), fb.getHeight(), null, null);
        TraceContext ctx = new TraceContext();
        int localWidth = fb.getWidth();
        int localHeight = fb.getHeight();
        for (int y = 0; y < localHeight; y++) {
            int rowBase = y * localWidth;
            for (int x = 0; x < localWidth; x++) {
                generatePrimaryRay(cam, x, y, 0.5, 0.5, ctx);
                sampleEnvironment(ctx.rayDx, ctx.rayDy, ctx.rayDz, ctx);
                fb.getColorBuffer()[rowBase + x] = packColor(
                        toneMap(ctx.envR),
                        toneMap(ctx.envG),
                        toneMap(ctx.envB)
                );
            }
        }
    }

    private void fillBackground(FrameBuffer fb) {
        if (hasVisibleEnvironment()) {
            double envScale = effectiveEnvironmentScale();
            double fillR = environmentMap != null ? environmentMap.getAverageR() * envScale : backgroundR * envScale;
            double fillG = environmentMap != null ? environmentMap.getAverageG() * envScale : backgroundG * envScale;
            double fillB = environmentMap != null ? environmentMap.getAverageB() * envScale : backgroundB * envScale;
            Arrays.fill(fb.getColorBuffer(), packColor(
                    toneMap(fillR),
                    toneMap(fillG),
                    toneMap(fillB)
            ));
        } else {
            Arrays.fill(fb.getColorBuffer(), packColor(0.0, 0.0, 0.0));
        }
        Arrays.fill(fb.getDepthBuffer(), 1.0f);
    }

    private boolean hasVisibleEnvironment() {
        if (!skyEnabled || effectiveEnvironmentScale() <= 1e-6) {
            return false;
        }
        return environmentMap != null
                || backgroundR > 1e-6
                || backgroundG > 1e-6
                || backgroundB > 1e-6;
    }

    private double resolveSampleDrivenFeatureWeight(long sampleEstimate) {
        if (!sampleDrivenFeaturesEnabled || referenceMode) {
            return 1.0;
        }
        long span = Math.max(1L, STILL_TIER5_MIN_SAMPLES - STILL_TIER1_MIN_SAMPLES);
        double normalized = (sampleEstimate - STILL_TIER1_MIN_SAMPLES) / (double) span;
        double t = clamp01(normalized);
        double smooth = t * t * (3.0 - 2.0 * t);
        double floor = clamp01(sampleDrivenFeatureFloor);
        return floor + (1.0 - floor) * smooth;
    }

    private int resolveReferenceEnvironmentSamples(int bounce, SurfaceState surface, ReferenceSurfaceLobes lobes) {
        if (!hasVisibleEnvironment()) {
            return 0;
        }
        if (bounce != 0) {
            return 1;
        }
        return resolveGlossySamples(surface, lobes, REFERENCE_PRIMARY_ENV_SAMPLES_BASE, REFERENCE_PRIMARY_ENV_SAMPLES_MAX);
    }

    private int resolveReferenceEmissiveSamples(int bounce, SurfaceState surface, ReferenceSurfaceLobes lobes) {
        if (emissiveLights.length == 0 || emissiveLightPowerSum <= 1e-8) {
            return 0;
        }
        if (bounce != 0) {
            return 1;
        }
        return resolveGlossySamples(surface, lobes, REFERENCE_PRIMARY_EMISSIVE_SAMPLES_BASE, REFERENCE_PRIMARY_EMISSIVE_SAMPLES_MAX);
    }

    private int resolveGlossySamples(SurfaceState surface,
                                     ReferenceSurfaceLobes lobes,
                                     int baseSamples,
                                     int maxSamples) {
        double specWeight = lobes.totalWeight <= 1e-8 ? 0.0 : lobes.specularWeight / lobes.totalWeight;
        double glossiness = 1.0 - clamp01(surface.roughness);
        double weight = Math.max(glossiness, specWeight);
        int samples = baseSamples;
        if (weight >= REFERENCE_GLOSSY_SAMPLE_THRESHOLD) {
            samples += 1;
        }
        if (weight >= REFERENCE_ULTRA_GLOSSY_SAMPLE_THRESHOLD) {
            samples += 1;
        }
        if (weight >= REFERENCE_SUPER_GLOSSY_SAMPLE_THRESHOLD) {
            samples += 1;
        }
        return Math.max(baseSamples, Math.min(maxSamples, samples));
    }

    private double effectiveEnvironmentScale() {
        double baseScale = Math.max(0.0, environmentStrength);
        return baseScale * Math.max(0.0, environmentExposure);
    }

    private void rebuildEnvironmentRotationCache() {
        double yawRad = Math.toRadians(environmentYawDegrees);
        double pitchRad = Math.toRadians(environmentPitchDegrees);
        environmentYawCos = Math.cos(yawRad);
        environmentYawSin = Math.sin(yawRad);
        environmentPitchCos = Math.cos(pitchRad);
        environmentPitchSin = Math.sin(pitchRad);
    }

    private void rotateToEnvironment(double dx, double dy, double dz, TraceContext ctx) {
        double ry = dy * environmentPitchCos - dz * environmentPitchSin;
        double rz = dy * environmentPitchSin + dz * environmentPitchCos;
        double rx = dx * environmentYawCos + rz * environmentYawSin;
        double rz2 = -dx * environmentYawSin + rz * environmentYawCos;
        ctx.environmentDirX = rx;
        ctx.environmentDirY = ry;
        ctx.environmentDirZ = rz2;
    }

    private void rotateFromEnvironment(double dx, double dy, double dz, TraceContext ctx) {
        double rx = dx * environmentYawCos - dz * environmentYawSin;
        double rz = dx * environmentYawSin + dz * environmentYawCos;
        double ry = dy * environmentPitchCos + rz * environmentPitchSin;
        double rz2 = -dy * environmentPitchSin + rz * environmentPitchCos;
        ctx.sampleDx = rx;
        ctx.sampleDy = ry;
        ctx.sampleDz = rz2;
    }

    private void sampleCosineHemisphere(double nx, double ny, double nz, SplitMix64 rng, TraceContext ctx) {
        double r1 = 2.0 * Math.PI * rng.nextDouble();
        double r2 = rng.nextDouble();
        double r2s = Math.sqrt(r2);
        double lx = Math.cos(r1) * r2s;
        double ly = Math.sin(r1) * r2s;
        double lz = Math.sqrt(Math.max(0.0, 1.0 - r2));

        double tx;
        double ty;
        double tz;
        if (Math.abs(nx) > 0.5) {
            tx = 0.0;
            ty = 1.0;
            tz = 0.0;
        } else {
            tx = 1.0;
            ty = 0.0;
            tz = 0.0;
        }

        double bx = ny * tz - nz * ty;
        double by = nz * tx - nx * tz;
        double bz = nx * ty - ny * tx;
        double bLen = Math.sqrt(bx * bx + by * by + bz * bz);
        if (bLen > 1e-14) {
            double inv = 1.0 / bLen;
            bx *= inv;
            by *= inv;
            bz *= inv;
        } else {
            bx = 0.0;
            by = 0.0;
            bz = 1.0;
        }

        tx = by * nz - bz * ny;
        ty = bz * nx - bx * nz;
        tz = bx * ny - by * nx;

        double dx = tx * lx + bx * ly + nx * lz;
        double dy = ty * lx + by * ly + ny * lz;
        double dz = tz * lx + bz * ly + nz * lz;
        double lenSq = dx * dx + dy * dy + dz * dz;
        if (lenSq < 1e-14) {
            ctx.sampleDx = nx;
            ctx.sampleDy = ny;
            ctx.sampleDz = nz;
            return;
        }
        double invLen = 1.0 / Math.sqrt(lenSq);
        ctx.sampleDx = dx * invLen;
        ctx.sampleDy = dy * invLen;
        ctx.sampleDz = dz * invLen;
    }

    private void sampleUniformSphere(SplitMix64 rng, TraceContext ctx) {
        double z = 1.0 - 2.0 * rng.nextDouble();
        double radius = Math.sqrt(Math.max(0.0, 1.0 - z * z));
        double phi = 2.0 * Math.PI * rng.nextDouble();
        ctx.sampleDx = Math.cos(phi) * radius;
        ctx.sampleDy = z;
        ctx.sampleDz = Math.sin(phi) * radius;
    }

    private static int resolveAreaShadowSamples(AreaLight light, boolean motionActive) {
        int samples = Math.max(1, light.getShadowSamples());
        if (motionActive && samples > 1) {
            samples = Math.max(1, (samples + 1) / 2);
        }
        return samples;
    }

    private static void sampleAreaLightPosition(AreaLight light, SplitMix64 rng, Vec3 outPos, Vec3 tangentOut, Vec3 bitangentOut) {
        Vec3 origin = light.getPosition();
        Vec3 emissionDir = light.getEmissionDirection();
        double nx = emissionDir.x;
        double ny = emissionDir.y;
        double nz = emissionDir.z;
        double nLenSq = nx * nx + ny * ny + nz * nz;
        if (nLenSq < 1e-12) {
            nx = 0.0;
            ny = -1.0;
            nz = 0.0;
        } else {
            double invN = 1.0 / Math.sqrt(nLenSq);
            nx *= invN;
            ny *= invN;
            nz *= invN;
        }

        double ax = Math.abs(ny) < 0.92 ? 0.0 : 1.0;
        double ay = Math.abs(ny) < 0.92 ? 1.0 : 0.0;
        double az = 0.0;

        double tx = ay * nz - az * ny;
        double ty = az * nx - ax * nz;
        double tz = ax * ny - ay * nx;
        double tLenSq = tx * tx + ty * ty + tz * tz;
        if (tLenSq < 1e-12) {
            tx = 1.0;
            ty = 0.0;
            tz = 0.0;
        } else {
            double invT = 1.0 / Math.sqrt(tLenSq);
            tx *= invT;
            ty *= invT;
            tz *= invT;
        }

        double bx = ny * tz - nz * ty;
        double by = nz * tx - nx * tz;
        double bz = nx * ty - ny * tx;
        double bLenSq = bx * bx + by * by + bz * bz;
        if (bLenSq < 1e-12) {
            bx = 0.0;
            by = 0.0;
            bz = 1.0;
        } else {
            double invB = 1.0 / Math.sqrt(bLenSq);
            bx *= invB;
            by *= invB;
            bz *= invB;
        }

        tangentOut.set(tx, ty, tz);
        bitangentOut.set(bx, by, bz);

        double radius = Math.max(AREA_LIGHT_RADIUS_MIN, light.getSurfaceRadius());
        double diskR = radius * Math.sqrt(rng.nextDouble());
        double phi = 2.0 * Math.PI * rng.nextDouble();
        double ox = Math.cos(phi) * diskR;
        double oy = Math.sin(phi) * diskR;

        outPos.set(
                origin.x + tx * ox + bx * oy,
                origin.y + ty * ox + by * oy,
                origin.z + tz * ox + bz * oy);
    }

    private void resetAccumulation() {
        Arrays.fill(accumR, 0.0);
        Arrays.fill(accumG, 0.0);
        Arrays.fill(accumB, 0.0);
        Arrays.fill(accumLuma, 0.0);
        Arrays.fill(accumLumaSq, 0.0);
        Arrays.fill(sampleCounts, 0);
        Arrays.fill(denoiseR, 0.0);
        Arrays.fill(denoiseG, 0.0);
        Arrays.fill(denoiseB, 0.0);
        Arrays.fill(denoiseNoise, 0.0);
        Arrays.fill(denoiseScratchR, 0.0);
        Arrays.fill(denoiseScratchG, 0.0);
        Arrays.fill(denoiseScratchB, 0.0);
        Arrays.fill(guideDepth, Float.POSITIVE_INFINITY);
        Arrays.fill(guideNormal, 0.0f);
        Arrays.fill(guideAlbedo, 0.0f);
        Arrays.fill(guideRoughness, 1.0f);
        Arrays.fill(temporalHistoryR, 0.0);
        Arrays.fill(temporalHistoryG, 0.0);
        Arrays.fill(temporalHistoryB, 0.0);
        Arrays.fill(temporalHistoryDepth, Float.POSITIVE_INFINITY);
        Arrays.fill(temporalHistoryNormal, 0.0f);
        Arrays.fill(temporalHistoryAlbedo, 0.0f);
        temporalHistoryValid = false;
        accumulatedSamples = 0;
        guidesReady = false;
        nextTemporalBlendScale = 1.0;
        nextTemporalBlendFrames = 0;
    }

    private void softResetAccumulationPreserveHistory() {
        Arrays.fill(accumR, 0.0);
        Arrays.fill(accumG, 0.0);
        Arrays.fill(accumB, 0.0);
        Arrays.fill(accumLuma, 0.0);
        Arrays.fill(accumLumaSq, 0.0);
        Arrays.fill(sampleCounts, 0);
        Arrays.fill(denoiseR, 0.0);
        Arrays.fill(denoiseG, 0.0);
        Arrays.fill(denoiseB, 0.0);
        Arrays.fill(denoiseNoise, 0.0);
        Arrays.fill(smartSectionBlendScale, 0.0);
        Arrays.fill(smartSectionDetailScale, 0.0);
        Arrays.fill(smartSectionScratch, 0.0);
        Arrays.fill(denoiseScratchR, 0.0);
        Arrays.fill(denoiseScratchG, 0.0);
        Arrays.fill(denoiseScratchB, 0.0);
        Arrays.fill(guideDepth, Float.POSITIVE_INFINITY);
        Arrays.fill(guideNormal, 0.0f);
        Arrays.fill(guideAlbedo, 0.0f);
        Arrays.fill(guideRoughness, 1.0f);
        accumulatedSamples = 0L;
        guidesReady = false;
    }

    private void softResetAccumulationInvalidateHistory() {
        softResetAccumulationPreserveHistory();
        invalidateTemporalHistory();
    }

    private void allocateAccumulation(int w, int h) {
        int count = safePixelCount(w, h);
        accumR = new double[count];
        accumG = new double[count];
        accumB = new double[count];
        accumLuma = new double[count];
        accumLumaSq = new double[count];
        sampleCounts = new int[count];
        denoiseR = new double[count];
        denoiseG = new double[count];
        denoiseB = new double[count];
        denoiseNoise = new double[count];
        smartSectionBlendScale = new double[count];
        smartSectionDetailScale = new double[count];
        smartSectionScratch = new double[count];
        denoiseScratchR = new double[count];
        denoiseScratchG = new double[count];
        denoiseScratchB = new double[count];
        guideDepth = new float[count];
        guideNormal = new float[count * 3];
        guideAlbedo = new float[count * 3];
        guideRoughness = new float[count];
        temporalHistoryR = new double[count];
        temporalHistoryG = new double[count];
        temporalHistoryB = new double[count];
        temporalHistoryDepth = new float[count];
        temporalHistoryNormal = new float[count * 3];
        temporalHistoryAlbedo = new float[count * 3];
        temporalUpdateMask = new boolean[count];
        temporalHistoryValid = false;
    }

    private void updateTemporalHistory(int count, boolean[] updateMask) {
        if (count <= 0) {
            temporalHistoryValid = false;
            return;
        }
        int vectorCount = count * 3;
        if (updateMask == null) {
            System.arraycopy(denoiseR, 0, temporalHistoryR, 0, count);
            System.arraycopy(denoiseG, 0, temporalHistoryG, 0, count);
            System.arraycopy(denoiseB, 0, temporalHistoryB, 0, count);
            System.arraycopy(guideDepth, 0, temporalHistoryDepth, 0, count);
            System.arraycopy(guideNormal, 0, temporalHistoryNormal, 0, vectorCount);
            System.arraycopy(guideAlbedo, 0, temporalHistoryAlbedo, 0, vectorCount);
            temporalHistoryValid = true;
            return;
        }

        boolean anyUpdated = false;
        int limit = Math.min(count, updateMask.length);
        for (int i = 0; i < limit; i++) {
            if (!updateMask[i]) {
                continue;
            }
            anyUpdated = true;
            temporalHistoryR[i] = denoiseR[i];
            temporalHistoryG[i] = denoiseG[i];
            temporalHistoryB[i] = denoiseB[i];
            temporalHistoryDepth[i] = guideDepth[i];
            int base = i * 3;
            temporalHistoryNormal[base] = guideNormal[base];
            temporalHistoryNormal[base + 1] = guideNormal[base + 1];
            temporalHistoryNormal[base + 2] = guideNormal[base + 2];
            temporalHistoryAlbedo[base] = guideAlbedo[base];
            temporalHistoryAlbedo[base + 1] = guideAlbedo[base + 1];
            temporalHistoryAlbedo[base + 2] = guideAlbedo[base + 2];
        }
        if (anyUpdated) {
            temporalHistoryValid = true;
        }
    }

    private void invalidateTemporalHistory() {
        temporalHistoryValid = false;
        nextTemporalBlendScale = 1.0;
        nextTemporalBlendFrames = 0;
    }

    private void onPreviewMotionStateChanged(boolean nextState) {
        if (previewMotionActive == nextState) {
            return;
        }
        previewMotionActive = nextState;
        previewPhase = nextState ? PreviewPhase.MOTION_ENTER : PreviewPhase.MOTION_EXIT_RESYNC;
        previewPhaseFrameSequence = 0;
        motionTileCursor = 0;
        motionTileEpochCounter = 0;
        previewSmoothFrameCounter = 0;
        previewSmoothDesyncCounter = 0;
        previewSmoothBoostLevel = 0;
        previewSmoothBoostHoldFrames = 0;
        Arrays.fill(motionTileEpoch, -1);
        softResetAccumulationInvalidateHistory();
    }

    private DenoiserRuntimePackageBridge.PackageStatus resolveDenoiserRuntimePackageStatus() {
        if (denoiseRuntimePackageStatus == null) {
            denoiseRuntimePackageStatus = DenoiserRuntimePackageBridge.resolve(
                    denoiseRuntimePackageRootOverride,
                    denoiseRuntimePackageRequired
            );
        }
        return denoiseRuntimePackageStatus;
    }

    private void applyDenoiseAndResolve(int[] outColor,
                                        double invSamples,
                                        double temporalBlendScale,
                                        boolean allowFullDenoise,
                                        boolean[] temporalMask) {
        int count = Math.min(outColor.length, accumR.length);
        DenoiseSchedule.State schedule = DenoiseSchedule.resolve(
                accumulatedSamples,
                denoiseRadius,
                denoiseStrength);
        if (!schedule.active()) {
            invalidateTemporalHistory();
            return;
        }
        if (!allowFullDenoise && accumulatedSamples > 0L) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES, 1L);
            denoiserTelemetry.onSkip("skip_motion_cadence", 0.0, Math.max(1.0, accumulatedSamples));
            resolveCarrierWithoutFullDenoise(outColor, count, temporalBlendScale, temporalMask);
            return;
        }

        RuntimeDenoiserOrchestrator.TelemetrySnapshot previous = denoiserTelemetry.snapshot();
        RuntimeDenoiserOrchestrator.Decision decision = RuntimeDenoiserOrchestrator.decide(
                width,
                height,
                denoiserTelemetry.smoothedLatencyMs(),
            denoiseFastMode,
            previous.lastInvalidGuideRatio(),
            previous.lastEnergyRatio(),
            previous.lastEffectiveSpp()
        );
        decision = RuntimeDenoiserOrchestrator.applyOverrides(
            decision,
            width,
            height,
            denoiseRuntimeModeOverride,
            denoiseTilePresetOverride
        );
        denoiserTelemetry.onDecision(decision);
        if (decision.mode() == RuntimeDenoiserOrchestrator.RuntimeMode.FULL_FRAME) {
            tileSize = Math.max(8, decision.tileSize());
        } else {
            tileSize = Math.max(8, decision.tileSize());
            denoiseTileOverlap = decision.overlap();
        }
        double effectiveSpp = RuntimeDenoiserOrchestrator.estimateEffectiveSpp(sampleCounts, count, accumulatedSamples);
        double invalidGuideRatio = RuntimeDenoiserOrchestrator.invalidGuideRatio(
            guideDepth,
            guideNormal,
            guideAlbedo,
            guideRoughness,
            count,
            width,
            height
        );
        double meanNoise = RuntimeDenoiserOrchestrator.estimateMeanRelativeNoise(
                accumLuma,
                accumLumaSq,
                sampleCounts,
                accumulatedSamples,
                count
        );

        if (effectiveSpp >= 48.0 && meanNoise < 0.018 && invalidGuideRatio < 0.20) {
            denoiserTelemetry.onSkip("skip_clean_high_spp", invalidGuideRatio, effectiveSpp);
            for (int i = 0; i < count; i++) {
                writeResolvedPixel(outColor, i);
            }
            invalidateTemporalHistory();
            return;
        }

        double preLuma = RuntimeDenoiserOrchestrator.meanAccumulatedLuminance(
                accumR,
                accumG,
                accumB,
                sampleCounts,
                accumulatedSamples,
                count
        );
        buildSmartSectionMaps(count, effectiveSpp, meanNoise, invalidGuideRatio);
        long denoiseStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.DENOISE);
        long denoiseStart = System.nanoTime();
        JointBilateralDenoiser.apply(
                width,
                height,
                workerCount,
                threadPool,
                schedule.radius(),
                schedule.strength(),
            exposure,
            toneMapMode,
                invSamples,
                accumR,
                accumG,
                accumB,
                accumLuma,
                accumLumaSq,
                accumulatedSamples,
                sampleCounts,
                guideDepth,
                guideNormal,
                guideAlbedo,
                guideRoughness,
                denoiseR,
                denoiseG,
                denoiseB,
                denoiseNoise,
                smartSectionBlendScale,
                smartSectionDetailScale,
                denoiseScratchR,
                denoiseScratchG,
                denoiseScratchB,
                outColor,
                denoiseFastMode
        );
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.DENOISE, denoiseStage);

        double latencyMs = (System.nanoTime() - denoiseStart) / 1_000_000.0;
        if (RuntimeDenoiserOrchestrator.hasNonFinite(denoiseR, count)
                || RuntimeDenoiserOrchestrator.hasNonFinite(denoiseG, count)
                || RuntimeDenoiserOrchestrator.hasNonFinite(denoiseB, count)) {
            denoiserTelemetry.onFallback("fallback_nonfinite_output", 1.0, invalidGuideRatio, effectiveSpp);
            for (int i = 0; i < count; i++) {
                writeResolvedPixel(outColor, i);
            }
            invalidateTemporalHistory();
            return;
        }

        if (temporalHistoryValid && accumulatedSamples > 0L) {
            long temporalStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.TEMPORAL);
            TemporalReprojectionDenoiser.apply(
                    count,
                    accumulatedSamples,
                    sampleCounts,
                    denoiseNoise,
                    denoiseR,
                    denoiseG,
                    denoiseB,
                    guideDepth,
                    guideNormal,
                    guideAlbedo,
                    temporalHistoryR,
                    temporalHistoryG,
                    temporalHistoryB,
                    temporalHistoryDepth,
                    temporalHistoryNormal,
                    temporalHistoryAlbedo,
                    temporalBlendScale,
                    temporalMask);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.TEMPORAL, temporalStage);
        }

        double postLuma = RuntimeDenoiserOrchestrator.meanLuminance(denoiseR, denoiseG, denoiseB, count);
        double energyRatio = preLuma <= 1e-6 ? 1.0 : postLuma / preLuma;
        if (!Double.isFinite(energyRatio) || energyRatio > 1.55) {
            denoiserTelemetry.onFallback("fallback_energy_amplification", energyRatio, invalidGuideRatio, effectiveSpp);
            for (int i = 0; i < count; i++) {
                writeResolvedPixel(outColor, i);
            }
            invalidateTemporalHistory();
            return;
        }

        String lowConfidenceReason = "";
        if (RuntimeDenoiserOrchestrator.isGuideInvalidityLowConfidence(
                invalidGuideRatio,
                width,
                height,
                decision.mode(),
                effectiveSpp,
                meanNoise,
                previous.lastInvalidGuideRatio(),
            previous.lastReason()
        )) {
            lowConfidenceReason = "low_confidence_guide_invalidity";
        } else if (energyRatio < 0.30) {
            lowConfidenceReason = "low_confidence_energy_loss";
        } else if (decision.mode() == RuntimeDenoiserOrchestrator.RuntimeMode.TILED
                && denoiseTileOverlap < 20
                && width >= 1920
                && meanNoise > 0.24) {
            lowConfidenceReason = "low_confidence_tile_seam_uncertainty";
        } else if (effectiveSpp < 2.0 && meanNoise > 0.40) {
            lowConfidenceReason = "low_confidence_low_sample_noisy_input";
        }
        if (!lowConfidenceReason.isEmpty()) {
            denoiserTelemetry.onLowConfidence(lowConfidenceReason, invalidGuideRatio, effectiveSpp);
        }
        updateTemporalHistory(count, temporalMask);
        denoiserTelemetry.onDenoiseLatency(latencyMs, energyRatio, invalidGuideRatio, effectiveSpp);
    }

    private void resolveCarrierWithoutFullDenoise(int[] outColor,
                                                  int count,
                                                  double temporalBlendScale,
                                                  boolean[] temporalMask) {
        if (count <= 0) {
            return;
        }
        long resolveStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.CARRIER_RESOLVE);
        for (int i = 0; i < count; i++) {
            int resolvedSamples = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, 0L);
            double resolvedInvSamples = AdaptiveSamplingSupport.inverseSampleCount(resolvedSamples, 0L);
            denoiseR[i] = accumR[i] * resolvedInvSamples;
            denoiseG[i] = accumG[i] * resolvedInvSamples;
            denoiseB[i] = accumB[i] * resolvedInvSamples;
            denoiseNoise[i] = DenoiseSupport.relativeNoise(accumLuma[i], accumLumaSq[i], resolvedSamples);
        }

        if (temporalHistoryValid && accumulatedSamples > 0L) {
            long temporalStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.TEMPORAL);
            TemporalReprojectionDenoiser.apply(
                    count,
                    accumulatedSamples,
                    sampleCounts,
                    denoiseNoise,
                    denoiseR,
                    denoiseG,
                    denoiseB,
                    guideDepth,
                    guideNormal,
                    guideAlbedo,
                    temporalHistoryR,
                    temporalHistoryG,
                    temporalHistoryB,
                    temporalHistoryDepth,
                    temporalHistoryNormal,
                    temporalHistoryAlbedo,
                    temporalBlendScale,
                    temporalMask);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.TEMPORAL, temporalStage);
        }

        for (int i = 0; i < count; i++) {
            outColor[i] = packColor(
                    toneMap(denoiseR[i]),
                    toneMap(denoiseG[i]),
                    toneMap(denoiseB[i])
            );
        }
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.CARRIER_RESOLVE, resolveStage);
        updateTemporalHistory(count, temporalMask);
    }

    private void buildSmartSectionMaps(int count,
                                       double effectiveSpp,
                                       double meanNoise,
                                       double invalidGuideRatio) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int samples = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, 0L);
            double noise = localRelativeNoise(i, samples, effectiveSpp, meanNoise);
            double sampleScarcity = 1.0 - DenoiseSupport.clamp01(samples / 22.0);
            double roughness = i < guideRoughness.length ? DenoiseSupport.clamp01(guideRoughness[i]) : 1.0;
            double specularity = 1.0 - roughness;
            double edge = estimateGuideEdge(i);
            double luma = resolvedAccumLuminance(i, samples);
            double highlight = DenoiseSupport.clamp01((luma - 0.40) / 0.65);

            double blendBase = 0.56
                    + noise * 0.84
                    + sampleScarcity * 0.28
                    + DenoiseSupport.clamp01(invalidGuideRatio) * 0.08;
            double protection = 1.0
                    - edge * 0.72
                    - specularity * 0.45
                    - highlight * 0.33;
            smartSectionBlendScale[i] = DenoiseSupport.clamp01(0.14 + blendBase * Math.max(0.12, protection));

            double detailBase = 0.72
                    + edge * 1.04
                    + specularity * 0.36
                    + highlight * 0.40
                    + (1.0 - noise) * 0.18;
            smartSectionDetailScale[i] = DenoiseSupport.clamp01(detailBase);
        }

        smoothSmartSectionMap(smartSectionBlendScale, count, 2, 0.72, 0.28);
        smoothSmartSectionMap(smartSectionDetailScale, count, 2, 0.66, 0.34);
    }

    private void smoothSmartSectionMap(double[] map,
                                       int count,
                                       int iterations,
                                       double centerWeight,
                                       double neighborWeight) {
        if (map == null || map.length < count || smartSectionScratch.length < count || count <= 0) {
            return;
        }
        int localWidth = Math.max(1, width);
        int localHeight = Math.max(1, Math.min(height, (count + localWidth - 1) / localWidth));
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int y = 0; y < localHeight; y++) {
                int row = y * localWidth;
                for (int x = 0; x < localWidth; x++) {
                    int idx = row + x;
                    if (idx >= count) {
                        continue;
                    }
                    double sum = map[idx] * centerWeight;
                    double sumW = centerWeight;

                    if (x > 0) {
                        double continuity = guideContinuityWeight(idx, idx - 1);
                        double weight = neighborWeight * continuity;
                        sumW += weight;
                        sum += map[idx - 1] * weight;
                    }
                    if (x + 1 < localWidth && idx + 1 < count) {
                        double continuity = guideContinuityWeight(idx, idx + 1);
                        double weight = neighborWeight * continuity;
                        sumW += weight;
                        sum += map[idx + 1] * weight;
                    }
                    if (y > 0) {
                        int n = idx - localWidth;
                        if (n >= 0) {
                            double continuity = guideContinuityWeight(idx, n);
                            double weight = neighborWeight * continuity;
                            sumW += weight;
                            sum += map[n] * weight;
                        }
                    }
                    if (y + 1 < localHeight) {
                        int n = idx + localWidth;
                        if (n < count) {
                            double continuity = guideContinuityWeight(idx, n);
                            double weight = neighborWeight * continuity;
                            sumW += weight;
                            sum += map[n] * weight;
                        }
                    }

                    smartSectionScratch[idx] = sumW <= 1e-6 ? map[idx] : DenoiseSupport.clamp01(sum / sumW);
                }
            }
            System.arraycopy(smartSectionScratch, 0, map, 0, count);
        }
    }

    private double guideContinuityWeight(int center, int sample) {
        float centerDepth = center < guideDepth.length ? guideDepth[center] : Float.NaN;
        float sampleDepth = sample < guideDepth.length ? guideDepth[sample] : Float.NaN;
        boolean centerHit = Float.isFinite(centerDepth);
        boolean sampleHit = Float.isFinite(sampleDepth);
        if (centerHit != sampleHit) {
            return 0.08;
        }

        double depthWeight = 1.0;
        if (centerHit) {
            double relDepth = Math.abs(sampleDepth - centerDepth)
                    / Math.max(1e-4, Math.max(Math.abs(centerDepth), Math.abs(sampleDepth)));
            depthWeight = 1.0 / (1.0 + relDepth * 44.0);
        }

        double normalWeight = 1.0;
        int centerBase = center * 3;
        int sampleBase = sample * 3;
        if (centerBase + 2 < guideNormal.length && sampleBase + 2 < guideNormal.length) {
            double dot = guideNormal[centerBase] * guideNormal[sampleBase]
                    + guideNormal[centerBase + 1] * guideNormal[sampleBase + 1]
                    + guideNormal[centerBase + 2] * guideNormal[sampleBase + 2];
            normalWeight = Math.pow(DenoiseSupport.clamp01(dot), 10.0);
        }

        double albedoWeight = 1.0;
        if (centerBase + 2 < guideAlbedo.length && sampleBase + 2 < guideAlbedo.length) {
            double dr = guideAlbedo[sampleBase] - guideAlbedo[centerBase];
            double dg = guideAlbedo[sampleBase + 1] - guideAlbedo[centerBase + 1];
            double db = guideAlbedo[sampleBase + 2] - guideAlbedo[centerBase + 2];
            albedoWeight = Math.exp(-(dr * dr + dg * dg + db * db) / (2.0 * 0.09 * 0.09));
        }
        return DenoiseSupport.clamp01(depthWeight * normalWeight * albedoWeight);
    }

    private double estimateGuideEdge(int center) {
        int localWidth = Math.max(1, width);
        int x = center % localWidth;
        int y = center / localWidth;
        double edge = 0.0;
        edge = Math.max(edge, estimateGuideEdgeBetween(center, center - 1, x > 0));
        edge = Math.max(edge, estimateGuideEdgeBetween(center, center + 1, x + 1 < localWidth));
        edge = Math.max(edge, estimateGuideEdgeBetween(center, center - localWidth, y > 0));
        edge = Math.max(edge, estimateGuideEdgeBetween(center, center + localWidth, true));
        return DenoiseSupport.clamp01(edge);
    }

    private double estimateGuideEdgeBetween(int center, int sample, boolean validDirection) {
        if (!validDirection || sample < 0 || sample >= guideDepth.length || center < 0 || center >= guideDepth.length) {
            return 0.0;
        }
        float centerDepth = guideDepth[center];
        float sampleDepth = guideDepth[sample];
        boolean centerHit = Float.isFinite(centerDepth);
        boolean sampleHit = Float.isFinite(sampleDepth);
        if (centerHit != sampleHit) {
            return 1.0;
        }

        double depthEdge = 0.0;
        if (centerHit) {
            double relDepth = Math.abs(sampleDepth - centerDepth)
                    / Math.max(1e-4, Math.max(Math.abs(centerDepth), Math.abs(sampleDepth)));
            depthEdge = DenoiseSupport.clamp01(relDepth * 14.0);
        }

        int centerBase = center * 3;
        int sampleBase = sample * 3;
        double normalEdge = 0.0;
        if (centerBase + 2 < guideNormal.length && sampleBase + 2 < guideNormal.length) {
            double dot = guideNormal[centerBase] * guideNormal[sampleBase]
                    + guideNormal[centerBase + 1] * guideNormal[sampleBase + 1]
                    + guideNormal[centerBase + 2] * guideNormal[sampleBase + 2];
            normalEdge = 1.0 - DenoiseSupport.clamp01(dot);
        }

        double albedoEdge = 0.0;
        if (centerBase + 2 < guideAlbedo.length && sampleBase + 2 < guideAlbedo.length) {
            double dr = Math.abs(guideAlbedo[sampleBase] - guideAlbedo[centerBase]);
            double dg = Math.abs(guideAlbedo[sampleBase + 1] - guideAlbedo[centerBase + 1]);
            double db = Math.abs(guideAlbedo[sampleBase + 2] - guideAlbedo[centerBase + 2]);
            albedoEdge = DenoiseSupport.clamp01((dr + dg + db) * 0.75);
        }

        return DenoiseSupport.clamp01(depthEdge * 0.52 + normalEdge * 0.36 + albedoEdge * 0.22);
    }

    private double localRelativeNoise(int pixelIndex, int localSamples, double effectiveSpp, double meanNoise) {
        if (pixelIndex < 0 || pixelIndex >= accumLuma.length || pixelIndex >= accumLumaSq.length) {
            return DenoiseSupport.clamp01(meanNoise);
        }
        double localNoise = DenoiseSupport.relativeNoise(accumLuma[pixelIndex], accumLumaSq[pixelIndex], localSamples);
        if (!Double.isFinite(localNoise)) {
            localNoise = meanNoise;
        }
        if (effectiveSpp > 24.0) {
            localNoise *= 0.92;
        }
        return DenoiseSupport.clamp01(localNoise);
    }

    private double resolvedAccumLuminance(int pixelIndex, int sampleCount) {
        if (pixelIndex < 0 || pixelIndex >= accumR.length || pixelIndex >= accumG.length || pixelIndex >= accumB.length) {
            return 0.0;
        }
        double inv = AdaptiveSamplingSupport.inverseSampleCount(
                sampleCount,
            0L);
        return DenoiseSupport.luminance(accumR[pixelIndex] * inv, accumG[pixelIndex] * inv, accumB[pixelIndex] * inv);
    }

    private static boolean parseFastDenoiseMode(Object value, boolean fallback) {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof Number numberValue) {
            return numberValue.intValue() != 0;
        }
        if (value instanceof String text) {
            String normalized = text.trim().toLowerCase();
            if ("fast".equals(normalized) || "light".equals(normalized) || "viewport".equals(normalized)) {
                return true;
            }
            if ("quality".equals(normalized) || "full".equals(normalized)) {
                return false;
            }
        }
        return fallback;
    }

    private boolean ensureRuntimeBuffers(int fbWidth, int fbHeight, int[] outColor) {
        int count = safePixelCount(fbWidth, fbHeight);
        if (outColor == null || outColor.length < count) {
            return false;
        }
        if (accumR.length < count
                || accumG.length < count
                || accumB.length < count
                || accumLuma.length < count
                || accumLumaSq.length < count
                || sampleCounts.length < count
                || denoiseR.length < count
                || denoiseG.length < count
                || denoiseB.length < count
                || denoiseNoise.length < count
                || denoiseScratchR.length < count
                || denoiseScratchG.length < count
                || denoiseScratchB.length < count
                || guideDepth.length < count
                || guideNormal.length < count * 3
                || guideAlbedo.length < count * 3
                || guideRoughness.length < count) {
            allocateAccumulation(fbWidth, fbHeight);
            accumulatedSamples = 0L;
            guidesReady = false;
        }
        return true;
    }

    private int safePixelCount(int w, int h) {
        long count = (long) Math.max(1, w) * (long) Math.max(1, h);
        if (count > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        return (int) count;
    }

    private int resolveRenderTileSize(int fbWidth, int fbHeight) {
        int baseTile = Math.max(8, tileSize);
        if (!autoHardwareScheduling || tileSizePinned) {
            return baseTile;
        }
        int workers = Math.max(1, Math.min(workerCount, autoWorkerCap));
        long pixels = Math.max(1L, (long) fbWidth * (long) fbHeight);
        long targetTiles = Math.max(4L, (long) workers * autoTilesPerWorker);
        long tileArea = Math.max(64L, pixels / targetTiles);
        int adaptive = (int) Math.round(Math.sqrt((double) tileArea));
        return snapTileSize(adaptive, 8, 128);
    }

    private int resolveActiveWorkerCount(int tileCount) {
        if (threadPool == null || workerCount <= 1 || tileCount <= 1) {
            return 1;
        }
        int maxActive = Math.max(1, Math.min(Math.min(workerCount, autoWorkerCap), tileCount));
        if (!autoHardwareScheduling || workerCountPinned) {
            return maxActive;
        }
        int granularityLimited = Math.max(1, tileCount / 2);
        return Math.max(1, Math.min(maxActive, granularityLimited));
    }

    private int snapTileSize(int value, int min, int max) {
        int clamped = Math.max(min, Math.min(max, value));
        int snapped = (clamped / 4) * 4;
        if (snapped < min) {
            return min;
        }
        return snapped;
    }

    private void tuneAutoScheduling(int tileCount, double frameMs, long tileCostNanos, long tileCostSamples) {
        if (!Double.isFinite(frameMs) || frameMs <= 0.0) {
            return;
        }
        autoWorkerCap = Math.max(1, Math.min(workerCount, autoWorkerCap));
        applyTileCostModel(tileCostNanos, tileCostSamples);
        sampleHardwareTelemetry();
        updateAutoSmoothedFrameMs(frameMs);

        double target = clamp(autoTargetFrameMs, AUTO_TARGET_FRAME_MS_MIN, AUTO_TARGET_FRAME_MS_MAX);
        double slowThreshold = target * 1.12;
        double fastThreshold = target * 0.74;

        if (autoSmoothedFrameMs > slowThreshold) {
            applySlowAutoAdjustment();
            return;
        }

        if (autoSmoothedFrameMs < fastThreshold) {
            applyFastAutoAdjustment(tileCount);
            return;
        }

        resetAutoSchedulingStreaks();
    }

    private void updateAutoSmoothedFrameMs(double frameMs) {
        if (autoSmoothedFrameMs <= 0.0) {
            autoSmoothedFrameMs = frameMs;
            return;
        }
        autoSmoothedFrameMs = lerp(autoSmoothedFrameMs, frameMs, AUTO_FRAME_EWMA_ALPHA);
    }

    private void applySlowAutoAdjustment() {
        autoSlowFrameStreak++;
        autoFastFrameStreak = 0;
        if (isHardwareUnderutilized()) {
            autoWorkerCap = Math.min(workerCount, autoWorkerCap + 1);
            autoTilesPerWorker = Math.max(AUTO_TILES_PER_WORKER_MIN, autoTilesPerWorker - 1);
        }
        if (autoSlowFrameStreak >= AUTO_SLOW_STREAK_TRIGGER) {
            autoTilesPerWorker = Math.max(AUTO_TILES_PER_WORKER_MIN, autoTilesPerWorker - 1);
            autoWorkerCap = Math.min(workerCount, autoWorkerCap + 1);
            autoSlowFrameStreak = 0;
        }
    }

    private void applyFastAutoAdjustment(int tileCount) {
        autoFastFrameStreak++;
        autoSlowFrameStreak = 0;
        if (autoFastFrameStreak >= AUTO_FAST_STREAK_TRIGGER) {
            autoTilesPerWorker = Math.min(AUTO_TILES_PER_WORKER_MAX, autoTilesPerWorker + 1);
            int minimumCapForTiles = Math.max(1, (tileCount + autoTilesPerWorker - 1) / autoTilesPerWorker);
            int relaxedCap = isHardwareHighlyLoaded()
                    ? autoWorkerCap - 1
                    : autoWorkerCap;
            autoWorkerCap = Math.max(minimumCapForTiles, relaxedCap);
            autoFastFrameStreak = 0;
        }
    }

    private void resetAutoSchedulingStreaks() {
        autoSlowFrameStreak = 0;
        autoFastFrameStreak = 0;
    }

    private void scheduleAutoTuning(int tileCount,
                                    double frameMs,
                                    LongAdder tileCostNanos,
                                    LongAdder tileCostSamples,
                                    LongAccumulator tileCostMinNanos,
                                    LongAccumulator tileCostMaxNanos) {
        if (!Double.isFinite(frameMs) || frameMs <= 0.0) {
            return;
        }
        long tileNanos = tileCostNanos == null ? 0L : tileCostNanos.sum();
        long tileSamples = tileCostSamples == null ? 0L : tileCostSamples.sum();
        long minTileNanos = tileCostMinNanos == null ? Long.MAX_VALUE : tileCostMinNanos.get();
        long maxTileNanos = tileCostMaxNanos == null ? Long.MIN_VALUE : tileCostMaxNanos.get();
        if (tileNanos > 0L && tileSamples > 0L) {
            recordTileTimingConsistency(tileNanos, tileSamples, minTileNanos, maxTileNanos);
        }
        updatePreviewSmoothGuard();
        autoSchedulingBatch.addSample(frameMs, tileNanos, tileSamples);

        if (!autoSchedulingBatch.shouldFlush()) {
            return;
        }

        tuneAutoScheduling(
                tileCount,
                autoSchedulingBatch.meanFrameMs(),
                autoSchedulingBatch.accumulatedTileCostNanos(),
                autoSchedulingBatch.accumulatedTileCostSamples());
        autoSchedulingBatch.reset();
    }

    private void recordTileTimingConsistency(long tileCostNanos,
                                             long tileCostSamples,
                                             long minTileNanos,
                                             long maxTileNanos) {
        if (!RuntimeInstrumentation.isEnabled() || tileCostNanos <= 0L || tileCostSamples <= 0L) {
            return;
        }
        long safeMin = minTileNanos == Long.MAX_VALUE ? 0L : Math.max(0L, minTileNanos);
        long safeMax = maxTileNanos == Long.MIN_VALUE ? 0L : Math.max(0L, maxTileNanos);
        long spread = Math.max(0L, safeMax - safeMin);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MIN_NS, safeMin);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MAX_NS, safeMax);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_SPREAD_NS, spread);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_SAMPLES, tileCostSamples);
        double meanTileNanos = tileCostNanos / (double) Math.max(1L, tileCostSamples);
        if (!Double.isFinite(meanTileNanos) || meanTileNanos <= 0.0) {
            return;
        }
        double skewRatio = safeMax / meanTileNanos;
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MAX_OVER_MEAN_X1000,
                Math.max(0L, Math.round(skewRatio * 1000.0)));
    }

    private void recordTileTemporalDesync(boolean fullFrameCoverage, int tileCount) {
        if (!RuntimeInstrumentation.isEnabled()
                || !previewMotionActive
                || referenceMode
                || tileCount <= 0
                || fullFrameCoverage) {
            return;
        }
        int oldestEpoch = Integer.MAX_VALUE;
        int limit = Math.min(tileCount, motionTileEpoch.length);
        for (int i = 0; i < limit; i++) {
            int epoch = motionTileEpoch[i];
            if (epoch >= 0 && epoch < oldestEpoch) {
                oldestEpoch = epoch;
            }
        }
        if (oldestEpoch == Integer.MAX_VALUE) {
            return;
        }
        int epochAge = motionTileEpochCounter - oldestEpoch;
        if (epochAge > 0) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_DESYNC_FRAMES, 1L);
            previewSmoothDesyncCounter++;
        }
    }

    private void updatePreviewSmoothGuard() {
        if (!previewQualityLadderEnabled || referenceMode || !previewMotionActive) {
            previewSmoothFrameCounter = 0;
            previewSmoothDesyncCounter = 0;
            previewSmoothBoostLevel = 0;
            previewSmoothBoostHoldFrames = 0;
            return;
        }
        previewSmoothFrameCounter++;
        if (previewSmoothFrameCounter < PREVIEW_SMOOTH_ADJUST_INTERVAL_FRAMES) {
            return;
        }
        double desyncRatio = previewSmoothDesyncCounter / (double) Math.max(1, previewSmoothFrameCounter);
        if (desyncRatio >= 0.22) {
            previewSmoothBoostLevel = 1;
            previewSmoothBoostHoldFrames = PREVIEW_SMOOTH_BOOST_HOLD_FRAMES;
        } else if (previewSmoothBoostHoldFrames <= 0 && desyncRatio < 0.08) {
            previewSmoothBoostLevel = 0;
        }
        previewSmoothBoostHoldFrames = Math.max(0, previewSmoothBoostHoldFrames - PREVIEW_SMOOTH_ADJUST_INTERVAL_FRAMES);
        previewSmoothFrameCounter = 0;
        previewSmoothDesyncCounter = 0;
    }

    private void applyTileCostModel(long tileCostNanos, long tileCostSamples) {
        if (!autoTileCostModelEnabled || tileCostNanos <= 0L || tileCostSamples <= 0L) {
            return;
        }
        long measuredTiles = Math.max(0L, tileCostSamples);
        if (measuredTiles <= 0L) {
            return;
        }
        double meanTileMs = (tileCostNanos / 1_000_000.0) / measuredTiles;
        if (!Double.isFinite(meanTileMs) || meanTileMs <= 0.0) {
            return;
        }
        if (autoSmoothedTileMs <= 0.0) {
            autoSmoothedTileMs = meanTileMs;
        } else {
            autoSmoothedTileMs = lerp(autoSmoothedTileMs, meanTileMs, AUTO_FRAME_EWMA_ALPHA);
        }
        double targetFrameMs = clamp(autoTargetFrameMs, AUTO_TARGET_FRAME_MS_MIN, AUTO_TARGET_FRAME_MS_MAX);
        int desiredTilesPerWorker = Math.max(
                AUTO_TILES_PER_WORKER_MIN,
                Math.min(AUTO_TILES_PER_WORKER_MAX, (int) Math.round(targetFrameMs / Math.max(0.1, autoSmoothedTileMs))));
        if (desiredTilesPerWorker < autoTilesPerWorker) {
            autoTilesPerWorker--;
        } else if (desiredTilesPerWorker > autoTilesPerWorker) {
            autoTilesPerWorker++;
        }
    }

    private double clamp(double value, double min, double max) {
        if (!Double.isFinite(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    private void sampleHardwareTelemetry() {
        if (!autoHardwareTelemetryEnabled) {
            return;
        }
        autoHardwareSampleCountdown--;
        if (autoHardwareSampleCountdown > 0) {
            return;
        }
        autoHardwareSampleCountdown = AUTO_HW_SAMPLE_INTERVAL;
        HardwareTelemetrySampler.Sample sample = HardwareTelemetrySampler.sample();
        autoSmoothedProcessCpu = ewma(autoSmoothedProcessCpu, sample.processCpuLoad(), AUTO_HW_EWMA_ALPHA);
        autoSmoothedSystemCpu = ewma(autoSmoothedSystemCpu, sample.systemCpuLoad(), AUTO_HW_EWMA_ALPHA);
    }

    private double ewma(double current, double next, double alpha) {
        if (!Double.isFinite(next)) {
            return current;
        }
        if (!Double.isFinite(current)) {
            return next;
        }
        return lerp(current, next, alpha);
    }

    private void recordTileCost(LongAdder tileCostNanos,
                                LongAdder tileCostSamples,
                                LongAccumulator tileMinNanos,
                                LongAccumulator tileMaxNanos,
                                long tileStartNanos) {
        if (tileCostNanos == null
                || tileCostSamples == null
                || tileMinNanos == null
                || tileMaxNanos == null
                || tileStartNanos <= 0L) {
            return;
        }
        long elapsed = System.nanoTime() - tileStartNanos;
        if (elapsed <= 0L) {
            return;
        }
        tileCostNanos.add(elapsed);
        tileCostSamples.increment();
        tileMinNanos.accumulate(elapsed);
        tileMaxNanos.accumulate(elapsed);
    }

    private boolean isHardwareUnderutilized() {
        return Double.isFinite(autoSmoothedProcessCpu)
                && autoSmoothedProcessCpu < AUTO_PROCESS_CPU_LOW;
    }

    private boolean isHardwareHighlyLoaded() {
        if (Double.isFinite(autoSmoothedProcessCpu) && autoSmoothedProcessCpu >= AUTO_PROCESS_CPU_HIGH) {
            return true;
        }
        return Double.isFinite(autoSmoothedSystemCpu)
                && autoSmoothedSystemCpu >= AUTO_PROCESS_CPU_HIGH;
    }

    private void rebuildThreadPool() {
        shutdownPool();
        if (workerCount > 1) {
            threadPool = new ThreadPool(workerCount);
        }
    }

    private void shutdownPool() {
        if (threadPool != null) {
            threadPool.shutdown();
            threadPool = null;
        }
    }

    private long seedForWorker(int workerIndex, long sampleTarget) {
        long x = 0x9E3779B97F4A7C15L;
        x ^= (sampleTarget + 0xBF58476D1CE4E5B9L);
        x ^= ((long) workerIndex + 1L) * 0x94D049BB133111EBL;
        return x;
    }

    private double toneMap(double c) {
        return ToneMapSupport.toneMap(c, exposure, toneMapMode);
    }

    private int packColor(double r, double g, double b) {
        int ir = (int) (Math.max(0.0, Math.min(1.0, r)) * 255.0 + 0.5);
        int ig = (int) (Math.max(0.0, Math.min(1.0, g)) * 255.0 + 0.5);
        int ib = (int) (Math.max(0.0, Math.min(1.0, b)) * 255.0 + 0.5);
        return 0xFF000000 | (ir << 16) | (ig << 8) | ib;
    }

    private long mixHash(long hash, long value) {
        hash ^= value;
        hash *= 0x100000001b3L;
        return hash;
    }

    private long quantizedBits(double value, double step) {
        return Math.round(value / step);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private PhongMaterial toPhongMaterial(Material base) {
        if (base instanceof PhongMaterial phongMaterial) {
            return phongMaterial;
        }
        Vec3 c = base != null ? base.getBaseColor() : new Vec3(0.75, 0.75, 0.75);
        PhongMaterial out = new PhongMaterial(c, 32.0);
        if (base != null) {
            out.copyFrom(base);
        }
        return out;
    }

    static final class TraceContext {
        final PathTracerBVHNode[] nodeStack = new PathTracerBVHNode[1024];
        final Hit hit = new Hit();
        final Hit tempHit = new Hit();
        final SurfaceState surface = new SurfaceState();
        final SurfaceState shadowSurface = new SurfaceState();
        final Vec3 tmpVec0 = new Vec3();
        final Vec3 tmpVec1 = new Vec3();
        final Vec3 tmpVec2 = new Vec3();
        final Vec3 tmpVec3 = new Vec3();
        final Vec3 tmpVec4 = new Vec3();
        final Vec3 tmpVec5 = new Vec3();
        final double[] spectralScratch0 = new double[SPECTRAL_BAND_COUNT];
        final double[] spectralRgb0 = new double[3];
        int spectralHeroBand = -1;
        int spectralCompanionBand = -1;
        final EnvironmentMap.Sample environmentSample = new EnvironmentMap.Sample();
        final ReferencePathState referencePath = new ReferencePathState();
        final ReferenceSurfaceLobes referenceLobes = new ReferenceSurfaceLobes();
        final ReferenceBounceSample referenceBounce = new ReferenceBounceSample();
        double rayOx, rayOy, rayOz;
        double rayDx, rayDy, rayDz;
        double shadowBaseX, shadowBaseY, shadowBaseZ;
        double shadowOx, shadowOy, shadowOz;
        double shadowTMin;
        double sampleDx, sampleDy, sampleDz;
        double environmentDirX, environmentDirY, environmentDirZ;
        double envR, envG, envB;
        double lightR, lightG, lightB;
        double outR, outG, outB;
        boolean primaryGuidePending;
        boolean primaryGuideCaptured;
        boolean primaryGuideHasHit;
        double primaryGuideTravel;
        double primaryGuideDepth;
        double primaryGuideNx, primaryGuideNy, primaryGuideNz;
        double primaryGuideBaseR, primaryGuideBaseG, primaryGuideBaseB;
        double primaryGuideRoughness;
        double primaryGuideSpecularity;
        double primaryGuideEmissionLuma;
        boolean previewLastEventDelta;
        double previewLastBsdfPdf;
        double previewLastSurfacePx;
        double previewLastSurfacePy;
        double previewLastSurfacePz;
        boolean previewLastSurfaceValid;
        PreviewPathMetrics pathMetrics;
        long pathMetricScale;
    }

}