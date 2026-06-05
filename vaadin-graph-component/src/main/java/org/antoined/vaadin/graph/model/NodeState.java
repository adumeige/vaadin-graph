package org.antoined.vaadin.graph.model;

import java.io.Serializable;

/** Client-facing node geometry. Part of {@link WorkspaceState}; carries no domain object. */
public record NodeState(
        String id,
        double cx,
        double cy,
        double width,
        double height
) implements Serializable {
}
