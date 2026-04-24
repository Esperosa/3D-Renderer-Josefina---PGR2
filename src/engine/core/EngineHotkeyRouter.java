package engine.core;

import engine.math.Vec3;
import engine.render.post.DitherRenderer;

import java.awt.event.KeyEvent;

final class EngineHotkeyRouter {

    private EngineHotkeyRouter() {
    }

    static void handle(Engine engine) {
        if (engine.outputRenderController != null && engine.outputRenderController.isRenderInProgress()) {
            if (engine.input.isKeyPressed(KeyEvent.VK_SPACE)) {
                engine.toggleOutputRenderPause();
            }
            if (engine.input.isKeyPressed(KeyEvent.VK_ESCAPE)) {
                engine.cancelOutputRender();
            }
            return;
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_H)) {
            engine.printHelp();
        }
        if (!engine.input.isAltDown() && !engine.input.isCtrlDown()) {
            if (engine.input.isKeyPressed(KeyEvent.VK_Q)) {
                engine.setNavigationPreset(Engine.NavigationPreset.FPS);
            } else if (engine.input.isKeyPressed(KeyEvent.VK_E)) {
                engine.setNavigationPreset(Engine.NavigationPreset.BLENDER);
            }
        }
        if (engine.navigationPreset == Engine.NavigationPreset.BLENDER
                && !engine.mouseCaptured
                && !engine.input.isAltDown()
                && !engine.input.isCtrlDown()
                && !engine.addMenuActive
                && engine.input.isKeyPressed(KeyEvent.VK_SPACE)) {
            engine.toggleAnimationPlayback();
        }
        if (!engine.mouseCaptured && !engine.input.isCtrlDown() && !engine.input.isAltDown()) {
            if (engine.input.isKeyPressed(KeyEvent.VK_LEFT)) {
                engine.stepTimelineFrame(-1);
            } else if (engine.input.isKeyPressed(KeyEvent.VK_RIGHT)) {
                engine.stepTimelineFrame(1);
            }
        }
        if (!engine.input.isCtrlDown() && !engine.input.isAltDown()) {
            if (engine.input.isKeyPressed(KeyEvent.VK_INSERT)) {
                if (engine.input.isShiftDown()) {
                    engine.removeTimelineKeyForSelection();
                } else {
                    engine.addTimelineKeyForSelection();
                }
            }
        }
        if (engine.input.isCtrlDown() && !engine.input.isAltDown() && engine.input.isKeyPressed(KeyEvent.VK_INSERT)) {
            EngineTimelineController.addKeyForAllAnimatables(engine);
        }
        if (!engine.input.isCtrlDown()
                && !engine.input.isAltDown()
                && !engine.addMenuActive
                && engine.input.isKeyPressed(KeyEvent.VK_K)) {
            if (engine.input.isShiftDown()) {
                engine.addTimelineReleaseKeyForSelection();
            } else {
                engine.addTimelineKeyForSelection();
            }
        }

