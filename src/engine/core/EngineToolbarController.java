package engine.core;

import engine.render.post.DitherRenderer;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

final class EngineToolbarController {
    private static final String TOOLBAR_ROLE = "engine.toolbar.role";
    private static final String ROLE_GROUP = "group";
    private static final String ROLE_PRIMARY_ROW = "primaryRow";
    private static final String ROLE_BADGE = "badge";
    private static final double PRIMARY_NAVIGATION_WEIGHT = 1.2;
    private static final double PRIMARY_SYSTEM_WEIGHT = 1.0;
    private static final int TOOLBAR_ROW_SPACING = 6;

    private EngineToolbarController() {
    }

    static void setupToolbar(Engine engine) {
        JPanel bar = prepareToolbarPanel(engine);
        JPanel primaryRow = buildPrimaryRow(
                createNavigationGroup(engine),
                createSystemGroup(engine),
                createHelpGroup(engine));
        JPanel displayRow = buildDisplayRow(createDisplayGroup(engine));

        bar.add(primaryRow);
        bar.add(Box.createRigidArea(new Dimension(0, TOOLBAR_ROW_SPACING)));
        bar.add(displayRow);

        finalizeToolbarSetup(engine, bar);
    }

    static void refreshUiIndicators(Engine engine) {
        DitherRenderer.DitherStyle ditherStyle = currentDitherStyle(engine);
        refreshDynamicToggleLabels(engine);
        refreshNavigationStates(engine);
        refreshDisplayStates(engine, ditherStyle);
        refreshSystemStates(engine);
        applyResponsiveToolbarLayout(engine);
    }

    private static JPanel prepareToolbarPanel(Engine engine) {
        JPanel bar = engine.window.getToolbarPanel();
        bar.removeAll();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        UiTheme.styleToolbarPanel(bar);
        return bar;
    }

    private static JPanel createNavigationGroup(Engine engine) {
        engine.lightMouse = toolbarToggle(
                engine,
                UiStrings.Toolbar.CURSOR,
                "Přepne zachycení kurzoru pro rychlou navigaci ve viewportu.",
                () -> toggleMouseCapture(engine));
        engine.lightNavFps = navigationToggle(
                engine,
                UiStrings.Toolbar.FPS,
                "Režim volné FPS navigace.",
                Engine.NavigationPreset.FPS);
        engine.lightNavBlend = navigationToggle(
                engine,
                UiStrings.Toolbar.BLENDER,
                "Navigace inspirovaná Blenderem se zaměřením na editaci objektů.",
                Engine.NavigationPreset.BLENDER);
        return toolbarGroup(
                UiStrings.Toolbar.NAVIGATION,
                engine.lightMouse,
                engine.lightNavFps,
                engine.lightNavBlend,
                createSelectionModeCombo(engine));
    }

    private static JPanel createDisplayGroup(Engine engine) {
        engine.lightModel = renderModeToggle(
                engine,
                RenderMode.MODEL,
                UiStrings.renderModeLabel(RenderMode.MODEL),
                "Rychlý solidní náhled bez pokročilého shadingu.");
        engine.lightBasic = renderModeToggle(
                engine,
                RenderMode.BASIC,
                UiStrings.renderModeLabel(RenderMode.BASIC),
                "Jednoduchý nerušený viewport režim.");
        engine.lightPhong = renderModeToggle(
                engine,
                RenderMode.PHONG,
                UiStrings.renderModeLabel(RenderMode.PHONG),
                "Lit preview pro práci se světly a materiály.");
        engine.lightWire = renderModeToggle(
                engine,
                RenderMode.WIREFRAME,
                UiStrings.renderModeLabel(RenderMode.WIREFRAME),
                "Topologie a siluety bez vyplnění.");
        engine.lightDither = ditherModeToggle(
                engine,
                UiStrings.renderModeLabel(RenderMode.DITHERING),
                "Stylizovaný dither náhled s modrým šumem.",
                DitherRenderer.DitherStyle.BLUE_NOISE);
        engine.lightAscii = ditherModeToggle(
                engine,
                "ASCII",
                "ASCII stylizace viewportu.",
                DitherRenderer.DitherStyle.ASCII);
        engine.lightTemporal = renderModeToggle(
                engine,
                RenderMode.TEMPORAL_NOISE,
                UiStrings.renderModeLabel(RenderMode.TEMPORAL_NOISE),
                "Animovaný noise flow režim.");
        engine.lightRay = renderModeToggle(
                engine,
                RenderMode.RAY_TRACING,
                "Ray",
                "Ray Tracing náhled s progresivním skládáním.");
        engine.lightPath = renderModeToggle(
                engine,
                RenderMode.PATH_TRACING,
                "Path",
                "Path Tracing pro finální věrnější náhled.");
        engine.lightHex = renderModeToggle(
                engine,
                RenderMode.HEX_MOSAIC,
                UiStrings.renderModeLabel(RenderMode.HEX_MOSAIC),
                "Stylizace do hexagonální mozaiky.");
        return toolbarGroup(
                UiStrings.Toolbar.DISPLAY,
                engine.lightModel,
                engine.lightBasic,
                engine.lightPhong,
                engine.lightWire,
                engine.lightDither,
                engine.lightAscii,
                engine.lightTemporal,
                engine.lightRay,
                engine.lightPath,
                engine.lightHex);
    }

