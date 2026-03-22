package engine.core;

import java.awt.Component;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

import engine.camera.Camera;
import engine.camera.CameraController;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.core.history.EditorCommand;
import engine.core.history.HistoryTransaction;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.AABB;
import engine.math.Mat4;
import engine.math.Ray;
import engine.math.Vec3;
import engine.math.Vec4;
import engine.physics.AABBCollider;
import engine.physics.PhysicsWorld;
import engine.render.FrameBuffer;
import engine.render.PostProcessor;
import engine.render.RenderPipeline;
import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.HexMosaicRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.post.WireframeRenderer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.ProgressiveRenderDefaults;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.sim.water.WaterSimulation;
import engine.util.BitFont;
import engine.util.RayIntersectionUtil;
import engine.util.RuntimeInstrumentation;
import engine.util.ThreadPool;
import engine.util.UiBuilder;

/**
 * Represents centrální řídicí třídu enginu.
 */
public class Engine {

    static final String WINDOW_TITLE = "3D Render Physics - FPS/Blender Controls";
    static final long DOUBLE_CLICK_THRESHOLD_NS = 360_000_000L;
    static final int ACTION_HISTORY_LIMIT = 64;
    static final double HISTORY_EPS = 1e-6;

    enum TransformMode { NONE, MOVE, ROTATE, SCALE }
    enum AxisConstraint { NONE, X, Y, Z }
    enum NavigationPreset { FPS, BLENDER }
    enum SelectionViewMode { FRAME_AND_FOCUS, SELECT_ONLY }
    enum BottomDockWorkspace { TIMELINE, MATERIAL }
    enum ForceFieldType { VECTOR, POINT, TURBULENCE }
    enum SceneItemType { ENTITY, LIGHT, FORCE_FIELD, WORLD }

    static class SceneItemState {
        boolean visibleInView;
        boolean visibleInOutput;

        SceneItemState() {
            this.visibleInView = true;
            this.visibleInOutput = true;
        }
    }

    static class ForceField {
        String name;
        ForceFieldType type;
        Vec3 position;
        Vec3 direction;
        double strength;
        boolean attract;
        double radius;
        double turbulenceScale;
        double seed;
    }

    static class SceneItemRef {
        SceneItemType type;
        Entity entity;
        Light light;
        ForceField forceField;
    }

    static class OverlayIcon {
        SceneItemRef ref;
        int x;
        int y;
        int radius;
        double depth;
    }

    static class GizmoAxisData {
        AxisConstraint axis;
        int color;
        boolean valid;
        int endX;
        int endY;
        int moveX;
        int moveY;
        int scaleX;
        int scaleY;
        int rotateX;
        int rotateY;
        int rotateRadius;
    }

    static class GizmoScreenData {
        boolean valid;
        int centerX;
        int centerY;
        GizmoAxisData[] axes;
    }

    Window window;
    Scene scene;
    Camera camera;
    PerspectiveCamera perspectiveCamera;
    OrthographicCamera orthographicCamera;
    boolean orthographicProjection;
    CameraController cameraController;
    FrameBuffer frameBuffer;
    Input input;
    EditorShortcutRouter editorShortcutRouter;
    PhysicsWorld physicsWorld;

    RasterRenderer rasterRenderer;
    DitherRenderer ditherRenderer;
    TemporalNoiseRenderer temporalNoiseRenderer;
    WireframeRenderer wireframeRenderer;
    HexMosaicRenderer hexMosaicRenderer;
    RayTracerRenderer rayTracerRenderer;
    PathTracerRenderer pathTracerRenderer;
    Renderer activeRenderer;
    RenderMode activeMode;
    RenderPipeline renderPipeline;
    PostProcessor postAAPostProcessor;

    Entity demoEntity;
    Entity floorEntity;
    Entity outputCameraEntity;
    Entity selectedEntity;
    Light selectedLight;
    ForceField selectedForceField;
    DirectionalLight sunLight;
    DirectionalLight fillLight;
    PointLight warmWorldLight;
    PointLight coolWorldLight;
    Vec3 worldLightColor;
    Vec3 worldBackgroundColor;
    String worldPresetKey;
    double worldEnvironmentYawDegrees;
    double worldEnvironmentPitchDegrees;
    double worldSunBaseIntensity;
    double worldFillBaseIntensity;
    double worldWarmBaseIntensity;
    double worldCoolBaseIntensity;
    double worldLightStrength;
    double worldLightAppliedStrength;
    boolean worldLightAnimationEnabled;
    final WaterSimulation waterSimulation;
    boolean waterSimulationEnabled;
    boolean waterRenderEnabled;
    double waterCollisionFloorY;

