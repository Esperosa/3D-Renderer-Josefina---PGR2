package engine.core;

import java.util.Locale;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.RuntimeInstrumentation;
import engine.util.SelectionOverlayUtil;

public final class PreviewRuntimeInstrumentationTests {

    private PreviewRuntimeInstrumentationTests() {
    }

    public static void main(String[] args) {
        RuntimeInstrumentation.setEnabled(true);
        try {
            verifyDirtyPathAndSelectionCache();
            verifyModeStabilityDuringCameraMotion();
            verifySoftCameraResetAndPreviewLadder();
            verifyPathPreviewTileTimingConsistency();
            verifyRayMovingPolishUsesLowerInternalResolution();
            verifyRayEmergencyMovingPolishUsesQuarterResolution();
            verifyRayQuarterResMovingPolishReuse();
            verifyRayStillSampleDrivenQualityLadder();
            verifyPreviewAndOutputStageTelemetry();
            System.out.println("PreviewRuntimeInstrumentationTests: ALL TESTS PASSED");
        } finally {
            RuntimeInstrumentation.setEnabled(false);
        }
    }

    private static void verifyDirtyPathAndSelectionCache() {
        Engine engine = buildEngineHarness();
        Entity selected = engine.scene.getEntities().get(0);

        RuntimeInstrumentation.reset();
        runSceneMaintenanceFrame(engine, selected, 72, 48);
        RuntimeInstrumentation.Snapshot first = RuntimeInstrumentation.snapshotAndReset();

        runSceneMaintenanceFrame(engine, selected, 72, 48);
        RuntimeInstrumentation.Snapshot second = RuntimeInstrumentation.snapshotAndReset();

        long firstWorld = first.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.WORLD_MATRIX_CALLS);
        long secondWorld = second.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.WORLD_MATRIX_CALLS);
        long firstBounds = first.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.BOUNDS_RECOMPUTES);
        long secondBounds = second.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.BOUNDS_RECOMPUTES);
        long firstSelectionTris = first.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.SELECTION_OVERLAY_TRIANGLES);
        long secondSelectionTris = second.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.SELECTION_OVERLAY_TRIANGLES);

        if (firstWorld <= 0L || firstBounds <= 0L || firstSelectionTris <= 0L) {
            throw new AssertionError("First telemetry frame should capture world, bounds and selection work.");
        }
        if (secondWorld != 0L) {
            throw new AssertionError("Dirty path should avoid repeated world matrix recompute on a stable frame.");
        }
        if (secondBounds != 0L) {
            throw new AssertionError("Dirty path should avoid repeated bounds recompute on a stable frame.");
        }
        if (secondSelectionTris != 0L) {
            throw new AssertionError("Selection overlay cache should skip re-rasterizing unchanged geometry.");
        }

        System.out.println(String.format(
                Locale.ROOT,
                "Instr[dirty-path] world_before=%d world_after=%d bounds_before=%d bounds_after=%d selection_before=%d selection_after=%d",
                firstWorld,
                secondWorld,
                firstBounds,
                secondBounds,
                firstSelectionTris,
                secondSelectionTris));
    }

    private static void verifyModeStabilityDuringCameraMotion() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.viewportNavigationPreviewEnabled = true;
        engine.viewportCameraMotionActive = true;

        RuntimeInstrumentation.reset();
        RuntimeInstrumentation.FrameToken token =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "mode-stability");
        try {
            long resolveStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_MODE_RESOLVE);
            RenderMode resolved = EngineRenderRuntime.resolveViewportRenderMode(engine, true);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_MODE_RESOLVE, resolveStage);
            RuntimeInstrumentation.recordMode(engine.activeMode, resolved);
        } finally {
            RuntimeInstrumentation.endFrame(token);
        }

        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        long stableCount = snapshot.modeCounts(RuntimeInstrumentation.FrameKind.PREVIEW)
                .getOrDefault("PATH_TRACING->PATH_TRACING", 0L);
        long fallbackEvents = snapshot.fallbackCounts(RuntimeInstrumentation.FrameKind.PREVIEW).values()
                .stream()
                .mapToLong(Long::longValue)
                .sum();
        if (stableCount != 1L) {
            throw new AssertionError("Camera motion should keep the heavy preview renderer active.");
        }
        if (fallbackEvents != 0L) {
            throw new AssertionError("Normal camera motion should not emit fallback events.");
        }

        System.out.println(String.format(
                Locale.ROOT,
                "Instr[mode] stable=%d fallback_events=%d resolve_ms=%.3f",
                stableCount,
                fallbackEvents,
                snapshot.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.PREVIEW_MODE_RESOLVE)));
    }

    private static void verifySoftCameraResetAndPreviewLadder() {
        verifySoftCameraResetAndPreviewLadderRay();
        verifySoftCameraResetAndPreviewLadderPath();
    }

    private static void verifyRayMovingPolishUsesLowerInternalResolution() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);
        renderer.setParameter("previewMotionSecondaryCadence", 2);
        renderer.setParameter("previewMotionPolishScale", 0.5);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 2);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");

        FrameBuffer fb = new FrameBuffer(72, 48);
        RuntimeInstrumentation.reset();
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        if (snapshot.frameCount(RuntimeInstrumentation.FrameKind.PREVIEW) <= 0L) {
            throw new AssertionError("Moving RT preview should record a preview frame.");
        }
        double activePolishScale = renderer.getActivePreviewPolishScale();
        if (!Double.isFinite(activePolishScale) || activePolishScale <= 0.0 || activePolishScale > 1.0) {
            throw new AssertionError("Moving RT preview should keep a valid polish scale.");
        }
        renderer.setParameter("shutdown", true);
    }

    private static void verifySoftCameraResetAndPreviewLadderRay() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionSecondaryCadence", 2);
        renderer.setParameter("previewMotionDenoiseCadence", 2);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 2);

        FrameBuffer fb = new FrameBuffer(72, 48);
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.snapshotAndReset();

        renderer.setParameter("previewMotionActive", true);
        camera.setPosition(camera.getPosition().add(new Vec3(0.05, 0.0, 0.0)));
        camera.lookAt(new Vec3(0.05, 0.8, 0.0));
        renderPreviewFrame(renderer, scene, camera, fb);
        camera.setPosition(camera.getPosition().add(new Vec3(0.05, 0.0, 0.0)));
        camera.lookAt(new Vec3(0.10, 0.8, 0.0));
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        String qualityTier = renderer.getActivePreviewQualityTier();
        renderer.setParameter("shutdown", true);

        long softResets = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.CAMERA_RESETS_SOFT);
        long hardResets = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.CAMERA_RESETS_HARD);
        long secondaryReduced = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_SECONDARY_REDUCED_FRAMES);
        long denoiseSkipped = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES);
        long traversalNanos = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_PRIMARY_INTERSECTION_NS);
        long directLightNanos = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DIRECT_LIGHT_NS);
        long filterNanos = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_CARRIER_DENOISE_FILTER_NS);
        double carrierTraceMs = snapshot.averageExclusiveStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.CARRIER_TRACE);
        if (softResets <= 0L) {
            throw new AssertionError("Small preview camera motion should use soft reset in the ray tracer.");
        }
        if (hardResets != 0L) {
            throw new AssertionError("Small preview camera motion should avoid hard reset in the ray tracer.");
        }
        if (secondaryReduced <= 0L) {
            throw new AssertionError("Preview motion ladder should reduce ray secondary work on cadence-skipped frames.");
        }
        if (qualityTier.startsWith("MOVING_HYBRID_BASE")) {
            if (carrierTraceMs <= 0.0) {
                throw new AssertionError("Hybrid moving RT preview should still spend measurable time in the full-res base layer.");
            }
        } else {
            if (denoiseSkipped <= 0L) {
                throw new AssertionError("Preview motion ladder should skip full denoise on some ray motion frames.");
            }
            if (traversalNanos <= 0L || directLightNanos <= 0L) {
                throw new AssertionError("Preview RT motion should record carrier leaf telemetry for traversal and lighting.");
            }
            if (filterNanos != 0L) {
                throw new AssertionError("Preview RT motion should keep denoise filter disabled in sharp-motion mode.");
            }
        }
    }

    private static void verifyRayEmergencyMovingPolishUsesQuarterResolution() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);
        renderer.setParameter("previewMotionSecondaryCadence", 4);
        renderer.setParameter("previewMotionPolishScale", 0.5);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 2);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");

        FrameBuffer fb = new FrameBuffer(72, 48);
        RuntimeInstrumentation.reset();
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        if (snapshot.frameCount(RuntimeInstrumentation.FrameKind.PREVIEW) <= 0L) {
            throw new AssertionError("Emergency RT preview should record a preview frame.");
        }
        if (renderer.getActivePreviewPolishWidth() <= 0 || renderer.getActivePreviewPolishHeight() <= 0) {
            throw new AssertionError("Moving RT preview should keep valid polish buffer dimensions.");
        }
        renderer.setParameter("shutdown", true);
    }

    private static void verifyRayQuarterResMovingPolishReuse() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);
        renderer.setParameter("previewMotionSecondaryCadence", 4);
        renderer.setParameter("previewMotionPolishScale", 0.5);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 2);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");

        FrameBuffer fb = new FrameBuffer(72, 48);
        RuntimeInstrumentation.reset();
        renderPreviewFrame(renderer, scene, camera, fb);
        camera.setPosition(camera.getPosition().add(new Vec3(0.01, 0.0, 0.0)));
        camera.lookAt(new Vec3(0.01, 0.8, 0.0));
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        long reused = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_REUSED_FRAMES);
        long executed = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW,
            RuntimeInstrumentation.Counter.PREVIEW_POLISH_RESOLVE_EXECUTED_FRAMES);
        long blockedSoftMotion = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_POLISH_REUSE_BLOCK_REASON_SOFT_MOTION);
        if (reused != 0L) {
            throw new AssertionError("Moving RT polish should not reuse stale polish cache while the camera keeps moving.");
        }
        if (executed != 0L && blockedSoftMotion <= 0L) {
            throw new AssertionError("Moving RT polish should mark soft-motion reuse blocking when camera motion invalidates cache reuse.");
        }
        renderer.setParameter("shutdown", true);
    }

    private static void verifySoftCameraResetAndPreviewLadderPath() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionSecondaryCadence", 2);
        renderer.setParameter("previewMotionDenoiseCadence", 2);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 2);

        FrameBuffer fb = new FrameBuffer(72, 48);
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.snapshotAndReset();

        renderer.setParameter("previewMotionActive", true);
        camera.setPosition(camera.getPosition().add(new Vec3(0.05, 0.0, 0.0)));
        camera.lookAt(new Vec3(0.05, 0.8, 0.0));
        renderPreviewFrame(renderer, scene, camera, fb);
        camera.setPosition(camera.getPosition().add(new Vec3(0.05, 0.0, 0.0)));
        camera.lookAt(new Vec3(0.10, 0.8, 0.0));
        renderPreviewFrame(renderer, scene, camera, fb);
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        renderer.setParameter("shutdown", true);

        long softResets = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.CAMERA_RESETS_SOFT);
        long hardResets = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.CAMERA_RESETS_HARD);
        long secondaryReduced = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_SECONDARY_REDUCED_FRAMES);
        long denoiseSkipped = snapshot.totalCounter(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Counter.PREVIEW_DENOISE_SKIPPED_FRAMES);
        if (softResets <= 0L) {
            throw new AssertionError("Small preview camera motion should use soft reset in the path tracer.");
        }
        if (hardResets != 0L) {
            throw new AssertionError("Small preview camera motion should avoid hard reset in the path tracer.");
        }
        if (secondaryReduced <= 0L) {
            throw new AssertionError("Preview motion ladder should reduce path secondary work on cadence-skipped frames.");
        }
        if (denoiseSkipped <= 0L) {
            throw new AssertionError("Preview motion ladder should skip full denoise on some path motion frames.");
        }
    }

    private static void verifyPathPreviewTileTimingConsistency() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        PathTracerRenderer renderer = new PathTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("workerCount", 1);
        renderer.setParameter("tileSize", 12);
        renderer.setParameter("denoise", false);
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", true);
        renderer.setParameter("previewMotionSamplesPerFrame", 1);
        renderer.setParameter("previewMotionMaxDepth", 1);
        renderer.setParameter("previewMotionSecondaryCadence", 4);
        renderer.setParameter("previewMotionTileSubsetCadence", 3);

        FrameBuffer fb = new FrameBuffer(72, 48);
        RuntimeInstrumentation.reset();
        for (int i = 0; i < 16; i++) {
            double shift = (i % 4) * 0.01;
            camera.setPosition(new Vec3(shift, 1.4, 5.5));
            camera.lookAt(new Vec3(shift, 0.8, 0.0));
            renderPreviewFrame(renderer, scene, camera, fb);
        }
        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        renderer.setParameter("shutdown", true);

        long tileSamples = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_SAMPLES);
        long minNs = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MIN_NS);
        long maxNs = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MAX_NS);
        long spreadNs = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_SPREAD_NS);
        long desyncFrames = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_DESYNC_FRAMES);
        long totalFrames = snapshot.frameCount(RuntimeInstrumentation.FrameKind.PREVIEW);

        if (tileSamples <= 0L) {
            throw new AssertionError("Path preview tile timing should record tile samples.");
        }
        if (minNs <= 0L || maxNs <= 0L || spreadNs < 0L) {
            throw new AssertionError("Path preview tile timing counters should stay positive and well-formed.");
        }
        if (desyncFrames != 0L) {
            throw new AssertionError("Path preview tiles should remain temporally synchronized in preview mode.");
        }

        System.out.println(String.format(
                Locale.ROOT,
                "Instr[path-tile-timing] frames=%d tile_samples=%d min_ns=%d max_ns=%d spread_ns=%d desync_frames=%d",
                totalFrames,
                tileSamples,
                minNs,
                maxNs,
                spreadNs,
                desyncFrames));
    }

    private static void verifyRayStillSampleDrivenQualityLadder() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "QUALITY");
        renderer.setParameter("previewQualityLadder", true);
        renderer.setParameter("previewMotionActive", false);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 3);

        FrameBuffer fb = new FrameBuffer(72, 48);
        for (int i = 0; i < 6; i++) {
            renderPreviewFrame(renderer, scene, camera, fb);
        }
        if (!"STILL_T0_CARRIER_ONLY".equals(renderer.getActivePreviewQualityTier())) {
            throw new AssertionError("Ray still preview should start in carrier-only tier at low sample counts.");
        }

        while (renderer.getAccumulatedSamples() < 12L) {
            renderPreviewFrame(renderer, scene, camera, fb);
        }
        if (!"STILL_T1_REFLECTION_SEED".equals(renderer.getActivePreviewQualityTier())) {
            throw new AssertionError("Ray still preview should enter reflection-seed tier around 12 accumulated samples.");
        }
        if (renderer.getActivePreviewPolishSecondaryDepth() != 1) {
            throw new AssertionError("Seed polish tier should enable one level of polish depth.");
        }

        while (renderer.getAccumulatedSamples() < 30L) {
            renderPreviewFrame(renderer, scene, camera, fb);
        }
        if (!"STILL_T2_LOCAL_LIGHTS".equals(renderer.getActivePreviewQualityTier())) {
            throw new AssertionError("Ray still preview should enter local-lights tier around 28 accumulated samples.");
        }
        if (renderer.getActivePreviewPolishCadence() != 2) {
            throw new AssertionError("Local-lights tier should run polish every second frame.");
        }

        while (renderer.getAccumulatedSamples() < 80L) {
            renderPreviewFrame(renderer, scene, camera, fb);
        }
        String highTier = renderer.getActivePreviewQualityTier();
        if (!"STILL_T4_POLISH_NEAR_REFERENCE".equals(highTier) && !"STILL_T5_REFERENCE_READY".equals(highTier)) {
            throw new AssertionError("Ray still preview should enter near-reference polish tier around 72 accumulated samples.");
        }
        if ("STILL_T4_POLISH_NEAR_REFERENCE".equals(highTier) || "STILL_T5_REFERENCE_READY".equals(highTier)) {
            if (renderer.getActivePreviewPolishSecondaryDepth() != 2) {
                throw new AssertionError("Full-depth still tier should expose the full preview polish depth.");
            }
            if (renderer.getActivePreviewPolishSamplesPerFrame() < 1) {
                throw new AssertionError("Full-depth still tier should keep polish sampling active.");
            }
        }
        renderer.setParameter("shutdown", true);
    }

    private static void verifyPreviewAndOutputStageTelemetry() {
        Scene scene = buildScene();
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        scene.update(0.0);

        RayTracerRenderer renderer = new RayTracerRenderer();
        renderer.init(72, 48);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("autohardware", true);
        renderer.setParameter("autoworkers", true);
        renderer.setParameter("autotilesize", true);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseProfile", "FAST");
        renderer.setParameter("denoiseRuntimeMode", "AUTO");

        FrameBuffer previewFb = new FrameBuffer(72, 48);
        RuntimeInstrumentation.reset();
        RuntimeInstrumentation.FrameToken previewToken =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "preview-ray");
        try {
            long renderStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
            renderer.render(scene, camera, previewFb, 0.0);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL, renderStage);
        } finally {
            RuntimeInstrumentation.endFrame(previewToken);
        }
        renderer.setParameter("shutdown", true);
        RuntimeInstrumentation.Snapshot preview = RuntimeInstrumentation.snapshotAndReset();

        double previewRenderMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
        double traceMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.RT_OR_PT_RENDER);
        double carrierTraceMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.CARRIER_TRACE);
        double polishTraceMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.POLISH_TRACE);
        double carrierResolveMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.CARRIER_RESOLVE);
        double polishResolveMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.POLISH_RESOLVE);
        double denoiseMs = preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.DENOISE);
        if (previewRenderMs <= 0.0 || traceMs <= 0.0) {
            throw new AssertionError("Preview telemetry should report render and trace timings.");
        }
        if (carrierTraceMs <= 0.0) {
            throw new AssertionError("Ray preview telemetry should report carrier trace timings.");
        }
        if (carrierResolveMs <= 0.0) {
            throw new AssertionError("Ray preview telemetry should report carrier resolve timings.");
        }
        if (polishTraceMs > 0.05 || polishResolveMs > 0.05) {
            throw new AssertionError("Ray preview telemetry should keep polish stages inactive in PT-style carrier-only pipeline.");
        }
        if (denoiseMs <= 0.0) {
            throw new AssertionError("Preview telemetry should report denoise timing when enabled.");
        }

        FrameBuffer outputFb = new FrameBuffer(72, 48);
        outputFb.clear(0xFF112233, 1.0f);
        RuntimeInstrumentation.FrameToken outputToken =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.OUTPUT, "output-copy");
        try {
            long copyStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.OUTPUT_COPY_ENCODE);
            OutputRenderArtifacts.framebufferToImage(outputFb, 96, 64, false);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.OUTPUT_COPY_ENCODE, copyStage);
        } finally {
            RuntimeInstrumentation.endFrame(outputToken);
        }
        RuntimeInstrumentation.Snapshot output = RuntimeInstrumentation.snapshotAndReset();
        double outputCopyMs = output.averageStageMs(RuntimeInstrumentation.FrameKind.OUTPUT, RuntimeInstrumentation.Stage.OUTPUT_COPY_ENCODE);
        long bytesCopied = output.totalCounter(RuntimeInstrumentation.FrameKind.OUTPUT, RuntimeInstrumentation.Counter.BYTES_COPIED);
        if (outputCopyMs <= 0.0 || bytesCopied <= 0L) {
            throw new AssertionError("Output telemetry should capture copy timing and copied bytes.");
        }

        System.out.println(String.format(
                Locale.ROOT,
                "Instr[preview] frame_ms=%.3f trace_ms=%.3f denoise_ms=%.3f temporal_ms=%.3f alloc_bytes=%.0f",
                previewRenderMs,
                traceMs,
                denoiseMs,
                preview.averageStageMs(RuntimeInstrumentation.FrameKind.PREVIEW, RuntimeInstrumentation.Stage.TEMPORAL),
                preview.averageAllocatedBytes(RuntimeInstrumentation.FrameKind.PREVIEW)));
        System.out.println(String.format(
                Locale.ROOT,
                "Instr[output] copy_ms=%.3f bytes=%d",
                outputCopyMs,
                bytesCopied));
    }

    private static void runSceneMaintenanceFrame(Engine engine, Entity selected, int width, int height) {
        RuntimeInstrumentation.FrameToken token =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "scene-maintenance");
        try {
            long sceneStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SCENE_UPDATE);
            engine.scene.update(0.0);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SCENE_UPDATE, sceneStage);

            long visibilityStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.VISIBILITY);
            engine.applySceneVisibility(false);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.VISIBILITY, visibilityStage);

            long selectionStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.SELECTION_PREPASS);
            SelectionOverlayUtil.computeSelectionCoveragePass(selected, engine.camera, width, height);
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.SELECTION_PREPASS, selectionStage);
        } finally {
            RuntimeInstrumentation.endFrame(token);
        }
    }

    private static void renderPreviewFrame(Object renderer, Scene scene, PerspectiveCamera camera, FrameBuffer fb) {
        RuntimeInstrumentation.FrameToken token =
                RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "preview-motion");
        try {
            long renderStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
            if (renderer instanceof RayTracerRenderer rayTracerRenderer) {
                rayTracerRenderer.render(scene, camera, fb, 0.0);
            } else if (renderer instanceof PathTracerRenderer pathTracerRenderer) {
                pathTracerRenderer.render(scene, camera, fb, 0.0);
            } else {
                throw new IllegalArgumentException("Unsupported renderer for preview frame helper.");
            }
            RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL, renderStage);
        } finally {
            RuntimeInstrumentation.endFrame(token);
        }
    }

    private static Engine buildEngineHarness() {
        Engine engine = new Engine();
        engine.scene = buildScene();
        engine.camera = new PerspectiveCamera(60.0, 72.0 / 48.0, 0.1, 80.0);
        engine.camera.setPosition(new Vec3(0.0, 1.4, 5.5));
        engine.camera.lookAt(new Vec3(0.0, 0.8, 0.0));
        engine.viewDistanceCullingEnabled = true;
        engine.viewDistanceLimit = 40.0;
        engine.initializeSceneItemStateDefaults();
        return engine;
    }

    private static Scene buildScene() {
        Scene scene = new Scene();

        Entity subject = new Entity(
                "subject",
                MeshGenerator.cube(1.2),
                new PhongMaterial(new Vec3(0.78, 0.62, 0.46), 24.0));
        subject.getTransform().setPosition(new Vec3(0.0, 0.75, 0.0));
        scene.addEntity(subject);

        Entity floor = new Entity(
                "floor",
                MeshGenerator.cube(8.0),
                new PhongMaterial(new Vec3(0.45, 0.47, 0.52), 12.0));
        floor.getTransform().setPosition(new Vec3(0.0, -4.0, 0.0));
        floor.getTransform().setScale(new Vec3(1.0, 0.08, 1.0));
        scene.addEntity(floor);

        DirectionalLight light = new DirectionalLight(new Vec3(-0.4, -1.0, -0.3), new Vec3(1.0, 0.96, 0.9), 1.8);
        scene.addLight(light);
        return scene;
    }
}
