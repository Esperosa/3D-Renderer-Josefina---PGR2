package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class ImageTextureNodeDefinition extends BaseMaterialNodeDefinition {
    public ImageTextureNodeDefinition() {
        super(
                "Image Texture",
                "Texture",
                0x3E7BA8,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("vector", "Vector", MaterialNodeGraph.ValueType.VECTOR)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("color", "Color", MaterialNodeGraph.ValueType.COLOR),
                        out("alpha", "Alpha", MaterialNodeGraph.ValueType.VALUE),
                        out("red", "Red", MaterialNodeGraph.ValueType.VALUE),
                        out("green", "Green", MaterialNodeGraph.ValueType.VALUE),
                        out("blue", "Blue", MaterialNodeGraph.ValueType.VALUE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setText("file_path", "");
        node.setEnum("uv_set", "UV0");
        node.setEnum("color_space", MaterialNodeGraph.TextureColorSpace.SRGB.name());
        node.setNumber("linear", 1.0);
        node.setNumber("flip_v", 0.0);
        node.setNumber("offset_u", 0.0);
        node.setNumber("offset_v", 0.0);
        node.setNumber("scale_u", 1.0);
        node.setNumber("scale_v", 1.0);
        node.setNumber("rotation", 0.0);
        node.setColor("fallback_color", Vec3.ONE);
        node.setNumber("fallback_alpha", 1.0);
    }
}
