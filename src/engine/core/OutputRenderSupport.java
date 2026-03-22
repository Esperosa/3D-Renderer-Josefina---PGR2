package engine.core;

import engine.ui.UiStrings;

import java.util.Locale;

/**
 * Represents sdílené pomocné metody pro výstupní postup.
 */
final class OutputRenderSupport {

    private OutputRenderSupport() {
    }

    static void syncLegacySettings(OutputRenderController.Settings settings) {
        String baseDirectory = settings.baseDirectory;
        if (baseDirectory == null || baseDirectory.isBlank()) {
            baseDirectory = settings.outputDirectory;
        }
        if (baseDirectory == null || baseDirectory.isBlank()) {
            baseDirectory = "renders";
        }
        String sessionName = settings.sessionName;
        if (sessionName == null || sessionName.isBlank()) {
            sessionName = settings.filePrefix;
        }
        if (sessionName == null || sessionName.isBlank()) {
            sessionName = "render";
        }
        settings.baseDirectory = baseDirectory.trim();
        settings.outputDirectory = settings.baseDirectory;
        settings.sessionName = sessionName.trim();
        settings.filePrefix = settings.sessionName;
        settings.format = normalizeStillFormat(settings.format);
        settings.exportType = exportTypeFromRequestType(requestTypeFromExportType(settings.exportType));
        settings.frameStart = normalizeFrameStart(settings.frameStart, settings.frameEnd);
        settings.frameEnd = normalizeFrameEnd(settings.frameStart, settings.frameEnd);
        settings.frameRate = clampFrameRate(settings.frameRate);
        settings.sequenceSubfolderName = OutputPathUtil.sanitizeSegment(settings.sequenceSubfolderName, "sequence");
        settings.jpgQuality = Math.max(0.05, Math.min(1.0, settings.jpgQuality));
        settings.aviJpegQuality = Math.max(0.05, Math.min(1.0, settings.aviJpegQuality));
    }

    static String formatBytes(long bytes) {
        double value = Math.max(0L, bytes);
        String[] units = new String[]{"B", "KB", "MB", "GB"};
        int unitIndex = 0;
        while (value >= 1024.0 && unitIndex < units.length - 1) {
            value /= 1024.0;
            unitIndex++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, units[unitIndex]);
    }

    static boolean isSampleBasedMode(RenderMode mode) {
        return mode == RenderMode.RAY_TRACING || mode == RenderMode.PATH_TRACING;
    }

    static String onOff(boolean value) {
        return value ? UiStrings.Common.YES : UiStrings.Common.NO;
    }

    static String safeUpper(String value) {
        return value == null ? "-" : value.toUpperCase(Locale.ROOT);
    }

    static boolean isSupportedStillFormat(String fmt) {
        if (fmt == null) {
            return false;
        }
        String f = fmt.trim().toLowerCase(Locale.ROOT);
        return "png".equals(f) || "jpg".equals(f) || "jpeg".equals(f);
    }

    static int computeInternalDimension(int outputSize, double internalScale) {
        int safeOutput = Math.max(64, outputSize);
        return Math.max(64, Math.min(safeOutput, (int) Math.round(safeOutput * Math.max(0.25, Math.min(1.0, internalScale)))));
    }

    static int normalizeFrameStart(int startFrame, int endFrame) {
        return Math.max(0, Math.min(startFrame, endFrame));
    }

    static int normalizeFrameEnd(int startFrame, int endFrame) {
        return Math.max(normalizeFrameStart(startFrame, endFrame), Math.max(startFrame, endFrame));
    }

    static double clampFrameRate(double frameRate) {
        if (!Double.isFinite(frameRate)) {
            return 24.0;
        }
        return Math.max(1.0, Math.min(240.0, frameRate));
    }

    static String normalizeStillFormat(String fmt) {
        return OutputPathUtil.normalizeStillFormat(fmt);
    }

    static OutputRenderRequestType requestTypeFromExportType(String exportType) {
        return switch (OutputPathUtil.normalizeExportType(exportType)) {
            case "sequence" -> OutputRenderRequestType.IMAGE_SEQUENCE;
            case "gif" -> OutputRenderRequestType.ANIMATED_GIF;
            case "avi" -> OutputRenderRequestType.ANIMATED_AVI;
            default -> OutputRenderRequestType.STILL;
        };
    }

    static String exportTypeFromRequestType(OutputRenderRequestType type) {
        if (type == null) {
            return "still";
        }
        return switch (type) {
            case IMAGE_SEQUENCE -> "sequence";
            case ANIMATED_GIF -> "gif";
            case ANIMATED_AVI -> "avi";
            case STILL -> "still";
        };
    }

    static String requestTypeLabel(OutputRenderRequestType type) {
        if (type == null) {
            return UiStrings.Output.STILL_IMAGE;
        }
        return switch (type) {
            case STILL -> UiStrings.Output.STILL_IMAGE;
            case IMAGE_SEQUENCE -> UiStrings.Output.IMAGE_SEQUENCE;
            case ANIMATED_GIF -> UiStrings.Output.ANIMATED_GIF;
            case ANIMATED_AVI -> UiStrings.Output.ANIMATED_AVI;
        };
    }

    static String formatDuration(double seconds) {
        if (!Double.isFinite(seconds) || seconds <= 0.0) {
            return "0.0s";
        }
        long totalSeconds = (long) Math.floor(seconds);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long secs = totalSeconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.ROOT, "%dh %02dm %02ds", hours, minutes, secs);
        }
        if (minutes > 0L) {
            return String.format(Locale.ROOT, "%dm %02ds", minutes, secs);
        }
        return String.format(Locale.ROOT, "%.1fs", seconds);
    }

    static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
