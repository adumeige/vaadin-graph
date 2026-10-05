package io.github.adumeige.vaadin.graph.karibu

import com.github.mvysny.karibudsl.v10.VaadinDsl
import com.github.mvysny.karibudsl.v10.init
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasComponents
import com.vaadin.flow.data.provider.DataProvider
import io.github.adumeige.vaadin.graph.component.WorkspaceView
import io.github.adumeige.vaadin.graph.model.WorkspaceDescriptor
import io.github.adumeige.vaadin.graph.model.WorkspaceGrid

/**
 * Karibu-DSL entry point for [WorkspaceView]. Adds a workspace to any `@VaadinDsl` block:
 *
 * ```kotlin
 * workspaceView<Story>(storyProvider) {
 *     describe { story -> WorkspaceDescriptor(story.x, story.y, 200.0, 100.0, story.edges) }
 *     render { story -> StoryCard(story) }
 *     grid { size = 20; style = GridStyle.DOTS }
 * }
 * ```
 */
@VaadinDsl
fun <T : Any> (@VaadinDsl HasComponents).workspaceView(
    dataProvider: DataProvider<T, *>,
    block: (@VaadinDsl WorkspaceView<T>).() -> Unit = {},
): WorkspaceView<T> = init(WorkspaceView<T>()) {
    setDataProvider(dataProvider)
    block()
}

/** Sets the topology/geometry function. Use inside the [workspaceView] block. */
fun <T : Any> WorkspaceView<T>.describe(block: (T) -> WorkspaceDescriptor<T>) {
    setDescribe { block(it) }
}

/** Sets the node-rendering function. Use inside the [workspaceView] block. */
fun <T : Any> WorkspaceView<T>.render(block: (T) -> Component) {
    setRender { block(it) }
}

/** Configures the background grid. Use inside the [workspaceView] block. */
fun WorkspaceView<*>.grid(block: WorkspaceGrid.() -> Unit) {
    setGrid(WorkspaceGrid().apply(block))
}
