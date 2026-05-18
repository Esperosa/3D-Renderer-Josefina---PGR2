import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;

final class RendererApp {
    private final SceneModel scene = SceneModel.createDefault();
    private final ViewportPanel viewport = new ViewportPanel(scene);
    private JComboBox<Entity> entityCombo;
    private JLabel modeLabel;
    private JToggleButton projectionToggle;
    private JToggleButton fillToggle;
    private JToggleButton textureToggle;
    private JToggleButton animationToggle;

    void start() {
        JFrame frame = new JFrame("PGRF2 2026 - Úloha 1");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(viewport, BorderLayout.CENTER);
        frame.add(createControlPanel(), BorderLayout.EAST);
        viewport.setSelectionListener(this::refreshUi);
        frame.setSize(1180, 720);
        frame.setMinimumSize(new Dimension(920, 560));
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        viewport.requestFocusInWindow();
        refreshUi();
    }

    private Component createControlPanel() {
        JPanel panel = new JPanel();
        panel.setPreferredSize(new Dimension(270, 10));
        panel.setBackground(new Color(32, 35, 39));
        panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
        panel.setLayout(new javax.swing.BoxLayout(panel, javax.swing.BoxLayout.Y_AXIS));

        JLabel title = label("Úloha 1", 18, true);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));

        entityCombo = new JComboBox<>(scene.entities.toArray(Entity[]::new));
        entityCombo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        entityCombo.addActionListener(e -> {
            int index = entityCombo.getSelectedIndex();
            if (index >= 0) {
                scene.selectedIndex = index;
                viewport.requestFocusInWindow();
                refreshUi();
            }
        });
        panel.add(sectionLabel("Aktivní těleso"));
        panel.add(entityCombo);
        panel.add(Box.createVerticalStrut(12));

        ButtonGroup transformGroup = new ButtonGroup();
        JPanel transformPanel = new JPanel(new GridLayout(1, 3, 6, 0));
        transformPanel.setOpaque(false);
        transformPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        addTransformButton(transformPanel, transformGroup, "T", TransformMode.TRANSLATE);
        addTransformButton(transformPanel, transformGroup, "R", TransformMode.ROTATE);
        addTransformButton(transformPanel, transformGroup, "Y", TransformMode.SCALE);
        panel.add(sectionLabel("Režim transformace"));
        panel.add(transformPanel);
        panel.add(Box.createVerticalStrut(12));

        projectionToggle = toggle("Perspektiva / pravoúhlá", true, () -> {
            scene.perspective = !scene.perspective;
            refreshUi();
        });
        fillToggle = toggle("Plochy / drát", true, () -> {
            scene.filled = !scene.filled;
            refreshUi();
        });
        textureToggle = toggle("Textura aktivního", true, () -> {
            scene.selected().textureEnabled = !scene.selected().textureEnabled;
            refreshUi();
        });
        animationToggle = toggle("Animace", true, () -> {
            scene.lightAnimation = !scene.lightAnimation;
            refreshUi();
        });
        panel.add(projectionToggle);
        panel.add(Box.createVerticalStrut(6));
        panel.add(fillToggle);
        panel.add(Box.createVerticalStrut(6));
        panel.add(textureToggle);
        panel.add(Box.createVerticalStrut(6));
        panel.add(animationToggle);
        panel.add(Box.createVerticalStrut(10));

        JButton colorButton = button("Barva světla (C)", () -> {
            scene.cycleLightColor();
            refreshUi();
        });
        panel.add(colorButton);
        panel.add(Box.createVerticalStrut(16));

        modeLabel = label("", 12, false);
        modeLabel.setVerticalAlignment(SwingConstants.TOP);
        panel.add(modeLabel);
        panel.add(Box.createVerticalGlue());

        JLabel controls = label("<html>"
                + "Kamera: WASD, Q/E, myš<br>"
                + "Výběr: klik, 1-7<br>"
                + "P: projekce, M: drát/plochy<br>"
                + "U: textura, mezerník: animace<br>"
                + "Šipky: transformace, PgUp/PgDn: osa Z"
                + "</html>", 12, false);
        controls.setForeground(new Color(205, 212, 222));
        panel.add(controls);
        return panel;
    }

    private void addTransformButton(JPanel panel, ButtonGroup group, String text, TransformMode mode) {
        JRadioButton button = new JRadioButton(text);
        button.setForeground(Color.WHITE);
        button.setBackground(new Color(45, 49, 56));
        button.setFocusPainted(false);
        button.setHorizontalAlignment(SwingConstants.CENTER);
        button.addActionListener(e -> {
            viewport.setTransformMode(mode);
            viewport.requestFocusInWindow();
            refreshUi();
        });
        if (mode == TransformMode.TRANSLATE) {
            button.setSelected(true);
        }
        group.add(button);
        panel.add(button);
    }

    private JToggleButton toggle(String text, boolean selected, Runnable action) {
        JToggleButton button = new JToggleButton(text, selected);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.setFocusPainted(false);
        button.addActionListener(e -> {
            action.run();
            viewport.requestFocusInWindow();
        });
        return button;
    }

    private JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        button.setFocusPainted(false);
        button.addActionListener(e -> {
            action.run();
            viewport.requestFocusInWindow();
        });
        return button;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = label(text, 12, true);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
        return label;
    }

    private JLabel label(String text, int size, boolean bold) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(label.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN, (float) size));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private void refreshUi() {
        if (entityCombo != null && entityCombo.getSelectedIndex() != scene.selectedIndex) {
            entityCombo.setSelectedIndex(scene.selectedIndex);
        }
        if (projectionToggle != null) {
            projectionToggle.setSelected(scene.perspective);
            projectionToggle.setText(scene.perspective ? "Perspektiva (P)" : "Pravoúhlá (P)");
        }
        if (fillToggle != null) {
            fillToggle.setSelected(scene.filled);
            fillToggle.setText(scene.filled ? "Plochy (M)" : "Drátový model (M)");
        }
        if (textureToggle != null) {
            textureToggle.setSelected(scene.selected().textureEnabled);
            textureToggle.setText(scene.selected().textureEnabled ? "Textura zapnutá (U)" : "Textura vypnutá (U)");
            textureToggle.setEnabled(!scene.selected().lightMarker);
        }
        if (animationToggle != null) {
            animationToggle.setSelected(scene.lightAnimation);
            animationToggle.setText(scene.lightAnimation ? "Animace ON" : "Animace OFF");
        }
        if (modeLabel != null) {
            modeLabel.setText("<html><b>Stav</b><br>"
                    + "Aktivní: " + scene.selected().name + "<br>"
                    + "Transformace: " + viewport.getTransformMode().label + "<br>"
                    + "Světlo jde vybrat jako těleso 7 a posouvat přes T + šipky."
                    + "</html>");
        }
        viewport.repaint();
    }
}