        if (!engine.addMenuActive) {
            boolean temporalGrainHotkey = engine.activeMode == RenderMode.TEMPORAL_NOISE
                    && !engine.input.isCtrlDown()
                    && !engine.input.isAltDown()
                    && engine.input.isCharPressed('ž');
            if (temporalGrainHotkey) {
                engine.cycleTemporalNoiseGrainPreset();
            }
            if (engine.isRenderHotkeyPressed(RenderMode.MODEL)) {
                engine.setRenderMode(RenderMode.MODEL);
            } else if (engine.isRenderHotkeyPressed(RenderMode.BASIC)) {
                engine.setRenderMode(RenderMode.BASIC);
            } else if (engine.isRenderHotkeyPressed(RenderMode.PHONG)) {
                engine.setRenderMode(RenderMode.PHONG);
            } else if (engine.isRenderHotkeyPressed(RenderMode.WIREFRAME)) {
                engine.setRenderMode(RenderMode.WIREFRAME);
            } else if (engine.isRenderHotkeyPressed(RenderMode.DITHERING)) {
                engine.setDitherStyle(DitherRenderer.DitherStyle.BLUE_NOISE);
                engine.setRenderMode(RenderMode.DITHERING);
            } else if (engine.isAsciiRenderHotkeyPressed()) {
                engine.setDitherStyle(DitherRenderer.DitherStyle.ASCII);
                engine.setRenderMode(RenderMode.DITHERING);
            } else if (!temporalGrainHotkey && engine.isRenderHotkeyPressed(RenderMode.TEMPORAL_NOISE)) {
                engine.setRenderMode(RenderMode.TEMPORAL_NOISE);
            } else if (engine.isRenderHotkeyPressed(RenderMode.RAY_TRACING)) {
                engine.setRenderMode(RenderMode.RAY_TRACING);
            } else if (engine.isRenderHotkeyPressed(RenderMode.PATH_TRACING)) {
                engine.setRenderMode(RenderMode.PATH_TRACING);
            } else if (engine.isRenderHotkeyPressed(RenderMode.HEX_MOSAIC)) {
                engine.setRenderMode(RenderMode.HEX_MOSAIC);
            }
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_F1) || engine.input.isKeyPressed(KeyEvent.VK_BACK_QUOTE)) {
            engine.cycleDitherStyle();
            engine.setRenderMode(RenderMode.DITHERING);
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_V) && engine.activeMode == RenderMode.TEMPORAL_NOISE) {
            engine.cycleTemporalNoiseMode();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_U) && engine.activeMode == RenderMode.HEX_MOSAIC) {
            engine.cycleHexWowMode();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_Y) && engine.activeMode == RenderMode.HEX_MOSAIC) {
            engine.toggleHexDebugCells();
        }

        if (!engine.input.isShiftDown()
                && !engine.input.isCtrlDown()
                && engine.input.isKeyPressed(KeyEvent.VK_Z)
                && engine.transformMode == Engine.TransformMode.NONE
                && !engine.addMenuActive) {
            engine.cycleRenderMode();
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_TAB)) {
            engine.cycleCameraMode();
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_F4) || engine.input.isKeyPressed(KeyEvent.VK_O)) {
            engine.toggleProjectionCamera();
        }

        if (engine.input.isCtrlDown() && engine.input.isKeyPressed(KeyEvent.VK_NUMPAD1)) {
            engine.cameraController.snapView(new Vec3(0.0, 0.0, -1.0), true);
        }
        if (engine.input.isCtrlDown() && engine.input.isKeyPressed(KeyEvent.VK_NUMPAD3)) {
            engine.cameraController.snapView(new Vec3(1.0, 0.0, 0.0), true);
        }
        if (engine.input.isCtrlDown() && engine.input.isKeyPressed(KeyEvent.VK_NUMPAD7)) {
            engine.cameraController.snapView(new Vec3(0.0, -1.0, 0.0), true);
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_F5)) {
            engine.toggleFrustumCulling();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F6)) {
            engine.toggleBackfaceCulling();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F7)) {
            engine.togglePhysics();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F8)) {
            engine.autoRotateDemo = !engine.autoRotateDemo;
            System.out.println("Auto rotate demo: " + (engine.autoRotateDemo ? "ON" : "OFF"));
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F2)) {
            engine.toggleUpscaleFilter();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F3)) {
            engine.togglePostAA();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F9)) {
            engine.toggleParallelRaster();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_B)) {
            engine.toggleDebugOverlay();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_N)) {
            engine.toggleEditorOverlay();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F10)) {
            engine.cycleRenderScale();
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F11)) {
            engine.adjustWorkerCount(-1);
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_F12)) {
            engine.adjustWorkerCount(1);
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_PAGE_DOWN)) {
            engine.adjustPathSamplesPerFrame(-1);
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_PAGE_UP)) {
            engine.adjustPathSamplesPerFrame(1);
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_MINUS) || engine.input.isKeyPressed(KeyEvent.VK_SUBTRACT)) {
            engine.adjustMoveSpeed(0.85);
        }
        if (engine.input.isKeyPressed(KeyEvent.VK_EQUALS) || engine.input.isKeyPressed(KeyEvent.VK_ADD) || engine.input.isCharPressed('+')) {
            engine.adjustMoveSpeed(1.15);
        }
        if (!engine.input.isAltDown() && engine.input.isKeyPressed(KeyEvent.VK_COMMA)) {
            engine.adjustLookSensitivity(0.85);
        }
        if (!engine.input.isAltDown() && engine.input.isKeyPressed(KeyEvent.VK_PERIOD)) {
            engine.adjustLookSensitivity(1.15);
        }

        if (engine.input.isShiftDown() && engine.input.isKeyPressed(KeyEvent.VK_A)) {
            engine.addMenuActive = true;
            System.out.println("Add menu: C Cube, S Sphere, P Plane, Y Cylinder, N Cone, T Torus, H Capsule, R Pyramid, D Crystal, K Torus Knot, Esc Cancel");
        }
        if (engine.addMenuActive) {
            if (engine.input.isKeyPressed(KeyEvent.VK_C)) {
                engine.addPrimitive("cube");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_S)) {
                engine.addPrimitive("sphere");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_P)) {
                engine.addPrimitive("plane");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_Y)) {
                engine.addPrimitive("cylinder");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_N)) {
                engine.addPrimitive("cone");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_T)) {
                engine.addPrimitive("torus");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_H)) {
                engine.addPrimitive("capsule");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_R)) {
                engine.addPrimitive("pyramid");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_D)) {
                engine.addPrimitive("crystal");
                engine.addMenuActive = false;
            } else if (engine.input.isKeyPressed(KeyEvent.VK_K)) {
                engine.addPrimitive("torus-knot");
                engine.addMenuActive = false;
            }
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_OPEN_BRACKET)) {
            engine.selectRelative(-1);
        } else if (engine.input.isKeyPressed(KeyEvent.VK_CLOSE_BRACKET)) {
            engine.selectRelative(1);
        }

        if (!engine.addMenuActive
                && engine.navigationPreset == Engine.NavigationPreset.BLENDER
                && !engine.mouseCaptured
                && !engine.input.isAltDown()
                && !engine.input.isCtrlDown()
                && engine.selectionSupportsTransform()
                && engine.input.isKeyPressed(KeyEvent.VK_S)) {
            engine.activateTransformMode(Engine.TransformMode.SCALE);
            engine.setAxisConstraint(Engine.AxisConstraint.NONE);
        }

        if (!engine.addMenuActive && engine.input.isAltDown()) {
            if (engine.input.isKeyPressed(KeyEvent.VK_G)) {
                engine.toggleTransformMode(Engine.TransformMode.MOVE);
            } else if (engine.input.isKeyPressed(KeyEvent.VK_R)) {
                engine.toggleTransformMode(Engine.TransformMode.ROTATE);
            } else if (engine.input.isKeyPressed(KeyEvent.VK_S)) {
                engine.toggleTransformMode(Engine.TransformMode.SCALE);
            }
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_X)) {
            engine.setAxisConstraint(Engine.AxisConstraint.X);
        } else if (engine.input.isKeyPressed(KeyEvent.VK_Y)) {
            engine.setAxisConstraint(Engine.AxisConstraint.Y);
        } else if (engine.input.isKeyPressed(KeyEvent.VK_Z) && engine.transformMode != Engine.TransformMode.NONE) {
            engine.setAxisConstraint(Engine.AxisConstraint.Z);
        }

        if (engine.input.isKeyPressed(KeyEvent.VK_ENTER)) {
            engine.commitSceneGesture();
            engine.transformMode = Engine.TransformMode.NONE;
            engine.axisConstraint = Engine.AxisConstraint.NONE;
            engine.gizmoDragActive = false;
        }

    }
}
