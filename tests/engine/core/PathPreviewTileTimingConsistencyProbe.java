package engine.core;

import java.util.Locale;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.core.PathTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.RuntimeInstrumentation;

public final class PathPreviewTileTimingConsistencyProbe {

    private PathPreviewTileTimingConsistencyProbe() {
    }

    public static void main(String[] args) {
        RuntimeInstrumentation.setEnabled(true);
        try {
            runProbe();
        } finally {
            RuntimeInstrumentation.setEnabled(false);
        }
    }

    private static void runProbe() {
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
        for (int i = 0; i < 24; i++) {
            double shift = (i % 4) * 0.01;
            camera.setPosition(new Vec3(shift, 1.4, 5.5));
            camera.lookAt(new Vec3(shift, 0.8, 0.0));
            RuntimeInstrumentation.FrameToken token =
                    RuntimeInstrumentation.beginFrame(RuntimeInstrumentation.FrameKind.PREVIEW, "pt-tile-timing");
            try {
                long renderStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL);
                renderer.render(scene, camera, fb, i * 0.016);
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.PREVIEW_RENDER_TOTAL, renderStage);
            } finally {
                RuntimeInstrumentation.endFrame(token);
            }
        }

        RuntimeInstrumentation.Snapshot snapshot = RuntimeInstrumentation.snapshotAndReset();
        renderer.setParameter("shutdown", true);

        long frames = snapshot.frameCount(RuntimeInstrumentation.FrameKind.PREVIEW);
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
        long ratioX1000 = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_MAX_OVER_MEAN_X1000);
        long desyncFrames = snapshot.totalCounter(
                RuntimeInstrumentation.FrameKind.PREVIEW,
                RuntimeInstrumentation.Counter.PREVIEW_PT_TILE_TIMING_DESYNC_FRAMES);

        double avgMaxOverMean = frames <= 0L ? 0.0 : (ratioX1000 / (double) frames) / 1000.0;

        String status = desyncFrames > 0L ? "DESYNC_DETECTED" : "SYNC_OK";
        System.out.println(String.format(
            Locale.ROOT,
            "PT_TILE_TIMING|status=%s|frames=%d|tile_samples=%d|min_ns=%d|max_ns=%d|spread_ns=%d|avg_max_over_mean=%.3f|desync_frames=%d",
            status,
            frames,
            tileSamples,
            minNs,
            maxNs,
            spreadNs,
            avgMaxOverMean,
            desyncFrames));

        if (tileSamples <= 0L) {
            throw new AssertionError("Tile timing probe did not collect tile samples.");
        }
        if (desyncFrames != 0L) {
            throw new AssertionError("Path preview tile timing should stay temporally synchronized across tiles.");
        }
    }

    private static Scene buildScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.10, 0.10, 0.12));

        Entity floor = new Entity(
                "floor",
                MeshGenerator.plane(8.0, 8.0, 10, 10),
                new PhongMaterial(new Vec3(0.35, 0.37, 0.40), 16.0));
        floor.getTransform().setPosition(new Vec3(0.0, -1.0, 0.0));
        scene.addEntity(floor);

        Entity sphere = new Entity(
                "sphere",
                MeshGenerator.sphere(1.0, 32, 24),
                new PhongMaterial(new Vec3(0.35, 0.62, 0.92), 36.0));
        sphere.getTransform().setPosition(new Vec3(0.0, 0.15, 0.0));
        scene.addEntity(sphere);

        scene.addLight(new DirectionalLight(new Vec3(-0.5, -1.0, -0.3), new Vec3(1.0, 0.95, 0.90), 1.25));
        scene.addLight(new DirectionalLight(new Vec3(0.2, -0.6, 0.5), new Vec3(0.25, 0.35, 0.55), 0.45));
        scene.update(0.0);
        return scene;
    }
}
