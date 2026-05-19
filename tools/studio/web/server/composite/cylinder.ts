/**
 * Approximate a cylinder by stacking N square cuboids along an axis and
 * tapering them in a circular cross-section. With segments=1 it's just a
 * square prism; with segments=8 + corner_trim it visually reads as round.
 *
 * The cross-section is square (we can't make a circle from axis-aligned
 * cuboids), but we can fake roundness by creating 3 nested cuboids of
 * decreasing dimensions to round off corners — at the cost of more polygons.
 */
import type { CuboidSpec } from '../state'

export type CylinderOptions = {
  /** Center of the base disc. */
  base: [number, number, number]
  /** Total length along axis. */
  length: number
  /** Diameter (= max width and depth of the cross-section). */
  diameter: number
  /** Axis the cylinder extrudes along. */
  axis: 'x' | 'y' | 'z'
  /** Number of length-wise segments (1 = simple prism). */
  segments?: number
  /**
   * Roundness layers. 1 = single square cross-section; 2 = adds a chamfered
   * outer ring; 3 = double-chamfered (most "round" with axis-aligned cuboids).
   * More layers = more polygons + smoother look.
   */
  roundness?: 1 | 2 | 3
}

export function generateCylinder(opts: CylinderOptions): CuboidSpec[] {
  const segments = Math.max(1, Math.floor(opts.segments ?? 1))
  const roundness = (opts.roundness ?? 1) as 1 | 2 | 3
  const segLen = opts.length / segments
  const r = opts.diameter / 2
  // For roundness=2, inset corners by ~15% of radius and add a thinner cross-
  // shape on top to soften the silhouette.
  // For roundness=3, add an octagonal cross-section approximation.
  const out: CuboidSpec[] = []
  for (let i = 0; i < segments; i++) {
    const t0 = i * segLen
    const t1 = (i + 1) * segLen
    const layers: { halfA: number; halfB: number }[] = [{ halfA: r, halfB: r }]
    if (roundness >= 2) {
      const inset = r * 0.3
      // outer "plus" — wider cross slightly inset, plus a thinner cross
      layers.push({ halfA: r - inset, halfB: r + inset * 0.05 })
    }
    if (roundness >= 3) {
      const inset2 = r * 0.45
      layers.push({ halfA: r - inset2 * 0.3, halfB: r - inset2 })
    }
    for (const L of layers) {
      const { halfA, halfB } = L
      if (opts.axis === 'y') {
        out.push({
          from: [opts.base[0] - halfA, opts.base[1] + t0, opts.base[2] - halfB],
          to: [opts.base[0] + halfA, opts.base[1] + t1, opts.base[2] + halfB],
        })
      } else if (opts.axis === 'x') {
        out.push({
          from: [opts.base[0] + t0, opts.base[1] - halfA, opts.base[2] - halfB],
          to: [opts.base[0] + t1, opts.base[1] + halfA, opts.base[2] + halfB],
        })
      } else {
        out.push({
          from: [opts.base[0] - halfA, opts.base[1] - halfB, opts.base[2] + t0],
          to: [opts.base[0] + halfA, opts.base[1] + halfB, opts.base[2] + t1],
        })
      }
    }
  }
  return out
}