    boolean running;
    boolean physicsEnabled;
    boolean autoRotateDemo;
    boolean frustumCullingEnabled;
    boolean backfaceCullingEnabled;
    boolean parallelRasterEnabled;
    int parallelWorkerCount;
    boolean addMenuActive;
    TransformMode transformMode;
    AxisConstraint axisConstraint;
    int spawnCounter;
    final Random random;
    Robot mouseRobot;
    boolean mouseCaptured;
    boolean viewportContextMenuRecapturePending;
    boolean objectFocusMode;
    boolean draggingSelectedObject;
    boolean pendingSelectedObjectDrag;
    int pendingDragStartMouseX;
    int pendingDragStartMouseY;
    boolean captureSelectLatch;
    boolean gizmoDragActive;
    double renderScale;
    boolean progressiveViewportEnabled;
    double interactiveRenderScale;
    boolean interactiveRenderScaleActive;
    boolean safetyMonitorEnabled;
    boolean safetyRecoveryActive;
    String safetyRecoveryReason;
    long safetyRecoveryUntilNanos;
    long safetyLastRecoveryNanos;
    int safetyRecoveryCount;
    int safetySevereFrameStreak;
    double safetyViewportScaleClamp;
    long lastViewportInteractionNanos;
    double viewportTargetFps;
    double viewportSmoothedFrameMs;
    double viewportFastFrameMs;
    double viewportPredictedFrameMs;
    double viewportHeavySmoothedFrameMs;
    double viewportHeavyFastFrameMs;
    double viewportHeavyPredictedFrameMs;
    long viewportLastHeavyFrameNanos;
    int viewportFrameDropStreak;
    double viewportAdaptiveScaleCurrent;
    double viewportAdaptiveScaleApplied;
    double viewportScalePressureSeconds;
    double viewportCriticalPressureSeconds;
    long viewportCriticalPreviewStartNanos;
    long viewportCriticalPreviewHoldUntilNanos;
    long viewportCriticalRecoverStartNanos;
    boolean viewportCriticalPreviewActive;
    long viewportWarmupUntilNanos;
    boolean viewportWarmupCaptureActive;
    int viewportWarmupSampleCount;
    int viewportWarmupSampleWriteIndex;
    double[] viewportWarmupFrameSamples;
    double viewportWarmupSeedMs;
    double viewportWarmupSeedWeight;
    long viewportWarmupSeedExpiresNanos;
    boolean viewportInteractionActiveLast;
    boolean viewDistanceCullingEnabled;
    double viewDistanceLimit;
    boolean viewportNavigationPreviewEnabled;
    boolean viewportNavigationPreviewActive;
    boolean viewportCameraMotionActive;
    boolean viewportSceneMotionActive;
    boolean viewportMotionLatchedActive;
    long viewportMotionEntryBoostUntilNanos;
    long viewportMotionHoldUntilNanos;
    int viewportMotionExitStableFrames;
    boolean viewportFallbackLockActive;
    boolean launchFullscreen;
    RenderMode viewportNavigationFallbackMode;
    RenderMode viewportDisplayedMode;
    boolean renderModeSwitchTransitionActive;
    String renderModeSwitchTargetLabel;
    long renderModeSwitchStartNanos;
    long renderModeSwitchRevealStartNanos;
    long renderModeSwitchSampleBaseline;
    String viewportAutoPolicyTier;
    double viewportAutoOverloadRatio;
    int viewportDynamicResolutionTierIndex;
    long viewportDynamicResolutionLastSwitchNanos;
    int viewportDynamicResolutionSwitchCount;
    int viewportDynamicResolutionDownshiftCount;
    int viewportDynamicResolutionUpshiftCount;
    int viewportDynamicResolutionDownshiftArmFrames;
    int viewportDynamicResolutionRecoverStableFrames;
    int viewportDynamicResolutionRecoverSampleGateFrames;
    int viewportDynamicDecisionWindowCount;
    int viewportDynamicDecisionWindowIndex;
    double viewportDynamicDecisionWindowSumMs;
    double viewportDynamicDecisionWindowMeanMs;
    double[] viewportDynamicDecisionWindowSamplesMs;
    int viewportUpshiftEvaluationFrames;
    int viewportUpshiftBlockedByDwellCount;
    int viewportUpshiftBlockedByArmCount;
    int viewportUpshiftBlockedByOverloadCount;
    int viewportUpshiftBlockedByFrameDropCount;
    int viewportUpshiftBlockedByCriticalPressureCount;
    int viewportUpshiftBlockedByScalePressureCount;
    int viewportUpshiftBlockedByQualityTierCount;
    int viewportUpshiftBlockedBySampleGateCount;
    double viewportUpshiftLastOverloadRatio;
    double viewportUpshiftLastOverloadThreshold;
    int viewportUpshiftLastSampleCount;
    int viewportUpshiftLastSampleGate;
    String viewportUpshiftLastQualityTier;
    boolean viewportUpshiftLastMotionActive;
    String viewportUpshiftLastBlockReason;
    long startupAppStartNanos;
    long startupWindowCreatedNanos;
    long startupFirstRenderedFrameNanos;
    long startupFirstPresentedFrameNanos;
    long startupFirstInteractiveFrameNanos;
    long startupSplashClosedNanos;
    long startupFocusRequestedNanos;
    long startupFocusAcquiredNanos;
    boolean startupInputReady;
    boolean startupFocusReady;
    double viewportPathGentleMotionSeconds;
    boolean viewportPathDenoiseEnabledApplied;
    String viewportPathDenoiseProfileApplied;
    String viewportPathDenoiseRuntimeModeApplied;
    int baseWidth;
    int baseHeight;
    int explicitPreviewRenderWidth;
    int explicitPreviewRenderHeight;
    int lastCanvasWidth;
    int lastCanvasHeight;
    boolean postAAEnabled;
    int[] postAATemp;
    String loadedModelPath;
    String loadedDiffuseTexturePath;
    boolean smoothUpscaling;
    long escapeExitArmedUntilNanos;
    NavigationPreset navigationPreset;
    SelectionViewMode selectionViewMode;
    CameraController.Mode fpsCameraMode;
    Vec3 savedFpsPosition;
    Vec3 savedFpsForward;
    boolean savedFpsPoseValid;
    Vec3 savedBlendPosition;
    Vec3 savedBlendForward;
    boolean savedBlendPoseValid;
    boolean debugOverlayEnabled;
    boolean editorOverlayEnabled;
    boolean hexDebugCells;
    double hudFps;
    double hudFrameTimeMs;
    long lastSelectionClickNanos;
    Entity lastSelectionClickEntity;
    boolean animationPlaybackEnabled;
    double animatedSceneElapsedSeconds;
    boolean timelineEnabled;
    boolean timelineLoop;
    int timelineStartFrame;
    int timelineEndFrame;
    int timelineCurrentFrame;
    double timelineFps;
    double timelineFrameCursor;
    int lightCounter;
    int forceFieldCounter;
    final List<ForceField> forceFields;
    final SceneTimeline sceneTimeline;
    final IdentityHashMap<Object, SceneItemState> sceneItemStates;
    final IdentityHashMap<Light, String> lightNames;
    final List<SceneItemRef> sceneOutlinerItems;
    boolean suppressSceneOutlinerSelectionEvent;
    boolean suppressSceneDetailRebuild;

    int ditherToneCount;
    double ditherContrast;
    double ditherLightAssist;
    boolean ditherInvert;
    int ditherCellSize;
    String ditherAsciiCharset;

    double temporalTickRate;
    double temporalNearContribution;
    double temporalGrazingContribution;
    double temporalMinSpeed;
    double temporalMaxSpeed;
    double temporalEdgeBlendStrength;
    int temporalGrainCellSize;
    int temporalPaletteLevels;

    double hexCellSizeSetting;
    int hexQuantizationLevels;
    double hexOutlineStrength;
    boolean hexEdgeAware;
    boolean hexDistanceScaling;
    double hexWowStrength;

    int raySamplesPerFrame;
    int rayTileSize;
    int rayMaxDepth;
    int rayDiffuseBounces;
    int rayGlossyBounces;
    int rayTransmissionBounces;
    int rayVolumeBounces;
    int rayTransparentBounces;
    boolean rayDirectLighting;
    boolean rayShadows;
    boolean rayReflections;
    boolean rayDenoise;
    int rayDenoiseRadius;
    double rayDenoiseStrength;
    String rayToneMap;

    int pathSamplesPerFrame;
    int pathTileSize;
    int pathMaxDepth;
    int pathDiffuseBounces;
    int pathGlossyBounces;
    int pathTransmissionBounces;
    int pathVolumeBounces;
    int pathTransparentBounces;
    boolean pathDirectLighting;
    boolean pathSkyEnvironment;
    boolean pathDenoise;
    boolean pathAccumulationLock;
    int pathDenoiseRadius;
    double pathDenoiseStrength;
    double pathClampDirect;
    double pathClampIndirect;
    String pathToneMap;

    final OutputRenderController outputRenderController;
    final SceneImportController sceneImportController;

    JToggleButton lightMouse;
    JToggleButton lightNavFps;
    JToggleButton lightNavBlend;
    JToggleButton lightModel;
    JToggleButton lightBasic;
    JToggleButton lightPhong;
    JToggleButton lightWire;
    JToggleButton lightDither;
    JToggleButton lightAscii;
    JToggleButton lightTemporal;
    JToggleButton lightRay;
    JToggleButton lightPath;
    JToggleButton lightHex;
    JToggleButton lightDebug;
    JToggleButton lightEditor;
    JToggleButton lightProjection;
    JToggleButton lightPhysics;
    JToggleButton lightParallel;
    JToggleButton lightAA;

    JLabel objectHeaderLabel;
    JTextField posXField;
    JTextField posYField;
    JTextField posZField;
    JTextField rotXField;
    JTextField rotYField;
    JTextField rotZField;
    JTextField scaleXField;
    JTextField scaleYField;
    JTextField scaleZField;
    boolean suppressObjectFieldApply;
    DefaultListModel<String> sceneOutlinerModel;
    JList<String> sceneOutlinerList;
    JPanel sceneDetailsPanel;
    JTextField timelineCurrentFrameField;
    JLabel timelineStatusLabel;
    JTextField timelineDockCurrentFrameField;
    JTextField timelineDockStartFrameField;
    JTextField timelineDockEndFrameField;
    JTextField timelineDockFpsField;
    JLabel timelineDockStatusLabel;
    JComponent timelineDockStripComponent;
    BottomDockWorkspace bottomDockWorkspace;
    JPanel bottomDockCardPanel;
    JLabel bottomDockTitleLabel;
    JLabel bottomDockSubtitleLabel;
    JPanel materialDockHostPanel;
    JToggleButton bottomDockTimelineButton;
    JToggleButton bottomDockMaterialButton;
    final MaterialDockViewState materialDockViewState;
    final Deque<EditorCommand> undoHistory;
    final Deque<EditorCommand> redoHistory;
    HistoryTransaction activeHistoryTransaction;
    boolean historyRestoring;

