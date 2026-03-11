package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class MapRangeNodeDefinition extends BaseMaterialNodeDefinition {
    public MapRangeNodeDefinition() {
        super(
                "Map Range",
                "Utility",
                0x4E708A,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("value", "Value", MaterialNodeGraph.ValueType.VALUE),
                        in("from_min", "From Min", MaterialNodeGraph.ValueType.VALUE),
                        in("from_max", "From Max", MaterialNodeGraph.ValueType.VALUE),
                        in("to_min", "To Min", MaterialNodeGraph.ValueType.VALUE),
                        in("to_max", "To Max", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("value", "Value", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("value", 0.5);
        node.setNumber("from_min", 0.0);
        node.setNumber("from_max", 1.0);
        node.setNumber("to_min", 0.0);
        node.setNumber("to_max", 1.0);
    }
}
