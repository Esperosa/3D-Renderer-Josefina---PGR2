package engine.core;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import engine.camera.Camera;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.HexMosaicRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.post.WireframeRenderer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.preview.ProgressiveRenderDefaults;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.Scene;
import engine.sim.water.WaterParticleRenderer;
import engine.sim.water.WaterSimulation;
import engine.ui.UiStrings;
import engine.util.AnimatedGifWriter;
import engine.util.BitFont;
import engine.util.MjpegAviWriter;
import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;

/**
 * řídím finální output rendering mimo realtime viewport.
 */
public class OutputRenderController {

    private static final long VIEWPORT_PREVIEW_INTERVAL_NS = 2_000_000_000L;
    private static final long UI_PROGRESS_UPDATE_MIN_NS = 40_000_000L;
    private static final long PAUSE_WAIT_SLICE_MS = 40L;

    public static final class PreviewState {
        public final String headline;
        public final String progressText;
        public final String metricsText;
        public final double progressFraction;
        public final boolean paused;
        public final boolean cancellable;
        public final boolean pausable;

        PreviewState(
                String headline,
                String progressText,
                String metricsText,
                double progressFraction,
                boolean paused,
                boolean cancellable,
                boolean pausable) {
            this.headline = headline;
            this.progressText = progressText;
            this.metricsText = metricsText;
            this.progressFraction = progressFraction;
            this.paused = paused;
            this.cancellable = cancellable;
            this.pausable = pausable;
        }
    }

    public static final class Settings {
        public int width = 1920;
        public int height = 1080;
        public RenderMode mode = RenderMode.PATH_TRACING;
        public String format = "png";
        public String baseDirectory = "renders";
        public boolean createSessionFolder = true;
        public boolean appendTimestampToSession = true;
        public String sessionName = "render";
        public String exportType = "still";
        public boolean useTimelineRange = true;
        public int frameStart = 0;
        public int frameEnd = 120;
        public double frameRate = 24.0;
        public boolean gifLoopForever = true;
        public double aviJpegQuality = 0.90;
        public boolean writeManifest = true;
        public boolean writePreviewImage = true;
        public boolean writeLogFile = true;
        public boolean openFolderAfterRender = false;
        public double jpgQuality = 0.92;
        public String sequenceSubfolderName = "sequence";
        public boolean saveAlphaWhenPossible = true;
        public String outputDirectory = "renders";
        public String filePrefix = "render";
        public double internalScale = 1.0;
        public int workerCount = ThreadPool.recommendedOutputWorkerCount();
        public int tileSize = 24;
        public int targetSamples = 192;
        public int samplesPerStep = 1;
        public int maxDepth = 6;
        public boolean directLighting = true;
        public boolean shadows = true;
        public boolean reflections = true;
        public boolean sky = true;
        public boolean denoise = true;
        public int denoiseRadius = ProgressiveRenderDefaults.OUTPUT_DENOISE_RADIUS;
        public double denoiseStrength = ProgressiveRenderDefaults.OUTPUT_DENOISE_STRENGTH;
        public String toneMap = "EXPOSURE";
        public double pathClampDirect = ProgressiveRenderDefaults.OUTPUT_PATH_CLAMP_DIRECT;
        public double pathClampIndirect = ProgressiveRenderDefaults.OUTPUT_PATH_CLAMP_INDIRECT;
        public boolean referenceClampEnabled = true;
        public boolean wireframeDepthHiddenLines = true;
        public boolean wireframeSilhouetteBoost = true;
        public boolean wireframeDashedMode = false;
        public String ditherStyle = DitherRenderer.DitherStyle.BLUE_NOISE.name();
        public int ditherToneCount = 2;
        public double ditherContrast = 1.15;
        public double ditherLightAssist = 0.38;
        public boolean ditherInvert = false;
        public int ditherCellSize = 6;
        public String ditherAsciiCharset = BitFont.DEFAULT_ASCII_CHARSET;
        public double temporalTickRate = 6.8;
        public double temporalNearContribution = 2.22;
        public double temporalGrazingContribution = 1.87;
        public double temporalMinSpeed = 0.55;
        public double temporalMaxSpeed = 10.61;
        public double temporalEdgeBlendStrength = 0.12;
        public int temporalGrainCellSize = 2;
        public int temporalPaletteLevels = 5;
        public double hexCellSize = 6.0;
        public int hexQuantizationLevels = 8;
        public double hexOutlineStrength = 0.42;
        public boolean hexEdgeAware = false;
        public boolean hexDistanceScaling = false;
        public boolean hexDebugCells = false;
        public String hexWowMode = "classic";
        public double hexWowStrength = 0.22;
    }

    private final Settings settings;

    private volatile boolean renderInProgress;
    private volatile OutputRenderPendingRequest pendingRequest;
    private volatile boolean renderCancelRequested;
    private volatile long progressCurrent;
    private volatile long progressTarget;
    private volatile String progressMessage;
    private volatile long renderStartNanos;
    private volatile long progressUiLastUpdateNanos;
    private final AtomicReference<int[]> viewportPreviewPixels;
    private volatile int viewportPreviewWidth;
    private volatile int viewportPreviewHeight;
    private final AtomicReference<String[]> viewportPreviewLines;
    private final AtomicReference<PreviewState> viewportPreviewState;
    private volatile long viewportPreviewLastPublishNanos;
    private volatile OutputRenderJob activeViewportPreviewJob;
    private volatile int progressFrameNumber;
    private volatile int progressFrameIndex;
    private volatile int progressFrameCount;
    private volatile int progressFramesCompleted;
    private volatile String progressCurrentFile;
    private volatile String progressOutputFolder;
    private volatile boolean renderPaused;
    private volatile long renderPauseStartedNanos;
    private volatile long renderPausedAccumulatedNanos;
    private volatile long progressRateSampleNanos;
    private volatile long progressRateSampleUnits;
    private volatile double progressUnitsPerSecondSmoothed;
    private volatile long progressAdvanceSampleNanos;
    private volatile long progressAdvanceSampleUnits;
    private volatile double progressInstantUnitsPerSecond;
    private volatile long progressFastestUnitNanos;
    private volatile long progressSlowestUnitNanos;

    public OutputRenderController() {
        this.settings = new Settings();
        this.renderInProgress = false;
        this.pendingRequest = null;
        this.renderCancelRequested = false;
        this.progressCurrent = 0L;
        this.progressTarget = 1L;
        this.progressMessage = "Nečinný";
        this.renderStartNanos = 0L;
        this.progressUiLastUpdateNanos = 0L;
        this.viewportPreviewPixels = new AtomicReference<>(new int[]{0xFF050608});
        this.viewportPreviewWidth = 1;
        this.viewportPreviewHeight = 1;
        this.viewportPreviewLines = new AtomicReference<>(new String[0]);
        this.viewportPreviewState = new AtomicReference<>(null);
        this.viewportPreviewLastPublishNanos = 0L;
        this.activeViewportPreviewJob = null;
        this.progressFrameNumber = 0;
        this.progressFrameIndex = -1;
        this.progressFrameCount = 0;
        this.progressFramesCompleted = 0;
        this.progressCurrentFile = "";
        this.progressOutputFolder = "";
        this.renderPaused = false;
        this.renderPauseStartedNanos = 0L;
        this.renderPausedAccumulatedNanos = 0L;
        this.progressRateSampleNanos = 0L;
        this.progressRateSampleUnits = 0L;
        this.progressUnitsPerSecondSmoothed = 0.0;
        this.progressAdvanceSampleNanos = 0L;
        this.progressAdvanceSampleUnits = 0L;
        this.progressInstantUnitsPerSecond = 0.0;
        this.progressFastestUnitNanos = 0L;
        this.progressSlowestUnitNanos = 0L;
    }

    public Settings settings() {
        return settings;
    }

    public boolean hasViewportPreview() {
        int[] pixels = viewportPreviewPixels.get();
        return pixels != null && pixels.length >= Math.max(1, viewportPreviewWidth * viewportPreviewHeight);
    }

    public int[] getViewportPreviewPixels() {
        return viewportPreviewPixels.get();
    }

    public int getViewportPreviewWidth() {
        return Math.max(1, viewportPreviewWidth);
    }

    public int getViewportPreviewHeight() {
        return Math.max(1, viewportPreviewHeight);
    }

    public String[] getViewportPreviewLines() {
        String[] lines = viewportPreviewLines.get();
        return lines == null ? new String[0] : lines.clone();
    }

    public PreviewState getViewportPreviewState() {
        return viewportPreviewState.get();
    }

