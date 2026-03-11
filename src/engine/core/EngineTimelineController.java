package engine.core;

import engine.scene.Entity;
import engine.scene.Light;

import javax.swing.JLabel;
import javax.swing.JTextField;
import java.util.Locale;

final class EngineTimelineController {
    private EngineTimelineController() {
    }

    static void updatePlayback(Engine engine, double dt) {
        if (engine == null || engine.sceneTimeline == null || !engine.timelineEnabled) {
            return;
        }
        if (engine.timelineEndFrame < engine.timelineStartFrame) {
            engine.timelineEndFrame = engine.timelineStartFrame;
        }
        if (engine.timelineCurrentFrame < engine.timelineStartFrame) {
            engine.timelineCurrentFrame = engine.timelineStartFrame;
        }
        if (engine.timelineCurrentFrame > engine.timelineEndFrame) {
            engine.timelineCurrentFrame = engine.timelineEndFrame;
        }

        if (engine.animationPlaybackEnabled && engine.timelineFps > 0.0 && engine.sceneTimeline.hasAnyKeyframes()) {
            engine.timelineFrameCursor += dt * engine.timelineFps;
            int wholeFrames = (int) Math.floor(engine.timelineFrameCursor);
            if (wholeFrames > 0) {
                engine.timelineFrameCursor -= wholeFrames;
                advanceFrames(engine, wholeFrames);
            }
            applyCurrentFrame(engine);
        }
        refreshUi(engine);
    }

    static void setCurrentFrame(Engine engine, int frame) {
        if (engine == null) {
            return;
        }
        int clamped = clamp(frame, engine.timelineStartFrame, engine.timelineEndFrame);
        if (engine.timelineCurrentFrame != clamped) {
            engine.timelineCurrentFrame = clamped;
        }
        engine.timelineFrameCursor = 0.0;
        applyCurrentFrame(engine);
        refreshUi(engine);
        engine.refreshObjectInspectorValues();
    }

    static void applyFrameForOutput(Engine engine, int frame) {
        if (engine == null || engine.sceneTimeline == null) {
            return;
        }
        int clamped = clamp(frame, engine.timelineStartFrame, engine.timelineEndFrame);
        engine.timelineCurrentFrame = clamped;
        engine.timelineFrameCursor = 0.0;
        if (engine.sceneTimeline.hasAnyKeyframes()) {
            engine.sceneTimeline.applyAtFrame(engine, engine.timelineCurrentFrame);
        }
    }

    static void stepFrame(Engine engine, int delta) {
        if (engine == null) {
            return;
        }
        setCurrentFrame(engine, engine.timelineCurrentFrame + delta);
    }

    static void setRange(Engine engine, int start, int end) {
        if (engine == null) {
            return;
        }
        EngineHistoryManager.recordTimelineChange(engine, "Změna rozsahu časové osy", () -> {
            int safeStart = Math.max(0, start);
            int safeEnd = Math.max(safeStart, end);
            engine.timelineStartFrame = safeStart;
            engine.timelineEndFrame = safeEnd;
            engine.timelineCurrentFrame = clamp(engine.timelineCurrentFrame, safeStart, safeEnd);
            engine.timelineFrameCursor = 0.0;
            applyCurrentFrame(engine);
            refreshUi(engine);
        });
    }

    static void setFrameRate(Engine engine, double fps) {
        if (engine == null) {
            return;
        }
        EngineHistoryManager.recordTimelineChange(engine, "Změna FPS časové osy", () -> {
            engine.timelineFps = Math.max(1.0, Math.min(240.0, fps));
            refreshUi(engine);
        });
    }

