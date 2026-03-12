package engine.core;

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
import engine.render.ray.RayTracerRenderer;
import engine.render.ray.PathTracerRenderer;
import engine.sim.water.WaterSimulation;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.scene.Scene;
import engine.util.BitFont;
import engine.util.RayIntersectionUtil;
import engine.util.ThreadPool;
import engine.util.UiBuilder;

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
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.DefaultListModel;
import javax.swing.JList;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JToggleButton;

/**
 * Tady držím centrální řídicí třídu enginu.
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
    boolean objectFocusMode;
    boolean draggingSelectedObject;
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
    double viewportAdaptiveScaleCurrent;
    double viewportAdaptiveScaleApplied;
    double viewportScalePressureSeconds;
    double viewportCriticalPressureSeconds;
    long viewportCriticalPreviewStartNanos;
    boolean viewportCriticalPreviewActive;
    boolean viewDistanceCullingEnabled;
    double viewDistanceLimit;
    boolean viewportNavigationPreviewEnabled;
    boolean viewportNavigationPreviewActive;
    RenderMode viewportNavigationFallbackMode;
    RenderMode viewportDisplayedMode;
    int baseWidth;
    int baseHeight;
    int lastCanvasWidth;
    int lastCanvasHeight;
    boolean postAAEnabled;
    int[] postAATemp;
    String loadedModelPath;
    String loadedDiffuseTexturePath;
    boolean smoothUpscaling;
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
    int rayDenoiseStartSamples;
    int rayDenoiseRadius;
    double rayDenoiseStrength;

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
    int pathDenoiseStartSamples;
    int pathDenoiseRadius;
    double pathDenoiseStrength;

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
        this.objectFocusMode = false;
        this.draggingSelectedObject = false;
        this.captureSelectLatch = false;
        this.gizmoDragActive = false;
        this.renderScale = 1.00;
        this.progressiveViewportEnabled = true;
        this.interactiveRenderScale = 0.75;
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
        this.viewportAdaptiveScaleCurrent = 1.0;
        this.viewportAdaptiveScaleApplied = 1.0;
        this.viewportScalePressureSeconds = 0.0;
        this.viewportCriticalPressureSeconds = 0.0;
        this.viewportCriticalPreviewStartNanos = 0L;
        this.viewportCriticalPreviewActive = false;
        this.viewDistanceCullingEnabled = false;
        this.viewDistanceLimit = 120.0;
        this.viewportNavigationPreviewEnabled = true;
        this.viewportNavigationPreviewActive = false;
        this.viewportNavigationFallbackMode = RenderMode.MODEL;
        this.viewportDisplayedMode = RenderMode.PHONG;
        this.baseWidth = 1200;
        this.baseHeight = 760;
        this.lastCanvasWidth = this.baseWidth;
        this.lastCanvasHeight = this.baseHeight;
        this.postAAEnabled = false;
        this.postAATemp = new int[0];
        this.loadedModelPath = null;
        this.loadedDiffuseTexturePath = null;
        this.smoothUpscaling = false;
        this.navigationPreset = NavigationPreset.FPS;
        this.selectionViewMode = SelectionViewMode.FRAME_AND_FOCUS;
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
        this.worldLightColor = new Vec3(0.18, 0.20, 0.24);
        this.worldBackgroundColor = new Vec3(0.06, 0.08, 0.11);
        this.worldPresetKey = "Studio Neutral";
        this.worldSunBaseIntensity = 1.35;
        this.worldFillBaseIntensity = 0.42;
        this.worldWarmBaseIntensity = 0.55;
        this.worldCoolBaseIntensity = 0.48;
        this.worldLightStrength = 1.0;
        this.worldLightAppliedStrength = 1.0;
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
        this.rayDenoiseStartSamples = 6;
        this.rayDenoiseRadius = 1;
        this.rayDenoiseStrength = 0.28;

        this.pathSamplesPerFrame = 1;
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
        this.pathDenoiseStartSamples = 12;
        this.pathDenoiseRadius = 1;
        this.pathDenoiseStrength = 0.24;

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
     * Tady inicializuju subsystémy a spustím hlavní smyčku aplikace.
     */
    public void start() {
        EngineLifecycleController.start(this);
    }

    /**
     * Tady za běhu přepnu aktivní renderer podle zvoleného režimu.
     *
     * @param mode sem předám cílový režim vykreslování
     */
    public void setRenderMode(RenderMode mode) {
        EngineLifecycleController.setRenderMode(this, mode);
    }

    /**
     * Tady vyžádám korektní ukončení aplikace.
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
                rayDenoiseStartSamples,
                rayDenoiseRadius,
                rayDenoiseStrength
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
                pathDenoiseStartSamples,
                pathDenoiseRadius,
                pathDenoiseStrength
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
                entity.computeWorldBounds();
                AABB bounds = entity.getWorldBounds();
                Vec3 center = bounds != null ? bounds.center() : entity.getTransform().getPosition();
                double radius = 0.0;
                if (bounds != null) {
                    radius = bounds.getMax().sub(center).length();
                } else if (entity.getMesh().getBounds() != null) {
                    radius = entity.getMesh().getBounds().getRadius();
                }
                if (center.sub(cameraPos).length() - radius > viewDistanceLimit) {
                    visible = false;
                }
            }
            entity.setVisible(visible);
        }
        for (Light light : scene.getLights()) {
            SceneItemState state = stateFor(light);
            boolean enabled = outputPass ? state.visibleInOutput : state.visibleInView;
            if (enabled && distanceCull && light instanceof PointLight point) {
                if (point.getPosition().sub(cameraPos).length() > viewDistanceLimit) {
                    enabled = false;
                }
            }
            light.setEnabled(enabled);
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

    void applyRenderScale(boolean verboseLog) {
        EngineRenderRuntime.applyRenderScale(this, verboseLog);
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

