package engine.util;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tady držím pevně velký thread pool pro paralelní vykreslování.
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
        int reserve = cpu >= 12 ? 2 : 1;
        return Math.max(1, cpu - reserve);
    }

    /**
     * Tady odešlu dávku úloh a počkám, až se všechny dokončí.
     *
     * @param tasks sem předám pole úloh
     */
    public void submitAndWait(Runnable[] tasks) {
        if (tasks == null || tasks.length == 0) {
            return;
        }
        List<Future<?>> futures = new ArrayList<>(tasks.length);
        for (Runnable task : tasks) {
            if (task != null) {
                futures.add(executor.submit(task));
            }
        }

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
    }

    /**
     * Tady odešlu jednu úlohu asynchronně.
     *
     * @param task sem předám úlohu
     * @return vrátím objekt Future pro sledování dokončení
     */
    public Future<?> submit(Runnable task) {
        if (task == null) {
            return null;
        }
        return executor.submit(task);
    }

    /** @return vrátím počet pracovních vláken */
    public int getThreadCount() {
        return threadCount;
    }

    /** Tady pool ukončím při vypnutí enginu. */
    public void shutdown() {
        executor.shutdownNow();
    }
}
