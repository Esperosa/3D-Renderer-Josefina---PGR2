package engine.util;

import engine.math.Vec3;

public final class OverlayDrawUtil {

    private OverlayDrawUtil() {
    }

    public static void darkenRegion(int[] pixels, int w, int h, int x0, int y0, int x1, int y1, double amount) {
        int ax0 = Math.max(0, x0);
        int ay0 = Math.max(0, y0);
        int ax1 = Math.min(w, x1);
        int ay1 = Math.min(h, y1);
        if (ax0 >= ax1 || ay0 >= ay1) {
            return;
        }
        double a = Math.max(0.0, Math.min(1.0, amount));
        for (int y = ay0; y < ay1; y++) {
            int row = y * w;
            for (int x = ax0; x < ax1; x++) {
                int idx = row + x;
                pixels[idx] = blendToColor(pixels[idx], 0xFF000000, a);
            }
        }
    }

    public static void drawDashedRect(
            int[] pixels,
            int w,
            int h,
            int x0,
            int y0,
            int x1,
            int y1,
            int colorA,
            int colorB,
            int onLen,
            int offLen) {
        int cycle = Math.max(1, onLen + offLen);
        for (int x = x0; x <= x1; x++) {
            int k = Math.floorMod(x - x0, cycle);
            if (k < onLen) {
                int col = ((x - x0) / cycle) % 2 == 0 ? colorA : colorB;
                setPixelSafe(pixels, w, h, x, y0, col);
                setPixelSafe(pixels, w, h, x, y1, col);
            }
        }
        for (int y = y0; y <= y1; y++) {
            int k = Math.floorMod(y - y0, cycle);
            if (k < onLen) {
                int col = ((y - y0) / cycle) % 2 == 0 ? colorA : colorB;
                setPixelSafe(pixels, w, h, x0, y, col);
                setPixelSafe(pixels, w, h, x1, y, col);
            }
        }
    }

    public static void setPixelSafe(int[] pixels, int w, int h, int x, int y, int color) {
        if (x < 0 || y < 0 || x >= w || y >= h) {
            return;
        }
        pixels[y * w + x] = color;
    }

    public static int blendToColor(int dst, int src, double alpha) {
        double a = Math.max(0.0, Math.min(1.0, alpha));
        int dr = (dst >> 16) & 0xFF;
        int dg = (dst >> 8) & 0xFF;
        int db = dst & 0xFF;
        int sr = (src >> 16) & 0xFF;
        int sg = (src >> 8) & 0xFF;
        int sb = src & 0xFF;
        int r = (int) Math.round(dr * (1.0 - a) + sr * a);
        int g = (int) Math.round(dg * (1.0 - a) + sg * a);
        int b = (int) Math.round(db * (1.0 - a) + sb * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static void drawCrosshair(int[] pixels, int w, int h, int color, int shadowColor) {
        int cx = w / 2;
        int cy = h / 2;
        for (int i = -8; i <= 8; i++) {
            if (i >= -2 && i <= 2) {
                continue;
            }
            int x = cx + i;
            int y = cy + i;
            if (x > 0 && x < w - 1) {
                int idx = cy * w + x;
                pixels[idx] = color;
                pixels[idx - w] = shadowColor;
                pixels[idx + w] = shadowColor;
            }
            if (y > 0 && y < h - 1) {
                int idx = y * w + cx;
                pixels[idx] = color;
                if (cx > 0) {
                    pixels[idx - 1] = shadowColor;
                }
                if (cx + 1 < w) {
                    pixels[idx + 1] = shadowColor;
                }
            }
        }
    }

    public static void drawWorldAxisWidget(int[] pixels, int w, int h, Vec3 camRight, Vec3 camUp) {
        int ox = 62;
        int oy = h - 62;
        int len = 30;

        drawSquare(pixels, w, h, ox - 34, oy - 34, 68, 68, 0xFF0C131D);
        drawSquareBorder(pixels, w, h, ox - 34, oy - 34, 68, 68, 0xFF33506E);

        int xdx = (int) Math.round(new Vec3(1.0, 0.0, 0.0).dot(camRight) * len);
        int xdy = (int) Math.round(-new Vec3(1.0, 0.0, 0.0).dot(camUp) * len);
        int ydx = (int) Math.round(new Vec3(0.0, 1.0, 0.0).dot(camRight) * len);
        int ydy = (int) Math.round(-new Vec3(0.0, 1.0, 0.0).dot(camUp) * len);
        int zdx = (int) Math.round(new Vec3(0.0, 0.0, 1.0).dot(camRight) * len);
        int zdy = (int) Math.round(-new Vec3(0.0, 0.0, 1.0).dot(camUp) * len);

        drawLine(pixels, w, h, ox, oy, ox + xdx, oy + xdy, 0xFFE45151);
        drawLine(pixels, w, h, ox, oy, ox + ydx, oy + ydy, 0xFF65D167);
        drawLine(pixels, w, h, ox, oy, ox + zdx, oy + zdy, 0xFF67A8FF);
        drawSquare(pixels, w, h, ox - 2, oy - 2, 5, 5, 0xFFE2EAF8);
    }

    public static void drawSquare(int[] pixels, int w, int h, int x, int y, int sw, int sh, int color) {
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(w, x + sw);
        int y1 = Math.min(h, y + sh);
        for (int py = y0; py < y1; py++) {
            int row = py * w;
            for (int px = x0; px < x1; px++) {
                pixels[row + px] = color;
            }
        }
    }

    public static void drawSquareBorder(int[] pixels, int w, int h, int x, int y, int sw, int sh, int color) {
        drawLine(pixels, w, h, x, y, x + sw - 1, y, color);
        drawLine(pixels, w, h, x, y + sh - 1, x + sw - 1, y + sh - 1, color);
        drawLine(pixels, w, h, x, y, x, y + sh - 1, color);
        drawLine(pixels, w, h, x + sw - 1, y, x + sw - 1, y + sh - 1, color);
    }

    public static void drawLineThick(
            int[] pixels, int w, int h, int x0, int y0, int x1, int y1, int color, int thickness) {
        int t = Math.max(1, thickness);
        if (t <= 1) {
            drawLine(pixels, w, h, x0, y0, x1, y1, color);
            return;
        }
        int r = t / 2;
        for (int oy = -r; oy <= r; oy++) {
            for (int ox = -r; ox <= r; ox++) {
                if (ox * ox + oy * oy > r * r + 1) {
                    continue;
                }
                drawLine(pixels, w, h, x0 + ox, y0 + oy, x1 + ox, y1 + oy, color);
            }
        }
    }

    public static void drawLine(int[] pixels, int w, int h, int x0, int y0, int x1, int y1, int color) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        int x = x0;
        int y = y0;
        while (true) {
            if (x >= 0 && y >= 0 && x < w && y < h) {
                pixels[y * w + x] = color;
            }
            if (x == x1 && y == y1) {
                break;
            }
            int e2 = err << 1;
            if (e2 >= dy) {
                err += dy;
                x += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y += sy;
            }
        }
    }

    public static void drawArrowHead(
            int[] pixels, int w, int h, int x0, int y0, int x1, int y1, int color, double headSize) {
        drawLine(pixels, w, h, x0, y0, x1, y1, color);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 1e-5) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double px = -uy;
        double py = ux;
        int lx = (int) Math.round(x1 - ux * headSize + px * (headSize * 0.55));
        int ly = (int) Math.round(y1 - uy * headSize + py * (headSize * 0.55));
        int rx = (int) Math.round(x1 - ux * headSize - px * (headSize * 0.55));
        int ry = (int) Math.round(y1 - uy * headSize - py * (headSize * 0.55));
        drawLine(pixels, w, h, x1, y1, lx, ly, color);
        drawLine(pixels, w, h, x1, y1, rx, ry, color);
    }

