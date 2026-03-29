package engine.core;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

import engine.geometry.Mesh;
import engine.material.Material;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialPreviewRenderer;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.physics.PhysicsWorld;
import engine.render.Texture;
import engine.scene.Entity;
import engine.ui.UiStrings;

public final class MaterialDockRefactorTests {

    private MaterialDockRefactorTests() {
    }

    public static void main(String[] args) {
        testMaterialDockViewStateKeepsPreviewSettings();
        testRebuildIntoUsesLocalizedEmptyStates();
        testWorkspaceModesShapeDockLayout();
        testWholeBottomDockDetachesAsSingleWorkspace();
        testMaterialWorkspaceRemovesTimelineShortcutDupes();
        testRebuildIntoDoesNotAttachGraphToPhongSelection();
        testRebuildIntoDoesNotMutateNonPhongMaterial();
        testStartupJosefinaSelectionDoesNotAttachGraph();
        testSceneInspectorDoesNotAttachGraphToPhongSelection();
        testSceneInspectorDoesNotMutateNonPhongMaterial();
        testSessionRefreshKeepsSelectedNodeAndPreviewAlive();
        testSessionAttachesGraphOnlyAfterEdit();
        testAutoLayoutGraphProducesOrderedColumns();
        testCanvasRendersTexturedNodePreview();
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
        state.setWorkspaceMode(MaterialDockViewState.WorkspaceMode.GRAPH_FOCUS);
        state.setInspectorWidth(612);
        if (state.workspaceMode() != MaterialDockViewState.WorkspaceMode.GRAPH_FOCUS) {
            throw new AssertionError("Workspace mód se musí držet ve view stavu");
        }
        if (state.inspectorWidth() != 612) {
            throw new AssertionError("Šířka inspektoru se musí držet ve view stavu");
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

        if (material.hasNodeGraph()) {
            throw new AssertionError("Pouhé otevření material dock session nesmí připojit node graph k materiálu");
        }

        MaterialNodeGraph graph = session.graph;
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

    private static void testWorkspaceModesShapeDockLayout() {
        Engine engine = new Engine();
        Entity entity = createMeshEntity();
        engine.selectedEntity = entity;
        PhongMaterial material = (PhongMaterial) entity.getMaterial();

        engine.materialDockViewState.setWorkspaceMode(MaterialDockViewState.WorkspaceMode.GRAPH_FOCUS);
        MaterialDockSession graphSession = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        graphSession.buildWorkspace();
        if (graphSession.previewPanel != null) {
            throw new AssertionError("Graph focus nemá vytvářet lookdev preview panel");
        }
        if (graphSession.inspectorScroll == null) {
            throw new AssertionError("Graph focus má nechat inspektor dostupný");
        }

        engine.materialDockViewState.setWorkspaceMode(MaterialDockViewState.WorkspaceMode.LOOKDEV_FOCUS);
        MaterialDockSession lookdevSession = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        lookdevSession.buildWorkspace();
        if (lookdevSession.previewPanel == null) {
            throw new AssertionError("Lookdev focus musí mít preview panel");
        }
        if (lookdevSession.inspectorScroll != null) {
            throw new AssertionError("Lookdev focus má skrýt boční inspektor");
        }

        engine.materialDockViewState.setWorkspaceMode(MaterialDockViewState.WorkspaceMode.BALANCED);
    }

    private static void testWholeBottomDockDetachesAsSingleWorkspace() {
        if (GraphicsEnvironment.isHeadless()) {
            return;
        }
        Engine engine = new Engine();
        Window window = new Window("Bottom dock test", 1280, 720);
        try {
            engine.window = window;
            EngineBottomDock.setup(engine);

            JPanel mainHost = window.getTimelinePanel();
            if (!mainHost.isVisible()) {
                throw new AssertionError("Bottom dock musí být ve výchozím stavu viditelný v hlavním okně");
            }
            if (mainHost.getComponentCount() != 1 || engine.bottomDockRootPanel == null) {
                throw new AssertionError("Bottom dock musí být připojený jako jeden kořenový workspace");
            }
            if (engine.bottomDockRootPanel.getParent() != mainHost) {
                throw new AssertionError("Kořen bottom docku musí být hostovaný v hlavním dock panelu");
            }

            EngineBottomDock.detach(engine);

            if (mainHost.isVisible()) {
                throw new AssertionError("Po odpojení musí spodní dock z hlavního UI zmizet");
            }
            if (mainHost.getComponentCount() != 0) {
                throw new AssertionError("Po odpojení nesmí v hlavním host panelu zůstat kopie bottom docku");
            }
            if (!EngineBottomDock.isDetached(engine) || engine.detachedBottomDockWindow == null) {
                throw new AssertionError("Odpojení musí vytvořit samostatné okno pro celý bottom dock");
            }
            if (engine.bottomDockRootPanel.getParent() != engine.detachedBottomDockWindow.hostPanel()) {
                throw new AssertionError("Odpojené okno musí hostovat celý bottom dock root");
            }

            EngineBottomDock.showWorkspace(engine, Engine.BottomDockWorkspace.MATERIAL);
            if (engine.bottomDockWorkspace != Engine.BottomDockWorkspace.MATERIAL) {
                throw new AssertionError("Odpojený bottom dock musí dál přepínat workspace přes stejné taby");
            }

            EngineBottomDock.attach(engine);

            if (EngineBottomDock.isDetached(engine)) {
                throw new AssertionError("Vrácení do docku musí zavřít detached okno");
            }
            if (!mainHost.isVisible() || mainHost.getComponentCount() != 1) {
                throw new AssertionError("Po návratu musí být bottom dock znovu jediným obsahem hlavního host panelu");
            }
            if (engine.bottomDockRootPanel.getParent() != mainHost) {
                throw new AssertionError("Po návratu musí být stejný root panel připojen zpět do hlavního okna");
            }
        } finally {
            EngineBottomDock.disposeDetached(engine);
            window.dispose();
        }
    }

    private static void testMaterialWorkspaceRemovesTimelineShortcutDupes() {
        Engine engine = new Engine();
        JPanel host = new JPanel();

        EngineMaterialDock.rebuildInto(engine, host);
        if (containsText(host, UiStrings.Common.BACK_TO_TIMELINE)) {
            throw new AssertionError("Empty state materiálového workspace nemá duplikovat přepnutí na timeline");
        }

        Entity entity = createMeshEntity();
        engine.selectedEntity = entity;
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        material.setNodeGraph(MaterialGraphAuthoring.createAuthoringGraphFromMaterial(material));
        MaterialDockSession session = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        session.buildWorkspace();
        MaterialNodeGraph.Node output = session.graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        session.graph.setSelectedNodeId(output == null ? -1 : output.getId());
        session.refreshInspector();

        if (session.inspectorPanel != null && containsText(session.inspectorPanel, UiStrings.Common.BACK_TO_TIMELINE)) {
            throw new AssertionError("Output inspector nemá duplikovat timeline přepínač uvnitř nodového inspektoru");
        }
    }

    private static void testSessionAttachesGraphOnlyAfterEdit() {
        Engine engine = new Engine();
        Entity entity = createMeshEntity();
        engine.selectedEntity = entity;
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        MaterialDockSession session = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        session.buildWorkspace();

        if (material.hasNodeGraph()) {
            throw new AssertionError("Pouhé otevření session nesmí měnit shading zdroj materiálu");
        }

        MaterialNodeGraph.Node principled = session.graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (principled == null) {
            throw new AssertionError("Pracovní graf musí obsahovat Principled BSDF");
        }

        principled.setNumber("roughness", 0.19);
        session.noteMaterialHistoryLabel("Test attach grafu");
        session.markStructureChanged();

        if (!material.hasNodeGraph()) {
            throw new AssertionError("Node graph se musí připojit až při první skutečné úpravě");
        }

        MaterialNodeGraph.Node persisted = material.getNodeGraph().findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (persisted == null || Math.abs(persisted.getNumber("roughness", 0.0) - 0.19) > 1e-9) {
            throw new AssertionError("První edit musí propsat pracovní graf do materiálu");
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

    private static void testRebuildIntoDoesNotAttachGraphToPhongSelection() {
        Engine engine = new Engine();
        JPanel host = new JPanel();
        Entity entity = createMeshEntity();
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        engine.selectedEntity = entity;

        if (material.hasNodeGraph()) {
            throw new AssertionError("Výchozí testovací materiál nesmí mít node graph před rebuildem");
        }

        EngineMaterialDock.rebuildInto(engine, host);

        if (material.hasNodeGraph()) {
            throw new AssertionError("Pouhý výběr Phong materiálu nesmí přidat node graph a změnit shading");
        }
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

    private static void testStartupJosefinaSelectionDoesNotAttachGraph() {
        Engine engine = new Engine();
        engine.physicsWorld = new PhysicsWorld();
        engine.scene = EngineSceneBootstrap.createDefaultScene(engine);
        engine.sceneDetailsPanel = new JPanel();
        JPanel host = new JPanel();

        Entity josefina = engine.demoEntity;
        if (josefina == null) {
            throw new AssertionError("Startup scéna musí vytvořit demo entitu Josefína");
        }
        if (!(josefina.getMaterial() instanceof PhongMaterial material)) {
            throw new AssertionError("Startup Josefína musí používat Phong materiál");
        }
        if (!material.hasNodeGraph()) {
            throw new AssertionError("Startup Josefína musí mít authoring graph už po načtení scény");
        }
        long signatureBefore = material.getNodeGraph().signature();
        int nodesBefore = material.getNodeGraph().getNodes().size();
        int linksBefore = material.getNodeGraph().getLinks().size();

        engine.selectedEntity = josefina;
        EngineSceneInspector.buildEntityDetails(engine, josefina);
        EngineMaterialDock.rebuildInto(engine, host);

        if (!material.hasNodeGraph()) {
            throw new AssertionError("Pouhý výběr startup modelu nesmí graph ztratit");
        }
        if (material.getNodeGraph().signature() != signatureBefore
                || material.getNodeGraph().getNodes().size() != nodesBefore
                || material.getNodeGraph().getLinks().size() != linksBefore) {
            throw new AssertionError("Pouhý výběr startup modelu Josefína nesmí mutovat už inicializovaný graph");
        }
    }

    private static void testSceneInspectorDoesNotAttachGraphToPhongSelection() {
        Engine engine = new Engine();
        engine.sceneDetailsPanel = new JPanel();
        Entity entity = createMeshEntity();
        PhongMaterial material = (PhongMaterial) entity.getMaterial();

        if (material.hasNodeGraph()) {
            throw new AssertionError("Výchozí testovací materiál nesmí mít graph před inspektorem");
        }

        EngineSceneInspector.buildEntityDetails(engine, entity);

        if (material.hasNodeGraph()) {
            throw new AssertionError("Pouhý výběr objektu v scene inspectoru nesmí připojit node graph");
        }
        assertContainsText(engine.sceneDetailsPanel, "Připraví se při první editaci");
    }

    private static void testCanvasRendersTexturedNodePreview() {
        Engine engine = new Engine();
        Entity entity = createMeshEntity();
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        material.getDiffuseMap().setTexture(new Texture(2, 2, new int[]{
                0xFFFF0000, 0xFF00FF00,
                0xFF0000FF, 0xFFFFFFFF
        }));
        material.setNodeGraph(MaterialGraphAuthoring.createAuthoringGraphFromMaterial(material));

        MaterialDockSession session = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        session.buildWorkspace();
        if (session.canvas == null) {
            throw new AssertionError("Material graph canvas musí existovat");
        }
        session.canvas.setSize(960, 640);
        BufferedImage image = new BufferedImage(960, 640, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = image.createGraphics();
        try {
            session.canvas.paint(g2);
        } finally {
            g2.dispose();
        }
    }

    private static void testAutoLayoutGraphProducesOrderedColumns() {
        Engine engine = new Engine();
        Entity entity = createMeshEntity();
        engine.selectedEntity = entity;
        PhongMaterial material = (PhongMaterial) entity.getMaterial();
        material.setNodeGraph(MaterialGraphAuthoring.createAuthoringGraphFromMaterial(material));
        for (MaterialNodeGraph.Node node : material.getNodeGraph().getNodes()) {
            node.setX(32.0);
            node.setY(32.0);
        }

        MaterialDockSession session = new MaterialDockSession(engine, new JPanel(), entity, material, new JLabel());
        session.buildWorkspace();
        session.canvas.autoLayoutAll();

        MaterialNodeGraph graph = material.getNodeGraph();
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        MaterialNodeGraph.Node baseColor = graph.findFirstNode(MaterialNodeGraph.NodeType.IMPORTED_BASE_COLOR);
        if (output == null || principled == null || baseColor == null) {
            throw new AssertionError("Testovací graph musí obsahovat output, principled i imported base color");
        }
        if (!(output.getX() > principled.getX() && principled.getX() > baseColor.getX())) {
            throw new AssertionError("Auto layout musí skládat imported uzly vlevo, shader uprostřed a output vpravo");
        }

        for (MaterialNodeGraph.Node a : graph.getNodes()) {
            Rectangle boundsA = new Rectangle((int) Math.round(a.getX()), (int) Math.round(a.getY()),
                    nodeBaseWidth(a), nodeBaseHeight(a));
            for (MaterialNodeGraph.Node b : graph.getNodes()) {
                if (a.getId() >= b.getId()) {
                    continue;
                }
                Rectangle boundsB = new Rectangle((int) Math.round(b.getX()), (int) Math.round(b.getY()),
                        nodeBaseWidth(b), nodeBaseHeight(b));
                if (boundsA.intersects(boundsB)) {
                    throw new AssertionError("Auto layout nesmí nechávat uzly přes sebe: " + a.getType() + " vs " + b.getType());
                }
            }
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

    private static int nodeBaseHeight(MaterialNodeGraph.Node node) {
        int rows = Math.max(3, Math.max(node.getType().inputs().length, node.getType().outputs().length));
        int detail = switch (node.getType()) {
            case IMPORTED_BASE_COLOR,
                 IMPORTED_METAL_ROUGHNESS,
                 IMPORTED_EMISSIVE,
                 IMPORTED_NORMAL,
                 IMAGE_TEXTURE -> 74 + 8 + 2 * 15 + 4;
            case RGB,
                 COLOR_RAMP -> 16 + 8 + 15 + 4;
            case OUTPUT_MATERIAL,
                 PRINCIPLED_BSDF,
                 GLASS_BSDF,
                 EMISSION_SHADER,
                 MIX_SHADER,
                 VOLUME_MEDIUM,
                 TEXTURE_COORDINATE,
                 MAPPING,
                 NORMAL_MAP,
                 TRANSPARENT_BSDF,
                 VALUE,
                 NOISE_TEXTURE,
                 MIX_COLOR,
                 MATH,
                 CLAMP,
                 MAP_RANGE -> 2 * 15 + 4;
            default -> 0;
        };
        return 40 + detail + 18 + rows * 22 + 10;
    }

    private static int nodeBaseWidth(MaterialNodeGraph.Node node) {
        return switch (node.getType()) {
            case PRINCIPLED_BSDF -> 292;
            case OUTPUT_MATERIAL -> 242;
            case VOLUME_MEDIUM -> 238;
            case MAPPING -> 244;
            case IMAGE_TEXTURE,
                 IMPORTED_BASE_COLOR,
                 IMPORTED_METAL_ROUGHNESS,
                 IMPORTED_EMISSIVE,
                 IMPORTED_NORMAL -> 272;
            case NORMAL_MAP -> 236;
            case TEXTURE_COORDINATE -> 228;
            case TRANSPARENT_BSDF -> 232;
            case COMBINE_RGB, SEPARATE_RGB -> 216;
            case MIX_COLOR, CLAMP, MAP_RANGE -> 224;
            case COLOR_RAMP, NOISE_TEXTURE -> 228;
            case MIX_SHADER, GLASS_BSDF -> 236;
            case EMISSION_SHADER -> 222;
            default -> 208;
        };
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
