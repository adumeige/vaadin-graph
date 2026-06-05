package org.antoined.vaadin.graph.layout;

import org.antoined.vaadin.graph.routing.Point2D;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A Fruchterman–Reingold force-directed layout: every pair of nodes repels, every edge acts as a
 * spring, and a gentle gravity keeps disconnected pieces from drifting away. Iterations cool down
 * linearly so the system settles. Seeded from the current positions (deterministic — no randomness),
 * with a tiny index-based nudge to break symmetry when nodes coincide.
 */
public class ForceDirectedLayout implements LayoutAlgorithm {

    private final int iterations;
    private final double idealDistance;   // k; <= 0 derives it from node sizes
    private final double gravity;
    private final double minDistance;     // enforced gap between boxes in the finishing pass

    public ForceDirectedLayout() {
        this(24.0);
    }

    /**
     * @param minDistance minimum gap to keep between node boxes, in workspace px
     */
    public ForceDirectedLayout(double minDistance) {
        this(400, 0.0, 0.02, minDistance);
    }

    /**
     * @param iterations    relaxation passes
     * @param idealDistance target spacing {@code k}; if {@code <= 0}, derived from node sizes
     * @param gravity       pull toward the centroid (0 = none); keeps the graph compact
     * @param minDistance   minimum gap between node boxes, enforced by the finishing pass
     */
    public ForceDirectedLayout(int iterations, double idealDistance, double gravity, double minDistance) {
        this.iterations = iterations;
        this.idealDistance = idealDistance;
        this.gravity = gravity;
        this.minDistance = minDistance;
    }

    @Override
    public Map<String, Point2D> layout(LayoutInput input) {
        List<LayoutNode> nodes = input.nodes();
        int n = nodes.size();
        if (n == 0) {
            return Map.of();
        }

        double[] x = new double[n];
        double[] y = new double[n];
        double[] dx = new double[n];
        double[] dy = new double[n];
        Map<String, Integer> index = new HashMap<>();
        double diagonalSum = 0;
        for (int i = 0; i < n; i++) {
            LayoutNode node = nodes.get(i);
            x[i] = node.cx();
            y[i] = node.cy();
            index.put(node.id(), i);
            diagonalSum += Math.hypot(node.width(), node.height());
        }

        // Ideal distance ~ node diagonal so boxes settle roughly a node apart.
        double k = idealDistance > 0 ? idealDistance : (diagonalSum / n) * 0.9;

        int[][] edges = input.edges().stream()
                .filter(e -> index.containsKey(e.sourceId()) && index.containsKey(e.targetId()))
                .map(e -> new int[]{index.get(e.sourceId()), index.get(e.targetId())})
                .toArray(int[][]::new);

        double startTemp = k;

        for (int iter = 0; iter < iterations; iter++) {
            for (int i = 0; i < n; i++) {
                dx[i] = 0;
                dy[i] = 0;
            }

            // Repulsion between every pair: f = k^2 / d.
            for (int i = 0; i < n; i++) {
                for (int j = i + 1; j < n; j++) {
                    double ddx = x[i] - x[j];
                    double ddy = y[i] - y[j];
                    double dist = Math.hypot(ddx, ddy);
                    if (dist < 0.01) {
                        // Deterministic symmetry-breaking nudge.
                        ddx = ((i * 7 + j) % 7) - 3;
                        ddy = ((i * 3 + j * 5) % 7) - 3;
                        dist = Math.hypot(ddx, ddy);
                        if (dist < 0.01) {
                            ddx = 1;
                            dist = 1;
                        }
                    }
                    double force = (k * k) / dist;
                    double ux = ddx / dist;
                    double uy = ddy / dist;
                    dx[i] += ux * force;
                    dy[i] += uy * force;
                    dx[j] -= ux * force;
                    dy[j] -= uy * force;
                }
            }

            // Attraction along edges (springs): f = d^2 / k.
            for (int[] e : edges) {
                int u = e[0];
                int v = e[1];
                double ddx = x[u] - x[v];
                double ddy = y[u] - y[v];
                double dist = Math.hypot(ddx, ddy);
                if (dist < 0.01) {
                    continue;
                }
                double force = (dist * dist) / k;
                double ux = ddx / dist;
                double uy = ddy / dist;
                dx[u] -= ux * force;
                dy[u] -= uy * force;
                dx[v] += ux * force;
                dy[v] += uy * force;
            }

            // Gravity toward the centroid.
            if (gravity > 0) {
                double cx = 0;
                double cy = 0;
                for (int i = 0; i < n; i++) {
                    cx += x[i];
                    cy += y[i];
                }
                cx /= n;
                cy /= n;
                for (int i = 0; i < n; i++) {
                    dx[i] += (cx - x[i]) * gravity;
                    dy[i] += (cy - y[i]) * gravity;
                }
            }

            // Apply, capping each step by the (linearly cooling) temperature.
            double temp = startTemp * (1.0 - (double) iter / iterations);
            for (int i = 0; i < n; i++) {
                double d = Math.hypot(dx[i], dy[i]);
                if (d > 0) {
                    double step = Math.min(d, temp);
                    x[i] += (dx[i] / d) * step;
                    y[i] += (dy[i] / d) * step;
                }
            }
        }

        // Finishing pass: force placement gives the global structure (clusters, edge lengths) but
        // can leave boxes touching; a minimal-displacement separation guarantees no overlap.
        List<LayoutNode> placed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            LayoutNode node = nodes.get(i);
            placed.add(new LayoutNode(node.id(), x[i], y[i], node.width(), node.height()));
        }
        return new OverlapRemovalLayout(minDistance, 300).layout(new LayoutInput(placed, input.edges()));
    }
}
