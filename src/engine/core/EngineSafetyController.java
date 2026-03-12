package engine.core;

/**
 * Tady držím lehký watchdog nad viewport loopem, aby mi editor při přetížení radši krátce zvolnil,
 * než aby dlouho dusil CPU a přestal reagovat.
 */
final class EngineSafetyController {
    private static final double SEVERE_FRAME_MS = 650.0;
    private static final double HARD_FRAME_MS = 1500.0;
    private static final int SEVERE_STREAK_LIMIT = 2;
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
        if (engine.safetyRecoveryActive && now >= engine.safetyRecoveryUntilNanos) {
            finishRecovery(engine);
        }
        if (!engine.safetyRecoveryActive && isMemoryPressureCritical()) {
            armRecovery(engine, now, "Paměťový tlak");
        }
        return engine.safetyRecoveryActive;
    }

    static void recordFrame(Engine engine, double frameTimeMs, long now) {
        if (engine == null || !engine.safetyMonitorEnabled || !Double.isFinite(frameTimeMs) || frameTimeMs <= 0.0) {
            return;
        }
        if (engine.safetyRecoveryActive) {
            engine.safetySevereFrameStreak = 0;
            return;
        }

        if (frameTimeMs >= HARD_FRAME_MS) {
            armRecovery(engine, now, "Extrémně dlouhý frame");
            return;
        }
        if (frameTimeMs >= SEVERE_FRAME_MS) {
            engine.safetySevereFrameStreak++;
            if (engine.safetySevereFrameStreak >= SEVERE_STREAK_LIMIT) {
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

        // Tady při recovery shodím akumulaci těžkých rendererů a interní scale clamp, aby se mi viewport rychle nadechl.
        if (engine.pathTracerRenderer != null) {
            engine.pathTracerRenderer.setParameter("reset", true);
        }
        if (engine.rayTracerRenderer != null) {
            engine.rayTracerRenderer.setParameter("reset", true);
        }
        if (engine.frameBuffer != null) {
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
        if (engine.frameBuffer != null) {
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
