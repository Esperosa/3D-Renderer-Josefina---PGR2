package engine.core;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

import engine.material.Material;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialPresets;
import engine.material.PhongMaterial;
import engine.material.TextureMap;
import engine.math.Vec3;
import engine.render.Texture;
import engine.scene.Entity;
import engine.ui.UiStrings;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

final class EngineMaterialDock {
    static final ExecutorService PREVIEW_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MaterialPreviewWorker");
        thread.setDaemon(true);
        return thread;
    });

    private EngineMaterialDock() {
    }

    static void rebuild(Engine engine) {
        if (engine == null || engine.materialDockHostPanel == null) {
            return;
        }
        rebuildInto(engine, engine.materialDockHostPanel);
    }

    static void rebuildInto(Engine engine, JPanel host) {
        if (engine == null || host == null) {
            return;
        }
        host.removeAll();
        host.setLayout(new BorderLayout(0, 8));
        host.setOpaque(false);
        EditorFocusContext.mark(host, EditorFocusContext.MATERIAL_WORKSPACE);

        Entity entity = engine.selectedEntity;
        PhongMaterial material = entity == null ? null : existingPhongMaterial(entity);
        JLabel summaryLabel = new JLabel();
        host.add(buildSummaryBar(engine, entity, material, summaryLabel), BorderLayout.NORTH);
        if (entity == null) {
            summaryLabel.setText(UiStrings.MaterialDock.NO_OBJECT_SELECTED);
            host.add(buildEmptyState(engine, UiStrings.MaterialDock.EMPTY_SELECT_OBJECT), BorderLayout.CENTER);
            host.revalidate();
            host.repaint();
            return;
        }
        if (entity.getMesh() == null) {
            summaryLabel.setText(UiStrings.MaterialDock.NO_MESH_SELECTED);
            host.add(buildEmptyState(engine, UiStrings.MaterialDock.EMPTY_NO_MESH), BorderLayout.CENTER);
            host.revalidate();
            host.repaint();
            return;
        }
        if (material == null) {
            summaryLabel.setText("Materiál není typu Phong");
            JPanel unsupported = buildEmptyState(engine,
                    "Vybraný objekt používá jiný typ materiálu. Pro node workspace proveďte převod na Phong explicitně.");
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 0));
            actions.setOpaque(false);
            actions.add(createMiniButton(engine, "Převést na Phong", () -> {
                engine.applySceneEdit("Převod materiálu na Phong", () -> {
                    Material source = entity.getMaterial();
                    Vec3 base = source != null ? source.getBaseColor() : new Vec3(0.7, 0.7, 0.7);
                    PhongMaterial converted = new PhongMaterial(base, 32.0);
                    if (source != null) {
                        converted.copyFrom(source);
                    }
                    converted.getOrCreateNodeGraph();
                    MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(converted);
                    entity.setMaterial(converted);
                });
                rebuildInto(engine, host);
                engine.rebuildSceneDetailsPanel();
            }));
            unsupported.add(actions, BorderLayout.SOUTH);
            host.add(unsupported, BorderLayout.CENTER);
            host.revalidate();
            host.repaint();
            return;
        }

        MaterialDockSession session = new MaterialDockSession(engine, host, entity, material, summaryLabel);
        host.add(session.buildWorkspace(), BorderLayout.CENTER);
        host.add(session.buildFooter(), BorderLayout.SOUTH);
        session.refreshInspector();
        session.refreshSummary();
        host.revalidate();
        host.repaint();
    }

    static JPanel buildSummaryBar(Engine engine,
                                  Entity entity,
                                  PhongMaterial material,
                                  JLabel subtitleLabel) {
        JPanel bar = new JPanel(new BorderLayout(10, 0));
        bar.setOpaque(true);
        bar.setBackground(UiTheme.PANEL_ELEVATED);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)
        ));

        JPanel left = new JPanel();
        left.setOpaque(false);
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(
                entity == null ? UiStrings.MaterialDock.WORKSPACE_TITLE : UiStrings.MaterialDock.materialTitle(entity.getName()),
                UiTheme.brandIcon(16),
                JLabel.LEFT
        );
        title.setForeground(UiTheme.TEXT_PRIMARY);
        subtitleLabel.setForeground(UiTheme.TEXT_MUTED);
        subtitleLabel.setText(material == null ? UiStrings.Common.NO_MATERIAL : material.getName());
        left.add(title);
        left.add(subtitleLabel);
        bar.add(left, BorderLayout.CENTER);

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(createMiniButton(engine, UiStrings.Dock.TIMELINE, engine::showTimelineWorkspace));
        actions.add(createMiniButton(engine, UiStrings.Common.OPEN_SCENE_TAB, () -> engine.window.selectRightTab("Scene")));
        bar.add(actions, BorderLayout.EAST);
        return bar;
    }

    static JPanel buildEmptyState(Engine engine, String message) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(true);
        panel.setBackground(UiTheme.PANEL_INSET);

        JPanel card = new JPanel();
        card.setOpaque(true);
        card.setBackground(UiTheme.PANEL_ELEVATED);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(UiTheme.BORDER_SUBTLE, 1, true),
                BorderFactory.createEmptyBorder(18, 18, 18, 18)
        ));
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        JLabel title = new JLabel(UiStrings.MaterialDock.EMPTY_STATE_TITLE, UiTheme.brandIcon(16), JLabel.LEFT);
        title.setForeground(UiTheme.TEXT_PRIMARY);
        card.add(title);
        card.add(Box.createRigidArea(new Dimension(0, 8)));
        JLabel body = new JLabel(message);
        body.setForeground(UiTheme.TEXT_SECONDARY);
        card.add(body);
        card.add(Box.createRigidArea(new Dimension(0, 14)));
        card.add(createMiniButton(engine, UiStrings.Common.BACK_TO_TIMELINE, engine::showTimelineWorkspace));

        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 18, 18));
        wrap.setOpaque(false);
        wrap.add(card);
        panel.add(wrap, BorderLayout.NORTH);
        return panel;
    }

    static JButton createMiniButton(Engine engine, String label, Runnable action) {
        JButton button = new JButton(label);
        UiBuilder.styleGhostButton(button);
        button.setPreferredSize(new Dimension(Math.max(120, button.getPreferredSize().width), UiTheme.COMPACT_BUTTON_HEIGHT));
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, UiTheme.COMPACT_BUTTON_HEIGHT));
        button.addActionListener(e -> {
            if (action != null) {
                action.run();
            }
            engine.focusCanvas();
        });
        return button;
    }

    static JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(UiTheme.ACCENT_GLOW);
        label.setBorder(BorderFactory.createEmptyBorder(6, 0, 4, 0));
        return label;
    }

    static JLabel infoLabel(String text) {
        return UiBuilder.helperText(text);
    }

    static String textureSummary(String label, TextureMap map) {
        if (map == null || !map.hasTexture()) {
            return label + ": žádná";
        }
        Texture texture = map.getTexture();
        String uv = map.getTexCoord() > 0 ? "UV1" : "UV0";
        return label
                + ": "
                + texture.getWidth()
                + "x"
                + texture.getHeight()
                + " | "
                + uv
                + " | "
                + (map.isLinear() ? "lineární" : "nearest");
    }

    static String formatShort(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    static void markCustom(PhongMaterial material) {
        if (material != null) {
            material.setPresetName(MaterialPresets.Preset.CUSTOM.id());
        }
    }

    static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    static Vec3 clampColor(Vec3 color) {
        if (color == null) {
            return new Vec3(1.0, 1.0, 1.0);
        }
        return new Vec3(clamp01(color.x), clamp01(color.y), clamp01(color.z));
    }

    static PhongMaterial.AlphaMode parseAlphaMode(String text) {
        if ("MASK".equalsIgnoreCase(text)) {
            return PhongMaterial.AlphaMode.MASK;
        }
        if ("BLEND".equalsIgnoreCase(text)) {
            return PhongMaterial.AlphaMode.BLEND;
        }
        return PhongMaterial.AlphaMode.OPAQUE;
    }

    static String[] enumNames(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].name();
        }
        return out;
    }

    static PhongMaterial ensurePhongMaterial(Entity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getMaterial() instanceof PhongMaterial) {
            PhongMaterial material = (PhongMaterial) entity.getMaterial();
            if (!material.hasNodeGraph()) {
                material.getOrCreateNodeGraph();
                MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
            } else {
                MaterialGraphAuthoring.syncCompatibilityBindings(material);
            }
            return material;
        }
        Vec3 base = entity.getMaterial() != null ? entity.getMaterial().getBaseColor() : new Vec3(0.7, 0.7, 0.7);
        PhongMaterial material = new PhongMaterial(base, 32.0);
        if (entity.getMaterial() != null) {
            material.copyFrom(entity.getMaterial());
        }
        material.getOrCreateNodeGraph();
        MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
        entity.setMaterial(material);
        return material;
    }

    static PhongMaterial existingPhongMaterial(Entity entity) {
        if (entity == null || !(entity.getMaterial() instanceof PhongMaterial)) {
            return null;
        }
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        if (!material.hasNodeGraph()) {
            material.getOrCreateNodeGraph();
            MaterialGraphAuthoring.syncGraphDefaultsFromMaterial(material);
        } else {
            MaterialGraphAuthoring.syncCompatibilityBindings(material);
        }
        return material;
    }
}
