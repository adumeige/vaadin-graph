package io.github.adumeige.vaadin.graph.routing;

import java.util.List;

import static io.github.adumeige.vaadin.graph.routing.Geometry.rectBoundaryIntersect;

/** Straight router: a single boundary-to-boundary segment (two points). */
public class StraightRouter implements EdgeRouter {

    @Override
    public List<Point2D> route(Rect source, Rect target) {
        Point2D p1 = rectBoundaryIntersect(source, new Point2D(target.cx(), target.cy()));
        Point2D p2 = rectBoundaryIntersect(target, new Point2D(source.cx(), source.cy()));
        return List.of(p1, p2);
    }
}
