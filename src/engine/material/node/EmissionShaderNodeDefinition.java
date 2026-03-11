package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class EmissionShaderNodeDefinition extends BaseMaterialNodeDefinition {
    public EmissionShaderNodeDefinition() {
        super(
                "Emission",
                "Shader",
                0xD16F2C,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        in("strength", "Strength", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("emission", "Emission", MaterialNodeGraph.ValueType.SURFACE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new engine.math.Vec3(1.0, 0.82, 0.56));
        node.setNumber("strength", 2.0);
    }
}
