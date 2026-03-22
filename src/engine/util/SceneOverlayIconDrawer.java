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
            Camera camera,
            double pixelScale) {
        if (light == null) {
            return;
        }
        int baseColor = selected ? 0xFFFFCE57 : 0xFF7BC7FF;
        int accent = selected ? 0xFFFFEEC2 : 0xFFBDE7FF;
        int thick2 = scaledPixels(2, pixelScale, 1);
        int thick3 = scaledPixels(3, pixelScale, 1);
        int arrow = scaledPixels(7, pixelScale, 3);

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
            int px = (int) Math.round(-uy * (r + scaledPixels(2, pixelScale, 1)));
            int py = (int) Math.round(ux * (r + scaledPixels(2, pixelScale, 1)));
            int bx = (int) Math.round(cx + ux * (r + scaledPixels(7, pixelScale, 3)));
            int by = (int) Math.round(cy + uy * (r + scaledPixels(7, pixelScale, 3)));
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, bx + px, by + py, baseColor, thick3);
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, bx - px, by - py, baseColor, thick3);
            OverlayDrawUtil.drawLineThick(pixels, w, h, bx + px, by + py, bx - px, by - py, accent, thick3);
            OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, Math.max(1, r - scaledPixels(3, pixelScale, 1)), accent, thick2);
            return;
        }

        if (light instanceof AreaLight) {
            AreaLight area = (AreaLight) light;
            OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - r, cy - r, 2 * r + 1, 2 * r + 1, baseColor);
            OverlayDrawUtil.drawCircleOutlineThick(
                    pixels, w, h, cx, cy, Math.max(scaledPixels(5, pixelScale, 2), r - scaledPixels(4, pixelScale, 2)),
                    accent, thick2);
            Vec3 p = area.getPosition();
            Vec3 d = area.getEmissionDirection();
            int[] end = new int[2];
            double[] depth = new double[1];
            if (ScreenProjectionUtil.projectWorldPointWithDepth(p.add(d.mul(0.9)), vp, w, h, end, depth)) {
                OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, end[0], end[1], accent, arrow, thick3);
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
            int ex = (int) Math.round(cx + sx * (r + scaledPixels(10, pixelScale, 4)));
            int ey = (int) Math.round(cy + sy * (r + scaledPixels(10, pixelScale, 4)));
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, ex, ey, baseColor, thick3);
            OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, ex, ey, accent, arrow, thick3);
            int half = scaledPixels(4, pixelScale, 2);
            OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - half, cy - half, 2 * half + 1, 2 * half + 1, accent);
            return;
        }

        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, thick2);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - r, cy, cx + r, cy, accent, thick3);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy - r, cx, cy + r, accent, thick3);
        int centerHalf = scaledPixels(1, pixelScale, 1);
        OverlayDrawUtil.drawSquare(pixels, w, h, cx - centerHalf, cy - centerHalf, 2 * centerHalf + 1, 2 * centerHalf + 1, accent);
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
            Camera camera,
            double pixelScale) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        int thick3 = scaledPixels(3, pixelScale, 1);
        int arrow = scaledPixels(8, pixelScale, 3);
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
        int ex = (int) Math.round(cx + sx * (r + scaledPixels(9, pixelScale, 3)));
        int ey = (int) Math.round(cy + sy * (r + scaledPixels(9, pixelScale, 3)));
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy, ex, ey, baseColor, thick3);
        OverlayDrawUtil.drawArrowHeadThick(pixels, w, h, cx, cy, ex, ey, accent, arrow, thick3);
        int half = scaledPixels(3, pixelScale, 1);
        OverlayDrawUtil.drawSquareBorder(pixels, w, h, cx - half, cy - half, 2 * half + 1, 2 * half + 1, accent);
    }

    public static void drawPointForceIcon(
            int[] pixels, int w, int h, int cx, int cy, int r, boolean attract, boolean selected, double pixelScale) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        int thick2 = scaledPixels(2, pixelScale, 1);
        int thick3 = scaledPixels(3, pixelScale, 1);
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, thick2);
        OverlayDrawUtil.drawCircleOutlineThick(
                pixels, w, h, cx, cy,
                Math.max(scaledPixels(5, pixelScale, 2), r - scaledPixels(4, pixelScale, 2)), accent, thick2);
        OverlayDrawUtil.drawLineThick(pixels, w, h, cx - r / 2, cy, cx + r / 2, cy, accent, thick3);
        if (attract) {
            OverlayDrawUtil.drawLineThick(pixels, w, h, cx, cy - r / 2, cx, cy + r / 2, accent, thick3);
        }
    }

    public static void drawTurbulenceForceIcon(
            int[] pixels, int w, int h, int cx, int cy, int r, boolean selected, double pixelScale) {
        int baseColor = selected ? 0xFFFFC767 : 0xFF8DE8B8;
        int accent = selected ? 0xFFFFEDC0 : 0xFFC9FFE3;
        int thick2 = scaledPixels(2, pixelScale, 1);
        int thick3 = scaledPixels(3, pixelScale, 1);
        OverlayDrawUtil.drawCircleOutlineThick(pixels, w, h, cx, cy, r, baseColor, thick2);
        OverlayDrawUtil.drawCircleOutlineThick(
                pixels, w, h, cx, cy,
                Math.max(scaledPixels(4, pixelScale, 2), r - scaledPixels(4, pixelScale, 2)), accent, thick2);
        OverlayDrawUtil.drawCircleOutlineThick(
                pixels, w, h, cx, cy,
                Math.max(scaledPixels(2, pixelScale, 1), r - scaledPixels(7, pixelScale, 3)), baseColor, thick2);
        OverlayDrawUtil.drawLineThick(
                pixels, w, h, cx - r + scaledPixels(2, pixelScale, 1), cy + scaledPixels(1, pixelScale, 1),
                cx - scaledPixels(1, pixelScale, 1), cy - scaledPixels(2, pixelScale, 1), accent, thick3);
        OverlayDrawUtil.drawLineThick(
                pixels, w, h, cx - scaledPixels(1, pixelScale, 1), cy - scaledPixels(2, pixelScale, 1),
                cx + r - scaledPixels(2, pixelScale, 1), cy + scaledPixels(2, pixelScale, 1), accent, thick3);
    }

    private static int scaledPixels(int basePixels, double pixelScale, int minPixels) {
        int scaled = (int) Math.round(basePixels * pixelScale);
        return Math.max(minPixels, scaled);
    }
}
