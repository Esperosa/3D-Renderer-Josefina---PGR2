package engine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

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

    public static int recommendedWorkerCount() {
        int cpu = Math.max(1, Runtime.getRuntime().availableProcessors());
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

 /**
 * Submits dávku úloh a počkám, až se všechny dokončí.
 *
 * @param tasks pole úloh
 */
    public void submitAndWait(Runnable[] tasks) {
        if (tasks == null || tasks.length == 0) {
            return;
        }
        RuntimeInstrumentation.FrameToken frameToken = RuntimeInstrumentation.captureCurrentToken();
        LongAdder workerBusyNanos = new LongAdder();
        List<Future<?>> futures = new ArrayList<>(tasks.length);
        for (Runnable task : tasks) {
            if (task != null) {
                futures.add(executor.submit(() -> {
                    RuntimeInstrumentation.attachFrame(frameToken);
                    long busyStart = System.nanoTime();
                    try {
                        task.run();
                    } finally {
                        workerBusyNanos.add(Math.max(0L, System.nanoTime() - busyStart));
                        RuntimeInstrumentation.clearAttachedFrame(frameToken);
                    }
                }));
            }
        }

        long waitStart = System.nanoTime();
        for (Future<?> f : futures) {
            try {
                f.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("ThreadPool interrupted while waiting for tasks.", e);
            } catch (ExecutionException e) {
                throw new RuntimeException("ThreadPool task failed.", e.getCause());
            }
        }
        long waitNanos = Math.max(0L, System.nanoTime() - waitStart);
        long busyNanos = workerBusyNanos.sum();
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WORKER_WAIT_NS, waitNanos);
        RuntimeInstrumentation.addCounter(RuntimeInstrumentation.Counter.WORKER_BUSY_NS, busyNanos);
        long totalWindowNanos = waitNanos * Math.max(1, futures.size());
        RuntimeInstrumentation.addCounter(
                RuntimeInstrumentation.Counter.WORKER_IDLE_NS,
                Math.max(0L, totalWindowNanos - busyNanos));
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
}