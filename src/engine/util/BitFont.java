package engine.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Represents jednoduchý bitmapový font s pevnou šířkou pro ASCII dithering.
 */
public class BitFont {

    private static final int ASCII_START = 32;
    private static final int ASCII_END = 126;
    private static final int GLYPH_COUNT = ASCII_END - ASCII_START + 1;
    public static final String DEFAULT_ASCII_CHARSET =
            " .'`^\",:;Il!i~+_-?][}{1)(|\\/tfjrxnuvczXYUJCLQ0OZmwqpdbkhao*#MW&8%B@$";

    private final int charWidth;
    private final int charHeight;
    private final boolean[][][] glyphs;

    public BitFont() {
        this(6, 8);
    }

    public BitFont(int charWidth, int charHeight) {
        this.charWidth = Math.max(2, charWidth);
        this.charHeight = Math.max(2, charHeight);
        this.glyphs = new boolean[GLYPH_COUNT][this.charHeight][this.charWidth];
        initDefaultGlyphs();
    }

    public static String sanitizeCharset(String charset) {
        if (charset == null) {
            return DEFAULT_ASCII_CHARSET;
        }

        boolean[] seen = new boolean[GLYPH_COUNT];
        StringBuilder normalized = new StringBuilder(charset.length());
        for (int i = 0; i < charset.length(); i++) {
            char ch = charset.charAt(i);
            if (ch < ASCII_START || ch > ASCII_END) {
                continue;
            }
            int glyphIndex = ch - ASCII_START;
            if (seen[glyphIndex]) {
                continue;
            }
            seen[glyphIndex] = true;
            normalized.append(ch);
        }
        if (normalized.length() == 0) {
            return DEFAULT_ASCII_CHARSET;
        }
        return normalized.toString();
    }

 /**
 * Returns bitmapu glyfu pro zadaný znak.
 *
 * @param c ASCII znak v rozsahu 32 až 126
 * @return vrátí masku boolean[charHeight][charWidth], kde true znamená popředí
 */
    public boolean[][] getGlyph(char c) {
        if (c < ASCII_START || c > ASCII_END) {
            c = ' ';
        }
        return glyphs[c - ASCII_START];
    }

 /**
 * vyrastruje znak do vybrané oblasti pixelového bufferu.
 *
 * @param pixels barevný buffer framebufferu
 * @param stride šířku bufferu v pixelech
 * @param startX levý horní roh cílové buňky na ose X
 * @param startY levý horní roh cílové buňky na ose Y
 * @param c znak k vykreslení
 * @param fgColor ARGB barvu popředí
 * @param bgColor ARGB barvu pozadí
 */
    public void drawChar(int[] pixels, int stride, int startX, int startY,
                         char c, int fgColor, int bgColor) {
        if (pixels == null || stride <= 0) {
            return;
        }
        int height = pixels.length / stride;
        if (height <= 0) {
            return;
        }

        boolean[][] glyph = getGlyph(c);
        for (int y = 0; y < charHeight; y++) {
            int py = startY + y;
            if (py < 0 || py >= height) {
                continue;
            }
            int row = py * stride;
            boolean[] glyphRow = glyph[y];
            for (int x = 0; x < charWidth; x++) {
                int px = startX + x;
                if (px < 0 || px >= stride) {
                    continue;
                }
                pixels[row + px] = glyphRow[x] ? fgColor : bgColor;
            }
        }
    }

    public int getCharWidth() {
        return charWidth;
    }

    public int getCharHeight() {
        return charHeight;
    }

    private void initDefaultGlyphs() {
        BufferedImage img = new BufferedImage(charWidth, charHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, charWidth, charHeight);

        Font font = new Font(Font.MONOSPACED, Font.PLAIN, Math.max(8, charHeight + 2));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < GLYPH_COUNT; i++) {
            char ch = (char) (ASCII_START + i);
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, charWidth, charHeight);

            if (ch != ' ') {
                int glyphWidth = fm.charWidth(ch);
                int x = Math.max(0, (charWidth - glyphWidth) / 2);
                int y = fm.getAscent() + (charHeight - fm.getHeight()) / 2;
                if (y < 1) {
                    y = charHeight - 1;
                }
                g.setColor(Color.WHITE);
                g.drawString(String.valueOf(ch), x, y);
            }

            boolean[][] out = glyphs[i];
            for (int gy = 0; gy < charHeight; gy++) {
                for (int gx = 0; gx < charWidth; gx++) {
                    int argb = img.getRGB(gx, gy);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >>> 16) & 0xFF;
                    int gr = (argb >>> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int lum = (r * 299 + gr * 587 + b * 114) / 1000;
                    out[gy][gx] = a > 16 && lum > 32;
                }
            }
        }
        g.dispose();
    }
}