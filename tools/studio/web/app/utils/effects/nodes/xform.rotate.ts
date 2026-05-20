import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

export const xformRotate: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const angle = (num(params.angle_deg, 0) * Math.PI) / 180
  const ox = num(params.ox, w / 2)
  const oy = num(params.oy, h / 2)
  g.translate(ox, oy)
  g.rotate(angle)
  g.translate(-ox, -oy)
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
