package engine.render.post;

import java.util.Random;

/**
 * Tady držím procedurální generátor šumu bez externích knihoven.
 * Poskytuju si přes něj hash, hodnotový a gradientní šum s časovou kontinuitou.
 */
public class NoiseGenerator {

    private static final int TABLE_SIZE = 256;
    private final int[] permutation;

    public NoiseGenerator() {
        this(0x5F3759DFL);
    }

    public NoiseGenerator(long seed) {
        this.permutation = new int[TABLE_SIZE * 2];
        int[] p = new int[TABLE_SIZE];
        for (int i = 0; i < TABLE_SIZE; i++) {
            p[i] = i;
        }
        Random random = new Random(seed);
        for (int i = TABLE_SIZE - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            int tmp = p[i];
            p[i] = p[j];
            p[j] = tmp;
        }
        for (int i = 0; i < permutation.length; i++) {
            permutation[i] = p[i & (TABLE_SIZE - 1)];
        }
    }

    // Tady držím hash šum.
    public int hash(int x, int y) {
        int h = x * 0x45d9f3b ^ y * 0x119de1f3;
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }

    public int hash(int x, int y, int z) {
        int h = x * 0x1f123bb5 ^ y * 0x5f356495 ^ z * 0x6c8e9cf5;
        h ^= (h >>> 16);
        h *= 0x7feb352d;
        h ^= (h >>> 15);
        h *= 0x846ca68b;
        h ^= (h >>> 16);
        return h;
    }

    // Tady držím hodnotový šum.
    public double valueNoise2D(double x, double y) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int x1 = x0 + 1;
        int y1 = y0 + 1;

        double tx = x - x0;
        double ty = y - y0;
        double u = fade(tx);
        double v = fade(ty);

        double v00 = toUnit(hash(x0, y0));
        double v10 = toUnit(hash(x1, y0));
        double v01 = toUnit(hash(x0, y1));
        double v11 = toUnit(hash(x1, y1));

        double ix0 = lerp(v00, v10, u);
        double ix1 = lerp(v01, v11, u);
        return lerp(ix0, ix1, v);
    }

    public double valueNoise3D(double x, double y, double z) {
        int x0 = fastFloor(x);
        int y0 = fastFloor(y);
        int z0 = fastFloor(z);
        int x1 = x0 + 1;
        int y1 = y0 + 1;
        int z1 = z0 + 1;

        double tx = x - x0;
        double ty = y - y0;
        double tz = z - z0;
        double u = fade(tx);
        double v = fade(ty);
        double w = fade(tz);

        double c000 = toUnit(hash(x0, y0, z0));
        double c100 = toUnit(hash(x1, y0, z0));
        double c010 = toUnit(hash(x0, y1, z0));
        double c110 = toUnit(hash(x1, y1, z0));
        double c001 = toUnit(hash(x0, y0, z1));
        double c101 = toUnit(hash(x1, y0, z1));
        double c011 = toUnit(hash(x0, y1, z1));
        double c111 = toUnit(hash(x1, y1, z1));

        double x00 = lerp(c000, c100, u);
        double x10 = lerp(c010, c110, u);
        double x01 = lerp(c001, c101, u);
        double x11 = lerp(c011, c111, u);

        double y0v = lerp(x00, x10, v);
        double y1v = lerp(x01, x11, v);
        return lerp(y0v, y1v, w);
    }

    // Tady držím gradientní šum typu podobného Perlinovi.
    public double gradientNoise2D(double x, double y) {
        int xi = fastFloor(x) & 255;
        int yi = fastFloor(y) & 255;
        double xf = x - fastFloor(x);
        double yf = y - fastFloor(y);

        int aa = permutation[permutation[xi] + yi];
        int ab = permutation[permutation[xi] + yi + 1];
        int ba = permutation[permutation[xi + 1] + yi];
        int bb = permutation[permutation[xi + 1] + yi + 1];

        double u = fade(xf);
        double v = fade(yf);

        double x1 = lerp(grad(aa, xf, yf, 0.0), grad(ba, xf - 1.0, yf, 0.0), u);
        double x2 = lerp(grad(ab, xf, yf - 1.0, 0.0), grad(bb, xf - 1.0, yf - 1.0, 0.0), u);
        return lerp(x1, x2, v);
    }

    public double gradientNoise3D(double x, double y, double z) {
        int xi = fastFloor(x) & 255;
        int yi = fastFloor(y) & 255;
        int zi = fastFloor(z) & 255;
        double xf = x - fastFloor(x);
        double yf = y - fastFloor(y);
        double zf = z - fastFloor(z);

        int aaa = permutation[permutation[permutation[xi] + yi] + zi];
        int aba = permutation[permutation[permutation[xi] + yi + 1] + zi];
        int aab = permutation[permutation[permutation[xi] + yi] + zi + 1];
        int abb = permutation[permutation[permutation[xi] + yi + 1] + zi + 1];
        int baa = permutation[permutation[permutation[xi + 1] + yi] + zi];
        int bba = permutation[permutation[permutation[xi + 1] + yi + 1] + zi];
        int bab = permutation[permutation[permutation[xi + 1] + yi] + zi + 1];
        int bbb = permutation[permutation[permutation[xi + 1] + yi + 1] + zi + 1];

        double u = fade(xf);
        double v = fade(yf);
        double w = fade(zf);

        double x1 = lerp(grad(aaa, xf, yf, zf), grad(baa, xf - 1.0, yf, zf), u);
        double x2 = lerp(grad(aba, xf, yf - 1.0, zf), grad(bba, xf - 1.0, yf - 1.0, zf), u);
        double y1 = lerp(x1, x2, v);

        double x3 = lerp(grad(aab, xf, yf, zf - 1.0), grad(bab, xf - 1.0, yf, zf - 1.0), u);
        double x4 = lerp(grad(abb, xf, yf - 1.0, zf - 1.0), grad(bbb, xf - 1.0, yf - 1.0, zf - 1.0), u);
        double y2 = lerp(x3, x4, v);

        return lerp(y1, y2, w);
    }

    // Tady držím FBM vrstevnění.
    public double fbm2D(double x, double y, int octaves, double lacunarity, double persistence) {
        int oct = Math.max(1, octaves);
        double amp = 0.5;
        double freq = 1.0;
        double sum = 0.0;
        double norm = 0.0;

        for (int i = 0; i < oct; i++) {
            sum += gradientNoise2D(x * freq, y * freq) * amp;
            norm += amp;
            freq *= lacunarity;
            amp *= persistence;
        }
        return norm > 1e-12 ? sum / norm : 0.0;
    }

    public double fbm3D(double x, double y, double z, int octaves, double lacunarity, double persistence) {
        int oct = Math.max(1, octaves);
        double amp = 0.5;
        double freq = 1.0;
        double sum = 0.0;
        double norm = 0.0;

        for (int i = 0; i < oct; i++) {
            sum += gradientNoise3D(x * freq, y * freq, z * freq) * amp;
            norm += amp;
            freq *= lacunarity;
            amp *= persistence;
        }
        return norm > 1e-12 ? sum / norm : 0.0;
    }

    // Tady držím pomocné metody.
    public static double fade(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    public static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    public static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h < 8 ? x : y;
        double v;
        if (h < 4) {
            v = y;
        } else if (h == 12 || h == 14) {
            v = x;
        } else {
            v = z;
        }
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }

    private static int fastFloor(double x) {
        int i = (int) x;
        return x < i ? i - 1 : i;
    }

    private static double toUnit(int h) {
        return ((h & 0x7fffffff) / (double) Integer.MAX_VALUE);
    }
}
