package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ValueNodeDefinition extends BaseMaterialNodeDefinition {
    public ValueNodeDefinition() {
        super(
                "Value",
                "Input",
                0x728A4A,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("value", "Value", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("value", 0.5);
        node.setNumber("slider_min", 0.0);
        node.setNumber("slider_max", 1.0);
    }
}
