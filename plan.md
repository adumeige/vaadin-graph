# WorkspaceView — Claude Code Implementation Handoff

## Context

This document is a complete implementation plan for `WorkspaceView<T>`, a Vaadin/Karibu-DSL
component that renders a zoomable, pannable graph workspace. It is a data-driven render component
analogous to `Grid<T>`: it takes a `DataProvider<T, *>`, a `describe` lambda
`(T) -> WorkspaceDescriptor<T>` for graph topology, and a `render` lambda `(T) -> Component` for
node visuals.

All architectural decisions are final. Do not re-open them. Execute the plan in module order.

---

## Project conventions

- Kotlin, Karibu-DSL, Spring Boot, Vaadin 24
- Follow `stateflow-karibu-binding.md` for any StateFlow integration — do not invent binding patterns
- All Vaadin view code uses Karibu DSL; no constructor calls outside the three permitted cases
- `kotlinx.serialization` for JSON; `ktor-client` is available but not needed here
- TypeScript for client-side code; compile with the project's existing Vite/webpack setup
- No ORM; no Spring Data; no Vaadin OGM
- Prefer `data class` + `sealed interface` over inheritance hierarchies

---

## Module structure

```
workspace/
  src/main/kotlin/…/workspace/
    model/
      WorkspaceDescriptor.kt
      EdgeDescriptor.kt
      WorkspaceGrid.kt
      WorkspaceState.kt          ← serializable aggregate pushed to client
    component/
      WorkspaceView.kt           ← main Vaadin component
      WorkspaceDsl.kt            ← karibu-dsl entry point
      NodeClickEvent.kt          ← ComponentEvent fired when a node slot is clicked
    routing/
      EdgeRouter.kt              ← interface
      ThreeSegmentRouter.kt      ← implementation
      Geometry.kt                ← Point2D, Rect, rectBoundaryIntersect
  src/main/frontend/workspace/
    types.ts                     ← TS mirrors of server DTOs
    geometry.ts                  ← client-side geometry (boundary intersect, segment math)
    router.ts                    ← EdgeRouter interface + ThreeSegmentRouter in TS
    renderer.ts                  ← SVG edge + label builder
    grid.ts                      ← canvas grid painter
    viewport.ts                  ← ViewportState, fitContent, transform management
    workspace.ts                 ← entry point, web component definition, wires everything
```

---

## Phase 1 — Kotlin model

### 1.1 `Geometry.kt`

```kotlin
package …workspace.routing

data class Point2D(val x: Double, val y: Double)

data class Rect(val cx: Double, val cy: Double, val width: Double, val height: Double) {
    val left get() = cx - width / 2
    val right get() = cx + width / 2
    val top get() = cy - height / 2
    val bottom get() = cy + height / 2
}

/**
 * Returns the point where the segment (cx,cy)→(tx,ty) exits the boundary of [rect].
 * If (tx,ty) is inside the rect, returns (cx,cy) unchanged.
 */
fun rectBoundaryIntersect(rect: Rect, toward: Point2D): Point2D {
    val dx = toward.x - rect.cx
    val dy = toward.y - rect.cy
    if (dx == 0.0 && dy == 0.0) return Point2D(rect.cx, rect.cy)

    // Candidate t values for each side
    val tCandidates = mutableListOf<Double>()
    if (dx != 0.0) {
        tCandidates += (if (dx > 0) rect.right else rect.left - rect.cx) / dx
    }
    if (dy != 0.0) {
        tCandidates += (if (dy > 0) rect.bottom else rect.top - rect.cy) / dy
    }

    val t = tCandidates.filter { it > 0.0 }.minOrNull() ?: return Point2D(rect.cx, rect.cy)
    return Point2D(rect.cx + dx * t, rect.cy + dy * t)
}
```

### 1.2 `EdgeRouter.kt`

```kotlin
package …workspace.routing

fun interface EdgeRouter {
    /**
     * Returns an ordered list of points forming the edge path.
     * First point exits the source boundary; last point enters the target boundary.
     * Implementations must call [rectBoundaryIntersect] to clip at boundaries.
     */
    fun route(source: Rect, target: Rect): List<Point2D>
}
```

### 1.3 `ThreeSegmentRouter.kt`

```kotlin
package …workspace.routing

class ThreeSegmentRouter : EdgeRouter {
    override fun route(source: Rect, target: Rect): List<Point2D> {
        val srcCenter = Point2D(source.cx, source.cy)
        val dstCenter = Point2D(target.cx, target.cy)

        val p1 = rectBoundaryIntersect(source, dstCenter)
        val p2 = rectBoundaryIntersect(target, srcCenter)

        val dx = kotlin.math.abs(dstCenter.x - srcCenter.x)
        val dy = kotlin.math.abs(dstCenter.y - srcCenter.y)

        // Horizontal dominant: elbow on x midpoint
        // Vertical dominant:   elbow on y midpoint
        val mid = if (dx >= dy) {
            val mx = (p1.x + p2.x) / 2
            listOf(Point2D(mx, p1.y), Point2D(mx, p2.y))
        } else {
            val my = (p1.y + p2.y) / 2
            listOf(Point2D(p1.x, my), Point2D(p2.x, my))
        }

        return listOf(p1) + mid + listOf(p2)
    }
}
```

For `EdgeRouting.STRAIGHT`, the router returns exactly `[p1, p2]` (two points, one segment).
Implement this as a second object or a branch in `WorkspaceView` — do not add a second
`StraightRouter` class unless it simplifies things.

### 1.4 `WorkspaceDescriptor.kt`

