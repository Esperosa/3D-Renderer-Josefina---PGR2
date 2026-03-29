import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.material.MaterialGraphEvaluator;
import engine.material.PhongMaterial;
import engine.math.Vec3;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class GltfMaterialExtensionImportTests {

    private GltfMaterialExtensionImportTests() {
    }

    public static void main(String[] args) throws Exception {
        testGltfBaseColorDoesNotFlipV();
        Path tempDir = Files.createTempDirectory("gltf-material-ext");
        try {
            Path png = tempDir.resolve("texture.png");
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xCC80A0FF);
            ImageIO.write(image, "png", png.toFile());

            float[] positions = new float[]{
                    -1.0f, -1.0f, 0.0f,
                    1.0f, -1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f
            };
            float[] normals = new float[]{
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f
            };
            float[] uv0 = new float[]{
                    0.0f, 0.0f,
                    1.0f, 0.0f,
                    0.5f, 1.0f
            };
            float[] uv1 = new float[]{
                    0.2f, 0.1f,
                    0.8f, 0.1f,
                    0.5f, 0.9f
            };
            short[] indices = new short[]{0, 1, 2};

            ByteBuffer buffer = ByteBuffer.allocate(126).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : positions) buffer.putFloat(value);
            for (float value : normals) buffer.putFloat(value);
            for (float value : uv0) buffer.putFloat(value);
            for (float value : uv1) buffer.putFloat(value);
            for (short index : indices) buffer.putShort(index);
            Files.write(tempDir.resolve("geom.bin"), buffer.array());

            String gltf = "{\n"
                    + "  \"asset\": {\"version\": \"2.0\", \"generator\": \"UnitTest\"},\n"
                    + "  \"extensionsUsed\": [\n"
                    + "    \"KHR_texture_transform\",\n"
                    + "    \"KHR_materials_transmission\",\n"
                    + "    \"KHR_materials_ior\",\n"
                    + "    \"KHR_materials_emissive_strength\",\n"
                    + "    \"KHR_materials_clearcoat\",\n"
                    + "    \"KHR_materials_specular\",\n"
                    + "    \"KHR_materials_sheen\"\n"
                    + "  ],\n"
                    + "  \"buffers\": [{\"uri\": \"geom.bin\", \"byteLength\": 126}],\n"
                    + "  \"bufferViews\": [\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 0, \"byteLength\": 36},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 36, \"byteLength\": 36},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 72, \"byteLength\": 24},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 96, \"byteLength\": 24},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 120, \"byteLength\": 6}\n"
                    + "  ],\n"
                    + "  \"accessors\": [\n"
                    + "    {\"bufferView\": 0, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\"},\n"
                    + "    {\"bufferView\": 1, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\"},\n"
                    + "    {\"bufferView\": 2, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC2\"},\n"
                    + "    {\"bufferView\": 3, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC2\"},\n"
                    + "    {\"bufferView\": 4, \"componentType\": 5123, \"count\": 3, \"type\": \"SCALAR\"}\n"
                    + "  ],\n"
                    + "  \"images\": [{\"uri\": \"texture.png\"}],\n"
                    + "  \"samplers\": [{\"magFilter\": 9729, \"minFilter\": 9987}],\n"
                    + "  \"textures\": [{\"source\": 0, \"sampler\": 0}],\n"
                    + "  \"materials\": [{\n"
                    + "    \"name\": \"ExtMat\",\n"
                    + "    \"doubleSided\": true,\n"
                    + "    \"alphaMode\": \"BLEND\",\n"
                    + "    \"alphaCutoff\": 0.33,\n"
                    + "    \"pbrMetallicRoughness\": {\n"
                    + "      \"baseColorFactor\": [0.7, 0.8, 0.9, 0.6],\n"
                    + "      \"metallicFactor\": 0.5,\n"
                    + "      \"roughnessFactor\": 0.25,\n"
                    + "      \"baseColorTexture\": {\n"
                    + "        \"index\": 0,\n"
                    + "        \"texCoord\": 1,\n"
                    + "        \"extensions\": {\n"
                    + "          \"KHR_texture_transform\": {\n"
                    + "            \"offset\": [0.1, 0.2],\n"
                    + "            \"scale\": [0.8, 0.7],\n"
                    + "            \"rotation\": 0.5,\n"
                    + "            \"texCoord\": 1\n"
                    + "          }\n"
                    + "        }\n"
                    + "      },\n"
                    + "      \"metallicRoughnessTexture\": {\"index\": 0}\n"
                    + "    },\n"
                    + "    \"normalTexture\": {\"index\": 0, \"scale\": 0.6},\n"
                    + "    \"emissiveFactor\": [0.9, 0.5, 0.2],\n"
                    + "    \"emissiveTexture\": {\"index\": 0, \"texCoord\": 1},\n"
                    + "    \"extensions\": {\n"
                    + "      \"KHR_materials_transmission\": {\"transmissionFactor\": 0.72},\n"
                    + "      \"KHR_materials_ior\": {\"ior\": 1.45},\n"
                    + "      \"KHR_materials_emissive_strength\": {\"emissiveStrength\": 3.0},\n"
                    + "      \"KHR_materials_clearcoat\": {\"clearcoatFactor\": 0.65, \"clearcoatRoughnessFactor\": 0.12},\n"
                    + "      \"KHR_materials_specular\": {\"specularFactor\": 0.84, \"specularColorFactor\": [0.7, 0.8, 0.9]},\n"
                    + "      \"KHR_materials_sheen\": {\"sheenColorFactor\": [0.2, 0.3, 0.4], \"sheenRoughnessFactor\": 0.55}\n"
                    + "    }\n"
                    + "  }],\n"
                    + "  \"meshes\": [{\"primitives\": [{\n"
                    + "    \"attributes\": {\"POSITION\": 0, \"NORMAL\": 1, \"TEXCOORD_0\": 2, \"TEXCOORD_1\": 3},\n"
                    + "    \"indices\": 4,\n"
                    + "    \"material\": 0\n"
                    + "  }]}],\n"
                    + "  \"nodes\": [{\"mesh\": 0, \"name\": \"Triangle\"}],\n"
                    + "  \"scenes\": [{\"nodes\": [0]}],\n"
                    + "  \"scene\": 0\n"
                    + "}\n";
            Path gltfPath = tempDir.resolve("scene.gltf");
            Files.writeString(gltfPath, gltf);

            ImportedScene scene = new ModelImporter().importScene(gltfPath.toString());
            assertTrue(scene.size() == 1, "Expected one imported entry");
            ImportedScene.Entry entry = scene.getEntries().get(0);
            assertTrue(entry.getMesh().getUVs() != null, "Expected UV0 data");
            assertTrue(entry.getMesh().getUV2s() != null, "Expected UV1 data");

            PhongMaterial mat = entry.getMaterial();
            assertTrue(mat != null, "Expected imported material");
            assertTrue(!mat.getDiffuseMap().isFlipV(), "glTF textures should not flip V");
            assertTrue(mat.isDoubleSided(), "doubleSided should import");
            assertTrue(mat.getAlphaMode() == PhongMaterial.AlphaMode.BLEND, "alphaMode should import");
            assertNear(0.33, mat.getAlphaCutoff(), 1e-6, "alphaCutoff");
            assertNear(0.72, mat.getTransmission(), 1e-6, "transmission");
            assertNear(1.45, mat.getRefractiveIndex(), 1e-6, "IOR");
            assertNear(3.0, mat.getEmissionStrength(), 1e-6, "emissiveStrength");
            assertNear(0.65, mat.getClearcoatFactor(), 1e-6, "clearcoatFactor");
            assertNear(0.12, mat.getClearcoatRoughness(), 1e-6, "clearcoatRoughness");
            assertNear(0.84, mat.getSpecularFactor(), 1e-6, "specularFactor");
            assertNear(0.2, mat.getSheenColor().x, 1e-6, "sheenColor.x");
            assertNear(0.55, mat.getSheenRoughness(), 1e-6, "sheenRoughness");
            assertNear(0.6, mat.getNormalScale(), 1e-6, "normalScale");
            assertTrue(mat.getDiffuseMap().hasTexture(), "Expected base color texture");
            assertTrue(mat.getDiffuseMap().getTexCoord() == 1, "Expected base color to use UV1");
            assertNear(0.1, mat.getDiffuseMap().getOffsetU(), 1e-6, "texture offsetU");
            assertNear(0.2, mat.getDiffuseMap().getOffsetV(), 1e-6, "texture offsetV");
            assertNear(0.8, mat.getDiffuseMap().getScaleU(), 1e-6, "texture scaleU");
            assertNear(0.7, mat.getDiffuseMap().getScaleV(), 1e-6, "texture scaleV");
            assertNear(0.5, mat.getDiffuseMap().getRotation(), 1e-6, "texture rotation");
            assertTrue(mat.hasNormalTexture(), "Expected normal texture");
            assertTrue(mat.hasMetallicRoughnessTexture(), "Expected metallic-roughness texture");
            assertTrue(mat.hasEmissiveTexture(), "Expected emissive texture");
            assertTrue(mat.hasNodeGraph(), "Imported GLTF material should have an authoring graph immediately");
            assertTrue(mat.getShadingModel() == engine.material.Material.ShadingModel.TRANSMISSIVE,
                    "Expected transmissive shading model");

            MaterialGraphEvaluator.Result graph = MaterialGraphEvaluator.evaluate(
                    mat,
                    MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.2, 0.3, true, 0.5, 0.5)
            );
            assertNear(0.7 * (128.0 / 255.0), graph.baseColor.x, 1e-6, "graph baseColor.x");
            assertNear(0.8 * (160.0 / 255.0), graph.baseColor.y, 1e-6, "graph baseColor.y");
            assertNear(0.9, graph.baseColor.z, 1e-6, "graph baseColor.z");
            assertNear(0.6 * (204.0 / 255.0), graph.opacity, 1e-6, "graph opacity");
            assertNear(0.25 * (160.0 / 255.0), graph.roughness, 1e-6, "graph roughness");
            assertNear(0.5, graph.metallic, 1e-6, "graph metallic");
            assertNear(0.9 * (128.0 / 255.0), graph.emissionColor.x, 1e-6, "graph emission.x");
            assertNear(0.5 * (160.0 / 255.0), graph.emissionColor.y, 1e-6, "graph emission.y");
            assertNear(0.2, graph.emissionColor.z, 1e-6, "graph emission.z");
            assertNear(3.0, graph.emissionStrength, 1e-6, "graph emission strength");

            System.out.println("GltfMaterialExtensionImportTests: ALL TESTS PASSED");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void testGltfBaseColorDoesNotFlipV() throws Exception {
        Path tempDir = Files.createTempDirectory("gltf-v-orientation");
        try {
            Path png = tempDir.resolve("texture.png");
            BufferedImage image = new BufferedImage(1, 2, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xFFFF0000);
            image.setRGB(0, 1, 0xFF0000FF);
            ImageIO.write(image, "png", png.toFile());

            float[] positions = new float[]{
                    -1.0f, -1.0f, 0.0f,
                    1.0f, -1.0f, 0.0f,
                    0.0f, 1.0f, 0.0f
            };
            float[] normals = new float[]{
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f,
                    0.0f, 0.0f, 1.0f
            };
            float[] uv0 = new float[]{
                    0.5f, 0.25f,
                    0.5f, 0.25f,
                    0.5f, 0.25f
            };
            short[] indices = new short[]{0, 1, 2};

            ByteBuffer buffer = ByteBuffer.allocate(102).order(ByteOrder.LITTLE_ENDIAN);
            for (float value : positions) buffer.putFloat(value);
            for (float value : normals) buffer.putFloat(value);
            for (float value : uv0) buffer.putFloat(value);
            for (short index : indices) buffer.putShort(index);
            Files.write(tempDir.resolve("geom.bin"), buffer.array());

            String gltf = "{\n"
                    + "  \"asset\": {\"version\": \"2.0\", \"generator\": \"UnitTest\"},\n"
                    + "  \"buffers\": [{\"uri\": \"geom.bin\", \"byteLength\": 102}],\n"
                    + "  \"bufferViews\": [\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 0, \"byteLength\": 36},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 36, \"byteLength\": 36},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 72, \"byteLength\": 24},\n"
                    + "    {\"buffer\": 0, \"byteOffset\": 96, \"byteLength\": 6}\n"
                    + "  ],\n"
                    + "  \"accessors\": [\n"
                    + "    {\"bufferView\": 0, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\"},\n"
                    + "    {\"bufferView\": 1, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC3\"},\n"
                    + "    {\"bufferView\": 2, \"componentType\": 5126, \"count\": 3, \"type\": \"VEC2\"},\n"
                    + "    {\"bufferView\": 3, \"componentType\": 5123, \"count\": 3, \"type\": \"SCALAR\"}\n"
                    + "  ],\n"
                    + "  \"images\": [{\"uri\": \"texture.png\"}],\n"
                    + "  \"samplers\": [{\"magFilter\": 9728, \"minFilter\": 9728}],\n"
                    + "  \"textures\": [{\"source\": 0, \"sampler\": 0}],\n"
                    + "  \"materials\": [{\n"
                    + "    \"name\": \"FlipCheck\",\n"
                    + "    \"pbrMetallicRoughness\": {\n"
                    + "      \"baseColorFactor\": [1.0, 1.0, 1.0, 1.0],\n"
                    + "      \"baseColorTexture\": {\"index\": 0, \"texCoord\": 0}\n"
                    + "    }\n"
                    + "  }],\n"
                    + "  \"meshes\": [{\"primitives\": [{\n"
                    + "    \"attributes\": {\"POSITION\": 0, \"NORMAL\": 1, \"TEXCOORD_0\": 2},\n"
                    + "    \"indices\": 3,\n"
                    + "    \"material\": 0\n"
                    + "  }]}],\n"
                    + "  \"nodes\": [{\"mesh\": 0, \"name\": \"Triangle\"}],\n"
                    + "  \"scenes\": [{\"nodes\": [0]}],\n"
                    + "  \"scene\": 0\n"
                    + "}\n";
            Path gltfPath = tempDir.resolve("flip-test.gltf");
            Files.writeString(gltfPath, gltf);

            ImportedScene scene = new ModelImporter().importScene(gltfPath.toString());
            PhongMaterial material = scene.getEntries().get(0).getMaterial();
            assertTrue(material != null, "Expected imported material for flip test");
            assertTrue(!material.getDiffuseMap().isFlipV(), "glTF diffuse map should keep original V orientation");
            material.getOrCreateNodeGraph();

            MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                    material,
                    MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.25, false, 0.0, 0.0)
            );
            assertVecNear(new Vec3(1.0, 0.0, 0.0), result.baseColor, 1e-6, "glTF V orientation");
        } finally {
            deleteRecursively(tempDir);
        }
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

    private static void assertNear(double expected, double actual, double eps, String label) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertVecNear(Vec3 expected, Vec3 actual, double eps, String label) {
        assertNear(expected.x, actual.x, eps, label + ".x");
        assertNear(expected.y, actual.y, eps, label + ".y");
        assertNear(expected.z, actual.z, eps, label + ".z");
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
