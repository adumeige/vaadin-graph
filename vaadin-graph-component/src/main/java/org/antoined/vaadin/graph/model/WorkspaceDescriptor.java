package org.antoined.vaadin.graph.model;

import java.util.List;

/**
 * The topology and geometry of a single node, returned by the {@code describe} lambda. Holds the
 * node's center, size, and outgoing edges. The {@code edges} list defaults to empty.
 *
 * @param <T> the domain type of graph nodes
 */
public record WorkspaceDescriptor<T>(
        double cx,
        double cy,
        double width,
        double height,
        List<EdgeDescriptor<T>> edges
) {

    public WorkspaceDescriptor {
        edges = edges == null ? List.of() : List.copyOf(edges);
    }

    /** A node with no outgoing edges. */
    public WorkspaceDescriptor(double cx, double cy, double width, double height) {
        this(cx, cy, width, height, List.of());
    }
}
