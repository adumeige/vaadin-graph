package io.github.adumeige.vaadin.graph.layout;

import io.github.adumeige.vaadin.graph.routing.Point2D;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Separates overlapping nodes with minimal displacement. Iteratively pushes each overlapping pair
 * apart along its axis of least penetration until no padded boxes overlap (or the iteration cap is
 * hit). Topology (edges) is ignored — this only de-clutters; force-directed layouts can use it.
 */
public class OverlapRemovalLayout implements LayoutAlgorithm {

    private final double padding;
    private final int maxIterations;

    public OverlapRemovalLayout() {
        this(24.0, 500);
    }

    /**
     * @param minDistance minimum gap to keep between node boxes, in workspace px
     */
    public OverlapRemovalLayout(double minDistance) {
        this(minDistance, 500);
    }

    /**
     * @param padding       minimum gap to keep between boxes, in workspace px
     * @param maxIterations safety cap on relaxation passes
     */
    public OverlapRemovalLayout(double padding, int maxIterations) {
        this.padding = padding;
        this.maxIterations = maxIterations;
    }

    @Override
    public Map<String, Point2D> layout(LayoutInput input) {
        List<LayoutNode> nodes = input.nodes();
        int n = nodes.size();
        double[] x = new double[n];
        double[] y = new double[n];
        double[] hw = new double[n];
        double[] hh = new double[n];
        for (int i = 0; i < n; i++) {
            LayoutNode node = nodes.get(i);
            x[i] = node.cx();
            y[i] = node.cy();
            hw[i] = node.width() / 2;
            hh[i] = node.height() / 2;
        }

        for (int iter = 0; iter < maxIterations; iter++) {
            boolean moved = false;
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double dx = x[j] - x[i];
                    double dy = y[j] - y[i];
                    double overlapX = hw[i] + hw[j] + padding - Math.abs(dx);
                    double overlapY = hh[i] + hh[j] + padding - Math.abs(dy);
                    if (overlapX <= 0 || overlapY <= 0) {
                        continue;   // not overlapping
                    }
                    // Push apart along the axis of least penetration, half each.
                    if (overlapX < overlapY) {
                        double shift = overlapX / 2;
                        double sign = dx != 0 ? Math.signum(dx) : (i < j ? -1 : 1);
                        x[i] -= sign * shift;
                        x[j] += sign * shift;
                    } else {
                        double shift = overlapY / 2;
                        double sign = dy != 0 ? Math.signum(dy) : (i < j ? -1 : 1);
                        y[i] -= sign * shift;
                        y[j] += sign * shift;
                    }
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }

        Map<String, Point2D> result = new HashMap<>();
        for (int i = 0; i < n; i++) {
            result.put(nodes.get(i).id(), new Point2D(x[i], y[i]));
        }
        return result;
    }
}
