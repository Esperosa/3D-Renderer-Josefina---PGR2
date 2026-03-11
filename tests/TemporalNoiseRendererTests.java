import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.post.TemporalNoiseRenderer;
import engine.render.raster.RasterRenderer;
import engine.scene.Entity;
import engine.scene.Scene;

import java.util.Arrays;

public final class TemporalNoiseRendererTests {

    private TemporalNoiseRendererTests() {
    }

    public static void main(String[] args) {
        testGrainPresetHelpersUseOnlyAllowedModes();
        testDeterministicOutputForFixedTime();
        testTemporalEvolutionExistsWithStaticCamera();
        testAxisLockedDirectionsOnly();
        testGrainUsesIntegerShiftsOnly();
        testObjectInteriorMaintainsCellCoherence();
        testCoarseGrainUsesOneValuePerRenderedCell();
        testSmoothInteriorKeepsUniformMotionWithinCell();
        testFlatFaceRegionHasUniformDirectionAndSpeed();
        testCubeFacesExposeDistinctDirectionAndTiming();
        testCombinedAxisMotionUsesSharedStepper();
        testSphereSmoothRegionAvoidsTriangleSnapping();
        testCylinderSmoothRegionAvoidsFaceSnapping();
        testBackgroundRemainsStatic();
        testSingleFrameUsesFiveTonePalette();
        testEdgeMaskHighlightsSilhouette();
        testAnalysisCacheReusesSemistaticMaps();
        testNoiseSampleBudgetIsConstant();
        System.out.println("TemporalNoiseRendererTests: ALL TESTS PASSED");
    }

    private static void testGrainPresetHelpersUseOnlyAllowedModes() {
        if (TemporalNoiseRenderer.normalizeGrainCellSizePreset(0) != 1
                || TemporalNoiseRenderer.normalizeGrainCellSizePreset(1) != 1
                || TemporalNoiseRenderer.normalizeGrainCellSizePreset(2) != 2
                || TemporalNoiseRenderer.normalizeGrainCellSizePreset(3) != 2
                || TemporalNoiseRenderer.normalizeGrainCellSizePreset(4) != 4
                || TemporalNoiseRenderer.normalizeGrainCellSizePreset(7) != 4) {
            throw new AssertionError("Grain preset normalizace nepoužívá jen režimy 1x1, 2x2 a 4x4.");
        }
        if (TemporalNoiseRenderer.nextGrainCellSizePreset(1) != 2
                || TemporalNoiseRenderer.nextGrainCellSizePreset(2) != 4
                || TemporalNoiseRenderer.nextGrainCellSizePreset(4) != 1) {
            throw new AssertionError("Grain preset cyklus nemá pořadí 1x1 -> 2x2 -> 4x4 -> 1x1.");
        }
    }

