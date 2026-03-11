package engine.core;

import engine.math.Mat4;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.ui.UiStrings;
import engine.util.OverlayDrawUtil;
import engine.util.SceneOverlayIconDrawer;
import engine.util.ScreenProjectionUtil;

import java.util.ArrayList;
import java.util.List;

final class EngineViewportOverlay {
    private EngineViewportOverlay() {
    }

    static Vec3 selectionPivotPosition(Engine engine) {
        if (engine.selectedEntity != null) {
            return engine.selectedEntity.getTransform().getPosition();
        }
        if (engine.selectedLight != null) {
            if (engine.selectedLight instanceof PointLight) {
                return ((PointLight) engine.selectedLight).getPosition();
            }
            if (engine.selectedLight instanceof DirectionalLight) {
                return directionalLightAnchorWorld(engine, directionalSlotForLight(engine, engine.selectedLight));
            }
        }
        if (engine.selectedForceField != null) {
            return engine.selectedForceField.position;
        }
        return null;
    }

    static double selectionVisualScale(Engine engine) {
        if (engine.selectedEntity != null) {
            Vec3 scale = engine.selectedEntity.getTransform().getScale();
            return Math.max(0.35, (Math.abs(scale.x) + Math.abs(scale.y) + Math.abs(scale.z)) / 3.0);
        }
        if (engine.selectedLight != null) {
            if (engine.selectedLight instanceof ConeLight) {
                return Math.max(0.4, ((ConeLight) engine.selectedLight).getConeAngleDegrees() / 50.0);
            }
            if (engine.selectedLight instanceof AreaLight) {
                return Math.max(0.4, ((AreaLight) engine.selectedLight).getSpreadAngleDegrees() / 110.0);
            }
            return Math.max(0.4, 0.35 + engine.selectedLight.getIntensity() * 0.45);
        }
        if (engine.selectedForceField != null) {
            return Math.max(0.35, Math.min(3.0, 0.25 + engine.selectedForceField.radius * 0.15));
        }
        return 1.0;
    }

    static void drawEditorDebugOverlays(Engine engine, FrameBuffer fb) {
        if (fb == null || engine.camera == null) {
            syncWorldAxisWidget(engine, false);
            return;
        }
        syncWorldAxisWidget(engine, engine.editorOverlayEnabled);
        if (isViewingThroughOutputCamera(engine)) {
            drawOutputFrameGuide(engine, fb);
        }
        drawOutputCameraWireOverlay(engine, fb);
        if (engine.editorOverlayEnabled) {
            if (engine.mouseCaptured) {
                drawCrosshair(fb, 0xFFE0E7F4, 0xFF0A111A);
            }
            drawSceneItemWireIcons(engine, fb);
            if (engine.selectionSupportsTransform()
                    && engine.navigationPreset == Engine.NavigationPreset.BLENDER
                    && !engine.mouseCaptured) {
                drawSelectedTransformGizmo(engine, fb);
            }
        }
    }

    static boolean isViewingThroughOutputCamera(Engine engine) {
        return CameraViewUtil.isViewingThroughOutputCamera(engine.outputCameraEntity, engine.camera);
    }

    static void drawOutputCameraWireOverlay(Engine engine, FrameBuffer fb) {
        if (engine.outputCameraEntity == null || engine.camera == null) {
            return;
        }
        if (engine.navigationPreset != Engine.NavigationPreset.BLENDER) {
            return;
        }
        if (engine.outputRenderController.isRenderInProgress()) {
            return;
        }
        if (isViewingThroughOutputCamera(engine)) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();

        Vec3[] local = outputCameraWireLocal();
        int[][] edges = outputCameraWireEdges();

        Mat4 model = engine.outputCameraEntity.getWorldMatrix();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        int[][] projected = new int[local.length][2];
        boolean[] valid = new boolean[local.length];

        for (int i = 0; i < local.length; i++) {
            Vec3 world = model.transformPoint(local[i]);
            int[] out = new int[2];
            if (projectWorldPoint(world, vp, w, h, out)) {
                projected[i][0] = out[0];
                projected[i][1] = out[1];
                valid[i] = true;
            }
        }

        for (int i = 0; i < edges.length; i++) {
            int a = edges[i][0];
            int b = edges[i][1];
            if (!valid[a] || !valid[b]) {
                continue;
            }
            int color = (i < 8) ? 0xFF6FC8FF : 0xFF59F0A2;
            OverlayDrawUtil.drawLine(
                    pixels, w, h, projected[a][0], projected[a][1], projected[b][0], projected[b][1], color);
        }
    }

