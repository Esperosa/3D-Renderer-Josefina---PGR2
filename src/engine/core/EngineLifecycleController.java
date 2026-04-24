package engine.core;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import engine.camera.CameraController;
import engine.camera.OrthographicCamera;
import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
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
import engine.render.ray.core.RayTracerRenderer;
import engine.sim.water.WaterParticleRenderer;
import engine.sim.water.WaterSimulation;
import engine.util.RuntimeInstrumentation;
import engine.util.SelectionOverlayUtil;

final class EngineLifecycleController {
    private static final long RENDER_MODE_SWITCH_MIN_SHOW_NS = 1_000_000_000L;
    private static final long RENDER_MODE_SWITCH_FADE_IN_NS = 300_000_000L;
    private static final long RENDER_MODE_SWITCH_FADE_OUT_NS = 650_000_000L;
    private static final long RENDER_MODE_SWITCH_MIN_SAMPLES = 15L;
    private static final long RENDER_MODE_SWITCH_HEAVY_MIN_SAMPLES = 4L;
    private static final long RENDER_MODE_SWITCH_HEAVY_MAX_VIEWPORT_MIN_SAMPLES = 1L;
    private static final double RENDER_MODE_SWITCH_LARGE_VIEWPORT_MP = 2.5;

    private EngineLifecycleController() {
    }

