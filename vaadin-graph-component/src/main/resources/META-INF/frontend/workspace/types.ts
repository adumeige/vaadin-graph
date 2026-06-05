export type EdgeRouting = 'STRAIGHT' | 'ORTHOGONAL' | 'CURVED' | 'ROUNDED'
export type EdgeStyle = 'SOLID' | 'DASHED' | 'DOTTED'
export type ArrowType = 'NONE' | 'ARROW' | 'CIRCLE' | 'DIAMOND'
export type GridStyle = 'LINES' | 'DOTS'

export interface NodeState {
    id: string
    cx: number
    cy: number
    width: number
    height: number
}

export interface EdgeState {
    id: string
    sourceId: string
    targetId: string
    label: string | null
    labelOffset: number
    color: string
    strokeWidth: number
    routing: EdgeRouting
    startArrow: ArrowType
    endArrow: ArrowType
    style: EdgeStyle
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
    panX: number
    panY: number
    zoom: number
}

export interface WorkspaceState {
    nodes: NodeState[]
    edges: EdgeState[]
    grid: WorkspaceGrid
    viewport: ViewportState | null   // non-null = pin this transform
    fit: boolean                     // true = one-shot fit-to-content (when viewport is null)
    edgeSpacing: number              // perpendicular gap between multi-edges, in px
}
