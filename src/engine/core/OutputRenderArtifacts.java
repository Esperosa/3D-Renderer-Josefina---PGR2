package engine.core;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tady zapisuju artefakty a serializuju výsledky output renderu.
 */
final class OutputRenderArtifacts {

    private OutputRenderArtifacts() {
    }

    static BufferedImage framebufferToImage(engine.render.FrameBuffer fb, int outW, int outH, boolean withAlpha) {
        int srcW = fb.getWidth();
        int srcH = fb.getHeight();
        int[] src = fb.getColorBuffer();
        BufferedImage base = new BufferedImage(srcW, srcH, BufferedImage.TYPE_INT_ARGB);
        base.setRGB(0, 0, srcW, srcH, src, 0, srcW);

        if (srcW != outW || srcH != outH) {
            BufferedImage scaled = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2 = scaled.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(base, 0, 0, outW, outH, null);
            g2.dispose();
            base = scaled;
        }

        if (withAlpha) {
            return base;
        }

        BufferedImage rgb = new BufferedImage(outW, outH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = rgb.createGraphics();
        g2.drawImage(base, 0, 0, null);
        g2.dispose();
        return rgb;
    }

    static void writeSessionArtifacts(OutputRenderJob job, double elapsedSeconds) throws IOException {
        if (job == null || job.paths == null || job.session == null) {
            return;
        }
        Files.createDirectories(job.paths.sessionFolder);
        if (job.requestType == OutputRenderRequestType.IMAGE_SEQUENCE) {
            Files.createDirectories(job.paths.sequenceFolder);
        }

        if (job.writePreviewImage && job.session.previewImage != null) {
            Path preview = writeOutputImage(job.session.previewImage, job.paths.previewPath, "png", 1.0);
            registerGeneratedFile(job, preview);
        }
        if (job.writeLogFile) {
            registerGeneratedFile(job, job.paths.logPath);
        }
        if (job.writeManifest) {
            registerGeneratedFile(job, job.paths.manifestPath);
        }
        if (job.writeLogFile) {
            writeTextFile(job.paths.logPath, buildLogText(job, elapsedSeconds));
        }
        if (job.writeManifest) {
            writeTextFile(job.paths.manifestPath, buildManifest(job, elapsedSeconds).toJson());
        }
    }

    static void registerGeneratedFile(OutputRenderJob job, Path path) {
        if (job == null || job.session == null || path == null) {
            return;
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (!job.session.generatedFiles.contains(normalized)) {
            job.session.generatedFiles.add(normalized);
        }
    }

    private static OutputRenderManifest buildManifest(OutputRenderJob job, double elapsedSeconds) {
        OutputRenderManifest manifest = new OutputRenderManifest();
        manifest.timestamp = job.timestampToken;
        manifest.exportType = job.exportType;
        manifest.renderer = job.mode == null ? RenderMode.PATH_TRACING.name() : job.mode.name();
        manifest.width = job.width;
        manifest.height = job.height;
        manifest.internalScale = job.internalScale;
        manifest.internalWidth = job.internalWidth;
        manifest.internalHeight = job.internalHeight;
        manifest.workerCount = job.workerCount;
        manifest.tileSize = job.tileSize;
        manifest.targetSamples = job.targetSamples;
        manifest.samplesPerStep = job.samplesPerStep;
        manifest.maxDepth = job.maxDepth;
        manifest.outputFormat = job.format;
        manifest.frameStart = job.frameStart;
        manifest.frameEnd = job.frameEnd;
        manifest.stillFrame = job.stillFrame;
        manifest.frameRate = job.frameRate;
        manifest.frameCount = job.frameCount;
        manifest.baseDirectory = job.baseDirectory;
        manifest.sessionFolderName = job.paths.sessionFolderName;
        manifest.finalFolder = job.paths.sessionFolder.toString();
        manifest.cancelled = job.session.cancelled;
        manifest.success = job.session.success;
        manifest.statusMessage = job.session.statusMessage;
        manifest.renderDurationMillis = Math.max(0L, (long) Math.round(elapsedSeconds * 1000.0));
        manifest.denoise = job.denoise;
        manifest.writeManifest = job.writeManifest;
        manifest.writePreviewImage = job.writePreviewImage;
        manifest.writeLogFile = job.writeLogFile;
        manifest.createSessionFolder = job.createSessionFolder;
        manifest.appendTimestampToSession = job.appendTimestampToSession;
        manifest.gifLoopForever = job.gifLoopForever;
        manifest.aviJpegQuality = job.aviJpegQuality;
        manifest.jpgQuality = job.jpgQuality;
        manifest.sequenceSubfolderName = job.sequenceSubfolderName;
        manifest.saveAlphaWhenPossible = job.saveAlphaWhenPossible;
        manifest.useTimelineRange = job.useTimelineRange;
        manifest.rendererSettings.putAll(buildRelevantRendererSettings(job));
        for (Path generated : job.session.generatedFiles) {
            manifest.generatedFiles.add(relativeToSession(job, generated));
        }
        return manifest;
    }

    private static Map<String, Object> buildRelevantRendererSettings(OutputRenderJob job) {
        Map<String, Object> settingsMap = new LinkedHashMap<>();
        settingsMap.put("frustumCulling", job.frustumCulling);
        settingsMap.put("backfaceCulling", job.backfaceCulling);
        settingsMap.put("workerCount", job.workerCount);
        settingsMap.put("internalScale", job.internalScale);
        switch (job.mode) {
            case MODEL, BASIC, PHONG -> settingsMap.put("singlePass", true);
            case WIREFRAME -> {
                settingsMap.put("depthHiddenLines", job.wireframeDepthHiddenLines);
                settingsMap.put("silhouetteBoost", job.wireframeSilhouetteBoost);
                settingsMap.put("dashedMode", job.wireframeDashedMode);
            }
            case DITHERING -> {
                settingsMap.put("style", job.ditherStyle);
                settingsMap.put("toneCount", job.ditherToneCount);
                settingsMap.put("contrast", job.ditherContrast);
                settingsMap.put("invert", job.ditherInvert);
                settingsMap.put("cellSize", job.ditherCellSize);
                settingsMap.put("asciiCharset", job.ditherAsciiCharset);
            }
            case TEMPORAL_NOISE -> {
                settingsMap.put("temporalTickRate", job.temporalTickRate);
                settingsMap.put("depthNearContribution", job.temporalNearContribution);
                settingsMap.put("grazingContribution", job.temporalGrazingContribution);
                settingsMap.put("minSpeed", job.temporalMinSpeed);
                settingsMap.put("maxSpeed", job.temporalMaxSpeed);
                settingsMap.put("edgeBlendStrength", job.temporalEdgeBlendStrength);
                settingsMap.put("grainCellSize", job.temporalGrainCellSize);
                settingsMap.put("paletteLevels", job.temporalPaletteLevels);
            }
            case HEX_MOSAIC -> {
                settingsMap.put("cellSize", job.hexCellSize);
                settingsMap.put("quantizationLevels", job.hexQuantizationLevels);
                settingsMap.put("outlineStrength", job.hexOutlineStrength);
                settingsMap.put("edgeAware", job.hexEdgeAware);
                settingsMap.put("distanceScaling", job.hexDistanceScaling);
                settingsMap.put("debugCells", job.hexDebugCells);
                settingsMap.put("wowMode", job.hexWowMode);
                settingsMap.put("wowStrength", job.hexWowStrength);
            }
            case RAY_TRACING -> {
                settingsMap.put("tileSize", job.tileSize);
                settingsMap.put("targetSamples", job.targetSamples);
                settingsMap.put("samplesPerStep", job.samplesPerStep);
                settingsMap.put("maxDepth", job.maxDepth);
                settingsMap.put("directLighting", job.directLighting);
                settingsMap.put("shadows", job.shadows);
                settingsMap.put("reflections", job.reflections);
                settingsMap.put("sky", job.sky);
                settingsMap.put("denoise", job.denoise);
            }
            case PATH_TRACING -> {
                settingsMap.put("tileSize", job.tileSize);
                settingsMap.put("targetSamples", job.targetSamples);
                settingsMap.put("samplesPerStep", job.samplesPerStep);
                settingsMap.put("maxDepth", job.maxDepth);
                settingsMap.put("directLighting", job.directLighting);
                settingsMap.put("sky", job.sky);
                settingsMap.put("denoise", job.denoise);
            }
        }
        return settingsMap;
    }

    private static String buildLogText(OutputRenderJob job, double elapsedSeconds) {
        StringBuilder log = new StringBuilder(512);
        log.append("Status: ").append(job.session.statusMessage).append('\n');
        log.append("Export: ").append(OutputRenderSupport.requestTypeLabel(job.requestType)).append('\n');
        log.append("Renderer: ").append(job.mode).append('\n');
        log.append("Rozlišení: ").append(job.width).append('x').append(job.height).append('\n');
        log.append("Interní rozlišení: ").append(job.internalWidth).append('x').append(job.internalHeight).append('\n');
        log.append("Snímky: ").append(job.requestType == OutputRenderRequestType.STILL
                ? Integer.toString(job.stillFrame)
                : (job.frameStart + " .. " + job.frameEnd)).append('\n');
        log.append("FPS: ").append(String.format(Locale.ROOT, "%.2f", job.frameRate)).append('\n');
        log.append("Čas: ").append(OutputRenderSupport.formatDuration(elapsedSeconds)).append('\n');
        log.append("Vygenerované soubory:\n");
        for (Path generated : job.session.generatedFiles) {
            log.append(" - ").append(relativeToSession(job, generated)).append('\n');
        }
        return log.toString();
    }

    private static String relativeToSession(OutputRenderJob job, Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        try {
            return job.paths.sessionFolder.relativize(absolute).toString().replace('\\', '/');
        } catch (IllegalArgumentException ex) {
            return absolute.toString().replace('\\', '/');
        }
    }

    private static void writeTextFile(Path path, String content) throws IOException {
        Path parent = path.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                content == null ? "" : content,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        );
    }

    static Path writeOutputImage(BufferedImage image,
                                 Path outPath,
                                 String format,
                                 double quality) throws IOException {
        if (image == null || outPath == null) {
            return null;
        }
        Path parent = outPath.toAbsolutePath().normalize().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String normalized = OutputRenderSupport.normalizeStillFormat(format);
        if ("jpg".equals(normalized)) {
            writeJpegImage(image, outPath, quality);
        } else {
            ImageIO.write(image, "png", outPath.toFile());
        }
        return outPath.toAbsolutePath().normalize();
    }

    private static void writeJpegImage(BufferedImage image, Path outPath, double quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG ImageIO writer available.");
        }
        BufferedImage rgb = ensureRgbImage(image);
        ImageWriter writer = writers.next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        if (param.canWriteCompressed()) {
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality((float) Math.max(0.05, Math.min(1.0, quality)));
        }
        ImageOutputStream ios = ImageIO.createImageOutputStream(outPath.toFile());
        if (ios == null) {
            writer.dispose();
            throw new IOException("Unable to create JPEG output stream for " + outPath);
        }
        try (ios) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static BufferedImage ensureRgbImage(BufferedImage image) {
        if (image == null) {
            return null;
        }
        if (image.getType() == BufferedImage.TYPE_INT_RGB) {
            return image;
        }
        BufferedImage rgb = new BufferedImage(
                Math.max(1, image.getWidth()),
                Math.max(1, image.getHeight()),
                BufferedImage.TYPE_INT_RGB
        );
        Graphics2D g2 = rgb.createGraphics();
        g2.drawImage(image, 0, 0, null);
        g2.dispose();
        return rgb;
    }
}
