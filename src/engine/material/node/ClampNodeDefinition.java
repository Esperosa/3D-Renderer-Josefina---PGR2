package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ClampNodeDefinition extends BaseMaterialNodeDefinition {
    public ClampNodeDefinition() {
        super(
                "Clamp",
                "Utility",
                0x587B62,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("value", "Value", MaterialNodeGraph.ValueType.VALUE),
                        in("min", "Min", MaterialNodeGraph.ValueType.VALUE),
                        in("max", "Max", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("value", "Value", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("value", 0.5);
        node.setNumber("min", 0.0);
        node.setNumber("max", 1.0);
    }
}
