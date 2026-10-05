package io.github.adumeige.vaadin.graph.demo;

import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.markdown.Markdown;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import io.github.adumeige.vaadin.graph.component.WorkspaceView;
import io.github.adumeige.vaadin.graph.layout.AStarLayout;
import io.github.adumeige.vaadin.graph.layout.ForceDirectedLayout;
import io.github.adumeige.vaadin.graph.layout.HierarchicalLayout;
import io.github.adumeige.vaadin.graph.layout.LayoutAlgorithm;
import io.github.adumeige.vaadin.graph.layout.OrthogonalLayout;
import io.github.adumeige.vaadin.graph.layout.OverlapRemovalLayout;
import io.github.adumeige.vaadin.graph.model.ArrowType;
import io.github.adumeige.vaadin.graph.model.EdgeDescriptor;
import io.github.adumeige.vaadin.graph.model.EdgeRouting;
import io.github.adumeige.vaadin.graph.model.EdgeStyle;
import io.github.adumeige.vaadin.graph.model.WorkspaceDescriptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * A worked example of {@link WorkspaceView}: a small service-architecture graph with rich entities,
 * a switchable node renderer (card / form / markdown), an inline edge editor, and controls to add
 * nodes and edges at runtime.
 */
@Route("")
@PageTitle("vaadin-graph demo")
public class DemoView extends VerticalLayout {

    /** How each node is rendered. Switching this re-renders every node via {@code setRender}. */
    private enum RenderMode {
        CARD("Card"), FORM("Form"), MARKDOWN("Markdown");
        final String label;

        RenderMode(String label) {
            this.label = label;
        }
    }

    /** Selectable demo datasets. */
    private enum Dataset {
        SAMPLE("Sample (6 nodes)"), LARGE("Large (~150 nodes)");
        final String label;

        Dataset(String label) {
            this.label = label;
        }
    }

    private final List<GraphNode> nodes = new ArrayList<>();
    private final List<GraphEdge> edges = new ArrayList<>();
    private final ListDataProvider<GraphNode> nodeProvider = new ListDataProvider<>(nodes);
    private final WorkspaceView<GraphNode> workspace = new WorkspaceView<>();

    private RenderMode renderMode = RenderMode.CARD;
    private int addedCount = 0;

    // Edge-editor state
    private GraphEdge selectedEdge;
    private final Div edgeEditor = new Div();
    private final Span edgeEditorTitle = new Span("Select an edge to edit");
    private final TextField edgeLabel = new TextField("Label");
    private final TextField edgeColor = new TextField("Color (hex)");
    private final ComboBox<EdgeRouting> edgeRouting = new ComboBox<>("Routing");
    private final ComboBox<ArrowType> edgeStartArrow = new ComboBox<>("Start arrow");
    private final ComboBox<ArrowType> edgeEndArrow = new ComboBox<>("End arrow");
    private final IntegerField edgeStroke = new IntegerField("Stroke width");
    private final ComboBox<EdgeStyle> edgeStyle = new ComboBox<>("Style");
    private Div edgeFields;   // the editor's field container, shown once an edge is selected

    // Add-edge combos (kept in sync with the node list)
    private final ComboBox<GraphNode> newEdgeSource = new ComboBox<>("Source");
    private final ComboBox<GraphNode> newEdgeTarget = new ComboBox<>("Target");

    // Node selection / move-by-grid state
    private GraphNode selectedNode;
    private final Span nodeSelectionTitle = new Span("Click a node to select it");
    private Div nodeMovePad;   // the arrow pad, shown once a node is selected

    public DemoView() {
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        loadSample();

        workspace.setId("workspace");
        workspace.setSizeFull();
        workspace.setDataProvider(nodeProvider);
        workspace.setRender(this::renderNode);
        workspace.setDescribe(this::describe);
        // Lets applyLayout(...) write computed positions back into our model.
        workspace.setPositionWriter((node, p) -> {
            node.setX(p.x());
            node.setY(p.y());
        });
        workspace.addNodeClickListener(e -> selectNode(e.getItem()));
        workspace.addEdgeClickListener(e -> {
            if (e.getEdge().payload() instanceof GraphEdge edge) {
                selectEdge(edge);
            }
        });

        HorizontalLayout body = new HorizontalLayout(workspace, buildSidePanel());
        body.setSizeFull();
        body.setSpacing(false);
        body.setFlexGrow(1, workspace);

        add(buildHeader(), body);
        setFlexGrow(1, body);
    }

