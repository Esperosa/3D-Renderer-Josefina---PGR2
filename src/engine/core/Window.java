package engine.core;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
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
import java.awt.geom.Path2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.Scrollable;
import javax.swing.border.EmptyBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import engine.io.FileUtil;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;
import engine.util.RuntimeInstrumentation;

/**
 * Tady držím hlavní AWT okno s canvasem, toolbarem a postranními panely.
 */
public class Window {

    static final String APP_ICON_PNG_PATH = "assets/icons/IcoUni.png";
    static final String APP_ICON_ICO_PATH = "assets/icons/IcoUni.ico";
    private static final Color FRAME_BG = UiTheme.APP_BG;
    private static final Color PANEL_BG = UiTheme.PANEL_BG;
    private static final Color TAB_BORDER = UiTheme.BORDER_SUBTLE;
    private static final Color ICON_FG = UiTheme.ACCENT;
    private static final Color OVERLAY_FG = UiTheme.ACCENT;
    private static final Color OVERLAY_SHADOW = new Color(0, 0, 0);

    private final JFrame frame;
    private final Canvas canvas;
    private final JPanel toolbar;
    private final JPanel timelineDock;
    private final JTabbedPane rightTabs;
    private final JPopupMenu contextMenu;
    private final Map<String, JPanel> rightTabContents;
    private final Map<String, Integer> rightTabIndices;
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
    private volatile boolean renderModeSwitchOverlayActive;
    private volatile double renderModeSwitchOverlayAlpha;
    private volatile String renderModeSwitchOverlayLabel;
    private int timelineDockHeight;
    private boolean timelineDockManuallyResized;
    private boolean timelineResizeDragging;
    private int timelineResizeStartScreenY;
    private int timelineResizeStartHeight;

    /**
     * Tady okno vytvořím a rovnou ho zobrazím.
     *
     * @param title sem předám titulek okna
     * @param width sem předám počáteční šířku v pixelech
     * @param height sem předám počáteční výšku v pixelech
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

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(FRAME_BG);

        toolbar = new JPanel(new WrapLayout(WrapLayout.LEFT, 8, 8));
        UiTheme.styleToolbarPanel(toolbar);
        root.add(toolbar, BorderLayout.NORTH);

        timelineDock = new JPanel(new BorderLayout(8, 0));
        timelineDock.setBackground(UiTheme.PANEL_BG);
        timelineDock.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, UiTheme.BORDER_SUBTLE));
        timelineDockHeight = computeDefaultTimelineDockHeight(height);
        timelineDock.setPreferredSize(new Dimension(width, timelineDockHeight));
        root.add(timelineDock, BorderLayout.SOUTH);

        canvas = new Canvas();
        canvas.setBackground(UiTheme.VIEWPORT_BG);
        canvas.setPreferredSize(new Dimension(width, height));
        root.add(canvas, BorderLayout.CENTER);

        rightTabs = new JTabbedPane(JTabbedPane.LEFT, JTabbedPane.SCROLL_TAB_LAYOUT);
        rightTabs.setFocusable(false);
        rightTabs.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 0, 0, TAB_BORDER),
                new EmptyBorder(0, 4, 0, 0)
        ));
        rightTabs.setPreferredSize(new Dimension(computeRightPanelWidth(width, height), height));
        rightTabs.setMinimumSize(new Dimension(UiTheme.RIGHT_PANEL_MIN, 0));
        UiTheme.installTabbedPaneTheme(rightTabs);
        root.add(rightTabs, BorderLayout.EAST);
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
                // A tady nic navic nedelam, callback volam tesne pred show().
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                // A tady schvalne nic nedelam, potvrzene akce resi jejich vlastni callback.
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

        frame.setContentPane(root);
        frame.setBackground(FRAME_BG);
        frame.pack();
        frame.setMinimumSize(new Dimension(1060, 700));
        syncResponsiveLayout();
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
        cursorCaptured = false;
        smoothUpscaling = true;
        worldAxisWidgetVisible = false;
        worldAxisRightX = 1.0;
        worldAxisRightY = 0.0;
        worldAxisRightZ = 0.0;
        worldAxisUpX = 0.0;
        worldAxisUpY = 1.0;
        worldAxisUpZ = 0.0;
        timelineDockManuallyResized = false;
        timelineResizeDragging = false;
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
            // A tady tise ignoruju platformy, ktere nepovoli integraci ikony do taskbaru.
        }
    }

    private Image loadAppIcon() {
        if (FileUtil.exists(APP_ICON_PNG_PATH)) {
            try {
                return ImageIO.read(new File(APP_ICON_PNG_PATH));
            } catch (IOException ex) {
                // A tady prechazim na ICO asset nize, kdyz nacteni PNG selze.
            }
        }
        if (FileUtil.exists(APP_ICON_ICO_PATH)) {
            return Toolkit.getDefaultToolkit().getImage(APP_ICON_ICO_PATH);
        }
        return null;
    }

    /**
     * Tady přenesu int[] barevný buffer do okna.
     *
     * @param pixels sem předám pole pixelů ARGB o délce width * height
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
                drawOverlayText(g2);
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
     * Tady buffer strategy vytvořím jen tehdy, když má canvas opravdu validní peer.
     * Tím zabráním pádu při startu, minimalizaci nebo zavírání okna.
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

    /** @return vrátím kreslicí canvas pro připojení input listenerů */
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

