package engine.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import engine.math.Mat4;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Light;
import engine.scene.PointLight;
import engine.util.OverlayDrawUtil;
import engine.util.SceneOverlayIconDrawer;
import engine.util.ScreenProjectionUtil;

final class EngineViewportOverlay {
    private static long cachedSceneStatsVersion = Long.MIN_VALUE;
    private static int cachedSceneObjectCount = 0;
    private static long cachedSceneTriangleCount = 0L;
    private static long cachedSceneVertexCount = 0L;
    private static final double GRID_DEPTH_BIAS = 0.0035;
    private static final double GRID_MAX_ALPHA_MOTION_MULTIPLIER = 0.45;
    private static final int GRID_SEGMENTS_PER_LINE = 36;
    private static final int GRID_MIN_HALF_LINES = 8;
    private static final int GRID_MAX_HALF_LINES = 22;
    private static final int GRID_USABLE_DEPTH_SAMPLES_X = 8;
    private static final int GRID_USABLE_DEPTH_SAMPLES_Y = 6;

    private static final class GridPoint {
        int x;
        int y;
        double depth;
        boolean valid;
    }

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
            drawEditorGroundGrid(engine, fb);
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
        FrameBuffer viewportFb = overlayInteractionFrameBuffer(engine);
        if (engine == null
                || engine.outputCameraEntity == null
                || engine.camera == null
                || viewportFb == null) {
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
        int w = viewportFb.getWidth();
        int h = viewportFb.getHeight();
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

        if (isViewportOverlayBuffer(engine, fb)) {
            int dimOverlay = 0x85000000;
            OverlayDrawUtil.fillRegion(pixels, w, h, 0, 0, w, y0, dimOverlay);
            OverlayDrawUtil.fillRegion(pixels, w, h, 0, y1 + 1, w, h, dimOverlay);
            OverlayDrawUtil.fillRegion(pixels, w, h, 0, y0, x0, y1 + 1, dimOverlay);
            OverlayDrawUtil.fillRegion(pixels, w, h, x1 + 1, y0, w, y1 + 1, dimOverlay);
        } else {
            OverlayDrawUtil.darkenRegion(pixels, w, h, 0, 0, w, y0, 0.52);
            OverlayDrawUtil.darkenRegion(pixels, w, h, 0, y1 + 1, w, h, 0.52);
            OverlayDrawUtil.darkenRegion(pixels, w, h, 0, y0, x0, y1 + 1, 0.52);
            OverlayDrawUtil.darkenRegion(pixels, w, h, x1 + 1, y0, w, y1 + 1, 0.52);
        }
        OverlayDrawUtil.drawDashedRect(pixels, w, h, x0, y0, x1, y1, 0xFF68C9FF, 0xFFFFA84E, 8, 4);
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
        Vec3 camForward = engine.camera.getForward().normalize();
        engine.window.setWorldAxisWidgetVectors(
                camRight.x, camRight.y, camRight.z,
                camUp.x, camUp.y, camUp.z,
                camForward.x, camForward.y, camForward.z);
    }

    private static void drawEditorGroundGrid(Engine engine, FrameBuffer fb) {
        if (engine == null || fb == null || engine.camera == null) {
            return;
        }
        FrameBuffer depthSource = engine.frameBuffer;
        if (!hasUsableDepthBuffer(depthSource)) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();
        if (w <= 1 || h <= 1 || pixels == null) {
            return;
        }

        double floorY = engine.floorEntity != null
                ? engine.floorEntity.getTransform().getPosition().y + 0.006
                : 0.0;
        Vec3 cam = engine.camera.getPosition();
        double spacing = resolveGridSpacing(cam, floorY);
        int halfLines = resolveGridHalfLines(spacing);
        double originX = Math.floor(cam.x / spacing) * spacing;
        double originZ = Math.floor(cam.z / spacing) * spacing;
        double span = halfLines * spacing;
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        boolean overlayTarget = isViewportOverlayBuffer(engine, fb);
        double alphaMultiplier = gridAlphaMultiplier(engine);

        for (int i = -halfLines; i <= halfLines; i++) {
            double x = originX + i * spacing;
            double z = originZ + i * spacing;
            boolean majorX = isMajorGridLine(x, spacing);
            boolean majorZ = isMajorGridLine(z, spacing);
            drawProjectedGridLineDepthTested(
                    pixels, w, h, depthSource, vp, overlayTarget,
                    new Vec3(x, floorY, originZ - span),
                    new Vec3(x, floorY, originZ + span),
                    adjustAlpha(majorX ? 0x64A8C7E7 : 0x2A7D92AA, alphaMultiplier));
            drawProjectedGridLineDepthTested(
                    pixels, w, h, depthSource, vp, overlayTarget,
                    new Vec3(originX - span, floorY, z),
                    new Vec3(originX + span, floorY, z),
                    adjustAlpha(majorZ ? 0x64A8C7E7 : 0x2A7D92AA, alphaMultiplier));
        }
    }

