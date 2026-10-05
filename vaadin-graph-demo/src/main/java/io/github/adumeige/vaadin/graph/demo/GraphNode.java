package io.github.adumeige.vaadin.graph.demo;

/**
 * A richer demo entity than a bare node: a service in an architecture diagram, with a type, status,
 * owner, a markdown description, and a position. Mutable, with identity equality — instances are
 * used as keys by {@code WorkspaceView}, so two distinct nodes are never "equal".
 */
public class GraphNode {

    private final String id;
    private String name;
    private NodeType type;
    private NodeStatus status;
    private String owner;
    private String description;
    private double x;
    private double y;

    public GraphNode(String id, String name, NodeType type, NodeStatus status,
                     String owner, String description, double x, double y) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.status = status;
        this.owner = owner;
        this.description = description;
        this.x = x;
        this.y = y;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public NodeType getType() {
        return type;
    }

    public void setType(NodeType type) {
        this.type = type;
    }

    public NodeStatus getStatus() {
        return status;
    }

    public void setStatus(NodeStatus status) {
        this.status = status;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }
}
