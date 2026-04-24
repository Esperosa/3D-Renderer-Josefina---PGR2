package engine.util;

import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLongArray;

import com.sun.management.ThreadMXBean;

/**
 * Lightweight, opt-in runtime instrumentation for preview/output frame telemetry.
 */
public final class RuntimeInstrumentation {

    public enum FrameKind {
        PREVIEW,
        OUTPUT
    }

    public enum Stage {
        INPUT,
        CAMERA_UPDATE,
        SCENE_UPDATE,
        VISIBILITY,
        WATER_UPDATE,
        SELECTION_PICK,
        SELECTION_PREPASS,
        PREVIEW_MODE_RESOLVE,
        PREVIEW_RENDER_TOTAL,
        RT_OR_PT_RENDER,
        CARRIER_TRACE,
        POLISH_TRACE,
        CARRIER_RESOLVE,
        POLISH_RESOLVE,
        DENOISE,
        CARRIER_DENOISE,
        POLISH_DENOISE,
        TEMPORAL,
        OVERLAYS,
        HUD_UI,
        BLIT_PRESENT,
        WINDOW_PRESENT,
        OUTPUT_RENDER_TOTAL,
        OUTPUT_COPY_ENCODE
    }

    public enum Counter {
        GET_ALL_MESH_ENTITIES_CALLS,
        ENTITIES_VISITED,
        WORLD_MATRIX_CALLS,
        BOUNDS_RECOMPUTES,
        VISIBILITY_CULL_TESTS,
        WATER_PROXY_REBUILDS,
        PICK_CANDIDATES,
        PICK_TRIANGLE_TESTS,
        SELECTION_OVERLAY_TRIANGLES,
        PREVIEW_MOTION_FRAMES,
        CAMERA_SIGNATURE_CHANGES,
        CAMERA_RESETS_HARD,
        CAMERA_RESETS_SOFT,
        PREVIEW_SECONDARY_REDUCED_FRAMES,
        PREVIEW_DENOISE_SKIPPED_FRAMES,
        PREVIEW_POLISH_CADENCE_HITS,
        PREVIEW_CONFIGURED_POLISH_SCALE_X1000,
        PREVIEW_EXECUTED_POLISH_SCALE_X1000,
        PREVIEW_EXECUTED_POLISH_BUFFER_WIDTH,
        PREVIEW_EXECUTED_POLISH_BUFFER_HEIGHT,
        PREVIEW_POLISH_EXECUTED_FRAMES,
        PREVIEW_POLISH_EXECUTED_MOVING_FRAMES,
        PREVIEW_POLISH_EXECUTED_STILL_FRAMES,
        PREVIEW_POLISH_SKIPPED_CADENCE_FRAMES,
        PREVIEW_POLISH_SKIPPED_DISABLED_FRAMES,
        PREVIEW_POLISH_EXECUTED_HALF_RES_FRAMES,
        PREVIEW_POLISH_EXECUTED_QUARTER_RES_FRAMES,
        PREVIEW_POLISH_EXECUTED_FULL_RES_FRAMES,
        PREVIEW_POLISH_RESOLVE_EXECUTED_FRAMES,
        PREVIEW_POLISH_RESOLVE_REUSED_FRAMES,
        PREVIEW_POLISH_CACHE_INVALIDATIONS,
        PREVIEW_POLISH_RESOLVE_CADENCE_HITS,
        PREVIEW_POLISH_PACKED_CACHE_REBUILD_NS,
        PREVIEW_POLISH_PACKED_CACHE_REUSE_NS,
        PREVIEW_POLISH_STILL_CACHE_WORK_NS,
        PREVIEW_POLISH_RESOLVE_REBUILD_DIRECT_PACK_NS,
        PREVIEW_POLISH_RESOLVE_REBUILD_RESOLVE_LOWRES_NS,
        PREVIEW_POLISH_RESOLVE_REBUILD_UPSCALE_PACK_NS,
        PREVIEW_POLISH_UPSCALE_MAP_BUILD_NS,
        PREVIEW_POLISH_UPSCALE_MAP_INVALIDATIONS,
        PREVIEW_POLISH_REUSE_ALLOWED_FRAMES,
        PREVIEW_POLISH_REUSE_BLOCKED_FRAMES,
        PREVIEW_POLISH_REUSE_BLOCK_REASON_SOFT_MOTION,
        PREVIEW_POLISH_REUSE_BLOCK_REASON_INTEGRAND,
        PREVIEW_POLISH_REUSE_BLOCK_REASON_REBUILD,
        PREVIEW_POLISH_REUSE_BLOCK_REASON_SCALE_CHANGE,
        PREVIEW_POLISH_REUSE_BLOCK_REASON_TIER_CHANGE,
        PREVIEW_AUTOSCHED_HW_SAMPLE_HITS,
        PREVIEW_GEOMETRY_SIGNATURE_NS,
        PREVIEW_LIGHTING_SIGNATURE_NS,
        PREVIEW_CAMERA_SIGNATURE_NS,
        PREVIEW_CAMERA_SIG_EXTRACT_NS,
        PREVIEW_CAMERA_SIG_PROJECTION_NS,
        PREVIEW_CAMERA_SIG_HASH_NS,
        PREVIEW_CAMERA_SIG_DELTA_NS,
        PREVIEW_CAMERA_SIG_COMPARE_NS,
        PREVIEW_CAMERA_SIG_RESET_APPLY_NS,
        PREVIEW_DEPTH_CLEAR_NS,
        PREVIEW_RUNTIME_BUFFER_ENSURE_NS,
        PREVIEW_POLISH_BUFFER_ENSURE_NS,
        PREVIEW_CAMERA_STATE_BUILD_NS,
        PREVIEW_AUTOSCHED_NS,
        PREVIEW_AUTOSCHED_TILE_COST_NS,
        PREVIEW_AUTOSCHED_HW_SAMPLE_NS,
        PREVIEW_AUTOSCHED_SMOOTHING_NS,
        PREVIEW_AUTOSCHED_THRESHOLD_NS,
        PREVIEW_AUTOSCHED_DECISION_NS,
        PREVIEW_RENDER_SETUP_NS,
        PREVIEW_HYBRID_BASE_RESOURCE_SYNC_NS,
        PREVIEW_HYBRID_BASE_SETUP_NS,
        PREVIEW_HYBRID_BASE_PREPARE_NS,
        PREVIEW_HYBRID_BASE_BINNING_NS,
        PREVIEW_HYBRID_BASE_RASTER_NS,
        PREVIEW_HYBRID_BASE_OUTPUT_NS,
        PREVIEW_HYBRID_BASE_REDUCED_SHADE_NS,
        PREVIEW_HYBRID_BASE_GUIDED_UPSCALE_NS,
        PREVIEW_HYBRID_BASE_REDUCED_SHADE_STORE_NS,
        PREVIEW_HYBRID_BASE_INACTIVE_SCAN_NS,
        PREVIEW_HYBRID_BASE_GUIDE_PRECHECK_NS,
        PREVIEW_HYBRID_BASE_UPSCALE_FAST_PATH_NS,
        PREVIEW_HYBRID_BASE_EDGE_WEIGHT_NS,
        PREVIEW_HYBRID_BASE_FINAL_COMPOSITE_WRITE_NS,
        PREVIEW_HYBRID_BASE_UPSCALE_MAP_BUILD_NS,
        PREVIEW_HYBRID_BASE_FAST_PATH_PIXELS,
        PREVIEW_HYBRID_BASE_EDGE_PATH_PIXELS,
        PREVIEW_BASE_PREPARE_TRANSFORM_NS,
        PREVIEW_BASE_TILE_BINNING_NS,
        PREVIEW_BASE_RASTER_FILL_NS,
        PREVIEW_BASE_DEPTH_TEST_NS,
        PREVIEW_BASE_GUIDES_NS,
        PREVIEW_BASE_SHADING_NS,
        PREVIEW_BASE_DIRECT_LIGHT_NS,
        PREVIEW_BASE_MATERIAL_NS,
        PREVIEW_BASE_FRAMEBUFFER_WRITE_NS,
        PREVIEW_CARRIER_SIMPLIFIED_FRAMES,
        PREVIEW_CARRIER_REDUCED_SHADOW_FRAMES,
        PREVIEW_CARRIER_PRIMARY_INTERSECTION_NS,
        PREVIEW_CARRIER_SURFACE_SAMPLE_NS,
        PREVIEW_CARRIER_DIRECT_LIGHT_NS,
        PREVIEW_CARRIER_SHADOW_QUERY_NS,
        PREVIEW_CARRIER_ENVIRONMENT_NS,
        PREVIEW_CARRIER_EXTRA_MATERIAL_LOBES_NS,
        PREVIEW_CARRIER_DIRECTIONAL_LIGHT_NS,
        PREVIEW_CARRIER_POINT_LIGHT_NS,
        PREVIEW_CARRIER_SPOT_LIGHT_NS,
        PREVIEW_CARRIER_AREA_LIGHT_NS,
        PREVIEW_CARRIER_LOCAL_LIGHT_CANDIDATES,
        PREVIEW_CARRIER_LOCAL_LIGHT_SHADED,
        PREVIEW_CARRIER_LOCAL_LIGHT_SHADOWED,
        PREVIEW_PT_PATH_BOUNCE_NS,
        PREVIEW_PT_DIRECT_LIGHT_NS,
        PREVIEW_PT_SHADOW_QUERY_NS,
        PREVIEW_PT_DIRECTIONAL_LIGHT_NS,
        PREVIEW_PT_POINT_LIGHT_NS,
        PREVIEW_PT_ENVIRONMENT_LIGHTING_NS,
        PREVIEW_PT_EMISSIVE_LIGHTING_NS,
        PREVIEW_PT_LOCAL_LIGHT_CANDIDATES,
        PREVIEW_PT_LOCAL_LIGHT_SHADED,
        PREVIEW_PT_LOCAL_LIGHT_SHADOWED,
        PREVIEW_PT_TILE_TIMING_MIN_NS,
        PREVIEW_PT_TILE_TIMING_MAX_NS,
        PREVIEW_PT_TILE_TIMING_SPREAD_NS,
        PREVIEW_PT_TILE_TIMING_SAMPLES,
        PREVIEW_PT_TILE_TIMING_MAX_OVER_MEAN_X1000,
        PREVIEW_PT_TILE_TIMING_DESYNC_FRAMES,
        PREVIEW_CARRIER_DENOISE_SEED_NS,
        PREVIEW_CARRIER_DENOISE_NOISE_PROFILE_NS,
        PREVIEW_CARRIER_DENOISE_FILTER_NS,
        PREVIEW_CARRIER_DENOISE_COMMIT_NS,
        PREVIEW_CARRIER_DENOISE_PASS_COUNT,
        PREVIEW_CARRIER_DENOISE_HOT_PIXEL_PIXELS,
        PREVIEW_CARRIER_DENOISE_EDGE_TAPS,
        PREVIEW_CARRIER_RESOLVE_METRICS_NS,
        PREVIEW_CARRIER_RESOLVE_FROM_ACCUM_NS,
        PREVIEW_CARRIER_RESOLVE_TEMPORAL_NS,
        PREVIEW_CARRIER_RESOLVE_BASE_COPY_NS,
        PREVIEW_CARRIER_RESOLVE_DEPTH_COPY_NS,
        PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_FETCH_NS,
        PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_BLEND_NS,
        PREVIEW_CARRIER_RESOLVE_CACHED_POLISH_COMPOSE_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_FULL_FRAME_SCAN_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_REGION_MASK_BUILD_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_IDENTITY_CHECK_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_FETCH_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_BLEND_WRITE_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_NS,
        PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_PIXELS,
        PREVIEW_MOVING_POLISH_COMPOSE_PASSTHROUGH_PIXELS,
        PREVIEW_MOVING_POLISH_COMPOSE_ACTIVE_TILE_PIXELS,
        PREVIEW_MOVING_POLISH_COMPOSE_FRAME_GENERATION_MISMATCHES,
        PREVIEW_MOVING_POLISH_COMPOSE_CACHE_REUSE_GENERATION_MISMATCHES,
        PREVIEW_MOVING_POLISH_COMPOSE_STALE_ACTIVE_PIXELS,
        PREVIEW_MOVING_POLISH_COMPOSE_SKIPPED_REUSE_FRAMES,
        PREVIEW_MOVING_POLISH_COMPOSE_PARTIAL_ACTIVE_REGION_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_100_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_90_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_80_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_70_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_60_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_50_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_40_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_33_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_25_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_20_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_16_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_12_FRAMES,
        PREVIEW_DYNAMIC_RES_TIER_SWITCHES,
        PREVIEW_DYNAMIC_RES_TIER_DOWNSHIFTS,
        PREVIEW_DYNAMIC_RES_TIER_UPSHIFTS,
        PREVIEW_CARRIER_RESOLVE_OUTPUT_NS,
        BYTES_COPIED,
        WORKER_BUSY_NS,
        WORKER_IDLE_NS,
        WORKER_WAIT_NS
    }

