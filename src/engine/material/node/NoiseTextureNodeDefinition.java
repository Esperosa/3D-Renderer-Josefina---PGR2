package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class NoiseTextureNodeDefinition extends BaseMaterialNodeDefinition {
    public NoiseTextureNodeDefinition() {
        super(
                "Noise Texture",
                "Texture",
                0x5D6E92,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("scale", "Scale", MaterialNodeGraph.ValueType.VALUE),
                        in("detail", "Detail", MaterialNodeGraph.ValueType.VALUE),
                        in("roughness", "Roughness", MaterialNodeGraph.ValueType.VALUE),
                        in("distortion", "Distortion", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        out("factor", "Factor", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("scale", 5.0);
        node.setNumber("detail", 4.0);
        node.setNumber("roughness", 0.55);
        node.setNumber("distortion", 0.15);
        node.setEnum("coordinate_source", MaterialNodeGraph.CoordinateSource.UV0.name());
        node.setNumber("seed", 1.0);
    }
}
