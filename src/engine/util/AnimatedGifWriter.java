package engine.util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

/**
 * Tady zapisuju animovaný GIF jen přes JDK ImageIO.
 */
public final class AnimatedGifWriter implements Closeable {

    private final ImageWriter gifWriter;
    private final ImageWriteParam writeParam;
    private final IIOMetadata metadataTemplate;
    private final ImageOutputStream output;
    private boolean started;

    public AnimatedGifWriter(Path outputPath, int frameDelayMs, boolean loopForever) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix("gif");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No GIF ImageIO writer available.");
        }
        this.gifWriter = writers.next();
        this.writeParam = gifWriter.getDefaultWriteParam();
        this.output = ImageIO.createImageOutputStream(outputPath.toFile());
        if (this.output == null) {
            throw new IOException("Unable to create output stream for GIF: " + outputPath);
        }
        ImageTypeSpecifier imageType = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_ARGB);
        this.metadataTemplate = gifWriter.getDefaultImageMetadata(imageType, writeParam);
        configureMetadata(this.metadataTemplate, Math.max(10, frameDelayMs), loopForever);
        this.gifWriter.setOutput(this.output);
        this.gifWriter.prepareWriteSequence(null);
        this.started = true;
    }

    public void writeFrame(BufferedImage frame) throws IOException {
        if (!started) {
            throw new IllegalStateException("GIF writer is closed.");
        }
        if (frame == null) {
            return;
        }
        gifWriter.writeToSequence(new IIOImage(frame, null, metadataTemplate), writeParam);
    }

    @Override
    public void close() throws IOException {
        IOException error = null;
        try {
            if (started) {
                gifWriter.endWriteSequence();
                started = false;
            }
        } catch (IOException ex) {
            error = ex;
        } finally {
            try {
                output.close();
            } catch (IOException ex) {
                if (error == null) {
                    error = ex;
                }
            } finally {
                gifWriter.dispose();
            }
        }
        if (error != null) {
            throw error;
        }
    }

    private static void configureMetadata(IIOMetadata metadata, int delayMs, boolean loopForever) throws IOException {
        String formatName = metadata.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree(formatName);

        IIOMetadataNode graphicsControlExtension = getOrCreateChild(root, "GraphicControlExtension");
        int delayCs = Math.max(1, delayMs / 10);
        graphicsControlExtension.setAttribute("disposalMethod", "none");
        graphicsControlExtension.setAttribute("userInputFlag", "FALSE");
        graphicsControlExtension.setAttribute("transparentColorFlag", "FALSE");
        graphicsControlExtension.setAttribute("delayTime", Integer.toString(delayCs));
        graphicsControlExtension.setAttribute("transparentColorIndex", "0");

        IIOMetadataNode comments = getOrCreateChild(root, "CommentExtensions");
        comments.setAttribute("CommentExtension", "Created by 3D Render Physics Engine");

        IIOMetadataNode appExtensions = getOrCreateChild(root, "ApplicationExtensions");
        IIOMetadataNode appNode = new IIOMetadataNode("ApplicationExtension");
        appNode.setAttribute("applicationID", "NETSCAPE");
        appNode.setAttribute("authenticationCode", "2.0");
        int loop = loopForever ? 0 : 1;
        byte[] loopBytes = new byte[]{0x1, (byte) (loop & 0xFF), (byte) ((loop >> 8) & 0xFF)};
        appNode.setUserObject(loopBytes);
        appExtensions.appendChild(appNode);

        metadata.setFromTree(formatName, root);
    }

    private static IIOMetadataNode getOrCreateChild(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (name.equals(root.item(i).getNodeName())) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }
}
