package io.github.adumeige.vaadin.graph.component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ClientCallable;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.HasSize;
import com.vaadin.flow.component.Tag;
import com.vaadin.flow.component.dependency.JsModule;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.data.provider.DataProvider;
import com.vaadin.flow.data.provider.Query;
import com.vaadin.flow.dom.Element;
import com.vaadin.flow.function.SerializableBiConsumer;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.shared.Registration;
import io.github.adumeige.vaadin.graph.layout.LayoutAlgorithm;
import io.github.adumeige.vaadin.graph.layout.LayoutEdge;
import io.github.adumeige.vaadin.graph.layout.LayoutInput;
import io.github.adumeige.vaadin.graph.layout.LayoutNode;
import io.github.adumeige.vaadin.graph.model.EdgeDescriptor;
import io.github.adumeige.vaadin.graph.model.EdgeState;
import io.github.adumeige.vaadin.graph.model.NodeState;
import io.github.adumeige.vaadin.graph.model.ViewportState;
import io.github.adumeige.vaadin.graph.model.WorkspaceDescriptor;
import io.github.adumeige.vaadin.graph.model.WorkspaceGrid;
import io.github.adumeige.vaadin.graph.model.WorkspaceState;
import io.github.adumeige.vaadin.graph.routing.EdgeRouter;
import io.github.adumeige.vaadin.graph.routing.Point2D;
import io.github.adumeige.vaadin.graph.routing.ThreeSegmentRouter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * A zoomable, pannable graph workspace. Data-driven like {@code Grid<T>}: supply a
 * {@link DataProvider}, a {@code describe} function for topology/geometry, and a {@code render}
 * function for node visuals. The component diffs items on refresh, hosts each rendered node as a
 * light-DOM slot, and pushes a domain-free {@link WorkspaceState} snapshot to the client.
 *
 * @param <T> the domain type of graph nodes
 */
@Tag("vaadin-workspace-view")
@JsModule("./workspace/workspace.ts")
public class WorkspaceView<T> extends Component implements HasSize {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Configuration ──────────────────────────────────────────────────────────

    private DataProvider<T, ?> dataProvider = DataProvider.ofCollection(new ArrayList<T>());
    private transient Registration dataProviderRegistration;

    private SerializableFunction<T, WorkspaceDescriptor<T>> describe =
            item -> new WorkspaceDescriptor<>(0, 0, 160, 80);
    private SerializableFunction<T, Component> render = item -> new Div();
    private WorkspaceGrid grid = new WorkspaceGrid();
    private EdgeRouter router = new ThreeSegmentRouter();

    /** Perpendicular separation between edges that share the same node pair, in workspace px. */
    private double edgeSpacing = 12.0;

    /** Writes a layout-computed position back into a domain object. Required for {@link #applyLayout}. */
    private SerializableBiConsumer<T, Point2D> positionWriter;

    // ── Internal state ─────────────────────────────────────────────────────────

    /** Maps domain object → (internalId, slot element, rendered component); insertion-ordered. */
    private final LinkedHashMap<T, NodeSlot> nodeSlots = new LinkedHashMap<>();

    /** Reverse map: internalId → domain object, kept in sync with {@link #nodeSlots}. */
    private final Map<String, T> idToItem = new HashMap<>();

    /** Reverse map: edge id → (source item, descriptor), rebuilt on every refresh. */
    private final Map<String, EdgeRef<T>> idToEdge = new HashMap<>();

    /** One-shot explicit viewport (from {@link #setViewport}); cleared after it is sent once. */
    private ViewportState pendingViewport = null;
    /** One-shot fit request; true initially so the first render fits, then false to preserve view. */
    private boolean fitRequested = true;

    /** When set, the next refresh disposes and re-creates every slot (e.g. after {@code setRender}). */
    private boolean forceRebuild = false;

    /** Guards {@link #scheduleRefresh} so repeated calls in one request collapse to a single refresh. */
    private transient boolean refreshScheduled = false;

