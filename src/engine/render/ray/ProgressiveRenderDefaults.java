package engine.render.ray;

/**
 * Centralni vychozi kvalita pro progresivni ray/path rendering.
 */
public final class ProgressiveRenderDefaults {

    public static final int PATH_VIEWPORT_SAMPLES_PER_FRAME = 6;
    public static final int RAY_VIEWPORT_ADAPTIVE_MIN_SAMPLES = 12;
    public static final double RAY_VIEWPORT_ADAPTIVE_THRESHOLD = 0.055;
    public static final int RAY_VIEWPORT_DENOISE_RADIUS = 2;
    public static final double RAY_VIEWPORT_DENOISE_STRENGTH = 0.58;

    public static final int PATH_VIEWPORT_ADAPTIVE_MIN_SAMPLES = 40;
    public static final double PATH_VIEWPORT_ADAPTIVE_THRESHOLD = 0.032;
    public static final int PATH_VIEWPORT_DENOISE_RADIUS = 2;
    public static final double PATH_VIEWPORT_DENOISE_STRENGTH = 0.26;
    public static final double PATH_VIEWPORT_CLAMP_DIRECT = 8.0;
    public static final double PATH_VIEWPORT_CLAMP_INDIRECT = 6.0;

    public static final int OUTPUT_RAY_ADAPTIVE_MIN_SAMPLES = 24;
    public static final double OUTPUT_RAY_ADAPTIVE_THRESHOLD = 0.032;
    public static final int OUTPUT_PATH_ADAPTIVE_MIN_SAMPLES = 64;
    public static final double OUTPUT_PATH_ADAPTIVE_THRESHOLD = 0.030;
    public static final int OUTPUT_DENOISE_RADIUS = 2;
    public static final double OUTPUT_DENOISE_STRENGTH = 0.30;
    public static final double OUTPUT_PATH_CLAMP_DIRECT = 10.0;
    public static final double OUTPUT_PATH_CLAMP_INDIRECT = 8.0;

    private ProgressiveRenderDefaults() {
    }
}
