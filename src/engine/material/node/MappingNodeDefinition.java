package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class MappingNodeDefinition extends BaseMaterialNodeDefinition {
    public MappingNodeDefinition() {
        super(
                "Mapping",
                "Input",
                0x5E7FAF,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("vector", "Vector", MaterialNodeGraph.ValueType.VECTOR)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("vector", "Vector", MaterialNodeGraph.ValueType.VECTOR)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("location_x", 0.0);
        node.setNumber("location_y", 0.0);
        node.setNumber("location_z", 0.0);
        node.setNumber("rotation_x", 0.0);
        node.setNumber("rotation_y", 0.0);
        node.setNumber("rotation_z", 0.0);
        node.setNumber("scale_x", 1.0);
        node.setNumber("scale_y", 1.0);
        node.setNumber("scale_z", 1.0);
    }
}