    public WorkspaceView() {
        registerDataProviderListener();
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    public void setDataProvider(DataProvider<T, ?> dataProvider) {
        this.dataProvider = dataProvider;
        registerDataProviderListener();
        scheduleRefresh();
    }

    public DataProvider<T, ?> getDataProvider() {
        return dataProvider;
    }

    public void setDescribe(SerializableFunction<T, WorkspaceDescriptor<T>> describe) {
        this.describe = describe;
        scheduleRefresh();
    }

    public void setRender(SerializableFunction<T, Component> render) {
        this.render = render;
        // Re-render every existing node with the new function, not just future ones.
        forceRebuild = true;
        scheduleRefresh();
    }

    public void setGrid(WorkspaceGrid grid) {
        this.grid = grid;
        scheduleRefresh();
    }

    public WorkspaceGrid getGrid() {
        return grid;
    }

    public void setRouter(EdgeRouter router) {
        this.router = router;
    }

    public EdgeRouter getRouter() {
        return router;
    }

    /** Sets the perpendicular gap between edges that share the same node pair (multi-edges). */
    public void setEdgeSpacing(double edgeSpacing) {
        this.edgeSpacing = edgeSpacing;
        scheduleRefresh();
    }

    public double getEdgeSpacing() {
        return edgeSpacing;
    }

    /** Fit all nodes into view on the next render. */
    public void fitContent() {
        fitRequested = true;
        pendingViewport = null;
        scheduleRefresh();
    }

    /** Pin the viewport to an explicit transform, overriding {@link #fitContent()}. */
    public void setViewport(double panX, double panY, double zoom) {
        fitRequested = false;
        pendingViewport = new ViewportState(panX, panY, zoom);
        scheduleRefresh();
    }

    /** Re-fetch, re-describe, and push state to the client (preserving the current viewport). */
    public void refresh() {
        scheduleRefresh();
    }

    /**
     * Installs the function that writes a layout-computed position back into a domain object. Must
     * be set before {@link #applyLayout(LayoutAlgorithm)}.
     */
    public void setPositionWriter(SerializableBiConsumer<T, Point2D> positionWriter) {
        this.positionWriter = positionWriter;
    }

    /**
     * Runs {@code algorithm} over the current node geometry, writes the resulting positions back via
     * the {@linkplain #setPositionWriter position writer}, and refreshes. The view is not re-fitted;
     * call {@link #fitContent()} afterwards if desired.
     *
     * @throws IllegalStateException if no position writer has been set
     */
    public void applyLayout(LayoutAlgorithm algorithm) {
        if (positionWriter == null) {
            throw new IllegalStateException("setPositionWriter(...) must be called before applyLayout(...)");
        }
        List<T> items = fetchItems();
        Map<T, WorkspaceDescriptor<T>> descriptors = new LinkedHashMap<>();
        for (T item : items) {
            descriptors.put(item, describe.apply(item));
        }

        List<LayoutNode> layoutNodes = new ArrayList<>();
        for (T item : items) {
            NodeSlot slot = nodeSlots.get(item);
            if (slot == null) {
                continue;
            }
            WorkspaceDescriptor<T> d = descriptors.get(item);
            layoutNodes.add(new LayoutNode(slot.id(), d.cx(), d.cy(), d.width(), d.height()));
        }

        List<LayoutEdge> layoutEdges = new ArrayList<>();
        for (T item : items) {
            NodeSlot srcSlot = nodeSlots.get(item);
            if (srcSlot == null) {
                continue;
            }
            for (EdgeDescriptor<T> edge : descriptors.get(item).edges()) {
                NodeSlot dstSlot = nodeSlots.get(edge.target());
                if (dstSlot != null) {
                    layoutEdges.add(new LayoutEdge(srcSlot.id(), dstSlot.id()));
                }
            }
        }

        Map<String, Point2D> positions = algorithm.layout(new LayoutInput(layoutNodes, layoutEdges));
        positions.forEach((id, pos) -> {
            T item = idToItem.get(id);
            if (item != null) {
                positionWriter.accept(item, pos);
            }
        });
        scheduleRefresh();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Registration addNodeClickListener(ComponentEventListener<NodeClickEvent<T>> listener) {
        return addListener((Class) NodeClickEvent.class, (ComponentEventListener) listener);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Registration addEdgeClickListener(ComponentEventListener<EdgeClickEvent<T>> listener) {
        return addListener((Class) EdgeClickEvent.class, (ComponentEventListener) listener);
    }

    // ── Client callback ────────────────────────────────────────────────────────

    /** Invoked by the client when a node slot is clicked; resolves the id and fires the event. */
    @ClientCallable
    public void onNodeClicked(String nodeId) {
        T item = idToItem.get(nodeId);
        if (item == null) {
            return;
        }
        fireEvent(new NodeClickEvent<>(this, true, item));
    }

    /** Invoked by the client when an edge is clicked; resolves the id and fires the event. */
    @ClientCallable
    public void onEdgeClicked(String edgeId) {
        EdgeRef<T> ref = idToEdge.get(edgeId);
        if (ref == null) {
            return;
        }
        fireEvent(new EdgeClickEvent<>(this, true, ref.source(), ref.edge().target(), ref.edge()));
    }

    // ── Internals ──────────────────────────────────────────────────────────────

    private void registerDataProviderListener() {
        if (dataProviderRegistration != null) {
            dataProviderRegistration.remove();
        }
        dataProviderRegistration = dataProvider.addDataProviderListener(e -> scheduleRefresh());
    }

    private void scheduleRefresh() {
        // UI.getCurrent() may be null during init; the component pushes state once attached.
        // Coalesce: many setters (or applyLayout + fitContent) in one request collapse to a single
        // doRefresh, so one-shot flags like fit/viewport aren't consumed by an earlier refresh.
        if (refreshScheduled) {
            return;
        }
        getUI().ifPresent(ui -> {
            refreshScheduled = true;
            ui.access(() -> {
                refreshScheduled = false;
                doRefresh();
            });
        });
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<T> fetchItems() {
        return (List<T>) ((DataProvider) dataProvider).fetch(new Query()).collect(Collectors.toList());
    }

    private void doRefresh() {
        List<T> items = fetchItems();
        Set<T> next = new LinkedHashSet<>(items);

        // ── Full rebuild requested (e.g. render function changed) ───────────────
        if (forceRebuild) {
            for (NodeSlot slot : nodeSlots.values()) {
                getElement().removeChild(slot.element());
                slot.element().removeAllChildren();
            }
            nodeSlots.clear();
            forceRebuild = false;
        }

        // ── Diff: remove stale slots ────────────────────────────────────────────
        List<T> stale = new ArrayList<>();
        for (T existing : nodeSlots.keySet()) {
            if (!next.contains(existing)) {
                stale.add(existing);
            }
        }
        for (T s : stale) {
            NodeSlot slot = nodeSlots.remove(s);
            getElement().removeChild(slot.element());
            slot.element().removeAllChildren();
        }

        // ── Diff: add new slots ─────────────────────────────────────────────────
        for (T item : items) {
            if (nodeSlots.containsKey(item)) {
                continue;
            }
            String id = UUID.randomUUID().toString();
            Component rendered = render.apply(item);
            Element slotEl = new Element("div");
            slotEl.setAttribute("slot", "node-" + id);
            slotEl.setAttribute("data-node-id", id);
            slotEl.appendChild(rendered.getElement());
            getElement().appendChild(slotEl);
            nodeSlots.put(item, new NodeSlot(id, slotEl, rendered));
        }

        // ── Keep reverse map in sync ────────────────────────────────────────────
        idToItem.clear();
        nodeSlots.forEach((item, slot) -> idToItem.put(slot.id(), item));

        // ── Describe every item exactly once ────────────────────────────────────
        Map<T, WorkspaceDescriptor<T>> descriptors = new LinkedHashMap<>();
        for (T item : items) {
            descriptors.put(item, describe.apply(item));
        }

        // ── Node states ─────────────────────────────────────────────────────────
        List<NodeState> nodeStates = new ArrayList<>();
        for (T item : items) {
            NodeSlot slot = nodeSlots.get(item);
            if (slot == null) {
                continue;
            }
            WorkspaceDescriptor<T> d = descriptors.get(item);
            nodeStates.add(new NodeState(slot.id(), d.cx(), d.cy(), d.width(), d.height()));
        }

        // ── Edge states (resolve targets → ids; suffix duplicates) ──────────────
        idToEdge.clear();
        Map<String, Integer> pairCount = new HashMap<>();
        List<EdgeState> edgeStates = new ArrayList<>();
        for (T item : items) {
            NodeSlot srcSlot = nodeSlots.get(item);
            if (srcSlot == null) {
                continue;
            }
            String srcId = srcSlot.id();
            for (EdgeDescriptor<T> edge : descriptors.get(item).edges()) {
                NodeSlot dstSlot = nodeSlots.get(edge.target());
                if (dstSlot == null) {
                    continue;
                }
                String dstId = dstSlot.id();
                String key = srcId + "->" + dstId;
                int idx = pairCount.merge(key, 0, (old, ignored) -> old + 1);
                String id = idx == 0 ? srcId + "-" + dstId : srcId + "-" + dstId + "-" + idx;
                idToEdge.put(id, new EdgeRef<>(item, edge));
                edgeStates.add(new EdgeState(
                        id, srcId, dstId,
                        edge.label(), edge.labelOffset(), edge.color(), edge.strokeWidth(),
                        edge.routing(), edge.startArrow(), edge.endArrow(), edge.style()));
            }
        }

        // ── Serialize and push ──────────────────────────────────────────────────
        // Viewport and fit are one-shot: after sending, the client preserves its own pan/zoom.
        WorkspaceState state = new WorkspaceState(
                nodeStates, edgeStates, grid, pendingViewport, fitRequested, edgeSpacing);
        pendingViewport = null;
        fitRequested = false;
        try {
            getElement().setProperty("workspaceState", MAPPER.writeValueAsString(state));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workspace state", e);
        }
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        scheduleRefresh();
    }

    /** Per-node bookkeeping: internal id, light-DOM slot element, and the rendered component. */
    private record NodeSlot(String id, Element element, Component rendered) {
    }

    /** Per-edge bookkeeping: the source domain object and its descriptor (which holds the target). */
    private record EdgeRef<E>(E source, EdgeDescriptor<E> edge) {
    }
}
