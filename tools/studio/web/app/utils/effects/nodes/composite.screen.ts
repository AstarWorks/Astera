import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas } from './_util'

/** Screen blend: 1 - (1-a)(1-b) — lighter areas pop, darks preserved. */
export const compositeScreen: NodeCompute = (inputs, _params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const b = canvasInput(inputs, 'b')
  const a = canvasInput(inputs, 'a')
  if (b) g.drawImage(b.canvas, 0, 0)
  if (a) {
    g.globalCompositeOperation = 'screen'
    g.drawImage(a.canvas, 0, 0)
    g.globalCompositeOperation = 'source-over'
  }
  return { kind: 'canvas', canvas }
}
