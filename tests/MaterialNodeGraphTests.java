import engine.material.MaterialGraphEvaluator;
import engine.material.MaterialGraphAuthoring;
import engine.material.MaterialNodeGraph;
import engine.material.MaterialTextureSetImporter;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.Texture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class MaterialNodeGraphTests {

    private MaterialNodeGraphTests() {
    }

    public static void main(String[] args) {
        testImportedBaseColorNodeSamplesTexture();
        testImageTextureNodeLoadsFileAndFeedsChannels();
        testNoiseRampChainDrivesBaseColor();
        testMixShaderBlendsGlassAndEmission();
        testMapRangeClampChainControlsRoughness();
        testMultiplePrincipledNodesOwnIndependentDefaults();
        testDisconnectedPrincipledSocketsUseNodeDefaults();
        testImageTextureMappingChainAffectsSampling();
        testTextureSetImportAutoWiresPrincipledGraph();
        testNormalMapCompatibilityBridgeTracksGraph();
        testVolumeOnlyRoutingProducesPureVolumeState();
        testGraphCopiesWithMaterial();
        testGraphCopyDeepCopiesNodeColors();
        System.out.println("MaterialNodeGraphTests: ALL TESTS PASSED");
    }

    private static void testImportedBaseColorNodeSamplesTexture() {
        PhongMaterial material = new PhongMaterial(new Vec3(1.0, 1.0, 1.0), 32.0);
        material.setOpacity(1.0);
        material.getDiffuseMap().setTexture(new Texture(1, 1, new int[]{0x8040C020}));
        material.getDiffuseMap().setLinear(false);
        material.getOrCreateNodeGraph();

        MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.25, 0.75, false, 0.0, 0.0)
        );

        assertNear(64.0 / 255.0, result.baseColor.x, 1e-6, "Base color red mismatch");
        assertNear(192.0 / 255.0, result.baseColor.y, 1e-6, "Base color green mismatch");
        assertNear(32.0 / 255.0, result.baseColor.z, 1e-6, "Base color blue mismatch");
        assertNear(128.0 / 255.0, result.opacity, 1e-6, "Opacity mismatch");
    }

    private static void testNoiseRampChainDrivesBaseColor() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.5, 0.5, 0.5), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node bsdf = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (bsdf == null) {
            throw new AssertionError("Expected default Principled BSDF node");
        }
        graph.disconnectInput(bsdf.getId(), "base_color");

        MaterialNodeGraph.Node noise = graph.addNode(MaterialNodeGraph.NodeType.NOISE_TEXTURE, 60.0, 60.0);
        noise.setNumber("scale", 18.0);
        noise.setNumber("detail", 5.0);
        noise.setEnum("coordinate_source", MaterialNodeGraph.CoordinateSource.UV0.name());
        MaterialNodeGraph.Node ramp = graph.addNode(MaterialNodeGraph.NodeType.COLOR_RAMP, 120.0, 120.0);
        ramp.setColor("color_a", new Vec3(0.05, 0.07, 0.14));
        ramp.setColor("color_b", new Vec3(0.93, 0.68, 0.18));
        graph.connect(noise.getId(), "factor", ramp.getId(), "factor");
        graph.connect(ramp.getId(), "color", bsdf.getId(), "base_color");

        MaterialGraphEvaluator.Result first = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.12, 0.14, false, 0.0, 0.0)
        );
        MaterialGraphEvaluator.Result second = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.78, 0.21, false, 0.0, 0.0)
        );

        double delta = Math.abs(first.baseColor.x - second.baseColor.x)
                + Math.abs(first.baseColor.y - second.baseColor.y)
                + Math.abs(first.baseColor.z - second.baseColor.z);
        if (delta < 0.08) {
            throw new AssertionError("Noise + ColorRamp chain did not change the evaluated base color");
        }
    }

    private static void testImageTextureNodeLoadsFileAndFeedsChannels() {
        try {
            Path imagePath = Files.createTempFile("material-node-image", ".png");
            BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xA04080FF);
            ImageIO.write(image, "png", imagePath.toFile());

            PhongMaterial material = new PhongMaterial(new Vec3(0.2, 0.2, 0.2), 32.0);
            MaterialNodeGraph graph = material.getOrCreateNodeGraph();
            MaterialNodeGraph.Node bsdf = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
            if (bsdf == null) {
                throw new AssertionError("Expected default Principled BSDF node");
            }
            graph.disconnectInput(bsdf.getId(), "base_color");
            graph.disconnectInput(bsdf.getId(), "roughness");
            graph.disconnectInput(bsdf.getId(), "metallic");
            graph.disconnectInput(bsdf.getId(), "opacity");

            MaterialNodeGraph.Node imageNode = graph.addNode(MaterialNodeGraph.NodeType.IMAGE_TEXTURE, 80.0, 40.0);
            imageNode.setText("file_path", imagePath.toAbsolutePath().normalize().toString());
            imageNode.setEnum("color_space", MaterialNodeGraph.TextureColorSpace.DATA.name());
            imageNode.setNumber("linear", 0.0);
            graph.connect(imageNode.getId(), "color", bsdf.getId(), "base_color");
            graph.connect(imageNode.getId(), "green", bsdf.getId(), "roughness");
            graph.connect(imageNode.getId(), "blue", bsdf.getId(), "metallic");
            graph.connect(imageNode.getId(), "alpha", bsdf.getId(), "opacity");

            MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                    material,
                    MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
            );

            assertNear(64.0 / 255.0, result.baseColor.x, 1e-6, "Image texture red mismatch");
            assertNear(128.0 / 255.0, result.baseColor.y, 1e-6, "Image texture green mismatch");
            assertNear(1.0, result.baseColor.z, 1e-6, "Image texture blue mismatch");
            assertNear(128.0 / 255.0, result.roughness, 1e-6, "Image texture roughness channel mismatch");
            assertNear(1.0, result.metallic, 1e-6, "Image texture metallic channel mismatch");
            assertNear(160.0 / 255.0, result.opacity, 1e-6, "Image texture alpha mismatch");
        } catch (Exception ex) {
            throw new AssertionError("Image texture node test failed: " + ex.getMessage(), ex);
        }
    }

    private static void testVolumeOnlyRoutingProducesPureVolumeState() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.8, 0.8, 0.8), 32.0);
        material.setMediumColor(new Vec3(0.2, 0.45, 0.9));
        material.setDensity(0.65);
        material.setThickness(1.2);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        if (output == null) {
            throw new AssertionError("Expected default Material Output node");
        }
        graph.disconnectInput(output.getId(), "surface");

        MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );

        if (!result.isPureVolume()) {
            throw new AssertionError("Expected pure volume result after disconnecting surface");
        }
        if (result.transmission < 0.99) {
            throw new AssertionError("Pure volume should force near-full transmission");
        }
        if (result.opacity < 0.1) {
            throw new AssertionError("Pure volume should still produce visible preview opacity");
        }
    }

    private static void testMixShaderBlendsGlassAndEmission() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.4, 0.4, 0.4), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        MaterialNodeGraph.Node basePrincipled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (output == null || basePrincipled == null) {
            throw new AssertionError("Expected default output and principled nodes");
        }
        graph.disconnectInput(output.getId(), "surface");
        if (!graph.removeNode(basePrincipled.getId())) {
            throw new AssertionError("Expected default principled node to be removable");
        }

        MaterialNodeGraph.Node glass = graph.addNode(MaterialNodeGraph.NodeType.GLASS_BSDF, 220.0, 60.0);
        glass.setColor("color", new Vec3(0.7, 0.88, 1.0));
        glass.setNumber("ior", 1.52);
        MaterialNodeGraph.Node emission = graph.addNode(MaterialNodeGraph.NodeType.EMISSION_SHADER, 220.0, 260.0);
        emission.setColor("color", new Vec3(1.0, 0.4, 0.12));
        emission.setNumber("strength", 6.0);
        MaterialNodeGraph.Node factor = graph.addNode(MaterialNodeGraph.NodeType.VALUE, 60.0, 160.0);
        factor.setNumber("value", 0.35);
        MaterialNodeGraph.Node mix = graph.addNode(MaterialNodeGraph.NodeType.MIX_SHADER, 500.0, 150.0);
        graph.connect(glass.getId(), "bsdf", mix.getId(), "shader_a");
        graph.connect(emission.getId(), "emission", mix.getId(), "shader_b");
        graph.connect(factor.getId(), "value", mix.getId(), "factor");
        graph.connect(mix.getId(), "shader", output.getId(), "surface");

        MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );

        if (result.transmission < 0.55) {
            throw new AssertionError("Mixed shader should retain glass transmission");
        }
        if (result.emissionStrength < 1.5) {
            throw new AssertionError("Mixed shader should retain some emission contribution");
        }
        if (result.baseColor.x < 0.5 || result.baseColor.y < 0.55) {
            throw new AssertionError("Mixed shader base color is not blending as expected");
        }
    }

    private static void testMapRangeClampChainControlsRoughness() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.6, 0.6, 0.6), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node bsdf = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (bsdf == null) {
            throw new AssertionError("Expected default Principled BSDF node");
        }
        graph.disconnectInput(bsdf.getId(), "roughness");

        MaterialNodeGraph.Node noise = graph.addNode(MaterialNodeGraph.NodeType.NOISE_TEXTURE, 60.0, 60.0);
        noise.setNumber("scale", 16.0);
        noise.setNumber("detail", 5.0);
        MaterialNodeGraph.Node mapRange = graph.addNode(MaterialNodeGraph.NodeType.MAP_RANGE, 280.0, 60.0);
        mapRange.setNumber("from_min", 0.15);
        mapRange.setNumber("from_max", 0.85);
        mapRange.setNumber("to_min", -0.4);
        mapRange.setNumber("to_max", 1.4);
        MaterialNodeGraph.Node clamp = graph.addNode(MaterialNodeGraph.NodeType.CLAMP, 500.0, 60.0);
        graph.connect(noise.getId(), "factor", mapRange.getId(), "value");
        graph.connect(mapRange.getId(), "value", clamp.getId(), "value");
        graph.connect(clamp.getId(), "value", bsdf.getId(), "roughness");

        MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.22, 0.71, false, 0.0, 0.0)
        );

        if (result.roughness < -1e-6 || result.roughness > 1.0 + 1e-6) {
            throw new AssertionError("Clamp + Map Range chain failed to keep roughness in range");
        }
    }

    private static void testMultiplePrincipledNodesOwnIndependentDefaults() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.25, 0.25, 0.25), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node output = graph.findFirstNode(MaterialNodeGraph.NodeType.OUTPUT_MATERIAL);
        MaterialNodeGraph.Node first = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (output == null || first == null) {
            throw new AssertionError("Expected output and first principled node");
        }
        graph.disconnectInput(first.getId(), "base_color");
        graph.disconnectInput(first.getId(), "roughness");
        first.setColor("base_color", new Vec3(0.95, 0.18, 0.10));
        first.setNumber("roughness", 0.14);

        MaterialNodeGraph.Node second = graph.addNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF, 640.0, 120.0);
        second.setColor("base_color", new Vec3(0.08, 0.30, 0.94));
        second.setNumber("roughness", 0.88);

        graph.disconnectInput(output.getId(), "surface");
        graph.connect(first.getId(), "bsdf", output.getId(), "surface");
        MaterialGraphEvaluator.Result firstResult = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );

        graph.disconnectInput(output.getId(), "surface");
        graph.connect(second.getId(), "bsdf", output.getId(), "surface");
        MaterialGraphEvaluator.Result secondResult = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.5, 0.5, false, 0.0, 0.0)
        );

        if (Math.abs(firstResult.baseColor.x - secondResult.baseColor.x) < 0.3) {
            throw new AssertionError("Two principled nodes are not evaluating independently");
        }
        assertNear(0.14, firstResult.roughness, 1e-6, "First principled roughness mismatch");
        assertNear(0.88, secondResult.roughness, 1e-6, "Second principled roughness mismatch");
    }

    private static void testDisconnectedPrincipledSocketsUseNodeDefaults() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.92, 0.15, 0.12), 32.0);
        material.setRoughness(0.08);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
        if (principled == null) {
            throw new AssertionError("Expected default Principled BSDF node");
        }
        graph.disconnectInput(principled.getId(), "base_color");
        graph.disconnectInput(principled.getId(), "roughness");
        principled.setColor("base_color", new Vec3(0.12, 0.64, 0.88));
        principled.setNumber("roughness", 0.73);

        material.setDiffuseColor(new Vec3(0.85, 0.05, 0.05));
        material.setRoughness(0.04);

        MaterialGraphEvaluator.Result result = MaterialGraphEvaluator.evaluate(
                material,
                MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.25, 0.25, false, 0.0, 0.0)
        );

        assertNear(0.12, result.baseColor.x, 1e-6, "Node-owned principled base color was overridden by material");
        assertNear(0.64, result.baseColor.y, 1e-6, "Node-owned principled base color was overridden by material");
        assertNear(0.88, result.baseColor.z, 1e-6, "Node-owned principled base color was overridden by material");
        assertNear(0.73, result.roughness, 1e-6, "Node-owned principled roughness was overridden by material");
    }

    private static void testImageTextureMappingChainAffectsSampling() {
        try {
            Path imagePath = Files.createTempFile("mapping-chain-image", ".png");
            BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, 0xFFFF0000);
            image.setRGB(1, 0, 0xFF0000FF);
            ImageIO.write(image, "png", imagePath.toFile());

            PhongMaterial material = new PhongMaterial(new Vec3(0.5, 0.5, 0.5), 32.0);
            MaterialNodeGraph graph = material.getOrCreateNodeGraph();
            MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
            if (principled == null) {
                throw new AssertionError("Expected default principled node");
            }
            graph.disconnectInput(principled.getId(), "base_color");

            MaterialNodeGraph.Node texCoord = graph.addNode(MaterialNodeGraph.NodeType.TEXTURE_COORDINATE, 20.0, 20.0);
            MaterialNodeGraph.Node mapping = graph.addNode(MaterialNodeGraph.NodeType.MAPPING, 240.0, 20.0);
            MaterialNodeGraph.Node imageNode = graph.addNode(MaterialNodeGraph.NodeType.IMAGE_TEXTURE, 480.0, 20.0);
            imageNode.setText("file_path", imagePath.toAbsolutePath().normalize().toString());
            imageNode.setEnum("color_space", MaterialNodeGraph.TextureColorSpace.DATA.name());
            imageNode.setNumber("linear", 0.0);
            graph.connect(texCoord.getId(), "uv0", mapping.getId(), "vector");
            graph.connect(mapping.getId(), "vector", imageNode.getId(), "vector");
            graph.connect(imageNode.getId(), "color", principled.getId(), "base_color");

            mapping.setNumber("location_x", 0.0);
            MaterialGraphEvaluator.Result left = MaterialGraphEvaluator.evaluate(
                    material,
                    MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.10, 0.50, false, 0.0, 0.0)
            );
            mapping.setNumber("location_x", 0.60);
            MaterialGraphEvaluator.Result right = MaterialGraphEvaluator.evaluate(
                    material,
                    MaterialGraphEvaluator.Context.ofTriangle(0.0, 0.0, 0.0, true, 0.10, 0.50, false, 0.0, 0.0)
            );

            if (left.baseColor.x < 0.8 || left.baseColor.z > 0.2) {
                throw new AssertionError("Expected unmapped sample to read the red texel");
            }
            if (right.baseColor.z < 0.8 || right.baseColor.x > 0.2) {
                throw new AssertionError("Expected mapping offset to move sampling to the blue texel");
            }
        } catch (Exception ex) {
            throw new AssertionError("Mapping chain test failed: " + ex.getMessage(), ex);
        }
    }

    private static void testTextureSetImportAutoWiresPrincipledGraph() {
        try {
            Path basePath = createSolidTexture("demo_basecolor", 0xFFB07030);
            Path roughPath = createSolidTexture("demo_roughness", 0xFF808080);
            Path normalPath = createSolidTexture("demo_normal", 0xFF8080FF);

            PhongMaterial material = new PhongMaterial(new Vec3(0.6, 0.6, 0.6), 32.0);
            MaterialTextureSetImporter.ImportResult result = MaterialTextureSetImporter.importFiles(
                    material,
                    List.of(basePath, roughPath, normalPath)
            );
            if (!result.success()) {
                throw new AssertionError("PBR texture-set import did not succeed");
            }

            MaterialNodeGraph graph = material.getNodeGraph();
            if (graph == null) {
                throw new AssertionError("Texture-set import did not create a node graph");
            }
            if (graph.findFirstNode(MaterialNodeGraph.NodeType.TEXTURE_COORDINATE) == null) {
                throw new AssertionError("Texture-set import is missing Texture Coordinate");
            }
            if (graph.findFirstNode(MaterialNodeGraph.NodeType.MAPPING) == null) {
                throw new AssertionError("Texture-set import is missing Mapping");
            }
            if (graph.findFirstNode(MaterialNodeGraph.NodeType.NORMAL_MAP) == null) {
                throw new AssertionError("Texture-set import is missing Normal Map");
            }
            MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
            if (principled == null || graph.findInputLink(principled.getId(), "base_color") == null) {
                throw new AssertionError("Texture-set import did not wire base color into Principled");
            }
            if (graph.findInputLink(principled.getId(), "normal") == null) {
                throw new AssertionError("Texture-set import did not wire a normal path into Principled");
            }
        } catch (Exception ex) {
            throw new AssertionError("Texture-set import test failed: " + ex.getMessage(), ex);
        }
    }

    private static void testNormalMapCompatibilityBridgeTracksGraph() {
        try {
            Path normalPath = createSolidTexture("bridge_normal", 0xFF8080FF);
            PhongMaterial material = new PhongMaterial(new Vec3(0.4, 0.4, 0.4), 32.0);
            MaterialNodeGraph graph = material.getOrCreateNodeGraph();
            MaterialNodeGraph.Node principled = graph.findFirstNode(MaterialNodeGraph.NodeType.PRINCIPLED_BSDF);
            if (principled == null) {
                throw new AssertionError("Expected default principled node");
            }
            MaterialNodeGraph.Node texCoord = graph.addNode(MaterialNodeGraph.NodeType.TEXTURE_COORDINATE, 20.0, 20.0);
            MaterialNodeGraph.Node mapping = graph.addNode(MaterialNodeGraph.NodeType.MAPPING, 200.0, 20.0);
            MaterialNodeGraph.Node imageNode = graph.addNode(MaterialNodeGraph.NodeType.IMAGE_TEXTURE, 420.0, 20.0);
            MaterialNodeGraph.Node normalMap = graph.addNode(MaterialNodeGraph.NodeType.NORMAL_MAP, 620.0, 20.0);
            imageNode.setText("file_path", normalPath.toAbsolutePath().normalize().toString());
            imageNode.setEnum("color_space", MaterialNodeGraph.TextureColorSpace.DATA.name());
            graph.connect(texCoord.getId(), "uv0", mapping.getId(), "vector");
            graph.connect(mapping.getId(), "vector", imageNode.getId(), "vector");
            graph.connect(imageNode.getId(), "color", normalMap.getId(), "color");
            graph.connect(normalMap.getId(), "normal", principled.getId(), "normal");
            normalMap.setNumber("strength", 1.7);

            MaterialGraphAuthoring.syncCompatibilityBindings(material);

            if (!material.hasNormalTexture()) {
                throw new AssertionError("Větev normály v grafu nepropsala kompatibilní slot textury normály");
            }
            assertNear(1.7, material.getNormalScale(), 1e-6, "Síla normály z grafu se nepropsala do kompatibilního slotu");
        } catch (Exception ex) {
            throw new AssertionError("Test kompatibilní větve normal mapy selhal: " + ex.getMessage(), ex);
        }
    }

    private static void testGraphCopiesWithMaterial() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.6, 0.4, 0.2), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        graph.addNode(MaterialNodeGraph.NodeType.VALUE, 20.0, 20.0);

        PhongMaterial copy = material.copy();
        if (!copy.hasNodeGraph()) {
            throw new AssertionError("Copied material lost node graph");
        }
        if (copy.getNodeGraph() == graph) {
            throw new AssertionError("Copied material reused the same graph instance");
        }
        if (copy.getNodeGraph().getNodes().size() != graph.getNodes().size()) {
            throw new AssertionError("Copied material graph node count mismatch");
        }
    }

    private static void testGraphCopyDeepCopiesNodeColors() {
        PhongMaterial material = new PhongMaterial(new Vec3(0.6, 0.4, 0.2), 32.0);
        MaterialNodeGraph graph = material.getOrCreateNodeGraph();
        MaterialNodeGraph.Node rgb = graph.addNode(MaterialNodeGraph.NodeType.RGB, 30.0, 30.0);
        rgb.setColor("color", new Vec3(0.2, 0.5, 0.9));

        PhongMaterial copy = material.copy();
        MaterialNodeGraph.Node copiedRgb = null;
        for (MaterialNodeGraph.Node node : copy.getNodeGraph().getNodes()) {
            if (node.getType() == MaterialNodeGraph.NodeType.RGB) {
                copiedRgb = node;
                break;
            }
        }
        if (copiedRgb == null) {
            throw new AssertionError("Expected copied RGB node");
        }

        rgb.setColor("color", new Vec3(0.95, 0.1, 0.1));
        Vec3 copiedColor = copiedRgb.getColor("color", Vec3.ZERO);
        assertNear(0.2, copiedColor.x, 1e-6, "Copied graph color shared state with source graph");
        assertNear(0.5, copiedColor.y, 1e-6, "Copied graph color shared state with source graph");
        assertNear(0.9, copiedColor.z, 1e-6, "Copied graph color shared state with source graph");
    }

    private static Path createSolidTexture(String prefix, int argb) throws Exception {
        Path dir = Files.createTempDirectory("material-texture-set");
        Path path = dir.resolve(prefix + ".png");
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        ImageIO.write(image, "png", path.toFile());
        return path;
    }

    private static void assertNear(double expected, double actual, double eps, String message) {
        if (Math.abs(expected - actual) > eps) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