    private static JPanel createSystemGroup(Engine engine) {
        engine.lightProjection = toolbarToggle(
                engine,
                UiStrings.Toolbar.PROJECTION_ORTHO,
                "Přepíná perspektivní a ortografickou projekci.",
                engine::toggleProjectionCamera);
        engine.lightPhysics = toolbarToggle(
                engine,
                UiStrings.Toolbar.PHYSICS,
                "Zapne nebo pozastaví fyziku scény.",
                engine::togglePhysics);
        engine.lightParallel = toolbarToggle(
                engine,
                UiStrings.Toolbar.THREADS,
                "Zapne paralelní rasterizaci a ukáže počet vláken.",
                engine::toggleParallelRaster);
        engine.lightAA = toolbarToggle(
                engine,
                UiStrings.Toolbar.ANTI_ALIAS,
                "Post-process anti-aliasing pro viewport.",
                engine::togglePostAA);
        engine.lightEditor = toolbarToggle(
                engine,
                UiStrings.Toolbar.OVERLAY,
                "Přepne editorové overlaye a manipulátory.",
                engine::toggleEditorOverlay);
        engine.lightDebug = toolbarToggle(
                engine,
                UiStrings.Toolbar.DEBUG,
                "Zobrazí technické debug informace viewportu.",
                engine::toggleDebugOverlay);
        return toolbarGroup(
                UiStrings.Toolbar.SYSTEM,
                engine.lightProjection,
                engine.lightPhysics,
                engine.lightParallel,
                engine.lightAA,
                engine.lightEditor,
                engine.lightDebug);
    }

    private static JPanel createHelpGroup(Engine engine) {
        return toolbarGroup(UiStrings.Toolbar.HELP, createHelpButton(engine));
    }

    private static JPanel toolbarGroup(String title, Component... components) {
        JPanel group = new JPanel(new WrapLayout(WrapLayout.LEFT, 6, 4));
        UiTheme.styleToolbarGroup(group);
        markRole(group, ROLE_GROUP);
        group.setAlignmentX(0.0f);
        group.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        addComponents(group, markRole(UiTheme.createBadge(title), ROLE_BADGE));
        addComponents(group, components);
        return group;
    }

