import type { NodeCompute } from '../render'
import { makeCanvas, num, str } from './_util'

export const shapeLine: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const x0 = num(params.x0, 0)
  const y0 = num(params.y0, h / 2)
  const x1 = num(params.x1, w)
  const y1 = num(params.y1, h / 2)
  const t = num(params.thickness, 1)
  const color = str(params.color, '#ffffff')
  g.strokeStyle = color
  g.lineWidth = t
  g.lineCap = 'round'
  g.beginPath()
  g.moveTo(x0, y0)
  g.lineTo(x1, y1)
  g.stroke()
  return { kind: 'canvas', canvas }
}
