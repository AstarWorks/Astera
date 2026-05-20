/**
 * Generate a tapered blade as a stack of N axis-aligned cuboids, each one
 * slightly narrower than the one below, producing a faux taper from base to
 * tip. Pure function — no state mutation; the caller pushes the result into
 * `addCuboidBatch`.
 *
 * Coordinates are MC 1/16-block units. The blade rises along +Y from `base`.
 */
import type { CuboidSpec } from '../state'

export type TaperedBladeOptions = {
  /** Center of the blade's base (bottom face center). */
  base: [number, number, number]
  /** Total height of the blade. */
  length: number
  /** Width (X axis) at the base. */
  base_width: number
  /** Width (X axis) at the tip. */
  tip_width: number
  /** Number of cuboid segments. More = smoother taper, more polygons. */
  segments: number
  /** Z thickness. Defaults to half of base_width (typical flat blade). */
  thickness?: number
  /** Per-segment scale curve. `linear` (default), `convex`, `concave`. */
  curve?: 'linear' | 'convex' | 'concave'
}

function ease(t: number, curve: TaperedBladeOptions['curve']): number {
  switch (curve) {
    case 'convex':
      return Math.sqrt(t)
    case 'concave':
      return t * t
    case 'linear':
    default:
      return t
  }
}

export function generateTaperedBlade(opts: TaperedBladeOptions): CuboidSpec[] {
  const segments = Math.max(1, Math.floor(opts.segments))
  const thickness = opts.thickness ?? Math.max(opts.base_width / 2, 1)
  const halfT = thickness / 2
  const segHeight = opts.length / segments
  const [bx, by, bz] = opts.base
  const out: CuboidSpec[] = []
  for (let i = 0; i < segments; i++) {
    // Sample width at the segment's center so the discrete stair-step is
    // visually centered around the smooth taper line.
    const tRaw = segments === 1 ? 0.5 : (i + 0.5) / segments
    const t = ease(tRaw, opts.curve ?? 'linear')
    const w = opts.base_width + (opts.tip_width - opts.base_width) * t
    const halfW = w / 2
    const y0 = by + i * segHeight
    const y1 = by + (i + 1) * segHeight
    out.push({
      from: [bx - halfW, y0, bz - halfT],
      to: [bx + halfW, y1, bz + halfT],
    })
  }
  return out
}
