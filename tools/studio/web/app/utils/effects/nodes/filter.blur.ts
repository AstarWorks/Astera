import type { NodeCompute } from '../render'
import { makeCanvas, num } from './_util'

/**
 * Canvas2D filter='blur(Npx)' wrapped as a node. Output is the blurred
 * input. Soft pixel-art glow can be built by compositing original over
 * a blurred clone; the dedicated filter.glow node does exactly that.
 */
export const filterBlur: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const r = Math.max(0, num(params.radius, 1))
  if (r === 0) {
    g.drawImage(inImg.canvas, 0, 0)
  } else {
    g.filter = `blur(${r}px)`
    g.drawImage(inImg.canvas, 0, 0)
    g.filter = 'none'
  }
  return { kind: 'canvas', canvas }
}
