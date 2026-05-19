import type { NodeCompute } from '../render'
import { makeCanvas, num, str, ease, type EasingKind } from './_util'

/**
 * One-shot easing ramp on scale (and optionally alpha).
 *
 * Plays the easing curve from `from` → `to` over `duration_frames` starting
 * at `start_frame`. After the ramp, the `after` behavior decides whether the
 * image holds at `to`, fades alpha to 0, or loops the ramp back.
 *
 * Use case: a shockwave ring that expands from 0 to 8× scale over 8 frames
 * with ease_out then disappears (`from=0, to=8, duration=8, easing=ease_out,
 * after=fade`).
 */
export const animScaleRamp: NodeCompute = (inputs, params, ctx) => {
  const [w, h] = ctx.size
  const { canvas, ctx: g } = makeCanvas(w, h)
  const inImg = inputs.image
  if (!inImg || inImg.kind !== 'canvas') return { kind: 'canvas', canvas }

  const from = num(params.from, 0)
  const to = num(params.to, 1)
  const startFrame = Math.max(0, Math.round(num(params.start_frame, 0)))
  const total = Math.max(1, ctx.totalFrames)
  const duration = Math.max(
    1,
    Math.round(num(params.duration_frames, total)),
  )
  const easingKind = (str(params.easing, 'ease_out') as EasingKind)
  const after = str(params.after, 'hold')

  const rel = ctx.frame - startFrame
  if (rel < 0) {
    // not started yet — emit a tiny pre-ramp scale at `from` (typically 0 = invisible).
    return drawScaled(g, inImg.canvas, from, 1, w, h, canvas)
  }

  let t = rel / duration
  let alpha = 1

  if (t >= 1) {
    switch (after) {
      case 'loop': {
        // Modulo back into the ramp.
        t = (rel % duration) / duration
        break
      }
      case 'fade': {
        // Hold at `to` but fade alpha over the remainder of the loop.
        t = 1
        const remainder = total - (startFrame + duration)
        if (remainder > 0) {
          const fadeT = Math.min(1, (rel - duration) / remainder)
          alpha = 1 - fadeT
        } else {
          alpha = 0
        }
        break
      }
      case 'hold':
      default:
        t = 1
    }
  }

  const eased = ease(t, easingKind)
  const scale = from + (to - from) * eased
  return drawScaled(g, inImg.canvas, scale, alpha, w, h, canvas)
}

function drawScaled(
  g: OffscreenCanvasRenderingContext2D,
  src: OffscreenCanvas,
  scale: number,
  alpha: number,
  w: number,
  h: number,
  out: OffscreenCanvas,
) {
  if (scale <= 0 || alpha <= 0) {
    return { kind: 'canvas' as const, canvas: out }
  }
  g.globalAlpha = alpha
  g.translate(w / 2, h / 2)
  g.scale(scale, scale)
  g.translate(-w / 2, -h / 2)
  g.drawImage(src, 0, 0)
  g.setTransform(1, 0, 0, 1, 0, 0)
  g.globalAlpha = 1
  return { kind: 'canvas' as const, canvas: out }
}
