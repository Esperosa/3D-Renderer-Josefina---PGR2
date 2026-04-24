package engine.core;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;

/**
 * Represents stav jedné výstupní session během renderu.
 */
final class OutputRenderSessionContext {
    final ArrayList<Path> generatedFiles = new ArrayList<>();
    BufferedImage previewImage;
    int previewFrameNumber = Integer.MIN_VALUE;
    int renderedFrameCount;
    boolean cancelled;
    boolean success;
    String statusMessage = "Čeká";
}
