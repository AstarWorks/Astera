import type { NodeCompute } from '../render'
import { num, seededRandom } from './_util'

/**
 * Perlin-style 1D noise driver. Emits `center + amplitude * noise(t * freq)`
 * as a `number` that downstream params can bind to.
 *
 * Uses a simple 1-octave value-noise (seeded random at integer lattice +
 * smoothstep interpolation) — sufficient for "wiggle" feel; we don't need
 * full Perlin gradients for a 1D scalar.
 */
export const animWiggler: NodeCompute = (_inputs, params, ctx) => {
  const frequency = Math.max(0.01, num(params.frequency, 1.0))
  const amplitude = num(params.amplitude, 1.0)
  const center = num(params.center, 0)
  const seed = Math.round(num(params.seed, 1)) || 1
  const u = ctx.t * frequency
  const noise = noise1d(u, seed)
  return { kind: 'number', value: center + amplitude * noise }
}

/** 1D value noise in [-1, 1] with smoothstep interpolation. Deterministic
 *  from (x, seed); cached per-integer-lattice via the seeded RNG. */
function noise1d(x: number, seed: number): number {
  const i = Math.floor(x)
  const f = x - i
  // smoothstep
  const u = f * f * (3 - 2 * f)
  const a = lattice(i, seed)
  const b = lattice(i + 1, seed)
  // a, b ∈ [0,1] → mapped to [-1, 1] after lerp
  return (a + (b - a) * u) * 2 - 1
}

function lattice(i: number, seed: number): number {
  // Cheap deterministic hash for a single integer lattice point.
  // Reusing our existing Mulberry32 PRNG with `seed + i` as the seed gives
  // independent samples per lattice point.
  const rng = seededRandom(seed * 73856093 + i * 19349663)
  return rng()
}