    public static void drawArrowHeadThick(
            int[] pixels,
            int w,
            int h,
            int x0,
            int y0,
            int x1,
            int y1,
            int color,
            double headSize,
            int thickness) {
        drawLineThick(pixels, w, h, x0, y0, x1, y1, color, thickness);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double len = Math.hypot(dx, dy);
        if (len < 1e-5) {
            return;
        }
        double ux = dx / len;
        double uy = dy / len;
        double px = -uy;
        double py = ux;
        int lx = (int) Math.round(x1 - ux * headSize + px * (headSize * 0.55));
        int ly = (int) Math.round(y1 - uy * headSize + py * (headSize * 0.55));
        int rx = (int) Math.round(x1 - ux * headSize - px * (headSize * 0.55));
        int ry = (int) Math.round(y1 - uy * headSize - py * (headSize * 0.55));
        drawLineThick(pixels, w, h, x1, y1, lx, ly, color, thickness);
        drawLineThick(pixels, w, h, x1, y1, rx, ry, color, thickness);
    }

    public static void drawCircleOutline(int[] pixels, int w, int h, int cx, int cy, int radius, int color) {
        if (radius <= 0) {
            return;
        }
        int steps = Math.max(28, (int) Math.round(radius * 6.0));
        int prevX = cx + radius;
        int prevY = cy;
        for (int i = 1; i <= steps; i++) {
            double a = (i * Math.PI * 2.0) / (double) steps;
            int x = (int) Math.round(cx + Math.cos(a) * radius);
            int y = (int) Math.round(cy + Math.sin(a) * radius);
            drawLine(pixels, w, h, prevX, prevY, x, y, color);
            prevX = x;
            prevY = y;
        }
    }

    public static void drawCircleOutlineThick(
            int[] pixels, int w, int h, int cx, int cy, int radius, int color, int thickness) {
        int t = Math.max(1, thickness);
        if (t <= 1) {
            drawCircleOutline(pixels, w, h, cx, cy, radius, color);
            return;
        }
        int r0 = Math.max(1, radius - t / 2);
        int r1 = Math.max(r0, radius + t / 2);
        for (int r = r0; r <= r1; r++) {
            drawCircleOutline(pixels, w, h, cx, cy, r, color);
        }
    }

    public static int distanceSq(int x0, int y0, int x1, int y1) {
        int dx = x0 - x1;
        int dy = y0 - y1;
        return dx * dx + dy * dy;
    }

    public static int brightenColor(int color, double amount) {
        double a = Math.max(0.0, Math.min(1.0, amount));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) Math.round(r + (255 - r) * a);
        g = (int) Math.round(g + (255 - g) * a);
        b = (int) Math.round(b + (255 - b) * a);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    public static int dimColor(int color, double amount) {
        double a = Math.max(0.0, Math.min(1.0, amount));
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = (int) Math.round(r * (1.0 - a));
        g = (int) Math.round(g * (1.0 - a));
        b = (int) Math.round(b * (1.0 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