    public static final class FrameToken {
        private static final FrameToken NOOP = new FrameToken();

        private final boolean noop;
        private final FrameKind kind;
        @SuppressWarnings("unused")
        private final String label;
        private final long startNanos;
        private final AtomicLongArray stageInclusiveNanos;
        private final AtomicLongArray stageExclusiveNanos;
        private final AtomicLongArray counters;
        private final long allocatedBytesStart;

        private volatile long endNanos;
        private volatile long allocatedBytesEnd;
        private volatile String activeMode;
        private volatile String displayedMode;
        private volatile String fallbackReason;

        private FrameToken() {
            this.noop = true;
            this.kind = FrameKind.PREVIEW;
            this.label = "";
            this.startNanos = 0L;
            this.stageInclusiveNanos = null;
            this.stageExclusiveNanos = null;
            this.counters = null;
            this.allocatedBytesStart = -1L;
            this.endNanos = 0L;
            this.allocatedBytesEnd = -1L;
        }

        private FrameToken(FrameKind kind, String label, long allocatedBytesStart) {
            this.noop = false;
            this.kind = kind == null ? FrameKind.PREVIEW : kind;
            this.label = label == null ? "" : label;
            this.startNanos = System.nanoTime();
            this.stageInclusiveNanos = new AtomicLongArray(Stage.values().length);
            this.stageExclusiveNanos = new AtomicLongArray(Stage.values().length);
            this.counters = new AtomicLongArray(Counter.values().length);
            this.allocatedBytesStart = allocatedBytesStart;
            this.endNanos = 0L;
            this.allocatedBytesEnd = -1L;
        }
    }

