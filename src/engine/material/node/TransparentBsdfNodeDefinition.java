package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class TransparentBsdfNodeDefinition extends BaseMaterialNodeDefinition {
    public TransparentBsdfNodeDefinition() {
        super(
                "Transparent BSDF",
                "Shader",
                0x6C8ED6,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        in("opacity", "Opacity", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("bsdf", "BSDF", MaterialNodeGraph.ValueType.SURFACE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("color", new Vec3(1.0, 1.0, 1.0));
        node.setNumber("opacity", 0.0);
    }
}
