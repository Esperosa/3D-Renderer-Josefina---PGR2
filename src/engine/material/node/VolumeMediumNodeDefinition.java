package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class VolumeMediumNodeDefinition extends BaseMaterialNodeDefinition {
    public VolumeMediumNodeDefinition() {
        super(
                "Volume Medium",
                "Volume",
                0x469C84,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        in("density", "Density", MaterialNodeGraph.ValueType.VALUE),
                        in("anisotropy", "Anisotropy", MaterialNodeGraph.ValueType.VALUE),
                        in("thickness", "Thickness", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("volume", "Volume", MaterialNodeGraph.ValueType.VOLUME)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new Vec3(0.85, 0.90, 1.0));
        node.setNumber("density", 0.0);
        node.setNumber("anisotropy", 0.0);
        node.setNumber("thickness", 0.1);
    }
}
