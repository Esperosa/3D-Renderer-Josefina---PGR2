package engine.core;

/**
 * Tady držím lehký watchdog nad viewport loopem, aby mi editor při přetížení radši krátce zvolnil,
 * než aby dlouho dusil CPU a přestal reagovat.
 */
final class EngineSafetyController {
    private static final double SEVERE_FRAME_MS = 650.0;
    private static final double HARD_FRAME_MS = 1500.0;
    private static final int SEVERE_STREAK_LIMIT = 2;
    private static final double HEAVY_MODE_SEVERE_FRAME_MS = 1800.0;
    private static final double HEAVY_MODE_HARD_FRAME_MS = 3200.0;
    private static final int HEAVY_MODE_SEVERE_STREAK_LIMIT = 4;
    private static final long RECOVERY_DURATION_NS = 1_250_000_000L;
    private static final long RECOVERY_REARM_GAP_NS = 900_000_000L;
    private static final int RECOVERY_SLEEP_MS = 28;
    private static final double RECOVERY_SCALE_CLAMP = 0.58;
    private static final long MEMORY_MIN_HEADROOM_BYTES = 256L * 1024L * 1024L;
    private static final double MEMORY_USED_RATIO_LIMIT = 0.92;

    private EngineSafetyController() {
    }

    static boolean shouldHoldFrame(Engine engine, long now) {
        if (engine == null || !engine.safetyMonitorEnabled) {
            return false;
        }
        if (engine.activeMode == RenderMode.PATH_TRACING
                && engine.pathAccumulationLock
                && !engine.timelineEnabled
                && !engine.safetyRecoveryActive) {
            return false;
        }
        if (engine.safetyRecoveryActive && now >= engine.safetyRecoveryUntilNanos) {
            finishRecovery(engine);
        }
        if (!engine.safetyRecoveryActive && isMemoryPressureCritical()) {
            armRecovery(engine, now, "Paměťový tlak");
        }
        // Safety uz nesmi stopnout viewport loop; fallback render mode resi EngineRenderRuntime.
        return false;
    }

    static void recordFrame(Engine engine, double frameTimeMs, long now, boolean viewportInteractionActive) {
        if (engine == null || !engine.safetyMonitorEnabled || !Double.isFinite(frameTimeMs) || frameTimeMs <= 0.0) {
            return;
        }
        boolean heavyViewportMode = engine.activeMode == RenderMode.RAY_TRACING
                || engine.activeMode == RenderMode.PATH_TRACING;
        if (engine.activeMode == RenderMode.PATH_TRACING
                && engine.pathAccumulationLock
                && !EngineRenderRuntime.shouldAdvanceDynamicScene(engine, viewportInteractionActive)) {
            return;
        }
        if (engine.safetyRecoveryActive) {
            engine.safetySevereFrameStreak = 0;
            return;
        }

        double hardFrameMs = heavyViewportMode ? HEAVY_MODE_HARD_FRAME_MS : HARD_FRAME_MS;
        double severeFrameMs = heavyViewportMode ? HEAVY_MODE_SEVERE_FRAME_MS : SEVERE_FRAME_MS;
        int severeStreakLimit = heavyViewportMode ? HEAVY_MODE_SEVERE_STREAK_LIMIT : SEVERE_STREAK_LIMIT;

        if (frameTimeMs >= hardFrameMs) {
            armRecovery(engine, now, "Extrémně dlouhý frame");
            return;
        }
        if (frameTimeMs >= severeFrameMs) {
            engine.safetySevereFrameStreak++;
            if (engine.safetySevereFrameStreak >= severeStreakLimit) {
                armRecovery(engine, now, "Přetížený viewport");
            }
            return;
        }
        engine.safetySevereFrameStreak = Math.max(0, engine.safetySevereFrameStreak - 1);
    }

