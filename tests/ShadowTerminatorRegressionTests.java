import java.lang.reflect.Field;
import java.lang.reflect.Method;

import engine.camera.PerspectiveCamera;
import engine.geometry.Mesh;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class ShadowTerminatorRegressionTests {
    private static final double SHADOW_PLANE_EPS = 1e-3;
    private static final double RAY_EPS = 1e-4;

    private ShadowTerminatorRegressionTests() {
    }

    public static void main(String[] args) {
        Scene scene = createScene();
        PerspectiveCamera camera = createCamera();

        assertRayKeepsLighting(scene, camera);
        assertPathKeepsLighting(scene, camera);
        assertShadowOriginPushesPastPlane(
                "engine.render.ray.RayTracerRenderer",
                "engine.render.ray.RayHit",
                "engine.render.ray.RaySurfaceState",
                "engine.render.ray.RayTraceContext");
        assertShadowOriginPushesPastPlane(
                "engine.render.ray.PathTracerRenderer",
                "engine.render.ray.Hit",
                "engine.render.ray.SurfaceState",
                "engine.render.ray.PathTracerRenderer$TraceContext");

        System.out.println("ShadowTerminatorRegressionTests: ALL TESTS PASSED");
    }

    private static Scene createScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(Vec3.ZERO);
        scene.setBackgroundColor(Vec3.ZERO);
        scene.addLight(new DirectionalLight(
                new Vec3(-0.9701425, 0.0, 0.2425356),
                Vec3.ONE,
                2.2));

        float nx = 0.9238795f;
        float ny = 0.0f;
        float nz = 0.3826834f;
        Mesh mesh = new Mesh(
                "terminator-tri",
                new float[]{
                        -1.5f, -1.2f, 0.0f,
                        1.5f, -1.2f, 0.0f,
                        0.0f, 1.6f, 0.0f
                },
                new float[]{
                        nx, ny, nz,
                        nx, ny, nz,
                        nx, ny, nz
                },
                new int[]{0, 2, 1}
        );

        PhongMaterial material = new PhongMaterial(new Vec3(0.95, 0.95, 0.95), 8.0);
        material.setSpecularColor(Vec3.ZERO);
        material.setSpecularFactor(0.0);
        material.setReflectivity(0.0);
        material.setClearcoatFactor(0.0);
        material.setTransmission(0.0);
        material.setEmissionColor(Vec3.ZERO);
        material.setEmissionStrength(0.0);

        scene.addEntity(new Entity("terminator-tri", mesh, material));
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera createCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.0));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static void assertRayKeepsLighting(Scene scene, PerspectiveCamera camera) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.init(96, 96);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", true);
        renderer.setParameter("reflections", false);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertBright(fb.getColor(48, 48), "RayTracer");
    }

    private static void assertPathKeepsLighting(Scene scene, PerspectiveCamera camera) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(96, 96);
        renderer.init(96, 96);
        renderer.setParameter("samplesPerFrame", 1);
        renderer.setParameter("maxDepth", 1);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("denoise", false);
        renderer.render(scene, camera, fb, 0.0);
        assertBright(fb.getColor(48, 48), "PathTracer");
    }

    private static void assertBright(int argb, String label) {
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (r + g + b < 210) {
            throw new AssertionError(label + " still self-shadowed the lit terminator case: rgb=("
                    + r + "," + g + "," + b + ")");
        }
    }

    private static void assertShadowOriginPushesPastPlane(String rendererClassName,
                                                          String hitClassName,
                                                          String surfaceClassName,
                                                          String contextClassName) {
        try {
            Class<?> rendererClass = Class.forName(rendererClassName);
            Object renderer = rendererClass.getConstructor().newInstance();
            Class<?> hitClass = Class.forName(hitClassName);
            Class<?> surfaceClass = Class.forName(surfaceClassName);
            Class<?> contextClass = Class.forName(contextClassName);
            var hitCtor = hitClass.getDeclaredConstructor();
            hitCtor.setAccessible(true);
            var surfaceCtor = surfaceClass.getDeclaredConstructor();
            surfaceCtor.setAccessible(true);
            var contextCtor = contextClass.getDeclaredConstructor();
            contextCtor.setAccessible(true);
            Object hit = hitCtor.newInstance();
            Object surface = surfaceCtor.newInstance();
            Object ctx = contextCtor.newInstance();

            setDouble(hitClass, hit, "px", 0.0);
            setDouble(hitClass, hit, "py", 0.0);
            setDouble(hitClass, hit, "pz", 0.0);

            setDouble(surfaceClass, surface, "nx", 1.0);
            setDouble(surfaceClass, surface, "ny", 0.0);
            setDouble(surfaceClass, surface, "nz", 0.0);
            setDouble(surfaceClass, surface, "geomNx", 0.0);
            setDouble(surfaceClass, surface, "geomNy", 0.0);
            setDouble(surfaceClass, surface, "geomNz", 1.0);

            double lx = 0.9950371902099893;
            double ly = 0.0;
            double lz = -0.09950371902099893;

            Method prepareShadowRay = rendererClass.getDeclaredMethod(
                    "prepareShadowRay",
                    hitClass,
                    surfaceClass,
                    double.class,
                    double.class,
                    double.class,
                    contextClass);
            prepareShadowRay.setAccessible(true);
            prepareShadowRay.invoke(renderer, hit, surface, lx, ly, lz, ctx);

            double shadowOx = getDouble(contextClass, ctx, "shadowOx");
            double shadowOy = getDouble(contextClass, ctx, "shadowOy");
            double shadowOz = getDouble(contextClass, ctx, "shadowOz");
            double shadowTMin = getDouble(contextClass, ctx, "shadowTMin");
            double planeLift = shadowOz;

            if (planeLift > -SHADOW_PLANE_EPS + 1e-6) {
                throw new AssertionError(rendererClass.getSimpleName()
                        + " did not push shadow origin past the geometric plane: planeLift=" + planeLift);
            }
            if (Math.abs(shadowTMin - RAY_EPS) > 1e-12) {
                throw new AssertionError(rendererClass.getSimpleName()
                        + " changed shadowTMin instead of keeping the fixed epsilon: " + shadowTMin);
            }
            if (shadowOx <= 0.0 && shadowOy == 0.0 && shadowOz == 0.0) {
                throw new AssertionError(rendererClass.getSimpleName()
                        + " did not move the shadow origin.");
            }
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Failed to validate shadow origin math for " + rendererClassName, ex);
        }
    }

    private static void setDouble(Class<?> type, Object target, String fieldName, double value)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setDouble(target, value);
    }

    private static double getDouble(Class<?> type, Object target, String fieldName)
            throws ReflectiveOperationException {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getDouble(target);
    }
}
