package engine.util;

public final class FastAAUtil {

    private FastAAUtil() {
    }

    public static int[] applyFastPostAA(int[] src, int width, int height, int[] tempBuffer) {
        int count = width * height;
        int[] temp = tempBuffer;
        if (temp == null || temp.length != count) {
            temp = new int[count];
        }

        if (width < 3 || height < 3) {
            return temp;
        }

        System.arraycopy(src, 0, temp, 0, width);
        System.arraycopy(src, count - width, temp, count - width, width);
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            temp[row] = src[row];
            temp[row + width - 1] = src[row + width - 1];
        }

        final int edgeThreshold = 36;
        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                int c = src[idx];
                int l = src[idx - 1];
                int r = src[idx + 1];
                int u = src[idx - width];
                int d = src[idx + width];

                int lc = luma8(c);
                int ll = luma8(l);
                int lr = luma8(r);
                int lu = luma8(u);
                int ld = luma8(d);

                int min = Math.min(lc, Math.min(ll, Math.min(lr, Math.min(lu, ld))));
                int max = Math.max(lc, Math.max(ll, Math.max(lr, Math.max(lu, ld))));
                if (max - min < edgeThreshold) {
                    temp[idx] = c;
                    continue;
                }

                int cr = (c >> 16) & 0xFF;
                int cg = (c >> 8) & 0xFF;
                int cb = c & 0xFF;
                int lrR = (l >> 16) & 0xFF;
                int lrG = (l >> 8) & 0xFF;
                int lrB = l & 0xFF;
                int rrR = (r >> 16) & 0xFF;
                int rrG = (r >> 8) & 0xFF;
                int rrB = r & 0xFF;
                int urR = (u >> 16) & 0xFF;
                int urG = (u >> 8) & 0xFF;
                int urB = u & 0xFF;
                int drR = (d >> 16) & 0xFF;
                int drG = (d >> 8) & 0xFF;
                int drB = d & 0xFF;

                int outR = (cr * 12 + lrR + rrR + urR + drR) >> 4;
                int outG = (cg * 12 + lrG + rrG + urG + drG) >> 4;
                int outB = (cb * 12 + lrB + rrB + urB + drB) >> 4;
                temp[idx] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
            }
        }

        System.arraycopy(temp, 0, src, 0, count);
        return temp;
    }

    public static int luma8(int argb) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return (r * 77 + g * 150 + b * 29) >> 8;
    }
}
