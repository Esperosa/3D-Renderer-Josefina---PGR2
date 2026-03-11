package engine.render;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Tady držím jednoduchou neměnnou 2D texturu v ARGB8 formátu.
 */
public class Texture {

    private final int width;
    private final int height;
    private final int[] pixels;

    public Texture(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Texture dimensions must be positive.");
        }
        if (pixels == null || pixels.length < width * height) {
            throw new IllegalArgumentException("Texture pixel buffer is invalid.");
        }
        this.width = width;
        this.height = height;
        this.pixels = pixels;
    }

    public static Texture load(String filePath) {
        try {
            BufferedImage image = ImageIO.read(new File(filePath));
            if (image == null) {
                throw new RuntimeException("Unsupported texture format: " + filePath);
            }
            int w = image.getWidth();
            int h = image.getHeight();
            int[] data = new int[w * h];
            image.getRGB(0, 0, w, h, data, 0, w);
            return new Texture(w, h, data);
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load texture: " + filePath, ex);
        }
    }

    public int sampleNearest(double u, double v) {
        return sampleNearest(u, v, true);
    }

    public int sampleNearest(double u, double v, boolean flipV) {
        double uu = wrap(u);
        double vv = wrap(flipV ? 1.0 - v : v);
        int x = (int) Math.floor(uu * width);
        int y = (int) Math.floor(vv * height);
        if (x >= width) {
            x = width - 1;
        }
        if (y >= height) {
            y = height - 1;
        }
        return pixels[y * width + x];
    }

    public int sampleBilinear(double u, double v) {
        return sampleBilinear(u, v, true);
    }

    public int sampleBilinear(double u, double v, boolean flipV) {
        double uu = wrap(u) * (width - 1);
        double vv = wrap(flipV ? 1.0 - v : v) * (height - 1);

        int x0 = (int) Math.floor(uu);
        int y0 = (int) Math.floor(vv);
        int x1 = Math.min(width - 1, x0 + 1);
        int y1 = Math.min(height - 1, y0 + 1);

        double tx = uu - x0;
        double ty = vv - y0;

        int c00 = pixels[y0 * width + x0];
        int c10 = pixels[y0 * width + x1];
        int c01 = pixels[y1 * width + x0];
        int c11 = pixels[y1 * width + x1];

        double a00 = (c00 >>> 24) & 0xFF;
        double r00 = (c00 >> 16) & 0xFF;
        double g00 = (c00 >> 8) & 0xFF;
        double b00 = c00 & 0xFF;
        double a10 = (c10 >>> 24) & 0xFF;
        double r10 = (c10 >> 16) & 0xFF;
        double g10 = (c10 >> 8) & 0xFF;
        double b10 = c10 & 0xFF;
        double a01 = (c01 >>> 24) & 0xFF;
        double r01 = (c01 >> 16) & 0xFF;
        double g01 = (c01 >> 8) & 0xFF;
        double b01 = c01 & 0xFF;
        double a11 = (c11 >>> 24) & 0xFF;
        double r11 = (c11 >> 16) & 0xFF;
        double g11 = (c11 >> 8) & 0xFF;
        double b11 = c11 & 0xFF;

        double a0 = a00 + (a10 - a00) * tx;
        double r0 = r00 + (r10 - r00) * tx;
        double g0 = g00 + (g10 - g00) * tx;
        double b0 = b00 + (b10 - b00) * tx;
        double a1 = a01 + (a11 - a01) * tx;
        double r1 = r01 + (r11 - r01) * tx;
        double g1 = g01 + (g11 - g01) * tx;
        double b1 = b01 + (b11 - b01) * tx;

        int a = (int) Math.round(a0 + (a1 - a0) * ty);
        int r = (int) Math.round(r0 + (r1 - r0) * ty);
        int g = (int) Math.round(g0 + (g1 - g0) * ty);
        int b = (int) Math.round(b0 + (b1 - b0) * ty);

        a = Math.max(0, Math.min(255, a));
        r = Math.max(0, Math.min(255, r));
        g = Math.max(0, Math.min(255, g));
        b = Math.max(0, Math.min(255, b));
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private double wrap(double v) {
        double w = v - Math.floor(v);
        if (w < 0.0) {
            w += 1.0;
        }
        return w;
    }
}
