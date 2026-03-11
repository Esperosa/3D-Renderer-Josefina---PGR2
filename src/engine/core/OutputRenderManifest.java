package engine.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class OutputRenderManifest {

    String timestamp;
    String exportType;
    String renderer;
    int width;
    int height;
    double internalScale;
    int internalWidth;
    int internalHeight;
    int workerCount;
    int tileSize;
    int targetSamples;
    int samplesPerStep;
    int maxDepth;
    String outputFormat;
    int frameStart;
    int frameEnd;
    int stillFrame;
    double frameRate;
    int frameCount;
    String finalFolder;
    final List<String> generatedFiles = new ArrayList<>();
    final Map<String, Object> rendererSettings = new LinkedHashMap<>();
    boolean cancelled;
    boolean success;
    long renderDurationMillis;
    String statusMessage;
    String sessionFolderName;
    String baseDirectory;
    boolean denoise;
    boolean writeManifest;
    boolean writePreviewImage;
    boolean writeLogFile;
    boolean createSessionFolder;
    boolean appendTimestampToSession;
    boolean gifLoopForever;
    double aviJpegQuality;
    double jpgQuality;
    String sequenceSubfolderName;
    boolean saveAlphaWhenPossible;
    boolean useTimelineRange;

    String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("timestamp", timestamp);
        root.put("exportType", exportType);
        root.put("renderer", renderer);
        root.put("width", width);
        root.put("height", height);
        root.put("internalScale", internalScale);
        root.put("internalWidth", internalWidth);
        root.put("internalHeight", internalHeight);
        root.put("workerCount", workerCount);
        root.put("tileSize", tileSize);
        root.put("targetSamples", targetSamples);
        root.put("samplesPerStep", samplesPerStep);
        root.put("maxDepth", maxDepth);
        root.put("outputFormat", outputFormat);
        root.put("frameStart", frameStart);
        root.put("frameEnd", frameEnd);
        root.put("stillFrame", stillFrame);
        root.put("fps", frameRate);
        root.put("frameCount", frameCount);
        root.put("baseDirectory", baseDirectory);
        root.put("sessionFolderName", sessionFolderName);
        root.put("finalFolder", finalFolder);
        root.put("generatedFiles", generatedFiles);
        root.put("denoise", denoise);
        root.put("writeManifest", writeManifest);
        root.put("writePreviewImage", writePreviewImage);
        root.put("writeLogFile", writeLogFile);
        root.put("createSessionFolder", createSessionFolder);
        root.put("appendTimestampToSession", appendTimestampToSession);
        root.put("gifLoopForever", gifLoopForever);
        root.put("aviJpegQuality", aviJpegQuality);
        root.put("jpgQuality", jpgQuality);
        root.put("sequenceSubfolderName", sequenceSubfolderName);
        root.put("saveAlphaWhenPossible", saveAlphaWhenPossible);
        root.put("useTimelineRange", useTimelineRange);
        root.put("rendererSettings", rendererSettings);
        root.put("cancelled", cancelled);
        root.put("success", success);
        root.put("renderDurationMillis", renderDurationMillis);
        root.put("renderDurationSeconds", renderDurationMillis / 1000.0);
        root.put("statusMessage", statusMessage);
        StringBuilder sb = new StringBuilder(2048);
        appendJsonValue(sb, root, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void appendJsonValue(StringBuilder sb, Object value, int indent) {
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof String text) {
            sb.append('"').append(escapeJson(text)).append('"');
            return;
        }
        if (value instanceof Number number) {
            if (number instanceof Double || number instanceof Float) {
                double d = number.doubleValue();
                if (!Double.isFinite(d)) {
                    sb.append("0");
                } else {
                    sb.append(String.format(Locale.ROOT, "%.6f", d).replaceAll("0+$", "").replaceAll("\\.$", ""));
                }
            } else {
                sb.append(number);
            }
            return;
        }
        if (value instanceof Boolean flag) {
            sb.append(flag);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            appendJsonObject(sb, map, indent);
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            appendJsonArray(sb, iterable, indent);
            return;
        }
        sb.append('"').append(escapeJson(String.valueOf(value))).append('"');
    }

    private static void appendJsonObject(StringBuilder sb, Map<?, ?> map, int indent) {
        sb.append("{\n");
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 2);
            sb.append('"').append(escapeJson(String.valueOf(entry.getKey()))).append("\": ");
            appendJsonValue(sb, entry.getValue(), indent + 2);
            if (index < map.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
            index++;
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void appendJsonArray(StringBuilder sb, Iterable<?> iterable, int indent) {
        List<Object> values = new ArrayList<>();
        for (Object value : iterable) {
            values.add(value);
        }
        sb.append("[\n");
        for (int i = 0; i < values.size(); i++) {
            indent(sb, indent + 2);
            appendJsonValue(sb, values.get(i), indent + 2);
            if (i < values.size() - 1) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append(' ');
        }
    }

    private static String escapeJson(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