    public static final class Snapshot {
        private final SummarySnapshot preview;
        private final SummarySnapshot output;

        private Snapshot(SummarySnapshot preview, SummarySnapshot output) {
            this.preview = preview;
            this.output = output;
        }

        public long frameCount(FrameKind kind) {
            return summary(kind).frameCount;
        }

        public double averageFrameMs(FrameKind kind) {
            SummarySnapshot summary = summary(kind);
            if (summary.frameCount <= 0L) {
                return 0.0;
            }
            return summary.totalFrameNanos / 1_000_000.0 / summary.frameCount;
        }

        public double averageStageMs(FrameKind kind, Stage stage) {
            SummarySnapshot summary = summary(kind);
            if (summary.frameCount <= 0L || stage == null) {
                return 0.0;
            }
            return summary.stageTotals[stage.ordinal()] / 1_000_000.0 / summary.frameCount;
        }

        public double averageExclusiveStageMs(FrameKind kind, Stage stage) {
            SummarySnapshot summary = summary(kind);
            if (summary.frameCount <= 0L || stage == null) {
                return 0.0;
            }
            return summary.stageExclusiveTotals[stage.ordinal()] / 1_000_000.0 / summary.frameCount;
        }

        public long totalCounter(FrameKind kind, Counter counter) {
            SummarySnapshot summary = summary(kind);
            if (counter == null) {
                return 0L;
            }
            return summary.counterTotals[counter.ordinal()];
        }

