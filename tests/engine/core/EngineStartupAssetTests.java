package engine.core;

import engine.io.ImportedScene;
import engine.io.ModelImporter;

public final class EngineStartupAssetTests {

    private EngineStartupAssetTests() {
    }

    public static void main(String[] args) {
        testStartupAssetIsInternalProjectAsset();
        testStartupAssetImports();
        System.out.println("EngineStartupAssetTests: ALL TESTS PASSED");
    }

    private static void testStartupAssetIsInternalProjectAsset() {
        String path = EngineSceneBootstrap.STARTUP_MODEL_PATH;
        if (path == null || path.isBlank()) {
            throw new AssertionError("Startup asset path is blank");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.contains(":/") || normalized.startsWith("//")) {
            throw new AssertionError("Startup asset must be project-relative, got: " + path);
        }
        if (!normalized.startsWith("assets/")) {
            throw new AssertionError("Startup asset should live under assets/, got: " + path);
        }
    }

    private static void testStartupAssetImports() {
        ImportedScene imported = new ModelImporter().importScene(EngineSceneBootstrap.STARTUP_MODEL_PATH);
        if (imported == null || imported.size() <= 0) {
            throw new AssertionError("Startup asset import produced no scene entries");
        }
        if (imported.getEntries().get(0) == null || imported.getEntries().get(0).getMesh() == null) {
            throw new AssertionError("Startup asset first entry has no mesh");
        }
    }
}
