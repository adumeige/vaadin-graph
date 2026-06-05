package org.antoined.vaadin.graph.model;

/**
 * Describes a single edge originating from the node being described, pointing at another domain
 * object {@code target}. Built via {@link #to(Object)}; all visual attributes have sensible
 * defaults.
 *
 * <pre>{@code
 * EdgeDescriptor.to(otherNode)
 *     .label("depends on")
 *     .routing(EdgeRouting.ORTHOGONAL)
 *     .endArrow(ArrowType.DIAMOND)
 *     .build();
 * }</pre>
 *
 * @param <T> the domain type of graph nodes
 */
public final class EdgeDescriptor<T> {

    private final T target;
    private final String label;
    private final double labelOffset;
    private final String color;
    private final int strokeWidth;
    private final EdgeRouting routing;
    private final ArrowType startArrow;
    private final ArrowType endArrow;
    private final EdgeStyle style;
    private final Object payload;

    private EdgeDescriptor(Builder<T> b) {
        this.target = b.target;
        this.label = b.label;
        this.labelOffset = b.labelOffset;
        this.color = b.color;
        this.strokeWidth = b.strokeWidth;
        this.routing = b.routing;
        this.startArrow = b.startArrow;
        this.endArrow = b.endArrow;
        this.style = b.style;
        this.payload = b.payload;
    }

    /** Starts a builder for an edge toward {@code target}. */
    public static <T> Builder<T> to(T target) {
        return new Builder<>(target);
    }

    public T target() {
        return target;
    }

    public String label() {
        return label;
    }

    public double labelOffset() {
        return labelOffset;
    }

    public String color() {
        return color;
    }

    public int strokeWidth() {
        return strokeWidth;
    }

    public EdgeRouting routing() {
        return routing;
    }

    public ArrowType startArrow() {
        return startArrow;
    }

    public ArrowType endArrow() {
        return endArrow;
    }

    public EdgeStyle style() {
        return style;
    }

    /** Opaque caller-supplied data, echoed back via {@code EdgeClickEvent.getEdge().payload()}. */
    public Object payload() {
        return payload;
    }

    public static final class Builder<T> {
        private final T target;
        private String label = null;
        private double labelOffset = 0.5;
        private String color = "#888888";
        private int strokeWidth = 2;
        private EdgeRouting routing = EdgeRouting.STRAIGHT;
        private ArrowType startArrow = ArrowType.NONE;
        private ArrowType endArrow = ArrowType.ARROW;
        private EdgeStyle style = EdgeStyle.SOLID;
        private Object payload = null;

        private Builder(T target) {
            this.target = target;
        }

        public Builder<T> label(String label) {
            this.label = label;
            return this;
        }

        public Builder<T> labelOffset(double labelOffset) {
            this.labelOffset = labelOffset;
            return this;
        }

        public Builder<T> color(String color) {
            this.color = color;
            return this;
        }

        public Builder<T> strokeWidth(int strokeWidth) {
            this.strokeWidth = strokeWidth;
            return this;
        }

        public Builder<T> routing(EdgeRouting routing) {
            this.routing = routing;
            return this;
        }

        public Builder<T> startArrow(ArrowType startArrow) {
            this.startArrow = startArrow;
            return this;
        }

        public Builder<T> endArrow(ArrowType endArrow) {
            this.endArrow = endArrow;
            return this;
        }

        public Builder<T> style(EdgeStyle style) {
            this.style = style;
            return this;
        }

        public Builder<T> payload(Object payload) {
            this.payload = payload;
            return this;
        }

        public EdgeDescriptor<T> build() {
            return new EdgeDescriptor<>(this);
        }
    }
}
