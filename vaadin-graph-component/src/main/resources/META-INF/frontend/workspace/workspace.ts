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
    private initialized = false      // has the viewport been set at least once?
    private userAdjusted = false     // has the user (or setViewport) pinned the view?

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
        svg .edge-hit { pointer-events: stroke; cursor: pointer; }
        .node-layer { position: absolute; inset: 0; pointer-events: none; }
        .node-slot  { position: absolute; transform: translate(-50%, -50%);
                      cursor: pointer; pointer-events: auto; }
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
        // Handle a property assigned before the element was upgraded.
        this.upgradeProperty('workspaceState')
        this.ro.observe(this)
        this.bindInteractions()
        this.bindEdgeClicks()
        if (this.state) this.applyState()
    }

    disconnectedCallback() {
        this.ro.disconnect()
    }

    private upgradeProperty(prop: string) {
        if (Object.prototype.hasOwnProperty.call(this, prop)) {
            const value = (this as any)[prop]
            delete (this as any)[prop]
            ;(this as any)[prop] = value
        }
    }

    private applyState() {
        if (!this.state) return
        if (!this.isConnected) return
        this.syncNodeSlots()
        if (this.state.viewport) {
            // Explicit transform pinned by the server (setViewport).
            this.viewport.fromState(this.state.viewport)
            this.userAdjusted = true
            this.initialized = true
        } else if (this.state.fit || !this.initialized) {
            // One-shot fit, or the very first render.
            this.viewport.fitContent(this.state.nodes, this.offsetWidth, this.offsetHeight)
            this.userAdjusted = false
            this.initialized = true
        }
        // else: a plain data refresh — keep the user's current pan/zoom.
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
                if (slottedEl && slottedEl.firstElementChild) {
                    slot.appendChild(slottedEl.firstElementChild)
                }

                // ── Click → server ────────────────────────────────────────────────
                const nodeId = node.id
                slot.addEventListener('click', (e) => {
                    e.stopPropagation()   // prevent pan-start on the host element
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

        // Test/observability hooks
        this.dataset.nodeCount = String(this.state.nodes.length)
        this.dataset.edgeCount = String(this.state.edges.length)
        this.setAttribute('rendered', '')
        this.dispatchEvent(new CustomEvent('workspace-rendered', {detail: {nodes: this.state.nodes.length}}))
    }

    private onResize() {
        this.canvas.width = this.offsetWidth
        this.canvas.height = this.offsetHeight
        // Re-fit responsively only while the user hasn't taken control of the view.
        if (this.state && !this.userAdjusted) {
            this.viewport.fitContent(this.state.nodes, this.offsetWidth, this.offsetHeight)
        }
        this.render()
    }

    // ── Interactions ────────────────────────────────────────────────────────

    private isPanning = false
    private lastMouseX = 0
    private lastMouseY = 0

    private bindEdgeClicks() {
        // Delegated: survives svg innerHTML rebuilds. Edge paths carry data-edge-id and a wide
        // transparent .edge-hit sibling makes thin lines comfortable to click.
        this.svgLayer.addEventListener('click', (e) => {
            const pathEl = (e.target as Element).closest('[data-edge-id]')
            if (!pathEl) return
            e.stopPropagation()   // don't start a pan
            const edgeId = pathEl.getAttribute('data-edge-id')!
            ;(this as any).$server?.onEdgeClicked(edgeId)
        })
    }

    private bindInteractions() {
        // Mouse wheel zoom
        this.addEventListener('wheel', e => {
            e.preventDefault()
            const rect = this.getBoundingClientRect()
            this.viewport.zoomAt(-e.deltaY, e.clientX - rect.left, e.clientY - rect.top)
            this.userAdjusted = true
            this.render()
        }, {passive: false})

        // Middle-click or left-click on empty space (not a node slot) to pan
        this.addEventListener('mousedown', e => {
            if (e.button === 1 || (e.button === 0 && (e.target === this || e.target === this.transformLayer))) {
                this.isPanning = true
                this.lastMouseX = e.clientX
                this.lastMouseY = e.clientY
                e.preventDefault()
            }
        })

        window.addEventListener('mousemove', e => {
            if (!this.isPanning) return
            this.viewport.pan(e.clientX - this.lastMouseX, e.clientY - this.lastMouseY)
            this.lastMouseX = e.clientX
            this.lastMouseY = e.clientY
            this.userAdjusted = true
            this.render()
        })

        window.addEventListener('mouseup', () => {
            this.isPanning = false
        })

        // ── Touch: simultaneous pan + pinch ────────────────────────────────────
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
            if (e.touches.length === 0) return
            const mid = touchMid(e.touches)
            lastMidX = mid.x
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
                this.viewport.zoomAt((dist - lastDist) * 0.5, focalX, focalY)
            }

            lastMidX = mid.x
            lastMidY = mid.y
            lastDist = dist
            this.userAdjusted = true
            this.render()
        }, {passive: false})
    }
}

customElements.define('vaadin-workspace-view', VaadinWorkspaceView)