    public void captureStylizedViewportSettings(Engine engine) {
        if (engine == null) {
            return;
        }
        settings.wireframeDepthHiddenLines = engine.wireframeRenderer != null && engine.wireframeRenderer.isDepthHiddenLines();
        settings.wireframeSilhouetteBoost = engine.wireframeRenderer == null || engine.wireframeRenderer.isSilhouetteBoost();
        settings.wireframeDashedMode = engine.wireframeRenderer != null && engine.wireframeRenderer.isDashedMode();
        settings.ditherStyle = engine.ditherRenderer != null
                ? engine.ditherRenderer.getStyle().name()
                : settings.ditherStyle;
        settings.ditherToneCount = engine.ditherToneCount;
        settings.ditherContrast = engine.ditherContrast;
        settings.ditherLightAssist = engine.ditherLightAssist;
        settings.ditherInvert = engine.ditherInvert;
        settings.ditherCellSize = engine.ditherCellSize;
        settings.ditherAsciiCharset = engine.ditherAsciiCharset;
        settings.temporalTickRate = engine.temporalTickRate;
        settings.temporalNearContribution = engine.temporalNearContribution;
        settings.temporalGrazingContribution = engine.temporalGrazingContribution;
        settings.temporalMinSpeed = engine.temporalMinSpeed;
        settings.temporalMaxSpeed = engine.temporalMaxSpeed;
        settings.temporalEdgeBlendStrength = engine.temporalEdgeBlendStrength;
        settings.temporalGrainCellSize = TemporalNoiseRenderer.normalizeGrainCellSizePreset(engine.temporalGrainCellSize);
        settings.temporalPaletteLevels = engine.temporalPaletteLevels;
        settings.hexCellSize = engine.hexCellSizeSetting;
        settings.hexQuantizationLevels = engine.hexQuantizationLevels;
        settings.hexOutlineStrength = engine.hexOutlineStrength;
        settings.hexEdgeAware = engine.hexEdgeAware;
        settings.hexDistanceScaling = engine.hexDistanceScaling;
        settings.hexDebugCells = engine.hexDebugCells;
        settings.hexWowMode = engine.hexMosaicRenderer != null
                ? engine.hexMosaicRenderer.getWowModeName()
                : settings.hexWowMode;
        settings.hexWowStrength = engine.hexWowStrength;
        settings.toneMap = engine.activeMode == RenderMode.RAY_TRACING
            ? engine.rayToneMap
            : engine.pathToneMap;
    }

    public boolean isRenderInProgress() {
        return renderInProgress;
    }

    public boolean isRenderPaused() {
        return renderPaused;
    }

    int previewInternalWidth() {
        OutputRenderSupport.syncLegacySettings(settings);
        return OutputRenderSupport.computeInternalDimension(settings.width, settings.internalScale);
    }

    int previewInternalHeight() {
        OutputRenderSupport.syncLegacySettings(settings);
        return OutputRenderSupport.computeInternalDimension(settings.height, settings.internalScale);
    }

    long previewEstimatedWorkingSetBytes() {
        OutputRenderJob preview = buildPreviewJob();
        return estimateWorkingSetBytes(preview, preview.internalWidth, preview.internalHeight);
    }

    String previewResourceBudgetWarning() {
        return validateResourceBudget(buildPreviewJob());
    }

    public void requestStill(String formatOverride) {
        requestStill(-1, settings.frameRate, formatOverride);
    }

    public void requestStill(int timelineFrame, String formatOverride) {
        requestStill(timelineFrame, settings.frameRate, formatOverride);
    }

    public void requestStill(int timelineFrame, double frameRate, String formatOverride) {
        if (renderInProgress) {
            System.out.println("Output render already in progress.");
            return;
        }
        OutputRenderSupport.syncLegacySettings(settings);
        String fmt = formatOverride == null ? settings.format : formatOverride.trim().toLowerCase(Locale.ROOT);
        if (!OutputRenderSupport.isSupportedStillFormat(fmt)) {
            return;
        }
        OutputRenderPendingRequest req = new OutputRenderPendingRequest();
        req.type = OutputRenderRequestType.STILL;
        req.outputFormat = OutputRenderSupport.normalizeStillFormat(fmt);
        int safeFrame = Math.max(0, timelineFrame);
        req.frameStart = safeFrame;
        req.frameEnd = safeFrame;
        req.frameRate = OutputRenderSupport.clampFrameRate(frameRate);
        settings.exportType = "still";
        pendingRequest = req;
    }

    public void requestImageSequence(int startFrame, int endFrame, double fps, String formatOverride) {
        if (renderInProgress) {
            System.out.println("Output render already in progress.");
            return;
        }
        OutputRenderSupport.syncLegacySettings(settings);
        String fmt = formatOverride == null ? settings.format : formatOverride.trim().toLowerCase(Locale.ROOT);
        if (!OutputRenderSupport.isSupportedStillFormat(fmt)) {
            return;
        }
        OutputRenderPendingRequest req = new OutputRenderPendingRequest();
        req.type = OutputRenderRequestType.IMAGE_SEQUENCE;
        req.outputFormat = OutputRenderSupport.normalizeStillFormat(fmt);
        req.frameStart = OutputRenderSupport.normalizeFrameStart(startFrame, endFrame);
        req.frameEnd = OutputRenderSupport.normalizeFrameEnd(startFrame, endFrame);
        req.frameRate = OutputRenderSupport.clampFrameRate(fps);
        settings.exportType = "sequence";
        pendingRequest = req;
    }

    public void requestAnimatedGif(int startFrame, int endFrame, double fps) {
        if (renderInProgress) {
            System.out.println("Output render already in progress.");
            return;
        }
        OutputRenderPendingRequest req = new OutputRenderPendingRequest();
        req.type = OutputRenderRequestType.ANIMATED_GIF;
        req.outputFormat = "gif";
        req.frameStart = OutputRenderSupport.normalizeFrameStart(startFrame, endFrame);
        req.frameEnd = OutputRenderSupport.normalizeFrameEnd(startFrame, endFrame);
        req.frameRate = OutputRenderSupport.clampFrameRate(fps);
        settings.exportType = "gif";
        pendingRequest = req;
    }

    public void requestAnimatedAvi(int startFrame, int endFrame, double fps) {
        if (renderInProgress) {
            System.out.println("Output render already in progress.");
            return;
        }
        OutputRenderSupport.syncLegacySettings(settings);
        OutputRenderPendingRequest req = new OutputRenderPendingRequest();
        req.type = OutputRenderRequestType.ANIMATED_AVI;
        req.outputFormat = "avi";
        req.frameStart = OutputRenderSupport.normalizeFrameStart(startFrame, endFrame);
        req.frameEnd = OutputRenderSupport.normalizeFrameEnd(startFrame, endFrame);
        req.frameRate = OutputRenderSupport.clampFrameRate(fps);
        settings.exportType = "avi";
        pendingRequest = req;
    }

    public void togglePauseRender() {
        if (!renderInProgress) {
            return;
        }
        if (renderPaused) {
            long pauseStart = renderPauseStartedNanos;
            if (pauseStart > 0L) {
                renderPausedAccumulatedNanos += Math.max(0L, System.nanoTime() - pauseStart);
            }
            renderPauseStartedNanos = 0L;
            renderPaused = false;
            progressMessage = "Render obnoven";
        } else {
            renderPauseStartedNanos = System.nanoTime();
            renderPaused = true;
            progressMessage = "Render pozastaven";
        }
        PreviewState state = buildViewportPreviewState(activeViewportPreviewJob);
        viewportPreviewState.set(state);
        viewportPreviewLines.set(buildViewportPreviewLines(state));
    }

    public void cancelRender() {
        renderCancelRequested = true;
    }

    public void dispose() {
        renderCancelRequested = true;
        renderPaused = false;
        clearViewportPreview();
    }

    public void processPendingRequest(Scene scene,
                                      BiFunction<Integer, Integer, Camera> cameraBuilder,
                                      Consumer<Boolean> visibilityApplier,
                                      boolean frustumCullingEnabled,
                                      boolean backfaceCullingEnabled,
                                      IntConsumer timelineFrameApplier) {
        if (renderInProgress) {
            return;
        }
        OutputRenderPendingRequest req = pendingRequest;
        if (req == null) {
            return;
        }
        pendingRequest = null;
        startRenderJob(scene, cameraBuilder, visibilityApplier, frustumCullingEnabled, backfaceCullingEnabled, timelineFrameApplier, req);
    }

