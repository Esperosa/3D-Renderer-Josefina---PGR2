package engine.material;

import engine.math.MathUtil;
import engine.math.Vec3;

/**
 * vyhodnocuju povrchové větve shaderu materiálového grafu.
 */
final class MaterialGraphSurfaceEvaluator {

    private MaterialGraphSurfaceEvaluator() {
    }

    static MaterialGraphEvaluator.SurfaceData evaluate(MaterialGraphEvaluator.EvalState state, int nodeId) {
        if (state.surfaceCache.containsKey(nodeId)) {
            return state.surfaceCache.get(nodeId);
        }
        MaterialNodeGraph.Node node = state.graph.getNodeById(nodeId);
        if (node == null) {
            return null;
        }
        String cycleKey = "surface:" + nodeId;
        if (!state.activeKeys.add(cycleKey)) {
            return null;
        }
        MaterialGraphEvaluator.SurfaceData out;
        try {
            out = switch (node.getType()) {
                case PRINCIPLED_BSDF -> evaluatePrincipledSurface(state, node);
                case GLASS_BSDF -> evaluateGlassSurface(state, node);
                case EMISSION_SHADER -> evaluateEmissionSurface(state, node);
                case MIX_SHADER -> evaluateMixShaderSurface(state, node);
                case TRANSPARENT_BSDF -> evaluateTransparentSurface(state, node);
                default -> null;
            };
        } finally {
            state.activeKeys.remove(cycleKey);
        }
        if (out != null) {
            state.surfaceCache.put(nodeId, out);
        }
        return out;
    }

    static MaterialGraphEvaluator.SurfaceData defaultSurface(MaterialGraphEvaluator.EvalState state, PhongMaterial material) {
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        if (material == null) {
            out.baseColor.set(0.7, 0.7, 0.7);
        } else {
            out.baseColor.set(material.getDiffuseColor());
        }
        out.roughness = material == null ? 0.55 : material.getRoughness();
        out.metallic = material == null ? 0.0 : material.getMetallic();
        out.specularFactor = material == null ? 1.0 : material.getSpecularFactor();
        out.refractiveIndex = material == null ? 1.0 : material.getRefractiveIndex();
        out.dispersion = material == null ? 0.0 : material.getDispersion();
        out.transmission = material == null ? 0.0 : material.getTransmission();
        out.opacity = material == null ? 1.0 : material.getOpacity();
        out.emissionColor.set(material == null ? Vec3.ZERO : material.getEmissionColor());
        out.emissionStrength = material == null ? 0.0 : material.getEmissionStrength();
        out.clearcoatFactor = material == null ? 0.0 : material.getClearcoatFactor();
        out.clearcoatRoughness = material == null ? 0.0 : material.getClearcoatRoughness();
        out.sheenColor.set(material == null ? Vec3.ZERO : material.getSheenColor());
        out.sheenRoughness = material == null ? 0.0 : material.getSheenRoughness();
        return out;
    }

