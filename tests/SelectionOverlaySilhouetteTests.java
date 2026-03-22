import java.util.Arrays;

import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.raster.RasterRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.SelectionOverlayUtil;

public final class SelectionOverlaySilhouetteTests {

    private SelectionOverlaySilhouetteTests() {
    }

    public static void main(String[] args) {
        testSelectionOutlineStaysSilhouetteOnly();
        testSelectionOutlineForPartiallyVisibleModel();
        System.out.println("SelectionOverlaySilhouetteTests: ALL TESTS PASSED");
    }

    private static void testSelectionOutlineStaysSilhouetteOnly() {
        int width = 640;
        int height = 400;

        Scene scene = new Scene();
        scene.setBackgroundColor(new Vec3(0.07, 0.08, 0.10));
        scene.setAmbientColor(new Vec3(0.18, 0.18, 0.18));

        Entity sphere = new Entity(
                "ProbeSphere",
                MeshGenerator.sphere(1.1, 40, 48),
                new PhongMaterial(new Vec3(0.82, 0.74, 0.68), 64.0));
        scene.addEntity(sphere);
        scene.addLight(new DirectionalLight(new Vec3(-0.45, -1.0, -0.25), new Vec3(1.0, 0.97, 0.93), 1.7));

        PerspectiveCamera camera = new PerspectiveCamera(60.0, width / (double) height, 0.1, 100.0);
        camera.setPosition(new Vec3(0.0, 0.15, 4.1));
        camera.lookAt(new Vec3(0.0, 0.05, 0.0));

        RasterRenderer renderer = new RasterRenderer();
        renderer.init(width, height);
        renderer.setParameter("unlitMode", false);
        renderer.setParameter("modelPreviewMode", false);

        FrameBuffer framebuffer = new FrameBuffer(width, height);
        renderer.render(scene, camera, framebuffer, 0.0);

        int[] before = Arrays.copyOf(framebuffer.getColorBuffer(), framebuffer.getColorBuffer().length);
        // Use new renderer-agnostic two-phase approach
        SelectionOverlayUtil.computeSelectionCoveragePass(sphere, camera, width, height);
        SelectionOverlayUtil.drawCachedSelectionOutline(framebuffer);
        int[] after = framebuffer.getColorBuffer();

        int clearColor = 0xFF000000 | scene.getBackgroundColor().toIntRGB();
        int objectPixels = 0;
        int objectPixelsChanged = 0;
        int changedPixels = 0;
        float[] depth = framebuffer.getDepthBuffer();
        for (int i = 0; i < before.length; i++) {
            if (before[i] != clearColor && depth[i] < 0.999f) {
                objectPixels++;
            }
            if (before[i] != after[i]) {
                changedPixels++;
                if (before[i] != clearColor && depth[i] < 0.999f) {
                    objectPixelsChanged++;
                }
            }
        }

        if (objectPixels <= 0) {
            throw new AssertionError("Test setup failure: rendered object pixel count must be > 0");
        }

        double changedRatio = changedPixels / (double) objectPixels;
        if (changedRatio > 0.42) {
            throw new AssertionError("Selection outline changed too much of the object surface: ratio=" + changedRatio);
        }

        double interiorChangedRatio = objectPixelsChanged / (double) objectPixels;
        if (interiorChangedRatio > 0.015) {
            throw new AssertionError("Selection overlay changed too much inside-object shading: ratio=" + interiorChangedRatio);
        }
    }

    private static void testSelectionOutlineForPartiallyVisibleModel() {
        int width = 640;
        int height = 400;

        Scene scene = new Scene();
        scene.setBackgroundColor(new Vec3(0.07, 0.08, 0.10));
        scene.setAmbientColor(new Vec3(0.18, 0.18, 0.18));

        Entity sphere = new Entity(
                "OffscreenProbeSphere",
                MeshGenerator.sphere(1.1, 40, 48),
                new PhongMaterial(new Vec3(0.82, 0.74, 0.68), 64.0));
        sphere.getTransform().setPosition(new Vec3(2.2, 0.05, 0.0));
        scene.addEntity(sphere);
        scene.addLight(new DirectionalLight(new Vec3(-0.45, -1.0, -0.25), new Vec3(1.0, 0.97, 0.93), 1.7));

        PerspectiveCamera camera = new PerspectiveCamera(60.0, width / (double) height, 0.1, 100.0);
        camera.setPosition(new Vec3(0.0, 0.15, 4.1));
        camera.lookAt(new Vec3(0.0, 0.05, 0.0));

        RasterRenderer renderer = new RasterRenderer();
        renderer.init(width, height);
        renderer.setParameter("unlitMode", false);
        renderer.setParameter("modelPreviewMode", false);

        FrameBuffer framebuffer = new FrameBuffer(width, height);
        renderer.render(scene, camera, framebuffer, 0.0);

        int[] before = Arrays.copyOf(framebuffer.getColorBuffer(), framebuffer.getColorBuffer().length);
        // Use new renderer-agnostic two-phase approach
        SelectionOverlayUtil.computeSelectionCoveragePass(sphere, camera, width, height);
        SelectionOverlayUtil.drawCachedSelectionOutline(framebuffer);
        int[] after = framebuffer.getColorBuffer();

        int clearColor = 0xFF000000 | scene.getBackgroundColor().toIntRGB();
        int objectPixels = 0;
        int changedPixels = 0;
        int rightBandChangedPixels = 0;
        int rightBandStart = width - Math.max(8, width / 6);
        float[] depth = framebuffer.getDepthBuffer();
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int idx = row + x;
                if (before[idx] != clearColor && depth[idx] < 0.999f) {
                    objectPixels++;
                }
                if (before[idx] != after[idx]) {
                    changedPixels++;
                    if (x >= rightBandStart) {
                        rightBandChangedPixels++;
                    }
                }
            }
        }

        if (objectPixels <= 0) {
            throw new AssertionError("Test setup failure: partially visible object pixel count must be > 0");
        }
        if (changedPixels <= 0) {
            throw new AssertionError("Selection overlay should affect at least one pixel for partially visible model");
        }
        if (rightBandChangedPixels <= 0) {
            throw new AssertionError("Selection overlay should produce contour in the clipped viewport side band for partially visible model");
        }
    }
}
