package engine.render.ray.core;

import engine.render.ray.bvh.*;
import engine.camera.PerspectiveCamera;
import engine.geometry.MeshGenerator;
import engine.material.PhongMaterial;
import engine.math.Vec3;
import engine.render.FrameBuffer;
import engine.scene.DirectionalLight;
import engine.scene.Entity;
import engine.scene.Scene;

public final class ProductionRuntimeLoadingSmoke {

    private ProductionRuntimeLoadingSmoke() {
    }

    public static void main(String[] args) {
        String presentRoot = "runtime/denoiser-package";
        String missingRoot = "runtime/denoiser-package-missing-for-smoke";

        Scene scene = buildScene();
        PerspectiveCamera camera = buildCamera();

        CaseResult rayPresent = runRayCase(scene, camera, presentRoot, true);
        CaseResult pathPresent = runPathCase(scene, camera, presentRoot, true);

        CaseResult rayMissingSoft = runRayCase(scene, camera, missingRoot, false);
        CaseResult pathMissingSoft = runPathCase(scene, camera, missingRoot, false);

        CaseResult rayMissingRequired = runRayCase(scene, camera, missingRoot, true);
        CaseResult pathMissingRequired = runPathCase(scene, camera, missingRoot, true);

        RuntimeDenoiserOrchestrator.Decision d2160 = RuntimeDenoiserOrchestrator.decide(
                3840,
                2160,
                0.0,
                false,
                0.0,
                1.0,
                4.0
        );

        boolean passPresent = rayPresent.packageReady && pathPresent.packageReady;
        boolean passSoft = rayMissingSoft.renderNonDark && pathMissingSoft.renderNonDark;
        boolean passRequired = "fallback_runtime_package_missing".equals(rayMissingRequired.lastReason)
                && "fallback_runtime_package_missing".equals(pathMissingRequired.lastReason)
                && rayMissingRequired.fallbackCount > 0
                && pathMissingRequired.fallbackCount > 0;
        boolean pass2160 = d2160.mode() == RuntimeDenoiserOrchestrator.RuntimeMode.TILED && d2160.tiledMandatory();

        String json = "{\n"
                + "  \"present_package\": " + rayPresent.toJson("ray", pathPresent) + ",\n"
                + "  \"missing_package_required_false\": " + rayMissingSoft.toJson("ray", pathMissingSoft) + ",\n"
                + "  \"missing_package_required_true\": " + rayMissingRequired.toJson("ray", pathMissingRequired) + ",\n"
                + "  \"enforcement_2160\": {\n"
                + "    \"mode\": \"" + d2160.mode().name() + "\",\n"
                + "    \"tiled_mandatory\": " + d2160.tiledMandatory() + "\n"
                + "  },\n"
                + "  \"verification\": {\n"
                + "    \"package_present_pass\": " + passPresent + ",\n"
                + "    \"soft_fallback_pass\": " + passSoft + ",\n"
                + "    \"required_mode_pass\": " + passRequired + ",\n"
                + "    \"enforcement_2160_pass\": " + pass2160 + "\n"
                + "  }\n"
                + "}";

        System.out.println(json);

        if (!(passPresent && passSoft && passRequired && pass2160)) {
            throw new IllegalStateException("ProductionRuntimeLoadingSmoke failed verification checks");
        }
    }

    private static CaseResult runRayCase(Scene scene,
                                         PerspectiveCamera camera,
                                         String packageRoot,
                                         boolean required) {
        RayTracerRenderer renderer = new RayTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("workerCount", 2);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", 0.6);
        renderer.setParameter("denoiseRuntimePackageRoot", packageRoot);
        renderer.setParameter("denoiseRuntimePackageRequired", required);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);
        renderer.setParameter("shadows", true);

        for (int i = 0; i < 3; i++) {
            renderer.render(scene, camera, fb, i * 0.016);
        }

