package engine.core;

import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;

final class EngineBottomDock {
    private static final String CARD_TIMELINE = "timeline";
    private static final String CARD_MATERIAL = "material";

    private EngineBottomDock() {
    }

    static void setup(Engine engine) {
        if (engine == null || engine.window == null) {
            return;
        }
        JPanel host = engine.window.getTimelinePanel();
        host.removeAll();
        host.setLayout(new BorderLayout(0, 0));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(true);
        header.setBackground(UiTheme.PANEL_INSET);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, UiTheme.BORDER_SUBTLE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)
        ));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.X_AXIS));
        JPanel titleStack = new JPanel();
        titleStack.setOpaque(false);
        titleStack.setLayout(new BoxLayout(titleStack, BoxLayout.Y_AXIS));
        engine.bottomDockTitleLabel = new JLabel(UiStrings.Dock.TITLE);
        engine.bottomDockTitleLabel.setForeground(UiTheme.TEXT_PRIMARY);
        engine.bottomDockTitleLabel.setFont(engine.bottomDockTitleLabel.getFont().deriveFont(java.awt.Font.BOLD, 13f));
        engine.bottomDockSubtitleLabel = new JLabel(UiStrings.Dock.TIMELINE_SUBTITLE);
        engine.bottomDockSubtitleLabel.setForeground(UiTheme.TEXT_MUTED);
        titleStack.add(engine.bottomDockTitleLabel);
        titleStack.add(Box.createRigidArea(new Dimension(0, 2)));
        titleStack.add(engine.bottomDockSubtitleLabel);
        left.add(titleStack);
        header.add(left, BorderLayout.WEST);

        JPanel tabs = new JPanel();
        tabs.setOpaque(false);
        tabs.setLayout(new BoxLayout(tabs, BoxLayout.X_AXIS));
        engine.bottomDockTimelineButton = createWorkspaceButton(engine, UiStrings.Dock.TIMELINE, Engine.BottomDockWorkspace.TIMELINE);
        engine.bottomDockMaterialButton = createWorkspaceButton(engine, UiStrings.Dock.MATERIAL, Engine.BottomDockWorkspace.MATERIAL);
        tabs.add(engine.bottomDockTimelineButton);
        tabs.add(Box.createRigidArea(new Dimension(8, 1)));
        tabs.add(engine.bottomDockMaterialButton);
        header.add(tabs, BorderLayout.EAST);

        engine.bottomDockCardPanel = new JPanel(new CardLayout());
        engine.bottomDockCardPanel.setOpaque(false);

        JPanel timelineHost = new JPanel(new BorderLayout());
        timelineHost.setOpaque(false);
        JPanel materialHost = new JPanel(new BorderLayout());
        materialHost.setOpaque(false);

        engine.materialDockHostPanel = materialHost;
        EngineTimelineDock.populate(engine, timelineHost);
        EngineMaterialDock.rebuildInto(engine, materialHost);

        engine.bottomDockCardPanel.add(timelineHost, CARD_TIMELINE);
        engine.bottomDockCardPanel.add(materialHost, CARD_MATERIAL);

        host.add(header, BorderLayout.NORTH);
        host.add(engine.bottomDockCardPanel, BorderLayout.CENTER);
        syncDockSizing(engine, host);
        host.revalidate();
        host.repaint();

        showWorkspace(engine, engine.bottomDockWorkspace);
    }

    static void showWorkspace(Engine engine, Engine.BottomDockWorkspace workspace) {
        if (engine == null || engine.bottomDockCardPanel == null) {
            return;
        }
        Engine.BottomDockWorkspace next = workspace == null
                ? Engine.BottomDockWorkspace.TIMELINE
                : workspace;
        engine.bottomDockWorkspace = next;
        CardLayout layout = (CardLayout) engine.bottomDockCardPanel.getLayout();
        layout.show(engine.bottomDockCardPanel, next == Engine.BottomDockWorkspace.MATERIAL ? CARD_MATERIAL : CARD_TIMELINE);
        if (engine.bottomDockTitleLabel != null) {
            engine.bottomDockTitleLabel.setText(next == Engine.BottomDockWorkspace.MATERIAL
                    ? UiStrings.Dock.MATERIAL
                    : UiStrings.Dock.TIMELINE);
        }
        if (engine.bottomDockSubtitleLabel != null) {
            engine.bottomDockSubtitleLabel.setText(next == Engine.BottomDockWorkspace.MATERIAL
                    ? UiStrings.Dock.MATERIAL_SUBTITLE
                    : UiStrings.Dock.TIMELINE_SUBTITLE);
        }
        updateWorkspaceButtons(engine);
        if (engine.window != null) {
            syncDockSizing(engine, engine.window.getTimelinePanel());
        }
        engine.bottomDockCardPanel.revalidate();
        engine.bottomDockCardPanel.repaint();
    }

    private static JToggleButton createWorkspaceButton(Engine engine,
                                                       String label,
                                                       Engine.BottomDockWorkspace workspace) {
        JToggleButton button = UiBuilder.createStyledToggleButton(label);
        button.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        button.addActionListener(e -> {
            showWorkspace(engine, workspace);
            engine.focusCanvas();
        });
        return button;
    }

    private static void updateWorkspaceButtons(Engine engine) {
        if (engine.bottomDockTimelineButton != null) {
            UiBuilder.styleLight(engine.bottomDockTimelineButton,
                    engine.bottomDockWorkspace == Engine.BottomDockWorkspace.TIMELINE);
        }
        if (engine.bottomDockMaterialButton != null) {
            UiBuilder.styleLight(engine.bottomDockMaterialButton,
                    engine.bottomDockWorkspace == Engine.BottomDockWorkspace.MATERIAL);
        }
    }

    private static void syncDockSizing(Engine engine, JPanel host) {
        if (engine == null || host == null) {
            return;
        }
        BorderLayout layout = host.getLayout() instanceof BorderLayout
                ? (BorderLayout) host.getLayout()
                : null;
        Component north = layout != null ? layout.getLayoutComponent(BorderLayout.NORTH) : null;
        int headerHeight = north != null ? north.getPreferredSize().height : 0;
        int minHeight = Math.max(UiTheme.BOTTOM_DOCK_MIN_HEIGHT, headerHeight + 10);
        Dimension preferred = host.getPreferredSize();
        host.setMinimumSize(new Dimension(0, minHeight));
        host.setPreferredSize(new Dimension(
                Math.max(1, preferred != null ? preferred.width : host.getWidth()),
                Math.max(engine.window.getTimelineDockHeight(), minHeight)
        ));
    }
}
