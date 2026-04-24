package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class MixColorNodeDefinition extends BaseMaterialNodeDefinition {
    public MixColorNodeDefinition() {
        super(
                "Mix Color",
                "Color",
                0x8F5E7E,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color_a", "Color A", MaterialNodeGraph.ValueType.COLOR),
                        in("color_b", "Color B", MaterialNodeGraph.ValueType.COLOR),
                        in("factor", "Factor", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color_a", new Vec3(0.2, 0.2, 0.2));
        node.setColor("color_b", new Vec3(0.85, 0.85, 0.85));
        node.setNumber("factor", 0.5);
        node.setEnum("blend_mode", MaterialNodeGraph.BlendMode.MIX.name());
    }
}