    static JPanel toolbarPrimaryRow() {
        JPanel row = new JPanel(new GridBagLayout());
        row.setOpaque(false);
        markRole(row, ROLE_PRIMARY_ROW);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private static JPanel buildPrimaryRow(JPanel navigationGroup, JPanel systemGroup, JPanel helpGroup) {
        JPanel row = toolbarPrimaryRow();
        addPrimaryToolbarGroup(row, navigationGroup, 0, PRIMARY_NAVIGATION_WEIGHT);
        addPrimaryToolbarGroup(row, systemGroup, 1, PRIMARY_SYSTEM_WEIGHT);
        addPrimaryToolbarGroup(row, helpGroup, 2, 0.0);
        return row;
    }

    static void addPrimaryToolbarGroup(JPanel row, JPanel group, int gridX, double weightX) {
        if (row == null || group == null) {
            return;
        }
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = gridX;
        constraints.gridy = 0;
        constraints.weightx = Math.max(0.0, weightX);
        constraints.fill = weightX > 0.0 ? GridBagConstraints.HORIZONTAL : GridBagConstraints.NONE;
        constraints.anchor = GridBagConstraints.FIRST_LINE_START;
        row.add(group, constraints);
    }

    private static JPanel buildDisplayRow(JPanel displayGroup) {
        JPanel row = toolbarStretchRow();
        row.add(displayGroup, BorderLayout.CENTER);
        return row;
    }

    private static JPanel toolbarStretchRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private static JButton createHelpButton(Engine engine) {
        JButton button = new JButton(UiStrings.Toolbar.HELP_BUTTON);
        UiTheme.styleGhostButton(button);
        button.setToolTipText("Zobrazí informace o projektu a přehled ovládání.");
        button.addActionListener(e -> {
            engine.printHelp();
            engine.focusCanvas();
        });
        return button;
    }

    private static JComboBox<String> createSelectionModeCombo(Engine engine) {
        JComboBox<String> selectionMode = new JComboBox<>(new String[]{
                UiStrings.Toolbar.SELECTION_FRAME_AND_FOCUS,
                UiStrings.Toolbar.SELECTION_ONLY
        });
        UiTheme.styleComboBox(selectionMode);
        selectionMode.setSelectedIndex(
                engine.selectionViewMode == Engine.SelectionViewMode.FRAME_AND_FOCUS ? 0 : 1);
        selectionMode.setToolTipText("Určuje chování kliknutí na objekt ve viewportu.");
        selectionMode.addActionListener(e -> {
            engine.selectionViewMode = selectionMode.getSelectedIndex() == 0
                    ? Engine.SelectionViewMode.FRAME_AND_FOCUS
                    : Engine.SelectionViewMode.SELECT_ONLY;
            engine.focusCanvas();
        });
        return selectionMode;
    }

    private static JToggleButton navigationToggle(
            Engine engine,
            String label,
            String tooltip,
            Engine.NavigationPreset preset) {
        return toolbarToggle(engine, label, tooltip, () -> engine.setNavigationPreset(preset));
    }

    private static JToggleButton renderModeToggle(
            Engine engine,
            RenderMode renderMode,
            String label,
            String tooltip) {
        return toolbarToggle(engine, label, tooltip, () -> engine.setRenderMode(renderMode));
    }

    private static JToggleButton ditherModeToggle(
            Engine engine,
            String label,
            String tooltip,
            DitherRenderer.DitherStyle ditherStyle) {
        return toolbarToggle(engine, label, tooltip, () -> {
            engine.setDitherStyle(ditherStyle);
            engine.setRenderMode(RenderMode.DITHERING);
        });
    }

    private static JToggleButton toolbarToggle(Engine engine, String label, String tooltip, Runnable action) {
        JToggleButton toggle = engine.createLightToggle(label, action);
        toggle.setToolTipText(tooltip);
        return toggle;
    }

    private static void finalizeToolbarSetup(Engine engine, JPanel bar) {
        applyResponsiveToolbarLayout(engine);
        bar.revalidate();
        bar.repaint();
        engine.setupRightPanel();
        engine.setupViewportContextMenu();
        engine.refreshUiIndicators();
    }

    private static void refreshDynamicToggleLabels(Engine engine) {
        updateToggleText(engine.lightMouse, engine.mouseCaptured
                ? UiStrings.Toolbar.CURSOR + "*"
                : UiStrings.Toolbar.CURSOR);
        updateToggleText(engine.lightProjection, engine.orthographicProjection
                ? UiStrings.Toolbar.PROJECTION_ORTHO
                : UiStrings.Toolbar.PROJECTION_PERSP);
        updateToggleText(engine.lightParallel, UiStrings.Toolbar.THREADS + " x" + engine.parallelWorkerCount);
    }

    private static void refreshNavigationStates(Engine engine) {
        setToggleSelected(engine.lightMouse, engine.mouseCaptured);
        setToggleSelected(engine.lightNavFps, engine.navigationPreset == Engine.NavigationPreset.FPS);
        setToggleSelected(engine.lightNavBlend, engine.navigationPreset == Engine.NavigationPreset.BLENDER);
    }

    private static void refreshDisplayStates(Engine engine, DitherRenderer.DitherStyle ditherStyle) {
        boolean ditherModeActive = engine.activeMode == RenderMode.DITHERING;
        setToggleSelected(engine.lightModel, engine.activeMode == RenderMode.MODEL);
        setToggleSelected(engine.lightBasic, engine.activeMode == RenderMode.BASIC);
        setToggleSelected(engine.lightPhong, engine.activeMode == RenderMode.PHONG);
        setToggleSelected(engine.lightWire, engine.activeMode == RenderMode.WIREFRAME);
        setToggleSelected(engine.lightDither, ditherModeActive && ditherStyle != DitherRenderer.DitherStyle.ASCII);
        setToggleSelected(engine.lightAscii, ditherModeActive && ditherStyle == DitherRenderer.DitherStyle.ASCII);
        setToggleSelected(engine.lightTemporal, engine.activeMode == RenderMode.TEMPORAL_NOISE);
        setToggleSelected(engine.lightRay, engine.activeMode == RenderMode.RAY_TRACING);
        setToggleSelected(engine.lightPath, engine.activeMode == RenderMode.PATH_TRACING);
        setToggleSelected(engine.lightHex, engine.activeMode == RenderMode.HEX_MOSAIC);
    }

    private static void refreshSystemStates(Engine engine) {
        setToggleSelected(engine.lightDebug, engine.debugOverlayEnabled);
        setToggleSelected(engine.lightEditor, engine.editorOverlayEnabled);
        setToggleSelected(engine.lightProjection, engine.orthographicProjection);
        setToggleSelected(engine.lightPhysics, engine.physicsEnabled);
        setToggleSelected(engine.lightParallel, engine.parallelRasterEnabled);
        setToggleSelected(engine.lightAA, engine.postAAEnabled);
    }

    private static DitherRenderer.DitherStyle currentDitherStyle(Engine engine) {
        return engine.ditherRenderer != null
                ? engine.ditherRenderer.getStyle()
                : DitherRenderer.DitherStyle.BLUE_NOISE;
    }

    private static void updateToggleText(JToggleButton toggle, String text) {
        if (toggle != null) {
            toggle.setText(text);
        }
    }

    private static void setToggleSelected(JToggleButton toggle, boolean selected) {
        if (toggle != null) {
            toggle.setSelected(selected);
        }
    }

    private static void toggleMouseCapture(Engine engine) {
        if (engine.mouseCaptured) {
            engine.releaseMouseCapture();
        } else {
            engine.captureMouse();
        }
    }

    static void applyResponsiveToolbarLayout(Engine engine) {
        if (engine == null || engine.window == null) {
            return;
        }
        JPanel bar = engine.window.getToolbarPanel();
        if (bar == null) {
            return;
        }
        int availableWidth = toolbarAvailableWidth(engine, bar);
        UiTheme.ToolbarMetrics metrics = UiTheme.toolbarMetricsForWidth(availableWidth);
        applyResponsiveToolbarLayout(bar, metrics);
        bar.revalidate();
        bar.repaint();
    }

    private static int toolbarAvailableWidth(Engine engine, JPanel bar) {
        int availableWidth = Math.max(1, bar.getWidth());
        if (availableWidth > 1 || engine.window.getFrame() == null || engine.window.getFrame().getContentPane() == null) {
            return availableWidth;
        }
        return Math.max(1, engine.window.getFrame().getContentPane().getWidth());
    }

    private static void applyResponsiveToolbarLayout(Component component, UiTheme.ToolbarMetrics metrics) {
        applyResponsivePanelLayout(component, metrics);
        applyResponsiveComponentStyle(component, metrics);

        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                applyResponsiveToolbarLayout(child, metrics);
            }
        }
    }

    private static void applyResponsivePanelLayout(Component component, UiTheme.ToolbarMetrics metrics) {
        if (!(component instanceof JPanel panel)) {
            return;
        }
        Object role = panel.getClientProperty(TOOLBAR_ROLE);
        if (panel.getLayout() instanceof WrapLayout wrapLayout && ROLE_GROUP.equals(role)) {
            wrapLayout.setHgap(metrics.groupHGap());
            wrapLayout.setVgap(metrics.groupVGap());
            return;
        }
        if (ROLE_PRIMARY_ROW.equals(role)) {
            applyPrimaryToolbarRowGap(panel, metrics.rowHGap());
        }
    }

    private static void applyResponsiveComponentStyle(Component component, UiTheme.ToolbarMetrics metrics) {
        if (component instanceof JLabel label) {
            Object role = label.getClientProperty(TOOLBAR_ROLE);
            if (ROLE_BADGE.equals(role)) {
                UiTheme.styleToolbarBadge(label, metrics);
            }
        } else if (component instanceof JComboBox<?> combo) {
            UiTheme.styleToolbarComboBox(combo, metrics);
        } else if (component instanceof JToggleButton toggle) {
            UiTheme.styleToolbarToggle(toggle, toggle.isSelected(), metrics);
        } else if (component instanceof JButton button) {
            UiTheme.styleToolbarGhostButton(button, metrics);
        }
    }

    static void applyPrimaryToolbarRowGap(JPanel row, int gap) {
        if (row == null || !(row.getLayout() instanceof GridBagLayout layout)) {
            return;
        }
        Component[] children = row.getComponents();
        for (int i = 0; i < children.length; i++) {
            Component child = children[i];
            GridBagConstraints constraints = layout.getConstraints(child);
            int rightInset = i == children.length - 1 ? 0 : Math.max(0, gap);
            Insets currentInsets = constraints.insets == null ? new Insets(0, 0, 0, 0) : constraints.insets;
            if (currentInsets.right == rightInset) {
                continue;
            }
            constraints.insets = new Insets(currentInsets.top, currentInsets.left, currentInsets.bottom, rightInset);
            layout.setConstraints(child, constraints);
        }
    }

    private static void addComponents(Container container, Component... components) {
        if (container == null || components == null) {
            return;
        }
        for (Component component : components) {
            container.add(component);
        }
    }

    private static <T extends JComponent> T markRole(T component, String role) {
        if (component != null) {
            component.putClientProperty(TOOLBAR_ROLE, role);
        }
        return component;
    }
}
