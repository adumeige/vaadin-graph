package org.antoined.vaadin.graph.demo;

import org.antoined.vaadin.graph.model.ArrowType;
import org.antoined.vaadin.graph.model.EdgeRouting;
import org.antoined.vaadin.graph.model.EdgeStyle;

/**
 * A mutable relationship between two {@link GraphNode}s. The demo owns these so they can be edited
 * in place and pushed back through {@code WorkspaceView.refresh()}.
 */
public class GraphEdge {

    private final GraphNode source;
    private final GraphNode target;
    private String label;
    private String color;
    private EdgeRouting routing;
    private ArrowType startArrow;
    private ArrowType endArrow;
    private int strokeWidth;
    private EdgeStyle style = EdgeStyle.SOLID;

    public GraphEdge(GraphNode source, GraphNode target, String label, String color,
                     EdgeRouting routing, ArrowType startArrow, ArrowType endArrow, int strokeWidth) {
        this.source = source;
        this.target = target;
        this.label = label;
        this.color = color;
        this.routing = routing;
        this.startArrow = startArrow;
        this.endArrow = endArrow;
        this.strokeWidth = strokeWidth;
    }

    public GraphNode getSource() {
        return source;
    }

    public GraphNode getTarget() {
        return target;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public EdgeRouting getRouting() {
        return routing;
    }

    public void setRouting(EdgeRouting routing) {
        this.routing = routing;
    }

    public ArrowType getStartArrow() {
        return startArrow;
    }

    public void setStartArrow(ArrowType startArrow) {
        this.startArrow = startArrow;
    }

    public ArrowType getEndArrow() {
        return endArrow;
    }

    public void setEndArrow(ArrowType endArrow) {
        this.endArrow = endArrow;
    }

    public int getStrokeWidth() {
        return strokeWidth;
    }

    public void setStrokeWidth(int strokeWidth) {
        this.strokeWidth = strokeWidth;
    }

    public EdgeStyle getStyle() {
        return style;
    }

    public void setStyle(EdgeStyle style) {
        this.style = style;
    }
}
