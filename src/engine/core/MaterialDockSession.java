package engine.core;

import engine.material.MaterialGraphEvaluator;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPreviewRenderer;
import engine.material.MaterialPresets;
import engine.material.MaterialSupportMatrix;
import engine.material.MaterialTextureSetImporter;
import engine.material.NodeTextureLibrary;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;
import engine.util.UiBuilder;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FileDialog;
import java.awt.FlowLayout;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import javax.swing.AbstractAction;
import javax.swing.ActionMap;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;

final class MaterialDockSession {
    final Engine engine;
    final JPanel host;
    final Entity entity;
    final PhongMaterial material;
    final MaterialNodeGraph graph;
    final JLabel summaryLabel;
    final MaterialDockViewState viewState;
    final MaterialPreviewRenderer.Settings previewSettings;
    JPanel inspectorPanel;
    JScrollPane inspectorScroll;
    MaterialGraphCanvas canvas;
    MaterialDockPreviewPanel previewPanel;
    JLabel lookdevSummaryLabel;
    JLabel graphSummaryLabel;
    JLabel previewCompatibilityLabel;
    JLabel rasterBadge;
    JLabel rayBadge;
    JLabel pathBadge;
    PhongMaterial lastCommittedMaterialState;
    String pendingMaterialHistoryLabel;

    MaterialDockSession(Engine engine,
                        JPanel host,
                        Entity entity,
                        PhongMaterial material,
                        JLabel summaryLabel) {
        this.engine = engine;
        this.host = host;
        this.entity = entity;
        this.material = material;
        this.graph = material.getOrCreateNodeGraph();
        this.summaryLabel = summaryLabel;
        this.viewState = engine.materialDockViewState;
        this.previewSettings = viewState.previewSettings();
        this.lastCommittedMaterialState = engine.captureMaterialHistoryState(entity);
        this.pendingMaterialHistoryLabel = null;
    }

