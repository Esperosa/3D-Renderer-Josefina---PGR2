package engine.core;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.IllegalComponentStateException;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import engine.io.FileUtil;
import engine.math.Vec3;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;
import engine.util.RuntimeInstrumentation;

/**
 * Hlavní AWT okno s canvasem, toolbarem a postranními panely.
 */
public class Window {
    enum AxisWidgetTarget {
        NONE,
        POS_X,
        NEG_X,
        POS_Y,
        NEG_Y,
        POS_Z,
        NEG_Z
    }

    static final String APP_ICON_PNG_PATH = "assets/icons/IcoUni.png";
    static final String APP_ICON_ICO_PATH = "assets/icons/IcoUni.ico";
    private static final Color FRAME_BG = UiTheme.APP_BG;
    private static final Color PANEL_BG = UiTheme.PANEL_BG;
    private static final Color TAB_BORDER = UiTheme.BORDER_SUBTLE;
    private static final Color ICON_FG = UiTheme.ACCENT;
    private static final Color OVERLAY_FG = UiTheme.ACCENT;
    private static final int AXIS_WIDGET_BOX_SIZE = 146;
    private static final int AXIS_WIDGET_MARGIN = 28;
    private static final int AXIS_WIDGET_ANCHOR_X = 96;
    private static final int AXIS_WIDGET_ANCHOR_Y = 96;
    private static final double AXIS_WIDGET_HIT_PADDING = 6.0;
    private static final double AXIS_WIDGET_AXIS_LENGTH = 1.08;
    private static final double AXIS_WIDGET_CAMERA_DISTANCE = 4.0;
    private static final double AXIS_WIDGET_FOCAL_LENGTH = 2.86;
    private static final double AXIS_WIDGET_DEPTH_FLOOR = 1.8;
    private static final double AXIS_WIDGET_SCREEN_SCALE = 63.0;
    private static final Color RENDER_PREVIEW_BAR_BG = new Color(18, 24, 31);
    private static final Color RENDER_PREVIEW_PROGRESS_BG = new Color(28, 38, 49, 228);
    private static final Color RENDER_PREVIEW_PROGRESS_FILL = new Color(113, 197, 255, 238);
    private static final Color RENDER_PREVIEW_TEXT = new Color(236, 241, 248);
    private static final Color RENDER_PREVIEW_TEXT_MUTED = new Color(170, 182, 197);
    private static final int RENDER_PREVIEW_BAR_HEIGHT = 72;
    private static final Color AXIS_WIDGET_X_COLOR = new Color(255, 64, 86);
    private static final Color AXIS_WIDGET_Y_COLOR = new Color(126, 255, 24);
    private static final Color AXIS_WIDGET_Z_COLOR = new Color(72, 146, 255);
    private static final AxisWidgetSpec[] AXIS_WIDGET_SPECS = new AxisWidgetSpec[]{
            new AxisWidgetSpec(AxisWidgetTarget.POS_X, "X", new Vec3(1.0, 0.0, 0.0), AXIS_WIDGET_X_COLOR, true),
            new AxisWidgetSpec(AxisWidgetTarget.NEG_X, null, new Vec3(-1.0, 0.0, 0.0), AXIS_WIDGET_X_COLOR, false),
            new AxisWidgetSpec(AxisWidgetTarget.POS_Y, "Y", new Vec3(0.0, 1.0, 0.0), AXIS_WIDGET_Y_COLOR, true),
            new AxisWidgetSpec(AxisWidgetTarget.NEG_Y, null, new Vec3(0.0, -1.0, 0.0), AXIS_WIDGET_Y_COLOR, false),
            new AxisWidgetSpec(AxisWidgetTarget.POS_Z, "Z", new Vec3(0.0, 0.0, 1.0), AXIS_WIDGET_Z_COLOR, true),
            new AxisWidgetSpec(AxisWidgetTarget.NEG_Z, null, new Vec3(0.0, 0.0, -1.0), AXIS_WIDGET_Z_COLOR, false)
    };

    private final JFrame frame;
    private final JPanel rootPanel;
    private final Canvas canvas;
    private final JPanel toolbar;
    private final JPanel timelineDock;
    private final JPanel renderPreviewDock;
    private final JPanel southDockHost;
    private final JPanel southDockEmptyPanel;
    private final CardLayout southDockLayout;
    private final JPanel rightSidebar;
    private final JPanel rightSceneBrowserHost;
    private final JSplitPane rightSidebarSplit;
    private final JTabbedPane rightTabs;
    private final JPopupMenu contextMenu;
    private JLabel renderPreviewHeadlineLabel;
    private JLabel renderPreviewMetricsLabel;
    private JProgressBar renderPreviewProgressBar;
    private JButton renderPreviewPauseButton;
    private JButton renderPreviewCancelButton;
    private final Map<String, JPanel> rightTabContents;
    private final Map<String, Integer> rightTabIndices;
    private final Map<String, JScrollPane> rightTabScrollPanes;
    private Runnable contextMenuBeforeShowAction;
    private Runnable contextMenuAfterAction;
    private Runnable contextMenuAfterCancelAction;

    private int width;
    private int height;
    private BufferedImage backBuffer;
    private final Cursor defaultCursor;
    private final Cursor hiddenCursor;
    private final Font overlayFont;
    private final Font modeSwitchOverlayFont;
    private final Font modeSwitchOverlaySubFont;
    private final GraphicsDevice fullscreenDevice;
    private volatile String[] overlayLines;
    private volatile boolean renderPreviewMode;
    private final boolean fullscreen;
    private boolean cursorCaptured;
    private boolean smoothUpscaling;
    private volatile boolean closeRequested;
    private volatile boolean worldAxisWidgetVisible;
    private volatile double worldAxisRightX;
    private volatile double worldAxisRightY;
    private volatile double worldAxisRightZ;
    private volatile double worldAxisUpX;
    private volatile double worldAxisUpY;
    private volatile double worldAxisUpZ;
    private volatile double worldAxisForwardX;
    private volatile double worldAxisForwardY;
    private volatile double worldAxisForwardZ;
    private volatile boolean renderModeSwitchOverlayActive;
    private volatile double renderModeSwitchOverlayAlpha;
    private volatile String renderModeSwitchOverlayLabel;
    private int timelineDockHeight;
    private boolean timelineDockManuallyResized;
    private boolean timelineResizeDragging;
    private int timelineResizeStartScreenY;
    private int timelineResizeStartHeight;
    private boolean timelineDockAttached;
    private Runnable renderPreviewPauseAction;
    private Runnable renderPreviewCancelAction;

    /**
     * Okno vytvoří a rovnou ho zobrazí.
     *
     * @param title titulek okna
     * @param width počáteční šířka v pixelech
     * @param height počáteční výška v pixelech
     */
    public Window(String title, int width, int height) {
        this(title, width, height, false);
    }

