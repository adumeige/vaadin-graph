package org.antoined.vaadin.graph.component;

import com.vaadin.flow.component.ComponentEvent;

/**
 * Fired when the user clicks a node slot in the workspace. {@link #getItem()} is the domain object
 * that was clicked, resolved server-side from the clicked node id.
 *
 * @param <T> the domain type of graph nodes
 */
public class NodeClickEvent<T> extends ComponentEvent<WorkspaceView<T>> {

    private final transient T item;

    public NodeClickEvent(WorkspaceView<T> source, boolean fromClient, T item) {
        super(source, fromClient);
        this.item = item;
    }

    public T getItem() {
        return item;
    }
}
