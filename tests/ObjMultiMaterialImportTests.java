import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.PhongMaterial;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ObjMultiMaterialImportTests {

    private ObjMultiMaterialImportTests() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("obj-multi-material");
        try {
            Path redTexture = dir.resolve("red diffuse.png");
            Path blueTexture = dir.resolve("blue.png");
            writeSolidPng(redTexture, 0xFFFF2020);
            writeSolidPng(blueTexture, 0xFF2040FF);

            Path mtl = dir.resolve("sample.mtl");
            Files.writeString(mtl, ""
                    + "newmtl RedMat\n"
                    + "Kd 1.0 1.0 1.0\n"
                    + "map_Kd -s 1 1 1 \"red diffuse.png\"\n"
                    + "newmtl BlueMat\n"
                    + "Kd 1.0 1.0 1.0\n"
                    + "map_Kd blue.png\n");

            Path obj = dir.resolve("sample.obj");
            Files.writeString(obj, ""
                    + "mtllib sample.mtl\n"
                    + "o Left\n"
                    + "usemtl RedMat\n"
                    + "v -2 0 0\n"
                    + "v -1 0 0\n"
                    + "v -2 1 0\n"
                    + "vt 0 0\n"
                    + "vt 1 0\n"
                    + "vt 0 1\n"
                    + "vn 0 0 1\n"
                    + "f 1/1/1 2/2/1 3/3/1\n"
                    + "o Right\n"
                    + "usemtl BlueMat\n"
                    + "v 1 0 0\n"
                    + "v 2 0 0\n"
                    + "v 1 1 0\n"
                    + "vt 0 0\n"
                    + "vt 1 0\n"
                    + "vt 0 1\n"
                    + "f 4/4/1 5/5/1 6/6/1\n");

            ImportedScene scene = new ModelImporter().importScene(obj.toString());
            assertTrue(scene.size() == 2, "Expected OBJ import to keep two material parts");

            ImportedScene.Entry first = scene.getEntries().get(0);
            ImportedScene.Entry second = scene.getEntries().get(1);
            assertMaterial(first.getMaterial(), "RedMat", 0xFFFF2020);
            assertMaterial(second.getMaterial(), "BlueMat", 0xFF2040FF);

            System.out.println("ObjMultiMaterialImportTests: ALL TESTS PASSED");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void assertMaterial(PhongMaterial material, String expectedName, int expectedTexel) {
        assertTrue(material != null, "Expected imported material");
        assertTrue(expectedName.equals(material.getName()),
                "Expected material name " + expectedName + ", got " + material.getName());
        assertTrue(material.getDiffuseTexture() != null, "Expected diffuse texture on " + expectedName);
        int actual = material.getDiffuseTexture().sampleNearest(0.5, 0.5);
        if ((actual & 0x00FFFFFF) != (expectedTexel & 0x00FFFFFF)) {
            throw new AssertionError("Unexpected texel for " + expectedName
                    + " expected=0x" + Integer.toHexString(expectedTexel)
                    + " actual=0x" + Integer.toHexString(actual));
        }
    }

    private static void writeSolidPng(Path path, int argb) throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ImageIO.write(image, "png", path.toFile());
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        Files.walk(path)
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
