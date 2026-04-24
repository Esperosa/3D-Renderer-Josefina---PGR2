package engine.material.node;

import engine.material.MaterialNodeGraph;

public interface MaterialNodeDefinition {
    String title();

    String category();

    int accentRgb();

    boolean isDeletable();

    MaterialNodeGraph.SocketDefinition[] inputs();

    MaterialNodeGraph.SocketDefinition[] outputs();

    default void applyDefaults(MaterialNodeGraph.Node node) {
    }
}