        public double averageAllocatedBytes(FrameKind kind) {
            SummarySnapshot summary = summary(kind);
            if (summary.frameCount <= 0L || summary.allocatedBytesTotal < 0L) {
                return -1.0;
            }
            return summary.allocatedBytesTotal / (double) summary.frameCount;
        }

        public Map<String, Long> modeCounts(FrameKind kind) {
            return summary(kind).modeCounts;
        }

        public Map<String, Long> fallbackCounts(FrameKind kind) {
            return summary(kind).fallbackCounts;
        }

        public List<String> recentModeTimeline(FrameKind kind) {
            return summary(kind).recentModes;
        }

        public List<String> recentFallbackTimeline(FrameKind kind) {
            return summary(kind).recentFallbacks;
        }

        private SummarySnapshot summary(FrameKind kind) {
            return kind == FrameKind.OUTPUT ? output : preview;
        }
    }

    private static final class SummarySnapshot {
        final long frameCount;
        final long totalFrameNanos;
        final long[] stageTotals;
        final long[] stageExclusiveTotals;
        final long[] counterTotals;
        final long allocatedBytesTotal;
        final Map<String, Long> modeCounts;
        final Map<String, Long> fallbackCounts;
        final List<String> recentModes;
        final List<String> recentFallbacks;

