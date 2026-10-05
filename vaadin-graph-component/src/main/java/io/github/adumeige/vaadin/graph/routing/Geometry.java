package io.github.adumeige.vaadin.graph.routing;

/** Geometry helpers shared by the server-side edge routers. */
public final class Geometry {

    private Geometry() {
    }

    /**
     * Returns the point where the ray from the center of {@code rect} toward {@code toward}
     * exits the rectangle boundary. If {@code toward} coincides with the center, the center is
     * returned unchanged.
     *
     * <p>Mirrors {@code rectBoundaryIntersect} in {@code geometry.ts} so server and client agree.
     */
    public static Point2D rectBoundaryIntersect(Rect rect, Point2D toward) {
        double dx = toward.x() - rect.cx();
        double dy = toward.y() - rect.cy();
        if (dx == 0.0 && dy == 0.0) {
            return new Point2D(rect.cx(), rect.cy());
        }

        double hw = rect.width() / 2;
        double hh = rect.height() / 2;

        double best = Double.POSITIVE_INFINITY;
        if (dx != 0.0) {
            double t = (dx > 0 ? hw : -hw) / dx;
            if (t > 0.0 && t < best) {
                best = t;
            }
        }
        if (dy != 0.0) {
            double t = (dy > 0 ? hh : -hh) / dy;
            if (t > 0.0 && t < best) {
                best = t;
            }
        }
        if (Double.isInfinite(best)) {
            return new Point2D(rect.cx(), rect.cy());
        }
        return new Point2D(rect.cx() + dx * best, rect.cy() + dy * best);
    }
}
