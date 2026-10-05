package io.github.adumeige.vaadin.graph.layout;

import java.util.List;

/** Snapshot of the graph passed to a {@link LayoutAlgorithm}: current node boxes and edges. */
public record LayoutInput(List<LayoutNode> nodes, List<LayoutEdge> edges) {
}
