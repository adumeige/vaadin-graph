package io.github.adumeige.vaadin.graph.layout;

import io.github.adumeige.vaadin.graph.routing.Point2D;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A*-based placement: nodes are placed one at a time (hubs first). Each node's ideal spot is the
 * centroid of its already-placed neighbours; from there an A* / best-first search expands outward
 * over grid cells and stops at the nearest cell where the node's box clears every placed node
 * (respecting the minimum distance). The result is compact, overlap-free, and keeps connected nodes
 * close. The grid cell is the largest node plus the minimum distance.
 */
public class AStarLayout implements LayoutAlgorithm {

    private static final int MAX_EXPANSIONS = 20_000;

    private final double minDistance;

    public AStarLayout() {
        this(24.0);
    }

    /** @param minDistance minimum gap kept between node boxes, in px */
    public AStarLayout(double minDistance) {
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
        double maxW = 0;
        double maxH = 0;
        for (int i = 0; i < n; i++) {
            LayoutNode node = nodes.get(i);
            idx.put(node.id(), i);
            maxW = Math.max(maxW, node.width());
            maxH = Math.max(maxH, node.height());
        }
        double cellW = maxW + minDistance;
        double cellH = maxH + minDistance;

        // Undirected neighbours.
        List<List<Integer>> adj = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            adj.add(new ArrayList<>());
        }
        for (LayoutEdge e : input.edges()) {
            Integer u = idx.get(e.sourceId());
            Integer v = idx.get(e.targetId());
            if (u == null || v == null || u.intValue() == v.intValue()) {
                continue;
            }
            adj.get(u).add(v);
            adj.get(v).add(u);
        }

        // Placement order: highest degree first, ties by current position (stable, deterministic).
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            order.add(i);
        }
        order.sort(Comparator
                .comparingInt((Integer i) -> -adj.get(i).size())
                .thenComparingDouble(i -> nodes.get(i).cy())
                .thenComparingDouble(i -> nodes.get(i).cx()));

        Point2D[] placed = new Point2D[n];
        Map<String, Point2D> result = new HashMap<>();

        for (int oi = 0; oi < order.size(); oi++) {
            int i = order.get(oi);
            LayoutNode node = nodes.get(i);

            // Ideal = centroid of placed neighbours, else current position.
            double ix = node.cx();
            double iy = node.cy();
            int placedNeighbours = 0;
            double sx = 0;
            double sy = 0;
            for (int v : adj.get(i)) {
                if (placed[v] != null) {
                    sx += placed[v].x();
                    sy += placed[v].y();
                    placedNeighbours++;
                }
            }
            if (placedNeighbours > 0) {
                ix = sx / placedNeighbours;
                iy = sy / placedNeighbours;
            } else if (oi == 0) {
                ix = 0;
                iy = 0;
            }

            int idealCol = (int) Math.round(ix / cellW);
            int idealRow = (int) Math.round(iy / cellH);

            Point2D spot = search(idealCol, idealRow, cellW, cellH, node, placed);
            placed[i] = spot;
            result.put(node.id(), spot);
        }
        return result;
    }

    /** Best-first (A*, admissible h = 0) search outward for the nearest collision-free cell. */
    private Point2D search(int startCol, int startRow, double cellW, double cellH,
                           LayoutNode node, Point2D[] placed) {
        PriorityQueue<long[]> open = new PriorityQueue<>(Comparator.comparingDouble(c -> Double.longBitsToDouble(c[2])));
        Set<Long> visited = new HashSet<>();
        open.add(cell(startCol, startRow, 0.0));
        visited.add(key(startCol, startRow));

        int expansions = 0;
        while (!open.isEmpty() && expansions++ < MAX_EXPANSIONS) {
            long[] c = open.poll();
            int col = (int) c[0];
            int row = (int) c[1];
            double cx = col * cellW;
            double cy = row * cellH;
            if (!collides(cx, cy, node, placed)) {
                return new Point2D(cx, cy);
            }
            for (int dc = -1; dc <= 1; dc++) {
                for (int dr = -1; dr <= 1; dr++) {
                    if (dc == 0 && dr == 0) {
                        continue;
                    }
                    int nc = col + dc;
                    int nr = row + dr;
                    if (visited.add(key(nc, nr))) {
                        double g = Math.hypot((nc - startCol) * cellW, (nr - startRow) * cellH);
                        open.add(cell(nc, nr, g));
                    }
                }
            }
        }
        // Fallback (should not happen): place at the ideal cell.
        return new Point2D(startCol * cellW, startRow * cellH);
    }

    private boolean collides(double cx, double cy, LayoutNode node, Point2D[] placed) {
        for (int j = 0; j < placed.length; j++) {
            Point2D p = placed[j];
            if (p == null) {
                continue;
            }
            // Placed nodes share the same grid; use the node's own size for the gap test, plus the
            // minimum distance. Cells are sized to the largest node, so this is conservative.
            double gapX = node.width() + minDistance;
            double gapY = node.height() + minDistance;
            if (Math.abs(cx - p.x()) < gapX && Math.abs(cy - p.y()) < gapY) {
                return true;
            }
        }
        return false;
    }

    private static long key(int col, int row) {
        return ((long) col << 32) ^ (row & 0xffffffffL);
    }

    private static long[] cell(int col, int row, double g) {
        return new long[]{col, row, Double.doubleToLongBits(g)};
    }
}
