import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.PhongMaterial;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ObjMaterialImportTests {

    private static final double EPS = 1e-6;

    private ObjMaterialImportTests() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("obj-mtl-test");
        try {
            Path obj = dir.resolve("sample.obj");
            Path mtl = dir.resolve("sample.mtl");

            String mtlText = ""
                    + "newmtl TestMat\n"
                    + "Kd 0.20 0.40 0.60\n"
                    + "Ks 0.50 0.50 0.50\n"
                    + "Ns 64.0\n"
                    + "Ni 1.45\n"
                    + "d 0.75\n";
            Files.writeString(mtl, mtlText);

            String objText = ""
                    + "mtllib sample.mtl\n"
                    + "usemtl TestMat\n"
                    + "v 0 0 0\n"
                    + "v 1 0 0\n"
                    + "v 0 1 0\n"
                    + "vn 0 0 1\n"
                    + "vt 0 0\n"
                    + "vt 1 0\n"
                    + "vt 0 1\n"
                    + "f 1/1/1 2/2/1 3/3/1\n";
            Files.writeString(obj, objText);

            ImportedScene scene = new ModelImporter().importScene(obj.toString());
            if (scene.size() != 1) {
                throw new AssertionError("Expected one imported entry, got: " + scene.size());
            }
            ImportedScene.Entry entry = scene.getEntries().get(0);
            PhongMaterial mat = entry.getMaterial();
            if (mat == null) {
                throw new AssertionError("Expected OBJ importer to resolve material from MTL.");
            }
            assertNear(0.20, mat.getDiffuseColor().x, "Diffuse R");
            assertNear(0.40, mat.getDiffuseColor().y, "Diffuse G");
            assertNear(0.60, mat.getDiffuseColor().z, "Diffuse B");
            assertNear(64.0, mat.getShininess(), "Shininess");
            assertNear(0.75, mat.getOpacity(), "Opacity");
            assertNear(1.45, mat.getRefractiveIndex(), "IOR");
            System.out.println("ObjMaterialImportTests: ALL TESTS PASSED");
        } finally {
            try {
                Files.walk(dir)
                        .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (Exception ignored) {
                            }
                        });
            } catch (Exception ignored) {
            }
        }
    }

    private static void assertNear(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPS) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }
}
