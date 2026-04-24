package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class SeparateRgbNodeDefinition extends BaseMaterialNodeDefinition {
    public SeparateRgbNodeDefinition() {
        super(
                "Separate RGB",
                "Converter",
                0x7A5AA8,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("red", "Red", MaterialNodeGraph.ValueType.VALUE),
                        out("green", "Green", MaterialNodeGraph.ValueType.VALUE),
                        out("blue", "Blue", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }
}
