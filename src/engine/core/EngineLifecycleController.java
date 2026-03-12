package engine.core;

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
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.sim.water.WaterParticleRenderer;
import engine.sim.water.WaterSimulation;
import engine.util.SelectionOverlayUtil;

import java.awt.AWTException;
import java.awt.Robot;
import java.awt.event.MouseEvent;

final class EngineLifecycleController {
    private EngineLifecycleController() {
    }

    static void start(Engine engine) {
        if (engine.running) {
            return;
        }

        final int width = engine.baseWidth;
        final int height = engine.baseHeight;

        engine.window = new Window(Engine.WINDOW_TITLE, width, height);
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

        engine.running = true;
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

            engine.input.poll();
            EngineRenderRuntime.handleWindowResizeIfNeeded(engine);
            EngineSelectionController.handleObjectInteraction(engine);
            EngineCameraRuntime.updateMouseCaptureDelta(engine);
            EngineHotkeyRouter.handle(engine);
            engine.sceneImportController.update(engine);
            engine.outputRenderController.processPendingRequest(
                    engine.scene,
                    (w, h) -> EngineCameraRuntime.buildOutputRenderCamera(engine, w, h),
                    outputPass -> engine.applySceneVisibility(outputPass),
                    engine.frustumCullingEnabled,
                    engine.backfaceCullingEnabled,
                    frame -> EngineTimelineController.applyFrameForOutput(engine, frame)
            );
            if (engine.outputRenderController.isRenderInProgress()) {
                engine.window.setOverlayText(engine.outputRenderController.getViewportPreviewLines());
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
                try {
                    Thread.sleep(33);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            if (EngineSafetyController.shouldHoldFrame(engine, now)) {
                String[] holdOverlay = EngineSafetyController.augmentOverlay(
                        engine,
                        engine.debugOverlayEnabled ? EngineViewportOverlay.buildDebugHudLines(engine, elapsed) : null
                );
                engine.window.setOverlayText(holdOverlay);
                engine.window.blit(
                        engine.frameBuffer.getColorBuffer(),
                        engine.frameBuffer.getWidth(),
                        engine.frameBuffer.getHeight());
                try {
                    Thread.sleep(EngineSafetyController.recoverySleepMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }
            Vec3 cameraPosBefore = engine.camera != null ? engine.camera.getPosition() : Vec3.ZERO;
            Vec3 cameraForwardBefore = engine.camera != null ? engine.camera.getForward() : Vec3.ZERO;
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
            EngineSelectionController.updateSelectedObjectDrag(engine, dt);
            EngineTransformController.updateTransformTool(engine);
            EngineTimelineController.updatePlayback(engine, dt);

            if (engine.physicsEnabled && engine.animationPlaybackEnabled) {
                physicsAccumulator += dt;
                while (physicsAccumulator >= fixedStep) {
                    engine.applyForceFields(elapsed);
                    engine.physicsWorld.step(fixedStep);
                    physicsAccumulator -= fixedStep;
                }
            }

            EngineAnimationController.animateScene(engine, elapsed);
            engine.scene.update(engine.animationPlaybackEnabled ? dt : 0.0);
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
                        engine.waterSimulationEnabled && engine.animationPlaybackEnabled
                );
            }
            engine.refreshObjectInspectorValues();
            engine.refreshTimelineUi();
            engine.applySceneVisibility(false);
            boolean mouseInteraction = engine.input.isMouseButtonDown(MouseEvent.BUTTON1)
                    || engine.input.isMouseButtonDown(MouseEvent.BUTTON2)
                    || engine.input.isMouseButtonDown(MouseEvent.BUTTON3);
            boolean cameraMoved = engine.camera != null
                    && (engine.camera.getPosition().sub(cameraPosBefore).lengthSquared() > 1e-8
                    || engine.camera.getForward().sub(cameraForwardBefore).lengthSquared() > 1e-8);
            boolean viewportInteractionActive = mouseInteraction
                    || cameraMoved
                    || engine.draggingSelectedObject
                    || engine.gizmoDragActive
                    || engine.objectFocusMode
                    || engine.sceneImportController.isBusy();
            EngineRenderRuntime.updateRealtimePerformanceState(engine, viewportInteractionActive);
            RenderMode viewportRenderMode = EngineRenderRuntime.resolveViewportRenderMode(engine, viewportInteractionActive);
            engine.viewportDisplayedMode = viewportRenderMode;
            engine.viewportNavigationPreviewActive = viewportRenderMode != (engine.activeMode == null ? RenderMode.PHONG : engine.activeMode);
            try {
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
                if (engine.waterRenderEnabled) {
                    WaterParticleRenderer.render(engine.waterSimulation, engine.camera, engine.frameBuffer);
                }
                if (engine.activeMode != RenderMode.TEMPORAL_NOISE) {
                    SelectionOverlayUtil.drawSelectedOutline(engine.frameBuffer, engine.selectedEntity, engine.camera);
                }
                EngineViewportOverlay.drawEditorDebugOverlays(engine, engine.frameBuffer);
            } catch (Throwable renderFailure) {
                System.out.println("Render failure in mode " + engine.activeMode + ": " + renderFailure.getMessage());
                renderFailure.printStackTrace(System.out);
                if (engine.activeMode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
                    engine.pathTracerRenderer.setParameter("reset", true);
                } else if (engine.activeMode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
                    engine.rayTracerRenderer.setParameter("reset", true);
                }
                if (engine.frameBuffer != null) {
                    engine.frameBuffer.clear(0xFF050608, 1.0f);
                }
                EngineSafetyController.recordRenderFailure(engine, System.nanoTime());
            }
            engine.window.setOverlayText(EngineSafetyController.augmentOverlay(
                    engine,
                    engine.debugOverlayEnabled ? EngineViewportOverlay.buildDebugHudLines(engine, elapsed) : null
            ));
            engine.window.blit(
                    engine.frameBuffer.getColorBuffer(),
                    engine.frameBuffer.getWidth(),
                    engine.frameBuffer.getHeight());

            long frameEnd = System.nanoTime();
            EngineRenderRuntime.recordViewportFrameTime(engine, (frameEnd - frameStart) / 1_000_000.0);
            EngineSafetyController.recordFrame(engine, (frameEnd - frameStart) / 1_000_000.0, frameEnd);
            frameCounter++;
            fpsAccumulator += dt;
            frameTimeAccumulatorMs += (frameEnd - frameStart) / 1_000_000.0;
            if (fpsAccumulator >= 0.5) {
                double fps = frameCounter / fpsAccumulator;
                double avgMs = frameTimeAccumulatorMs / frameCounter;
                String perfText;
                if (engine.activeMode == RenderMode.PATH_TRACING) {
                    perfText = String.format(
                            "PT spp %d | CPU x%d",
                            engine.pathTracerRenderer.getAccumulatedSamples(),
                            engine.pathTracerRenderer.getWorkerCount()
                    );
                } else if (engine.activeMode == RenderMode.RAY_TRACING) {
                    perfText = String.format(
                            "RT spp %d | CPU x%d",
                            engine.rayTracerRenderer.getAccumulatedSamples(),
                            engine.rayTracerRenderer.getWorkerCount()
                    );
                } else {
                    perfText = String.format("MT %s x%d",
                            engine.parallelRasterEnabled ? "ON" : "OFF", engine.parallelWorkerCount);
                }
                engine.window.setTitle(String.format(
                        "%s | %s%s | %.1f FPS | %.2f ms | scale %.2f | %s",
                        Engine.WINDOW_TITLE,
                        engine.activeMode,
                        engine.viewportNavigationPreviewActive && engine.viewportDisplayedMode != null
                                ? " [view " + engine.viewportDisplayedMode + "]"
                                : "",
                        fps,
                        avgMs,
                        EngineRenderRuntime.effectiveRenderScale(engine),
                        perfText
                ));
                engine.hudFps = fps;
                engine.hudFrameTimeMs = avgMs;
                fpsAccumulator = 0.0;
                frameTimeAccumulatorMs = 0.0;
                frameCounter = 0;
            }

            try {
                if (engine.activeMode != RenderMode.PATH_TRACING) {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        shutdown(engine);
    }

    static void setRenderMode(Engine engine, RenderMode mode) {
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
        if (engine.window != null) {
            engine.window.setCursorCaptured(false);
            engine.window.dispose();
            engine.window = null;
        }
    }
}