        RuntimeDenoiserOrchestrator.TelemetrySnapshot t = renderer.getDenoiserTelemetrySnapshot();
        return CaseResult.from(
                renderer.getDenoiserRuntimePackageStatusSummary(),
                t.fallbackCount(),
                t.skipCount(),
                t.lowConfidenceCount(),
                t.lastReason(),
                fb.getColor(32, 32)
        );
    }

    private static CaseResult runPathCase(Scene scene,
                                          PerspectiveCamera camera,
                                          String packageRoot,
                                          boolean required) {
        PathTracerRenderer renderer = new PathTracerRenderer();
        FrameBuffer fb = new FrameBuffer(64, 64);
        renderer.init(64, 64);
        renderer.setParameter("workerCount", 2);
        renderer.setParameter("samplesPerFrame", 4);
        renderer.setParameter("denoise", true);
        renderer.setParameter("denoiseRadius", 2);
        renderer.setParameter("denoiseStrength", 0.55);
        renderer.setParameter("denoiseRuntimePackageRoot", packageRoot);
        renderer.setParameter("denoiseRuntimePackageRequired", required);
        renderer.setParameter("sky", false);
        renderer.setParameter("directLighting", true);

        for (int i = 0; i < 3; i++) {
            renderer.render(scene, camera, fb, i * 0.016);
        }

        RuntimeDenoiserOrchestrator.TelemetrySnapshot t = renderer.getDenoiserTelemetrySnapshot();
        return CaseResult.from(
                renderer.getDenoiserRuntimePackageStatusSummary(),
                t.fallbackCount(),
                t.skipCount(),
                t.lowConfidenceCount(),
                t.lastReason(),
                fb.getColor(32, 32)
        );
    }

    private static Scene buildScene() {
        Scene scene = new Scene();
        scene.setAmbientColor(new Vec3(0.08, 0.08, 0.08));
        scene.setBackgroundColor(new Vec3(0.01, 0.02, 0.03));
        scene.addLight(new DirectionalLight(new Vec3(-0.4, -0.5, -1.0), Vec3.ONE, 1.4));

        Entity sphere = new Entity("sphere", MeshGenerator.sphere(0.8, 20, 14),
                new PhongMaterial(new Vec3(0.92, 0.28, 0.18), 48.0));
        sphere.getTransform().setPosition(new Vec3(0.0, 0.0, 0.2));
        scene.addEntity(sphere);
        scene.update(0.0);
        return scene;
    }

    private static PerspectiveCamera buildCamera() {
        PerspectiveCamera camera = new PerspectiveCamera(60.0, 1.0, 0.1, 50.0);
        camera.setPosition(new Vec3(0.0, 0.0, 3.2));
        camera.lookAt(Vec3.ZERO);
        return camera;
    }

    private static final class CaseResult {
        private final boolean packageReady;
        private final long fallbackCount;
        private final long skipCount;
        private final long lowConfidenceCount;
        private final String lastReason;
        private final boolean renderNonDark;
        private final String statusSummary;

        private CaseResult(boolean packageReady,
                           long fallbackCount,
                           long skipCount,
                           long lowConfidenceCount,
                           String lastReason,
                           boolean renderNonDark,
                           String statusSummary) {
            this.packageReady = packageReady;
            this.fallbackCount = fallbackCount;
            this.skipCount = skipCount;
            this.lowConfidenceCount = lowConfidenceCount;
            this.lastReason = lastReason == null ? "" : lastReason;
            this.renderNonDark = renderNonDark;
            this.statusSummary = statusSummary == null ? "" : statusSummary;
        }

        private static CaseResult from(String statusSummary,
                                       long fallbackCount,
                                       long skipCount,
                                       long lowConfidenceCount,
                                       String lastReason,
                                       int argb) {
            int r = (argb >>> 16) & 0xFF;
            int g = (argb >>> 8) & 0xFF;
            int b = argb & 0xFF;
            boolean visible = r >= 12 || g >= 12 || b >= 12;
            boolean ready = statusSummary != null && statusSummary.startsWith("ready(");
            return new CaseResult(ready, fallbackCount, skipCount, lowConfidenceCount, lastReason, visible, statusSummary);
        }

        private String toJson(String firstName, CaseResult second) {
            return "{\n"
                    + "    \"" + firstName + "\": " + asJsonObject() + ",\n"
                    + "    \"path\": " + second.asJsonObject() + "\n"
                    + "  }";
        }

        private String asJsonObject() {
            return "{"
                    + "\"package_ready\":" + packageReady + ","
                    + "\"status_summary\":\"" + escape(statusSummary) + "\"," 
                    + "\"render_non_dark\":" + renderNonDark + ","
                    + "\"fallback_count\":" + fallbackCount + ","
                    + "\"skip_count\":" + skipCount + ","
                    + "\"low_confidence_count\":" + lowConfidenceCount + ","
                    + "\"last_reason\":\"" + escape(lastReason) + "\""
                    + "}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
