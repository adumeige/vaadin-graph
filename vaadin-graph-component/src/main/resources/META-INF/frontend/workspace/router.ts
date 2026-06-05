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
