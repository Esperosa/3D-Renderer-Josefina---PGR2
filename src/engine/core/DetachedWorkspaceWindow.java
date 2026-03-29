package engine.core;

import engine.ui.UiTheme;

import javax.swing.JFrame;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

final class DetachedWorkspaceWindow {
    private final JFrame frame;
    private final JPanel hostPanel;
    private final Runnable attachCallback;
    private boolean disposeSilently;

    DetachedWorkspaceWindow(Engine engine,
                            String title,
                            String subtitle,
                            Runnable attachCallback) {
        this.attachCallback = attachCallback;
        this.disposeSilently = false;
        Frame owner = engine != null && engine.window != null ? engine.window.getFrame() : null;
        this.frame = new JFrame(title);
        if (owner != null) {
            this.frame.setLocationByPlatform(true);
        }
        this.frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        this.frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (!disposeSilently && DetachedWorkspaceWindow.this.attachCallback != null) {
                    DetachedWorkspaceWindow.this.attachCallback.run();
                }
            }
        });

        frame.setTitle(title + " | " + subtitle);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(UiTheme.APP_BG);

        hostPanel = new JPanel(new BorderLayout());
        hostPanel.setOpaque(false);

        root.add(hostPanel, BorderLayout.CENTER);

        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(900, 320));
        frame.setSize(new Dimension(1240, 420));
        if (owner != null) {
            frame.setLocation(owner.getX() + 48, owner.getY() + 48);
        }
    }

    JPanel hostPanel() {
        return hostPanel;
    }

    void updateTitle(String title, String subtitle) {
        String safeTitle = title == null || title.isBlank() ? "Workspace" : title;
        String safeSubtitle = subtitle == null ? "" : subtitle.trim();
        frame.setTitle(safeSubtitle.isEmpty() ? safeTitle : safeTitle + " | " + safeSubtitle);
    }

    void show() {
        frame.setVisible(true);
        frame.toFront();
        frame.requestFocus();
    }

    void focus() {
        if (!frame.isVisible()) {
            frame.setVisible(true);
        }
        frame.toFront();
        frame.requestFocus();
    }

    void disposeSilently() {
        disposeSilently = true;
        frame.dispose();
    }

    boolean isDisplayable() {
        return frame.isDisplayable();
    }
}
