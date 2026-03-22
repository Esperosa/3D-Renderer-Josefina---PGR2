package engine.core;

import java.lang.reflect.Field;

import engine.render.Renderer;
import engine.render.post.DitherRenderer;
import engine.render.post.HexMosaicRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.post.WireframeRenderer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;

public final class EngineSafetyRendererCoverageTests {

    private EngineSafetyRendererCoverageTests() {
    }

    public static void main(String[] args) throws Exception {
        coversViewportRenderersInBoundedOverloadSandbox();
        System.out.println("EngineSafetyRendererCoverageTests: ALL TESTS PASSED");
    }

    private static void coversViewportRenderersInBoundedOverloadSandbox() throws Exception {
        Scenario[] scenarios = new Scenario[]{
                new Scenario("MODEL", RenderMode.MODEL, false, engine -> engine.rasterRenderer = new RasterRenderer()),
                new Scenario("BASIC", RenderMode.BASIC, false, engine -> engine.rasterRenderer = new RasterRenderer()),
                new Scenario("PHONG", RenderMode.PHONG, false, engine -> engine.rasterRenderer = new RasterRenderer()),
                new Scenario("WIREFRAME", RenderMode.WIREFRAME, false, engine -> engine.wireframeRenderer = new WireframeRenderer()),
                new Scenario("DITHER-BLUE", RenderMode.DITHERING, false, engine -> {
                    engine.ditherRenderer = new DitherRenderer();
                    engine.ditherRenderer.setParameter("style", DitherRenderer.DitherStyle.BLUE_NOISE);
                }),
                new Scenario("DITHER-ASCII", RenderMode.DITHERING, false, engine -> {
                    engine.ditherRenderer = new DitherRenderer();
                    engine.ditherRenderer.setParameter("style", DitherRenderer.DitherStyle.ASCII);
                }),
                new Scenario("TEMPORAL", RenderMode.TEMPORAL_NOISE, false, engine -> engine.temporalNoiseRenderer = new TemporalNoiseRenderer()),
                new Scenario("HEX", RenderMode.HEX_MOSAIC, false, engine -> engine.hexMosaicRenderer = new HexMosaicRenderer()),
                new Scenario("RAY", RenderMode.RAY_TRACING, true, engine -> {
                    engine.rayTracerRenderer = new RayTracerRenderer();
                    engine.rayTracerRenderer.init(8, 8);
                    setAccumulatedSamples(engine.rayTracerRenderer, 9L);
                }),
                new Scenario("PATH", RenderMode.PATH_TRACING, true, engine -> {
                    engine.pathTracerRenderer = new PathTracerRenderer();
                    engine.pathTracerRenderer.init(8, 8);
                    setAccumulatedSamples(engine.pathTracerRenderer, 11L);
                })
        };

        long now = 1_000_000_000L;
        for (int i = 0; i < scenarios.length; i++) {
            Scenario scenario = scenarios[i];
            Engine engine = new Engine();
            engine.activeMode = scenario.mode;
            if (scenario.heavyAccumulator) {
                engine.pathAccumulationLock = false;
            }
            scenario.setup.apply(engine);

            Renderer resolved = EngineRenderRuntime.configureRendererForMode(engine, scenario.mode);
            if (resolved == null) {
                throw new AssertionError("Expected renderer for scenario " + scenario.name);
            }
            runBoundedOverloadScenario(engine, now + i * 5_000_000_000L, scenario.name, scenario.heavyAccumulator);
        }
    }

    private static void runBoundedOverloadScenario(Engine engine,
                                                   long now,
                                                   String scenarioName,
                                                   boolean heavyAccumulator) throws Exception {
        // Tady overload jen simuluju přes omezené frame časy, takže test ověří watchdog bez reálného pálení CPU.
        double severeFrameMs = heavyAccumulator ? 1900.0 : 720.0;
        EngineSafetyController.recordFrame(engine, severeFrameMs, now, true);
        if (heavyAccumulator) {
            engine.lastViewportInteractionNanos = now + 39_900_000L;
        }
        EngineSafetyController.recordFrame(engine, severeFrameMs + 15.0, now + 40_000_000L, true);
        if (heavyAccumulator) {
            EngineSafetyController.recordFrame(engine, severeFrameMs + 25.0, now + 80_000_000L, true);
            EngineSafetyController.recordFrame(engine, severeFrameMs + 35.0, now + 120_000_000L, true);
        }

        if (!engine.safetyRecoveryActive) {
            throw new AssertionError("Safety recovery should arm for scenario " + scenarioName);
        }
        if (EngineSafetyController.shouldHoldFrame(engine, now + 80_000_000L)) {
            throw new AssertionError("Safety recovery must stay non-blocking for scenario " + scenarioName);
        }
        if (engine.effectiveRenderScale() >= 0.99) {
            throw new AssertionError("Safety clamp should lower effective scale for scenario " + scenarioName);
        }

        String[] overlay = EngineSafetyController.augmentOverlay(engine, new String[]{"BASE"});
        if (overlay.length < 2 || !overlay[0].contains("SAFE RECOVERY")) {
            throw new AssertionError("Safety overlay should expose recovery state for scenario " + scenarioName);
        }

        if (heavyAccumulator) {
            long samples = readAccumulatedSamples(engine);
            if (samples < 0L) {
                throw new AssertionError("Safety recovery should keep heavy renderer sample counters valid for scenario "
                        + scenarioName + ", got " + samples);
            }
        }

        if (EngineSafetyController.shouldHoldFrame(engine, now + 3_200_000_000L)) {
            throw new AssertionError("Safety hold should stay disabled after cooldown for scenario " + scenarioName);
        }
        if (engine.safetyRecoveryActive) {
            throw new AssertionError("Recovery flag should clear after cooldown for scenario " + scenarioName);
        }
        if (Math.abs(engine.safetyViewportScaleClamp - 1.0) > 1e-9) {
            throw new AssertionError("Safety clamp should reset after cooldown for scenario " + scenarioName);
        }
    }

    private static long readAccumulatedSamples(Engine engine) {
        if (engine.activeMode == RenderMode.RAY_TRACING && engine.rayTracerRenderer != null) {
            return engine.rayTracerRenderer.getAccumulatedSamples();
        }
        if (engine.activeMode == RenderMode.PATH_TRACING && engine.pathTracerRenderer != null) {
            return engine.pathTracerRenderer.getAccumulatedSamples();
        }
        return 0L;
    }

    private static void setAccumulatedSamples(Object renderer, long value) throws Exception {
        Field field = renderer.getClass().getDeclaredField("accumulatedSamples");
        field.setAccessible(true);
        field.setLong(renderer, value);
    }

    private interface ScenarioSetup {
        void apply(Engine engine) throws Exception;
    }

    private static final class Scenario {
        private final String name;
        private final RenderMode mode;
        private final boolean heavyAccumulator;
        private final ScenarioSetup setup;

        private Scenario(String name,
                         RenderMode mode,
                         boolean heavyAccumulator,
                         ScenarioSetup setup) {
            this.name = name;
            this.mode = mode;
            this.heavyAccumulator = heavyAccumulator;
            this.setup = setup;
        }
    }
}
