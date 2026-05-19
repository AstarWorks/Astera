import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas } from './_util'

/** a over b: standard alpha-over compositing. Input handles `a` (top) and `b` (bottom). */
export const compositeOver: NodeCompute = (inputs, _params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const b = canvasInput(inputs, 'b')
  const a = canvasInput(inputs, 'a')
  if (b) g.drawImage(b.canvas, 0, 0)
  if (a) g.drawImage(a.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