    public Window(String title, int width, int height, boolean fullscreen) {
        this.width = width;
        this.height = height;
        this.closeRequested = false;
        this.rightTabContents = new LinkedHashMap<>();
        this.rightTabIndices = new LinkedHashMap<>();
        this.rightTabScrollPanes = new LinkedHashMap<>();
        this.fullscreen = fullscreen;
        this.fullscreenDevice = fullscreen ? resolveFullscreenDevice() : null;
        this.renderModeSwitchOverlayActive = false;
        this.renderModeSwitchOverlayAlpha = 0.0;
        this.renderModeSwitchOverlayLabel = "";

        frame = new JFrame(title);
        if (fullscreen) {
            frame.setUndecorated(true);
            frame.setResizable(false);
        }
        installAppIcon();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested = true;
                exitFullscreenIfNeeded();
                frame.dispose();
            }
        });

        rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(FRAME_BG);

        toolbar = new JPanel(new WrapLayout(WrapLayout.LEFT, 8, 8));
        UiTheme.styleToolbarPanel(toolbar);
        rootPanel.add(toolbar, BorderLayout.NORTH);

        timelineDock = new JPanel(new BorderLayout(8, 0));
        timelineDock.setBackground(UiTheme.PANEL_BG);
        timelineDock.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER_SUBTLE));
        timelineDockHeight = computeDefaultTimelineDockHeight(height);
        timelineDock.setPreferredSize(new Dimension(width, timelineDockHeight));
        renderPreviewDock = createRenderPreviewDock();
        southDockLayout = new CardLayout();
        southDockHost = new JPanel(southDockLayout);
        southDockHost.setOpaque(true);
        southDockHost.setBackground(UiTheme.PANEL_BG);
        southDockEmptyPanel = new JPanel();
        southDockEmptyPanel.setOpaque(false);
        southDockHost.add(timelineDock, "timeline");
        southDockHost.add(renderPreviewDock, "render-preview");
        southDockHost.add(southDockEmptyPanel, "empty");
        southDockLayout.show(southDockHost, "timeline");
        rootPanel.add(southDockHost, BorderLayout.SOUTH);

        canvas = new Canvas();
        canvas.setBackground(UiTheme.VIEWPORT_BG);
        canvas.setPreferredSize(new Dimension(width, height));
        rootPanel.add(canvas, BorderLayout.CENTER);

        rightSceneBrowserHost = new JPanel(new BorderLayout());
        rightSceneBrowserHost.setOpaque(true);
        rightSceneBrowserHost.setBackground(PANEL_BG);
        rightSceneBrowserHost.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, TAB_BORDER),
                new EmptyBorder(0, 0, 0, 0)
        ));
        rightSceneBrowserHost.setMinimumSize(new Dimension(UiTheme.RIGHT_PANEL_MIN, 96));

        rightTabs = new JTabbedPane(JTabbedPane.LEFT, JTabbedPane.SCROLL_TAB_LAYOUT);
        rightTabs.setFocusable(false);
        rightTabs.setBorder(new EmptyBorder(0, 4, 0, 0));
        rightTabs.setMinimumSize(new Dimension(UiTheme.RIGHT_PANEL_MIN, 132));
        UiTheme.installTabbedPaneTheme(rightTabs);

        rightSidebarSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, true, rightSceneBrowserHost, rightTabs);
        rightSidebarSplit.setOpaque(false);
        rightSidebarSplit.setBorder(BorderFactory.createEmptyBorder());
        rightSidebarSplit.setResizeWeight(0.34);
        rightSidebarSplit.setContinuousLayout(true);
        rightSidebarSplit.setDividerSize(3);
        rightSidebarSplit.setFocusable(false);
        rightSidebarSplit.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        g2.setColor(PANEL_BG);
                        g2.fillRect(0, 0, getWidth(), getHeight());
                        g2.setColor(TAB_BORDER);
                        int y = Math.max(1, getHeight() / 2);
                        g2.drawLine(0, y, getWidth(), y);
                        g2.dispose();
                    }
                };
            }
        });

        rightSidebar = new JPanel(new BorderLayout());
        rightSidebar.setOpaque(true);
        rightSidebar.setBackground(PANEL_BG);
        rightSidebar.setPreferredSize(new Dimension(computeRightPanelWidth(width, height), height));
        rightSidebar.setMinimumSize(new Dimension(UiTheme.RIGHT_PANEL_MIN, 0));
        rightSidebar.add(rightSidebarSplit, BorderLayout.CENTER);
        rootPanel.add(rightSidebar, BorderLayout.EAST);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                syncResponsiveLayout();
            }
        });

        contextMenu = new JPopupMenu();
        UiTheme.stylePopupMenu(contextMenu);
        contextMenuBeforeShowAction = null;
        contextMenuAfterAction = null;
        contextMenuAfterCancelAction = null;
        contextMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                // Záměrně bez další práce. Callback volám těsně před show().
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // Záměrně bez další práce. Potvrzené akce řeší vlastní callback.
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                runContextMenuCallback(contextMenuAfterCancelAction);
                canvas.requestFocusInWindow();
            }
        });
        canvas.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShowContextMenu(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                maybeShowContextMenu(e);
            }
        });

        frame.setContentPane(rootPanel);
        frame.setBackground(FRAME_BG);
        frame.pack();
        frame.setMinimumSize(new Dimension(1060, 700));
        syncResponsiveLayout();
        javax.swing.SwingUtilities.invokeLater(() ->
                rightSidebarSplit.setDividerLocation(Math.max(180, (int) Math.round(Math.max(1, height) * 0.38))));
        if (fullscreen) {
            Rectangle bounds = fullscreenDevice != null
                    ? fullscreenDevice.getDefaultConfiguration().getBounds()
                    : GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDefaultConfiguration()
                    .getBounds();
            frame.setBounds(bounds);
            frame.setVisible(true);
            if (fullscreenDevice != null && fullscreenDevice.isFullScreenSupported()) {
                try {
                    fullscreenDevice.setFullScreenWindow(frame);
                } catch (RuntimeException ignored) {
                    frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
                }
            } else {
                frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            }
        } else {
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        }

        backBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        defaultCursor = Cursor.getDefaultCursor();
        BufferedImage blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        hiddenCursor = Toolkit.getDefaultToolkit().createCustomCursor(blank, new Point(0, 0), "hidden");
        overlayFont = new Font(Font.MONOSPACED, Font.BOLD, 13);
        modeSwitchOverlayFont = new Font(Font.SANS_SERIF, Font.BOLD, 64);
        modeSwitchOverlaySubFont = new Font(Font.SANS_SERIF, Font.PLAIN, 20);
        overlayLines = new String[0];
        renderPreviewMode = false;
        renderPreviewPauseAction = null;
        renderPreviewCancelAction = null;
        cursorCaptured = false;
        smoothUpscaling = true;
        worldAxisWidgetVisible = false;
        worldAxisRightX = 1.0;
        worldAxisRightY = 0.0;
        worldAxisRightZ = 0.0;
        worldAxisUpX = 0.0;
        worldAxisUpY = 1.0;
        worldAxisUpZ = 0.0;
        worldAxisForwardX = 0.0;
        worldAxisForwardY = 0.0;
        worldAxisForwardZ = -1.0;
        timelineDockManuallyResized = false;
        timelineResizeDragging = false;
        timelineDockAttached = true;
        applySouthDockVisibilityState();
        ensureBufferStrategy();
        canvas.requestFocusInWindow();
        installTimelineDockResizer();
    }

    private void installAppIcon() {
        Image icon = loadAppIcon();
        if (icon == null) {
            return;
        }
        frame.setIconImage(icon);
        try {
            if (!Taskbar.isTaskbarSupported()) {
                return;
            }
            Taskbar taskbar = Taskbar.getTaskbar();
            if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                taskbar.setIconImage(icon);
            }
        } catch (UnsupportedOperationException | SecurityException ex) {
            // Některé platformy integraci ikony do taskbaru nepovolí.
        }
    }

    private Image loadAppIcon() {
        if (FileUtil.exists(APP_ICON_PNG_PATH)) {
            try {
                return ImageIO.read(new File(APP_ICON_PNG_PATH));
            } catch (IOException ex) {
                // Při selhání PNG zkusím níže ICO variantu.
            }
        }
        if (FileUtil.exists(APP_ICON_ICO_PATH)) {
            return Toolkit.getDefaultToolkit().getImage(APP_ICON_ICO_PATH);
        }
        return null;
    }

    /**
     * Přenese barevný buffer do okna.
     *
     * @param pixels pole pixelů ARGB o délce width * height
     */
    public void blit(int[] pixels) {
        blit(pixels, width, height);
    }

    public void blit(int[] pixels, int srcWidth, int srcHeight) {
        blit(pixels, srcWidth, srcHeight, true);
    }

    public void blitPreview(int[] pixels, int srcWidth, int srcHeight) {
        blit(pixels, srcWidth, srcHeight, false);
    }

    public void blit(int[] pixels, int srcWidth, int srcHeight, boolean drawViewportChrome) {
        if (srcWidth <= 0 || srcHeight <= 0) {
            return;
        }
        if (pixels == null || pixels.length < srcWidth * srcHeight) {
            return;
        }
        if (canvas == null || frame == null || !canvas.isDisplayable() || !frame.isDisplayable()) {
            return;
        }
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
            return;
        }

        if (backBuffer.getWidth() != srcWidth || backBuffer.getHeight() != srcHeight) {
            backBuffer = new BufferedImage(srcWidth, srcHeight, BufferedImage.TYPE_INT_ARGB);
        }
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.BYTES_COPIED,
                Math.max(0L, (long) srcWidth * (long) srcHeight * 4L));
        backBuffer.setRGB(0, 0, srcWidth, srcHeight, pixels, 0, srcWidth);

        BufferStrategy strategy = ensureBufferStrategy();
        if (strategy == null) {
            return;
        }

        do {
            do {
                Graphics g;
                try {
                    g = strategy.getDrawGraphics();
                } catch (IllegalStateException ex) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g;
                if (smoothUpscaling && (srcWidth != canvas.getWidth() || srcHeight != canvas.getHeight())) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                } else {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                    g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
                }
                g2.drawImage(backBuffer, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
                long hudStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.HUD_UI);
                if (drawViewportChrome && cursorCaptured) {
                    drawCrosshair(g2, canvas.getWidth(), canvas.getHeight());
                }
                if (drawViewportChrome) {
                    drawWorldAxisWidget(g2, canvas.getWidth(), canvas.getHeight());
                }
                if (!renderPreviewMode) {
                    drawOverlayText(g2);
                }
                drawRenderModeSwitchOverlay(g2, canvas.getWidth(), canvas.getHeight());
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.HUD_UI, hudStage);
                g2.dispose();
            } while (strategy.contentsRestored());
            try {
                long presentStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.WINDOW_PRESENT);
                strategy.show();
                RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.WINDOW_PRESENT, presentStage);
            } catch (IllegalStateException ex) {
                return;
            }
        } while (strategy.contentsLost());

        long presentStage = RuntimeInstrumentation.startStage(RuntimeInstrumentation.Stage.WINDOW_PRESENT);
        Toolkit.getDefaultToolkit().sync();
        RuntimeInstrumentation.endStage(RuntimeInstrumentation.Stage.WINDOW_PRESENT, presentStage);
    }

    /**
     * Buffer strategy vytvoří jen tehdy, když má canvas validní peer.
     * Tím zabrání pádu při startu, minimalizaci nebo zavírání okna.
     */
    private BufferStrategy ensureBufferStrategy() {
        if (canvas == null || !canvas.isDisplayable() || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
            return null;
        }
        BufferStrategy strategy = canvas.getBufferStrategy();
        if (strategy != null) {
            return strategy;
        }
        try {
            canvas.createBufferStrategy(2);
            return canvas.getBufferStrategy();
        } catch (IllegalStateException ex) {
            return null;
        }
    }

    /** @return kreslicí canvas pro připojení input listenerů */
    public Canvas getCanvas() {
        return canvas;
    }

    public boolean requestCanvasFocus() {
        if (frame == null || canvas == null || !frame.isDisplayable() || !canvas.isDisplayable()) {
            return false;
        }
        try {
            frame.toFront();
            frame.requestFocus();
            canvas.requestFocus();
            return canvas.requestFocusInWindow();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public boolean isCanvasFocusOwner() {
        return canvas != null && canvas.isFocusOwner();
    }

    public boolean isInputReady() {
        return frame != null
                && canvas != null
                && frame.isDisplayable()
                && frame.isVisible()
                && canvas.isDisplayable()
                && canvas.isShowing()
                && canvas.getWidth() > 0
                && canvas.getHeight() > 0;
    }

    public JFrame getFrame() {
        return frame;
    }

    public boolean isFullscreen() {
        return fullscreen;
    }

    /** @return aktuální šířku okna */
    public int getWidth() {
        return Math.max(1, canvas.getWidth());
    }

    /** @return aktuální výšku okna */
    public int getHeight() {
        return Math.max(1, canvas.getHeight());
    }

    /**
     * Zpracuje resize okna a znovu alokuje backBuffer.
     *
     * @param w nová šířka
     * @param h nová výška
     */
    public void resize(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        backBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        canvas.setPreferredSize(new Dimension(width, height));
        frame.pack();
        syncResponsiveLayout();
    }

    /** Uvolní AWT prostředky. */
    public void dispose() {
        closeRequested = true;
        exitFullscreenIfNeeded();
        if (frame != null) {
            frame.dispose();
        }
    }

    public boolean isCloseRequested() {
        return closeRequested || frame == null || !frame.isDisplayable();
    }

    public void requestClose() {
        closeRequested = true;
        exitFullscreenIfNeeded();
        if (frame != null) {
            frame.dispose();
        }
    }

    public void setTitle(String title) {
        frame.setTitle(title);
    }

    public Component addToolbarButton(String label, Runnable action) {
        JButton button = new JButton(label);
        styleButton(button);
        button.addActionListener(e -> {
            if (action != null) {
                action.run();
            }
            canvas.requestFocusInWindow();
        });
        toolbar.add(button);
        toolbar.revalidate();
        toolbar.repaint();
        frame.validate();
        return button;
    }

    public JPanel getToolbarPanel() {
        return toolbar;
    }

    public JPanel getTimelinePanel() {
        return timelineDock;
    }

    public int getTimelineDockHeight() {
        return timelineDockHeight;
    }

    public void setTimelineDockHeight(int height) {
        applyTimelineDockHeight(height);
    }

    public JPanel createRightTab(String title) {
        return createRightTab(title, title, (Icon) null);
    }

    public JPanel createRightTab(String key, String title, String iconName) {
        return createRightTab(key, title, createVectorIcon(iconName, 16));
    }

    public JPanel createRightTab(String key, String title, Icon icon) {
        String safeKey = key == null || key.isBlank() ? title : key;
        JPanel existing = rightTabContents.get(safeKey);
        if (existing != null) {
            return existing;
        }

        JPanel content = new RightTabContentPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(PANEL_BG);
        content.setBorder(new EmptyBorder(10, 12, 10, 10));
        content.setMinimumSize(new Dimension(0, 0));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 0, PANEL_BG));
        scroll.setViewportBorder(new EmptyBorder(0, 2, 0, 0));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setMinimumSize(new Dimension(0, 0));
        UiTheme.styleScrollPane(scroll, PANEL_BG);
        String visibleTitle = icon != null ? "" : title;
        rightTabs.addTab(visibleTitle, icon, scroll, title);
        int tabIndex = rightTabs.getTabCount() - 1;
        rightTabs.setToolTipTextAt(tabIndex, title);
        rightTabContents.put(safeKey, content);
        rightTabIndices.put(safeKey, tabIndex);
        rightTabScrollPanes.put(safeKey, scroll);
        return content;
    }

    public void setRightSidebarSceneBrowser(Component content) {
        rightSceneBrowserHost.removeAll();
        if (content != null) {
            rightSceneBrowserHost.add(content, BorderLayout.CENTER);
        }
        rightSceneBrowserHost.revalidate();
        rightSceneBrowserHost.repaint();
    }

    public Point captureRightTabViewPosition(String key) {
        JScrollPane scroll = rightTabScrollPanes.get(key);
        return scroll == null ? null : scroll.getViewport().getViewPosition();
    }

    public void restoreRightTabViewPosition(String key, Point position) {
        if (position == null) {
            return;
        }
        JScrollPane scroll = rightTabScrollPanes.get(key);
        if (scroll == null) {
            return;
        }
        Point target = new Point(Math.max(0, position.x), Math.max(0, position.y));
        javax.swing.SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(target));
    }

    public void selectRightTab(String title) {
        Integer idx = rightTabIndices.get(title);
        if (idx != null && idx >= 0 && idx < rightTabs.getTabCount()) {
            rightTabs.setSelectedIndex(idx);
            return;
        }
        for (int i = 0; i < rightTabs.getTabCount(); i++) {
            String t = rightTabs.getTitleAt(i);
            String tip = rightTabs.getToolTipTextAt(i);
            if (title.equals(t) || title.equals(tip)) {
                rightTabs.setSelectedIndex(i);
                return;
            }
        }
    }

    public JMenuItem addContextMenuItem(String label, Runnable action) {
        JMenuItem item = new JMenuItem(label);
        item.addActionListener(e -> {
            try {
                if (action != null) {
                    action.run();
                }
            } finally {
                runContextMenuCallback(contextMenuAfterAction);
                canvas.requestFocusInWindow();
            }
        });
        UiTheme.styleMenuItem(item);
        contextMenu.add(item);
        UiTheme.stylePopupMenu(contextMenu);
        return item;
    }

    public void clearContextMenuItems() {
        contextMenu.removeAll();
    }

    public void addContextMenuSeparator() {
        contextMenu.addSeparator();
        UiTheme.stylePopupMenu(contextMenu);
    }

    public void setContextMenuCallbacks(Runnable beforeShow, Runnable afterAction, Runnable afterCancel) {
        contextMenuBeforeShowAction = beforeShow;
        contextMenuAfterAction = afterAction;
        contextMenuAfterCancelAction = afterCancel;
    }

    public void setCursorCaptured(boolean captured) {
        cursorCaptured = captured;
        canvas.setCursor(captured ? hiddenCursor : defaultCursor);
    }

    public void setSmoothUpscaling(boolean smoothUpscaling) {
        this.smoothUpscaling = smoothUpscaling;
    }

    public void setOverlayText(String[] lines) {
        if (lines == null || lines.length == 0) {
            overlayLines = new String[0];
            return;
        }
        overlayLines = lines.clone();
    }

    public void setRenderPreviewMode(boolean active) {
        if (renderPreviewMode == active) {
            return;
        }
        renderPreviewMode = active;
        toolbar.setVisible(!active);
        rightSidebar.setVisible(!active);
        if (!active) {
            updateRenderPreviewDock(null);
        }
        applySouthDockVisibilityState();
        syncResponsiveLayout();
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    public void setTimelineDockAttached(boolean attached) {
        timelineDockAttached = attached;
        applySouthDockVisibilityState();
        syncResponsiveLayout();
        rootPanel.revalidate();
        rootPanel.repaint();
    }

    public void setRenderPreviewState(OutputRenderController.PreviewState state) {
        updateRenderPreviewDock(state);
    }

    public void setRenderPreviewActions(Runnable pauseAction, Runnable cancelAction) {
        renderPreviewPauseAction = pauseAction;
        renderPreviewCancelAction = cancelAction;
    }

    private void applySouthDockVisibilityState() {
        if (renderPreviewMode) {
            southDockHost.setVisible(true);
            southDockLayout.show(southDockHost, "render-preview");
            timelineDock.setVisible(false);
            renderPreviewDock.setVisible(true);
            return;
        }
        if (timelineDockAttached) {
            southDockHost.setVisible(true);
            southDockLayout.show(southDockHost, "timeline");
            timelineDock.setVisible(true);
            renderPreviewDock.setVisible(false);
            return;
        }
        southDockLayout.show(southDockHost, "empty");
        southDockHost.setVisible(false);
        timelineDock.setVisible(false);
        renderPreviewDock.setVisible(false);
    }

    public void setWorldAxisWidgetVisible(boolean visible) {
        worldAxisWidgetVisible = visible;
    }

    public void setWorldAxisWidgetVectors(
            double rightX,
            double rightY,
            double rightZ,
            double upX,
            double upY,
            double upZ,
            double forwardX,
            double forwardY,
            double forwardZ) {
        worldAxisRightX = rightX;
        worldAxisRightY = rightY;
        worldAxisRightZ = rightZ;
        worldAxisUpX = upX;
        worldAxisUpY = upY;
        worldAxisUpZ = upZ;
        worldAxisForwardX = forwardX;
        worldAxisForwardY = forwardY;
        worldAxisForwardZ = forwardZ;
    }

    AxisWidgetTarget pickWorldAxisWidgetTarget(int canvasX, int canvasY) {
        WorldAxisWidgetGeometry geometry = computeWorldAxisWidgetGeometry(
                canvas != null ? canvas.getWidth() : width,
                canvas != null ? canvas.getHeight() : height);
        if (geometry == null || geometry.handles == null) {
            return AxisWidgetTarget.NONE;
        }
        AxisWidgetHandle best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (AxisWidgetHandle handle : geometry.handles) {
            if (handle == null || handle.target == AxisWidgetTarget.NONE) {
                continue;
            }
            double dx = canvasX - handle.centerX;
            double dy = canvasY - handle.centerY;
            double radius = handle.radius + AXIS_WIDGET_HIT_PADDING;
            double distSq = dx * dx + dy * dy;
            if (distSq > radius * radius) {
                continue;
            }
            double score = distSq - handle.depth * 6.0;
            if (score < bestScore) {
                bestScore = score;
                best = handle;
            }
        }
        return best == null ? AxisWidgetTarget.NONE : best.target;
    }

    public void setRenderModeSwitchOverlay(String label, double alpha, boolean active) {
        renderModeSwitchOverlayLabel = label == null ? "" : label.trim();
        renderModeSwitchOverlayAlpha = Math.max(0.0, Math.min(1.0, alpha));
        renderModeSwitchOverlayActive = active && renderModeSwitchOverlayAlpha > 0.0;
    }

    public void presentRenderModeSwitchOverlayNow(String label, double alpha) {
        setRenderModeSwitchOverlay(label, alpha, true);
        if (canvas == null || frame == null || !canvas.isDisplayable() || !frame.isDisplayable()) {
            return;
        }
        if (canvas.getWidth() <= 0 || canvas.getHeight() <= 0) {
            return;
        }
        BufferStrategy strategy = ensureBufferStrategy();
        if (strategy == null) {
            return;
        }

        do {
            do {
                Graphics g;
                try {
                    g = strategy.getDrawGraphics();
                } catch (IllegalStateException ex) {
                    return;
                }
                Graphics2D g2 = (Graphics2D) g;
                if (backBuffer != null && backBuffer.getWidth() > 0 && backBuffer.getHeight() > 0) {
                    g2.drawImage(backBuffer, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
                } else {
                    g2.setColor(Color.BLACK);
                    g2.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());
                }
                drawRenderModeSwitchOverlay(g2, canvas.getWidth(), canvas.getHeight());
                g2.dispose();
            } while (strategy.contentsRestored());
            try {
                strategy.show();
            } catch (IllegalStateException ex) {
                return;
            }
        } while (strategy.contentsLost());

        Toolkit.getDefaultToolkit().sync();
    }

    public boolean isCursorCaptured() {
        return cursorCaptured;
    }

    public Point getCanvasCenterOnScreen() {
        try {
            Point p = canvas.getLocationOnScreen();
            return new Point(p.x + canvas.getWidth() / 2, p.y + canvas.getHeight() / 2);
        } catch (IllegalComponentStateException ex) {
            return null;
        }
    }

    public Point getCapturePointOnScreen() {
        Point canvasCenter = getCanvasCenterOnScreen();
        if (canvasCenter != null) {
            return canvasCenter;
        }
        try {
            Point p = frame.getLocationOnScreen();
            return new Point(p.x + frame.getWidth() / 2, p.y + frame.getHeight() / 2);
        } catch (IllegalComponentStateException ex) {
            return null;
        }
    }

    public boolean isViewportFocusActive() {
        return frame.isFocused() && canvas.isDisplayable() && canvas.isShowing();
    }

    private void drawCrosshair(Graphics g, int w, int h) {
        int cx = w / 2;
        int cy = h / 2;
        g.setColor(new Color(255, 255, 255, 220));
        g.drawLine(cx - 10, cy, cx - 2, cy);
        g.drawLine(cx + 2, cy, cx + 10, cy);
        g.drawLine(cx, cy - 10, cx, cy - 2);
        g.drawLine(cx, cy + 2, cx, cy + 10);
        g.drawRect(cx - 1, cy - 1, 2, 2);
    }

    private void drawWorldAxisWidget(Graphics2D g2, int w, int h) {
        WorldAxisWidgetGeometry geometry = computeWorldAxisWidgetGeometry(w, h);
        if (geometry == null) {
            return;
        }

        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Object oldTextAa = g2.getRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING);
        Object oldRender = g2.getRenderingHint(RenderingHints.KEY_RENDERING);
        Object oldStrokeControl = g2.getRenderingHint(RenderingHints.KEY_STROKE_CONTROL);
        Stroke oldStroke = g2.getStroke();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        AxisWidgetHandle[] positiveHandles = collectPrimaryAxisHandles(geometry.handles);
        java.util.Arrays.sort(positiveHandles, (a, b) -> Double.compare(a.depth, b.depth));
        for (AxisWidgetHandle positive : positiveHandles) {
            drawAxisLine(g2, geometry.centerX, geometry.centerY, positive, withAlpha(positive.color, positive.lineAlpha));
        }

        for (AxisWidgetHandle handle : geometry.sortedHandles) {
            if (handle == null) {
                continue;
            }
            drawAxisHandle(g2, handle);
        }
        g2.setStroke(oldStroke);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, oldTextAa);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, oldRender);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, oldStrokeControl);
    }

    private void drawAxisLine(Graphics2D g2, int x0, int y0, AxisWidgetHandle handle, Color color) {
        if (handle == null) {
            return;
        }
        double axisDx = handle.centerX - x0;
        double axisDy = handle.centerY - y0;
        double axisLen = Math.max(1.0, Math.hypot(axisDx, axisDy));
        double visibleLen = Math.max(0.0, axisLen - handle.radius);
        double x1 = x0 + axisDx * visibleLen / axisLen;
        double y1 = y0 + axisDy * visibleLen / axisLen;
        Line2D.Double line = new Line2D.Double(x0, y0, x1, y1);
        Stroke oldStroke = g2.getStroke();
        float coreWidth = (float) Math.max(2.0, handle.radius * 0.21);
        g2.setStroke(new BasicStroke(coreWidth + 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(withAlpha(handle.color, Math.max(34, handle.lineAlpha / 3)));
        g2.draw(line);
        g2.setStroke(new BasicStroke(coreWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.setColor(color);
        g2.draw(line);
        g2.setStroke(oldStroke);
    }

    private void drawAxisHandle(Graphics2D g2, AxisWidgetHandle handle) {
        if (handle == null) {
            return;
        }
        double diameter = handle.radius * 2.0;
        double drawX = handle.centerX - handle.radius;
        double drawY = handle.centerY - handle.radius;
        Ellipse2D.Double shape = new Ellipse2D.Double(drawX, drawY, diameter, diameter);
        if (handle.primary) {
            double outerHaloRadius = handle.radius * (handle.frontFacing ? 1.92 : 1.62);
            Ellipse2D.Double outerHalo = new Ellipse2D.Double(
                    handle.centerX - outerHaloRadius,
                    handle.centerY - outerHaloRadius,
                    outerHaloRadius * 2.0,
                    outerHaloRadius * 2.0);
            g2.setColor(withAlpha(handle.color, handle.frontFacing ? 54 : 34));
            g2.fill(outerHalo);
        }
        g2.setColor(handle.fill);
        g2.fill(shape);
        g2.setColor(handle.outline);
        g2.draw(shape);
        if (handle.label != null && !handle.label.isBlank()) {
            Font oldFont = g2.getFont();
            float fontSize = (float) Math.max(handle.primary ? 10.2 : 8.6, handle.radius * (handle.primary ? 0.84 : 0.78));
            g2.setFont(oldFont.deriveFont(Font.BOLD, fontSize));
            FontMetrics metrics = g2.getFontMetrics();
            int textX = (int) Math.round(handle.centerX - metrics.stringWidth(handle.label) / 2.0);
            int textY = (int) Math.round(handle.centerY + Math.max(3, metrics.getAscent() / 2.0 - 1.2));
            g2.setColor(new Color(244, 247, 251, handle.labelAlpha));
            g2.drawString(handle.label, textX, textY);
            g2.setFont(oldFont);
        }
    }

    private WorldAxisWidgetGeometry computeWorldAxisWidgetGeometry(int w, int h) {
        if (!worldAxisWidgetVisible || w <= 0 || h <= 0) {
            return null;
        }

        AxisWidgetAnchor anchor = resolveAxisWidgetAnchor(w, h);
        AxisWidgetBasis basis = resolveAxisWidgetBasis();

        WorldAxisWidgetGeometry geometry = new WorldAxisWidgetGeometry();
        geometry.centerX = anchor.centerX;
        geometry.centerY = anchor.centerY;
        geometry.handles = buildAxisHandles(basis, anchor.centerX, anchor.centerY);
        geometry.sortedHandles = geometry.handles.clone();
        java.util.Arrays.sort(geometry.sortedHandles, (a, b) -> Double.compare(a.depth, b.depth));
        return geometry;
    }

    private AxisWidgetAnchor resolveAxisWidgetAnchor(int w, int h) {
        int half = AXIS_WIDGET_BOX_SIZE / 2;
        int minX = AXIS_WIDGET_MARGIN + half;
        int maxX = Math.max(minX, w - AXIS_WIDGET_MARGIN - half);
        int minY = AXIS_WIDGET_MARGIN + half;
        int maxY = Math.max(minY, h - AXIS_WIDGET_MARGIN - half);
        AxisWidgetAnchor anchor = new AxisWidgetAnchor();
        anchor.centerX = Math.max(minX, Math.min(maxX, AXIS_WIDGET_ANCHOR_X));
        anchor.centerY = Math.max(minY, Math.min(maxY, h - AXIS_WIDGET_ANCHOR_Y));
        return anchor;
    }

    private AxisWidgetBasis resolveAxisWidgetBasis() {
        Vec3 right = new Vec3(worldAxisRightX, worldAxisRightY, worldAxisRightZ).normalize();
        Vec3 suppliedUp = new Vec3(worldAxisUpX, worldAxisUpY, worldAxisUpZ).normalize();
        if (suppliedUp.lengthSquared() < 1e-8) {
            suppliedUp = new Vec3(0.0, 1.0, 0.0);
        }
        Vec3 viewDirection = new Vec3(-worldAxisForwardX, -worldAxisForwardY, -worldAxisForwardZ).normalize();
        if (viewDirection.lengthSquared() < 1e-8) {
            viewDirection = new Vec3(0.0, 0.0, 1.0);
        }

        right = right.sub(viewDirection.mul(right.dot(viewDirection))).normalize();
        if (right.lengthSquared() < 1e-8) {
            right = suppliedUp.cross(viewDirection).normalize();
            if (right.lengthSquared() < 1e-8) {
                right = new Vec3(1.0, 0.0, 0.0);
            }
        }

        Vec3 up = suppliedUp.sub(viewDirection.mul(suppliedUp.dot(viewDirection))).normalize();
        if (up.lengthSquared() < 1e-8) {
            up = viewDirection.cross(right).normalize();
        } else {
            Vec3 correctedRight = up.cross(viewDirection).normalize();
            if (correctedRight.lengthSquared() >= 1e-8) {
                right = correctedRight;
            }
        }
        if (up.lengthSquared() < 1e-8) {
            up = new Vec3(0.0, 1.0, 0.0);
        }

        AxisWidgetBasis basis = new AxisWidgetBasis();
        basis.right = right;
        basis.up = up;
        basis.viewDirection = viewDirection;
        return basis;
    }

    private AxisWidgetHandle[] buildAxisHandles(AxisWidgetBasis basis, int centerX, int centerY) {
        AxisWidgetHandle[] handles = new AxisWidgetHandle[AXIS_WIDGET_SPECS.length];
        for (int i = 0; i < AXIS_WIDGET_SPECS.length; i++) {
            AxisWidgetSpec spec = AXIS_WIDGET_SPECS[i];
            handles[i] = buildAxisHandle(
                    spec.target,
                    spec.label,
                    spec.axis,
                    basis.right,
                    basis.up,
                    basis.viewDirection,
                    centerX,
                    centerY,
                    spec.primary,
                    spec.color);
        }
        return handles;
    }

    private AxisWidgetHandle[] collectPrimaryAxisHandles(AxisWidgetHandle[] handles) {
        int count = 0;
        for (AxisWidgetHandle handle : handles) {
            if (handle != null && handle.primary) {
                count++;
            }
        }
        AxisWidgetHandle[] result = new AxisWidgetHandle[count];
        int index = 0;
        for (AxisWidgetHandle handle : handles) {
            if (handle != null && handle.primary) {
                result[index++] = handle;
            }
        }
        return result;
    }

    private AxisWidgetHandle buildAxisHandle(
            AxisWidgetTarget target,
            String label,
            Vec3 worldAxis,
            Vec3 cameraRight,
            Vec3 cameraUp,
            Vec3 cameraForward,
            int ox,
            int oy,
            boolean primary,
            Color baseColor) {
        double localX = worldAxis.dot(cameraRight);
        double localY = worldAxis.dot(cameraUp);
        double localZ = worldAxis.dot(cameraForward);
        double depthDistance = Math.max(AXIS_WIDGET_DEPTH_FLOOR, AXIS_WIDGET_CAMERA_DISTANCE - localZ * AXIS_WIDGET_AXIS_LENGTH);
        double perspectiveScale = AXIS_WIDGET_FOCAL_LENGTH / depthDistance;
        double frontness = Math.max(0.0, Math.min(1.0, (localZ + 1.0) * 0.5));
        double alphaFactor = 0.50 + frontness * 0.50;
        double radiusFactor = 0.96 + frontness * 0.08;
        AxisWidgetHandle handle = new AxisWidgetHandle();
        handle.target = target;
        handle.label = label;
        handle.primary = primary;
        handle.frontFacing = localZ >= 0.0;
        handle.centerX = ox + localX * AXIS_WIDGET_AXIS_LENGTH * perspectiveScale * AXIS_WIDGET_SCREEN_SCALE;
        handle.centerY = oy - localY * AXIS_WIDGET_AXIS_LENGTH * perspectiveScale * AXIS_WIDGET_SCREEN_SCALE;
        handle.depth = localZ;
        handle.radius = primary
                ? 10.4 * radiusFactor
                : 7.4 * radiusFactor;
        handle.color = baseColor;
        handle.fill = primary
                ? withAlpha(baseColor, (int) Math.round(250 * alphaFactor))
                : withAlpha(baseColor, 51);
        handle.outline = primary
                ? withAlpha(blend(baseColor, Color.BLACK, 0.12), 255)
                : withAlpha(baseColor, (int) Math.round(246 * (0.44 + frontness * 0.26)));
        handle.lineAlpha = primary ? (int) Math.round(238 * (0.58 + frontness * 0.42)) : 0;
        handle.labelAlpha = primary ? (int) Math.round(224 + frontness * 31) : 0;
        return handle;
    }

    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), Math.max(0, Math.min(255, alpha)));
    }

    private static Color blend(Color a, Color b, double t) {
        double clamped = Math.max(0.0, Math.min(1.0, t));
        int r = (int) Math.round(a.getRed() * (1.0 - clamped) + b.getRed() * clamped);
        int g = (int) Math.round(a.getGreen() * (1.0 - clamped) + b.getGreen() * clamped);
        int bl = (int) Math.round(a.getBlue() * (1.0 - clamped) + b.getBlue() * clamped);
        int alpha = (int) Math.round(a.getAlpha() * (1.0 - clamped) + b.getAlpha() * clamped);
        return new Color(r, g, bl, alpha);
    }

    private void drawOverlayText(Graphics2D g2) {
        String[] lines = overlayLines;
        if (lines == null || lines.length == 0) {
            return;
        }

        g2.setFont(overlayFont);
        g2.setComposite(AlphaComposite.SrcOver);
        g2.setPaintMode();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

        FontMetrics metrics = g2.getFontMetrics(overlayFont);
        int x = 14;
        int y = 16;
        int rowH = Math.max(16, metrics.getHeight() + 2);
        int maxRows = Math.max(4, (canvas.getHeight() - y - 10) / rowH);
        int rowCount = Math.min(lines.length, maxRows);
        if (rowCount <= 0) {
            return;
        }

        int maxKeyW = 0;
        for (int i = 0; i < rowCount; i++) {
            String key = extractOverlayKey(lines[i]);
            maxKeyW = Math.max(maxKeyW, metrics.stringWidth(key));
        }
        int railX = x;
        int keyX = x + 8;
        int colGap = 10;
        int keyColW = Math.max(46, maxKeyW + 4);
        int valueX = keyX + keyColW + colGap;
        int valueColW = Math.max(120, canvas.getWidth() - valueX - 14);

        int top = y - 2;
        int bottom = y + rowCount * rowH - 5;
        g2.setColor(new Color(110, 156, 198, 185));
        g2.fillRect(railX - 1, top, 2, Math.max(10, bottom - top));

        for (int i = 0; i < rowCount; i++) {
            String raw = lines[i] == null ? "" : lines[i];
            String key = extractOverlayKey(raw);
            String value = extractOverlayValue(raw);

            int baseY = y + i * rowH + metrics.getAscent();
            Color accent = resolveLineColor(key);

            g2.setColor(accent);
            g2.drawString(key, keyX, baseY);

            String clippedValue = clipTextToWidth(value, metrics, valueColW);
            g2.setColor(new Color(220, 231, 244, 238));
            g2.drawString(clippedValue, valueX, baseY);
        }
    }

    private void drawRenderModeSwitchOverlay(Graphics2D g2, int w, int h) {
        if (!renderModeSwitchOverlayActive || renderModeSwitchOverlayAlpha <= 0.0 || w <= 0 || h <= 0) {
            return;
        }
        double alpha = Math.max(0.0, Math.min(1.0, renderModeSwitchOverlayAlpha));
        String label = renderModeSwitchOverlayLabel == null || renderModeSwitchOverlayLabel.isBlank()
                ? "RENDER"
                : renderModeSwitchOverlayLabel;

        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) alpha));
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, w, h);

        int titleSize = Math.max(42, Math.min(96, (int) Math.round(Math.min(w, h) * 0.13)));
        Font titleFont = modeSwitchOverlayFont.deriveFont((float) titleSize);
        g2.setFont(titleFont);
        FontMetrics titleMetrics = g2.getFontMetrics();
        int titleX = (w - titleMetrics.stringWidth(label)) / 2;
        int titleY = (h / 2) - Math.max(8, titleSize / 10);

        g2.setColor(new Color(34, 0, 56));
        g2.drawString(label, titleX + 4, titleY + 4);
        g2.setColor(new Color(187, 72, 255));
        g2.drawString(label, titleX, titleY);

        String sub = "Načítám další render režim...";
        int subSize = Math.max(16, Math.min(30, (int) Math.round(titleSize * 0.36)));
        Font subFont = modeSwitchOverlaySubFont.deriveFont((float) subSize);
        g2.setFont(subFont);
        FontMetrics subMetrics = g2.getFontMetrics();
        int subX = (w - subMetrics.stringWidth(sub)) / 2;
        int subY = titleY + Math.max(36, (int) Math.round(titleSize * 0.9));
        g2.setColor(new Color(228, 221, 236));
        g2.drawString(sub, subX, subY);
    }

    private String extractOverlayKey(String line) {
        if (line == null) {
            return "";
        }
        int idx = line.indexOf(':');
        if (idx > 0) {
            return line.substring(0, idx).trim();
        }
        return line.trim();
    }

    private String extractOverlayValue(String line) {
        if (line == null) {
            return "";
        }
        int idx = line.indexOf(':');
        if (idx >= 0 && idx + 1 < line.length()) {
            return line.substring(idx + 1).trim();
        }
        return "";
    }

    private Color resolveLineColor(String key) {
        if (key == null) {
            return OVERLAY_FG;
        }
        if ("FBK".equals(key) || "AUT".equals(key)) {
            return new Color(255, 188, 108);
        }
        if ("FPS".equals(key) || "FT".equals(key) || "SPP".equals(key)) {
            return new Color(147, 228, 171);
        }
        if ("CAM".equals(key) || "POS".equals(key)) {
            return new Color(138, 198, 255);
        }
        if ("GEO".equals(key) || "RT".equals(key) || "PT".equals(key) || "RST".equals(key)) {
            return new Color(205, 222, 238);
        }
        return OVERLAY_FG;
    }

    private String clipTextToWidth(String value, FontMetrics metrics, int maxWidth) {
        if (value == null) {
            return "";
        }
        if (metrics.stringWidth(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "...";
        int keep = Math.max(0, value.length() - 1);
        while (keep > 0) {
            String candidate = value.substring(0, keep) + ellipsis;
            if (metrics.stringWidth(candidate) <= maxWidth) {
                return candidate;
            }
            keep--;
        }
        return ellipsis;
    }

    private void maybeShowContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        if (contextMenu.getComponentCount() == 0) {
            return;
        }
        runContextMenuCallback(contextMenuBeforeShowAction);
        UiTheme.stylePopupMenu(contextMenu);
        contextMenu.show(canvas, e.getX(), e.getY());
        e.consume();
    }

    private void runContextMenuCallback(Runnable callback) {
        if (callback != null) {
            callback.run();
        }
    }

    private JPanel createRenderPreviewDock() {
        JPanel dock = new JPanel(new BorderLayout(12, 0));
        dock.setOpaque(true);
        dock.setBackground(RENDER_PREVIEW_BAR_BG);
        dock.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, TAB_BORDER),
                new EmptyBorder(9, 14, 9, 14)));
        dock.setPreferredSize(new Dimension(width, RENDER_PREVIEW_BAR_HEIGHT));
        dock.setMinimumSize(new Dimension(0, RENDER_PREVIEW_BAR_HEIGHT));

        JPanel summary = new JPanel();
        summary.setOpaque(false);
        summary.setLayout(new BoxLayout(summary, BoxLayout.Y_AXIS));

        renderPreviewHeadlineLabel = new JLabel("Připravuji render", SwingConstants.LEFT);
        renderPreviewHeadlineLabel.setForeground(RENDER_PREVIEW_TEXT);
        renderPreviewHeadlineLabel.setFont(renderPreviewHeadlineLabel.getFont().deriveFont(Font.BOLD, 13f));
        renderPreviewHeadlineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(renderPreviewHeadlineLabel);
        summary.add(Box.createVerticalStrut(3));

        renderPreviewMetricsLabel = new JLabel("Běží 0.0s · ETA 0.0s", SwingConstants.LEFT);
        renderPreviewMetricsLabel.setForeground(RENDER_PREVIEW_TEXT_MUTED);
        renderPreviewMetricsLabel.setFont(renderPreviewMetricsLabel.getFont().deriveFont(Font.PLAIN, 11.5f));
        renderPreviewMetricsLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        summary.add(renderPreviewMetricsLabel);
        summary.add(Box.createVerticalStrut(5));

        renderPreviewProgressBar = new JProgressBar(0, 1000);
        renderPreviewProgressBar.setAlignmentX(Component.LEFT_ALIGNMENT);
        renderPreviewProgressBar.setBorderPainted(false);
        renderPreviewProgressBar.setStringPainted(false);
        renderPreviewProgressBar.setOpaque(true);
        renderPreviewProgressBar.setBackground(RENDER_PREVIEW_PROGRESS_BG);
        renderPreviewProgressBar.setForeground(RENDER_PREVIEW_PROGRESS_FILL);
        renderPreviewProgressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
        renderPreviewProgressBar.setPreferredSize(new Dimension(240, 6));
        renderPreviewProgressBar.setMinimumSize(new Dimension(32, 6));
        summary.add(renderPreviewProgressBar);

        dock.add(summary, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        renderPreviewPauseButton = new JButton("Pozastavit");
        engine.util.UiBuilder.stylePrimaryButton(renderPreviewPauseButton);
        renderPreviewPauseButton.addActionListener(e -> runRenderPreviewAction(renderPreviewPauseAction));
        actions.add(renderPreviewPauseButton);

        renderPreviewCancelButton = new JButton("Ukončit render");
        engine.util.UiBuilder.styleGhostButton(renderPreviewCancelButton);
        renderPreviewCancelButton.addActionListener(e -> runRenderPreviewAction(renderPreviewCancelAction));
        actions.add(renderPreviewCancelButton);
        dock.add(actions, BorderLayout.EAST);
        updateRenderPreviewDock(null);
        return dock;
    }

    private void updateRenderPreviewDock(OutputRenderController.PreviewState state) {
        if (renderPreviewHeadlineLabel == null
                || renderPreviewMetricsLabel == null
                || renderPreviewProgressBar == null
                || renderPreviewPauseButton == null
                || renderPreviewCancelButton == null) {
            return;
        }
        String headline = state == null
                ? "Připravuji render"
                : clipTextForLabel(
                        safeLabel(state.headline) + " · " + safeLabel(state.progressText),
                        132);
        String metrics = state == null
                ? "Běží 0.0s · ETA 0.0s"
                : clipTextForLabel(safeLabel(state.metricsText), 176);
        renderPreviewHeadlineLabel.setText(headline);
        renderPreviewMetricsLabel.setText(metrics);
        renderPreviewProgressBar.setValue(state == null
                ? 0
                : (int) Math.round(Math.max(0.0, Math.min(1.0, state.progressFraction)) * 1000.0));
        boolean interactive = state != null && state.cancellable;
        renderPreviewPauseButton.setVisible(state != null && state.pausable);
        renderPreviewPauseButton.setEnabled(interactive);
        renderPreviewPauseButton.setText(state != null && state.paused ? "Pokračovat" : "Pozastavit");
        renderPreviewCancelButton.setEnabled(interactive);
    }

    private void runRenderPreviewAction(Runnable action) {
        if (action != null) {
            action.run();
        }
        if (canvas != null) {
            canvas.requestFocusInWindow();
        }
    }

    private static String safeLabel(String value) {
        return value == null ? "" : value;
    }

    private static String clipTextForLabel(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value == null ? "" : value;
        }
        return value.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private GraphicsDevice resolveFullscreenDevice() {
        try {
            return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private void exitFullscreenIfNeeded() {
        if (fullscreenDevice == null) {
            return;
        }
        try {
            if (fullscreenDevice.getFullScreenWindow() == frame) {
                fullscreenDevice.setFullScreenWindow(null);
            }
        } catch (RuntimeException ignored) {
            // Platformní fullscreen fallback nechám bez dalšího zásahu doznít.
        }
    }

    private void styleButton(JButton button) {
        button.setMargin(new Insets(4, 8, 4, 8));
        engine.util.UiBuilder.styleSecondaryButton(button);
    }

    public Icon createVectorIcon(String name, int size) {
        String id = name == null ? "" : name.trim().toLowerCase();
        int safeSize = Math.max(12, size);
        return new VectorIcon(id, safeSize);
    }

    private static final class VectorIcon implements Icon {
        private final String iconName;
        private final int size;

        private VectorIcon(String iconName, int size) {
            this.iconName = iconName;
            this.size = size;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(ICON_FG);
            g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int s = size;
            int ox = x;
            int oy = y;

            if ("render".equals(iconName)) {
                g2.drawOval(ox + 2, oy + 4, s - 4, s - 8);
                g2.fillOval(ox + s / 2 - 2, oy + s / 2 - 2, 4, 4);
            } else if ("scene".equals(iconName)) {
                Path2D p = new Path2D.Double();
                p.moveTo(ox + 3, oy + s - 5);
                p.lineTo(ox + s / 2.0, oy + 3);
                p.lineTo(ox + s - 3, oy + s - 5);
                p.closePath();
                g2.draw(p);
                g2.drawLine(ox + s / 2, oy + 3, ox + s / 2, oy + s - 5);
            } else if ("world".equals(iconName) || "sun".equals(iconName)) {
                int centerX = ox + s / 2;
                int centerY = oy + s / 2;
                int radius = Math.max(3, s / 5);
                g2.drawOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
                int rayInner = radius + 2;
                int rayOuter = Math.max(rayInner + 2, s / 2 - 1);
                for (int i = 0; i < 8; i++) {
                    double angle = Math.PI * 0.25 * i;
                    int x1 = centerX + (int) Math.round(Math.cos(angle) * rayInner);
                    int y1 = centerY + (int) Math.round(Math.sin(angle) * rayInner);
                    int x2 = centerX + (int) Math.round(Math.cos(angle) * rayOuter);
                    int y2 = centerY + (int) Math.round(Math.sin(angle) * rayOuter);
                    g2.drawLine(x1, y1, x2, y2);
                }
            } else if ("controls".equals(iconName)) {
                g2.drawRoundRect(ox + 2, oy + 2, s - 4, s - 4, 5, 5);
                g2.drawLine(ox + s / 2, oy + 4, ox + s / 2, oy + s - 4);
                g2.drawLine(ox + 4, oy + s / 2, ox + s - 4, oy + s / 2);
            } else if ("object".equals(iconName) || "item".equals(iconName)) {
                g2.drawLine(ox + 3, oy + s - 3, ox + s - 5, oy + 5);
                g2.drawLine(ox + 3, oy + s - 3, ox + s - 3, oy + s - 3);
                g2.drawLine(ox + 3, oy + s - 3, ox + 3, oy + 3);
            } else if ("output".equals(iconName)) {
                g2.drawRoundRect(ox + 2, oy + 3, s - 7, s - 6, 3, 3);
                g2.fillOval(ox + s - 7, oy + s / 2 - 2, 4, 4);
                g2.drawLine(ox + s - 8, oy + s / 2, ox + s - 3, oy + s / 2);
            } else {
                g2.drawOval(ox + 3, oy + 3, s - 6, s - 6);
            }
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return size;
        }

        @Override
        public int getIconHeight() {
            return size;
        }
    }

    private void installTimelineDockResizer() {
        final int grip = 10;
        timelineDock.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e == null || e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                if (e.getY() > grip) {
                    return;
                }
                timelineResizeDragging = true;
                timelineResizeStartScreenY = e.getYOnScreen();
                timelineResizeStartHeight = timelineDockHeight;
                timelineDock.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                timelineResizeDragging = false;
                timelineDock.setCursor(Cursor.getDefaultCursor());
            }
        });

        timelineDock.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                if (timelineResizeDragging) {
                    return;
                }
                if (e != null && e.getY() <= grip) {
                    timelineDock.setCursor(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR));
                } else {
                    timelineDock.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!timelineResizeDragging || e == null) {
                    return;
                }
                int delta = timelineResizeStartScreenY - e.getYOnScreen();
                timelineDockManuallyResized = true;
                applyTimelineDockHeight(timelineResizeStartHeight + delta);
            }
        });
    }

    private void applyTimelineDockHeight(int targetHeight) {
        int frameHeight = currentFrameHeight();
        int max = Math.max(computeDefaultTimelineDockHeight(frameHeight), frameHeight - 240);
        int min = UiTheme.BOTTOM_DOCK_MIN_HEIGHT;
        if (timelineDock != null) {
            Dimension dockMin = timelineDock.getMinimumSize();
            if (dockMin != null) {
                min = Math.max(min, dockMin.height);
            }
        }
        int clamped = Math.max(min, Math.min(max, targetHeight));
        int currentWidth = currentFrameWidth();
        Dimension preferredSize = timelineDock.getPreferredSize();
        if (clamped == timelineDockHeight
                && preferredSize != null
                && preferredSize.width == currentWidth
                && preferredSize.height == timelineDockHeight) {
            return;
        }
        timelineDockHeight = clamped;
        timelineDock.setPreferredSize(new Dimension(currentWidth, timelineDockHeight));
        if (!renderPreviewMode) {
            southDockHost.setPreferredSize(new Dimension(currentWidth, timelineDockHeight));
        }
        timelineDock.revalidate();
        if (frame != null) {
            frame.revalidate();
        }
    }

    private void updateRightTabsPreferredWidth() {
        if (rightTabs == null || rightSidebar == null) {
            return;
        }
        int frameWidth = currentFrameWidth();
        int frameHeight = currentFrameHeight();
        int preferred = computeRightPanelWidth(frameWidth, frameHeight);
        rightSidebar.setPreferredSize(new Dimension(preferred, Math.max(1, frameHeight)));
        rightTabs.setPreferredSize(new Dimension(preferred, Math.max(1, frameHeight)));
        rightSidebar.revalidate();
        rightTabs.revalidate();
    }

    private void syncResponsiveLayout() {
        syncViewportMetrics();
        updateRightTabsPreferredWidth();
        if (timelineDockManuallyResized) {
            applyTimelineDockHeight(timelineDockHeight);
        } else {
            applyTimelineDockHeight(computeDefaultTimelineDockHeight(currentFrameHeight()));
        }
        int currentWidth = currentFrameWidth();
        renderPreviewDock.setPreferredSize(new Dimension(currentWidth, RENDER_PREVIEW_BAR_HEIGHT));
        int southHeight = renderPreviewMode
                ? RENDER_PREVIEW_BAR_HEIGHT
                : (timelineDockAttached ? timelineDockHeight : 0);
        southDockHost.setPreferredSize(new Dimension(currentWidth, southHeight));
        toolbar.revalidate();
        timelineDock.revalidate();
        renderPreviewDock.revalidate();
        southDockHost.revalidate();
        if (frame != null && frame.getContentPane() != null) {
            frame.getContentPane().revalidate();
        }
    }

    private void syncViewportMetrics() {
        if (canvas != null) {
            if (canvas.getWidth() > 0) {
                width = canvas.getWidth();
            }
            if (canvas.getHeight() > 0) {
                height = canvas.getHeight();
            }
        }
    }

    private int currentFrameWidth() {
        if (frame != null && frame.getContentPane() != null && frame.getContentPane().getWidth() > 0) {
            return frame.getContentPane().getWidth();
        }
        return Math.max(1, width);
    }

    private int currentFrameHeight() {
        if (frame != null && frame.getContentPane() != null && frame.getContentPane().getHeight() > 0) {
            return frame.getContentPane().getHeight();
        }
        return Math.max(1, height + timelineDockHeight);
    }

    static int computeRightPanelWidth(int frameWidth, int frameHeight) {
        int safeWidth = Math.max(1, frameWidth);
        int safeHeight = Math.max(1, frameHeight);
        double aspect = (double) safeWidth / (double) safeHeight;
        double ratio;
        if (aspect >= 2.05) {
            ratio = 0.31;
        } else if (aspect >= 1.77) {
            ratio = 0.29;
        } else if (aspect >= 1.59) {
            ratio = 0.27;
        } else {
            ratio = 0.25;
        }
        int preferred = (int) Math.round(safeWidth * ratio);
        return clampInt(preferred, UiTheme.RIGHT_PANEL_MIN, UiTheme.RIGHT_PANEL_MAX);
    }

    static int computeDefaultTimelineDockHeight(int frameHeight) {
        int preferred = (int) Math.round(Math.max(1, frameHeight) * 0.12);
        return clampInt(preferred, UiTheme.BOTTOM_DOCK_DEFAULT_HEIGHT, 180);
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static final class AxisWidgetHandle {
        AxisWidgetTarget target;
        double centerX;
        double centerY;
        double radius;
        double depth;
        boolean frontFacing;
        boolean primary;
        String label;
        Color color;
        Color fill;
        Color outline;
        int lineAlpha;
        int labelAlpha;
    }

    private static final class AxisWidgetSpec {
        final AxisWidgetTarget target;
        final String label;
        final Vec3 axis;
        final Color color;
        final boolean primary;

        AxisWidgetSpec(AxisWidgetTarget target, String label, Vec3 axis, Color color, boolean primary) {
            this.target = target;
            this.label = label;
            this.axis = axis;
            this.color = color;
            this.primary = primary;
        }
    }

    private static final class AxisWidgetBasis {
        Vec3 right;
        Vec3 up;
        Vec3 viewDirection;
    }

    private static final class AxisWidgetAnchor {
        int centerX;
        int centerY;
    }

    private static final class WorldAxisWidgetGeometry {
        int centerX;
        int centerY;
        AxisWidgetHandle[] handles;
        AxisWidgetHandle[] sortedHandles;
    }

    private static final class RightTabContentPanel extends JPanel implements Scrollable {
        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return Math.max(32, visibleRect.height - 32);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
