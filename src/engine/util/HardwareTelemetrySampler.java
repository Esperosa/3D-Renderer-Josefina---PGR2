package engine.util;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

import com.sun.management.OperatingSystemMXBean;

public final class HardwareTelemetrySampler {

    private static final OperatingSystemMXBean OPERATING_SYSTEM_BEAN = resolveOperatingSystemBean();
    private static final java.lang.management.ThreadMXBean THREAD_BEAN = resolveThreadBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS = resolveGarbageCollectorBeans();
    private static final Runtime RUNTIME = Runtime.getRuntime();

    private HardwareTelemetrySampler() {
    }

    public static Sample sample() {
        CpuSample cpuSample = sampleCpuOnly();
        long usedHeapBytes = Math.max(0L, RUNTIME.totalMemory() - RUNTIME.freeMemory());
        long maxHeapBytes = Math.max(0L, RUNTIME.maxMemory());
        int liveThreadCount = resolveLiveThreadCount();
        long gcTimeMillis = resolveGcTimeMillis();

        return new Sample(
                cpuSample.processCpuLoad(),
                cpuSample.systemCpuLoad(),
                cpuSample.processCpuTimeNanos(),
                usedHeapBytes,
                maxHeapBytes,
                liveThreadCount,
                gcTimeMillis);
    }

    public static CpuSample sampleCpuOnly() {
        double processCpuLoad = Double.NaN;
        double systemCpuLoad = Double.NaN;
        long processCpuTimeNanos = -1L;

        try {
            OperatingSystemMXBean osBean = OPERATING_SYSTEM_BEAN;
            if (osBean != null) {
                processCpuLoad = normalizeCpuLoad(osBean.getProcessCpuLoad());
                systemCpuLoad = normalizeCpuLoad(resolveSystemCpuLoad(osBean));
                processCpuTimeNanos = osBean.getProcessCpuTime();
            }
        } catch (RuntimeException ignored) {
 // Best-effort telemetry only.
        }

        return new CpuSample(
                processCpuLoad,
                systemCpuLoad,
                processCpuTimeNanos);
    }

    private static double normalizeCpuLoad(double load) {
        if (!Double.isFinite(load) || load < 0.0) {
            return Double.NaN;
        }
        return Math.max(0.0, Math.min(1.0, load));
    }

    @SuppressWarnings("deprecation")
    private static double resolveSystemCpuLoad(OperatingSystemMXBean osBean) {
        double cpuLoad = osBean.getCpuLoad();
        if (Double.isFinite(cpuLoad) && cpuLoad >= 0.0) {
            return cpuLoad;
        }
        return osBean.getSystemCpuLoad();
    }

    private static int resolveLiveThreadCount() {
        try {
            java.lang.management.ThreadMXBean threadBean = THREAD_BEAN;
            return threadBean == null
                    ? fallbackLiveThreadCount()
                    : Math.max(1, threadBean.getThreadCount());
        } catch (RuntimeException ignored) {
            return fallbackLiveThreadCount();
        }
    }

    private static long resolveGcTimeMillis() {
        long total = 0L;
        try {
            for (GarbageCollectorMXBean gcBean : GC_BEANS) {
                if (gcBean == null) {
                    continue;
                }
                long value = gcBean.getCollectionTime();
                if (value > 0L) {
                    total += value;
                }
            }
        } catch (RuntimeException ignored) {
            return 0L;
        }
        return Math.max(0L, total);
    }

    private static OperatingSystemMXBean resolveOperatingSystemBean() {
        try {
            java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            return bean instanceof OperatingSystemMXBean osBean ? osBean : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static int fallbackLiveThreadCount() {
        return Math.max(1, Thread.activeCount());
    }

    private static java.lang.management.ThreadMXBean resolveThreadBean() {
        try {
            return ManagementFactory.getThreadMXBean();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static List<GarbageCollectorMXBean> resolveGarbageCollectorBeans() {
        try {
            return ManagementFactory.getGarbageCollectorMXBeans();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    public record CpuSample(double processCpuLoad,
                            double systemCpuLoad,
                            long processCpuTimeNanos) {
    }

    public record Sample(double processCpuLoad,
                         double systemCpuLoad,
                         long processCpuTimeNanos,
                         long usedHeapBytes,
                         long maxHeapBytes,
                         int liveThreadCount,
                         long gcTimeMillis) {
    }
}