```kotlin
package …workspace.model

import …workspace.routing.EdgeRouter
import …workspace.routing.ThreeSegmentRouter

enum class EdgeRouting { STRAIGHT, ORTHOGONAL }
enum class ArrowType { NONE, ARROW, CIRCLE, DIAMOND }
enum class GridStyle { LINES, DOTS }

data class EdgeDescriptor<T>(
    val target: T,
    val label: String? = null,
    val labelOffset: Double = 0.5,
    val color: String = "#888888",
    val strokeWidth: Int = 2,
    val routing: EdgeRouting = EdgeRouting.STRAIGHT,
    val startArrow: ArrowType = ArrowType.NONE,
    val endArrow: ArrowType = ArrowType.ARROW,
)

data class WorkspaceDescriptor<T>(
    val cx: Double,
    val cy: Double,
    val width: Double,
    val height: Double,
    val edges: List<EdgeDescriptor<T>> = emptyList(),
)
```

### 1.5 `WorkspaceGrid.kt`

```kotlin
package …workspace.model

import kotlinx . serialization . Serializable

        @Serializable
        data class WorkspaceGrid(
            val enabled: Boolean = true,
            val size: Int = 20,
            val style: GridStyle = GridStyle.DOTS,
            val snapEnabled: Boolean = false,
            val snapStrength: Int = 8,
            val minZoomVisible: Double = 0.25,
        )
```

### 1.6 `WorkspaceState.kt`

This is the **only** thing serialized and pushed to the client. It contains no `T` — all domain
objects are resolved to strings before serialization.

```kotlin
package …workspace.model

import kotlinx . serialization . Serializable

        @Serializable
        data class WorkspaceState(
            val nodes: List<NodeState>,
            val edges: List<EdgeState>,
            val grid: WorkspaceGrid,
            val viewport: ViewportState?,   // null = fitContent on client
        )

@Serializable
data class NodeState(
    val id: String,
    val cx: Double,
    val cy: Double,
    val width: Double,
    val height: Double,
)

@Serializable
data class EdgeState(
    val id: String,
    val sourceId: String,
    val targetId: String,
    val label: String?,
    val labelOffset: Double,
    val color: String,
    val strokeWidth: Int,
    val routing: EdgeRouting,
    val startArrow: ArrowType,
    val endArrow: ArrowType,
)

@Serializable
data class ViewportState(
    val panX: Double,
    val panY: Double,
    val zoom: Double,
)
```

---

## Phase 2 — `WorkspaceView.kt`

This is the main Vaadin component. Key responsibilities:

1. Accept `DataProvider<T, *>`, `describe`, `render` lambdas
2. Listen to data provider changes
3. Maintain a `nodeSlots: MutableMap<T, Pair<String, Element>>` — maps domain object to
   `(internalId, slotElement)`. Use `LinkedHashMap` to preserve insertion order.
4. On refresh: diff items, add/remove slot elements, re-describe all, serialize state, push to client
5. Expose `fitContent()`, `setViewport()`, `refresh()` as public API
6. Be a proper Vaadin `Component` that can host child elements (the node slots)

```kotlin
package …workspace.component

import com . vaadin . flow . component . Component
        import com . vaadin . flow . component . HasSize
        import com . vaadin . flow . component . Tag
        import com . vaadin . flow . component . dependency . JsModule
        import com . vaadin . flow . component . dependency . NpmPackage
        import com . vaadin . flow . data . provider . DataProvider
        import com . vaadin . flow . data . provider . Query
        import kotlinx . serialization . encodeToString
        import kotlinx . serialization . json . Json
        import …workspace.model.*
import …workspace.routing.*
import java . util . UUID

        @Tag("vaadin-workspace-view")
        @JsModule("./workspace/workspace.js")   // compiled output of workspace.ts
        class WorkspaceView<T : Any> : Component(), HasSize {

            // ── Configuration ────────────────────────────────────────────────────────

            var dataProvider: DataProvider<T, Nothing?> = DataProvider.ofCollection(emptyList())
                set(value) {
                    field = value
                    dataProviderRegistration?.remove()
                    dataProviderRegistration = value.addDataProviderListener { scheduleRefresh() }
                    scheduleRefresh()
                }

            var describe: (T) -> WorkspaceDescriptor<T> = { WorkspaceDescriptor(0.0, 0.0, 160.0, 80.0) }
                set(value) {
                    field = value; scheduleRefresh()
                }

            var render: (T) -> Component = { com.vaadin.flow.component.html.Div() }
                set(value) {
                    field = value; scheduleRefresh()
                }

            var grid: WorkspaceGrid = WorkspaceGrid()
                set(value) {
                    field = value; scheduleRefresh()
                }

            var router: EdgeRouter = ThreeSegmentRouter()

            // ── Internal state ───────────────────────────────────────────────────────

            private var dataProviderRegistration: com.vaadin.flow.shared.Registration? = null

            /** Maps domain object → (internalId, slot div element, rendered component) */
            private val nodeSlots = LinkedHashMap<T, Triple<String, com.vaadin.flow.dom.Element, Component>>()

            private var pendingViewport: ViewportState? = null
            private var fitOnNextRender: Boolean = true

            // ── Public API ───────────────────────────────────────────────────────────

            fun fitContent() {
                fitOnNextRender = true
                pendingViewport = null
                scheduleRefresh()
            }

            fun setViewport(panX: Double, panY: Double, zoom: Double) {
                fitOnNextRender = false
                pendingViewport = ViewportState(panX, panY, zoom)
                scheduleRefresh()
            }

            fun refresh() = scheduleRefresh()

            // ── Internals ────────────────────────────────────────────────────────────

            private fun scheduleRefresh() {
                // In Vaadin 24, UI.getCurrent() may be null during init — guard accordingly.
                // The component will push state once attached if UI is not yet available.
                val ui = ui.orElse(null) ?: return
                ui.access { doRefresh() }
            }

            private fun doRefresh() {
                val items = dataProvider.fetch(Query()).toList()

                // ── Diff ────────────────────────────────────────────────────────────
                val currentItems = nodeSlots.keys.toSet()
                val newItems = items.toSet()

                // Remove stale
                (currentItems - newItems).forEach { stale ->
                    val (_, slotEl, renderedComp) = nodeSlots[stale]!!
                    element.removeChild(slotEl)
                    // Detach rendered component properly
                    com.vaadin.flow.component.ComponentUtil.findParentComponent(renderedComp)
                    slotEl.removeAllChildren()
                    nodeSlots.remove(stale)
                }

                // Add new
                (newItems - currentItems).forEach { fresh ->
                    val id = UUID.randomUUID().toString()
                    val rendered = render(fresh)
                    val slotEl = com.vaadin.flow.dom.Element("div").apply {
                        setAttribute("slot", "node-$id")
                        setAttribute("data-node-id", id)
                        appendChild(rendered.element)
                    }
                    element.appendChild(slotEl)
                    nodeSlots[fresh] = Triple(id, slotEl, rendered)
                }

                // ── Describe all ────────────────────────────────────────────────────
                val descriptors: Map<T, WorkspaceDescriptor<T>> = items.associateWith { describe(it) }

                // ── Build NodeState list ─────────────────────────────────────────────
                val nodeStates: List<NodeState> = items.mapNotNull { item ->
                    val (id, _, _) = nodeSlots[item] ?: return@mapNotNull null
                    val desc = descriptors[item]!!
                    NodeState(id = id, cx = desc.cx, cy = desc.cy, width = desc.width, height = desc.height)
                }

                // ── Build EdgeState list ─────────────────────────────────────────────
                // Resolve T targets → ids; drop edges whose target is not in current set.
                // Track per-pair edge count to produce unique ids for multi-edges.
                val itemToId: Map<T, String> = nodeSlots.mapValues { (_, v) -> v.first }
                val pairCount = mutableMapOf<Pair<String, String>, Int>()

                val edgeStates: List<EdgeState> = items.flatMap { item ->
                    val srcId = itemToId[item] ?: return@flatMap emptyList()
                    descriptors[item]!!.edges.mapNotNull { edge ->
                        val dstId = itemToId[edge.target] ?: return@mapNotNull null
                        val pair = srcId to dstId
                        val idx = pairCount.merge(pair, 0) { old, _ -> old + 1 }!!
                        EdgeState(
                            id = if (idx == 0) "$srcId-$dstId" else "$srcId-$dstId-$idx",
                            sourceId = srcId,
                            targetId = dstId,
                            label = edge.label,
                            labelOffset = edge.labelOffset,
                            color = edge.color,
                            strokeWidth = edge.strokeWidth,
                            routing = edge.routing,
                            startArrow = edge.startArrow,
                            endArrow = edge.endArrow,
                        )
                    }
                }

                // ── Serialize and push ───────────────────────────────────────────────
                val state = WorkspaceState(
                    nodes = nodeStates,
                    edges = edgeStates,
                    grid = grid,
                    viewport = if (fitOnNextRender) null else pendingViewport,
                )
                val json = Json.encodeToString(state)
                element.setProperty("workspaceState", json)
            }

            override fun onAttach(attachEvent: com.vaadin.flow.component.AttachEvent) {
                super.onAttach(attachEvent)
                scheduleRefresh()
            }
        }
```

