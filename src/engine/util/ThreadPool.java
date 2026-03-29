package engine.util;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;

/**
 * Represents pevně velký thread pool pro paralelní vykreslování.
 */
public class ThreadPool {

    private final ExecutorService executor;
    private final int threadCount;

    public ThreadPool() {
        this(recommendedWorkerCount());
    }

    public ThreadPool(int threads) {
        this.threadCount = Math.max(1, threads);
        AtomicInteger counter = new AtomicInteger(1);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "RenderWorker-" + counter.getAndIncrement());
            t.setDaemon(true);
            t.setPriority(Math.max(Thread.MIN_PRIORITY + 1, Thread.NORM_PRIORITY - 1));
            return t;
        };
        this.executor = Executors.newFixedThreadPool(this.threadCount, factory);
    }

    public static int availableWorkerCount() {
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    public static int recommendedWorkerCount() {
        int cpu = availableWorkerCount();
        if (cpu <= 2) {
            return 1;
        }
        int reserve;
        if (cpu >= 24) {
            reserve = 4;
        } else if (cpu >= 16) {
            reserve = 3;
        } else if (cpu >= 8) {
            reserve = 2;
        } else {
            reserve = 1;
        }
        return Math.max(1, cpu - reserve);
    }

    public static int recommendedOutputWorkerCount() {
        int cpu = availableWorkerCount();
        if (cpu <= 2) {
            return 1;
        }
        double systemCpuLoad = HardwareTelemetrySampler.sampleCpuOnly().systemCpuLoad();
        int reserve;
        if (cpu >= 32) {
            reserve = 3;
        } else if (cpu >= 16) {
            reserve = 2;
        } else {
            reserve = 1;
        }
        if (Double.isFinite(systemCpuLoad)) {
            if (systemCpuLoad >= 0.88) {
                reserve += cpu >= 16 ? 2 : 1;
            } else if (systemCpuLoad >= 0.74) {
                reserve += 1;
            } else if (systemCpuLoad <= 0.30) {
                reserve = Math.max(1, reserve - 1);
            }
        }
        return Math.max(1, cpu - Math.max(1, Math.min(cpu - 1, reserve)));
    }

 /**
 * Submits dávku úloh a počkám, až se všechny dokončí.
 *
 * @param tasks pole úloh
 */
    public void submitAndWait(Runnable[] tasks) {
        if (tasks == null || tasks.length == 0) {
            return;
        }
        int scheduled = 0;
        for (Runnable task : tasks) {
            if (task != null) {
                scheduled++;
            }
        }
        if (scheduled == 0) {
            return;
        }
        BatchWaitState batch = new BatchWaitState(scheduled);
        Runnable inlineTask = null;
        for (Runnable task : tasks) {
            if (task == null) {
                continue;
            }
            if (inlineTask == null) {
                inlineTask = task;
            } else {
                scheduleBatchTask(batch, task);
            }
        }
        runBatchTaskInline(batch, inlineTask);
        awaitBatch(batch);
    }

 /**
 * Spustí opakovanou dávku pracovníků bez vytváření dočasného pole úloh.
 *
 * @param taskCount počet worker tasků
 * @param workerTask tělo worker tasku
 */
    public void submitAndWait(int taskCount, IntConsumer workerTask) {
        if (taskCount <= 0 || workerTask == null) {
            return;
        }
        BatchWaitState batch = new BatchWaitState(taskCount);
        for (int workerIndex = 1; workerIndex < taskCount; workerIndex++) {
            final int index = workerIndex;
            scheduleBatchTask(batch, () -> workerTask.accept(index));
        }
        runBatchTaskInline(batch, () -> workerTask.accept(0));
        awaitBatch(batch);
    }

 /**
 * Submits jednu úlohu asynchronně.
 *
 * @param task úlohu
 * @return vrátí objekt Future pro sledování dokončení
 */
    public Future<?> submit(Runnable task) {
        if (task == null) {
            return null;
        }
        return executor.submit(task);
    }

 /** @return vrátí počet pracovních vláken */
    public int getThreadCount() {
        return threadCount;
    }

 /** pool ukončím při vypnutí enginu. */
    public void shutdown() {
        executor.shutdownNow();
    }

    private void scheduleBatchTask(BatchWaitState batch, Runnable task) {
        executor.execute(() -> {
            runBatchTask(batch, task);
        });
    }

    private void runBatchTaskInline(BatchWaitState batch, Runnable task) {
        if (task == null) {
            return;
        }
        runBatchTask(batch, task);
    }

    private void runBatchTask(BatchWaitState batch, Runnable task) {
        RuntimeInstrumentation.attachFrame(batch.frameToken);
        long busyStart = System.nanoTime();
        try {
            task.run();
        } catch (Throwable t) {
            batch.failure.compareAndSet(null, t);
        } finally {
            batch.workerBusyNanos.add(Math.max(0L, System.nanoTime() - busyStart));
            RuntimeInstrumentation.clearAttachedFrame(batch.frameToken);
            batch.latch.countDown();
        }
    }

    private void awaitBatch(BatchWaitState batch) {
        long waitStart = System.nanoTime();
        try {
            batch.latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ThreadPool interrupted while waiting for tasks.", e);
        }
        long waitNanos = Math.max(0L, System.nanoTime() - waitStart);
        long batchWallNanos = Math.max(0L, System.nanoTime() - batch.startNanos);
        long busyNanos = batch.workerBusyNanos.sum();
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WORKER_WAIT_NS, waitNanos);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WORKER_BUSY_NS, busyNanos);
        long totalWindowNanos = batchWallNanos * Math.max(1, batch.taskCount);
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.WORKER_IDLE_NS,
                Math.max(0L, totalWindowNanos - busyNanos));
        Throwable failure = batch.failure.get();
        if (failure != null) {
            throw new RuntimeException("ThreadPool task failed.", failure);
        }
    }

    private static final class BatchWaitState {
        final RuntimeInstrumentation.FrameToken frameToken;
        final LongAdder workerBusyNanos;
        final CountDownLatch latch;
        final AtomicReference<Throwable> failure;
        final int taskCount;
        final long startNanos;

        BatchWaitState(int taskCount) {
            this.frameToken = RuntimeInstrumentation.captureCurrentToken();
            this.workerBusyNanos = new LongAdder();
            this.latch = new CountDownLatch(taskCount);
            this.failure = new AtomicReference<>();
            this.taskCount = taskCount;
            this.startNanos = System.nanoTime();
        }
    }
}
