import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

export const xformTranslate: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  g.translate(num(params.dx, 0), num(params.dy, 0))
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
