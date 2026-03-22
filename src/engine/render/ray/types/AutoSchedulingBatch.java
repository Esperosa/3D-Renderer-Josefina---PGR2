package engine.render.ray.core;

import engine.render.ray.bvh.*;
final class AutoSchedulingBatch {

    private int intervalFrames;
    private int countdown;
    private double frameMsSum;
    private int frameCount;
    private long tileCostNanos;
    private long tileCostSamples;

    AutoSchedulingBatch(int intervalFrames) {
        setIntervalFrames(intervalFrames);
    }

    void setIntervalFrames(int intervalFrames) {
        this.intervalFrames = Math.max(1, intervalFrames);
        reset();
    }

    void reset() {
        countdown = intervalFrames;
        frameMsSum = 0.0;
        frameCount = 0;
        tileCostNanos = 0L;
        tileCostSamples = 0L;
    }

    void addSample(double frameMs, long tileNanos, long tileSamples) {
        frameMsSum += frameMs;
        frameCount++;
        tileCostNanos += Math.max(0L, tileNanos);
        tileCostSamples += Math.max(0L, tileSamples);
    }

    boolean shouldFlush() {
        countdown--;
        return countdown <= 0;
    }

    double meanFrameMs() {
        return frameMsSum / Math.max(1, frameCount);
    }

    long accumulatedTileCostNanos() {
        return tileCostNanos;
    }

    long accumulatedTileCostSamples() {
        return tileCostSamples;
    }
}
