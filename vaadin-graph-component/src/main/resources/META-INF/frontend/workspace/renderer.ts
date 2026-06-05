import {ArrowType, EdgeState, WorkspaceState} from './types'
import {
    longestSegmentMidpoint,
    Point2D,
    rayBoxExit,
    rectBoundaryIntersect,
    segmentMidpoint,
    selfLoopLabelPoint,
    selfLoopSvgPath,
} from './geometry'

/** Amplitude of a lone CURVED edge's bow, as a fraction of the distance between its two nodes. */
const CURVE_BOW_RATIO = 0.3
/** Corner-rounding radius for CURVED / ROUNDED edges, as a fraction of that node distance. */
const CORNER_RADIUS_RATIO = 0.5

export function buildEdgeSvg(state: WorkspaceState): string {
    const nodeMap = new Map(state.nodes.map(n => [n.id, n]))
    const lines: string[] = []
    const edgeSpacing = state.edgeSpacing   // perpendicular gap between multi-edges

    // Group edges by canonical (unordered) pair key to detect multi-edges.
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

    for (const [key, group] of pairGroups) {
        const midIndex = (group.length - 1) / 2

        // Canonical perpendicular for the whole group, derived from the pair's node centers in a
        // fixed (key) order — NOT from each edge's own direction. This keeps anti-parallel edges
        // (A→B and B→A) on opposite sides instead of stacking them.
        const [aId, bId] = key.split(':')
        const na = nodeMap.get(aId)
        const nb = nodeMap.get(bId)
        let perp = {x: 0, y: 0}
        if (na && nb && aId !== bId) {
            const ddx = nb.cx - na.cx
            const ddy = nb.cy - na.cy
            const len = Math.hypot(ddx, ddy) || 1
            perp = {x: -ddy / len, y: ddx / len}
        }

        group.forEach((edge, i) => {
            const src = nodeMap.get(edge.sourceId)
            const dst = nodeMap.get(edge.targetId)
            if (!src || !dst) return

            // ── Self-loop ────────────────────────────────────────────────────────
            if (edge.sourceId === edge.targetId) {
                lines.push(edgePaths(edge, selfLoopSvgPath(src)))
                if (edge.label) {
                    lines.push(buildLabel(edge, selfLoopLabelPoint(src)))
                }
                return
            }

            const srcRect = {cx: src.cx, cy: src.cy, width: src.width, height: src.height}
            const dstRect = {cx: dst.cx, cy: dst.cy, width: dst.width, height: dst.height}
            const sc = {x: src.cx, y: src.cy}
            const dc = {x: dst.cx, y: dst.cy}
            const dlen = Math.hypot(dc.x - sc.x, dc.y - sc.y) || 1
            const dir = {x: (dc.x - sc.x) / dlen, y: (dc.y - sc.y) / dlen}

            // Boundary attachment. A lone edge clips the centre-to-centre line at each box. A
            // multi-edge runs in its own lane: shift both centres perpendicular by `offset` and clip
            // the *offset* line — so each edge meets the boundary at its own point, not the centre.
            const offset = (i - midIndex) * edgeSpacing
            const p1 = offset === 0
                ? rectBoundaryIntersect(srcRect, dc)
                : rayBoxExit(srcRect, {x: sc.x + perp.x * offset, y: sc.y + perp.y * offset}, dir)
            const p2 = offset === 0
                ? rectBoundaryIntersect(dstRect, sc)
                : rayBoxExit(dstRect, {x: dc.x + perp.x * offset, y: dc.y + perp.y * offset}, {x: -dir.x, y: -dir.y})
            const gap = Math.hypot(p2.x - p1.x, p2.y - p1.y)

            // ── Curved: a 2-segment line with one rounded bend at the centre ────────
            // Bow away from the centre line (lanes fan symmetrically); a lone edge bows to +perp.
            if (edge.routing === 'CURVED') {
                const bowDir = offset === 0 ? 1 : Math.sign(offset)
                const amp = bowDir * gap * CURVE_BOW_RATIO
                const mid = segmentMidpoint(p1, p2)
                const bend = {x: mid.x + perp.x * amp, y: mid.y + perp.y * amp}
                lines.push(edgePaths(edge, roundedPolylinePath([p1, bend, p2], gap * CORNER_RADIUS_RATIO)))
                if (edge.label) {
                    lines.push(buildLabel(edge, bend))
                }
                return
            }

            // ── Straight / orthogonal / rounded ─────────────────────────────────────
            const points = edge.routing === 'STRAIGHT'
                ? [p1, p2]
                : elbow(p1, p2, Math.abs(dc.x - sc.x) >= Math.abs(dc.y - sc.y))

            if (edge.routing === 'ROUNDED') {
                lines.push(edgePaths(edge, roundedPolylinePath(points, gap * CORNER_RADIUS_RATIO)))
            } else {
                lines.push(buildPath(edge, points))
            }

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

function buildPath(edge: EdgeState, points: Point2D[]): string {
    const d = 'M ' + points.map(p => `${p.x} ${p.y}`).join(' L ')
    return edgePaths(edge, d)
}

/** Three-segment (4-point) elbow between two boundary points; the elbow sits on the dominant axis. */
function elbow(p1: Point2D, p2: Point2D, horizontalDominant: boolean): Point2D[] {
    if (horizontalDominant) {
        const mx = (p1.x + p2.x) / 2
        return [p1, {x: mx, y: p1.y}, {x: mx, y: p2.y}, p2]
    }
    const my = (p1.y + p2.y) / 2
    return [p1, {x: p1.x, y: my}, {x: p2.x, y: my}, p2]
}

/**
 * SVG path for a polyline with each interior corner replaced by a circular-ish fillet of the given
 * radius (a single quadratic per corner, straight elsewhere). The radius is clamped to half of each
 * adjoining segment so it always fits; collinear corners stay straight.
 */
function roundedPolylinePath(points: Point2D[], radius: number): string {
    if (points.length < 3) {
        return 'M ' + points.map(p => `${p.x} ${p.y}`).join(' L ')
    }
    let d = `M ${points[0].x} ${points[0].y}`
    for (let i = 1; i < points.length - 1; i++) {
        const prev = points[i - 1]
        const cur = points[i]
        const next = points[i + 1]
        const v1x = cur.x - prev.x, v1y = cur.y - prev.y
        const v2x = next.x - cur.x, v2y = next.y - cur.y
        const l1 = Math.hypot(v1x, v1y) || 1
        const l2 = Math.hypot(v2x, v2y) || 1
        const r = Math.min(radius, l1 / 2, l2 / 2)
        const t1 = {x: cur.x - (v1x / l1) * r, y: cur.y - (v1y / l1) * r}   // approach point
        const t2 = {x: cur.x + (v2x / l2) * r, y: cur.y + (v2y / l2) * r}   // departure point
        d += ` L ${t1.x} ${t1.y} Q ${cur.x} ${cur.y} ${t2.x} ${t2.y}`
    }
    const last = points[points.length - 1]
    d += ` L ${last.x} ${last.y}`
    return d
}

/**
 * Renders one edge as: its arrow markers, a wide transparent "hit" path for comfortable clicking,
 * and the visible stroked path. Both paths carry data-edge-id so a delegated click handler can
 * resolve the edge whichever one the pointer lands on.
 */
function edgePaths(edge: EdgeState, d: string): string {
    const markerId = `arrow-${edge.id}`
    const startM = edge.startArrow !== 'NONE' ? `marker-start="url(#${markerId}-start)"` : ''
    const endM = edge.endArrow !== 'NONE' ? `marker-end="url(#${markerId}-end)"` : ''
    const hitWidth = Math.max(edge.strokeWidth, 12)
    return [
        markerDef(markerId + '-start', edge.startArrow, edge.color, true),
        markerDef(markerId + '-end', edge.endArrow, edge.color, false),
        // Wide transparent hit path stays solid for comfortable clicking.
        `<path d="${d}" fill="none" stroke="transparent" stroke-width="${hitWidth}"`,
        ` class="edge-hit" data-edge-id="${edge.id}"/>`,
        `<path d="${d}" fill="none" stroke="${edge.color}"`,
        ` stroke-width="${edge.strokeWidth}" ${startM} ${endM} ${strokeDash(edge)} class="edge-line" data-edge-id="${edge.id}"/>`,
    ].join('')
}

/** stroke-dasharray (and linecap for dots) for the edge's stroke style, scaled by width. */
function strokeDash(edge: EdgeState): string {
    const w = Math.max(1, edge.strokeWidth)
    switch (edge.style) {
        case 'DASHED':
            return `stroke-dasharray="${w * 4} ${w * 3}"`
        case 'DOTTED':
            // Zero-length dashes with round caps render as round dots.
            return `stroke-dasharray="0 ${w * 2.4}" stroke-linecap="round"`
        default:
            return ''
    }
}

function buildLabel(edge: EdgeState, pt: Point2D): string {
    // Measure label width roughly at 11px: ~6.5px per char + 8px padding each side
    const w = Math.max(48, edge.label!.length * 6.5 + 16)
    return [
        `<rect x="${pt.x - w / 2}" y="${pt.y - 9}" width="${w}" height="16" rx="3"`,
        ` fill="var(--lumo-base-color, white)" opacity="0.85"/>`,
        `<text x="${pt.x}" y="${pt.y + 1}" text-anchor="middle" dominant-baseline="central"`,
        ` font-size="11" fill="${edge.color}" font-family="var(--lumo-font-family, sans-serif)">`,
        `${escapeXml(edge.label!)}</text>`,
    ].join('\n')
}

function escapeXml(s: string): string {
    return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
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