**Important implementation notes:**

- `dataProvider.fetch(Query())` uses the raw unchecked cast to `DataProvider<T, Nothing?>`. If the
  project uses a filter type, adjust the cast at the call site. The workspace never sets a filter
  itself — filtering is the caller's responsibility.
- Duplicate edges (same `srcId-dstId`) are possible if `describe` returns multiple edges to the
  same target. Append an index suffix to the id in that case.
- `scheduleRefresh()` must be idempotent and safe to call before attach.

---

## Phase 3 — `WorkspaceDsl.kt`

```kotlin
package …workspace.component

import com . github . mvysny . karibudsl . v10 . VaadinDsl
        import com . github . mvysny . karibudsl . v10 . init
        import com . vaadin . flow . component . HasComponents
        import com . vaadin . flow . data . provider . DataProvider
        import …workspace.model.*

@VaadinDsl
fun <T : Any> (@VaadinDsl HasComponents).workspaceView(
    dataProvider: DataProvider<T, Nothing?>,
    block: WorkspaceView<T>.() -> Unit = {},
): WorkspaceView<T> = init(WorkspaceView()) {
    this.dataProvider = dataProvider
    block()
}

/** Convenience setter — use inside the DSL block */
fun <T : Any> WorkspaceView<T>.describe(block: (T) -> WorkspaceDescriptor<T>) {
    this.describe = block
}

fun <T : Any> WorkspaceView<T>.render(block: (T) -> com.vaadin.flow.component.Component) {
    this.render = block
}

fun WorkspaceView<*>.grid(block: WorkspaceGrid.() -> Unit) {
    this.grid = WorkspaceGrid().apply(block)
}
```

Usage pattern:

```kotlin
workspaceView<Story>(storyProvider) {
    describe { story ->
        WorkspaceDescriptor(
            cx = story.canvasX, cy = story.canvasY,
            width = 200.0, height = 100.0,
            edges = story.relations.map {
                EdgeDescriptor(target = it.target, label = it.type.label, color = it.type.color)
            }
        )
    }
    render { story -> StoryCard(story) }
    grid {
        size = 20
        style = GridStyle.DOTS
        snapEnabled = false
    }
}
```

---

## Phase 3b — `NodeClickEvent.kt`

A standard Vaadin `ComponentEvent` carrying the clicked domain object `T`. The server resolves
the node id (received from the client) back to `T` via the `nodeSlots` map before firing.

```kotlin
package …workspace.component

import com . vaadin . flow . component . ComponentEvent
        import com . vaadin . flow . component . DomEvent
        import com . vaadin . flow . component . EventData

/**
 * Fired when the user clicks a node slot in the workspace.
 * [item] is the domain object `T` that was clicked.
 */
class NodeClickEvent<T : Any>(
    source: WorkspaceView<T>,
    fromClient: Boolean,
    val item: T,
) : ComponentEvent<WorkspaceView<T>>(source, fromClient)
```

In `WorkspaceView`, add:

