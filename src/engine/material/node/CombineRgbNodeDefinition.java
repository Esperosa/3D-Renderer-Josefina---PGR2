package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class CombineRgbNodeDefinition extends BaseMaterialNodeDefinition {
    public CombineRgbNodeDefinition() {
        super(
                "Combine RGB",
                "Converter",
                0x8A658C,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("red", "Red", MaterialNodeGraph.ValueType.VALUE),
                        in("green", "Green", MaterialNodeGraph.ValueType.VALUE),
                        in("blue", "Blue", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("red", 0.8);
        node.setNumber("green", 0.8);
        node.setNumber("blue", 0.8);
    }
}
