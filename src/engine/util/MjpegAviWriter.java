package engine.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Tady zapisuju minimální MJPEG AVI nad RIFF AVI a JPEG enkódováním z ImageIO.
 */
public final class MjpegAviWriter implements Closeable {

    private static final int AVIIF_KEYFRAME = 0x00000010;

    private final RandomAccessFile raf;
    private final Path outputPath;
    private final int width;
    private final int height;
    private final int microSecPerFrame;
    private final int streamRate;
    private final int streamScale;
    private final float jpegQuality;
    private final long riffSizeOffset;
    private final long hdrlSizeOffset;
    private final long avihDataOffset;
    private final long strlSizeOffset;
    private final long strhDataOffset;
    private final long moviSizeOffset;
    private final long moviListTypeOffset;
    private final List<IndexEntry> indexEntries = new ArrayList<>();

    private boolean closed;
    private int frameCount;
    private int maxChunkSize;

    public MjpegAviWriter(Path outputPath,
                          int width,
                          int height,
                          double frameRate,
                          double jpegQuality) throws IOException {
        this.outputPath = outputPath.toAbsolutePath().normalize();
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
        this.jpegQuality = (float) Math.max(0.05, Math.min(1.0, jpegQuality));

        int[] rateScale = computeRateScale(frameRate);
        this.streamRate = rateScale[0];
        this.streamScale = rateScale[1];
        this.microSecPerFrame = Math.max(1, (int) Math.round(1_000_000.0 * this.streamScale / this.streamRate));

        this.raf = new RandomAccessFile(this.outputPath.toFile(), "rw");
        this.raf.setLength(0L);

        writeFourCC("RIFF");
        this.riffSizeOffset = raf.getFilePointer();
        writeIntLE(0);
        writeFourCC("AVI ");

        writeFourCC("LIST");
        this.hdrlSizeOffset = raf.getFilePointer();
        writeIntLE(0);
        writeFourCC("hdrl");

        writeFourCC("avih");
        writeIntLE(56);
        this.avihDataOffset = raf.getFilePointer();
        writeZeroBytes(56);

        writeFourCC("LIST");
        this.strlSizeOffset = raf.getFilePointer();
        writeIntLE(0);
        writeFourCC("strl");

        writeFourCC("strh");
        writeIntLE(56);
        this.strhDataOffset = raf.getFilePointer();
        writeZeroBytes(56);

        writeFourCC("strf");
        writeIntLE(40);
        writeBitmapInfoHeader();

        long hdrlEnd = raf.getFilePointer();
        patchIntLE(this.strlSizeOffset, (int) (hdrlEnd - (this.strlSizeOffset + 4L)));
        patchIntLE(this.hdrlSizeOffset, (int) (hdrlEnd - (this.hdrlSizeOffset + 4L)));

        writeFourCC("LIST");
        this.moviSizeOffset = raf.getFilePointer();
        writeIntLE(0);
        this.moviListTypeOffset = raf.getFilePointer();
        writeFourCC("movi");
        this.closed = false;
        this.frameCount = 0;
        this.maxChunkSize = 0;
    }

    public void writeFrame(BufferedImage image) throws IOException {
        ensureOpen();
        if (image == null) {
            return;
        }
        byte[] jpegBytes = encodeJpeg(image, width, height, jpegQuality);
        long chunkOffset = raf.getFilePointer();
        writeFourCC("00dc");
        writeIntLE(jpegBytes.length);
        raf.write(jpegBytes);
        if ((jpegBytes.length & 1) != 0) {
            raf.write(0);
        }
        indexEntries.add(new IndexEntry(chunkOffset, jpegBytes.length));
        frameCount++;
        maxChunkSize = Math.max(maxChunkSize, jpegBytes.length + 8);
    }

