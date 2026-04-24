package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class ColorRampNodeDefinition extends BaseMaterialNodeDefinition {
    public ColorRampNodeDefinition() {
        super(
                "ColorRamp",
                "Converter",
                0x9B7D3E,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("factor", "Factor", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        out("factor", "Factor", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color_a", new Vec3(0.08, 0.08, 0.08));
        node.setColor("color_b", new Vec3(0.95, 0.95, 0.95));
        node.setNumber("position_a", 0.18);
        node.setNumber("position_b", 0.82);
    }
}
