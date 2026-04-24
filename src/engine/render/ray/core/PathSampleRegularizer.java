package engine.render.ray.core;

final class PathSampleRegularizer {

    private static final double MIN_BRANCH_PROBABILITY = 0.02;
    private static final double SECONDARY_GLOSSY_ROUGHNESS_FLOOR = 0.02;
    private static final double DEEP_GLOSSY_ROUGHNESS_FLOOR = 0.05;
    private static final double CONTRIBUTION_FLOOR_LUMA = 0.04;
    private static final double DIRECT_SOFT_CLIP = 0.70;
    private static final double EMISSION_SOFT_CLIP = 0.65;
    private static final double ENVIRONMENT_SOFT_CLIP = 0.70;

    private PathSampleRegularizer() {
    }

    enum ContributionKind {
        DIRECT,
        EMISSION,
        ENVIRONMENT
    }

    static double boundedInverseProbability(double probability) {
        return 1.0 / Math.max(MIN_BRANCH_PROBABILITY, probability);
    }

    static double boundedSelectionRatio(double probability) {
        return probability / Math.max(MIN_BRANCH_PROBABILITY, probability);
    }

    static double regularizeRoughness(double roughness,
                                      int bounce,
                                      double primaryRoughness,
                                      double primarySpecularity) {
        double baseRoughness = DenoiseSupport.clamp01(roughness);
        if (bounce <= 0) {
            return baseRoughness;
        }
        double primaryGloss = DenoiseSupport.clamp01(primarySpecularity * 0.75 + (1.0 - primaryRoughness) * 0.55);
        double floor = bounce == 1
                ? SECONDARY_GLOSSY_ROUGHNESS_FLOOR + primaryGloss * 0.06
                : DEEP_GLOSSY_ROUGHNESS_FLOOR + primaryGloss * 0.10 + Math.min(0.08, (bounce - 2) * 0.03);
        return Math.max(baseRoughness, Math.min(0.48, floor));
    }

    static double contributionScale(double contributionR,
                                    double contributionG,
                                    double contributionB,
                                    ContributionKind kind,
                                    int bounce,
                                    double primaryBaseLuma,
                                    double primaryRoughness,
                                    double primarySpecularity,
                                    double primaryEmissionLuma) {
        double contributionLuma = DenoiseSupport.luminance(contributionR, contributionG, contributionB);
        if (!Double.isFinite(contributionLuma) || contributionLuma <= 0.0) {
            return 1.0;
        }

        double baseLuma = Math.max(CONTRIBUTION_FLOOR_LUMA, primaryBaseLuma);
        double gloss = DenoiseSupport.clamp01(primarySpecularity * 0.80 + (1.0 - primaryRoughness) * 0.50);
        double emissionBoost = Math.sqrt(Math.max(0.0, primaryEmissionLuma)) * 0.35;
        double bouncePenalty = 1.0 / (1.0 + Math.max(0, bounce) * (kind == ContributionKind.DIRECT ? 0.28 : 0.42));

        double threshold = (0.22 + baseLuma * (2.40 + gloss * 1.70) + emissionBoost) * bouncePenalty;
        double softClip = DIRECT_SOFT_CLIP;
        switch (kind) {
            case DIRECT -> {
                threshold = threshold * 1.25 + 0.10;
                softClip = DIRECT_SOFT_CLIP;
            }
            case EMISSION -> {
                threshold = threshold * 0.85 + 0.08;
                softClip = EMISSION_SOFT_CLIP;
            }
            case ENVIRONMENT -> {
                threshold = threshold * 0.95 + 0.08;
                softClip = ENVIRONMENT_SOFT_CLIP;
            }
            default -> {
                softClip = DIRECT_SOFT_CLIP;
            }
        }

        if (contributionLuma <= threshold) {
            return 1.0;
        }
        double compressedLuma = threshold + (contributionLuma - threshold) * softClip;
        return compressedLuma / contributionLuma;
    }
}

