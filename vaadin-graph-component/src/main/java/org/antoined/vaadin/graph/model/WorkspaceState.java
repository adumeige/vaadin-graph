package org.antoined.vaadin.graph.model;

import java.io.Serializable;
import java.util.List;

/**
 * The complete, domain-free snapshot serialized and pushed to the client on every refresh.
 *
 * <p>Viewport handling: a non-null {@link #viewport} pins an explicit transform; otherwise
 * {@link #fit} requests a one-shot fit-to-content. When both are absent the client preserves its
 * current pan/zoom — so data refreshes (e.g. nudging a node) don't recenter the view.
 */
public record WorkspaceState(
        List<NodeState> nodes,
        List<EdgeState> edges,
        WorkspaceGrid grid,
        ViewportState viewport,
        boolean fit,
        double edgeSpacing
) implements Serializable {
}