    private static void drawProjectedGridLineDepthTested(
            int[] pixels,
            int w,
            int h,
            FrameBuffer depthSource,
            Mat4 vp,
            boolean overlayTarget,
            Vec3 a,
            Vec3 b,
            int color) {
        GridPoint previous = null;
        for (int i = 0; i <= GRID_SEGMENTS_PER_LINE; i++) {
            double t = i / (double) GRID_SEGMENTS_PER_LINE;
            Vec3 world = new Vec3(
                    a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t);
            GridPoint current = projectGridPoint(world, vp, w, h);
            if (previous != null && previous.valid && current.valid) {
                drawDepthTestedScreenLine(pixels, w, h, depthSource, previous, current, color, overlayTarget);
            }
            previous = current;
        }
    }

    private static GridPoint projectGridPoint(Vec3 world, Mat4 vp, int width, int height) {
        GridPoint point = new GridPoint();
        int[] out = new int[2];
        double[] depth = new double[1];
        if (!projectWorldPointWithDepth(world, vp, width, height, out, depth)) {
            return point;
        }
        point.x = out[0];
        point.y = out[1];
        point.depth = depth[0];
        point.valid = Double.isFinite(point.depth);
        return point;
    }

    private static void drawDepthTestedScreenLine(
            int[] pixels,
            int w,
            int h,
            FrameBuffer depthSource,
            GridPoint a,
            GridPoint b,
            int color,
            boolean overlayTarget) {
        int dx = b.x - a.x;
        int dy = b.y - a.y;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps <= 0) {
            plotGridPixel(pixels, w, h, depthSource, a.x, a.y, a.depth, color, overlayTarget);
            return;
        }
        double invSteps = 1.0 / steps;
        for (int i = 0; i <= steps; i++) {
            double t = i * invSteps;
            int x = (int) Math.round(a.x + dx * t);
            int y = (int) Math.round(a.y + dy * t);
            double depth = a.depth + (b.depth - a.depth) * t;
            plotGridPixel(pixels, w, h, depthSource, x, y, depth, color, overlayTarget);
        }
    }

    private static void plotGridPixel(
            int[] pixels,
            int w,
            int h,
            FrameBuffer depthSource,
            int x,
            int y,
            double gridDepth,
            int color,
            boolean overlayTarget) {
        if (x < 0 || y < 0 || x >= w || y >= h || !Double.isFinite(gridDepth)) {
            return;
        }
        if (!gridDepthVisible(depthSource, x, y, w, h, gridDepth)) {
            return;
        }
        int idx = y * w + x;
        if (overlayTarget) {
            pixels[idx] = color;
        } else {
            double alpha = ((color >>> 24) & 0xFF) / 255.0;
            pixels[idx] = OverlayDrawUtil.blendToColor(pixels[idx], color, alpha);
        }
    }

    private static boolean gridDepthVisible(
            FrameBuffer depthSource,
            int overlayX,
            int overlayY,
            int overlayWidth,
            int overlayHeight,
            double gridDepth) {
        if (depthSource == null || depthSource.getDepthBuffer() == null) {
            return false;
        }
        int depthWidth = depthSource.getWidth();
        int depthHeight = depthSource.getHeight();
        if (depthWidth <= 0 || depthHeight <= 0) {
            return false;
        }
        int sx = scaleIndex(overlayX, overlayWidth, depthWidth);
        int sy = scaleIndex(overlayY, overlayHeight, depthHeight);
        float sceneDepth = depthSource.getDepth(sx, sy);
        if (!Float.isFinite(sceneDepth) || sceneDepth >= 0.9995f) {
            return false;
        }
        return gridDepth <= sceneDepth + GRID_DEPTH_BIAS;
    }

    private static int scaleIndex(int value, int sourceSize, int targetSize) {
        if (targetSize <= 1 || sourceSize <= 1) {
            return 0;
        }
        double t = value / (double) (sourceSize - 1);
        int scaled = (int) Math.round(t * (targetSize - 1));
        return Math.max(0, Math.min(targetSize - 1, scaled));
    }

    private static boolean hasUsableDepthBuffer(FrameBuffer fb) {
        if (fb == null || fb.getDepthBuffer() == null || fb.getWidth() <= 0 || fb.getHeight() <= 0) {
            return false;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        for (int sy = 0; sy < GRID_USABLE_DEPTH_SAMPLES_Y; sy++) {
            int y = GRID_USABLE_DEPTH_SAMPLES_Y <= 1
                    ? h / 2
                    : (int) Math.round(sy * (h - 1) / (double) (GRID_USABLE_DEPTH_SAMPLES_Y - 1));
            for (int sx = 0; sx < GRID_USABLE_DEPTH_SAMPLES_X; sx++) {
                int x = GRID_USABLE_DEPTH_SAMPLES_X <= 1
                        ? w / 2
                        : (int) Math.round(sx * (w - 1) / (double) (GRID_USABLE_DEPTH_SAMPLES_X - 1));
                float depth = fb.getDepth(x, y);
                if (Float.isFinite(depth) && depth < 0.9995f) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double resolveGridSpacing(Vec3 cameraPosition, double floorY) {
        if (cameraPosition == null) {
            return 1.0;
        }
        double height = Math.max(0.0, Math.abs(cameraPosition.y - floorY));
        double raw = Math.max(1.0, height * 0.16);
        if (raw <= 1.5) {
            return 1.0;
        }
        if (raw <= 3.5) {
            return 2.0;
        }
        if (raw <= 7.5) {
            return 5.0;
        }
        return 10.0;
    }

    private static int resolveGridHalfLines(double spacing) {
        double safeSpacing = Math.max(1.0, spacing);
        return Math.max(GRID_MIN_HALF_LINES, Math.min(GRID_MAX_HALF_LINES, (int) Math.round(22.0 / safeSpacing)));
    }

    private static boolean isMajorGridLine(double coordinate, double spacing) {
        double majorSpacing = Math.max(5.0, spacing * 5.0);
        double normalized = coordinate / majorSpacing;
        return Math.abs(normalized - Math.rint(normalized)) < 1e-4;
    }

    private static double gridAlphaMultiplier(Engine engine) {
        if (engine == null) {
            return 1.0;
        }
        boolean motion = engine.viewportCameraMotionActive
                || engine.viewportSceneMotionActive
                || engine.viewportMotionLatchedActive
                || engine.viewportNavigationPreviewActive;
        if (!motion) {
            return 1.0;
        }
        return GRID_MAX_ALPHA_MOTION_MULTIPLIER;
    }

    private static int adjustAlpha(int color, double multiplier) {
        int alpha = (color >>> 24) & 0xFF;
        int next = (int) Math.round(alpha * Math.max(0.0, Math.min(1.0, multiplier)));
        return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, next)) << 24);
    }

    static void drawSceneItemWireIcons(Engine engine, FrameBuffer fb) {
        if (fb == null || engine.scene == null || engine.camera == null) {
            return;
        }
        int w = fb.getWidth();
        int h = fb.getHeight();
        int[] pixels = fb.getColorBuffer();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        double iconPixelScale = overlayPixelScaleFromCanvas(engine, w, h);
        List<Engine.OverlayIcon> icons = collectOverlayIcons(engine, vp, w, h, true, iconPixelScale);
        if (icons.isEmpty()) {
            return;
        }

        for (Engine.OverlayIcon icon : icons) {
            if (icon == null || icon.ref == null) {
                continue;
            }
            if (icon.ref.type == Engine.SceneItemType.LIGHT) {
                drawLightIcon(engine, pixels, w, h, vp, icon, iconPixelScale);
            } else if (icon.ref.type == Engine.SceneItemType.FORCE_FIELD) {
                drawForceIcon(engine, pixels, w, h, icon, iconPixelScale);
            }
        }
    }

    static List<Engine.OverlayIcon> collectOverlayIcons(
            Engine engine, Mat4 vp, int width, int height, boolean onlyViewEnabled, double iconPixelScale) {
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

            Engine.OverlayIcon icon = projectOverlayIcon(worldPos, vp, width, height, radius, iconPixelScale);
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
            Engine.OverlayIcon icon = projectOverlayIcon(field.position, vp, width, height, radius, iconPixelScale);
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

    static Engine.OverlayIcon projectOverlayIcon(Vec3 worldPos, Mat4 vp, int width, int height, int radius,
                                                 double iconPixelScale) {
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
        icon.radius = Math.max(3, scaledPixels(iconPixelScale, radius));
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

    static void drawLightIcon(Engine engine, int[] pixels, int w, int h, Mat4 vp,
                              Engine.OverlayIcon icon, double iconPixelScale) {
        Light light = icon.ref.light;
        if (light == null) {
            return;
        }
        SceneOverlayIconDrawer.drawLightIcon(
                pixels, w, h, vp, light, light == engine.selectedLight, icon.x, icon.y, icon.radius,
                engine.camera, iconPixelScale);
    }

    static void drawForceIcon(Engine engine, int[] pixels, int w, int h, Engine.OverlayIcon icon, double iconPixelScale) {
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
                        pixels, w, h, cx, cy, r, field.direction, selected, engine.camera, iconPixelScale);
                break;
            }
            case POINT: {
                SceneOverlayIconDrawer.drawPointForceIcon(
                        pixels, w, h, cx, cy, r, field.attract, selected, iconPixelScale);
                break;
            }
            case TURBULENCE:
            default: {
                SceneOverlayIconDrawer.drawTurbulenceForceIcon(pixels, w, h, cx, cy, r, selected, iconPixelScale);
                break;
            }
        }
    }

    static Engine.SceneItemRef pickOverlayItemUnderMouse(Engine engine, int mouseX, int mouseY) {
        FrameBuffer viewportFb = overlayInteractionFrameBuffer(engine);
        if (viewportFb == null || engine.camera == null || engine.scene == null) {
            return null;
        }
        int fx = canvasToFramebufferX(engine, mouseX);
        int fy = canvasToFramebufferY(engine, mouseY);
        int w = viewportFb.getWidth();
        int h = viewportFb.getHeight();
        Mat4 vp = engine.camera.getProjectionMatrix().multiply(engine.camera.getViewMatrix());
        double iconPixelScale = overlayPixelScaleFromCanvas(engine, w, h);
        int pickPadding = Math.max(2, scaledPixels(iconPixelScale, 4));
        List<Engine.OverlayIcon> icons = collectOverlayIcons(engine, vp, w, h, true, iconPixelScale);

        Engine.OverlayIcon best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Engine.OverlayIcon icon : icons) {
            if (icon == null || icon.ref == null) {
                continue;
            }
            int dx = fx - icon.x;
            int dy = fy - icon.y;
            int r = icon.radius + pickPadding;
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
        double gizmoPixelScale = overlayPixelScaleFromCanvas(engine, w, h);
        Engine.GizmoScreenData gizmo = computeGizmoScreenData(engine, w, h, gizmoPixelScale);
        if (gizmo == null || !gizmo.valid || gizmo.axes == null) {
            return;
        }
        int arrowHeadSize = Math.max(4, scaledPixels(gizmoPixelScale, 8.0));
        int axisSquareHalf = Math.max(2, scaledPixels(gizmoPixelScale, 4.0));
        int axisSquareSize = axisSquareHalf * 2 + 1;
        int rotateHandleRadius = Math.max(2, scaledPixels(gizmoPixelScale, 5.0));
        int rotateHandleHighlightRadius = Math.max(3, scaledPixels(gizmoPixelScale, 7.0));
        int centerSquareHalf = Math.max(2, scaledPixels(gizmoPixelScale, 4.0));
        int centerSquareSize = centerSquareHalf * 2 + 1;
        int centerDotHalf = Math.max(1, scaledPixels(gizmoPixelScale, 1.0));
        int centerDotSize = centerDotHalf * 2 + 1;

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
                    pixels, w, h, gizmo.centerX, gizmo.centerY, axis.moveX, axis.moveY, arrowColor, arrowHeadSize);
                OverlayDrawUtil.drawSquareBorder(
                    pixels, w, h, axis.scaleX - axisSquareHalf, axis.scaleY - axisSquareHalf,
                    axisSquareSize, axisSquareSize, scaleColor);
                OverlayDrawUtil.drawCircleOutline(pixels, w, h, axis.rotateX, axis.rotateY, rotateHandleRadius, rotateColor);
            if (axisActive) {
                OverlayDrawUtil.drawCircleOutline(
                    pixels, w, h, axis.rotateX, axis.rotateY, rotateHandleHighlightRadius, 0xFFEAF2FF);
            }
        }
        int centerScaleColor = uniformScaleActive ? 0xFFFFE08A : 0xFFF1D37B;
        OverlayDrawUtil.drawSquareBorder(
                pixels, w, h, gizmo.centerX - centerSquareHalf, gizmo.centerY - centerSquareHalf,
                centerSquareSize, centerSquareSize, centerScaleColor);
            OverlayDrawUtil.drawSquare(
                pixels, w, h, gizmo.centerX - centerDotHalf, gizmo.centerY - centerDotHalf,
                centerDotSize, centerDotSize, 0xFFEAF2FF);
    }

            static Engine.GizmoScreenData computeGizmoScreenData(Engine engine, int width, int height, double gizmoPixelScale) {
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
        center.add(new Vec3(axisLen, 0.0, 0.0)), vp, width, height, gizmoPixelScale);
        fillGizmoAxis(engine, out, 1, Engine.AxisConstraint.Y, 0xFF66D66F,
        center.add(new Vec3(0.0, axisLen, 0.0)), vp, width, height, gizmoPixelScale);
        fillGizmoAxis(engine, out, 2, Engine.AxisConstraint.Z, 0xFF67A9FF,
        center.add(new Vec3(0.0, 0.0, axisLen)), vp, width, height, gizmoPixelScale);
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
            int height,
            double gizmoPixelScale) {
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
    double scaleHandleOffset = Math.max(4.0, scaledPixels(gizmoPixelScale, 12.0));
    double rotateBaseOffset = Math.max(3.0, scaledPixels(gizmoPixelScale, 8.0));
    int rotateMinRadius = Math.max(6, scaledPixels(gizmoPixelScale, 14.0));
    data.scaleX = (int) Math.round(gizmo.centerX + ux * (len + scaleHandleOffset));
    data.scaleY = (int) Math.round(gizmo.centerY + uy * (len + scaleHandleOffset));
    data.rotateRadius = (int) Math.max(rotateMinRadius, Math.round(len * (0.72 + 0.10 * slot) + rotateBaseOffset));
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
        FrameBuffer viewportFb = overlayInteractionFrameBuffer(engine);
        if (engine.navigationPreset != Engine.NavigationPreset.BLENDER
                || !engine.selectionSupportsTransform()
                || viewportFb == null) {
            return false;
        }
        double gizmoPixelScale = overlayPixelScaleFromCanvas(
            engine, viewportFb.getWidth(), viewportFb.getHeight());
        Engine.GizmoScreenData gizmo = computeGizmoScreenData(
            engine, viewportFb.getWidth(), viewportFb.getHeight(), gizmoPixelScale);
        if (gizmo == null || !gizmo.valid || gizmo.axes == null) {
            return false;
        }

        int fx = canvasToFramebufferX(engine, mouseX);
        int fy = canvasToFramebufferY(engine, mouseY);
        final int moveRadius = Math.max(4, scaledPixels(gizmoPixelScale, 8.0));
        final int scaleRadius = Math.max(4, scaledPixels(gizmoPixelScale, 9.0));
        final int centerUniformScaleRadius = Math.max(4, scaledPixels(gizmoPixelScale, 8.0));
        final int moveRadiusSq = moveRadius * moveRadius;
        final int scaleRadiusSq = scaleRadius * scaleRadius;
        final int centerUniformScaleRadiusSq = centerUniformScaleRadius * centerUniformScaleRadius;
        final int rotateBand = Math.max(2, scaledPixels(gizmoPixelScale, 4.0));

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
        FrameBuffer viewportFb = overlayInteractionFrameBuffer(engine);
        if (engine.window == null || viewportFb == null) {
            return canvasX;
        }
        int cw = Math.max(1, engine.window.getCanvas().getWidth());
        int fw = Math.max(1, viewportFb.getWidth());
        double t = cw > 1 ? (double) canvasX / (double) (cw - 1) : 0.0;
        int x = (int) Math.round(t * (fw - 1));
        if (x < 0) {
            return 0;
        }
        return Math.min(fw - 1, x);
    }

    static int canvasToFramebufferY(Engine engine, int canvasY) {
        FrameBuffer viewportFb = overlayInteractionFrameBuffer(engine);
        if (engine.window == null || viewportFb == null) {
            return canvasY;
        }
        int ch = Math.max(1, engine.window.getCanvas().getHeight());
        int fh = Math.max(1, viewportFb.getHeight());
        double t = ch > 1 ? (double) canvasY / (double) (ch - 1) : 0.0;
        int y = (int) Math.round(t * (fh - 1));
        if (y < 0) {
            return 0;
        }
        return Math.min(fh - 1, y);
    }

    static double overlayPixelScaleFromCanvas(Engine engine, int framebufferWidth, int framebufferHeight) {
        if (engine == null || engine.window == null || engine.window.getCanvas() == null) {
            return 1.0;
        }
        int canvasWidth = Math.max(1, engine.window.getCanvas().getWidth());
        int canvasHeight = Math.max(1, engine.window.getCanvas().getHeight());
        int fbWidth = Math.max(1, framebufferWidth);
        int fbHeight = Math.max(1, framebufferHeight);
        double sx = (double) fbWidth / (double) canvasWidth;
        double sy = (double) fbHeight / (double) canvasHeight;
        double scale = Math.min(sx, sy);
        if (!Double.isFinite(scale)) {
            return 1.0;
        }
        return Math.max(0.1, scale);
    }

    static int scaledPixels(double scale, double pixels) {
        if (!Double.isFinite(scale) || !Double.isFinite(pixels)) {
            return (int) Math.max(1, Math.round(Math.max(1.0, pixels)));
        }
        return (int) Math.max(1, Math.round(Math.max(1.0, pixels * scale)));
    }

    private static FrameBuffer overlayInteractionFrameBuffer(Engine engine) {
        if (engine == null) {
            return null;
        }
        if (engine.viewportOverlayFrameBuffer != null) {
            return engine.viewportOverlayFrameBuffer;
        }
        return engine.frameBuffer;
    }

    private static boolean isViewportOverlayBuffer(Engine engine, FrameBuffer fb) {
        return engine != null && fb != null && fb == engine.viewportOverlayFrameBuffer;
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

    static String[] buildDebugHudLines(Engine engine) {
        String camMode = engine.cameraController != null ? engine.cameraController.getMode().toString() : "Žádná";
        String navMode = engine.navigationPreset != null ? engine.navigationPreset.toString() : "FPS";
        String projection = engine.orthographicProjection ? "Orto" : "Persp";
        Vec3 pos = engine.camera != null ? engine.camera.getPosition() : Vec3.ZERO;
        String viewMode = engine.viewportDisplayedMode != null ? engine.viewportDisplayedMode.name() : "UNKNOWN";

        long meshVersion = engine.scene != null ? engine.scene.getMeshEntityVersion() : 0L;
        if (meshVersion != cachedSceneStatsVersion) {
            cachedSceneStatsVersion = meshVersion;
            cachedSceneObjectCount = 0;
            cachedSceneTriangleCount = 0L;
            cachedSceneVertexCount = 0L;
            if (engine.scene != null) {
                List<engine.scene.Entity> meshEntities = engine.scene.getAllMeshEntities();
                cachedSceneObjectCount = meshEntities.size();
                for (engine.scene.Entity entity : meshEntities) {
                    if (entity == null || entity.getMesh() == null) {
                        continue;
                    }
                    cachedSceneTriangleCount += entity.getMesh().getTriangleCount();
                    cachedSceneVertexCount += entity.getMesh().getVertexCount();
                }
            }
        }

        List<String> lines = new ArrayList<>();
        lines.add(String.format(Locale.ROOT, "FPS: %.1f", engine.hudFps));
        lines.add(String.format(Locale.ROOT, "FT: %.2f ms", engine.hudFrameTimeMs));
        lines.add(String.format(Locale.ROOT,
                "SCL: %.2fx%s",
                engine.effectiveRenderScale(),
                engine.interactiveRenderScaleActive ? " INT" : ""));
        lines.add("CAM: " + projection + " | " + navMode + " | " + camMode);
        lines.add(String.format(Locale.ROOT, "POS: %.2f %.2f %.2f", pos.x, pos.y, pos.z));
        lines.add("GEO: "
                + shortCount(cachedSceneTriangleCount) + " tri | "
                + shortCount(cachedSceneVertexCount) + " vtx | "
                + cachedSceneObjectCount + " obj");

        if (engine.progressiveViewportEnabled) {
            lines.add(String.format(Locale.ROOT,
                    "AUT: %s x%.2f p%.1fms d%d",
                    engine.viewportAutoPolicyTier,
                    engine.viewportAutoOverloadRatio,
                    engine.viewportPredictedFrameMs,
                    engine.viewportFrameDropStreak));
        }
        if (engine.viewportNavigationPreviewActive) {
            lines.add("FBK: " + engine.viewportNavigationFallbackMode
                    + " " + (engine.viewportFallbackLockActive ? "lock" : "auto")
                    + " " + (engine.viewportCameraMotionActive ? "mov" : "idle"));
        }

        if (viewMode.equals(RenderMode.RAY_TRACING.name())) {
            lines.add("SPP: " + engine.rayTracerRenderer.getAccumulatedSamples());
            lines.add(String.format(Locale.ROOT,
                    "RT: d%d den %s r%d s%.2f",
                    engine.rayMaxDepth,
                    engine.rayDenoise ? "on" : "off",
                    engine.rayDenoiseRadius,
                    engine.rayDenoiseStrength));
            lines.add("BNC: di" + engine.rayDiffuseBounces
                    + " gl" + engine.rayGlossyBounces
                    + " tr" + engine.rayTransmissionBounces);
        } else if (viewMode.equals(RenderMode.PATH_TRACING.name())) {
            lines.add("SPP: " + engine.pathTracerRenderer.getAccumulatedSamples());
            lines.add(String.format(Locale.ROOT,
                    "PT: d%d den %s %.2fs",
                    engine.pathMaxDepth,
                    engine.pathDenoise ? "on" : "off",
                    engine.viewportPathGentleMotionSeconds));
            lines.add("DNR: " + engine.viewportPathDenoiseProfileApplied
                    + "/" + engine.viewportPathDenoiseRuntimeModeApplied);
            lines.add(String.format(Locale.ROOT, "CLP: d%.1f i%.1f", engine.pathClampDirect, engine.pathClampIndirect));
        } else {
            lines.add("RST: wk " + engine.parallelWorkerCount
                    + " mt " + (engine.parallelRasterEnabled ? "on" : "off")
                    + (engine.viewDistanceCullingEnabled ? String.format(Locale.ROOT, " rng %.0f", engine.viewDistanceLimit) : ""));
        }

        if (engine.sceneImportController.isBusy()) {
            lines.add("JOB: import");
        }
        if (engine.outputRenderController.isRenderInProgress()) {
            lines.add("JOB: output");
        }
        return lines.toArray(String[]::new);
    }

    private static String shortCount(long value) {
        long safe = Math.max(0L, value);
        if (safe >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1fM", safe / 1_000_000.0);
        }
        if (safe >= 1_000L) {
            return String.format(Locale.ROOT, "%.1fk", safe / 1_000.0);
        }
        return Long.toString(safe);
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
