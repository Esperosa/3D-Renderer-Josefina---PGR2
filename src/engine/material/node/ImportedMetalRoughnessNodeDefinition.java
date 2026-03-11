package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class ImportedMetalRoughnessNodeDefinition extends BaseMaterialNodeDefinition {
    public ImportedMetalRoughnessNodeDefinition() {
        super(
                "Imported Metal/Rough",
                "Texture",
                0x366BAA,
                true,
                new MaterialNodeGraph.SocketDefinition[0],
                new MaterialNodeGraph.SocketDefinition[]{
                        out("roughness", "Roughness", MaterialNodeGraph.ValueType.VALUE),
                        out("metallic", "Metallic", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }
}