    static void recordRenderFailure(Engine engine, long now) {
        if (engine == null || !engine.safetyMonitorEnabled) {
            return;
        }
        armRecovery(engine, now, "Chyba renderu");
    }

    static String[] augmentOverlay(Engine engine, String[] baseLines) {
        if (engine == null || !engine.safetyRecoveryActive) {
            return baseLines;
        }
        String[] safetyLines = new String[]{
                "SAFE RECOVERY: viewport je krátce ztlumený",
                "DŮVOD: " + safeReason(engine.safetyRecoveryReason)
                        + "  SCALE " + String.format("%.2f", engine.safetyViewportScaleClamp)
                        + "  COUNT " + engine.safetyRecoveryCount
        };
        if (baseLines == null || baseLines.length == 0) {
            return safetyLines;
        }
        String[] merged = new String[safetyLines.length + baseLines.length];
        System.arraycopy(safetyLines, 0, merged, 0, safetyLines.length);
        System.arraycopy(baseLines, 0, merged, safetyLines.length, baseLines.length);
        return merged;
    }

    static int recoverySleepMillis() {
        return RECOVERY_SLEEP_MS;
    }

    private static void armRecovery(Engine engine, long now, String reason) {
        if (engine.safetyRecoveryActive) {
            engine.safetyRecoveryUntilNanos = Math.max(engine.safetyRecoveryUntilNanos, now + RECOVERY_DURATION_NS);
            engine.safetyRecoveryReason = safeReason(reason);
            return;
        }
        if (now - engine.safetyLastRecoveryNanos < RECOVERY_REARM_GAP_NS) {
            return;
        }

        engine.safetyRecoveryActive = true;
        engine.safetyRecoveryReason = safeReason(reason);
        engine.safetyRecoveryUntilNanos = now + RECOVERY_DURATION_NS;
        engine.safetyLastRecoveryNanos = now;
        engine.safetyRecoveryCount++;
        engine.safetySevereFrameStreak = 0;
        engine.safetyViewportScaleClamp = Math.min(engine.safetyViewportScaleClamp, RECOVERY_SCALE_CLAMP);

        // Safety recovery má jen tlumit viewport scale; progresivní akumulaci nerestartujeme,
        // protože to ve špičkách vede k viditelnému cyklickému resetování sample countu.
        if (engine.frameBuffer != null
                && !(engine.pathAccumulationLock && engine.activeMode == RenderMode.PATH_TRACING)) {
            EngineRenderRuntime.applyRenderScale(engine, false);
        }
        if ("Paměťový tlak".equals(engine.safetyRecoveryReason)) {
            engine.postAATemp = new int[0];
            System.gc();
        }
        System.out.println("Safety recovery: " + engine.safetyRecoveryReason);
    }

    private static void finishRecovery(Engine engine) {
        engine.safetyRecoveryActive = false;
        engine.safetyRecoveryReason = "";
        engine.safetyRecoveryUntilNanos = 0L;
        engine.safetyViewportScaleClamp = 1.0;
        engine.safetySevereFrameStreak = 0;
        if (engine.frameBuffer != null
                && !(engine.pathAccumulationLock && engine.activeMode == RenderMode.PATH_TRACING)) {
            EngineRenderRuntime.applyRenderScale(engine, false);
        }
    }

    private static boolean isMemoryPressureCritical() {
        Runtime runtime = Runtime.getRuntime();
        long maxMemory = runtime.maxMemory();
        if (maxMemory <= 0L || maxMemory == Long.MAX_VALUE) {
            return false;
        }
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        long headroom = Math.max(0L, maxMemory - usedMemory);
        double usedRatio = usedMemory / (double) maxMemory;
        return headroom <= MEMORY_MIN_HEADROOM_BYTES || usedRatio >= MEMORY_USED_RATIO_LIMIT;
    }

    private static String safeReason(String reason) {
        return reason == null || reason.isBlank() ? "Neznámý důvod" : reason;
    }
}
