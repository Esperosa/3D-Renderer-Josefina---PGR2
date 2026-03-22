package engine.core;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.geom.Ellipse2D;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import engine.io.FileUtil;

final class StartupLoadingScreen {
    private static final String ICON_PATH = "assets/icons/IcoUni.png";

    private final JWindow window;
    private final LoadingCanvas canvas;

    private StartupLoadingScreen(JWindow window, LoadingCanvas canvas) {
        this.window = window;
        this.canvas = canvas;
    }

    static StartupLoadingScreen show(String appTitle) {
        Holder holder = new Holder();
        runOnEdtAndWait(() -> {
            JWindow window = new JWindow();
            LoadingCanvas canvas = new LoadingCanvas(appTitle, loadBrandImage());
            window.setBackground(new Color(0, 0, 0, 0));
            window.setContentPane(canvas);
            window.setSize(560, 280);
            window.setLocationRelativeTo(null);
            window.setAlwaysOnTop(true);
            window.setVisible(true);
            canvas.start();
            holder.value = new StartupLoadingScreen(window, canvas);
        });
        return holder.value;
    }

    void setPhase(String text) {
        if (text == null) {
            return;
        }
        runOnEdtAndWait(() -> canvas.setPhase(text));
    }

    void close() {
        runOnEdtAndWait(() -> {
            canvas.stop();
            window.setVisible(false);
            window.dispose();
        });
    }

    private static Image loadBrandImage() {
        if (!FileUtil.exists(ICON_PATH)) {
            return null;
        }
        try {
            return ImageIO.read(new File(ICON_PATH));
        } catch (IOException ex) {
            return Toolkit.getDefaultToolkit().getImage(ICON_PATH);
        }
    }

    private static void runOnEdtAndWait(Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(task);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ex) {
            throw new RuntimeException("Failed to update startup loading screen", ex.getCause());
        }
    }

    private static final class Holder {
        private StartupLoadingScreen value;
    }

    private static final class LoadingCanvas extends JComponent {
        private final String appTitle;
        private final Image brandImage;
        private final Font titleFont;
        private final Font subtitleFont;
        private final Font phaseFont;
        private final Timer timer;

        private String phaseText;
        private double t;

        private LoadingCanvas(String appTitle, Image brandImage) {
            this.appTitle = appTitle == null || appTitle.isBlank() ? "3D Render Physics" : appTitle;
            this.brandImage = brandImage;
            this.phaseText = "Inicializuji engine...";
            this.t = 0.0;
            this.titleFont = new Font("Serif", Font.BOLD, 34);
            this.subtitleFont = new Font("SansSerif", Font.BOLD, 14);
            this.phaseFont = new Font("SansSerif", Font.PLAIN, 13);
            this.timer = new Timer(24, e -> {
                t += 0.06;
                repaint();
            });
            setOpaque(false);
        }

        void start() {
            timer.start();
        }

        void stop() {
            timer.stop();
        }

        void setPhase(String text) {
            this.phaseText = text;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();
            GradientPaint bg = new GradientPaint(
                    0, 0, new Color(17, 25, 38, 250),
                    w, h, new Color(29, 40, 57, 250)
            );
            g2.setPaint(bg);
            g2.fillRoundRect(0, 0, w, h, 26, 26);

            g2.setColor(new Color(93, 157, 209, 210));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 26, 26);

            int iconX = 34;
            int iconY = 70;
            int iconSize = 96;
            g2.setColor(new Color(255, 255, 255, 26));
            g2.fillOval(iconX - 14, iconY - 14, iconSize + 28, iconSize + 28);

            if (brandImage != null) {
                g2.drawImage(brandImage, iconX, iconY, iconSize, iconSize, null);
            } else {
                g2.setColor(new Color(156, 213, 255, 230));
                g2.fillOval(iconX, iconY, iconSize, iconSize);
            }

            int textX = 162;
            int titleY = 102;
            g2.setColor(new Color(241, 246, 252));
            g2.setFont(titleFont);
            g2.drawString("JOSEFINA", textX, titleY);

            g2.setColor(new Color(159, 192, 226));
            g2.setFont(subtitleFont);
            g2.drawString("Jirka | Pelican Studio", textX + 2, titleY + 24);

            g2.setColor(new Color(198, 216, 236));
            g2.setFont(phaseFont);
            g2.drawString(phaseText, textX + 2, titleY + 52);

            drawSpinner(g2, textX + 2, titleY + 76);
            drawAppTitle(g2, w, h);

            g2.dispose();
        }

        private void drawSpinner(Graphics2D g2, int x, int y) {
            int dots = 8;
            double radius = 20.0;
            for (int i = 0; i < dots; i++) {
                double phase = t + i * (Math.PI * 2.0 / dots);
                double alpha = 0.25 + 0.75 * Math.max(0.0, Math.sin(phase));
                int px = x + (int) Math.round(Math.cos(i * (Math.PI * 2.0 / dots)) * radius);
                int py = y + (int) Math.round(Math.sin(i * (Math.PI * 2.0 / dots)) * radius);
                g2.setColor(new Color(126, 192, 243, (int) Math.round(255 * alpha)));
                g2.fill(new Ellipse2D.Double(px - 4, py - 4, 8, 8));
            }
        }

        private void drawAppTitle(Graphics2D g2, int w, int h) {
            g2.setFont(new Font("SansSerif", Font.PLAIN, 12));
            String text = appTitle;
            FontMetrics fm = g2.getFontMetrics();
            int tw = fm.stringWidth(text);
            g2.setColor(new Color(176, 197, 220, 180));
            g2.drawString(text, Math.max(16, w - tw - 18), h - 18);
        }
    }
}
