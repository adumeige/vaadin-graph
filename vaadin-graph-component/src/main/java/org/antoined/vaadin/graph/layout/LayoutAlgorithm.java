package org.antoined.vaadin.graph.layout;

import org.antoined.vaadin.graph.routing.Point2D;

import java.io.Serializable;
import java.util.Map;

/**
 * Computes new center positions for graph nodes. Implementations are interchangeable — overlap
 * removal today, force-directed / hierarchical / orthogonal layouts later — so callers depend only
 * on this interface.
 *
 * @see org.antoined.vaadin.graph.component.WorkspaceView#applyLayout(LayoutAlgorithm)
 */
@FunctionalInterface
public interface LayoutAlgorithm extends Serializable {

    /**
     * Returns new centers keyed by node id. Ids not present in the result keep their current
     * position; unknown ids are ignored.
     */
    Map<String, Point2D> layout(LayoutInput input);
}
