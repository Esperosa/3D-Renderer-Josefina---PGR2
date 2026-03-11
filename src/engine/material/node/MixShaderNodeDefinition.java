package engine.material.node;

import engine.material.MaterialNodeGraph;

public final class MixShaderNodeDefinition extends BaseMaterialNodeDefinition {
    public MixShaderNodeDefinition() {
        super(
                "Mix Shader",
                "Shader",
                0x71549D,
                true,
                new MaterialNodeGraph.SocketDefinition[]{
                        in("shader_a", "Shader A", MaterialNodeGraph.ValueType.SURFACE),
                        in("shader_b", "Shader B", MaterialNodeGraph.ValueType.SURFACE),
                        in("factor", "Factor", MaterialNodeGraph.ValueType.VALUE)
                },
                new MaterialNodeGraph.SocketDefinition[]{
                        out("shader", "Shader", MaterialNodeGraph.ValueType.SURFACE)
                }
        );
    }

    @Override
    public void applyDefaults(MaterialNodeGraph.Node node) {
        node.setNumber("factor", 0.5);
    }
}