    // ── Layout ───────────────────────────────────────────────────────────────

    private Component buildHeader() {
        H3 title = new H3("vaadin-graph — WorkspaceView demo");
        title.getStyle().set("margin", "0");

        Button fit = new Button("Fit", e -> workspace.fitContent());
        Button zoom = new Button("Zoom in", e -> workspace.setViewport(60, 60, 1.4));

        HorizontalLayout header = new HorizontalLayout(title, fit, zoom);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.setPadding(true);
        header.setSpacing(true);
        header.getStyle().set("border-bottom", "1px solid var(--lumo-contrast-10pct)");
        header.expand(title);
        return header;
    }

    private Component buildSidePanel() {
        // ── Dataset ──────────────────────────────────────────────────────────
        RadioButtonGroup<Dataset> datasetSwitch = new RadioButtonGroup<>();
        datasetSwitch.setId("dataset-switch");
        datasetSwitch.setItems(Dataset.values());
        datasetSwitch.setValue(Dataset.SAMPLE);
        datasetSwitch.setItemLabelGenerator(d -> d.label);
        datasetSwitch.addValueChangeListener(e -> loadDataset(e.getValue()));

        // ── Display ──────────────────────────────────────────────────────────
        RadioButtonGroup<RenderMode> renderGroup = new RadioButtonGroup<>("Render nodes as");
        renderGroup.setItems(RenderMode.values());
        renderGroup.setValue(renderMode);
        renderGroup.setItemLabelGenerator(m -> m.label);
        renderGroup.addValueChangeListener(e -> {
            renderMode = e.getValue();
            workspace.setRender(this::renderNode);   // forces a re-render of every node
        });
        IntegerField edgeSpacing = new IntegerField("Edge spacing (px)");
        edgeSpacing.setId("edge-spacing");
        edgeSpacing.setValue((int) workspace.getEdgeSpacing());
        edgeSpacing.setMin(0);
        edgeSpacing.setMax(80);
        edgeSpacing.setStepButtonsVisible(true);
        edgeSpacing.setHelperText("Gap between edges sharing two nodes");
        edgeSpacing.addValueChangeListener(e -> workspace.setEdgeSpacing(e.getValue() == null ? 0 : e.getValue()));

        // ── Add node ─────────────────────────────────────────────────────────
        TextField nodeName = new TextField("Name");
        nodeName.setId("node-name");
        ComboBox<NodeType> nodeType = new ComboBox<>("Type");
        nodeType.setId("node-type");
        nodeType.setItems(NodeType.values());
        nodeType.setItemLabelGenerator(NodeType::label);
        nodeType.setValue(NodeType.SERVICE);
        ComboBox<NodeStatus> nodeStatus = new ComboBox<>("Status");
        nodeStatus.setItems(NodeStatus.values());
        nodeStatus.setItemLabelGenerator(NodeStatus::label);
        nodeStatus.setValue(NodeStatus.HEALTHY);
        Button addNode = new Button("Add node", e -> {
            String name = nodeName.getValue().isBlank()
                    ? nodeType.getValue().label() + " " + (addedCount + 1)
                    : nodeName.getValue();
            // New nodes pile up near the centre (deliberately overlapping) so "Retopology"
            // has something to untangle.
            GraphNode n = new GraphNode(UUID.randomUUID().toString(), name,
                    nodeType.getValue(), nodeStatus.getValue(), "you",
                    "New **" + name + "** node.\n\nEdit me in code.",
                    440 + addedCount * 14, 620 + addedCount * 14);
            addedCount++;
            nodes.add(n);
            nodeProvider.refreshAll();
            workspace.fitContent();
            refreshNodeCombos();
            nodeName.clear();
            Notification.show("Added node: " + name);
        });
        addNode.setId("add-node-btn");
        addNode.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        // ── Add edge ─────────────────────────────────────────────────────────
        newEdgeSource.setId("new-edge-source");
        newEdgeTarget.setId("new-edge-target");
        newEdgeSource.setItemLabelGenerator(GraphNode::getName);
        newEdgeTarget.setItemLabelGenerator(GraphNode::getName);
        ComboBox<EdgeRouting> newEdgeRouting = new ComboBox<>("Routing");
        newEdgeRouting.setItems(EdgeRouting.values());
        newEdgeRouting.setValue(EdgeRouting.STRAIGHT);
        TextField newEdgeLabel = new TextField("Label");
        refreshNodeCombos();
        Button addEdge = new Button("Add edge", e -> {
            GraphNode s = newEdgeSource.getValue();
            GraphNode t = newEdgeTarget.getValue();
            if (s == null || t == null) {
                Notification.show("Pick a source and target");
                return;
            }
            edges.add(new GraphEdge(s, t, newEdgeLabel.getValue(), "#8E8E8E",
                    newEdgeRouting.getValue(), ArrowType.NONE, ArrowType.ARROW, 2));
            workspace.refresh();
            newEdgeLabel.clear();
            Notification.show("Added edge: " + s.getName() + " → " + t.getName());
        });
        addEdge.setId("add-edge-btn");
        addEdge.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        // ── Layout ───────────────────────────────────────────────────────────
        ComboBox<String> layoutAlgo = new ComboBox<>("Algorithm");
        layoutAlgo.setId("layout-algo");
        layoutAlgo.setItems("Force-directed", "Hierarchical", "Orthogonal", "A*", "Overlap removal");
        layoutAlgo.setValue("Force-directed");
        IntegerField minDistance = new IntegerField("Min distance (px)");
        minDistance.setId("min-distance");
        minDistance.setValue(24);
        minDistance.setMin(0);
        minDistance.setMax(300);
        minDistance.setStepButtonsVisible(true);
        Button runLayout = new Button("Run layout", e -> {
            double md = minDistance.getValue() == null ? 0 : minDistance.getValue();
            LayoutAlgorithm algo = switch (layoutAlgo.getValue()) {
                case "Hierarchical" -> new HierarchicalLayout(md);
                case "Orthogonal" -> new OrthogonalLayout(md);
                case "A*" -> new AStarLayout(md);
                case "Overlap removal" -> new OverlapRemovalLayout(md);
                default -> new ForceDirectedLayout(md);
            };
            workspace.applyLayout(algo);
            workspace.fitContent();
            Notification.show("Applied: " + layoutAlgo.getValue() + " (min " + (int) md + " px)");
        });
        runLayout.setId("run-layout-btn");
        runLayout.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        VerticalLayout panel = new VerticalLayout(
                section("Dataset", datasetSwitch),
                section("Display", renderGroup, edgeSpacing),
                section("Layout", layoutAlgo, minDistance, runLayout),
                section("Add node", nodeName, nodeType, nodeStatus, addNode),
                section("Add edge", newEdgeSource, newEdgeTarget, newEdgeRouting, newEdgeLabel, addEdge),
                buildSelectedNodeSection(),
                buildEdgeEditor());
        panel.setWidth("330px");
        panel.setHeightFull();
        panel.setPadding(true);
        panel.setSpacing(true);
        panel.getStyle()
                .set("border-left", "1px solid var(--lumo-contrast-10pct)")
                .set("overflow", "auto")
                .set("flex", "0 0 auto");
        return panel;
    }