    /** @return vrátím aktuální šířku okna */
    public int getWidth() {
        return Math.max(1, canvas.getWidth());
    }

    /** @return vrátím aktuální výšku okna */
    public int getHeight() {
        return Math.max(1, canvas.getHeight());
    }

    /**
     * Tady zpracovávám resize okna a znovu alokuju backBuffer.
     *
     * @param w sem předám novou šířku
     * @param h sem předám novou výšku
     */
    public void resize(int w, int h) {
        width = Math.max(1, w);
        height = Math.max(1, h);
        backBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        canvas.setPreferredSize(new Dimension(width, height));
        frame.pack();
        syncResponsiveLayout();
    }

    /** Tady uvolním AWT prostředky. */
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
        content.setBorder(new EmptyBorder(14, 16, 14, 14));

        JScrollPane scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 0, PANEL_BG));
        scroll.setViewportBorder(new EmptyBorder(0, 2, 0, 0));
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        UiTheme.styleScrollPane(scroll, PANEL_BG);
        rightTabs.addTab(title, icon, scroll);
        int tabIndex = rightTabs.getTabCount() - 1;
        rightTabs.setToolTipTextAt(tabIndex, title);
        rightTabContents.put(safeKey, content);
        rightTabIndices.put(safeKey, tabIndex);
        return content;
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

    public void setWorldAxisWidgetVisible(boolean visible) {
        worldAxisWidgetVisible = visible;
    }

    public void setWorldAxisWidgetVectors(
            double rightX,
            double rightY,
            double rightZ,
            double upX,
            double upY,
            double upZ) {
        worldAxisRightX = rightX;
        worldAxisRightY = rightY;
        worldAxisRightZ = rightZ;
        worldAxisUpX = upX;
        worldAxisUpY = upY;
        worldAxisUpZ = upZ;
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
        if (!worldAxisWidgetVisible || w <= 0 || h <= 0) {
            return;
        }

        int boxSize = 68;
        int half = boxSize / 2;
        int margin = 28;
        int len = 30;
        int minX = margin + half;
        int maxX = Math.max(minX, w - margin - half);
        int minY = margin + half;
        int maxY = Math.max(minY, h - margin - half);
        int ox = Math.max(minX, Math.min(maxX, 62));
        int oy = Math.max(minY, Math.min(maxY, h - 62));

        double rx = worldAxisRightX;
        double ry = worldAxisRightY;
        double rz = worldAxisRightZ;
        double ux = worldAxisUpX;
        double uy = worldAxisUpY;
        double uz = worldAxisUpZ;

        int xdx = (int) Math.round(rx * len);
        int xdy = (int) Math.round(-ux * len);
        int ydx = (int) Math.round(ry * len);
        int ydy = (int) Math.round(-uy * len);
        int zdx = (int) Math.round(rz * len);
        int zdy = (int) Math.round(-uz * len);

        Object oldAa = g2.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        Stroke oldStroke = g2.getStroke();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(12, 19, 29, 220));
        g2.fillRoundRect(ox - half, oy - half, boxSize, boxSize, 14, 14);
        g2.setColor(new Color(51, 80, 110, 235));
        g2.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawRoundRect(ox - half, oy - half, boxSize, boxSize, 14, 14);

        drawAxisLine(g2, ox, oy, ox + xdx, oy + xdy, new Color(228, 81, 81));
        drawAxisLine(g2, ox, oy, ox + ydx, oy + ydy, new Color(101, 209, 103));
        drawAxisLine(g2, ox, oy, ox + zdx, oy + zdy, new Color(103, 168, 255));

        g2.setColor(new Color(226, 234, 248));
        g2.fillOval(ox - 3, oy - 3, 7, 7);
        g2.setColor(new Color(12, 19, 29));
        g2.drawOval(ox - 3, oy - 3, 7, 7);
        g2.setStroke(oldStroke);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAa);
    }

    private void drawAxisLine(Graphics2D g2, int x0, int y0, int x1, int y1, Color color) {
        g2.setColor(new Color(0, 0, 0, 120));
        g2.drawLine(x0 + 1, y0 + 1, x1 + 1, y1 + 1);
        g2.setColor(color);
        g2.drawLine(x0, y0, x1, y1);
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
            // A tady platformni fullscreen fallback nechavam tise odeznit.
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
            } else if ("controls".equals(iconName)) {
                g2.drawRoundRect(ox + 2, oy + 2, s - 4, s - 4, 5, 5);
                g2.drawLine(ox + s / 2, oy + 4, ox + s / 2, oy + s - 4);
                g2.drawLine(ox + 4, oy + s / 2, ox + s - 4, oy + s / 2);
            } else if ("object".equals(iconName)) {
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
        timelineDock.revalidate();
        if (frame != null) {
            frame.revalidate();
        }
    }

    private void updateRightTabsPreferredWidth() {
        if (rightTabs == null) {
            return;
        }
        int frameWidth = currentFrameWidth();
        int frameHeight = currentFrameHeight();
        int preferred = computeRightPanelWidth(frameWidth, frameHeight);
        rightTabs.setPreferredSize(new Dimension(preferred, Math.max(1, frameHeight)));
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
        toolbar.revalidate();
        timelineDock.revalidate();
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
