package engine.core;

import java.awt.Component;
import java.awt.Container;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import engine.geometry.Mesh;
import engine.material.Material;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPreviewRenderer;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.scene.Entity;
import engine.ui.UiStrings;

public final class MaterialDockRefactorTests {

    private MaterialDockRefactorTests() {
    }

    public static void main(String[] args) {
        testMaterialDockViewStateKeepsPreviewSettings();
        testRebuildIntoUsesLocalizedEmptyStates();
        testRebuildIntoDoesNotMutateNonPhongMaterial();
        testSceneInspectorDoesNotMutateNonPhongMaterial();
        testSessionRefreshKeepsSelectedNodeAndPreviewAlive();
        System.out.println("MaterialDockRefactorTests: ALL TESTS PASSED");
    }

    private static void testMaterialDockViewStateKeepsPreviewSettings() {
        MaterialDockViewState state = new MaterialDockViewState();
        if (!state.isLookdevPanelExpanded()) {
            throw new AssertionError("Lookdev panel má být ve výchozím stavu rozbalený");
        }
        state.setLookdevPanelExpanded(false);
        if (state.isLookdevPanelExpanded()) {
            throw new AssertionError("Lookdev panel musí jít sbalit");
        }

        MaterialPreviewRenderer.Settings settings = state.previewSettings();
        settings.previewMode = MaterialPreviewRenderer.PreviewMode.PATH;
        settings.backgroundMode = MaterialPreviewRenderer.BackgroundMode.CHECKER;
        settings.primitive = MaterialPreviewRenderer.PreviewPrimitive.ROUNDED_CUBE;
        if (state.previewSettings().previewMode != MaterialPreviewRenderer.PreviewMode.PATH) {
            throw new AssertionError("Preview mode se musí držet ve workspace view stavu");
        }
        if (state.previewSettings().backgroundMode != MaterialPreviewRenderer.BackgroundMode.CHECKER) {
            throw new AssertionError("Pozadí preview se musí držet ve workspace view stavu");
        }
    }

    private static void testRebuildIntoUsesLocalizedEmptyStates() {
        Engine engine = new Engine();
        JPanel host = new JPanel();

        EngineMaterialDock.rebuildInto(engine, host);
        assertContainsText(host, UiStrings.MaterialDock.WORKSPACE_TITLE);
        assertContainsText(host, UiStrings.MaterialDock.EMPTY_SELECT_OBJECT);

        Entity meshless = new Entity("BezMeshe", null, new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 32.0));
        engine.selectedEntity = meshless;
        host.removeAll();
        EngineMaterialDock.rebuildInto(engine, host);
        assertContainsText(host, UiStrings.MaterialDock.EMPTY_NO_MESH);
    }

    private static void testSessionRefreshKeepsSelectedNodeAndPreviewAlive() {
        Engine engine = new Engine();
        Entity entity = createMeshEntity();
        engine.selectedEntity = entity;
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        MaterialDockSession session = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());

        JComponent workspace = session.buildWorkspace();
        if (workspace == null) {
            throw new AssertionError("Workspace komponenta nesmí být null");
        }

        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (principled == null) {
            throw new AssertionError("Výchozí graf musí obsahovat Principled BSDF");
        }
        graph.setSelectedNodeId(principled.getId());
        session.refreshInspector();
        session.refreshSummary();
        session.requestPreviewRefresh();

        if (graph.getSelectedNodeId() != principled.getId()) {
            throw new AssertionError("Refresh inspektoru nesmí ztratit selected node");
        }
        if (session.previewPanel == null) {
            throw new AssertionError("Session musí vytvořit preview panel");
        }
    }

    private static void testRebuildIntoDoesNotMutateNonPhongMaterial() {
        Engine engine = new Engine();
        JPanel host = new JPanel();
        Entity entity = createMeshEntity();
        Material nonPhong = new Material(new Vec3(0.2, 0.4, 0.8));
        nonPhong.setName("Generic Material");
        entity.setMaterial(nonPhong);
        engine.selectedEntity = entity;

        EngineMaterialDock.rebuildInto(engine, host);

        if (entity.getMaterial() != nonPhong) {
            throw new AssertionError("Pouhé otevření material docku nesmí převádět materiál na Phong");
        }
        assertContainsText(host, "Phong");
    }

    private static void testSceneInspectorDoesNotMutateNonPhongMaterial() {
        Engine engine = new Engine();
        engine.sceneDetailsPanel = new JPanel();
        Entity entity = createMeshEntity();
        Material nonPhong = new Material(new Vec3(0.85, 0.85, 0.85));
        nonPhong.setName("Imported PBR Proxy");
        entity.setMaterial(nonPhong);

        EngineSceneInspector.buildEntityDetails(engine, entity);

        if (entity.getMaterial() != nonPhong) {
            throw new AssertionError("Pouhý výběr objektu v scene inspectoru nesmí měnit typ materiálu");
        }
    }

    private static Entity createMeshEntity() {
        Mesh mesh = new Mesh(
                "tri",
                new float[]{
                        -1.0f, -1.0f, 0.0f,
                        1.0f, -1.0f, 0.0f,
                        0.0f, 1.0f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2}
        );
        mesh.setUVs(new float[]{
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.5f, 1.0f
        });
        return new Entity("Materiálový objekt", mesh, new PhongMaterial(new Vec3(0.7, 0.7, 0.7), 32.0));
    }

    private static void assertContainsText(Container container, String expected) {
        if (!containsText(container, expected)) {
            throw new AssertionError("V komponentě chybí očekávaný text: " + expected);
        }
    }

    private static boolean containsText(Component component, String expected) {
        if (component instanceof JLabel label) {
            String text = label.getText();
            if (text != null && text.contains(expected)) {
                return true;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (containsText(child, expected)) {
                    return true;
                }
            }
        }
        return false;
    }
}
