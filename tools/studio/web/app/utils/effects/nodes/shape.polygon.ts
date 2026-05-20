import type { NodeCompute } from '../render'
import { makeCanvas, num, str } from './_util'

export const shapePolygon: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const cx = num(params.cx, w / 2)
  const cy = num(params.cy, h / 2)
  const r = num(params.radius, Math.min(w, h) * 0.3)
  const sides = Math.max(3, Math.round(num(params.sides, 5)))
  const rot = (num(params.rotation_deg, 0) * Math.PI) / 180
  const color = str(params.color, '#ffffff')
  g.fillStyle = color
  g.beginPath()
  for (let i = 0; i < sides; i++) {
    const a = rot + (i * Math.PI * 2) / sides - Math.PI / 2
    const x = cx + Math.cos(a) * r
    const y = cy + Math.sin(a) * r
    if (i === 0) g.moveTo(x, y)
    else g.lineTo(x, y)
  }
  g.closePath()
  g.fill()
  return { kind: 'canvas', canvas }
}
