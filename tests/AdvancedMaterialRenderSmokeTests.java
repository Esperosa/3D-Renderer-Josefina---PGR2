import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.Texture;
import engine.render.raster.RasterRenderer;
import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;
import engine.scene.Entity;
import engine.scene.Scene;

public final class AdvancedMaterialRenderSmokeTests {

    private AdvancedMaterialRenderSmokeTests() {
    }

    public static void main(String[] args) {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.0, 0.0, 0.0));
        scene.setBackgroundColor(new Vec3(0.0, 0.0, 0.0));

        Mesh mesh = new Mesh(
                "tri",
                new float[]{
                        -1.2f, -1.0f, 0.0f,
                        1.2f, -1.0f, 0.0f,
                        0.0f, 1.2f, 0.0f
                },
                new float[]{
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f,
                        0.0f, 0.0f, 1.0f
                },
                new int[]{0, 1, 2}
        );
        mesh.setUVs(new float[]{
                0.0f, 0.0f,
                1.0f, 0.0f,
                0.5f, 1.0f
        });
        mesh.setUV2s(new float[]{
                0.15f, 0.05f,
                0.85f, 0.05f,
                0.50f, 0.95f
        });

        PhongMaterial material = new PhongMaterial(new Vec3(0.8, 0.8, 0.8), 32.0);
        material.setDiffuseTexture(new Texture(1, 1, new int[]{0xFFFFFFFF}));
        material.getDiffuseMap().setTexCoord(1);
        material.setNormalTexture(new Texture(1, 1, new int[]{0xFF8080FF}));
        material.setNormalScale(0.8);
        material.setMetallicRoughnessTexture(new Texture(1, 1, new int[]{0xFF00B0E0}));
        material.setEmissiveTexture(new Texture(1, 1, new int[]{0xFFFFB060}));
        material.setEmissionColor(new Vec3(1.0, 0.6, 0.2));
        material.setEmissionStrength(1.8);
        material.setTransmission(0.35);
        material.setOpacity(0.85);
        material.setAlphaMode(PhongMaterial.AlphaMode.BLEND);
        material.setDoubleSided(true);
        material.setClearcoatFactor(0.35);
        material.setClearcoatRoughness(0.12);
        material.setSheenColor(new Vec3(0.15, 0.12, 0.08));
        material.setSheenRoughness(0.4);

        scene.addEntity(new Entity("emissive-tri", mesh, material));
        scene.update(0.0);

        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);

        assertRaster(scene, camera);
        assertRay(scene, camera);
        assertPath(scene, camera);
        System.out.println("AdvancedMaterialRenderSmokeTests: ALL TESTS PASSED");
    }

    private static void assertRaster(Scene scene, PerspectiveCamera camera) {
        RasterRenderer renderer = new RasterRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("unlitMode", false);
        renderer.render(scene, camera, fb, 0.0);
        assertVisiblePixel(fb.getColor(32, 32), "Raster");
    }

    private static void assertRay(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("samplesPerFrame", 2);
        renderer.setParameter("maxDepth", 3);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertVisiblePixel(fb.getColor(32, 32), "RayTracer");
    }

    private static void assertPath(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("maxDepth", 4);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertVisiblePixel(fb.getColor(32, 32), "PathTracer");
    }

    private static void assertVisiblePixel(int argb, String label) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r + g + b <= 12) {
            throw new AssertionError(label + " produced only background at center pixel");
        }
    }
}
