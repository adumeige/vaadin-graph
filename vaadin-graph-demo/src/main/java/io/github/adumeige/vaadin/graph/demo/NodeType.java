package io.github.adumeige.vaadin.graph.demo;

/** Kind of service represented by a graph node, with a display label and accent color. */
public enum NodeType {
    SERVICE("Service", "#4C8BF5"),
    DATABASE("Database", "#34A853"),
    QUEUE("Queue", "#FB8C00"),
    GATEWAY("Gateway", "#9C27B0"),
    CACHE("Cache", "#00897B");

    private final String label;
    private final String color;

    NodeType(String label, String color) {
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
