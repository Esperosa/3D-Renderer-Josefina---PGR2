package engine.render.ray.core;

import engine.render.ray.preview.ProgressiveRenderDefaults;
import engine.render.ray.bvh.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import engine.render.raster.RasterRenderer;
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
 * Progresivni CPU ray tracer s BVH akceleraci,
 * tiled renderovanim a volitelnym denoisingem.
 */
public class RayTracerRenderer implements Renderer {

    private static final double RAY_EPS = 1e-4;
    private static final double INF_T = 1e30;
    private static final double INV_PI = 1.0 / Math.PI;
    private static final int AUTO_TILES_PER_WORKER_MIN = 4;
    private static final int AUTO_TILES_PER_WORKER_MAX = 16;
    private static final double AUTO_TARGET_FRAME_MS_MIN = 10.0;
    private static final double AUTO_TARGET_FRAME_MS_MAX = 45.0;
    private static final double AUTO_FRAME_EWMA_ALPHA = 0.18;
    private static final int AUTO_SLOW_STREAK_TRIGGER = 4;
    private static final int AUTO_FAST_STREAK_TRIGGER = 8;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_DEFAULT = 50;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_MIN = 1;
    private static final int AUTO_SCHEDULING_INTERVAL_FRAMES_MAX = 60;
    private static final int AUTO_HW_SAMPLE_INTERVAL = 24;
    private static final double AUTO_PROCESS_CPU_LOW = 0.62;
    private static final double AUTO_PROCESS_CPU_HIGH = 0.95;
    private static final double AUTO_HW_EWMA_ALPHA = 0.24;
    private static final long STILL_TIER1_MIN_SAMPLES = 12L;
    private static final long STILL_TIER2_MIN_SAMPLES = 28L;
    private static final long STILL_TIER3_MIN_SAMPLES = 48L;
    private static final long STILL_TIER4_MIN_SAMPLES = 72L;
    private static final long STILL_TIER5_MIN_SAMPLES = 100L;
        private static final int PREVIEW_STILL_WARMUP_FRAMES = 2;
    private static final double MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_SCALE = 0.06;
    private static final double MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_MIN = 0.005;
    private static final double MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_MAX = 0.08;
    private static final int MOVING_HYBRID_SPARSE_TILE_SIZE = 16;
    private static final int MOVING_POLISH_COMPOSE_TILE_SIZE = 8;
    private static final int TILE_CLAIM_CHUNK = 4;
    private static final long ADVANCED_OPTICS_UNLOCK_SAMPLES = 50L;
    private static final long ADVANCED_OPTICS_FULL_SAMPLES = 170L;
    private static final double DISPERSION_IOR_SPREAD = 0.06;
    private static final double CAUSTIC_BOOST_MAX = 1.28;
    private static final double AREA_LIGHT_RADIUS_MIN = 0.01;
    private static final int STILL_CINEMATIC_AREA_SAMPLES_MIN = 12;
    private static final int STILL_CINEMATIC_AREA_SAMPLES_MAX = 64;
    private static final double STILL_CINEMATIC_REFLECTION_CARRY_BOOST = 1.30;
    private static final double MOTION_REFLECTION_CARRY_BOOST = 0.85;

    private int width = 1;
    private int height = 1;
    private int workerCount = ThreadPool.recommendedWorkerCount();
    private boolean autoHardwareScheduling = true;
    private boolean workerCountPinned = false;
    private int autoWorkerCap = ThreadPool.recommendedWorkerCount();
    private int autoTilesPerWorker = 8;
    private double autoTargetFrameMs = 18.0;
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
    private int maxDepth = 3;
    private int tileSize = 24;
    private boolean tileSizePinned = false;
    private int leafSize = 8;
    private double exposure = 1.22;
    private boolean directLighting = true;
    private boolean shadowsEnabled = true;
    private boolean reflectionsEnabled = true;
    private boolean skyEnabled = true;
    private boolean denoiseEnabled = true;
    private boolean adaptiveSamplingEnabled = true;
    private int adaptiveMinSamples = ProgressiveRenderDefaults.RAY_VIEWPORT_ADAPTIVE_MIN_SAMPLES;
    private double adaptiveThreshold = ProgressiveRenderDefaults.RAY_VIEWPORT_ADAPTIVE_THRESHOLD;
    private int denoiseRadius = ProgressiveRenderDefaults.RAY_VIEWPORT_DENOISE_RADIUS;
    private double denoiseStrength = ProgressiveRenderDefaults.RAY_VIEWPORT_DENOISE_STRENGTH;
    private String materialProfile = "RT";
    private boolean denoiseFastMode = false;
    private int denoiseTileOverlap = 16;
    private String denoiseRuntimeModeOverride = "AUTO";
    private String denoiseTilePresetOverride = "BALANCED";
    private String denoiseRuntimePackageRootOverride = "";
    private boolean denoiseRuntimePackageRequired = false;
    private DenoiserRuntimePackageBridge.PackageStatus denoiseRuntimePackageStatus;
    private int toneMapMode = ToneMapSupport.MODE_EXPOSURE;
    private final RuntimeDenoiserOrchestrator.Telemetry denoiserTelemetry = new RuntimeDenoiserOrchestrator.Telemetry();

    private ThreadPool threadPool;
    private final RasterRenderer hybridBaseRenderer = new RasterRenderer();
    private FrameBuffer hybridBaseFrameBuffer = new FrameBuffer(1, 1, true);
    private int[] hybridBaseCompositePacked = new int[1];
    private float[] hybridBaseShadeR = new float[1];
    private float[] hybridBaseShadeG = new float[1];
    private float[] hybridBaseShadeB = new float[1];
    private float[] hybridBaseShadeDepth = new float[1];
    private int[] hybridBaseShadeObjectId = new int[1];
    private int[] hybridBaseUpscaleMapX0 = new int[1];
    private int[] hybridBaseUpscaleMapX1 = new int[1];
    private float[] hybridBaseUpscaleMapWx0 = new float[1];
    private float[] hybridBaseUpscaleMapWx1 = new float[1];
    private int[] hybridBaseUpscaleMapY0Row = new int[1];
    private int[] hybridBaseUpscaleMapY1Row = new int[1];
    private float[] hybridBaseUpscaleMapWy0 = new float[1];
    private float[] hybridBaseUpscaleMapWy1 = new float[1];
    private byte[] hybridBaseUpscaleRowMode = new byte[1];
    private int[] hybridBaseUpscaleRowObjectId = new int[1];
    private float[] hybridBaseUpscaleRowDepth = new float[1];
    private int[] hybridBaseUpscaleRowSample00 = new int[1];
    private int[] hybridBaseUpscaleRowSample10 = new int[1];
    private int[] hybridBaseUpscaleRowSample01 = new int[1];
    private int[] hybridBaseUpscaleRowSample11 = new int[1];
    private float[] hybridBaseUpscaleRowW00 = new float[1];
    private float[] hybridBaseUpscaleRowW10 = new float[1];
    private float[] hybridBaseUpscaleRowW01 = new float[1];
    private float[] hybridBaseUpscaleRowW11 = new float[1];
    private float[] hybridBaseUpscaleRowShadeR = new float[1];
    private float[] hybridBaseUpscaleRowShadeG = new float[1];
    private float[] hybridBaseUpscaleRowShadeB = new float[1];
    private int[] hybridBaseUpscaleRowEdgeX = new int[1];
    private byte[] hybridBaseSparseTileMask = new byte[1];
    private int hybridBaseSparseTileCols = 1;
    private int hybridBaseSparseTileRows = 1;
    private boolean hybridBaseCompositePackedValid = false;
    private boolean hybridBaseUpscaleMapValid = false;
    private int hybridBaseShadeWidth = 1;
    private int hybridBaseShadeHeight = 1;
    private int hybridBaseUpscaleMapFullWidth = 0;
    private int hybridBaseUpscaleMapFullHeight = 0;
    private int hybridBaseUpscaleMapSourceWidth = 0;
    private int hybridBaseUpscaleMapSourceHeight = 0;

    private double[] accumR = new double[1];
    private double[] accumG = new double[1];
    private double[] accumB = new double[1];
    private double[] accumLuma = new double[1];
    private double[] accumLumaSq = new double[1];
    private int[] sampleCounts = new int[1];
    private double[] polishAccumR = new double[1];
    private double[] polishAccumG = new double[1];
    private double[] polishAccumB = new double[1];
    private int[] polishSampleCounts = new int[1];
    private double[] polishResolvedR = new double[1];
    private double[] polishResolvedG = new double[1];
    private double[] polishResolvedB = new double[1];
    private double[] polishCompositeR = new double[1];
    private double[] polishCompositeG = new double[1];
    private double[] polishCompositeB = new double[1];
    private int[] polishCompositePacked = new int[1];
    private int[] polishComposeCandidateIndices = new int[1];
    private int[] polishComposeBlendIndices = new int[1];
    private int[] polishComposeBlendPacked = new int[1];
    private byte[] polishComposeSparseTileMask = new byte[1];
    private int polishComposeSparseTileCols = 1;
    private int polishComposeSparseTileRows = 1;
    private int[] polishUpscaleMapX0 = new int[1];
    private int[] polishUpscaleMapX1 = new int[1];
    private float[] polishUpscaleMapWx0 = new float[1];
    private float[] polishUpscaleMapWx1 = new float[1];
    private int[] polishUpscaleMapY0Row = new int[1];
    private int[] polishUpscaleMapY1Row = new int[1];
    private float[] polishUpscaleMapWy0 = new float[1];
    private float[] polishUpscaleMapWy1 = new float[1];
    private boolean polishLayerActive = false;
    private boolean polishPackedCompositeCacheValid = false;
    private boolean polishDoubleCompositeCacheValid = false;
    private boolean polishUpscaleMapValid = false;
    private int polishUpscaleMapFullWidth = 0;
    private int polishUpscaleMapFullHeight = 0;
    private int polishUpscaleMapSourceWidth = 0;
    private int polishUpscaleMapSourceHeight = 0;
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

    private RayTriangle[] triangles = new RayTriangle[0];
    private int triangleCount = 0;
    private int[] triangleOrder = new int[0];
    private double[] centroidX = new double[0];
    private double[] centroidY = new double[0];
    private double[] centroidZ = new double[0];
    private RayBVHNode bvhRoot;

    private RayDirLightCache[] dirLights = new RayDirLightCache[0];
    private RayPointLightCache[] pointLights = new RayPointLightCache[0];
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
    private String environmentMapKey = "";
    private EnvironmentMap environmentMap;

    private long geometrySignature = Long.MIN_VALUE;
    private long lightingSignature = Long.MIN_VALUE;
    private long cameraSignature = Long.MIN_VALUE;
    private PreviewCameraResetSupport.Snapshot previousCameraSnapshot;
    private boolean previewQualityLadderEnabled = false;
    private boolean previewMotionActive = false;
    private int previewMotionSecondaryCadence = 1;
    private int previewMotionTileSubsetCadence = 1;
    private int previewMotionDenoiseCadence = 1;
    private int previewMotionBaseCompositeCadence = 1;
    private int previewMotionSamplesPerFrameLimit = 0;
    private int previewMotionDepthLimit = 0;
    private double previewMotionPolishScale = 0.5;
    private double previewMotionBaseShadingScale = 1.0;
    private boolean previewMotionDominantContributionOnly = false;
    private int previewMotionMaxLocalLights = -1;
    private int previewMotionMaxShadowedLocalLights = -1;
    private double previewMotionThroughputTermination = 0.0;
    private double previewMotionRoughnessSecondarySkip = 0.0;
    private long previewFrameSequence = 0L;
    private PreviewPhase previewPhase = PreviewPhase.STILL_STEADY;
    private int previewPhaseFrameSequence = 0;
    private long activePreviewFrameGeneration = 0L;
    private long activeBaseCarrierGeneration = 0L;
    private long activePolishCompositeGeneration = -1L;
    private long activePolishCompositeCameraSignature = Long.MIN_VALUE;
    private long lastComposedFrameGeneration = -1L;
    private long lastComposedBaseGeneration = -1L;
    private long lastComposedPolishGeneration = -1L;
    private double nextTemporalBlendScale = 1.0;
    private int nextTemporalBlendFrames = 0;
    private String activePreviewQualityTier = "MOVING_CARRIER";
    private int activePreviewPolishCadence = 1;
    private int activePreviewPolishSamplesPerFrame = 0;
    private int activePreviewPolishSecondaryDepth = 0;
    private double activePreviewPolishScale = 1.0;
    private int activeCarrierDenoisePassCap = 0;
    private int activeCarrierDenoiseRadiusCap = 0;
    private int activeCarrierMaxLocalLights = -1;
    private int activeCarrierMaxShadowedLocalLights = -1;
    private boolean activeCarrierSkipDenoiserAnalysis = false;
    private boolean activeCarrierDisableSheen = false;
    private boolean activeCarrierDisableClearcoat = false;
    private boolean activeCarrierDisableDirectionalShadows = false;
    private boolean activeCarrierDisablePointLightShadows = false;
    private boolean activeCarrierPointLightsDiffuseOnly = false;
    private boolean activeCarrierDiffuseOnlyDirectLighting = false;
    private boolean activeCarrierDisableReflections = false;
    private boolean activeCarrierDisableTransmission = false;
    private double activeCarrierSecondaryRoughnessSkip = 0.0;
    private double activeCarrierSecondaryThroughputTermination = 0.0;
    private int activePolishIntegrandDepth = -1;
    private int polishWidth = 1;
    private int polishHeight = 1;
    private boolean activeHybridBaseReducedShading = false;
    private double activeHybridBaseShadingScale = 1.0;
    private PreviewCameraResetSupport.MotionDelta currentCameraMotionDelta = PreviewCameraResetSupport.MotionDelta.NONE;
    private PreviewCameraResetSupport.ResetKind currentCameraResetKind = null;
    private boolean framePolishIntegrandChanged = false;
    private boolean framePolishScaleChanged = false;
    private boolean framePolishTierChanged = false;
    private boolean framePolishRebuildChanged = false;
    private int carrierMotionTileCursor = 0;
    private int carrierMotionTileLayoutCols = -1;
    private int carrierMotionTileLayoutRows = -1;
    private boolean[] carrierTileRenderPlanMask = new boolean[1];

    private enum PreviewPhase {
        STILL_STEADY,
        MOTION_ENTER,
        MOTION_STEADY,
        MOTION_EXIT_RESYNC,
        STILL_WARMUP
    }

    private enum MovingPolishGuardContext {
        REUSE,
        COMPOSE
    }

    private enum MovingPolishGuardDecision {
        ALLOW,
        SKIP_REUSE_GENERATION_MISMATCH,
        SKIP_COMPOSE_GENERATION_MISMATCH
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
        beginFramePolishReuseState();
        int fbWidth = fb.getWidth();
        int fbHeight = fb.getHeight();
        if (fbWidth != width || fbHeight != height) {
            resize(fbWidth, fbHeight);
        }

