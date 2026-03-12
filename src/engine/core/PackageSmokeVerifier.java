package engine.core;

import engine.geometry.Mesh;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.io.ObjLoader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Tady ověřím, že zabalená aplikace opravdu obsahuje minimální runtime assety,
 * které potřebuji pro start okna a výchozí scénu.
 */
public final class PackageSmokeVerifier {
    private static final String ICON_PNG_PATH = "assets/icons/IcoUni.png";
    private static final String ICON_ICO_PATH = "assets/icons/IcoUni.ico";
    private static final String DEFAULT_OBJ_PATH = "assets/models/cube.obj";

    private PackageSmokeVerifier() {
    }

    public static void run() {
        verifyExistingFile(ICON_PNG_PATH);
        verifyExistingFile(ICON_ICO_PATH);
        verifyExistingFile(DEFAULT_OBJ_PATH);
        verifyExistingFile(EngineSceneBootstrap.STARTUP_MODEL_PATH);
        verifyIcon();
        verifyDefaultObj();
        verifyStartupModel();
        System.out.println("PackageSmokeVerifier: ALL CHECKS PASSED");
    }

    private static void verifyExistingFile(String path) {
        File file = new File(path);
        if (!file.isFile()) {
            throw new IllegalStateException("Missing packaged file: " + file.getPath());
        }
        if (file.length() <= 0L) {
            throw new IllegalStateException("Packaged file is empty: " + file.getPath());
        }
    }

    private static void verifyIcon() {
        try {
            BufferedImage image = ImageIO.read(new File(ICON_PNG_PATH));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalStateException("Packaged icon PNG is unreadable: " + ICON_PNG_PATH);
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read packaged icon PNG: " + ICON_PNG_PATH, ex);
        }
    }

    private static void verifyDefaultObj() {
        Mesh mesh = new ObjLoader().load(DEFAULT_OBJ_PATH);
        if (mesh == null || mesh.getTriangleCount() <= 0) {
            throw new IllegalStateException("Packaged OBJ fallback did not load triangles: " + DEFAULT_OBJ_PATH);
        }
    }

    private static void verifyStartupModel() {
        ImportedScene imported = new ModelImporter().importScene(EngineSceneBootstrap.STARTUP_MODEL_PATH);
        if (imported == null || imported.getEntries() == null || imported.getEntries().isEmpty()) {
            throw new IllegalStateException("Packaged startup scene is empty: " + EngineSceneBootstrap.STARTUP_MODEL_PATH);
        }
    }
}
