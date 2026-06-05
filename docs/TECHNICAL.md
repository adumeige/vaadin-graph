# vaadin-graph — Technical documentation

This document describes how `WorkspaceView` is built: the architecture, the server↔client protocol,
the refresh lifecycle, the edge-rendering pipeline, and the layout subsystem. For usage see the
[README](../README.md).

---

## 1. Architecture at a glance

`WorkspaceView<T>` is **data-driven and server-authoritative**, in the spirit of `Grid<T>`. The
component owns no graph data; it pulls items from a `DataProvider<T,?>` and asks two caller-supplied
functions to describe and render each item:

```
DataProvider<T,?> ──fetch──▶ items
                              │
            describe(T) ──────┤── geometry + outgoing edges (WorkspaceDescriptor)
            render(T)   ──────┘── node visuals (any Vaadin Component)
                              │
                     diff + serialize
                              ▼
   server  ────────  WorkspaceState (JSON, domain-free)  ────────▶  client
                     pushed as an element property                  <vaadin-workspace-view>
                              ▲                                              │
            @ClientCallable   └──── onNodeClicked / onEdgeClicked ◀──────────┘
```

Two halves:

- **Server** (`vaadin-graph-component`, Java) — diffs items, hosts each rendered node as a light-DOM
  slot, resolves edge targets to ids, and serializes a domain-free snapshot.
- **Client** (`META-INF/frontend/workspace/*.ts`) — a vanilla web component that paints the grid,
  routes & draws the SVG edges, positions the node slots, and handles pan/zoom and clicks.

The serialized snapshot (`WorkspaceState`) contains **no `T`** — all domain objects are resolved to
string ids before crossing to the client. Jackson serializes enums by `name()`, which matches the
TypeScript string-literal unions exactly (e.g. `EdgeRouting.CURVED` ⇄ `'CURVED'`).

### Module layout

```
vaadin-graph-component/src/main/
  java/org/antoined/vaadin/graph/
    component/   WorkspaceView, NodeClickEvent, EdgeClickEvent
    model/       WorkspaceDescriptor, EdgeDescriptor, WorkspaceGrid,
                 WorkspaceState / NodeState / EdgeState / ViewportState,
                 EdgeRouting, ArrowType, GridStyle, EdgeStyle
    routing/     Point2D, Rect, Geometry, EdgeRouter, ThreeSegmentRouter, StraightRouter
    layout/      LayoutAlgorithm, LayoutInput / LayoutNode / LayoutEdge,
                 OverlapRemovalLayout, ForceDirectedLayout, HierarchicalLayout,
                 OrthogonalLayout, AStarLayout
  resources/META-INF/frontend/workspace/
    types.ts geometry.ts router.ts renderer.ts grid.ts viewport.ts workspace.ts
```

> The `routing/` Java classes (`EdgeRouter`, `ThreeSegmentRouter`, `StraightRouter`) mirror the
> client routers for parity / headless use. **Edge geometry is computed on the client**; the server
> only sends each edge's routing *mode*.

---

## 2. Server↔client protocol

The only thing sent to the client is the `workspaceState` element property — a JSON string of:

```java
record WorkspaceState(
    List<NodeState> nodes,        // id, cx, cy, width, height
    List<EdgeState> edges,        // see below
    WorkspaceGrid   grid,         // enabled, size, style, snapEnabled, snapStrength, minZoomVisible
    ViewportState   viewport,     // panX, panY, zoom — or null
    boolean         fit,          // one-shot "fit to content"
    double          edgeSpacing)  // gap between multi-edges, px

record EdgeState(
    String id, String sourceId, String targetId,
    String label, double labelOffset,
    String color, int strokeWidth,
    EdgeRouting routing, ArrowType startArrow, ArrowType endArrow, EdgeStyle style)
```

### Viewport handling (one-shot)

To keep data refreshes from yanking the user's view around, viewport intent is **one-shot**:

- `viewport != null` → pin that transform (from `setViewport`).
- else `fit == true` → fit-to-content once (from `fitContent`, and on first render).
- else → the client keeps its current pan/zoom.

