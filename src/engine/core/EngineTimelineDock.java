package engine.core;

import engine.ui.UiTheme;
import engine.ui.WrapLayout;
import engine.util.UiBuilder;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;

final class EngineTimelineDock {
    private EngineTimelineDock() {
    }

    static void setup(Engine engine) {
        if (engine == null || engine.window == null) {
            return;
        }
        populate(engine,
                engine.timelineDockHostPanel != null
                        ? engine.timelineDockHostPanel
                        : engine.window.getTimelinePanel());
    }

    static void populate(Engine engine, JPanel host) {
        if (engine == null || host == null) {
            return;
        }
        host.removeAll();
        host.setLayout(new BorderLayout(8, 6));
        host.setOpaque(true);
        host.setBackground(UiTheme.PANEL_BG);
        EditorFocusContext.mark(host, EditorFocusContext.TIMELINE);

        JPanel controls = new JPanel(new WrapLayout(WrapLayout.LEFT, 6, 6));
        controls.setOpaque(false);

        JButton prev = createButton(engine, "Předchozí", "Posune časovou osu o jeden snímek zpět.", () -> engine.stepTimelineFrame(-1));
        JButton next = createButton(engine, "Další", "Posune časovou osu o jeden snímek vpřed.", () -> engine.stepTimelineFrame(1));
        JButton play = createButton(engine, "Přehrát / pauza", "Spustí nebo pozastaví přehrávání animace.", engine::toggleAnimationPlayback);
        JButton addKey = createButton(engine, "Klíč výběru", "Vloží klíč pro aktuální výběr.", engine::addTimelineKeyForSelection);
        JButton removeKey = createButton(engine, "Smazat klíč", "Smaže klíč aktuálního výběru.", engine::removeTimelineKeyForSelection);
        JButton addRelease = createButton(engine, "Release klíč", "Vloží release klíč pro fyziku.", engine::addTimelineReleaseKeyForSelection);
        JButton removeRelease = createButton(engine, "Smazat release", "Smaže release klíč aktuálního výběru.", engine::removeTimelineReleaseKeyForSelection);
        JButton addAll = createButton(engine, "Klíč všem", "Vloží klíč všem animovatelným položkám.", () -> EngineTimelineController.addKeyForAllAnimatables(engine));
        JButton clearAll = createButton(engine, "Vyčistit klíče", "Odstraní všechny klíče z časové osy.", engine::clearTimelineKeys);

        controls.add(prev);
        controls.add(next);
        controls.add(play);
        controls.add(addKey);
        controls.add(removeKey);
        controls.add(addRelease);
        controls.add(removeRelease);
        controls.add(addAll);
        controls.add(clearAll);
        controls.add(Box.createRigidArea(new Dimension(14, 1)));

        engine.timelineDockStartFrameField = createField(engine, Integer.toString(engine.timelineStartFrame), text -> {
            int start = Math.max(0, (int) Math.round(engine.parseOrFallback(text, engine.timelineStartFrame)));
            engine.setTimelineRange(start, engine.timelineEndFrame);
        });
        engine.timelineDockCurrentFrameField = createField(engine, Integer.toString(engine.timelineCurrentFrame), text -> {
            int current = Math.max(0, (int) Math.round(engine.parseOrFallback(text, engine.timelineCurrentFrame)));
            engine.setTimelineCurrentFrame(current);
        });
        engine.timelineDockEndFrameField = createField(engine, Integer.toString(engine.timelineEndFrame), text -> {
            int end = Math.max(0, (int) Math.round(engine.parseOrFallback(text, engine.timelineEndFrame)));
            engine.setTimelineRange(engine.timelineStartFrame, end);
        });
        engine.timelineDockFpsField = createField(engine, engine.formatTransformValue(engine.timelineFps), text -> {
            double fps = Math.max(1.0, Math.min(240.0, engine.parseOrFallback(text, engine.timelineFps)));
            EngineTimelineController.setFrameRate(engine, fps);
        });

        controls.add(label("Začátek"));
        controls.add(engine.timelineDockStartFrameField);
        controls.add(label("Aktuální"));
        controls.add(engine.timelineDockCurrentFrameField);
        controls.add(label("Konec"));
        controls.add(engine.timelineDockEndFrameField);
        controls.add(label("FPS"));
        controls.add(engine.timelineDockFpsField);

        TimelineStrip strip = new TimelineStrip(engine);
        engine.timelineDockStripComponent = strip;

        JPanel statusPanel = new JPanel();
        statusPanel.setLayout(new BoxLayout(statusPanel, BoxLayout.X_AXIS));
        statusPanel.setOpaque(false);
        statusPanel.setBorder(BorderFactory.createEmptyBorder(0, 6, 4, 6));
        engine.timelineDockStatusLabel = new JLabel();
        engine.timelineDockStatusLabel.setForeground(UiTheme.ACCENT);
        statusPanel.add(engine.timelineDockStatusLabel);
        statusPanel.add(Box.createHorizontalGlue());
        JLabel hint = new JLabel("Posuv: klik/tah | Space: play | ←/→: snímek | Insert/K: klíč | Shift+K: release");
        hint.setForeground(UiTheme.TEXT_MUTED);
        statusPanel.add(hint);

        host.add(controls, BorderLayout.NORTH);
        host.add(strip, BorderLayout.CENTER);
        host.add(statusPanel, BorderLayout.SOUTH);
        host.revalidate();
        host.repaint();
        refresh(engine);
    }

