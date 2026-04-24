package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ImportedBaseColorNodeDefinition extends BaseMaterialNodeDefinition {
    public ImportedBaseColorNodeDefinition() {
        super(
                "Imported Base Color",
                "Texture",
                0x366BAA,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        out("alpha", "Alpha", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }
}