```kotlin
// ── Click event support ──────────────────────────────────────────────────

/** Reverse map: internalId → T, kept in sync with nodeSlots */
private val idToItem = HashMap<String, T>()

fun addNodeClickListener(
    listener: com.vaadin.flow.component.ComponentEventListener<NodeClickEvent<T>>
): com.vaadin.flow.shared.Registration =
    addListener(NodeClickEvent::class.java as Class<NodeClickEvent<T>>, listener)

/**
 * Called by the client when a node slot is clicked.
 * Resolves the id to a domain object and fires [NodeClickEvent].
 */
@com.vaadin.flow.component.ClientCallable
fun onNodeClicked(nodeId: String) {
    val item = idToItem[nodeId] ?: return
    fireEvent(NodeClickEvent(this, true, item))
}
```

Keep `idToItem` in sync inside `doRefresh`:

```kotlin
// After building nodeSlots diff:
idToItem.clear()
nodeSlots.forEach { (item, triple) -> idToItem[triple.first] = item }
```

The client fires the event by calling the `@ClientCallable` method. See Phase 4.7 for the
client-side wiring — node slots get a `click` listener that calls
`this.$server.onNodeClicked(nodeId)`.

---

## Phase 4 — TypeScript client

All files live in `src/main/frontend/workspace/`. The compiled output is
`workspace.js` referenced by `@JsModule` above.

### 4.1 `types.ts`

Mirror of server DTOs. Keep in sync with Kotlin model manually.

```typescript
export type EdgeRouting = 'STRAIGHT' | 'ORTHOGONAL'
export type ArrowType = 'NONE' | 'ARROW' | 'CIRCLE' | 'DIAMOND'
export type GridStyle = 'LINES' | 'DOTS'

export interface NodeState {
    id: string
    cx: number;
    cy: number
    width: number;
    height: number
}

export interface EdgeState {
    id: string
    sourceId: string;
    targetId: string
    label: string | null
    labelOffset: number
    color: string
    strokeWidth: number
    routing: EdgeRouting
    startArrow: ArrowType
    endArrow: ArrowType
}

export interface WorkspaceGrid {
    enabled: boolean
    size: number
    style: GridStyle
    snapEnabled: boolean
    snapStrength: number
    minZoomVisible: number
}

export interface ViewportState {
    panX: number;
    panY: number;
    zoom: number
}

export interface WorkspaceState {
    nodes: NodeState[]
    edges: EdgeState[]
    grid: WorkspaceGrid
    viewport: ViewportState | null   // null = fitContent
}
```

### 4.2 `geometry.ts`

```typescript
export interface Point2D {
    x: number;
    y: number
}

export interface Rect {
    cx: number;
    cy: number;
    width: number;
    height: number
}

export function rectBoundaryIntersect(rect: Rect, toward: Point2D): Point2D {
    const dx = toward.x - rect.cx
    const dy = toward.y - rect.cy
    if (dx === 0 && dy === 0) return {x: rect.cx, y: rect.cy}

    const candidates: number[] = []
    const hw = rect.width / 2
    const hh = rect.height / 2

    if (dx !== 0) candidates.push((dx > 0 ? hw : -hw) / dx)
    if (dy !== 0) candidates.push((dy > 0 ? hh : -hh) / dy)

    const t = Math.min(...candidates.filter(c => c > 0))
    return {x: rect.cx + dx * t, y: rect.cy + dy * t}
}

export function segmentMidpoint(a: Point2D, b: Point2D): Point2D {
    return {x: (a.x + b.x) / 2, y: (a.y + b.y) / 2}
}

export function segmentLength(a: Point2D, b: Point2D): number {
    return Math.hypot(b.x - a.x, b.y - a.y)
}

/** Returns the point at fraction t along a polyline defined by points */
export function polylinePointAt(points: Point2D[], t: number): Point2D {
    const total = points.slice(1).reduce((acc, p, i) => acc + segmentLength(points[i], p), 0)
    let target = total * Math.max(0, Math.min(1, t))
    for (let i = 0; i < points.length - 1; i++) {
        const len = segmentLength(points[i], points[i + 1])
        if (target <= len) {
            const f = target / len
            return {
                x: points[i].x + f * (points[i + 1].x - points[i].x),
                y: points[i].y + f * (points[i + 1].y - points[i].y)
            }
        }
        target -= len
    }
    return points[points.length - 1]
}

/** Index of the longest segment in a polyline */
export function longestSegmentMidpoint(points: Point2D[]): Point2D {
    let best = -1, bestLen = -1
    for (let i = 0; i < points.length - 1; i++) {
        const l = segmentLength(points[i], points[i + 1])
        if (l > bestLen) {
            bestLen = l;
            best = i
        }
    }
    return segmentMidpoint(points[best], points[best + 1])
}

/**
 * Returns the SVG cubic bezier `d` attribute for a self-loop arcing above [node].
 * The loop exits the top-right boundary quadrant and re-enters the top-left quadrant.
 * Use this string directly as the `d` attribute of a `<path>` — do NOT route via
 * buildPolylinePath, which expects a straight polyline.
 */
export function selfLoopSvgPath(node: { cx: number; cy: number; width: number; height: number }): string {
    const r = Math.max(30, node.width * 0.45)
    const top = node.cy - node.height / 2
    const x1 = node.cx + node.width * 0.25
    const x2 = node.cx - node.width * 0.25
    const cy1 = top - r * 1.6
    const cx1 = node.cx + r
    const cx2 = node.cx - r
    return `M ${x1} ${top} C ${cx1} ${cy1}, ${cx2} ${cy1}, ${x2} ${top}`
}

/**
 * Returns two representative points for self-loop label placement —
 * the apex of the arc above the node.
 */
export function selfLoopLabelPoint(node: { cx: number; cy: number; height: number }): Point2D {
    return {x: node.cx, y: node.cy - node.height / 2 - Math.max(30, 0) * 1.3}
}
```

### 4.3 `router.ts`

