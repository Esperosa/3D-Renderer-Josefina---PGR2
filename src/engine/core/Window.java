package engine.core;

import engine.io.FileUtil;
import engine.ui.UiTheme;
import engine.ui.WrapLayout;

import java.awt.BorderLayout;
import java.awt.BasicStroke;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.IllegalComponentStateException;
import java.awt.Insets;
import java.awt.MenuItem;
import java.awt.Point;
import java.awt.PopupMenu;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.Taskbar;
import java.awt.Toolkit;
import java.awt.AlphaComposite;
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
import javax.swing.JButton;
import javax.swing.Icon;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.JTabbedPane;
import javax.swing.border.EmptyBorder;

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
    private final PopupMenu contextMenu;
    private final Map<String, JPanel> rightTabContents;
    private final Map<String, Integer> rightTabIndices;

    private int width;
    private int height;
    private BufferedImage backBuffer;
    private final Cursor defaultCursor;
    private final Cursor hiddenCursor;
    private final Font overlayFont;
    private volatile String[] overlayLines;
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
    private int timelineDockHeight;
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
        this.width = width;
        this.height = height;
        this.closeRequested = false;
        this.rightTabContents = new LinkedHashMap<>();
        this.rightTabIndices = new LinkedHashMap<>();

        frame = new JFrame(title);
        installAppIcon();
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                closeRequested = true;
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
        timelineDockHeight = UiTheme.BOTTOM_DOCK_DEFAULT_HEIGHT;
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
        rightTabs.setPreferredSize(new Dimension(420, height));
        rightTabs.setMinimumSize(new Dimension(UiTheme.RIGHT_PANEL_MIN, 0));
        UiTheme.installTabbedPaneTheme(rightTabs);
        root.add(rightTabs, BorderLayout.EAST);
        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                updateRightTabsPreferredWidth();
                toolbar.revalidate();
                timelineDock.revalidate();
                frame.getContentPane().revalidate();
            }
        });

        contextMenu = new PopupMenu();
        canvas.add(contextMenu);
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
        updateRightTabsPreferredWidth();
        frame.setBackground(FRAME_BG);
        frame.pack();
        frame.setMinimumSize(new Dimension(1060, 700));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        backBuffer = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        defaultCursor = Cursor.getDefaultCursor();
        BufferedImage blank = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        hiddenCursor = Toolkit.getDefaultToolkit().createCustomCursor(blank, new Point(0, 0), "hidden");
        overlayFont = new Font(Font.MONOSPACED, Font.BOLD, 18);
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
            // Tady tiše ignoruju platformy, které nepovolí integraci ikony do taskbaru.
        }
    }

    private Image loadAppIcon() {
        if (FileUtil.exists(APP_ICON_PNG_PATH)) {
            try {
                return ImageIO.read(new File(APP_ICON_PNG_PATH));
            } catch (IOException ex) {
                // Tady spadnu na ICO asset níž, když PNG načtení selže.
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
                if (drawViewportChrome && cursorCaptured) {
                    drawCrosshair(g2, canvas.getWidth(), canvas.getHeight());
                }
                if (drawViewportChrome) {
                    drawWorldAxisWidget(g2, canvas.getWidth(), canvas.getHeight());
                }
                drawOverlayText(g2);
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

    public JFrame getFrame() {
        return frame;
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
    }

    /** Tady uvolním AWT prostředky. */
    public void dispose() {
        closeRequested = true;
        if (frame != null) {
            frame.dispose();
        }
    }

    public boolean isCloseRequested() {
        return closeRequested || frame == null || !frame.isDisplayable();
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

    public MenuItem addContextMenuItem(String label, Runnable action) {
        MenuItem item = new MenuItem(label);
        item.addActionListener(e -> {
            if (action != null) {
                action.run();
            }
            canvas.requestFocusInWindow();
        });
        contextMenu.add(item);
        return item;
    }

    public void clearContextMenuItems() {
        contextMenu.removeAll();
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
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

        FontMetrics metrics = g2.getFontMetrics(overlayFont);
        int x = 14;
        int y = 18 + metrics.getAscent();
        int lineStep = metrics.getHeight() + 2;
        int maxChars = 0;
        for (String line : lines) {
            if (line != null) {
                maxChars = Math.max(maxChars, line.length());
            }
        }
        int clipW = Math.max(32, Math.min(canvas.getWidth() - x - 4, metrics.charWidth('W') * maxChars + 8));
        int clipH = Math.max(16, Math.min(canvas.getHeight() - 8, lines.length * lineStep + 6));
        if (clipW <= 0 || clipH <= 0) {
            return;
        }
        g2.setClip(x - 2, 8, clipW, clipH);

        for (String line : lines) {
            if (line == null || line.isEmpty()) {
                y += lineStep;
                continue;
            }
            g2.setColor(OVERLAY_SHADOW);
            g2.drawString(line, x + 1, y + 1);
            g2.setColor(OVERLAY_FG);
            g2.drawString(line, x, y);
            y += lineStep;
        }
        g2.setClip(null);
    }

    private void maybeShowContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        if (contextMenu.getItemCount() == 0) {
            return;
        }
        contextMenu.show(canvas, e.getX(), e.getY());
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
                applyTimelineDockHeight(timelineResizeStartHeight + delta);
            }
        });
    }

    private void applyTimelineDockHeight(int targetHeight) {
        int frameHeight = frame != null ? frame.getHeight() : (height + 120);
        int max = Math.max(110, frameHeight - 240);
        int min = UiTheme.BOTTOM_DOCK_MIN_HEIGHT;
        if (timelineDock != null) {
            Dimension dockMin = timelineDock.getMinimumSize();
            if (dockMin != null) {
                min = Math.max(min, dockMin.height);
            }
        }
        int clamped = Math.max(min, Math.min(max, targetHeight));
        if (clamped == timelineDockHeight) {
            return;
        }
        timelineDockHeight = clamped;
        timelineDock.setPreferredSize(new Dimension(width, timelineDockHeight));
        timelineDock.revalidate();
        if (frame != null) {
            frame.revalidate();
        }
    }

    private void updateRightTabsPreferredWidth() {
        if (rightTabs == null) {
            return;
        }
        int frameWidth = frame != null ? Math.max(width, frame.getWidth()) : width;
        int preferred = Math.max(UiTheme.RIGHT_PANEL_MIN,
                Math.min(UiTheme.RIGHT_PANEL_MAX, (int) Math.round(frameWidth * 0.29)));
        rightTabs.setPreferredSize(new Dimension(preferred, Math.max(1, height)));
        rightTabs.revalidate();
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
