package org.antoined.vaadin.graph.routing;

import java.util.List;

import static org.antoined.vaadin.graph.routing.Geometry.rectBoundaryIntersect;

/**
 * Orthogonal router: three axis-aligned segments (four points) with the elbow placed on the
 * mid-line of the dominant axis. Mirrors {@code ThreeSegmentRouter} in {@code router.ts}.
 */
public class ThreeSegmentRouter implements EdgeRouter {

    @Override
    public List<Point2D> route(Rect source, Rect target) {
        Point2D p1 = rectBoundaryIntersect(source, new Point2D(target.cx(), target.cy()));
        Point2D p2 = rectBoundaryIntersect(target, new Point2D(source.cx(), source.cy()));

        double dx = Math.abs(target.cx() - source.cx());
        double dy = Math.abs(target.cy() - source.cy());

        if (dx >= dy) {
            double mx = (p1.x() + p2.x()) / 2;
            return List.of(p1, new Point2D(mx, p1.y()), new Point2D(mx, p2.y()), p2);
        } else {
            double my = (p1.y() + p2.y()) / 2;
            return List.of(p1, new Point2D(p1.x(), my), new Point2D(p2.x(), my), p2);
        }
    }
}
