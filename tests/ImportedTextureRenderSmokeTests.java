import engine.camera.PerspectiveCamera;
import engine.io.ImportedScene;
import engine.io.ModelImporter;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.raster.RasterRenderer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ImportedTextureRenderSmokeTests {

    private ImportedTextureRenderSmokeTests() {
    }

    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("imported-texture-render");
        try {
            Path gltfPath = writeTexturedQuadScene(dir);
            ImportedScene imported = new ModelImporter().importScene(gltfPath.toString());
            if (imported.size() != 1) {
                throw new AssertionError("Expected one imported entry, got " + imported.size());
            }
            ImportedScene.Entry importedEntry = imported.getEntries().get(0);
            if (importedEntry.getMesh().getUVs() == null) {
                throw new AssertionError("Expected imported mesh UVs");
            }
            if (importedEntry.getMaterial() == null || importedEntry.getMaterial().getDiffuseTexture() == null) {
                throw new AssertionError("Expected imported diffuse texture");
            }
            importedEntry.getMaterial().getOrCreateNodeGraph();
            assertRedDominant(importedEntry.getMaterial().getDiffuseTexture().sampleNearest(0.5, 0.5),
                    "Imported material");

            Scene scene = new Scene();
            scene.setAmbientColor(new Vec3(0.2, 0.2, 0.2));
            scene.setBackgroundColor(new Vec3(0.0, 0.0, 0.0));
            scene.addLight(new DirectionalLight(new Vec3(0.0, 0.0, -1.0), Vec3.ONE, 1.0));

            Entity entity = new Entity(importedEntry.getName(), importedEntry.getMesh(), importedEntry.getMaterial());
            scene.addEntity(entity);
            scene.update(0.0);

            PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
            camera.setPosition(new Vec3(0.0, 0.0, 3.0));
            camera.lookAt(Vec3.ZERO);

            assertRaster(scene, camera);
            assertRay(scene, camera);
            assertPath(scene, camera);
            System.out.println("ImportedTextureRenderSmokeTests: ALL TESTS PASSED");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void assertRaster(Scene scene, PerspectiveCamera camera) {
        RasterRenderer renderer = new RasterRenderer();
        FrameBuffer fb = new FrameBuffer(72, 72);
        renderer.init(72, 72);
        renderer.setParameter("unlitMode", false);
        renderer.setParameter("backfaceCulling", false);
        renderer.render(scene, camera, fb, 0.0);
        assertRedDominant(fb.getColor(36, 36), "Raster");
    }

    private static void assertRay(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(72, 72);
        renderer.init(72, 72);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("maxDepth", 3);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertRedDominant(fb.getColor(36, 36), "RayTracer");
    }

    private static void assertPath(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(72, 72);
        renderer.init(72, 72);
        renderer.setParameter("samplesPerFrame", 8);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertRedDominant(fb.getColor(36, 36), "PathTracer");
    }

    private static Path writeTexturedQuadScene(Path dir) throws Exception {
        Path texture = dir.resolve("albedo.png");
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF3030);
        ImageIO.write(image, "png", texture.toFile());

        float[] positions = new float[]{
                -1.0f, -1.0f, 0.0f,
                1.0f, -1.0f, 0.0f,
                1.0f, 1.0f, 0.0f,
                -1.0f, 1.0f, 0.0f
        };
        float[] normals = new float[]{
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f,
                0.0f, 0.0f, 1.0f
        };
        float[] uvs = new float[]{
                0.0f, 0.0f,
                1.0f, 0.0f,
                1.0f, 1.0f,
                0.0f, 1.0f
        };
        short[] indices = new short[]{0, 1, 2, 0, 2, 3};

        ByteBuffer buffer = ByteBuffer.allocate(140).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : positions) buffer.putFloat(value);
        for (float value : normals) buffer.putFloat(value);
        for (float value : uvs) buffer.putFloat(value);
        for (short index : indices) buffer.putShort(index);
        Files.write(dir.resolve("geom.bin"), buffer.array());

        String gltf = "{\n"
                + "  \"asset\": {\"version\": \"2.0\", \"generator\": \"ImportedTextureRenderSmokeTests\"},\n"
                + "  \"buffers\": [{\"uri\": \"geom.bin\", \"byteLength\": 140}],\n"
                + "  \"bufferViews\": [\n"
                + "    {\"buffer\": 0, \"byteOffset\": 0, \"byteLength\": 48},\n"
                + "    {\"buffer\": 0, \"byteOffset\": 48, \"byteLength\": 48},\n"
                + "    {\"buffer\": 0, \"byteOffset\": 96, \"byteLength\": 32},\n"
                + "    {\"buffer\": 0, \"byteOffset\": 128, \"byteLength\": 12}\n"
                + "  ],\n"
                + "  \"accessors\": [\n"
                + "    {\"bufferView\": 0, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC3\"},\n"
                + "    {\"bufferView\": 1, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC3\"},\n"
                + "    {\"bufferView\": 2, \"componentType\": 5126, \"count\": 4, \"type\": \"VEC2\"},\n"
                + "    {\"bufferView\": 3, \"componentType\": 5123, \"count\": 6, \"type\": \"SCALAR\"}\n"
                + "  ],\n"
                + "  \"images\": [{\"uri\": \"albedo.png\"}],\n"
                + "  \"textures\": [{\"source\": 0}],\n"
                + "  \"materials\": [{\n"
                + "    \"name\": \"QuadMat\",\n"
                + "    \"doubleSided\": true,\n"
                + "    \"pbrMetallicRoughness\": {\n"
                + "      \"baseColorFactor\": [1.0, 1.0, 1.0, 1.0],\n"
                + "      \"metallicFactor\": 0.0,\n"
                + "      \"roughnessFactor\": 1.0,\n"
                + "      \"baseColorTexture\": {\"index\": 0}\n"
                + "    }\n"
                + "  }],\n"
                + "  \"meshes\": [{\"primitives\": [{\n"
                + "    \"attributes\": {\"POSITION\": 0, \"NORMAL\": 1, \"TEXCOORD_0\": 2},\n"
                + "    \"indices\": 3,\n"
                + "    \"material\": 0\n"
                + "  }]}],\n"
                + "  \"nodes\": [{\"mesh\": 0, \"name\": \"Quad\"}],\n"
                + "  \"scenes\": [{\"nodes\": [0]}],\n"
                + "  \"scene\": 0\n"
                + "}\n";
        Path gltfPath = dir.resolve("scene.gltf");
        Files.writeString(gltfPath, gltf);
        return gltfPath;
    }

    private static void assertRedDominant(int argb, String label) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r < 40 || r <= g + 15 || r <= b + 15) {
            throw new AssertionError(label + " did not render imported texture color."
                    + " argb=0x" + Integer.toHexString(argb));
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
}
