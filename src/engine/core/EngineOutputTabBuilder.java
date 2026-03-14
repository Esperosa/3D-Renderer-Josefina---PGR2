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
import javax.swing.JTextArea;
import java.awt.Dimension;
import java.awt.Font;
import java.io.File;

/**
 * Tady stavím workflow panel pro finální výstup.
 */
final class EngineOutputTabBuilder {

    private EngineOutputTabBuilder() {
    }

    static JPanel build(Engine engine) {
        JPanel outputTab = engine.window.createRightTab("Output", UiStrings.Tabs.OUTPUT, "output");
        outputTab.removeAll();
        OutputRenderController.Settings outputSettings = engine.outputRenderController.settings();
        normalizeOutputSettings(outputSettings);
        outputTab.add(UiBuilder.panelHeader("Výstup", "Workflow pro statický snímek, sekvenci, GIF a AVI s oddělenými render session složkami."));
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

        JPanel outputTargetSection = engine.addCollapsibleSection(outputTab, "Cíl výstupu", true);
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
        outputTargetSection.add(engine.sectionTitle(outputSettings.createSessionFolder
                ? "Session složky jsou řízené automaticky a mohou připojit timestamp."
                : "Session složka zůstává pevná podle zadaného názvu, takže další exporty míří do stejné složky."));
        engine.addTextRow(outputTargetSection, "Název / prefix session", outputSettings.sessionName, value -> {
            outputSettings.sessionName = value.isBlank() ? "render" : value.trim();
            outputSettings.filePrefix = outputSettings.sessionName;
            refreshOutputTab(engine);
        });
        engine.addBooleanRow(outputTargetSection, "Připojit timestamp", outputSettings.appendTimestampToSession, value -> {
            outputSettings.appendTimestampToSession = value;
            refreshOutputTab(engine);
        });
        outputTargetSection.add(engine.sectionTitle("Výsledná cesta"));
        outputTargetSection.add(createReadOnlyBlock(previewPaths.sessionFolder.toString() + "\n"
                + buildResolvedPathPreview(previewPaths, exportType, outputSettings.format)));

        JPanel outputTypeSection = engine.addCollapsibleSection(outputTab, "Typ výstupu", true);
        engine.addComboRow(outputTypeSection, "Typ exportu",
                outputTypeLabels(),
                outputTypeLabel(exportType),
                value -> {
                    outputSettings.exportType = parseOutputTypeLabel(value);
                    refreshOutputTab(engine);
                });
        outputTypeSection.add(UiBuilder.helperText("Zvolený typ určuje viditelné formátové volby, path preview i session summary."));

        JPanel timingSection = engine.addCollapsibleSection(outputTab, "Rozsah snímků a časování", true);
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
        timingSection.add(engine.sectionTitle("Počet snímků: " + frameCount));
        timingSection.add(engine.sectionTitle("Náhled délky: " + formatDuration(durationSeconds)));
        if ("still".equals(exportType)) {
            timingSection.add(engine.sectionTitle("Statický snímek používá snímek "
                    + (outputSettings.useTimelineRange ? engine.timelineCurrentFrame : outputSettings.frameStart) + "."));
        }

        JPanel formatSection = engine.addCollapsibleSection(outputTab, "Formát obrazu / videa", true);
        if ("still".equals(exportType) || "sequence".equals(exportType)) {
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
        } else if ("gif".equals(exportType)) {
            engine.addBooleanRow(formatSection, "Opakovat stále dokola", outputSettings.gifLoopForever, value -> {
                outputSettings.gifLoopForever = value;
                refreshOutputTab(engine);
            });
            formatSection.add(engine.sectionTitle("Zpoždění snímku se odvozuje z FPS: "
                    + Math.max(10, (int) Math.round(1000.0 / Math.max(1.0, resolvedFps))) + " ms"));
        } else {
            engine.addNumericRow(formatSection, "Kvalita MJPEG",
                    engine.formatTransformValue(outputSettings.aviJpegQuality),
                    text -> {
                        outputSettings.aviJpegQuality = Math.max(0.05, Math.min(1.0,
                                engine.parseOrFallback(text, outputSettings.aviJpegQuality)));
                        refreshOutputTab(engine);
                    });
            formatSection.add(UiBuilder.helperText("AVI se zapisuje jako fixed-rate MJPEG bez externích kodeků."));
        }

        JPanel engineSection = engine.addCollapsibleSection(outputTab, "Renderer výstupu", true);
        engineSection.add(engine.sectionTitle("Renderer: " + EngineRenderPanelSupport.renderModeLabel(outputMode)));
        engineSection.add(engine.sectionTitle(EngineRenderPanelSupport.renderModeSummary(outputMode)));
        engineSection.add(engine.sectionTitle(EngineRenderPanelSupport.renderModeTuningHint(outputMode)));
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
        outputModeActions.add(engine.actionButton("Otevřít živé nastavení renderu", () -> engine.window.selectRightTab("Render")));
        engineSection.add(outputModeActions);
        engineSection.add(Box.createRigidArea(new Dimension(0, 4)));
        engineSection.add(engine.sectionTitle("Výstupní kamera"));
        JPanel outputCameraActions = EngineRenderPanelSupport.createButtonGrid(1);
        outputCameraActions.add(engine.actionButton("Nastavit z aktuálního pohledu", engine::syncOutputCameraFromCurrentView));
        outputCameraActions.add(engine.actionButton("Dívat se výstupní kamerou", () -> engine.jumpViewToOutputCamera(false)));
        outputCameraActions.add(engine.actionButton("FPS pohled z výstupní kamery", () -> engine.jumpViewToOutputCamera(true)));
        outputCameraActions.add(engine.actionButton("Vybrat objekt kamery", () -> {
            if (engine.outputCameraEntity != null) {
                engine.setCurrentEntitySelection(engine.outputCameraEntity);
                engine.window.selectRightTab("Object");
                engine.refreshObjectInspectorValues();
            }
        }));
        engineSection.add(outputCameraActions);

        JPanel qualitySection = engine.addCollapsibleSection(outputTab, "Kvalita a výkon", true);
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
        } else {
            qualitySection.add(UiBuilder.helperText("Tento renderer je single-pass. Tile a akumulace vzorků se zde nezobrazuje."));
        }
        qualitySection.add(engine.sectionTitle("Odhad interního rozlišení: " + internalWidth + " x " + internalHeight));
        qualitySection.add(engine.sectionTitle("Odhad paměti: " + formatBytes(estimatedWorkingSet)));
        if (budgetWarning != null) {
            qualitySection.add(createWarningLabel(budgetWarning));
        } else {
            qualitySection.add(UiBuilder.helperText("Paměťový rozpočet je v aktuální rezervě heapu."));
        }

