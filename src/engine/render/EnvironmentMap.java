package engine.render;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Immutable equirectangular HDR environment map with a tiny built-in Radiance loader.
 */
public final class EnvironmentMap {

    private static final double TWO_PI = Math.PI * 2.0;
    private static final double IMPORTANCE_EPS = 1e-12;
    private static final double UNIFORM_SPHERE_PDF = 1.0 / (4.0 * Math.PI);

    private final int width;
    private final int height;
    private final float[] rgb;
    private final String sourcePath;
    private final double[] importanceWeights;
    private final double[] rowCdf;
    private final double[] columnCdfs;
    private final double totalImportance;
    private final double averageR;
    private final double averageG;
    private final double averageB;

    public static final class Sample {
        public double dx;
        public double dy;
        public double dz;
        public double r;
        public double g;
        public double b;
        public double pdf;
    }

    private EnvironmentMap(int width, int height, float[] rgb, String sourcePath) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Environment map dimensions must be positive.");
        }
        if (rgb == null || rgb.length < width * height * 3) {
            throw new IllegalArgumentException("Environment map pixel buffer is invalid.");
        }
        this.width = width;
        this.height = height;
        this.rgb = rgb;
        this.sourcePath = sourcePath == null ? "" : sourcePath;

        int pixelCount = width * height;
        this.importanceWeights = new double[pixelCount];
        this.rowCdf = new double[height];
        this.columnCdfs = new double[pixelCount];

        double totalR = 0.0;
        double totalG = 0.0;
        double totalB = 0.0;
        double total = 0.0;
        double solidWeightedR = 0.0;
        double solidWeightedG = 0.0;
        double solidWeightedB = 0.0;
        double solidWeightSum = 0.0;
        for (int y = 0; y < height; y++) {
            double sinTheta = Math.sin(Math.PI * ((y + 0.5) / height));
            double rowTotal = 0.0;
            int rowBase = y * width;
            for (int x = 0; x < width; x++) {
                int colorIndex = (rowBase + x) * 3;
                double r = Math.max(0.0, rgb[colorIndex]);
                double g = Math.max(0.0, rgb[colorIndex + 1]);
                double b = Math.max(0.0, rgb[colorIndex + 2]);
                totalR += r;
                totalG += g;
                totalB += b;
                double weight = luminance(r, g, b) * Math.max(1e-4, sinTheta);
                double solidWeight = Math.max(0.0, sinTheta);
                solidWeightedR += r * solidWeight;
                solidWeightedG += g * solidWeight;
                solidWeightedB += b * solidWeight;
                solidWeightSum += solidWeight;
                importanceWeights[rowBase + x] = weight;
                rowTotal += weight;
                columnCdfs[rowBase + x] = rowTotal;
            }
            if (rowTotal > IMPORTANCE_EPS) {
                double invRowTotal = 1.0 / rowTotal;
                for (int x = 0; x < width; x++) {
                    columnCdfs[rowBase + x] *= invRowTotal;
                }
            } else {
                for (int x = 0; x < width; x++) {
                    columnCdfs[rowBase + x] = (x + 1.0) / width;
                }
            }
            total += rowTotal;
            rowCdf[y] = total;
        }
        if (total > IMPORTANCE_EPS) {
            double invTotal = 1.0 / total;
            for (int y = 0; y < height; y++) {
                rowCdf[y] *= invTotal;
            }
        } else {
            for (int y = 0; y < height; y++) {
                rowCdf[y] = (y + 1.0) / height;
            }
        }
        this.totalImportance = total;
        if (solidWeightSum > IMPORTANCE_EPS) {
            double invSolidWeight = 1.0 / solidWeightSum;
            this.averageR = solidWeightedR * invSolidWeight;
            this.averageG = solidWeightedG * invSolidWeight;
            this.averageB = solidWeightedB * invSolidWeight;
        } else {
            double invPixelCount = 1.0 / pixelCount;
            this.averageR = totalR * invPixelCount;
            this.averageG = totalG * invPixelCount;
            this.averageB = totalB * invPixelCount;
        }
    }

    public static EnvironmentMap loadRadiance(String filePath) {
        Path path = Path.of(filePath);
        try (InputStream stream = new BufferedInputStream(Files.newInputStream(path))) {
            RadianceHeader header = readHeader(stream, filePath);
            byte[] rgbe = readPixels(stream, header.width, header.height);
            float[] rgb = decodeRgb(rgbe, header.width, header.height, header.flipX, header.flipY);
            return new EnvironmentMap(header.width, header.height, rgb, path.toString());
        } catch (IOException ex) {
            throw new RuntimeException("Failed to load HDR environment map: " + filePath, ex);
        }
    }

    public void sample(double dx, double dy, double dz, Sample out) {
        if (out == null) {
            return;
        }
        out.dx = dx;
        out.dy = dy;
        out.dz = dz;
        out.r = 0.0;
        out.g = 0.0;
        out.b = 0.0;
        sampleAtUv(directionToU(dx, dz), directionToV(dy), out);
        out.pdf = pdfForDirection(dx, dy, dz);
    }

    public void importanceSample(double randomU, double randomV, Sample out) {
        if (out == null) {
            return;
        }
        if (totalImportance <= IMPORTANCE_EPS) {
            sampleUniformSphere(randomU, randomV, out);
            sample(out.dx, out.dy, out.dz, out);
            out.pdf = UNIFORM_SPHERE_PDF;
            return;
        }

        int row = findCdfIndex(rowCdf, 0, rowCdf.length, randomV);
        int rowBase = row * width;
        int column = findCdfIndex(columnCdfs, rowBase, rowBase + width, randomU) - rowBase;

        double u = (column + 0.5) / width;
        double v = (row + 0.5) / height;
        directionFromUv(u, v, out);
        out.r = 0.0;
        out.g = 0.0;
        out.b = 0.0;
        sampleAtUv(u, v, out);
        out.pdf = pdfForPixel(column, row);
    }

    public double pdfForDirection(double dx, double dy, double dz) {
        if (totalImportance <= IMPORTANCE_EPS) {
            return UNIFORM_SPHERE_PDF;
        }
        int x = wrapPixel((int) Math.floor(directionToU(dx, dz) * width), width);
        int y = clampPixel((int) Math.floor(directionToV(dy) * height), height);
        return pdfForPixel(x, y);
    }

    public double getAverageR() {
        return averageR;
    }

    public double getAverageG() {
        return averageG;
    }

    public double getAverageB() {
        return averageB;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    private void sampleAtUv(double u, double v, Sample out) {
        double uu = wrap01(u) * width;
        double vv = clamp01(v) * Math.max(1, height - 1);
        int x0 = floorToPixel(uu, width);
        int y0 = clampPixel((int) Math.floor(vv), height);
        int x1 = (x0 + 1) % width;
        int y1 = Math.min(height - 1, y0 + 1);

        double tx = uu - Math.floor(uu);
        double ty = vv - Math.floor(vv);
        sampleTexel(x0, y0, out, 1.0 - tx, 1.0 - ty);
        sampleTexel(x1, y0, out, tx, 1.0 - ty);
        sampleTexel(x0, y1, out, 1.0 - tx, ty);
        sampleTexel(x1, y1, out, tx, ty);
    }

    private void sampleTexel(int x, int y, Sample out, double weightX, double weightY) {
        double weight = weightX * weightY;
        int index = (y * width + x) * 3;
        out.r += rgb[index] * weight;
        out.g += rgb[index + 1] * weight;
        out.b += rgb[index + 2] * weight;
    }

    private double pdfForPixel(int x, int y) {
        if (totalImportance <= IMPORTANCE_EPS) {
            return UNIFORM_SPHERE_PDF;
        }
        double theta = Math.PI * ((y + 0.5) / height);
        double sinTheta = Math.max(1e-4, Math.sin(theta));
        if (sinTheta <= 1e-6) {
            return UNIFORM_SPHERE_PDF;
        }
        double discreteProbability = importanceWeights[y * width + x] / totalImportance;
        double solidAngle = (TWO_PI / width) * (Math.PI / height) * sinTheta;
        if (solidAngle <= 1e-12) {
            return UNIFORM_SPHERE_PDF;
        }
        return discreteProbability / solidAngle;
    }

    private void sampleUniformSphere(double randomU, double randomV, Sample out) {
        double z = 1.0 - 2.0 * randomV;
        double radius = Math.sqrt(Math.max(0.0, 1.0 - z * z));
        double phi = TWO_PI * randomU;
        out.dx = Math.cos(phi) * radius;
        out.dy = z;
        out.dz = Math.sin(phi) * radius;
    }

    private static RadianceHeader readHeader(InputStream stream, String filePath) throws IOException {
        String signature = readAsciiLine(stream);
        if (!signature.startsWith("#?RADIANCE") && !signature.startsWith("#?RGBE")) {
            throw new IOException("Unsupported HDR signature in " + filePath);
        }

        boolean formatValid = false;
        String line;
        while (!(line = readAsciiLine(stream)).isEmpty()) {
            if (line.startsWith("FORMAT=") && line.contains("32-bit_rle_rgbe")) {
                formatValid = true;
            }
        }
        if (!formatValid) {
            throw new IOException("Unsupported HDR pixel encoding in " + filePath);
        }

        String resolution = readAsciiLine(stream);
        while (resolution.isBlank()) {
            resolution = readAsciiLine(stream);
        }

        String[] tokens = resolution.trim().split("\\s+");
        if (tokens.length != 4) {
            throw new IOException("Unsupported HDR resolution line in " + filePath + ": " + resolution);
        }

        boolean flipY;
        boolean flipX;
        int width;
        int height;
        if (tokens[0].endsWith("Y") && tokens[2].endsWith("X")) {
            flipY = tokens[0].startsWith("+");
            flipX = tokens[2].startsWith("-");
            height = Integer.parseInt(tokens[1]);
            width = Integer.parseInt(tokens[3]);
        } else if (tokens[0].endsWith("X") && tokens[2].endsWith("Y")) {
            flipX = tokens[0].startsWith("-");
            flipY = tokens[2].startsWith("+");
            width = Integer.parseInt(tokens[1]);
            height = Integer.parseInt(tokens[3]);
        } else {
            throw new IOException("Unsupported HDR orientation in " + filePath + ": " + resolution);
        }
        return new RadianceHeader(width, height, flipX, flipY);
    }

    private static byte[] readPixels(InputStream stream, int width, int height) throws IOException {
        byte[] pixels = new byte[width * height * 4];
        int dest = 0;
        if (width < 8 || width > 0x7fff) {
            readFully(stream, pixels, 0, pixels.length);
            return pixels;
        }

        byte[] scanline = new byte[width * 4];
        for (int y = 0; y < height; y++) {
            int b0 = readUnsignedByte(stream);
            int b1 = readUnsignedByte(stream);
            int b2 = readUnsignedByte(stream);
            int b3 = readUnsignedByte(stream);
            if (b0 != 2 || b1 != 2 || (b2 & 0x80) != 0) {
                pixels[dest++] = (byte) b0;
                pixels[dest++] = (byte) b1;
                pixels[dest++] = (byte) b2;
                pixels[dest++] = (byte) b3;
                readFully(stream, pixels, dest, pixels.length - dest);
                return pixels;
            }
            int scanlineWidth = (b2 << 8) | b3;
            if (scanlineWidth != width) {
                throw new IOException("Corrupt HDR scanline width.");
            }
            for (int channel = 0; channel < 4; channel++) {
                int offset = channel * width;
                int x = 0;
                while (x < width) {
                    int count = readUnsignedByte(stream);
                    if (count > 128) {
                        int runLength = count - 128;
                        int value = readUnsignedByte(stream);
                        Arrays.fill(scanline, offset + x, offset + x + runLength, (byte) value);
                        x += runLength;
                    } else if (count > 0) {
                        readFully(stream, scanline, offset + x, count);
                        x += count;
                    } else {
                        throw new IOException("Corrupt HDR RLE packet.");
                    }
                }
            }
            for (int x = 0; x < width; x++) {
                pixels[dest++] = scanline[x];
                pixels[dest++] = scanline[width + x];
                pixels[dest++] = scanline[2 * width + x];
                pixels[dest++] = scanline[3 * width + x];
            }
        }
        return pixels;
    }

    private static float[] decodeRgb(byte[] rgbe, int width, int height, boolean flipX, boolean flipY) {
        float[] out = new float[width * height * 3];
        for (int y = 0; y < height; y++) {
            int destY = flipY ? (height - 1 - y) : y;
            for (int x = 0; x < width; x++) {
                int destX = flipX ? (width - 1 - x) : x;
                int srcIndex = (y * width + x) * 4;
                int destIndex = (destY * width + destX) * 3;
                int exponent = rgbe[srcIndex + 3] & 0xFF;
                if (exponent == 0) {
                    out[destIndex] = 0.0f;
                    out[destIndex + 1] = 0.0f;
                    out[destIndex + 2] = 0.0f;
                    continue;
                }
                float scale = Math.scalb(1.0f, exponent - 136);
                out[destIndex] = (rgbe[srcIndex] & 0xFF) * scale;
                out[destIndex + 1] = (rgbe[srcIndex + 1] & 0xFF) * scale;
                out[destIndex + 2] = (rgbe[srcIndex + 2] & 0xFF) * scale;
            }
        }
        return out;
    }

    private static void readFully(InputStream stream, byte[] buffer, int offset, int length) throws IOException {
        int remaining = length;
        int cursor = offset;
        while (remaining > 0) {
            int read = stream.read(buffer, cursor, remaining);
            if (read < 0) {
                throw new IOException("Unexpected end of HDR stream.");
            }
            cursor += read;
            remaining -= read;
        }
    }

    private static int readUnsignedByte(InputStream stream) throws IOException {
        int value = stream.read();
        if (value < 0) {
            throw new IOException("Unexpected end of HDR stream.");
        }
        return value;
    }

    private static String readAsciiLine(InputStream stream) throws IOException {
        StringBuilder builder = new StringBuilder();
        while (true) {
            int value = stream.read();
            if (value < 0) {
                break;
            }
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                builder.append((char) value);
            }
        }
        return builder.toString();
    }

    private static int findCdfIndex(double[] cdf, int start, int end, double value) {
        double target = clamp01(value);
        int low = start;
        int high = end - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (target <= cdf[mid]) {
                high = mid;
            } else {
                low = mid + 1;
            }
        }
        return low;
    }

    private static void directionFromUv(double u, double v, Sample out) {
        double phi = wrap01(u) * TWO_PI;
        double theta = clamp01(v) * Math.PI;
        double sinTheta = Math.sin(theta);
        out.dx = Math.cos(phi) * sinTheta;
        out.dy = Math.cos(theta);
        out.dz = Math.sin(phi) * sinTheta;
    }

    private static double directionToU(double dx, double dz) {
        double phi = Math.atan2(dz, dx);
        if (phi < 0.0) {
            phi += TWO_PI;
        }
        return phi / TWO_PI;
    }

    private static double directionToV(double dy) {
        return Math.acos(clampSigned(dy)) / Math.PI;
    }

    private static double luminance(double r, double g, double b) {
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static double clampSigned(double value) {
        return Math.max(-1.0, Math.min(1.0, value));
    }

    private static double wrap01(double value) {
        double wrapped = value - Math.floor(value);
        if (wrapped < 0.0) {
            wrapped += 1.0;
        }
        return wrapped;
    }

    private static int floorToPixel(double value, int size) {
        int pixel = (int) Math.floor(value);
        if (pixel < 0) {
            pixel = ((pixel % size) + size) % size;
        }
        if (pixel >= size) {
            pixel %= size;
        }
        return pixel;
    }

    private static int wrapPixel(int value, int size) {
        if (value < 0) {
            return ((value % size) + size) % size;
        }
        if (value >= size) {
            return value % size;
        }
        return value;
    }

    private static int clampPixel(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private static final class RadianceHeader {
        final int width;
        final int height;
        final boolean flipX;
        final boolean flipY;

        RadianceHeader(int width, int height, boolean flipX, boolean flipY) {
            this.width = width;
            this.height = height;
            this.flipX = flipX;
            this.flipY = flipY;
        }
    }
}
