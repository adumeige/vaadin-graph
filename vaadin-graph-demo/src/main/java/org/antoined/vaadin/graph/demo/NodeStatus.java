package org.antoined.vaadin.graph.demo;

/** Operational status of a graph node, with a display label and badge color. */
public enum NodeStatus {
    HEALTHY("Healthy", "#2E7D32"),
    DEGRADED("Degraded", "#F9A825"),
    DOWN("Down", "#C62828");

    private final String label;
    private final String color;

    NodeStatus(String label, String color) {
        this.label = label;
        this.color = color;
    }

    public String label() {
        return label;
    }

    public String color() {
        return color;
    }
}
