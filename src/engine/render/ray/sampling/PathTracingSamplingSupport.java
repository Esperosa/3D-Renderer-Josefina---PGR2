package engine.render.ray.core;

import engine.render.ray.bvh.*;
final class PathTracingSamplingSupport {

    private PathTracingSamplingSupport() {
    }

    static double powerHeuristic(double primaryPdf, double secondaryPdf) {
        double a = Math.max(0.0, primaryPdf);
        double b = Math.max(0.0, secondaryPdf);
        double a2 = a * a;
        double b2 = b * b;
        double denom = a2 + b2;
        if (denom <= 1e-12) {
            return 0.0;
        }
        return a2 / denom;
    }

    static double cosineHemispherePdf(double nDotL) {
        if (!Double.isFinite(nDotL) || nDotL <= 0.0) {
            return 0.0;
        }
        return nDotL / Math.PI;
    }

    static double phongLobePdf(double exponent, double cosAlpha) {
        if (!Double.isFinite(cosAlpha) || cosAlpha <= 0.0) {
            return 0.0;
        }
        double safeExponent = Math.max(1.0, exponent);
        return ((safeExponent + 1.0) * 0.5 / Math.PI) * Math.pow(cosAlpha, safeExponent);
    }

    static double uniformSpherePdf() {
        return 1.0 / (4.0 * Math.PI);
    }
}

