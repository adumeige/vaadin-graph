package io.github.adumeige.vaadin.graph.layout;

import io.github.adumeige.vaadin.graph.routing.Point2D;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Layered (Sugiyama-style) layout: assigns each node to a layer following edge direction and stacks
 * the layers top-to-bottom. Cycles are broken with a DFS (back edges ignored), layers come from a
 * longest-path assignment, and nodes within a layer are ordered by the barycenter of their parents
 * to reduce crossings. Best for DAG-ish graphs (call hierarchies, pipelines, dependency trees).
 */
public class HierarchicalLayout implements LayoutAlgorithm {

    private final double minDistance;

    public HierarchicalLayout() {
        this(24.0);
    }

    /** @param minDistance gap between adjacent nodes within a layer and between layers, in px */
    public HierarchicalLayout(double minDistance) {
        this.minDistance = minDistance;
    }

    @Override
    public Map<String, Point2D> layout(LayoutInput input) {
        List<LayoutNode> nodes = input.nodes();
        int n = nodes.size();
        if (n == 0) {
            return Map.of();
        }

        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < n; i++) {
            idx.put(nodes.get(i).id(), i);
        }

        // Forward adjacency, de-duplicated, self-loops dropped.
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        Set<Long> seen = new HashSet<>();
        for (LayoutEdge e : input.edges()) {
            Integer u = idx.get(e.sourceId());
            Integer v = idx.get(e.targetId());
            if (u == null || v == null || u.intValue() == v.intValue()) {
                continue;
            }
            if (seen.add(((long) u << 32) | (v & 0xffffffffL))) {
                adj.get(u).add(v);
            }
        }

        // Iterative DFS: build a DAG (drop back edges) and a finish order for topological sort.
        List<List<Integer>> dag = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            dag.add(new ArrayList<>());
        }
        int[] color = new int[n];   // 0 white, 1 gray, 2 black
        List<Integer> finish = new ArrayList<>();
        for (int s = 0; s < n; s++) {
            if (color[s] != 0) {
                continue;
            }
            Deque<int[]> stack = new ArrayDeque<>();
            stack.push(new int[]{s, 0});
            color[s] = 1;
            while (!stack.isEmpty()) {
                int[] top = stack.peek();
                int u = top[0];
                if (top[1] < adj.get(u).size()) {
                    int v = adj.get(u).get(top[1]++);
                    if (color[v] == 0) {
                        dag.get(u).add(v);        // tree edge
                        color[v] = 1;
                        stack.push(new int[]{v, 0});
                    } else if (color[v] == 2) {
                        dag.get(u).add(v);        // forward/cross edge — safe
                    }
                    // color[v] == 1 → back edge → ignore (breaks the cycle)
                } else {
                    color[u] = 2;
                    finish.add(u);
                    stack.pop();
                }
            }
        }

        // Longest-path layering in topological order (reverse finish order).
        int[] layer = new int[n];
        for (int i = finish.size() - 1; i >= 0; i--) {
            int u = finish.get(i);
            for (int v : dag.get(u)) {
                if (layer[v] < layer[u] + 1) {
                    layer[v] = layer[u] + 1;
                }
            }
        }

        // Group nodes by layer.
        int maxLayer = 0;
        for (int l : layer) {
            maxLayer = Math.max(maxLayer, l);
        }
        List<List<Integer>> layers = new ArrayList<>();
        for (int l = 0; l <= maxLayer; l++) {
            layers.add(new ArrayList<>());
        }
        for (int i = 0; i < n; i++) {
            layers.get(layer[i]).add(i);
        }

        // Order each layer by the barycenter of its parents' positions in the previous layer.
        int[] pos = new int[n];   // position (index) within layer, filled top-down
        for (int l = 0; l <= maxLayer; l++) {
            List<Integer> row = layers.get(l);
            if (l > 0) {
                final int prev = l - 1;
                row.sort((a, b) -> Double.compare(barycenter(a, prev, layer, dag, pos), barycenter(b, prev, layer, dag, pos)));
            }
            for (int p = 0; p < row.size(); p++) {
                pos[row.get(p)] = p;
            }
        }

        // Assign coordinates: layers stacked on Y, nodes spread on X and centered.
        Map<String, Point2D> result = new HashMap<>();
        double y = 0;
        for (int l = 0; l <= maxLayer; l++) {
            List<Integer> row = layers.get(l);
            double layerHeight = 0;
            double totalWidth = 0;
            for (int i : row) {
                layerHeight = Math.max(layerHeight, nodes.get(i).height());
                totalWidth += nodes.get(i).width();
            }
            totalWidth += Math.max(0, row.size() - 1) * minDistance;

            double x = -totalWidth / 2;
            for (int i : row) {
                LayoutNode node = nodes.get(i);
                result.put(node.id(), new Point2D(x + node.width() / 2, y + layerHeight / 2));
                x += node.width() + minDistance;
            }
            y += layerHeight + minDistance;
        }
        return result;
    }

    /** Average within-layer position of {@code node}'s parents in {@code prevLayer}. */
    private double barycenter(int node, int prevLayer, int[] layer, List<List<Integer>> dag, int[] pos) {
        double sum = 0;
        int count = 0;
        for (int u = 0; u < dag.size(); u++) {
            if (layer[u] == prevLayer) {
                for (int v : dag.get(u)) {
                    if (v == node) {
                        sum += pos[u];
                        count++;
                    }
                }
            }
        }
        return count == 0 ? Double.MAX_VALUE / 2 : sum / count;
    }
}
