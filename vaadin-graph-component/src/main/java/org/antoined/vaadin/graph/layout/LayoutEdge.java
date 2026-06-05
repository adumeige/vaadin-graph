package org.antoined.vaadin.graph.layout;

/** A directed connection between two {@link LayoutNode} ids, for layouts that consider topology. */
public record LayoutEdge(String sourceId, String targetId) {
}
