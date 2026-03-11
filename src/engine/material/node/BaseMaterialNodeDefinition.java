package engine.material.node;

import engine.material.MaterialNodeGraph;

abstract class BaseMaterialNodeDefinition implements MaterialNodeDefinition {
    private final String title;
    private final String category;
    private final int accentRgb;
    private final boolean deletable;
    private final MaterialNodeGraph.SocketDefinition[] inputs;
    private final MaterialNodeGraph.SocketDefinition[] outputs;

    BaseMaterialNodeDefinition(String title,
                               String category,
                               int accentRgb,
                               boolean deletable,
                               MaterialNodeGraph.SocketDefinition[] inputs,
                               MaterialNodeGraph.SocketDefinition[] outputs) {
        this.title = title;
        this.category = category;
        this.accentRgb = accentRgb;
        this.deletable = deletable;
        this.inputs = inputs == null ? new MaterialNodeGraph.SocketDefinition[0] : inputs;
        this.outputs = outputs == null ? new MaterialNodeGraph.SocketDefinition[0] : outputs;
    }

    @Override
    public final String title() {
        return title;
    }

    @Override
    public final String category() {
        return category;
    }

    @Override
    public final int accentRgb() {
        return accentRgb;
    }

    @Override
    public final boolean isDeletable() {
        return deletable;
    }

    @Override
    public final MaterialNodeGraph.SocketDefinition[] inputs() {
        return inputs;
    }

    @Override
    public final MaterialNodeGraph.SocketDefinition[] outputs() {
        return outputs;
    }

    protected static MaterialNodeGraph.SocketDefinition in(String key,
                                                           String label,
                                                           MaterialNodeGraph.ValueType type) {
        return MaterialNodeGraph.inputSocket(key, label, type);
    }

    protected static MaterialNodeGraph.SocketDefinition out(String key,
                                                            String label,
                                                            MaterialNodeGraph.ValueType type) {
        return MaterialNodeGraph.outputSocket(key, label, type);
    }
}