        JPanel outputModeSettingsSection = engine.addCollapsibleSection(outputTab, "Specifická nastavení rendereru", true);
        buildOutputModeSettings(engine, outputModeSettingsSection, outputSettings, outputMode);

        JPanel summarySection = engine.addCollapsibleSection(outputTab, "Souhrn výstupní session", true);
        summarySection.add(createReadOnlyBlock(buildOutputSessionSummary(
                outputSettings,
                outputMode,
                exportType,
                previewPaths,
                resolvedStartFrame,
                resolvedEndFrame,
                resolvedFps,
                frameCount,
                durationSeconds,
                internalWidth,
                internalHeight,
                estimatedWorkingSet
        )));

        JPanel outputRunSection = engine.addCollapsibleSection(outputTab, "Spuštění", true);
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
        outputRunSection.add(Box.createRigidArea(new Dimension(0, 4)));
        outputRunSection.add(UiBuilder.helperText("Statický snímek, sekvence, GIF i AVI se ukládají do session složky zobrazené výše."));
        outputRunSection.add(engine.sectionTitle(EngineRenderPanelSupport.isProgressiveRenderMode(outputMode)
                ? "Progresivní režimy akumulují vzorky, dokud nedosáhnou cílové hodnoty pro každý snímek."
                : "Single-pass režimy vyrenderují snímek jedním průchodem a ihned zapisují soubory."));
        outputTab.revalidate();
        outputTab.repaint();
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

