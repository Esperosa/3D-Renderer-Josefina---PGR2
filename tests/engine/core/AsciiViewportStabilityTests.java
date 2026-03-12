package engine.core;

import engine.render.post.DitherRenderer;

public final class AsciiViewportStabilityTests {

    private AsciiViewportStabilityTests() {
    }

    public static void main(String[] args) {
        keepsAsciiViewportScaleStableDuringInteraction();
        System.out.println("AsciiViewportStabilityTests: ALL TESTS PASSED");
    }

    private static void keepsAsciiViewportScaleStableDuringInteraction() {
        Engine engine = new Engine();
        engine.activeMode = RenderMode.DITHERING;
        engine.progressiveViewportEnabled = true;
        engine.viewportTargetFps = 25.0;
        engine.interactiveRenderScale = 0.55;
        engine.viewportSmoothedFrameMs = 72.0;
        engine.ditherRenderer = new DitherRenderer();
        engine.ditherRenderer.setParameter("style", DitherRenderer.DitherStyle.ASCII);

        for (int i = 0; i < 40; i++) {
            EngineRenderRuntime.recordViewportFrameTime(engine, 72.0);
            EngineRenderRuntime.updateRealtimePerformanceState(engine, true);
        }

        if (engine.interactiveRenderScaleActive) {
            throw new AssertionError("ASCII viewport should keep a stable internal grid during interaction.");
        }
        if (engine.viewportAdaptiveScaleApplied != 1.0) {
            throw new AssertionError("ASCII viewport should stay at full internal resolution to avoid flicker.");
        }
    }
}
