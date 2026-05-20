import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

export const xformScale: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const sx = num(params.sx, 1)
  const sy = num(params.sy, 1)
  const ox = num(params.ox, w / 2)
  const oy = num(params.oy, h / 2)
  g.translate(ox, oy)
  g.scale(sx, sy)
  g.translate(-ox, -oy)
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
