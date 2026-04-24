package engine.core;

import engine.camera.PerspectiveCamera;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.Scene;

public final class ViewportOverlayDepthTests {
    private ViewportOverlayDepthTests() {
    }

    public static void main(String[] args) {
        testGroundGridDoesNotDrawWithoutSceneDepth();
        System.out.println("ViewportOverlayDepthTests: ALL TESTS PASSED");
    }

    private static void testGroundGridDoesNotDrawWithoutSceneDepth() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.editorOverlayEnabled = true;
        engine.activeMode = RenderMode.PATH_TRACING;
        engine.camera = new PerspectiveCamera(60.0, 16.0 / 9.0, 0.1, 100.0);
        engine.camera.setPosition(new Vec3(0.0, 1.5, 5.0));
        engine.camera.lookAt(new Vec3(0.0, 0.0, 0.0));
        engine.frameBuffer = new FrameBuffer(160, 90);
        engine.frameBuffer.clear(0xFF101820, 1.0f);

        FrameBuffer overlay = new FrameBuffer(160, 90);
        overlay.clear(0x00000000, 1.0f);
        EngineViewportOverlay.drawEditorDebugOverlays(engine, overlay);

        int changedPixels = 0;
        for (int pixel : overlay.getColorBuffer()) {
            if (pixel != 0) {
                changedPixels++;
            }
        }
        if (changedPixels != 0) {
            throw new AssertionError("Ground grid should not draw without a usable scene depth buffer.");
        }
    }
}
