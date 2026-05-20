import type { NodeCompute } from '../render'
import { makeCanvas, num, str } from './_util'

export const shapeCircle: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const cx = num(params.cx, w / 2)
  const cy = num(params.cy, h / 2)
  const r = num(params.radius, Math.min(w, h) * 0.25)
  const color = str(params.color, '#ffffff')
  g.fillStyle = color
  g.beginPath()
  g.arc(cx, cy, r, 0, Math.PI * 2)
  g.fill()
  return { kind: 'canvas', canvas }
}
