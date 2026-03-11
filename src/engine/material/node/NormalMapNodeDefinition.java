package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class NormalMapNodeDefinition extends BaseMaterialNodeDefinition {
    public NormalMapNodeDefinition() {
        super(
                "Normal Map",
                "Texture",
                0x4D8F88,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        in("strength", "Strength", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("normal", "Normal", MaterialNodeGraph.ValueType.VECTOR)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new Vec3(0.5, 0.5, 1.0));
        node.setNumber("strength", 1.0);
    }
}
