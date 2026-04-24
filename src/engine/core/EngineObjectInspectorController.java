package engine.core;

import engine.math.Vec3;
import engine.ui.UiTheme;
import engine.util.UiBuilder;

import java.awt.Color;
import javax.swing.BorderFactory;
import javax.swing.JTextField;

final class EngineObjectInspectorController {
    private static final Color KEY_BG = new Color(74, 45, 24);
    private static final Color KEY_BORDER = UiTheme.ACCENT;
    private static final Color KEY_FG = new Color(255, 236, 203);
    private static final Color RELEASE_BG = new Color(31, 58, 48);
    private static final Color RELEASE_BORDER = UiTheme.SUCCESS;
    private static final Color RELEASE_FG = new Color(221, 248, 236);

    private EngineObjectInspectorController() {
    }

    static void refreshObjectInspectorValues(Engine engine) {
        if (engine.objectHeaderLabel == null) {
            return;
        }
        Engine.SceneItemRef outlinerRef = EngineSceneInspector.selectedOutlinerRef(engine);
        if (engine.selectedEntity == null) {
            if (engine.selectedLight != null) {
                engine.objectHeaderLabel.setText("Světlo · " + engine.getLightName(engine.selectedLight));
            } else if (engine.selectedForceField != null) {
                engine.objectHeaderLabel.setText("Síla · " + engine.selectedForceField.name);
            } else if (outlinerRef != null && outlinerRef.type == Engine.SceneItemType.WORLD) {
                engine.objectHeaderLabel.setText("Prostředí");
            } else {
                engine.objectHeaderLabel.setText("Bez výběru");
            }
            setItemSectionVisibility(engine, false);
            styleTransformFields(engine, false, false);
            return;
        }
        setItemSectionVisibility(engine, true);

        boolean hasExactTransformKey = engine.sceneTimeline != null
                && engine.sceneTimeline.hasEntityKey(engine.selectedEntity, engine.timelineCurrentFrame);
        boolean hasExactReleaseKey = engine.sceneTimeline != null
                && engine.sceneTimeline.hasEntityReleaseKey(engine.selectedEntity, engine.timelineCurrentFrame);
        StringBuilder header = new StringBuilder(engine.selectedEntity.getName());
        if (engine.objectFocusMode) {
            header.append(" · fokus");
        }
        if (hasExactTransformKey) {
            header.append("  [KLÍČ @ ").append(engine.timelineCurrentFrame).append("]");
        } else if (hasExactReleaseKey) {
            header.append("  [RELEASE @ ").append(engine.timelineCurrentFrame).append("]");
        }
        engine.objectHeaderLabel.setText(header.toString());
        styleTransformFields(engine, hasExactTransformKey, hasExactReleaseKey);

        if (engine.suppressObjectFieldApply) {
            return;
        }
        engine.suppressObjectFieldApply = true;
        try {
            Vec3 pos = engine.selectedEntity.getTransform().getPosition();
            Vec3 rotRad = engine.selectedEntity.getTransform().getEulerAngles();
            Vec3 scl = engine.selectedEntity.getTransform().getScale();

            setFieldIfNotFocused(engine.posXField, engine.formatTransformValue(pos.x));
            setFieldIfNotFocused(engine.posYField, engine.formatTransformValue(pos.y));
            setFieldIfNotFocused(engine.posZField, engine.formatTransformValue(pos.z));

            setFieldIfNotFocused(engine.rotXField, engine.formatTransformValue(Math.toDegrees(rotRad.x)));
            setFieldIfNotFocused(engine.rotYField, engine.formatTransformValue(Math.toDegrees(rotRad.y)));
            setFieldIfNotFocused(engine.rotZField, engine.formatTransformValue(Math.toDegrees(rotRad.z)));

            setFieldIfNotFocused(engine.scaleXField, engine.formatTransformValue(scl.x));
            setFieldIfNotFocused(engine.scaleYField, engine.formatTransformValue(scl.y));
            setFieldIfNotFocused(engine.scaleZField, engine.formatTransformValue(scl.z));
        } finally {
            engine.suppressObjectFieldApply = false;
        }
        engine.syncOutlinerSelectionToCurrentSelection();
    }

