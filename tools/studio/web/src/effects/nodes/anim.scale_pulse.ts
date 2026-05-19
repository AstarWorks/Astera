import type { NodeCompute } from '../render'
import { makeCanvas, num, str, ease, type EasingKind } from './_util'

/**
 * Periodic scale pulse between [min, max], one round-trip every
 * `period_seconds`. The cycle shape is controlled by `easing`:
 *
 *   sin (default) — smooth back-and-forth (heartbeat / breathing)
 *   ease_in_out  — soft pulse with hold at peak
 *   bounce_out   — bounces back at peak (impact aftershock)
 *   square       — step at midpoint (strobe)
 *
 * All cycle-shape easings map a [0,1] phase to a [0,1] amplitude that gets
 * lerped between `min` and `max`. (For sin/square that's the literal cycle
 * shape; for ease_in_out/bounce_out the cycle is built as
 * `easing(phase < 0.5 ? phase*2 : (1-phase)*2)` to ensure round-trip.)
 */
export const animScalePulse: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }
  const min = num(params.min, 0.8)
  const max = num(params.max, 1.2)
  const period = num(params.period_seconds, 1.0) || 1
  const easingKind = str(params.easing, 'sin') as EasingKind

  // Phase in [0,1] for the current period.
  const phase = (ctx.t / period) % 1

  let amp: number
  if (easingKind === 'sin' || easingKind === 'square') {
    // These two return cycle-shapes directly: ease(phase) ∈ [0,1].
    amp = ease(phase, easingKind)
  } else {
    // For one-shot easings, fold the cycle so the curve plays forward then
    // reverses: phase 0..0.5 → 0..1 via ease, phase 0.5..1 → 1..0 via ease.
    const folded = phase < 0.5 ? phase * 2 : (1 - phase) * 2
    amp = ease(folded, easingKind)
  }

  const s = min + (max - min) * amp
  g.translate(w / 2, h / 2)
  g.scale(s, s)
  g.translate(-w / 2, -h / 2)
  g.drawImage(inImg.canvas, 0, 0)
  return { kind: 'canvas', canvas }
}