    public Engine() {
        this.outputRenderController = new OutputRenderController();
        this.sceneImportController = new SceneImportController();
        this.materialDockViewState = new MaterialDockViewState();
        this.physicsEnabled = true;
        this.autoRotateDemo = false;
        this.frustumCullingEnabled = true;
        this.backfaceCullingEnabled = false;
        this.parallelRasterEnabled = true;
        this.parallelWorkerCount = ThreadPool.recommendedWorkerCount();
        this.addMenuActive = false;
        this.transformMode = TransformMode.NONE;
        this.axisConstraint = AxisConstraint.NONE;
        this.spawnCounter = 0;
        this.random = new Random(42L);
        this.orthographicProjection = false;
        this.mouseCaptured = false;
        this.viewportContextMenuRecapturePending = false;
        this.objectFocusMode = false;
        this.draggingSelectedObject = false;
        this.pendingSelectedObjectDrag = false;
        this.pendingDragStartMouseX = 0;
        this.pendingDragStartMouseY = 0;
        this.captureSelectLatch = false;
        this.gizmoDragActive = false;
        this.renderScale = 1.00;
        this.progressiveViewportEnabled = true;
        this.interactiveRenderScale = 0.80;
        this.interactiveRenderScaleActive = false;
        this.safetyMonitorEnabled = true;
        this.safetyRecoveryActive = false;
        this.safetyRecoveryReason = "";
        this.safetyRecoveryUntilNanos = 0L;
        this.safetyLastRecoveryNanos = 0L;
        this.safetyRecoveryCount = 0;
        this.safetySevereFrameStreak = 0;
        this.safetyViewportScaleClamp = 1.0;
        this.lastViewportInteractionNanos = 0L;
        this.viewportTargetFps = 25.0;
        this.viewportSmoothedFrameMs = 1000.0 / this.viewportTargetFps;
        this.viewportFastFrameMs = this.viewportSmoothedFrameMs;
        this.viewportPredictedFrameMs = this.viewportSmoothedFrameMs;
        this.viewportHeavySmoothedFrameMs = this.viewportSmoothedFrameMs;
        this.viewportHeavyFastFrameMs = this.viewportSmoothedFrameMs;
        this.viewportHeavyPredictedFrameMs = this.viewportSmoothedFrameMs;
        this.viewportLastHeavyFrameNanos = 0L;
        this.viewportFrameDropStreak = 0;
        this.viewportAdaptiveScaleCurrent = 1.0;
        this.viewportAdaptiveScaleApplied = 1.0;
        this.viewportScalePressureSeconds = 0.0;
        this.viewportCriticalPressureSeconds = 0.0;
        this.viewportCriticalPreviewStartNanos = 0L;
        this.viewportCriticalPreviewHoldUntilNanos = 0L;
        this.viewportCriticalRecoverStartNanos = 0L;
        this.viewportCriticalPreviewActive = false;
        this.viewportWarmupUntilNanos = 0L;
        this.viewportWarmupCaptureActive = false;
        this.viewportWarmupSampleCount = 0;
        this.viewportWarmupSampleWriteIndex = 0;
        this.viewportWarmupFrameSamples = new double[16];
        this.viewportWarmupSeedMs = this.viewportSmoothedFrameMs;
        this.viewportWarmupSeedWeight = 0.0;
        this.viewportWarmupSeedExpiresNanos = 0L;
        this.viewportInteractionActiveLast = false;
        this.viewDistanceCullingEnabled = false;
        this.viewDistanceLimit = 120.0;
        this.viewportNavigationPreviewEnabled = true;
        this.viewportNavigationPreviewActive = false;
        this.viewportCameraMotionActive = false;
        this.viewportSceneMotionActive = false;
        this.viewportMotionLatchedActive = false;
        this.viewportMotionEntryBoostUntilNanos = 0L;
        this.viewportMotionHoldUntilNanos = 0L;
        this.viewportMotionExitStableFrames = 0;
        this.viewportFallbackLockActive = false;
        this.launchFullscreen = false;
        this.viewportNavigationFallbackMode = RenderMode.MODEL;
        this.viewportDisplayedMode = RenderMode.PHONG;
        this.renderModeSwitchTransitionActive = false;
        this.renderModeSwitchTargetLabel = "";
        this.renderModeSwitchStartNanos = 0L;
        this.renderModeSwitchRevealStartNanos = 0L;
        this.renderModeSwitchSampleBaseline = -1L;
        this.viewportAutoPolicyTier = "BALANCED";
        this.viewportAutoOverloadRatio = 1.0;
        this.viewportDynamicResolutionTierIndex = 0;
        this.viewportDynamicResolutionLastSwitchNanos = 0L;
        this.viewportDynamicResolutionSwitchCount = 0;
        this.viewportDynamicResolutionDownshiftCount = 0;
        this.viewportDynamicResolutionUpshiftCount = 0;
        this.viewportDynamicResolutionDownshiftArmFrames = 0;
        this.viewportDynamicResolutionRecoverStableFrames = 0;
        this.viewportDynamicResolutionRecoverSampleGateFrames = 0;
        this.viewportDynamicDecisionWindowCount = 0;
        this.viewportDynamicDecisionWindowIndex = 0;
        this.viewportDynamicDecisionWindowSumMs = 0.0;
        this.viewportDynamicDecisionWindowMeanMs = this.viewportSmoothedFrameMs;
        this.viewportDynamicDecisionWindowSamplesMs = new double[30];
        this.viewportUpshiftEvaluationFrames = 0;
        this.viewportUpshiftBlockedByDwellCount = 0;
        this.viewportUpshiftBlockedByArmCount = 0;
        this.viewportUpshiftBlockedByOverloadCount = 0;
        this.viewportUpshiftBlockedByFrameDropCount = 0;
        this.viewportUpshiftBlockedByCriticalPressureCount = 0;
        this.viewportUpshiftBlockedByScalePressureCount = 0;
        this.viewportUpshiftBlockedByQualityTierCount = 0;
        this.viewportUpshiftBlockedBySampleGateCount = 0;
        this.viewportUpshiftLastOverloadRatio = 1.0;
        this.viewportUpshiftLastOverloadThreshold = 0.0;
        this.viewportUpshiftLastSampleCount = 0;
        this.viewportUpshiftLastSampleGate = 0;
        this.viewportUpshiftLastQualityTier = "";
        this.viewportUpshiftLastMotionActive = false;
        this.startupAppStartNanos = 0L;
        this.startupWindowCreatedNanos = 0L;
        this.startupFirstRenderedFrameNanos = 0L;
        this.startupFirstPresentedFrameNanos = 0L;
        this.startupFirstInteractiveFrameNanos = 0L;
        this.startupSplashClosedNanos = 0L;
        this.startupFocusRequestedNanos = 0L;
        this.startupFocusAcquiredNanos = 0L;
        this.startupInputReady = false;
        this.startupFocusReady = false;
        this.viewportPathGentleMotionSeconds = 0.0;
        this.viewportPathDenoiseEnabledApplied = true;
        this.viewportPathDenoiseProfileApplied = "QUALITY";
        this.viewportPathDenoiseRuntimeModeApplied = "FULL_FRAME";
        this.baseWidth = 1200;
        this.baseHeight = 760;
        this.explicitPreviewRenderWidth = 0;
        this.explicitPreviewRenderHeight = 0;
        this.lastCanvasWidth = this.baseWidth;
        this.lastCanvasHeight = this.baseHeight;
        this.postAAEnabled = false;
        this.postAATemp = new int[0];
        this.loadedModelPath = null;
        this.loadedDiffuseTexturePath = null;
        this.smoothUpscaling = false;
        this.escapeExitArmedUntilNanos = 0L;
        this.navigationPreset = NavigationPreset.FPS;
        this.selectionViewMode = SelectionViewMode.SELECT_ONLY;
        this.fpsCameraMode = CameraController.Mode.FREE_LOOK;
        this.savedFpsPosition = null;
        this.savedFpsForward = null;
        this.savedFpsPoseValid = false;
        this.savedBlendPosition = null;
        this.savedBlendForward = null;
        this.savedBlendPoseValid = false;
        this.debugOverlayEnabled = true;
        this.editorOverlayEnabled = true;
        this.hexDebugCells = false;
        this.hudFps = 0.0;
        this.hudFrameTimeMs = 0.0;
        this.lastSelectionClickNanos = 0L;
        this.lastSelectionClickEntity = null;
        this.animationPlaybackEnabled = true;
        this.animatedSceneElapsedSeconds = 0.0;
        this.timelineEnabled = false;
        this.timelineLoop = true;
        this.timelineStartFrame = 1;
        this.timelineEndFrame = 120;
        this.timelineCurrentFrame = 1;
        this.timelineFps = 24.0;
        this.timelineFrameCursor = 0.0;
        this.lightCounter = 0;
        this.forceFieldCounter = 0;
        this.forceFields = new ArrayList<>();
        this.sceneTimeline = new SceneTimeline();
        this.sceneItemStates = new IdentityHashMap<>();
        this.lightNames = new IdentityHashMap<>();
        this.sceneOutlinerItems = new ArrayList<>();
        this.suppressSceneOutlinerSelectionEvent = false;
        this.suppressSceneDetailRebuild = false;

        this.outputCameraEntity = null;
        this.selectedLight = null;
        this.selectedForceField = null;
        this.worldLightColor = Vec3.ZERO;
        this.worldBackgroundColor = Vec3.ZERO;
        this.worldPresetKey = WorldPresetCatalog.defaultPreset().key();
        this.worldEnvironmentYawDegrees = 0.0;
        this.worldEnvironmentPitchDegrees = 0.0;
        this.worldSunBaseIntensity = 0.0;
        this.worldFillBaseIntensity = 0.0;
        this.worldWarmBaseIntensity = 0.0;
        this.worldCoolBaseIntensity = 0.0;
        this.worldLightStrength = 0.0;
        this.worldLightAppliedStrength = 0.0;
        applyPresetState(WorldPresetCatalog.defaultPreset());
        this.worldLightAppliedStrength = this.worldLightStrength;
        this.worldLightAnimationEnabled = false;
        this.waterSimulation = new WaterSimulation(5600);
        this.waterSimulationEnabled = true;
        this.waterRenderEnabled = true;
        this.waterCollisionFloorY = 0.0;

        this.ditherToneCount = 2;
        this.ditherContrast = 1.15;
        this.ditherLightAssist = 0.38;
        this.ditherInvert = false;
        this.ditherCellSize = 6;
        this.ditherAsciiCharset = BitFont.DEFAULT_ASCII_CHARSET;

        this.temporalTickRate = 6.8;
        this.temporalNearContribution = 2.22;
        this.temporalGrazingContribution = 1.87;
        this.temporalMinSpeed = 0.55;
        this.temporalMaxSpeed = 10.61;
        this.temporalEdgeBlendStrength = 0.12;
        this.temporalGrainCellSize = TemporalNoiseRenderer.normalizeGrainCellSizePreset(2);
        this.temporalPaletteLevels = 5;

        this.hexCellSizeSetting = 6.0;
        this.hexQuantizationLevels = 8;
        this.hexOutlineStrength = 0.42;
        this.hexEdgeAware = false;
        this.hexDistanceScaling = false;
        this.hexWowStrength = 0.22;

        this.raySamplesPerFrame = 1;
        this.rayTileSize = 24;
        this.rayMaxDepth = 4;
        this.rayDiffuseBounces = 4;
        this.rayGlossyBounces = 3;
        this.rayTransmissionBounces = 2;
        this.rayVolumeBounces = 1;
        this.rayTransparentBounces = 2;
        this.rayDirectLighting = true;
        this.rayShadows = true;
        this.rayReflections = true;
        this.rayDenoise = true;
        this.rayDenoiseRadius = ProgressiveRenderDefaults.RAY_VIEWPORT_DENOISE_RADIUS;
        this.rayDenoiseStrength = ProgressiveRenderDefaults.RAY_VIEWPORT_DENOISE_STRENGTH;
        this.rayToneMap = "FILMIC";

        this.pathSamplesPerFrame = ProgressiveRenderDefaults.PATH_VIEWPORT_SAMPLES_PER_FRAME;
        this.pathTileSize = 24;
        this.pathMaxDepth = 6;
        this.pathDiffuseBounces = 6;
        this.pathGlossyBounces = 5;
        this.pathTransmissionBounces = 4;
        this.pathVolumeBounces = 2;
        this.pathTransparentBounces = 3;
        this.pathDirectLighting = true;
        this.pathSkyEnvironment = true;
        this.pathDenoise = true;
        this.pathAccumulationLock = true;
        this.pathDenoiseRadius = ProgressiveRenderDefaults.PATH_VIEWPORT_DENOISE_RADIUS;
        this.pathDenoiseStrength = ProgressiveRenderDefaults.PATH_VIEWPORT_DENOISE_STRENGTH;
        this.pathClampDirect = ProgressiveRenderDefaults.PATH_VIEWPORT_CLAMP_DIRECT;
        this.pathClampIndirect = ProgressiveRenderDefaults.PATH_VIEWPORT_CLAMP_INDIRECT;
        this.pathToneMap = "FILMIC";

        this.suppressObjectFieldApply = false;
        this.sceneOutlinerModel = null;
        this.sceneOutlinerList = null;
        this.sceneDetailsPanel = null;
        this.timelineCurrentFrameField = null;
        this.timelineStatusLabel = null;
        this.timelineDockCurrentFrameField = null;
        this.timelineDockStartFrameField = null;
        this.timelineDockEndFrameField = null;
        this.timelineDockFpsField = null;
        this.timelineDockStatusLabel = null;
        this.timelineDockStripComponent = null;
        this.bottomDockWorkspace = BottomDockWorkspace.TIMELINE;
        this.bottomDockCardPanel = null;
        this.bottomDockTitleLabel = null;
        this.bottomDockSubtitleLabel = null;
        this.materialDockHostPanel = null;
        this.bottomDockTimelineButton = null;
        this.bottomDockMaterialButton = null;
        this.undoHistory = new ArrayDeque<>();
        this.redoHistory = new ArrayDeque<>();
        this.activeHistoryTransaction = null;
        this.historyRestoring = false;
    }

