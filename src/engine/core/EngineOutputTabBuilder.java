package engine.core;

import engine.render.post.TemporalNoiseRenderer;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.ThreadPool;
import engine.util.UiBuilder;

import javax.swing.Box;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Dimension;
import java.io.File;

/**
 * stavím workflow panel pro finální výstup.
 */
final class EngineOutputTabBuilder {

    private EngineOutputTabBuilder() {
    }

    static JPanel build(Engine engine) {
        java.awt.Point scrollPosition = engine.window.captureRightTabViewPosition("Output");
        JPanel outputTab = engine.window.createRightTab("Output", UiStrings.Tabs.OUTPUT, "output");
        outputTab.removeAll();
        OutputRenderController.Settings outputSettings = engine.outputRenderController.settings();
        normalizeOutputSettings(outputSettings);
        outputTab.add(UiBuilder.panelHeader("Výstup", null));
        outputTab.add(Box.createRigidArea(new Dimension(0, UiTheme.SPACE_3)));
        RenderMode outputMode = outputSettings.mode == null ? RenderMode.PATH_TRACING : outputSettings.mode;
        String exportType = OutputPathUtil.normalizeExportType(outputSettings.exportType);
        int resolvedStartFrame = resolvedOutputStartFrame(engine, outputSettings, exportType);
        int resolvedEndFrame = resolvedOutputEndFrame(engine, outputSettings, exportType);
        double resolvedFps = resolvedOutputFps(engine, outputSettings);
        int frameCount = estimatedOutputFrameCount(exportType, resolvedStartFrame, resolvedEndFrame);
        double durationSeconds = estimatedOutputDurationSeconds(exportType, frameCount, resolvedFps);
        int internalWidth = engine.outputRenderController.previewInternalWidth();
        int internalHeight = engine.outputRenderController.previewInternalHeight();
        long estimatedWorkingSet = engine.outputRenderController.previewEstimatedWorkingSetBytes();
        String budgetWarning = engine.outputRenderController.previewResourceBudgetWarning();
        OutputPathUtil.SessionPaths previewPaths = OutputPathUtil.resolveSessionPaths(
                outputSettings.baseDirectory,
                outputSettings.sessionName,
                outputSettings.createSessionFolder,
                outputSettings.appendTimestampToSession,
                outputSettings.sequenceSubfolderName,
                OutputPathUtil.timestampNow()
        );

        JPanel outputTargetSection = engine.addCollapsibleSection(outputTab, "Target", true);
        engine.addTextRow(outputTargetSection, "Základní složka", outputSettings.baseDirectory, value -> {
            outputSettings.baseDirectory = value.isBlank() ? "renders" : value.trim();
            outputSettings.outputDirectory = outputSettings.baseDirectory;
            refreshOutputTab(engine);
        });
        outputTargetSection.add(engine.actionButton("Vybrat složku...", () -> browseOutputFolder(engine, outputSettings)));
        outputTargetSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engine.addBooleanRow(outputTargetSection, "Vytvořit session složku", outputSettings.createSessionFolder, value -> {
            outputSettings.createSessionFolder = value;
            refreshOutputTab(engine);
        });
        engine.addTextRow(outputTargetSection, "Název / prefix session", outputSettings.sessionName, value -> {
            outputSettings.sessionName = value.isBlank() ? "render" : value.trim();
            outputSettings.filePrefix = outputSettings.sessionName;
            refreshOutputTab(engine);
        });
        engine.addBooleanRow(outputTargetSection, "Připojit timestamp", outputSettings.appendTimestampToSession, value -> {
            outputSettings.appendTimestampToSession = value;
            refreshOutputTab(engine);
        });
        UiBuilder.addReadOnlyRow(outputTargetSection, "Složka", previewPaths.sessionFolder.toString());
        UiBuilder.addReadOnlyRow(outputTargetSection, "Aktivní", previewPaths.primaryOutputPreview(exportType, outputSettings.format));

        JPanel timingSection = engine.addCollapsibleSection(outputTab, "Frames", true);
        engine.addComboRow(timingSection, "Typ exportu",
                outputTypeLabels(),
                outputTypeLabel(exportType),
                value -> {
                    outputSettings.exportType = parseOutputTypeLabel(value);
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(timingSection, "Použít rozsah časové osy", outputSettings.useTimelineRange, value -> {
            outputSettings.useTimelineRange = value;
            refreshOutputTab(engine);
        });
        engine.addNumericRow(timingSection, "Začátek",
                Integer.toString(outputSettings.useTimelineRange ? engine.timelineStartFrame : outputSettings.frameStart),
                text -> {
                    int start = Math.max(0, (int) Math.round(engine.parseOrFallback(text,
                            outputSettings.useTimelineRange ? engine.timelineStartFrame : outputSettings.frameStart)));
                    if (outputSettings.useTimelineRange) {
                        engine.setTimelineRange(start, engine.timelineEndFrame);
                    } else {
                        outputSettings.frameStart = Math.min(start, outputSettings.frameEnd);
                    }
                    refreshOutputTab(engine);
                });
        engine.addNumericRow(timingSection, "Konec",
                Integer.toString(outputSettings.useTimelineRange ? engine.timelineEndFrame : outputSettings.frameEnd),
                text -> {
                    int end = Math.max(0, (int) Math.round(engine.parseOrFallback(text,
                            outputSettings.useTimelineRange ? engine.timelineEndFrame : outputSettings.frameEnd)));
                    if (outputSettings.useTimelineRange) {
                        engine.setTimelineRange(engine.timelineStartFrame, end);
                    } else {
                        outputSettings.frameEnd = Math.max(outputSettings.frameStart, end);
                    }
                    refreshOutputTab(engine);
                });
        engine.addNumericRow(timingSection, "FPS",
                engine.formatTransformValue(outputSettings.useTimelineRange ? engine.timelineFps : outputSettings.frameRate),
                text -> {
                    double fps = Math.max(1.0, Math.min(240.0,
                            engine.parseOrFallback(text, outputSettings.useTimelineRange ? engine.timelineFps : outputSettings.frameRate)));
                    if (outputSettings.useTimelineRange) {
                        engine.timelineFps = fps;
                        engine.refreshTimelineUi();
                    } else {
                        outputSettings.frameRate = fps;
                    }
                    refreshOutputTab(engine);
                });
        UiBuilder.addReadOnlyRow(timingSection, "Rozsah", frameCount + " snímků");
        UiBuilder.addReadOnlyRow(timingSection, "Délka", formatDuration(durationSeconds));
        switch (exportType) {
            case "still" -> UiBuilder.addReadOnlyRow(timingSection, "Snímek",
                    Integer.toString(outputSettings.useTimelineRange ? engine.timelineCurrentFrame : outputSettings.frameStart));
            default -> {
            }
        }

        JPanel formatSection = engine.addCollapsibleSection(outputTab, "Format", true);
        switch (exportType) {
            case "still", "sequence" -> {
                engine.addComboRow(formatSection, "Formát obrazu",
                        new String[]{"PNG", "JPG"},
                        "jpg".equalsIgnoreCase(outputSettings.format) ? "JPG" : "PNG",
                        value -> {
                            outputSettings.format = "JPG".equalsIgnoreCase(value) ? "jpg" : "png";
                            refreshOutputTab(engine);
                        });
                if ("jpg".equalsIgnoreCase(outputSettings.format)) {
                    engine.addNumericRow(formatSection, "Kvalita JPG",
                            engine.formatTransformValue(outputSettings.jpgQuality),
                            text -> {
                                outputSettings.jpgQuality = Math.max(0.05, Math.min(1.0,
                                        engine.parseOrFallback(text, outputSettings.jpgQuality)));
                                refreshOutputTab(engine);
                            });
                } else {
                    engine.addBooleanRow(formatSection, "Uložit alfa kanál, pokud to jde", outputSettings.saveAlphaWhenPossible, value -> {
                        outputSettings.saveAlphaWhenPossible = value;
                        refreshOutputTab(engine);
                    });
                }
            }
            case "gif" -> {
                engine.addBooleanRow(formatSection, "Opakovat stále dokola", outputSettings.gifLoopForever, value -> {
                    outputSettings.gifLoopForever = value;
                    refreshOutputTab(engine);
                });
                UiBuilder.addReadOnlyRow(formatSection, "Zpoždění",
                        Math.max(10, (int) Math.round(1000.0 / Math.max(1.0, resolvedFps))) + " ms");
            }
            default -> {
                engine.addNumericRow(formatSection, "Kvalita MJPEG",
                        engine.formatTransformValue(outputSettings.aviJpegQuality),
                        text -> {
                            outputSettings.aviJpegQuality = Math.max(0.05, Math.min(1.0,
                                    engine.parseOrFallback(text, outputSettings.aviJpegQuality)));
                            refreshOutputTab(engine);
                        });
            }
        }

        JPanel engineSection = engine.addCollapsibleSection(outputTab, "Engine", true);
        engine.addComboRow(engineSection, "Renderer",
                EngineRenderPanelSupport.renderModeLabels(),
                EngineRenderPanelSupport.renderModeLabel(outputMode),
                value -> selectOutputRenderMode(engine, outputSettings, EngineRenderPanelSupport.parseRenderModeLabel(value)));
        JPanel outputModeActions = EngineRenderPanelSupport.createButtonGrid(1);
        outputModeActions.add(engine.actionButton("Použít renderer viewportu", () -> {
            outputSettings.mode = engine.activeMode == null ? RenderMode.PHONG : engine.activeMode;
            refreshOutputTab(engine);
        }));
        outputModeActions.add(engine.actionButton("Převzít nastavení viewportu", () -> {
            EngineRenderPanelSupport.syncOutputTuningFromViewport(engine, outputSettings);
            refreshOutputTab(engine);
        }));
        engineSection.add(outputModeActions);
        engineSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engineSection.add(UiBuilder.infoLine("Výstupní kamera"));
        JPanel outputCameraActions = EngineRenderPanelSupport.createButtonGrid(1);
        outputCameraActions.add(engine.actionButton("Nastavit z aktuálního pohledu", engine::syncOutputCameraFromCurrentView));
        outputCameraActions.add(engine.actionButton("Dívat se výstupní kamerou", () -> engine.jumpViewToOutputCamera(false)));
        outputCameraActions.add(engine.actionButton("FPS pohled z výstupní kamery", () -> engine.jumpViewToOutputCamera(true)));
        outputCameraActions.add(engine.actionButton("Vybrat objekt kamery", () -> {
            if (engine.outputCameraEntity != null) {
                engine.setCurrentEntitySelection(engine.outputCameraEntity);
                engine.window.selectRightTab("Item");
                engine.refreshObjectInspectorValues();
            }
        }));
        engineSection.add(outputCameraActions);

        JPanel qualitySection = engine.addCollapsibleSection(outputTab, "Quality", true);
        engine.addNumericRow(qualitySection, "Šířka", Integer.toString(outputSettings.width), text -> {
            outputSettings.width = Math.max(64, Math.min(16384,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.width))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(qualitySection, "Výška", Integer.toString(outputSettings.height), text -> {
            outputSettings.height = Math.max(64, Math.min(16384,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.height))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(qualitySection, "Interní měřítko", engine.formatTransformValue(outputSettings.internalScale), text -> {
            outputSettings.internalScale = Math.max(0.25, Math.min(1.0,
                    engine.parseOrFallback(text, outputSettings.internalScale)));
            refreshOutputTab(engine);
        });
        qualitySection.add(engine.actionButton("Převzít z viewportu", () -> {
            outputSettings.width = Math.max(1, engine.frameBuffer != null ? engine.frameBuffer.getWidth() : engine.baseWidth);
            outputSettings.height = Math.max(1, engine.frameBuffer != null ? engine.frameBuffer.getHeight() : engine.baseHeight);
            refreshOutputTab(engine);
        }));
        qualitySection.add(Box.createRigidArea(new Dimension(0, 4)));
        addOutputWorkerCountRow(engine, qualitySection, outputSettings);
        if (EngineRenderPanelSupport.isProgressiveRenderMode(outputMode)) {
            addOutputProgressiveCommon(engine, qualitySection, outputSettings);
        }
        UiBuilder.addReadOnlyRow(qualitySection, "Interní", internalWidth + " x " + internalHeight);
        UiBuilder.addReadOnlyRow(qualitySection, "Paměť", formatBytes(estimatedWorkingSet));
        if (budgetWarning != null) {
            qualitySection.add(createWarningLabel(budgetWarning));
        }

        JPanel outputModeSettingsSection = engine.addCollapsibleSection(outputTab, "Mode", true);
        buildOutputModeSettings(engine, outputModeSettingsSection, outputSettings, outputMode);

        JPanel outputRunSection = engine.addCollapsibleSection(outputTab, "Run", true);
        UiBuilder.addReadOnlyRow(outputRunSection, "Cíl", previewPaths.primaryOutputPreview(exportType, outputSettings.format));
        UiBuilder.addReadOnlyRow(outputRunSection, "Renderer", EngineRenderPanelSupport.renderModeLabel(outputMode));
        UiBuilder.addReadOnlyRow(outputRunSection, "Rozsah", frameCount + " snímků");
        UiBuilder.addReadOnlyRow(outputRunSection, "Délka", formatDuration(durationSeconds));
        outputRunSection.add(Box.createRigidArea(new Dimension(0, 8)));
        JPanel outputRunGrid = EngineRenderPanelSupport.createButtonGrid(1);
        outputRunGrid.add(EngineRenderPanelSupport.primaryActionButton(engine, UiStrings.Output.RENDER_STILL, () -> {
            outputSettings.exportType = "still";
            engine.requestOutputStill(null);
        }));
        outputRunGrid.add(EngineRenderPanelSupport.primaryActionButton(engine, UiStrings.Output.RENDER_SEQUENCE, () -> {
            outputSettings.exportType = "sequence";
            engine.requestOutputImageSequence();
        }));
        outputRunGrid.add(EngineRenderPanelSupport.primaryActionButton(engine, UiStrings.Output.RENDER_GIF, () -> {
            outputSettings.exportType = "gif";
            engine.requestOutputAnimatedGif();
        }));
        outputRunGrid.add(EngineRenderPanelSupport.primaryActionButton(engine, UiStrings.Output.RENDER_AVI, () -> {
            outputSettings.exportType = "avi";
            engine.requestOutputAnimatedAvi();
        }));
        outputRunGrid.add(engine.actionButton("Zrušit render", engine.outputRenderController::cancelRender));
        outputRunSection.add(outputRunGrid);
        outputRunSection.add(UiBuilder.infoLine(EngineRenderPanelSupport.isProgressiveRenderMode(outputMode)
                ? "Progresivní akumulace vzorků."
                : "Single-pass zápis."));
        outputTab.revalidate();
        outputTab.repaint();
        engine.window.restoreRightTabViewPosition("Output", scrollPosition);
        return outputTab;
    }

    private static void normalizeOutputSettings(OutputRenderController.Settings outputSettings) {
        if (outputSettings == null) {
            return;
        }
        if (outputSettings.baseDirectory == null || outputSettings.baseDirectory.isBlank()) {
            outputSettings.baseDirectory = outputSettings.outputDirectory == null || outputSettings.outputDirectory.isBlank()
                    ? "renders"
                    : outputSettings.outputDirectory.trim();
        }
        if (outputSettings.sessionName == null || outputSettings.sessionName.isBlank()) {
            outputSettings.sessionName = outputSettings.filePrefix == null || outputSettings.filePrefix.isBlank()
                    ? "render"
                    : outputSettings.filePrefix.trim();
        }
        outputSettings.outputDirectory = outputSettings.baseDirectory;
        outputSettings.filePrefix = outputSettings.sessionName;
        outputSettings.exportType = OutputPathUtil.normalizeExportType(outputSettings.exportType);
        outputSettings.format = OutputPathUtil.normalizeStillFormat(outputSettings.format);
        outputSettings.sequenceSubfolderName = OutputPathUtil.sanitizeSegment(outputSettings.sequenceSubfolderName, "sequence");
        outputSettings.frameStart = Math.max(0, Math.min(outputSettings.frameStart, outputSettings.frameEnd));
        outputSettings.frameEnd = Math.max(outputSettings.frameStart, outputSettings.frameEnd);
        outputSettings.frameRate = Math.max(1.0, Math.min(240.0, outputSettings.frameRate));
        outputSettings.jpgQuality = Math.max(0.05, Math.min(1.0, outputSettings.jpgQuality));
        outputSettings.temporalTickRate = Math.max(0.1, Math.min(20.0, outputSettings.temporalTickRate));
        outputSettings.temporalNearContribution = Math.max(0.0, Math.min(4.0, outputSettings.temporalNearContribution));
        outputSettings.temporalGrazingContribution = Math.max(0.0, Math.min(4.0, outputSettings.temporalGrazingContribution));
        outputSettings.temporalMinSpeed = Math.max(0.1, Math.min(12.0, outputSettings.temporalMinSpeed));
        outputSettings.temporalMaxSpeed = Math.max(outputSettings.temporalMinSpeed, Math.min(12.0, outputSettings.temporalMaxSpeed));
        outputSettings.temporalEdgeBlendStrength = Math.max(0.0, Math.min(0.25, outputSettings.temporalEdgeBlendStrength));
        outputSettings.temporalGrainCellSize = TemporalNoiseRenderer.normalizeGrainCellSizePreset(outputSettings.temporalGrainCellSize);
        outputSettings.temporalPaletteLevels = Math.max(2, Math.min(8, outputSettings.temporalPaletteLevels));
        outputSettings.aviJpegQuality = Math.max(0.05, Math.min(1.0, outputSettings.aviJpegQuality));
    }

    private static void refreshOutputTab(Engine engine) {
        build(engine);
        engine.window.selectRightTab("Output");
    }

    private static void browseOutputFolder(Engine engine, OutputRenderController.Settings outputSettings) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Vyberte základní složku výstupu");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        File current = new File(outputSettings.baseDirectory == null || outputSettings.baseDirectory.isBlank()
                ? "."
                : outputSettings.baseDirectory);
        if (current.exists()) {
            File chooserDir = current.isDirectory() ? current : current.getParentFile();
            if (chooserDir != null) {
                chooser.setCurrentDirectory(chooserDir);
            }
            chooser.setSelectedFile(current);
        }
        int result = chooser.showOpenDialog(engine.window != null ? engine.window.getFrame() : null);
        if (result == JFileChooser.APPROVE_OPTION && chooser.getSelectedFile() != null) {
            outputSettings.baseDirectory = chooser.getSelectedFile().getAbsolutePath();
            outputSettings.outputDirectory = outputSettings.baseDirectory;
            refreshOutputTab(engine);
        }
    }

    private static String[] outputTypeLabels() {
        return new String[]{
                UiStrings.Output.STILL_IMAGE,
                UiStrings.Output.IMAGE_SEQUENCE,
                UiStrings.Output.ANIMATED_GIF,
                UiStrings.Output.ANIMATED_AVI
        };
    }

    private static String outputTypeLabel(String exportType) {
        return UiStrings.outputTypeLabel(OutputPathUtil.normalizeExportType(exportType));
    }

    private static String parseOutputTypeLabel(String label) {
        return UiStrings.outputTypeKey(label);
    }

    private static int resolvedOutputStartFrame(Engine engine,
                                                OutputRenderController.Settings outputSettings,
                                                String exportType) {
        if ("still".equals(exportType)) {
            return outputSettings.useTimelineRange ? engine.timelineCurrentFrame : outputSettings.frameStart;
        }
        return outputSettings.useTimelineRange ? engine.timelineStartFrame : outputSettings.frameStart;
    }

    private static int resolvedOutputEndFrame(Engine engine,
                                              OutputRenderController.Settings outputSettings,
                                              String exportType) {
        if ("still".equals(exportType)) {
            return resolvedOutputStartFrame(engine, outputSettings, exportType);
        }
        return outputSettings.useTimelineRange ? engine.timelineEndFrame : outputSettings.frameEnd;
    }

    private static double resolvedOutputFps(Engine engine, OutputRenderController.Settings outputSettings) {
        return outputSettings.useTimelineRange ? engine.timelineFps : outputSettings.frameRate;
    }

    private static int estimatedOutputFrameCount(String exportType, int startFrame, int endFrame) {
        if ("still".equals(exportType)) {
            return 1;
        }
        return Math.max(1, endFrame - startFrame + 1);
    }

    private static double estimatedOutputDurationSeconds(String exportType, int frameCount, double fps) {
        if ("still".equals(exportType)) {
            return 0.0;
        }
        return frameCount / Math.max(1.0, fps);
    }

    private static JLabel createWarningLabel(String text) {
        JLabel label = new JLabel("<html><b>Upozornění na rozpočet:</b> " + text + "</html>");
        label.setAlignmentX(0.0f);
        label.setForeground(UiTheme.WARNING);
        return label;
    }

    private static String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        String[] units = new String[]{"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format("%.1f %s", value, units[unitIndex]);
    }

    private static String formatDuration(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return "0.0s";
        }
        long totalSeconds = (long) Math.floor(seconds);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long secs = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format("%dh %02dm %02ds", hours, minutes, secs);
        }
        if (minutes > 0L) {
            return String.format("%dm %02ds", minutes, secs);
        }
        return String.format("%.1fs", seconds);
    }

    private static void selectOutputRenderMode(Engine engine,
                                               OutputRenderController.Settings outputSettings,
                                               RenderMode mode) {
        outputSettings.mode = mode == null ? RenderMode.PATH_TRACING : mode;
        refreshOutputTab(engine);
    }

    private static void buildOutputModeSettings(Engine engine,
                                                JPanel section,
                                                OutputRenderController.Settings outputSettings,
                                                RenderMode outputMode) {
        switch (outputMode) {
            case MODEL -> {
                addOutputRasterToggles(section);
            }
            case BASIC -> {
                addOutputRasterToggles(section);
            }
            case PHONG -> {
                addOutputRasterToggles(section);
            }
            case WIREFRAME -> buildOutputWireframeSettings(engine, section, outputSettings);
            case DITHERING -> buildOutputDitherSettings(engine, section, outputSettings);
            case TEMPORAL_NOISE -> buildOutputTemporalSettings(engine, section, outputSettings);
            case RAY_TRACING -> buildOutputRaySettings(engine, section, outputSettings);
            case PATH_TRACING -> buildOutputPathSettings(engine, section, outputSettings);
            case HEX_MOSAIC -> buildOutputHexSettings(engine, section, outputSettings);
        }
    }

    private static void addOutputWorkerCountRow(Engine engine,
                                                JPanel section,
                                                OutputRenderController.Settings outputSettings) {
        engine.addNumericRow(section, "Počet vláken", Integer.toString(outputSettings.workerCount), text -> {
            int max = ThreadPool.availableWorkerCount();
            outputSettings.workerCount = Math.max(1, Math.min(max,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.workerCount))));
            refreshOutputTab(engine);
        });
    }