    // ── Move-by-grid ─────────────────────────────────────────────────────────────

    private Component buildSelectedNodeSection() {
        Button up = arrowButton("↑", "move-up", () -> moveSelected(0, -1));
        Button down = arrowButton("↓", "move-down", () -> moveSelected(0, 1));
        Button left = arrowButton("←", "move-left", () -> moveSelected(-1, 0));
        Button right = arrowButton("→", "move-right", () -> moveSelected(1, 0));

        Div pad = new Div(spacer(), up, spacer(), left, spacer(), right, spacer(), down, spacer());
        pad.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "repeat(3, 40px)")
                .set("gap", "4px")
                .set("justify-content", "start");
        pad.setVisible(false);
        nodeMovePad = pad;

        nodeSelectionTitle.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Span hint = new Span("Each move snaps to the grid (" + workspace.getGrid().getSize() + " px).");
        hint.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        return section("Move node", nodeSelectionTitle, pad, hint);
    }

    private Button arrowButton(String glyph, String id, Runnable action) {
        Button b = new Button(glyph, e -> action.run());
        b.setId(id);
        b.getStyle().set("min-width", "40px").set("width", "40px").set("padding", "0");
        return b;
    }

    private Div spacer() {
        Div d = new Div();
        d.getStyle().set("width", "40px").set("height", "36px");
        return d;
    }

    private void selectNode(GraphNode node) {
        selectedNode = node;
        nodeSelectionTitle.setText("Selected: " + node.getName());
        if (nodeMovePad != null) {
            nodeMovePad.setVisible(true);
        }
    }

    private void moveSelected(int dx, int dy) {
        if (selectedNode == null) {
            return;
        }
        double step = workspace.getGrid().getSize();
        // Snap to the grid, then step one cell in the chosen direction.
        selectedNode.setX(Math.round(selectedNode.getX() / step) * step + dx * step);
        selectedNode.setY(Math.round(selectedNode.getY() / step) * step + dy * step);
        nodeProvider.refreshAll();   // viewport is preserved, so the node moves in place
    }

    private Component buildEdgeEditor() {
        edgeRouting.setItems(EdgeRouting.values());
        edgeStartArrow.setItems(ArrowType.values());
        edgeEndArrow.setItems(ArrowType.values());
        edgeStyle.setItems(EdgeStyle.values());
        edgeStroke.setMin(1);
        edgeStroke.setMax(12);
        edgeStroke.setStepButtonsVisible(true);

        edgeLabel.setId("edit-label");
        edgeColor.setId("edit-color");
        edgeRouting.setId("edit-routing");
        edgeStartArrow.setId("edit-start-arrow");
        edgeEndArrow.setId("edit-end-arrow");
        edgeStroke.setId("edit-stroke");
        edgeStyle.setId("edit-style");

        Button save = new Button("Save", e -> saveSelectedEdge());
        save.setId("edit-save");
        save.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        Button delete = new Button("Delete", e -> deleteSelectedEdge());
        delete.setId("edit-delete");
        delete.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        HorizontalLayout actions = new HorizontalLayout(save, delete);

        Div fields = new Div(edgeLabel, edgeColor, edgeRouting, edgeStyle, edgeStartArrow, edgeEndArrow,
                edgeStroke, actions);
        fields.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-s)");
        fields.setVisible(false);   // shown once an edge is selected

        edgeEditorTitle.getStyle().set("color", "var(--lumo-secondary-text-color)");
        edgeEditor.add(edgeEditorTitle, fields);
        edgeEditor.getStyle().set("display", "flex").set("flex-direction", "column").set("gap", "var(--lumo-space-s)");

        this.edgeFields = fields;   // selectEdge toggles this once an edge is picked
        return section("Edit edge", edgeEditor);
    }

    private Component section(String heading, Component... contents) {
        H4 h = new H4(heading);
        h.getStyle().set("margin", "0 0 var(--lumo-space-xs) 0");
        VerticalLayout box = new VerticalLayout(contents);
        box.setPadding(false);
        box.setSpacing(false);
        box.getStyle().set("gap", "var(--lumo-space-xs)");
        VerticalLayout wrapper = new VerticalLayout(h, box);
        wrapper.setPadding(false);
        wrapper.setSpacing(false);
        wrapper.getStyle()
                .set("padding", "var(--lumo-space-s)")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");
        return wrapper;
    }

    // ── Edge editor logic ──────────────────────────────────────────────────────

    private void selectEdge(GraphEdge edge) {
        selectedEdge = edge;
        edgeEditorTitle.setText(edge.getSource().getName() + " → " + edge.getTarget().getName());
        edgeLabel.setValue(edge.getLabel() == null ? "" : edge.getLabel());
        edgeColor.setValue(edge.getColor());
        edgeRouting.setValue(edge.getRouting());
        edgeStartArrow.setValue(edge.getStartArrow());
        edgeEndArrow.setValue(edge.getEndArrow());
        edgeStroke.setValue(edge.getStrokeWidth());
        edgeStyle.setValue(edge.getStyle());
        edgeFields.setVisible(true);
    }

    private void saveSelectedEdge() {
        if (selectedEdge == null) {
            return;
        }
        selectedEdge.setLabel(edgeLabel.getValue());
        selectedEdge.setColor(edgeColor.getValue());
        selectedEdge.setRouting(edgeRouting.getValue());
        selectedEdge.setStartArrow(edgeStartArrow.getValue());
        selectedEdge.setEndArrow(edgeEndArrow.getValue());
        selectedEdge.setStrokeWidth(edgeStroke.getValue() == null ? 2 : edgeStroke.getValue());
        selectedEdge.setStyle(edgeStyle.getValue() == null ? EdgeStyle.SOLID : edgeStyle.getValue());
        workspace.refresh();
        Notification.show("Edge updated");
    }

    private void deleteSelectedEdge() {
        if (selectedEdge == null) {
            return;
        }
        edges.remove(selectedEdge);
        selectedEdge = null;
        edgeFields.setVisible(false);
        edgeEditorTitle.setText("Select an edge to edit");
        workspace.refresh();
        Notification.show("Edge deleted");
    }

    private void refreshNodeCombos() {
        newEdgeSource.setItems(nodes);
        newEdgeTarget.setItems(nodes);
    }

    // ── WorkspaceView callbacks ─────────────────────────────────────────────────

    private WorkspaceDescriptor<GraphNode> describe(GraphNode node) {
        List<EdgeDescriptor<GraphNode>> outgoing = edges.stream()
                .filter(e -> e.getSource() == node)
                .map(e -> EdgeDescriptor.to(e.getTarget())
                        .label(e.getLabel())
                        .color(e.getColor())
                        .routing(e.getRouting())
                        .startArrow(e.getStartArrow())
                        .endArrow(e.getEndArrow())
                        .strokeWidth(e.getStrokeWidth())
                        .style(e.getStyle())
                        .payload(e)               // recover this exact edge on click
                        .build())
                .toList();
        double height = switch (renderMode) {
            case CARD -> 100;
            case FORM -> 118;
            case MARKDOWN -> 150;
        };
        return new WorkspaceDescriptor<>(node.getX(), node.getY(), 200, height, outgoing);
    }

    private Component renderNode(GraphNode node) {
        return switch (renderMode) {
            case CARD -> renderCard(node);
            case FORM -> renderForm(node);
            case MARKDOWN -> renderMarkdown(node);
        };
    }

    private Component renderCard(GraphNode node) {
        Span name = new Span(node.getName());
        name.getStyle().set("font-weight", "700");
        Span type = new Span(node.getType().label());
        type.getStyle().set("font-size", "var(--lumo-font-size-xs)").set("color", "var(--lumo-secondary-text-color)");
        Div card = new Div(name, type, statusBadge(node));
        return chrome(card, node);
    }

    private Component renderForm(GraphNode node) {
        Div form = new Div(
                fieldRow("Name", node.getName()),
                fieldRow("Type", node.getType().label()),
                fieldRow("Owner", node.getOwner()),
                fieldRow("Status", node.getStatus().label()));
        return chrome(form, node);
    }

    private Component renderMarkdown(GraphNode node) {
        Markdown md = new Markdown("### " + node.getName() + "\n\n" + node.getDescription());
        Div wrap = new Div(md);
        return chrome(wrap, node);
    }

    private Div fieldRow(String key, String value) {
        Span k = new Span(key + ": ");
        k.getStyle().set("color", "var(--lumo-secondary-text-color)");
        Span v = new Span(value);
        v.getStyle().set("font-weight", "600");
        return new Div(k, v);
    }

    private Span statusBadge(GraphNode node) {
        Span dot = new Span("●");
        dot.getStyle().set("color", node.getStatus().color()).set("margin-right", "4px");
        Span badge = new Span(dot, new Span(node.getStatus().label()));
        badge.getStyle().set("font-size", "var(--lumo-font-size-xs)");
        return badge;
    }

    private Div chrome(Div el, GraphNode node) {
        el.getStyle()
                .set("display", "flex").set("flex-direction", "column").set("gap", "2px")
                .set("width", "100%").set("height", "100%").set("box-sizing", "border-box")
                .set("padding", "8px 10px").set("overflow", "hidden")
                .set("font-size", "var(--lumo-font-size-s)")
                .set("background", "var(--lumo-base-color)")
                .set("border", "1px solid var(--lumo-contrast-20pct)")
                .set("border-left", "4px solid " + node.getType().color())
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("box-shadow", "var(--lumo-box-shadow-xs)");
        return el;
    }

    // ── Seed data ──────────────────────────────────────────────────────────────

    private void loadSample() {
        GraphNode api = node("API Gateway", NodeType.GATEWAY, NodeStatus.HEALTHY, "platform",
                "Routes **all** ingress traffic.\n\n- TLS termination\n- Rate limiting", 220, 180);
        GraphNode auth = node("Auth Service", NodeType.SERVICE, NodeStatus.HEALTHY, "identity",
                "Issues **JWT** tokens.\n\nBacked by the `users` store.", 220, 460);
        GraphNode orders = node("Orders Service", NodeType.SERVICE, NodeStatus.DEGRADED, "commerce",
                "Owns the order lifecycle.\n\n> p99 latency elevated.", 600, 180);
        GraphNode db = node("Orders DB", NodeType.DATABASE, NodeStatus.HEALTHY, "commerce",
                "PostgreSQL 16 primary + replica.", 980, 330);   // off-axis so the rounded elbow shows
        GraphNode bus = node("Event Bus", NodeType.QUEUE, NodeStatus.HEALTHY, "platform",
                "Kafka topic `orders.events`.", 600, 460);
        GraphNode cache = node("Session Cache", NodeType.CACHE, NodeStatus.DOWN, "identity",
                "Redis cluster.\n\n**OUTAGE** — failover in progress.", 980, 460);

        nodes.addAll(List.of(api, auth, orders, db, bus, cache));

        edges.add(styled(new GraphEdge(api, auth, "authenticates", "#9C27B0",
                EdgeRouting.STRAIGHT, ArrowType.NONE, ArrowType.ARROW, 2), EdgeStyle.DASHED));
        edges.add(new GraphEdge(api, orders, "routes", "#4C8BF5",
                EdgeRouting.CURVED, ArrowType.NONE, ArrowType.ARROW, 2));     // single curved edge
        edges.add(new GraphEdge(orders, db, "reads / writes", "#34A853",
                EdgeRouting.ROUNDED, ArrowType.NONE, ArrowType.DIAMOND, 2));
        edges.add(new GraphEdge(orders, bus, "publishes", "#FB8C00",
                EdgeRouting.CURVED, ArrowType.NONE, ArrowType.ARROW, 2));
        edges.add(new GraphEdge(bus, orders, "consumes", "#00897B",
                EdgeRouting.CURVED, ArrowType.NONE, ArrowType.ARROW, 2));     // curved multi-edge with the line above
        edges.add(styled(new GraphEdge(auth, cache, "sessions", "#C62828",
                EdgeRouting.STRAIGHT, ArrowType.NONE, ArrowType.CIRCLE, 2), EdgeStyle.DOTTED));
        edges.add(new GraphEdge(orders, orders, "retry", "#607D8B",
                EdgeRouting.STRAIGHT, ArrowType.NONE, ArrowType.ARROW, 2));   // self-loop
    }

    private GraphNode node(String name, NodeType type, NodeStatus status, String owner,
                           String description, double x, double y) {
        return new GraphNode(UUID.randomUUID().toString(), name, type, status, owner, description, x, y);
    }

    private static GraphEdge styled(GraphEdge edge, EdgeStyle style) {
        edge.setStyle(style);
        return edge;
    }

    /** Replaces the live graph with the chosen dataset and resets selection/editor state. */
    private void loadDataset(Dataset dataset) {
        nodes.clear();
        edges.clear();
        selectedNode = null;
        selectedEdge = null;
        addedCount = 0;
        switch (dataset) {
            case SAMPLE -> loadSample();
            case LARGE -> loadLarge();
        }
        if (edgeFields != null) {
            edgeFields.setVisible(false);
        }
        if (nodeMovePad != null) {
            nodeMovePad.setVisible(false);
        }
        edgeEditorTitle.setText("Select an edge to edit");
        nodeSelectionTitle.setText("Click a node to select it");
        nodeProvider.refreshAll();
        refreshNodeCombos();
        workspace.fitContent();
        Notification.show("Loaded " + dataset.label);
    }

    /** A ~150-node procedurally generated graph (deterministic), laid out as a rough grid. */
    private void loadLarge() {
        Random rnd = new Random(20260605L);
        NodeType[] types = NodeType.values();
        NodeStatus[] statusPool = {
                NodeStatus.HEALTHY, NodeStatus.HEALTHY, NodeStatus.HEALTHY, NodeStatus.HEALTHY,
                NodeStatus.DEGRADED, NodeStatus.DOWN};
        String[] owners = {"platform", "commerce", "identity", "data", "growth"};
        EdgeStyle[] styles = {EdgeStyle.SOLID, EdgeStyle.SOLID, EdgeStyle.SOLID, EdgeStyle.DASHED, EdgeStyle.DOTTED};
        EdgeRouting[] routings = {EdgeRouting.STRAIGHT, EdgeRouting.STRAIGHT, EdgeRouting.CURVED, EdgeRouting.ROUNDED};

        int count = 150;
        int cols = 13;
        List<GraphNode> created = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            NodeType type = types[rnd.nextInt(types.length)];
            NodeStatus status = statusPool[rnd.nextInt(statusPool.length)];
            String name = codeFor(type) + "-" + (i + 1);
            String owner = owners[rnd.nextInt(owners.length)];
            String desc = "Auto-generated **" + name + "**.\n\nType: " + type.label();
            created.add(new GraphNode(UUID.randomUUID().toString(), name, type, status, owner, desc,
                    (i % cols) * 240.0, (i / cols) * 150.0));
        }
        nodes.addAll(created);

        // Tree backbone (guarantees a connected graph) plus some cross links.
        for (int i = 1; i < count; i++) {
            edges.add(randomEdge(created.get(i), created.get(rnd.nextInt(i)), rnd, routings, styles));
        }
        for (int k = 0; k < 45; k++) {
            int a = rnd.nextInt(count);
            int b = rnd.nextInt(count);
            if (a != b) {
                edges.add(randomEdge(created.get(a), created.get(b), rnd, routings, styles));
            }
        }
    }

    private GraphEdge randomEdge(GraphNode src, GraphNode dst, Random rnd,
                                 EdgeRouting[] routings, EdgeStyle[] styles) {
        GraphEdge edge = new GraphEdge(src, dst, null, src.getType().color(),
                routings[rnd.nextInt(routings.length)], ArrowType.NONE, ArrowType.ARROW, 2);
        edge.setStyle(styles[rnd.nextInt(styles.length)]);
        return edge;
    }

    private String codeFor(NodeType type) {
        return switch (type) {
            case SERVICE -> "svc";
            case DATABASE -> "db";
            case QUEUE -> "mq";
            case GATEWAY -> "gw";
            case CACHE -> "cache";
        };
    }
}
