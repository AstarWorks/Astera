import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas } from './_util'

/** Multiply blend: a*b. Darkens; useful for shadow / vignette overlays. */
export const compositeMultiply: NodeCompute = (inputs, _params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const b = canvasInput(inputs, 'b')
  const a = canvasInput(inputs, 'a')
  if (b) g.drawImage(b.canvas, 0, 0)
  if (a) {
    g.globalCompositeOperation = 'multiply'
    g.drawImage(a.canvas, 0, 0)
    g.globalCompositeOperation = 'source-over'
  }
  return { kind: 'canvas', canvas }
}