```typescript
import {Point2D, Rect, rectBoundaryIntersect} from './geometry'
import {EdgeRouting} from './types'

export interface EdgeRouter {
    route(source: Rect, target: Rect): Point2D[]
}

export class ThreeSegmentRouter implements EdgeRouter {
    route(source: Rect, target: Rect): Point2D[] {
        const p1 = rectBoundaryIntersect(source, {x: target.cx, y: target.cy})
        const p2 = rectBoundaryIntersect(target, {x: source.cx, y: source.cy})

        const dx = Math.abs(target.cx - source.cx)
        const dy = Math.abs(target.cy - source.cy)

        if (dx >= dy) {
            const mx = (p1.x + p2.x) / 2
            return [p1, {x: mx, y: p1.y}, {x: mx, y: p2.y}, p2]
        } else {
            const my = (p1.y + p2.y) / 2
            return [p1, {x: p1.x, y: my}, {x: p2.x, y: my}, p2]
        }
    }
}

export class StraightRouter implements EdgeRouter {
    route(source: Rect, target: Rect): Point2D[] {
        const p1 = rectBoundaryIntersect(source, {x: target.cx, y: target.cy})
        const p2 = rectBoundaryIntersect(target, {x: source.cx, y: source.cy})
        return [p1, p2]
    }
}

export function routerFor(routing: EdgeRouting): EdgeRouter {
    return routing === 'ORTHOGONAL' ? new ThreeSegmentRouter() : new StraightRouter()
}
```

### 4.4 `renderer.ts`

Builds the SVG edge layer from `WorkspaceState`. Returns an SVG string injected into the shadow
DOM's `<svg>` element via `innerHTML`.

Handles three cases:

- **Normal edge** — source and target are different nodes; routed via `routerFor`
- **Self-loop** — `sourceId === targetId`; rendered as a cubic bezier arc above the node via
  `selfLoopSvgPath`; label placed at arc apex via `selfLoopLabelPoint`
- **Multi-edge** — multiple edges between the same ordered pair; each is offset perpendicular
  to the direct line by `(index - midIndex) * MULTI_EDGE_OFFSET` px so they fan out and remain
  individually readable

```typescript
import {NodeState, EdgeState, ArrowType, WorkspaceState} from './types'
import {
    Point2D, longestSegmentMidpoint, segmentMidpoint,
    selfLoopSvgPath, selfLoopLabelPoint,
} from './geometry'
import {routerFor} from './router'

/** Perpendicular separation between parallel multi-edges, in workspace px */
const MULTI_EDGE_OFFSET = 12

export function buildEdgeSvg(state: WorkspaceState): string {
    const nodeMap = new Map(state.nodes.map(n => [n.id, n]))
    const lines: string[] = ['<defs></defs>']

    // Group edges by canonical pair key (smaller id first) to detect multi-edges.
    // Self-loops are keyed as "id:id".
    const pairGroups = new Map<string, EdgeState[]>()
    for (const edge of state.edges) {
        const key = edge.sourceId <= edge.targetId
            ? `${edge.sourceId}:${edge.targetId}`
            : `${edge.targetId}:${edge.sourceId}`
        const group = pairGroups.get(key) ?? []
        group.push(edge)
        pairGroups.set(key, group)
    }

    for (const [, group] of pairGroups) {
        const midIndex = (group.length - 1) / 2

        group.forEach((edge, i) => {
            const src = nodeMap.get(edge.sourceId)
            const dst = nodeMap.get(edge.targetId)
            if (!src || !dst) return

            // ── Self-loop ────────────────────────────────────────────────────────
            if (edge.sourceId === edge.targetId) {
                const d = selfLoopSvgPath(src)
                const markerId = `arrow-${edge.id}`
                lines.push(markerDef(markerId + '-start', edge.startArrow, edge.color, true))
                lines.push(markerDef(markerId + '-end', edge.endArrow, edge.color, false))
                const startM = edge.startArrow !== 'NONE' ? `marker-start="url(#${markerId}-start)"` : ''
                const endM = edge.endArrow !== 'NONE' ? `marker-end="url(#${markerId}-end)"` : ''
                lines.push(
                    `<path d="${d}" fill="none" stroke="${edge.color}"` +
                    ` stroke-width="${edge.strokeWidth}" ${startM} ${endM} data-edge-id="${edge.id}"/>`
                )
                if (edge.label) {
                    lines.push(buildLabel(edge, selfLoopLabelPoint(src)))
                }
                return
            }

            // ── Normal / multi-edge ──────────────────────────────────────────────
            const router = routerFor(edge.routing)
            let points = router.route(
                {cx: src.cx, cy: src.cy, width: src.width, height: src.height},
                {cx: dst.cx, cy: dst.cy, width: dst.width, height: dst.height},
            )

            // Apply perpendicular offset for multi-edges (offset = 0 for single edges)
            const offset = (i - midIndex) * MULTI_EDGE_OFFSET
            if (offset !== 0) {
                points = applyPerpendicularOffset(points, offset)
            }

            lines.push(buildPath(edge, points))

            if (edge.label) {
                const labelPt = edge.routing === 'STRAIGHT'
                    ? segmentMidpoint(points[0], points[points.length - 1])
                    : longestSegmentMidpoint(points)
                lines.push(buildLabel(edge, labelPt))
            }
        })
    }

    return lines.join('\n')
}

/**
 * Offsets every point in a polyline by [offset] px perpendicular to the
 * overall direction of the path (first→last point). Positive offset is
 * to the left of the direction of travel.
 */
function applyPerpendicularOffset(points: Point2D[], offset: number): Point2D[] {
    if (points.length < 2) return points
    const first = points[0], last = points[points.length - 1]
    const dx = last.x - first.x
    const dy = last.y - first.y
    const len = Math.hypot(dx, dy)
    if (len === 0) return points
    // Perpendicular unit vector (rotate 90° left)
    const px = -dy / len
    const py = dx / len
    return points.map(p => ({x: p.x + px * offset, y: p.y + py * offset}))
}

function buildPath(edge: EdgeState, points: Point2D[]): string {
    const d = 'M ' + points.map(p => `${p.x} ${p.y}`).join(' L ')
    const markerId = `arrow-${edge.id}`
    const startM = edge.startArrow !== 'NONE' ? `marker-start="url(#${markerId}-start)"` : ''
    const endM = edge.endArrow !== 'NONE' ? `marker-end="url(#${markerId}-end)"` : ''
    return [
        markerDef(markerId + '-start', edge.startArrow, edge.color, true),
        markerDef(markerId + '-end', edge.endArrow, edge.color, false),
        `<path d="${d}" fill="none" stroke="${edge.color}"`,
        ` stroke-width="${edge.strokeWidth}" ${startM} ${endM} data-edge-id="${edge.id}"/>`,
    ].join('')
}

function buildLabel(edge: EdgeState, pt: Point2D): string {
    // Measure label width roughly at 11px: ~6.5px per char + 8px padding each side
    const w = Math.max(48, edge.label!.length * 6.5 + 16)
    return [
        `<rect x="${pt.x - w / 2}" y="${pt.y - 9}" width="${w}" height="16" rx="3"`,
        ` fill="var(--lumo-base-color, white)" opacity="0.85"/>`,
        `<text x="${pt.x}" y="${pt.y + 1}" text-anchor="middle" dominant-baseline="central"`,
        ` font-size="11" fill="${edge.color}" font-family="var(--lumo-font-family, sans-serif)">`,
        `${edge.label}</text>`,
    ].join('\n')
}

function markerDef(id: string, type: ArrowType, color: string, reverse: boolean): string {
    if (type === 'NONE') return ''
    const orient = reverse ? 'auto-start-reverse' : 'auto'
    return `<defs><marker id="${id}" viewBox="0 0 10 10" refX="8" refY="5"` +
        ` markerWidth="6" markerHeight="6" orient="${orient}">${markerShape(type, color)}</marker></defs>`
}

function markerShape(type: ArrowType, color: string): string {
    switch (type) {
        case 'ARROW':
            return `<path d="M2 1L8 5L2 9" fill="none" stroke="${color}" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>`
        case 'CIRCLE':
            return `<circle cx="5" cy="5" r="3" fill="${color}"/>`
        case 'DIAMOND':
            return `<path d="M1 5L5 2L9 5L5 8Z" fill="${color}"/>`
        default:
            return ''
    }
}
```