After each push the server clears `pendingViewport` and `fitRequested`. The client tracks
`initialized` and `userAdjusted` (set on wheel/drag/touch) so a window resize re-fits only until the
user has taken control.

### Refresh coalescing

Every setter calls `scheduleRefresh()`, which guards with a `refreshScheduled` flag and a single
`ui.access(...)`. Multiple mutations in one request (e.g. `applyLayout(...)` followed by
`fitContent()`) therefore collapse into **one** `doRefresh`, so one-shot flags aren't consumed by an
earlier refresh.

---

## 3. Refresh lifecycle (`WorkspaceView.doRefresh`)

1. **Fetch** items: `dataProvider.fetch(new Query<>())`.
2. **Force rebuild** (only after `setRender`): dispose every slot so all nodes re-render.
3. **Diff** the item set against `nodeSlots` (a `LinkedHashMap<T, NodeSlot>`):
   - *remove* stale slots (detach the light-DOM `<div slot=…>` element);
   - *add* new slots — mint a UUID id, call `render(item)`, wrap the component's element in
     `<div slot="node-<id>" data-node-id="<id>">`, append it to the host's light DOM.
4. **Sync** `idToItem` (`internalId → T`).
5. **Describe** each item exactly once → `WorkspaceDescriptor<T>`.
6. **NodeState** list from slot ids + descriptor geometry.
7. **EdgeState** list: resolve each `EdgeDescriptor.target()` to its slot id (dropping edges to
   absent nodes); suffix duplicate `src-dst` pairs for unique ids; populate `idToEdge`
   (`edgeId → (source item, descriptor)`) so clicks resolve back to domain objects.
8. **Serialize** `WorkspaceState` with Jackson and `setProperty("workspaceState", json)`.

Node component instances are reused across refreshes for stable `T` keys (only `setRender` forces a
rebuild). `describe` is called once per item per refresh.

---

## 4. Node slots (light DOM → shadow DOM)

A rendered node is a real Vaadin component. The server appends it in the host's **light DOM**:

```html
<vaadin-workspace-view>
  <div slot="node-abc" data-node-id="abc"><my-card>…</my-card></div>
</vaadin-workspace-view>
```

There is no matching `<slot>` in the shadow root, so the browser doesn't project it. Instead
`workspace.ts` **moves** the component into a positioned `.node-slot` div inside the shadow
`node-layer` (`syncNodeSlots`), sets `left/top/width/height` from the `NodeState`, and attaches a
`click` → `this.$server.onNodeClicked(id)` handler. Stale slots are removed when their id disappears.

---

## 5. Component API (`WorkspaceView<T>`)

| Method | Purpose |
|---|---|
| `setDataProvider(DataProvider<T,?>)` | Source of nodes; the view listens for changes. |
| `setDescribe(SerializableFunction<T, WorkspaceDescriptor<T>>)` | Topology + geometry per node. |
| `setRender(SerializableFunction<T, Component>)` | Node visuals; **re-renders all** existing nodes. |
| `setGrid(WorkspaceGrid)` / `getGrid()` | Background grid config. |
| `setEdgeSpacing(double)` / `getEdgeSpacing()` | Gap between edges sharing a node pair. |
| `fitContent()` | One-shot fit-to-content on the next render. |
| `setViewport(panX, panY, zoom)` | Pin an explicit transform. |
| `refresh()` | Re-fetch / re-describe / push (viewport preserved). |
| `setPositionWriter(SerializableBiConsumer<T, Point2D>)` | How a layout writes a position back into `T`. |
| `applyLayout(LayoutAlgorithm)` | Run a layout and write results back (see §8). |
| `addNodeClickListener(...)` / `addEdgeClickListener(...)` | Component events. |

### Events

- `NodeClickEvent<T>` → `getItem()`.
- `EdgeClickEvent<T>` → `getSourceItem()`, `getTargetItem()`, `getEdge()` (the `EdgeDescriptor`).
  `EdgeDescriptor.payload(Object)` carries opaque caller data recovered via
  `event.getEdge().payload()` — handy to map a clicked edge back to your own model, including for
  multi-edges.

### Model