        SummarySnapshot(long frameCount,
                        long totalFrameNanos,
                        long[] stageTotals,
                        long[] stageExclusiveTotals,
                        long[] counterTotals,
                        long allocatedBytesTotal,
                        Map<String, Long> modeCounts,
                        Map<String, Long> fallbackCounts,
                        List<String> recentModes,
                        List<String> recentFallbacks) {
            this.frameCount = frameCount;
            this.totalFrameNanos = totalFrameNanos;
            this.stageTotals = stageTotals;
            this.stageExclusiveTotals = stageExclusiveTotals;
            this.counterTotals = counterTotals;
            this.allocatedBytesTotal = allocatedBytesTotal;
            this.modeCounts = Collections.unmodifiableMap(modeCounts);
            this.fallbackCounts = Collections.unmodifiableMap(fallbackCounts);
            this.recentModes = Collections.unmodifiableList(recentModes);
            this.recentFallbacks = Collections.unmodifiableList(recentFallbacks);
        }
    }

    private static final class SummaryAccumulator {
        private static final int TIMELINE_LIMIT = 256;

        long frameCount;
        long totalFrameNanos;
        long allocatedBytesTotal;
        final long[] stageTotals = new long[Stage.values().length];
        final long[] stageExclusiveTotals = new long[Stage.values().length];
        final long[] counterTotals = new long[Counter.values().length];
        final LinkedHashMap<String, Long> modeCounts = new LinkedHashMap<>();
        final LinkedHashMap<String, Long> fallbackCounts = new LinkedHashMap<>();
        final ArrayDeque<String> recentModes = new ArrayDeque<>();
        final ArrayDeque<String> recentFallbacks = new ArrayDeque<>();

        void record(FrameToken token) {
            frameCount++;
            long frameNanos = Math.max(0L, token.endNanos - token.startNanos);
            totalFrameNanos += frameNanos;
            if (token.allocatedBytesStart >= 0L && token.allocatedBytesEnd >= token.allocatedBytesStart) {
                allocatedBytesTotal += (token.allocatedBytesEnd - token.allocatedBytesStart);
            } else if (allocatedBytesTotal < 0L) {
                allocatedBytesTotal = -1L;
            }
            for (Stage stage : Stage.values()) {
                stageTotals[stage.ordinal()] += token.stageInclusiveNanos.get(stage.ordinal());
                stageExclusiveTotals[stage.ordinal()] += token.stageExclusiveNanos.get(stage.ordinal());
            }
            for (Counter counter : Counter.values()) {
                counterTotals[counter.ordinal()] += token.counters.get(counter.ordinal());
            }
            if (token.activeMode != null || token.displayedMode != null) {
                String entry = safe(token.activeMode) + "->" + safe(token.displayedMode);
                modeCounts.merge(entry, 1L, Long::sum);
                pushTimeline(recentModes, entry);
            }
            if (token.fallbackReason != null && !token.fallbackReason.isBlank()) {
                fallbackCounts.merge(token.fallbackReason, 1L, Long::sum);
                pushTimeline(recentFallbacks, token.fallbackReason);
            }
        }