    private static int estimatedOutputFileCount(OutputRenderController.Settings outputSettings,
                                                String exportType,
                                                int frameCount) {
        int count = "sequence".equals(exportType) ? frameCount : 1;
        if (outputSettings.writePreviewImage) {
            count++;
        }
        if (outputSettings.writeManifest) {
            count++;
        }
        if (outputSettings.writeLogFile) {
            count++;
        }
        return count;
    }

    private static String buildResolvedPathPreview(OutputPathUtil.SessionPaths paths,
                                                   String exportType,
                                                   String stillFormat) {
        StringBuilder preview = new StringBuilder(256);
        preview.append("snímek: ").append(paths.stillPathForFormat(stillFormat).getFileName()).append('\n');
        preview.append("sekvence: ").append(paths.sequenceFolderName).append("/frame_0000.")
                .append(OutputPathUtil.normalizeStillFormat(stillFormat)).append(" ...").append('\n');
        preview.append("gif: ").append(paths.gifPath.getFileName()).append('\n');
        preview.append("avi: ").append(paths.aviPath.getFileName()).append('\n');
        preview.append("aktivní výstup: ").append(paths.primaryOutputPreview(exportType, stillFormat));
        return preview.toString();
    }

    private static String buildOutputSessionSummary(OutputRenderController.Settings outputSettings,
                                                    RenderMode outputMode,
                                                    String exportType,
                                                    OutputPathUtil.SessionPaths previewPaths,
                                                    int startFrame,
                                                    int endFrame,
                                                    double fps,
                                                    int frameCount,
                                                    double durationSeconds,
                                                    int internalWidth,
                                                    int internalHeight,
                                                    long estimatedWorkingSet) {
        StringBuilder summary = new StringBuilder(512);
        summary.append("Výsledná složka: ").append(previewPaths.sessionFolder).append('\n');
        summary.append("Typ exportu: ").append(outputTypeLabel(exportType)).append('\n');
        summary.append("Renderer: ").append(EngineRenderPanelSupport.renderModeLabel(outputMode)).append('\n');
        summary.append("Rozlišení: ").append(outputSettings.width).append(" x ").append(outputSettings.height).append('\n');
        summary.append("Interní rozlišení: ").append(internalWidth).append(" x ").append(internalHeight)
                .append(" @ scale ").append(String.format("%.2f", outputSettings.internalScale)).append('\n');
        summary.append("Rozsah snímků: ");
        if ("still".equals(exportType)) {
            summary.append(startFrame);
        } else {
            summary.append(startFrame).append(" .. ").append(endFrame);
        }
        summary.append('\n');
        summary.append("FPS: ").append(String.format("%.2f", fps)).append('\n');
        summary.append("Odhad snímků: ").append(frameCount).append('\n');
        summary.append("Odhad souborů: ").append(estimatedOutputFileCount(outputSettings, exportType, frameCount)).append('\n');
        summary.append("Odhad délky: ").append(formatDuration(durationSeconds)).append('\n');
        summary.append("Odhad paměti: ").append(formatBytes(estimatedWorkingSet)).append('\n');
        summary.append("Denoise: ").append(outputSettings.denoise ? UiStrings.Common.YES : UiStrings.Common.NO).append('\n');
        summary.append("Primární výstup: ").append(previewPaths.primaryOutputPreview(exportType, outputSettings.format)).append('\n');
        summary.append("Obsah session: ");
        if ("sequence".equals(exportType)) {
            summary.append("manifest + preview + log + ").append(previewPaths.sequenceFolderName).append("/frames");
        } else if ("gif".equals(exportType)) {
            summary.append("manifest + preview + log + animation.gif");
        } else if ("avi".equals(exportType)) {
            summary.append("manifest + preview + log + animation.avi");
        } else {
            summary.append("manifest + preview + log + statický snímek");
        }
        return summary.toString();
    }