 /**
 * inicializuju subsystémy a spustí hlavní smyčku aplikace.
 */
    public void start() {
        EngineLifecycleController.start(this);
    }

 /**
 * za běhu přepnu aktivní renderer podle zvoleného režimu.
 *
 * @param mode cílový režim vykreslování
 */
    public void setRenderMode(RenderMode mode) {
        EngineLifecycleController.setRenderMode(this, mode);
    }

 /**
 * vyžádám korektní ukončení aplikace.
 */
    public void shutdown() {
        EngineLifecycleController.shutdown(this);
    }


    void cycleRenderMode() {
        EngineNavigationController.cycleRenderMode(this);
    }

    void cycleCameraMode() {
        EngineNavigationController.cycleCameraMode(this);
    }

    void setNavigationPreset(NavigationPreset preset) {
        EngineNavigationController.setNavigationPreset(this, preset);
    }

    void toggleTransformMode(TransformMode mode) {
        EngineNavigationController.toggleTransformMode(this, mode);
    }

    void selectRelative(int delta) {
        EngineNavigationController.selectRelative(this, delta);
    }

    void clearSelection(String reason) {
        EngineSelectionController.clearSelection(this, reason);
    }

    boolean deleteCurrentSelection() {
        return EngineSelectionController.deleteCurrentSelection(this);
    }

