package io.github.adumeige.vaadin.graph.layout;

/** A node's current geometry, fed to a {@link LayoutAlgorithm}. {@code id} matches the node slot id. */
public record LayoutNode(String id, double cx, double cy, double width, double height) {
}