    static Engine.SceneItemRef pickOutputCameraWireUnderMouse(Engine engine, int mouseX, int mouseY) {
        if (engine == null
                || engine.outputCameraEntity == null
                || engine.camera == null
                || engine.frameBuffer == null) {
            return null;
        }
        if (engine.navigationPreset != Engine.NavigationPreset.BLENDER) {
            return null;
        }
        if (engine.outputRenderController.isRenderInProgress()) {
            return null;
        }
        if (isViewingThroughOutputCamera(engine)) {
            return null;
        }

        int fx = canvasToFramebufferX(engine, mouseX);
        int fy = canvasToFramebufferY(engine, mouseY);
        int w = engine.frameBuffer.getWidth();
        int h = engine.frameBuffer.getHeight();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());

        Vec3[] local = outputCameraWireLocal();
        int[][] edges = outputCameraWireEdges();
        Mat4 model = engine.outputCameraEntity.getWorldMatrix();
        int[][] projected = new int[local.length][2];
        boolean[] valid = new boolean[local.length];
        double[] depth = new double[local.length];

        for (int i = 0; i < local.length; i++) {
            Vec3 world = model.transformPoint(local[i]);
            int[] out = new int[2];
            double[] z = new double[1];
            if (projectWorldPointWithDepth(world, vp, w, h, out, z)) {
                projected[i][0] = out[0];
                projected[i][1] = out[1];
                depth[i] = z[0];
                valid[i] = true;
            }
        }