    void undoLastAction() {
        EngineHistoryManager.undoLastAction(this);
    }

    void redoLastAction() {
        EngineHistoryManager.redoLastAction(this);
    }

    String getUndoActionLabel() {
        return EngineHistoryManager.getUndoLabel(this);
    }

    String getRedoActionLabel() {
        return EngineHistoryManager.getRedoLabel(this);
    }

    void beginSceneGesture(String label) {
        EngineHistoryManager.beginSceneGesture(this, label);
    }

    void commitSceneGesture() {
        EngineHistoryManager.commitSceneGesture(this);
    }

    void revertSceneGesture() {
        EngineHistoryManager.revertSceneGesture(this);
    }

    void applySceneEdit(String label, Runnable mutation) {
        EngineHistoryManager.recordSceneChange(this, label, mutation);
    }

    <T> T applySceneEdit(String label, Supplier<T> mutation) {
        return EngineHistoryManager.recordSceneChange(this, label, mutation);
    }

    PhongMaterial captureMaterialHistoryState(Entity entity) {
        return EngineHistoryManager.captureMaterialState(entity);
    }

    boolean materialHistoryStatesEqual(PhongMaterial left, PhongMaterial right) {
        return EngineHistoryManager.materialStatesEqual(left, right);
    }

    void pushMaterialHistoryCommand(String label, Entity entity, PhongMaterial before, PhongMaterial after) {
        EngineHistoryManager.pushMaterialSnapshotCommand(this, label, entity, before, after);
    }

    void afterHistorySnapshotApplied() {
        applyWorldLightSettings();
        applySceneVisibility(false);
        refreshObjectInspectorValues();
        refreshSceneOutliner();
        syncOutlinerSelectionToCurrentSelection();
        rebuildSceneDetailsPanel();
        rebuildWorldTab();
        rebuildMaterialDock();
        refreshUiIndicators();
        refreshTimelineUi();
    }
    void setCurrentEntitySelection(Entity entity) {
        if (selectedEntity != entity) {
            lastSelectionClickEntity = null;
            lastSelectionClickNanos = 0L;
        }
        selectedEntity = entity;
        selectedLight = null;
        selectedForceField = null;
        objectFocusMode = false;
        draggingSelectedObject = false;
        transformMode = TransformMode.NONE;
        axisConstraint = AxisConstraint.NONE;
        gizmoDragActive = false;
    }

    void setCurrentLightSelection(Light light) {
        selectedEntity = null;
        selectedLight = light;
        selectedForceField = null;
        lastSelectionClickEntity = null;
        lastSelectionClickNanos = 0L;
        objectFocusMode = false;
        draggingSelectedObject = false;
        transformMode = TransformMode.NONE;
        axisConstraint = AxisConstraint.NONE;
        gizmoDragActive = false;
    }

    void setCurrentForceFieldSelection(ForceField field) {
        selectedEntity = null;
        selectedLight = null;
        selectedForceField = field;
        lastSelectionClickEntity = null;
        lastSelectionClickNanos = 0L;
        objectFocusMode = false;
        draggingSelectedObject = false;
        transformMode = TransformMode.NONE;
        axisConstraint = AxisConstraint.NONE;
        gizmoDragActive = false;
    }

    boolean selectionSupportsTransform() {
        if (selectedEntity != null) {
            return !selectedEntity.isStatic();
        }
        return selectedLight != null || selectedForceField != null;
    }

    Vec3 selectionPivotPosition() {
        return EngineViewportOverlay.selectionPivotPosition(this);
    }

    Ray buildPickRay(int mouseX, int mouseY) {
        if (window == null || camera == null) {
            return null;
        }

        int canvasW = window.getCanvas().getWidth();
        int canvasH = window.getCanvas().getHeight();
        if (canvasW <= 1 || canvasH <= 1) {
            return null;
        }

        double ndcX = ((mouseX + 0.5) / canvasW) * 2.0 - 1.0;
        double ndcY = 1.0 - ((mouseY + 0.5) / canvasH) * 2.0;

        Mat4 vp = camera.getProjectionMatrix().multiply(camera.getViewMatrix());
        Mat4 invVp;
        try {
            invVp = vp.inverse();
        } catch (IllegalStateException ex) {
            return null;
        }

        Vec3 nearPoint = invVp.transform(new Vec4(ndcX, ndcY, -1.0, 1.0)).perspectiveDivide();
        Vec3 farPoint = invVp.transform(new Vec4(ndcX, ndcY, 1.0, 1.0)).perspectiveDivide();
        Vec3 direction = farPoint.sub(nearPoint).normalize();
        if (direction.lengthSquared() < 1e-10) {
            return null;
        }

        Vec3 origin = (camera instanceof PerspectiveCamera) ? camera.getPosition() : nearPoint;
        return new Ray(origin, direction);
    }

    double intersectRayMesh(Vec3 origin, Vec3 direction, Mesh mesh, Mat4 model, double maxT) {
        return RayIntersectionUtil.intersectRayMesh(origin, direction, mesh, model, maxT);
    }


    Vec3 outputCameraForward() {
        return CameraViewUtil.outputCameraForward(outputCameraEntity);
    }

    void activateTransformMode(TransformMode mode) {
        EngineTransformController.activateTransformMode(this, mode);
    }

    void setAxisConstraint(AxisConstraint axis) {
        EngineTransformController.setAxisConstraint(this, axis);
    }

    Vec3 spawnPositionFromPointer(double fallbackDistance) {
        return EngineSpawnPlacementController.spawnPositionFromPointer(this, fallbackDistance);
    }

    void addPrimitive(String type) {
        applySceneEdit("Přidání objektu", () -> EngineSceneActions.addPrimitive(this, type));
    }

    void addWaterEmitter() {
        applySceneEdit("Přidání spray emitoru", () -> EngineSceneActions.addWaterEmitter(this));
    }

    void importModelOrSceneFromDialog() {
        EngineSceneActions.importModelOrSceneFromDialog(this);
    }

    void importModelOrSceneAsync(String filePath) {
        sceneImportController.startImport(this, filePath);
    }

    void printHelp() {
        EngineSceneActions.printHelp();
    }

    void toggleProjectionCamera() {
        EngineCameraRuntime.toggleProjectionCamera(this);
    }

    AABBCollider createFittedAabbCollider(Mesh mesh, double uniformScale) {
        if (mesh.getAABB() == null) {
            return new AABBCollider(new Vec3(0.5, 0.5, 0.5));
        }
        Vec3 min = mesh.getAABB().getMin();
        Vec3 max = mesh.getAABB().getMax();
        Vec3 center = min.add(max).mul(0.5);
        Vec3 half = max.sub(min).mul(0.5);

        Vec3 scaledCenter = center.mul(uniformScale);
        Vec3 scaledHalf = new Vec3(
                Math.max(0.1, Math.abs(half.x * uniformScale)),
                Math.max(0.1, Math.abs(half.y * uniformScale)),
                Math.max(0.1, Math.abs(half.z * uniformScale))
        );

        AABBCollider collider = new AABBCollider(scaledHalf);
        collider.setOffset(scaledCenter);
        return collider;
    }

