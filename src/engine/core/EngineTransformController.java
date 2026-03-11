package engine.core;

import engine.math.Quaternion;
import engine.math.Vec3;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.PointLight;

import java.awt.event.MouseEvent;

final class EngineTransformController {
    private EngineTransformController() {
    }

    static void activateTransformMode(Engine engine, Engine.TransformMode mode) {
        if (mode == Engine.TransformMode.NONE) {
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.objectFocusMode = false;
            engine.gizmoDragActive = false;
            return;
        }
        if (!engine.selectionSupportsTransform()) {
            System.out.println("Select transformable item first (object/light/force).");
            return;
        }
        engine.transformMode = mode;
        engine.objectFocusMode = true;
        engine.axisConstraint = Engine.AxisConstraint.NONE;
        engine.gizmoDragActive = false;
        System.out.println("Transform mode: " + engine.transformMode + " (X/Y/Z for axis)");
    }

    static void setAxisConstraint(Engine engine, Engine.AxisConstraint axis) {
        engine.axisConstraint = axis;
        if (axis == Engine.AxisConstraint.NONE) {
            System.out.println("Axis constraint: FREE");
        } else {
            System.out.println("Axis constraint: " + axis);
        }
    }

    static void updateTransformTool(Engine engine) {
        if (!engine.selectionSupportsTransform() || engine.transformMode == Engine.TransformMode.NONE) {
            return;
        }
        if (engine.gizmoDragActive && !engine.input.isMouseButtonDown(MouseEvent.BUTTON1)) {
            engine.commitSceneGesture();
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
            return;
        }
        int dx = engine.input.getMouseDX();
        int dy = engine.input.getMouseDY();
        if (dx == 0 && dy == 0) {
            return;
        }

        double speedMul = engine.input.isShiftDown() ? 0.25 : 1.0;
        double deltaScalar = (dx - dy) * 0.01 * speedMul;
        switch (engine.transformMode) {
            case MOVE:
                applyMove(engine, deltaScalar, dx, dy, speedMul);
                break;
            case ROTATE:
                applyRotate(engine, deltaScalar);
                break;
            case SCALE:
                applyScale(engine, deltaScalar);
                break;
            default:
                break;
        }

        if (engine.selectedEntity != null && engine.selectedEntity.getRigidBody() != null) {
            engine.selectedEntity.getRigidBody().setVelocity(Vec3.ZERO);
        }
    }

    private static void applyMove(Engine engine, double deltaScalar, int dx, int dy, double speedMul) {
        engine.beginSceneGesture(historyLabel(engine, Engine.TransformMode.MOVE));
        Vec3 delta;
        switch (engine.axisConstraint) {
            case X:
                delta = new Vec3(deltaScalar, 0.0, 0.0);
                break;
            case Y:
                delta = new Vec3(0.0, deltaScalar, 0.0);
                break;
            case Z:
                delta = new Vec3(0.0, 0.0, deltaScalar);
                break;
            default:
                double s = 0.01 * speedMul;
                delta = engine.camera.getRight().mul(dx * s).add(engine.camera.getUp().mul(-dy * s));
                break;
        }
        applySelectionMove(engine, delta);
    }

    private static void applyRotate(Engine engine, double deltaScalar) {
        engine.beginSceneGesture(historyLabel(engine, Engine.TransformMode.ROTATE));
        Vec3 axis;
        switch (engine.axisConstraint) {
            case X:
                axis = new Vec3(1.0, 0.0, 0.0);
                break;
            case Y:
                axis = new Vec3(0.0, 1.0, 0.0);
                break;
            case Z:
                axis = new Vec3(0.0, 0.0, 1.0);
                break;
            default:
                axis = Vec3.UP;
                break;
        }
        applySelectionRotate(engine, axis, deltaScalar);
    }

    private static void applyScale(Engine engine, double deltaScalar) {
        engine.beginSceneGesture(historyLabel(engine, Engine.TransformMode.SCALE));
        if (engine.selectedEntity == null) {
            applySelectionScale(engine, Math.max(0.05, 1.0 + deltaScalar));
            return;
        }
        Vec3 scale = engine.selectedEntity.getTransform().getScale();
        double factor = Math.max(0.05, 1.0 + deltaScalar);
        Vec3 next;
        switch (engine.axisConstraint) {
            case X:
                next = new Vec3(scale.x * factor, scale.y, scale.z);
                break;
            case Y:
                next = new Vec3(scale.x, scale.y * factor, scale.z);
                break;
            case Z:
                next = new Vec3(scale.x, scale.y, scale.z * factor);
                break;
            default:
                next = new Vec3(scale.x * factor, scale.y * factor, scale.z * factor);
                break;
        }
        engine.selectedEntity.getTransform().setScale(next);
    }

