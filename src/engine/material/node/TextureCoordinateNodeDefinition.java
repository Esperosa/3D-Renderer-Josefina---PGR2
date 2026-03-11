package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class TextureCoordinateNodeDefinition extends BaseMaterialNodeDefinition {
    public TextureCoordinateNodeDefinition() {
        super(
                "Texture Coordinate",
                "Input",
                0x4A6CA8,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("uv0", "UV0", MaterialNodeGraph.ValueType.VECTOR),
                        out("uv1", "UV1", MaterialNodeGraph.ValueType.VECTOR),
                        out("world", "World", MaterialNodeGraph.ValueType.VECTOR)
                }
        );
    }
}