    void captureMouse() {
        EngineCameraRuntime.captureMouse(this);
    }

    void releaseMouseCapture() {
        EngineCameraRuntime.releaseMouseCapture(this);
    }

    void adjustMoveSpeed(double factor) {
        double next = cameraController.getMoveSpeed() * factor;
        cameraController.setMoveSpeed(next);
        System.out.printf("Move speed: %.2f%n", cameraController.getMoveSpeed());
        refreshUiIndicators();
    }

    void adjustLookSensitivity(double factor) {
        double next = cameraController.getRotateSpeed() * factor;
        cameraController.setRotateSpeed(next);
        System.out.printf("Look sensitivity: %.5f%n", cameraController.getRotateSpeed());
        refreshUiIndicators();
    }

    void adjustPathSamplesPerFrame(int delta) {
        if (pathTracerRenderer == null) {
            return;
        }
        int next = Math.max(1, Math.min(32, pathTracerRenderer.getSamplesPerFrame() + delta));
        pathSamplesPerFrame = next;
        pathTracerRenderer.setParameter("samplesPerFrame", pathSamplesPerFrame);
        System.out.println("Path samples/frame: " + next);
    }

    void applyRaySettings() {
        rayMaxDepth = RenderSettingsSync.applyRaySettings(
                rayTracerRenderer,
                raySamplesPerFrame,
                rayTileSize,
                rayDiffuseBounces,
                rayGlossyBounces,
                rayTransmissionBounces,
                rayVolumeBounces,
                rayTransparentBounces,
                rayDirectLighting,
                rayShadows,
                rayReflections,
                rayDenoise,
                rayDenoiseRadius,
                rayDenoiseStrength,
                rayToneMap
        );
    }

    void applyPathSettings() {
        pathMaxDepth = RenderSettingsSync.applyPathSettings(
                pathTracerRenderer,
                pathSamplesPerFrame,
                pathTileSize,
                pathDiffuseBounces,
                pathGlossyBounces,
                pathTransmissionBounces,
                pathVolumeBounces,
                pathTransparentBounces,
                pathDirectLighting,
                pathSkyEnvironment,
                pathDenoise,
                pathDenoiseRadius,
                pathDenoiseStrength,
                pathClampDirect,
                pathClampIndirect,
                pathToneMap
        );
    }

    Entity createOutputCameraEntity() {
        return EngineCameraRuntime.createOutputCameraEntity();
    }

    void syncOutputCameraFromCurrentView() {
        EngineCameraRuntime.syncOutputCameraFromCurrentView(this);
    }

    void applyCameraPose(Vec3 position, Vec3 forward) {
        EngineCameraRuntime.applyCameraPose(this, position, forward);
    }

    void rememberCurrentFpsPose() {
        EngineCameraRuntime.rememberCurrentFpsPose(this);
    }

    void rememberCurrentBlendPose() {
        EngineCameraRuntime.rememberCurrentBlendPose(this);
    }

    void jumpViewToOutputCamera(boolean fpsCapture) {
        EngineCameraRuntime.jumpViewToOutputCamera(this, fpsCapture);
    }

    void requestOutputStill(String format) {
        outputRenderController.captureStylizedViewportSettings(this);
        OutputRenderController.Settings outputSettings = outputRenderController.settings();
        int frame = outputSettings.useTimelineRange ? timelineCurrentFrame : Math.max(0, outputSettings.frameStart);
        double fps = outputSettings.useTimelineRange ? timelineFps : outputSettings.frameRate;
        outputSettings.exportType = "still";
        outputRenderController.requestStill(frame, fps, format);
    }

    void requestOutputImageSequence() {
        outputRenderController.captureStylizedViewportSettings(this);
        OutputRenderController.Settings outputSettings = outputRenderController.settings();
        int startFrame = outputSettings.useTimelineRange ? timelineStartFrame : outputSettings.frameStart;
        int endFrame = outputSettings.useTimelineRange ? timelineEndFrame : outputSettings.frameEnd;
        double fps = outputSettings.useTimelineRange ? timelineFps : outputSettings.frameRate;
        outputSettings.exportType = "sequence";
        outputRenderController.requestImageSequence(
                startFrame,
                endFrame,
                fps,
                outputSettings.format
        );
    }

    void requestOutputAnimatedGif() {
        outputRenderController.captureStylizedViewportSettings(this);
        OutputRenderController.Settings outputSettings = outputRenderController.settings();
        int startFrame = outputSettings.useTimelineRange ? timelineStartFrame : outputSettings.frameStart;
        int endFrame = outputSettings.useTimelineRange ? timelineEndFrame : outputSettings.frameEnd;
        double fps = outputSettings.useTimelineRange ? timelineFps : outputSettings.frameRate;
        outputSettings.exportType = "gif";
        outputRenderController.requestAnimatedGif(
                startFrame,
                endFrame,
                fps
        );
    }

    void requestOutputAnimatedAvi() {
        outputRenderController.captureStylizedViewportSettings(this);
        OutputRenderController.Settings outputSettings = outputRenderController.settings();
        int startFrame = outputSettings.useTimelineRange ? timelineStartFrame : outputSettings.frameStart;
        int endFrame = outputSettings.useTimelineRange ? timelineEndFrame : outputSettings.frameEnd;
        double fps = outputSettings.useTimelineRange ? timelineFps : outputSettings.frameRate;
        outputSettings.exportType = "avi";
        outputRenderController.requestAnimatedAvi(
                startFrame,
                endFrame,
                fps
        );
    }

    void setTimelineCurrentFrame(int frame) {
        EngineTimelineController.setCurrentFrame(this, frame);
    }

    void stepTimelineFrame(int delta) {
        EngineTimelineController.stepFrame(this, delta);
    }

    void setTimelineRange(int startFrame, int endFrame) {
        EngineTimelineController.setRange(this, startFrame, endFrame);
    }

    void addTimelineKeyForSelection() {
        EngineTimelineController.addKeyForSelection(this);
    }

    void addTimelineReleaseKeyForSelection() {
        EngineTimelineController.addReleaseKeyForSelection(this);
    }

    void removeTimelineKeyForSelection() {
        EngineTimelineController.removeKeyForSelection(this);
    }

    void removeTimelineReleaseKeyForSelection() {
        EngineTimelineController.removeReleaseKeyForSelection(this);
    }

    void clearTimelineKeys() {
        EngineTimelineController.clearAllKeys(this);
    }

    void refreshTimelineUi() {
        EngineTimelineController.refreshUi(this);
    }

    void rebuildMaterialDock() {
        EngineMaterialDock.rebuild(this);
    }

    void setBottomDockWorkspace(BottomDockWorkspace workspace) {
        EngineBottomDock.showWorkspace(this, workspace);
    }

    void showTimelineWorkspace() {
        setBottomDockWorkspace(BottomDockWorkspace.TIMELINE);
    }

    void showMaterialWorkspace() {
        setBottomDockWorkspace(BottomDockWorkspace.MATERIAL);
    }

    void setupRightPanel() {
        EngineUiSetup.setupRightPanel(this);
    }

    void setupViewportContextMenu() {
        EngineUiSetup.setupViewportContextMenu(this);
    }

    SceneItemState stateFor(Object item) {
        if (item == null) {
            return new SceneItemState();
        }
        SceneItemState state = sceneItemStates.get(item);
        if (state != null) {
            return state;
        }
        SceneItemState created = new SceneItemState();
        sceneItemStates.put(item, created);
        return created;
    }

