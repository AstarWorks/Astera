import type { NodeCompute } from '../render'
import { makeCanvas, num, pair } from './_util'

/**
 * Rotates the incoming image around `origin` at `speed_deg_per_sec`.
 * Animation driver: derives its rotation purely from `ctx.t`.
 *
 * Passthrough if no input — anim.rotate without an `image` is a no-op
 * (returns a blank canvas of the right size).
 */
export const animRotate: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const speed = num(params.speed_deg_per_sec, 90)
  const [ox, oy] = pair(params.origin, [w / 2, h / 2])
  const angle = ((speed * ctx.t) % 360) * (Math.PI / 180)
  g.translate(ox, oy)
  g.rotate(angle)
  g.translate(-ox, -oy)
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
