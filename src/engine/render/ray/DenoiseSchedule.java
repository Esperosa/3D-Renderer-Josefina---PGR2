package engine.render.ray;

final class DenoiseSchedule {

    private static final long MIN_ACTIVE_SAMPLES = 1L;
    private static final long SETTLED_SAMPLES = 30L;
    private static final double MAX_EARLY_STRENGTH_BOOST = 0.30;

    private DenoiseSchedule() {
    }

    record State(boolean active, int radius, double strength) {
    }

    static State resolve(long accumulatedSamples,
                         int configuredRadius,
                         double configuredStrength) {
        int baseRadius = clampRadius(configuredRadius);
        double baseStrength = DenoiseSupport.clamp01(configuredStrength);
        if (accumulatedSamples < MIN_ACTIVE_SAMPLES || baseStrength <= 0.0) {
            return new State(false, baseRadius, 0.0);
        }

        long settledSamples = settledSamples();
        double settleProgress = settledSamples <= 1L
                ? 1.0
                : DenoiseSupport.clamp01((accumulatedSamples - 1.0) / Math.max(1.0, settledSamples - 1.0));
        double earlyWeight = 1.0 - settleProgress;
        int effectiveRadius = Math.min(4, baseRadius + (earlyWeight > 0.10 ? 1 : 0));
        double effectiveStrength = DenoiseSupport.clamp01(baseStrength + earlyWeight * MAX_EARLY_STRENGTH_BOOST);
        return new State(true, effectiveRadius, effectiveStrength);
    }

    static long settledSamples() {
        return SETTLED_SAMPLES;
    }

    private static int clampRadius(int radius) {
        return Math.max(1, Math.min(4, radius));
    }
}
