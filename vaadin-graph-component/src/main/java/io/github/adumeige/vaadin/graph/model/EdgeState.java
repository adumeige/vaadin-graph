package io.github.adumeige.vaadin.graph.model;

import java.io.Serializable;

/** Client-facing edge data. Part of {@link WorkspaceState}; targets are resolved to ids. */
public record EdgeState(
        String id,
        String sourceId,
        String targetId,
        String label,
        double labelOffset,
        String color,
        int strokeWidth,
        EdgeRouting routing,
        ArrowType startArrow,
        ArrowType endArrow,
        EdgeStyle style
) implements Serializable {
}
