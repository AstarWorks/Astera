/**
 * Emits an `EffectorConfig` that downstream `particle.emitter` reads via its
 * `effector` input port and applies per-particle each frame.
 *
 * Modes:
 *   - `attract`: force vector points from particle → origin
 *   - `repel`:   force vector points from origin → particle
 *   - `vortex`:  force vector is perpendicular to (particle → origin),
 *                creating a swirl. Sign of strength decides spin direction.
 *   - `wind`:    constant force in the direction of `angle_deg` (no falloff
 *                from origin; origin is unused).
 *
 * Falloff modulates strength by distance:
 *   - `linear`:         1 - clamp(d / radius, 0, 1)
 *   - `inverse_square`: 1 / (1 + (d / radius)^2)
 *   - `none`:           always 1 within radius, 0 outside
 */
import type { NodeCompute, EffectorConfig } from '../render'
import { num, pair, str } from './_util'

export const particleEffector: NodeCompute = (_inputs, params, ctx) => {
  const [w, h] = ctx.size
  const mode = (str(params.mode, 'attract') as EffectorConfig['mode'])
  const origin = pair(params.origin, [w / 2, h / 2])
  const strength = num(params.strength, 1)
  const radius = Math.max(0.5, num(params.radius, 16))
  const falloff = (str(params.falloff, 'linear') as EffectorConfig['falloff'])
  return {
    kind: 'effector',
    config: { mode, origin, strength, radius, falloff },
  }
}