    private static void applySelectionMove(Engine engine, Vec3 delta) {
        if (delta == null) {
            return;
        }
        if (engine.selectedEntity != null) {
            engine.selectedEntity.getTransform().translate(delta);
            return;
        }
        if (engine.selectedLight instanceof PointLight) {
            PointLight pl = (PointLight) engine.selectedLight;
            pl.setPosition(pl.getPosition().add(delta));
            return;
        }
        if (engine.selectedLight instanceof DirectionalLight) {
            DirectionalLight dl = (DirectionalLight) engine.selectedLight;
            Vec3 tweak = new Vec3(delta.x, delta.y, delta.z).mul(0.35);
            Vec3 next = dl.getDirection().add(tweak);
            if (next.lengthSquared() > 1e-10) {
                dl.setDirection(next.normalize());
            }
            return;
        }
        if (engine.selectedForceField != null) {
            engine.selectedForceField.position = engine.selectedForceField.position.add(delta);
        }
    }

    private static void applySelectionRotate(Engine engine, Vec3 axis, double radians) {
        if (axis == null) {
            return;
        }
        if (engine.selectedEntity != null) {
            engine.selectedEntity.getTransform().rotate(axis, radians);
            return;
        }
        if (engine.selectedLight instanceof DirectionalLight) {
            DirectionalLight dl = (DirectionalLight) engine.selectedLight;
            dl.setDirection(rotateDirection(dl.getDirection(), axis, radians));
            return;
        }
        if (engine.selectedLight instanceof AreaLight) {
            AreaLight al = (AreaLight) engine.selectedLight;
            al.setEmissionDirection(rotateDirection(al.getEmissionDirection(), axis, radians));
            return;
        }
        if (engine.selectedLight instanceof ConeLight) {
            ConeLight cl = (ConeLight) engine.selectedLight;
            cl.setDirection(rotateDirection(cl.getDirection(), axis, radians));
            return;
        }
        if (engine.selectedForceField != null && engine.selectedForceField.type == Engine.ForceFieldType.VECTOR) {
            engine.selectedForceField.direction = rotateDirection(engine.selectedForceField.direction, axis, radians);
        }
    }

    private static void applySelectionScale(Engine engine, double factor) {
        if (factor <= 0.0) {
            return;
        }
        if (engine.selectedLight != null) {
            engine.selectedLight.setIntensity(Math.max(0.0, engine.selectedLight.getIntensity() * factor));
            if (engine.selectedLight instanceof ConeLight) {
                ConeLight cl = (ConeLight) engine.selectedLight;
                cl.setConeAngleDegrees(cl.getConeAngleDegrees() * factor);
            } else if (engine.selectedLight instanceof AreaLight) {
                AreaLight al = (AreaLight) engine.selectedLight;
                al.setSpreadAngleDegrees(al.getSpreadAngleDegrees() * factor);
            }
            return;
        }
        if (engine.selectedForceField != null) {
            engine.selectedForceField.strength = Math.max(0.0, engine.selectedForceField.strength * factor);
            if (engine.selectedForceField.type != Engine.ForceFieldType.VECTOR) {
                engine.selectedForceField.radius = Math.max(0.05, engine.selectedForceField.radius * factor);
            }
            if (engine.selectedForceField.type == Engine.ForceFieldType.TURBULENCE) {
                engine.selectedForceField.turbulenceScale =
                        Math.max(0.05, engine.selectedForceField.turbulenceScale * Math.sqrt(factor));
            }
        }
    }

    private static Vec3 rotateDirection(Vec3 direction, Vec3 axis, double radians) {
        Vec3 src = direction == null ? new Vec3(0.0, -1.0, 0.0) : direction;
        if (src.lengthSquared() < 1e-12) {
            src = new Vec3(0.0, -1.0, 0.0);
        }
        Quaternion q = Quaternion.fromAxisAngle(axis.normalize(), radians);
        Vec3 out = q.rotateVector(src).normalize();
        if (out.lengthSquared() < 1e-12) {
            return src.normalize();
        }
        return out;
    }

    private static String historyLabel(Engine engine, Engine.TransformMode mode) {
        if (mode == Engine.TransformMode.MOVE) {
            if (engine.selectedEntity != null) {
                return "Přesun objektu";
            }
            if (engine.selectedLight != null) {
                return "Přesun světla";
            }
            return "Přesun síly";
        }
        if (mode == Engine.TransformMode.ROTATE) {
            if (engine.selectedEntity != null) {
                return "Rotace objektu";
            }
            if (engine.selectedLight != null) {
                return "Rotace světla";
            }
            return "Rotace síly";
        }
        if (engine.selectedEntity != null) {
            return "Změna měřítka objektu";
        }
        if (engine.selectedLight != null) {
            return "Úprava světla";
        }
        return "Úprava síly";
    }
}