    private void startRenderJob(Scene scene,
                                BiFunction<Integer, Integer, Camera> cameraBuilder,
                                Consumer<Boolean> visibilityApplier,
                                boolean frustumCullingEnabled,
                                boolean backfaceCullingEnabled,
                                IntConsumer timelineFrameApplier,
                                OutputRenderPendingRequest request) {
        if (scene == null || cameraBuilder == null || visibilityApplier == null) {
            return;
        }
        OutputRenderJob job = buildOutputRenderJob(request, frustumCullingEnabled, backfaceCullingEnabled);
        String resourceIssue = validateResourceBudget(job);
        if (resourceIssue != null) {
            System.out.println(resourceIssue);
            progressMessage = resourceIssue;
            clearViewportPreview();
            return;
        }
        try {
            job.paths = createSessionPaths(job);
            job.session = new OutputRenderSessionContext();
        } catch (IOException ex) {
            progressMessage = "Output render selhal: " + ex.getMessage();
            clearViewportPreview();
            System.out.println(progressMessage);
            return;
        }
        renderInProgress = true;
        renderCancelRequested = false;
        renderPaused = false;
        renderPauseStartedNanos = 0L;
        renderPausedAccumulatedNanos = 0L;
        progressCurrent = 0L;
        progressTarget = computeInitialProgressTarget(job);
        progressMessage = "Připravuji output render";
        renderStartNanos = System.nanoTime();
        progressUiLastUpdateNanos = 0L;
        progressRateSampleNanos = renderStartNanos;
        progressRateSampleUnits = 0L;
        progressUnitsPerSecondSmoothed = 0.0;
        progressAdvanceSampleNanos = renderStartNanos;
        progressAdvanceSampleUnits = 0L;
        progressInstantUnitsPerSecond = 0.0;
        progressFastestUnitNanos = 0L;
        progressSlowestUnitNanos = 0L;
        progressFrameNumber = job.stillFrame;
        progressFrameIndex = job.requestType == OutputRenderRequestType.STILL ? 0 : -1;
        progressFrameCount = job.frameCount;
        progressFramesCompleted = 0;
        progressCurrentFile = "";
        progressOutputFolder = job.paths.sessionFolder.toString();
        openProgressDialog(job);

        Thread worker = new Thread(() -> runRenderJob(scene, cameraBuilder, visibilityApplier, timelineFrameApplier, job),
                "output-render-worker");
        worker.setDaemon(true);
        worker.setPriority(Math.max(Thread.MIN_PRIORITY + 1, Thread.NORM_PRIORITY - 1));
        worker.start();
    }

    private OutputRenderJob buildOutputRenderJob(OutputRenderPendingRequest request,
                                                 boolean frustumCullingEnabled,
                                                 boolean backfaceCullingEnabled) {
        OutputRenderSupport.syncLegacySettings(settings);
        OutputRenderJob job = new OutputRenderJob();
        OutputRenderRequestType type = request != null && request.type != null
                ? request.type
                : OutputRenderSupport.requestTypeFromExportType(settings.exportType);
        String requestedFormat = request != null ? request.outputFormat : settings.format;
        String imageFormat = OutputRenderSupport.normalizeStillFormat(requestedFormat);
        job.requestType = type;
        job.exportType = OutputRenderSupport.exportTypeFromRequestType(type);
        job.mode = settings.mode != null ? settings.mode : RenderMode.PATH_TRACING;
        job.imageFormat = imageFormat;
        job.format = switch (type) {
            case ANIMATED_GIF -> "gif";
            case ANIMATED_AVI -> "avi";
            default -> imageFormat;
        };
        job.baseDirectory = settings.baseDirectory;
        job.sessionName = settings.sessionName;
        job.outputDirectory = settings.baseDirectory;
        job.filePrefix = settings.sessionName;
        job.createSessionFolder = settings.createSessionFolder;
        job.appendTimestampToSession = settings.appendTimestampToSession;
        job.useTimelineRange = settings.useTimelineRange;
        job.gifLoopForever = settings.gifLoopForever;
        job.aviJpegQuality = Math.max(0.05, Math.min(1.0, settings.aviJpegQuality));
        job.writeManifest = settings.writeManifest;
        job.writePreviewImage = settings.writePreviewImage;
        job.writeLogFile = settings.writeLogFile;
        job.openFolderAfterRender = settings.openFolderAfterRender;
        job.jpgQuality = Math.max(0.05, Math.min(1.0, settings.jpgQuality));
        job.sequenceSubfolderName = OutputPathUtil.sanitizeSegment(settings.sequenceSubfolderName, "sequence");
        job.saveAlphaWhenPossible = settings.saveAlphaWhenPossible;
        job.width = Math.max(64, settings.width);
        job.height = Math.max(64, settings.height);
        job.internalScale = Math.max(0.25, Math.min(1.0, settings.internalScale));
        job.internalWidth = OutputRenderSupport.computeInternalDimension(job.width, job.internalScale);
        job.internalHeight = OutputRenderSupport.computeInternalDimension(job.height, job.internalScale);
        job.workerCount = Math.max(1, Math.min(ThreadPool.availableWorkerCount(), settings.workerCount));
        job.tileSize = Math.max(8, Math.min(128, settings.tileSize));
        job.targetSamples = Math.max(1, settings.targetSamples);
        job.samplesPerStep = Math.max(1, Math.min(64, settings.samplesPerStep));
        job.maxDepth = Math.max(1, Math.min(16, settings.maxDepth));
        job.directLighting = settings.directLighting;
        job.shadows = settings.shadows;
        job.reflections = settings.reflections;
        job.sky = settings.sky;
        job.referencePathMode = job.mode == RenderMode.PATH_TRACING;
        job.denoise = settings.denoise;
        job.denoiseRadius = Math.max(1, Math.min(4, settings.denoiseRadius));
        job.denoiseStrength = Math.max(0.0, Math.min(1.0, settings.denoiseStrength));
        job.toneMap = settings.toneMap;
        job.pathClampDirect = Math.max(0.0, settings.pathClampDirect);
        job.pathClampIndirect = Math.max(0.0, settings.pathClampIndirect);
        job.referenceClampEnabled = settings.referenceClampEnabled;
        job.wireframeDepthHiddenLines = settings.wireframeDepthHiddenLines;
        job.wireframeSilhouetteBoost = settings.wireframeSilhouetteBoost;
        job.wireframeDashedMode = settings.wireframeDashedMode;
        job.ditherStyle = settings.ditherStyle;
        job.ditherToneCount = Math.max(2, settings.ditherToneCount);
        job.ditherContrast = Math.max(0.1, Math.min(4.0, settings.ditherContrast));
        job.ditherLightAssist = Math.max(0.0, Math.min(1.0, settings.ditherLightAssist));
        job.ditherInvert = settings.ditherInvert;
        job.ditherCellSize = Math.max(2, settings.ditherCellSize);
        job.ditherAsciiCharset = settings.ditherAsciiCharset;
        job.temporalTickRate = Math.max(0.1, Math.min(20.0, settings.temporalTickRate));
        job.temporalNearContribution = Math.max(0.0, Math.min(4.0, settings.temporalNearContribution));
        job.temporalGrazingContribution = Math.max(0.0, Math.min(4.0, settings.temporalGrazingContribution));
        job.temporalMinSpeed = Math.max(0.1, Math.min(12.0, settings.temporalMinSpeed));
        job.temporalMaxSpeed = Math.max(job.temporalMinSpeed, Math.min(12.0, settings.temporalMaxSpeed));
        job.temporalEdgeBlendStrength = Math.max(0.0, Math.min(0.25, settings.temporalEdgeBlendStrength));
        job.temporalGrainCellSize = TemporalNoiseRenderer.normalizeGrainCellSizePreset(settings.temporalGrainCellSize);
        job.temporalPaletteLevels = Math.max(2, Math.min(8, settings.temporalPaletteLevels));
        job.hexCellSize = settings.hexCellSize;
        job.hexQuantizationLevels = Math.max(2, Math.min(32, settings.hexQuantizationLevels));
        job.hexOutlineStrength = settings.hexOutlineStrength;
        job.hexEdgeAware = settings.hexEdgeAware;
        job.hexDistanceScaling = settings.hexDistanceScaling;
        job.hexDebugCells = settings.hexDebugCells;
        job.hexWowMode = settings.hexWowMode;
        job.hexWowStrength = settings.hexWowStrength;
        job.frustumCulling = frustumCullingEnabled;
        job.backfaceCulling = backfaceCullingEnabled;
        if (request != null) {
            job.frameStart = OutputRenderSupport.normalizeFrameStart(request.frameStart, request.frameEnd);
            job.frameEnd = OutputRenderSupport.normalizeFrameEnd(request.frameStart, request.frameEnd);
            job.frameRate = OutputRenderSupport.clampFrameRate(request.frameRate);
        } else {
            job.frameStart = OutputRenderSupport.normalizeFrameStart(settings.frameStart, settings.frameEnd);
            job.frameEnd = OutputRenderSupport.normalizeFrameEnd(settings.frameStart, settings.frameEnd);
            job.frameRate = OutputRenderSupport.clampFrameRate(settings.frameRate);
        }
        job.stillFrame = job.frameStart;
        job.frameCount = type == OutputRenderRequestType.STILL ? 1 : Math.max(1, job.frameEnd - job.frameStart + 1);
        job.timestampToken = OutputPathUtil.timestampNow();
        job.paths = OutputPathUtil.resolveSessionPaths(
                job.baseDirectory,
                job.sessionName,
                job.createSessionFolder,
                job.appendTimestampToSession,
                job.sequenceSubfolderName,
                job.timestampToken
        );
        return job;
    }

