package org.antoined.vaadin.graph.routing;

/** An axis-aligned rectangle defined by its center and size, in workspace coordinates. */
public record Rect(double cx, double cy, double width, double height) {

    public double left() {
        return cx - width / 2;
    }

    public double right() {
        return cx + width / 2;
    }

    public double top() {
        return cy - height / 2;
    }

    public double bottom() {
        return cy + height / 2;
    }
}
