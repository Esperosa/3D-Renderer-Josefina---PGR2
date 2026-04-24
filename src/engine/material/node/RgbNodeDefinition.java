package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class RgbNodeDefinition extends BaseMaterialNodeDefinition {
    public RgbNodeDefinition() {
        super(
                "RGB",
                "Input",
                0xA14F4F,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new Vec3(0.8, 0.8, 0.8));
    }
}
