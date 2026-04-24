import engine.core.FeatureMaturityNotes;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialSupportMatrix;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ProjectPresentationTests {

    private ProjectPresentationTests() {
    }

    public static void main(String[] args) throws Exception {
        assertExists("README.md");
        assertExists("docs/architecture.md");
        assertExists("docs/rendering.md");
        assertExists("docs/materials.md");
        assertExists("docs/output.md");
        assertExists("docs/readme-assets/banner.svg");
        assertExists("docs/readme-assets/renderer-overview.svg");
        assertExists("docs/readme-assets/workflow-overview.svg");
        assertExists("tests/run-project-metrics.ps1");
        assertExists("tests/run-project-metrics.sh");

        assertSupport(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        assertSupport(MaterialNodeGraph.NodeType.GLASS_BSDF);
        assertSupport(MaterialNodeGraph.NodeType.TRANSPARENT_BSDF);
        assertSupport(MaterialNodeGraph.NodeType.VOLUME_MEDIUM);
        assertSupport(MaterialNodeGraph.NodeType.IMAGE_TEXTURE);

        assertNonBlank(FeatureMaturityNotes.MATERIAL_GRAPH_SOURCE_OF_TRUTH, "Missing material maturity note");
        assertNonBlank(FeatureMaturityNotes.SPRAY_PARTICLE_SYSTEM, "Missing spray maturity note");
        assertNonBlank(FeatureMaturityNotes.GALAXY_EXPERIMENTAL, "Missing galaxy maturity note");
        System.out.println("ProjectPresentationTests: ALL TESTS PASSED");
    }

    private static void assertSupport(MaterialNodeGraph.NodeType type) {
        MaterialSupportMatrix.NodeSupport support = MaterialSupportMatrix.forNode(type);
        if (support == null) {
            throw new AssertionError("Missing support entry for node type " + type);
        }
        if (support.raster() == null || support.ray() == null || support.path() == null) {
            throw new AssertionError("Incomplete renderer support entry for node type " + type);
        }
        if (support.compactSummary().isBlank()) {
            throw new AssertionError("Empty compact summary for node type " + type);
        }
    }

    private static void assertExists(String relativePath) throws Exception {
        Path path = Path.of(relativePath);
        if (!Files.exists(path)) {
            throw new AssertionError("Expected file is missing: " + relativePath);
        }
    }

    private static void assertNonBlank(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new AssertionError(message);
        }
    }
}
