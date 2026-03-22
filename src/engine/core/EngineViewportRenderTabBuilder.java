package engine.core;

import java.awt.Dimension;

import javax.swing.Box;
import javax.swing.JPanel;

import engine.render.post.DitherRenderer;
import engine.render.post.TemporalNoiseRenderer;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.ThreadPool;
import engine.util.UiBuilder;

/**
 * Tady stavím panel pro živé nastavení viewport rendererů.
 */
final class EngineViewportRenderTabBuilder {

    private EngineViewportRenderTabBuilder() {
    }

    static JPanel build(Engine engine) {
        JPanel renderTab = engine.window.createRightTab("Rendering", UiStrings.Tabs.RENDER, "render");
        renderTab.removeAll();
        OutputRenderController.Settings outputSettings = engine.outputRenderController.settings();
        renderTab.add(UiBuilder.panelHeader("Render", "Nastavení viewport renderu, stylizovaných režimů a finálního výstupu."));
        renderTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));

        JPanel routerSection = engine.addCollapsibleSection(renderTab, "Směrování rendererů", true);
        String[] modeOptions = EngineRenderPanelSupport.renderModeLabels();
        engine.addComboRow(routerSection, "Viewport",
                modeOptions,
                EngineRenderPanelSupport.renderModeLabel(engine.activeMode == null ? RenderMode.PHONG : engine.activeMode),
                value -> engine.setRenderMode(EngineRenderPanelSupport.parseRenderModeLabel(value)));
        engine.addComboRow(routerSection, "Výstup",
                modeOptions,
                EngineRenderPanelSupport.renderModeLabel(outputSettings.mode == null ? RenderMode.PATH_TRACING : outputSettings.mode),
                value -> outputSettings.mode = EngineRenderPanelSupport.parseRenderModeLabel(value));
        JPanel routerActions = EngineRenderPanelSupport.createButtonGrid(1);
        routerActions.add(engine.actionButton("Výstup = renderer viewportu", () -> {
            outputSettings.mode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
            build(engine);
            engine.window.selectRightTab("Render");
        }));
        routerActions.add(engine.actionButton("Otevřít panel Výstup", () -> engine.window.selectRightTab("Output")));
        routerSection.add(routerActions);
        routerSection.add(Box.createRigidArea(new Dimension(0, 4)));
        routerSection.add(engine.sectionTitle("Viewport: "
                + EngineRenderPanelSupport.renderModeLabel(engine.activeMode == null ? RenderMode.PHONG : engine.activeMode)
                + " | Výstup: "
                + EngineRenderPanelSupport.renderModeLabel(outputSettings.mode == null ? RenderMode.PATH_TRACING : outputSettings.mode)));
        routerSection.add(engine.sectionTitle(EngineRenderPanelSupport.renderModeSummary(
                engine.activeMode == null ? RenderMode.PHONG : engine.activeMode)));
        routerSection.add(UiBuilder.helperText("Viewport je laděný pro interaktivitu. Výstup drží vlastní kvalitu v panelu Výstup."));

        JPanel modeSection = engine.addCollapsibleSection(renderTab, "Rychlé režimy", true);
        JPanel modeGrid = EngineRenderPanelSupport.createButtonGrid();
        modeGrid.add(engine.actionButton("Model (G)", () -> engine.setRenderMode(RenderMode.MODEL)));
        modeGrid.add(engine.actionButton("Basic (1)", () -> engine.setRenderMode(RenderMode.BASIC)));
        modeGrid.add(engine.actionButton("Phong (2)", () -> engine.setRenderMode(RenderMode.PHONG)));
        modeGrid.add(engine.actionButton("Wireframe (3)", () -> engine.setRenderMode(RenderMode.WIREFRAME)));
        modeGrid.add(engine.actionButton("Dither (4)", () -> {
            engine.setDitherStyle(DitherRenderer.DitherStyle.BLUE_NOISE);
            engine.setRenderMode(RenderMode.DITHERING);
        }));
        modeGrid.add(engine.actionButton("ASCII (5)", () -> {
            engine.setDitherStyle(DitherRenderer.DitherStyle.ASCII);
            engine.setRenderMode(RenderMode.DITHERING);
        }));
        modeGrid.add(engine.actionButton("Temporal (6)", () -> engine.setRenderMode(RenderMode.TEMPORAL_NOISE)));
        modeGrid.add(engine.actionButton("Ray Tracing (7)", () -> engine.setRenderMode(RenderMode.RAY_TRACING)));
        modeGrid.add(engine.actionButton("Path Tracing (8)", () -> engine.setRenderMode(RenderMode.PATH_TRACING)));
        modeGrid.add(engine.actionButton("Hex (9)", () -> engine.setRenderMode(RenderMode.HEX_MOSAIC)));
        modeSection.add(modeGrid);
        modeSection.add(Box.createRigidArea(new Dimension(0, 6)));
        modeSection.add(UiBuilder.helperText("Níže upravujete živý náhled viewportu. Finální render si drží vlastní nastavení."));

        JPanel globalSection = engine.addCollapsibleSection(renderTab, "Globální", false);
        engine.addBooleanRow(globalSection, "Frustum culling", engine.frustumCullingEnabled, value -> {
            if (engine.frustumCullingEnabled != value) {
                engine.toggleFrustumCulling();
            }
        });
        engine.addBooleanRow(globalSection, "Backface culling", engine.backfaceCullingEnabled, value -> {
            if (engine.backfaceCullingEnabled != value) {
                engine.toggleBackfaceCulling();
            }
        });
        engine.addBooleanRow(globalSection, "Paralelní raster", engine.parallelRasterEnabled, value -> {
            if (engine.parallelRasterEnabled != value) {
                engine.toggleParallelRaster();
            }
        });
        engine.addBooleanRow(globalSection, "Post AA", engine.postAAEnabled, value -> {
            if (engine.postAAEnabled != value) {
                engine.togglePostAA();
            }
        });
        engine.addBooleanRow(globalSection, "Progresivní viewport", engine.progressiveViewportEnabled, value -> {
            engine.progressiveViewportEnabled = value;
            EngineRenderRuntime.updateRealtimePerformanceState(engine, false);
        });
        engine.addBooleanRow(globalSection, "Culling podle vzdálenosti", engine.viewDistanceCullingEnabled, value -> {
            engine.viewDistanceCullingEnabled = value;
            engine.applySceneVisibility(false);
        });
        engine.addBooleanRow(globalSection, "Nouzový preview mód", engine.viewportNavigationPreviewEnabled, value ->
                engine.viewportNavigationPreviewEnabled = value);
        engine.addComboRow(globalSection, "Fallback režim",
                new String[]{"Model", "Basic", "Phong", "Wireframe"},
                EngineRenderPanelSupport.renderModeLabel(
                        engine.viewportNavigationFallbackMode == null ? RenderMode.MODEL : engine.viewportNavigationFallbackMode),
                value -> engine.viewportNavigationFallbackMode = EngineRenderPanelSupport.parseRenderModeLabel(value));
        engine.addNumericRow(globalSection, "Cílové FPS viewportu", engine.formatTransformValue(engine.viewportTargetFps), text -> {
            engine.viewportTargetFps = Math.max(12.0, Math.min(25.0, engine.parseOrFallback(text, engine.viewportTargetFps)));
        });
        engine.addNumericRow(globalSection, "Měřítko viewportu", engine.formatTransformValue(engine.renderScale), text -> {
            engine.renderScale = Math.max(0.30, Math.min(1.00, engine.parseOrFallback(text, engine.renderScale)));
            engine.applyRenderScale();
        });
        engine.addNumericRow(globalSection, "Min. interaktivní měřítko", engine.formatTransformValue(engine.interactiveRenderScale), text -> {
            engine.interactiveRenderScale = Math.max(0.35, Math.min(1.00, engine.parseOrFallback(text, engine.interactiveRenderScale)));
            EngineRenderRuntime.updateRealtimePerformanceState(engine, false);
        });
        engine.addNumericRow(globalSection, "Dohled", engine.formatTransformValue(engine.viewDistanceLimit), text -> {
            engine.viewDistanceLimit = Math.max(10.0, Math.min(5000.0, engine.parseOrFallback(text, engine.viewDistanceLimit)));
            engine.applySceneVisibility(false);
        });
        engine.addNumericRow(globalSection, "Počet vláken", Integer.toString(engine.parallelWorkerCount), text -> {
            int max = ThreadPool.recommendedWorkerCount();
            int target = Math.max(1, Math.min(max, (int) Math.round(engine.parseOrFallback(text, engine.parallelWorkerCount))));
            engine.adjustWorkerCount(target - engine.parallelWorkerCount);
        });

        JPanel wireframeSection = engine.addCollapsibleSection(renderTab, "Wireframe", false);
        engine.addBooleanRow(wireframeSection, "Hloubkově skryté hrany", engine.wireframeRenderer.isDepthHiddenLines(), value ->
                engine.wireframeRenderer.setParameter("depthHiddenLines", value));
        engine.addBooleanRow(wireframeSection, "Zvýraznit siluetu", engine.wireframeRenderer.isSilhouetteBoost(), value ->
                engine.wireframeRenderer.setParameter("silhouetteBoost", value));
        engine.addBooleanRow(wireframeSection, "Přerušované hrany", engine.wireframeRenderer.isDashedMode(), value ->
                engine.wireframeRenderer.setParameter("dashedMode", value));

        JPanel ditherSection = engine.addCollapsibleSection(renderTab, "Dither / ASCII", false);
        DitherRenderer.DitherStyle ditherStyle = engine.ditherRenderer.getStyle();
        engine.addComboRow(ditherSection, "Styl",
                new String[]{"BLUE_NOISE", "PATTERN", "ASCII"},
                ditherStyle.toString(),
                value -> {
                    engine.setDitherStyle(DitherRenderer.DitherStyle.valueOf(value));
                    build(engine);
                    engine.window.selectRightTab("Render");
                });
        engine.addNumericRow(ditherSection, "Počet tónů", Integer.toString(engine.ditherToneCount), text -> {
            engine.ditherToneCount = Math.max(2, (int) Math.round(engine.parseOrFallback(text, engine.ditherToneCount)));
            engine.ditherRenderer.setParameter("toneCount", engine.ditherToneCount);
        });
        engine.addNumericRow(ditherSection, "Kontrast", engine.formatTransformValue(engine.ditherContrast), text -> {
            engine.ditherContrast = Math.max(0.1, Math.min(4.0, engine.parseOrFallback(text, engine.ditherContrast)));
            engine.ditherRenderer.setParameter("contrast", engine.ditherContrast);
        });
        engine.addNumericRow(ditherSection, "Světelná pomoc", engine.formatTransformValue(engine.ditherLightAssist), text -> {
            engine.ditherLightAssist = Math.max(0.0, Math.min(1.0, engine.parseOrFallback(text, engine.ditherLightAssist)));
            engine.ditherRenderer.setParameter("lightAssist", engine.ditherLightAssist);
        });
        engine.addBooleanRow(ditherSection, "Invertovat", engine.ditherInvert, value -> {
            engine.ditherInvert = value;
            engine.ditherRenderer.setParameter("invert", engine.ditherInvert);
        });
        if (ditherStyle == DitherRenderer.DitherStyle.ASCII) {
            engine.addNumericRow(ditherSection, "Velikost buňky", Integer.toString(engine.ditherCellSize), text -> {
                engine.ditherCellSize = Math.max(2, (int) Math.round(engine.parseOrFallback(text, engine.ditherCellSize)));
                engine.ditherRenderer.setParameter("cellSize", engine.ditherCellSize);
            });
            engine.addTextRow(ditherSection, "Znaková sada ASCII", engine.ditherAsciiCharset, value -> {
                if (!value.isBlank()) {
                    engine.ditherAsciiCharset = value;
                    engine.ditherRenderer.setParameter("asciiCharset", engine.ditherAsciiCharset);
                }
            });
        }

        JPanel temporalSection = engine.addCollapsibleSection(renderTab, "Temporal Noise", false);
        temporalSection.add(UiBuilder.helperText("Temporal Noise používá stabilní 2D grain na pevné mřížce. Objekty běží integer kroky po X i Y přes společný regionální krokovač, pozadí zůstává statické."));
        engine.addNumericRow(temporalSection, "Tempo posuvu", engine.formatTransformValue(engine.temporalTickRate), text -> {
            engine.temporalTickRate = Math.max(0.1, Math.min(20.0, engine.parseOrFallback(text, engine.temporalTickRate)));
            engine.temporalNoiseRenderer.setParameter("temporalTickRate", engine.temporalTickRate);
        });
        engine.addNumericRow(temporalSection, "Blízkostní příspěvek", engine.formatTransformValue(engine.temporalNearContribution), text -> {
            engine.temporalNearContribution = Math.max(0.0, Math.min(4.0, engine.parseOrFallback(text, engine.temporalNearContribution)));
            engine.temporalNoiseRenderer.setParameter("depthNearContribution", engine.temporalNearContribution);
        });
        engine.addNumericRow(temporalSection, "Příspěvek šikmého úhlu", engine.formatTransformValue(engine.temporalGrazingContribution), text -> {
            engine.temporalGrazingContribution = Math.max(0.0, Math.min(4.0, engine.parseOrFallback(text, engine.temporalGrazingContribution)));
            engine.temporalNoiseRenderer.setParameter("grazingContribution", engine.temporalGrazingContribution);
        });
        engine.addNumericRow(temporalSection, "Minimální rychlost", engine.formatTransformValue(engine.temporalMinSpeed), text -> {
            engine.temporalMinSpeed = Math.max(0.1, Math.min(12.0, engine.parseOrFallback(text, engine.temporalMinSpeed)));
            engine.temporalNoiseRenderer.setParameter("minSpeed", engine.temporalMinSpeed);
        });
        engine.addNumericRow(temporalSection, "Maximální rychlost", engine.formatTransformValue(engine.temporalMaxSpeed), text -> {
            engine.temporalMaxSpeed = Math.max(0.1, Math.min(12.0, engine.parseOrFallback(text, engine.temporalMaxSpeed)));
            engine.temporalNoiseRenderer.setParameter("maxSpeed", engine.temporalMaxSpeed);
        });
        engine.addNumericRow(temporalSection, "Síla okrajového blendu", engine.formatTransformValue(engine.temporalEdgeBlendStrength), text -> {
            engine.temporalEdgeBlendStrength = Math.max(0.0, Math.min(0.25, engine.parseOrFallback(text, engine.temporalEdgeBlendStrength)));
            engine.temporalNoiseRenderer.setParameter("edgeBlendStrength", engine.temporalEdgeBlendStrength);
        });
        engine.addComboRow(temporalSection, "Velikost zrna",
                TemporalNoiseRenderer.grainCellSizePresetLabels(),
                TemporalNoiseRenderer.grainCellSizePresetLabel(engine.temporalGrainCellSize),
                value -> {
            engine.temporalGrainCellSize = TemporalNoiseRenderer.grainCellSizePresetFromLabel(value);
            engine.temporalNoiseRenderer.setParameter("grainCellSize", engine.temporalGrainCellSize);
        });
        temporalSection.add(UiBuilder.helperText("Klávesa Ž v režimu Temporal Noise cykluje preset 1x1, 2x2 a 4x4."));
        engine.addNumericRow(temporalSection, "Úrovně palety", Integer.toString(engine.temporalPaletteLevels), text -> {
            engine.temporalPaletteLevels = Math.max(2, Math.min(8,
                    (int) Math.round(engine.parseOrFallback(text, engine.temporalPaletteLevels))));
            engine.temporalNoiseRenderer.setParameter("paletteLevels", engine.temporalPaletteLevels);
        });

        JPanel hexSection = engine.addCollapsibleSection(renderTab, "Hex", false);
        engine.addNumericRow(hexSection, "Velikost buňky", engine.formatTransformValue(engine.hexCellSizeSetting), text -> {
            engine.hexCellSizeSetting = Math.max(4.0, Math.min(64.0, engine.parseOrFallback(text, engine.hexCellSizeSetting)));
            engine.hexMosaicRenderer.setParameter("cellSize", engine.hexCellSizeSetting);
        });
        engine.addNumericRow(hexSection, "Kvantizace", Integer.toString(engine.hexQuantizationLevels), text -> {
            engine.hexQuantizationLevels = Math.max(2, Math.min(32,
                    (int) Math.round(engine.parseOrFallback(text, engine.hexQuantizationLevels))));
            engine.hexMosaicRenderer.setParameter("quantizationLevels", engine.hexQuantizationLevels);
        });
        engine.addNumericRow(hexSection, "Outline", engine.formatTransformValue(engine.hexOutlineStrength), text -> {
            engine.hexOutlineStrength = Math.max(0.0, Math.min(1.0, engine.parseOrFallback(text, engine.hexOutlineStrength)));
            engine.hexMosaicRenderer.setParameter("outlineStrength", engine.hexOutlineStrength);
        });
        engine.addNumericRow(hexSection, "Síla wow efektu", engine.formatTransformValue(engine.hexWowStrength), text -> {
            engine.hexWowStrength = Math.max(0.0, Math.min(1.0, engine.parseOrFallback(text, engine.hexWowStrength)));
            engine.hexMosaicRenderer.setParameter("wowStrength", engine.hexWowStrength);
        });
        engine.addComboRow(hexSection, "Theme",
                new String[]{"CLASSIC", "PRISM", "NEON"},
                engine.hexMosaicRenderer.getWowModeName().toUpperCase(),
                value -> engine.hexMosaicRenderer.setParameter("wowMode", value.toLowerCase()));
        engine.addBooleanRow(hexSection, "Edge aware", engine.hexEdgeAware, value -> {
            engine.hexEdgeAware = value;
            engine.hexMosaicRenderer.setParameter("edgeAware", engine.hexEdgeAware);
        });
        engine.addBooleanRow(hexSection, "Škálování vzdáleností", engine.hexDistanceScaling, value -> {
            engine.hexDistanceScaling = value;
            engine.hexMosaicRenderer.setParameter("distanceScaling", engine.hexDistanceScaling);
        });
        engine.addBooleanRow(hexSection, "Debug buňky", engine.hexDebugCells, value -> {
            engine.hexDebugCells = value;
            engine.hexMosaicRenderer.setParameter("debugCells", engine.hexDebugCells);
        });

        JPanel raySection = engine.addCollapsibleSection(renderTab, "Ray Tracing", false);
        buildRaySection(engine, raySection);
        JPanel pathSection = engine.addCollapsibleSection(renderTab, "Path Tracing", false);
        buildPathSection(engine, pathSection);
        return renderTab;
    }

    private static void buildRaySection(Engine engine, JPanel raySection) {
        engine.addNumericRow(raySection, "Vzorky / snímek", Integer.toString(engine.raySamplesPerFrame), text -> {
            engine.raySamplesPerFrame = Math.max(1, Math.min(64,
                    (int) Math.round(engine.parseOrFallback(text, engine.raySamplesPerFrame))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Velikost tile", Integer.toString(engine.rayTileSize), text -> {
            engine.rayTileSize = Math.max(8, Math.min(128,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayTileSize))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Diffuse", Integer.toString(engine.rayDiffuseBounces), text -> {
            engine.rayDiffuseBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayDiffuseBounces))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Glossy", Integer.toString(engine.rayGlossyBounces), text -> {
            engine.rayGlossyBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayGlossyBounces))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Transmission", Integer.toString(engine.rayTransmissionBounces), text -> {
            engine.rayTransmissionBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayTransmissionBounces))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Volume", Integer.toString(engine.rayVolumeBounces), text -> {
            engine.rayVolumeBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayVolumeBounces))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Transparent", Integer.toString(engine.rayTransparentBounces), text -> {
            engine.rayTransparentBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayTransparentBounces))));
            engine.applyRaySettings();
        });
        engine.addBooleanRow(raySection, "Přímé světlo", engine.rayDirectLighting, value -> {
            engine.rayDirectLighting = value;
            engine.applyRaySettings();
        });
        engine.addBooleanRow(raySection, "Stíny", engine.rayShadows, value -> {
            engine.rayShadows = value;
            engine.applyRaySettings();
        });
        engine.addBooleanRow(raySection, "Odrazy", engine.rayReflections, value -> {
            engine.rayReflections = value;
            engine.applyRaySettings();
        });
        engine.addBooleanRow(raySection, "Denoise", engine.rayDenoise, value -> {
            engine.rayDenoise = value;
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Radius denoise", Integer.toString(engine.rayDenoiseRadius), text -> {
            engine.rayDenoiseRadius = Math.max(1, Math.min(4,
                    (int) Math.round(engine.parseOrFallback(text, engine.rayDenoiseRadius))));
            engine.applyRaySettings();
        });
        engine.addNumericRow(raySection, "Síla denoise", engine.formatTransformValue(engine.rayDenoiseStrength), text -> {
            engine.rayDenoiseStrength = Math.max(0.0, Math.min(1.0, engine.parseOrFallback(text, engine.rayDenoiseStrength)));
            engine.applyRaySettings();
        });
        engine.addComboRow(raySection, "Tone map",
                new String[]{"EXPOSURE", "FILMIC", "ACES"},
                engine.rayToneMap,
                value -> {
                    engine.rayToneMap = value;
                    engine.applyRaySettings();
                });
    }

    private static void buildPathSection(Engine engine, JPanel pathSection) {
        engine.addNumericRow(pathSection, "Vzorky / snímek", Integer.toString(engine.pathSamplesPerFrame), text -> {
            engine.pathSamplesPerFrame = Math.max(1, Math.min(64,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathSamplesPerFrame))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Velikost tile", Integer.toString(engine.pathTileSize), text -> {
            engine.pathTileSize = Math.max(8, Math.min(128,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathTileSize))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Diffuse", Integer.toString(engine.pathDiffuseBounces), text -> {
            engine.pathDiffuseBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathDiffuseBounces))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Glossy", Integer.toString(engine.pathGlossyBounces), text -> {
            engine.pathGlossyBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathGlossyBounces))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Transmission", Integer.toString(engine.pathTransmissionBounces), text -> {
            engine.pathTransmissionBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathTransmissionBounces))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Volume", Integer.toString(engine.pathVolumeBounces), text -> {
            engine.pathVolumeBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathVolumeBounces))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Transparent", Integer.toString(engine.pathTransparentBounces), text -> {
            engine.pathTransparentBounces = Math.max(0, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathTransparentBounces))));
            engine.applyPathSettings();
        });
        engine.addBooleanRow(pathSection, "Přímé světlo", engine.pathDirectLighting, value -> {
            engine.pathDirectLighting = value;
            engine.applyPathSettings();
        });
        engine.addBooleanRow(pathSection, "Obloha / environment", engine.pathSkyEnvironment, value -> {
            engine.pathSkyEnvironment = value;
            engine.applyPathSettings();
        });
        engine.addBooleanRow(pathSection, "Denoise", engine.pathDenoise, value -> {
            engine.pathDenoise = value;
            engine.applyPathSettings();
        });
        engine.addBooleanRow(pathSection, "Zamknout akumulaci (bez resetu)", engine.pathAccumulationLock, value -> {
            engine.pathAccumulationLock = value;
        });
        engine.addNumericRow(pathSection, "Radius denoise", Integer.toString(engine.pathDenoiseRadius), text -> {
            engine.pathDenoiseRadius = Math.max(1, Math.min(4,
                    (int) Math.round(engine.parseOrFallback(text, engine.pathDenoiseRadius))));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Síla denoise", engine.formatTransformValue(engine.pathDenoiseStrength), text -> {
            engine.pathDenoiseStrength = Math.max(0.0, Math.min(1.0, engine.parseOrFallback(text, engine.pathDenoiseStrength)));
            engine.applyPathSettings();
        });
        engine.addComboRow(pathSection, "Tone map",
                new String[]{"EXPOSURE", "FILMIC", "ACES"},
                engine.pathToneMap,
                value -> {
                    engine.pathToneMap = value;
                    engine.applyPathSettings();
                });
        engine.addNumericRow(pathSection, "Clamp direct", engine.formatTransformValue(engine.pathClampDirect), text -> {
            engine.pathClampDirect = Math.max(0.0, engine.parseOrFallback(text, engine.pathClampDirect));
            engine.applyPathSettings();
        });
        engine.addNumericRow(pathSection, "Clamp indirect", engine.formatTransformValue(engine.pathClampIndirect), text -> {
            engine.pathClampIndirect = Math.max(0.0, engine.parseOrFallback(text, engine.pathClampIndirect));
            engine.applyPathSettings();
        });
    }
}
