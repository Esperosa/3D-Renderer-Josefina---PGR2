package engine.core;

import engine.ui.UiStrings;
import engine.util.UiBuilder;

import javax.swing.JButton;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.awt.GridLayout;

/**
 * Tady držím sdílené pomocné metody pro render panely.
 */
final class EngineRenderPanelSupport {

    private EngineRenderPanelSupport() {
    }

    static JPanel createButtonGrid() {
        return createButtonGrid(2);
    }

    static JPanel createButtonGrid(int columns) {
        int safeColumns = Math.max(1, columns);
        JPanel grid = new JPanel(new GridLayout(0, safeColumns, 6, 6));
        grid.setOpaque(false);
        grid.setAlignmentX(0.0f);
        grid.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return grid;
    }

    static JButton primaryActionButton(Engine engine, String text, Runnable action) {
        JButton button = engine.actionButton(text, action);
        UiBuilder.stylePrimaryButton(button);
        return button;
    }

    static String[] renderModeLabels() {
        RenderMode[] modes = new RenderMode[]{
                RenderMode.MODEL,
                RenderMode.BASIC,
                RenderMode.PHONG,
                RenderMode.WIREFRAME,
                RenderMode.DITHERING,
                RenderMode.TEMPORAL_NOISE,
                RenderMode.RAY_TRACING,
                RenderMode.PATH_TRACING,
                RenderMode.HEX_MOSAIC
        };
        String[] labels = new String[modes.length];
        for (int i = 0; i < modes.length; i++) {
            labels[i] = renderModeLabel(modes[i]);
        }
        return labels;
    }

    static String renderModeLabel(RenderMode mode) {
        return UiStrings.renderModeLabel(mode);
    }

    static RenderMode parseRenderModeLabel(String label) {
        if (label == null) {
            return RenderMode.PATH_TRACING;
        }
        return switch (label.trim().toLowerCase()) {
            case "model" -> RenderMode.MODEL;
            case "basic" -> RenderMode.BASIC;
            case "phong" -> RenderMode.PHONG;
            case "wireframe" -> RenderMode.WIREFRAME;
            case "dithering" -> RenderMode.DITHERING;
            case "temporal noise" -> RenderMode.TEMPORAL_NOISE;
            case "ray tracing" -> RenderMode.RAY_TRACING;
            case "path tracing" -> RenderMode.PATH_TRACING;
            case "hex mosaic" -> RenderMode.HEX_MOSAIC;
            default -> RenderMode.PATH_TRACING;
        };
    }

    static void syncOutputTuningFromViewport(Engine engine, OutputRenderController.Settings outputSettings) {
        if (engine == null || outputSettings == null) {
            return;
        }
        outputSettings.workerCount = Math.max(1, engine.parallelWorkerCount);
        engine.outputRenderController.captureStylizedViewportSettings(engine);
        RenderMode mode = outputSettings.mode == null ? RenderMode.PATH_TRACING : outputSettings.mode;
        switch (mode) {
            case RAY_TRACING -> {
                outputSettings.tileSize = Math.max(8, engine.rayTileSize);
                outputSettings.samplesPerStep = Math.max(1, engine.raySamplesPerFrame);
                outputSettings.maxDepth = Math.max(1, engine.rayMaxDepth);
                outputSettings.directLighting = engine.rayDirectLighting;
                outputSettings.shadows = engine.rayShadows;
                outputSettings.reflections = engine.rayReflections;
                outputSettings.denoise = engine.rayDenoise;
                outputSettings.denoiseStartSamples = Math.max(1, engine.rayDenoiseStartSamples);
                outputSettings.denoiseRadius = Math.max(1, engine.rayDenoiseRadius);
                outputSettings.denoiseStrength = engine.rayDenoiseStrength;
            }
            case PATH_TRACING -> {
                outputSettings.tileSize = Math.max(8, engine.pathTileSize);
                outputSettings.samplesPerStep = Math.max(1, engine.pathSamplesPerFrame);
                outputSettings.maxDepth = Math.max(1, engine.pathMaxDepth);
                outputSettings.directLighting = engine.pathDirectLighting;
                outputSettings.sky = engine.pathSkyEnvironment;
                outputSettings.denoise = engine.pathDenoise;
                outputSettings.denoiseStartSamples = Math.max(1, engine.pathDenoiseStartSamples);
                outputSettings.denoiseRadius = Math.max(1, engine.pathDenoiseRadius);
                outputSettings.denoiseStrength = engine.pathDenoiseStrength;
            }
            default -> {
            }
        }
    }

    static boolean isProgressiveRenderMode(RenderMode mode) {
        return mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING;
    }

    static String renderModeSummary(RenderMode mode) {
        if (mode == null) {
            mode = RenderMode.PATH_TRACING;
        }
        return switch (mode) {
            case MODEL -> "Model je nejlehčí solid preview a ignoruje textury, nody i odlesky.";
            case BASIC -> "Basic je rychlý nelitovaný režim pro blokování a orientaci ve scéně.";
            case PHONG -> "Phong je rychlý lit preview pro světla, textury a kompozici.";
            case WIREFRAME -> "Wireframe zdůrazňuje topologii a čitelnost siluet.";
            case DITHERING -> "Dither převádí scénu do stylizovaného single-pass vzhledu.";
            case TEMPORAL_NOISE -> "Temporal odhaluje formu přes animovaný noise flow a paletu.";
            case RAY_TRACING -> "Ray Tracing je pomalejší kvalitní režim s progresivním skládáním.";
            case PATH_TRACING -> "Path Tracing je nejvěrnější progresivní režim pro finální render.";
            case HEX_MOSAIC -> "Hex převádí obraz do buněčné stylizace s hexagonální strukturou.";
        };
    }

    static String renderModeTuningHint(RenderMode mode) {
        if (mode == null) {
            mode = RenderMode.PATH_TRACING;
        }
        return switch (mode) {
            case MODEL ->
                    "Model preview používá jen základní barvy objektů a hodí se při pohybu v těžkých scénách.";
            case BASIC, PHONG ->
                    "Tohle jsou viewport režimy orientované na realtime. Pro finální kvalitu použijte panel Výstup.";
            case WIREFRAME ->
                    "Wireframe má vlastní edge volby. Převzetí viewportu je dobrý start pro stejný vzhled.";
            case DITHERING ->
                    "Dither má ve výstupu vlastní pattern, kontrast i ASCII parametry.";
            case TEMPORAL_NOISE ->
                    "Temporal má ve výstupu vlastní flow a paletu. Převzetí viewportu urychlí nastavení.";
            case HEX_MOSAIC ->
                    "Hex má vlastní cell, outline a theme parametry dostupné ve Výstupu.";
            case RAY_TRACING ->
                    "Viewport může při pohybu krátce spadnout na rychlejší preview. Výstup drží plné ray nastavení.";
            case PATH_TRACING ->
                    "Viewportový Path Tracing berte jako look-dev preview. Finální kvalitu řídí panel Výstup.";
        };
    }
}
