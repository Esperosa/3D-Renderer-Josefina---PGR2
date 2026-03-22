package engine.core;

import engine.render.ray.core.PathTracerRenderer;
import engine.render.ray.core.RayTracerRenderer;

final class RenderSettingsSync {

    private RenderSettingsSync() {
    }

    static int computeMaxBounceDepth(int a, int b, int c, int d, int e) {
        return Math.max(1, Math.max(a, Math.max(b, Math.max(c, Math.max(d, e)))));
    }

    static int applyRaySettings(
            RayTracerRenderer rayTracerRenderer,
            int raySamplesPerFrame,
            int rayTileSize,
            int rayDiffuseBounces,
            int rayGlossyBounces,
            int rayTransmissionBounces,
            int rayVolumeBounces,
            int rayTransparentBounces,
            boolean rayDirectLighting,
            boolean rayShadows,
            boolean rayReflections,
            boolean rayDenoise,
            int rayDenoiseRadius,
            double rayDenoiseStrength,
            String toneMapMode) {
        if (rayTracerRenderer == null) {
            return 1;
        }
        int rayMaxDepth = computeMaxBounceDepth(
                rayDiffuseBounces,
                rayGlossyBounces,
                rayTransmissionBounces,
                rayVolumeBounces,
                rayTransparentBounces
        );
        rayTracerRenderer.setParameter("samplesPerFrame", raySamplesPerFrame);
        rayTracerRenderer.setParameter("tileSize", rayTileSize);
        rayTracerRenderer.setParameter("maxDepth", rayMaxDepth);
        rayTracerRenderer.setParameter("directLighting", rayDirectLighting);
        rayTracerRenderer.setParameter("shadows", rayShadows);
        rayTracerRenderer.setParameter("reflections", rayReflections);
        rayTracerRenderer.setParameter("denoise", rayDenoise);
        rayTracerRenderer.setParameter("denoiseRadius", rayDenoiseRadius);
        rayTracerRenderer.setParameter("denoiseStrength", rayDenoiseStrength);
        rayTracerRenderer.setParameter("toneMap", toneMapMode);
        return rayMaxDepth;
    }

    static int applyPathSettings(
            PathTracerRenderer pathTracerRenderer,
            int pathSamplesPerFrame,
            int pathTileSize,
            int pathDiffuseBounces,
            int pathGlossyBounces,
            int pathTransmissionBounces,
            int pathVolumeBounces,
            int pathTransparentBounces,
            boolean pathDirectLighting,
            boolean pathSkyEnvironment,
            boolean pathDenoise,
            int pathDenoiseRadius,
            double pathDenoiseStrength,
            double pathClampDirect,
            double pathClampIndirect,
            String toneMapMode) {
        if (pathTracerRenderer == null) {
            return 1;
        }
        int pathMaxDepth = computeMaxBounceDepth(
                pathDiffuseBounces,
                pathGlossyBounces,
                pathTransmissionBounces,
                pathVolumeBounces,
                pathTransparentBounces
        );
        pathTracerRenderer.setParameter("samplesPerFrame", pathSamplesPerFrame);
        pathTracerRenderer.setParameter("tileSize", pathTileSize);
        pathTracerRenderer.setParameter("maxDepth", pathMaxDepth);
        pathTracerRenderer.setParameter("directLighting", pathDirectLighting);
        pathTracerRenderer.setParameter("sky", pathSkyEnvironment);
        pathTracerRenderer.setParameter("referenceMode", false);
        pathTracerRenderer.setParameter("historyFireflyClamp", true);
        pathTracerRenderer.setParameter("denoise", pathDenoise);
        pathTracerRenderer.setParameter("denoiseRadius", pathDenoiseRadius);
        pathTracerRenderer.setParameter("denoiseStrength", pathDenoiseStrength);
        pathTracerRenderer.setParameter("toneMap", toneMapMode);
        pathTracerRenderer.setParameter("clampDirect", pathClampDirect);
        pathTracerRenderer.setParameter("clampIndirect", pathClampIndirect);
        return pathMaxDepth;
    }
}
