package engine.core;

import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
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
        engine.window.setTimelineDockAttached(!isDetached(engine));
        host.removeAll();
        host.setLayout(new BorderLayout(0, 0));

        JPanel root = buildRoot(engine);
        engine.bottomDockRootPanel = root;

        if (isDetached(engine)) {
            host.setVisible(false);
            DetachedWorkspaceWindow detachedWindow = ensureDetachedWindow(engine);
            detachedWindow.hostPanel().removeAll();
            detachedWindow.hostPanel().add(root, BorderLayout.CENTER);
            detachedWindow.hostPanel().revalidate();
            detachedWindow.hostPanel().repaint();
            detachedWindow.show();
        } else {
            host.setVisible(true);
            host.add(root, BorderLayout.CENTER);
            syncDockSizing(engine, host);
            host.revalidate();
            host.repaint();
        }

        showWorkspace(engine, engine.bottomDockWorkspace);
    }

    private static JPanel buildRoot(Engine engine) {
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setOpaque(false);

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

        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
        controls.add(tabs);
        controls.add(Box.createRigidArea(new Dimension(10, 1)));
        engine.bottomDockDetachButton = createDetachButton(engine);
        controls.add(engine.bottomDockDetachButton);
        header.add(controls, BorderLayout.EAST);

        engine.bottomDockCardPanel = new JPanel(new CardLayout());
        engine.bottomDockCardPanel.setOpaque(false);

        JPanel timelineHost = new JPanel(new BorderLayout());
        timelineHost.setOpaque(false);
        JPanel materialHost = new JPanel(new BorderLayout());
        materialHost.setOpaque(false);

        engine.timelineDockHostPanel = timelineHost;
        engine.materialDockHostPanel = materialHost;
        EngineTimelineDock.populate(engine, timelineHost);
        EngineMaterialDock.rebuildInto(engine, materialHost);

        engine.bottomDockCardPanel.add(timelineHost, CARD_TIMELINE);
        engine.bottomDockCardPanel.add(materialHost, CARD_MATERIAL);

        root.add(header, BorderLayout.NORTH);
        root.add(engine.bottomDockCardPanel, BorderLayout.CENTER);
        return root;
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
            engine.bottomDockSubtitleLabel.setText(workspaceSubtitle(next));
        }
        updateWorkspaceButtons(engine);
        if (engine.bottomDockCardPanel != null) {
            engine.bottomDockCardPanel.revalidate();
            engine.bottomDockCardPanel.repaint();
        }
        if (isDetached(engine)) {
            updateDetachedWindowTitle(engine);
            focusDetached(engine);
        } else if (engine.window != null) {
            syncDockSizing(engine, engine.window.getTimelinePanel());
        }
    }

    static void detach(Engine engine) {
        if (engine == null || engine.window == null || engine.bottomDockRootPanel == null || isDetached(engine)) {
            return;
        }
        JPanel mainHost = engine.window.getTimelinePanel();
        mainHost.removeAll();
        mainHost.setVisible(false);
        engine.window.setTimelineDockAttached(false);
        mainHost.revalidate();
        mainHost.repaint();

        DetachedWorkspaceWindow detachedWindow = ensureDetachedWindow(engine);
        detachedWindow.hostPanel().removeAll();
        detachedWindow.hostPanel().add(engine.bottomDockRootPanel, BorderLayout.CENTER);
        detachedWindow.hostPanel().revalidate();
        detachedWindow.hostPanel().repaint();
        updateDetachedWindowTitle(engine);
        detachedWindow.show();
        updateWorkspaceButtons(engine);
    }

    static void attach(Engine engine) {
        if (engine == null || engine.window == null || engine.bottomDockRootPanel == null) {
            return;
        }
        DetachedWorkspaceWindow detachedWindow = engine.detachedBottomDockWindow;
        if (detachedWindow != null) {
            detachedWindow.hostPanel().removeAll();
        }
        JPanel mainHost = engine.window.getTimelinePanel();
        mainHost.removeAll();
        mainHost.add(engine.bottomDockRootPanel, BorderLayout.CENTER);
        mainHost.setVisible(true);
        engine.window.setTimelineDockAttached(true);
        syncDockSizing(engine, mainHost);
        mainHost.revalidate();
        mainHost.repaint();
        if (detachedWindow != null) {
            detachedWindow.disposeSilently();
        }
        engine.detachedBottomDockWindow = null;
        updateWorkspaceButtons(engine);
    }

    static void focusDetached(Engine engine) {
        if (engine != null && engine.detachedBottomDockWindow != null) {
            engine.detachedBottomDockWindow.focus();
        }
    }

    static void disposeDetached(Engine engine) {
        if (engine == null) {
            return;
        }
        DetachedWorkspaceWindow detachedWindow = engine.detachedBottomDockWindow;
        engine.detachedBottomDockWindow = null;
        if (detachedWindow != null) {
            detachedWindow.disposeSilently();
        }
    }

    static boolean isDetached(Engine engine) {
        return engine != null
                && engine.detachedBottomDockWindow != null
                && engine.detachedBottomDockWindow.isDisplayable();
    }

    private static DetachedWorkspaceWindow ensureDetachedWindow(Engine engine) {
        if (engine.detachedBottomDockWindow != null && engine.detachedBottomDockWindow.isDisplayable()) {
            return engine.detachedBottomDockWindow;
        }
        engine.detachedBottomDockWindow = new DetachedWorkspaceWindow(
                engine,
                UiStrings.Dock.TITLE,
                workspaceSubtitle(engine.bottomDockWorkspace),
                () -> attach(engine)
        );
        return engine.detachedBottomDockWindow;
    }

    private static void updateDetachedWindowTitle(Engine engine) {
        if (engine == null || engine.detachedBottomDockWindow == null) {
            return;
        }
        engine.detachedBottomDockWindow.updateTitle(UiStrings.Dock.TITLE, workspaceSubtitle(engine.bottomDockWorkspace));
    }

    private static String workspaceSubtitle(Engine.BottomDockWorkspace workspace) {
        return workspace == Engine.BottomDockWorkspace.MATERIAL
                ? UiStrings.Dock.MATERIAL_SUBTITLE
                : UiStrings.Dock.TIMELINE_SUBTITLE;
    }

    private static JToggleButton createWorkspaceButton(Engine engine,
                                                       String label,
                                                       Engine.BottomDockWorkspace workspace) {
        JToggleButton button = UiBuilder.createStyledToggleButton(label);
        button.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        button.addActionListener(e -> {
            showWorkspace(engine, workspace);
            if (!isDetached(engine)) {
                engine.focusCanvas();
            }
        });
        return button;
    }

    private static JButton createDetachButton(Engine engine) {
        JButton button = new JButton(UiStrings.Dock.DETACH_WINDOW);
        UiBuilder.styleGhostButton(button);
        button.addActionListener(e -> {
            if (isDetached(engine)) {
                attach(engine);
            } else {
                detach(engine);
            }
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
        if (engine.bottomDockDetachButton != null) {
            engine.bottomDockDetachButton.setText(isDetached(engine)
                    ? UiStrings.Dock.ATTACH_WINDOW
                    : UiStrings.Dock.DETACH_WINDOW);
        }
    }

    private static void syncDockSizing(Engine engine, JPanel host) {
        if (engine == null || host == null || engine.window == null) {
            return;
        }
        BorderLayout layout = host.getLayout() instanceof BorderLayout
                ? (BorderLayout) host.getLayout()
                : null;
        Component center = layout != null ? layout.getLayoutComponent(BorderLayout.CENTER) : null;
        int contentHeight = center != null ? center.getPreferredSize().height : 0;
        int minHeight = Math.max(UiTheme.BOTTOM_DOCK_MIN_HEIGHT, contentHeight);
        Dimension preferred = host.getPreferredSize();
        host.setMinimumSize(new Dimension(0, minHeight));
        host.setPreferredSize(new Dimension(
                Math.max(1, preferred != null ? preferred.width : host.getWidth()),
                Math.max(engine.window.getTimelineDockHeight(), minHeight)
        ));
    }
}