    JComponent buildWorkspace() {
        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setOpaque(false);
        EditorFocusContext.mark(root, EditorFocusContext.MATERIAL_WORKSPACE);

        JPanel north = new JPanel();
        north.setOpaque(false);
        north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
        JPanel lookdevBody = UiBuilder.addCollapsibleSection(
                north,
                UiStrings.MaterialDock.LOOKDEV_SECTION,
                viewState.isLookdevPanelExpanded(),
                engine::focusCanvas,
                this::handleLookdevPanelToggled
        );
        lookdevBody.setLayout(new BorderLayout());
        lookdevBody.add(buildLookdevContent(), BorderLayout.CENTER);
        root.add(north, BorderLayout.NORTH);

        canvas = new MaterialGraphCanvas(this);
        canvas.setOpaque(true);
        canvas.setBackground(UiTheme.PANEL_INSET);
        canvas.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)
        ));
        root.add(canvas, BorderLayout.CENTER);

        inspectorPanel = new JPanel();
        inspectorPanel.setOpaque(true);
        inspectorPanel.setBackground(UiTheme.PANEL_BG);
        inspectorPanel.setLayout(new BoxLayout(inspectorPanel, BoxLayout.Y_AXIS));
        inspectorPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        inspectorScroll = new JScrollPane(inspectorPanel);
        UiTheme.styleScrollPane(inspectorScroll, UiTheme.PANEL_BG);
        inspectorScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        inspectorScroll.setPreferredSize(new Dimension(UiTheme.MATERIAL_WORKSPACE_INSPECTOR_WIDTH, 0));
        inspectorScroll.setMinimumSize(new Dimension(UiTheme.MATERIAL_WORKSPACE_INSPECTOR_MIN_WIDTH, 0));
        root.add(inspectorScroll, BorderLayout.EAST);
        requestPreviewRefresh();
        return root;
    }

    JComponent buildFooter() {
        return EngineMaterialDock.infoLabel(UiStrings.MaterialDock.FOOTER_HINT_PREFIX
                + EditorKeymap.shortcutLabel(EditorActionId.DELETE)
                + " maže, "
                + EditorKeymap.shortcutLabel(EditorActionId.DUPLICATE)
                + " duplikuje, "
                + EditorKeymap.shortcutLabel(EditorActionId.FRAME_SELECTED)
                + "/"
                + EditorKeymap.shortcutLabel(EditorActionId.FRAME_ALL)
                + " zarovnává a "
                + EditorKeymap.shortcutLabel(EditorActionId.UNDO)
                + " / "
                + EditorKeymap.shortcutLabel(EditorActionId.REDO)
                + " vrací změny.");
    }

    void refreshSummary() {
        if (summaryLabel == null) {
            return;
        }
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        boolean surface = output != null && graph.findInputLink(output.getId(), "surface") != null;
        boolean volume = output != null && graph.findInputLink(output.getId(), "volume") != null;
        String route = surface && volume ? "povrch + objem" : surface ? "povrch" : volume ? "objem" : "bez výstupu";
        MaterialSupportMatrix.GraphSupport support = MaterialSupportMatrix.summarize(graph);
        summaryLabel.setText(material.getName()
                + " | "
                + graph.getNodes().size()
                + " uzlů / "
                + graph.getLinks().size()
                + " spojení | "
                + route);
        if (lookdevSummaryLabel != null) {
            lookdevSummaryLabel.setText("Aktivní výstup: " + route + " | normála: "
                    + (MaterialGraphAuthoring.hasConnectedNormalPath(material) ? "ano" : "ne"));
        }
        if (graphSummaryLabel != null) {
            graphSummaryLabel.setText("Graf: " + graph.getNodes().size() + " uzlů, "
                    + graph.getLinks().size() + " spojení, preset "
                    + MaterialPresets.displayNameForId(material.getPresetName())
                    + " | " + support.compactSummary());
        }
        if (previewCompatibilityLabel != null) {
            previewCompatibilityLabel.setText(buildCompatibilityHint(surface, volume, support));
        }
        refreshCompatibilityBadges(surface, volume, support);
    }

    void refreshInspector() {
        if (inspectorPanel == null) {
            return;
        }
        inspectorPanel.removeAll();
        MaterialNodeGraph.Node node = graph.getNodeById(graph.getSelectedNodeId());
        if (node == null) {
            JLabel title = new JLabel(UiStrings.MaterialDock.NODE_INSPECTOR);
            title.setForeground(UiTheme.TEXT_PRIMARY);
            inspectorPanel.add(title);
            inspectorPanel.add(Box.createRigidArea(new Dimension(0, 8)));
            inspectorPanel.add(EngineMaterialDock.infoLabel(UiStrings.MaterialDock.NODE_INSPECTOR_EMPTY));
            inspectorPanel.add(EngineMaterialDock.infoLabel(UiStrings.MaterialDock.NODE_INSPECTOR_ADD_HINT));
            inspectorPanel.add(Box.createRigidArea(new Dimension(0, 12)));
            inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.RESET_DEFAULT_GRAPH, () -> {
                noteMaterialHistoryLabel("Reset grafu materiálu");
                graph.clearAndReset();
                MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
                EngineMaterialDock.markCustom(material);
                markStructureChanged();
            }));
        } else {
            buildNodeInspector(node);
        }
        inspectorPanel.add(Box.createVerticalGlue());
        refreshInspectorWidth();
        inspectorPanel.revalidate();
        inspectorPanel.repaint();
        refreshSummary();
        if (canvas != null) {
            canvas.repaint();
        }
    }

    private void refreshInspectorWidth() {
        if (inspectorPanel == null || inspectorScroll == null) {
            return;
        }
        Dimension preferred = inspectorPanel.getPreferredSize();
        int resolvedWidth = Math.max(
                UiTheme.MATERIAL_WORKSPACE_INSPECTOR_WIDTH,
                preferred.width + 28
        );
        Dimension width = new Dimension(resolvedWidth, 0);
        inspectorScroll.setPreferredSize(width);
        inspectorScroll.setMinimumSize(width);
        inspectorScroll.revalidate();
        if (host != null) {
            host.revalidate();
        }
    }

    void markStructureChanged() {
        MaterialGraphAuthoring.syncCompatibilityBindings(material);
        commitMaterialHistoryIfChanged(defaultMaterialHistoryLabel());
        refreshInspector();
        requestPreviewRefresh();
        repaintViewport();
    }

    void markMaterialChanged() {
        MaterialGraphAuthoring.syncCompatibilityBindings(material);
        commitMaterialHistoryIfChanged(defaultMaterialHistoryLabel());
        refreshSummary();
        if (canvas != null) {
            canvas.repaint();
        }
        requestPreviewRefresh();
        repaintViewport();
    }

    void markVisualChanged() {
        if (canvas != null) {
            canvas.repaint();
        }
    }

    void repaintViewport() {
        if (engine.window != null && engine.window.getCanvas() != null) {
            engine.window.getCanvas().repaint();
        }
        host.repaint();
    }

    void requestPreviewRefresh() {
        if (previewPanel != null && viewState.isLookdevPanelExpanded()) {
            previewPanel.requestRefresh();
        }
    }

    private void handleLookdevPanelToggled(boolean expanded) {
        viewState.setLookdevPanelExpanded(expanded);
        if (expanded) {
            requestPreviewRefresh();
        }
        if (host != null) {
            host.revalidate();
            host.repaint();
        }
    }

    void noteMaterialHistoryLabel(String label) {
        pendingMaterialHistoryLabel = label;
    }

    private void commitMaterialHistoryIfChanged(String fallbackLabel) {
        PhongMaterial current = engine.captureMaterialHistoryState(entity);
        if (!engine.materialHistoryStatesEqual(lastCommittedMaterialState, current)) {
            String label = pendingMaterialHistoryLabel;
            if (label == null || label.isBlank()) {
                label = fallbackLabel;
            }
            engine.pushMaterialHistoryCommand(label, entity, lastCommittedMaterialState, current);
            lastCommittedMaterialState = current == null ? null : current.copy();
        }
        pendingMaterialHistoryLabel = null;
    }

    private String defaultMaterialHistoryLabel() {
        if (pendingMaterialHistoryLabel != null && !pendingMaterialHistoryLabel.isBlank()) {
            return pendingMaterialHistoryLabel;
        }
        MaterialNodeGraph.Node node = graph.getNodeById(graph.getSelectedNodeId());
        if (node == null) {
            return "Úprava materiálu";
        }
        return switch (node.getType()) {
            case PRINCIPLED_BSDF -> "Úprava Principled BSDF";
            case GLASS_BSDF -> "Úprava Glass BSDF";
            case TRANSPARENT_BSDF -> "Úprava Transparent BSDF";
            case EMISSION_SHADER -> "Úprava emise";
            case VOLUME_MEDIUM -> "Úprava objemu";
            case IMAGE_TEXTURE -> "Úprava textury";
            case OUTPUT_MATERIAL -> "Úprava výstupu materiálu";
            default -> "Úprava uzlu";
        };
    }

    private JComponent buildLookdevContent() {
        JPanel content = new JPanel(new BorderLayout(12, 0));
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.add(UiBuilder.panelHeader(UiStrings.MaterialDock.LOOKDEV_TITLE,
                FeatureMaturityNotes.MATERIAL_GRAPH_SOURCE_OF_TRUTH));
        left.add(Box.createRigidArea(new Dimension(0, 8)));

        JPanel badges = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        badges.setOpaque(false);
        rasterBadge = UiBuilder.badge("Raster");
        rayBadge = UiBuilder.badge("Ray");
        pathBadge = UiBuilder.badge("Path");
        badges.add(rasterBadge);
        badges.add(rayBadge);
        badges.add(pathBadge);
        left.add(badges);

        left.add(Box.createRigidArea(new Dimension(0, 8)));
        lookdevSummaryLabel = EngineMaterialDock.infoLabel("");
        graphSummaryLabel = EngineMaterialDock.infoLabel("");
        previewCompatibilityLabel = EngineMaterialDock.infoLabel("");
        left.add(lookdevSummaryLabel);
        left.add(graphSummaryLabel);
        left.add(previewCompatibilityLabel);
        left.add(Box.createRigidArea(new Dimension(0, 10)));

        JPanel actions = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 0));
        actions.setOpaque(false);
        actions.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.DUPLICATE_MATERIAL, this::duplicateMaterial));
        actions.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.RESET_GRAPH, () -> {
            noteMaterialHistoryLabel("Reset grafu materiálu");
            graph.clearAndReset();
            MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
            EngineMaterialDock.markCustom(material);
            markStructureChanged();
        }));
        actions.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.IMPORT_PBR, this::importPbrTextureSet));
        actions.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.APPLY_TO_SELECTED, () -> {
            entity.setMaterial(material);
            repaintViewport();
        }));
        left.add(actions);

        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setOpaque(false);
        previewPanel = new MaterialDockPreviewPanel(this);
        previewPanel.setPreferredSize(new Dimension(236, 184));
        previewPanel.setMinimumSize(new Dimension(236, 184));
        right.add(previewPanel, BorderLayout.CENTER);
        right.add(buildPreviewControls(), BorderLayout.SOUTH);

        content.add(left, BorderLayout.CENTER);
        content.add(right, BorderLayout.EAST);
        return content;
    }

    private JComponent buildPreviewControls() {
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.add(comboRow(UiStrings.MaterialDock.PREVIEW_PRIMITIVE, MaterialPreviewRenderer.PreviewPrimitive.values(),
                previewSettings.primitive, value -> {
                    previewSettings.primitive = value;
                    requestPreviewRefresh();
                }));
        controls.add(comboRow(UiStrings.MaterialDock.PREVIEW_LIGHTING, MaterialPreviewRenderer.LightingPreset.values(),
                previewSettings.lightingPreset, value -> {
                    previewSettings.lightingPreset = value;
                    requestPreviewRefresh();
                }));
        controls.add(comboRow(UiStrings.MaterialDock.PREVIEW_BACKGROUND, MaterialPreviewRenderer.BackgroundMode.values(),
                previewSettings.backgroundMode, value -> {
                    previewSettings.backgroundMode = value;
                    requestPreviewRefresh();
                }));
        controls.add(comboRow(UiStrings.MaterialDock.PREVIEW_RENDERER, MaterialPreviewRenderer.PreviewMode.values(),
                previewSettings.previewMode, value -> {
                    previewSettings.previewMode = value;
                    requestPreviewRefresh();
                }));
        return controls;
    }

    private <E extends Enum<E>> JComponent comboRow(String label,
                                                    E[] values,
                                                    E selected,
                                                    java.util.function.Consumer<E> onChange) {
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        JLabel rowLabel = new JLabel(label);
        rowLabel.setForeground(UiTheme.TEXT_SECONDARY);
        row.add(rowLabel, BorderLayout.WEST);
        javax.swing.JComboBox<E> combo = new javax.swing.JComboBox<>(values);
        combo.setSelectedItem(selected);
        UiTheme.styleComboBox(combo);
        combo.setRenderer((list, value, index, isSelected, cellHasFocus) -> {
            JLabel renderer = new JLabel(value == null ? "" : previewLabel(value));
            renderer.setOpaque(true);
            renderer.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
            renderer.setBackground(isSelected ? UiTheme.ACCENT_PURPLE : UiTheme.PANEL_BG);
            renderer.setForeground(UiTheme.TEXT_PRIMARY);
            return renderer;
        });
        combo.addActionListener(e -> {
            @SuppressWarnings("unchecked")
            E value = (E) combo.getSelectedItem();
            if (value != null && onChange != null) {
                onChange.accept(value);
            }
            engine.focusCanvas();
        });
        row.add(combo, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        return row;
    }

    private String previewLabel(Enum<?> value) {
        if (value instanceof MaterialPreviewRenderer.PreviewPrimitive primitive) {
            return primitive.label();
        }
        if (value instanceof MaterialPreviewRenderer.LightingPreset lightingPreset) {
            return lightingPreset.label();
        }
        if (value instanceof MaterialPreviewRenderer.BackgroundMode backgroundMode) {
            return backgroundMode.label();
        }
        if (value instanceof MaterialPreviewRenderer.PreviewMode previewMode) {
            return previewMode.label();
        }
        return value == null ? "" : value.name();
    }

    private void refreshCompatibilityBadges(boolean surfaceConnected,
                                           boolean volumeConnected,
                                           MaterialSupportMatrix.GraphSupport support) {
        if (rasterBadge == null || rayBadge == null || pathBadge == null) {
            return;
        }
        rasterBadge.setText("Raster: " + support.raster().label());
        rayBadge.setText("Ray: " + support.ray().label());
        pathBadge.setText("Path: " + support.path().label());
        rasterBadge.setBackground(supportColor(support.raster()));
        rayBadge.setBackground(supportColor(support.ray()));
        pathBadge.setBackground(supportColor(support.path()));
        rasterBadge.setForeground(UiTheme.TEXT_PRIMARY);
        rayBadge.setForeground(UiTheme.TEXT_PRIMARY);
        pathBadge.setForeground(support.path() == MaterialSupportMatrix.SupportLevel.FULL
                ? Color.BLACK
                : UiTheme.TEXT_PRIMARY);
        surfaceConnected = surfaceConnected || volumeConnected;
        rasterBadge.setVisible(surfaceConnected);
        rayBadge.setVisible(surfaceConnected);
        pathBadge.setVisible(surfaceConnected);
    }

    private Color supportColor(MaterialSupportMatrix.SupportLevel level) {
        if (level == null) {
            return UiTheme.BORDER_STRONG;
        }
        return switch (level) {
            case FULL -> UiTheme.ACCENT_GLOW;
            case APPROXIMATE -> UiTheme.WARNING;
            case LIMITED -> UiTheme.ACCENT_PURPLE;
            case UNSUPPORTED -> UiTheme.ERROR;
        };
    }

    private String buildCompatibilityHint(boolean surfaceConnected,
                                          boolean volumeConnected,
                                          MaterialSupportMatrix.GraphSupport support) {
        if (!surfaceConnected && !volumeConnected) {
            return "Výstup není zapojený. Preview ukazuje jen pozadí a fallback stav.";
        }
        if (volumeConnected && !surfaceConnected) {
            return support.compactSummary()
                    + ". Homogenní volume workflow je poctivě nejsilnější v Path režimu.";
        }
        if (MaterialGraphAuthoring.hasConnectedNormalPath(material)) {
            return support.compactSummary() + ". " + FeatureMaturityNotes.NORMAL_COMPATIBILITY_BRIDGE;
        }
        return support.compactSummary() + ". " + FeatureMaturityNotes.RASTER_APPROXIMATION;
    }

    private void duplicateMaterial() {
        PhongMaterial before = engine.captureMaterialHistoryState(entity);
        PhongMaterial copy = material.copy();
        copy.setName(material.getName() + UiStrings.MaterialDock.MATERIAL_COPY_SUFFIX);
        entity.setMaterial(copy);
        engine.pushMaterialHistoryCommand("Duplikace materiálu", entity, before, engine.captureMaterialHistoryState(entity));
        EngineMaterialDock.rebuild(engine);
        repaintViewport();
    }

    private void importPbrTextureSet() {
        List<Path> selected = chooseMultipleImageFiles();
        if (selected.isEmpty()) {
            return;
        }
        MaterialTextureSetImporter.ImportResult result = MaterialTextureSetImporter.importFiles(material, selected);
        if (!result.success()) {
            refreshSummary();
            return;
        }
        EngineMaterialDock.markCustom(material);
        noteMaterialHistoryLabel("Import PBR sady");
        commitMaterialHistoryIfChanged("Import PBR sady");
        EngineMaterialDock.rebuild(engine);
        repaintViewport();
    }

    private List<Path> chooseMultipleImageFiles() {
        ArrayList<Path> out = new ArrayList<>();
        if (engine.window == null || engine.window.getFrame() == null) {
            return out;
        }
        FileDialog dialog = new FileDialog(engine.window.getFrame(), "Importovat PBR sadu", FileDialog.LOAD);
        dialog.setMultipleMode(true);
        dialog.setFilenameFilter((dir, name) -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".png")
                    || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg")
                    || lower.endsWith(".bmp")
                    || lower.endsWith(".tga")
                    || lower.endsWith(".gif");
        });
        dialog.setVisible(true);
        java.io.File[] files = dialog.getFiles();
        if (files == null || files.length == 0) {
            return out;
        }
        for (java.io.File file : files) {
            if (file != null) {
                out.add(file.toPath().toAbsolutePath().normalize());
            }
        }
        return out;
    }

    void selectNode(MaterialNodeGraph.Node node) {
        graph.setSelectedNodeId(node == null ? -1 : node.getId());
        refreshInspector();
    }

    private void buildNodeInspector(MaterialNodeGraph.Node node) {
        JLabel title = new JLabel(node.getType().title());
        title.setForeground(new Color(234, 241, 248));
        inspectorPanel.add(title);
        inspectorPanel.add(EngineMaterialDock.infoLabel(node.getType().category() + " uzel"));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        addConnectionSummary(node);
        String compatibilityNote = nodeCompatibilityNote(node.getType());
        if (compatibilityNote != null && !compatibilityNote.isBlank()) {
            inspectorPanel.add(EngineMaterialDock.infoLabel(compatibilityNote));
            inspectorPanel.add(Box.createRigidArea(new Dimension(0, 4)));
        }
        switch (node.getType()) {
            case OUTPUT_MATERIAL -> buildOutputInspector(node);
            case PRINCIPLED_BSDF -> buildPrincipledInspector(node);
            case GLASS_BSDF -> buildGlassInspector(node);
            case EMISSION_SHADER -> buildEmissionShaderInspector(node);
            case MIX_SHADER -> buildMixShaderInspector(node);
            case VOLUME_MEDIUM -> buildVolumeInspector(node);
            case IMPORTED_BASE_COLOR -> buildTextureInspector(node, material.getDiffuseMap(), "Base Color");
            case IMPORTED_METAL_ROUGHNESS -> buildTextureInspector(node, material.getMetallicRoughnessMap(), "Metal/Roughness");
            case IMPORTED_EMISSIVE -> buildTextureInspector(node, material.getEmissiveMap(), "Emissive");
            case TEXTURE_COORDINATE -> buildTextureCoordinateInspector(node);
            case MAPPING -> buildMappingInspector(node);
            case IMAGE_TEXTURE -> buildImageTextureInspector(node);
            case NORMAL_MAP -> buildNormalMapInspector(node);
            case TRANSPARENT_BSDF -> buildTransparentInspector(node);
            case SEPARATE_RGB -> buildSeparateRgbInspector(node);
            case COMBINE_RGB -> buildCombineRgbInspector(node);
            case RGB -> buildRgbInspector(node);
            case VALUE -> buildValueInspector(node);
            case NOISE_TEXTURE -> buildNoiseInspector(node);
            case COLOR_RAMP -> buildColorRampInspector(node);
            case MIX_COLOR -> buildMixInspector(node);
            case MATH -> buildMathInspector(node);
            case CLAMP -> buildClampInspector(node);
            case MAP_RANGE -> buildMapRangeInspector(node);
        }
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 12)));
        inspectorPanel.add(EngineMaterialDock.sectionLabel(UiStrings.MaterialDock.NODE_ACTIONS));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.RESET_NODE_DEFAULTS, () -> {
            noteMaterialHistoryLabel("Reset výchozích hodnot uzlu");
            node.getType().applyDefaults(node);
            if (node.getType() == MaterialNodeGraph.NodeType.PRINCIPLED_BSDF
                    || node.getType() == MaterialNodeGraph.NodeType.GLASS_BSDF
                    || node.getType() == MaterialNodeGraph.NodeType.EMISSION_SHADER
                    || node.getType() == MaterialNodeGraph.NodeType.VOLUME_MEDIUM
                    || node.getType() == MaterialNodeGraph.NodeType.TRANSPARENT_BSDF) {
                MaterialGraphAuthoring.syncCompatibilityBindings(material);
            }
            EngineMaterialDock.markCustom(material);
            markStructureChanged();
        }));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.DISCONNECT_INPUTS, () -> {
            noteMaterialHistoryLabel("Odpojení vstupů uzlu");
            boolean changed = false;
            for (MaterialNodeGraph.SocketDefinition input : node.getType().inputs()) {
                changed |= graph.disconnectInput(node.getId(), input.key());
            }
            if (changed) {
                EngineMaterialDock.markCustom(material);
                markStructureChanged();
            }
        }));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.DISCONNECT_OUTPUTS, () -> {
            noteMaterialHistoryLabel("Odpojení výstupů uzlu");
            boolean changed = false;
            for (MaterialNodeGraph.SocketDefinition output : node.getType().outputs()) {
                changed |= graph.disconnectOutput(node.getId(), output.key());
            }
            if (changed) {
                EngineMaterialDock.markCustom(material);
                markStructureChanged();
            }
        }));
        if (node.getType().isDeletable()) {
            inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
            inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.DELETE_NODE, () -> {
                noteMaterialHistoryLabel("Smazání uzlu");
                if (graph.removeNode(node.getId())) {
                    EngineMaterialDock.markCustom(material);
                    markStructureChanged();
                }
            }));
        }
    }

    private void addConnectionSummary(MaterialNodeGraph.Node node) {
        int inputConnections = 0;
        for (MaterialNodeGraph.SocketDefinition input : node.getType().inputs()) {
            if (graph.findInputLink(node.getId(), input.key()) != null) {
                inputConnections++;
            }
        }
        int outputConnections = 0;
        for (engine.material.MaterialNodeGraph.Link link : graph.getLinks()) {
            if (link.getFromNodeId() == node.getId()) {
                outputConnections++;
            }
        }
        inspectorPanel.add(EngineMaterialDock.infoLabel("Připojené vstupy: " + inputConnections + " | připojené výstupy: " + outputConnections));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 4)));
    }

    private String nodeCompatibilityNote(MaterialNodeGraph.NodeType type) {
        MaterialSupportMatrix.NodeSupport support = MaterialSupportMatrix.forNode(type);
        String note = support.note();
        if (note == null || note.isBlank()) {
            return support.compactSummary();
        }
        return support.compactSummary() + ". " + note;
    }

    private void appendLinkedInputSummary(MaterialNodeGraph.Node node) {
        List<String> linked = new ArrayList<>();
        for (MaterialNodeGraph.SocketDefinition input : node.getType().inputs()) {
            engine.material.MaterialNodeGraph.Link link = graph.findInputLink(node.getId(), input.key());
            if (link != null) {
                MaterialNodeGraph.Node source = graph.getNodeById(link.getFromNodeId());
                if (source != null) {
                    linked.add(input.label() + " <- " + source.getType().title());
                }
            }
        }
        if (linked.isEmpty()) {
            inspectorPanel.add(EngineMaterialDock.infoLabel("Bez připojených vstupů. Uzel používá své výchozí hodnoty."));
            return;
        }
        for (String line : linked) {
            inspectorPanel.add(EngineMaterialDock.infoLabel(line));
        }
    }

    private void buildOutputInspector(MaterialNodeGraph.Node node) {
        engine.addTextRow(inspectorPanel, "Název materiálu", material.getName(), value -> {
            EngineMaterialDock.markCustom(material);
            material.setName(value);
            markMaterialChanged();
        });
        engine.addComboRow(inspectorPanel, "Předvolba",
                MaterialPresets.presetNames(),
                MaterialPresets.displayNameForId(material.getPresetName()),
                value -> {
                    MaterialPresets.apply(value, material);
                    markMaterialChanged();
                    refreshInspector();
                });
        engine.addComboRow(inspectorPanel, "Alpha režim",
                new String[]{"OPAQUE", "MASK", "BLEND"},
                material.getAlphaMode().name(),
                value -> {
                    EngineMaterialDock.markCustom(material);
                    material.setAlphaMode(EngineMaterialDock.parseAlphaMode(value));
                    markMaterialChanged();
                });
        engine.addNumericRow(inspectorPanel, "Alpha cutoff", engine.formatTransformValue(material.getAlphaCutoff()), text -> {
            EngineMaterialDock.markCustom(material);
            material.setAlphaCutoff(EngineMaterialDock.clamp01(engine.parseOrFallback(text, material.getAlphaCutoff())));
            markMaterialChanged();
        });
        engine.addBooleanRow(inspectorPanel, "Oboustranné", material.isDoubleSided(), value -> {
            EngineMaterialDock.markCustom(material);
            material.setDoubleSided(value);
            markMaterialChanged();
        });
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Směrování"));
        inspectorPanel.add(EngineMaterialDock.infoLabel("Vstup Surface: " + (graph.findInputLink(node.getId(), "surface") == null ? "nepřipojeno" : "připojeno")));
        inspectorPanel.add(EngineMaterialDock.infoLabel("Vstup Volume: " + (graph.findInputLink(node.getId(), "volume") == null ? "nepřipojeno" : "připojeno")));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.RESET_DEFAULT_GRAPH, () -> {
            noteMaterialHistoryLabel("Reset grafu materiálu");
            graph.clearAndReset();
            MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
            EngineMaterialDock.markCustom(material);
            markStructureChanged();
        }));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.Common.BACK_TO_TIMELINE, engine::showTimelineWorkspace));
    }

    private void buildPrincipledInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Povrch"));
        engine.addColorPickerRow(inspectorPanel, "Base Color", node.getColor("base_color", material.getDiffuseColor()), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("base_color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Roughness", node.getNumber("roughness", material.getRoughness()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("roughness", value);
            markStructureChanged();
        });
        addRangedValueRow("Metallic", node.getNumber("metallic", material.getMetallic()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("metallic", value);
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Specular", engine.formatTransformValue(node.getNumber("specular", material.getSpecularFactor())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("specular", engine.parseOrFallback(text, node.getNumber("specular", material.getSpecularFactor())));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "IOR", engine.formatTransformValue(node.getNumber("ior", material.getRefractiveIndex())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("ior", Math.max(1.0, engine.parseOrFallback(text, node.getNumber("ior", material.getRefractiveIndex()))));
            markStructureChanged();
        });
        addRangedValueRow("Transmission", node.getNumber("transmission", material.getTransmission()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("transmission", value);
            markStructureChanged();
        });
        addRangedValueRow("Opacity", node.getNumber("opacity", material.getOpacity()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("opacity", value);
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Emise a vrstvy"));
        engine.addColorPickerRow(inspectorPanel, "Emission", node.getColor("emission", material.getEmissionColor()), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("emission", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Emission Strength", engine.formatTransformValue(node.getNumber("emission_strength", material.getEmissionStrength())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("emission_strength", Math.max(0.0, engine.parseOrFallback(text, node.getNumber("emission_strength", material.getEmissionStrength()))));
            markStructureChanged();
        });
        addRangedValueRow("Clearcoat", node.getNumber("clearcoat", material.getClearcoatFactor()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("clearcoat", value);
            markStructureChanged();
        });
        addRangedValueRow("Clearcoat Roughness", node.getNumber("clearcoat_roughness", material.getClearcoatRoughness()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("clearcoat_roughness", value);
            markStructureChanged();
        });
        engine.addColorPickerRow(inspectorPanel, "Sheen Color", node.getColor("sheen_color", material.getSheenColor()), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("sheen_color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Sheen Roughness", node.getNumber("sheen_roughness", material.getSheenRoughness()), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("sheen_roughness", value);
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel("Nepropojené sockety berou výchozí hodnoty z této konkrétní Principled node, ne z globálního materiálu."));
        appendLinkedInputSummary(node);
    }

    private void buildGlassInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color", node.getColor("color", new Vec3(0.88, 0.94, 1.0)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Roughness", node.getNumber("roughness", 0.04), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("roughness", value);
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "IOR", engine.formatTransformValue(node.getNumber("ior", 1.45)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("ior", Math.max(1.0, engine.parseOrFallback(text, node.getNumber("ior", 1.45))));
            markStructureChanged();
        });
        addRangedValueRow("Opacity", node.getNumber("opacity", 1.0), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("opacity", value);
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void buildEmissionShaderInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color", node.getColor("color", new Vec3(1.0, 0.82, 0.56)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Strength", engine.formatTransformValue(node.getNumber("strength", 2.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("strength", Math.max(0.0, engine.parseOrFallback(text, node.getNumber("strength", 2.0))));
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void buildMixShaderInspector(MaterialNodeGraph.Node node) {
        addRangedValueRow("Factor", node.getNumber("factor", 0.5), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("factor", value);
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel("Míchá dva surface shadery do jednoho výstupu."));
        appendLinkedInputSummary(node);
    }

    private void buildVolumeInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Objem"));
        engine.addColorPickerRow(inspectorPanel, "Medium Color", node.getColor("color", material.getMediumColor()), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Density", engine.formatTransformValue(node.getNumber("density", material.getDensity())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("density", Math.max(0.0, engine.parseOrFallback(text, node.getNumber("density", material.getDensity()))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Anisotropy", engine.formatTransformValue(node.getNumber("anisotropy", material.getAnisotropy())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("anisotropy", engine.parseOrFallback(text, node.getNumber("anisotropy", material.getAnisotropy())));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Thickness", engine.formatTransformValue(node.getNumber("thickness", material.getThickness())), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("thickness", Math.max(0.0, engine.parseOrFallback(text, node.getNumber("thickness", material.getThickness()))));
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel("Density " + EngineMaterialDock.formatShort(node.getNumber("density", material.getDensity()))
                + " | anisotropy " + EngineMaterialDock.formatShort(node.getNumber("anisotropy", material.getAnisotropy()))));
        appendLinkedInputSummary(node);
    }

    private void buildTextureCoordinateInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.infoLabel("Výstupy UV0, UV1 a World pro další texturovací uzly."));
        appendLinkedInputSummary(node);
    }

    private void buildMappingInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Posun"));
        engine.addNumericRow(inspectorPanel, "Location X", engine.formatTransformValue(node.getNumber("location_x", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("location_x", engine.parseOrFallback(text, node.getNumber("location_x", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Location Y", engine.formatTransformValue(node.getNumber("location_y", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("location_y", engine.parseOrFallback(text, node.getNumber("location_y", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Location Z", engine.formatTransformValue(node.getNumber("location_z", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("location_z", engine.parseOrFallback(text, node.getNumber("location_z", 0.0)));
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Rotace"));
        engine.addNumericRow(inspectorPanel, "Rotation X", engine.formatTransformValue(node.getNumber("rotation_x", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("rotation_x", engine.parseOrFallback(text, node.getNumber("rotation_x", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Rotation Y", engine.formatTransformValue(node.getNumber("rotation_y", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("rotation_y", engine.parseOrFallback(text, node.getNumber("rotation_y", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Rotation Z", engine.formatTransformValue(node.getNumber("rotation_z", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("rotation_z", engine.parseOrFallback(text, node.getNumber("rotation_z", 0.0)));
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Měřítko"));
        engine.addNumericRow(inspectorPanel, "Scale X", engine.formatTransformValue(node.getNumber("scale_x", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale_x", engine.parseOrFallback(text, node.getNumber("scale_x", 1.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale Y", engine.formatTransformValue(node.getNumber("scale_y", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale_y", engine.parseOrFallback(text, node.getNumber("scale_y", 1.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale Z", engine.formatTransformValue(node.getNumber("scale_z", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale_z", engine.parseOrFallback(text, node.getNumber("scale_z", 1.0)));
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel("Pro kompatibilní normal-map bridge je plně podporovaná 2D UV transformace přes Z rotaci a XY offset/scale."));
        appendLinkedInputSummary(node);
    }

    private void buildTextureInspector(MaterialNodeGraph.Node node, TextureMap map, String label) {
        inspectorPanel.add(EngineMaterialDock.sectionLabel(label + " mapa"));
        inspectorPanel.add(EngineMaterialDock.infoLabel(EngineMaterialDock.textureSummary(label, map)));
        if (map == null || !map.hasTexture()) {
            inspectorPanel.add(EngineMaterialDock.infoLabel("V tomto slotu není načtená importovaná textura."));
            return;
        }
        engine.addBooleanRow(inspectorPanel, "Lineární filtrování", map.isLinear(), value -> {
            EngineMaterialDock.markCustom(material);
            map.setLinear(value);
            markMaterialChanged();
        });
        engine.addBooleanRow(inspectorPanel, "Překlopit V", map.isFlipV(), value -> {
            EngineMaterialDock.markCustom(material);
            map.setFlipV(value);
            markMaterialChanged();
        });
        engine.addComboRow(inspectorPanel, "UV sada", new String[]{"UV0", "UV1"},
                map.getTexCoord() > 0 ? "UV1" : "UV0", value -> {
                    EngineMaterialDock.markCustom(material);
                    map.setTexCoord("UV1".equalsIgnoreCase(value) ? 1 : 0);
                    markMaterialChanged();
                });
        engine.addNumericRow(inspectorPanel, "Offset U", engine.formatTransformValue(map.getOffsetU()), text -> {
            EngineMaterialDock.markCustom(material);
            map.setOffsetU(engine.parseOrFallback(text, map.getOffsetU()));
            markMaterialChanged();
        });
        engine.addNumericRow(inspectorPanel, "Offset V", engine.formatTransformValue(map.getOffsetV()), text -> {
            EngineMaterialDock.markCustom(material);
            map.setOffsetV(engine.parseOrFallback(text, map.getOffsetV()));
            markMaterialChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale U", engine.formatTransformValue(map.getScaleU()), text -> {
            EngineMaterialDock.markCustom(material);
            map.setScaleU(engine.parseOrFallback(text, map.getScaleU()));
            markMaterialChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale V", engine.formatTransformValue(map.getScaleV()), text -> {
            EngineMaterialDock.markCustom(material);
            map.setScaleV(engine.parseOrFallback(text, map.getScaleV()));
            markMaterialChanged();
        });
        engine.addNumericRow(inspectorPanel, "Rotation", engine.formatTransformValue(map.getRotation()), text -> {
            EngineMaterialDock.markCustom(material);
            map.setRotation(engine.parseOrFallback(text, map.getRotation()));
            markMaterialChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void buildImageTextureInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.sectionLabel(UiStrings.MaterialDock.IMAGE_SOURCE));
        String filePath = node.getText("file_path", "");
        engine.addTextRow(inspectorPanel, "Soubor", filePath, value -> updateImageNodePath(node, value));
        inspectorPanel.add(EngineMaterialDock.infoLabel(NodeTextureLibrary.describe(filePath)));
        inspectorPanel.add(EngineMaterialDock.infoLabel("Výstupy: Color/Alpha a samostatné kanály Red, Green, Blue."));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.IMAGE_PICK, () -> {
            String selected = chooseImageFile(node.getText("file_path", ""));
            if (selected != null) {
                updateImageNodePath(node, selected);
            }
        }));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.IMAGE_RELOAD, () -> {
            NodeTextureLibrary.invalidate(node.getText("file_path", null));
            markStructureChanged();
        }));
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 6)));
        inspectorPanel.add(EngineMaterialDock.createMiniButton(engine, UiStrings.MaterialDock.IMAGE_CLEAR, () -> updateImageNodePath(node, "")));
        inspectorPanel.add(EngineMaterialDock.sectionLabel(UiStrings.MaterialDock.SAMPLING));
        engine.addBooleanRow(inspectorPanel, "Lineární filtrování", node.getNumber("linear", 1.0) >= 0.5, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("linear", value ? 1.0 : 0.0);
            markStructureChanged();
        });
        engine.addComboRow(inspectorPanel, "Color Space",
                EngineMaterialDock.enumNames(MaterialNodeGraph.TextureColorSpace.values()),
                node.getEnum("color_space", MaterialNodeGraph.TextureColorSpace.SRGB.name()),
                value -> {
                    EngineMaterialDock.markCustom(material);
                    node.setEnum("color_space", value);
                    markStructureChanged();
                });
        engine.addBooleanRow(inspectorPanel, "Překlopit V", node.getNumber("flip_v", 0.0) >= 0.5, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("flip_v", value ? 1.0 : 0.0);
            markStructureChanged();
        });
        engine.addComboRow(inspectorPanel, "UV sada", new String[]{"UV0", "UV1"},
                node.getEnum("uv_set", "UV0"), value -> {
                    EngineMaterialDock.markCustom(material);
                    node.setEnum("uv_set", value);
                    markStructureChanged();
                });
        engine.addNumericRow(inspectorPanel, "Offset U", engine.formatTransformValue(node.getNumber("offset_u", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("offset_u", engine.parseOrFallback(text, node.getNumber("offset_u", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Offset V", engine.formatTransformValue(node.getNumber("offset_v", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("offset_v", engine.parseOrFallback(text, node.getNumber("offset_v", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale U", engine.formatTransformValue(node.getNumber("scale_u", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale_u", engine.parseOrFallback(text, node.getNumber("scale_u", 1.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Scale V", engine.formatTransformValue(node.getNumber("scale_v", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale_v", engine.parseOrFallback(text, node.getNumber("scale_v", 1.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Rotation", engine.formatTransformValue(node.getNumber("rotation", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("rotation", engine.parseOrFallback(text, node.getNumber("rotation", 0.0)));
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.sectionLabel("Fallback"));
        engine.addColorPickerRow(inspectorPanel, "Fallback Color", node.getColor("fallback_color", Vec3.ONE), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("fallback_color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Fallback Alpha", node.getNumber("fallback_alpha", 1.0), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("fallback_alpha", value);
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void buildNormalMapInspector(MaterialNodeGraph.Node node) {
        addRangedValueRow("Strength", node.getNumber("strength", 1.0), 0.0, 4.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("strength", value);
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel(FeatureMaturityNotes.NORMAL_COMPATIBILITY_BRIDGE));
        appendLinkedInputSummary(node);
    }

    private void buildTransparentInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color", node.getColor("color", Vec3.ONE), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Opacity", node.getNumber("opacity", 0.0), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("opacity", value);
            markStructureChanged();
        });
        inspectorPanel.add(EngineMaterialDock.infoLabel("Transparent BSDF je poctivě aproximovaný jako přenos + opacity. V raster preview je zjednodušený."));
        appendLinkedInputSummary(node);
    }

    private void buildSeparateRgbInspector(MaterialNodeGraph.Node node) {
        inspectorPanel.add(EngineMaterialDock.infoLabel("Rozděluje color vstup na jednotlivé kanály R, G a B."));
        appendLinkedInputSummary(node);
    }

    private void buildCombineRgbInspector(MaterialNodeGraph.Node node) {
        engine.addNumericRow(inspectorPanel, "Red", engine.formatTransformValue(node.getNumber("red", 0.8)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("red", EngineMaterialDock.clamp01(engine.parseOrFallback(text, node.getNumber("red", 0.8))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Green", engine.formatTransformValue(node.getNumber("green", 0.8)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("green", EngineMaterialDock.clamp01(engine.parseOrFallback(text, node.getNumber("green", 0.8))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Blue", engine.formatTransformValue(node.getNumber("blue", 0.8)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("blue", EngineMaterialDock.clamp01(engine.parseOrFallback(text, node.getNumber("blue", 0.8))));
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void updateImageNodePath(MaterialNodeGraph.Node node, String rawPath) {
        if (node == null) {
            return;
        }
        noteMaterialHistoryLabel("Změna cesty textury");
        String previous = node.getText("file_path", null);
        NodeTextureLibrary.invalidate(previous);
        String normalized = rawPath == null ? "" : rawPath.trim();
        node.setText("file_path", normalized);
        NodeTextureLibrary.invalidate(normalized);
        EngineMaterialDock.markCustom(material);
        markStructureChanged();
    }

    private String chooseImageFile(String currentPath) {
        if (engine.window == null || engine.window.getFrame() == null) {
            return null;
        }
        FileDialog dialog = new FileDialog(engine.window.getFrame(), "Vybrat texturu", FileDialog.LOAD);
        dialog.setFilenameFilter((dir, name) -> {
            String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".png")
                    || lower.endsWith(".jpg")
                    || lower.endsWith(".jpeg")
                    || lower.endsWith(".bmp")
                    || lower.endsWith(".gif");
        });
        if (currentPath != null && !currentPath.isBlank()) {
            try {
                Path path = Path.of(currentPath).toAbsolutePath().normalize();
                Path parent = path.getParent();
                if (parent != null) {
                    dialog.setDirectory(parent.toString());
                }
                dialog.setFile(path.getFileName().toString());
            } catch (RuntimeException ignored) {
                // Při neplatné cestě necháme dialog ve výchozím stavu.
            }
        }
        dialog.setVisible(true);
        String file = dialog.getFile();
        if (file == null || file.isBlank()) {
            return null;
        }
        String directory = dialog.getDirectory();
        Path resolved = directory == null || directory.isBlank()
                ? Path.of(file)
                : Path.of(directory, file);
        return resolved.toAbsolutePath().normalize().toString();
    }

    private void addRangedValueRow(String label,
                                   double value,
                                   double min,
                                   double max,
                                   java.util.function.Consumer<Double> onCommit) {
        double low = Math.min(min, max);
        double high = Math.max(min, max);
        double clamped = Math.max(low, Math.min(high, value));

        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setAlignmentX(0.0f);

        JLabel key = new JLabel(label);
        key.setForeground(new Color(192, 223, 248));
        row.add(key, BorderLayout.WEST);

        JPanel controls = new JPanel(new BorderLayout(6, 0));
        controls.setOpaque(false);

        JSlider slider = new JSlider(0, 1000, toSliderValue(clamped, low, high));
        slider.setOpaque(false);
        slider.setFocusable(false);

        javax.swing.JTextField field = new javax.swing.JTextField(7);
        UiBuilder.styleInspectorField(field);
        field.setText(engine.formatTransformValue(clamped));

        Runnable commitField = () -> {
            double parsed = engine.parseOrFallback(field.getText(), fromSliderValue(slider.getValue(), low, high));
            double bounded = Math.max(low, Math.min(high, parsed));
            field.setText(engine.formatTransformValue(bounded));
            slider.setValue(toSliderValue(bounded, low, high));
            if (onCommit != null) {
                onCommit.accept(bounded);
            }
        };

        slider.addChangeListener(e -> {
            double bounded = fromSliderValue(slider.getValue(), low, high);
            field.setText(engine.formatTransformValue(bounded));
            if (!slider.getValueIsAdjusting() && onCommit != null) {
                onCommit.accept(bounded);
            }
        });
        field.addActionListener(e -> commitField.run());
        field.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override
            public void focusLost(java.awt.event.FocusEvent e) {
                commitField.run();
            }
        });

        controls.add(slider, BorderLayout.CENTER);
        controls.add(field, BorderLayout.EAST);
        row.add(controls, BorderLayout.CENTER);

        inspectorPanel.add(row);
        inspectorPanel.add(Box.createRigidArea(new Dimension(0, 4)));
    }

    private int toSliderValue(double value, double min, double max) {
        if (Math.abs(max - min) < 1e-9) {
            return 0;
        }
        double t = (value - min) / (max - min);
        return (int) Math.round(Math.max(0.0, Math.min(1.0, t)) * 1000.0);
    }

    private double fromSliderValue(int sliderValue, double min, double max) {
        double t = Math.max(0.0, Math.min(1.0, sliderValue / 1000.0));
        return min + (max - min) * t;
    }

    private void buildRgbInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color", node.getColor("color", new Vec3(0.8, 0.8, 0.8)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
    }

    private void buildValueInspector(MaterialNodeGraph.Node node) {
        double sliderMin = node.getNumber("slider_min", 0.0);
        double sliderMax = node.getNumber("slider_max", 1.0);
        addRangedValueRow("Value", node.getNumber("value", 0.5), sliderMin, sliderMax, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("value", value);
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Range Min", engine.formatTransformValue(sliderMin), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("slider_min", engine.parseOrFallback(text, sliderMin));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Range Max", engine.formatTransformValue(sliderMax), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("slider_max", engine.parseOrFallback(text, sliderMax));
            markStructureChanged();
        });
    }

    private void buildNoiseInspector(MaterialNodeGraph.Node node) {
        engine.addComboRow(inspectorPanel, "Coordinates",
                EngineMaterialDock.enumNames(MaterialNodeGraph.CoordinateSource.values()),
                node.getEnum("coordinate_source", MaterialNodeGraph.CoordinateSource.UV0.name()),
                value -> {
                    EngineMaterialDock.markCustom(material);
                    node.setEnum("coordinate_source", value);
                    markStructureChanged();
                });
        engine.addNumericRow(inspectorPanel, "Scale", engine.formatTransformValue(node.getNumber("scale", 5.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("scale", Math.max(0.0001, engine.parseOrFallback(text, node.getNumber("scale", 5.0))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Detail", engine.formatTransformValue(node.getNumber("detail", 4.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("detail", Math.max(1.0, engine.parseOrFallback(text, node.getNumber("detail", 4.0))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Roughness", engine.formatTransformValue(node.getNumber("roughness", 0.55)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("roughness", EngineMaterialDock.clamp01(engine.parseOrFallback(text, node.getNumber("roughness", 0.55))));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Distortion", engine.formatTransformValue(node.getNumber("distortion", 0.15)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("distortion", engine.parseOrFallback(text, node.getNumber("distortion", 0.15)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Seed", engine.formatTransformValue(node.getNumber("seed", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("seed", Math.round(engine.parseOrFallback(text, node.getNumber("seed", 1.0))));
            markStructureChanged();
        });
        MaterialGraphEvaluator.Result preview = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );
        inspectorPanel.add(EngineMaterialDock.infoLabel("Preview base " + EngineMaterialDock.formatShort(preview.baseColor.x) + ", " + EngineMaterialDock.formatShort(preview.baseColor.y) + ", " + EngineMaterialDock.formatShort(preview.baseColor.z)));
    }

    private void buildColorRampInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color A", node.getColor("color_a", new Vec3(0.08, 0.08, 0.08)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color_a", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        engine.addColorPickerRow(inspectorPanel, "Color B", node.getColor("color_b", new Vec3(0.95, 0.95, 0.95)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color_b", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Pos A", node.getNumber("position_a", 0.18), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("position_a", value);
            markStructureChanged();
        });
        addRangedValueRow("Pos B", node.getNumber("position_b", 0.82), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("position_b", value);
            markStructureChanged();
        });
    }

    private void buildMixInspector(MaterialNodeGraph.Node node) {
        engine.addColorPickerRow(inspectorPanel, "Color A", node.getColor("color_a", new Vec3(0.2, 0.2, 0.2)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color_a", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        engine.addColorPickerRow(inspectorPanel, "Color B", node.getColor("color_b", new Vec3(0.85, 0.85, 0.85)), color -> {
            EngineMaterialDock.markCustom(material);
            node.setColor("color_b", EngineMaterialDock.clampColor(color));
            markStructureChanged();
        });
        addRangedValueRow("Factor", node.getNumber("factor", 0.5), 0.0, 1.0, value -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("factor", value);
            markStructureChanged();
        });
        engine.addComboRow(inspectorPanel, "Blend",
                EngineMaterialDock.enumNames(MaterialNodeGraph.BlendMode.values()),
                node.getEnum("blend_mode", MaterialNodeGraph.BlendMode.MIX.name()),
                value -> {
                    EngineMaterialDock.markCustom(material);
                    node.setEnum("blend_mode", value);
                    markStructureChanged();
                });
    }

    private void buildMathInspector(MaterialNodeGraph.Node node) {
        engine.addNumericRow(inspectorPanel, "A", engine.formatTransformValue(node.getNumber("a", 0.5)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("a", engine.parseOrFallback(text, node.getNumber("a", 0.5)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "B", engine.formatTransformValue(node.getNumber("b", 0.5)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("b", engine.parseOrFallback(text, node.getNumber("b", 0.5)));
            markStructureChanged();
        });
        engine.addComboRow(inspectorPanel, "Operation",
                EngineMaterialDock.enumNames(MaterialNodeGraph.MathOperation.values()),
                node.getEnum("operation", MaterialNodeGraph.MathOperation.MULTIPLY.name()),
                value -> {
                    EngineMaterialDock.markCustom(material);
                node.setEnum("operation", value);
                markStructureChanged();
                });
    }

    private void buildClampInspector(MaterialNodeGraph.Node node) {
        engine.addNumericRow(inspectorPanel, "Value", engine.formatTransformValue(node.getNumber("value", 0.5)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("value", engine.parseOrFallback(text, node.getNumber("value", 0.5)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Min", engine.formatTransformValue(node.getNumber("min", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("min", engine.parseOrFallback(text, node.getNumber("min", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "Max", engine.formatTransformValue(node.getNumber("max", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("max", engine.parseOrFallback(text, node.getNumber("max", 1.0)));
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }

    private void buildMapRangeInspector(MaterialNodeGraph.Node node) {
        engine.addNumericRow(inspectorPanel, "Value", engine.formatTransformValue(node.getNumber("value", 0.5)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("value", engine.parseOrFallback(text, node.getNumber("value", 0.5)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "From Min", engine.formatTransformValue(node.getNumber("from_min", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("from_min", engine.parseOrFallback(text, node.getNumber("from_min", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "From Max", engine.formatTransformValue(node.getNumber("from_max", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("from_max", engine.parseOrFallback(text, node.getNumber("from_max", 1.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "To Min", engine.formatTransformValue(node.getNumber("to_min", 0.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("to_min", engine.parseOrFallback(text, node.getNumber("to_min", 0.0)));
            markStructureChanged();
        });
        engine.addNumericRow(inspectorPanel, "To Max", engine.formatTransformValue(node.getNumber("to_max", 1.0)), text -> {
            EngineMaterialDock.markCustom(material);
            node.setNumber("to_max", engine.parseOrFallback(text, node.getNumber("to_max", 1.0)));
            markStructureChanged();
        });
        appendLinkedInputSummary(node);
    }
}

final class MaterialDockPreviewPanel extends JComponent {
    private final MaterialDockSession session;
    private volatile BufferedImage previewImage;
    private volatile long renderedSignature = Long.MIN_VALUE;
    private volatile long scheduledSignature = Long.MIN_VALUE;
    private volatile boolean rendering;

        MaterialDockPreviewPanel(MaterialDockSession session) {
        this.session = session;
        setOpaque(true);
        setBackground(UiTheme.PANEL_INSET);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(8, 8, 8, 8)
        ));
    }

    void requestRefresh() {
        long signature = MaterialPreviewRenderer.signature(session.material, session.previewSettings);
        if (signature == renderedSignature || signature == scheduledSignature) {
            repaint();
            return;
        }
        scheduledSignature = signature;
        rendering = true;
        int width = Math.max(128, getWidth() > 0 ? getWidth() - 16 : 220);
        int height = Math.max(96, getHeight() > 0 ? getHeight() - 16 : 168);
        EngineMaterialDock.PREVIEW_EXECUTOR.submit(() -> {
            BufferedImage rendered = MaterialPreviewRenderer.render(session.material, session.previewSettings.copy(), width, height);
            SwingUtilities.invokeLater(() -> {
                if (scheduledSignature != signature) {
                    return;
                }
                previewImage = rendered;
                renderedSignature = signature;
                rendering = false;
                repaint();
            });
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setColor(UiTheme.PANEL_INSET);
        g2.fillRect(0, 0, getWidth(), getHeight());

        int inset = 8;
        int drawX = inset;
        int drawY = inset;
        int drawW = Math.max(1, getWidth() - inset * 2);
        int drawH = Math.max(1, getHeight() - inset * 2);
        if (previewImage != null) {
            g2.drawImage(previewImage, drawX, drawY, drawW, drawH, null);
        } else {
            g2.setColor(UiTheme.PANEL_BG);
            g2.fillRoundRect(drawX, drawY, drawW, drawH, 16, 16);
            g2.setColor(UiTheme.TEXT_MUTED);
            g2.drawString(UiStrings.MaterialDock.PREVIEW_TITLE, drawX + 12, drawY + 22);
        }
        if (rendering) {
            g2.setColor(new Color(0, 0, 0, 110));
            g2.fillRoundRect(drawX + 8, drawY + 8, 120, 24, 12, 12);
            g2.setColor(UiTheme.TEXT_PRIMARY);
            g2.drawString(UiStrings.MaterialDock.PREVIEW_REFRESHING, drawX + 18, drawY + 24);
        }
        g2.dispose();
    }
}

final class MaterialGraphCanvas extends JComponent {
    private static final int HEADER_HEIGHT = 28;
    private static final int ROW_HEIGHT = 22;
    private static final int SOCKET_RADIUS = 6;
    private static final int MIN_ROWS = 3;
    private static final Stroke LINK_STROKE = new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

    private final MaterialDockSession session;
    private Point dragAnchorScreen;
    private double dragAnchorNodeX;
    private double dragAnchorNodeY;
    private int draggingNodeId = -1;
    private boolean nodeMovedDuringDrag;
    private boolean panning;
    private PendingLink pendingLink;
    private boolean linkStructureChanged;

        MaterialGraphCanvas(MaterialDockSession session) {
        this.session = session;
        setOpaque(true);
        setBackground(new Color(11, 15, 20));
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        EditorFocusContext.mark(this, EditorFocusContext.MATERIAL_GRAPH);
        installKeyBindings();
        MouseAdapter mouseHandler = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                if (maybeShowPopup(e)) {
                    return;
                }
                GraphHit hit = hitTest(e.getPoint());
                if (SwingUtilities.isMiddleMouseButton(e)
                        || (SwingUtilities.isLeftMouseButton(e) && e.isShiftDown() && hit.node == null)) {
                    panning = true;
                    dragAnchorScreen = e.getPoint();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && hit.socket != null && hit.socket.input) {
                    MaterialNodeGraph.Link existing = session.graph.findInputLink(hit.node.getId(), hit.socket.definition.key());
                    if (existing != null) {
                        MaterialNodeGraph.Node sourceNode = session.graph.getNodeById(existing.getFromNodeId());
                        MaterialNodeGraph.SocketDefinition sourceSocket = sourceNode == null ? null : sourceNode.getType().output(existing.getFromSocket());
                        if (sourceSocket != null) {
                            session.noteMaterialHistoryLabel("Úprava spojení uzlů");
                            session.graph.disconnectInput(hit.node.getId(), hit.socket.definition.key());
                            session.selectNode(hit.node);
                            pendingLink = new PendingLink(
                                    existing.getFromNodeId(),
                                    existing.getFromSocket(),
                                    sourceSocket.valueType(),
                                    e.getPoint(),
                                    hit.node.getId(),
                                    hit.socket.definition.key()
                            );
                            linkStructureChanged = true;
                            EngineMaterialDock.markCustom(session.material);
                            session.markVisualChanged();
                            session.refreshInspector();
                            return;
                        }
                    }
                }
                if (SwingUtilities.isLeftMouseButton(e) && hit.socket != null && !hit.socket.input) {
                    session.selectNode(hit.node);
                    session.graph.bringNodeToFront(hit.node.getId());
                    pendingLink = new PendingLink(
                            hit.node.getId(),
                            hit.socket.definition.key(),
                            hit.socket.definition.valueType(),
                            e.getPoint(),
                            -1,
                            null
                    );
                    repaint();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e) && hit.node != null) {
                    session.selectNode(hit.node);
                    session.graph.bringNodeToFront(hit.node.getId());
                    draggingNodeId = hit.node.getId();
                    dragAnchorScreen = e.getPoint();
                    dragAnchorNodeX = hit.node.getX();
                    dragAnchorNodeY = hit.node.getY();
                    nodeMovedDuringDrag = false;
                    repaint();
                    return;
                }
                if (SwingUtilities.isLeftMouseButton(e)) {
                    session.selectNode(null);
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panning && dragAnchorScreen != null) {
                    session.graph.setViewOffsetX(session.graph.getViewOffsetX() + e.getX() - dragAnchorScreen.x);
                    session.graph.setViewOffsetY(session.graph.getViewOffsetY() + e.getY() - dragAnchorScreen.y);
                    dragAnchorScreen = e.getPoint();
                    session.markVisualChanged();
                    return;
                }
                if (draggingNodeId >= 0 && dragAnchorScreen != null) {
                    MaterialNodeGraph.Node node = session.graph.getNodeById(draggingNodeId);
                    if (node != null) {
                        double zoom = session.graph.getZoom();
                        double x = dragAnchorNodeX + (e.getX() - dragAnchorScreen.x) / zoom;
                        double y = dragAnchorNodeY + (e.getY() - dragAnchorScreen.y) / zoom;
                        if (!e.isAltDown()) {
                            x = snapToGrid(x);
                            y = snapToGrid(y);
                        }
                        if (Math.abs(node.getX() - x) > 1e-6 || Math.abs(node.getY() - y) > 1e-6) {
                            node.setX(x);
                            node.setY(y);
                            nodeMovedDuringDrag = true;
                            session.markVisualChanged();
                        }
                    }
                    return;
                }
                if (pendingLink != null) {
                    pendingLink.mousePoint = e.getPoint();
                    repaint();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (maybeShowPopup(e)) {
                    clearTransientState();
                    return;
                }
                if (pendingLink != null) {
                    GraphHit hit = hitTest(e.getPoint());
                    if (hit.socket != null && hit.socket.input) {
                        if (session.graph.connect(pendingLink.fromNodeId, pendingLink.fromSocket, hit.node.getId(), hit.socket.definition.key())) {
                            session.noteMaterialHistoryLabel("Propojení uzlů");
                            EngineMaterialDock.markCustom(session.material);
                            session.markStructureChanged();
                            linkStructureChanged = false;
                        }
                    }
                }
                clearTransientState();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                double oldZoom = session.graph.getZoom();
                double factor = e.getWheelRotation() < 0 ? 1.1 : 0.9;
                double newZoom = Math.max(0.45, Math.min(1.85, oldZoom * factor));
                if (Math.abs(newZoom - oldZoom) < 1e-6) {
                    return;
                }
                double graphX = (e.getX() - session.graph.getViewOffsetX()) / oldZoom;
                double graphY = (e.getY() - session.graph.getViewOffsetY()) / oldZoom;
                session.graph.setZoom(newZoom);
                session.graph.setViewOffsetX(e.getX() - graphX * newZoom);
                session.graph.setViewOffsetY(e.getY() - graphY * newZoom);
                session.markVisualChanged();
            }

            private boolean maybeShowPopup(MouseEvent e) {
                if (e == null || !e.isPopupTrigger()) {
                    return false;
                }
                showPopup(e.getPoint());
                e.consume();
                return true;
            }
        };
        addMouseListener(mouseHandler);
        addMouseMotionListener(mouseHandler);
        addMouseWheelListener(mouseHandler);
    }

    private void installKeyBindings() {
        ActionMap actionMap = getActionMap();
        actionMap.put(EditorActionId.DELETE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                deleteSelectedNode();
            }
        });
        actionMap.put(EditorActionId.DUPLICATE, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                duplicateSelectedNode();
            }
        });
        actionMap.put(EditorActionId.FRAME_ALL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frameAllNodes();
            }
        });
        actionMap.put(EditorActionId.FRAME_SELECTED, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                frameSelectedNode();
            }
        });
        actionMap.put(EditorActionId.CANCEL, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                clearTransientState();
            }
        });
    }

    private void deleteSelectedNode() {
        MaterialNodeGraph.Node node = session.graph.getNodeById(session.graph.getSelectedNodeId());
        if (node != null && node.getType().isDeletable() && session.graph.removeNode(node.getId())) {
            session.noteMaterialHistoryLabel("Smazání uzlu");
            EngineMaterialDock.markCustom(session.material);
            session.markStructureChanged();
        }
    }

    private void duplicateSelectedNode() {
        MaterialNodeGraph.Node node = session.graph.getNodeById(session.graph.getSelectedNodeId());
        if (node == null) {
            return;
        }
        MaterialNodeGraph.Node copy = session.graph.duplicateNode(node.getId(), 28.0, 28.0);
        if (copy != null) {
            session.noteMaterialHistoryLabel("Duplikace uzlu");
            EngineMaterialDock.markCustom(session.material);
            session.selectNode(copy);
            session.markStructureChanged();
        }
    }

    private void frameSelectedNode() {
        MaterialNodeGraph.Node node = session.graph.getNodeById(session.graph.getSelectedNodeId());
        if (node == null) {
            frameAllNodes();
            return;
        }
        NodeLayout layout = nodeLayout(node);
        session.graph.setViewOffsetX(getWidth() * 0.5 - layout.bounds.width * 0.5 - node.getX() * session.graph.getZoom());
        session.graph.setViewOffsetY(getHeight() * 0.5 - layout.bounds.height * 0.5 - node.getY() * session.graph.getZoom());
        session.markVisualChanged();
    }

    private void frameAllNodes() {
        List<MaterialNodeGraph.Node> nodes = session.graph.getNodes();
        if (nodes.isEmpty()) {
            session.graph.setZoom(1.0);
            session.graph.setViewOffsetX(72.0);
            session.graph.setViewOffsetY(52.0);
            session.markVisualChanged();
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (MaterialNodeGraph.Node node : nodes) {
            int width = baseNodeWidth(node);
            int rows = Math.max(MIN_ROWS, Math.max(node.getType().inputs().length, node.getType().outputs().length));
            int height = HEADER_HEIGHT + 18 + rows * ROW_HEIGHT + 10;
            minX = Math.min(minX, node.getX());
            minY = Math.min(minY, node.getY());
            maxX = Math.max(maxX, node.getX() + width);
            maxY = Math.max(maxY, node.getY() + height);
        }
        double graphWidth = Math.max(240.0, maxX - minX);
        double graphHeight = Math.max(180.0, maxY - minY);
        double zoomX = (getWidth() - 80.0) / graphWidth;
        double zoomY = (getHeight() - 80.0) / graphHeight;
        double zoom = Math.max(0.45, Math.min(1.45, Math.min(zoomX, zoomY)));
        session.graph.setZoom(zoom);
        session.graph.setViewOffsetX((getWidth() - graphWidth * zoom) * 0.5 - minX * zoom);
        session.graph.setViewOffsetY((getHeight() - graphHeight * zoom) * 0.5 - minY * zoom);
        session.markVisualChanged();
    }

    private double snapToGrid(double value) {
        return Math.round(value / 12.0) * 12.0;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        drawGrid(g2);
        drawLinks(g2);
        drawNodes(g2);
        drawPendingLink(g2);
        g2.dispose();
    }

    private void drawGrid(Graphics2D g2) {
        int w = getWidth();
        int h = getHeight();
        g2.setColor(new Color(12, 17, 23));
        g2.fillRect(0, 0, w, h);
        double zoom = session.graph.getZoom();
        double spacing = 28.0 * zoom;
        double offsetX = session.graph.getViewOffsetX() % spacing;
        double offsetY = session.graph.getViewOffsetY() % spacing;
        g2.setColor(new Color(22, 29, 36));
        for (double x = offsetX; x < w; x += spacing) {
            g2.drawLine((int) Math.round(x), 0, (int) Math.round(x), h);
        }
        for (double y = offsetY; y < h; y += spacing) {
            g2.drawLine(0, (int) Math.round(y), w, (int) Math.round(y));
        }
    }

    private void drawLinks(Graphics2D g2) {
        g2.setStroke(LINK_STROKE);
        for (MaterialNodeGraph.Link link : session.graph.getLinks()) {
            MaterialNodeGraph.Node fromNode = session.graph.getNodeById(link.getFromNodeId());
            MaterialNodeGraph.Node toNode = session.graph.getNodeById(link.getToNodeId());
            if (fromNode == null || toNode == null) {
                continue;
            }
            SocketLayout fromSocket = socketLayout(fromNode, false, link.getFromSocket());
            SocketLayout toSocket = socketLayout(toNode, true, link.getToSocket());
            if (fromSocket == null || toSocket == null) {
                continue;
            }
            drawLink(g2, fromSocket.center, toSocket.center, socketColor(fromSocket.definition.valueType()));
        }
    }

    private void drawNodes(Graphics2D g2) {
        for (MaterialNodeGraph.Node node : session.graph.getNodes()) {
            NodeLayout layout = nodeLayout(node);
            int x = layout.bounds.x;
            int y = layout.bounds.y;
            int w = layout.bounds.width;
            int h = layout.bounds.height;
            Color accent = new Color(node.getType().accentRgb());
            boolean selected = node.getId() == session.graph.getSelectedNodeId();

            if (selected) {
                g2.setColor(new Color(
                        UiTheme.ACCENT_PURPLE.getRed(),
                        UiTheme.ACCENT_PURPLE.getGreen(),
                        UiTheme.ACCENT_PURPLE.getBlue(),
                        52
                ));
                g2.fillRoundRect(x - 3, y - 3, w + 6, h + 6, 18, 18);
            }
            g2.setColor(new Color(24, 31, 39));
            g2.fillRoundRect(x, y, w, h, 16, 16);
            g2.setColor(accent);
            g2.fillRoundRect(x, y, w, scale(HEADER_HEIGHT), 16, 16);
            g2.fillRect(x, y + scale(HEADER_HEIGHT / 2), w, scale(HEADER_HEIGHT / 2));
            g2.setColor(selected ? UiTheme.ACCENT_PURPLE : new Color(63, 82, 102));
            g2.drawRoundRect(x, y, w, h, 16, 16);

            String title = node.getType() == MaterialNodeGraph.NodeType.IMAGE_TEXTURE
                    ? node.getText("label", node.getType().title())
                    : node.getType().title();
            g2.setColor(new Color(245, 249, 255));
            g2.drawString(title, x + scale(12), y + scale(18));
            g2.setColor(new Color(228, 235, 242));
            g2.drawString(node.getType().category(), x + scale(12), y + scale(30));

            int rows = Math.max(MIN_ROWS, Math.max(node.getType().inputs().length, node.getType().outputs().length));
            for (int i = 0; i < rows; i++) {
                int baseline = y + scale(HEADER_HEIGHT + 20 + i * ROW_HEIGHT);
                MaterialNodeGraph.SocketDefinition input = i < node.getType().inputs().length ? node.getType().inputs()[i] : null;
                MaterialNodeGraph.SocketDefinition output = i < node.getType().outputs().length ? node.getType().outputs()[i] : null;
                if (input != null) {
                    SocketLayout in = socketLayout(node, true, input.key());
                    boolean linkedInput = session.graph.findInputLink(node.getId(), input.key()) != null;
                    drawSocket(g2, in.center, socketColor(input.valueType()),
                            linkedInput,
                            pendingLink != null && MaterialNodeGraph.canConnect(pendingLink.valueType, input.valueType()));
                    g2.setColor(new Color(202, 216, 231));
                    g2.drawString(trimText(g2, input.label(), w / 2 - scale(26)), x + scale(18), baseline);
                    if (!linkedInput) {
                        drawInlineDefaultHint(g2, node, input, x + scale(18), baseline + scale(4), w / 2 - scale(40));
                    }
                }
                if (output != null) {
                    SocketLayout out = socketLayout(node, false, output.key());
                    drawSocket(g2, out.center, socketColor(output.valueType()), hasOutputConnection(node, output.key()), false);
                    String text = trimText(g2, output.label(), w / 2 - scale(26));
                    FontMetrics metrics = g2.getFontMetrics();
                    g2.setColor(new Color(202, 216, 231));
                    g2.drawString(text, x + w - scale(18) - metrics.stringWidth(text), baseline);
                }
            }
        }
    }

    private void drawPendingLink(Graphics2D g2) {
        if (pendingLink == null) {
            return;
        }
        MaterialNodeGraph.Node fromNode = session.graph.getNodeById(pendingLink.fromNodeId);
        SocketLayout fromSocket = fromNode == null ? null : socketLayout(fromNode, false, pendingLink.fromSocket);
        if (fromSocket == null) {
            return;
        }
        drawLink(g2, fromSocket.center, pendingLink.mousePoint, socketColor(pendingLink.valueType));
    }

    private void drawSocket(Graphics2D g2, Point center, Color color, boolean linked, boolean compatibleTarget) {
        int radius = linked ? scale(SOCKET_RADIUS) : scale(SOCKET_RADIUS - 1);
        if (compatibleTarget) {
            g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
            int glow = radius + scale(6);
            g2.fillOval(center.x - glow, center.y - glow, glow * 2, glow * 2);
        }
        g2.setColor(new Color(13, 18, 24));
        g2.fillOval(center.x - radius - 2, center.y - radius - 2, (radius + 2) * 2, (radius + 2) * 2);
        g2.setColor(color);
        g2.fillOval(center.x - radius, center.y - radius, radius * 2, radius * 2);
        g2.setColor(linked ? Color.WHITE : new Color(34, 45, 58));
        g2.drawOval(center.x - radius, center.y - radius, radius * 2, radius * 2);
    }

    private void drawLink(Graphics2D g2, Point from, Point to, Color color) {
        int handle = Math.max(scale(48), Math.abs(to.x - from.x) / 2);
        Path2D path = new Path2D.Double();
        path.moveTo(from.x, from.y);
        path.curveTo(from.x + handle, from.y, to.x - handle, to.y, to.x, to.y);
        g2.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 68));
        g2.draw(path);
        g2.setColor(color);
        g2.fillOval(from.x - 4, from.y - 4, 8, 8);
        g2.fillOval(to.x - 4, to.y - 4, 8, 8);
    }

    private void drawInlineDefaultHint(Graphics2D g2,
                                       MaterialNodeGraph.Node node,
                                       MaterialNodeGraph.SocketDefinition input,
                                       int x,
                                       int y,
                                       int maxWidth) {
        if (node == null || input == null || maxWidth < scale(18)) {
            return;
        }
        switch (input.valueType()) {
            case COLOR -> {
                Vec3 color = node.getColor(input.key(), null);
                if (color == null) {
                    return;
                }
                g2.setColor(new Color(
                        (int) Math.round(EngineMaterialDock.clamp01(color.x) * 255.0),
                        (int) Math.round(EngineMaterialDock.clamp01(color.y) * 255.0),
                        (int) Math.round(EngineMaterialDock.clamp01(color.z) * 255.0)
                ));
                g2.fillRoundRect(x, y, scale(20), scale(8), 6, 6);
                g2.setColor(new Color(255, 255, 255, 90));
                g2.drawRoundRect(x, y, scale(20), scale(8), 6, 6);
            }
            case VALUE -> {
                String text = EngineMaterialDock.formatShort(node.getNumber(input.key(), 0.0));
                g2.setColor(new Color(255, 255, 255, 80));
                g2.drawString(trimText(g2, text, maxWidth), x, y + scale(8));
            }
            case VECTOR -> {
                g2.setColor(new Color(184, 169, 255));
                g2.drawString("vektor", x, y + scale(8));
            }
            default -> {
            }
        }
    }

    private boolean hasOutputConnection(MaterialNodeGraph.Node node, String socketKey) {
        for (MaterialNodeGraph.Link link : session.graph.getLinks()) {
            if (link.getFromNodeId() == node.getId() && link.getFromSocket().equalsIgnoreCase(socketKey)) {
                return true;
            }
        }
        return false;
    }

    private void showPopup(Point point) {
        GraphHit hit = hitTest(point);
        double graphX = (point.x - session.graph.getViewOffsetX()) / session.graph.getZoom();
        double graphY = (point.y - session.graph.getViewOffsetY()) / session.graph.getZoom();
        JPopupMenu popup = new JPopupMenu();

        if (hit.node != null) {
            session.selectNode(hit.node);
        }
        if (hit.socket != null && hit.socket.input) {
            JMenuItem disconnectInput = new JMenuItem("Odpojit " + hit.socket.definition.label());
            disconnectInput.setEnabled(session.graph.findInputLink(hit.node.getId(), hit.socket.definition.key()) != null);
            disconnectInput.addActionListener(e -> {
                if (session.graph.disconnectInput(hit.node.getId(), hit.socket.definition.key())) {
                    session.noteMaterialHistoryLabel("Odpojení vstupu uzlu");
                    EngineMaterialDock.markCustom(session.material);
                    session.markStructureChanged();
                }
            });
            popup.add(disconnectInput);
        }
        if (hit.node != null) {
            JMenuItem disconnectInputs = new JMenuItem(UiStrings.MaterialDock.DISCONNECT_INPUTS);
            disconnectInputs.addActionListener(e -> {
                boolean changed = false;
                for (MaterialNodeGraph.SocketDefinition input : hit.node.getType().inputs()) {
                    changed |= session.graph.disconnectInput(hit.node.getId(), input.key());
                }
                if (changed) {
                    session.noteMaterialHistoryLabel("Odpojení vstupů uzlu");
                    EngineMaterialDock.markCustom(session.material);
                    session.markStructureChanged();
                }
            });
            popup.add(disconnectInputs);

            JMenuItem disconnectOutputs = new JMenuItem(UiStrings.MaterialDock.DISCONNECT_OUTPUTS);
            disconnectOutputs.addActionListener(e -> {
                boolean changed = false;
                for (MaterialNodeGraph.SocketDefinition output : hit.node.getType().outputs()) {
                    changed |= session.graph.disconnectOutput(hit.node.getId(), output.key());
                }
                if (changed) {
                    session.noteMaterialHistoryLabel("Odpojení výstupů uzlu");
                    EngineMaterialDock.markCustom(session.material);
                    session.markStructureChanged();
                }
            });
            popup.add(disconnectOutputs);

            JMenuItem delete = new JMenuItem(UiStrings.MaterialDock.DELETE_NODE);
            delete.setEnabled(hit.node.getType().isDeletable());
            delete.addActionListener(e -> {
                if (session.graph.removeNode(hit.node.getId())) {
                    session.noteMaterialHistoryLabel("Smazání uzlu");
                    EngineMaterialDock.markCustom(session.material);
                    session.markStructureChanged();
                }
            });
            JMenuItem duplicate = new JMenuItem(UiStrings.MaterialDock.DUPLICATE_NODE);
            duplicate.setEnabled(hit.node.getType().isDeletable());
            duplicate.addActionListener(e -> {
                MaterialNodeGraph.Node copy = session.graph.duplicateNode(hit.node.getId(), 28.0, 28.0);
                if (copy != null) {
                    session.noteMaterialHistoryLabel("Duplikace uzlu");
                    EngineMaterialDock.markCustom(session.material);
                    session.selectNode(copy);
                    session.markStructureChanged();
                }
            });
            popup.add(duplicate);
            popup.add(delete);
            popup.addSeparator();
        }

        JMenu addMenu = new JMenu("Přidat uzel");
        LinkedHashMap<String, JMenu> byCategory = new LinkedHashMap<>();
        for (MaterialNodeGraph.NodeType type : MaterialNodeGraph.addableNodeTypes()) {
            JMenu category = byCategory.computeIfAbsent(type.category(), label -> {
                JMenu menu = new JMenu(label);
                addMenu.add(menu);
                return menu;
            });
            JMenuItem item = new JMenuItem(type.title());
            item.addActionListener(e -> {
                MaterialNodeGraph.Node created = session.graph.addNode(type, graphX, graphY);
                session.noteMaterialHistoryLabel("Přidání uzlu");
                EngineMaterialDock.markCustom(session.material);
                session.selectNode(created);
                session.markStructureChanged();
            });
            category.add(item);
        }
        popup.add(addMenu);

        JMenuItem reset = new JMenuItem(UiStrings.MaterialDock.RESET_DEFAULT_GRAPH);
        reset.addActionListener(e -> {
            session.noteMaterialHistoryLabel("Reset grafu materiálu");
            session.graph.clearAndReset();
            MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(session.material);
            EngineMaterialDock.markCustom(session.material);
            session.markStructureChanged();
        });
        popup.add(reset);
        popup.show(this, point.x, point.y);
    }

    private GraphHit hitTest(Point point) {
        List<MaterialNodeGraph.Node> nodes = session.graph.getNodes();
        for (int i = nodes.size() - 1; i >= 0; i--) {
            MaterialNodeGraph.Node node = nodes.get(i);
            NodeLayout layout = nodeLayout(node);
            if (!layout.bounds.contains(point)) {
                continue;
            }
            for (MaterialNodeGraph.SocketDefinition input : node.getType().inputs()) {
                SocketLayout socket = socketLayout(node, true, input.key());
                if (socket != null && socket.hitBounds.contains(point)) {
                    return new GraphHit(node, socket);
                }
            }
            for (MaterialNodeGraph.SocketDefinition output : node.getType().outputs()) {
                SocketLayout socket = socketLayout(node, false, output.key());
                if (socket != null && socket.hitBounds.contains(point)) {
                    return new GraphHit(node, socket);
                }
            }
            return new GraphHit(node, null);
        }
        return new GraphHit(null, null);
    }

    private NodeLayout nodeLayout(MaterialNodeGraph.Node node) {
        int baseWidth = baseNodeWidth(node);
        int rows = Math.max(MIN_ROWS, Math.max(node.getType().inputs().length, node.getType().outputs().length));
        int baseHeight = HEADER_HEIGHT + 18 + rows * ROW_HEIGHT + 10;
        int x = (int) Math.round(session.graph.getViewOffsetX() + node.getX() * session.graph.getZoom());
        int y = (int) Math.round(session.graph.getViewOffsetY() + node.getY() * session.graph.getZoom());
        int width = scale(baseWidth);
        int height = scale(baseHeight);
        return new NodeLayout(new Rectangle(x, y, width, height));
    }

    private int baseNodeWidth(MaterialNodeGraph.Node node) {
        return switch (node.getType()) {
            case PRINCIPLED_BSDF -> 286;
            case OUTPUT_MATERIAL -> 242;
            case VOLUME_MEDIUM -> 238;
            case MAPPING -> 244;
            case IMAGE_TEXTURE -> 252;
            case NORMAL_MAP -> 236;
            case TEXTURE_COORDINATE -> 228;
            case TRANSPARENT_BSDF -> 232;
            case COMBINE_RGB, SEPARATE_RGB -> 216;
            case MIX_COLOR, CLAMP, MAP_RANGE -> 224;
            case COLOR_RAMP, NOISE_TEXTURE -> 228;
            case MIX_SHADER, GLASS_BSDF -> 236;
            case EMISSION_SHADER -> 222;
            default -> 208;
        };
    }

    private SocketLayout socketLayout(MaterialNodeGraph.Node node, boolean input, String socketKey) {
        MaterialNodeGraph.SocketDefinition[] sockets = input ? node.getType().inputs() : node.getType().outputs();
        for (int i = 0; i < sockets.length; i++) {
            MaterialNodeGraph.SocketDefinition definition = sockets[i];
            if (!definition.key().equalsIgnoreCase(socketKey)) {
                continue;
            }
            NodeLayout nodeLayout = nodeLayout(node);
            int x = input ? nodeLayout.bounds.x + scale(10) : nodeLayout.bounds.x + nodeLayout.bounds.width - scale(10);
            int y = nodeLayout.bounds.y + scale(HEADER_HEIGHT + 14 + i * ROW_HEIGHT);
            Point center = new Point(x, y);
            Rectangle hitBounds = new Rectangle(center.x - scale(10), center.y - scale(10), scale(20), scale(20));
            return new SocketLayout(definition, input, center, hitBounds);
        }
        return null;
    }

    private Color socketColor(MaterialNodeGraph.ValueType valueType) {
        return switch (valueType) {
            case COLOR -> new Color(226, 104, 84);
            case VALUE -> new Color(116, 209, 114);
            case VECTOR -> new Color(116, 68, 255);
            case SURFACE -> new Color(234, 191, 91);
            case VOLUME -> new Color(94, 204, 170);
        };
    }

    private String trimText(Graphics2D g2, String text, int maxWidth) {
        if (text == null) {
            return "";
        }
        FontMetrics metrics = g2.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String out = text;
        while (out.length() > 3 && metrics.stringWidth(out + "...") > maxWidth) {
            out = out.substring(0, out.length() - 1);
        }
        return out + "...";
    }

    private int scale(int value) {
        return (int) Math.round(value * session.graph.getZoom());
    }

    private void clearTransientState() {
        if (linkStructureChanged && pendingLink != null && pendingLink.hasRestoreTarget()) {
            session.graph.connect(
                    pendingLink.fromNodeId,
                    pendingLink.fromSocket,
                    pendingLink.restoreToNodeId,
                    pendingLink.restoreToSocket
            );
            linkStructureChanged = false;
            session.refreshInspector();
            session.markVisualChanged();
        }
        if (nodeMovedDuringDrag) {
            session.noteMaterialHistoryLabel("Přesun uzlu");
            session.markStructureChanged();
        } else if (linkStructureChanged) {
            session.markStructureChanged();
        }
        dragAnchorScreen = null;
        draggingNodeId = -1;
        nodeMovedDuringDrag = false;
        panning = false;
        pendingLink = null;
        linkStructureChanged = false;
        repaint();
    }
}

final class NodeLayout {
    final Rectangle bounds;

    NodeLayout(Rectangle bounds) {
        this.bounds = bounds;
    }
}

final class SocketLayout {
    final MaterialNodeGraph.SocketDefinition definition;
    final boolean input;
    final Point center;
    final Rectangle hitBounds;

    SocketLayout(MaterialNodeGraph.SocketDefinition definition,
                 boolean input,
                 Point center,
                 Rectangle hitBounds) {
        this.definition = definition;
        this.input = input;
        this.center = center;
        this.hitBounds = hitBounds;
    }
}

final class GraphHit {
    final MaterialNodeGraph.Node node;
    final SocketLayout socket;

    GraphHit(MaterialNodeGraph.Node node, SocketLayout socket) {
        this.node = node;
        this.socket = socket;
    }
}

final class PendingLink {
    final int fromNodeId;
    final String fromSocket;
    final MaterialNodeGraph.ValueType valueType;
    final int restoreToNodeId;
    final String restoreToSocket;
    Point mousePoint;

    PendingLink(int fromNodeId,
                String fromSocket,
                MaterialNodeGraph.ValueType valueType,
                Point mousePoint,
                int restoreToNodeId,
                String restoreToSocket) {
        this.fromNodeId = fromNodeId;
        this.fromSocket = fromSocket;
        this.valueType = valueType;
        this.mousePoint = mousePoint;
        this.restoreToNodeId = restoreToNodeId;
        this.restoreToSocket = restoreToSocket;
    }

    boolean hasRestoreTarget() {
        return restoreToNodeId >= 0 && restoreToSocket != null && !restoreToSocket.isBlank();
    }
}