### 4.5 `grid.ts`

```typescript
import {WorkspaceGrid} from './types'

export function paintGrid(
    canvas: HTMLCanvasElement,
    grid: WorkspaceGrid,
    panX: number, panY: number,
    zoom: number,
): void {
    const ctx = canvas.getContext('2d')!
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    if (!grid.enabled || zoom < grid.minZoomVisible) return

    const step = grid.size * zoom
    const offsetX = ((panX % step) + step) % step
    const offsetY = ((panY % step) + step) % step

    ctx.strokeStyle = getComputedStyle(canvas).getPropertyValue('--lumo-contrast-10pct')
        || 'rgba(0,0,0,0.1)'
    ctx.fillStyle = ctx.strokeStyle

    if (grid.style === 'LINES') {
        ctx.lineWidth = 0.5
        ctx.beginPath()
        for (let x = offsetX; x < canvas.width; x += step) {
            ctx.moveTo(x, 0);
            ctx.lineTo(x, canvas.height)
        }
        for (let y = offsetY; y < canvas.height; y += step) {
            ctx.moveTo(0, y);
            ctx.lineTo(canvas.width, y)
        }
        ctx.stroke()
    } else {
        const r = Math.max(1, zoom * 0.8)
        for (let x = offsetX; x < canvas.width; x += step) {
            for (let y = offsetY; y < canvas.height; y += step) {
                ctx.beginPath()
                ctx.arc(x, y, r, 0, Math.PI * 2)
                ctx.fill()
            }
        }
    }
}
```

### 4.6 `viewport.ts`

```typescript
import {NodeState, ViewportState} from './types'

export class Viewport {
    panX = 0;
    panY = 0;
    zoom = 1

    private minZoom = 0.1
    private maxZoom = 4.0

    applyTransform(el: HTMLElement): void {
        el.style.transform = `translate(${this.panX}px, ${this.panY}px) scale(${this.zoom})`
    }

    pan(dx: number, dy: number): void {
        this.panX += dx
        this.panY += dy
    }

    /** Zoom toward a screen-space focal point (e.g. mouse position) */
    zoomAt(delta: number, focalX: number, focalY: number): void {
        const factor = delta > 0 ? 1.1 : 1 / 1.1
        const newZoom = Math.max(this.minZoom, Math.min(this.maxZoom, this.zoom * factor))
        const ratio = newZoom / this.zoom
        this.panX = focalX + (this.panX - focalX) * ratio
        this.panY = focalY + (this.panY - focalY) * ratio
        this.zoom = newZoom
    }

    fitContent(nodes: NodeState[], containerWidth: number, containerHeight: number): void {
        if (nodes.length === 0) {
            this.panX = 0;
            this.panY = 0;
            this.zoom = 1;
            return
        }

        const padding = 40
        const minX = Math.min(...nodes.map(n => n.cx - n.width / 2))
        const minY = Math.min(...nodes.map(n => n.cy - n.height / 2))
        const maxX = Math.max(...nodes.map(n => n.cx + n.width / 2))
        const maxY = Math.max(...nodes.map(n => n.cy + n.height / 2))

        const contentW = maxX - minX
        const contentH = maxY - minY

        const scaleX = (containerWidth - padding * 2) / contentW
        const scaleY = (containerHeight - padding * 2) / contentH
        this.zoom = Math.min(scaleX, scaleY, 1)   // never zoom in beyond 1:1

        this.panX = (containerWidth - contentW * this.zoom) / 2 - minX * this.zoom
        this.panY = (containerHeight - contentH * this.zoom) / 2 - minY * this.zoom
    }

    fromState(vp: ViewportState): void {
        this.panX = vp.panX;
        this.panY = vp.panY;
        this.zoom = vp.zoom
    }

    toState(): ViewportState {
        return {panX: this.panX, panY: this.panY, zoom: this.zoom}
    }
}
```