    void initializeSceneItemStateDefaults() {
        sceneItemStates.clear();
        if (scene == null) {
            return;
        }
        for (Entity entity : scene.getEntities()) {
            SceneItemState state = stateFor(entity);
            if (entity == outputCameraEntity) {
                state.visibleInView = false;
                state.visibleInOutput = false;
            }
        }
        for (Light light : scene.getLights()) {
            stateFor(light);
        }
        for (ForceField forceField : forceFields) {
            stateFor(forceField);
        }
    }

    void applySceneVisibility(boolean outputPass) {
        if (scene == null) {
            return;
        }
        Vec3 cameraPos = !outputPass && camera != null ? camera.getPosition() : null;
        boolean distanceCull = !outputPass && viewDistanceCullingEnabled && cameraPos != null;
        for (Entity entity : scene.getEntities()) {
            SceneItemState state = stateFor(entity);
            boolean visible = outputPass ? state.visibleInOutput : state.visibleInView;
            if (entity == outputCameraEntity) {
                visible = false;
            }
            if (visible && distanceCull && entity.getMesh() != null) {
                AABB bounds = entity.getWorldBounds();
                Vec3 center = bounds != null ? bounds.center() : entity.getTransform().getPosition();
                double radius = 0.0;
                if (bounds != null) {
                    radius = bounds.getMax().sub(center).length();
                } else if (entity.getMesh().getBounds() != null) {
                    radius = entity.getMesh().getBounds().getRadius();
                }
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.VISIBILITY_CULL_TESTS, 1L);
                double dx = center.x - cameraPos.x;
                double dy = center.y - cameraPos.y;
                double dz = center.z - cameraPos.z;
                double threshold = viewDistanceLimit + radius;
                if (dx * dx + dy * dy + dz * dz > threshold * threshold) {
                    visible = false;
                }
            }
            entity.setVisible(visible);
        }
        for (Light light : scene.getLights()) {
            SceneItemState state = stateFor(light);
            boolean enabled = outputPass ? state.visibleInOutput : state.visibleInView;
            if (enabled && distanceCull && light instanceof PointLight point) {
                Vec3 pointPos = point.getPosition();
                RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.VISIBILITY_CULL_TESTS, 1L);
                if (pointPos != null) {
                    double dx = pointPos.x - cameraPos.x;
                    double dy = pointPos.y - cameraPos.y;
                    double dz = pointPos.z - cameraPos.z;
                    if (dx * dx + dy * dy + dz * dz > viewDistanceLimit * viewDistanceLimit) {
                        enabled = false;
                    }
                }
            }
            light.setEnabled(enabled);
        }
    }

    public void setLaunchFullscreen(boolean launchFullscreen) {
        this.launchFullscreen = launchFullscreen;
    }

    public void setExplicitPreviewRenderResolution(int width, int height) {
        if (width > 0 && height > 0) {
            this.explicitPreviewRenderWidth = width;
            this.explicitPreviewRenderHeight = height;
            return;
        }
        this.explicitPreviewRenderWidth = 0;
        this.explicitPreviewRenderHeight = 0;
    }

    public int getExplicitPreviewRenderWidth() {
        return explicitPreviewRenderWidth;
    }

    public int getExplicitPreviewRenderHeight() {
        return explicitPreviewRenderHeight;
    }

    private void applyPresetState(WorldPresetCatalog.Definition preset) {
        if (preset == null) {
            return;
        }
        worldPresetKey = preset.key();
        worldLightColor = preset.ambientColor();
        worldBackgroundColor = preset.backgroundColor();
        worldLightStrength = preset.strength();
        worldSunBaseIntensity = preset.sunIntensity() / preset.strength();
        worldFillBaseIntensity = preset.fillIntensity() / preset.strength();
        worldWarmBaseIntensity = preset.warmIntensity() / preset.strength();
        worldCoolBaseIntensity = preset.coolIntensity() / preset.strength();
        if (sunLight != null) {
            sunLight.setColor(preset.sunColor());
        }
        if (fillLight != null) {
            fillLight.setColor(preset.fillColor());
        }
        if (warmWorldLight != null) {
            warmWorldLight.setColor(preset.warmColor());
        }
        if (coolWorldLight != null) {
            coolWorldLight.setColor(preset.coolColor());
        }
    }

    void applyWorldLightSettings() {
        EngineWorldManager.applyWorldLightSettings(this);
    }

    void applyWorldPreset(String presetName) {
        applySceneEdit("Změna předvolby prostředí", () -> EngineWorldManager.applyWorldPreset(this, presetName));
        rebuildWorldTab();
    }

    String getLightName(Light light) {
        return EngineWorldManager.getLightName(this, light);
    }

    Vec3 spawnInFrontOfCamera(double distance) {
        return EngineWorldManager.spawnInFrontOfCamera(this, distance);
    }

    void addPointLight() {
        applySceneEdit("Přidání bodového světla", () -> EngineWorldManager.addPointLight(this));
    }

    void addAreaLight() {
        applySceneEdit("Přidání plošného světla", () -> EngineWorldManager.addAreaLight(this));
    }

    void addConeLight() {
        applySceneEdit("Přidání kuželového světla", () -> EngineWorldManager.addConeLight(this));
    }

    void addVectorForceField() {
        applySceneEdit("Přidání vektorové síly", () -> EngineWorldManager.addVectorForceField(this));
    }

    void addPointForceField(boolean attract) {
        applySceneEdit(attract ? "Přidání attractoru" : "Přidání repulsoru",
                () -> EngineWorldManager.addPointForceField(this, attract));
    }

    void addTurbulenceForceField() {
        applySceneEdit("Přidání turbulence", () -> EngineWorldManager.addTurbulenceForceField(this));
    }

    void applyForceFields(double elapsedSeconds) {
        EngineWorldManager.applyForceFields(this, elapsedSeconds);
    }

    void applyOutlinerSelectionFromList() {
        EngineSceneInspector.applyOutlinerSelectionFromList(this);
    }

    boolean deleteSelectedOutlinerItem() {
        return EngineSceneInspector.deleteSelectedOutlinerItem(this);
    }

    void refreshSceneOutliner() {
        EngineSceneInspector.refreshSceneOutliner(this);
    }

    void syncOutlinerSelectionToCurrentSelection() {
        EngineSceneInspector.syncOutlinerSelectionToCurrentSelection(this);
    }

    void rebuildSceneDetailsPanel() {
        EngineSceneInspector.rebuildSceneDetailsPanel(this);
        rebuildMaterialDock();
    }

    void rebuildWorldTab() {
        if (window == null) {
            return;
        }
        JPanel worldTab = EngineScenePanels.buildWorldTab(this);
        worldTab.revalidate();
        worldTab.repaint();
    }
    JPanel addCollapsibleSection(JPanel parent, String title, boolean expandedByDefault) {
        return UiBuilder.addCollapsibleSection(parent, title, expandedByDefault, this::focusCanvas);
    }

    JCheckBox addBooleanRow(JPanel parent, String label, boolean initial, Consumer<Boolean> onChange) {
        JCheckBox checkBox = UiBuilder.addBooleanRow(parent, label, initial, onChange, this::focusCanvas);
        attachTimelineValuePopup(checkBox, null);
        return checkBox;
    }

    JTextField addNumericRow(JPanel parent, String label, String initial, Consumer<String> onCommit) {
        JTextField field = UiBuilder.addNumericRow(parent, label, initial, onCommit);
        attachTimelineValuePopup(field, () -> {
            UiBuilder.normalizeNumericField(field);
            if (onCommit != null) {
                onCommit.accept(field.getText().trim());
            }
        });
        return field;
    }

    void addColorPickerRow(JPanel parent, String label, Vec3 initialColor, Consumer<Vec3> onCommit) {
        UiBuilder.addColorPickerRow(
                parent,
                label,
                initialColor,
                onCommit,
                window != null ? window.getCanvas() : null,
                this::focusCanvas,
                () -> {
                    if (selectedEntity != null || selectedLight != null || selectedForceField != null) {
                        addTimelineKeyForSelection();
                    }
                },
                () -> {
                    if (selectedEntity != null || selectedLight != null || selectedForceField != null) {
                        removeTimelineKeyForSelection();
                    }
                }
        );
    }

    JTextField addTextRow(JPanel parent, String label, String initial, Consumer<String> onCommit) {
        JTextField field = UiBuilder.addTextRow(parent, label, initial, onCommit);
        attachTimelineValuePopup(field, () -> {
            if (onCommit != null) {
                onCommit.accept(field.getText().trim());
            }
        });
        return field;
    }

    JComboBox<String> addComboRow(JPanel parent, String label, String[] values, String selected,
                                           Consumer<String> onChange) {
        JComboBox<String> combo = UiBuilder.addComboRow(parent, label, values, selected, onChange, this::focusCanvas);
        attachTimelineValuePopup(combo, () -> {
            if (onChange != null) {
                Object value = combo.getSelectedItem();
                if (value != null) {
                    onChange.accept(value.toString());
                }
            }
        });
        return combo;
    }

    JLabel sectionTitle(String text) {
        return UiBuilder.sectionTitle(text);
    }

    JButton actionButton(String text, Runnable action) {
        return UiBuilder.actionButton(text, action, this::focusCanvas);
    }

    JTextField addTransformField(JPanel parent, String axis) {
        JTextField field = UiBuilder.addTransformField(parent, axis, this::applyObjectInspectorValues);
        attachTimelineValuePopup(field, this::applyObjectInspectorValues);
        return field;
    }

    void refreshObjectInspectorValues() {
        EngineObjectInspectorController.refreshObjectInspectorValues(this);
    }

    String formatTransformValue(double v) {
        return String.format("%.4f", v);
    }

    void applyObjectInspectorValues() {
        EngineObjectInspectorController.applyObjectInspectorValues(this);
    }

    double parseOrFallback(String text, double fallback) {
        return UiBuilder.parseOrFallback(text, fallback);
    }

    JToggleButton createLightToggle(String text, Runnable action) {
        return UiBuilder.createLightToggle(text, action, this::focusCanvas);
    }

    void focusCanvas() {
        if (window != null && window.getCanvas() != null) {
            window.getCanvas().requestFocusInWindow();
        }
    }

    private void attachTimelineValuePopup(Component target, Runnable preCommit) {
        EngineTimelineValuePopupSupport.attach(this, target, preCommit);
    }

    void refreshUiIndicators() {
        EngineToolbarController.refreshUiIndicators(this);
    }

    void toggleFrustumCulling() {
        EngineRenderRuntime.toggleFrustumCulling(this);
    }

    void toggleBackfaceCulling() {
        EngineRenderRuntime.toggleBackfaceCulling(this);
    }

    void togglePhysics() {
        EngineRenderRuntime.togglePhysics(this);
    }

    void toggleAnimationPlayback() {
        EngineRenderRuntime.toggleAnimationPlayback(this);
    }

    void togglePostAA() {
        EngineRenderRuntime.togglePostAA(this);
    }

    void setDitherStyle(DitherRenderer.DitherStyle style) {
        EngineRenderRuntime.setDitherStyle(this, style);
    }

    void cycleDitherStyle() {
        EngineRenderRuntime.cycleDitherStyle(this);
    }

    void cycleTemporalNoiseMode() {
        EngineRenderRuntime.cycleTemporalNoiseMode(this);
    }

    void cycleTemporalNoiseGrainPreset() {
        EngineRenderRuntime.cycleTemporalNoiseGrainPreset(this);
    }

    void cycleHexWowMode() {
        EngineRenderRuntime.cycleHexWowMode(this);
    }

    void toggleHexDebugCells() {
        EngineRenderRuntime.toggleHexDebugCells(this);
    }

    void toggleDebugOverlay() {
        EngineRenderRuntime.toggleDebugOverlay(this);
    }

    void toggleEditorOverlay() {
        EngineRenderRuntime.toggleEditorOverlay(this);
    }

    void toggleUpscaleFilter() {
        EngineRenderRuntime.toggleUpscaleFilter(this);
    }

    void toggleParallelRaster() {
        EngineRenderRuntime.toggleParallelRaster(this);
    }

    void adjustWorkerCount(int delta) {
        EngineRenderRuntime.adjustWorkerCount(this, delta);
    }

    void cycleRenderScale() {
        EngineRenderRuntime.cycleRenderScale(this);
    }

    void applyRenderScale() {
        EngineRenderRuntime.applyRenderScale(this);
    }

    double effectiveRenderScale() {
        return EngineRenderRuntime.effectiveRenderScale(this);
    }

    private boolean isAnyKeyPressed(int... keyCodes) {
        for (int keyCode : keyCodes) {
            if (input.isKeyPressed(keyCode)) {
                return true;
            }
        }
        return false;
    }

    boolean isAsciiRenderHotkeyPressed() {
        if (input.isCtrlDown() || input.isAltDown()) {
            return false;
        }
        return isAnyKeyPressed(KeyEvent.VK_5, KeyEvent.VK_NUMPAD5)
                || input.isCharPressed('5')
                || input.isCharPressed('ř');
    }

    boolean isRenderHotkeyPressed(RenderMode mode) {
        if (input.isCtrlDown() || input.isAltDown()) {
            return false;
        }
        switch (mode) {
            case MODEL:
                return isAnyKeyPressed(KeyEvent.VK_G)
                        || input.isCharPressed('g');
            case BASIC:
                return isAnyKeyPressed(KeyEvent.VK_1, KeyEvent.VK_NUMPAD1)
                        || input.isCharPressed('+')
                        || input.isCharPressed('1');
            case PHONG:
                return isAnyKeyPressed(KeyEvent.VK_2, KeyEvent.VK_NUMPAD2)
                        || input.isCharPressed('ě')
                        || input.isCharPressed('2');
            case WIREFRAME:
                return isAnyKeyPressed(KeyEvent.VK_3, KeyEvent.VK_NUMPAD3)
                        || input.isCharPressed('š')
                        || input.isCharPressed('3');
            case DITHERING:
                return isAnyKeyPressed(KeyEvent.VK_4, KeyEvent.VK_NUMPAD4)
                        || input.isCharPressed('4')
                        || input.isCharPressed('č');
            case TEMPORAL_NOISE:
                return isAnyKeyPressed(KeyEvent.VK_6, KeyEvent.VK_NUMPAD6)
                        || input.isCharPressed('6')
                        || input.isCharPressed('ž');
            case RAY_TRACING:
                return isAnyKeyPressed(KeyEvent.VK_7, KeyEvent.VK_NUMPAD7)
                        || input.isCharPressed('7')
                        || input.isCharPressed('ý');
            case PATH_TRACING:
                return isAnyKeyPressed(KeyEvent.VK_8, KeyEvent.VK_NUMPAD8, KeyEvent.VK_0, KeyEvent.VK_NUMPAD0)
                        || input.isCharPressed('8')
                        || input.isCharPressed('á')
                        || input.isCharPressed('0')
                        || input.isCharPressed('é');
            case HEX_MOSAIC:
                return isAnyKeyPressed(KeyEvent.VK_9, KeyEvent.VK_NUMPAD9)
                        || input.isCharPressed('9')
                        || input.isCharPressed('í');
            default:
                return false;
        }
    }
}