        long geometrySignatureStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        long gSig = computeGeometrySignature(scene);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_GEOMETRY_SIGNATURE_NS,
                    System.nanoTime() - geometrySignatureStart);
        }
        if (gSig != geometrySignature) {
            geometrySignature = gSig;
            rebuildGeometry(scene);
            framePolishRebuildChanged = true;
            resetAccumulation();
        }

        long lightingSignatureStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        long lSig = computeLightingSignature(scene);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_LIGHTING_SIGNATURE_NS,
                    System.nanoTime() - lightingSignatureStart);
        }
        if (lSig != lightingSignature) {
            lightingSignature = lSig;
            rebuildLightCache(scene);
            framePolishRebuildChanged = true;
            resetAccumulation();
        }

        long cameraSignatureStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        PreviewCameraResetSupport.Snapshot currentCameraSnapshot =
                PreviewCameraResetSupport.capture(camera, fbWidth, fbHeight);
        long cameraDeltaStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        currentCameraMotionDelta = PreviewCameraResetSupport.measure(previousCameraSnapshot, currentCameraSnapshot);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_DELTA_NS,
                    System.nanoTime() - cameraDeltaStart);
        }
        long cSig = currentCameraSnapshot.fullSignature;
        long cameraCompareStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        boolean cameraChanged = cSig != cameraSignature;
        PreviewCameraResetSupport.ResetKind nextCameraResetKind = currentCameraResetKind;
        if (cameraChanged) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.CAMERA_SIGNATURE_CHANGES, 1L);
            cameraSignature = cSig;
            nextCameraResetKind = PreviewCameraResetSupport.classify(
                    previousCameraSnapshot,
                    currentCameraSnapshot,
                    previewQualityLadderEnabled);
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_COMPARE_NS,
                    System.nanoTime() - cameraCompareStart);
        }
        if (cameraChanged) {
            currentCameraResetKind = nextCameraResetKind;
            long cameraResetApplyStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
            applyCameraReset(currentCameraResetKind);
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIG_RESET_APPLY_NS,
                        System.nanoTime() - cameraResetApplyStart);
            }
        }
        previousCameraSnapshot = currentCameraSnapshot;
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_SIGNATURE_NS,
                    System.nanoTime() - cameraSignatureStart);
        }

        if (triangleCount == 0 || bvhRoot == null) {
            renderEnvironmentOnly(camera, fb);
            return;
        }

        long depthClearStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        Arrays.fill(fb.getDepthBuffer(), 1.0f);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_DEPTH_CLEAR_NS,
                    System.nanoTime() - depthClearStart);
        }
        int[] outColor = fb.getColorBuffer();
        long runtimeBuffersStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        boolean runtimeBuffersReady = ensureRuntimeBuffers(fbWidth, fbHeight, outColor);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_RUNTIME_BUFFER_ENSURE_NS,
                    System.nanoTime() - runtimeBuffersStart);
        }
        if (!runtimeBuffersReady) {
            fillBackground(fb);
            return;
        }

        long renderSetupStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        final long frameSequence = ++previewFrameSequence;
        activePreviewFrameGeneration = frameSequence;
        updatePreviewPhaseState();
        final boolean fullFrameCoverage = shouldRenderFullFrameForCurrentPhase();
        final int effectiveSamplesPerFrame = resolveEffectiveSamplesPerFrame();
        final long sampleTarget = accumulatedSamples + (fullFrameCoverage ? effectiveSamplesPerFrame : 0L);
        PreviewQualityTierPlan previewQualityPlan = resolvePreviewQualityTier(sampleTarget);
        applyPreviewQualityPlan(previewQualityPlan);
        applyMotionSecondaryCadenceGate(frameSequence);
        if (activeCarrierDisableReflections || activeCarrierDisableTransmission) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_SECONDARY_REDUCED_FRAMES, 1L);
        }
        final boolean runFullDenoise = resolveRunFullDenoise(frameSequence);
        final double invSamples = 1.0 / Math.max(1L, sampleTarget);
        final boolean captureGuides = denoiseEnabled && !guidesReady;
        final double temporalBlendScale = consumeTemporalBlendScale();

        if (autoHardwareScheduling && !workerCountPinned) {
            int recommended = ThreadPool.recommendedWorkerCount();
            if (recommended != workerCount) {
                workerCount = recommended;
                rebuildThreadPool();
            }
        }

        long cameraStateBuildStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        RayCameraState cam = buildCameraState(camera, fbWidth, fbHeight);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CAMERA_STATE_BUILD_NS,
                    System.nanoTime() - cameraStateBuildStart);
        }
        int tileW = resolveRenderTileSize(fbWidth, fbHeight);
        int tileH = tileW;
        int tileCols = (fbWidth + tileW - 1) / tileW;
        int tileRows = (fbHeight + tileH - 1) / tileH;
        int tileCount = tileCols * tileRows;
        boolean[] carrierTileRenderPlan = resolveCarrierTileRenderPlan(tileCount, tileCols, tileRows, fullFrameCoverage);
        final boolean captureGuidesForCarrier = captureGuides && fullFrameCoverage;
        int activeWorkers = resolveActiveWorkerCount(tileCount);
        boolean skipCarrierFrame = previewMotionActive
            && previewPhase == PreviewPhase.MOTION_STEADY
            && "MOVING_CARRIER_ULTRA_REDUCED".equals(activePreviewQualityTier)
            && accumulatedSamples > 0L
            && (previewPhaseFrameSequence % 2 != 0);
        LongAdder tileCostNanos = new LongAdder();
        LongAdder tileCostSamples = new LongAdder();
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_RENDER_SETUP_NS,
                    System.nanoTime() - renderSetupStart);
        }
        long traceStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.RT_OR_PT_RENDER);
        long carrierTraceStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.CARRIER_TRACE);
        if (skipCarrierFrame) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SIMPLIFIED_FRAMES, 1L);
        } else {
            executeCarrierPass(tileCols, tileW, tileH, fbWidth, fbHeight, cam, outColor,
                    captureGuidesForCarrier, effectiveSamplesPerFrame, sampleTarget, activeWorkers,
                    tileCount, carrierTileRenderPlan, tileCostNanos, tileCostSamples);
        }
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.CARRIER_TRACE, carrierTraceStage);
        if (fullFrameCoverage) {
            if (!skipCarrierFrame) {
                accumulatedSamples = sampleTarget;
            }
        }
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.RT_OR_PT_RENDER, traceStage);

        if (captureGuidesForCarrier) {
            guidesReady = true;
        }

        int resolveCount = Math.min(outColor.length, accumR.length);

        long carrierResolveStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.CARRIER_RESOLVE);
        if (denoiseEnabled && !previewMotionActive) {
            applyDenoiseAndResolve(outColor, invSamples, temporalBlendScale, runFullDenoise);
        } else {
            if (previewMotionActive && denoiseEnabled) {
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES, 1L);
                denoiserTelemetry.onSkip("skip_motion_force_sharp", 0.0, Math.max(1.0, accumulatedSamples));
            }
            resolveCarrierWithoutFullDenoise(
                    outColor,
                    resolveCount,
                    temporalBlendScale,
                    carrierTileRenderPlan,
                    tileCols,
                    tileW,
                    tileH,
                    fbWidth,
                    fbHeight,
                    fullFrameCoverage,
                    skipCarrierFrame);
        }
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.CARRIER_RESOLVE, carrierResolveStage);

        advancePreviewPhaseAfterFrame();

        if (autoHardwareScheduling) {
            long autoSchedulingStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
            double frameMs = (System.nanoTime() - frameStartNanos) / 1_000_000.0;
            scheduleAutoTuning(tileCount, frameMs, tileCostNanos, tileCostSamples);
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_NS,
                        System.nanoTime() - autoSchedulingStart);
            }
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
        if (("previewmotiontilesubsetcadence".equals(k) || "motiontilesubsetcadence".equals(k)) && value instanceof Number) {
            previewMotionTileSubsetCadence = Math.max(1, Math.min(16, ((Number) value).intValue()));
            return;
        }
        if (("previewmotiondenoisecadence".equals(k) || "motiondenoisecadence".equals(k)) && value instanceof Number) {
            previewMotionDenoiseCadence = Math.max(1, Math.min(8, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionbasecompositecadence".equals(k) || "motionbasecompositecadence".equals(k)) && value instanceof Number) {
            previewMotionBaseCompositeCadence = Math.max(1, Math.min(8, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionsamplesperframe".equals(k) || "motionsamplesperframe".equals(k)) && value instanceof Number) {
            previewMotionSamplesPerFrameLimit = Math.max(0, Math.min(64, ((Number) value).intValue()));
            return;
        }
        if (("previewmotionmaxdepth".equals(k) || "motionmaxdepth".equals(k)) && value instanceof Number) {
            previewMotionDepthLimit = Math.max(0, Math.min(32, ((Number) value).intValue()));
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
        if (("previewmotionthroughputtermination".equals(k) || "motionthroughputtermination".equals(k)) && value instanceof Number) {
            previewMotionThroughputTermination = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if (("previewmotionroughnesssecondaryskip".equals(k) || "motionroughnesssecondaryskip".equals(k)) && value instanceof Number) {
            previewMotionRoughnessSecondarySkip = Math.max(0.0, Math.min(1.0, ((Number) value).doubleValue()));
            return;
        }
        if (("previewmotionpolishscale".equals(k) || "motionpolishscale".equals(k)) && value instanceof Number) {
            previewMotionPolishScale = clamp(((Number) value).doubleValue(), 0.08, 1.0);
            return;
        }
        if (("previewmotionbaseshadingscale".equals(k) || "motionbaseshadingscale".equals(k)) && value instanceof Number) {
            previewMotionBaseShadingScale = clamp(((Number) value).doubleValue(), 0.18, 1.0);
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
            if (next != maxDepth) {
                maxDepth = next;
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
        if ("directlighting".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != directLighting) {
                directLighting = next;
                resetAccumulation();
            }
            return;
        }
        if ("shadows".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != shadowsEnabled) {
                shadowsEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if ("reflections".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != reflectionsEnabled) {
                reflectionsEnabled = next;
                resetAccumulation();
            }
            return;
        }
        if ("sky".equals(k) && value instanceof Boolean) {
            boolean next = (Boolean) value;
            if (next != skyEnabled) {
                skyEnabled = next;
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
        if ("reset".equals(k) || "resetaccumulation".equals(k)) {
            resetAccumulation();
            return;
        }
        if ("shutdown".equals(k) && value instanceof Boolean && (Boolean) value) {
            shutdownPool();
        }
    }

    @Override
    public String getName() {
        return "Ray Tracer (CPU)";
    }

    public int getWorkerCount() {
        return workerCount;
    }

    public synchronized long getAccumulatedSamples() {
        return accumulatedSamples;
    }

    public synchronized int getSamplesPerFrame() {
        return samplesPerFrame;
    }

    public synchronized String getActivePreviewQualityTier() {
        return activePreviewQualityTier;
    }

    public synchronized long getActivePreviewFrameGeneration() {
        return activePreviewFrameGeneration;
    }

    public synchronized long getActiveBaseCarrierGeneration() {
        return activeBaseCarrierGeneration;
    }

    public synchronized long getActivePolishCompositeGeneration() {
        return activePolishCompositeGeneration;
    }

    public synchronized long getLastComposedFrameGeneration() {
        return lastComposedFrameGeneration;
    }

    public synchronized long getLastComposedBaseGeneration() {
        return lastComposedBaseGeneration;
    }

    public synchronized long getLastComposedPolishGeneration() {
        return lastComposedPolishGeneration;
    }

    public synchronized int getActivePreviewPolishCadence() {
        return activePreviewPolishCadence;
    }

    public synchronized int getActivePreviewPolishSamplesPerFrame() {
        return activePreviewPolishSamplesPerFrame;
    }

    public synchronized int getActivePreviewPolishSecondaryDepth() {
        return activePreviewPolishSecondaryDepth;
    }

    public synchronized double getActivePreviewPolishScale() {
        return activePreviewPolishScale;
    }

    public synchronized double getConfiguredPreviewMotionPolishScale() {
        return previewMotionPolishScale;
    }

    public synchronized double getConfiguredPreviewMotionBaseShadingScale() {
        return previewMotionBaseShadingScale;
    }

    public synchronized double getActiveHybridBaseShadingScale() {
        return activeHybridBaseShadingScale;
    }

    public synchronized int getActiveHybridBaseShadingWidth() {
        return hybridBaseShadeWidth;
    }

    public synchronized int getActiveHybridBaseShadingHeight() {
        return hybridBaseShadeHeight;
    }

    public synchronized int getActivePreviewPolishWidth() {
        return polishWidth;
    }

    public synchronized int getActivePreviewPolishHeight() {
        return polishHeight;
    }

    public synchronized boolean isActivePolishUpscaleMapReuseActive() {
        return polishUpscaleMapValid
                && polishUpscaleMapFullWidth == width
                && polishUpscaleMapFullHeight == height
                && polishUpscaleMapSourceWidth == polishWidth
                && polishUpscaleMapSourceHeight == polishHeight
                && (polishWidth != width || polishHeight != height);
    }

    public synchronized long getActivePolishUpscaleMapMemoryBytes() {
        long ints = (long) polishUpscaleMapX0.length
                + polishUpscaleMapX1.length
                + polishUpscaleMapY0Row.length
                + polishUpscaleMapY1Row.length;
        long floats = (long) polishUpscaleMapWx0.length
                + polishUpscaleMapWx1.length
                + polishUpscaleMapWy0.length
                + polishUpscaleMapWy1.length;
        return ints * Integer.BYTES + floats * Float.BYTES;
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

    private record PreviewQualityTierPlan(String name,
                                          int polishSecondaryDepth,
                                          int polishCadence,
                                          int polishSamplesPerFrame,
                                          double polishScale,
                                          int carrierDenoisePassCap,
                                          int carrierDenoiseRadiusCap,
                                          int carrierMaxLocalLights,
                                          int carrierMaxShadowedLocalLights,
                                          boolean carrierSkipDenoiserAnalysis,
                                          boolean carrierDisableSheen,
                                          boolean carrierDisableClearcoat,
                                          boolean carrierDisableDirectionalShadows,
                                          boolean carrierDisablePointLightShadows,
                                          boolean carrierPointLightsDiffuseOnly,
                                          boolean carrierDiffuseOnlyDirectLighting,
                                          boolean carrierDisableReflections,
                                          boolean carrierDisableTransmission,
                                          double carrierSecondaryRoughnessSkip,
                                          double carrierSecondaryThroughputTermination) {
    }

    private void executeCarrierPass(int tileCols,
                                    int tileW,
                                    int tileH,
                                    int fbWidth,
                                    int fbHeight,
                                    RayCameraState camera,
                                    int[] outColor,
                                    boolean captureGuides,
                                    int effectiveSamplesPerFrame,
                                    long sampleTarget,
                                    int activeWorkers,
                                    int tileCount,
                                    boolean[] tileRenderPlan,
                                    LongAdder tileCostNanos,
                                    LongAdder tileCostSamples) {
        RayCarrierTraceMetrics carrierMetrics = RuntimeInstrumentation.isEnabled() ? new RayCarrierTraceMetrics() : null;
        if (activeWorkers <= 1 || threadPool == null || tileCount <= 1) {
            RayTraceContext ctx = new RayTraceContext();
            configureTraceContextForCarrier(ctx, carrierMetrics);
            RaySplitMix64 rng = new RaySplitMix64(seedForWorker(0, sampleTarget));
            for (int tile = 0; tile < tileCount; tile++) {
                if (tileRenderPlan != null && (tile >= tileRenderPlan.length || !tileRenderPlan[tile])) {
                    continue;
                }
                long tileStartNanos = System.nanoTime();
                renderCarrierTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, camera, outColor,
                    captureGuides, effectiveSamplesPerFrame, rng, ctx);
                recordTileCost(tileCostNanos, tileCostSamples, tileStartNanos);
            }
            flushCarrierTraceMetrics(carrierMetrics);
            return;
        }

        AtomicInteger tileCursor = new AtomicInteger(0);
        Runnable[] tasks = new Runnable[activeWorkers];
        for (int w = 0; w < activeWorkers; w++) {
            final int workerIndex = w;
            tasks[w] = () -> {
                RayTraceContext ctx = new RayTraceContext();
                configureTraceContextForCarrier(ctx, carrierMetrics);
                RaySplitMix64 rng = new RaySplitMix64(seedForWorker(workerIndex, sampleTarget));
                int chunkStart;
                while ((chunkStart = tileCursor.getAndAdd(TILE_CLAIM_CHUNK)) < tileCount) {
                    int chunkEnd = Math.min(tileCount, chunkStart + TILE_CLAIM_CHUNK);
                    for (int tile = chunkStart; tile < chunkEnd; tile++) {
                        if (tileRenderPlan != null && (tile >= tileRenderPlan.length || !tileRenderPlan[tile])) {
                            continue;
                        }
                        long tileStartNanos = System.nanoTime();
                        renderCarrierTile(tile, tileCols, tileW, tileH, fbWidth, fbHeight, camera, outColor,
                            captureGuides, effectiveSamplesPerFrame, rng, ctx);
                        recordTileCost(tileCostNanos, tileCostSamples, tileStartNanos);
                    }
                }
            };
        }
        threadPool.submitAndWait(tasks);
        flushCarrierTraceMetrics(carrierMetrics);
    }

    private void configureTraceContextForCarrier(RayTraceContext ctx, RayCarrierTraceMetrics metrics) {
        if (ctx == null) {
            return;
        }
        ctx.carrierMetrics = metrics;
        ctx.carrierDisableSheen = activeCarrierDisableSheen;
        ctx.carrierDisableClearcoat = activeCarrierDisableClearcoat;
        ctx.carrierDisableDirectionalShadows = activeCarrierDisableDirectionalShadows;
        ctx.carrierDisablePointLightShadows = activeCarrierDisablePointLightShadows;
        ctx.carrierPointLightsDiffuseOnly = activeCarrierPointLightsDiffuseOnly;
        ctx.carrierDiffuseOnlyDirectLighting = activeCarrierDiffuseOnlyDirectLighting;
        ctx.carrierMaxLocalLights = activeCarrierMaxLocalLights;
        ctx.carrierMaxShadowedLocalLights = activeCarrierMaxShadowedLocalLights;
        ctx.carrierLeafSampleScale = 0;
    }

    private void flushCarrierTraceMetrics(RayCarrierTraceMetrics metrics) {
        if (metrics == null || !RuntimeInstrumentation.isEnabled()) {
            return;
        }
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_PRIMARY_INTERSECTION_NS,
                metrics.primaryIntersectionNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SURFACE_SAMPLE_NS,
                metrics.surfaceSampleNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DIRECT_LIGHT_NS,
                metrics.directLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SHADOW_QUERY_NS,
                metrics.shadowQueryNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_ENVIRONMENT_NS,
                metrics.environmentNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_EXTRA_MATERIAL_LOBES_NS,
                metrics.extraMaterialLobesNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DIRECTIONAL_LIGHT_NS,
                metrics.directionalLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_POINT_LIGHT_NS,
                metrics.pointLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SPOT_LIGHT_NS,
                metrics.spotLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_AREA_LIGHT_NS,
                metrics.areaLightNanos.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_CANDIDATES,
                metrics.localLightCandidates.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_SHADED,
                metrics.localLightShaded.sum());
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_LOCAL_LIGHT_SHADOWED,
                metrics.localLightShadowed.sum());
    }

    private void renderCarrierTile(int tileIndex,
                                   int tileCols,
                                   int tileW,
                                   int tileH,
                                   int fbWidth,
                                   int fbHeight,
                                   RayCameraState camera,
                                   int[] outColor,
                                   boolean captureGuides,
                                   int effectiveSamplesPerFrame,
                                   RaySplitMix64 rng,
                                   RayTraceContext ctx) {
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
                    if (ctx.carrierMetrics != null && ((x & 7) == 0) && ((y & 7) == 0)) {
                        ctx.carrierLeafSampleScale = 64;
                    } else {
                        ctx.carrierLeafSampleScale = 0;
                    }
                    traceCarrier(ctx);
                    if (needsGuideCapture) {
                        storePrimaryGuide(idx, ctx);
                        needsGuideCapture = false;
                    }
                    double sampleR = ctx.outR;
                    double sampleG = ctx.outG;
                    double sampleB = ctx.outB;
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

    private void executeHybridBaseCarrierPass(Scene scene,
                                              Camera camera,
                                              FrameBuffer target,
                                              double time) {
        if (scene == null || camera == null || target == null) {
            return;
        }
        hybridBaseRenderer.render(scene, camera, target, time);
        if (activeHybridBaseReducedShading) {
            if (shouldRebuildMovingHybridBaseComposite()) {
                buildReducedMovingHybridBaseComposite(camera, target);
            }
        } else {
            hybridBaseCompositePackedValid = false;
        }
    }

    private boolean shouldRebuildMovingHybridBaseComposite() {
        if (!previewMotionActive) {
            return true;
        }
        int cadence = Math.max(1, previewMotionBaseCompositeCadence);
        if (cadence <= 1) {
            return true;
        }
        if (!hybridBaseCompositePackedValid || previewPhase == PreviewPhase.MOTION_ENTER) {
            return true;
        }
        return (previewPhaseFrameSequence % cadence) == 0;
    }

    private void buildReducedMovingHybridBaseComposite(Camera camera, FrameBuffer target) {
        if (camera == null || target == null || !activeHybridBaseReducedShading) {
            hybridBaseCompositePackedValid = false;
            return;
        }
        int fullWidth = target.getWidth();
        int fullHeight = target.getHeight();
        ensureHybridBaseShadingBuffers(fullWidth, fullHeight);
        int[] baseColor = target.getColorBuffer();
        float[] baseDepth = target.getDepthBuffer();
        float[] baseNormal = target.getNormalBuffer();
        float[] baseWorldPos = target.getWorldPosBuffer();
        int[] baseObjectId = target.getObjectIdBuffer();
        if (baseColor == null
                || baseDepth == null
                || baseNormal == null
                || baseWorldPos == null
                || baseObjectId == null) {
            hybridBaseCompositePackedValid = false;
            return;
        }

        final boolean measure = RuntimeInstrumentation.isEnabled();
        long outputStart = measure ? System.nanoTime() : 0L;
        long reducedShadeStart = measure ? System.nanoTime() : 0L;
        int shadeCount = Math.min(safePixelCount(hybridBaseShadeWidth, hybridBaseShadeHeight), hybridBaseShadeR.length);
        int pointLightLimit = activeCarrierMaxLocalLights < 0
                ? pointLights.length
                : Math.min(activeCarrierMaxLocalLights, pointLights.length);
        for (int sy = 0; sy < hybridBaseShadeHeight; sy++) {
            int fullY = clampIndex((int) Math.round(((sy + 0.5) * fullHeight / Math.max(1.0, (double) hybridBaseShadeHeight)) - 0.5), fullHeight);
            int row = sy * hybridBaseShadeWidth;
            for (int sx = 0; sx < hybridBaseShadeWidth; sx++) {
                int shadeIdx = row + sx;
                if (shadeIdx >= shadeCount) {
                    break;
                }
                int fullX = clampIndex((int) Math.round(((sx + 0.5) * fullWidth / Math.max(1.0, (double) hybridBaseShadeWidth)) - 0.5), fullWidth);
                int fullIdx = fullY * fullWidth + fullX;
                int objectId = baseObjectId[fullIdx];
                float depth = baseDepth[fullIdx];
                int normalBase = fullIdx * 3;
                float nx = baseNormal[normalBase];
                float ny = baseNormal[normalBase + 1];
                float nz = baseNormal[normalBase + 2];
                double nLenSq = nx * nx + ny * ny + nz * nz;
                if (nLenSq > 1e-12) {
                    double inv = 1.0 / Math.sqrt(nLenSq);
                    nx *= inv;
                    ny *= inv;
                    nz *= inv;
                } else {
                    nx = 0.0f;
                    ny = 1.0f;
                    nz = 0.0f;
                }

                if (objectId < 0 || depth >= 1.0f) {
                    hybridBaseShadeObjectId[shadeIdx] = objectId;
                    hybridBaseShadeDepth[shadeIdx] = depth;
                    hybridBaseShadeR[shadeIdx] = 1.0f;
                    hybridBaseShadeG[shadeIdx] = 1.0f;
                    hybridBaseShadeB[shadeIdx] = 1.0f;
                    continue;
                }

                double wx = baseWorldPos[normalBase];
                double wy = baseWorldPos[normalBase + 1];
                double wz = baseWorldPos[normalBase + 2];
                double outR = ambientR;
                double outG = ambientG;
                double outB = ambientB;

                for (RayDirLightCache light : dirLights) {
                    double ndotl = Math.max(0.0, nx * light.lx + ny * light.ly + nz * light.lz);
                    if (ndotl <= 0.0) {
                        continue;
                    }
                    outR += light.r * ndotl;
                    outG += light.g * ndotl;
                    outB += light.b * ndotl;
                }

                if (pointLightLimit != 0) {
                    int shadedLights = 0;
                    for (RayPointLightCache light : pointLights) {
                        double lx = light.px - wx;
                        double ly = light.py - wy;
                        double lz = light.pz - wz;
                        double dist2 = lx * lx + ly * ly + lz * lz;
                        if (dist2 < 1e-12) {
                            continue;
                        }
                        double dist = Math.sqrt(dist2);
                        double invDist = 1.0 / dist;
                        lx *= invDist;
                        ly *= invDist;
                        lz *= invDist;
                        double ndotl = Math.max(0.0, nx * lx + ny * ly + nz * lz);
                        if (ndotl <= 0.0) {
                            continue;
                        }
                        double attenuation = light.light.attenuation(dist) * light.light.angularAttenuation(wx, wy, wz);
                        if (attenuation <= 1e-12) {
                            continue;
                        }
                        outR += light.r * attenuation * ndotl;
                        outG += light.g * attenuation * ndotl;
                        outB += light.b * attenuation * ndotl;
                        shadedLights++;
                        if (pointLightLimit > 0 && shadedLights >= pointLightLimit) {
                            break;
                        }
                    }
                }

                hybridBaseShadeObjectId[shadeIdx] = objectId;
                hybridBaseShadeDepth[shadeIdx] = depth;
                hybridBaseShadeR[shadeIdx] = (float) outR;
                hybridBaseShadeG[shadeIdx] = (float) outG;
                hybridBaseShadeB[shadeIdx] = (float) outB;
            }
        }
        long reducedShadeTotalNanos = measure ? System.nanoTime() - reducedShadeStart : 0L;
        if (measure) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_REDUCED_SHADE_NS,
                    reducedShadeTotalNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_REDUCED_SHADE_STORE_NS,
                    0L);
        }

        ensureHybridBaseUpscaleRowCapacity(fullWidth);
        long mapBuildNanos = ensureHybridBaseUpscaleMap();
        if (measure && mapBuildNanos > 0L) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_UPSCALE_MAP_BUILD_NS,
                    mapBuildNanos);
        }
        long inactiveScanNanos = 0L;
        long guidePrecheckNanos = 0L;
        long fastUpscaleNanos = 0L;
        long edgeWeightNanos = 0L;
        long finalWriteNanos = 0L;
        long fastPathPixels = 0L;
        long edgePathPixels = 0L;
        int fullCount = Math.min(safePixelCount(fullWidth, fullHeight), hybridBaseCompositePacked.length);
        long baseCopyStart = measure ? System.nanoTime() : 0L;
        System.arraycopy(baseColor, 0, hybridBaseCompositePacked, 0, fullCount);
        if (measure) {
            finalWriteNanos += System.nanoTime() - baseCopyStart;
        }
        long sparseMaskStart = measure ? System.nanoTime() : 0L;
        buildHybridBaseSparseTileMask(fullWidth, fullHeight, fullCount, baseObjectId, baseDepth);
        if (measure) {
            inactiveScanNanos += System.nanoTime() - sparseMaskStart;
        }

        for (int tileY = 0; tileY < hybridBaseSparseTileRows; tileY++) {
            int yStart = tileY * MOVING_HYBRID_SPARSE_TILE_SIZE;
            int yEnd = Math.min(fullHeight, yStart + MOVING_HYBRID_SPARSE_TILE_SIZE);
            int tileRow = tileY * hybridBaseSparseTileCols;
            for (int tileX = 0; tileX < hybridBaseSparseTileCols; tileX++) {
                if (hybridBaseSparseTileMask[tileRow + tileX] == 0) {
                    continue;
                }
                int xStart = tileX * MOVING_HYBRID_SPARSE_TILE_SIZE;
                int xEnd = Math.min(fullWidth, xStart + MOVING_HYBRID_SPARSE_TILE_SIZE);
                for (int y = yStart; y < yEnd; y++) {
                    int row = y * fullWidth;
                    int y0Row = hybridBaseUpscaleMapY0Row[y];
                    int y1Row = hybridBaseUpscaleMapY1Row[y];
                    double wy0 = hybridBaseUpscaleMapWy0[y];
                    double wy1 = hybridBaseUpscaleMapWy1[y];
                    int edgeCount = 0;

                    for (int x = xStart; x < xEnd; x++) {
                        int idx = row + x;
                        if (idx >= fullCount) {
                            break;
                        }
                        int objectId = baseObjectId[idx];
                        float depth = baseDepth[idx];
                        if (objectId < 0 || depth >= 1.0f) {
                            continue;
                        }
                        long precheckStart = measure ? System.nanoTime() : 0L;
                        int x0 = hybridBaseUpscaleMapX0[x];
                        int x1 = hybridBaseUpscaleMapX1[x];
                        double wx0 = hybridBaseUpscaleMapWx0[x];
                        double wx1 = hybridBaseUpscaleMapWx1[x];
                        int sample00 = y0Row + x0;
                        int sample10 = y0Row + x1;
                        int sample01 = y1Row + x0;
                        int sample11 = y1Row + x1;
                        hybridBaseUpscaleRowSample00[x] = sample00;
                        hybridBaseUpscaleRowSample10[x] = sample10;
                        hybridBaseUpscaleRowSample01[x] = sample01;
                        hybridBaseUpscaleRowSample11[x] = sample11;
                        hybridBaseUpscaleRowW00[x] = (float) (wx0 * wy0);
                        hybridBaseUpscaleRowW10[x] = (float) (wx1 * wy0);
                        hybridBaseUpscaleRowW01[x] = (float) (wx0 * wy1);
                        hybridBaseUpscaleRowW11[x] = (float) (wx1 * wy1);
                        boolean fast = isHybridBaseFastPathEligible(
                                objectId,
                                depth,
                                sample00,
                                sample10,
                                sample01,
                                sample11,
                                shadeCount);
                        if (measure) {
                            guidePrecheckNanos += System.nanoTime() - precheckStart;
                        }
                        if (fast) {
                            long fastPathStart = measure ? System.nanoTime() : 0L;
                            float w00 = hybridBaseUpscaleRowW00[x];
                            float w10 = hybridBaseUpscaleRowW10[x];
                            float w01 = hybridBaseUpscaleRowW01[x];
                            float w11 = hybridBaseUpscaleRowW11[x];
                            float shadeR = hybridBaseShadeR[sample00] * w00
                                    + hybridBaseShadeR[sample10] * w10
                                    + hybridBaseShadeR[sample01] * w01
                                    + hybridBaseShadeR[sample11] * w11;
                            float shadeG = hybridBaseShadeG[sample00] * w00
                                    + hybridBaseShadeG[sample10] * w10
                                    + hybridBaseShadeG[sample01] * w01
                                    + hybridBaseShadeG[sample11] * w11;
                            float shadeB = hybridBaseShadeB[sample00] * w00
                                    + hybridBaseShadeB[sample10] * w10
                                    + hybridBaseShadeB[sample01] * w01
                                    + hybridBaseShadeB[sample11] * w11;
                            if (measure) {
                                fastUpscaleNanos += System.nanoTime() - fastPathStart;
                            }
                            long writeStart = measure ? System.nanoTime() : 0L;
                            int argb = baseColor[idx];
                            double baseR = ((argb >>> 16) & 0xFF) / 255.0;
                            double baseG = ((argb >>> 8) & 0xFF) / 255.0;
                            double baseB = (argb & 0xFF) / 255.0;
                            hybridBaseCompositePacked[idx] = packColor(baseR * shadeR, baseG * shadeG, baseB * shadeB);
                            if (measure) {
                                long elapsed = System.nanoTime() - writeStart;
                                finalWriteNanos += elapsed;
                            }
                            fastPathPixels++;
                        } else {
                            hybridBaseUpscaleRowObjectId[x] = objectId;
                            hybridBaseUpscaleRowDepth[x] = depth;
                            hybridBaseUpscaleRowEdgeX[edgeCount++] = x;
                        }
                    }

                    long edgePathStart = measure ? System.nanoTime() : 0L;
                    for (int i = 0; i < edgeCount; i++) {
                        int x = hybridBaseUpscaleRowEdgeX[i];
                        int objectId = hybridBaseUpscaleRowObjectId[x];
                        float depth = hybridBaseUpscaleRowDepth[x];
                        int sample00 = hybridBaseUpscaleRowSample00[x];
                        int sample10 = hybridBaseUpscaleRowSample10[x];
                        int sample01 = hybridBaseUpscaleRowSample01[x];
                        int sample11 = hybridBaseUpscaleRowSample11[x];
                        double weightSum = 0.0;
                        double shadeR = 0.0;
                        double shadeG = 0.0;
                        double shadeB = 0.0;

                        weightSum += accumulateHybridBaseDepthAwareSample(
                                objectId, depth, sample00, hybridBaseUpscaleRowW00[x], shadeCount);
                        shadeR += hybridBaseUpscaleAccumR;
                        shadeG += hybridBaseUpscaleAccumG;
                        shadeB += hybridBaseUpscaleAccumB;

                        weightSum += accumulateHybridBaseDepthAwareSample(
                                objectId, depth, sample10, hybridBaseUpscaleRowW10[x], shadeCount);
                        shadeR += hybridBaseUpscaleAccumR;
                        shadeG += hybridBaseUpscaleAccumG;
                        shadeB += hybridBaseUpscaleAccumB;

                        weightSum += accumulateHybridBaseDepthAwareSample(
                                objectId, depth, sample01, hybridBaseUpscaleRowW01[x], shadeCount);
                        shadeR += hybridBaseUpscaleAccumR;
                        shadeG += hybridBaseUpscaleAccumG;
                        shadeB += hybridBaseUpscaleAccumB;

                        weightSum += accumulateHybridBaseDepthAwareSample(
                                objectId, depth, sample11, hybridBaseUpscaleRowW11[x], shadeCount);
                        shadeR += hybridBaseUpscaleAccumR;
                        shadeG += hybridBaseUpscaleAccumG;
                        shadeB += hybridBaseUpscaleAccumB;

                        if (weightSum <= 1e-9) {
                            int fallbackIdx = resolveHybridBaseFallbackSample(
                                    objectId,
                                    sample00, hybridBaseUpscaleRowW00[x],
                                    sample10, hybridBaseUpscaleRowW10[x],
                                    sample01, hybridBaseUpscaleRowW01[x],
                                    sample11, hybridBaseUpscaleRowW11[x],
                                    shadeCount);
                            if (fallbackIdx >= 0) {
                                hybridBaseUpscaleRowShadeR[x] = hybridBaseShadeR[fallbackIdx];
                                hybridBaseUpscaleRowShadeG[x] = hybridBaseShadeG[fallbackIdx];
                                hybridBaseUpscaleRowShadeB[x] = hybridBaseShadeB[fallbackIdx];
                            } else {
                                hybridBaseUpscaleRowShadeR[x] = 1.0f;
                                hybridBaseUpscaleRowShadeG[x] = 1.0f;
                                hybridBaseUpscaleRowShadeB[x] = 1.0f;
                            }
                        } else {
                            double invWeight = 1.0 / weightSum;
                            hybridBaseUpscaleRowShadeR[x] = (float) (shadeR * invWeight);
                            hybridBaseUpscaleRowShadeG[x] = (float) (shadeG * invWeight);
                            hybridBaseUpscaleRowShadeB[x] = (float) (shadeB * invWeight);
                        }
                        edgePathPixels++;
                    }
                    if (measure) {
                        edgeWeightNanos += System.nanoTime() - edgePathStart;
                    }

                    long writeStart = measure ? System.nanoTime() : 0L;
                    for (int i = 0; i < edgeCount; i++) {
                        int x = hybridBaseUpscaleRowEdgeX[i];
                        int idx = row + x;
                        if (idx >= fullCount) {
                            continue;
                        }
                        int argb = baseColor[idx];
                        double baseR = ((argb >>> 16) & 0xFF) / 255.0;
                        double baseG = ((argb >>> 8) & 0xFF) / 255.0;
                        double baseB = (argb & 0xFF) / 255.0;
                        hybridBaseCompositePacked[idx] = packColor(
                                baseR * hybridBaseUpscaleRowShadeR[x],
                                baseG * hybridBaseUpscaleRowShadeG[x],
                                baseB * hybridBaseUpscaleRowShadeB[x]);
                    }
                    if (measure) {
                        long elapsed = System.nanoTime() - writeStart;
                        finalWriteNanos += elapsed;
                    }
                }
            }
        }
        if (measure) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_GUIDED_UPSCALE_NS,
                    guidePrecheckNanos + fastUpscaleNanos + edgeWeightNanos + finalWriteNanos + mapBuildNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_INACTIVE_SCAN_NS,
                    inactiveScanNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_GUIDE_PRECHECK_NS,
                    guidePrecheckNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_UPSCALE_FAST_PATH_NS,
                    fastUpscaleNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_EDGE_WEIGHT_NS,
                    edgeWeightNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_FINAL_COMPOSITE_WRITE_NS,
                    finalWriteNanos);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_FAST_PATH_PIXELS,
                    fastPathPixels);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_EDGE_PATH_PIXELS,
                    edgePathPixels);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_OUTPUT_NS,
                    System.nanoTime() - outputStart);
        }
        hybridBaseCompositePackedValid = true;
    }

    private double hybridBaseUpscaleAccumR = 0.0;
    private double hybridBaseUpscaleAccumG = 0.0;
    private double hybridBaseUpscaleAccumB = 0.0;

    private double accumulateHybridBaseDepthAwareSample(int objectId,
                                                        float depth,
                                                        int sampleIdx,
                                                        double bilinearWeight,
                                                        int shadeCount) {
        hybridBaseUpscaleAccumR = 0.0;
        hybridBaseUpscaleAccumG = 0.0;
        hybridBaseUpscaleAccumB = 0.0;
        if (sampleIdx < 0 || sampleIdx >= shadeCount || bilinearWeight <= 1e-9) {
            return 0.0;
        }
        if (hybridBaseShadeObjectId[sampleIdx] != objectId) {
            return 0.0;
        }
        float sampleDepth = hybridBaseShadeDepth[sampleIdx];
        double depthTolerance = hybridBaseDepthTolerance(depth);
        double depthWeight = 1.0 - Math.min(1.0, Math.abs(sampleDepth - depth) / depthTolerance);
        if (depthWeight <= 0.0) {
            return 0.0;
        }
        double weight = bilinearWeight * depthWeight;
        hybridBaseUpscaleAccumR = hybridBaseShadeR[sampleIdx] * weight;
        hybridBaseUpscaleAccumG = hybridBaseShadeG[sampleIdx] * weight;
        hybridBaseUpscaleAccumB = hybridBaseShadeB[sampleIdx] * weight;
        return weight;
    }

    private boolean isHybridBaseFastPathEligible(int objectId,
                                                 float depth,
                                                 int sample00,
                                                 int sample10,
                                                 int sample01,
                                                 int sample11,
                                                 int shadeCount) {
        if (sample00 < 0 || sample10 < 0 || sample01 < 0 || sample11 < 0
                || sample00 >= shadeCount || sample10 >= shadeCount
                || sample01 >= shadeCount || sample11 >= shadeCount) {
            return false;
        }
        if (hybridBaseShadeObjectId[sample00] != objectId
                || hybridBaseShadeObjectId[sample10] != objectId
                || hybridBaseShadeObjectId[sample01] != objectId
                || hybridBaseShadeObjectId[sample11] != objectId) {
            return false;
        }
        double tolerance = hybridBaseDepthTolerance(depth);
        float depth00 = hybridBaseShadeDepth[sample00];
        float depth10 = hybridBaseShadeDepth[sample10];
        float depth01 = hybridBaseShadeDepth[sample01];
        float depth11 = hybridBaseShadeDepth[sample11];
        if (Math.abs(depth00 - depth) > tolerance
                || Math.abs(depth10 - depth) > tolerance
                || Math.abs(depth01 - depth) > tolerance
                || Math.abs(depth11 - depth) > tolerance) {
            return false;
        }
        float minDepth = Math.min(Math.min(depth00, depth10), Math.min(depth01, depth11));
        float maxDepth = Math.max(Math.max(depth00, depth10), Math.max(depth01, depth11));
        return (maxDepth - minDepth) <= tolerance;
    }

    private int resolveHybridBaseFallbackSample(int objectId,
                                                int sample00, float weight00,
                                                int sample10, float weight10,
                                                int sample01, float weight01,
                                                int sample11, float weight11,
                                                int shadeCount) {
        int bestIdx = -1;
        float bestWeight = -1.0f;
        if (sample00 >= 0 && sample00 < shadeCount && hybridBaseShadeObjectId[sample00] == objectId && weight00 > bestWeight) {
            bestIdx = sample00;
            bestWeight = weight00;
        }
        if (sample10 >= 0 && sample10 < shadeCount && hybridBaseShadeObjectId[sample10] == objectId && weight10 > bestWeight) {
            bestIdx = sample10;
            bestWeight = weight10;
        }
        if (sample01 >= 0 && sample01 < shadeCount && hybridBaseShadeObjectId[sample01] == objectId && weight01 > bestWeight) {
            bestIdx = sample01;
            bestWeight = weight01;
        }
        if (sample11 >= 0 && sample11 < shadeCount && hybridBaseShadeObjectId[sample11] == objectId && weight11 > bestWeight) {
            bestIdx = sample11;
        }
        return bestIdx;
    }

    private double hybridBaseDepthTolerance(float depth) {
        return Math.max(
                MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_MIN,
                Math.min(MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_MAX, depth * MOVING_HYBRID_GUIDE_DEPTH_TOLERANCE_SCALE));
    }

    private void ensureHybridBaseShadingBuffers(int fullWidth, int fullHeight) {
        int targetWidth = resolveScaledDimension(fullWidth, activeHybridBaseShadingScale);
        int targetHeight = resolveScaledDimension(fullHeight, activeHybridBaseShadingScale);
        int shadeCount = safePixelCount(targetWidth, targetHeight);
        if (hybridBaseShadeWidth != targetWidth
                || hybridBaseShadeHeight != targetHeight
                || hybridBaseShadeR.length != shadeCount
                || hybridBaseShadeObjectId.length != shadeCount) {
            hybridBaseShadeWidth = targetWidth;
            hybridBaseShadeHeight = targetHeight;
            hybridBaseShadeR = new float[shadeCount];
            hybridBaseShadeG = new float[shadeCount];
            hybridBaseShadeB = new float[shadeCount];
            hybridBaseShadeDepth = new float[shadeCount];
            hybridBaseShadeObjectId = new int[shadeCount];
            invalidateHybridBaseUpscaleMap();
            hybridBaseCompositePackedValid = false;
        }
    }

    private long ensureHybridBaseUpscaleMap() {
        if (hybridBaseShadeWidth == width && hybridBaseShadeHeight == height) {
            invalidateHybridBaseUpscaleMap();
            return 0L;
        }
        if (hybridBaseUpscaleMapValid
                && hybridBaseUpscaleMapFullWidth == width
                && hybridBaseUpscaleMapFullHeight == height
                && hybridBaseUpscaleMapSourceWidth == hybridBaseShadeWidth
                && hybridBaseUpscaleMapSourceHeight == hybridBaseShadeHeight
                && hybridBaseUpscaleMapX0.length >= width
                && hybridBaseUpscaleMapY0Row.length >= height) {
            return 0L;
        }
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        ensureHybridBaseUpscaleMapCapacity(width, height);
        for (int x = 0; x < width; x++) {
            double srcX = ((x + 0.5) * hybridBaseShadeWidth / Math.max(1.0, (double) width)) - 0.5;
            int x0 = clampIndex((int) Math.floor(srcX), hybridBaseShadeWidth);
            int x1 = clampIndex(x0 + 1, hybridBaseShadeWidth);
            double tx = clamp(srcX - x0, 0.0, 1.0);
            hybridBaseUpscaleMapX0[x] = x0;
            hybridBaseUpscaleMapX1[x] = x1;
            hybridBaseUpscaleMapWx0[x] = (float) (1.0 - tx);
            hybridBaseUpscaleMapWx1[x] = (float) tx;
        }
        for (int y = 0; y < height; y++) {
            double srcY = ((y + 0.5) * hybridBaseShadeHeight / Math.max(1.0, (double) height)) - 0.5;
            int y0 = clampIndex((int) Math.floor(srcY), hybridBaseShadeHeight);
            int y1 = clampIndex(y0 + 1, hybridBaseShadeHeight);
            double ty = clamp(srcY - y0, 0.0, 1.0);
            hybridBaseUpscaleMapY0Row[y] = y0 * hybridBaseShadeWidth;
            hybridBaseUpscaleMapY1Row[y] = y1 * hybridBaseShadeWidth;
            hybridBaseUpscaleMapWy0[y] = (float) (1.0 - ty);
            hybridBaseUpscaleMapWy1[y] = (float) ty;
        }
        hybridBaseUpscaleMapValid = true;
        hybridBaseUpscaleMapFullWidth = width;
        hybridBaseUpscaleMapFullHeight = height;
        hybridBaseUpscaleMapSourceWidth = hybridBaseShadeWidth;
        hybridBaseUpscaleMapSourceHeight = hybridBaseShadeHeight;
        return RuntimeInstrumentation.isEnabled() ? System.nanoTime() - start : 0L;
    }

    private void ensureHybridBaseUpscaleMapCapacity(int fullWidth, int fullHeight) {
        if (hybridBaseUpscaleMapX0.length < fullWidth) {
            hybridBaseUpscaleMapX0 = new int[fullWidth];
            hybridBaseUpscaleMapX1 = new int[fullWidth];
            hybridBaseUpscaleMapWx0 = new float[fullWidth];
            hybridBaseUpscaleMapWx1 = new float[fullWidth];
        }
        if (hybridBaseUpscaleMapY0Row.length < fullHeight) {
            hybridBaseUpscaleMapY0Row = new int[fullHeight];
            hybridBaseUpscaleMapY1Row = new int[fullHeight];
            hybridBaseUpscaleMapWy0 = new float[fullHeight];
            hybridBaseUpscaleMapWy1 = new float[fullHeight];
        }
    }

    private void invalidateHybridBaseUpscaleMap() {
        hybridBaseUpscaleMapValid = false;
        hybridBaseUpscaleMapFullWidth = 0;
        hybridBaseUpscaleMapFullHeight = 0;
        hybridBaseUpscaleMapSourceWidth = 0;
        hybridBaseUpscaleMapSourceHeight = 0;
    }

    private void ensureHybridBaseUpscaleRowCapacity(int fullWidth) {
        if (hybridBaseUpscaleRowMode.length >= fullWidth) {
            return;
        }
        hybridBaseUpscaleRowMode = new byte[fullWidth];
        hybridBaseUpscaleRowObjectId = new int[fullWidth];
        hybridBaseUpscaleRowDepth = new float[fullWidth];
        hybridBaseUpscaleRowSample00 = new int[fullWidth];
        hybridBaseUpscaleRowSample10 = new int[fullWidth];
        hybridBaseUpscaleRowSample01 = new int[fullWidth];
        hybridBaseUpscaleRowSample11 = new int[fullWidth];
        hybridBaseUpscaleRowW00 = new float[fullWidth];
        hybridBaseUpscaleRowW10 = new float[fullWidth];
        hybridBaseUpscaleRowW01 = new float[fullWidth];
        hybridBaseUpscaleRowW11 = new float[fullWidth];
        hybridBaseUpscaleRowShadeR = new float[fullWidth];
        hybridBaseUpscaleRowShadeG = new float[fullWidth];
        hybridBaseUpscaleRowShadeB = new float[fullWidth];
        hybridBaseUpscaleRowEdgeX = new int[fullWidth];
    }

    private void ensureHybridBaseSparseTileMaskCapacity(int fullWidth, int fullHeight) {
        int tileCols = Math.max(1, (fullWidth + MOVING_HYBRID_SPARSE_TILE_SIZE - 1) / MOVING_HYBRID_SPARSE_TILE_SIZE);
        int tileRows = Math.max(1, (fullHeight + MOVING_HYBRID_SPARSE_TILE_SIZE - 1) / MOVING_HYBRID_SPARSE_TILE_SIZE);
        int tileCount = Math.max(1, safePixelCount(tileCols, tileRows));
        if (hybridBaseSparseTileMask.length < tileCount) {
            hybridBaseSparseTileMask = new byte[tileCount];
        }
        Arrays.fill(hybridBaseSparseTileMask, 0, tileCount, (byte) 0);
        hybridBaseSparseTileCols = tileCols;
        hybridBaseSparseTileRows = tileRows;
    }

    private void markHybridBaseSparseTileNeighborhood(int tileX, int tileY) {
        int minTileX = Math.max(0, tileX - 1);
        int maxTileX = Math.min(hybridBaseSparseTileCols - 1, tileX + 1);
        int minTileY = Math.max(0, tileY - 1);
        int maxTileY = Math.min(hybridBaseSparseTileRows - 1, tileY + 1);
        for (int ny = minTileY; ny <= maxTileY; ny++) {
            int row = ny * hybridBaseSparseTileCols;
            for (int nx = minTileX; nx <= maxTileX; nx++) {
                hybridBaseSparseTileMask[row + nx] = 1;
            }
        }
    }

    private void buildHybridBaseSparseTileMask(int fullWidth,
                                               int fullHeight,
                                               int fullCount,
                                               int[] baseObjectId,
                                               float[] baseDepth) {
        ensureHybridBaseSparseTileMaskCapacity(fullWidth, fullHeight);
        if (baseObjectId == null || baseDepth == null) {
            return;
        }
        for (int y = 0; y < fullHeight; y++) {
            int row = y * fullWidth;
            int tileY = y / MOVING_HYBRID_SPARSE_TILE_SIZE;
            for (int x = 0; x < fullWidth; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= fullCount || idx >= baseObjectId.length || idx >= baseDepth.length) {
                    break;
                }
                int objectId = baseObjectId[idx];
                float depth = baseDepth[idx];
                if (objectId < 0 || depth >= 1.0f) {
                    continue;
                }
                int tileX = x / MOVING_HYBRID_SPARSE_TILE_SIZE;
                hybridBaseSparseTileMask[tileY * hybridBaseSparseTileCols + tileX] = 1;

                boolean edgePixel = false;
                if (x > 0) {
                    int leftIdx = idx - 1;
                    if (leftIdx >= 0
                            && leftIdx < fullCount
                            && leftIdx < baseObjectId.length
                            && leftIdx < baseDepth.length
                            && (baseObjectId[leftIdx] != objectId || Math.abs(baseDepth[leftIdx] - depth) > 0.01f)) {
                        edgePixel = true;
                    }
                }
                if (!edgePixel && y > 0) {
                    int upIdx = idx - fullWidth;
                    if (upIdx >= 0
                            && upIdx < fullCount
                            && upIdx < baseObjectId.length
                            && upIdx < baseDepth.length
                            && (baseObjectId[upIdx] != objectId || Math.abs(baseDepth[upIdx] - depth) > 0.01f)) {
                        edgePixel = true;
                    }
                }
                if (edgePixel) {
                    markHybridBaseSparseTileNeighborhood(tileX, tileY);
                }
            }
        }
    }

    private void copyHybridBaseGuides(int fbWidth, int fbHeight) {
        long guideStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int count = Math.min(safePixelCount(fbWidth, fbHeight), guideDepth.length);
        float[] baseDepth = hybridBaseFrameBuffer.getDepthBuffer();
        float[] baseNormal = hybridBaseFrameBuffer.getNormalBuffer();
        int[] baseColor = hybridBaseFrameBuffer.getColorBuffer();
        if (baseDepth == null || baseColor == null) {
            guidesReady = false;
            return;
        }
        for (int i = 0; i < count; i++) {
            float depth = i < baseDepth.length ? baseDepth[i] : 1.0f;
            guideDepth[i] = depth < 1.0f ? depth : Float.POSITIVE_INFINITY;
            int argb = baseColor[i];
            int base = i * 3;
            if (base + 2 < guideAlbedo.length) {
                guideAlbedo[base] = ((argb >>> 16) & 0xFF) / 255.0f;
                guideAlbedo[base + 1] = ((argb >>> 8) & 0xFF) / 255.0f;
                guideAlbedo[base + 2] = (argb & 0xFF) / 255.0f;
            }
            if (baseNormal != null && base + 2 < baseNormal.length && base + 2 < guideNormal.length) {
                guideNormal[base] = baseNormal[base];
                guideNormal[base + 1] = baseNormal[base + 1];
                guideNormal[base + 2] = baseNormal[base + 2];
            } else if (base + 2 < guideNormal.length) {
                guideNormal[base] = 0.0f;
                guideNormal[base + 1] = 1.0f;
                guideNormal[base + 2] = 0.0f;
            }
            if (i < guideRoughness.length) {
                guideRoughness[i] = 0.5f;
            }
        }
        guidesReady = true;
        if (RuntimeInstrumentation.isEnabled()) {
            long elapsed = System.nanoTime() - guideStart;
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_HYBRID_BASE_OUTPUT_NS,
                    elapsed);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_BASE_GUIDES_NS,
                    elapsed);
        }
    }

    private void writeResolvedPixel(int[] outColor, int idx) {
        if (outColor == null || idx < 0 || idx >= outColor.length || idx >= accumR.length) {
            return;
        }
        double invSamples = AdaptiveSamplingSupport.inverseSampleCount(
                AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, idx, accumulatedSamples),
                accumulatedSamples);
        outColor[idx] = packColor(
                toneMap(accumR[idx] * invSamples),
                toneMap(accumG[idx] * invSamples),
                toneMap(accumB[idx] * invSamples)
        );
    }

    private void traceCarrier(RayTraceContext ctx) {
        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;
        RayCarrierTraceMetrics metrics = ctx.carrierLeafSampleScale > 0 ? ctx.carrierMetrics : null;
        long metricScale = ctx.carrierLeafSampleScale > 0 ? ctx.carrierLeafSampleScale : 1L;

        while (true) {
            long traversalStart = metrics == null ? 0L : System.nanoTime();
            boolean hit = intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx);
            if (metrics != null) {
                metrics.primaryIntersectionNanos.add((System.nanoTime() - traversalStart) * metricScale);
            }
            if (!hit) {
                markPrimaryGuideMiss(ctx);
                if (hasVisibleEnvironment()) {
                    long environmentStart = metrics == null ? 0L : System.nanoTime();
                    sampleEnvironment(dx, dy, dz, ctx);
                    if (metrics != null) {
                        metrics.environmentNanos.add((System.nanoTime() - environmentStart) * metricScale);
                    }
                    ctx.outR = ctx.envR;
                    ctx.outG = ctx.envG;
                    ctx.outB = ctx.envB;
                } else {
                    ctx.outR = 0.0;
                    ctx.outG = 0.0;
                    ctx.outB = 0.0;
                }
                return;
            }

            RayTriangle tri = ctx.hit.triangle;
            long surfaceStart = metrics == null ? 0L : System.nanoTime();
            sampleSurface(tri, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (metrics != null) {
                metrics.surfaceSampleNanos.add((System.nanoTime() - surfaceStart) * metricScale);
            }
            if (ctx.surface.discard) {
                advancePrimaryGuide(ctx);
                ox = ctx.hit.px + dx * RAY_EPS;
                oy = ctx.hit.py + dy * RAY_EPS;
                oz = ctx.hit.pz + dz * RAY_EPS;
                continue;
            }

            capturePrimaryGuideSurface(ctx);
            evaluateSurfaceLocalRadiance(tri, dx, dy, dz, null, ctx);
            return;
        }
    }

    private void tracePolish(RayTraceContext ctx, RaySplitMix64 rng, int effectivePolishSecondaryDepth) {
        ctx.outR = 0.0;
        ctx.outG = 0.0;
        ctx.outB = 0.0;
        if (effectivePolishSecondaryDepth <= 0) {
            return;
        }

        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;

        while (true) {
            if (!intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx)) {
                return;
            }
            RayTriangle tri = ctx.hit.triangle;
            sampleSurface(tri, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (ctx.surface.discard) {
                ox = ctx.hit.px + dx * RAY_EPS;
                oy = ctx.hit.py + dy * RAY_EPS;
                oz = ctx.hit.pz + dz * RAY_EPS;
                continue;
            }
            break;
        }

        if (!prepareNextSecondaryRay(ctx, rng, dx, dy, dz, 1.0, 1.0, 1.0)) {
            return;
        }

        double throughputR = ctx.secondaryThroughputR;
        double throughputG = ctx.secondaryThroughputG;
        double throughputB = ctx.secondaryThroughputB;
        ox = ctx.secondaryOx;
        oy = ctx.secondaryOy;
        oz = ctx.secondaryOz;
        dx = ctx.secondaryDx;
        dy = ctx.secondaryDy;
        dz = ctx.secondaryDz;

        double radianceR = 0.0;
        double radianceG = 0.0;
        double radianceB = 0.0;
        double advancedOpticsWeight = resolveAdvancedOpticsWeight();
        double causticCarry = 0.0;
        int secondaryDepth = 0;

        while (secondaryDepth < effectivePolishSecondaryDepth) {
            if (!intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx)) {
                if (hasVisibleEnvironment()) {
                    sampleEnvironment(dx, dy, dz, ctx);
                    radianceR += throughputR * ctx.envR;
                    radianceG += throughputG * ctx.envG;
                    radianceB += throughputB * ctx.envB;
                }
                break;
            }

            RayTriangle tri = ctx.hit.triangle;
            sampleSurface(tri, ctx.hit, dx, dy, dz, ctx.surface, ctx);
            if (ctx.surface.discard) {
                ox = ctx.hit.px + dx * RAY_EPS;
                oy = ctx.hit.py + dy * RAY_EPS;
                oz = ctx.hit.pz + dz * RAY_EPS;
                continue;
            }

            evaluateSurfaceLocalRadiance(tri, dx, dy, dz, rng, ctx);
            radianceR += throughputR * ctx.outR;
            radianceG += throughputG * ctx.outG;
            radianceB += throughputB * ctx.outB;
            secondaryDepth++;
            if (secondaryDepth >= effectivePolishSecondaryDepth) {
                break;
            }

            if (!prepareNextSecondaryRay(ctx, rng, dx, dy, dz, throughputR, throughputG, throughputB)) {
                break;
            }
            throughputR = ctx.secondaryThroughputR;
            throughputG = ctx.secondaryThroughputG;
            throughputB = ctx.secondaryThroughputB;
            ox = ctx.secondaryOx;
            oy = ctx.secondaryOy;
            oz = ctx.secondaryOz;
            dx = ctx.secondaryDx;
            dy = ctx.secondaryDy;
            dz = ctx.secondaryDz;
        }

        ctx.outR = radianceR;
        ctx.outG = radianceG;
        ctx.outB = radianceB;
    }

    private int selectTopLocalLights(RayTraceContext ctx,
                                     double nx,
                                     double ny,
                                     double nz) {
        if (ctx == null || pointLights.length == 0) {
            return 0;
        }
        int maxLocalLights = ctx.carrierMaxLocalLights;
        if (maxLocalLights <= 0) {
            return 0;
        }
        maxLocalLights = Math.min(maxLocalLights, ctx.selectedLocalLightIndices.length);
        ctx.selectedLocalLightCandidateCount = 0;
        Arrays.fill(ctx.selectedLocalLightIndices, -1);
        Arrays.fill(ctx.selectedLocalLightImportance, Double.NEGATIVE_INFINITY);
        int selectedCount = 0;
        for (int i = 0; i < pointLights.length; i++) {
            RayPointLightCache light = pointLights[i];
            double lx = light.px - ctx.hit.px;
            double ly = light.py - ctx.hit.py;
            double lz = light.pz - ctx.hit.pz;
            double distSq = lx * lx + ly * ly + lz * lz;
            if (distSq < 1e-12) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            double invDist = 1.0 / dist;
            lx *= invDist;
            ly *= invDist;
            lz *= invDist;
            double nDotL = nx * lx + ny * ly + nz * lz;
            if (nDotL <= 0.0) {
                continue;
            }
            double angularAttenuation = 1.0;
            if (!(light.light.getClass() == PointLight.class)) {
                angularAttenuation = light.light.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                if (angularAttenuation <= 0.0) {
                    continue;
                }
            }
            double att = light.light.attenuation(dist) * angularAttenuation;
            if (att <= 0.0) {
                continue;
            }
            ctx.selectedLocalLightCandidateCount++;
            double importance = light.luminance * att * nDotL;
            int insertIndex = -1;
            for (int slot = 0; slot < maxLocalLights; slot++) {
                if (importance > ctx.selectedLocalLightImportance[slot]) {
                    insertIndex = slot;
                    break;
                }
            }
            if (insertIndex < 0) {
                continue;
            }
            for (int slot = maxLocalLights - 1; slot > insertIndex; slot--) {
                ctx.selectedLocalLightIndices[slot] = ctx.selectedLocalLightIndices[slot - 1];
                ctx.selectedLocalLightImportance[slot] = ctx.selectedLocalLightImportance[slot - 1];
                ctx.selectedLocalLightLx[slot] = ctx.selectedLocalLightLx[slot - 1];
                ctx.selectedLocalLightLy[slot] = ctx.selectedLocalLightLy[slot - 1];
                ctx.selectedLocalLightLz[slot] = ctx.selectedLocalLightLz[slot - 1];
                ctx.selectedLocalLightDist[slot] = ctx.selectedLocalLightDist[slot - 1];
                ctx.selectedLocalLightAtt[slot] = ctx.selectedLocalLightAtt[slot - 1];
                ctx.selectedLocalLightNDotL[slot] = ctx.selectedLocalLightNDotL[slot - 1];
            }
            ctx.selectedLocalLightIndices[insertIndex] = i;
            ctx.selectedLocalLightImportance[insertIndex] = importance;
            ctx.selectedLocalLightLx[insertIndex] = lx;
            ctx.selectedLocalLightLy[insertIndex] = ly;
            ctx.selectedLocalLightLz[insertIndex] = lz;
            ctx.selectedLocalLightDist[insertIndex] = dist;
            ctx.selectedLocalLightAtt[insertIndex] = att;
            ctx.selectedLocalLightNDotL[insertIndex] = nDotL;
            selectedCount = Math.min(maxLocalLights, selectedCount + 1);
        }
        return selectedCount;
    }

    private void evaluateSurfaceLocalRadiance(RayTriangle tri,
                                              double dx,
                                              double dy,
                                              double dz,
                                              RaySplitMix64 rng,
                                              RayTraceContext ctx) {
        double nx = ctx.surface.nx;
        double ny = ctx.surface.ny;
        double nz = ctx.surface.nz;
        double baseR = ctx.surface.baseR;
        double baseG = ctx.surface.baseG;
        double baseB = ctx.surface.baseB;
        double lightR = 0.0;
        double lightG = 0.0;
        double lightB = 0.0;
        RayCarrierTraceMetrics metrics = ctx.carrierLeafSampleScale > 0 ? ctx.carrierMetrics : null;
        long metricScale = ctx.carrierLeafSampleScale > 0 ? ctx.carrierLeafSampleScale : 1L;
        long directLightNanos = 0L;
        long shadowQueryNanos = 0L;
        long directionalLightNanos = 0L;
        long pointLightNanos = 0L;
        long spotLightNanos = 0L;
        long areaLightNanos = 0L;
        long extraMaterialLobesNanos = 0L;
        long localLightCandidates = 0L;
        long localLightShaded = 0L;
        long localLightShadowed = 0L;
        boolean disableSheen = ctx.carrierDisableSheen;
        boolean disableClearcoat = ctx.carrierDisableClearcoat;
        boolean disableDirectionalShadows = ctx.carrierDisableDirectionalShadows;
        boolean disablePointLightShadows = ctx.carrierDisablePointLightShadows;
        boolean pointLightsDiffuseOnly = ctx.carrierPointLightsDiffuseOnly;
        boolean allLightsDiffuseOnly = ctx.carrierDiffuseOnlyDirectLighting;
        double diffuseR = baseR * INV_PI;
        double diffuseG = baseG * INV_PI;
        double diffuseB = baseB * INV_PI;
        double surfaceSheenR = disableSheen ? 0.0 : ctx.surface.sheenR;
        double surfaceSheenG = disableSheen ? 0.0 : ctx.surface.sheenG;
        double surfaceSheenB = disableSheen ? 0.0 : ctx.surface.sheenB;

        if (directLighting) {
            long directStart = metrics == null ? 0L : System.nanoTime();
            double vx = -dx;
            double vy = -dy;
            double vz = -dz;
            double nDotV = Math.max(1e-6, nx * vx + ny * vy + nz * vz);
            double roughnessForG = clamp01(ctx.surface.roughness);
            double alpha = roughnessToAlpha(ctx.surface.roughness);
            double alphaSq = alpha * alpha;
            double f0R = clamp01(ctx.surface.specR);
            double f0G = clamp01(ctx.surface.specG);
            double f0B = clamp01(ctx.surface.specB);
            double clearcoatFactor = disableClearcoat ? 0.0 : clamp01(ctx.surface.clearcoatFactor);
            double coatRoughness = disableClearcoat ? 1.0 : clamp01(ctx.surface.clearcoatRoughness);
            double coatAlpha = roughnessToAlpha(ctx.surface.clearcoatRoughness);
            double coatAlphaSq = coatAlpha * coatAlpha;

            for (RayDirLightCache light : dirLights) {
                long lightStart = metrics == null ? 0L : System.nanoTime();
                double nDotL = nx * light.lx + ny * light.ly + nz * light.lz;
                if (nDotL <= 0.0) {
                    if (metrics != null) {
                        directionalLightNanos += System.nanoTime() - lightStart;
                    }
                    continue;
                }
                prepareShadowRay(ctx.hit, ctx.surface, light.lx, light.ly, light.lz, ctx);
                if (shadowsEnabled && !disableDirectionalShadows) {
                    long shadowStart = metrics == null ? 0L : System.nanoTime();
                    boolean occluded = intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            light.lx, light.ly, light.lz,
                            ctx.shadowTMin, INF_T, ctx);
                    if (metrics != null) {
                        shadowQueryNanos += System.nanoTime() - shadowStart;
                    }
                    if (occluded) {
                        if (metrics != null) {
                            directionalLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                }

                double hx = light.lx + vx;
                double hy = light.ly + vy;
                double hz = light.lz + vz;
                double hLenSq = hx * hx + hy * hy + hz * hz;
                double specularTerm = 0.0;
                double fresnelR = 0.0;
                double fresnelG = 0.0;
                double fresnelB = 0.0;
                double clearcoatTerm = 0.0;
                double clearcoatFresnel = 0.0;
                double sheenTerm = 0.0;
                if (hLenSq > 1e-14) {
                    long lobeStart = metrics == null ? 0L : System.nanoTime();
                    double invH = 1.0 / Math.sqrt(hLenSq);
                    hx *= invH;
                    hy *= invH;
                    hz *= invH;
                    double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                    double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                    if (!allLightsDiffuseOnly) {
                        specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG);
                        fresnelR = schlickFresnelColor(vDotH, f0R);
                        fresnelG = schlickFresnelColor(vDotH, f0G);
                        fresnelB = schlickFresnelColor(vDotH, f0B);
                        if (clearcoatFactor > 1e-6) {
                            clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                            clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                        }
                        if (!disableSheen) {
                            sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                        }
                    }
                    if (metrics != null) {
                        extraMaterialLobesNanos += System.nanoTime() - lobeStart;
                    }
                }

                lightR += light.r * nDotL * (diffuseR
                        + specularTerm * fresnelR
                        + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                        + surfaceSheenR * INV_PI * sheenTerm);
                lightG += light.g * nDotL * (diffuseG
                        + specularTerm * fresnelG
                        + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                        + surfaceSheenG * INV_PI * sheenTerm);
                lightB += light.b * nDotL * (diffuseB
                        + specularTerm * fresnelB
                        + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                        + surfaceSheenB * INV_PI * sheenTerm);
                if (metrics != null) {
                    directionalLightNanos += System.nanoTime() - lightStart;
                }
            }

            int selectedLocalLights = 0;
            if (ctx.carrierMaxLocalLights != 0) {
                if (ctx.carrierMaxLocalLights > 0 && pointLights.length > ctx.carrierMaxLocalLights) {
                    selectedLocalLights = selectTopLocalLights(ctx, nx, ny, nz);
                    localLightCandidates += ctx.selectedLocalLightCandidateCount;
                }
                int localLimit = selectedLocalLights > 0 ? selectedLocalLights : pointLights.length;
                int shadowedLocalLights = 0;
                for (int localIndex = 0; localIndex < localLimit; localIndex++) {
                    int pointLightIndex = selectedLocalLights > 0
                            ? ctx.selectedLocalLightIndices[localIndex]
                            : localIndex;
                    if (pointLightIndex < 0 || pointLightIndex >= pointLights.length) {
                        continue;
                    }
                    RayPointLightCache light = pointLights[pointLightIndex];
                    boolean isSpot = light.light instanceof ConeLight;
                    boolean isArea = light.light instanceof AreaLight;
                    long lightStart = metrics == null ? 0L : System.nanoTime();
                    double lx;
                    double ly;
                    double lz;
                    double dist;
                    double att;
                    double nDotL;
                    if (selectedLocalLights > 0) {
                        lx = ctx.selectedLocalLightLx[localIndex];
                        ly = ctx.selectedLocalLightLy[localIndex];
                        lz = ctx.selectedLocalLightLz[localIndex];
                        dist = ctx.selectedLocalLightDist[localIndex];
                        att = ctx.selectedLocalLightAtt[localIndex];
                        nDotL = ctx.selectedLocalLightNDotL[localIndex];
                    } else {
                        double rawLx = light.px - ctx.hit.px;
                        double rawLy = light.py - ctx.hit.py;
                        double rawLz = light.pz - ctx.hit.pz;
                        double distSq = rawLx * rawLx + rawLy * rawLy + rawLz * rawLz;
                        if (distSq < 1e-12) {
                            if (metrics != null) {
                                if (isArea) {
                                    areaLightNanos += System.nanoTime() - lightStart;
                                } else if (isSpot) {
                                    spotLightNanos += System.nanoTime() - lightStart;
                                } else {
                                    pointLightNanos += System.nanoTime() - lightStart;
                                }
                            }
                            continue;
                        }
                        dist = Math.sqrt(distSq);
                        double invDist = 1.0 / dist;
                        lx = rawLx * invDist;
                        ly = rawLy * invDist;
                        lz = rawLz * invDist;
                        nDotL = nx * lx + ny * ly + nz * lz;
                        if (nDotL <= 0.0) {
                            if (metrics != null) {
                                if (isArea) {
                                    areaLightNanos += System.nanoTime() - lightStart;
                                } else if (isSpot) {
                                    spotLightNanos += System.nanoTime() - lightStart;
                                } else {
                                    pointLightNanos += System.nanoTime() - lightStart;
                                }
                            }
                            continue;
                        }
                        double angularAttenuation = 1.0;
                        if (!(light.light.getClass() == PointLight.class)) {
                            angularAttenuation = light.light.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                        }
                        att = light.light.attenuation(dist) * angularAttenuation;
                        localLightCandidates++;
                        if (att <= 0.0) {
                            if (metrics != null) {
                                if (isArea) {
                                    areaLightNanos += System.nanoTime() - lightStart;
                                } else if (isSpot) {
                                    spotLightNanos += System.nanoTime() - lightStart;
                                } else {
                                    pointLightNanos += System.nanoTime() - lightStart;
                                }
                            }
                            continue;
                        }
                    }

                    boolean useShadow = !disablePointLightShadows
                            && shadowsEnabled
                            && (ctx.carrierMaxShadowedLocalLights < 0 || shadowedLocalLights < ctx.carrierMaxShadowedLocalLights);
                    if (isArea) {
                        AreaLight areaLight = (AreaLight) light.light;
                        int areaSamples = rng == null ? 1 : resolveAreaShadowSamples(areaLight, previewMotionActive);
                        double accumAreaR = 0.0;
                        double accumAreaG = 0.0;
                        double accumAreaB = 0.0;
                        for (int sampleIndex = 0; sampleIndex < areaSamples; sampleIndex++) {
                            if (rng != null) {
                                sampleAreaLightPosition(areaLight, rng, ctx.tmpVec0, ctx.tmpVec1, ctx.tmpVec2);
                            } else {
                                ctx.tmpVec0.set(light.px, light.py, light.pz);
                            }
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
                            if (useShadow) {
                                prepareShadowRay(ctx.hit, ctx.surface, sampleLx, sampleLy, sampleLz, ctx);
                                long shadowStart = metrics == null ? 0L : System.nanoTime();
                                boolean occluded = intersectAny(
                                        ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                                        sampleLx, sampleLy, sampleLz,
                                        ctx.shadowTMin, Math.max(ctx.shadowTMin, sampleDist - RAY_EPS), ctx);
                                if (metrics != null) {
                                    shadowQueryNanos += System.nanoTime() - shadowStart;
                                }
                                if (occluded) {
                                    continue;
                                }
                            }

                            double hx = sampleLx + vx;
                            double hy = sampleLy + vy;
                            double hz = sampleLz + vz;
                            double hLenSq = hx * hx + hy * hy + hz * hz;
                            double specularTerm = 0.0;
                            double fresnelR = 0.0;
                            double fresnelG = 0.0;
                            double fresnelB = 0.0;
                            double clearcoatTerm = 0.0;
                            double clearcoatFresnel = 0.0;
                            double sheenTerm = 0.0;
                            if (hLenSq > 1e-14) {
                                long lobeStart = metrics == null ? 0L : System.nanoTime();
                                double invH = 1.0 / Math.sqrt(hLenSq);
                                hx *= invH;
                                hy *= invH;
                                hz *= invH;
                                double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                                double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                                if (!allLightsDiffuseOnly && !pointLightsDiffuseOnly) {
                                    specularTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, alphaSq, roughnessForG);
                                    fresnelR = schlickFresnelColor(vDotH, f0R);
                                    fresnelG = schlickFresnelColor(vDotH, f0G);
                                    fresnelB = schlickFresnelColor(vDotH, f0B);
                                    if (clearcoatFactor > 1e-6) {
                                        clearcoatTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, coatAlphaSq, coatRoughness);
                                        clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                                    }
                                    if (!disableSheen) {
                                        sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                                    }
                                }
                                if (metrics != null) {
                                    extraMaterialLobesNanos += System.nanoTime() - lobeStart;
                                }
                            }

                            accumAreaR += light.r * sampleAtt * sampleNDotL * (diffuseR
                                    + specularTerm * fresnelR
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + surfaceSheenR * INV_PI * sheenTerm);
                            accumAreaG += light.g * sampleAtt * sampleNDotL * (diffuseG
                                    + specularTerm * fresnelG
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + surfaceSheenG * INV_PI * sheenTerm);
                            accumAreaB += light.b * sampleAtt * sampleNDotL * (diffuseB
                                    + specularTerm * fresnelB
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + surfaceSheenB * INV_PI * sheenTerm);
                        }
                        if (useShadow) {
                            shadowedLocalLights++;
                            localLightShadowed++;
                        }
                        if (areaSamples > 0) {
                            double invAreaSamples = 1.0 / areaSamples;
                            accumAreaR *= invAreaSamples;
                            accumAreaG *= invAreaSamples;
                            accumAreaB *= invAreaSamples;
                        }
                        if ((accumAreaR + accumAreaG + accumAreaB) > 1e-12) {
                            lightR += accumAreaR;
                            lightG += accumAreaG;
                            lightB += accumAreaB;
                            localLightShaded++;
                        }
                        if (metrics != null) {
                            areaLightNanos += System.nanoTime() - lightStart;
                        }
                        continue;
                    }
                    if (useShadow) {
                        prepareShadowRay(ctx.hit, ctx.surface, lx, ly, lz, ctx);
                        long shadowStart = metrics == null ? 0L : System.nanoTime();
                        boolean occluded = intersectAny(
                                ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                                lx, ly, lz,
                                ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx);
                        if (metrics != null) {
                            shadowQueryNanos += System.nanoTime() - shadowStart;
                        }
                        shadowedLocalLights++;
                        localLightShadowed++;
                        if (occluded) {
                            if (metrics != null) {
                                if (isArea) {
                                    areaLightNanos += System.nanoTime() - lightStart;
                                } else if (isSpot) {
                                    spotLightNanos += System.nanoTime() - lightStart;
                                } else {
                                    pointLightNanos += System.nanoTime() - lightStart;
                                }
                            }
                            continue;
                        }
                    }

                    double hx = lx + vx;
                    double hy = ly + vy;
                    double hz = lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double specularTerm = 0.0;
                    double fresnelR = 0.0;
                    double fresnelG = 0.0;
                    double fresnelB = 0.0;
                    double clearcoatTerm = 0.0;
                    double clearcoatFresnel = 0.0;
                    double sheenTerm = 0.0;
                    if (hLenSq > 1e-14) {
                        long lobeStart = metrics == null ? 0L : System.nanoTime();
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                        if (!allLightsDiffuseOnly && !pointLightsDiffuseOnly) {
                            specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG);
                            fresnelR = schlickFresnelColor(vDotH, f0R);
                            fresnelG = schlickFresnelColor(vDotH, f0G);
                            fresnelB = schlickFresnelColor(vDotH, f0B);
                            if (clearcoatFactor > 1e-6) {
                                clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                                clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                            }
                            if (!disableSheen) {
                                sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                            }
                        }
                        if (metrics != null) {
                            extraMaterialLobesNanos += System.nanoTime() - lobeStart;
                        }
                    }

                    lightR += light.r * att * nDotL * (diffuseR
                            + specularTerm * fresnelR
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + surfaceSheenR * INV_PI * sheenTerm);
                    lightG += light.g * att * nDotL * (diffuseG
                            + specularTerm * fresnelG
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + surfaceSheenG * INV_PI * sheenTerm);
                    lightB += light.b * att * nDotL * (diffuseB
                            + specularTerm * fresnelB
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + surfaceSheenB * INV_PI * sheenTerm);
                    localLightShaded++;
                    if (metrics != null) {
                        long lightElapsed = System.nanoTime() - lightStart;
                        if (isArea) {
                            areaLightNanos += lightElapsed;
                        } else if (isSpot) {
                            spotLightNanos += lightElapsed;
                        } else {
                            pointLightNanos += lightElapsed;
                        }
                    }
                }
            }

            if (metrics != null) {
                directLightNanos += System.nanoTime() - directStart;
            }
        }

        double ndotv = Math.max(0.0, -(dx * nx + dy * ny + dz * nz));
        double fresnel = schlickFresnel(ndotv, ctx.surface.refractiveIndex);
        double clearcoatStrength = disableClearcoat ? 0.0 : clamp01(ctx.surface.clearcoatFactor) * 0.25;
        double reflectionStrength = reflectionsEnabled
                ? clamp01(Math.max(ctx.surface.reflectivity, fresnel) + clearcoatStrength)
                : 0.0;
        double transmissionStrength = clamp01(ctx.surface.transmission * (1.0 - fresnel));
        double localWeight = clamp01((1.0 - transmissionStrength) * Math.max(0.12, 1.0 - reflectionStrength * 0.35));
        double ambientLightR = baseR * ambientR * localWeight;
        double ambientLightG = baseG * ambientG * localWeight;
        double ambientLightB = baseB * ambientB * localWeight;
        double sheenWeight = disableSheen ? 0.0 : Math.pow(1.0 - ndotv, 1.0 + tri.material.getSheenRoughness() * 5.0);

        ctx.outR = ambientLightR + lightR * localWeight + ctx.surface.emissionR + surfaceSheenR * sheenWeight * 0.25;
        ctx.outG = ambientLightG + lightG * localWeight + ctx.surface.emissionG + surfaceSheenG * sheenWeight * 0.25;
        ctx.outB = ambientLightB + lightB * localWeight + ctx.surface.emissionB + surfaceSheenB * sheenWeight * 0.25;
        if (metrics != null) {
            metrics.directLightNanos.add(directLightNanos * metricScale);
            metrics.shadowQueryNanos.add(shadowQueryNanos * metricScale);
            metrics.directionalLightNanos.add(directionalLightNanos * metricScale);
            metrics.pointLightNanos.add(pointLightNanos * metricScale);
            metrics.spotLightNanos.add(spotLightNanos * metricScale);
            metrics.areaLightNanos.add(areaLightNanos * metricScale);
            metrics.extraMaterialLobesNanos.add(extraMaterialLobesNanos * metricScale);
            metrics.localLightCandidates.add(localLightCandidates * metricScale);
            metrics.localLightShaded.add(localLightShaded * metricScale);
            metrics.localLightShadowed.add(localLightShadowed * metricScale);
        }
    }

    private boolean prepareNextSecondaryRay(RayTraceContext ctx,
                                            RaySplitMix64 rng,
                                            double dx,
                                            double dy,
                                            double dz,
                                            double throughputR,
                                            double throughputG,
                                            double throughputB) {
        double nx = ctx.surface.nx;
        double ny = ctx.surface.ny;
        double nz = ctx.surface.nz;
        double ndotv = Math.max(0.0, -(dx * nx + dy * ny + dz * nz));
        double fresnel = schlickFresnel(ndotv, ctx.surface.refractiveIndex);
        double clearcoatStrength = clamp01(ctx.surface.clearcoatFactor) * 0.25;
        double reflectionStrength = reflectionsEnabled
                ? clamp01(Math.max(ctx.surface.reflectivity, fresnel) + clearcoatStrength)
                : 0.0;
        double transmissionStrength = clamp01(ctx.surface.transmission * (1.0 - fresnel));

        if (activeCarrierDisableReflections) {
            reflectionStrength = 0.0;
        }
        if (activeCarrierDisableTransmission) {
            transmissionStrength = 0.0;
        }
        if (activeCarrierSecondaryRoughnessSkip > 0.0
                && ctx.surface.roughness >= activeCarrierSecondaryRoughnessSkip) {
            reflectionStrength = 0.0;
        }
        if (activeCarrierSecondaryThroughputTermination > 0.0) {
            double throughputLuma = DenoiseSupport.luminance(throughputR, throughputG, throughputB);
            if (throughputLuma <= activeCarrierSecondaryThroughputTermination) {
                return false;
            }
        }

        double sumStrength = reflectionStrength + transmissionStrength;
        if (sumStrength > 1.0) {
            double inv = 1.0 / sumStrength;
            reflectionStrength *= inv;
            transmissionStrength *= inv;
            sumStrength = 1.0;
        }

        boolean canTransmit = transmissionStrength > 1e-5;
        boolean canReflect = reflectionsEnabled && reflectionStrength > 1e-5;
        if (!canTransmit && !canReflect) {
            return false;
        }

        double branchScale = sumStrength;
        double transmissionProb = (sumStrength > 1e-8 && canTransmit) ? (transmissionStrength / sumStrength) : 0.0;
        boolean pickTransmission = canTransmit && rng.nextDouble() < transmissionProb;
        if (pickTransmission) {
            Vec3 refracted = ctx.tmpVec0.set(dx, dy, dz)
                    .refract(ctx.tmpVec1.set(nx, ny, nz), ctx.surface.refractiveIndex, ctx.tmpVec0);
            if (refracted.lengthSquared() > 1e-10) {
                double tint = clamp01(ctx.surface.density * Math.max(0.05, ctx.surface.thickness) + transmissionStrength * 0.25);
                ctx.secondaryThroughputR = throughputR * branchScale * mix(1.0, ctx.surface.mediumR, tint);
                ctx.secondaryThroughputG = throughputG * branchScale * mix(1.0, ctx.surface.mediumG, tint);
                ctx.secondaryThroughputB = throughputB * branchScale * mix(1.0, ctx.surface.mediumB, tint);
                if ((ctx.secondaryThroughputR + ctx.secondaryThroughputG + ctx.secondaryThroughputB) < 1e-4) {
                    return false;
                }
                ctx.secondaryOx = ctx.hit.px + refracted.x * RAY_EPS;
                ctx.secondaryOy = ctx.hit.py + refracted.y * RAY_EPS;
                ctx.secondaryOz = ctx.hit.pz + refracted.z * RAY_EPS;
                ctx.secondaryDx = refracted.x;
                ctx.secondaryDy = refracted.y;
                ctx.secondaryDz = refracted.z;
                return true;
            }

            reflectionStrength = Math.max(reflectionStrength, transmissionStrength);
            transmissionStrength = 0.0;
            sumStrength = reflectionStrength;
            branchScale = sumStrength;
            canReflect = reflectionsEnabled && reflectionStrength > 1e-5;
        }

        if (!canReflect || sumStrength <= 1e-8) {
            return false;
        }

     // Tady drzim odrazy prostredi viditelne i u dielektrik s nizkym F0.
        double reflectionCarryBoost = previewMotionActive
            ? MOTION_REFLECTION_CARRY_BOOST
            : STILL_CINEMATIC_REFLECTION_CARRY_BOOST;
        double reflectionCarryR = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specR));
        double reflectionCarryG = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specG));
        double reflectionCarryB = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specB));
        ctx.secondaryThroughputR = throughputR * branchScale * reflectionCarryR;
        ctx.secondaryThroughputG = throughputG * branchScale * reflectionCarryG;
        ctx.secondaryThroughputB = throughputB * branchScale * reflectionCarryB;
        if ((ctx.secondaryThroughputR + ctx.secondaryThroughputG + ctx.secondaryThroughputB) < 1e-4) {
            return false;
        }

        double rDotN = dx * nx + dy * ny + dz * nz;
        double nextDx = dx - 2.0 * rDotN * nx;
        double nextDy = dy - 2.0 * rDotN * ny;
        double nextDz = dz - 2.0 * rDotN * nz;
        double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
        nextDx *= invLen;
        nextDy *= invLen;
        nextDz *= invLen;
        double reflectionRoughness = clamp01(ctx.surface.roughness * (1.0 - clamp01(ctx.surface.clearcoatFactor) * 0.55));
        perturbReflectionDirection(nextDx, nextDy, nextDz, reflectionRoughness, rng, ctx.tmpVec3, ctx.tmpVec4, ctx.tmpVec5);
        ctx.secondaryDx = ctx.tmpVec3.x;
        ctx.secondaryDy = ctx.tmpVec3.y;
        ctx.secondaryDz = ctx.tmpVec3.z;
        ctx.secondaryOx = ctx.hit.px + nx * RAY_EPS;
        ctx.secondaryOy = ctx.hit.py + ny * RAY_EPS;
        ctx.secondaryOz = ctx.hit.pz + nz * RAY_EPS;
        return true;
    }

    private void traceRay(RayTraceContext ctx, RaySplitMix64 rng, int effectiveMaxDepth) {
        double ox = ctx.rayOx;
        double oy = ctx.rayOy;
        double oz = ctx.rayOz;
        double dx = ctx.rayDx;
        double dy = ctx.rayDy;
        double dz = ctx.rayDz;

        double throughputR = 1.0;
        double throughputG = 1.0;
        double throughputB = 1.0;

        double radianceR = 0.0;
        double radianceG = 0.0;
        double radianceB = 0.0;
        double advancedOpticsWeight = resolveAdvancedOpticsWeight();
        double causticCarry = 0.0;
        boolean lastSpecularEvent = false;

        for (int depth = 0; depth < effectiveMaxDepth; depth++) {
            if (!intersectClosest(ox, oy, oz, dx, dy, dz, RAY_EPS, INF_T, ctx.hit, ctx)) {
                markPrimaryGuideMiss(ctx);
                if (hasVisibleEnvironment()) {
                    sampleEnvironment(dx, dy, dz, ctx);
                    radianceR += throughputR * ctx.envR;
                    radianceG += throughputG * ctx.envG;
                    radianceB += throughputB * ctx.envB;
                }
                break;
            }

            RayTriangle tri = ctx.hit.triangle;
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
            double lightR = 0.0;
            double lightG = 0.0;
            double lightB = 0.0;

            if (directLighting) {
                double vx = -dx;
                double vy = -dy;
                double vz = -dz;
                double nDotV = Math.max(1e-6, nx * vx + ny * vy + nz * vz);
                double roughnessForG = clamp01(ctx.surface.roughness);
                double alpha = roughnessToAlpha(ctx.surface.roughness);
                double alphaSq = alpha * alpha;
                double f0R = clamp01(ctx.surface.specR);
                double f0G = clamp01(ctx.surface.specG);
                double f0B = clamp01(ctx.surface.specB);
                double clearcoatFactor = clamp01(ctx.surface.clearcoatFactor);
                double coatRoughness = clamp01(ctx.surface.clearcoatRoughness);
                double coatAlpha = roughnessToAlpha(ctx.surface.clearcoatRoughness);
                double coatAlphaSq = coatAlpha * coatAlpha;

                for (RayDirLightCache light : dirLights) {
                    double nDotL = nx * light.lx + ny * light.ly + nz * light.lz;
                    if (nDotL <= 0.0) {
                        continue;
                    }
                    prepareShadowRay(ctx.hit, ctx.surface, light.lx, light.ly, light.lz, ctx);
                    if (shadowsEnabled && intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            light.lx, light.ly, light.lz,
                            ctx.shadowTMin, INF_T, ctx)) {
                        continue;
                    }

                    double hx = light.lx + vx;
                    double hy = light.ly + vy;
                    double hz = light.lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double specularTerm = 0.0;
                    double fresnelR = 0.0;
                    double fresnelG = 0.0;
                    double fresnelB = 0.0;
                    double clearcoatTerm = 0.0;
                    double clearcoatFresnel = 0.0;
                    double sheenTerm = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                        specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG);
                        fresnelR = schlickFresnelColor(vDotH, f0R);
                        fresnelG = schlickFresnelColor(vDotH, f0G);
                        fresnelB = schlickFresnelColor(vDotH, f0B);
                        if (clearcoatFactor > 1e-6) {
                            clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                            clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                        }
                        sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                    }

                        double diffuseR = baseR * INV_PI;
                        double diffuseG = baseG * INV_PI;
                        double diffuseB = baseB * INV_PI;
                        lightR += light.r * nDotL * (diffuseR
                            + specularTerm * fresnelR
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenR * INV_PI * sheenTerm);
                        lightG += light.g * nDotL * (diffuseG
                            + specularTerm * fresnelG
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenG * INV_PI * sheenTerm);
                        lightB += light.b * nDotL * (diffuseB
                            + specularTerm * fresnelB
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenB * INV_PI * sheenTerm);
                }

                for (RayPointLightCache light : pointLights) {
                    if (light.light instanceof AreaLight) {
                        AreaLight areaLight = (AreaLight) light.light;
                        int areaSamples = resolveAreaShadowSamples(areaLight, previewMotionActive);
                        double accumAreaR = 0.0;
                        double accumAreaG = 0.0;
                        double accumAreaB = 0.0;
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

                            prepareShadowRay(ctx.hit, ctx.surface, sampleLx, sampleLy, sampleLz, ctx);
                            if (shadowsEnabled && intersectAny(
                                    ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                                    sampleLx, sampleLy, sampleLz,
                                    ctx.shadowTMin, Math.max(ctx.shadowTMin, sampleDist - RAY_EPS), ctx)) {
                                continue;
                            }

                            double sampleAtt = areaLight.attenuation(sampleDist)
 * areaLight.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                            if (sampleAtt <= 0.0) {
                                continue;
                            }

                            double hx = sampleLx + vx;
                            double hy = sampleLy + vy;
                            double hz = sampleLz + vz;
                            double hLenSq = hx * hx + hy * hy + hz * hz;
                            double specularTerm = 0.0;
                            double fresnelR = 0.0;
                            double fresnelG = 0.0;
                            double fresnelB = 0.0;
                            double clearcoatTerm = 0.0;
                            double clearcoatFresnel = 0.0;
                            double sheenTerm = 0.0;
                            if (hLenSq > 1e-14) {
                                double invH = 1.0 / Math.sqrt(hLenSq);
                                hx *= invH;
                                hy *= invH;
                                hz *= invH;
                                double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                                double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                                specularTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, alphaSq, roughnessForG);
                                fresnelR = schlickFresnelColor(vDotH, f0R);
                                fresnelG = schlickFresnelColor(vDotH, f0G);
                                fresnelB = schlickFresnelColor(vDotH, f0B);
                                if (clearcoatFactor > 1e-6) {
                                    clearcoatTerm = ggxSpecularTerm(nDotV, sampleNDotL, nDotH, coatAlphaSq, coatRoughness);
                                    clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                                }
                                sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                            }

                            double diffuseR = baseR * INV_PI;
                            double diffuseG = baseG * INV_PI;
                            double diffuseB = baseB * INV_PI;
                            accumAreaR += light.r * sampleAtt * sampleNDotL * (diffuseR
                                    + specularTerm * fresnelR
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + ctx.surface.sheenR * INV_PI * sheenTerm);
                            accumAreaG += light.g * sampleAtt * sampleNDotL * (diffuseG
                                    + specularTerm * fresnelG
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + ctx.surface.sheenG * INV_PI * sheenTerm);
                            accumAreaB += light.b * sampleAtt * sampleNDotL * (diffuseB
                                    + specularTerm * fresnelB
                                    + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                                    + ctx.surface.sheenB * INV_PI * sheenTerm);
                        }

                        if (areaSamples > 0) {
                            double invAreaSamples = 1.0 / areaSamples;
                            lightR += accumAreaR * invAreaSamples;
                            lightG += accumAreaG * invAreaSamples;
                            lightB += accumAreaB * invAreaSamples;
                        }
                        continue;
                    }

                    double lx = light.px - ctx.hit.px;
                    double ly = light.py - ctx.hit.py;
                    double lz = light.pz - ctx.hit.pz;
                    double distSq = lx * lx + ly * ly + lz * lz;
                    if (distSq < 1e-12) {
                        continue;
                    }
                    double dist = Math.sqrt(distSq);
                    double invDist = 1.0 / dist;
                    lx *= invDist;
                    ly *= invDist;
                    lz *= invDist;

                    double nDotL = nx * lx + ny * ly + nz * lz;
                    if (nDotL <= 0.0) {
                        continue;
                    }
                    prepareShadowRay(ctx.hit, ctx.surface, lx, ly, lz, ctx);
                    if (shadowsEnabled && intersectAny(
                            ctx.shadowOx, ctx.shadowOy, ctx.shadowOz,
                            lx, ly, lz,
                            ctx.shadowTMin, Math.max(ctx.shadowTMin, dist - RAY_EPS), ctx)) {
                        continue;
                    }

                    double att = light.light.attenuation(dist)
 * light.light.angularAttenuation(ctx.hit.px, ctx.hit.py, ctx.hit.pz);
                    if (att <= 0.0) {
                        continue;
                    }

                    double hx = lx + vx;
                    double hy = ly + vy;
                    double hz = lz + vz;
                    double hLenSq = hx * hx + hy * hy + hz * hz;
                    double specularTerm = 0.0;
                    double fresnelR = 0.0;
                    double fresnelG = 0.0;
                    double fresnelB = 0.0;
                    double clearcoatTerm = 0.0;
                    double clearcoatFresnel = 0.0;
                    double sheenTerm = 0.0;
                    if (hLenSq > 1e-14) {
                        double invH = 1.0 / Math.sqrt(hLenSq);
                        hx *= invH;
                        hy *= invH;
                        hz *= invH;
                        double nDotH = Math.max(0.0, nx * hx + ny * hy + nz * hz);
                        double vDotH = Math.max(0.0, vx * hx + vy * hy + vz * hz);
                        specularTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, alphaSq, roughnessForG);
                        fresnelR = schlickFresnelColor(vDotH, f0R);
                        fresnelG = schlickFresnelColor(vDotH, f0G);
                        fresnelB = schlickFresnelColor(vDotH, f0B);
                        if (clearcoatFactor > 1e-6) {
                            clearcoatTerm = ggxSpecularTerm(nDotV, nDotL, nDotH, coatAlphaSq, coatRoughness);
                            clearcoatFresnel = schlickFresnelColor(vDotH, 0.04);
                        }
                        sheenTerm = sheenLobeTerm(vDotH, ctx.surface.sheenRoughness);
                    }

                        double diffuseR = baseR * INV_PI;
                        double diffuseG = baseG * INV_PI;
                        double diffuseB = baseB * INV_PI;
                        lightR += light.r * att * nDotL * (diffuseR
                            + specularTerm * fresnelR
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenR * INV_PI * sheenTerm);
                        lightG += light.g * att * nDotL * (diffuseG
                            + specularTerm * fresnelG
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenG * INV_PI * sheenTerm);
                        lightB += light.b * att * nDotL * (diffuseB
                            + specularTerm * fresnelB
                            + clearcoatTerm * clearcoatFresnel * clearcoatFactor
                            + ctx.surface.sheenB * INV_PI * sheenTerm);
                }
            }

            double ndotv = Math.max(0.0, -(dx * nx + dy * ny + dz * nz));
            double fresnel = schlickFresnel(ndotv, ctx.surface.refractiveIndex);
            double clearcoatStrength = clamp01(ctx.surface.clearcoatFactor) * 0.25;
            double reflectionStrength = reflectionsEnabled
                ? clamp01(Math.max(ctx.surface.reflectivity, fresnel) + clearcoatStrength)
                : 0.0;
            double transmissionStrength = clamp01(ctx.surface.transmission * (1.0 - fresnel));
            double sumStrength = reflectionStrength + transmissionStrength;
            if (sumStrength > 1.0) {
                double inv = 1.0 / sumStrength;
                reflectionStrength *= inv;
                transmissionStrength *= inv;
                sumStrength = 1.0;
            }
            double localWeight = clamp01((1.0 - transmissionStrength) * Math.max(0.12, 1.0 - reflectionStrength * 0.35));
            double ambientLightR = baseR * ambientR * localWeight;
            double ambientLightG = baseG * ambientG * localWeight;
            double ambientLightB = baseB * ambientB * localWeight;
            double sheenWeight = Math.pow(1.0 - ndotv, 1.0 + tri.material.getSheenRoughness() * 5.0);

            radianceR += throughputR * (ambientLightR + lightR * localWeight + ctx.surface.emissionR + ctx.surface.sheenR * sheenWeight * 0.25);
            radianceG += throughputG * (ambientLightG + lightG * localWeight + ctx.surface.emissionG + ctx.surface.sheenG * sheenWeight * 0.25);
            radianceB += throughputB * (ambientLightB + lightB * localWeight + ctx.surface.emissionB + ctx.surface.sheenB * sheenWeight * 0.25);

            double causticPathGuide = resolveCausticPathGuidance(depth, lastSpecularEvent, ctx.surface.roughness);
            double causticBoost = resolveCausticBoost(advancedOpticsWeight, causticCarry, causticPathGuide, ctx.surface);
            if (causticBoost > 1.0) {
                radianceR += throughputR * lightR * localWeight * (causticBoost - 1.0);
                radianceG += throughputG * lightG * localWeight * (causticBoost - 1.0);
                radianceB += throughputB * lightB * localWeight * (causticBoost - 1.0);
            }

            causticCarry *= lastSpecularEvent ? 0.70 : 0.42;

            if (depth + 1 >= effectiveMaxDepth) {
                break;
            }

            boolean canTransmit = transmissionStrength > 1e-5;
            boolean canReflect = reflectionsEnabled && reflectionStrength > 1e-5;
            if (!canTransmit && !canReflect) {
                break;
            }

            double branchScale = sumStrength;
            double transmissionProb = (sumStrength > 1e-8 && canTransmit) ? (transmissionStrength / sumStrength) : 0.0;
            boolean pickTransmission = canTransmit && rng.nextDouble() < transmissionProb;
            if (pickTransmission) {
                double transmissionIor = resolveTransmissionIor(
                    ctx.surface.refractiveIndex,
                    ctx.surface.dispersion,
                    advancedOpticsWeight,
                    rng.nextDouble());
                Vec3 refracted = ctx.tmpVec0.set(dx, dy, dz)
                    .refract(ctx.tmpVec1.set(nx, ny, nz), transmissionIor, ctx.tmpVec0);
                if (refracted.lengthSquared() > 1e-10) {
                    double tint = clamp01(ctx.surface.density * Math.max(0.05, ctx.surface.thickness) + transmissionStrength * 0.25);
                    double fresnelR = 1.0 - schlickFresnel(ndotv,
                        dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, -1.0));
                    double fresnelG = 1.0 - schlickFresnel(ndotv,
                        dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, 0.0));
                    double fresnelB = 1.0 - schlickFresnel(ndotv,
                        dispersedIor(ctx.surface.refractiveIndex, ctx.surface.dispersion, advancedOpticsWeight, 1.0));
                    throughputR *= branchScale * mix(1.0, ctx.surface.mediumR, tint) * Math.max(0.05, fresnelR);
                    throughputG *= branchScale * mix(1.0, ctx.surface.mediumG, tint) * Math.max(0.05, fresnelG);
                    throughputB *= branchScale * mix(1.0, ctx.surface.mediumB, tint) * Math.max(0.05, fresnelB);
                    double transmissionCausticGuide = resolveCausticPathGuidance(depth, lastSpecularEvent, ctx.surface.roughness);
                    causticCarry = Math.max(causticCarry, resolveCausticCarry(ctx.surface, advancedOpticsWeight, transmissionCausticGuide));
                    lastSpecularEvent = true;
                    if ((throughputR + throughputG + throughputB) < 1e-4) {
                        break;
                    }
                    ox = ctx.hit.px + refracted.x * RAY_EPS;
                    oy = ctx.hit.py + refracted.y * RAY_EPS;
                    oz = ctx.hit.pz + refracted.z * RAY_EPS;
                    dx = refracted.x;
                    dy = refracted.y;
                    dz = refracted.z;
                    continue;
                }

                reflectionStrength = Math.max(reflectionStrength, transmissionStrength);
                transmissionStrength = 0.0;
                sumStrength = reflectionStrength;
                branchScale = sumStrength;
                canReflect = reflectionsEnabled && reflectionStrength > 1e-5;
            }

            if (!canReflect || sumStrength <= 1e-8) {
                break;
            }

            lastSpecularEvent = ctx.surface.roughness <= 0.22 || ctx.surface.clearcoatFactor > 0.2;

            // Tady drzim odrazy prostredi viditelne i u dielektrik s nizkym F0.
                double reflectionCarryBoost = previewMotionActive
                    ? MOTION_REFLECTION_CARRY_BOOST
                    : STILL_CINEMATIC_REFLECTION_CARRY_BOOST;
                double reflectionCarryR = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specR));
                double reflectionCarryG = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specG));
                double reflectionCarryB = Math.max(reflectionStrength * reflectionCarryBoost, Math.max(0.05, ctx.surface.specB));
            throughputR *= branchScale * reflectionCarryR;
            throughputG *= branchScale * reflectionCarryG;
            throughputB *= branchScale * reflectionCarryB;
            if ((throughputR + throughputG + throughputB) < 1e-4) {
                break;
            }

            double rDotN = dx * nx + dy * ny + dz * nz;
            double nextDx = dx - 2.0 * rDotN * nx;
            double nextDy = dy - 2.0 * rDotN * ny;
            double nextDz = dz - 2.0 * rDotN * nz;
            double invLen = 1.0 / Math.sqrt(nextDx * nextDx + nextDy * nextDy + nextDz * nextDz);
            nextDx *= invLen;
            nextDy *= invLen;
            nextDz *= invLen;
            double reflectionRoughness = clamp01(ctx.surface.roughness * (1.0 - clamp01(ctx.surface.clearcoatFactor) * 0.55));
            perturbReflectionDirection(nextDx, nextDy, nextDz, reflectionRoughness, rng, ctx.tmpVec3, ctx.tmpVec4, ctx.tmpVec5);
            nextDx = ctx.tmpVec3.x;
            nextDy = ctx.tmpVec3.y;
            nextDz = ctx.tmpVec3.z;

            ox = ctx.hit.px + nx * RAY_EPS;
            oy = ctx.hit.py + ny * RAY_EPS;
            oz = ctx.hit.pz + nz * RAY_EPS;
            dx = nextDx;
            dy = nextDy;
            dz = nextDz;
        }

        markPrimaryGuideMiss(ctx);

        ctx.outR = radianceR;
        ctx.outG = radianceG;
        ctx.outB = radianceB;
    }

    private void sampleSurface(RayTriangle tri, RayHit RayHit, double dx, double dy, double dz, RaySurfaceState out, RayTraceContext ctx) {
        if (tri == null || RayHit == null || out == null) {
            return;
        }
        double w0 = 1.0 - RayHit.u - RayHit.v;
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
            nx = tri.n0x * w0 + tri.n1x * RayHit.u + tri.n2x * RayHit.v;
            ny = tri.n0y * w0 + tri.n1y * RayHit.u + tri.n2y * RayHit.v;
            nz = tri.n0z * w0 + tri.n1z * RayHit.u + tri.n2z * RayHit.v;
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

        PhongMaterial material = tri.material;
        if (material == null) {
            out.discard = false;
            out.smoothNx = nx;
            out.smoothNy = ny;
            out.smoothNz = nz;
            out.nx = nx;
            out.ny = ny;
            out.nz = nz;
            out.geomNx = geomNx;
            out.geomNy = geomNy;
            out.geomNz = geomNz;
            out.baseR = tri.baseR;
            out.baseG = tri.baseG;
            out.baseB = tri.baseB;
            out.opacity = 1.0;
            out.roughness = clamp01(tri.roughness);
            out.specR = clamp01(tri.specR);
            out.specG = clamp01(tri.specG);
            out.specB = clamp01(tri.specB);
            out.reflectivity = clamp01(tri.reflectivity);
            out.transmission = 0.0;
            out.refractiveIndex = 1.0;
            out.dispersion = 0.0;
            out.emissionR = 0.0;
            out.emissionG = 0.0;
            out.emissionB = 0.0;
            out.mediumR = 1.0;
            out.mediumG = 1.0;
            out.mediumB = 1.0;
            out.density = 0.0;
            out.thickness = 1.0;
            out.sheenR = 0.0;
            out.sheenG = 0.0;
            out.sheenB = 0.0;
            out.sheenRoughness = 0.0;
            out.clearcoatFactor = 0.0;
            out.clearcoatRoughness = 0.0;
            return;
        }
        double uv0U = tri.hasUV ? tri.u0 * w0 + tri.u1 * RayHit.u + tri.u2 * RayHit.v : 0.0;
        double uv0V = tri.hasUV ? tri.v0 * w0 + tri.v1 * RayHit.u + tri.v2 * RayHit.v : 0.0;
        double uv1U = tri.hasUV2 ? tri.u0b * w0 + tri.u1b * RayHit.u + tri.u2b * RayHit.v : 0.0;
        double uv1V = tri.hasUV2 ? tri.v0b * w0 + tri.v1b * RayHit.u + tri.v2b * RayHit.v : 0.0;
        MaterialGraphEvaluator.Result graph = material != null && material.hasNodeGraph()
                ? MaterialGraphEvaluator.evaluateTriangleShared(
                material,
                RayHit.px,
                RayHit.py,
                RayHit.pz,
                tri.hasUV,
                uv0U,
                uv0V,
                tri.hasUV2,
                uv1U,
                uv1V
        ) : null;
        boolean graphApplied = graph != null && graph.graphApplied;
        out.discard = false;
        out.smoothNx = nx;
        out.smoothNy = ny;
        out.smoothNz = nz;
        out.nx = nx;
        out.ny = ny;
        out.nz = nz;
        out.geomNx = geomNx;
        out.geomNy = geomNy;
        out.geomNz = geomNz;
        out.baseR = graphApplied ? graph.baseColor.x : tri.baseR;
        out.baseG = graphApplied ? graph.baseColor.y : tri.baseG;
        out.baseB = graphApplied ? graph.baseColor.z : tri.baseB;
        if (tri.floorGrid) {
            double cell = 2.0;
            int gx = (int) Math.floor(RayHit.px / cell);
            int gz = (int) Math.floor(RayHit.pz / cell);
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

        out.opacity = clamp01(graphApplied ? graph.opacity : material.getOpacity());
        if (!graphApplied) {
            TextureMap diffuseMap = material.getDiffuseMap();
            if (canSampleTextureMap(diffuseMap, tri)) {
                int baseTexel = sampleTextureMap(diffuseMap, tri, w0, RayHit.u, RayHit.v);
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

        double roughness = clamp01(graphApplied ? graph.roughness : material.getRoughness());
        double metallic = clamp01(graphApplied ? graph.metallic : material.getMetallic());
        if (!graphApplied) {
            TextureMap metallicRoughnessMap = material.getMetallicRoughnessMap();
            if (canSampleTextureMap(metallicRoughnessMap, tri)) {
                int mrTexel = sampleTextureMap(metallicRoughnessMap, tri, w0, RayHit.u, RayHit.v);
                roughness *= ((mrTexel >> 8) & 0xFF) / 255.0;
                metallic *= (mrTexel & 0xFF) / 255.0;
            }
        }
        out.roughness = clamp01(roughness);
        double clearcoatFactor = graphApplied ? graph.clearcoatFactor : material.getClearcoatFactor();
        double clearcoatRoughness = graphApplied ? graph.clearcoatRoughness : material.getClearcoatRoughness();
        double specular = graphApplied ? graph.specularFactor : material.getSpecularFactor();
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
        double transmission = graphApplied ? graph.transmission : material.getTransmission();
        out.transmission = clamp01(Math.max(transmission,
                material.getAlphaMode() == PhongMaterial.AlphaMode.BLEND ? 1.0 - out.opacity : transmission));
        applyMaterialProfileToSurface(out);
        out.refractiveIndex = Math.max(1.0, graphApplied ? graph.refractiveIndex : material.getRefractiveIndex());
        out.dispersion = clamp01(graphApplied ? graph.dispersion : material.getDispersion());
        Vec3 emissionColor = graphApplied ? graph.emissionColor : material.getEmissionColor();
        double emissionStrength = graphApplied ? graph.emissionStrength : material.getEmissionStrength();
        out.emissionR = emissionColor.x * emissionStrength;
        out.emissionG = emissionColor.y * emissionStrength;
        out.emissionB = emissionColor.z * emissionStrength;
        if (!graphApplied) {
            TextureMap emissiveMap = material.getEmissiveMap();
            if (canSampleTextureMap(emissiveMap, tri)) {
                int emissiveTexel = sampleTextureMap(emissiveMap, tri, w0, RayHit.u, RayHit.v);
                out.emissionR *= ((emissiveTexel >> 16) & 0xFF) / 255.0;
                out.emissionG *= ((emissiveTexel >> 8) & 0xFF) / 255.0;
                out.emissionB *= (emissiveTexel & 0xFF) / 255.0;
            }
        }
        Vec3 mediumColor = graphApplied ? graph.mediumColor : material.getMediumColor();
        out.mediumR = mediumColor.x;
        out.mediumG = mediumColor.y;
        out.mediumB = mediumColor.z;
        out.density = graphApplied ? graph.density : material.getDensity();
        out.thickness = Math.max(0.02, graphApplied ? graph.thickness : material.getThickness());
        Vec3 sheenColor = graphApplied ? graph.sheenColor : material.getSheenColor();
        out.sheenR = sheenColor.x;
        out.sheenG = sheenColor.y;
        out.sheenB = sheenColor.z;
        out.sheenRoughness = graphApplied ? graph.sheenRoughness : material.getSheenRoughness();
        out.clearcoatFactor = clearcoatFactor;
        out.clearcoatRoughness = clearcoatRoughness;

        Vec3 mappedNormal = applyNormalMap(material, tri, w0, RayHit.u, RayHit.v, out.nx, out.ny, out.nz, ctx);
        out.nx = mappedNormal.x;
        out.ny = mappedNormal.y;
        out.nz = mappedNormal.z;
    }

    private void applyMaterialProfileToSurface(RaySurfaceState out) {
        if (out == null || materialProfile == null || materialProfile.isEmpty()) {
            return;
        }
        switch (materialProfile) {
            case "RT" -> {
                // Tady nechavam RT vzhled lehce kontrastni, ale transmisivni materialy drzim jako ciste sklo.
                out.roughness = clamp01(out.roughness * 0.90);
                out.reflectivity = clamp01(out.reflectivity * 1.02);
                out.transmission = clamp01(out.transmission * 1.18);
                if (out.transmission > 0.05) {
                    out.roughness = clamp01(Math.max(0.01, out.roughness * 0.42));
                    out.reflectivity = clamp01(out.reflectivity * 0.80);
                    out.transmission = clamp01(out.transmission * 1.10);
                }
            }
            case "PT" -> {
                out.roughness = clamp01(out.roughness * 1.04);
                out.reflectivity = clamp01(out.reflectivity * 0.97);
                out.transmission = clamp01(out.transmission * 1.08);
            }
            case "DITHER" -> {
                out.roughness = clamp01(out.roughness * 1.16 + 0.04);
                out.reflectivity = clamp01(out.reflectivity * 0.80);
                out.transmission = clamp01(out.transmission * 0.58);
            }
            default -> {
                // Tady u PHONG/AUTO/default nechavam extrahovane hodnoty beze zmeny.
            }
        }
    }

    private void computeShadowTerminatorPoint(RayHit RayHit,
                                              RayTriangle tri,
                                              RaySurfaceState surface,
                                              RayTraceContext ctx) {
        if (RayHit == null || surface == null || ctx == null || tri == null) {
            ctx.shadowBaseX = RayHit != null ? RayHit.px : 0.0;
            ctx.shadowBaseY = RayHit != null ? RayHit.py : 0.0;
            ctx.shadowBaseZ = RayHit != null ? RayHit.pz : 0.0;
            return;
        }
        if (tri.flatNormal) {
            ctx.shadowBaseX = RayHit.px;
            ctx.shadowBaseY = RayHit.py;
            ctx.shadowBaseZ = RayHit.pz;
            return;
        }

        double w0 = 1.0 - RayHit.u - RayHit.v;

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

        double t0x = RayHit.px - tri.ax;
        double t0y = RayHit.py - tri.ay;
        double t0z = RayHit.pz - tri.az;

        double t1x = RayHit.px - tri.bx;
        double t1y = RayHit.py - tri.by;
        double t1z = RayHit.pz - tri.bz;

        double t2x = RayHit.px - tri.cx;
        double t2y = RayHit.py - tri.cy;
        double t2z = RayHit.pz - tri.cz;

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

        double shadowBaseX = RayHit.px + w0 * q0x + RayHit.u * q1x + RayHit.v * q2x;
        double shadowBaseY = RayHit.py + w0 * q0y + RayHit.u * q1y + RayHit.v * q2y;
        double shadowBaseZ = RayHit.pz + w0 * q0z + RayHit.u * q1z + RayHit.v * q2z;
        if (!Double.isFinite(shadowBaseX)
                || !Double.isFinite(shadowBaseY)
                || !Double.isFinite(shadowBaseZ)) {
            ctx.shadowBaseX = RayHit.px;
            ctx.shadowBaseY = RayHit.py;
            ctx.shadowBaseZ = RayHit.pz;
            return;
        }

        ctx.shadowBaseX = shadowBaseX;
        ctx.shadowBaseY = shadowBaseY;
        ctx.shadowBaseZ = shadowBaseZ;
    }

    private void prepareShadowRay(RayHit RayHit, RaySurfaceState surface, double lx, double ly, double lz, RayTraceContext ctx) {
        RayTriangle tri = RayHit.triangle;

        computeShadowTerminatorPoint(RayHit, tri, surface, ctx);

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
                                RayTriangle tri,
                                double w0,
                                double u,
                                double v,
                                double nx,
                                double ny,
                                double nz,
                                RayTraceContext ctx) {
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

    private boolean canSampleTextureMap(TextureMap map, RayTriangle tri) {
        if (map == null || !map.hasTexture() || tri == null) {
            return false;
        }
        return map.getTexCoord() > 0 ? tri.hasUV2 : tri.hasUV;
    }

    private int sampleTextureMap(TextureMap map, RayTriangle tri, double w0, double u, double v) {
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

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private double resolveAdvancedOpticsWeight() {
        if (previewMotionActive) {
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
                                                     boolean previousSpecularEvent,
                                                     double roughness) {
        if (bounceDepth <= 0) {
            return 0.0;
        }
        double depthGate = clamp01((bounceDepth + 0.2) / 2.4);
        double eventGuide = previousSpecularEvent ? 1.0 : 0.28;
        double roughnessGuide = 1.0 - clamp01(roughness * 0.95);
        return clamp01((0.35 + 0.65 * depthGate) * (0.60 * eventGuide + 0.40 * roughnessGuide));
    }

    private static double resolveCausticCarry(RaySurfaceState surface, double opticsWeight, double pathGuide) {
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
                                              RaySurfaceState surface) {
        if (surface == null || opticsWeight <= 0.0 || causticCarry <= 1e-6) {
            return 1.0;
        }
        double receiverFactor = 1.0 - clamp01(surface.roughness * 0.82);
        double guidedCarry = Math.pow(clamp01(causticCarry), 0.76) * (0.45 + 0.55 * clamp01(pathGuide));
        double boost = 1.0 + CAUSTIC_BOOST_MAX * guidedCarry * receiverFactor * clamp01(opticsWeight);
        return Math.max(1.0, boost);
    }

    private static double mix(double a, double b, double t) {
        return a + (b - a) * clamp01(t);
    }

    private static int resolveAreaShadowSamples(AreaLight light, boolean motionActive) {
        int samples = Math.max(1, light.getShadowSamples());
        if (!motionActive) {
            samples = Math.max(STILL_CINEMATIC_AREA_SAMPLES_MIN, samples * 3);
            samples = Math.min(STILL_CINEMATIC_AREA_SAMPLES_MAX, samples);
        }
        if (motionActive && samples > 1) {
            samples = Math.max(1, (int) Math.round(samples * 0.65));
        }
        return samples;
    }

    private static void perturbReflectionDirection(double perfectDx,
                                                   double perfectDy,
                                                   double perfectDz,
                                                   double roughness,
                                                   RaySplitMix64 rng,
                                                   Vec3 outDir,
                                                   Vec3 tangentOut,
                                                   Vec3 bitangentOut) {
        if (rng == null || roughness <= 0.02) {
            outDir.set(perfectDx, perfectDy, perfectDz);
            return;
        }

        double nx = perfectDx;
        double ny = perfectDy;
        double nz = perfectDz;
        double nLenSq = nx * nx + ny * ny + nz * nz;
        if (nLenSq < 1e-12) {
            outDir.set(0.0, 1.0, 0.0);
            return;
        }
        double invN = 1.0 / Math.sqrt(nLenSq);
        nx *= invN;
        ny *= invN;
        nz *= invN;

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

        double rough = clamp01(roughness);
        double exponent = mix(220.0, 6.0, Math.pow(rough, 0.85));
        double u1 = rng.nextDouble();
        double u2 = rng.nextDouble();
        double cosTheta = Math.pow(Math.max(1e-9, u1), 1.0 / (exponent + 1.0));
        double sinTheta = Math.sqrt(Math.max(0.0, 1.0 - cosTheta * cosTheta));
        double phi = 2.0 * Math.PI * u2;
        double sx = Math.cos(phi) * sinTheta;
        double sy = Math.sin(phi) * sinTheta;

        double dx = tx * sx + bx * sy + nx * cosTheta;
        double dy = ty * sx + by * sy + ny * cosTheta;
        double dz = tz * sx + bz * sy + nz * cosTheta;
        double dLenSq = dx * dx + dy * dy + dz * dz;
        if (dLenSq < 1e-12) {
            outDir.set(nx, ny, nz);
            return;
        }
        double invD = 1.0 / Math.sqrt(dLenSq);
        outDir.set(dx * invD, dy * invD, dz * invD);
    }

    private static void sampleAreaLightPosition(AreaLight light, RaySplitMix64 rng, Vec3 outPos, Vec3 tangentOut, Vec3 bitangentOut) {
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

    private boolean intersectClosest(double ox, double oy, double oz,
                                     double dx, double dy, double dz,
                                     double tMin, double tMax,
                                     RayHit outHit, RayTraceContext ctx) {
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
            RayBVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, closest)) {
                continue;
            }

            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    RayTriangle tri = triangles[triangleOrder[i]];
                    if (intersectTriangle(tri, ox, oy, oz, dx, dy, dz, tMin, closest, ctx.tempHit)) {
                        hitAnything = true;
                        closest = ctx.tempHit.t;
                        outHit.copyFrom(ctx.tempHit);
                    }
                }
                continue;
            }

            RayBVHNode left = node.left;
            RayBVHNode right = node.right;
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
                                 RayTraceContext ctx) {
        if (bvhRoot == null) {
            return false;
        }

        double invDx = Math.abs(dx) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dx;
        double invDy = Math.abs(dy) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dy;
        double invDz = Math.abs(dz) < 1e-14 ? Double.POSITIVE_INFINITY : 1.0 / dz;

        int sp = 0;
        ctx.nodeStack[sp++] = bvhRoot;

        while (sp > 0) {
            RayBVHNode node = ctx.nodeStack[--sp];
            if (!intersectsAabb(node, ox, oy, oz, invDx, invDy, invDz, tMin, tMax)) {
                continue;
            }
            if (node.left == null) {
                for (int i = node.start; i < node.end; i++) {
                    RayTriangle tri = triangles[triangleOrder[i]];
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

    private boolean shadowOccludes(RayTriangle tri, RayHit RayHit, double dx, double dy, double dz, RayTraceContext ctx) {
        sampleSurface(tri, RayHit, dx, dy, dz, ctx.shadowSurface, ctx);
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

    private boolean intersectTriangle(RayTriangle tri,
                                      double ox, double oy, double oz,
                                      double dx, double dy, double dz,
                                      double tMin, double tMax,
                                      RayHit out) {
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

    private boolean intersectsAabb(RayBVHNode node,
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

    private double aabbEntry(RayBVHNode node,
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

    private void generatePrimaryRay(RayCameraState camera, int px, int py, RaySplitMix64 rng, RayTraceContext ctx) {
        generatePrimaryRay(camera, px, py, rng.nextDouble(), rng.nextDouble(), ctx);
    }

    private void generatePrimaryRay(RayCameraState camera, int px, int py, double sampleX, double sampleY, RayTraceContext ctx) {
        resetPrimaryGuideState(ctx);
        double ndcX = ((px + sampleX) / camera.width) * 2.0 - 1.0;
        double ndcY = 1.0 - ((py + sampleY) / camera.height) * 2.0;

        if (camera.perspective) {
            double sx = ndcX * camera.aspect * camera.tanHalfFov;
            double sy = ndcY * camera.tanHalfFov;
            double dx = camera.fx + camera.rx * sx + camera.ux * sy;
            double dy = camera.fy + camera.ry * sx + camera.uy * sy;
            double dz = camera.fz + camera.rz * sx + camera.uz * sy;
            double invLen = 1.0 / Math.sqrt(dx * dx + dy * dy + dz * dz);

            ctx.rayOx = camera.px;
            ctx.rayOy = camera.py;
            ctx.rayOz = camera.pz;
            ctx.rayDx = dx * invLen;
            ctx.rayDy = dy * invLen;
            ctx.rayDz = dz * invLen;
            return;
        }

        double camX = (ndcX - camera.orthoM03) / camera.orthoM00;
        double camY = (ndcY - camera.orthoM13) / camera.orthoM11;
        ctx.rayOx = camera.px + camera.rx * camX + camera.ux * camY + camera.fx * camera.near;
        ctx.rayOy = camera.py + camera.ry * camX + camera.uy * camY + camera.fy * camera.near;
        ctx.rayOz = camera.pz + camera.rz * camX + camera.uz * camY + camera.fz * camera.near;
        ctx.rayDx = camera.fx;
        ctx.rayDy = camera.fy;
        ctx.rayDz = camera.fz;
    }

    private void beginPrimaryGuideCapture(RayTraceContext ctx) {
        resetPrimaryGuideState(ctx);
        ctx.primaryGuidePending = true;
    }

    private void resetPrimaryGuideState(RayTraceContext ctx) {
        ctx.primaryGuidePending = false;
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
    }

    private void advancePrimaryGuide(RayTraceContext ctx) {
        if (ctx.primaryGuidePending && !ctx.primaryGuideCaptured) {
            ctx.primaryGuideTravel += ctx.hit.t;
        }
    }

    private void capturePrimaryGuideSurface(RayTraceContext ctx) {
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
    }

    private void markPrimaryGuideMiss(RayTraceContext ctx) {
        if (ctx.primaryGuidePending && !ctx.primaryGuideCaptured) {
            ctx.primaryGuideCaptured = true;
            ctx.primaryGuideHasHit = false;
        }
    }

    private void storePrimaryGuide(int idx, RayTraceContext ctx) {
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

    private RayCameraState buildCameraState(Camera camera, int width, int height) {
        RayCameraState s = new RayCameraState();
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

        if (camera instanceof PerspectiveCamera) {
            PerspectiveCamera pc = (PerspectiveCamera) camera;
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
        List<RayTriangle> built = new ArrayList<>();
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
            Texture texture = material.getDiffuseTexture();
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
            double specR = material.getSpecularColor().x;
            double specG = material.getSpecularColor().y;
            double specB = material.getSpecularColor().z;
            double reflectivity = Math.max(0.0, Math.min(0.98, material.getReflectivity()));
            double roughness = Math.sqrt(2.0 / (Math.max(1.0, material.getShininess()) + 2.0));
            boolean floorGrid = "floor-grid".equals(material.getName());
            boolean textureLinear = material.isTextureFilteringLinear();

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

                built.add(new RayTriangle(
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
                        specR, specG, specB,
                        reflectivity, roughness,
                        textureLinear, floorGrid,
                        material
                ));
            }
        }

        triangles = built.toArray(new RayTriangle[0]);
        triangleCount = triangles.length;
        centroidX = new double[triangleCount];
        centroidY = new double[triangleCount];
        centroidZ = new double[triangleCount];
        for (int i = 0; i < triangleCount; i++) {
            centroidX[i] = triangles[i].centroidX;
            centroidY[i] = triangles[i].centroidY;
            centroidZ[i] = triangles[i].centroidZ;
        }
        rebuildBvh();
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

    private RayBVHNode buildNode(int start, int end) {
        RayBVHNode node = new RayBVHNode();
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
            RayTriangle tri = triangles[triangleOrder[i]];
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
                    RayTriangle tri = triangles[triIndex];
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
        environmentMapKey = scene.getEnvironmentMapKey();
        environmentMap = scene.getEnvironmentMap();

        List<RayDirLightCache> dir = new ArrayList<>();
        List<RayPointLightCache> points = new ArrayList<>();
        for (Light light : scene.getLights()) {
            if (light == null || !light.isEnabled()) {
                continue;
            }
            double lr = light.getColor().x * light.getIntensity();
            double lg = light.getColor().y * light.getIntensity();
            double lb = light.getColor().z * light.getIntensity();

            if (light instanceof DirectionalLight) {
                Vec3 d = ((DirectionalLight) light).getDirection();
                double lx = -d.x;
                double ly = -d.y;
                double lz = -d.z;
                double lenSq = lx * lx + ly * ly + lz * lz;
                if (lenSq < 1e-14) {
                    continue;
                }
                double invLen = 1.0 / Math.sqrt(lenSq);
                dir.add(new RayDirLightCache(lx * invLen, ly * invLen, lz * invLen, lr, lg, lb));
            } else if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                Vec3 p = pl.getPosition();
                points.add(new RayPointLightCache(pl, p.x, p.y, p.z, lr, lg, lb));
            }
        }
        dirLights = dir.toArray(new RayDirLightCache[0]);
        pointLights = points.toArray(new RayPointLightCache[0]);
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
            if (light instanceof DirectionalLight) {
                Vec3 d = ((DirectionalLight) light).getDirection();
                h = mixHash(h, Double.doubleToLongBits(d.x));
                h = mixHash(h, Double.doubleToLongBits(d.y));
                h = mixHash(h, Double.doubleToLongBits(d.z));
            } else if (light instanceof PointLight) {
                PointLight pl = (PointLight) light;
                Vec3 p = pl.getPosition();
                h = mixHash(h, Double.doubleToLongBits(p.x));
                h = mixHash(h, Double.doubleToLongBits(p.y));
                h = mixHash(h, Double.doubleToLongBits(p.z));
                h = mixHash(h, Double.doubleToLongBits(pl.getConstant()));
                h = mixHash(h, Double.doubleToLongBits(pl.getLinear()));
                h = mixHash(h, Double.doubleToLongBits(pl.getQuadratic()));
                if (pl instanceof ConeLight) {
                    ConeLight cl = (ConeLight) pl;
                    Vec3 d = cl.getDirection();
                    h = mixHash(h, Double.doubleToLongBits(d.x));
                    h = mixHash(h, Double.doubleToLongBits(d.y));
                    h = mixHash(h, Double.doubleToLongBits(d.z));
                    h = mixHash(h, Double.doubleToLongBits(cl.getConeAngleDegrees()));
                    h = mixHash(h, Double.doubleToLongBits(cl.getSoftness()));
                } else if (pl instanceof AreaLight) {
                    AreaLight al = (AreaLight) pl;
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
                if (previewQualityLadderEnabled && previewPhase == PreviewPhase.MOTION_STEADY) {
                    softResetMotionCarrierPreserveAccumulation();
                    invalidateTemporalHistory();
                } else {
                    softResetAccumulationInvalidateHistory();
                }
                nextTemporalBlendScale = 1.0;
                nextTemporalBlendFrames = 0;
                return;
            }
            if (shouldUseDeferredMovingHybridReset()) {
                softResetMovingHybridAccumulation();
            } else {
                softResetAccumulationPreserveHistory();
            }
            if (temporalHistoryValid) {
                nextTemporalBlendScale = previewMotionActive ? 0.14 : 0.28;
                nextTemporalBlendFrames = 1;
            }
            return;
        }
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.CAMERA_RESETS_HARD, 1L);
        nextTemporalBlendScale = 1.0;
        nextTemporalBlendFrames = 0;
        resetAccumulation();
    }

    private void updatePreviewPhaseState() {
        if (!previewQualityLadderEnabled) {
            previewPhase = PreviewPhase.STILL_STEADY;
            previewPhaseFrameSequence = 0;
            return;
        }
        if (previewMotionActive) {
            if (!isMotionPhase(previewPhase)) {
                resetStillReferenceHandoff();
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
        if (!previewQualityLadderEnabled) {
            return true;
        }
     // Tady v pohybu drzim full-frame update, aby se nerozjely tiles
     // a nevznikal black flicker/ghosting v hybrid base + polish kompozici.
        return true;
    }

    private boolean[] resolveCarrierTileRenderPlan(int tileCount, int tileCols, int tileRows, boolean fullFrameCoverage) {
        ensureCarrierTileRenderPlanMask(tileCount);
        boolean[] plan = carrierTileRenderPlanMask;
        if (tileCount <= 0) {
            return plan;
        }
        Arrays.fill(plan, 0, tileCount, false);
        if (fullFrameCoverage || !previewQualityLadderEnabled || !previewMotionActive) {
            Arrays.fill(plan, 0, tileCount, true);
            ensureCarrierMotionTileLayout(tileCount, tileCols, tileRows);
            return plan;
        }

        ensureCarrierMotionTileLayout(tileCount, tileCols, tileRows);
        int cadence = Math.max(1, previewMotionTileSubsetCadence);
        int tilesPerFrame = Math.max(1, (tileCount + cadence - 1) / cadence);
        int primaryStart = Math.floorMod(carrierMotionTileCursor, tileCount);
        int selected = 0;

        for (int i = 0; i < tilesPerFrame; i++) {
            int tile = (primaryStart + i) % tileCount;
            if (!plan[tile]) {
                plan[tile] = true;
                selected++;
            }
        }
        if (selected == 0) {
            plan[primaryStart] = true;
        }
        carrierMotionTileCursor = (primaryStart + tilesPerFrame) % tileCount;
        return plan;
    }

    private void ensureCarrierTileRenderPlanMask(int count) {
        int required = Math.max(1, count);
        if (carrierTileRenderPlanMask.length != required) {
            carrierTileRenderPlanMask = new boolean[required];
        }
    }

    private void ensureCarrierMotionTileLayout(int tileCount, int tileCols, int tileRows) {
        if (tileCount <= 0) {
            carrierMotionTileLayoutCols = tileCols;
            carrierMotionTileLayoutRows = tileRows;
            carrierMotionTileCursor = 0;
            return;
        }
        if (carrierMotionTileLayoutCols != tileCols || carrierMotionTileLayoutRows != tileRows) {
            carrierMotionTileLayoutCols = tileCols;
            carrierMotionTileLayoutRows = tileRows;
            carrierMotionTileCursor = 0;
        }
    }

    private boolean shouldUseDeferredMovingHybridReset() {
        return previewQualityLadderEnabled && previewMotionActive;
    }

    private void beginFramePolishReuseState() {
        currentCameraMotionDelta = PreviewCameraResetSupport.MotionDelta.NONE;
        currentCameraResetKind = null;
        framePolishIntegrandChanged = false;
        framePolishScaleChanged = false;
        framePolishTierChanged = false;
        framePolishRebuildChanged = false;
    }

    private int resolveEffectiveSamplesPerFrame() {
        int effective = samplesPerFrame;
        if (previewQualityLadderEnabled && previewMotionActive && previewMotionSamplesPerFrameLimit > 0) {
            effective = Math.min(effective, previewMotionSamplesPerFrameLimit);
        }
        return Math.max(1, effective);
    }

    private PreviewQualityTierPlan resolvePreviewQualityTier(long completedSamples) {
        int fullPolishDepth = Math.max(0, maxDepth - 1);
        if (!previewQualityLadderEnabled) {
            return new PreviewQualityTierPlan("UNRESTRICTED_RT", fullPolishDepth, 1, fullPolishDepth > 0 ? 1 : 0,
                1.0, 0, 0, -1, -1, false, false, false, false, false, false, false,
                false, false, 0.0, 0.0);
        }
        if (previewMotionActive) {
            int motionDepthLimit = previewMotionDepthLimit > 0
                    ? Math.max(1, Math.min(maxDepth, previewMotionDepthLimit))
                    : maxDepth;
            int motionPolishDepth = 0;
            double normalizedMotionScale = normalizePolishScale(previewMotionPolishScale);
                boolean dominantContributionOnly = previewMotionDominantContributionOnly
                    || previewMotionSecondaryCadence >= 6
                    || previewMotionTileSubsetCadence >= 6;
            boolean ultraEmergency = dominantContributionOnly
                || normalizedMotionScale <= 0.12
                || previewMotionSecondaryCadence >= 8
                || previewMotionDenoiseCadence >= 6
                || motionDepthLimit <= 1;
            boolean emergency = !ultraEmergency && (normalizedMotionScale <= 0.26
                    || previewMotionSecondaryCadence >= 4
                || previewMotionDenoiseCadence >= 3
                || motionDepthLimit <= 2);
            int carrierMaxLights = ultraEmergency ? 0 : (emergency ? 1 : 3);
            int carrierMaxShadowedLights = ultraEmergency ? 0 : (emergency ? 1 : 2);
            if (previewMotionMaxLocalLights >= 0) {
                carrierMaxLights = Math.min(carrierMaxLights, previewMotionMaxLocalLights);
            }
            if (previewMotionMaxShadowedLocalLights >= 0) {
                carrierMaxShadowedLights = Math.min(carrierMaxShadowedLights, previewMotionMaxShadowedLocalLights);
            }
            boolean disableReflections = dominantContributionOnly || motionDepthLimit <= 1 || ultraEmergency;
            boolean disableTransmission = dominantContributionOnly || motionDepthLimit <= 1 || ultraEmergency;
            double roughnessSkip = Math.max(
                    ultraEmergency ? 0.45 : (emergency ? 0.32 : 0.18),
                    previewMotionRoughnessSecondarySkip);
            double throughputTermination = Math.max(
                    ultraEmergency ? 0.12 : (emergency ? 0.08 : 0.04),
                    previewMotionThroughputTermination);
            return new PreviewQualityTierPlan(
                ultraEmergency ? "MOVING_CARRIER_ULTRA_REDUCED"
                    : (emergency ? "MOVING_CARRIER_EMERGENCY" : "MOVING_CARRIER_BALANCED"),
                motionPolishDepth,
                ultraEmergency ? Math.max(4, previewMotionSecondaryCadence) : (emergency ? Math.max(2, previewMotionSecondaryCadence) : Math.max(1, previewMotionSecondaryCadence)),
                0,
                1.0,
                ultraEmergency ? 1 : (emergency ? 1 : 2),
                ultraEmergency ? 1 : (emergency ? 1 : 2),
                carrierMaxLights,
                carrierMaxShadowedLights,
                dominantContributionOnly || ultraEmergency,
                emergency || dominantContributionOnly,
                emergency || dominantContributionOnly,
                false,
                false,
                emergency || dominantContributionOnly,
                ultraEmergency || dominantContributionOnly,
                disableReflections,
                disableTransmission,
                roughnessSkip,
                throughputTermination);
        }
        if (completedSamples < STILL_TIER1_MIN_SAMPLES) {
            return new PreviewQualityTierPlan("STILL_T0_CARRIER_ONLY", 0, 1, 0,
            1.0, 1, 1, 0, 0, true, true, true, false, false, true, true,
            false, false, 0.0, 0.0);
        }
        if (completedSamples < STILL_TIER2_MIN_SAMPLES) {
            return new PreviewQualityTierPlan("STILL_T1_REFLECTION_SEED", Math.min(1, fullPolishDepth), 4, fullPolishDepth > 0 ? 1 : 0,
            1.0, 1, 1, 1, 1, false, false, true, false, false, false, false,
            false, false, 0.0, 0.0);
        }
        if (completedSamples < STILL_TIER3_MIN_SAMPLES) {
            return new PreviewQualityTierPlan("STILL_T2_LOCAL_LIGHTS", Math.min(1, fullPolishDepth), 2, fullPolishDepth > 0 ? 1 : 0,
            1.0, 1, 1, 2, 1, false, false, false, false, false, false, false,
            false, false, 0.0, 0.0);
        }
        if (completedSamples < STILL_TIER4_MIN_SAMPLES) {
            return new PreviewQualityTierPlan("STILL_T3_TRANSMISSION_DEPTH", Math.max(1, Math.min(2, fullPolishDepth)), 1, fullPolishDepth > 0 ? 1 : 0,
            1.0, 0, 0, 4, 2, false, false, false, false, false, false, false,
            false, false, 0.0, 0.0);
        }
        if (completedSamples < STILL_TIER5_MIN_SAMPLES) {
            return new PreviewQualityTierPlan("STILL_T4_POLISH_NEAR_REFERENCE", fullPolishDepth, 1, fullPolishDepth > 0 ? 3 : 0,
            1.0, 0, 0, -1, 4, false, false, false, false, false, false, false,
            false, false, 0.0, 0.0);
        }
        return new PreviewQualityTierPlan("STILL_T5_REFERENCE_READY", fullPolishDepth, 1, fullPolishDepth > 0 ? 3 : 0,
            1.0, 0, 0, -1, -1, false, false, false, false, false, false, false,
            false, false, 0.0, 0.0);
    }

    private void applyPreviewQualityPlan(PreviewQualityTierPlan plan) {
        if (plan == null) {
            activePreviewQualityTier = "UNSPECIFIED";
            activePreviewPolishCadence = 1;
            activePreviewPolishSamplesPerFrame = 0;
            activePreviewPolishSecondaryDepth = 0;
            activePreviewPolishScale = 1.0;
            activeCarrierDenoisePassCap = 0;
            activeCarrierDenoiseRadiusCap = 0;
            activeCarrierMaxLocalLights = -1;
            activeCarrierMaxShadowedLocalLights = -1;
            activeCarrierSkipDenoiserAnalysis = false;
            activeCarrierDisableSheen = false;
            activeCarrierDisableClearcoat = false;
            activeCarrierDisableDirectionalShadows = false;
            activeCarrierDisablePointLightShadows = false;
            activeCarrierPointLightsDiffuseOnly = false;
            activeCarrierDiffuseOnlyDirectLighting = false;
            activeCarrierDisableReflections = false;
            activeCarrierDisableTransmission = false;
            activeCarrierSecondaryRoughnessSkip = 0.0;
            activeCarrierSecondaryThroughputTermination = 0.0;
            return;
        }
        boolean tierChanged = !activePreviewQualityTier.equals(plan.name());
        activePreviewQualityTier = plan.name();
        activePreviewPolishCadence = Math.max(1, plan.polishCadence());
        activePreviewPolishSamplesPerFrame = Math.max(0, plan.polishSamplesPerFrame());
        activePreviewPolishSecondaryDepth = Math.max(0, plan.polishSecondaryDepth());
        activeCarrierDenoisePassCap = Math.max(0, plan.carrierDenoisePassCap());
        activeCarrierDenoiseRadiusCap = Math.max(0, plan.carrierDenoiseRadiusCap());
        activeCarrierMaxLocalLights = plan.carrierMaxLocalLights();
        activeCarrierMaxShadowedLocalLights = plan.carrierMaxShadowedLocalLights();
        activeCarrierSkipDenoiserAnalysis = plan.carrierSkipDenoiserAnalysis();
        activeCarrierDisableSheen = plan.carrierDisableSheen();
        activeCarrierDisableClearcoat = plan.carrierDisableClearcoat();
        activeCarrierDisableDirectionalShadows = plan.carrierDisableDirectionalShadows();
        activeCarrierDisablePointLightShadows = plan.carrierDisablePointLightShadows();
        activeCarrierPointLightsDiffuseOnly = plan.carrierPointLightsDiffuseOnly();
        activeCarrierDiffuseOnlyDirectLighting = plan.carrierDiffuseOnlyDirectLighting();
        activeCarrierDisableReflections = plan.carrierDisableReflections();
        activeCarrierDisableTransmission = plan.carrierDisableTransmission();
        activeCarrierSecondaryRoughnessSkip = Math.max(0.0, plan.carrierSecondaryRoughnessSkip());
        activeCarrierSecondaryThroughputTermination = Math.max(0.0, plan.carrierSecondaryThroughputTermination());
        if (activeCarrierDisableSheen
                || activeCarrierDisableClearcoat
                || activeCarrierDisableDirectionalShadows
                || activeCarrierDisablePointLightShadows
                || activeCarrierPointLightsDiffuseOnly
            || activeCarrierDiffuseOnlyDirectLighting
            || activeCarrierDisableReflections
            || activeCarrierDisableTransmission) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_SIMPLIFIED_FRAMES, 1L);
        }
        if (activeCarrierDisableDirectionalShadows || activeCarrierDisablePointLightShadows) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_CARRIER_REDUCED_SHADOW_FRAMES, 1L);
        }
        double normalizedScale = normalizePolishScale(plan.polishScale());
        boolean polishShapeChanged = Math.abs(activePreviewPolishScale - normalizedScale) > 1e-6;
        framePolishScaleChanged |= polishShapeChanged;
        framePolishTierChanged |= tierChanged;
        activePreviewPolishScale = normalizedScale;
        boolean integrandChanged = activePolishIntegrandDepth != activePreviewPolishSecondaryDepth;
        framePolishIntegrandChanged |= integrandChanged;
        if (integrandChanged || polishShapeChanged) {
            resetPolishAccumulation();
            activePolishIntegrandDepth = activePreviewPolishSecondaryDepth;
        }
    }

    private boolean shouldRunPolishTrace(PreviewQualityTierPlan plan, long frameSequence) {
        if (plan == null) {
            return false;
        }
        if (plan.polishSecondaryDepth() <= 0 || plan.polishSamplesPerFrame() <= 0) {
            return false;
        }
        int cadence = Math.max(1, plan.polishCadence());
        if (previewMotionActive && previewPhase == PreviewPhase.MOTION_ENTER) {
            return true;
        }
        return cadence <= 1 || frameSequence % cadence == 0L;
    }

    private void applyMotionSecondaryCadenceGate(long frameSequence) {
        if (!previewQualityLadderEnabled || !previewMotionActive) {
            return;
        }
        int cadence = Math.max(1, previewMotionSecondaryCadence);
        if (cadence <= 1) {
            return;
        }
        if (frameSequence % cadence != 0L) {
            activeCarrierDisableReflections = true;
            activeCarrierDisableTransmission = true;
        }
    }

    private boolean shouldUseHybridMovingBaseLayer(PreviewQualityTierPlan plan) {
     // Tady hybrid moving base v pohybu stale dela viditelne chyby korektnosti
     // (flipped faces a chybejici environment/floor), proto drzim pohyb na ciste RT carrier ceste.
        return false;
    }

    private void configureHybridBaseRendererForPreviewPlan(boolean useHybridMovingBaseLayer) {
        boolean movingHybrid = useHybridMovingBaseLayer && previewMotionActive;
        activeHybridBaseShadingScale = movingHybrid ? clamp(previewMotionBaseShadingScale, 0.18, 1.0) : 1.0;
        activeHybridBaseReducedShading = movingHybrid && activeHybridBaseShadingScale < 0.999;
        hybridBaseRenderer.setParameter("previewFastMaterialMode", movingHybrid);
        hybridBaseRenderer.setParameter("previewDisableNormalMap", false);
        hybridBaseRenderer.setParameter("previewDisableMetallicRoughnessMap", false);
        hybridBaseRenderer.setParameter("previewDisableEmissiveMap", false);
        hybridBaseRenderer.setParameter("previewDisableTransmissionPreview", false);
        hybridBaseRenderer.setParameter("previewDisableSheen", false);
        hybridBaseRenderer.setParameter("previewDisableClearcoat", false);
        hybridBaseRenderer.setParameter("previewPointLightsDiffuseOnly", false);
        hybridBaseRenderer.setParameter("previewDiffuseOnlyLighting", false);
        hybridBaseRenderer.setParameter("previewBaseIdentityMode", activeHybridBaseReducedShading);
    }

    private boolean resolveRunFullDenoise(long frameSequence) {
        if (!previewQualityLadderEnabled || !previewMotionActive) {
            if (previewQualityLadderEnabled && !previewMotionActive && activePreviewPolishSamplesPerFrame <= 0) {
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

    private int resolveCarrierDenoisePassCap() {
        if (!previewQualityLadderEnabled || !previewMotionActive) {
            return 0;
        }
        return Math.max(0, activeCarrierDenoisePassCap);
    }

    private int resolveCarrierDenoiseRadiusCap() {
        if (!previewQualityLadderEnabled || !previewMotionActive) {
            return 0;
        }
        return Math.max(0, activeCarrierDenoiseRadiusCap);
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

    private void sampleEnvironment(double dx, double dy, double dz, RayTraceContext ctx) {
        if (!hasVisibleEnvironment()) {
            ctx.envR = 0.0;
            ctx.envG = 0.0;
            ctx.envB = 0.0;
            return;
        }
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

        for (RayDirLightCache light : dirLights) {
            double sun = Math.max(0.0, dx * light.lx + dy * light.ly + dz * light.lz);
            if (sun <= 1e-6) {
                continue;
            }
            double glow = Math.pow(sun, 220.0) * 6.5 + Math.pow(sun, 32.0) * 0.25;
            ctx.envR += light.r * glow;
            ctx.envG += light.g * glow;
            ctx.envB += light.b * glow;
        }

        ctx.envR = Math.max(0.0, ctx.envR);
        ctx.envG = Math.max(0.0, ctx.envG);
        ctx.envB = Math.max(0.0, ctx.envB);
    }

    private void renderEnvironmentOnly(Camera camera, FrameBuffer fb) {
        Arrays.fill(fb.getDepthBuffer(), 1.0f);
        if (!hasVisibleEnvironment()) {
            Arrays.fill(fb.getColorBuffer(), packColor(0.0, 0.0, 0.0));
            return;
        }

        RayCameraState cam = buildCameraState(camera, fb.getWidth(), fb.getHeight());
        RayTraceContext ctx = new RayTraceContext();
        int[] colorBuffer = fb.getColorBuffer();
        int width = fb.getWidth();
        int height = fb.getHeight();
        for (int y = 0; y < height; y++) {
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                generatePrimaryRay(cam, x, y, 0.5, 0.5, ctx);
                sampleEnvironment(ctx.rayDx, ctx.rayDy, ctx.rayDz, ctx);
                colorBuffer[rowBase + x] = packColor(
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

    private void rotateToEnvironment(double dx, double dy, double dz, RayTraceContext ctx) {
        double ry = dy * environmentPitchCos - dz * environmentPitchSin;
        double rz = dy * environmentPitchSin + dz * environmentPitchCos;
        double rx = dx * environmentYawCos + rz * environmentYawSin;
        double rz2 = -dx * environmentYawSin + rz * environmentYawCos;
        ctx.environmentDirX = rx;
        ctx.environmentDirY = ry;
        ctx.environmentDirZ = rz2;
    }

    private void resetAccumulation() {
        Arrays.fill(accumR, 0.0);
        Arrays.fill(accumG, 0.0);
        Arrays.fill(accumB, 0.0);
        Arrays.fill(accumLuma, 0.0);
        Arrays.fill(accumLumaSq, 0.0);
        Arrays.fill(sampleCounts, 0);
        resetPolishAccumulation();
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
        activePolishIntegrandDepth = -1;
        carrierMotionTileCursor = 0;
        carrierMotionTileLayoutCols = -1;
        carrierMotionTileLayoutRows = -1;
        resetStillReferenceHandoff();
    }

    private void softResetAccumulationPreserveHistory() {
        Arrays.fill(accumR, 0.0);
        Arrays.fill(accumG, 0.0);
        Arrays.fill(accumB, 0.0);
        Arrays.fill(accumLuma, 0.0);
        Arrays.fill(accumLumaSq, 0.0);
        Arrays.fill(sampleCounts, 0);
        softResetPolishAccumulationPreserveComposite();
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
        carrierMotionTileCursor = 0;
        carrierMotionTileLayoutCols = -1;
        carrierMotionTileLayoutRows = -1;
        resetStillReferenceHandoff();
    }

    private void softResetAccumulationInvalidateHistory() {
        softResetAccumulationPreserveHistory();
        invalidateTemporalHistory();
    }

    private void softResetMovingHybridAccumulation() {
        softResetPolishAccumulationPreserveComposite();
        accumulatedSamples = 0L;
        guidesReady = false;
        resetStillReferenceHandoff();
    }

    private void softResetMotionCarrierPreserveAccumulation() {
     // Tady pri pohybove fazi kamery nesmim drzet zastaralou carrier akumulaci,
        // jinak predchozi vzorky unikaji do obrazu jako ghosting stopy.
        softResetAccumulationPreserveHistory();
    }

    private void softResetPolishAccumulationPreserveComposite() {
        Arrays.fill(polishAccumR, 0.0);
        Arrays.fill(polishAccumG, 0.0);
        Arrays.fill(polishAccumB, 0.0);
        Arrays.fill(polishSampleCounts, 0);
        Arrays.fill(polishResolvedR, 0.0);
        Arrays.fill(polishResolvedG, 0.0);
        Arrays.fill(polishResolvedB, 0.0);
        polishLayerActive = hasAnyPolishCompositeCache();
    }

    private void resetPolishAccumulation() {
        Arrays.fill(polishAccumR, 0.0);
        Arrays.fill(polishAccumG, 0.0);
        Arrays.fill(polishAccumB, 0.0);
        Arrays.fill(polishSampleCounts, 0);
        Arrays.fill(polishResolvedR, 0.0);
        Arrays.fill(polishResolvedG, 0.0);
        Arrays.fill(polishResolvedB, 0.0);
        polishLayerActive = false;
        invalidatePolishCompositeCache();
    }

    private void allocateAccumulation(int w, int h) {
        int count = safePixelCount(w, h);
        accumR = new double[count];
        accumG = new double[count];
        accumB = new double[count];
        accumLuma = new double[count];
        accumLumaSq = new double[count];
        sampleCounts = new int[count];
        allocatePolishAccumulation(1, 1);
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
        polishCompositeR = new double[1];
        polishCompositeG = new double[1];
        polishCompositeB = new double[1];
        polishCompositePacked = new int[1];
        temporalHistoryValid = false;
        activePolishIntegrandDepth = -1;
        polishPackedCompositeCacheValid = false;
        polishDoubleCompositeCacheValid = false;
        activePolishCompositeGeneration = -1L;
        activePolishCompositeCameraSignature = Long.MIN_VALUE;
        lastComposedFrameGeneration = -1L;
        lastComposedBaseGeneration = -1L;
        lastComposedPolishGeneration = -1L;
    }

    private void allocatePolishAccumulation(int w, int h) {
        invalidatePolishUpscaleMap();
        polishWidth = Math.max(1, w);
        polishHeight = Math.max(1, h);
        int count = safePixelCount(polishWidth, polishHeight);
        polishAccumR = new double[count];
        polishAccumG = new double[count];
        polishAccumB = new double[count];
        polishSampleCounts = new int[count];
        polishResolvedR = new double[count];
        polishResolvedG = new double[count];
        polishResolvedB = new double[count];
        polishLayerActive = false;
        invalidatePolishCompositeCache();
    }

    private void invalidatePolishCompositeCache() {
        boolean hadCache = hasAnyPolishCompositeCache() || polishLayerActive;
        polishPackedCompositeCacheValid = false;
        polishDoubleCompositeCacheValid = false;
        activePolishCompositeGeneration = -1L;
        activePolishCompositeCameraSignature = Long.MIN_VALUE;
        if (polishCompositeR != null) {
            Arrays.fill(polishCompositeR, 0.0);
        }
        if (polishCompositeG != null) {
            Arrays.fill(polishCompositeG, 0.0);
        }
        if (polishCompositeB != null) {
            Arrays.fill(polishCompositeB, 0.0);
        }
        if (polishCompositePacked != null) {
            Arrays.fill(polishCompositePacked, 0);
        }
        if (hadCache) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_CACHE_INVALIDATIONS, 1L);
        }
    }

    private void invalidatePolishUpscaleMap() {
        if (polishUpscaleMapValid) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_UPSCALE_MAP_INVALIDATIONS,
                    1L);
        }
        polishUpscaleMapValid = false;
        polishUpscaleMapFullWidth = 0;
        polishUpscaleMapFullHeight = 0;
        polishUpscaleMapSourceWidth = 0;
        polishUpscaleMapSourceHeight = 0;
    }

    private void resetStillReferenceHandoff() {
    // Tady je still-reference handoff odstraneny; nechavam no-op kvuli stabilite call site mist.
    }

    private void ensureHybridBaseResources(int targetWidth, int targetHeight) {
        int safeWidth = Math.max(1, targetWidth);
        int safeHeight = Math.max(1, targetHeight);
        if (hybridBaseFrameBuffer.getWidth() != safeWidth || hybridBaseFrameBuffer.getHeight() != safeHeight) {
            hybridBaseFrameBuffer = new FrameBuffer(safeWidth, safeHeight, true);
            hybridBaseRenderer.init(safeWidth, safeHeight);
        } else {
            hybridBaseRenderer.resize(safeWidth, safeHeight);
        }
        int pixelCount = safePixelCount(safeWidth, safeHeight);
        if (hybridBaseCompositePacked.length != pixelCount) {
            hybridBaseCompositePacked = new int[pixelCount];
            hybridBaseCompositePackedValid = false;
        }
        hybridBaseRenderer.setParameter("parallel", workerCount > 1);
        hybridBaseRenderer.setParameter("workerCount", workerCount);
        hybridBaseRenderer.setParameter("unlitMode", false);
        hybridBaseRenderer.setParameter("modelPreviewMode", false);
        hybridBaseRenderer.setParameter("flatShading", false);
        hybridBaseRenderer.setParameter("frustumCulling", true);
        hybridBaseRenderer.setParameter("backfaceCulling", true);
        hybridBaseRenderer.setParameter("previewFastMaterialMode", false);
        hybridBaseRenderer.setParameter("previewDisableNormalMap", false);
        hybridBaseRenderer.setParameter("previewDisableMetallicRoughnessMap", false);
        hybridBaseRenderer.setParameter("previewDisableEmissiveMap", false);
        hybridBaseRenderer.setParameter("previewDisableTransmissionPreview", false);
        hybridBaseRenderer.setParameter("previewDisableSheen", false);
        hybridBaseRenderer.setParameter("previewDisableClearcoat", false);
        hybridBaseRenderer.setParameter("previewPointLightsDiffuseOnly", false);
        hybridBaseRenderer.setParameter("previewDiffuseOnlyLighting", false);
        hybridBaseRenderer.setParameter("previewBaseIdentityMode", false);
    }

    private int resolveScaledDimension(int fullDimension, double scale) {
        int full = Math.max(1, fullDimension);
        if (scale >= 0.999) {
            return full;
        }
        return Math.max(1, (int) Math.round(full * scale));
    }

    private double normalizePolishScale(double requestedScale) {
        if (requestedScale <= 0.26) {
            return 0.25;
        }
        if (requestedScale <= 0.55) {
            return 0.5;
        }
        return 1.0;
    }

    private void updateTemporalHistory(int count) {
        if (count <= 0) {
            temporalHistoryValid = false;
            return;
        }
        int vectorCount = count * 3;
        System.arraycopy(denoiseR, 0, temporalHistoryR, 0, count);
        System.arraycopy(denoiseG, 0, temporalHistoryG, 0, count);
        System.arraycopy(denoiseB, 0, temporalHistoryB, 0, count);
        System.arraycopy(guideDepth, 0, temporalHistoryDepth, 0, count);
        System.arraycopy(guideNormal, 0, temporalHistoryNormal, 0, vectorCount);
        System.arraycopy(guideAlbedo, 0, temporalHistoryAlbedo, 0, vectorCount);
        temporalHistoryValid = true;
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
        if (nextState) {
            softResetAccumulationInvalidateHistory();
        } else {
            invalidateTemporalHistory();
            nextTemporalBlendScale = 0.22;
            nextTemporalBlendFrames = 1;
        }
    }

    private void resolveCarrierFromAccum(int count) {
        if (count <= 0) {
            return;
        }
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        for (int i = 0; i < count; i++) {
            int resolvedSamples = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, accumulatedSamples);
            double resolvedInvSamples = AdaptiveSamplingSupport.inverseSampleCount(resolvedSamples, accumulatedSamples);
            denoiseR[i] = accumR[i] * resolvedInvSamples;
            denoiseG[i] = accumG[i] * resolvedInvSamples;
            denoiseB[i] = accumB[i] * resolvedInvSamples;
            denoiseNoise[i] = DenoiseSupport.relativeNoise(accumLuma[i], accumLumaSq[i], resolvedSamples);
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_FROM_ACCUM_NS,
                    System.nanoTime() - start);
        }
    }

    private boolean prepareHybridMovingPolishComposite(boolean polishUpdatedThisFrame) {
        if (!polishLayerActive) {
            return false;
        }
        if (polishUpdatedThisFrame) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_EXECUTED_FRAMES, 1L);
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_CADENCE_HITS, 1L);
            rebuildMovingPackedPolishCompositeCache();
            return polishPackedCompositeCacheValid;
        }
        if (polishPackedCompositeCacheValid) {
            MovingPolishGuardDecision guardDecision = evaluateMovingPolishGuard(MovingPolishGuardContext.REUSE);
            if (guardDecision != MovingPolishGuardDecision.ALLOW) {
                applyMovingPolishGuardDecision(guardDecision);
                return false;
            }
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REUSED_FRAMES, 1L);
            return true;
        }
        return false;
    }

    private MovingPolishGuardDecision evaluateMovingPolishGuard(MovingPolishGuardContext context) {
        if (context == MovingPolishGuardContext.REUSE) {
            if (!previewMotionActive) {
                return MovingPolishGuardDecision.ALLOW;
            }
            boolean generationMatches = activePolishCompositeGeneration == activePreviewFrameGeneration;
            boolean signatureMatches = activePolishCompositeCameraSignature == cameraSignature;
            return generationMatches || signatureMatches
                    ? MovingPolishGuardDecision.ALLOW
                    : MovingPolishGuardDecision.SKIP_REUSE_GENERATION_MISMATCH;
        }

        if (!previewMotionActive) {
            return MovingPolishGuardDecision.ALLOW;
        }
        boolean generationMismatch = activeBaseCarrierGeneration != activePolishCompositeGeneration;
        boolean signatureMismatch = cameraSignature != activePolishCompositeCameraSignature;
        return generationMismatch || signatureMismatch
                ? MovingPolishGuardDecision.SKIP_COMPOSE_GENERATION_MISMATCH
                : MovingPolishGuardDecision.ALLOW;
    }

    private void applyMovingPolishGuardDecision(MovingPolishGuardDecision decision) {
        if (decision == MovingPolishGuardDecision.SKIP_REUSE_GENERATION_MISMATCH) {
            recordSkippedMovingPolishReuse(true);
            return;
        }
        if (decision == MovingPolishGuardDecision.SKIP_COMPOSE_GENERATION_MISMATCH) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FRAME_GENERATION_MISMATCHES,
                    1L);
            recordSkippedMovingPolishReuse(false);
        }
    }

    private void recordSkippedMovingPolishReuse(boolean cacheReuseGenerationMismatch) {
        if (cacheReuseGenerationMismatch) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_CACHE_REUSE_GENERATION_MISMATCHES,
                    1L);
        }
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_SKIPPED_REUSE_FRAMES,
                1L);
    }

    private void composePackedPolishIntoHybridOutput(int[] outColor,
                                                     int count,
                                                     int[] objectId,
                                                     float[] depth,
                                                     int width,
                                                     int height) {
        if (outColor == null || count <= 0 || polishCompositePacked == null || !polishPackedCompositeCacheValid) {
            return;
        }
        MovingPolishGuardDecision guardDecision = evaluateMovingPolishGuard(MovingPolishGuardContext.COMPOSE);
        if (guardDecision != MovingPolishGuardDecision.ALLOW) {
            applyMovingPolishGuardDecision(guardDecision);
            return;
        }
        lastComposedFrameGeneration = activePreviewFrameGeneration;
        lastComposedBaseGeneration = activeBaseCarrierGeneration;
        lastComposedPolishGeneration = activePolishCompositeGeneration;
        int limit = Math.min(count, Math.min(outColor.length, polishCompositePacked.length));
        if (limit <= 0) {
            return;
        }
        long composeStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        long scanNs = 0L;
        long regionMaskBuildNs = 0L;
        long identityCheckNs = 0L;
        long fetchNs = 0L;
        long blendWriteNs = 0L;
        long passthroughNs = 0L;
        final boolean measure = RuntimeInstrumentation.isEnabled();

        int polishMinX = width;
        int polishMinY = height;
        int polishMaxX = -1;
        int polishMaxY = -1;
        int nonZeroPolishPixels = 0;
        long scanStart = measure ? System.nanoTime() : 0L;
        for (int i = 0; i < limit; i++) {
            int polish = polishCompositePacked[i];
            if ((polish & 0x00FFFFFF) == 0) {
                continue;
            }
            nonZeroPolishPixels++;
            if (width > 0) {
                int x = i % width;
                int y = i / Math.max(1, width);
                if (x < polishMinX) {
                    polishMinX = x;
                }
                if (x > polishMaxX) {
                    polishMaxX = x;
                }
                if (y < polishMinY) {
                    polishMinY = y;
                }
                if (y > polishMaxY) {
                    polishMaxY = y;
                }
            }
        }
        if (measure) {
            scanNs = System.nanoTime() - scanStart;
        }

        if (nonZeroPolishPixels <= 0 || objectId == null || depth == null || width <= 0 || height <= 0) {
            long fallbackFetchStart = measure ? System.nanoTime() : 0L;
            for (int i = 0; i < limit; i++) {
                int polish = polishCompositePacked[i];
                if ((polish & 0x00FFFFFF) != 0) {
                    outColor[i] = addPackedColor(outColor[i], polish);
                }
            }
            if (measure) {
                fetchNs += System.nanoTime() - fallbackFetchStart;
            }
        } else {
            ensureMovingPolishComposeSparseTileMaskCapacity(width, height);
            long maskBuildStart = measure ? System.nanoTime() : 0L;
            buildMovingPolishComposeSparseTileMask(width, height, limit, objectId, depth,
                    polishMinX, polishMinY, polishMaxX, polishMaxY);
            if (measure) {
                regionMaskBuildNs += System.nanoTime() - maskBuildStart;
            }

            ensureMovingPolishComposeWorkCapacity(limit);

            int candidateCount = 0;
            int activeTilePixels = 0;
            long identityStart = measure ? System.nanoTime() : 0L;
            for (int tileY = 0; tileY < polishComposeSparseTileRows; tileY++) {
                int yStart = tileY * MOVING_POLISH_COMPOSE_TILE_SIZE;
                int yEnd = Math.min(height, yStart + MOVING_POLISH_COMPOSE_TILE_SIZE);
                int tileRow = tileY * polishComposeSparseTileCols;
                for (int tileX = 0; tileX < polishComposeSparseTileCols; tileX++) {
                    if (polishComposeSparseTileMask[tileRow + tileX] == 0) {
                        continue;
                    }
                    int xStart = tileX * MOVING_POLISH_COMPOSE_TILE_SIZE;
                    int xEnd = Math.min(width, xStart + MOVING_POLISH_COMPOSE_TILE_SIZE);
                    for (int y = yStart; y < yEnd; y++) {
                        int row = y * width;
                        for (int x = xStart; x < xEnd; x++) {
                            int idx = row + x;
                            if (idx < 0 || idx >= limit || idx >= objectId.length || idx >= depth.length) {
                                continue;
                            }
                            activeTilePixels++;
                            if (objectId[idx] < 0 || depth[idx] >= 1.0f) {
                                continue;
                            }
                            polishComposeCandidateIndices[candidateCount++] = idx;
                        }
                    }
                }
            }
            if (measure) {
                identityCheckNs += System.nanoTime() - identityStart;
            }

            int blendCount = 0;
            long fetchStart = measure ? System.nanoTime() : 0L;
            for (int i = 0; i < candidateCount; i++) {
                int idx = polishComposeCandidateIndices[i];
                int polish = polishCompositePacked[idx];
                if ((polish & 0x00FFFFFF) == 0) {
                    continue;
                }
                polishComposeBlendIndices[blendCount] = idx;
                polishComposeBlendPacked[blendCount] = polish;
                blendCount++;
            }
            if (measure) {
                fetchNs += System.nanoTime() - fetchStart;
            }

            double polishContribution = 1.0;
            if (previewMotionActive && candidateCount > 0) {
                double blendCoverage = (double) blendCount / (double) Math.max(1, candidateCount);
                double tileCoverage = (double) blendCount / (double) Math.max(1, activeTilePixels);
                if (blendCoverage < 0.35 || tileCoverage < 0.22) {
                    // Tady pri ridkych partial frame vypinam cached polish prispevek,
                    // aby nebyl viditelny rozpad obrazu po tilech.
                    polishContribution = 0.0;
                } else if (blendCoverage < 0.75 || tileCoverage < 0.45) {
                    double blendRamp = (blendCoverage - 0.35) / 0.40;
                    double tileRamp = (tileCoverage - 0.22) / 0.23;
                    polishContribution = clamp(Math.min(blendRamp, tileRamp), 0.0, 1.0);
                }
            }

            long blendStart = measure ? System.nanoTime() : 0L;
            for (int i = 0; i < blendCount; i++) {
                int idx = polishComposeBlendIndices[i];
                outColor[idx] = polishContribution >= 0.999
                        ? addPackedColor(outColor[idx], polishComposeBlendPacked[i])
                        : addPackedColorScaled(outColor[idx], polishComposeBlendPacked[i], polishContribution);
            }
            if (measure) {
                blendWriteNs += System.nanoTime() - blendStart;
            }

            long passthroughStart = measure ? System.nanoTime() : 0L;
            int passthroughPixels = Math.max(0, limit - blendCount);
            if (measure) {
                passthroughNs += System.nanoTime() - passthroughStart;
            }
            if (measure) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_PIXELS,
                        blendCount);
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_PIXELS,
                        passthroughPixels);
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_TILE_PIXELS,
                        activeTilePixels);
                RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_STALE_ACTIVE_PIXELS,
                    Math.max(0, candidateCount - blendCount));
                if (blendCount > 0 && blendCount < activeTilePixels) {
                    RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PARTIAL_ACTIVE_REGION_FRAMES,
                        1L);
                }
            }
        }

        if (measure) {
            long composeElapsed = System.nanoTime() - composeStart;
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_COMPOSE_NS,
                    composeElapsed);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_PACKED_CACHE_REUSE_NS,
                    composeElapsed);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_FETCH_NS,
                    fetchNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_BLEND_NS,
                    blendWriteNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FULL_FRAME_SCAN_NS,
                    scanNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_REGION_MASK_BUILD_NS,
                    regionMaskBuildNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_IDENTITY_CHECK_NS,
                    identityCheckNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_FETCH_NS,
                    fetchNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_BLEND_WRITE_NS,
                    blendWriteNs);
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_NS,
                    passthroughNs);
        }
    }

    private void ensureMovingPolishComposeWorkCapacity(int pixelCount) {
        int size = Math.max(1, pixelCount);
        if (polishComposeCandidateIndices.length < size) {
            polishComposeCandidateIndices = new int[size];
        }
        if (polishComposeBlendIndices.length < size) {
            polishComposeBlendIndices = new int[size];
            polishComposeBlendPacked = new int[size];
        } else if (polishComposeBlendPacked.length < size) {
            polishComposeBlendPacked = new int[size];
        }
    }

    private void ensureMovingPolishComposeSparseTileMaskCapacity(int fullWidth, int fullHeight) {
        int tileCols = Math.max(1, (fullWidth + MOVING_POLISH_COMPOSE_TILE_SIZE - 1) / MOVING_POLISH_COMPOSE_TILE_SIZE);
        int tileRows = Math.max(1, (fullHeight + MOVING_POLISH_COMPOSE_TILE_SIZE - 1) / MOVING_POLISH_COMPOSE_TILE_SIZE);
        int tileCount = Math.max(1, safePixelCount(tileCols, tileRows));
        if (polishComposeSparseTileMask.length < tileCount) {
            polishComposeSparseTileMask = new byte[tileCount];
        }
        Arrays.fill(polishComposeSparseTileMask, 0, tileCount, (byte) 0);
        polishComposeSparseTileCols = tileCols;
        polishComposeSparseTileRows = tileRows;
    }

    private void markMovingPolishComposeTileNeighborhood(int tileX, int tileY) {
        int minTileX = Math.max(0, tileX - 1);
        int maxTileX = Math.min(polishComposeSparseTileCols - 1, tileX + 1);
        int minTileY = Math.max(0, tileY - 1);
        int maxTileY = Math.min(polishComposeSparseTileRows - 1, tileY + 1);
        for (int ny = minTileY; ny <= maxTileY; ny++) {
            int row = ny * polishComposeSparseTileCols;
            for (int nx = minTileX; nx <= maxTileX; nx++) {
                polishComposeSparseTileMask[row + nx] = 1;
            }
        }
    }

    private void buildMovingPolishComposeSparseTileMask(int fullWidth,
                                                        int fullHeight,
                                                        int fullCount,
                                                        int[] objectId,
                                                        float[] depth,
                                                        int polishMinX,
                                                        int polishMinY,
                                                        int polishMaxX,
                                                        int polishMaxY) {
        if (objectId == null || depth == null || polishMaxX < polishMinX || polishMaxY < polishMinY) {
            return;
        }
        int scanMinX = Math.max(0, polishMinX - MOVING_POLISH_COMPOSE_TILE_SIZE);
        int scanMaxX = Math.min(fullWidth - 1, polishMaxX + MOVING_POLISH_COMPOSE_TILE_SIZE);
        int scanMinY = Math.max(0, polishMinY - MOVING_POLISH_COMPOSE_TILE_SIZE);
        int scanMaxY = Math.min(fullHeight - 1, polishMaxY + MOVING_POLISH_COMPOSE_TILE_SIZE);

        for (int y = scanMinY; y <= scanMaxY; y++) {
            int row = y * fullWidth;
            int tileY = y / MOVING_POLISH_COMPOSE_TILE_SIZE;
            for (int x = scanMinX; x <= scanMaxX; x++) {
                int idx = row + x;
                if (idx < 0 || idx >= fullCount || idx >= objectId.length || idx >= depth.length || idx >= polishCompositePacked.length) {
                    continue;
                }
                int polish = polishCompositePacked[idx];
                if ((polish & 0x00FFFFFF) == 0) {
                    continue;
                }
                int id = objectId[idx];
                float d = depth[idx];
                if (id < 0 || d >= 1.0f) {
                    continue;
                }
                int tileX = x / MOVING_POLISH_COMPOSE_TILE_SIZE;
                polishComposeSparseTileMask[tileY * polishComposeSparseTileCols + tileX] = 1;

                boolean edgePixel = false;
                if (x > 0) {
                    int leftIdx = idx - 1;
                    if (leftIdx >= 0
                            && leftIdx < fullCount
                            && leftIdx < objectId.length
                            && leftIdx < depth.length
                            && (objectId[leftIdx] != id || Math.abs(depth[leftIdx] - d) > 0.01f)) {
                        edgePixel = true;
                    }
                }
                if (!edgePixel && y > 0) {
                    int upIdx = idx - fullWidth;
                    if (upIdx >= 0
                            && upIdx < fullCount
                            && upIdx < objectId.length
                            && upIdx < depth.length
                            && (objectId[upIdx] != id || Math.abs(depth[upIdx] - d) > 0.01f)) {
                        edgePixel = true;
                    }
                }
                if (edgePixel) {
                    markMovingPolishComposeTileNeighborhood(tileX, tileY);
                }
            }
        }
    }

    private int addPackedColor(int basePacked, int polishPacked) {
        int br = (basePacked >>> 16) & 0xFF;
        int bg = (basePacked >>> 8) & 0xFF;
        int bb = basePacked & 0xFF;
        int pr = (polishPacked >>> 16) & 0xFF;
        int pg = (polishPacked >>> 8) & 0xFF;
        int pb = polishPacked & 0xFF;
        int r = br + pr;
        int g = bg + pg;
        int b = bb + pb;
        if (r > 255) {
            r = 255;
        }
        if (g > 255) {
            g = 255;
        }
        if (b > 255) {
            b = 255;
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private int addPackedColorScaled(int basePacked, int polishPacked, double scale) {
        if (scale <= 1e-6) {
            return basePacked;
        }
        if (scale >= 0.999) {
            return addPackedColor(basePacked, polishPacked);
        }
        int br = (basePacked >>> 16) & 0xFF;
        int bg = (basePacked >>> 8) & 0xFF;
        int bb = basePacked & 0xFF;
        int pr = (polishPacked >>> 16) & 0xFF;
        int pg = (polishPacked >>> 8) & 0xFF;
        int pb = polishPacked & 0xFF;
        int r = br + (int) Math.round(pr * scale);
        int g = bg + (int) Math.round(pg * scale);
        int b = bb + (int) Math.round(pb * scale);
        if (r > 255) {
            r = 255;
        }
        if (g > 255) {
            g = 255;
        }
        if (b > 255) {
            b = 255;
        }
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void writeCarrierResolvedToOutput(int[] outColor, int count) {
        if (outColor == null || count <= 0) {
            return;
        }
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int limit = Math.min(count, outColor.length);
        for (int i = 0; i < limit; i++) {
            outColor[i] = packColor(
                    toneMap(denoiseR[i]),
                    toneMap(denoiseG[i]),
                    toneMap(denoiseB[i])
            );
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_OUTPUT_NS,
                    System.nanoTime() - start);
        }
    }

    private void resolvePolishIntoOutput(int[] outColor, int count, boolean polishUpdatedThisFrame) {
        if (outColor == null || count <= 0 || !polishLayerActive) {
            return;
        }
        if (polishUpdatedThisFrame) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_EXECUTED_FRAMES, 1L);
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_CADENCE_HITS, 1L);
            rebuildStillDoublePolishCompositeCache();
        } else if (polishDoubleCompositeCacheValid) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REUSED_FRAMES, 1L);
        } else {
            return;
        }
        compositeCachedPolishIntoOutput(outColor, count);
    }

    private boolean hasAnyPolishCompositeCache() {
        return polishPackedCompositeCacheValid || polishDoubleCompositeCacheValid;
    }

    private void rebuildMovingPackedPolishCompositeCache() {
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int limit = Math.min(width * height, polishCompositePacked.length);
        if (polishWidth == width && polishHeight == height) {
            long directPackStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
            int directLimit = Math.min(limit, polishAccumR.length);
            for (int i = 0; i < directLimit; i++) {
                polishCompositePacked[i] = packResolvedPolishSample(i);
            }
            for (int i = directLimit; i < limit; i++) {
                polishCompositePacked[i] = 0;
            }
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_DIRECT_PACK_NS,
                        System.nanoTime() - directPackStart);
            }
        } else {
            resolveMovingPolishResolvedBuffers();
            ensurePolishUpscaleMap();
            long upscalePackStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
            for (int y = 0; y < height; y++) {
                int row = y * width;
                int y0Row = polishUpscaleMapY0Row[y];
                int y1Row = polishUpscaleMapY1Row[y];
                double wy0 = polishUpscaleMapWy0[y];
                double wy1 = polishUpscaleMapWy1[y];
                for (int x = 0; x < width; x++) {
                    int idx = row + x;
                    if (idx >= limit) {
                        polishPackedCompositeCacheValid = true;
                        polishDoubleCompositeCacheValid = false;
                        if (RuntimeInstrumentation.isEnabled()) {
                            RuntimeInstrumentation.addCounter(
                                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_PACKED_CACHE_REBUILD_NS,
                                    System.nanoTime() - start);
                        }
                        return;
                    }
                    int x0 = polishUpscaleMapX0[x];
                    int x1 = polishUpscaleMapX1[x];
                    double wx0 = polishUpscaleMapWx0[x];
                    double wx1 = polishUpscaleMapWx1[x];

                    int i00 = y0Row + x0;
                    int i10 = y0Row + x1;
                    int i01 = y1Row + x0;
                    int i11 = y1Row + x1;

                    double r = polishResolvedR[i00] * wx0 * wy0
                            + polishResolvedR[i10] * wx1 * wy0
                            + polishResolvedR[i01] * wx0 * wy1
                            + polishResolvedR[i11] * wx1 * wy1;
                    double g = polishResolvedG[i00] * wx0 * wy0
                            + polishResolvedG[i10] * wx1 * wy0
                            + polishResolvedG[i01] * wx0 * wy1
                            + polishResolvedG[i11] * wx1 * wy1;
                    double b = polishResolvedB[i00] * wx0 * wy0
                            + polishResolvedB[i10] * wx1 * wy0
                            + polishResolvedB[i01] * wx0 * wy1
                            + polishResolvedB[i11] * wx1 * wy1;
                    polishCompositePacked[idx] = packColor(toneMap(r), toneMap(g), toneMap(b));
                }
            }
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_UPSCALE_PACK_NS,
                        System.nanoTime() - upscalePackStart);
            }
        }
        polishPackedCompositeCacheValid = true;
        polishDoubleCompositeCacheValid = false;
        activePolishCompositeGeneration = activePreviewFrameGeneration;
        activePolishCompositeCameraSignature = cameraSignature;
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_PACKED_CACHE_REBUILD_NS,
                    System.nanoTime() - start);
        }
    }

    private void resolveMovingPolishResolvedBuffers() {
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int polishCount = Math.min(safePixelCount(polishWidth, polishHeight), polishResolvedR.length);
        for (int i = 0; i < polishCount; i++) {
            int samples = polishSampleCounts[i];
            if (samples > 0) {
                double invSamples = 1.0 / samples;
                polishResolvedR[i] = polishAccumR[i] * invSamples;
                polishResolvedG[i] = polishAccumG[i] * invSamples;
                polishResolvedB[i] = polishAccumB[i] * invSamples;
            } else {
                polishResolvedR[i] = 0.0;
                polishResolvedG[i] = 0.0;
                polishResolvedB[i] = 0.0;
            }
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REBUILD_RESOLVE_LOWRES_NS,
                    System.nanoTime() - start);
        }
    }

    private void ensurePolishUpscaleMap() {
        if (polishWidth == width && polishHeight == height) {
            invalidatePolishUpscaleMap();
            return;
        }
        if (polishUpscaleMapValid
                && polishUpscaleMapFullWidth == width
                && polishUpscaleMapFullHeight == height
                && polishUpscaleMapSourceWidth == polishWidth
                && polishUpscaleMapSourceHeight == polishHeight
                && polishUpscaleMapX0.length >= width
                && polishUpscaleMapY0Row.length >= height) {
            return;
        }

        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        ensurePolishUpscaleMapCapacity(width, height);

        for (int x = 0; x < width; x++) {
            double srcX = ((x + 0.5) * polishWidth / Math.max(1.0, (double) width)) - 0.5;
            int x0 = clampIndex((int) Math.floor(srcX), polishWidth);
            int x1 = clampIndex(x0 + 1, polishWidth);
            double tx = clamp(srcX - x0, 0.0, 1.0);
            polishUpscaleMapX0[x] = x0;
            polishUpscaleMapX1[x] = x1;
            polishUpscaleMapWx0[x] = (float) (1.0 - tx);
            polishUpscaleMapWx1[x] = (float) tx;
        }
        for (int y = 0; y < height; y++) {
            double srcY = ((y + 0.5) * polishHeight / Math.max(1.0, (double) height)) - 0.5;
            int y0 = clampIndex((int) Math.floor(srcY), polishHeight);
            int y1 = clampIndex(y0 + 1, polishHeight);
            double ty = clamp(srcY - y0, 0.0, 1.0);
            polishUpscaleMapY0Row[y] = y0 * polishWidth;
            polishUpscaleMapY1Row[y] = y1 * polishWidth;
            polishUpscaleMapWy0[y] = (float) (1.0 - ty);
            polishUpscaleMapWy1[y] = (float) ty;
        }

        polishUpscaleMapFullWidth = width;
        polishUpscaleMapFullHeight = height;
        polishUpscaleMapSourceWidth = polishWidth;
        polishUpscaleMapSourceHeight = polishHeight;
        polishUpscaleMapValid = true;

        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_UPSCALE_MAP_BUILD_NS,
                    System.nanoTime() - start);
        }
    }

    private void ensurePolishUpscaleMapCapacity(int fullWidth, int fullHeight) {
        int safeWidth = Math.max(1, fullWidth);
        int safeHeight = Math.max(1, fullHeight);
        if (polishUpscaleMapX0.length < safeWidth) {
            polishUpscaleMapX0 = new int[safeWidth];
            polishUpscaleMapX1 = new int[safeWidth];
            polishUpscaleMapWx0 = new float[safeWidth];
            polishUpscaleMapWx1 = new float[safeWidth];
        }
        if (polishUpscaleMapY0Row.length < safeHeight) {
            polishUpscaleMapY0Row = new int[safeHeight];
            polishUpscaleMapY1Row = new int[safeHeight];
            polishUpscaleMapWy0 = new float[safeHeight];
            polishUpscaleMapWy1 = new float[safeHeight];
        }
    }

    private void rebuildStillDoublePolishCompositeCache() {
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int polishCount = Math.min(safePixelCount(polishWidth, polishHeight), polishResolvedR.length);
        for (int i = 0; i < polishCount; i++) {
            int samples = polishSampleCounts[i];
            if (samples > 0) {
                double invSamples = 1.0 / samples;
                polishResolvedR[i] = polishAccumR[i] * invSamples;
                polishResolvedG[i] = polishAccumG[i] * invSamples;
                polishResolvedB[i] = polishAccumB[i] * invSamples;
            }
        }
        int limit = Math.min(width * height, Math.min(polishCompositeR.length, Math.min(polishCompositeG.length, polishCompositeB.length)));
        if (polishWidth == width && polishHeight == height) {
            int directLimit = Math.min(limit, polishResolvedR.length);
            System.arraycopy(polishResolvedR, 0, polishCompositeR, 0, directLimit);
            System.arraycopy(polishResolvedG, 0, polishCompositeG, 0, directLimit);
            System.arraycopy(polishResolvedB, 0, polishCompositeB, 0, directLimit);
            for (int i = 0; i < directLimit; i++) {
                polishCompositePacked[i] = packColor(
                        toneMap(polishResolvedR[i]),
                        toneMap(polishResolvedG[i]),
                        toneMap(polishResolvedB[i]));
            }
            polishPackedCompositeCacheValid = false;
            polishDoubleCompositeCacheValid = true;
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_POLISH_STILL_CACHE_WORK_NS,
                        System.nanoTime() - start);
            }
            return;
        }
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (idx >= limit) {
                    polishPackedCompositeCacheValid = false;
                    polishDoubleCompositeCacheValid = true;
                    if (RuntimeInstrumentation.isEnabled()) {
                        RuntimeInstrumentation.addCounter(
                                RuntimeInstrumentation.Counter.PREVIEW_POLISH_STILL_CACHE_WORK_NS,
                                System.nanoTime() - start);
                    }
                    return;
                }
                polishCompositeR[idx] = sampleUpscaledPolish(polishResolvedR, x, y);
                polishCompositeG[idx] = sampleUpscaledPolish(polishResolvedG, x, y);
                polishCompositeB[idx] = sampleUpscaledPolish(polishResolvedB, x, y);
                polishCompositePacked[idx] = packColor(
                        toneMap(polishCompositeR[idx]),
                        toneMap(polishCompositeG[idx]),
                        toneMap(polishCompositeB[idx]));
            }
        }
        polishPackedCompositeCacheValid = false;
        polishDoubleCompositeCacheValid = true;
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_POLISH_STILL_CACHE_WORK_NS,
                    System.nanoTime() - start);
        }
    }

    private void compositeCachedPolishIntoOutput(int[] outColor, int count) {
        int limit = Math.min(Math.min(count, outColor.length), Math.min(denoiseR.length, polishCompositeR.length));
        for (int i = 0; i < limit; i++) {
            outColor[i] = packColor(
                    toneMap(denoiseR[i] + polishCompositeR[i]),
                    toneMap(denoiseG[i] + polishCompositeG[i]),
                    toneMap(denoiseB[i] + polishCompositeB[i])
            );
        }
    }

    private int packResolvedPolishSample(int index) {
        if (index < 0 || index >= polishAccumR.length || index >= polishSampleCounts.length) {
            return 0;
        }
        int samples = polishSampleCounts[index];
        if (samples <= 0) {
            return 0;
        }
        double invSamples = 1.0 / samples;
        return packColor(
                toneMap(polishAccumR[index] * invSamples),
                toneMap(polishAccumG[index] * invSamples),
                toneMap(polishAccumB[index] * invSamples));
    }

    private double sampleUpscaledPolish(double[] values, int x, int y) {
        if (values == null || values.length == 0 || polishWidth <= 0 || polishHeight <= 0) {
            return 0.0;
        }
        if (polishWidth == width && polishHeight == height) {
            int idx = y * width + x;
            return idx >= 0 && idx < values.length ? values[idx] : 0.0;
        }

        double srcX = ((x + 0.5) * polishWidth / Math.max(1.0, (double) width)) - 0.5;
        double srcY = ((y + 0.5) * polishHeight / Math.max(1.0, (double) height)) - 0.5;
        int x0 = clampIndex((int) Math.floor(srcX), polishWidth);
        int y0 = clampIndex((int) Math.floor(srcY), polishHeight);
        int x1 = clampIndex(x0 + 1, polishWidth);
        int y1 = clampIndex(y0 + 1, polishHeight);
        double tx = clamp(srcX - x0, 0.0, 1.0);
        double ty = clamp(srcY - y0, 0.0, 1.0);

        double v00 = values[y0 * polishWidth + x0];
        double v10 = values[y0 * polishWidth + x1];
        double v01 = values[y1 * polishWidth + x0];
        double v11 = values[y1 * polishWidth + x1];
        double top = v00 + (v10 - v00) * tx;
        double bottom = v01 + (v11 - v01) * tx;
        return top + (bottom - top) * ty;
    }

    private int clampIndex(int value, int size) {
        if (size <= 1) {
            return 0;
        }
        return Math.max(0, Math.min(size - 1, value));
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

    private void applyDenoiseAndResolve(int[] outColor, double invSamples) {
        applyDenoiseAndResolve(outColor, invSamples, 1.0, true);
    }

    private void applyDenoiseAndResolve(int[] outColor, double invSamples, double temporalBlendScale) {
        applyDenoiseAndResolve(outColor, invSamples, temporalBlendScale, true);
    }

    private void applyDenoiseAndResolve(int[] outColor,
                                        double invSamples,
                                        double temporalBlendScale,
                                        boolean allowFullDenoise) {
        int count = Math.min(outColor.length, accumR.length);
        DenoiseSchedule.State schedule = DenoiseSchedule.resolve(
                accumulatedSamples,
                denoiseRadius,
                denoiseStrength);
        if (!schedule.active()) {
            resolveCarrierFromAccum(count);
            writeCarrierResolvedToOutput(outColor, count);
            invalidateTemporalHistory();
            return;
        }
        if (!allowFullDenoise && temporalHistoryValid && accumulatedSamples > 0L) {
            RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES, 1L);
            denoiserTelemetry.onSkip("skip_motion_cadence", 0.0, Math.max(1.0, accumulatedSamples));
            resolveCarrierWithoutFullDenoise(
                    outColor,
                    count,
                    temporalBlendScale,
                    null,
                    0,
                    0,
                    0,
                    0,
                    0,
                    true,
                    false);
            return;
        }

        boolean simplifiedMovingCarrier = previewQualityLadderEnabled
                && previewMotionActive
                && activeCarrierSkipDenoiserAnalysis;
        RuntimeDenoiserOrchestrator.TelemetrySnapshot previous = denoiserTelemetry.snapshot();
        RuntimeDenoiserOrchestrator.Decision decision;
        double effectiveSpp;
        double invalidGuideRatio;
        double meanNoise;
        long metricsStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        if (simplifiedMovingCarrier) {
            decision = RuntimeDenoiserOrchestrator.decide(
                    width,
                    height,
                    denoiserTelemetry.smoothedLatencyMs(),
                    true,
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
            effectiveSpp = Math.max(1.0, accumulatedSamples);
            invalidGuideRatio = Double.isFinite(previous.lastInvalidGuideRatio())
                    ? previous.lastInvalidGuideRatio()
                    : 0.0;
            meanNoise = 1.0;
        } else {
            decision = RuntimeDenoiserOrchestrator.decide(
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
            effectiveSpp = RuntimeDenoiserOrchestrator.estimateEffectiveSpp(sampleCounts, count, accumulatedSamples);
            invalidGuideRatio = RuntimeDenoiserOrchestrator.invalidGuideRatio(
                    guideDepth,
                    guideNormal,
                    guideAlbedo,
                    guideRoughness,
                    count,
                    width,
                    height
            );
            meanNoise = RuntimeDenoiserOrchestrator.estimateMeanRelativeNoise(
                    accumLuma,
                    accumLumaSq,
                    sampleCounts,
                    accumulatedSamples,
                    count
            );
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_METRICS_NS,
                    System.nanoTime() - metricsStart);
        }
        denoiserTelemetry.onDecision(decision);
        if (decision.mode() == RuntimeDenoiserOrchestrator.RuntimeMode.FULL_FRAME) {
            tileSize = Math.max(8, decision.tileSize());
        } else {
            tileSize = Math.max(8, decision.tileSize());
            denoiseTileOverlap = decision.overlap();
        }

        if (!simplifiedMovingCarrier
                && effectiveSpp >= 48.0
                && meanNoise < 0.018
                && invalidGuideRatio < 0.20) {
            denoiserTelemetry.onSkip("skip_clean_high_spp", invalidGuideRatio, effectiveSpp);
            resolveCarrierFromAccum(count);
            writeCarrierResolvedToOutput(outColor, count);
            invalidateTemporalHistory();
            return;
        }

        double preLuma = simplifiedMovingCarrier ? 0.0 : RuntimeDenoiserOrchestrator.meanAccumulatedLuminance(
                accumR,
                accumG,
                accumB,
                sampleCounts,
                accumulatedSamples,
                count
        );
        if (!simplifiedMovingCarrier) {
            buildSmartSectionMaps(count, effectiveSpp, meanNoise, invalidGuideRatio);
        }
        long denoiseStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.DENOISE);
        long carrierDenoiseStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.CARRIER_DENOISE);
        long denoiseStart = System.nanoTime();
        int passCap = resolveCarrierDenoisePassCap();
        int radiusCap = resolveCarrierDenoiseRadiusCap();
        int effectiveRadius = radiusCap > 0 ? Math.min(schedule.radius(), radiusCap) : schedule.radius();
        JointBilateralDenoiser.apply(
                width,
                height,
                workerCount,
                threadPool,
                effectiveRadius,
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
                simplifiedMovingCarrier ? null : smartSectionBlendScale,
                simplifiedMovingCarrier ? null : smartSectionDetailScale,
                denoiseScratchR,
                denoiseScratchG,
                denoiseScratchB,
                outColor,
                denoiseFastMode,
                passCap
        );
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.CARRIER_DENOISE, carrierDenoiseStage);
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.DENOISE, denoiseStage);

        double latencyMs = (System.nanoTime() - denoiseStart) / 1_000_000.0;
        if (RuntimeDenoiserOrchestrator.hasNonFinite(denoiseR, count)
                || RuntimeDenoiserOrchestrator.hasNonFinite(denoiseG, count)
                || RuntimeDenoiserOrchestrator.hasNonFinite(denoiseB, count)) {
            denoiserTelemetry.onFallback("fallback_nonfinite_output", 1.0, invalidGuideRatio, effectiveSpp);
            resolveCarrierFromAccum(count);
            writeCarrierResolvedToOutput(outColor, count);
            invalidateTemporalHistory();
            return;
        }

        if (temporalHistoryValid && accumulatedSamples > 0L) {
            long temporalStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.TEMPORAL);
            long temporalStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
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
                    temporalBlendScale);
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_TEMPORAL_NS,
                        System.nanoTime() - temporalStart);
            }
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.TEMPORAL, temporalStage);
        }

        double energyRatio = 1.0;
        long postMetricsStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        if (!simplifiedMovingCarrier) {
            double postLuma = RuntimeDenoiserOrchestrator.meanLuminance(denoiseR, denoiseG, denoiseB, count);
            energyRatio = preLuma <= 1e-6 ? 1.0 : postLuma / preLuma;
            if (!Double.isFinite(energyRatio) || energyRatio > 1.55) {
                denoiserTelemetry.onFallback("fallback_energy_amplification", energyRatio, invalidGuideRatio, effectiveSpp);
                resolveCarrierFromAccum(count);
                writeCarrierResolvedToOutput(outColor, count);
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
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_METRICS_NS,
                    System.nanoTime() - postMetricsStart);
        }
        updateTemporalHistory(count);
        denoiserTelemetry.onDenoiseLatency(latencyMs, energyRatio, invalidGuideRatio, effectiveSpp);
    }

    private void resolveCarrierWithoutFullDenoise(int[] outColor,
                                                  int count,
                                                  double temporalBlendScale,
                                                  boolean[] tileRenderPlan,
                                                  int tileCols,
                                                  int tileW,
                                                  int tileH,
                                                  int fbWidth,
                                                  int fbHeight,
                                                  boolean fullFrameCoverage,
                                                  boolean carrierSkipped) {
        if (count <= 0) {
            return;
        }
        boolean partialCoverage = previewMotionActive
                && !fullFrameCoverage
                && tileRenderPlan != null
                && !carrierSkipped;
        if (partialCoverage) {
            resolveCarrierFromAccumForPlannedTiles(count, tileRenderPlan, tileCols, tileW, tileH, fbWidth, fbHeight);
        } else {
            resolveCarrierFromAccum(count);
        }

        if (!previewMotionActive && temporalHistoryValid && accumulatedSamples > 0L) {
            long temporalStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.TEMPORAL);
            long temporalStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
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
                    temporalBlendScale);
            if (RuntimeInstrumentation.isEnabled()) {
                RuntimeInstrumentation.addCounter(
                        RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_TEMPORAL_NS,
                        System.nanoTime() - temporalStart);
            }
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.TEMPORAL, temporalStage);
        }

        if (partialCoverage) {
            writeCarrierResolvedTilesToOutput(outColor, count, tileRenderPlan, tileCols, tileW, tileH, fbWidth, fbHeight);
        } else {
            writeCarrierResolvedToOutput(outColor, count);
        }
        if (previewMotionActive) {
            invalidateTemporalHistory();
        } else {
            updateTemporalHistory(count);
        }
    }

    private void resolveCarrierFromAccumForPlannedTiles(int count,
                                                        boolean[] tileRenderPlan,
                                                        int tileCols,
                                                        int tileW,
                                                        int tileH,
                                                        int fbWidth,
                                                        int fbHeight) {
        if (count <= 0 || tileRenderPlan == null || tileCols <= 0 || tileW <= 0 || tileH <= 0) {
            return;
        }
        long start = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        int tileCount = tileRenderPlan.length;
        for (int tile = 0; tile < tileCount; tile++) {
            if (!tileRenderPlan[tile]) {
                continue;
            }
            int tileX = tile % tileCols;
            int tileY = tile / tileCols;
            int x0 = tileX * tileW;
            int y0 = tileY * tileH;
            int x1 = Math.min(fbWidth, x0 + tileW);
            int y1 = Math.min(fbHeight, y0 + tileH);
            for (int y = y0; y < y1; y++) {
                int row = y * fbWidth;
                for (int x = x0; x < x1; x++) {
                    int i = row + x;
                    if (i < 0 || i >= count) {
                        continue;
                    }
                    int resolvedSamples = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, accumulatedSamples);
                    double resolvedInvSamples = AdaptiveSamplingSupport.inverseSampleCount(resolvedSamples, accumulatedSamples);
                    denoiseR[i] = accumR[i] * resolvedInvSamples;
                    denoiseG[i] = accumG[i] * resolvedInvSamples;
                    denoiseB[i] = accumB[i] * resolvedInvSamples;
                    denoiseNoise[i] = DenoiseSupport.relativeNoise(accumLuma[i], accumLumaSq[i], resolvedSamples);
                }
            }
        }
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_CARRIER_RESOLVE_FROM_ACCUM_NS,
                    System.nanoTime() - start);
        }
    }

    private void writeCarrierResolvedTilesToOutput(int[] outColor,
                                                   int count,
                                                   boolean[] tileRenderPlan,
                                                   int tileCols,
                                                   int tileW,
                                                   int tileH,
                                                   int fbWidth,
                                                   int fbHeight) {
        if (outColor == null || tileRenderPlan == null || tileCols <= 0 || tileW <= 0 || tileH <= 0) {
            return;
        }
        int tileCount = tileRenderPlan.length;
        for (int tile = 0; tile < tileCount; tile++) {
            if (!tileRenderPlan[tile]) {
                continue;
            }
            int tileX = tile % tileCols;
            int tileY = tile / tileCols;
            int x0 = tileX * tileW;
            int y0 = tileY * tileH;
            int x1 = Math.min(fbWidth, x0 + tileW);
            int y1 = Math.min(fbHeight, y0 + tileH);
            for (int y = y0; y < y1; y++) {
                int row = y * fbWidth;
                for (int x = x0; x < x1; x++) {
                    int i = row + x;
                    if (i < 0 || i >= count || i >= outColor.length) {
                        continue;
                    }
                    outColor[i] = packColor(denoiseR[i], denoiseG[i], denoiseB[i]);
                }
            }
        }
    }

    private static boolean parseFastDenoiseMode(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
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

    private void buildSmartSectionMaps(int count,
                                       double effectiveSpp,
                                       double meanNoise,
                                       double invalidGuideRatio) {
        if (count <= 0) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int samples = AdaptiveSamplingSupport.resolveSampleCount(sampleCounts, i, accumulatedSamples);
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
        double inv = AdaptiveSamplingSupport.inverseSampleCount(sampleCount, accumulatedSamples);
        return DenoiseSupport.luminance(accumR[pixelIndex] * inv, accumG[pixelIndex] * inv, accumB[pixelIndex] * inv);
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
            || smartSectionBlendScale.length < count
            || smartSectionDetailScale.length < count
            || smartSectionScratch.length < count
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
        long tileCostStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        applyTileCostModel(tileCostNanos, tileCostSamples);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_TILE_COST_NS,
                    System.nanoTime() - tileCostStart);
        }
        long hardwareSampleStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        sampleHardwareTelemetry();
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_HW_SAMPLE_NS,
                    System.nanoTime() - hardwareSampleStart);
        }
        long smoothingStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        updateAutoSmoothedFrameMs(frameMs);
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_SMOOTHING_NS,
                    System.nanoTime() - smoothingStart);
        }

        long thresholdStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        double target = clamp(autoTargetFrameMs, AUTO_TARGET_FRAME_MS_MIN, AUTO_TARGET_FRAME_MS_MAX);
        double slowThreshold = target * 1.12;
        double fastThreshold = target * 0.74;
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_THRESHOLD_NS,
                    System.nanoTime() - thresholdStart);
        }

        long decisionStart = RuntimeInstrumentation.isEnabled() ? System.nanoTime() : 0L;
        if (autoSmoothedFrameMs > slowThreshold) {
            applySlowAutoAdjustment();
            recordAutoDecisionNs(decisionStart);
            return;
        }

        if (autoSmoothedFrameMs < fastThreshold) {
            applyFastAutoAdjustment(tileCount);
            recordAutoDecisionNs(decisionStart);
            return;
        }

        resetAutoSchedulingStreaks();
        recordAutoDecisionNs(decisionStart);
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

    private void recordAutoDecisionNs(long decisionStart) {
        if (RuntimeInstrumentation.isEnabled()) {
            RuntimeInstrumentation.addCounter(
                    RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_DECISION_NS,
                    System.nanoTime() - decisionStart);
        }
    }

    private void scheduleAutoTuning(int tileCount, double frameMs, LongAdder tileCostNanos, LongAdder tileCostSamples) {
        if (!Double.isFinite(frameMs) || frameMs <= 0.0) {
            return;
        }
        long tileNanos = tileCostNanos == null ? 0L : tileCostNanos.sum();
        long tileSamples = tileCostSamples == null ? 0L : tileCostSamples.sum();
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
        HardwareTelemetrySampler.CpuSample sample = HardwareTelemetrySampler.sampleCpuOnly();
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.PREVIEW_AUTOSCHED_HW_SAMPLE_HITS,
                1L);
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

    private void recordTileCost(LongAdder tileCostNanos, LongAdder tileCostSamples, long tileStartNanos) {
        if (tileCostNanos == null || tileCostSamples == null || tileStartNanos <= 0L) {
            return;
        }
        long elapsed = System.nanoTime() - tileStartNanos;
        if (elapsed <= 0L) {
            return;
        }
        tileCostNanos.add(elapsed);
        tileCostSamples.increment();
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
        if (base instanceof PhongMaterial) {
            return (PhongMaterial) base;
        }
        Vec3 c = base != null ? base.getBaseColor() : new Vec3(0.75, 0.75, 0.75);
        PhongMaterial out = new PhongMaterial(c, 32.0);
        if (base != null) {
            out.copyFrom(base);
        }
        return out;
    }

}

