package engine.core;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class OutputPathUtil {

    private static final DateTimeFormatter SESSION_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT);

    private OutputPathUtil() {
    }

    static SessionPaths resolveSessionPaths(String baseDirectoryValue,
                                            String sessionNameValue,
                                            boolean createSessionFolder,
                                            boolean appendTimestamp,
                                            String sequenceSubfolderName,
                                            String timestampToken) {
        Path baseDirectory = normalizeBaseDirectory(baseDirectoryValue);
        String safeSessionName = sanitizeSegment(sessionNameValue, "render");
        String safeSequenceName = sanitizeSegment(sequenceSubfolderName, "sequence");
        String safeTimestamp = sanitizeTimestamp(timestampToken);
        String folderName = safeSessionName;
        if (createSessionFolder && appendTimestamp && !safeTimestamp.isBlank()) {
            folderName = folderName + "_" + safeTimestamp;
        }
        Path sessionFolder = baseDirectory.resolve(folderName).toAbsolutePath().normalize();

        Path sequenceFolder = sessionFolder.resolve(safeSequenceName);
        Path manifestPath = sessionFolder.resolve("manifest.json");
        Path previewPath = sessionFolder.resolve("preview.png");
        Path logPath = sessionFolder.resolve("log.txt");
        Path stillPathPng = sessionFolder.resolve("still.png");
        Path stillPathJpg = sessionFolder.resolve("still.jpg");
        Path gifPath = sessionFolder.resolve("animation.gif");
        Path aviPath = sessionFolder.resolve("animation.avi");
        return new SessionPaths(
                baseDirectory,
                sessionFolder,
                folderName,
                safeSequenceName,
                sequenceFolder,
                manifestPath,
                previewPath,
                logPath,
                stillPathPng,
                stillPathJpg,
                gifPath,
                aviPath
        );
    }

    static Path normalizeBaseDirectory(String value) {
        String base = value == null || value.isBlank() ? "renders" : value.trim();
        return Path.of(base).toAbsolutePath().normalize();
    }

    static String sanitizeSegment(String value, String fallback) {
        String normalized = value == null ? "" : value.trim();
        normalized = normalized.replaceAll("[\\\\/:*?\"<>|]+", "_");
        normalized = normalized.replaceAll("\\s+", "_");
        normalized = normalized.replaceAll("[^a-zA-Z0-9._-]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^[_\\.\\-]+", "");
        normalized = normalized.replaceAll("[_\\.\\-]+$", "");
        return normalized.isBlank() ? fallback : normalized;
    }

    static String timestampNow() {
        return LocalDateTime.now().format(SESSION_TIMESTAMP);
    }

    private static String sanitizeTimestamp(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        return token.replaceAll("[^0-9_]+", "");
    }

    static final class SessionPaths {
        final Path baseDirectory;
        final Path sessionFolder;
        final String sessionFolderName;
        final String sequenceFolderName;
        final Path sequenceFolder;
        final Path manifestPath;
        final Path previewPath;
        final Path logPath;
        final Path stillPngPath;
        final Path stillJpgPath;
        final Path gifPath;
        final Path aviPath;

        SessionPaths(Path baseDirectory,
                     Path sessionFolder,
                     String sessionFolderName,
                     String sequenceFolderName,
                     Path sequenceFolder,
                     Path manifestPath,
                     Path previewPath,
                     Path logPath,
                     Path stillPngPath,
                     Path stillJpgPath,
                     Path gifPath,
                     Path aviPath) {
            this.baseDirectory = baseDirectory;
            this.sessionFolder = sessionFolder;
            this.sessionFolderName = sessionFolderName;
            this.sequenceFolderName = sequenceFolderName;
            this.sequenceFolder = sequenceFolder;
            this.manifestPath = manifestPath;
            this.previewPath = previewPath;
            this.logPath = logPath;
            this.stillPngPath = stillPngPath;
            this.stillJpgPath = stillJpgPath;
            this.gifPath = gifPath;
            this.aviPath = aviPath;
        }

        Path stillPathForFormat(String format) {
            String normalized = normalizeStillFormat(format);
            return "jpg".equals(normalized) ? stillJpgPath : stillPngPath;
        }

        Path primaryOutputPath(String exportType, String stillFormat) {
            String normalized = normalizeExportType(exportType);
            return switch (normalized) {
                case "sequence" -> sequenceFolder;
                case "gif" -> gifPath;
                case "avi" -> aviPath;
                default -> stillPathForFormat(stillFormat);
            };
        }

        String primaryOutputPreview(String exportType, String stillFormat) {
            String normalized = normalizeExportType(exportType);
            return switch (normalized) {
                case "sequence" -> sequenceFolderName + "/frame_0000." + normalizeStillFormat(stillFormat) + " ...";
                case "gif" -> gifPath.getFileName().toString();
                case "avi" -> aviPath.getFileName().toString();
                default -> stillPathForFormat(stillFormat).getFileName().toString();
            };
        }

        Path sequenceFramePath(int frameNumber, int digits, String format) {
            String framePart = String.format(Locale.ROOT, "%0" + Math.max(1, digits) + "d", frameNumber);
            return sequenceFolder.resolve("frame_" + framePart + "." + normalizeStillFormat(format));
        }
    }

    static String normalizeStillFormat(String format) {
        if (format == null) {
            return "png";
        }
        String normalized = format.trim().toLowerCase(Locale.ROOT);
        if ("jpeg".equals(normalized)) {
            return "jpg";
        }
        return "jpg".equals(normalized) ? "jpg" : "png";
    }

    static String normalizeExportType(String exportType) {
        if (exportType == null) {
            return "still";
        }
        String normalized = exportType.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "sequence", "gif", "avi" -> normalized;
            default -> "still";
        };
    }
}
