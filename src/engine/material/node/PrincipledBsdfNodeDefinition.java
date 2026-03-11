package engine.material.node;

import engine.material.MaterialNodeGraph;
import engine.math.Vec3;

public final class PrincipledBsdfNodeDefinition extends BaseMaterialNodeDefinition {
    public PrincipledBsdfNodeDefinition() {
        super(
                "Principled BSDF",
                "Shader",
                0xB17630,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("base_color", "Base Color", MaterialNodeGraph.ValueType.COLOR),
                        in("roughness", "Roughness", MaterialNodeGraph.ValueType.VALUE),
                        in("metallic", "Metallic", MaterialNodeGraph.ValueType.VALUE),
                        in("specular", "Specular", MaterialNodeGraph.ValueType.VALUE),
                        in("ior", "IOR", MaterialNodeGraph.ValueType.VALUE),
                        in("transmission", "Transmission", MaterialNodeGraph.ValueType.VALUE),
                        in("opacity", "Opacity", MaterialNodeGraph.ValueType.VALUE),
                        in("emission", "Emission", MaterialNodeGraph.ValueType.COLOR),
                        in("emission_strength", "Emission Strength", MaterialNodeGraph.ValueType.VALUE),
                        in("clearcoat", "Clearcoat", MaterialNodeGraph.ValueType.VALUE),
                        in("clearcoat_roughness", "Clearcoat Roughness", MaterialNodeGraph.ValueType.VALUE),
                        in("sheen_color", "Sheen Color", MaterialNodeGraph.ValueType.COLOR),
                        in("sheen_roughness", "Sheen Roughness", MaterialNodeGraph.ValueType.VALUE),
                        in("normal", "Normal", MaterialNodeGraph.ValueType.VECTOR)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("bsdf", "BSDF", MaterialNodeGraph.ValueType.SURFACE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setColor("base_color", new Vec3(0.78, 0.78, 0.78));
        node.setNumber("roughness", 0.45);
        node.setNumber("metallic", 0.0);
        node.setNumber("specular", 1.0);
        node.setNumber("ior", 1.45);
        node.setNumber("transmission", 0.0);
        node.setNumber("opacity", 1.0);
        node.setColor("emission", Vec3.ZERO);
        node.setNumber("emission_strength", 0.0);
        node.setNumber("clearcoat", 0.0);
        node.setNumber("clearcoat_roughness", 0.0);
        node.setColor("sheen_color", Vec3.ZERO);
        node.setNumber("sheen_roughness", 0.0);
    }
}
