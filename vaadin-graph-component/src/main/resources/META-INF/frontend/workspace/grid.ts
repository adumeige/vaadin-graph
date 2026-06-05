import {WorkspaceGrid} from './types'

export function paintGrid(
    canvas: HTMLCanvasElement,
    grid: WorkspaceGrid,
    panX: number, panY: number,
    zoom: number,
): void {
    const ctx = canvas.getContext('2d')!
    ctx.clearRect(0, 0, canvas.width, canvas.height)

    if (!grid.enabled || zoom < grid.minZoomVisible) return

    const step = grid.size * zoom
    if (step <= 0) return
    const offsetX = ((panX % step) + step) % step
    const offsetY = ((panY % step) + step) % step

    const stroke = getComputedStyle(canvas).getPropertyValue('--lumo-contrast-10pct').trim()
        || 'rgba(0,0,0,0.1)'
    ctx.strokeStyle = stroke
    ctx.fillStyle = stroke

    if (grid.style === 'LINES') {
        ctx.lineWidth = 0.5
        ctx.beginPath()
        for (let x = offsetX; x < canvas.width; x += step) {
            ctx.moveTo(x, 0)
            ctx.lineTo(x, canvas.height)
        }
        for (let y = offsetY; y < canvas.height; y += step) {
            ctx.moveTo(0, y)
            ctx.lineTo(canvas.width, y)
        }
        ctx.stroke()
    } else {
        const r = Math.max(1, zoom * 0.8)
        for (let x = offsetX; x < canvas.width; x += step) {
            for (let y = offsetY; y < canvas.height; y += step) {
                ctx.beginPath()
                ctx.arc(x, y, r, 0, Math.PI * 2)
                ctx.fill()
            }
        }
    }
}
