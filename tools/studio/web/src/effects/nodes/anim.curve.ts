import type { NodeCompute } from '../render'
import { str } from './_util'
import type { CurveStop } from '../types'

/**
 * Multi-keyframe numeric curve emitter — emits a `number` that downstream
 * nodes can bind via a same-name scalar input port.
 *
 * Modes:
 *   - `loop` (default): keyframes span t ∈ [0,1], wrapped to ctx.t01 every loop
 *   - `once`: plays at ctx.t01 capped at 1 (holds the final value)
 *   - `pingpong`: ctx.t01 in [0,0.5] → curve 0..1, in [0.5,1] → curve 1..0
 *
 * Interpolation: linear between adjacent keyframes. Keyframes sorted by t
 * defensively on every compute (cheap; usually pre-sorted).
 *
 * Note: the executor merges this number into a downstream param of the same
 * port name. e.g. `anim.curve → filter.bloom.intensity` makes the bloom
 * intensity follow the curve over the loop.
 */
export const animCurve: NodeCompute = (_inputs, params, ctx) => {
  const rawStops = params.keyframes
  const mode = str(params.mode, 'loop')

  let stops: CurveStop[] = []
  if (Array.isArray(rawStops)) {
    for (const s of rawStops) {
      if (
        s &&
        typeof s === 'object' &&
        't' in s &&
        'v' in s &&
        Number.isFinite((s as { t: unknown }).t) &&
        Number.isFinite((s as { v: unknown }).v)
      ) {
        stops.push({
          t: Number((s as { t: number }).t),
          v: Number((s as { v: number }).v),
        })
      }
    }
  }
  if (stops.length === 0) {
    return { kind: 'number', value: 0 }
  }
  stops.sort((a, b) => a.t - b.t)

  let u = ctx.t01
  if (mode === 'pingpong') {
    u = u < 0.5 ? u * 2 : (1 - u) * 2
  } else if (mode === 'once') {
    u = Math.min(1, u)
  }
  // loop: u already in [0,1] (ctx.t01)

  return { kind: 'number', value: evalCurve(stops, u) }
}

function evalCurve(stops: CurveStop[], u: number): number {
  if (u <= stops[0].t) return stops[0].v
  if (u >= stops[stops.length - 1].t) return stops[stops.length - 1].v
  for (let i = 0; i < stops.length - 1; i++) {
    const a = stops[i]
    const b = stops[i + 1]
    if (u >= a.t && u <= b.t) {
      const dt = b.t - a.t
      if (dt < 1e-9) return a.v
      const local = (u - a.t) / dt
      return a.v + (b.v - a.v) * local
    }
  }
  return stops[stops.length - 1].v
}