    static void applyObjectInspectorValues(Engine engine) {
        if (engine.suppressObjectFieldApply || engine.selectedEntity == null) {
            return;
        }
        if (engine.posXField == null || engine.posYField == null || engine.posZField == null
                || engine.rotXField == null || engine.rotYField == null || engine.rotZField == null
                || engine.scaleXField == null || engine.scaleYField == null || engine.scaleZField == null) {
            return;
        }

        Vec3 oldPos = engine.selectedEntity.getTransform().getPosition();
        Vec3 oldRot = engine.selectedEntity.getTransform().getEulerAngles();
        Vec3 oldScale = engine.selectedEntity.getTransform().getScale();

        double px = engine.parseOrFallback(engine.posXField.getText(), oldPos.x);
        double py = engine.parseOrFallback(engine.posYField.getText(), oldPos.y);
        double pz = engine.parseOrFallback(engine.posZField.getText(), oldPos.z);

        double rxDeg = engine.parseOrFallback(engine.rotXField.getText(), Math.toDegrees(oldRot.x));
        double ryDeg = engine.parseOrFallback(engine.rotYField.getText(), Math.toDegrees(oldRot.y));
        double rzDeg = engine.parseOrFallback(engine.rotZField.getText(), Math.toDegrees(oldRot.z));

        double sx = Math.max(0.01, engine.parseOrFallback(engine.scaleXField.getText(), oldScale.x));
        double sy = Math.max(0.01, engine.parseOrFallback(engine.scaleYField.getText(), oldScale.y));
        double sz = Math.max(0.01, engine.parseOrFallback(engine.scaleZField.getText(), oldScale.z));

        engine.applySceneEdit("Úprava transformace objektu", () -> {
            engine.selectedEntity.getTransform().setPosition(new Vec3(px, py, pz));
            engine.selectedEntity.getTransform().setEulerAngles(
                    Math.toRadians(rxDeg),
                    Math.toRadians(ryDeg),
                    Math.toRadians(rzDeg)
            );
            engine.selectedEntity.getTransform().setScale(new Vec3(sx, sy, sz));
            if (engine.selectedEntity.getRigidBody() != null) {
                engine.selectedEntity.getRigidBody().setVelocity(Vec3.ZERO);
            }
            refreshObjectInspectorValues(engine);
        });
    }

    private static void setFieldIfNotFocused(JTextField field, String value) {
        if (field == null || field.isFocusOwner()) {
            return;
        }
        field.setText(value);
    }

    private static void styleTransformFields(Engine engine, boolean keyed, boolean releaseKey) {
        styleField(engine.posXField, keyed, releaseKey);
        styleField(engine.posYField, keyed, releaseKey);
        styleField(engine.posZField, keyed, releaseKey);
        styleField(engine.rotXField, keyed, releaseKey);
        styleField(engine.rotYField, keyed, releaseKey);
        styleField(engine.rotZField, keyed, releaseKey);
        styleField(engine.scaleXField, keyed, releaseKey);
        styleField(engine.scaleYField, keyed, releaseKey);
        styleField(engine.scaleZField, keyed, releaseKey);
    }

    private static void setItemSectionVisibility(Engine engine, boolean entitySelected) {
        if (engine.itemTransformSection != null) {
            engine.itemTransformSection.setVisible(entitySelected);
        }
        if (engine.itemOperationsSection != null) {
            engine.itemOperationsSection.setVisible(entitySelected);
        }
    }

    private static void styleField(JTextField field, boolean keyed, boolean releaseKey) {
        if (field == null) {
            return;
        }
        if (keyed) {
            field.setBackground(KEY_BG);
            field.setForeground(KEY_FG);
            field.setCaretColor(KEY_FG);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(KEY_BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
            return;
        }
        if (releaseKey) {
            field.setBackground(RELEASE_BG);
            field.setForeground(RELEASE_FG);
            field.setCaretColor(RELEASE_FG);
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(RELEASE_BORDER, 1, true),
                    BorderFactory.createEmptyBorder(4, 6, 4, 6)
            ));
            return;
        }
        UiBuilder.styleInspectorField(field);
    }
}
