package engine.render.ray.core;



import engine.render.ray.preview.*;
import engine.render.ray.bvh.*;
final class PathTracerTransport {
    private final PathTracerRenderer owner;

    PathTracerTransport(PathTracerRenderer owner) {
        this.owner = owner;
    }

    void tracePreviewPath(PathTracerRenderer.TraceContext ctx, SplitMix64 rng, int effectiveMaxBounces) {
        owner.tracePreviewPathInternal(ctx, rng, effectiveMaxBounces);
    }

    void traceReferencePath(PathTracerRenderer.TraceContext ctx, SplitMix64 rng) {
        ReferencePathState path = ctx.referencePath.reset(ctx);
        double featureWeight = owner.sampleDrivenFeatureWeight();
        boolean globalVolumeActive = owner.isGlobalVolumeEnabled() && owner.globalVolumeDensity() * featureWeight > 1e-6;
        double volumeDensity = globalVolumeActive ? Math.max(1e-6, owner.globalVolumeDensity() * featureWeight) : 0.0;
        double volumeAlbedoR = PathTracerRenderer.clamp01(owner.globalVolumeAlbedoR() * (0.75 + 0.25 * featureWeight));
        double volumeAlbedoG = PathTracerRenderer.clamp01(owner.globalVolumeAlbedoG() * (0.75 + 0.25 * featureWeight));
        double volumeAlbedoB = PathTracerRenderer.clamp01(owner.globalVolumeAlbedoB() * (0.75 + 0.25 * featureWeight));
        double volumeEmissionR = Math.max(0.0, owner.globalVolumeEmissionR() * featureWeight);
        double volumeEmissionG = Math.max(0.0, owner.globalVolumeEmissionG() * featureWeight);
        double volumeEmissionB = Math.max(0.0, owner.globalVolumeEmissionB() * featureWeight);
        boolean spectral14Active = owner.isPreviewStillFullTierActive();
        if (spectral14Active) {
            int heroBand = Math.min(owner.spectralBandCount() - 1, (int) (rng.nextDouble() * owner.spectralBandCount()));
            ctx.spectralHeroBand = heroBand;
            ctx.spectralCompanionBand = (heroBand + (owner.spectralBandCount() / 2)) % owner.spectralBandCount();
        } else {
            ctx.spectralHeroBand = -1;
            ctx.spectralCompanionBand = -1;
        }
        ReferenceSurfaceLobes lobes = ctx.referenceLobes;
        ReferenceBounceSample bounceSample = ctx.referenceBounce;

        for (int bounce = 0; bounce < owner.maxBounces(); bounce++) {
            boolean hasSurfaceHit = owner.intersectClosest(
                    path.ox,
                    path.oy,
                    path.oz,
                    path.dx,
                    path.dy,
                    path.dz,
                    owner.rayEps(),
                    owner.infT(),
                    ctx.hit,
                    ctx);
            if (globalVolumeActive) {
                double mediumMaxDistance = hasSurfaceHit ? ctx.hit.t : owner.globalVolumeMaxDistance();
                if (mediumMaxDistance > owner.rayEps()) {
                    double sampleDistance = -Math.log(Math.max(1e-9, 1.0 - rng.nextDouble())) / volumeDensity;
                    if (sampleDistance < mediumMaxDistance) {
                        double tr = Math.exp(-volumeDensity * sampleDistance);
                        double oneMinusTr = 1.0 - tr;
                        path.radianceR += path.throughputR * volumeEmissionR * oneMinusTr;
                        path.radianceG += path.throughputG * volumeEmissionG * oneMinusTr;
                        path.radianceB += path.throughputB * volumeEmissionB * oneMinusTr;
                        path.throughputR *= tr;
                        path.throughputG *= tr;
                        path.throughputB *= tr;

                        double scatterX = path.ox + path.dx * sampleDistance;
                        double scatterY = path.oy + path.dy * sampleDistance;
                        double scatterZ = path.oz + path.dz * sampleDistance;
                        path.throughputR *= volumeAlbedoR;
                        path.throughputG *= volumeAlbedoG;
                        path.throughputB *= volumeAlbedoB;

                        double phasePdf = PathTracerRenderer.sampleHenyeyGreensteinDirection(
                                path.dx,
                                path.dy,
                                path.dz,
                                owner.globalVolumeAnisotropy(),
                                rng,
                                ctx);
                        path.ox = scatterX + ctx.sampleDx * owner.rayEps();
                        path.oy = scatterY + ctx.sampleDy * owner.rayEps();
                        path.oz = scatterZ + ctx.sampleDz * owner.rayEps();
                        path.dx = ctx.sampleDx;
                        path.dy = ctx.sampleDy;
                        path.dz = ctx.sampleDz;
                        path.lastEventDelta = false;
                        path.lastBsdfPdf = phasePdf;
                        path.lastSurfaceValid = false;
                        continue;
                    }
                    double segTr = Math.exp(-volumeDensity * mediumMaxDistance);
                    double segOneMinusTr = 1.0 - segTr;
                    path.radianceR += path.throughputR * volumeEmissionR * segOneMinusTr;
                    path.radianceG += path.throughputG * volumeEmissionG * segOneMinusTr;
                    path.radianceB += path.throughputB * volumeEmissionB * segOneMinusTr;
                    path.throughputR *= segTr;
                    path.throughputG *= segTr;
                    path.throughputB *= segTr;
                }
            }

            if (!hasSurfaceHit) {
                owner.accumulateReferenceMiss(path, bounce, ctx);
                break;
            }

            Triangle tri = ctx.hit.triangle;
            owner.sampleSurface(tri, ctx.hit, path.dx, path.dy, path.dz, ctx.surface, ctx);
            if (ctx.surface.discard) {
                owner.advancePrimaryGuide(ctx);
                path.ox = ctx.hit.px + path.dx * owner.rayEps();
                path.oy = ctx.hit.py + path.dy * owner.rayEps();
                path.oz = ctx.hit.pz + path.dz * owner.rayEps();
                continue;
            }

            owner.populateReferenceSurfaceLobes(ctx.surface, path.dx, path.dy, path.dz, lobes);
            owner.accumulateReferenceEmission(path, ctx.hit, ctx.surface, bounce, ctx);
            if (owner.isDirectLightingEnabled()) {
                owner.accumulateReferenceDirectLighting(path, bounce, ctx.hit, ctx.surface, lobes, rng, ctx);
            }
            if (!owner.sampleReferenceNextBounce(ctx.surface, path, lobes, rng, bounceSample, ctx)) {
                break;
            }
            double throughputClamp = owner.throughputClampScale(path.throughputR, path.throughputG, path.throughputB, bounce);
            if (throughputClamp < 1.0) {
                path.throughputR *= throughputClamp;
                path.throughputG *= throughputClamp;
                path.throughputB *= throughputClamp;
            }
            if (!owner.survivesReferenceRussianRoulette(bounce, path, rng)) {
                break;
            }
            owner.advanceReferencePathState(path, ctx.hit, ctx.surface, bounceSample);
        }

        owner.markPrimaryGuideMiss(ctx);
        ctx.outR = path.radianceR;
        ctx.outG = path.radianceG;
        ctx.outB = path.radianceB;
    }
}