    private static void addOutputRasterToggles(JPanel section) {
        section.add(Box.createRigidArea(new Dimension(0, 2)));
    }

    private static void addOutputProgressiveCommon(Engine engine,
                                                   JPanel section,
                                                   OutputRenderController.Settings outputSettings) {
        engine.addNumericRow(section, "Velikost tile", Integer.toString(outputSettings.tileSize), text -> {
            outputSettings.tileSize = Math.max(8, Math.min(128,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.tileSize))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Cílové vzorky", Integer.toString(outputSettings.targetSamples), text -> {
            outputSettings.targetSamples = Math.max(1, Math.min(200000,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.targetSamples))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Vzorky / krok", Integer.toString(outputSettings.samplesPerStep), text -> {
            outputSettings.samplesPerStep = Math.max(1, Math.min(64,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.samplesPerStep))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Max depth", Integer.toString(outputSettings.maxDepth), text -> {
            outputSettings.maxDepth = Math.max(1, Math.min(16,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.maxDepth))));
            refreshOutputTab(engine);
        });
        engine.addBooleanRow(section, "Přímé světlo", outputSettings.directLighting,
                value -> {
                    outputSettings.directLighting = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Obloha / environment", outputSettings.sky,
                value -> {
                    outputSettings.sky = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Denoise", outputSettings.denoise,
                value -> {
                    outputSettings.denoise = value;
                    refreshOutputTab(engine);
                });
        engine.addNumericRow(section, "Radius denoise", Integer.toString(outputSettings.denoiseRadius), text -> {
            outputSettings.denoiseRadius = Math.max(1, Math.min(4,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.denoiseRadius))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Síla denoise", engine.formatTransformValue(outputSettings.denoiseStrength), text -> {
            outputSettings.denoiseStrength = Math.max(0.0, Math.min(1.0,
                    engine.parseOrFallback(text, outputSettings.denoiseStrength)));
            refreshOutputTab(engine);
        });
        engine.addComboRow(section, "Tone map",
                new String[]{"EXPOSURE", "FILMIC", "ACES"},
                outputSettings.toneMap,
                value -> {
                    outputSettings.toneMap = value;
                    refreshOutputTab(engine);
                });
    }

    private static void buildOutputRaySettings(Engine engine,
                                               JPanel section,
                                               OutputRenderController.Settings outputSettings) {
        engine.addBooleanRow(section, "Stíny", outputSettings.shadows,
                value -> {
                    outputSettings.shadows = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Odrazy", outputSettings.reflections,
                value -> {
                    outputSettings.reflections = value;
                    refreshOutputTab(engine);
                });
    }

    private static void buildOutputPathSettings(Engine engine,
                                                JPanel section,
                                                OutputRenderController.Settings outputSettings) {
        engine.addBooleanRow(section, "Reference clamp", outputSettings.referenceClampEnabled,
                value -> {
                    outputSettings.referenceClampEnabled = value;
                    refreshOutputTab(engine);
                });
        engine.addNumericRow(section, "Clamp direct", engine.formatTransformValue(outputSettings.pathClampDirect), text -> {
            outputSettings.pathClampDirect = Math.max(0.0, engine.parseOrFallback(text, outputSettings.pathClampDirect));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Clamp indirect", engine.formatTransformValue(outputSettings.pathClampIndirect), text -> {
            outputSettings.pathClampIndirect = Math.max(0.0, engine.parseOrFallback(text, outputSettings.pathClampIndirect));
            refreshOutputTab(engine);
        });
    }

    private static void buildOutputWireframeSettings(Engine engine,
                                                     JPanel section,
                                                     OutputRenderController.Settings outputSettings) {
        engine.addBooleanRow(section, "Hloubkově skryté hrany", outputSettings.wireframeDepthHiddenLines,
                value -> {
                    outputSettings.wireframeDepthHiddenLines = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Zvýraznit siluetu", outputSettings.wireframeSilhouetteBoost,
                value -> {
                    outputSettings.wireframeSilhouetteBoost = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Přerušované hrany", outputSettings.wireframeDashedMode,
                value -> {
                    outputSettings.wireframeDashedMode = value;
                    refreshOutputTab(engine);
                });
    }

    private static void buildOutputDitherSettings(Engine engine,
                                                  JPanel section,
                                                  OutputRenderController.Settings outputSettings) {
        String currentStyle = outputSettings.ditherStyle == null ? "BLUE_NOISE" : outputSettings.ditherStyle;
        engine.addComboRow(section, "Styl",
                new String[]{"BLUE_NOISE", "PATTERN", "ASCII"},
                currentStyle,
                value -> {
                    outputSettings.ditherStyle = value;
                    refreshOutputTab(engine);
                });
        engine.addNumericRow(section, "Počet tónů", Integer.toString(outputSettings.ditherToneCount), text -> {
            outputSettings.ditherToneCount = Math.max(2, Math.min(32,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.ditherToneCount))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Kontrast", engine.formatTransformValue(outputSettings.ditherContrast), text -> {
            outputSettings.ditherContrast = Math.max(0.1, Math.min(4.0,
                    engine.parseOrFallback(text, outputSettings.ditherContrast)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Světelná pomoc", engine.formatTransformValue(outputSettings.ditherLightAssist), text -> {
            outputSettings.ditherLightAssist = Math.max(0.0, Math.min(1.0,
                    engine.parseOrFallback(text, outputSettings.ditherLightAssist)));
            refreshOutputTab(engine);
        });
        engine.addBooleanRow(section, "Invertovat", outputSettings.ditherInvert,
                value -> {
                    outputSettings.ditherInvert = value;
                    refreshOutputTab(engine);
                });
        if ("ASCII".equalsIgnoreCase(currentStyle)) {
            engine.addNumericRow(section, "Velikost buňky", Integer.toString(outputSettings.ditherCellSize), text -> {
                outputSettings.ditherCellSize = Math.max(2, Math.min(128,
                        (int) Math.round(engine.parseOrFallback(text, outputSettings.ditherCellSize))));
                refreshOutputTab(engine);
            });
            engine.addTextRow(section, "Znaková sada ASCII", outputSettings.ditherAsciiCharset, value -> {
                if (!value.isBlank()) {
                    outputSettings.ditherAsciiCharset = value;
                }
                refreshOutputTab(engine);
            });
        }
    }

    private static void buildOutputTemporalSettings(Engine engine,
                                                    JPanel section,
                                                    OutputRenderController.Settings outputSettings) {
        engine.addNumericRow(section, "Tempo posuvu", engine.formatTransformValue(outputSettings.temporalTickRate), text -> {
            outputSettings.temporalTickRate = Math.max(0.1, Math.min(20.0,
                    engine.parseOrFallback(text, outputSettings.temporalTickRate)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Blízkostní příspěvek", engine.formatTransformValue(outputSettings.temporalNearContribution), text -> {
            outputSettings.temporalNearContribution = Math.max(0.0, Math.min(4.0,
                    engine.parseOrFallback(text, outputSettings.temporalNearContribution)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Příspěvek šikmého úhlu", engine.formatTransformValue(outputSettings.temporalGrazingContribution), text -> {
            outputSettings.temporalGrazingContribution = Math.max(0.0, Math.min(4.0,
                    engine.parseOrFallback(text, outputSettings.temporalGrazingContribution)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Minimální rychlost", engine.formatTransformValue(outputSettings.temporalMinSpeed), text -> {
            outputSettings.temporalMinSpeed = Math.max(0.1, Math.min(12.0,
                    engine.parseOrFallback(text, outputSettings.temporalMinSpeed)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Maximální rychlost", engine.formatTransformValue(outputSettings.temporalMaxSpeed), text -> {
            outputSettings.temporalMaxSpeed = Math.max(0.1, Math.min(12.0,
                    engine.parseOrFallback(text, outputSettings.temporalMaxSpeed)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Síla okrajového blendu", engine.formatTransformValue(outputSettings.temporalEdgeBlendStrength), text -> {
            outputSettings.temporalEdgeBlendStrength = Math.max(0.0, Math.min(0.25,
                    engine.parseOrFallback(text, outputSettings.temporalEdgeBlendStrength)));
            refreshOutputTab(engine);
        });
        engine.addComboRow(section, "Velikost zrna",
                TemporalNoiseRenderer.grainCellSizePresetLabels(),
                TemporalNoiseRenderer.grainCellSizePresetLabel(outputSettings.temporalGrainCellSize),
                value -> {
            outputSettings.temporalGrainCellSize = TemporalNoiseRenderer.grainCellSizePresetFromLabel(value);
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Úrovně palety", Integer.toString(outputSettings.temporalPaletteLevels), text -> {
            outputSettings.temporalPaletteLevels = Math.max(2, Math.min(8,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.temporalPaletteLevels))));
            refreshOutputTab(engine);
        });
    }

    private static void buildOutputHexSettings(Engine engine,
                                               JPanel section,
                                               OutputRenderController.Settings outputSettings) {
        engine.addNumericRow(section, "Velikost buňky", engine.formatTransformValue(outputSettings.hexCellSize), text -> {
            outputSettings.hexCellSize = Math.max(4.0, Math.min(64.0,
                    engine.parseOrFallback(text, outputSettings.hexCellSize)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Kvantizace", Integer.toString(outputSettings.hexQuantizationLevels), text -> {
            outputSettings.hexQuantizationLevels = Math.max(2, Math.min(32,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.hexQuantizationLevels))));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Outline", engine.formatTransformValue(outputSettings.hexOutlineStrength), text -> {
            outputSettings.hexOutlineStrength = Math.max(0.0, Math.min(1.0,
                    engine.parseOrFallback(text, outputSettings.hexOutlineStrength)));
            refreshOutputTab(engine);
        });
        engine.addNumericRow(section, "Síla wow efektu", engine.formatTransformValue(outputSettings.hexWowStrength), text -> {
            outputSettings.hexWowStrength = Math.max(0.0, Math.min(1.0,
                    engine.parseOrFallback(text, outputSettings.hexWowStrength)));
            refreshOutputTab(engine);
        });
        engine.addComboRow(section, "Theme",
                new String[]{"CLASSIC", "PRISM", "NEON"},
                outputSettings.hexWowMode == null ? "CLASSIC" : outputSettings.hexWowMode.toUpperCase(),
                value -> {
                    outputSettings.hexWowMode = value.toLowerCase();
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Edge aware", outputSettings.hexEdgeAware,
                value -> {
                    outputSettings.hexEdgeAware = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Škálování vzdáleností", outputSettings.hexDistanceScaling,
                value -> {
                    outputSettings.hexDistanceScaling = value;
                    refreshOutputTab(engine);
                });
        engine.addBooleanRow(section, "Debug buňky", outputSettings.hexDebugCells,
                value -> {
                    outputSettings.hexDebugCells = value;
                    refreshOutputTab(engine);
                });
    }
}
