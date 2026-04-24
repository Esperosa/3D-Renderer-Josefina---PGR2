package engine.util;

import engine.math.MathUtil;

/**
 * Represents pomocné operace pro práci s ARGB barvami.
 */
public final class ColorUtil {

    private ColorUtil() {}

 // barvy balím a rozbaluju.
    public static int pack(int r, int g, int b) {
        return pack(255, r, g, b);
    }

    public static int pack(int a, int r, int g, int b) {
        return ((clamp8(a) & 0xFF) << 24)
                | ((clamp8(r) & 0xFF) << 16)
                | ((clamp8(g) & 0xFF) << 8)
                | (clamp8(b) & 0xFF);
    }

    public static int packFloat(double r, double g, double b) {
        return pack(
                255,
                (int) Math.round(MathUtil.clamp01(r) * 255.0),
                (int) Math.round(MathUtil.clamp01(g) * 255.0),
                (int) Math.round(MathUtil.clamp01(b) * 255.0)
        );
    }

    public static int red(int argb) {
        return (argb >>> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >>> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    public static int alpha(int argb) {
        return (argb >>> 24) & 0xFF;
    }

 // Converts barvy na float reprezentaci.
    public static double redF(int argb) {
        return red(argb) / 255.0;
    }

    public static double greenF(int argb) {
        return green(argb) / 255.0;
    }

    public static double blueF(int argb) {
        return blue(argb) / 255.0;
    }

 // Builds základní barevné operace.
    public static int lerp(int color0, int color1, double t) {
        double k = MathUtil.clamp01(t);
        int a = (int) Math.round(alpha(color0) + (alpha(color1) - alpha(color0)) * k);
        int r = (int) Math.round(red(color0) + (red(color1) - red(color0)) * k);
        int g = (int) Math.round(green(color0) + (green(color1) - green(color0)) * k);
        int b = (int) Math.round(blue(color0) + (blue(color1) - blue(color0)) * k);
        return pack(a, r, g, b);
    }

    public static int multiply(int color, double factor) {
        double k = Math.max(0.0, factor);
        int r = (int) Math.round(red(color) * k);
        int g = (int) Math.round(green(color) * k);
        int b = (int) Math.round(blue(color) * k);
        return pack(alpha(color), r, g, b);
    }

    public static int add(int a, int b) {
        return pack(
                clamp8(alpha(a) + alpha(b)),
                clamp8(red(a) + red(b)),
                clamp8(green(a) + green(b)),
                clamp8(blue(a) + blue(b))
        );
    }

    public static double luminance(int argb) {
        return 0.299 * redF(argb) + 0.587 * greenF(argb) + 0.114 * blueF(argb);
    }

    public static int grayscale(double luminance) {
        int v = (int) Math.round(MathUtil.clamp01(luminance) * 255.0);
        return pack(v, v, v);
    }

    public static int toGrayscale(int argb) {
        return grayscale(luminance(argb));
    }

    private static int clamp8(int v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return v;
    }
}