    private static JTextArea createReadOnlyBlock(String text) {
        JTextArea area = new JTextArea(text == null ? "" : text);
        area.setEditable(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setOpaque(true);
        area.setBackground(UiTheme.PANEL_INSET);
        area.setForeground(UiTheme.TEXT_PRIMARY);
        area.setCaretColor(UiTheme.TEXT_PRIMARY);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(UiTheme.BORDER_STRONG, 1, true),
                javax.swing.BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));
        area.setAlignmentX(0.0f);
        area.setColumns(26);
        int logicalLines = Math.max(1, area.getText().split("\\R", -1).length);
        int estimatedWrappedLines = Math.max(logicalLines, (int) Math.ceil(area.getText().length() / 34.0));
        int lines = Math.max(4, Math.min(18, Math.max(logicalLines + 1, estimatedWrappedLines)));
        area.setRows(lines);
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, Math.max(88, lines * 18 + 20)));
        return area;
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
                section.add(UiBuilder.helperText("Model je nejrychlejší solid preview. Ignoruje materiály, textury i odrazy."));
                addOutputRasterToggles(section);
            }
            case BASIC -> {
                section.add(UiBuilder.helperText("Basic je single-pass nelitovaný raster pro layout a blokování."));
                addOutputRasterToggles(section);
            }
            case PHONG -> {
                section.add(UiBuilder.helperText("Phong je single-pass lit raster používající světla scény a textury materiálů."));
                addOutputRasterToggles(section);
            }
            case WIREFRAME -> buildOutputWireframeSettings(engine, section, outputSettings);
            case DITHERING -> buildOutputDitherSettings(engine, section, outputSettings);
            case TEMPORAL_NOISE -> buildOutputTemporalSettings(engine, section, outputSettings);
            case RAY_TRACING -> buildOutputRaySettings(engine, section, outputSettings);
            case PATH_TRACING -> buildOutputPathSettings(section);
            case HEX_MOSAIC -> buildOutputHexSettings(engine, section, outputSettings);
        }
    }

    private static void addOutputWorkerCountRow(Engine engine,
                                                JPanel section,
                                                OutputRenderController.Settings outputSettings) {
        engine.addNumericRow(section, "Počet vláken", Integer.toString(outputSettings.workerCount), text -> {
            int max = ThreadPool.recommendedWorkerCount();
            outputSettings.workerCount = Math.max(1, Math.min(max,
                    (int) Math.round(engine.parseOrFallback(text, outputSettings.workerCount))));
            refreshOutputTab(engine);
        });
    }

    private static void addOutputRasterToggles(JPanel section) {
        section.add(Box.createRigidArea(new Dimension(0, 4)));
        section.add(UiBuilder.helperText("Frustum culling i backface culling se zde řídí globálními volbami panelu Render."));
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
    }

    private static void buildOutputRaySettings(Engine engine,
                                               JPanel section,
                                               OutputRenderController.Settings outputSettings) {
        section.add(UiBuilder.helperText("Ray Tracing používá společné progresivní volby výše plus následující light transport přepínače."));
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

    private static void buildOutputPathSettings(JPanel section) {
        section.add(UiBuilder.helperText("Path Tracing používá společné progresivní volby výše. Další přepínače specifické pro output zde nejsou potřeba."));
    }

    private static void buildOutputWireframeSettings(Engine engine,
                                                     JPanel section,
                                                     OutputRenderController.Settings outputSettings) {
        section.add(UiBuilder.helperText("Wireframe je single-pass a používá vlastní edge styling."));
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
        section.add(UiBuilder.helperText("Temporal Noise používá stabilní 2D grain na pevné mřížce. Objekty běží integer kroky po X i Y přes společný regionální krokovač, pozadí zůstává statické."));
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
        section.add(UiBuilder.helperText("Hex je single-pass a používá vlastní buňku, outline a theme parametry."));
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
