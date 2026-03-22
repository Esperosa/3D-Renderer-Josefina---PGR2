package engine.material;

import engine.math.MathUtil;

/**
 * vyhodnocuju objemovou větev materiálového grafu.
 */
final class MaterialGraphVolumeEvaluator {

    private MaterialGraphVolumeEvaluator() {
    }

    static MaterialGraphEvaluator.VolumeData evaluate(MaterialGraphEvaluator.EvalState state, int nodeId) {
        if (state.volumeCache.containsKey(nodeId)) {
            return state.volumeCache.get(nodeId);
        }
        MaterialNodeGraph.Node node = state.graph.getNodeById(nodeId);
        if (node == null || node.getType() != MaterialNodeGraph.NodeType.VOLUME_MEDIUM) {
            return null;
        }
        String cycleKey = "volume:" + nodeId;
        if (!state.activeKeys.add(cycleKey)) {
            return null;
        }
        PhongMaterial material = state.material;
        MaterialGraphEvaluator.VolumeData out = state.borrowVolume();
        out.mediumColor.set(MaterialGraphValueEvaluator.resolveColorInput(state, node, "color", node.getColor("color", material.getMediumColor())));
        out.density = Math.max(0.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "density", node.getNumber("density", material.getDensity())));
        out.anisotropy = MathUtil.clamp(
                MaterialGraphValueEvaluator.resolveValueInput(state, node, "anisotropy", node.getNumber("anisotropy", material.getAnisotropy())),
                -0.99,
                0.99
        );
        out.thickness = Math.max(0.0, MaterialGraphValueEvaluator.resolveValueInput(state, node, "thickness", node.getNumber("thickness", material.getThickness())));
        MaterialGraphValueEvaluator.clampColorInPlace(out.mediumColor);
        state.activeKeys.remove(cycleKey);
        state.volumeCache.put(nodeId, out);
        return out;
    }
}