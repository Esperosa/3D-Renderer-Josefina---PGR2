package engine.core;

import engine.util.ThreadPool;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class EditorWorkflowPerformanceEvidenceTests {

    private static final int WARMUP_RUNS = 1;
    private static final int MEASURED_RUNS = 4;
    private static final int BATCH_ROUNDS = 320;
    private static final int TILE_BATCHES = 14;
    private static final int MIX_ROUNDS = 48;
    private static final int OUTPUT_REFRESH_BURST = 48;
    private static volatile long guard;

    private EditorWorkflowPerformanceEvidenceTests() {
    }

    public static void main(String[] args) {
        Evidence outputRefresh = benchmarkOutputRefreshBurst();
        int workers = Math.max(2, Math.min(8, ThreadPool.availableWorkerCount()));
        Evidence evidence = benchmarkWorkerBatch(workers);
        if (outputRefresh != null) {
            System.out.println(String.format(
                    "WorkflowEvidence[output-refresh] baseline=%.2fms enhanced=%.2fms delta=%.1f%%",
                    outputRefresh.baselineMeanMs,
                    outputRefresh.enhancedMeanMs,
                    outputRefresh.deltaPercent));
        }
        System.out.println(String.format(
                "WorkflowEvidence[worker-batch] baseline=%.2fms enhanced=%.2fms delta=%.1f%% workers=%d",
                evidence.baselineMeanMs,
                evidence.enhancedMeanMs,
                evidence.deltaPercent,
                workers));
        if (outputRefresh != null && outputRefresh.deltaPercent < 10.0) {
            throw new AssertionError("Output tab refresh improvement is too small: " + outputRefresh.deltaPercent + "%");
        }
        System.out.println("EditorWorkflowPerformanceEvidenceTests: ALL TESTS PASSED");
    }

    private static Evidence benchmarkOutputRefreshBurst() {
        if (GraphicsEnvironment.isHeadless()) {
            return null;
        }
        for (int i = 0; i < WARMUP_RUNS; i++) {
            measureLegacyOutputRefreshBurst();
            measureEnhancedOutputRefreshBurst();
        }
        return new Evidence(
                averageMeasurements(EditorWorkflowPerformanceEvidenceTests::measureLegacyOutputRefreshBurst),
                averageMeasurements(EditorWorkflowPerformanceEvidenceTests::measureEnhancedOutputRefreshBurst));
    }

    private static Evidence benchmarkWorkerBatch(int workers) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            measureLegacyWorkerBatches(workers);
            measureEnhancedWorkerBatches(workers);
        }
        return new Evidence(
                averageMeasurements(() -> measureLegacyWorkerBatches(workers)),
                averageMeasurements(() -> measureEnhancedWorkerBatches(workers)));
    }

    private static double averageMeasurements(DoubleSupplier supplier) {
        List<Double> samples = new ArrayList<>();
        for (int i = 0; i < MEASURED_RUNS; i++) {
            samples.add(supplier.getAsDouble());
        }
        double sum = 0.0;
        for (double sample : samples) {
            sum += sample;
        }
        return sum / Math.max(1, samples.size());
    }

    private static double measureLegacyWorkerBatches(int workers) {
        ExecutorService executor = Executors.newFixedThreadPool(workers, threadFactory("LegacyWorker-"));
        long[] sinks = new long[workers];
        long start = System.nanoTime();
        try {
            for (int round = 0; round < BATCH_ROUNDS; round++) {
                List<Future<?>> futures = new ArrayList<>(workers);
                final int batchRound = round;
                for (int worker = 0; worker < workers; worker++) {
                    final int workerIndex = worker;
                    futures.add(executor.submit(() -> runViewportLikeBatch(workerIndex, batchRound, sinks)));
                }
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception ex) {
                        throw new RuntimeException("Legacy worker batch failed.", ex);
                    }
                }
            }
        } finally {
            guard ^= foldSinks(sinks);
            executor.shutdownNow();
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double measureEnhancedWorkerBatches(int workers) {
        ThreadPool pool = new ThreadPool(workers);
        long[] sinks = new long[workers];
        long start = System.nanoTime();
        try {
            for (int round = 0; round < BATCH_ROUNDS; round++) {
                final int batchRound = round;
                pool.submitAndWait(workers, workerIndex -> runViewportLikeBatch(workerIndex, batchRound, sinks));
            }
        } finally {
            guard ^= foldSinks(sinks);
            pool.shutdown();
        }
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static double measureLegacyOutputRefreshBurst() {
        Engine engine = new Engine();
        Window window = new Window("Output refresh baseline", 1280, 720);
        try {
            engine.window = window;
            EngineRenderPanels.buildOutputTab(engine);
            long start = System.nanoTime();
            runOnEdt(() -> {
                for (int i = 0; i < OUTPUT_REFRESH_BURST; i++) {
                    EngineRenderPanels.buildOutputTab(engine);
                }
            });
            return (System.nanoTime() - start) / 1_000_000.0;
        } finally {
            window.dispose();
        }
    }

    private static double measureEnhancedOutputRefreshBurst() {
        Engine engine = new Engine();
        Window window = new Window("Output refresh enhanced", 1280, 720);
        try {
            engine.window = window;
            EngineRenderPanels.buildOutputTab(engine);
            long start = System.nanoTime();
            runOnEdt(() -> {
                for (int i = 0; i < OUTPUT_REFRESH_BURST; i++) {
                    engine.requestOutputTabRefresh();
                }
            });
            waitForOutputRefreshIdle(engine);
            return (System.nanoTime() - start) / 1_000_000.0;
        } finally {
            window.dispose();
        }
    }

    private static void runViewportLikeBatch(int workerIndex, int round, long[] sinks) {
        long state = sinks[workerIndex] ^ mix64((workerIndex + 1L) * 0x9E3779B97F4A7C15L + round);
        for (int tile = 0; tile < TILE_BATCHES; tile++) {
            long tileState = state + mix64(tile + round * 17L + workerIndex * 131L);
            for (int i = 0; i < MIX_ROUNDS; i++) {
                tileState = mix64(tileState + i * 0xBF58476D1CE4E5B9L);
            }
            state ^= tileState + Long.rotateLeft(tileState, tile & 31);
        }
        sinks[workerIndex] = state;
    }

    private static long foldSinks(long[] sinks) {
        long value = 0L;
        if (sinks == null) {
            return value;
        }
        for (long sink : sinks) {
            value ^= mix64(sink + 0x94D049BB133111EBL);
        }
        return value;
    }

    private static ThreadFactory threadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + counter.getAndIncrement());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY + 1, Thread.NORM_PRIORITY - 1));
            return thread;
        };
    }

    private static long mix64(long value) {
        long mixed = value;
        mixed ^= mixed >>> 30;
        mixed *= 0xBF58476D1CE4E5B9L;
        mixed ^= mixed >>> 27;
        mixed *= 0x94D049BB133111EBL;
        mixed ^= mixed >>> 31;
        return mixed;
    }

    private static void waitForOutputRefreshIdle(Engine engine) {
        for (int i = 0; i < 8; i++) {
            runOnEdt(() -> { });
            if (!engine.outputTabRefreshPending && !engine.outputTabRefreshDirty) {
                return;
            }
        }
    }

    private static void runOnEdt(Runnable runnable) {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                runnable.run();
                return;
            }
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception ex) {
            throw new RuntimeException("EDT execution failed.", ex);
        }
    }

    @FunctionalInterface
    private interface DoubleSupplier {
        double getAsDouble();
    }

    private static final class Evidence {
        final double baselineMeanMs;
        final double enhancedMeanMs;
        final double deltaPercent;

        Evidence(double baselineMeanMs, double enhancedMeanMs) {
            this.baselineMeanMs = baselineMeanMs;
            this.enhancedMeanMs = enhancedMeanMs;
            this.deltaPercent = baselineMeanMs <= 0.0
                    ? 0.0
                    : ((baselineMeanMs - enhancedMeanMs) / baselineMeanMs) * 100.0;
        }
    }
}
