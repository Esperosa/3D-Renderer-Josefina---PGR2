package engine.core;

import engine.io.FileUtil;

public final class WindowIconAssetTests {

    private WindowIconAssetTests() {
    }

    public static void main(String[] args) {
        testIconPathsAreProjectRelative();
        testBundledIconAssetsExist();
        System.out.println("WindowIconAssetTests: ALL TESTS PASSED");
    }

    private static void testIconPathsAreProjectRelative() {
        assertProjectRelative(Window.APP_ICON_PNG_PATH, "PNG");
        assertProjectRelative(Window.APP_ICON_ICO_PATH, "ICO");
    }

    private static void testBundledIconAssetsExist() {
        if (!FileUtil.exists(Window.APP_ICON_PNG_PATH)) {
            throw new AssertionError("Bundled PNG icon missing: " + Window.APP_ICON_PNG_PATH);
        }
        if (!FileUtil.exists(Window.APP_ICON_ICO_PATH)) {
            throw new AssertionError("Bundled ICO icon missing: " + Window.APP_ICON_ICO_PATH);
        }
    }

    private static void assertProjectRelative(String path, String label) {
        if (path == null || path.isBlank()) {
            throw new AssertionError(label + " icon path is blank");
        }
        String normalized = path.replace('\\', '/');
        if (normalized.contains(":/") || normalized.startsWith("//")) {
            throw new AssertionError(label + " icon must be project-relative, got: " + path);
        }
        if (!normalized.startsWith("assets/")) {
            throw new AssertionError(label + " icon should live under assets/, got: " + path);
        }
    }
}
