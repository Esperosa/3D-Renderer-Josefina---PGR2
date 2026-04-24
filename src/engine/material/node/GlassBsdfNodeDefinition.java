package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class GlassBsdfNodeDefinition extends BaseMaterialNodeDefinition {
    public GlassBsdfNodeDefinition() {
        super(
                "Glass BSDF",
                "Shader",
                0x3E90C5,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        in("roughness", "Roughness", MaterialNodeGraph.ValueType.VALUE),
                        in("ior", "IOR", MaterialNodeGraph.ValueType.VALUE),
                        in("dispersion", "Dispersion", MaterialNodeGraph.ValueType.VALUE),
                        in("opacity", "Opacity", MaterialNodeGraph.ValueType.VALUE),
                        in("normal", "Normal", MaterialNodeGraph.ValueType.VECTOR)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("bsdf", "BSDF", MaterialNodeGraph.ValueType.SURFACE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new engine.math.Vec3(0.88, 0.94, 1.0));
        node.setNumber("roughness", 0.04);
        node.setNumber("ior", 1.45);
        node.setNumber("dispersion", 0.0);
        node.setNumber("opacity", 1.0);
    }
}
