package engine.core;

/**
 * Tady držím odložený požadavek na spuštění výstupního renderu.
 */
final class OutputRenderPendingRequest {
    OutputRenderRequestType type;
    String outputFormat;
    int frameStart;
    int frameEnd;
    double frameRate;
}