    private OutputPathUtil.SessionPaths createSessionPaths(OutputRenderJob job) throws IOException {
        OutputPathUtil.SessionPaths paths = job.paths;
        if (paths == null) {
            paths = OutputPathUtil.resolveSessionPaths(
                    job.baseDirectory,
                    job.sessionName,
                    job.createSessionFolder,
                    job.appendTimestampToSession,
                    job.sequenceSubfolderName,
                    job.timestampToken
            );
        }
        int duplicateIndex = 1;
        while (Files.exists(paths.sessionFolder)) {
            String suffixName = job.sessionName + "_" + String.format(Locale.ROOT, "%02d", duplicateIndex++);
            paths = OutputPathUtil.resolveSessionPaths(
                    job.baseDirectory,
                    suffixName,
                    true,
                    job.appendTimestampToSession,
                    job.sequenceSubfolderName,
                    job.timestampToken
            );
        }
        Files.createDirectories(paths.sessionFolder);
        if (job.requestType == OutputRenderRequestType.IMAGE_SEQUENCE) {
            Files.createDirectories(paths.sequenceFolder);
        }
        return paths;
    }

    private void runRenderJob(Scene scene,
                              BiFunction<Integer, Integer, Camera> cameraBuilder,
                              Consumer<Boolean> visibilityApplier,
                              IntConsumer timelineFrameApplier,
                              OutputRenderJob job) {
        String finalStatus = "Neúspěšné.";
        FrameBuffer outFb = null;
        boolean visibilityApplied = false;
        try {
            outFb = new FrameBuffer(job.internalWidth, job.internalHeight);

            visibilityApplier.accept(true);
            visibilityApplied = true;
            try {
                switch (job.requestType) {
                    case IMAGE_SEQUENCE -> {
                        int rendered = renderImageSequenceJob(scene, cameraBuilder, timelineFrameApplier, job, outFb);
                        finalStatus = renderCancelRequested
                                ? "Zrušeno."
                                : "Uložena sekvence: " + rendered + " snímků do " + job.paths.sequenceFolder.toAbsolutePath();
                    }
                    case ANIMATED_GIF -> {
                        Path gif = renderAnimatedGifJob(scene, cameraBuilder, timelineFrameApplier, job, outFb);
                        if (renderCancelRequested) {
                            finalStatus = "Zrušeno.";
                        } else if (gif != null) {
                            finalStatus = "Uložen GIF: " + gif.toAbsolutePath();
                        } else {
                            finalStatus = "Uložení selhalo.";
                        }
                    }
                    case ANIMATED_AVI -> {
                        Path avi = renderAnimatedAviJob(scene, cameraBuilder, timelineFrameApplier, job, outFb);
                        if (renderCancelRequested) {
                            finalStatus = "Zrušeno.";
                        } else if (avi != null) {
                            finalStatus = "Uloženo AVI: " + avi.toAbsolutePath();
                        } else {
                            finalStatus = "Uložení selhalo.";
                        }
                    }
                    case STILL -> {
                        Path saved = renderStillJob(scene, cameraBuilder, timelineFrameApplier, job, outFb);
                        if (renderCancelRequested) {
                            finalStatus = "Zrušeno.";
                        } else if (saved != null) {
                            finalStatus = "Uloženo: " + saved.toAbsolutePath();
                        } else {
                            finalStatus = "Uložení selhalo.";
                        }
                    }
                }
            } finally {
                visibilityApplier.accept(false);
                visibilityApplied = false;
            }
        } catch (IOException | RuntimeException ex) {
            finalStatus = "Chyba: " + ex.getMessage();
            System.out.println("Output render failed: " + ex.getMessage());
            ex.printStackTrace(System.out);
        } finally {
            if (job != null && job.session != null) {
                if (job.session.previewImage == null && outFb != null) {
                    job.session.previewImage = OutputRenderArtifacts.framebufferToImage(outFb, job.width, job.height, true);
                }
                job.session.cancelled = renderCancelRequested;
                job.session.success = !renderCancelRequested
                        && !finalStatus.startsWith("Chyba")
                        && !finalStatus.startsWith("Uložení selhalo");
                job.session.statusMessage = finalStatus;
                try {
                    OutputRenderArtifacts.writeSessionArtifacts(job, outputElapsedSeconds());
                } catch (IOException artifactError) {
                    finalStatus = "Chyba při zápisu session artefaktů: " + artifactError.getMessage();
                    job.session.statusMessage = finalStatus;
                    job.session.success = false;
                    progressMessage = finalStatus;
                    System.out.println(finalStatus);
                }
            }
            renderInProgress = false;
            renderCancelRequested = false;
            renderPaused = false;
            renderPauseStartedNanos = 0L;
            progressMessage = finalStatus;
            updateProgressDialog(finalStatus, progressCurrent, progressTarget);
            finishProgressDialog(finalStatus);
            if (visibilityApplied) {
                visibilityApplier.accept(false);
            }
        }
    }

    private Path renderStillJob(Scene scene,
                                BiFunction<Integer, Integer, Camera> cameraBuilder,
                                IntConsumer timelineFrameApplier,
                                OutputRenderJob job,
                                FrameBuffer outFb) throws IOException {
        BufferedImage image = renderSingleFrame(
                scene,
                cameraBuilder,
                timelineFrameApplier,
                job,
                outFb,
                job.stillFrame,
                0,
                1
        );
        if (image == null || renderCancelRequested) {
            return null;
        }
        notePreviewImage(job, image, job.stillFrame);
        updateProgress(job, progressTarget, progressTarget, "Zapisuji statický snímek");
        progressCurrentFile = job.paths.stillPathForFormat(job.imageFormat).toString();
        Path out = OutputRenderArtifacts.writeOutputImage(
                image,
                job.paths.stillPathForFormat(job.imageFormat),
                job.imageFormat,
                job.jpgQuality
        );
        if (out != null) {
            OutputRenderArtifacts.registerGeneratedFile(job, out);
            System.out.println("Output render saved: " + out);
        }
        return out;
    }

    private int renderImageSequenceJob(Scene scene,
                                       BiFunction<Integer, Integer, Camera> cameraBuilder,
                                       IntConsumer timelineFrameApplier,
                                       OutputRenderJob job,
                                       FrameBuffer outFb) throws IOException {
        int frameCount = job.frameCount;
        int digits = Math.max(4, Integer.toString(Math.max(Math.abs(job.frameStart), Math.abs(job.frameEnd))).length());
        int renderedFrames = 0;
        for (int i = 0; i < frameCount; i++) {
            waitWhilePaused(job, "Sekvence");
            if (renderCancelRequested) {
                break;
            }
            int frame = job.frameStart + i;
            BufferedImage image = renderSingleFrame(
                    scene,
                    cameraBuilder,
                    timelineFrameApplier,
                    job,
                    outFb,
                    frame,
                    i,
                    frameCount
            );
            if (image == null || renderCancelRequested) {
                break;
            }
            notePreviewImage(job, image, frame);
            Path outPath = job.paths.sequenceFramePath(frame, digits, job.imageFormat);
            progressCurrentFile = outPath.toString();
            Path out = OutputRenderArtifacts.writeOutputImage(image, outPath, job.imageFormat, job.jpgQuality);
            if (out != null) {
                OutputRenderArtifacts.registerGeneratedFile(job, out);
                renderedFrames++;
            }
        }
        return renderedFrames;
    }