`WorkspaceDescriptor<T>(cx, cy, width, height, List<EdgeDescriptor<T>>)` is a record.
`EdgeDescriptor<T>` is built via `EdgeDescriptor.to(target)`:

```java
EdgeDescriptor.to(otherNode)
    .label("depends on")        // default null
    .labelOffset(0.5)
    .color("#888888")
    .strokeWidth(2)
    .routing(EdgeRouting.ORTHOGONAL)   // default STRAIGHT
    .startArrow(ArrowType.NONE)
    .endArrow(ArrowType.ARROW)
    .style(EdgeStyle.DASHED)           // default SOLID
    .payload(myEdgeObject)
    .build();
```

`WorkspaceGrid` is a mutable POJO (`enabled`, `size`, `style`, `snapEnabled`, `snapStrength`,
`minZoomVisible`). Enums: `EdgeRouting`, `ArrowType`, `GridStyle`, `EdgeStyle`.

---

## 6. Edge rendering pipeline (`renderer.ts`)

`buildEdgeSvg(state)` returns the SVG markup injected into the shadow `<svg>`.

1. **Group** edges by canonical unordered pair key (`min(id):max(id)`); self-loops key as `id:id`.
2. Per group, compute a **canonical perpendicular** `perp = rot90(centerB − centerA)` from the
   *id-sorted* node centers — a fixed reference so anti-parallel edges (A→B and B→A) separate to
   opposite sides instead of stacking.
3. For each edge `i`, `offset = (i − midIndex) · edgeSpacing`:

   - **Self-loop** (`source == target`): a cubic-bezier arc above the node (`selfLoopSvgPath`); label
     at the arc apex.
   - **Boundary attachment** — the key correctness point:
     - `offset == 0` → clip the centre-to-centre line at each box (`rectBoundaryIntersect`);
     - `offset != 0` → shift both centres by `perp·offset` and clip the **offset line**
       (`rayBoxExit` from the shifted origin). Each multi-edge thus meets the boundary at *its own*
       lane point — not the centre line — so lanes don't pinch together at the nodes.
   - **Build the path** from the two attachment points:

     | routing | path |
     |---|---|
     | `STRAIGHT` | `[p1, p2]` |
     | `ORTHOGONAL` | 3-segment `elbow(p1, p2)` on the dominant axis |
     | `ROUNDED` | the elbow with `roundedPolylinePath` (fillet each corner) |
     | `CURVED` | `[p1, bend, p2]` with one rounded bend; `bend = mid + perp · sign(offset) · gap · BOW_RATIO` (symmetric fan), rounded |

   `roundedPolylinePath(points, radius)` replaces each interior corner with a single quadratic
   fillet, radius clamped to half of each adjoining segment; the corner radius is a fraction of the
   inter-node distance.

4. **`edgePaths(edge, d)`** emits, per edge: the arrowhead `<marker>` defs, a **wide transparent
   "hit" path** (kept solid, for comfortable clicking), and the **visible stroked path** carrying
   `stroke` (color), `stroke-width`, arrow markers, the dash pattern, and `data-edge-id`. Both paths
   carry `data-edge-id` so a delegated click handler resolves the edge wherever the pointer lands.

### Stroke style

```ts
DASHED → stroke-dasharray="4w 3w"
DOTTED → stroke-dasharray="0 2.4w" stroke-linecap="round"   // round dots
```
(`w` = stroke width). The transparent hit path stays solid so click targets are unaffected.

### Arrowheads

`markerDef` builds a `<marker>` per end (`auto` / `auto-start-reverse` orientation). `markerShape`
draws `ARROW` (open chevron), `CIRCLE`, or `DIAMOND`; `NONE` emits nothing.

---

## 7. Client web component (`workspace.ts`)

Shadow DOM structure:

```
<canvas>                     ← background grid (paintGrid)
<div class="transform-layer">  ← translate(pan) scale(zoom)
  <svg>                      ← edges (buildEdgeSvg innerHTML)
  <div class="node-layer">   ← .node-slot divs (moved-in node components)
```

- **Pointer events**: `svg` is `pointer-events:none` except `.edge-hit` (`stroke`); `node-layer` is
  `none` while `.node-slot` is `auto` — so edges and nodes are both clickable and empty space pans.