    private static MaterialGraphEvaluator.SurfaceData evaluatePrincipledSurface(MaterialGraphEvaluator.EvalState state,
                                                                                MaterialNodeGraph.Node node) {
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        out.baseColor.set(MaterialGraphValueEvaluator.resolveColorInput(
                state, node, "base_color", node.getColor("base_color", state.borrowColor().set(0.78, 0.78, 0.78))));
        out.roughness = MaterialGraphValueEvaluator.resolveValueInput(state, node, "roughness", node.getNumber("roughness", 0.45));
        out.metallic = MaterialGraphValueEvaluator.resolveValueInput(state, node, "metallic", node.getNumber("metallic", 0.0));
        out.specularFactor = MaterialGraphValueEvaluator.resolveValueInput(state, node, "specular", node.getNumber("specular", 1.0));
        out.refractiveIndex = Math.max(1.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "ior", node.getNumber("ior", 1.45)));
        out.dispersion = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "dispersion", node.getNumber("dispersion", 0.0)));
        out.transmission = MaterialGraphValueEvaluator.resolveValueInput(state, node, "transmission", node.getNumber("transmission", 0.0));
        out.opacity = MaterialGraphValueEvaluator.resolveValueInput(state, node, "opacity", node.getNumber("opacity", 1.0));
        out.emissionColor.set(MaterialGraphValueEvaluator.resolveColorInput(state, node, "emission", node.getColor("emission", Vec3.ZERO)));
        out.emissionStrength = Math.max(0.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "emission_strength", node.getNumber("emission_strength", 0.0)));
        out.clearcoatFactor = MaterialGraphValueEvaluator.resolveValueInput(state, node, "clearcoat", node.getNumber("clearcoat", 0.0));
        out.clearcoatRoughness = MaterialGraphValueEvaluator.resolveValueInput(state, node, "clearcoat_roughness", node.getNumber("clearcoat_roughness", 0.0));
        out.sheenColor.set(MaterialGraphValueEvaluator.resolveColorInput(state, node, "sheen_color", node.getColor("sheen_color", Vec3.ZERO)));
        out.sheenRoughness = MaterialGraphValueEvaluator.resolveValueInput(state, node, "sheen_roughness", node.getNumber("sheen_roughness", 0.0));
        MaterialGraphValueEvaluator.clampColorInPlace(out.baseColor);
        MaterialGraphValueEvaluator.clampColorInPlace(out.emissionColor);
        MaterialGraphValueEvaluator.clampColorInPlace(out.sheenColor);
        return out;
    }

    private static MaterialGraphEvaluator.SurfaceData evaluateGlassSurface(MaterialGraphEvaluator.EvalState state,
                                                                           MaterialNodeGraph.Node node) {
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        out.baseColor.set(MaterialGraphValueEvaluator.resolveColorInput(
                state, node, "color", node.getColor("color", state.borrowColor().set(0.88, 0.94, 1.0))));
        MaterialGraphValueEvaluator.clampColorInPlace(out.baseColor);
        out.roughness = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "roughness",
                node.getNumber("roughness", 0.04)));
        out.metallic = 0.0;
        out.specularFactor = 1.0;
        out.refractiveIndex = Math.max(1.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "ior",
                node.getNumber("ior", 1.45)));
        out.dispersion = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "dispersion",
            node.getNumber("dispersion", 0.0)));
        out.transmission = 1.0;
        out.opacity = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "opacity",
                node.getNumber("opacity", 1.0)));
        out.emissionColor.zero();
        out.emissionStrength = 0.0;
        out.clearcoatFactor = 0.0;
        out.clearcoatRoughness = 0.0;
        out.sheenColor.zero();
        out.sheenRoughness = 0.0;
        return out;
    }

    private static MaterialGraphEvaluator.SurfaceData evaluateEmissionSurface(MaterialGraphEvaluator.EvalState state,
                                                                              MaterialNodeGraph.Node node) {
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        Vec3 defaultColor = node.getColor("color", state.borrowColor().set(1.0, 0.82, 0.56));
        Vec3 color = MaterialGraphValueEvaluator.copyColor(
                MaterialGraphValueEvaluator.resolveColorInput(state, node, "color", node.getColor("color", defaultColor)),
                state.borrowColor());
        MaterialGraphValueEvaluator.clampColorInPlace(color);
        out.baseColor.set(color).mulInPlace(0.22);
        out.roughness = 1.0;
        out.metallic = 0.0;
        out.specularFactor = 0.0;
        out.refractiveIndex = 1.0;
        out.dispersion = 0.0;
        out.transmission = 0.0;
        out.opacity = 1.0;
        out.emissionColor.set(color);
        out.emissionStrength = Math.max(0.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "strength",
                node.getNumber("strength", 2.0)));
        out.clearcoatFactor = 0.0;
        out.clearcoatRoughness = 0.0;
        out.sheenColor.zero();
        out.sheenRoughness = 0.0;
        return out;
    }

    private static MaterialGraphEvaluator.SurfaceData evaluateMixShaderSurface(MaterialGraphEvaluator.EvalState state,
                                                                               MaterialNodeGraph.Node node) {
        MaterialGraphEvaluator.SurfaceData a = resolveSurfaceInput(state, node, "shader_a", defaultSurface(state, state.material));
        MaterialGraphEvaluator.SurfaceData b = resolveSurfaceInput(state, node, "shader_b", defaultSurface(state, state.material));
        double factor = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "factor", node.getNumber("factor", 0.5)));
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        Vec3.lerp(a.baseColor, b.baseColor, factor, out.baseColor);
        out.roughness = MaterialGraphValueEvaluator.mix(a.roughness, b.roughness, factor);
        out.metallic = MaterialGraphValueEvaluator.mix(a.metallic, b.metallic, factor);
        out.specularFactor = MaterialGraphValueEvaluator.mix(a.specularFactor, b.specularFactor, factor);
        out.refractiveIndex = MaterialGraphValueEvaluator.mix(a.refractiveIndex, b.refractiveIndex, factor);
        out.dispersion = MaterialGraphValueEvaluator.mix(a.dispersion, b.dispersion, factor);
        out.transmission = MaterialGraphValueEvaluator.mix(a.transmission, b.transmission, factor);
        out.opacity = MaterialGraphValueEvaluator.mix(a.opacity, b.opacity, factor);
        Vec3.lerp(a.emissionColor, b.emissionColor, factor, out.emissionColor);
        out.emissionStrength = MaterialGraphValueEvaluator.mix(a.emissionStrength, b.emissionStrength, factor);
        out.clearcoatFactor = MaterialGraphValueEvaluator.mix(a.clearcoatFactor, b.clearcoatFactor, factor);
        out.clearcoatRoughness = MaterialGraphValueEvaluator.mix(a.clearcoatRoughness, b.clearcoatRoughness, factor);
        Vec3.lerp(a.sheenColor, b.sheenColor, factor, out.sheenColor);
        out.sheenRoughness = MaterialGraphValueEvaluator.mix(a.sheenRoughness, b.sheenRoughness, factor);
        return out;
    }

    private static MaterialGraphEvaluator.SurfaceData resolveSurfaceInput(MaterialGraphEvaluator.EvalState state,
                                                                          MaterialNodeGraph.Node node,
                                                                          String inputSocket,
                                                                          MaterialGraphEvaluator.SurfaceData fallback) {
        MaterialNodeGraph.Link link = state.graph.findInputLink(node.getId(), inputSocket);
        if (link == null) {
            return fallback;
        }
        MaterialGraphEvaluator.SurfaceData surface = evaluate(state, link.getFromNodeId());
        return surface == null ? fallback : surface;
    }

    private static MaterialGraphEvaluator.SurfaceData evaluateTransparentSurface(MaterialGraphEvaluator.EvalState state,
                                                                                 MaterialNodeGraph.Node node) {
        MaterialGraphEvaluator.SurfaceData out = state.borrowSurface();
        out.baseColor.set(MaterialGraphValueEvaluator.resolveColorInput(state, node, "color", node.getColor("color", Vec3.ONE)));
        MaterialGraphValueEvaluator.clampColorInPlace(out.baseColor);
        out.roughness = 0.0;
        out.metallic = 0.0;
        out.specularFactor = 0.0;
        out.refractiveIndex = 1.0;
        out.dispersion = 0.0;
        out.transmission = 1.0;
        out.opacity = MathUtil.clamp01(MaterialGraphValueEvaluator.resolveValueInput(state, node, "opacity", node.getNumber("opacity", 0.0)));
        out.emissionColor.zero();
        out.emissionStrength = 0.0;
        out.clearcoatFactor = 0.0;
        out.clearcoatRoughness = 0.0;
        out.sheenColor.zero();
        out.sheenRoughness = 0.0;
        return out;
    }
}