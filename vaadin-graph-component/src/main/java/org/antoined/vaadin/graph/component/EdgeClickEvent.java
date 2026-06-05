package org.antoined.vaadin.graph.component;

import com.vaadin.flow.component.ComponentEvent;
import org.antoined.vaadin.graph.model.EdgeDescriptor;

/**
 * Fired when the user clicks an edge in the workspace. Carries the resolved source and target
 * domain objects and the originating {@link EdgeDescriptor} (label, color, routing, …).
 *
 * @param <T> the domain type of graph nodes
 */
public class EdgeClickEvent<T> extends ComponentEvent<WorkspaceView<T>> {

    private final transient T sourceItem;
    private final transient T targetItem;
    private final transient EdgeDescriptor<T> edge;

    public EdgeClickEvent(WorkspaceView<T> source, boolean fromClient,
                          T sourceItem, T targetItem, EdgeDescriptor<T> edge) {
        super(source, fromClient);
        this.sourceItem = sourceItem;
        this.targetItem = targetItem;
        this.edge = edge;
    }

    /** The node the edge originates from. */
    public T getSourceItem() {
        return sourceItem;
    }

    /** The node the edge points at. */
    public T getTargetItem() {
        return targetItem;
    }

    /** The descriptor returned by {@code describe} for this edge. */
    public EdgeDescriptor<T> getEdge() {
        return edge;
    }
}
