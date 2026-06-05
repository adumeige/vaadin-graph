package org.antoined.vaadin.graph.layout;

import org.antoined.vaadin.graph.routing.Point2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orthogonal grid layout: snaps nodes onto a regular, axis-aligned grid. Cell size is the largest
 * node plus the minimum distance, so the result never overlaps. Nodes are ordered by a breadth-first
 * traversal from the highest-degree node, so connected nodes land in neighbouring cells and edges
 * stay short.
 */
public class OrthogonalLayout implements LayoutAlgorithm {

    private final double minDistance;

    public OrthogonalLayout() {
        this(24.0);
    }

    /** @param minDistance gap kept between adjacent grid cells, in px */
    public OrthogonalLayout(double minDistance) {
        this.minDistance = minDistance;
    }

    @Override
    public Map<String, Point2D> layout(LayoutInput input) {
        List<LayoutNode> nodes = input.nodes();
        int n = nodes.size();
        if (n == 0) {
            return Map.of();
        }

        double maxW = 0;
        double maxH = 0;
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            LayoutNode node = nodes.get(i);
            maxW = Math.max(maxW, node.width());
            maxH = Math.max(maxH, node.height());
            idx.put(node.id(), i);
        }
        double cellW = maxW + minDistance;
        double cellH = maxH + minDistance;

        List<Integer> order = breadthFirstOrder(nodes, input.edges(), idx);

        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil((double) n / cols);
        double originX = -(cols - 1) * cellW / 2;
        double originY = -(rows - 1) * cellH / 2;

        Map<String, Point2D> result = new HashMap<>();
        for (int k = 0; k < order.size(); k++) {
            int col = k % cols;
            int row = k / cols;
            LayoutNode node = nodes.get(order.get(k));
            result.put(node.id(), new Point2D(originX + col * cellW, originY + row * cellH));
        }
        return result;
    }

    /** BFS over the undirected graph from the highest-degree node, appending any unvisited nodes. */
    private List<Integer> breadthFirstOrder(List<LayoutNode> nodes, List<LayoutEdge> edges,
                                            Map<String, Integer> idx) {
        int n = nodes.size();
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (LayoutEdge e : edges) {
            Integer u = idx.get(e.sourceId());
            Integer v = idx.get(e.targetId());
            if (u == null || v == null || u.intValue() == v.intValue()) {
                continue;
            }
            adj.get(u).add(v);
            adj.get(v).add(u);
        }

        int start = 0;
        for (int i = 1; i < n; i++) {
            if (adj.get(i).size() > adj.get(start).size()) {
                start = i;
            }
        }

        boolean[] visited = new boolean[n];
        List<Integer> order = new ArrayList<>(n);
        Deque<Integer> queue = new ArrayDeque<>();
        for (int seed = 0; seed < n; seed++) {
            int s = (seed == 0) ? start : seed;
            if (visited[s]) {
                continue;
            }
            visited[s] = true;
            queue.add(s);
            while (!queue.isEmpty()) {
                int u = queue.poll();
                order.add(u);
                for (int v : adj.get(u)) {
                    if (!visited[v]) {
                        visited[v] = true;
                        queue.add(v);
                    }
                }
            }
        }
        return order;
    }
}