        SummarySnapshot snapshot() {
            return new SummarySnapshot(
                    frameCount,
                    totalFrameNanos,
                    Arrays.copyOf(stageTotals, stageTotals.length),
                    Arrays.copyOf(stageExclusiveTotals, stageExclusiveTotals.length),
                    Arrays.copyOf(counterTotals, counterTotals.length),
                    allocatedBytesTotal,
                    new LinkedHashMap<>(modeCounts),
                    new LinkedHashMap<>(fallbackCounts),
                    new ArrayList<>(recentModes),
                    new ArrayList<>(recentFallbacks));
        }

        void reset() {
            frameCount = 0L;
            totalFrameNanos = 0L;
            allocatedBytesTotal = 0L;
            Arrays.fill(stageTotals, 0L);
            Arrays.fill(stageExclusiveTotals, 0L);
            Arrays.fill(counterTotals, 0L);
            modeCounts.clear();
            fallbackCounts.clear();
            recentModes.clear();
            recentFallbacks.clear();
        }

        private void pushTimeline(ArrayDeque<String> timeline, String entry) {
            if (timeline.size() >= TIMELINE_LIMIT) {
                timeline.removeFirst();
            }
            timeline.addLast(entry);
        }
    }

    private static final Object LOCK = new Object();
    private static final ThreadLocal<FrameToken> CURRENT_FRAME = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<StageEntry>> CURRENT_STAGE_STACK = new ThreadLocal<>();
    private static final SummaryAccumulator PREVIEW_SUMMARY = new SummaryAccumulator();
    private static final SummaryAccumulator OUTPUT_SUMMARY = new SummaryAccumulator();
    private static final ThreadMXBean THREAD_MX_BEAN = resolveThreadMxBean();

    private static volatile boolean enabled = Boolean.getBoolean("engine.runtimeInstrumentation");

    private RuntimeInstrumentation() {
    }

    private static final class StageEntry {
        final FrameToken token;
        final Stage stage;
        final long startNanos;
        long childInclusiveNanos;

        private StageEntry(FrameToken token, Stage stage, long startNanos) {
            this.token = token;
            this.stage = stage;
            this.startNanos = startNanos;
            this.childInclusiveNanos = 0L;
        }
    }

