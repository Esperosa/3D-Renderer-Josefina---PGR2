package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ImportedEmissiveNodeDefinition extends BaseMaterialNodeDefinition {
    public ImportedEmissiveNodeDefinition() {
        super(
                "Imported Emissive",
                "Texture",
                0x366BAA,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR)
                }
        );
    }
}