    static void addKeyForSelection(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return;
        }
        int frame = engine.timelineCurrentFrame;
        EngineHistoryManager.recordTimelineChange(engine, "Přidání klíče", () -> {
            boolean changed = false;
            String label = selectedLabel(engine);
            if (engine.selectedEntity != null && engine.selectedEntity == engine.outputCameraEntity) {
                boolean a = engine.sceneTimeline.addOrReplaceEntityKey(engine.selectedEntity, frame);
                boolean b = engine.sceneTimeline.addOrReplaceCameraKey(engine, frame);
                changed = a || b;
            } else if (engine.selectedEntity != null) {
                changed = engine.sceneTimeline.addOrReplaceEntityKey(engine.selectedEntity, frame);
            } else if (engine.selectedLight != null) {
                changed = engine.sceneTimeline.addOrReplaceLightKey(engine.selectedLight, frame);
            } else if (engine.selectedForceField != null) {
                changed = engine.sceneTimeline.addOrReplaceForceKey(engine.selectedForceField, frame);
            } else {
                changed = engine.sceneTimeline.addOrReplaceCameraKey(engine, frame);
                label = "Camera";
            }
            if (changed) {
                engine.timelineEnabled = true;
                System.out.println("Timeline key added: " + label + " @ frame " + frame);
            }
            refreshUi(engine);
            engine.refreshObjectInspectorValues();
        });
    }

    static void addReleaseKeyForSelection(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return;
        }
        if (engine.selectedEntity == null || engine.selectedEntity == engine.outputCameraEntity) {
            System.out.println("Timeline: release key requires a selected scene object.");
            return;
        }
        int frame = engine.timelineCurrentFrame;
        EngineHistoryManager.recordTimelineChange(engine, "Přidání release klíče", () -> {
            boolean changed = engine.sceneTimeline.addOrReplaceEntityReleaseKey(engine.selectedEntity, frame);
            if (changed) {
                engine.timelineEnabled = true;
                System.out.println("Timeline release key added: "
                        + engine.selectedEntity.getName()
                        + " @ frame " + frame
                        + " (transform released to physics)");
            } else {
                System.out.println("Timeline release key already exists: "
                        + engine.selectedEntity.getName()
                        + " @ frame " + frame);
            }
            refreshUi(engine);
            engine.refreshObjectInspectorValues();
        });
    }

    static void addKeyForAllAnimatables(Engine engine) {
        if (engine == null || engine.sceneTimeline == null || engine.scene == null) {
            return;
        }
        int frame = engine.timelineCurrentFrame;
        EngineHistoryManager.recordTimelineChange(engine, "Přidání klíčů všem", () -> {
            int added = 0;
            for (Entity entity : engine.scene.getEntities()) {
                if (entity == null) {
                    continue;
                }
                if (engine.sceneTimeline.addOrReplaceEntityKey(entity, frame)) {
                    added++;
                }
            }
            for (Light light : engine.scene.getLights()) {
                if (light == null) {
                    continue;
                }
                if (engine.sceneTimeline.addOrReplaceLightKey(light, frame)) {
                    added++;
                }
            }
            for (Engine.ForceField field : engine.forceFields) {
                if (field == null) {
                    continue;
                }
                if (engine.sceneTimeline.addOrReplaceForceKey(field, frame)) {
                    added++;
                }
            }
            if (engine.sceneTimeline.addOrReplaceCameraKey(engine, frame)) {
                added++;
            }
            engine.timelineEnabled = true;
            System.out.println("Timeline keys added for all animatables @ frame " + frame + " (tracks touched: " + added + ")");
            refreshUi(engine);
        });
    }

    static void removeKeyForSelection(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return;
        }
        int frame = engine.timelineCurrentFrame;
        EngineHistoryManager.recordTimelineChange(engine, "Smazání klíče", () -> {
            boolean removed;
            String label = selectedLabel(engine);
            if (engine.selectedEntity != null && engine.selectedEntity == engine.outputCameraEntity) {
                boolean a = engine.sceneTimeline.removeEntityKey(engine.selectedEntity, frame);
                boolean b = engine.sceneTimeline.removeCameraKey(frame);
                removed = a || b;
            } else if (engine.selectedEntity != null) {
                removed = engine.sceneTimeline.removeEntityKey(engine.selectedEntity, frame);
            } else if (engine.selectedLight != null) {
                removed = engine.sceneTimeline.removeLightKey(engine.selectedLight, frame);
            } else if (engine.selectedForceField != null) {
                removed = engine.sceneTimeline.removeForceKey(engine.selectedForceField, frame);
            } else {
                removed = engine.sceneTimeline.removeCameraKey(frame);
                label = "Camera";
            }
            if (removed) {
                System.out.println("Timeline key removed: " + label + " @ frame " + frame);
            } else {
                System.out.println("Timeline: no key at frame " + frame + " for " + label + ".");
            }
            refreshUi(engine);
            engine.refreshObjectInspectorValues();
        });
    }

    static void removeReleaseKeyForSelection(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return;
        }
        if (engine.selectedEntity == null || engine.selectedEntity == engine.outputCameraEntity) {
            System.out.println("Timeline: release key removal requires a selected scene object.");
            return;
        }
        int frame = engine.timelineCurrentFrame;
        EngineHistoryManager.recordTimelineChange(engine, "Smazání release klíče", () -> {
            boolean removed = engine.sceneTimeline.removeEntityReleaseKey(engine.selectedEntity, frame);
            if (removed) {
                System.out.println("Timeline release key removed: "
                        + engine.selectedEntity.getName()
                        + " @ frame " + frame);
            } else {
                System.out.println("Timeline: no release key at frame "
                        + frame + " for " + engine.selectedEntity.getName() + ".");
            }
            refreshUi(engine);
            engine.refreshObjectInspectorValues();
        });
    }

    static void clearAllKeys(Engine engine) {
        if (engine == null) {
            return;
        }
        EngineHistoryManager.recordTimelineChange(engine, "Vyčištění časové osy", () -> {
            engine.sceneTimeline.clear();
            engine.timelineFrameCursor = 0.0;
            System.out.println("Timeline: all keyframes cleared.");
            refreshUi(engine);
            engine.refreshObjectInspectorValues();
        });
    }

    static void removeEntityTrack(Engine engine, Entity entity) {
        if (engine == null || entity == null || engine.sceneTimeline == null) {
            return;
        }
        engine.sceneTimeline.removeEntity(entity);
        refreshUi(engine);
    }

    static void removeLightTrack(Engine engine, Light light) {
        if (engine == null || light == null || engine.sceneTimeline == null) {
            return;
        }
        engine.sceneTimeline.removeLight(light);
        refreshUi(engine);
    }

    static void removeForceTrack(Engine engine, Engine.ForceField field) {
        if (engine == null || field == null || engine.sceneTimeline == null) {
            return;
        }
        engine.sceneTimeline.removeForceField(field);
        refreshUi(engine);
    }

    static void applyCurrentFrame(Engine engine) {
        if (engine == null || engine.sceneTimeline == null || !engine.timelineEnabled) {
            return;
        }
        if (!engine.sceneTimeline.hasAnyKeyframes()) {
            return;
        }
        engine.sceneTimeline.applyAtFrame(engine, engine.timelineCurrentFrame);
    }

    static String timelineStatus(Engine engine) {
        int total = engine.sceneTimeline != null ? engine.sceneTimeline.totalKeyCount() : 0;
        int entityKeys = engine.sceneTimeline != null ? engine.sceneTimeline.totalEntityKeys() : 0;
        int entityReleaseKeys = engine.sceneTimeline != null ? engine.sceneTimeline.totalEntityReleaseKeys() : 0;
        int lightKeys = engine.sceneTimeline != null ? engine.sceneTimeline.totalLightKeys() : 0;
        int forceKeys = engine.sceneTimeline != null ? engine.sceneTimeline.totalForceKeys() : 0;
        int cameraKeys = engine.sceneTimeline != null ? engine.sceneTimeline.cameraKeyCount() : 0;
        int selected = selectedKeyCount(engine);
        int selectedRelease = selectedReleaseKeyCount(engine);
        String selName = selectedLabel(engine);
        return String.format(
                Locale.US,
                "Timeline %s | frame %d [%d..%d] | fps %.2f | keys %d (E:%d ER:%d L:%d F:%d C:%d) | selected %s (%d + rel:%d)",
                engine.timelineEnabled ? "ON" : "OFF",
                engine.timelineCurrentFrame,
                engine.timelineStartFrame,
                engine.timelineEndFrame,
                engine.timelineFps,
                total,
                entityKeys,
                entityReleaseKeys,
                lightKeys,
                forceKeys,
                cameraKeys,
                selName,
                selected,
                selectedRelease
        );
    }

    static void refreshUi(Engine engine) {
        if (engine == null) {
            return;
        }
        JTextField current = engine.timelineCurrentFrameField;
        if (current != null && !current.isFocusOwner()) {
            current.setText(Integer.toString(engine.timelineCurrentFrame));
        }
        JLabel status = engine.timelineStatusLabel;
        if (status != null) {
            status.setText(timelineStatus(engine));
        }
        EngineTimelineDock.refresh(engine);
    }

    private static int selectedKeyCount(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return 0;
        }
        if (engine.selectedEntity != null) {
            if (engine.selectedEntity == engine.outputCameraEntity) {
                return engine.sceneTimeline.cameraKeyCount();
            }
            return engine.sceneTimeline.keyCount(engine.selectedEntity);
        }
        if (engine.selectedLight != null) {
            return engine.sceneTimeline.keyCount(engine.selectedLight);
        }
        if (engine.selectedForceField != null) {
            return engine.sceneTimeline.keyCount(engine.selectedForceField);
        }
        return engine.sceneTimeline.cameraKeyCount();
    }

    private static int selectedReleaseKeyCount(Engine engine) {
        if (engine == null || engine.sceneTimeline == null) {
            return 0;
        }
        if (engine.selectedEntity == null || engine.selectedEntity == engine.outputCameraEntity) {
            return 0;
        }
        return engine.sceneTimeline.releaseKeyCount(engine.selectedEntity);
    }

    private static String selectedLabel(Engine engine) {
        if (engine == null) {
            return "-";
        }
        if (engine.selectedEntity != null) {
            if (engine.selectedEntity == engine.outputCameraEntity) {
                return "Camera";
            }
            return engine.selectedEntity.getName();
        }
        if (engine.selectedLight != null) {
            return "Light: " + engine.getLightName(engine.selectedLight);
        }
        if (engine.selectedForceField != null) {
            return "Force: " + engine.selectedForceField.name;
        }
        return "Camera";
    }

    private static void advanceFrames(Engine engine, int amount) {
        if (amount <= 0) {
            return;
        }
        int frame = engine.timelineCurrentFrame + amount;
        if (frame <= engine.timelineEndFrame) {
            engine.timelineCurrentFrame = frame;
            return;
        }
        if (engine.timelineLoop) {
            int span = Math.max(1, engine.timelineEndFrame - engine.timelineStartFrame + 1);
            int offset = frame - engine.timelineStartFrame;
            int wrapped = ((offset % span) + span) % span;
            engine.timelineCurrentFrame = engine.timelineStartFrame + wrapped;
        } else {
            engine.timelineCurrentFrame = engine.timelineEndFrame;
            engine.animationPlaybackEnabled = false;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
