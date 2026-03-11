import engine.io.ImportedScene;
import engine.io.ModelImporter;

/**
 * Tady držím ruční rychlý test pro externí import souborů.
 * Spustím to takto:
 *   java -cp <classpath> ExternalImportSmokeTest <file1> [file2 ...]
 */
public class ExternalImportSmokeTest {

    public static void main(String[] args) {
        if (args == null || args.length == 0) {
            System.out.println("ExternalImportSmokeTest: provide one or more file paths.");
            return;
        }
        ModelImporter importer = new ModelImporter();
        int totalEntries = 0;
        int totalTriangles = 0;
        for (String path : args) {
            ImportedScene scene = importer.importScene(path);
            int sceneTriangles = 0;
            for (ImportedScene.Entry entry : scene.getEntries()) {
                if (entry != null && entry.getMesh() != null) {
                    sceneTriangles += entry.getMesh().getTriangleCount();
                }
            }
            totalEntries += scene.size();
            totalTriangles += sceneTriangles;
            System.out.println("Imported: " + path);
            System.out.println("  entries=" + scene.size() + ", triangles=" + sceneTriangles);
        }
        System.out.println("ExternalImportSmokeTest done. totalEntries=" + totalEntries
                + ", totalTriangles=" + totalTriangles);
    }
}