    public int getFrameCount() {
        return frameCount;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        IOException failure = null;
        try {
            long moviEnd = raf.getFilePointer();
            writeFourCC("idx1");
            writeIntLE(indexEntries.size() * 16);
            for (IndexEntry entry : indexEntries) {
                writeFourCC("00dc");
                writeIntLE(AVIIF_KEYFRAME);
                long relativeOffset = entry.chunkOffset - moviListTypeOffset;
                writeIntLE((int) relativeOffset);
                writeIntLE(entry.dataSize);
            }
            long fileEnd = raf.getFilePointer();

            patchIntLE(moviSizeOffset, (int) (moviEnd - (moviSizeOffset + 4L)));
            patchMainHeader();
            patchStreamHeader();
            patchIntLE(riffSizeOffset, (int) (fileEnd - 8L));
        } catch (IOException ex) {
            failure = ex;
        } finally {
            closed = true;
            try {
                raf.close();
            } catch (IOException closeError) {
                if (failure == null) {
                    failure = closeError;
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void patchMainHeader() throws IOException {
        raf.seek(avihDataOffset);
        writeIntLE(microSecPerFrame);
        int maxBytesPerSecond = frameCount > 0
                ? (int) Math.max(maxChunkSize, Math.round((maxChunkSize * (double) streamRate) / Math.max(1, streamScale)))
                : 0;
        writeIntLE(maxBytesPerSecond);
        writeIntLE(0);
        writeIntLE(0x10);
        writeIntLE(frameCount);
        writeIntLE(0);
        writeIntLE(1);
        writeIntLE(maxChunkSize);
        writeIntLE(width);
        writeIntLE(height);
        writeIntLE(0);
        writeIntLE(0);
        writeIntLE(0);
        writeIntLE(0);
    }

    private void patchStreamHeader() throws IOException {
        raf.seek(strhDataOffset);
        writeFourCC("vids");
        writeFourCC("MJPG");
        writeIntLE(0);
        writeShortLE(0);
        writeShortLE(0);
        writeIntLE(0);
        writeIntLE(streamScale);
        writeIntLE(streamRate);
        writeIntLE(0);
        writeIntLE(frameCount);
        writeIntLE(maxChunkSize);
        writeIntLE(-1);
        writeIntLE(0);
        writeShortLE(0);
        writeShortLE(0);
        writeShortLE(width);
        writeShortLE(height);
    }

    private void writeBitmapInfoHeader() throws IOException {
        writeIntLE(40);
        writeIntLE(width);
        writeIntLE(height);
        writeShortLE(1);
        writeShortLE(24);
        writeFourCC("MJPG");
        writeIntLE(width * height * 3);
        writeIntLE(0);
        writeIntLE(0);
        writeIntLE(0);
        writeIntLE(0);
    }

    private static byte[] encodeJpeg(BufferedImage source,
                                     int width,
                                     int height,
                                     float quality) throws IOException {
        BufferedImage rgb = ensureRgb(source, width, height);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageIO writer available for AVI export.");
        }
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(8192, width * height));
        ImageOutputStream ios = ImageIO.createImageOutputStream(output);
        if (ios == null) {
            writer.dispose();
            throw new IOException("Unable to create JPEG memory stream for AVI encoding.");
        }
        try (ios) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
            ios.flush();
        } finally {
            writer.dispose();
        }
        return output.toByteArray();
    }

    private static BufferedImage ensureRgb(BufferedImage source, int width, int height) {
        if (source.getWidth() == width
                && source.getHeight() == height
                && source.getType() == BufferedImage.TYPE_INT_RGB) {
            return source;
        }
        BufferedImage rgb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = rgb.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(source, 0, 0, width, height, null);
        g2.dispose();
        return rgb;
    }

    private static int[] computeRateScale(double frameRate) {
        double clamped = Double.isFinite(frameRate) ? Math.max(1.0, Math.min(240.0, frameRate)) : 24.0;
        int scale = 1000;
        int rate = Math.max(1, (int) Math.round(clamped * scale));
        int gcd = gcd(rate, scale);
        return new int[]{rate / gcd, scale / gcd};
    }

    private static int gcd(int a, int b) {
        int x = Math.abs(a);
        int y = Math.abs(b);
        while (y != 0) {
            int next = x % y;
            x = y;
            y = next;
        }
        return Math.max(1, x);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("AVI writer is already closed: " + outputPath);
        }
    }

    private void writeZeroBytes(int count) throws IOException {
        for (int i = 0; i < count; i++) {
            raf.write(0);
        }
    }

    private void patchIntLE(long offset, int value) throws IOException {
        long resume = raf.getFilePointer();
        raf.seek(offset);
        writeIntLE(value);
        raf.seek(resume);
    }

    private void writeIntLE(int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >>> 8) & 0xFF);
        raf.write((value >>> 16) & 0xFF);
        raf.write((value >>> 24) & 0xFF);
    }

    private void writeShortLE(int value) throws IOException {
        raf.write(value & 0xFF);
        raf.write((value >>> 8) & 0xFF);
    }

    private void writeFourCC(String fourCC) throws IOException {
        if (fourCC == null || fourCC.length() != 4) {
            throw new IllegalArgumentException("FOURCC must be exactly 4 characters.");
        }
        raf.write(fourCC.charAt(0));
        raf.write(fourCC.charAt(1));
        raf.write(fourCC.charAt(2));
        raf.write(fourCC.charAt(3));
    }

    private static final class IndexEntry {
        final long chunkOffset;
        final int dataSize;

        IndexEntry(long chunkOffset, int dataSize) {
            this.chunkOffset = chunkOffset;
            this.dataSize = dataSize;
        }
    }
}
