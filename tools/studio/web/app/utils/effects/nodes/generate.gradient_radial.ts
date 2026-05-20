import type { NodeCompute } from '../render'
import { makeCanvas, num, str } from './_util'

/**
 * Radial gradient field — single Canvas2D radialGradient draw, fills the
 * full canvas. inner_radius → outer_radius is the falloff band; outside
 * outer_radius is color_outer.
 */
export const generateGradientRadial: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const cx = num(params.cx, w / 2)
  const cy = num(params.cy, h / 2)
  const inner = Math.max(0, num(params.inner_radius, 0))
  const outer = Math.max(inner + 0.5, num(params.outer_radius, Math.min(w, h) / 2))
  const colorInner = str(params.color_inner, '#ffffff')
  const colorOuter = str(params.color_outer, '#00000000')
  const grad = g.createRadialGradient(cx, cy, inner, cx, cy, outer)
  grad.addColorStop(0, colorInner)
  grad.addColorStop(1, colorOuter)
  g.fillStyle = grad
  g.fillRect(0, 0, w, h)
  return { kind: 'canvas', canvas }
}