### 4.7 `workspace.ts` — Web component entry point

This file defines the custom element `<vaadin-workspace-view>` that Vaadin renders.

Changes from the base version:

- Node slots have a `click` listener that calls `this.$server.onNodeClicked(nodeId)`
- Touch handling tracks **midpoint** across all finger counts so pan works during pinch
- `(this as any).$server` is the Vaadin-injected server-side proxy — always available after
  the element is connected to a Vaadin view

```typescript
import {WorkspaceState} from './types'
import {Viewport} from './viewport'
import {buildEdgeSvg} from './renderer'
import {paintGrid} from './grid'

class VaadinWorkspaceView extends HTMLElement {
    private viewport = new Viewport()
    private state: WorkspaceState | null = null
    private shadow: ShadowRoot
    private canvas: HTMLCanvasElement
    private transformLayer: HTMLDivElement
    private svgLayer: SVGSVGElement
    private nodeLayer: HTMLDivElement
    private ro: ResizeObserver

    // Vaadin sets this property when state changes
    set workspaceState(json: string) {
        this.state = JSON.parse(json) as WorkspaceState
        this.applyState()
    }

    constructor() {
        super()
        this.shadow = this.attachShadow({mode: 'open'})
        this.shadow.innerHTML = `
      <style>
        :host { display: block; position: relative; overflow: hidden;
                width: 100%; height: 100%; min-height: 400px; }
        canvas { position: absolute; inset: 0; pointer-events: none; }
        .transform-layer { position: absolute; inset: 0;
                           transform-origin: 0 0; will-change: transform; }
        svg { position: absolute; inset: 0; width: 100%; height: 100%;
              overflow: visible; pointer-events: none; }
        .node-layer { position: absolute; inset: 0; }
        .node-slot  { position: absolute; transform: translate(-50%, -50%);
                      cursor: pointer; }
      </style>
      <canvas></canvas>
      <div class="transform-layer">
        <svg xmlns="http://www.w3.org/2000/svg"></svg>
        <div class="node-layer"></div>
      </div>
    `

        this.canvas = this.shadow.querySelector('canvas')!
        this.transformLayer = this.shadow.querySelector('.transform-layer')!
        this.svgLayer = this.shadow.querySelector('svg')!
        this.nodeLayer = this.shadow.querySelector('.node-layer')!
        this.ro = new ResizeObserver(() => this.onResize())
    }

    connectedCallback() {
        this.ro.observe(this)
        this.bindInteractions()
    }

    disconnectedCallback() {
        this.ro.disconnect()
    }

    private applyState() {
        if (!this.state) return
        this.syncNodeSlots()
        if (this.state.viewport) {
            this.viewport.fromState(this.state.viewport)
        } else {
            this.viewport.fitContent(this.state.nodes, this.offsetWidth, this.offsetHeight)
        }
        this.render()
    }

    private syncNodeSlots() {
        if (!this.state) return
        const nodeMap = new Map(this.state.nodes.map(n => [n.id, n]))

        // Remove stale slots
        for (const slot of Array.from(this.nodeLayer.querySelectorAll<HTMLElement>('.node-slot'))) {
            if (!nodeMap.has(slot.dataset.nodeId!)) slot.remove()
        }

        // Update / create slots
        for (const node of this.state.nodes) {
            let slot = this.nodeLayer.querySelector<HTMLElement>(`[data-node-id="${node.id}"]`)
            if (!slot) {
                slot = document.createElement('div')
                slot.className = 'node-slot'
                slot.dataset.nodeId = node.id

                // Move the slotted Vaadin component into the slot div
                const slottedEl = this.querySelector(`[slot="node-${node.id}"]`)
                if (slottedEl) slot.appendChild(slottedEl.firstElementChild!)

                // ── Click → server ────────────────────────────────────────────────
                // Capture nodeId in closure; do not re-read from dataset in handler
                // to avoid any timing issues during slot reuse.
                const nodeId = node.id
                slot.addEventListener('click', (e) => {
                    e.stopPropagation()   // prevent pan-start on the host element
                    // $server is injected by Vaadin's web component bootstrap
                    ;(this as any).$server?.onNodeClicked(nodeId)
                })

                this.nodeLayer.appendChild(slot)
            }
            slot.style.left = `${node.cx}px`
            slot.style.top = `${node.cy}px`
            slot.style.width = `${node.width}px`
            slot.style.height = `${node.height}px`
        }
    }

    private render() {
        if (!this.state) return
        this.canvas.width = this.offsetWidth
        this.canvas.height = this.offsetHeight
        paintGrid(this.canvas, this.state.grid, this.viewport.panX, this.viewport.panY, this.viewport.zoom)
        this.viewport.applyTransform(this.transformLayer)
        this.svgLayer.innerHTML = buildEdgeSvg(this.state)
    }

    private onResize() {
        this.canvas.width = this.offsetWidth
        this.canvas.height = this.offsetHeight
        if (this.state && !this.state.viewport) {
            this.viewport.fitContent(this.state.nodes, this.offsetWidth, this.offsetHeight)
        }
        this.render()
    }

    // ── Interactions ────────────────────────────────────────────────────────

    private isPanning = false
    private lastMouseX = 0;
    private lastMouseY = 0

    private bindInteractions() {
        // Mouse wheel zoom
        this.addEventListener('wheel', e => {
            e.preventDefault()
            const rect = this.getBoundingClientRect()
            this.viewport.zoomAt(-e.deltaY, e.clientX - rect.left, e.clientY - rect.top)
            this.render()
        }, {passive: false})

        // Middle-click or left-click on the host (not a node slot) to pan
        this.addEventListener('mousedown', e => {
            if (e.button === 1 || (e.button === 0 && (e.target === this || e.target === this.transformLayer))) {
                this.isPanning = true
                this.lastMouseX = e.clientX;
                this.lastMouseY = e.clientY
                e.preventDefault()
            }
        })

        window.addEventListener('mousemove', e => {
            if (!this.isPanning) return
            this.viewport.pan(e.clientX - this.lastMouseX, e.clientY - this.lastMouseY)
            this.lastMouseX = e.clientX;
            this.lastMouseY = e.clientY
            this.render()
        })

        window.addEventListener('mouseup', () => {
            this.isPanning = false
        })

        // ── Touch: simultaneous pan + pinch ────────────────────────────────────
        // Track BOTH midpoint (for pan) AND distance (for zoom) on every touchmove,
        // regardless of finger count. This means two-finger pan works during pinch.
        let lastMidX = 0, lastMidY = 0, lastDist = 0

        const touchMid = (touches: TouchList) => ({
            x: (touches[0].clientX + (touches[1]?.clientX ?? touches[0].clientX)) / 2,
            y: (touches[0].clientY + (touches[1]?.clientY ?? touches[0].clientY)) / 2,
        })

        const touchDist = (touches: TouchList) =>
            touches.length >= 2
                ? Math.hypot(touches[1].clientX - touches[0].clientX,
                    touches[1].clientY - touches[0].clientY)
                : 0

        this.addEventListener('touchstart', e => {
            const mid = touchMid(e.touches)
            lastMidX = mid.x;
            lastMidY = mid.y
            lastDist = touchDist(e.touches)
        }, {passive: true})

        this.addEventListener('touchmove', e => {
            e.preventDefault()
            const mid = touchMid(e.touches)
            const dist = touchDist(e.touches)

            // Pan: always apply midpoint delta
            this.viewport.pan(mid.x - lastMidX, mid.y - lastMidY)

            // Zoom: only when two fingers present
            if (e.touches.length >= 2 && lastDist > 0) {
                const rect = this.getBoundingClientRect()
                const focalX = mid.x - rect.left
                const focalY = mid.y - rect.top
                // zoomAt expects a deltaY-style value: positive = zoom in
                this.viewport.zoomAt((dist - lastDist) * 0.5, focalX, focalY)
            }

            lastMidX = mid.x;
            lastMidY = mid.y;
            lastDist = dist
            this.render()
        }, {passive: false})
    }
}

customElements.define('vaadin-workspace-view', VaadinWorkspaceView)
```

