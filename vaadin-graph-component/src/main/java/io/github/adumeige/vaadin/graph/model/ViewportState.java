package io.github.adumeige.vaadin.graph.model;

import java.io.Serializable;

/** Pan/zoom transform pushed to the client. A null viewport means "fit content". */
public record ViewportState(
        double panX,
        double panY,
        double zoom
) implements Serializable {
}
