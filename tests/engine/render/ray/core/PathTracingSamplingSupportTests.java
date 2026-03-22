package engine.render.ray.core;

import engine.render.ray.bvh.*;
public final class PathTracingSamplingSupportTests {

    private PathTracingSamplingSupportTests() {
    }

    public static void main(String[] args) {
        testPowerHeuristicPrefersBetterProposal();
        testCosineHemispherePdfMatchesLambertianPdf();
        testPhongLobePdfFallsWithAngle();
        testUniformSpherePdfMatchesClosedForm();
        System.out.println("PathTracingSamplingSupportTests: ALL TESTS PASSED");
    }

    private static void testPowerHeuristicPrefersBetterProposal() {
        double primaryDominant = PathTracingSamplingSupport.powerHeuristic(0.7, 0.1);
        double secondaryDominant = PathTracingSamplingSupport.powerHeuristic(0.1, 0.7);
        if (primaryDominant <= 0.95) {
            throw new AssertionError("Power heuristic should strongly prefer the better-matched proposal.");
        }
        if (secondaryDominant >= 0.05) {
            throw new AssertionError("Power heuristic should heavily downweight the weaker proposal.");
        }
    }

    private static void testCosineHemispherePdfMatchesLambertianPdf() {
        double normalIncidence = PathTracingSamplingSupport.cosineHemispherePdf(1.0);
        double grazing = PathTracingSamplingSupport.cosineHemispherePdf(0.25);
        assertNear(1.0 / Math.PI, normalIncidence, 1e-12,
                "Cosine hemisphere pdf should equal 1/pi at normal incidence.");
        assertNear(0.25 / Math.PI, grazing, 1e-12,
                "Cosine hemisphere pdf should scale linearly with nDotL.");
    }

    private static void testPhongLobePdfFallsWithAngle() {
        double sharpHighlight = PathTracingSamplingSupport.phongLobePdf(64.0, 0.98);
        double widerOffset = PathTracingSamplingSupport.phongLobePdf(64.0, 0.72);
        if (sharpHighlight <= widerOffset) {
            throw new AssertionError("Phong lobe pdf should favor directions closer to the perfect reflection.");
        }
    }

    private static void testUniformSpherePdfMatchesClosedForm() {
        assertNear(1.0 / (4.0 * Math.PI), PathTracingSamplingSupport.uniformSpherePdf(), 1e-12,
                "Uniform sphere pdf should equal 1/(4*pi).");
    }

    private static void assertNear(double expected, double actual, double tolerance, String message) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(message + " expected=" + expected + " actual=" + actual);
        }
    }
}
