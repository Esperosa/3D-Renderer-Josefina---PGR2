package engine.core;

import engine.math.Vec3;
import engine.physics.PhysicsWorld;
import engine.scene.Entity;
import engine.scene.Scene;
import engine.util.UiBuilder;

import java.awt.Component;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;

public final class EditorShortcutRouterTests {
    private static final double EPS = 1e-9;

    private EditorShortcutRouterTests() {
    }

    public static void main(String[] args) {
        testViewportLikeFocusRoutesUndoRedo();
        testRedoDoesNotTriggerOnBareShiftZ();
        testOutlinerDeleteRoutesThroughEditorShortcutLayer();
        testMaterialGraphActionsUseSharedActionIds();
        testTextFieldUndoKeepsEditorHistoryUntouched();
        testTextFieldDeleteDoesNotLeakIntoEditorActions();
        testEscapeCancelsTransientSceneGesture();
        testTimelineHomeResetsCurrentFrame();
        testTimelinePanelShortcutsControlPlaybackScrubAndKeys();
        System.out.println("EditorShortcutRouterTests: ALL TESTS PASSED");
        System.exit(0);
    }

    private static void testViewportLikeFocusRoutesUndoRedo() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "ViewportEntity");
        EngineHistoryManager.resetActionHistoryBaseline(engine);
        engine.applySceneEdit("Přesun objektu", () -> entity.getTransform().setPosition(new Vec3(3.0, 0.0, 0.0)));

        JPanel viewport = new JPanel();
        EditorFocusContext.mark(viewport, EditorFocusContext.VIEWPORT);

        assertTrue(route(engine, viewport, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "Ctrl+Z musí být zpracováno");
        assertVecNear(Vec3.ZERO, entity.getTransform().getPosition(), "Undo z viewportu musí vrátit transformaci");
        assertTrue(route(engine, viewport, KeyEvent.VK_Z,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK), "Ctrl+Shift+Z musí být zpracováno");
        assertVecNear(new Vec3(3.0, 0.0, 0.0), entity.getTransform().getPosition(), "Redo z viewportu musí vrátit změnu");

        engine.undoLastAction();
        assertTrue(route(engine, viewport, KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "Ctrl+Y musí fungovat jako sekundární redo");
        assertVecNear(new Vec3(3.0, 0.0, 0.0), entity.getTransform().getPosition(), "Ctrl+Y musí obnovit změnu");
    }

    private static void testRedoDoesNotTriggerOnBareShiftZ() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "RedoEntity");
        EngineHistoryManager.resetActionHistoryBaseline(engine);
        engine.applySceneEdit("Přesun objektu", () -> entity.getTransform().setPosition(new Vec3(5.0, 0.0, 0.0)));
        engine.undoLastAction();

        JPanel viewport = new JPanel();
        EditorFocusContext.mark(viewport, EditorFocusContext.VIEWPORT);

        assertFalse(route(engine, viewport, KeyEvent.VK_Z, InputEvent.SHIFT_DOWN_MASK),
                "Samotné Shift+Z už nesmí fungovat jako redo");
        assertVecNear(Vec3.ZERO, entity.getTransform().getPosition(), "Shift+Z nesmí změnit stav");
        assertEquals("Přesun objektu", engine.getRedoActionLabel(), "Redo label musí po Shift+Z zůstat zachovaný");
    }

    private static void testOutlinerDeleteRoutesThroughEditorShortcutLayer() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "OutlinerEntity");
        engine.setCurrentEntitySelection(entity);

        javax.swing.JList<String> outliner = new javax.swing.JList<>();
        EditorFocusContext.mark(outliner, EditorFocusContext.OUTLINER);

        assertTrue(route(engine, outliner, KeyEvent.VK_DELETE, 0), "Delete v outlineru musí být zpracováno");
        assertFalse(engine.scene.getEntities().contains(entity), "Delete v outlineru musí odstranit vybraný objekt");
    }

    private static void testMaterialGraphActionsUseSharedActionIds() {
        Engine engine = createEngine();
        JPanel graph = new JPanel();
        EditorFocusContext.mark(graph, EditorFocusContext.MATERIAL_GRAPH);
        AtomicInteger deleteCount = new AtomicInteger();
        AtomicInteger duplicateCount = new AtomicInteger();
        AtomicInteger frameSelectedCount = new AtomicInteger();
        AtomicInteger frameAllCount = new AtomicInteger();
        AtomicInteger cancelCount = new AtomicInteger();

        graph.getActionMap().put(EditorActionId.DELETE, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                deleteCount.incrementAndGet();
            }
        });
        graph.getActionMap().put(EditorActionId.DUPLICATE, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                duplicateCount.incrementAndGet();
            }
        });
        graph.getActionMap().put(EditorActionId.FRAME_SELECTED, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                frameSelectedCount.incrementAndGet();
            }
        });
        graph.getActionMap().put(EditorActionId.FRAME_ALL, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                frameAllCount.incrementAndGet();
            }
        });
        graph.getActionMap().put(EditorActionId.CANCEL, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                cancelCount.incrementAndGet();
            }
        });

        assertTrue(route(engine, graph, KeyEvent.VK_DELETE, 0), "Delete v material graphu musí jít přes action map");
        assertTrue(route(engine, graph, KeyEvent.VK_D, InputEvent.CTRL_DOWN_MASK), "Ctrl+D v material graphu musí jít přes action map");
        assertTrue(route(engine, graph, KeyEvent.VK_F, 0), "F v material graphu musí jít přes action map");
        assertTrue(route(engine, graph, KeyEvent.VK_HOME, 0), "Home v material graphu musí jít přes action map");
        assertTrue(route(engine, graph, KeyEvent.VK_ESCAPE, 0), "Escape v material graphu musí jít přes action map");

        assertEquals(1, deleteCount.get(), "Delete akce musí proběhnout jednou");
        assertEquals(1, duplicateCount.get(), "Duplicate akce musí proběhnout jednou");
        assertEquals(1, frameSelectedCount.get(), "Frame Selected akce musí proběhnout jednou");
        assertEquals(1, frameAllCount.get(), "Frame All akce musí proběhnout jednou");
        assertEquals(1, cancelCount.get(), "Cancel akce musí proběhnout jednou");
    }

    private static void testTextFieldUndoKeepsEditorHistoryUntouched() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "TextUndoEntity");
        EngineHistoryManager.resetActionHistoryBaseline(engine);
        engine.applySceneEdit("Přesun objektu", () -> entity.getTransform().setPosition(new Vec3(2.0, 0.0, 0.0)));

        JTextField field = new JTextField();
        UiBuilder.styleInspectorField(field);
        fireFocusGained(field);
        field.setText("abc");

        assertTrue(route(engine, field, KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK),
                "Ctrl+Z v textovém poli musí vracet lokální text");
        assertEquals("", field.getText(), "Undo v textovém poli musí vrátit lokální text");
        assertVecNear(new Vec3(2.0, 0.0, 0.0), entity.getTransform().getPosition(),
                "Lokální text undo nesmí sáhnout do editorové historie");

        assertTrue(route(engine, field, KeyEvent.VK_Z,
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK),
                "Ctrl+Shift+Z v textovém poli musí redo lokální text");
        assertEquals("abc", field.getText(), "Redo v textovém poli musí obnovit lokální text");
    }

    private static void testTextFieldDeleteDoesNotLeakIntoEditorActions() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "TextDeleteEntity");
        engine.setCurrentEntitySelection(entity);

        JTextField field = new JTextField();
        UiBuilder.styleInspectorField(field);
        fireFocusGained(field);
        field.setText("abc");

        assertFalse(route(engine, field, KeyEvent.VK_DELETE, 0),
                "Delete v textovém poli nesmí spouštět editorové mazání");
        assertTrue(engine.scene.getEntities().contains(entity),
                "Delete v textovém poli nesmí odstranit vybraný objekt");
    }

    private static void testEscapeCancelsTransientSceneGesture() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "EscapeEntity");
        engine.setCurrentEntitySelection(entity);
        entity.getTransform().setPosition(new Vec3(4.0, 0.0, 0.0));
        engine.transformMode = Engine.TransformMode.MOVE;
        engine.beginSceneGesture("Přesun objektu");
        entity.getTransform().setPosition(new Vec3(9.0, 0.0, 0.0));

        JPanel viewport = new JPanel();
        EditorFocusContext.mark(viewport, EditorFocusContext.VIEWPORT);

        assertTrue(route(engine, viewport, KeyEvent.VK_ESCAPE, 0), "Escape musí zrušit aktivní gesto");
        assertVecNear(new Vec3(4.0, 0.0, 0.0), entity.getTransform().getPosition(),
                "Escape musí vrátit původní stav gesta");
        assertEquals(Engine.TransformMode.NONE, engine.transformMode, "Escape musí ukončit transform mód");
    }

    private static void testTimelineHomeResetsCurrentFrame() {
        Engine engine = createEngine();
        engine.timelineStartFrame = 10;
        engine.timelineEndFrame = 60;
        engine.timelineCurrentFrame = 37;

        JPanel timeline = new JPanel();
        EditorFocusContext.mark(timeline, EditorFocusContext.TIMELINE);

        assertTrue(route(engine, timeline, KeyEvent.VK_HOME, 0), "Home v timeline musí být zpracováno");
        assertEquals(10, engine.timelineCurrentFrame, "Home v timeline musí skočit na začátek rozsahu");
    }

    private static void testTimelinePanelShortcutsControlPlaybackScrubAndKeys() {
        Engine engine = createEngine();
        Entity entity = addEntity(engine, "TimelineEntity");
        engine.setCurrentEntitySelection(entity);
        engine.timelineStartFrame = 1;
        engine.timelineEndFrame = 10;
        engine.timelineCurrentFrame = 4;

        JPanel timeline = new JPanel();
        EditorFocusContext.mark(timeline, EditorFocusContext.TIMELINE);

        boolean playbackBefore = engine.animationPlaybackEnabled;
        assertTrue(route(engine, timeline, KeyEvent.VK_SPACE, 0), "Space v timeline musí přepnout playback");
        assertEquals(!playbackBefore, engine.animationPlaybackEnabled, "Space musí přepnout přehrávání animace");
        assertTrue(route(engine, timeline, KeyEvent.VK_RIGHT, 0), "Šipka doprava v timeline musí projít");
        assertEquals(5, engine.timelineCurrentFrame, "Šipka doprava musí posunout o snímek vpřed");
        assertTrue(route(engine, timeline, KeyEvent.VK_LEFT, 0), "Šipka doleva v timeline musí projít");
        assertEquals(4, engine.timelineCurrentFrame, "Šipka doleva musí posunout o snímek zpět");

        assertTrue(route(engine, timeline, KeyEvent.VK_INSERT, 0), "Insert v timeline musí vložit klíč");
        assertTrue(engine.sceneTimeline.hasEntityKey(entity, engine.timelineCurrentFrame),
                "Insert musí vložit klíč aktuálního výběru");
        assertTrue(route(engine, timeline, KeyEvent.VK_INSERT, InputEvent.SHIFT_DOWN_MASK),
                "Shift+Insert v timeline musí smazat klíč");
        assertFalse(engine.sceneTimeline.hasEntityKey(entity, engine.timelineCurrentFrame),
                "Shift+Insert musí smazat klíč aktuálního výběru");
        assertTrue(route(engine, timeline, KeyEvent.VK_K, InputEvent.SHIFT_DOWN_MASK),
                "Shift+K v timeline musí vložit release klíč");
        assertTrue(engine.sceneTimeline.hasEntityReleaseKey(entity, engine.timelineCurrentFrame),
                "Shift+K musí vložit release klíč aktuálního výběru");
    }

    private static boolean route(Engine engine, Component focusOwner, int keyCode, int modifiers) {
        return EditorShortcutRouter.routeShortcut(engine, focusOwner, KeyStroke.getKeyStroke(keyCode, modifiers));
    }

    private static Engine createEngine() {
        Engine engine = new Engine();
        engine.scene = new Scene();
        engine.physicsWorld = new PhysicsWorld();
        return engine;
    }

    private static Entity addEntity(Engine engine, String name) {
        Entity entity = new Entity(name, null, new engine.material.PhongMaterial(new Vec3(0.7, 0.7, 0.7), 32.0));
        engine.scene.addEntity(entity);
        engine.stateFor(entity);
        return entity;
    }

    private static void fireFocusGained(JTextField field) {
        FocusEvent event = new FocusEvent(field, FocusEvent.FOCUS_GAINED);
        for (FocusListener listener : field.getFocusListeners()) {
            listener.focusGained(event);
        }
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, String message) {
        if (expected.sub(actual).length() > EPS) {
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
