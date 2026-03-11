package engine.core;

import engine.math.Vec3;

final class EngineAnimationController {
    private EngineAnimationController() {
    }

    static void animateScene(Engine engine, double elapsedSeconds) {
        if (!engine.animationPlaybackEnabled) {
            return;
        }
        if (engine.worldLightAnimationEnabled) {
            if (engine.activeMode != RenderMode.RAY_TRACING && engine.activeMode != RenderMode.PATH_TRACING) {
                if (engine.scene != null) {
                    double skyPulse = 0.5 + 0.5 * Math.sin(elapsedSeconds * 0.52);
                    double warmPulse = 0.5 + 0.5 * Math.sin(elapsedSeconds * 0.39 + 1.2);
                    engine.worldLightColor = new Vec3(
                            0.16 + 0.05 * warmPulse,
                            0.18 + 0.05 * skyPulse,
                            0.22 + 0.07 * skyPulse
                    );
                    engine.worldBackgroundColor = new Vec3(
                            0.05 + 0.012 * skyPulse,
                            0.07 + 0.014 * skyPulse,
                            0.10 + 0.016 * warmPulse
                    );
                    engine.applyWorldLightSettings();
                }
            }
            if (engine.warmWorldLight != null) {
                double t = elapsedSeconds * 0.45;
                engine.warmWorldLight.setPosition(new Vec3(
                        2.5 + Math.sin(t) * 0.9,
                        2.25 + Math.sin(t * 0.6) * 0.22,
                        2.0 + Math.cos(t) * 0.85
                ));
                engine.warmWorldLight.setIntensity(0.48 + (0.5 + 0.5 * Math.sin(t * 1.3)) * 0.18);
            }
            if (engine.coolWorldLight != null) {
                double t = elapsedSeconds * 0.38 + 1.7;
                engine.coolWorldLight.setPosition(new Vec3(
                        -2.4 + Math.sin(t) * 0.75,
                        2.0 + Math.cos(t * 0.8) * 0.20,
                        -2.5 + Math.cos(t) * 0.90
                ));
                engine.coolWorldLight.setIntensity(0.40 + (0.5 + 0.5 * Math.cos(t * 1.1)) * 0.16);
            }
        }
        if (engine.autoRotateDemo && engine.demoEntity != null) {
            engine.demoEntity.getTransform().setEulerAngles(
                    elapsedSeconds * 0.35,
                    elapsedSeconds * 0.55,
                    0.0
            );
        }
    }
}
