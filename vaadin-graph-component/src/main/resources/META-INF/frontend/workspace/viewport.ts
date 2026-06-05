import {NodeState, ViewportState} from './types'

export class Viewport {
    panX = 0
    panY = 0
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
            this.panX = 0
            this.panY = 0
            this.zoom = 1
            return
        }

        const padding = 40
        const minX = Math.min(...nodes.map(n => n.cx - n.width / 2))
        const minY = Math.min(...nodes.map(n => n.cy - n.height / 2))
        const maxX = Math.max(...nodes.map(n => n.cx + n.width / 2))
        const maxY = Math.max(...nodes.map(n => n.cy + n.height / 2))

        const contentW = Math.max(1, maxX - minX)
        const contentH = Math.max(1, maxY - minY)

        const scaleX = (containerWidth - padding * 2) / contentW
        const scaleY = (containerHeight - padding * 2) / contentH
        this.zoom = Math.max(this.minZoom, Math.min(scaleX, scaleY, 1))   // never zoom in beyond 1:1

        this.panX = (containerWidth - contentW * this.zoom) / 2 - minX * this.zoom
        this.panY = (containerHeight - contentH * this.zoom) / 2 - minY * this.zoom
    }

    fromState(vp: ViewportState): void {
        this.panX = vp.panX
        this.panY = vp.panY
        this.zoom = vp.zoom
    }

    toState(): ViewportState {
        return {panX: this.panX, panY: this.panY, zoom: this.zoom}
    }
}
