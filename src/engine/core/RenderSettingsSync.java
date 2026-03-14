package engine.core;

import engine.render.ray.PathTracerRenderer;
import engine.render.ray.RayTracerRenderer;

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
            double rayDenoiseStrength) {
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
            double pathDenoiseStrength) {
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
        pathTracerRenderer.setParameter("denoise", pathDenoise);
        pathTracerRenderer.setParameter("denoiseRadius", pathDenoiseRadius);
        pathTracerRenderer.setParameter("denoiseStrength", pathDenoiseStrength);
        return pathMaxDepth;
    }
}