    public static void setEnabled(boolean nextEnabled) {
        enabled = nextEnabled;
        if (!nextEnabled) {
            reset();
        }
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static FrameToken beginFrame(FrameKind kind, String label) {
        if (!enabled) {
            return FrameToken.NOOP;
        }
        CURRENT_STAGE_STACK.remove();
        FrameToken token = new FrameToken(kind, label, readAllocatedBytes());
        CURRENT_FRAME.set(token);
        return token;
    }

    public static void endFrame(FrameToken token) {
        if (token == null) {
            CURRENT_FRAME.remove();
            return;
        }
        if (CURRENT_FRAME.get() == token) {
            CURRENT_FRAME.remove();
        }
        CURRENT_STAGE_STACK.remove();
        if (!enabled || token.noop) {
            return;
        }
        token.endNanos = System.nanoTime();
        token.allocatedBytesEnd = readAllocatedBytes();
        synchronized (LOCK) {
            summaryFor(token.kind).record(token);
        }
    }

    public static FrameToken captureCurrentToken() {
        if (!enabled) {
            return FrameToken.NOOP;
        }
        FrameToken token = CURRENT_FRAME.get();
        return token == null ? FrameToken.NOOP : token;
    }

    public static void attachFrame(FrameToken token) {
        if (!enabled || token == null || token.noop) {
            return;
        }
        CURRENT_FRAME.set(token);
    }

    public static void clearAttachedFrame(FrameToken token) {
        if (!enabled || token == null || token.noop) {
            return;
        }
        if (CURRENT_FRAME.get() == token) {
            CURRENT_FRAME.remove();
        }
        CURRENT_STAGE_STACK.remove();
    }

    public static long startStage(Stage stage) {
        if (!enabled || stage == null) {
            return 0L;
        }
        FrameToken token = CURRENT_FRAME.get();
        if (token == null || token.noop) {
            return 0L;
        }
        long startNanos = System.nanoTime();
        ArrayDeque<StageEntry> stack = CURRENT_STAGE_STACK.get();
        if (stack == null) {
            stack = new ArrayDeque<>();
            CURRENT_STAGE_STACK.set(stack);
        }
        stack.addLast(new StageEntry(token, stage, startNanos));
        return startNanos;
    }

    public static void endStage(Stage stage, long startNanos) {
        if (!enabled || stage == null || startNanos <= 0L) {
            return;
        }
        FrameToken token = CURRENT_FRAME.get();
        if (token == null || token.noop) {
            return;
        }
        long endNanos = System.nanoTime();
        long inclusiveNanos = Math.max(0L, endNanos - startNanos);
        long exclusiveNanos = inclusiveNanos;

        ArrayDeque<StageEntry> stack = CURRENT_STAGE_STACK.get();
        if (stack != null && !stack.isEmpty()) {
            StageEntry entry = stack.peekLast();
            if (entry != null
                    && entry.token == token
                    && entry.stage == stage
                    && entry.startNanos == startNanos) {
                stack.removeLast();
                inclusiveNanos = Math.max(0L, endNanos - entry.startNanos);
                exclusiveNanos = Math.max(0L, inclusiveNanos - entry.childInclusiveNanos);
                StageEntry parent = stack.peekLast();
                if (parent != null && parent.token == token) {
                    parent.childInclusiveNanos += inclusiveNanos;
                }
                if (stack.isEmpty()) {
                    CURRENT_STAGE_STACK.remove();
                }
            }
        }

        token.stageInclusiveNanos.addAndGet(stage.ordinal(), inclusiveNanos);
        token.stageExclusiveNanos.addAndGet(stage.ordinal(), exclusiveNanos);
    }

    public static void addCounter(Counter counter, long delta) {
        if (!enabled || counter == null || delta == 0L) {
            return;
        }
        FrameToken token = CURRENT_FRAME.get();
        if (token == null || token.noop) {
            return;
        }
        token.counters.addAndGet(counter.ordinal(), delta);
    }

    public static void recordMode(Object activeMode, Object displayedMode) {
        if (!enabled) {
            return;
        }
        FrameToken token = CURRENT_FRAME.get();
        if (token == null || token.noop) {
            return;
        }
        token.activeMode = activeMode == null ? "null" : String.valueOf(activeMode);
        token.displayedMode = displayedMode == null ? "null" : String.valueOf(displayedMode);
    }

    public static void recordFallbackReason(String reason) {
        if (!enabled || reason == null || reason.isBlank()) {
            return;
        }
        FrameToken token = CURRENT_FRAME.get();
        if (token == null || token.noop) {
            return;
        }
        token.fallbackReason = reason;
    }

    public static Snapshot snapshot() {
        synchronized (LOCK) {
            return new Snapshot(PREVIEW_SUMMARY.snapshot(), OUTPUT_SUMMARY.snapshot());
        }
    }

    public static Snapshot snapshotAndReset() {
        synchronized (LOCK) {
            Snapshot snapshot = new Snapshot(PREVIEW_SUMMARY.snapshot(), OUTPUT_SUMMARY.snapshot());
            PREVIEW_SUMMARY.reset();
            OUTPUT_SUMMARY.reset();
            return snapshot;
        }
    }

    public static void reset() {
        CURRENT_FRAME.remove();
        synchronized (LOCK) {
            PREVIEW_SUMMARY.reset();
            OUTPUT_SUMMARY.reset();
        }
    }

    private static SummaryAccumulator summaryFor(FrameKind kind) {
        return kind == FrameKind.OUTPUT ? OUTPUT_SUMMARY : PREVIEW_SUMMARY;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value;
    }

    private static ThreadMXBean resolveThreadMxBean() {
        try {
            java.lang.management.ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean threadMxBean)) {
                return null;
            }
            if (!threadMxBean.isThreadAllocatedMemorySupported()) {
                return null;
            }
            if (!threadMxBean.isThreadAllocatedMemoryEnabled()) {
                threadMxBean.setThreadAllocatedMemoryEnabled(true);
            }
            return threadMxBean;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static long readAllocatedBytes() {
        if (THREAD_MX_BEAN == null) {
            return -1L;
        }
        try {
            long total = 0L;
            long[] threadIds = THREAD_MX_BEAN.getAllThreadIds();
            for (long threadId : threadIds) {
                long allocated = THREAD_MX_BEAN.getThreadAllocatedBytes(threadId);
                if (allocated > 0L) {
                    total += allocated;
                }
            }
            return total;
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }
}
