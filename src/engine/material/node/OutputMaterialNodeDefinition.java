package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class OutputMaterialNodeDefinition extends BaseMaterialNodeDefinition {
    public OutputMaterialNodeDefinition() {
        super(
                "Material Output",
                "Render",
                0x7B53B5,
                false,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("surface", "Surface", MaterialNodeGraph.ValueType.SURFACE),
                        in("volume", "Volume", MaterialNodeGraph.ValueType.VOLUME)
                },
                new MaterialNodeGraph.SocketDefinition[0]
        );
    }
}