---

## Phase 5 — CSS / theming

Add to `frontend/themes/your-theme/components/vaadin-workspace-view.css`:

```css
vaadin-workspace-view {
    display: block;
    width: 100%;
    height: 100%;
    min-height: 400px;
    background: var(--lumo-base-color);
    border-radius: var(--lumo-border-radius-m);
    border: 1px solid var(--lumo-contrast-10pct);
}
```

---

## Phase 6 — Karibu DSL extension function

To match the `horizontalLayout(this) { }` pattern exactly:

```kotlin
@VaadinDsl
fun <T : Any> (@VaadinDsl HasComponents).workspaceView(
    dataProvider: DataProvider<T, Nothing?>,
    block: WorkspaceView<T>.() -> Unit = {},
): WorkspaceView<T> = init(WorkspaceView<T>(), block).also {
        it.dataProvider = dataProvider
    }
```

---

## Acceptance criteria

Claude Code must verify each of these before marking implementation complete:

### Rendering

- [ ] `WorkspaceView<Story>` renders with `DataProvider.ofCollection(stories)` and shows nodes
  at correct positions
- [ ] Straight edges draw from boundary to boundary, not center to center
- [ ] Orthogonal edges produce exactly 3 segments (4 points) for non-degenerate cases
- [ ] Edge labels appear at midpoint of straight edge / longest segment of orthogonal edge
- [ ] All four arrow types render correctly (NONE, ARROW, CIRCLE, DIAMOND)
- [ ] Grid dots/lines tile correctly across the full canvas at all zoom levels
- [ ] Grid disappears below `minZoomVisible` zoom threshold

### Self-loops

- [ ] An edge where `sourceId === targetId` renders as a cubic bezier arc above the node
- [ ] The arc size scales with node width (`Math.max(30, width * 0.45)`)
- [ ] A label on a self-loop appears above the arc apex, not at the node center
- [ ] Start and end arrow markers render correctly on self-loop paths

### Multi-edges

- [ ] Two edges between the same pair of nodes render as two distinct parallel paths,
  offset perpendicular to the direct line by `MULTI_EDGE_OFFSET` (12px) each side
- [ ] Three or more edges between the same pair fan out symmetrically around the midline
- [ ] Multi-edge grouping is by unordered pair — A→B and B→A are in the same group
- [ ] Each edge in a multi-edge group has a unique id (no collision)

### Viewport

- [ ] Pinch-to-zoom and mouse-wheel zoom both anchor at the focal point
- [ ] Two-finger pan works simultaneously during pinch (midpoint tracking)
- [ ] `fitContent()` server call updates viewport on next render
- [ ] `setViewport(panX, panY, zoom)` overrides fitContent

### Data lifecycle

- [ ] Removing an item from the `DataProvider` and calling `refreshAll()` removes its node
  slot and all incident edges
- [ ] `describe` is called exactly once per item per refresh — no duplicate calls
- [ ] Component instances are reused across refreshes for stable `T` items (no re-render)
- [ ] `idToItem` reverse map is kept in sync with `nodeSlots` after every `doRefresh`

### Click events

- [ ] Clicking a node slot fires `NodeClickEvent<T>` on the server with the correct `item`
- [ ] `addNodeClickListener` returns a `Registration` that can be removed
- [ ] Clicking empty workspace space does NOT fire a node click event
- [ ] Node click does not interfere with pan (stopPropagation is called)

### DSL

- [ ] No string IDs leak to the DSL call site
- [ ] `workspaceView<T>(provider) { describe { ... }; render { ... } }` compiles and runs

---

## Known limitations (do not fix in this iteration)

- No edge interactivity — edges are purely visual, no click/hover events
- `ThreeSegmentRouter` does not avoid obstacles — deferred to future `AStarRouter`