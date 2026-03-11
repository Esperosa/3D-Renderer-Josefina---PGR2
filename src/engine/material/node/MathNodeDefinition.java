package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class MathNodeDefinition extends BaseMaterialNodeDefinition {
    public MathNodeDefinition() {
        super(
                "Math",
                "Utility",
                0x5B7A8F,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("a", "A", MaterialNodeGraph.ValueType.VALUE),
                        in("b", "B", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("value", "Value", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("a", 0.5);
        node.setNumber("b", 0.5);
        node.setEnum("operation", MaterialNodeGraph.MathOperation.MULTIPLY.name());
    }
}