    static void start(Engine engine) {
        if (engine.running) {
            return;
        }

        long startupAppStartNanos = System.nanoTime();
        engine.startupAppStartNanos = startupAppStartNanos;
        engine.startupWindowCreatedNanos = 0L;
        engine.startupFirstRenderedFrameNanos = 0L;
        engine.startupFirstPresentedFrameNanos = 0L;
        engine.startupFirstInteractiveFrameNanos = 0L;
        engine.startupSplashClosedNanos = 0L;
        engine.startupFocusRequestedNanos = 0L;
        engine.startupFocusAcquiredNanos = 0L;
        engine.startupInputReady = false;
        engine.startupFocusReady = false;

        Thread.currentThread().setName("RenderLoop");
        try {
            Thread.currentThread().setPriority(Math.max(Thread.MIN_PRIORITY + 1, Thread.NORM_PRIORITY - 1));
        } catch (SecurityException ignored) {
            // Když VM nepovolí změnu priority, zůstane výchozí hodnota.
        }

        StartupLoadingScreen loadingScreen = StartupLoadingScreen.show(Engine.WINDOW_TITLE);
        loadingScreen.setPhase("Pripravuji viewport a UI...");

        final int width = engine.baseWidth;
        final int height = engine.baseHeight;

        engine.window = new Window(Engine.WINDOW_TITLE, width, height, engine.launchFullscreen);
        engine.startupWindowCreatedNanos = System.nanoTime();
        engine.window.setSmoothUpscaling(engine.smoothUpscaling);
        engine.lastCanvasWidth = Math.max(1, engine.window.getCanvas().getWidth());
        engine.lastCanvasHeight = Math.max(1, engine.window.getCanvas().getHeight());
        engine.baseWidth = engine.lastCanvasWidth;
        engine.baseHeight = engine.lastCanvasHeight;
        engine.frameBuffer = new FrameBuffer(
                EngineRenderRuntime.scaledWidth(engine),
                EngineRenderRuntime.scaledHeight(engine));
        engine.input = new Input();
        engine.input.attach(engine.window.getCanvas());
        try {
            engine.mouseRobot = new Robot();
        } catch (AWTException e) {
            engine.mouseRobot = null;
            System.out.println("Mouse capture unavailable (Robot init failed).");
        }

        loadingScreen.setPhase("Nacitam scenu a render pipeline...");
        engine.physicsWorld = new PhysicsWorld(new Vec3(0.0, -9.81, 0.0));
        engine.scene = EngineSceneBootstrap.createDefaultScene(engine);
        engine.waterSimulation.clear();
        engine.waterCollisionFloorY = engine.floorEntity != null
                ? engine.floorEntity.getTransform().getPosition().y
                : 0.0;
        engine.initializeSceneItemStateDefaults();
        engine.applyWorldLightSettings();
        engine.applySceneVisibility(false);

        double aspect = (double) width / (double) height;
        engine.perspectiveCamera = new PerspectiveCamera(70.0, aspect, 0.1, 300.0);
        engine.perspectiveCamera.setPosition(new Vec3(0.0, 2.4, 7.0));
        engine.perspectiveCamera.lookAt(new Vec3(0.0, 1.0, 0.0));

        engine.orthographicCamera = new OrthographicCamera(-6.0 * aspect, 6.0 * aspect, -6.0, 6.0, 0.1, 300.0);
        EngineCameraRuntime.copyCameraPose(engine.perspectiveCamera, engine.orthographicCamera);

        engine.camera = engine.perspectiveCamera;
        engine.cameraController = new CameraController(engine.camera, engine.fpsCameraMode);
        engine.cameraController.setMouseLookAlways(false);
        engine.cameraController.frameTarget(new Vec3(0.0, 1.0, 0.0));
        EngineSceneBootstrap.focusInitialScene(engine);

        engine.rasterRenderer = new RasterRenderer();
        engine.ditherRenderer = new DitherRenderer();
        engine.temporalNoiseRenderer = new TemporalNoiseRenderer();
        engine.wireframeRenderer = new WireframeRenderer();
        engine.hexMosaicRenderer = new HexMosaicRenderer();
        engine.rayTracerRenderer = new RayTracerRenderer();
        engine.pathTracerRenderer = new PathTracerRenderer();

        int fbw = engine.frameBuffer.getWidth();
        int fbh = engine.frameBuffer.getHeight();
        engine.rasterRenderer.init(fbw, fbh);
        engine.ditherRenderer.init(fbw, fbh);
        engine.temporalNoiseRenderer.init(fbw, fbh);
        engine.wireframeRenderer.init(fbw, fbh);
        engine.hexMosaicRenderer.init(fbw, fbh);
        engine.rayTracerRenderer.init(fbw, fbh);
        engine.pathTracerRenderer.init(fbw, fbh);

        engine.rasterRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.rasterRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.ditherRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.ditherRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.ditherRenderer.setParameter("toneCount", engine.ditherToneCount);
        engine.ditherRenderer.setParameter("contrast", engine.ditherContrast);
        engine.ditherRenderer.setParameter("lightAssist", engine.ditherLightAssist);
        engine.ditherRenderer.setParameter("invert", engine.ditherInvert);
        engine.ditherRenderer.setParameter("cellSize", engine.ditherCellSize);
        engine.ditherRenderer.setParameter("asciiCharset", engine.ditherAsciiCharset);

        engine.temporalNoiseRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.temporalNoiseRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.temporalNoiseRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.temporalNoiseRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
        engine.temporalNoiseRenderer.setParameter("temporalTickRate", engine.temporalTickRate);
        engine.temporalNoiseRenderer.setParameter("depthNearContribution", engine.temporalNearContribution);
        engine.temporalNoiseRenderer.setParameter("grazingContribution", engine.temporalGrazingContribution);
        engine.temporalNoiseRenderer.setParameter("minSpeed", engine.temporalMinSpeed);
        engine.temporalNoiseRenderer.setParameter("maxSpeed", engine.temporalMaxSpeed);
        engine.temporalNoiseRenderer.setParameter("edgeBlendStrength", engine.temporalEdgeBlendStrength);
        engine.temporalNoiseRenderer.setParameter("grainCellSize", engine.temporalGrainCellSize);
        engine.temporalNoiseRenderer.setParameter("paletteLevels", engine.temporalPaletteLevels);

        engine.hexMosaicRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.hexMosaicRenderer.setParameter("parallel", engine.parallelRasterEnabled);
        engine.hexMosaicRenderer.setParameter("frustumCulling", engine.frustumCullingEnabled);
        engine.hexMosaicRenderer.setParameter("backfaceCulling", engine.backfaceCullingEnabled);
        engine.hexMosaicRenderer.setParameter("cellSize", engine.hexCellSizeSetting);
        engine.hexMosaicRenderer.setParameter("quantizationLevels", engine.hexQuantizationLevels);
        engine.hexMosaicRenderer.setParameter("outlineStrength", engine.hexOutlineStrength);
        engine.hexMosaicRenderer.setParameter("edgeAware", engine.hexEdgeAware);
        engine.hexMosaicRenderer.setParameter("distanceScaling", engine.hexDistanceScaling);
        engine.hexMosaicRenderer.setParameter("wowMode", "classic");
        engine.hexMosaicRenderer.setParameter("wowStrength", engine.hexWowStrength);

        engine.rayTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.pathTracerRenderer.setParameter("workerCount", engine.parallelWorkerCount);
        engine.applyRaySettings();
        engine.applyPathSettings();

        EngineCameraRuntime.syncOutputCameraFromCurrentView(engine);

        engine.renderPipeline = new RenderPipeline();
        engine.postAAPostProcessor = new PostProcessor() {
            @Override
            public void process(FrameBuffer fb, double time) {
                EngineRenderRuntime.applyFastPostAA(engine, fb);
            }

            @Override
            public String getName() {
                return "Fast AA";
            }
        };
        setRenderMode(engine, RenderMode.PHONG);
        EngineToolbarController.setupToolbar(engine);
        EngineBottomDock.setup(engine);
        engine.editorShortcutRouter = new EditorShortcutRouter(engine);
        engine.editorShortcutRouter.install();
        EngineNavigationController.setNavigationPreset(engine, engine.navigationPreset);
        EngineCameraRuntime.rememberCurrentFpsPose(engine);
        EngineCameraRuntime.rememberCurrentBlendPose(engine);

        EngineSceneActions.printHelp();
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        loadingScreen.setPhase("Dokoncuji inicializaci...");
        engine.running = true;
        boolean startupSplashClosed = false;
        boolean startupTimelinePrinted = false;
        long nextFocusRetryNanos = 0L;
        long lastNanos = System.nanoTime();
        double elapsed = 0.0;
        double physicsAccumulator = 0.0;
        double fixedStep = engine.physicsWorld.getFixedTimeStep();
        double fpsAccumulator = 0.0;
        double frameTimeAccumulatorMs = 0.0;
        int frameCounter = 0;

        while (engine.running && !engine.window.isCloseRequested()) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - lastNanos) / 1_000_000_000.0);
            lastNanos = now;
            elapsed += dt;
            long frameStart = now;
            RuntimeInstrumentation.FrameToken frameToken =
                    RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "preview");
            RenderMode viewportRenderMode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
            boolean viewportInteractionActive = false;
            try {
                long inputStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.INPUT);
                engine.input.poll();
                EngineRenderRuntime.handleWindowResizeIfNeeded(engine);
                EngineCameraRuntime.updateMouseCaptureDelta(engine);
                EngineHotkeyRouter.handle(engine);
                engine.sceneImportController.update(engine);
                engine.processRuntimeTasks(
                        resolveRuntimeTaskBudgetNanos(engine),
                        resolveRuntimeTaskBatchSize(engine));
                engine.outputRenderController.processPendingRequest(
                        engine.scene,
                        (w, h) -> EngineCameraRuntime.buildOutputRenderCamera(engine, w, h),
                        outputPass -> engine.applySceneVisibility(outputPass),
                        engine.frustumCullingEnabled,
                        engine.backfaceCullingEnabled,
                        frame -> EngineTimelineController.applyFrameForOutput(engine, frame)
                );
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.INPUT, inputStage);

                if (engine.outputRenderController.isRenderInProgress()) {
                    if (engine.navigationPreset == Engine.NavigationPreset.FPS && engine.mouseCaptured) {
                        engine.outputRenderRecapturePending = true;
                        engine.releaseMouseCapture();
                    }
                    engine.window.setRenderPreviewMode(true);
                    engine.window.setRenderPreviewActions(
                            engine::toggleOutputRenderPause,
                            engine::cancelOutputRender);
                    engine.window.setRenderPreviewState(engine.currentOutputRenderPreviewState());
                    RuntimeInstrumentation.recordMode(engine.activeMode, "OUTPUT_PREVIEW");
                    long hudStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.HUD_UI);
                    engine.window.setOverlayText(new String[0]);
                    RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.HUD_UI, hudStage);
                    updateRenderModeSwitchOverlay(engine, System.nanoTime());
                    long blitStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.BLIT_PRESENT);
                    if (engine.outputRenderController.hasViewportPreview()) {
                        engine.window.blitPreview(
                                engine.outputRenderController.getViewportPreviewPixels(),
                                engine.outputRenderController.getViewportPreviewWidth(),
                                engine.outputRenderController.getViewportPreviewHeight());
                    } else {
                        engine.window.blitPreview(
                                engine.frameBuffer.getColorBuffer(),
                                engine.frameBuffer.getWidth(),
                                engine.frameBuffer.getHeight());
                    }
                    RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.BLIT_PRESENT, blitStage);
                    if (!sleepForFrame(33)) {
                        break;
                    }
                    continue;
                }
                if (engine.outputRenderRecapturePending
                        && engine.navigationPreset == Engine.NavigationPreset.FPS
                        && !engine.mouseCaptured) {
                    engine.captureMouse();
                }
                engine.outputRenderRecapturePending = false;
                engine.window.setRenderPreviewActions(null, null);
                engine.window.setRenderPreviewState(null);
                engine.window.setRenderPreviewMode(false);

                long selectionStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SELECTION_PICK);
                EngineSelectionController.handleObjectInteraction(engine);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SELECTION_PICK, selectionStage);
                if (EngineSafetyController.shouldHoldFrame(engine, now)) {
                    RuntimeInstrumentation.recordMode(engine.activeMode, "SAFETY_HOLD");
                    long hudStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.HUD_UI);
                    String[] holdOverlay = EngineSafetyController.augmentOverlay(
                            engine,
                            engine.debugOverlayEnabled ? EngineViewportOverlay.buildDebugHudLines(engine) : null
                    );
                    engine.window.setOverlayText(holdOverlay);
                    RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.HUD_UI, hudStage);
                        updateRenderModeSwitchOverlay(engine, System.nanoTime());
                    long blitStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.BLIT_PRESENT);
                    engine.window.blit(
                            engine.frameBuffer.getColorBuffer(),
                            engine.frameBuffer.getWidth(),
                            engine.frameBuffer.getHeight());
                    RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.BLIT_PRESENT, blitStage);
                    if (!sleepForFrame(EngineSafetyController.recoverySleepMillis())) {
                        break;
                    }
                    continue;
                }
                Vec3 cameraPosBefore = engine.camera != null ? engine.camera.getPosition() : Vec3.ZERO;
                Vec3 cameraForwardBefore = engine.camera != null ? engine.camera.getForward() : Vec3.ZERO;
                long cameraStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.CAMERA_UPDATE);
                boolean axisSnapNavigationIntent = engine.axisSnapViewActive
                        && engine.navigationPreset == Engine.NavigationPreset.BLENDER
                        && (engine.input.isMouseButtonDown(MouseEvent.BUTTON2)
                        || engine.input.getScrollDelta() != 0
                        || isKeyboardMovementIntentActive(engine));
                if (axisSnapNavigationIntent) {
                    EngineCameraRuntime.restoreProjectionAfterAxisSnap(engine);
                }
                if (!engine.objectFocusMode || engine.mouseCaptured) {
                    engine.cameraController.update(engine.input, dt);
                } else if (engine.selectedEntity != null
                        && !engine.draggingSelectedObject
                        && !engine.gizmoDragActive) {
                    engine.cameraController.frameTarget(engine.selectedEntity.getTransform().getPosition());
                    engine.camera.lookAt(engine.selectedEntity.getTransform().getPosition());
                }
                if (engine.navigationPreset == Engine.NavigationPreset.FPS) {
                    EngineCameraRuntime.rememberCurrentFpsPose(engine);
                    boolean timelineDrivesCamera = engine.timelineEnabled
                            && engine.animationPlaybackEnabled
                            && engine.sceneTimeline != null
                            && engine.sceneTimeline.hasAnyCameraKeys();
                    if (!timelineDrivesCamera) {
                        EngineCameraRuntime.syncOutputCameraFromCurrentView(engine);
                    }
                } else if (engine.navigationPreset == Engine.NavigationPreset.BLENDER) {
                    EngineCameraRuntime.rememberCurrentBlendPose(engine);
                }
                EngineCameraRuntime.refreshOrthographicClipping(engine);
                EngineTransformController.updateTransformTool(engine);
                EngineTimelineController.updatePlayback(engine, dt);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.CAMERA_UPDATE, cameraStage);

                boolean mouseInteraction = engine.input.isMouseButtonDown(MouseEvent.BUTTON1)
                        || engine.input.isMouseButtonDown(MouseEvent.BUTTON2)
                        || engine.input.isMouseButtonDown(MouseEvent.BUTTON3);
                boolean keyboardMovementIntent = isKeyboardMovementIntentActive(engine);
                boolean cameraMoved = engine.camera != null
                    && (engine.camera.getPosition().sub(cameraPosBefore).lengthSquared() > 1e-6
                    || engine.camera.getForward().sub(cameraForwardBefore).lengthSquared() > 1e-6);
                viewportInteractionActive = mouseInteraction
                        || keyboardMovementIntent
                        || cameraMoved
                        || engine.draggingSelectedObject
                        || engine.gizmoDragActive
                        || engine.sceneImportController.isBusy();
                boolean advanceDynamicScene = EngineRenderRuntime.shouldAdvanceDynamicScene(engine, viewportInteractionActive);
                if (advanceDynamicScene && engine.animationPlaybackEnabled) {
                    engine.animatedSceneElapsedSeconds += dt;
                }
                boolean sceneMutating = (advanceDynamicScene && engine.animationPlaybackEnabled)
                        || engine.draggingSelectedObject
                        || engine.gizmoDragActive
                        || engine.sceneImportController.isBusy();
                engine.viewportSceneMotionActive = sceneMutating;
                engine.viewportCameraMotionActive = cameraMoved || keyboardMovementIntent || sceneMutating;
                if (engine.viewportCameraMotionActive) {
                    RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.PREVIEW_MOTION_FRAMES, 1L);
                }

                long selectionDragStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SELECTION_PICK);
                EngineSelectionController.updateSelectedObjectDrag(engine, dt);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SELECTION_PICK, selectionDragStage);

                long sceneStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SCENE_UPDATE);
                if (engine.physicsEnabled && engine.animationPlaybackEnabled && advanceDynamicScene) {
                    physicsAccumulator += dt;
                    while (physicsAccumulator >= fixedStep) {
                        engine.applyForceFields(engine.animatedSceneElapsedSeconds);
                        engine.physicsWorld.step(fixedStep);
                        physicsAccumulator -= fixedStep;
                    }
                }

                if (advanceDynamicScene) {
                    EngineAnimationController.animateScene(engine, engine.animatedSceneElapsedSeconds);
                }
                engine.scene.update(advanceDynamicScene && engine.animationPlaybackEnabled ? dt : 0.0);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SCENE_UPDATE, sceneStage);

                long waterStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.WATER_UPDATE);
                boolean needsWaterUpdate = engine.waterSimulationEnabled
                        || engine.waterRenderEnabled
                        || engine.waterSimulation.getActiveParticleCount() > 0;
                if (needsWaterUpdate) {
                    engine.waterCollisionFloorY = engine.floorEntity != null
                            ? engine.floorEntity.getTransform().getPosition().y
                            : WaterSimulation.resolveFloorY(engine.scene);
                    Vec3 sprayGravity = engine.physicsWorld != null
                            ? engine.physicsWorld.getGravity()
                            : new Vec3(0.0, -9.81, 0.0);
                    if (engine.timelineEnabled) {
                        double sprayTimeSeconds = engine.timelineCurrentFrame / Math.max(1.0, engine.timelineFps);
                        engine.waterSimulation.syncToTime(
                                engine.scene,
                                sprayTimeSeconds,
                                sprayGravity,
                                engine.waterCollisionFloorY,
                                engine.waterSimulationEnabled
                        );
                    } else {
                        engine.waterSimulation.update(
                                engine.scene,
                                dt,
                                sprayGravity,
                                engine.waterCollisionFloorY,
                                engine.waterSimulationEnabled && engine.animationPlaybackEnabled && advanceDynamicScene
                        );
                    }
                }
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.WATER_UPDATE, waterStage);

                engine.refreshObjectInspectorValues();
                engine.refreshTimelineUi();

                long visibilityStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.VISIBILITY);
                engine.applySceneVisibility(false);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.VISIBILITY, visibilityStage);

                long resolveStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_MODE_RESOLVE);
                EngineRenderRuntime.updateRealtimePerformanceState(engine, viewportInteractionActive);
                viewportRenderMode = EngineRenderRuntime.resolveViewportRenderMode(engine, viewportInteractionActive);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_MODE_RESOLVE, resolveStage);
                engine.viewportDisplayedMode = viewportRenderMode;
                engine.viewportNavigationPreviewActive = viewportRenderMode != (engine.activeMode == null ? RenderMode.PHONG : engine.activeMode);
                RuntimeInstrumentation.recordMode(engine.activeMode, viewportRenderMode);
                EngineRenderRuntime.synchronizeDisplayedPreviewLayerPolicy(engine, viewportRenderMode);
                boolean renderModeSwitchFadePhase = isRenderModeSwitchFadePhase(engine, System.nanoTime());
                if (!renderModeSwitchFadePhase) {
                    try {
                        long selectionPrepassStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SELECTION_PREPASS);
                        if (engine.selectedEntity != null && engine.activeMode != RenderMode.TEMPORAL_NOISE) {
                            SelectionOverlayUtil.computeSelectionCoveragePass(
                                    engine.selectedEntity,
                                    engine.camera,
                                    engine.frameBuffer.getWidth(),
                                    engine.frameBuffer.getHeight()
                            );
                        }
                        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SELECTION_PREPASS, selectionPrepassStage);

                        long previewRenderStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
                        if (engine.viewportNavigationPreviewActive) {
                            engine.activeRenderer = EngineRenderRuntime.configureRendererForMode(engine, engine.activeMode);
                            if (engine.renderPipeline != null) {
                                engine.renderPipeline.setActiveRenderer(engine.activeRenderer);
                            }
                            Renderer previewRenderer = EngineRenderRuntime.configureRendererForMode(engine, viewportRenderMode);
                            if (previewRenderer != null) {
                                previewRenderer.render(engine.scene, engine.camera, engine.frameBuffer, elapsed);
                            }
                        } else if (engine.renderPipeline != null) {
                            engine.renderPipeline.execute(engine.scene, engine.camera, engine.frameBuffer, elapsed);
                        } else if (engine.activeRenderer != null) {
                            engine.activeRenderer.render(engine.scene, engine.camera, engine.frameBuffer, elapsed);
                        }
                        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL, previewRenderStage);
                        if (engine.startupFirstRenderedFrameNanos == 0L) {
                            engine.startupFirstRenderedFrameNanos = System.nanoTime();
                        }

                        long overlayStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.OVERLAYS);
                        if (engine.waterRenderEnabled) {
                            WaterParticleRenderer.render(engine.waterSimulation, engine.camera, engine.frameBuffer);
                        }
                        if (engine.selectedEntity != null && engine.activeMode != RenderMode.TEMPORAL_NOISE) {
                            SelectionOverlayUtil.drawCachedSelectionOutline(engine.frameBuffer);
                        }
                        EngineViewportOverlay.drawEditorDebugOverlays(engine, engine.frameBuffer);
                        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.OVERLAYS, overlayStage);
                    } catch (Throwable renderFailure) {
                        System.out.println("Render failure in mode " + engine.activeMode + ": " + renderFailure.getMessage());
                        renderFailure.printStackTrace(System.out);
                        if (engine.activeMode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
                            if (!engine.pathAccumulationLock) {
                                engine.pathTracerRenderer.setParameter("reset", true);
                            }
                        } else if (engine.activeMode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
                            engine.rayTracerRenderer.setParameter("reset", true);
                        }
                        if (engine.frameBuffer != null) {
                            engine.frameBuffer.clear(0xFF050608, 1.0f);
                        }
                        EngineSafetyController.recordRenderFailure(engine, System.nanoTime());
                    }
                }
                long hudStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.HUD_UI);
                Window renderWindow = engine.window;
                if (renderWindow == null) {
                    continue;
                }
                renderWindow.setOverlayText(EngineSafetyController.augmentOverlay(
                        engine,
                        engine.debugOverlayEnabled ? EngineViewportOverlay.buildDebugHudLines(engine) : null
                ));
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.HUD_UI, hudStage);
                updateRenderModeSwitchOverlay(engine, System.nanoTime());
                long blitStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.BLIT_PRESENT);
                renderWindow.blit(
                        engine.frameBuffer.getColorBuffer(),
                        engine.frameBuffer.getWidth(),
                        engine.frameBuffer.getHeight());
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.BLIT_PRESENT, blitStage);
                if (engine.startupFirstPresentedFrameNanos == 0L) {
                    engine.startupFirstPresentedFrameNanos = System.nanoTime();
                }
            } finally {
                RuntimeInstrumentation.endFrame(frameToken);
            }

            if (!engine.startupInputReady && engine.window != null && engine.window.isInputReady()) {
                engine.startupInputReady = true;
            }
            if (engine.startupFirstInteractiveFrameNanos == 0L
                    && engine.startupFirstPresentedFrameNanos > 0L
                    && engine.startupInputReady) {
                engine.startupFirstInteractiveFrameNanos = System.nanoTime();
            }
            if (!startupSplashClosed
                    && engine.startupFirstInteractiveFrameNanos > 0L
                    && loadingScreen != null) {
                loadingScreen.close();
                startupSplashClosed = true;
                engine.startupSplashClosedNanos = System.nanoTime();
            }
            if (engine.window != null
                    && engine.startupFirstInteractiveFrameNanos > 0L
                    && engine.startupFocusAcquiredNanos == 0L
                    && System.nanoTime() >= nextFocusRetryNanos) {
                if (engine.window.requestCanvasFocus()) {
                    long focusSignalNanos = System.nanoTime();
                    if (engine.startupFocusRequestedNanos == 0L) {
                        engine.startupFocusRequestedNanos = focusSignalNanos;
                    }
                    if (engine.startupFocusAcquiredNanos == 0L) {
                        engine.startupFocusAcquiredNanos = focusSignalNanos;
                        engine.startupFocusReady = true;
                    }
                }
                nextFocusRetryNanos = System.nanoTime() + 120_000_000L;
            }
            if (engine.window != null
                    && engine.startupFocusAcquiredNanos == 0L
                    && engine.window.isCanvasFocusOwner()) {
                engine.startupFocusAcquiredNanos = System.nanoTime();
                engine.startupFocusReady = true;
            }
            if (!startupTimelinePrinted
                    && startupSplashClosed
                    && (engine.startupFocusAcquiredNanos > 0L
                    || System.nanoTime() - engine.startupSplashClosedNanos >= 1_200_000_000L)) {
                printStartupTimeline(engine);
                startupTimelinePrinted = true;
            }

            long frameEnd = System.nanoTime();
            EngineRenderRuntime.recordViewportFrameTime(engine, (frameEnd - frameStart) / 1_000_000.0, viewportRenderMode);
            EngineSafetyController.recordFrame(engine, (frameEnd - frameStart) / 1_000_000.0, frameEnd, viewportInteractionActive);
            frameCounter++;
            fpsAccumulator += dt;
            frameTimeAccumulatorMs += (frameEnd - frameStart) / 1_000_000.0;
            if (fpsAccumulator >= 0.5) {
                double fps = frameCounter / fpsAccumulator;
                double avgMs = frameTimeAccumulatorMs / frameCounter;
                double smoothedMs = Math.max(0.01, engine.viewportSmoothedFrameMs);
                double smoothedFps = 1000.0 / smoothedMs;
                double displayedFps = Math.min(fps, smoothedFps);
                double displayedMs = Math.max(avgMs, smoothedMs);
                String perfText = switch (engine.activeMode) {
                    case PATH_TRACING -> String.format(
                        "PT spp %d | CPU x%d",
                        engine.pathTracerRenderer.getAccumulatedSamples(),
                        engine.pathTracerRenderer.getWorkerCount()
                    );
                    case RAY_TRACING -> String.format(
                        "RT spp %d | CPU x%d",
                        engine.rayTracerRenderer.getAccumulatedSamples(),
                        engine.rayTracerRenderer.getWorkerCount()
                    );
                    default -> String.format("MT %s x%d",
                        engine.parallelRasterEnabled ? "ON" : "OFF", engine.parallelWorkerCount);
                };
                engine.window.setTitle(String.format(
                        "%s | %s%s | %.1f FPS | %.2f ms | scale %.2f | %s",
                        Engine.WINDOW_TITLE,
                        engine.activeMode,
                        engine.viewportNavigationPreviewActive && engine.viewportDisplayedMode != null
                                ? " [view " + engine.viewportDisplayedMode + "]"
                                : "",
                        displayedFps,
                        displayedMs,
                        EngineRenderRuntime.effectiveRenderScale(engine),
                        perfText
                ));
                    engine.hudFps = displayedFps;
                    engine.hudFrameTimeMs = displayedMs;
                fpsAccumulator = 0.0;
                frameTimeAccumulatorMs = 0.0;
                frameCounter = 0;
            }

            if (isRenderModeSwitchFadePhase(engine, frameEnd)) {
                if (!sleepForFrame(8)) {
                    break;
                }
            } else if (!sleepForViewportCap(engine, frameEnd - frameStart)) {
                break;
            }
        }

        if (!startupSplashClosed && loadingScreen != null) {
            loadingScreen.close();
            if (engine.startupSplashClosedNanos == 0L) {
                engine.startupSplashClosedNanos = System.nanoTime();
            }
        }
        if (!startupTimelinePrinted) {
            printStartupTimeline(engine);
        }

        shutdown(engine);
    }

    static void setRenderMode(Engine engine, RenderMode mode) {
        if (engine == null || mode == null) {
            return;
        }
        if (engine.activeMode != mode) {
            long now = System.nanoTime();
            engine.renderModeSwitchTransitionActive = true;
            engine.renderModeSwitchTargetLabel = formatRenderModeLabel(mode);
            engine.renderModeSwitchStartNanos = now;
            engine.renderModeSwitchRevealStartNanos = 0L;
            engine.renderModeSwitchSampleBaseline = currentAccumulatedSamplesForMode(engine, mode);

            // Reset progresivní historie zajistí čistý start po přepnutí režimu.
            if (engine.rayTracerRenderer != null) {
                engine.rayTracerRenderer.setParameter("reset", true);
            }
            if (engine.pathTracerRenderer != null) {
                engine.pathTracerRenderer.setParameter("reset", true);
            }

            // Overlay zobrazím hned po kliknutí, ještě před rekonfigurací rendereru.
            if (engine.window != null) {
                engine.window.presentRenderModeSwitchOverlayNow(engine.renderModeSwitchTargetLabel, 0.08);
            }
        }
        engine.activeMode = mode;
        EngineRenderRuntime.resetViewportAdaptiveAssist(engine);
        engine.activeRenderer = EngineRenderRuntime.configureRendererForMode(engine, mode);
        engine.viewportDisplayedMode = mode;
        engine.viewportNavigationPreviewActive = false;
        if (engine.renderPipeline != null) {
            engine.renderPipeline.setActiveRenderer(engine.activeRenderer);
            EngineRenderRuntime.rebuildPostPipeline(engine);
        }
        if (engine.frameBuffer != null) {
            EngineRenderRuntime.applyRenderScale(engine);
        }
        System.out.println("Render mode: " + engine.activeMode);
        engine.refreshUiIndicators();
    }

    private static String formatRenderModeLabel(RenderMode mode) {
        if (mode == null) {
            return "Phong";
        }
        return switch (mode) {
            case MODEL -> "Model";
            case BASIC -> "Basic";
            case PHONG -> "Phong";
            case WIREFRAME -> "Wireframe";
            case RAY_TRACING -> "Ray Tracing";
            case PATH_TRACING -> "Path Tracing";
            case DITHERING -> "Dithering";
            case TEMPORAL_NOISE -> "Temporal Noise";
            case HEX_MOSAIC -> "Hex Mosaic";
        };
    }

    private static void updateRenderModeSwitchOverlay(Engine engine, long now) {
        if (engine == null || engine.window == null) {
            return;
        }
        if (!engine.renderModeSwitchTransitionActive) {
            engine.window.setRenderModeSwitchOverlay("", 0.0, false);
            return;
        }

        long start = engine.renderModeSwitchStartNanos;
        if (start <= 0L) {
            start = now;
            engine.renderModeSwitchStartNanos = start;
        }
        long elapsed = Math.max(0L, now - start);
        double alpha = 1.0;
        long revealStart = engine.renderModeSwitchRevealStartNanos;
        if (revealStart <= 0L) {
            double fadeIn = Math.min(1.0, elapsed / (double) RENDER_MODE_SWITCH_FADE_IN_NS);
            alpha = fadeIn;
            long accumulatedSamples = currentAccumulatedSamplesForActiveMode(engine);
            boolean minShowReached = elapsed >= RENDER_MODE_SWITCH_MIN_SHOW_NS;
            boolean enoughSamples = !usesSampleAccumulation(engine.activeMode)
                    || accumulatedSamples >= requiredModeSwitchRevealSamples(engine);
            if (minShowReached && enoughSamples) {
                engine.renderModeSwitchRevealStartNanos = now;
                revealStart = now;
            }
        }

        if (revealStart > 0L) {
            long revealElapsed = Math.max(0L, now - revealStart);
            double fadeOut = Math.min(1.0, revealElapsed / (double) RENDER_MODE_SWITCH_FADE_OUT_NS);
            alpha = 1.0 - fadeOut;
            if (alpha <= 0.0) {
                engine.renderModeSwitchTransitionActive = false;
                engine.renderModeSwitchRevealStartNanos = 0L;
                engine.renderModeSwitchStartNanos = 0L;
                engine.renderModeSwitchSampleBaseline = -1L;
                engine.window.setRenderModeSwitchOverlay("", 0.0, false);
                return;
            }
        }

        engine.window.setRenderModeSwitchOverlay(
                engine.renderModeSwitchTargetLabel,
                alpha,
                true);
    }

    private static long currentAccumulatedSamplesForActiveMode(Engine engine) {
        if (engine == null || engine.activeMode == null) {
            return RENDER_MODE_SWITCH_MIN_SAMPLES;
        }
        long raw = currentAccumulatedSamplesForMode(engine, engine.activeMode);
        long baseline = engine.renderModeSwitchSampleBaseline;
        if (baseline < 0L) {
            return raw;
        }
        if (raw >= baseline) {
            return Math.max(0L, raw - baseline);
        }
        // Reset rendereru může shodit čítač pod baseline. Raw pak reprezentuje nové samples po přepnutí.
        return raw;
    }

    private static long currentAccumulatedSamplesForMode(Engine engine, RenderMode mode) {
        if (engine == null || mode == null) {
            return 0L;
        }
        if (mode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            return Math.max(0L, engine.pathTracerRenderer.getAccumulatedSamples());
        }
        if (mode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            return Math.max(0L, engine.rayTracerRenderer.getAccumulatedSamples());
        }
        return 0L;
    }

    private static boolean usesSampleAccumulation(RenderMode mode) {
        return mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING;
    }

    private static long requiredModeSwitchRevealSamples(Engine engine) {
        RenderMode mode = engine == null ? null : engine.activeMode;
        if (!usesSampleAccumulation(mode)) {
            return RENDER_MODE_SWITCH_MIN_SAMPLES;
        }
        double viewportMegaPixels = 0.0;
        if (engine != null && engine.frameBuffer != null) {
            viewportMegaPixels = (engine.frameBuffer.getWidth() * engine.frameBuffer.getHeight()) / 1_000_000.0;
        }
        if (viewportMegaPixels >= RENDER_MODE_SWITCH_LARGE_VIEWPORT_MP) {
            return RENDER_MODE_SWITCH_HEAVY_MAX_VIEWPORT_MIN_SAMPLES;
        }
        return RENDER_MODE_SWITCH_HEAVY_MIN_SAMPLES;
    }

    private static long resolveRuntimeTaskBudgetNanos(Engine engine) {
        RenderMode mode = engine == null || engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        if (mode == RenderMode.PATH_TRACING) {
            return (engine != null && (engine.safetyRecoveryActive || engine.viewportCriticalPreviewActive))
                    ? 1_500_000L
                    : 2_500_000L;
        }
        if (mode == RenderMode.RAY_TRACING) {
            return (engine != null && (engine.safetyRecoveryActive || engine.viewportCriticalPreviewActive))
                    ? 2_000_000L
                    : 3_500_000L;
        }
        return 6_000_000L;
    }

    private static int resolveRuntimeTaskBatchSize(Engine engine) {
        RenderMode mode = engine == null || engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
        if (mode == RenderMode.PATH_TRACING) {
            return 2;
        }
        if (mode == RenderMode.RAY_TRACING) {
            return 3;
        }
        return 12;
    }

    private static boolean isRenderModeSwitchFadePhase(Engine engine, long now) {
        if (engine == null || !engine.renderModeSwitchTransitionActive) {
            return false;
        }
        long start = engine.renderModeSwitchStartNanos;
        if (start <= 0L) {
            return false;
        }
        long elapsed = Math.max(0L, now - start);
        long revealStart = engine.renderModeSwitchRevealStartNanos;
        if (revealStart <= 0L) {
            return elapsed < RENDER_MODE_SWITCH_FADE_IN_NS;
        }
        long revealElapsed = Math.max(0L, now - revealStart);
        return revealElapsed < RENDER_MODE_SWITCH_FADE_OUT_NS;
    }

    private static boolean sleepForFrame(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean sleepForViewportCap(Engine engine, long frameDurationNanos) {
        if (engine == null) {
            return sleepForFrame(1);
        }
        double targetFps = Math.max(12.0, Math.min(25.0, engine.viewportTargetFps));
        long targetFrameNanos = (long) (1_000_000_000.0 / targetFps);
        long remainingNanos = targetFrameNanos - Math.max(0L, frameDurationNanos);
        if (remainingNanos <= 0L) {
            return sleepForFrame(1);
        }
        long sleepMillis = Math.max(1L, remainingNanos / 1_000_000L);
        return sleepForFrame(sleepMillis);
    }

    private static boolean isKeyboardMovementIntentActive(Engine engine) {
        if (engine == null || engine.input == null) {
            return false;
        }
        return engine.input.isKeyDown(KeyEvent.VK_W)
                || engine.input.isKeyDown(KeyEvent.VK_A)
                || engine.input.isKeyDown(KeyEvent.VK_S)
                || engine.input.isKeyDown(KeyEvent.VK_D)
                || engine.input.isKeyDown(KeyEvent.VK_Q)
                || engine.input.isKeyDown(KeyEvent.VK_E)
                || engine.input.isKeyDown(KeyEvent.VK_SPACE)
                || engine.input.isKeyDown(KeyEvent.VK_SHIFT)
                || engine.input.isKeyDown(KeyEvent.VK_UP)
                || engine.input.isKeyDown(KeyEvent.VK_DOWN)
                || engine.input.isKeyDown(KeyEvent.VK_LEFT)
                || engine.input.isKeyDown(KeyEvent.VK_RIGHT);
    }

    private static void printStartupTimeline(Engine engine) {
        if (engine == null || engine.startupAppStartNanos <= 0L) {
            return;
        }
        System.out.println(String.format(
                "Interactive[startup][timeline] app_start=0.000ms window_created=%s first_rendered_frame=%s first_presented_frame=%s first_interactive_frame=%s splash_closed=%s focus_requested=%s focus_acquired=%s input_ready=%s focus_ready=%s",
                formatStartupEvent(engine.startupAppStartNanos, engine.startupWindowCreatedNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupFirstRenderedFrameNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupFirstPresentedFrameNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupFirstInteractiveFrameNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupSplashClosedNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupFocusRequestedNanos),
                formatStartupEvent(engine.startupAppStartNanos, engine.startupFocusAcquiredNanos),
                engine.startupInputReady,
                engine.startupFocusReady));
    }

    private static String formatStartupEvent(long appStartNanos, long eventNanos) {
        if (eventNanos <= 0L || appStartNanos <= 0L) {
            return "n/a";
        }
        return String.format("%.3fms", Math.max(0.0, (eventNanos - appStartNanos) / 1_000_000.0));
    }

    static void shutdown(Engine engine) {
        engine.running = false;
        engine.mouseCaptured = false;
        if (engine.rayTracerRenderer != null) {
            engine.rayTracerRenderer.setParameter("shutdown", true);
        }
        if (engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.setParameter("shutdown", true);
        }
        if (engine.rasterRenderer != null) {
            engine.rasterRenderer.setParameter("shutdown", true);
        }
        engine.outputRenderController.dispose();
        engine.sceneImportController.dispose();
        if (engine.editorShortcutRouter != null) {
            engine.editorShortcutRouter.uninstall();
            engine.editorShortcutRouter = null;
        }
        EngineBottomDock.disposeDetached(engine);
        if (engine.window != null) {
            engine.window.setCursorCaptured(false);
            engine.window.dispose();
            engine.window = null;
        }
    }
}