    private static void testDeterministicOutputForFixedTime() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult a = renderTemporal(scene, camera, 128, 128, 1.35, TemporalNoiseRenderer.DebugView.FINAL);
        TemporalRenderResult b = renderTemporal(scene, camera, 128, 128, 1.35, TemporalNoiseRenderer.DebugView.FINAL);
        assertIntArrayEquals("Temporal determinismus", a.frameBuffer.getColorBuffer(), b.frameBuffer.getColorBuffer());
        assertFloatArrayEquals("Temporal hloubka", a.frameBuffer.getDepthBuffer(), b.frameBuffer.getDepthBuffer());
    }

    private static void testTemporalEvolutionExistsWithStaticCamera() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.24, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);

        int width = gBuffer.getWidth();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        float[] speed = result.renderer.copySpeedBuffer();
        Stats objectShift = new Stats();
        Stats backgroundShift = new Stats();
        int expressiveObjectPixels = 0;

        for (int y = 1; y < gBuffer.getHeight() - 1; y++) {
            int row = y * gBuffer.getWidth();
            for (int x = 1; x < gBuffer.getWidth() - 1; x++) {
                int idx = row + x;
                if (hasMixedNeighborhood(objectId, depth, width, idx)) {
                    continue;
                }
                int shiftX0 = result.renderer.computeShiftXAt(idx, 0.10);
                int shiftY0 = result.renderer.computeShiftYAt(idx, 0.10);
                int shiftX1 = result.renderer.computeShiftXAt(idx, 0.90);
                int shiftY1 = result.renderer.computeShiftYAt(idx, 0.90);
                double delta = Math.abs(shiftX1 - shiftX0) + Math.abs(shiftY1 - shiftY0);
                if (objectId[idx] >= 0 && depth[idx] < 1.0f) {
                    objectShift.add(delta);
                    if (speed[idx] > 1.15f || shiftY1 != shiftY0) {
                        expressiveObjectPixels++;
                    }
                } else {
                    backgroundShift.add(delta);
                }
            }
        }

        if (objectShift.count < 200 || backgroundShift.count < 200) {
            throw new AssertionError("Temporal evoluce neměla dost pixelů pro vyhodnocení.");
        }
        if (objectShift.mean() <= backgroundShift.mean() + 0.45) {
            throw new AssertionError("Objekt nemá dost odlišnou regionální temporal evoluci vůči pozadí."
                    + " object=" + objectShift.mean()
                    + ", background=" + backgroundShift.mean());
        }
        if (expressiveObjectPixels <= objectShift.count * 0.22) {
            throw new AssertionError("Objekt nemá dost expresivních regionů pro čitelnost při stojící kameře.");
        }
    }

    private static void testAxisLockedDirectionsOnly() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.40, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();

        for (int i = 0; i < flowX.length; i++) {
            int axisX = Math.round(flowX[i]);
            int axisY = Math.round(flowY[i]);
            boolean objectPixel = objectId[i] >= 0 && depth[i] < 1.0f;
            boolean valid = (axisX >= -1 && axisX <= 1) && (axisY >= -1 && axisY <= 1);
            if (!valid) {
                throw new AssertionError("Temporal direction není osově uzamčený na pixelu " + i
                        + ": " + flowX[i] + ", " + flowY[i]);
            }
            if (objectPixel && axisX == 0 && axisY == 0) {
                throw new AssertionError("Objektový pixel ztratil motion směr na pixelu " + i);
            }
            if (!objectPixel && (axisX != 0 || axisY != 0)) {
                throw new AssertionError("Pozadí nemá být v pohybu.");
            }
        }
    }

    private static void testGrainUsesIntegerShiftsOnly() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.40, TemporalNoiseRenderer.DebugView.FINAL);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();

        for (int i = 0; i < flowX.length; i++) {
            int sx0 = result.renderer.computeShiftXAt(i, 0.20);
            int sy0 = result.renderer.computeShiftYAt(i, 0.20);
            int sx1 = result.renderer.computeShiftXAt(i, 0.80);
            int sy1 = result.renderer.computeShiftYAt(i, 0.80);
            int axisX = Math.round(flowX[i]);
            int axisY = Math.round(flowY[i]);
            if (axisX == 0 && (sx0 != 0 || sx1 != 0)) {
                throw new AssertionError("Pixel bez X složky používá X shift na pixelu " + i);
            }
            if (axisY == 0 && (sy0 != 0 || sy1 != 0)) {
                throw new AssertionError("Pixel bez Y složky používá Y shift na pixelu " + i);
            }
        }

        int[] color = result.frameBuffer.getColorBuffer();
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        int width = gBuffer.getWidth();
        int checkedBlocks = 0;
        for (int y = 2; y < gBuffer.getHeight() - 2; y += 2) {
            for (int x = 2; x < gBuffer.getWidth() - 2; x += 2) {
                int idx = y * width + x;
                int idxRight = idx + 1;
                int idxDown = idx + width;
                int idxDiag = idx + width + 1;
                if (objectId[idx] >= 0 || depth[idx] < 1.0f
                        || objectId[idxRight] >= 0 || depth[idxRight] < 1.0f
                        || objectId[idxDown] >= 0 || depth[idxDown] < 1.0f
                        || objectId[idxDiag] >= 0 || depth[idxDiag] < 1.0f
                        || hasMixedNeighborhood(objectId, depth, width, idx)
                        || hasMixedNeighborhood(objectId, depth, width, idxRight)
                        || hasMixedNeighborhood(objectId, depth, width, idxDown)
                        || hasMixedNeighborhood(objectId, depth, width, idxDiag)) {
                    continue;
                }
                int a = color[idx];
                int b = color[idxRight];
                int c = color[idxDown];
                int d = color[idxDiag];
                if (a != b || a != c || a != d) {
                    throw new AssertionError("Zrno není ukotvené do rozšířené 2x2 buňky.");
                }
                checkedBlocks++;
            }
        }
        if (checkedBlocks < 80) {
            throw new AssertionError("Test velikosti zrna nenašel dost stabilních bloků.");
        }
    }

    private static void testObjectInteriorMaintainsCellCoherence() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.55, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        int[] color = result.frameBuffer.getColorBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        int width = gBuffer.getWidth();
        int coherentBlocks = 0;

        for (int y = 2; y < gBuffer.getHeight() - 2; y += 2) {
            for (int x = 2; x < gBuffer.getWidth() - 2; x += 2) {
                int idx = y * width + x;
                int idxRight = idx + 1;
                int idxDown = idx + width;
                int idxDiag = idx + width + 1;
                if (!(objectId[idx] >= 0 && depth[idx] < 1.0f)
                        || !(objectId[idxRight] >= 0 && depth[idxRight] < 1.0f)
                        || !(objectId[idxDown] >= 0 && depth[idxDown] < 1.0f)
                        || !(objectId[idxDiag] >= 0 && depth[idxDiag] < 1.0f)
                        || hasMixedNeighborhood(objectId, depth, width, idx)
                        || hasMixedNeighborhood(objectId, depth, width, idxRight)
                        || hasMixedNeighborhood(objectId, depth, width, idxDown)
                        || hasMixedNeighborhood(objectId, depth, width, idxDiag)) {
                    continue;
                }
                int a = color[idx];
                int b = color[idxRight];
                int c = color[idxDown];
                int d = color[idxDiag];
                if (a != b || a != c || a != d) {
                    throw new AssertionError("Objektový interiér rozpadá grain uvnitř 2x2 buňky.");
                }
                coherentBlocks++;
            }
        }

        if (coherentBlocks < 40) {
            throw new AssertionError("Test objektového grainu nenašel dost vnitřních bloků.");
        }
    }

    private static void testCoarseGrainUsesOneValuePerRenderedCell() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
        renderer.init(128, 128);
        renderer.setParameter("debugView", TemporalNoiseRenderer.DebugView.FINAL);
        renderer.setParameter("grainCellSize", 4);
        FrameBuffer fb = new FrameBuffer(128, 128);
        renderer.render(scene, camera, fb, 0.55);

        int[] color = fb.getColorBuffer();
        int width = fb.getWidth();
        int height = fb.getHeight();
        int cellSize = 4;
        for (int cellY = 0; cellY < height; cellY += cellSize) {
            for (int cellX = 0; cellX < width; cellX += cellSize) {
                int reference = color[cellY * width + cellX];
                for (int py = cellY; py < cellY + cellSize; py++) {
                    for (int px = cellX; px < cellX + cellSize; px++) {
                        if (color[py * width + px] != reference) {
                            throw new AssertionError("Hrubé zrno 4x4 se rozpadá na jemnější podvzorky.");
                        }
                    }
                }
            }
        }
    }

    private static void testSmoothInteriorKeepsUniformMotionWithinCell() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.55, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        float[] speed = result.renderer.copySpeedBuffer();
        float[] phase = result.renderer.copyPhaseBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        int width = gBuffer.getWidth();
        int checkedBlocks = 0;

        for (int y = 2; y < gBuffer.getHeight() - 2; y += 2) {
            for (int x = 2; x < gBuffer.getWidth() - 2; x += 2) {
                int idx = y * width + x;
                int idxRight = idx + 1;
                int idxDown = idx + width;
                int idxDiag = idx + width + 1;
                if (!(objectId[idx] >= 0 && depth[idx] < 1.0f)
                        || !(objectId[idxRight] >= 0 && depth[idxRight] < 1.0f)
                        || !(objectId[idxDown] >= 0 && depth[idxDown] < 1.0f)
                        || !(objectId[idxDiag] >= 0 && depth[idxDiag] < 1.0f)
                        || hasMixedNeighborhood(objectId, depth, width, idx)
                        || hasMixedNeighborhood(objectId, depth, width, idxRight)
                        || hasMixedNeighborhood(objectId, depth, width, idxDown)
                        || hasMixedNeighborhood(objectId, depth, width, idxDiag)) {
                    continue;
                }
                if (flowX[idx] != flowX[idxRight] || flowX[idx] != flowX[idxDown] || flowX[idx] != flowX[idxDiag]
                        || flowY[idx] != flowY[idxRight] || flowY[idx] != flowY[idxDown] || flowY[idx] != flowY[idxDiag]
                        || speed[idx] != speed[idxRight] || speed[idx] != speed[idxDown] || speed[idx] != speed[idxDiag]
                        || phase[idx] != phase[idxRight] || phase[idx] != phase[idxDown] || phase[idx] != phase[idxDiag]) {
                    throw new AssertionError("Smooth interiér rozpadá motion parametry uvnitř jedné grain buňky.");
                }
                checkedBlocks++;
            }
        }

        if (checkedBlocks < 40) {
            throw new AssertionError("Test smooth cell motion nenašel dost objektových bloků.");
        }
    }

    private static void testFlatFaceRegionHasUniformDirectionAndSpeed() {
        Scene scene = buildSmoothQuadScene();
        PerspectiveCamera camera = buildQuadCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.35, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        float[] speed = result.renderer.copySpeedBuffer();
        float[] phase = result.renderer.copyPhaseBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        int[] faceId = gBuffer.getFaceIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();

        Stats face0Speed = new Stats();
        Stats face1Speed = new Stats();
        Stats face0Phase = new Stats();
        Stats face1Phase = new Stats();
        int face0AxisX = Integer.MIN_VALUE;
        int face0AxisY = Integer.MIN_VALUE;
        int face1AxisX = Integer.MIN_VALUE;
        int face1AxisY = Integer.MIN_VALUE;

        for (int y = 1; y < gBuffer.getHeight() - 1; y++) {
            int row = y * gBuffer.getWidth();
            for (int x = 1; x < gBuffer.getWidth() - 1; x++) {
                int idx = row + x;
                if (!(objectId[idx] >= 0 && depth[idx] < 1.0f) || hasMixedNeighborhood(objectId, depth, gBuffer.getWidth(), idx)) {
                    continue;
                }
                int axisX = Math.round(flowX[idx]);
                int axisY = Math.round(flowY[idx]);
                if (faceId[idx] == 0) {
                    if (face0AxisX == Integer.MIN_VALUE) {
                        face0AxisX = axisX;
                        face0AxisY = axisY;
                    } else if (face0AxisX != axisX || face0AxisY != axisY) {
                        throw new AssertionError("První flat face nemá jednotný směr.");
                    }
                    face0Speed.add(speed[idx]);
                    face0Phase.add(phase[idx]);
                } else if (faceId[idx] == 1) {
                    if (face1AxisX == Integer.MIN_VALUE) {
                        face1AxisX = axisX;
                        face1AxisY = axisY;
                    } else if (face1AxisX != axisX || face1AxisY != axisY) {
                        throw new AssertionError("Druhý flat face nemá jednotný směr.");
                    }
                    face1Speed.add(speed[idx]);
                    face1Phase.add(phase[idx]);
                }
            }
        }

        if (face0Speed.count < 50 || face1Speed.count < 50) {
            throw new AssertionError("Flat-face test neměl dost pixelů.");
        }
        if (face0Speed.stddev() > 0.02 || face1Speed.stddev() > 0.02) {
            throw new AssertionError("Flat face nemá dost jednotnou rychlost.");
        }
        if (Math.abs(face0Phase.mean() - face1Phase.mean()) > 0.35) {
            throw new AssertionError("Coplanární plocha složená z více trianglů nemá sjednocený timing.");
        }
    }

    private static void testCubeFacesExposeDistinctDirectionAndTiming() {
        Scene scene = buildCubeScene();
        PerspectiveCamera camera = buildCubeCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.45, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        int[] objectId = gBuffer.getObjectIdBuffer();
        int[] faceId = gBuffer.getFaceIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        float[] speed = result.renderer.copySpeedBuffer();
        float[] phase = result.renderer.copyPhaseBuffer();

        java.util.Map<Integer, FaceSummary> faces = new java.util.HashMap<>();
        int width = gBuffer.getWidth();
        for (int y = 1; y < gBuffer.getHeight() - 1; y++) {
            int row = y * width;
            for (int x = 1; x < gBuffer.getWidth() - 1; x++) {
                int idx = row + x;
                if (!(objectId[idx] >= 0 && depth[idx] < 1.0f) || hasMixedNeighborhood(objectId, depth, width, idx)) {
                    continue;
                }
                int currentFaceId = faceId[idx];
                FaceSummary summary = faces.computeIfAbsent(currentFaceId, ignored -> new FaceSummary());
                summary.observe(currentFaceId, Math.round(flowX[idx]), Math.round(flowY[idx]), speed[idx], phase[idx]);
            }
        }

        java.util.List<FaceSummary> visibleFaces = new java.util.ArrayList<>();
        for (FaceSummary summary : faces.values()) {
            if (summary.count >= 30) {
                visibleFaces.add(summary);
            }
        }
        if (visibleFaces.size() < 2) {
            throw new AssertionError("Cube test nenašel dost viditelných faces.");
        }

        boolean foundDirectionDifference = false;
        boolean foundTimingDifference = false;
        boolean foundDualAxisMotion = false;
        for (int i = 0; i < visibleFaces.size(); i++) {
            for (int j = i + 1; j < visibleFaces.size(); j++) {
                FaceSummary a = visibleFaces.get(i);
                FaceSummary b = visibleFaces.get(j);
                if (a.axisX != b.axisX || a.axisY != b.axisY) {
                    foundDirectionDifference = true;
                }
                if (Math.abs(a.meanSpeed() - b.meanSpeed()) >= 0.12
                        || Math.abs(a.meanPhase() - b.meanPhase()) >= 1.15) {
                    foundTimingDifference = true;
                }
            }
            FaceSummary summary = visibleFaces.get(i);
            if (summary.axisX != 0 && summary.axisY != 0) {
                foundDualAxisMotion = true;
            }
        }

        if (!foundDirectionDifference) {
            throw new AssertionError("Viditelné cube faces nemají dost výrazně odlišné směry.");
        }
        if (!foundTimingDifference) {
            throw new AssertionError("Viditelné cube faces nemají dost výrazně odlišný timing nebo rychlost.");
        }
        if (!foundDualAxisMotion) {
            throw new AssertionError("Cube test nenašel žádnou face s kombinovaným X/Y pohybem.");
        }
    }

    private static void testCombinedAxisMotionUsesSharedStepper() {
        Scene scene = buildCubeScene();
        PerspectiveCamera camera = buildCubeCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.45, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();

        java.util.List<Integer> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < flowX.length; i++) {
            if (!(objectId[i] >= 0 && depth[i] < 1.0f)) {
                continue;
            }
            if (Math.round(flowX[i]) != 0 && Math.round(flowY[i]) != 0) {
                candidates.add(i);
            }
        }
        if (candidates.isEmpty()) {
            throw new AssertionError("Test nenašel pixel s kombinovaným X/Y pohybem.");
        }

        boolean foundSharedProgress = false;
        double[] samples = {0.12, 0.22, 0.32, 0.42, 0.52, 0.62, 0.72, 0.92, 1.12, 1.32};
        for (int candidate : candidates) {
            for (int i = 1; i < samples.length; i++) {
                int prevX = result.renderer.computeShiftXAt(candidate, samples[i - 1]);
                int prevY = result.renderer.computeShiftYAt(candidate, samples[i - 1]);
                int nextX = result.renderer.computeShiftXAt(candidate, samples[i]);
                int nextY = result.renderer.computeShiftYAt(candidate, samples[i]);
                if (nextX != prevX && nextY != prevY) {
                    foundSharedProgress = true;
                    break;
                }
            }
            if (foundSharedProgress) {
                break;
            }
        }

        if (!foundSharedProgress) {
            throw new AssertionError("Kombinovaný X/Y pohyb nepůsobí jako společný krokovač.");
        }
    }

    private static void testSphereSmoothRegionAvoidsTriangleSnapping() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.65, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        assertSmoothContinuity(result, gBuffer, "kouli");
    }

    private static void testCylinderSmoothRegionAvoidsFaceSnapping() {
        Scene scene = buildCylinderScene();
        PerspectiveCamera camera = buildCylinderCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.65, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        assertSmoothContinuity(result, gBuffer, "válci");
    }

    private static void testBackgroundRemainsStatic() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.60, TemporalNoiseRenderer.DebugView.FINAL);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();

        int checked = 0;
        for (int y = 1; y < gBuffer.getHeight() - 1; y++) {
            int row = y * gBuffer.getWidth();
            for (int x = 1; x < gBuffer.getWidth() - 1; x++) {
                int idx = row + x;
                if (objectId[idx] >= 0 || depth[idx] < 1.0f || hasMixedNeighborhood(objectId, depth, gBuffer.getWidth(), idx)) {
                    continue;
                }
                if (Math.round(flowX[idx]) != 0 || Math.round(flowY[idx]) != 0) {
                    throw new AssertionError("Pozadí nemá být v pohybu.");
                }
                if (result.renderer.computeShiftXAt(idx, 0.10) != 0
                        || result.renderer.computeShiftXAt(idx, 0.95) != 0
                        || result.renderer.computeShiftYAt(idx, 0.10) != 0
                        || result.renderer.computeShiftYAt(idx, 0.95) != 0) {
                    throw new AssertionError("Pozadí nesmí používat temporal shift.");
                }
                checked++;
            }
        }
        if (checked < 200) {
            throw new AssertionError("Test pozadí nenašel dost stabilních pixelů.");
        }
    }

    private static void testSingleFrameUsesFiveTonePalette() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.85, TemporalNoiseRenderer.DebugView.FINAL);

        int[] color = result.frameBuffer.getColorBuffer();
        int dark = 0;
        int bright = 0;
        int mid = 0;
        int usedLevels = 0;
        int[] histogram = new int[256];
        for (int argb : color) {
            int gray = argb & 0xFF;
            histogram[gray]++;
            if (gray <= 40) {
                dark++;
            }
            if (gray >= 216) {
                bright++;
            }
            if (gray >= 96 && gray <= 159) {
                mid++;
            }
        }
        for (int count : histogram) {
            if (count > 0) {
                usedLevels++;
            }
        }

        double total = Math.max(1, color.length);
        double darkShare = dark / total;
        double brightShare = bright / total;
        double midShare = mid / total;

        if (usedLevels < 4 || usedLevels > 5) {
            throw new AssertionError("Temporal frame nepoužívá očekávanou kvantizovanou paletu: " + usedLevels);
        }
        if (darkShare < 0.18) {
            throw new AssertionError("Temporal frame nemá dost tmavých úrovní: " + darkShare);
        }
        if (brightShare < 0.18) {
            throw new AssertionError("Temporal frame nemá dost světlých úrovní: " + brightShare);
        }
        if (midShare < 0.10 || midShare > 0.42) {
            throw new AssertionError("Temporal frame nemá vyvážený střed palety: " + midShare);
        }
    }

    private static void testEdgeMaskHighlightsSilhouette() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalRenderResult result = renderTemporal(scene, camera, 128, 128, 0.65, TemporalNoiseRenderer.DebugView.EDGE_MASK);
        FrameBuffer gBuffer = renderMask(scene, camera, 128, 128);

        int width = result.frameBuffer.getWidth();
        int height = result.frameBuffer.getHeight();
        int[] color = result.frameBuffer.getColorBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        Stats silhouette = new Stats();
        Stats interior = new Stats();

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                boolean objectPixel = objectId[idx] >= 0 && depth[idx] < 1.0f;
                if (!objectPixel) {
                    continue;
                }
                double gray = gray(color[idx]);
                if (hasMixedNeighborhood(objectId, depth, width, idx)) {
                    silhouette.add(gray);
                } else {
                    interior.add(gray);
                }
            }
        }

        if (silhouette.count < 40 || interior.count < 200) {
            throw new AssertionError("Edge test nenašel dost pixelů.");
        }
        if (silhouette.mean() <= interior.mean() + 18.0) {
            throw new AssertionError("Edge mask nedává dost silný rozdíl mezi siluetou a interiérem.");
        }
    }

    private static void testAnalysisCacheReusesSemistaticMaps() {
        Scene scene = buildSphereScene();
        PerspectiveCamera camera = buildCamera();
        TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
        renderer.init(96, 96);
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.render(scene, camera, fb, 0.15);
        long firstBuilds = renderer.getAnalysisBuildCount();
        renderer.render(scene, camera, fb, 0.42);
        long secondBuilds = renderer.getAnalysisBuildCount();
        long reuseCount = renderer.getAnalysisReuseCount();
        if (firstBuilds != 1L || secondBuilds != 1L) {
            throw new AssertionError("Semistatická analýza se zbytečně přestavěla i bez změny scény.");
        }
        if (reuseCount < 1L || !renderer.wasLastAnalysisCacheHit()) {
            throw new AssertionError("Renderer nevyužil cache analýzy při statické scéně.");
        }

        camera.setPosition(new Vec3(0.25, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);
        renderer.render(scene, camera, fb, 0.56);
        if (renderer.getAnalysisBuildCount() < 2L) {
            throw new AssertionError("Změna kamery neinvalidovala cached analýzu.");
        }
    }

    private static void testNoiseSampleBudgetIsConstant() {
        TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
        if (renderer.getFinalNoiseSamplesPerPixelUpperBound() > 4) {
            throw new AssertionError("Final pass používá příliš mnoho noise sample operací na pixel.");
        }
    }

    private static void assertSmoothContinuity(TemporalRenderResult result, FrameBuffer gBuffer, String label) {
        float[] flowX = result.renderer.copyFlowXBuffer();
        float[] flowY = result.renderer.copyFlowYBuffer();
        int[] objectId = gBuffer.getObjectIdBuffer();
        int[] faceId = gBuffer.getFaceIdBuffer();
        float[] depth = gBuffer.getDepthBuffer();
        float[] normal = gBuffer.getNormalBuffer();
        int width = gBuffer.getWidth();
        int height = gBuffer.getHeight();
        Stats seamMismatch = new Stats();
        Stats interiorMismatch = new Stats();

        for (int y = 1; y < height - 1; y++) {
            int row = y * width;
            for (int x = 1; x < width - 1; x++) {
                int idx = row + x;
                int right = idx + 1;
                if (!isSmoothNeighborPair(objectId, depth, normal, idx, right)) {
                    continue;
                }
                boolean sameAxis = Math.round(flowX[idx]) == Math.round(flowX[right])
                        && Math.round(flowY[idx]) == Math.round(flowY[right]);
                double mismatch = sameAxis ? 0.0 : 1.0;
                if (faceId[idx] != faceId[right]) {
                    seamMismatch.add(mismatch);
                } else {
                    interiorMismatch.add(mismatch);
                }
            }
        }

        if (seamMismatch.count < 30 || interiorMismatch.count < 120) {
            throw new AssertionError("Smooth test na " + label + " neměl dost sousedů.");
        }
        if (seamMismatch.mean() > interiorMismatch.mean() + 0.12) {
            throw new AssertionError("Smooth region na " + label + " stále příliš připomíná triangle snapping."
                    + " seam=" + seamMismatch.mean()
                    + ", interior=" + interiorMismatch.mean());
        }
    }

    private static boolean isSmoothNeighborPair(int[] objectId, float[] depth, float[] normal, int a, int b) {
        if (objectId[a] < 0 || objectId[b] < 0 || objectId[a] != objectId[b]) {
            return false;
        }
        if (Math.abs(depth[a] - depth[b]) > 0.015f) {
            return false;
        }
        int baseA = a * 3;
        int baseB = b * 3;
        double dot = normal[baseA] * normal[baseB]
                + normal[baseA + 1] * normal[baseB + 1]
                + normal[baseA + 2] * normal[baseB + 2];
        return dot >= 0.97;
    }

    private static Scene buildSphereScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.9, 0.9, 0.9), 16.0);
        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.9, 24, 18), material);
        sphere.getTransform().setPosition(new Vec3(0.0, 0.0, 0.0));
        scene.addEntity(sphere);
        scene.update(0.0);
        return scene;
    }

    private static Scene buildSmoothQuadScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        Mesh mesh = new Mesh(
                "smooth-quad",
                new float[]{
                        -1.4f, -1.1f, 0.0f,
                        1.4f, -1.1f, 0.0f,
                        1.4f, 1.1f, 0.0f,
                        -1.4f, 1.1f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2, 0, 2, 3}
        );
        PhongMaterial material = new PhongMaterial(new Vec3(0.8, 0.8, 0.8), 8.0);
        Entity quad = new Entity("quad", mesh, material);
        quad.getTransform().setEulerAngles(Math.toRadians(-18.0), Math.toRadians(22.0), 0.0);
        scene.addEntity(quad);
        scene.update(0.0);
        return scene;
    }

    private static Scene buildCylinderScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.82, 0.82, 0.82), 10.0);
        Entity cylinder = new Entity("cylinder", MeshGenerator.cylinder(0.75, 1.7, 28, 1), material);
        cylinder.getTransform().setEulerAngles(Math.toRadians(12.0), Math.toRadians(26.0), 0.0);
        scene.addEntity(cylinder);
        scene.update(0.0);
        return scene;
    }

    private static Scene buildCubeScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        PhongMaterial material = new PhongMaterial(new Vec3(0.86, 0.86, 0.86), 12.0);
        Entity cube = new Entity("cube", MeshGenerator.cube(1.4), material);
        cube.getTransform().setEulerAngles(Math.toRadians(22.0), Math.toRadians(34.0), Math.toRadians(-8.0));
        scene.addEntity(cube);
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static PerspectiveCamera buildQuadCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.15, 3.0));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static PerspectiveCamera buildCylinderCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.1, 3.4));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static PerspectiveCamera buildCubeCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.05, 3.3));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static TemporalRenderResult renderTemporal(Scene scene,
                                                       PerspectiveCamera camera,
                                                       int width,
                                                       int height,
                                                       double time,
                                                       TemporalNoiseRenderer.DebugView debugView) {
        TemporalNoiseRenderer renderer = new TemporalNoiseRenderer();
        renderer.init(width, height);
        renderer.setParameter("debugView", debugView);
        FrameBuffer fb = new FrameBuffer(width, height);
        renderer.render(scene, camera, fb, time);
        return new TemporalRenderResult(renderer, fb);
    }

    private static FrameBuffer renderMask(Scene scene, PerspectiveCamera camera, int width, int height) {
        RasterRenderer renderer = new RasterRenderer();
        renderer.init(width, height);
        renderer.setParameter("unlitMode", true);
        renderer.setParameter("backfaceCulling", false);
        FrameBuffer fb = new FrameBuffer(width, height, true);
        renderer.render(scene, camera, fb, 0.0);
        return fb;
    }

    private static boolean hasMixedNeighborhood(int[] objectId, float[] depth, int width, int idx) {
        boolean centerObject = objectId[idx] >= 0 && depth[idx] < 1.0f;
        return (objectId[idx - 1] >= 0 && depth[idx - 1] < 1.0f) != centerObject
                || (objectId[idx + 1] >= 0 && depth[idx + 1] < 1.0f) != centerObject
                || (objectId[idx - width] >= 0 && depth[idx - width] < 1.0f) != centerObject
                || (objectId[idx + width] >= 0 && depth[idx + width] < 1.0f) != centerObject;
    }

    private static double gray(int argb) {
        return ((argb >> 16) & 0xFF) * 0.2126
                + ((argb >> 8) & 0xFF) * 0.7152
                + (argb & 0xFF) * 0.0722;
    }

    private static void assertIntArrayEquals(String label, int[] expected, int[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + " mismatch");
        }
    }

    private static void assertFloatArrayEquals(String label, float[] expected, float[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(label + " mismatch");
        }
    }

    private record TemporalRenderResult(TemporalNoiseRenderer renderer, FrameBuffer frameBuffer) {
    }

    private static final class Stats {
        long count;
        double sum;
        double sumSq;

        void add(double value) {
            count++;
            sum += value;
            sumSq += value * value;
        }

        double mean() {
            return count <= 0 ? 0.0 : sum / count;
        }

        double stddev() {
            if (count <= 0) {
                return 0.0;
            }
            double mean = mean();
            return Math.sqrt(Math.max(0.0, sumSq / count - mean * mean));
        }
    }

    private static final class FaceSummary {
        int axisX;
        int axisY;
        int count;
        double speedSum;
        double phaseSum;
        boolean axisInitialized;

        void observe(int faceId, int sampleAxisX, int sampleAxisY, float sampleSpeed, float samplePhase) {
            if (!axisInitialized) {
                axisX = sampleAxisX;
                axisY = sampleAxisY;
                axisInitialized = true;
            } else if (axisX != sampleAxisX || axisY != sampleAxisY) {
                throw new AssertionError("Face " + faceId + " nemá jednotný kombinovaný směr: "
                        + axisX + "," + axisY + " vs " + sampleAxisX + "," + sampleAxisY);
            }
            count++;
            speedSum += sampleSpeed;
            phaseSum += samplePhase;
        }

        double meanSpeed() {
            return count <= 0 ? 0.0 : speedSum / count;
        }

        double meanPhase() {
            return count <= 0 ? 0.0 : phaseSum / count;
        }
    }
}
