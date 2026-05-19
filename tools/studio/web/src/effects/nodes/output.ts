import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas } from './_util'

/** Sink: passes its `image` input through unchanged. Blank if no input. */
export const outputNode: NodeCompute = (inputs, _params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = canvasInput(inputs, 'image')
  if (inImg) g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