    static void refresh(Engine engine) {
        if (engine == null) {
            return;
        }
        if (engine.timelineDockStartFrameField != null && !engine.timelineDockStartFrameField.isFocusOwner()) {
            engine.timelineDockStartFrameField.setText(Integer.toString(engine.timelineStartFrame));
        }
        if (engine.timelineDockCurrentFrameField != null && !engine.timelineDockCurrentFrameField.isFocusOwner()) {
            engine.timelineDockCurrentFrameField.setText(Integer.toString(engine.timelineCurrentFrame));
        }
        if (engine.timelineDockEndFrameField != null && !engine.timelineDockEndFrameField.isFocusOwner()) {
            engine.timelineDockEndFrameField.setText(Integer.toString(engine.timelineEndFrame));
        }
        if (engine.timelineDockFpsField != null && !engine.timelineDockFpsField.isFocusOwner()) {
            engine.timelineDockFpsField.setText(engine.formatTransformValue(engine.timelineFps));
        }
        if (engine.timelineDockStatusLabel != null) {
            engine.timelineDockStatusLabel.setText(EngineTimelineController.timelineStatus(engine));
        }
        if (engine.timelineDockStripComponent != null) {
            engine.timelineDockStripComponent.repaint();
        }
    }

    private static JButton createButton(Engine engine, String text, String tooltip, Runnable action) {
        JButton button = new JButton(text);
        UiBuilder.styleGhostButton(button);
        button.setPreferredSize(new Dimension(Math.max(74, button.getPreferredSize().width), UiTheme.COMPACT_BUTTON_HEIGHT));
        button.setToolTipText(tooltip);
        button.addActionListener(e -> {
            action.run();
            engine.focusCanvas();
        });
        return button;
    }

