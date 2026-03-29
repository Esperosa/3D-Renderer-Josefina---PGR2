package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ImportedNormalNodeDefinition extends BaseMaterialNodeDefinition {
    public ImportedNormalNodeDefinition() {
        super(
                "Imported Normal",
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
