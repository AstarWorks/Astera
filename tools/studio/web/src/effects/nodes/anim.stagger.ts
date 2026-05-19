/**
 * Layer-stagger filter — overlays the input image as N delayed copies, each
 * `delay_frames` further in the past, with progressive alpha decay.
 *
 * Pure-function trick: we don't actually need a persistent buffer. To get
 * "input from N frames ago" without history state, we re-call `renderFrame`
 * on a virtual past frame. But that's circular (this node IS inside
 * `renderFrame`). Instead we approximate by using the current input rendered
 * with a slight visual offset per layer — a poor man's stagger. The TRUE
 * solution requires per-node state (filter.trail handles that pattern).
 *
 * For v1 we use a position-stagger approximation: each layer is the current
 * frame shifted by (i × delay_frames × velocity-estimate). Not as accurate
 * as true time-stagger but produces the trail/cascade feel for radial
 * particle / expanding shapes. v2 will adopt filter.trail's state pattern.
 */
import type { NodeCompute } from '../render'
import { canvasInput } from '../render'
import { makeCanvas, num } from './_util'

export const animStagger: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = canvasInput(inputs, 'image')
  if (!inImg) return { kind: 'canvas', canvas }
  const count = Math.max(1, Math.min(8, Math.round(num(params.count, 3))))
  const decay = Math.max(0, Math.min(1, num(params.decay_alpha, 0.6)))

  // Draw layers from back (faintest, oldest) to front (sharpest, current).
  for (let i = count - 1; i >= 0; i--) {
    g.globalAlpha = Math.pow(decay, i)
    // Position-stagger: scale up slightly per layer index so trails read.
    // (True time-stagger requires state — see filter.trail in Day 3.)
    const s = 1 + i * 0.06
    g.translate(w / 2, h / 2)
    g.scale(s, s)
    g.translate(-w / 2, -h / 2)
    g.drawImage(inImg.canvas, 0, 0)
    g.setTransform(1, 0, 0, 1, 0, 0)
  }
  g.globalAlpha = 1
  return { kind: 'canvas', canvas }
}
