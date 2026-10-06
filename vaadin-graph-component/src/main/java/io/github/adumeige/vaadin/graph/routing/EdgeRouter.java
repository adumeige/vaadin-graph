package io.github.adumeige.vaadin.graph.routing;

import java.io.Serializable;
import java.util.List;

/**
 * Computes the polyline an edge follows between two node rectangles.
 *
 * <p>The first returned point exits the source boundary; the last enters the target boundary.
 * Implementations clip at boundaries via {@link Geometry#rectBoundaryIntersect}.
 *
 * <p>Note: in this iteration edge geometry is computed on the client (see {@code router.ts}); the
 * server-side routers are provided for parity and headless/test use.
 */
@FunctionalInterface
public interface EdgeRouter extends Serializable {

    List<Point2D> route(Rect source, Rect target);
}