    private Path renderAnimatedGifJob(Scene scene,
                                      BiFunction<Integer, Integer, Camera> cameraBuilder,
                                      IntConsumer timelineFrameApplier,
                                      OutputRenderJob job,
                                      FrameBuffer outFb) throws IOException {
        int frameCount = job.frameCount;
        int delayMs = Math.max(10, (int) Math.round(1000.0 / Math.max(1.0, job.frameRate)));
        Path out = job.paths.gifPath;
        progressCurrentFile = out.toString();
        try (AnimatedGifWriter writer = new AnimatedGifWriter(out, delayMs, job.gifLoopForever)) {
            for (int i = 0; i < frameCount; i++) {
                waitWhilePaused(job, "GIF");
                if (renderCancelRequested) {
                    break;
                }
                int frame = job.frameStart + i;
                BufferedImage image = renderSingleFrame(
                        scene,
                        cameraBuilder,
                        timelineFrameApplier,
                        job,
                        outFb,
                        frame,
                        i,
                        frameCount
                );
                if (image == null || renderCancelRequested) {
                    break;
                }
                notePreviewImage(job, image, frame);
                progressCurrentFile = out.getFileName() + " <= frame_" + String.format(Locale.ROOT, "%04d", frame);
                writer.writeFrame(image);
            }
        }
        if (renderCancelRequested) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
 // Cleanup errors after cancel are intentionally ignored.
            }
            return null;
        }
        OutputRenderArtifacts.registerGeneratedFile(job, out);
        System.out.println("Output render saved: " + out);
        return out;
    }

    private Path renderAnimatedAviJob(Scene scene,
                                      BiFunction<Integer, Integer, Camera> cameraBuilder,
                                      IntConsumer timelineFrameApplier,
                                      OutputRenderJob job,
                                      FrameBuffer outFb) throws IOException {
        int frameCount = job.frameCount;
        Path out = job.paths.aviPath;
        progressCurrentFile = out.toString();
        try (MjpegAviWriter writer = new MjpegAviWriter(
                out,
                job.width,
                job.height,
                job.frameRate,
                job.aviJpegQuality
        )) {
            for (int i = 0; i < frameCount; i++) {
                waitWhilePaused(job, "AVI");
                if (renderCancelRequested) {
                    break;
                }
                int frame = job.frameStart + i;
                BufferedImage image = renderSingleFrame(
                        scene,
                        cameraBuilder,
                        timelineFrameApplier,
                        job,
                        outFb,
                        frame,
                        i,
                        frameCount
                );
                if (image == null || renderCancelRequested) {
                    break;
                }
                notePreviewImage(job, image, frame);
                progressCurrentFile = out.getFileName() + " <= frame_" + String.format(Locale.ROOT, "%04d", frame);
                writer.writeFrame(image);
            }
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException cleanupIgnored) {
 // Cleanup errors after AVI failure are intentionally ignored.
            }
            throw ex;
        }
        if (renderCancelRequested) {
            try {
                Files.deleteIfExists(out);
            } catch (IOException ignored) {
 // Cleanup errors after cancel are intentionally ignored.
            }
            return null;
        }
        OutputRenderArtifacts.registerGeneratedFile(job, out);
        System.out.println("Output render saved: " + out);
        return out;
    }

    private void notePreviewImage(OutputRenderJob job, BufferedImage image, int frameNumber) {
        if (job == null || job.session == null || image == null) {
            return;
        }
        job.session.previewImage = image;
        job.session.previewFrameNumber = frameNumber;
        job.session.renderedFrameCount = Math.max(job.session.renderedFrameCount, progressFramesCompleted);
    }

    private BufferedImage renderSingleFrame(Scene scene,
                                            BiFunction<Integer, Integer, Camera> cameraBuilder,
                                            IntConsumer timelineFrameApplier,
                                            OutputRenderJob job,
                                            FrameBuffer outFb,
                                            int timelineFrame,
                                            int frameIndex,
                                            int frameCount) {
        RuntimeInstrumentation.FrameToken frameToken =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.OUTPUT, "output");
        try {
        progressFrameIndex = frameIndex;
        progressFrameCount = Math.max(1, frameCount);
        progressFrameNumber = timelineFrame >= 0 ? timelineFrame : job.stillFrame;
        if (timelineFrameApplier != null && timelineFrame >= 0) {
            timelineFrameApplier.accept(timelineFrame);
        }

        Camera renderCamera = cameraBuilder.apply(outFb.getWidth(), outFb.getHeight());
        if (renderCamera == null) {
            throw new IllegalStateException("Output camera builder returned null.");
        }
        RuntimeInstrumentation.recordMode(job.mode, job.mode);

        double renderTimeSeconds = timelineFrame >= 0
                ? (timelineFrame / Math.max(1.0, job.frameRate))
                : outputElapsedSeconds();

        long doneBase;
        long doneRange;
        long total;
        if (OutputRenderSupport.isSampleBasedMode(job.mode)) {
            long perFrame = Math.max(1, job.targetSamples);
            doneBase = (long) frameIndex * perFrame;
            doneRange = perFrame;
            total = Math.max(1L, (long) frameCount * perFrame);
        } else {
            doneBase = frameIndex;
            doneRange = 1L;
            total = Math.max(1L, frameCount);
        }

        String phase = timelineFrame >= 0
                ? "Snímek " + timelineFrame + " (" + (frameIndex + 1) + "/" + frameCount + ")"
                : UiStrings.Output.STILL_IMAGE;
        long outputRenderStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.OUTPUT_RENDER_TOTAL);
        renderProgressive(scene, job, renderCamera, outFb, renderTimeSeconds, doneBase, doneRange, total, phase);
        if (renderCancelRequested) {
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.OUTPUT_RENDER_TOTAL, outputRenderStage);
            return null;
        }
        renderWaterOverlay(scene, renderCamera, outFb, renderTimeSeconds);
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.OUTPUT_RENDER_TOTAL, outputRenderStage);
        publishViewportPreview(job, outFb, true);
        progressFramesCompleted = Math.min(frameCount, frameIndex + 1);
        if (job.session != null) {
            job.session.renderedFrameCount = Math.max(job.session.renderedFrameCount, progressFramesCompleted);
        }
        boolean withAlpha = job.saveAlphaWhenPossible
                && (job.requestType == OutputRenderRequestType.STILL
                || job.requestType == OutputRenderRequestType.IMAGE_SEQUENCE)
                && "png".equals(job.imageFormat);
        long copyStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.OUTPUT_COPY_ENCODE);
        BufferedImage image = OutputRenderArtifacts.framebufferToImage(outFb, job.width, job.height, withAlpha);
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.OUTPUT_COPY_ENCODE, copyStage);
        return image;
        } finally {
            RuntimeInstrumentation.endFrame(frameToken);
        }
    }

    private void renderProgressive(Scene scene,
                                   OutputRenderJob job,
                                   Camera renderCamera,
                                   FrameBuffer outFb,
                                   double renderTimeSeconds,
                                   long doneBase,
                                   long doneRange,
                                   long total,
                                   String phaseLabel) {
        Renderer renderer = createOutputRenderer(job, outFb);
        if (renderer == null) {
            return;
        }

        if (job.mode == RenderMode.PATH_TRACING) {
            PathTracerRenderer pathRenderer = (PathTracerRenderer) renderer;
            long target = Math.max(1, job.targetSamples);
            updateProgress(job, doneBase, total, phaseLabel + " | Path Tracing");
            while (!renderCancelRequested && pathRenderer.getAccumulatedSamples() < target) {
                waitWhilePaused(job, phaseLabel + " | Path Tracing");
                if (renderCancelRequested) {
                    break;
                }
                pathRenderer.render(scene, renderCamera, outFb, renderTimeSeconds);
                long done = Math.min(target, pathRenderer.getAccumulatedSamples());
                updateProgress(job, doneBase + done, total, phaseLabel + " | Path Tracing");
                publishViewportPreview(job, outFb, false);
            }
            finalizeProgressiveOutput(pathRenderer, scene, renderCamera, outFb, renderTimeSeconds);
            publishViewportPreview(job, outFb, true);
            pathRenderer.setParameter("shutdown", true);
            return;
        }

        if (job.mode == RenderMode.RAY_TRACING) {
            RayTracerRenderer rayRenderer = (RayTracerRenderer) renderer;
            long target = Math.max(1, job.targetSamples);
            updateProgress(job, doneBase, total, phaseLabel + " | Ray Tracing");
            while (!renderCancelRequested && rayRenderer.getAccumulatedSamples() < target) {
                waitWhilePaused(job, phaseLabel + " | Ray Tracing");
                if (renderCancelRequested) {
                    break;
                }
                rayRenderer.render(scene, renderCamera, outFb, renderTimeSeconds);
                long done = Math.min(target, rayRenderer.getAccumulatedSamples());
                updateProgress(job, doneBase + done, total, phaseLabel + " | Ray Tracing");
                publishViewportPreview(job, outFb, false);
            }
            finalizeProgressiveOutput(rayRenderer, scene, renderCamera, outFb, renderTimeSeconds);
            publishViewportPreview(job, outFb, true);
            rayRenderer.setParameter("shutdown", true);
            return;
        }

        updateProgress(job, doneBase, total, phaseLabel + " | Raster");
        waitWhilePaused(job, phaseLabel + " | Raster");
        if (renderCancelRequested) {
            return;
        }
        renderer.render(scene, renderCamera, outFb, renderTimeSeconds);
        updateProgress(job, doneBase + doneRange, total, phaseLabel + " | Raster");
        publishViewportPreview(job, outFb, true);
    }

    private void renderWaterOverlay(Scene scene,
                                    Camera renderCamera,
                                    FrameBuffer outFb,
                                    double renderTimeSeconds) {
        if (scene == null || renderCamera == null || outFb == null) {
            return;
        }
        if (!WaterSimulation.hasEmitterEntities(scene)) {
            return;
        }

        WaterSimulation water = new WaterSimulation();
        double floorY = WaterSimulation.resolveFloorY(scene);
        water.syncToTime(
                scene,
                Math.max(0.0, renderTimeSeconds),
                new Vec3(0.0, -9.81, 0.0),
                floorY,
                true
        );
        WaterParticleRenderer.render(water, renderCamera, outFb);
    }

    private Renderer createOutputRenderer(OutputRenderJob job, FrameBuffer fb) {
        if (job == null || fb == null) {
            return null;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();

        return switch (job.mode) {
            case MODEL -> {
                RasterRenderer renderer = new RasterRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("unlitMode", true);
                renderer.setParameter("modelPreviewMode", true);
                yield renderer;
            }
            case BASIC -> {
                RasterRenderer renderer = new RasterRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("unlitMode", true);
                renderer.setParameter("modelPreviewMode", false);
                yield renderer;
            }
            case PHONG -> {
                RasterRenderer renderer = new RasterRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("unlitMode", false);
                renderer.setParameter("modelPreviewMode", false);
                yield renderer;
            }
            case WIREFRAME -> {
                WireframeRenderer renderer = new WireframeRenderer();
                renderer.init(w, h);
                renderer.setParameter("depthHiddenLines", job.wireframeDepthHiddenLines);
                renderer.setParameter("silhouetteBoost", job.wireframeSilhouetteBoost);
                renderer.setParameter("dashedMode", job.wireframeDashedMode);
                yield renderer;
            }
            case DITHERING -> {
                DitherRenderer renderer = new DitherRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("style", job.ditherStyle);
                renderer.setParameter("toneCount", job.ditherToneCount);
                renderer.setParameter("contrast", job.ditherContrast);
                renderer.setParameter("lightAssist", job.ditherLightAssist);
                renderer.setParameter("invert", job.ditherInvert);
                renderer.setParameter("cellSize", job.ditherCellSize);
                renderer.setParameter("asciiCharset", job.ditherAsciiCharset);
                yield renderer;
            }
            case TEMPORAL_NOISE -> {
                TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("temporalTickRate", job.temporalTickRate);
                renderer.setParameter("depthNearContribution", job.temporalNearContribution);
                renderer.setParameter("grazingContribution", job.temporalGrazingContribution);
                renderer.setParameter("minSpeed", job.temporalMinSpeed);
                renderer.setParameter("maxSpeed", job.temporalMaxSpeed);
                renderer.setParameter("edgeBlendStrength", job.temporalEdgeBlendStrength);
                renderer.setParameter("grainCellSize", job.temporalGrainCellSize);
                renderer.setParameter("paletteLevels", job.temporalPaletteLevels);
                yield renderer;
            }
            case HEX_MOSAIC -> {
                HexMosaicRenderer renderer = new HexMosaicRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("cellSize", job.hexCellSize);
                renderer.setParameter("quantizationLevels", job.hexQuantizationLevels);
                renderer.setParameter("outlineStrength", job.hexOutlineStrength);
                renderer.setParameter("edgeAware", job.hexEdgeAware);
                renderer.setParameter("distanceScaling", job.hexDistanceScaling);
                renderer.setParameter("debugCells", job.hexDebugCells);
                renderer.setParameter("wowMode", job.hexWowMode);
                renderer.setParameter("wowStrength", job.hexWowStrength);
                yield renderer;
            }
            case RAY_TRACING -> createRayTracingRenderer(job, w, h);
            case PATH_TRACING -> createPathTracingRenderer(job, w, h);
            default -> {
                RasterRenderer renderer = new RasterRenderer();
                renderer.init(w, h);
                renderer.setParameter("parallel", true);
                renderer.setParameter("workerCount", job.workerCount);
                renderer.setParameter("frustumCulling", job.frustumCulling);
                renderer.setParameter("backfaceCulling", job.backfaceCulling);
                renderer.setParameter("unlitMode", false);
                yield renderer;
            }
        };
    }

    private RayTracerRenderer createRayTracingRenderer(OutputRenderJob job, int width, int height) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(width, height);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", false);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("workerCount", job.workerCount);
        renderer.setParameter("tileSize", job.tileSize);
        renderer.setParameter("samplesPerFrame", job.samplesPerStep);
        renderer.setParameter("denoiseCadenceSamples", resolveOutputDenoiseCadenceSamples(job));
        renderer.setParameter("maxDepth", job.maxDepth);
        renderer.setParameter("directLighting", job.directLighting);
        renderer.setParameter("shadows", job.shadows);
        renderer.setParameter("reflections", job.reflections);
        renderer.setParameter("sky", job.sky);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", ProgressiveRenderDefaults.OUTPUT_RAY_ADAPTIVE_MIN_SAMPLES);
        renderer.setParameter("adaptiveThreshold", ProgressiveRenderDefaults.OUTPUT_RAY_ADAPTIVE_THRESHOLD);
        renderer.setParameter("denoise", job.denoise);
        renderer.setParameter("denoiseRadius", job.denoiseRadius);
        renderer.setParameter("denoiseStrength", job.denoiseStrength);
        renderer.setParameter("denoiseProfile", "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", "FULL_FRAME");
        renderer.setParameter("denoiseTilePreset", "QUALITY");
        renderer.setParameter("toneMap", job.toneMap);
        renderer.setParameter("reset", true);
        return renderer;
    }

    private PathTracerRenderer createPathTracingRenderer(OutputRenderJob job, int width, int height) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(width, height);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", false);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("workerCount", job.workerCount);
        renderer.setParameter("tileSize", job.tileSize);
        renderer.setParameter("samplesPerFrame", job.samplesPerStep);
        renderer.setParameter("denoiseCadenceSamples", resolveOutputDenoiseCadenceSamples(job));
        renderer.setParameter("maxDepth", job.maxDepth);
        renderer.setParameter("directLighting", job.directLighting);
        renderer.setParameter("sky", job.sky);
        renderer.setParameter("referenceMode", job.referencePathMode);
        renderer.setParameter("historyFireflyClamp", !job.referencePathMode);
        renderer.setParameter("adaptiveSampling", true);
        renderer.setParameter("adaptiveMinSamples", ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_MIN_SAMPLES);
        renderer.setParameter("adaptiveThreshold", ProgressiveRenderDefaults.OUTPUT_PATH_ADAPTIVE_THRESHOLD);
        renderer.setParameter("denoise", job.denoise);
        renderer.setParameter("denoiseRadius", job.denoiseRadius);
        renderer.setParameter("denoiseStrength", job.denoiseStrength);
        renderer.setParameter("denoiseProfile", "QUALITY");
        renderer.setParameter("denoiseRuntimeMode", "FULL_FRAME");
        renderer.setParameter("denoiseTilePreset", "QUALITY");
        renderer.setParameter("toneMap", job.toneMap);
        renderer.setParameter("clampDirect", job.pathClampDirect);
        renderer.setParameter("clampIndirect", job.pathClampIndirect);
        renderer.setParameter("referenceClamp", job.referenceClampEnabled);
        renderer.setParameter("reset", true);
        return renderer;
    }

    private void finalizeProgressiveOutput(Renderer renderer,
                                           Scene scene,
                                           Camera renderCamera,
                                           FrameBuffer outFb,
                                           double renderTimeSeconds) {
        if (renderer == null || scene == null || renderCamera == null || outFb == null || renderCancelRequested) {
            return;
        }
        if (renderer instanceof RayTracerRenderer rayRenderer) {
            if (rayRenderer.getAccumulatedSamples() <= 0L) {
                return;
            }
            rayRenderer.setParameter("forceFullDenoiseResolve", true);
            rayRenderer.setParameter("resolveOnly", true);
            rayRenderer.render(scene, renderCamera, outFb, renderTimeSeconds);
            return;
        }
        if (renderer instanceof PathTracerRenderer pathRenderer) {
            if (pathRenderer.getAccumulatedSamples() <= 0L) {
                return;
            }
            pathRenderer.setParameter("forceFullDenoiseResolve", true);
            pathRenderer.setParameter("resolveOnly", true);
            pathRenderer.render(scene, renderCamera, outFb, renderTimeSeconds);
        }
    }

    private int resolveOutputDenoiseCadenceSamples(OutputRenderJob job) {
        if (job == null || !job.denoise || !OutputRenderSupport.isSampleBasedMode(job.mode)) {
            return 1;
        }
        long pixels = Math.max(1L, (long) job.internalWidth * (long) job.internalHeight);
        int cadence = job.mode == RenderMode.PATH_TRACING ? 4 : 3;
        if (pixels >= 160L * 90L) {
            cadence++;
        }
        if (pixels >= 256L * 144L) {
            cadence++;
        }
        if (job.workerCount >= 16) {
            cadence++;
        }
        return Math.max(1, Math.min(Math.max(1, job.targetSamples), cadence));
    }

    private long computeInitialProgressTarget(OutputRenderJob job) {
        if (job == null) {
            return 1L;
        }
        int frameCount = Math.max(1, job.frameCount);
        if (OutputRenderSupport.isSampleBasedMode(job.mode)) {
            return Math.max(1L, (long) frameCount * Math.max(1, job.targetSamples));
        }
        return Math.max(1L, frameCount);
    }

    private String validateResourceBudget(OutputRenderJob job) {
        if (job == null) {
            return "Output render rejected: missing job settings.";
        }
        int internalW = job.internalWidth;
        int internalH = job.internalHeight;
        long estimatedBytes = estimateWorkingSetBytes(job, internalW, internalH);
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        long headroomBytes = Math.max(0L, runtime.maxMemory() - usedBytes);
        long reserveBytes = Math.max(256L << 20, runtime.maxMemory() / 5L);
        if (estimatedBytes + reserveBytes > headroomBytes) {
            return "Output render rejected: estimated working set " + OutputRenderSupport.formatBytes(estimatedBytes)
                    + ", safe heap headroom " + OutputRenderSupport.formatBytes(Math.max(0L, headroomBytes - reserveBytes))
                    + ". Lower output size, internal scale or use a lighter renderer.";
        }
        return null;
    }

    private long estimateWorkingSetBytes(OutputRenderJob job, int internalW, int internalH) {
        long internalPixels = Math.max(1L, (long) internalW * (long) internalH);
        long outputPixels = Math.max(1L, (long) job.width * (long) job.height);
        long baseFrameBytes = internalPixels * 8L;
        long previewBytes = internalPixels * 4L;
        long encodedImageBytes = (internalPixels + outputPixels) * 4L;
        long rendererBytes = switch (job.mode) {
            case RAY_TRACING, PATH_TRACING -> internalPixels * 64L;
            case DITHERING, TEMPORAL_NOISE, HEX_MOSAIC, WIREFRAME -> internalPixels * 16L;
            default -> internalPixels * 12L;
        };
        return baseFrameBytes + previewBytes + encodedImageBytes + rendererBytes + (32L << 20);
    }

    private double outputElapsedSeconds() {
        if (renderStartNanos <= 0L) {
            return 0.0;
        }
        return (System.nanoTime() - renderStartNanos) / 1_000_000_000.0;
    }

    private double outputActiveElapsedSeconds() {
        if (renderStartNanos <= 0L) {
            return 0.0;
        }
        long now = System.nanoTime();
        long pausedNanos = renderPausedAccumulatedNanos;
        if (renderPaused && renderPauseStartedNanos > 0L) {
            pausedNanos += Math.max(0L, now - renderPauseStartedNanos);
        }
        return Math.max(0.0, (now - renderStartNanos - pausedNanos) / 1_000_000_000.0);
    }

    private double averageUnitsPerSecond() {
        double activeElapsed = outputActiveElapsedSeconds();
        if (activeElapsed <= 1e-6 || progressCurrent <= 0L) {
            return 0.0;
        }
        return progressCurrent / activeElapsed;
    }

    private double effectiveUnitsPerSecond() {
        double recentRate = progressUnitsPerSecondSmoothed;
        double averageRate = averageUnitsPerSecond();
        if (recentRate > 1e-6 && averageRate > 1e-6) {
            return recentRate * 0.68 + averageRate * 0.32;
        }
        return Math.max(recentRate, averageRate);
    }

    private double smoothedRemainingSeconds() {
        long remainingUnits = Math.max(0L, progressTarget - progressCurrent);
        if (remainingUnits <= 0L) {
            return 0.0;
        }
        double effectiveRate = effectiveUnitsPerSecond();
        if (effectiveRate > 1e-6) {
            return remainingUnits / effectiveRate;
        }
        double activeElapsed = outputActiveElapsedSeconds();
        if (progressCurrent <= 0L || activeElapsed <= 1e-6) {
            return 0.0;
        }
        return activeElapsed * remainingUnits / Math.max(1L, progressCurrent);
    }

    private void noteProgressAdvance(long current) {
        long now = System.nanoTime();
        long sampleNanos = progressAdvanceSampleNanos;
        long sampleUnits = progressAdvanceSampleUnits;
        if (sampleNanos <= 0L) {
            progressAdvanceSampleNanos = now;
            progressAdvanceSampleUnits = current;
            return;
        }
        long deltaUnits = current - sampleUnits;
        long deltaNanos = now - sampleNanos;
        if (deltaUnits <= 0L || deltaNanos <= 0L) {
            return;
        }
        long unitNanos = Math.max(1L, Math.round(deltaNanos / (double) deltaUnits));
        if (progressFastestUnitNanos <= 0L || unitNanos < progressFastestUnitNanos) {
            progressFastestUnitNanos = unitNanos;
        }
        if (unitNanos > progressSlowestUnitNanos) {
            progressSlowestUnitNanos = unitNanos;
        }
        progressInstantUnitsPerSecond = deltaUnits / (deltaNanos / 1_000_000_000.0);
        progressAdvanceSampleNanos = now;
        progressAdvanceSampleUnits = current;
    }

    private void refreshProgressRate(long current) {
        long now = System.nanoTime();
        long sampleNanos = progressRateSampleNanos;
        long sampleUnits = progressRateSampleUnits;
        if (sampleNanos <= 0L) {
            progressRateSampleNanos = now;
            progressRateSampleUnits = current;
            return;
        }
        long deltaNanos = now - sampleNanos;
        long deltaUnits = current - sampleUnits;
        if (deltaUnits <= 0L || deltaNanos < 180_000_000L) {
            return;
        }
        double instantaneousUnitsPerSecond = deltaUnits / (deltaNanos / 1_000_000_000.0);
        if (instantaneousUnitsPerSecond > 1e-6) {
            if (progressUnitsPerSecondSmoothed <= 1e-6) {
                progressUnitsPerSecondSmoothed = instantaneousUnitsPerSecond;
            } else {
                progressUnitsPerSecondSmoothed = progressUnitsPerSecondSmoothed * 0.78
                        + instantaneousUnitsPerSecond * 0.22;
            }
        }
        progressRateSampleNanos = now;
        progressRateSampleUnits = current;
    }

    private void waitWhilePaused(OutputRenderJob job, String phaseLabel) {
        if (!renderPaused) {
            return;
        }
        while (!renderCancelRequested && renderPaused) {
            updateProgress(job, progressCurrent, progressTarget, phaseLabel + " | Pozastaveno");
            try {
                Thread.sleep(PAUSE_WAIT_SLICE_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                renderCancelRequested = true;
                return;
            }
        }
        progressRateSampleNanos = System.nanoTime();
        progressRateSampleUnits = progressCurrent;
        progressAdvanceSampleNanos = progressRateSampleNanos;
        progressAdvanceSampleUnits = progressCurrent;
    }

    private void updateProgress(OutputRenderJob job, long current, long total, String phase) {
        progressCurrent = Math.max(0L, current);
        progressTarget = Math.max(1L, total);
        progressMessage = phase == null ? "" : phase;
        if (!renderPaused) {
            noteProgressAdvance(progressCurrent);
            refreshProgressRate(progressCurrent);
        }
        long now = System.nanoTime();
        boolean force = progressCurrent <= 1L || progressCurrent >= progressTarget;
        if (force || now - progressUiLastUpdateNanos >= UI_PROGRESS_UPDATE_MIN_NS) {
            progressUiLastUpdateNanos = now;
            updateProgressDialog(progressMessage, progressCurrent, progressTarget);
        }
    }

    private void openProgressDialog(OutputRenderJob job) {
        activeViewportPreviewJob = job;
        viewportPreviewLastPublishNanos = 0L;
        viewportPreviewPixels.set(new int[]{0xFF050608});
        viewportPreviewWidth = 1;
        viewportPreviewHeight = 1;
        PreviewState state = buildViewportPreviewState(job);
        viewportPreviewState.set(state);
        viewportPreviewLines.set(buildViewportPreviewLines(state));
    }

    private void updateProgressDialog(String message, long current, long total) {
        if (message != null && !message.isBlank()) {
            progressMessage = message;
        }
        progressCurrent = Math.max(0L, current);
        progressTarget = Math.max(1L, total);
        PreviewState state = buildViewportPreviewState(activeViewportPreviewJob);
        viewportPreviewState.set(state);
        viewportPreviewLines.set(buildViewportPreviewLines(state));
    }

    private void finishProgressDialog(String status) {
        progressMessage = status;
        PreviewState state = buildViewportPreviewState(activeViewportPreviewJob);
        viewportPreviewState.set(state);
        viewportPreviewLines.set(buildViewportPreviewLines(state));
    }

    private void publishViewportPreview(OutputRenderJob job, FrameBuffer outFb, boolean force) {
        if (job == null || outFb == null) {
            return;
        }
        long now = System.nanoTime();
        if (!force && now - viewportPreviewLastPublishNanos < VIEWPORT_PREVIEW_INTERVAL_NS) {
            return;
        }
        viewportPreviewLastPublishNanos = now;
        activeViewportPreviewJob = job;
        viewportPreviewWidth = Math.max(1, outFb.getWidth());
        viewportPreviewHeight = Math.max(1, outFb.getHeight());
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.BYTES_COPIED,
                (long) viewportPreviewWidth * (long) viewportPreviewHeight * 4L);
        viewportPreviewPixels.set(outFb.getColorBuffer().clone());
        PreviewState state = buildViewportPreviewState(job);
        viewportPreviewState.set(state);
        viewportPreviewLines.set(buildViewportPreviewLines(state));
    }

    private void clearViewportPreview() {
        viewportPreviewPixels.set(new int[]{0xFF050608});
        viewportPreviewWidth = 1;
        viewportPreviewHeight = 1;
        viewportPreviewLines.set(new String[0]);
        viewportPreviewState.set(null);
        viewportPreviewLastPublishNanos = 0L;
        activeViewportPreviewJob = null;
        progressFrameNumber = 0;
        progressFrameIndex = -1;
        progressFrameCount = 0;
        progressFramesCompleted = 0;
        progressCurrentFile = "";
        progressOutputFolder = "";
        progressRateSampleNanos = 0L;
        progressRateSampleUnits = 0L;
        progressUnitsPerSecondSmoothed = 0.0;
        progressAdvanceSampleNanos = 0L;
        progressAdvanceSampleUnits = 0L;
        progressInstantUnitsPerSecond = 0.0;
        progressFastestUnitNanos = 0L;
        progressSlowestUnitNanos = 0L;
    }

    private PreviewState buildViewportPreviewState(OutputRenderJob job) {
        if (job == null) {
            return new PreviewState(
                    "Připravuji render",
                    "0 %",
                    "Běží 0.0s · ETA 0.0s",
                    0.0,
                    renderPaused,
                    renderInProgress,
                    false);
        }
        double pct = (double) progressCurrent / Math.max(1L, progressTarget) * 100.0;
        double activeElapsedSeconds = outputActiveElapsedSeconds();
        double remainingSeconds = smoothedRemainingSeconds();
        String headline = buildPreviewHeadline(job);
        String progressText = buildPreviewProgressText(job, pct);
        String metricsText = buildPreviewMetricsText(job, activeElapsedSeconds, remainingSeconds);
        return new PreviewState(
                headline,
                progressText,
                metricsText,
                Math.max(0.0, Math.min(1.0, progressCurrent / (double) Math.max(1L, progressTarget))),
                renderPaused,
                renderInProgress,
                renderInProgress);
    }

    private String buildPreviewHeadline(OutputRenderJob job) {
        if (job.requestType == OutputRenderRequestType.STILL) {
            return UiStrings.Output.STILL_IMAGE;
        }
        return "Snímek " + progressFrameNumber
                + " · " + (Math.max(0, progressFrameIndex) + 1)
                + " / " + Math.max(1, progressFrameCount);
    }

    private String buildPreviewProgressText(OutputRenderJob job, double pct) {
        if (OutputRenderSupport.isSampleBasedMode(job.mode)) {
            long frameBase = job.requestType == OutputRenderRequestType.STILL
                    ? 0L
                    : (long) Math.max(0, progressFrameIndex) * Math.max(1L, job.targetSamples);
            long frameSamples = Math.max(0L, progressCurrent - frameBase);
            long frameTarget = Math.max(1L, job.targetSamples);
            return String.format(
                    Locale.ROOT,
                    "%.1f %% · %d / %d vzorků%s",
                    pct,
                    Math.min(frameTarget, frameSamples),
                    frameTarget,
                    renderPaused ? " · pozastaveno" : "");
        }
        if (job.requestType == OutputRenderRequestType.STILL) {
            return String.format(Locale.ROOT, "%.1f %% · dokončuji snímek%s", pct, renderPaused ? " · pozastaveno" : "");
        }
        return String.format(
                Locale.ROOT,
                "%.1f %% · %d / %d snímků%s",
                pct,
                Math.min(Math.max(0, progressFramesCompleted), Math.max(1, progressFrameCount)),
                Math.max(1, progressFrameCount),
                renderPaused ? " · pozastaveno" : "");
    }

    private String buildPreviewMetricsText(OutputRenderJob job, double activeElapsedSeconds, double remainingSeconds) {
        String unit = progressUnitLabel(job);
        String averageRate = formatRate(averageUnitsPerSecond(), unit);
        String instantRate = formatRate(resolveDisplayedInstantRate(), unit);
        String fastest = formatUnitDuration(progressFastestUnitNanos, unit);
        String slowest = formatUnitDuration(progressSlowestUnitNanos, unit);
        return "Běží " + OutputRenderSupport.formatDuration(activeElapsedSeconds)
                + " · ETA " + OutputRenderSupport.formatDuration(remainingSeconds)
                + " · Průměr " + averageRate
                + " · Aktuálně " + instantRate
                + " · Nejrychlejší " + fastest
                + " · Nejpomalejší " + slowest;
    }

    private double resolveDisplayedInstantRate() {
        double instant = progressInstantUnitsPerSecond;
        if (instant > 1e-6) {
            return instant;
        }
        return progressUnitsPerSecondSmoothed;
    }

    private String progressUnitLabel(OutputRenderJob job) {
        if (job == null) {
            return "jedn";
        }
        if (OutputRenderSupport.isSampleBasedMode(job.mode)) {
            return "vz";
        }
        return job.frameCount <= 1 ? "render" : "sn";
    }

    private String formatRate(double unitsPerSecond, String unit) {
        if (!Double.isFinite(unitsPerSecond) || unitsPerSecond <= 1e-6) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.2f %s/s", unitsPerSecond, unit);
    }

    private String formatUnitDuration(long unitNanos, String unit) {
        if (unitNanos <= 0L) {
            return "-";
        }
        double unitMs = unitNanos / 1_000_000.0;
        if (unitMs < 1000.0) {
            return String.format(Locale.ROOT, "%.0f ms/%s", unitMs, unit);
        }
        return OutputRenderSupport.formatDuration(unitMs / 1000.0) + "/" + unit;
    }

    private String[] buildViewportPreviewLines(PreviewState state) {
        if (state == null) {
            return new String[0];
        }
        return new String[]{
                "RENDER: " + safeText(state.headline),
                "PROGRES: " + safeText(state.progressText),
                "METRIKY: " + safeText(state.metricsText)
        };
    }

    private OutputRenderJob buildPreviewJob() {
        OutputRenderPendingRequest request = new OutputRenderPendingRequest();
        request.type = OutputRenderSupport.requestTypeFromExportType(settings.exportType);
        request.outputFormat = settings.format;
        request.frameStart = settings.frameStart;
        request.frameEnd = settings.frameEnd;
        request.frameRate = settings.frameRate;
        return buildOutputRenderJob(request, false, false);
    }

    private static String safeText(String value) {
        return value == null ? "" : value;
    }

}