- **`set workspaceState`** → `JSON.parse` → `applyState`: sync slots, decide viewport, `render`.
- **`render`** sizes the canvas, paints the grid, applies the transform, and sets
  `svg.innerHTML = buildEdgeSvg(state)`. It also exposes test hooks: `data-node-count`,
  `data-edge-count`, a `rendered` attribute, and a `workspace-rendered` event.
- **Interactions**: wheel/pinch zoom anchored at the focal point (`Viewport.zoomAt`), drag/touch pan,
  and a delegated `click` on the SVG layer → `$server.onEdgeClicked`.
- **`grid.ts`** tiles dots or lines and hides below `minZoomVisible`. **`viewport.ts`** owns
  pan/zoom math, `fitContent`, and state conversion.

---

## 8. Layout subsystem

```java
@FunctionalInterface
interface LayoutAlgorithm {
    Map<String, Point2D> layout(LayoutInput input);   // new centres by node id
}
record LayoutInput(List<LayoutNode> nodes, List<LayoutEdge> edges);
record LayoutNode(String id, double cx, double cy, double width, double height);
record LayoutEdge(String sourceId, String targetId);
```

`applyLayout(algorithm)` reads the current geometry (via `describe`), builds a `LayoutInput`, runs
the algorithm, and writes each result back through the `positionWriter` you registered with
`setPositionWriter`. The component stays data-driven — it never mutates your model directly — and the
layout result lives in the same place as everything else (so node nudging and re-layout share one
source of truth). It does **not** auto-fit; call `fitContent()` afterwards if you want.

| Algorithm | How it works |
|---|---|
| `OverlapRemovalLayout` | Iteratively pushes overlapping pairs apart along their axis of least penetration until none overlap (respecting the min distance). De-clutters without restructuring. |
| `ForceDirectedLayout` | Fruchterman–Reingold: pairwise repulsion `k²/d`, edge springs `d²/k`, gentle centroid gravity, linear cooling — seeded from current positions (deterministic). Finishes with an `OverlapRemovalLayout` pass so the organic result has no residual overlap. |
| `HierarchicalLayout` | Sugiyama-style: iterative DFS breaks cycles into a DAG, longest-path layering in topological order, barycenter ordering within layers to reduce crossings, then coordinate assignment with the min distance as spacing. |
| `OrthogonalLayout` | Snaps nodes onto a regular grid (cell = largest node + min distance, so it can't overlap); BFS order from the highest-degree node keeps connected nodes in adjacent cells. |
| `AStarLayout` | Places nodes hubs-first; each node's ideal cell is the centroid of its already-placed neighbours, and an A\*/priority-queue search expands outward to the nearest collision-free cell. |

All take a **minimum distance** constructor argument (the gap kept between node boxes).

---

## 9. Karibu DSL (`vaadin-graph-karibu`)

Kotlin extension functions over the Java component:

```kotlin
workspaceView(dataProvider) {          // adds a WorkspaceView<T> to any @VaadinDsl block
    describe { t -> WorkspaceDescriptor(t.x, t.y, 160.0, 80.0, t.edges) }
    render   { t -> StoryCard(t) }
    grid { size = 20; style = GridStyle.DOTS }
}
```

`describe`/`render` wrap the lambda in a `SerializableFunction`; `grid` applies a block to a fresh
`WorkspaceGrid`.

---

## 10. Frontend build notes

The TypeScript ships in `vaadin-graph-component/src/main/resources/META-INF/frontend/workspace/` and
is referenced by `@JsModule("./workspace/workspace.ts")`. Vaadin's Vite pipeline compiles the `.ts`
directly when the consuming app builds its frontend (the standard add-on convention).

Vaadin caches the optimized **production bundle** in `vaadin-graph-demo/src/main/bundles/`, which is
*not* under `target/`, so `mvn clean` doesn't remove it — and a content-only TS change isn't always
detected as needing a rebuild. If a frontend change doesn't appear, force it:

```bash
mvn -Pproduction -Dvaadin.force.production.build=true clean install
# or: rm vaadin-graph-demo/src/main/bundles/*.bundle
```