        final double pickRadius = 6.5;
        double bestScore = Double.POSITIVE_INFINITY;
        for (int[] edge : edges) {
            int a = edge[0];
            int b = edge[1];
            if (!valid[a] || !valid[b]) {
                continue;
            }
            double d = distanceToSegment(fx, fy, projected[a][0], projected[a][1], projected[b][0], projected[b][1]);
            if (d > pickRadius) {
                continue;
            }
            double z = Math.min(depth[a], depth[b]);
            double score = z * 100000.0 + d;
            if (score < bestScore) {
                bestScore = score;
            }
        }
        if (!Double.isFinite(bestScore)) {
            return null;
        }
        Engine.SceneItemRef ref = new Engine.SceneItemRef();
        ref.type = Engine.SceneItemType.ENTITY;
        ref.entity = engine.outputCameraEntity;
        return ref;
    }

    static void drawOutputFrameGuide(Engine engine, FrameBuffer fb) {
        OutputRenderController.Settings out = engine.outputRenderController.settings();
        if (out.width <= 0 || out.height <= 0) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();

        double frameAspect = (double) out.width / (double) out.height;
        double screenAspect = (double) w / (double) h;
        int frameW;
        int frameH;
        if (screenAspect > frameAspect) {
            frameH = h;
            frameW = (int) Math.round(frameH * frameAspect);
        } else {
            frameW = w;
            frameH = (int) Math.round(frameW / frameAspect);
        }
        frameW = Math.max(2, Math.min(w, frameW));
        frameH = Math.max(2, Math.min(h, frameH));
        int x0 = (w - frameW) / 2;
        int y0 = (h - frameH) / 2;
        int x1 = x0 + frameW - 1;
        int y1 = y0 + frameH - 1;

        OverlayDrawUtil.darkenRegion(pixels, w, h, 0, 0, w, y0, 0.52);
        OverlayDrawUtil.darkenRegion(pixels, w, h, 0, y1 + 1, w, h, 0.52);
        OverlayDrawUtil.darkenRegion(pixels, w, h, 0, y0, x0, y1 + 1, 0.52);
        OverlayDrawUtil.darkenRegion(pixels, w, h, x1 + 1, y0, w, y1 + 1, 0.52);
        OverlayDrawUtil.drawDashedRect(pixels, w, h, x0, y0, x1, y1, 0xFF68C9FF, 0xFFFFA84E, 8, 4);
    }

    private static void drawCrosshair(FrameBuffer fb, int color, int shadowColor) {
        OverlayDrawUtil.drawCrosshair(fb.getColorBuffer(), fb.getWidth(), fb.getHeight(), color, shadowColor);
    }

    private static void syncWorldAxisWidget(Engine engine, boolean visible) {
        if (engine == null || engine.window == null) {
            return;
        }
        engine.window.setWorldAxisWidgetVisible(visible);
        if (!visible || engine.camera == null) {
            return;
        }
        Vec3 camRight = engine.camera.getRight().normalize();
        Vec3 camUp = engine.camera.getUp().normalize();
        engine.window.setWorldAxisWidgetVectors(
                camRight.x, camRight.y, camRight.z,
                camUp.x, camUp.y, camUp.z);
    }

    static void drawSceneItemWireIcons(Engine engine, FrameBuffer fb) {
        if (fb == null || engine.scene == null || engine.camera == null) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        List<Engine.OverlayIcon> icons = collectOverlayIcons(engine, vp, w, h, true);
        if (icons.isEmpty()) {
            return;
        }

        for (Engine.OverlayIcon icon : icons) {
            if (icon == null || icon.ref == null) {
                continue;
            }
            if (icon.ref.type == Engine.SceneItemType.LIGHT) {
                drawLightIcon(engine, pixels, w, h, vp, icon);
            } else if (icon.ref.type == Engine.SceneItemType.FORCE_FIELD) {
                drawForceIcon(engine, pixels, w, h, icon);
            }
        }
    }

    static List<Engine.OverlayIcon> collectOverlayIcons(
            Engine engine, Mat4 vp, int width, int height, boolean onlyViewEnabled) {
        List<Engine.OverlayIcon> icons = new ArrayList<>();
        if (engine.scene == null || vp == null) {
            return icons;
        }

        int directionalSlot = 0;
        for (Light light : engine.scene.getLights()) {
            if (light == null) {
                continue;
            }
            Engine.SceneItemState state = engine.stateFor(light);
            if (onlyViewEnabled && !state.visibleInView) {
                continue;
            }
            Vec3 worldPos;
            int radius;
            if (light instanceof DirectionalLight) {
                worldPos = directionalLightAnchorWorld(engine, directionalSlot++);
                radius = 11;
            } else if (light instanceof PointLight) {
                worldPos = ((PointLight) light).getPosition();
                radius = (light instanceof ConeLight) ? 14 : ((light instanceof AreaLight) ? 12 : 10);
            } else {
                continue;
            }

            Engine.OverlayIcon icon = projectOverlayIcon(worldPos, vp, width, height, radius);
            if (icon == null) {
                continue;
            }
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.LIGHT;
            ref.light = light;
            icon.ref = ref;
            icons.add(icon);
        }

        for (Engine.ForceField field : engine.forceFields) {
            if (field == null) {
                continue;
            }
            Engine.SceneItemState state = engine.stateFor(field);
            if (onlyViewEnabled && !state.visibleInView) {
                continue;
            }
            int radius;
            switch (field.type) {
                case VECTOR:
                    radius = 11;
                    break;
                case POINT:
                    radius = 13;
                    break;
                case TURBULENCE:
                    radius = 12;
                    break;
                default:
                    radius = 10;
                    break;
            }
            Engine.OverlayIcon icon = projectOverlayIcon(field.position, vp, width, height, radius);
            if (icon == null) {
                continue;
            }
            Engine.SceneItemRef ref = new Engine.SceneItemRef();
            ref.type = Engine.SceneItemType.FORCE_FIELD;
            ref.forceField = field;
            icon.ref = ref;
            icons.add(icon);
        }
        return icons;
    }

    static Engine.OverlayIcon projectOverlayIcon(Vec3 worldPos, Mat4 vp, int width, int height, int radius) {
        if (worldPos == null) {
            return null;
        }
        int[] out = new int[2];
        double[] depth = new double[1];
        if (!projectWorldPointWithDepth(worldPos, vp, width, height, out, depth)) {
            return null;
        }
        Engine.OverlayIcon icon = new Engine.OverlayIcon();
        icon.x = out[0];
        icon.y = out[1];
        icon.radius = Math.max(6, radius);
        icon.depth = depth[0];
        return icon;
    }

    static Vec3 directionalLightAnchorWorld(Engine engine, int slot) {
        Vec3 base = engine.camera.getPosition()
                .add(engine.camera.getForward().mul(2.7))
                .add(engine.camera.getUp().mul(0.85));
        int column = slot % 3;
        int row = slot / 3;
        double xOffset = (column - 1) * 0.85;
        double yOffset = row * 0.55;
        return base.add(engine.camera.getRight().mul(xOffset)).add(engine.camera.getUp().mul(yOffset));
    }

    static void drawLightIcon(Engine engine, int[] pixels, int w, int h, Mat4 vp, Engine.OverlayIcon icon) {
        Light light = icon.ref.light;
        if (light == null) {
            return;
        }
        SceneOverlayIconDrawer.drawLightIcon(
                pixels, w, h, vp, light, light == engine.selectedLight, icon.x, icon.y, icon.radius, engine.camera);
    }

    static void drawForceIcon(Engine engine, int[] pixels, int w, int h, Engine.OverlayIcon icon) {
        Engine.ForceField field = icon.ref.forceField;
        if (field == null) {
            return;
        }
        boolean selected = field == engine.selectedForceField;
        int cx = icon.x;
        int cy = icon.y;
        int r = icon.radius;
        switch (field.type) {
            case VECTOR: {
                SceneOverlayIconDrawer.drawVectorForceIcon(
                        pixels, w, h, cx, cy, r, field.direction, selected, engine.camera);
                break;
            }
            case POINT: {
                SceneOverlayIconDrawer.drawPointForceIcon(
                        pixels, w, h, cx, cy, r, field.attract, selected);
                break;
            }
            case TURBULENCE:
            default: {
                SceneOverlayIconDrawer.drawTurbulenceForceIcon(pixels, w, h, cx, cy, r, selected);
                break;
            }
        }
    }

    static Engine.SceneItemRef pickOverlayItemUnderMouse(Engine engine, int mouseX, int mouseY) {
        if (engine.frameBuffer == null || engine.camera == null || engine.scene == null) {
            return null;
        }
        int fx = canvasToFramebufferX(engine, mouseX);
        int fy = canvasToFramebufferY(engine, mouseY);
        int w = engine.frameBuffer.getWidth();
        int h = engine.frameBuffer.getHeight();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        List<Engine.OverlayIcon> icons = collectOverlayIcons(engine, vp, w, h, true);

        Engine.OverlayIcon best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Engine.OverlayIcon icon : icons) {
            if (icon == null || icon.ref == null) {
                continue;
            }
            int dx = fx - icon.x;
            int dy = fy - icon.y;
            int r = icon.radius + 4;
            int distSq = dx * dx + dy * dy;
            if (distSq > r * r) {
                continue;
            }
            double score = icon.depth * 100000.0 + distSq;
            if (score < bestScore) {
                bestScore = score;
                best = icon;
            }
        }
        return best == null ? null : best.ref;
    }

    static void drawSelectedTransformGizmo(Engine engine, FrameBuffer fb) {
        if (!engine.selectionSupportsTransform() || engine.camera == null) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();
        Engine.GizmoScreenData gizmo = computeGizmoScreenData(engine, w, h);
        if (gizmo == null || !gizmo.valid || gizmo.axes == null) {
            return;
        }

        boolean uniformScaleActive = engine.transformMode == Engine.TransformMode.SCALE
                && engine.axisConstraint == Engine.AxisConstraint.NONE;
        for (Engine.GizmoAxisData axis : gizmo.axes) {
            if (axis == null || !axis.valid) {
                continue;
            }
            boolean axisActive = engine.transformMode != Engine.TransformMode.NONE
                    && engine.axisConstraint != Engine.AxisConstraint.NONE
                    && engine.axisConstraint == axis.axis;
            int lineColor = axisActive ? brightenColor(axis.color, 0.30) : axis.color;
            int arrowColor = axisActive ? brightenColor(axis.color, 0.45) : brightenColor(axis.color, 0.10);
            int scaleColor = axisActive ? brightenColor(axis.color, 0.40) : brightenColor(axis.color, 0.20);
            int ringColor = axisActive ? brightenColor(axis.color, 0.28) : dimColor(axis.color, 0.16);
            int rotateColor = axisActive ? brightenColor(axis.color, 0.45) : brightenColor(axis.color, 0.08);

            OverlayDrawUtil.drawCircleOutline(pixels, w, h, gizmo.centerX, gizmo.centerY, axis.rotateRadius, ringColor);
            OverlayDrawUtil.drawLine(pixels, w, h, gizmo.centerX, gizmo.centerY, axis.endX, axis.endY, lineColor);
            OverlayDrawUtil.drawArrowHead(
                    pixels, w, h, gizmo.centerX, gizmo.centerY, axis.moveX, axis.moveY, arrowColor, 8.0);
            OverlayDrawUtil.drawSquareBorder(pixels, w, h, axis.scaleX - 4, axis.scaleY - 4, 9, 9, scaleColor);
            OverlayDrawUtil.drawCircleOutline(pixels, w, h, axis.rotateX, axis.rotateY, 5, rotateColor);
            if (axisActive) {
                OverlayDrawUtil.drawCircleOutline(pixels, w, h, axis.rotateX, axis.rotateY, 7, 0xFFEAF2FF);
            }
        }
        int centerScaleColor = uniformScaleActive ? 0xFFFFE08A : 0xFFF1D37B;
        OverlayDrawUtil.drawSquareBorder(
                pixels, w, h, gizmo.centerX - 4, gizmo.centerY - 4, 9, 9, centerScaleColor);
        OverlayDrawUtil.drawSquare(pixels, w, h, gizmo.centerX - 1, gizmo.centerY - 1, 3, 3, 0xFFEAF2FF);
    }

    static Engine.GizmoScreenData computeGizmoScreenData(Engine engine, int width, int height) {
        if (!engine.selectionSupportsTransform() || engine.camera == null) {
            return null;
        }
        Engine.GizmoScreenData out = new Engine.GizmoScreenData();
        out.valid = false;
        out.axes = new Engine.GizmoAxisData[3];

        Vec3 center = selectionPivotPosition(engine);
        if (center == null) {
            return out;
        }
        double axisLen = Math.max(0.50, selectionVisualScale(engine)) * 1.3;
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        int[] c = new int[2];
        if (!projectWorldPoint(center, vp, width, height, c)) {
            return out;
        }
        out.valid = true;
        out.centerX = c[0];
        out.centerY = c[1];

        fillGizmoAxis(engine, out, 0, Engine.AxisConstraint.X, 0xFFE25454,
                center.add(new Vec3(axisLen, 0.0, 0.0)), vp, width, height);
        fillGizmoAxis(engine, out, 1, Engine.AxisConstraint.Y, 0xFF66D66F,
                center.add(new Vec3(0.0, axisLen, 0.0)), vp, width, height);
        fillGizmoAxis(engine, out, 2, Engine.AxisConstraint.Z, 0xFF67A9FF,
                center.add(new Vec3(0.0, 0.0, axisLen)), vp, width, height);
        return out;
    }

    static void fillGizmoAxis(
            Engine engine,
            Engine.GizmoScreenData gizmo,
            int slot,
            Engine.AxisConstraint axis,
            int color,
            Vec3 worldEnd,
            Mat4 vp,
            int width,
            int height) {
        Engine.GizmoAxisData data = new Engine.GizmoAxisData();
        data.axis = axis;
        data.color = color;
        data.valid = false;
        gizmo.axes[slot] = data;

        int[] end = new int[2];
        if (!projectWorldPoint(worldEnd, vp, width, height, end)) {
            return;
        }
        double dx = end[0] - gizmo.centerX;
        double dy = end[1] - gizmo.centerY;
        double len = Math.hypot(dx, dy);
        if (len < 1e-5) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;

        data.endX = end[0];
        data.endY = end[1];
        data.moveX = end[0];
        data.moveY = end[1];
        data.scaleX = (int) Math.round(gizmo.centerX + ux * (len + 12.0));
        data.scaleY = (int) Math.round(gizmo.centerY + uy * (len + 12.0));
        data.rotateRadius = (int) Math.max(14, Math.round(len * (0.72 + 0.10 * slot) + 8.0));
        data.rotateX = (int) Math.round(gizmo.centerX + ux * data.rotateRadius);
        data.rotateY = (int) Math.round(gizmo.centerY + uy * data.rotateRadius);
        data.valid = true;
    }

    static boolean projectWorldPoint(Vec3 world, Mat4 vp, int width, int height, int[] out) {
        return ScreenProjectionUtil.projectWorldPoint(world, vp, width, height, out);
    }

    static boolean projectWorldPointWithDepth(Vec3 world, Mat4 vp, int width, int height,
                                              int[] out, double[] depthOut) {
        return ScreenProjectionUtil.projectWorldPointWithDepth(world, vp, width, height, out, depthOut);
    }

    static boolean tryActivateGizmoHandleAtCanvas(Engine engine, int mouseX, int mouseY) {
        if (engine.navigationPreset != Engine.NavigationPreset.BLENDER
                || !engine.selectionSupportsTransform()
                || engine.frameBuffer == null) {
            return false;
        }
        Engine.GizmoScreenData gizmo = computeGizmoScreenData(
                engine, engine.frameBuffer.getWidth(), engine.frameBuffer.getHeight());
        if (gizmo == null || !gizmo.valid || gizmo.axes == null) {
            return false;
        }

        int fx = canvasToFramebufferX(engine, mouseX);
        int fy = canvasToFramebufferY(engine, mouseY);
        final int moveRadiusSq = 8 * 8;
        final int scaleRadiusSq = 9 * 9;
        final int centerUniformScaleRadiusSq = 8 * 8;
        final int rotateBand = 4;

        if (distanceSq(fx, fy, gizmo.centerX, gizmo.centerY) <= centerUniformScaleRadiusSq) {
            startGizmoTransform(engine, Engine.TransformMode.SCALE, Engine.AxisConstraint.NONE);
            return true;
        }

        for (Engine.GizmoAxisData axis : gizmo.axes) {
            if (axis == null || !axis.valid) {
                continue;
            }
            if (distanceSq(fx, fy, axis.moveX, axis.moveY) <= moveRadiusSq) {
                startGizmoTransform(engine, Engine.TransformMode.MOVE, axis.axis);
                return true;
            }
            if (distanceSq(fx, fy, axis.scaleX, axis.scaleY) <= scaleRadiusSq) {
                startGizmoTransform(engine, Engine.TransformMode.SCALE, axis.axis);
                return true;
            }
        }

        double distToCenter = Math.hypot(fx - gizmo.centerX, fy - gizmo.centerY);
        Engine.AxisConstraint rotateAxis = Engine.AxisConstraint.NONE;
        double bestRing = Double.MAX_VALUE;
        for (Engine.GizmoAxisData axis : gizmo.axes) {
            if (axis == null || !axis.valid) {
                continue;
            }
            double ringDelta = Math.abs(distToCenter - axis.rotateRadius);
            if (ringDelta <= rotateBand && ringDelta < bestRing) {
                bestRing = ringDelta;
                rotateAxis = axis.axis;
            }
        }
        if (rotateAxis != Engine.AxisConstraint.NONE) {
            startGizmoTransform(engine, Engine.TransformMode.ROTATE, rotateAxis);
            return true;
        }
        return false;
    }

    static void startGizmoTransform(Engine engine, Engine.TransformMode mode, Engine.AxisConstraint axis) {
        engine.beginSceneGesture(switch (mode) {
            case MOVE -> engine.selectedEntity != null ? "Přesun objektu" : engine.selectedLight != null ? "Přesun světla" : "Přesun síly";
            case ROTATE -> engine.selectedEntity != null ? "Rotace objektu" : engine.selectedLight != null ? "Rotace světla" : "Rotace síly";
            case SCALE -> engine.selectedEntity != null ? "Změna měřítka objektu" : engine.selectedLight != null ? "Úprava světla" : "Úprava síly";
            case NONE -> "Transformace";
        });
        engine.transformMode = mode;
        engine.axisConstraint = axis;
        engine.objectFocusMode = true;
        engine.gizmoDragActive = true;
        engine.draggingSelectedObject = false;
        System.out.println("Gizmo: " + mode + " " + axis);
    }

    static int canvasToFramebufferX(Engine engine, int canvasX) {
        if (engine.window == null || engine.frameBuffer == null) {
            return canvasX;
        }
        int cw = Math.max(1, engine.window.getCanvas().getWidth());
        int fw = Math.max(1, engine.frameBuffer.getWidth());
        double t = cw > 1 ? (double) canvasX / (double) (cw - 1) : 0.0;
        int x = (int) Math.round(t * (fw - 1));
        if (x < 0) {
            return 0;
        }
        return Math.min(fw - 1, x);
    }

    static int canvasToFramebufferY(Engine engine, int canvasY) {
        if (engine.window == null || engine.frameBuffer == null) {
            return canvasY;
        }
        int ch = Math.max(1, engine.window.getCanvas().getHeight());
        int fh = Math.max(1, engine.frameBuffer.getHeight());
        double t = ch > 1 ? (double) canvasY / (double) (ch - 1) : 0.0;
        int y = (int) Math.round(t * (fh - 1));
        if (y < 0) {
            return 0;
        }
        return Math.min(fh - 1, y);
    }

    static int distanceSq(int x0, int y0, int x1, int y1) {
        return OverlayDrawUtil.distanceSq(x0, y0, x1, y1);
    }

    static int brightenColor(int color, double amount) {
        return OverlayDrawUtil.brightenColor(color, amount);
    }

    static int dimColor(int color, double amount) {
        return OverlayDrawUtil.dimColor(color, amount);
    }

    private static Vec3[] outputCameraWireLocal() {
        return new Vec3[]{
                new Vec3(-0.22, -0.12, 0.20),
                new Vec3(0.22, -0.12, 0.20),
                new Vec3(0.22, 0.12, 0.20),
                new Vec3(-0.22, 0.12, 0.20),
                new Vec3(-0.34, -0.22, -0.30),
                new Vec3(0.34, -0.22, -0.30),
                new Vec3(0.34, 0.22, -0.30),
                new Vec3(-0.34, 0.22, -0.30),
                new Vec3(0.0, 0.0, -0.48)
        };
    }

    private static int[][] outputCameraWireEdges() {
        return new int[][]{
                {0, 1}, {1, 2}, {2, 3}, {3, 0},
                {4, 5}, {5, 6}, {6, 7}, {7, 4},
                {0, 4}, {1, 5}, {2, 6}, {3, 7},
                {4, 8}, {5, 8}, {6, 8}, {7, 8}
        };
    }

    private static double distanceToSegment(double px, double py, double ax, double ay, double bx, double by) {
        double abx = bx - ax;
        double aby = by - ay;
        double lenSq = abx * abx + aby * aby;
        if (lenSq <= 1e-9) {
            return Math.hypot(px - ax, py - ay);
        }
        double t = ((px - ax) * abx + (py - ay) * aby) / lenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + abx * t;
        double cy = ay + aby * t;
        return Math.hypot(px - cx, py - cy);
    }

    static String[] buildDebugHudLines(Engine engine, double elapsedSeconds) {
        String modeName = engine.activeMode != null ? UiStrings.renderModeLabel(engine.activeMode) : "Žádný";
        String viewportModeName = engine.viewportDisplayedMode != null
                ? UiStrings.renderModeLabel(engine.viewportDisplayedMode)
                : modeName;
        String camMode = engine.cameraController != null ? engine.cameraController.getMode().toString() : "Žádná";
        String navMode = engine.navigationPreset != null ? engine.navigationPreset.toString() : "FPS";
        String projection = engine.orthographicProjection ? "Orto" : "Persp";
        Vec3 pos = engine.camera != null ? engine.camera.getPosition() : Vec3.ZERO;
        String selectedName;
        if (engine.selectedEntity != null) {
            selectedName = engine.selectedEntity.getName();
        } else if (engine.selectedLight != null) {
            selectedName = "Světlo: " + engine.getLightName(engine.selectedLight);
        } else if (engine.selectedForceField != null) {
            selectedName = "Síla: " + engine.selectedForceField.name;
        } else {
            selectedName = "-";
        }
        String perf;
        if (engine.activeMode == RenderMode.PATH_TRACING) {
            perf = "PT spp " + engine.pathTracerRenderer.getAccumulatedSamples();
        } else if (engine.activeMode == RenderMode.RAY_TRACING) {
            perf = "RT spp " + engine.rayTracerRenderer.getAccumulatedSamples();
        } else if (engine.activeMode == RenderMode.HEX_MOSAIC) {
            perf = engine.hexMosaicRenderer.getName();
        } else {
            perf = "MT " + (engine.parallelRasterEnabled ? UiStrings.Common.YES : UiStrings.Common.NO) + " x" + engine.parallelWorkerCount;
        }
        String undo = engine.getUndoActionLabel();
        String redo = engine.getRedoActionLabel();

        return new String[]{
                "REŽIM: " + modeName
                        + (engine.viewportNavigationPreviewActive ? "  VIEW " + viewportModeName + " [FALLBACK]" : "")
                        + (!engine.viewportNavigationPreviewActive && engine.interactiveRenderScaleActive
                        ? "  [ADAPTIVE]" : ""),
                String.format("FPS: %.1f  MS: %.2f", engine.hudFps, engine.hudFrameTimeMs),
                "NAV: " + navMode + "  KAM: " + camMode + "  " + projection,
                String.format("POZ: %.2f %.2f %.2f", pos.x, pos.y, pos.z),
                "VÝBĚR: " + selectedName,
                "OBJEKTY: " + engine.scene.getAllMeshEntities().size() + "  FYZ: "
                        + (engine.physicsEnabled ? UiStrings.Common.YES : UiStrings.Common.NO),
                "ANIMACE: " + (engine.animationPlaybackEnabled ? "PŘEHRÁVÁNÍ" : "PAUZA"),
                "ČAS: " + (engine.timelineEnabled ? UiStrings.Common.YES : UiStrings.Common.NO)
                        + "  F " + engine.timelineCurrentFrame
                        + " [" + engine.timelineStartFrame + ".." + engine.timelineEndFrame + "]"
                        + "  KLÍČE " + engine.sceneTimeline.totalKeyCount(),
                "MĚŘÍTKO: " + String.format("%.2f", engine.effectiveRenderScale())
                        + (engine.interactiveRenderScaleActive ? " [INT]" : "")
                        + (engine.progressiveViewportEnabled ? String.format("  TARGET %.0f", engine.viewportTargetFps) : "")
                        + (engine.viewportCriticalPreviewActive ? "  FALLBACK " + engine.viewportNavigationFallbackMode : "")
                        + (engine.viewDistanceCullingEnabled ? "  RANGE " + String.format("%.0f", engine.viewDistanceLimit) : "")
                        + (engine.sceneImportController.isBusy() ? "  IMPORT" : "")
                        + "  " + perf,
                "VÝSTUP: " + engine.outputRenderController.settings().width
                        + "x" + engine.outputRenderController.settings().height
                        + " " + UiStrings.renderModeLabel(engine.outputRenderController.settings().mode)
                        + (engine.outputRenderController.isRenderInProgress() ? " [RENDER]" : "")
                        + (isViewingThroughOutputCamera(engine) ? " (kamera)" : ""),
                "HISTORIE: Ctrl+Z "
                        + (undo.isBlank() ? "[nic]" : "[" + undo + "]")
                        + "  Ctrl+Shift+Z "
                        + (redo.isBlank() ? "[nic]" : "[" + redo + "]"),
                "ČAS BĚHU: " + String.format("%.1f", elapsedSeconds) + "s"
        };
    }

    private static int directionalSlotForLight(Engine engine, Light light) {
        if (!(light instanceof DirectionalLight) || engine.scene == null) {
            return 0;
        }
        int slot = 0;
        for (Light candidate : engine.scene.getLights()) {
            if (!(candidate instanceof DirectionalLight)) {
                continue;
            }
            if (candidate == light) {
                return slot;
            }
            slot++;
        }
        return 0;
    }

}
