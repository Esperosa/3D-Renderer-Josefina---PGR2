package engine.core;

import engine.material.MaterialNodeGraph;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.physics.PhysicsWorld;
import engine.scene.Entity;
import engine.scene.Scene;

import java.util.Set;

public final class UndoRedoHistoryTests {

    private static final double EPS = 1e-9;

    private UndoRedoHistoryTests() {
    }

    public static void main(String[] args) {
        testTransformGestureCommitsSingleUndoStep();
        testSceneAddAndDeleteCanBeUndone();
        testMaterialNodeAddDeleteLinkAndUnlinkUndoRedo();
        testMaterialValueChangesUndoPrincipledGlassAndVolume();
        testTimelineKeyAddAndRemoveUndoRedo();
        testRedoStackClearsAfterNewAction();
        System.out.println("UndoRedoHistoryTests: ALL TESTS PASSED");
    }

    private static void testTransformGestureCommitsSingleUndoStep() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "TransformEntity");
        engine.setCurrentEntitySelection(entity);
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        engine.beginSceneGesture("Přesun objektu");
        entity.getTransform().setPosition(new Vec3(1.0, 2.0, 3.0));
        entity.getTransform().setPosition(new Vec3(4.0, 5.0, 6.0));
        engine.commitSceneGesture();

        assertEquals(1, engine.undoHistory.size(), "Transform drag musí vytvořit jediný krok historie");
        assertEquals("Přesun objektu", engine.getUndoActionLabel(), "Undo label transformace nesedí");
        assertVecNear(new Vec3(4.0, 5.0, 6.0), entity.getTransform().getPosition(), "Pozice po commitu transformace nesedí");

        engine.undoLastAction();
        assertVecNear(Vec3.ZERO, entity.getTransform().getPosition(), "Undo transformace musí vrátit původní pozici");
        assertEquals("Přesun objektu", engine.getRedoActionLabel(), "Redo label transformace nesedí");

        engine.redoLastAction();
        assertVecNear(new Vec3(4.0, 5.0, 6.0), entity.getTransform().getPosition(), "Redo transformace musí obnovit cílovou pozici");
    }

    private static void testSceneAddAndDeleteCanBeUndone() {
        Engine engine = createEngine();
        Entity entity = new Entity("SceneEntity", null, new PhongMaterial(new Vec3(0.6, 0.6, 0.6), 32.0));
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        engine.applySceneEdit("Přidání objektu", () -> engine.scene.addEntity(entity));
        assertTrue(engine.scene.getEntities().contains(entity), "Objekt musí být po přidání ve scéně");
        assertEquals("Přidání objektu", engine.getUndoActionLabel(), "Undo label přidání objektu nesedí");

        engine.undoLastAction();
        assertFalse(engine.scene.getEntities().contains(entity), "Undo přidání musí objekt odstranit");
        engine.redoLastAction();
        assertTrue(engine.scene.getEntities().contains(entity), "Redo přidání musí objekt vrátit");

        engine.setCurrentEntitySelection(entity);
        engine.applySceneEdit("Smazání objektu", () -> engine.scene.removeEntity(entity));
        assertFalse(engine.scene.getEntities().contains(entity), "Objekt musí být po smazání pryč");
        engine.undoLastAction();
        assertTrue(engine.scene.getEntities().contains(entity), "Undo smazání musí objekt vrátit");
    }

    private static void testMaterialNodeAddDeleteLinkAndUnlinkUndoRedo() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "MaterialEntity");
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node output = requireNode(graph, MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        MaterialNodeGraph.Node baseBsdf = requireNode(graph, MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);

        PhongMaterial beforeAdd = engine.captureMaterialHistoryState(entity);
        MaterialNodeGraph.Node glass = graph.addNode(MaterialNodeGraph.NodeType.GLASS_BSDF, 620.0, 180.0);
        graph.connect(glass.getId(), "bsdf", output.getId(), "surface");
        PhongMaterial afterAdd = engine.captureMaterialHistoryState(entity);
        engine.pushMaterialHistoryCommand("Přidání uzlu", entity, beforeAdd, afterAdd);

        assertTrue(graph.getNodeById(glass.getId()) != null, "Glass uzel musí po přidání existovat");
        assertLink(graph, output.getId(), "surface", glass.getId(), "bsdf", "Výstup musí po přidání vést z Glass uzlu");
        engine.undoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        output = requireNode(graph, MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        assertTrue(graph.getNodeById(glass.getId()) == null, "Undo přidání musí Glass uzel odstranit");
        assertLink(graph, output.getId(), "surface", baseBsdf.getId(), "bsdf", "Undo přidání musí vrátit původní Principled spojení");
        engine.redoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        output = requireNode(graph, MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        glass = requireNode(graph, MaterialNodeGraph.NodeType.GLASS_BSDF);
        assertLink(graph, output.getId(), "surface", glass.getId(), "bsdf", "Redo přidání musí obnovit Glass spojení");

        PhongMaterial beforeUnlink = engine.captureMaterialHistoryState(entity);
        graph.disconnectInput(output.getId(), "surface");
        PhongMaterial afterUnlink = engine.captureMaterialHistoryState(entity);
        engine.pushMaterialHistoryCommand("Odpojení uzlu", entity, beforeUnlink, afterUnlink);
        assertTrue(graph.findInputLink(output.getId(), "surface") == null, "Spojení musí být po odpojení pryč");
        engine.undoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        output = requireNode(graph, MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        glass = requireNode(graph, MaterialNodeGraph.NodeType.GLASS_BSDF);
        assertLink(graph, output.getId(), "surface", glass.getId(), "bsdf", "Undo odpojení musí vrátit Glass spojení");

        PhongMaterial beforeDelete = engine.captureMaterialHistoryState(entity);
        graph.removeNode(glass.getId());
        PhongMaterial afterDelete = engine.captureMaterialHistoryState(entity);
        engine.pushMaterialHistoryCommand("Smazání uzlu", entity, beforeDelete, afterDelete);
        assertTrue(graph.getNodeById(glass.getId()) == null, "Uzel musí být po smazání pryč");
        engine.undoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        assertTrue(graph.findFirstNode(MaterialNodeGraph.NodeType.GLASS_BSDF) != null, "Undo smazání musí Glass uzel vrátit");
        engine.redoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        assertTrue(graph.findFirstNode(MaterialNodeGraph.NodeType.GLASS_BSDF) == null, "Redo smazání musí Glass uzel znovu odstranit");
    }

    private static void testMaterialValueChangesUndoPrincipledGlassAndVolume() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "ValueEntity");
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node principled = requireNode(graph, MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        MaterialNodeGraph.Node volume = requireNode(graph, MaterialNodeGraph.NodeType.VOLUME_MEDIUM);
        MaterialNodeGraph.Node glass = graph.addNode(MaterialNodeGraph.NodeType.GLASS_BSDF, 540.0, 140.0);

        PhongMaterial before = engine.captureMaterialHistoryState(entity);
        principled.setNumber("roughness", 0.62);
        glass.setNumber("ior", 1.61);
        volume.setNumber("density", 0.48);
        PhongMaterial after = engine.captureMaterialHistoryState(entity);
        engine.pushMaterialHistoryCommand("Úprava materiálu", entity, before, after);

        assertNear(0.62, principled.getNumber("roughness", 0.0), "Roughness po úpravě nesedí");
        assertNear(1.61, glass.getNumber("ior", 0.0), "Glass IOR po úpravě nesedí");
        assertNear(0.48, volume.getNumber("density", 0.0), "Density po úpravě nesedí");

        engine.undoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        principled = requireNode(graph, MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        volume = requireNode(graph, MaterialNodeGraph.NodeType.VOLUME_MEDIUM);
        glass = requireNode(graph, MaterialNodeGraph.NodeType.GLASS_BSDF);
        assertNear(0.45, principled.getNumber("roughness", 0.45), "Undo roughness musí vrátit původní hodnotu");
        assertNear(1.45, glass.getNumber("ior", 1.45), "Undo Glass IOR musí vrátit původní hodnotu");
        assertNear(0.0, volume.getNumber("density", 0.0), "Undo volume density musí vrátit původní hodnotu");

        engine.redoLastAction();
        graph = ((PhongMaterial) entity.getMaterial()).getOrCreateNodeGraph();
        principled = requireNode(graph, MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        volume = requireNode(graph, MaterialNodeGraph.NodeType.VOLUME_MEDIUM);
        glass = requireNode(graph, MaterialNodeGraph.NodeType.GLASS_BSDF);
        assertNear(0.62, principled.getNumber("roughness", 0.0), "Redo roughness musí obnovit změnu");
        assertNear(1.61, glass.getNumber("ior", 0.0), "Redo Glass IOR musí obnovit změnu");
        assertNear(0.48, volume.getNumber("density", 0.0), "Redo volume density musí obnovit změnu");
    }

    private static void testTimelineKeyAddAndRemoveUndoRedo() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "TimelineEntity");
        engine.setCurrentEntitySelection(entity);
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        EngineTimelineController.addKeyForSelection(engine);
        Set<Integer> selectedFrames = engine.sceneTimeline.selectedFramesFor(entity, null, null, null);
        assertTrue(selectedFrames.contains(engine.timelineCurrentFrame), "Přidání klíče musí vytvořit frame v časové ose");
        assertEquals("Přidání klíče", engine.getUndoActionLabel(), "Undo label časové osy nesedí");

        engine.undoLastAction();
        selectedFrames = engine.sceneTimeline.selectedFramesFor(entity, null, null, null);
        assertFalse(selectedFrames.contains(engine.timelineCurrentFrame), "Undo přidání klíče musí frame odstranit");

        engine.redoLastAction();
        selectedFrames = engine.sceneTimeline.selectedFramesFor(entity, null, null, null);
        assertTrue(selectedFrames.contains(engine.timelineCurrentFrame), "Redo přidání klíče musí frame vrátit");

        EngineTimelineController.removeKeyForSelection(engine);
        selectedFrames = engine.sceneTimeline.selectedFramesFor(entity, null, null, null);
        assertFalse(selectedFrames.contains(engine.timelineCurrentFrame), "Smazání klíče musí frame odstranit");
        engine.undoLastAction();
        selectedFrames = engine.sceneTimeline.selectedFramesFor(entity, null, null, null);
        assertTrue(selectedFrames.contains(engine.timelineCurrentFrame), "Undo smazání klíče musí frame vrátit");
    }

    private static void testRedoStackClearsAfterNewAction() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "RedoEntity");
        engine.setCurrentEntitySelection(entity);
        EngineHistoryManager.resetActionHistoryBaseline(engine);

        engine.beginSceneGesture("Přesun objektu");
        entity.getTransform().setPosition(new Vec3(2.0, 0.0, 0.0));
        engine.commitSceneGesture();
        engine.undoLastAction();
        assertEquals("Přesun objektu", engine.getRedoActionLabel(), "Redo label po undo musí být dostupný");

        engine.applySceneEdit("Přesun objektu", () -> entity.getTransform().setPosition(new Vec3(7.0, 0.0, 0.0)));
        assertEquals("", engine.getRedoActionLabel(), "Nová akce musí zahodit redo větev");
    }

    private static Engine createEngine() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.physicsWorld = new PhysicsWorld();
        return engine;
    }

    private static Entity addEntity(Engine engine, String name) {
        PhongMaterial material = new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 32.0);
        Entity entity = new Entity(name, null, material);
        engine.scene.addEntity(entity);
        engine.stateFor(entity);
        return entity;
    }

    private static MaterialNodeGraph.Node requireNode(MaterialNodeGraph graph, MaterialNodeGraph.NodeType type) {
        MaterialNodeGraph.Node node = graph.findFirstNode(type);
        if (node == null) {
            throw new AssertionError("Chybí očekávaný uzel: " + type);
        }
        return node;
    }

    private static void assertLink(MaterialNodeGraph graph,
                                   int toNodeId,
                                   String toSocket,
                                   int fromNodeId,
                                   String fromSocket,
                                   String message) {
        MaterialNodeGraph.Link link = graph.findInputLink(toNodeId, toSocket);
        if (link == null
                || link.getFromNodeId() != fromNodeId
                || !fromSocket.equals(link.getFromSocket())) {
            throw new AssertionError(message);
        }
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, String message) {
        if (expected.sub(actual).length() > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNear(double expected, double actual, String message) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void assertFalse(boolean condition, String message) {
        if (condition) {
            throw new AssertionError(message);
        }
    }
}
