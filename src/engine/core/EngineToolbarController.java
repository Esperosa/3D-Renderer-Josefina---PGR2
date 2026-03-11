package engine.core;

import engine.render.post.DitherRenderer;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;
import engine.util.UiBuilder;

import java.awt.BorderLayout;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;

final class EngineToolbarController {

    private EngineToolbarController() {
    }

    static void setupToolbar(Engine engine) {
        JPanel bar = engine.window.getToolbarPanel();
        bar.removeAll();
        bar.setLayout(new BoxLayout(bar, BoxLayout.Y_AXIS));
        UiTheme.styleToolbarPanel(bar);

        engine.lightMouse = toolbarToggle(engine, UiStrings.Toolbar.CURSOR,
                "Přepne zachycení kurzoru pro rychlou navigaci ve viewportu.", () -> {
            if (engine.mouseCaptured) {
                engine.releaseMouseCapture();
            } else {
                engine.captureMouse();
            }
        });
        engine.lightNavFps = toolbarToggle(engine, UiStrings.Toolbar.FPS,
                "Režim volné FPS navigace.", () -> engine.setNavigationPreset(Engine.NavigationPreset.FPS));
        engine.lightNavBlend = toolbarToggle(engine, UiStrings.Toolbar.BLENDER,
                "Navigace inspirovaná Blenderem se zaměřením na editaci objektů.", () ->
                        engine.setNavigationPreset(Engine.NavigationPreset.BLENDER));
        engine.lightModel = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.MODEL),
                "Rychlý solidní náhled bez pokročilého shadingu.", () -> engine.setRenderMode(RenderMode.MODEL));
        engine.lightBasic = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.BASIC),
                "Jednoduchý nerušený viewport režim.", () -> engine.setRenderMode(RenderMode.BASIC));
        engine.lightPhong = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.PHONG),
                "Lit preview pro práci se světly a materiály.", () -> engine.setRenderMode(RenderMode.PHONG));
        engine.lightWire = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.WIREFRAME),
                "Topologie a siluety bez vyplnění.", () -> engine.setRenderMode(RenderMode.WIREFRAME));
        engine.lightDither = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.DITHERING),
                "Stylizovaný dither náhled s modrým šumem.", () -> {
            engine.setDitherStyle(DitherRenderer.DitherStyle.BLUE_NOISE);
            engine.setRenderMode(RenderMode.DITHERING);
        });
        engine.lightAscii = toolbarToggle(engine, "ASCII",
                "ASCII stylizace viewportu.", () -> {
            engine.setDitherStyle(DitherRenderer.DitherStyle.ASCII);
            engine.setRenderMode(RenderMode.DITHERING);
        });
        engine.lightTemporal = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.TEMPORAL_NOISE),
                "Animovaný noise flow režim.", () -> engine.setRenderMode(RenderMode.TEMPORAL_NOISE));
        engine.lightRay = toolbarToggle(engine, "Ray",
                "Ray Tracing náhled s progresivním skládáním.", () -> engine.setRenderMode(RenderMode.RAY_TRACING));
        engine.lightPath = toolbarToggle(engine, "Path",
                "Path Tracing pro finální věrnější náhled.", () -> engine.setRenderMode(RenderMode.PATH_TRACING));
        engine.lightHex = toolbarToggle(engine, UiStrings.renderModeLabel(RenderMode.HEX_MOSAIC),
                "Stylizace do hexagonální mozaiky.", () -> engine.setRenderMode(RenderMode.HEX_MOSAIC));
        engine.lightDebug = toolbarToggle(engine, UiStrings.Toolbar.DEBUG,
                "Zobrazí technické debug informace viewportu.", engine::toggleDebugOverlay);
        engine.lightEditor = toolbarToggle(engine, UiStrings.Toolbar.OVERLAY,
                "Přepne editorové overlaye a manipulátory.", engine::toggleEditorOverlay);
        engine.lightProjection = toolbarToggle(engine, UiStrings.Toolbar.PROJECTION_ORTHO,
                "Přepíná perspektivní a ortografickou projekci.", engine::toggleProjectionCamera);
        engine.lightPhysics = toolbarToggle(engine, UiStrings.Toolbar.PHYSICS,
                "Zapne nebo pozastaví fyziku scény.", engine::togglePhysics);
        engine.lightParallel = toolbarToggle(engine, UiStrings.Toolbar.THREADS,
                "Zapne paralelní rasterizaci a ukáže počet vláken.", engine::toggleParallelRaster);
        engine.lightAA = toolbarToggle(engine, UiStrings.Toolbar.ANTI_ALIAS,
                "Post-process anti-aliasing pro viewport.", engine::togglePostAA);

        JPanel navigationGroup = toolbarGroup(UiStrings.Toolbar.NAVIGATION);
        navigationGroup.add(engine.lightMouse);
        navigationGroup.add(engine.lightNavFps);
        navigationGroup.add(engine.lightNavBlend);

        JComboBox<String> selectionMode = new JComboBox<>(new String[]{
                UiStrings.Toolbar.SELECTION_FRAME_AND_FOCUS,
                UiStrings.Toolbar.SELECTION_ONLY
        });
        UiTheme.styleComboBox(selectionMode);
        selectionMode.setSelectedIndex(
                engine.selectionViewMode == Engine.SelectionViewMode.FRAME_AND_FOCUS ? 0 : 1
        );
        selectionMode.setToolTipText("Určuje chování kliknutí na objekt ve viewportu.");
        selectionMode.addActionListener(e -> {
            engine.selectionViewMode = selectionMode.getSelectedIndex() == 0
                    ? Engine.SelectionViewMode.FRAME_AND_FOCUS
                    : Engine.SelectionViewMode.SELECT_ONLY;
            engine.focusCanvas();
        });
        navigationGroup.add(selectionMode);

        JPanel displayGroup = toolbarGroup(UiStrings.Toolbar.DISPLAY);
        displayGroup.add(engine.lightModel);
        displayGroup.add(engine.lightBasic);
        displayGroup.add(engine.lightPhong);
        displayGroup.add(engine.lightWire);
        displayGroup.add(engine.lightDither);
        displayGroup.add(engine.lightAscii);
        displayGroup.add(engine.lightTemporal);
        displayGroup.add(engine.lightRay);
        displayGroup.add(engine.lightPath);
        displayGroup.add(engine.lightHex);

        JPanel systemGroup = toolbarGroup(UiStrings.Toolbar.SYSTEM);
        systemGroup.add(engine.lightProjection);
        systemGroup.add(engine.lightPhysics);
        systemGroup.add(engine.lightParallel);
        systemGroup.add(engine.lightAA);
        systemGroup.add(engine.lightEditor);
        systemGroup.add(engine.lightDebug);

        JButton helpBtn = new JButton(UiStrings.Toolbar.HELP_BUTTON);
        UiBuilder.styleGhostButton(helpBtn);
        helpBtn.setPreferredSize(new Dimension(helpBtn.getPreferredSize().width, 34));
        helpBtn.setToolTipText("Vypíše přehled kláves a ovládání do konzole.");
        helpBtn.addActionListener(e -> {
            engine.printHelp();
            engine.focusCanvas();
        });

        JPanel helpGroup = toolbarGroup(UiStrings.Toolbar.HELP);
        helpGroup.add(helpBtn);

        JPanel primaryRow = toolbarRow();
        primaryRow.add(navigationGroup);
        primaryRow.add(systemGroup);
        primaryRow.add(helpGroup);

        JPanel displayRow = toolbarStretchRow();
        displayRow.add(displayGroup, BorderLayout.CENTER);

        bar.add(primaryRow);
        bar.add(Box.createRigidArea(new Dimension(0, 6)));
        bar.add(displayRow);

        bar.revalidate();
        bar.repaint();
        engine.setupRightPanel();
        engine.setupViewportContextMenu();
        engine.refreshUiIndicators();
    }

    static void refreshUiIndicators(Engine engine) {
        DitherRenderer.DitherStyle ditherStyle = engine.ditherRenderer != null
                ? engine.ditherRenderer.getStyle()
                : DitherRenderer.DitherStyle.BLUE_NOISE;
        styleLight(engine.lightMouse, engine.mouseCaptured);
        styleLight(engine.lightNavFps, engine.navigationPreset == Engine.NavigationPreset.FPS);
        styleLight(engine.lightNavBlend, engine.navigationPreset == Engine.NavigationPreset.BLENDER);
        styleLight(engine.lightModel, engine.activeMode == RenderMode.MODEL);
        styleLight(engine.lightBasic, engine.activeMode == RenderMode.BASIC);
        styleLight(engine.lightPhong, engine.activeMode == RenderMode.PHONG);
        styleLight(engine.lightWire, engine.activeMode == RenderMode.WIREFRAME);
        styleLight(engine.lightDither,
                engine.activeMode == RenderMode.DITHERING && ditherStyle != DitherRenderer.DitherStyle.ASCII);
        styleLight(engine.lightAscii,
                engine.activeMode == RenderMode.DITHERING && ditherStyle == DitherRenderer.DitherStyle.ASCII);
        styleLight(engine.lightTemporal, engine.activeMode == RenderMode.TEMPORAL_NOISE);
        styleLight(engine.lightRay, engine.activeMode == RenderMode.RAY_TRACING);
        styleLight(engine.lightPath, engine.activeMode == RenderMode.PATH_TRACING);
        styleLight(engine.lightHex, engine.activeMode == RenderMode.HEX_MOSAIC);
        styleLight(engine.lightDebug, engine.debugOverlayEnabled);
        styleLight(engine.lightEditor, engine.editorOverlayEnabled);
        styleLight(engine.lightProjection, engine.orthographicProjection);
        styleLight(engine.lightPhysics, engine.physicsEnabled);
        styleLight(engine.lightParallel, engine.parallelRasterEnabled);
        styleLight(engine.lightAA, engine.postAAEnabled);
        if (engine.lightProjection != null) {
            engine.lightProjection.setText(engine.orthographicProjection
                    ? UiStrings.Toolbar.PROJECTION_ORTHO
                    : UiStrings.Toolbar.PROJECTION_PERSP);
        }
        if (engine.lightMouse != null) {
            engine.lightMouse.setText(engine.mouseCaptured
                    ? UiStrings.Toolbar.CURSOR + "*"
                    : UiStrings.Toolbar.CURSOR);
        }
        if (engine.lightParallel != null) {
            engine.lightParallel.setText(UiStrings.Toolbar.THREADS + " x" + engine.parallelWorkerCount);
        }
    }

    private static void styleLight(JToggleButton light, boolean active) {
        UiBuilder.styleLight(light, active);
    }

    private static JPanel toolbarGroup(String title) {
        JPanel group = new JPanel(new WrapLayout(WrapLayout.LEFT, 6, 4));
        UiTheme.styleToolbarGroup(group);
        group.setAlignmentX(0.0f);
        group.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        JLabel label = UiBuilder.badge(title);
        label.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        group.add(label);
        return group;
    }

    private static JPanel toolbarRow() {
        JPanel row = new JPanel(new WrapLayout(WrapLayout.LEFT, 10, 6));
        row.setOpaque(false);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private static JPanel toolbarStretchRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.setAlignmentX(0.0f);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        return row;
    }

    private static JToggleButton toolbarToggle(Engine engine, String label, String tooltip, Runnable action) {
        JToggleButton toggle = engine.createLightToggle(label, action);
        toggle.setToolTipText(tooltip);
        return toggle;
    }
}
