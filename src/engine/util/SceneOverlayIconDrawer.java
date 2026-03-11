package engine.util;

import engine.camera.Camera;
import engine.math.Mat4;
import engine.math.Vec3;
import engine.scene.AreaLight;
import engine.scene.ConeLight;
import engine.scene.DirectionalLight;
import engine.scene.Light;

public final class SceneOverlayIconDrawer {

    private SceneOverlayIconDrawer() {
    }

    public static void drawLightIcon(
            int[] pixels,
            int w,
            int h,
            Mat4 vp,
            Light light,
            boolean selected,
            int cx,
            int cy,
            int r,
            Camera camera) {
        if (light == null) {
            return;
        }
        int baseColor = selected ? 0xFFFFCE57 : 0xFF7BC7FF;
        int accent = selected ? 0xFFFFEEC2 : 0xFFBDE7FF;

        if (light instanceof ConeLight) {
            ConeLight cone = (ConeLight) light;
            Vec3 p = cone.getPosition();
            Vec3 d = cone.getDirection();
            int[] end = new int[2];
            double[] depth = new double[1];
            boolean dirOk = ScreenProjectionUtil.projectWorldPointWithDepth(p.add(d.mul(0.9)), vp, w, h, end, depth);
            int ex = dirOk ? end[0] : cx;
            int ey = dirOk ? end[1] : (cy - r - 10);
            double dx = ex - cx;
            double dy = ey - cy;
            double len = Math.hypot(dx, dy);
            if (len < 1e-5) {
                dx = 0.0;
                dy = -1.0;
                len = 1.0;
            }
            double ux = dx / len;
            double uy = dy / len;
            int px = (int) Math.round(-uy * (r + 2));
            int py = (int) Math.round(ux * (r + 2));
            int bx = (int) Math.round(cx + ux * (r + 7));
            int by = (int) Math.round(cy + uy * (r + 7));
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, bx + px, by + py, baseColor, 3);
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, bx - px, by - py, baseColor, 3);
            OverlayDrawUtil.drawLineThick(pixels, w, h, bx + px, by + py, bx - px, by - py, accent, 3);
            OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r - 3, accent, 2);
            return;
        }

        if (light instanceof AreaLight) {
            AreaLight area = (AreaLight) light;
            OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - r, cy - r, 2 * r + 1, 2 * r + 1, baseColor);
            OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, Math.max(5, r - 4), accent, 2);
            Vec3 p = area.getPosition();
            Vec3 d = area.getEmissionDirection();
            int[] end = new int[2];
            double[] depth = new double[1];
            if (ScreenProjectionUtil.projectWorldPointWithDepth(p.add(d.mul(0.9)), vp, w, h, end, depth)) {
                OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, end[0], end[1], accent, 7.0, 3);
            }
            return;
        }

        if (light instanceof DirectionalLight) {
            DirectionalLight directional = (DirectionalLight) light;
            Vec3 d = directional.getDirection().normalize();
            Vec3 right = camera.getRight().normalize();
            Vec3 up = camera.getUp().normalize();
            double sx = d.dot(right);
            double sy = -d.dot(up);
            double len = Math.hypot(sx, sy);
            if (len < 1e-5) {
                sx = 0.0;
                sy = -1.0;
                len = 1.0;
            }
            sx /= len;
            sy /= len;
            int ex = (int) Math.round(cx + sx * (r + 10));
            int ey = (int) Math.round(cy + sy * (r + 10));
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, ex, ey, baseColor, 3);
            OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, ex, ey, accent, 7.0, 3);
            OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - 4, cy - 4, 9, 9, accent);
            return;
        }

        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, 2);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - r, cy, cx + r, cy, accent, 3);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy - r, cx, cy + r, accent, 3);
        OverlayDrawUtil.drawSquare(pixels, w, h, cx - 1, cy - 1, 3, 3, accent);
    }

    public static void drawVectorForceIcon(
            int[] pixels,
            int w,
            int h,
            int cx,
            int cy,
            int r,
            Vec3 direction,
            boolean selected,
            Camera camera) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        Vec3 dir = direction == null ? new Vec3(0.0, -1.0, 0.0) : direction.normalize();
        Vec3 right = camera.getRight().normalize();
        Vec3 up = camera.getUp().normalize();
        double sx = dir.dot(right);
        double sy = -dir.dot(up);
        double len = Math.hypot(sx, sy);
        if (len < 1e-5) {
            sx = 0.0;
            sy = -1.0;
            len = 1.0;
        }
        sx /= len;
        sy /= len;
        int ex = (int) Math.round(cx + sx * (r + 9));
        int ey = (int) Math.round(cy + sy * (r + 9));
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, ex, ey, baseColor, 3);
        OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, ex, ey, accent, 8.0, 3);
        OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - 3, cy - 3, 7, 7, accent);
    }

    public static void drawPointForceIcon(
            int[] pixels, int w, int h, int cx, int cy, int r, boolean attract, boolean selected) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, 2);
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, Math.max(5, r - 4), accent, 2);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - r / 2, cy, cx + r / 2, cy, accent, 3);
        if (attract) {
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy - r / 2, cx, cy + r / 2, accent, 3);
        }
    }

    public static void drawTurbulenceForceIcon(int[] pixels, int w, int h, int cx, int cy, int r, boolean selected) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, 2);
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, Math.max(4, r - 4), accent, 2);
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, Math.max(2, r - 7), baseColor, 2);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - r + 2, cy + 1, cx - 1, cy - 2, accent, 3);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - 1, cy - 2, cx + r - 2, cy + 2, accent, 3);
    }
}