    private static JLabel label(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTheme.TEXT_SECONDARY);
        return label;
    }

    private static JTextField createField(Engine engine, String initial, java.util.function.Consumer<String> onCommit) {
        JTextField field = new JTextField(initial, 6);
        UiBuilder.styleInspectorField(field);
        field.addActionListener(e -> onCommit.accept(field.getText().trim()));
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                onCommit.accept(field.getText().trim());
            }
        });
        return field;
    }

    private static final class TimelineStrip extends JComponent {
        private final Engine engine;
        private final Stroke minorStroke = new BasicStroke(1.0f);
        private final Stroke currentStroke = new BasicStroke(1.9f);
        private boolean dragging;

        TimelineStrip(Engine engine) {
            this.engine = engine;
            setPreferredSize(new Dimension(200, 40));
            setMinimumSize(new Dimension(100, 34));
            setOpaque(false);
            setFocusable(true);
            EditorFocusContext.mark(this, EditorFocusContext.TIMELINE);
            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    dragging = true;
                    scrubTo(e.getX());
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragging) {
                        scrubTo(e.getX());
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragging = false;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() >= 2) {
                        engine.addTimelineKeyForSelection();
                    }
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            int x0 = 14;
            int x1 = Math.max(x0 + 2, w - 14);
            int y0 = 6;
            int y1 = h - 8;
            int spanPx = Math.max(1, x1 - x0);

            g2.setColor(UiTheme.PANEL_ELEVATED);
            g2.fillRoundRect(x0 - 6, y0 - 2, spanPx + 12, Math.max(8, y1 - y0 + 4), 8, 8);
            g2.setColor(UiTheme.BORDER_STRONG);
            g2.drawRoundRect(x0 - 6, y0 - 2, spanPx + 12, Math.max(8, y1 - y0 + 4), 8, 8);

            int start = engine.timelineStartFrame;
            int end = Math.max(start, engine.timelineEndFrame);
            int spanFrames = Math.max(1, end - start);

            int majorStep = timelineMajorStep(spanFrames);
            int minorStep = Math.max(1, majorStep / 2);
            g2.setStroke(minorStroke);
            for (int frame = start; frame <= end; frame += minorStep) {
                int x = frameToX(frame, start, spanFrames, x0, spanPx);
                boolean major = (frame - start) % majorStep == 0;
                g2.setColor(major ? UiTheme.TEXT_MUTED : UiTheme.BORDER_SUBTLE);
                int top = major ? y0 + 1 : y0 + 6;
                g2.drawLine(x, top, x, y1 - 1);
            }

            NavigableMap<Integer, Integer> global = engine.sceneTimeline.buildGlobalFrameUsage();
            for (Map.Entry<Integer, Integer> e : global.entrySet()) {
                int frame = e.getKey();
                if (frame < start || frame > end) {
                    continue;
                }
                int x = frameToX(frame, start, spanFrames, x0, spanPx);
                int count = Math.max(1, e.getValue());
                int alpha = Math.min(255, 90 + count * 22);
                g2.setColor(new Color(UiTheme.ACCENT.getRed(), UiTheme.ACCENT.getGreen(), UiTheme.ACCENT.getBlue(), alpha));
                g2.fillRect(x - 1, y0 + 10, 3, Math.max(6, (y1 - y0) / 2));
            }

            Set<Integer> selectedFrames = engine.sceneTimeline.selectedFramesFor(
                    engine.selectedEntity,
                    engine.selectedLight,
                    engine.selectedForceField,
                    engine.outputCameraEntity
            );
            for (int frame : selectedFrames) {
                if (frame < start || frame > end) {
                    continue;
                }
                int x = frameToX(frame, start, spanFrames, x0, spanPx);
                g2.setColor(UiTheme.ACCENT_PURPLE_GLOW);
                g2.fillOval(x - 3, y0 + 2, 7, 7);
                g2.setColor(UiTheme.TEXT_PRIMARY);
                g2.drawOval(x - 3, y0 + 2, 7, 7);
            }

            g2.setStroke(currentStroke);
            g2.setColor(UiTheme.ACCENT_PURPLE);
            int cx = frameToX(engine.timelineCurrentFrame, start, spanFrames, x0, spanPx);
            g2.drawLine(cx, y0, cx, y1);
            g2.fillPolygon(
                    new int[]{cx, cx - 6, cx + 6},
                    new int[]{y0 - 1, y0 - 9, y0 - 9},
                    3
            );
            g2.dispose();
        }

        private void scrubTo(int mouseX) {
            int start = engine.timelineStartFrame;
            int end = Math.max(start, engine.timelineEndFrame);
            int spanFrames = Math.max(1, end - start);
            int x0 = 14;
            int x1 = Math.max(x0 + 2, getWidth() - 14);
            int spanPx = Math.max(1, x1 - x0);
            double t = (double) (mouseX - x0) / (double) spanPx;
            t = Math.max(0.0, Math.min(1.0, t));
            int frame = start + (int) Math.round(t * spanFrames);
            engine.setTimelineCurrentFrame(frame);
        }

        private static int frameToX(int frame, int start, int spanFrames, int x0, int spanPx) {
            double t = (double) (frame - start) / (double) spanFrames;
            return x0 + (int) Math.round(t * spanPx);
        }

        private static int timelineMajorStep(int spanFrames) {
            if (spanFrames <= 60) {
                return 10;
            }
            if (spanFrames <= 240) {
                return 20;
            }
            if (spanFrames <= 600) {
                return 50;
            }
            return 100;
        }
    }
}
