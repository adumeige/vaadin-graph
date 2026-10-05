package io.github.adumeige.vaadin.graph.model;

import java.io.Serializable;

/**
 * Background grid configuration. Mutable so it reads naturally from both Java
 * ({@code grid.setSize(20)}) and the Karibu DSL ({@code grid { size = 20 }}); the field set is
 * serialized verbatim and mirrors the TypeScript {@code WorkspaceGrid}.
 */
public class WorkspaceGrid implements Serializable {

    private boolean enabled = true;
    private int size = 20;
    private GridStyle style = GridStyle.DOTS;
    private boolean snapEnabled = false;
    private int snapStrength = 8;
    private double minZoomVisible = 0.25;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getSize() {
        return size;
    }

    public void setSize(int size) {
        this.size = size;
    }

    public GridStyle getStyle() {
        return style;
    }

    public void setStyle(GridStyle style) {
        this.style = style;
    }

    public boolean isSnapEnabled() {
        return snapEnabled;
    }

    public void setSnapEnabled(boolean snapEnabled) {
        this.snapEnabled = snapEnabled;
    }

    public int getSnapStrength() {
        return snapStrength;
    }

    public void setSnapStrength(int snapStrength) {
        this.snapStrength = snapStrength;
    }

    public double getMinZoomVisible() {
        return minZoomVisible;
    }

    public void setMinZoomVisible(double minZoomVisible) {
        this.minZoomVisible = minZoomVisible;
    }
}
