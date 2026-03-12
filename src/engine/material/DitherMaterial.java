package engine.material;

import engine.util.BitFont;

/**
 * Tady držím parametry materiálu pro dithering a ASCII režimy vykreslení.
 */
public class DitherMaterial extends Material {

    private int toneCount;
    private double contrast;
    private boolean invert;
    private int cellSize;
    private String asciiCharset;

    public DitherMaterial() {
        this.toneCount = 2;
        this.contrast = 1.0;
        this.invert = false;
        this.cellSize = 6;
        this.asciiCharset = BitFont.DEFAULT_ASCII_CHARSET;
    }

    public int getToneCount() {
        return toneCount;
    }

    public void setToneCount(int n) {
        this.toneCount = Math.max(2, n);
    }

    public double getContrast() {
        return contrast;
    }

    public void setContrast(double c) {
        this.contrast = Math.max(0.1, Math.min(4.0, c));
    }

    public boolean isInvert() {
        return invert;
    }

    public void setInvert(boolean inv) {
        this.invert = inv;
    }

    public int getCellSize() {
        return cellSize;
    }

    public void setCellSize(int size) {
        this.cellSize = Math.max(2, size);
    }

    public String getAsciiCharset() {
        return asciiCharset;
    }

    public void setAsciiCharset(String charset) {
        if (charset == null) {
            return;
        }
        this.asciiCharset = BitFont.sanitizeCharset(charset);
    }
}
