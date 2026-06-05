export interface Point2D {
    x: number
    y: number
}

export interface Rect {
    cx: number
    cy: number
    width: number
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

/**
 * Exit point where a ray from [origin] (typically inside [rect]) heading along unit vector [dir]
 * leaves the box. Unlike {@link rectBoundaryIntersect}, the origin need not be the box centre — used
 * to clip an offset (multi-edge "lane") line at the boundary instead of the centre-to-centre line.
 */
export function rayBoxExit(rect: Rect, origin: Point2D, dir: Point2D): Point2D {
    const hw = rect.width / 2
    const hh = rect.height / 2
    let t = Infinity
    if (dir.x !== 0) {
        const tx = ((dir.x > 0 ? rect.cx + hw : rect.cx - hw) - origin.x) / dir.x
        if (tx > 0 && tx < t) t = tx
    }
    if (dir.y !== 0) {
        const ty = ((dir.y > 0 ? rect.cy + hh : rect.cy - hh) - origin.y) / dir.y
        if (ty > 0 && ty < t) t = ty
    }
    if (!isFinite(t)) return {x: origin.x, y: origin.y}
    return {x: origin.x + dir.x * t, y: origin.y + dir.y * t}
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

/** Midpoint of the longest segment in a polyline */
export function longestSegmentMidpoint(points: Point2D[]): Point2D {
    let best = -1, bestLen = -1
    for (let i = 0; i < points.length - 1; i++) {
        const l = segmentLength(points[i], points[i + 1])
        if (l > bestLen) {
            bestLen = l
            best = i
        }
    }
    return segmentMidpoint(points[best], points[best + 1])
}

/**
 * Returns the SVG cubic bezier `d` attribute for a self-loop arcing above [node].
 * The loop exits the top-right boundary quadrant and re-enters the top-left quadrant.
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

/** The apex point above the node, for self-loop label placement. */
export function selfLoopLabelPoint(node: { cx: number; cy: number; width: number; height: number }): Point2D {
    const r = Math.max(30, node.width * 0.45)
    return {x: node.cx, y: node.cy - node.height / 2 - r * 1.3}
}
