/**
 * Sword crossguard / hilt-bar. Default "T" cross: a wide bar perpendicular to
 * the blade, optionally with a small central bulge (collar) where it meets
 * the blade. Produces 1-3 cuboids depending on options.
 */
import type { CuboidSpec } from '../model-state'

export type CrossguardOptions = {
  /** Center of the bar (where blade meets handle). */
  center: [number, number, number]
  /** Total bar length along X. */
  width: number
  /** Bar height along Y. */
  height: number
  /** Bar thickness along Z. */
  depth: number
  /**
   * "T" = just the bar. "T+collar" adds a small block centered over the blade
   * for a chunky look. "X" sweeps the bar along Z too for an asymmetric guard.
   */
  shape?: 'T' | 'T+collar' | 'X'
  /** Collar dimension factor (only when shape='T+collar'). Default 0.6. */
  collarScale?: number
}

export function generateCrossguard(opts: CrossguardOptions): CuboidSpec[] {
  const [cx, cy, cz] = opts.center
  const halfW = opts.width / 2
  const halfH = opts.height / 2
  const halfD = opts.depth / 2
  const out: CuboidSpec[] = []
  // Bar along X axis
  out.push({
    from: [cx - halfW, cy - halfH, cz - halfD],
    to: [cx + halfW, cy + halfH, cz + halfD],
  })
  if (opts.shape === 'X') {
    // Second bar along Z axis for an X cross
    out.push({
      from: [cx - halfD, cy - halfH, cz - halfW],
      to: [cx + halfD, cy + halfH, cz + halfW],
    })
  } else if (opts.shape === 'T+collar') {
    const scale = opts.collarScale ?? 0.6
    const collarHalfX = (opts.width * scale) / 4
    const collarHalfY = opts.height * scale
    const collarHalfZ = (opts.depth * scale) / 2 + 0.1
    out.push({
      from: [cx - collarHalfX, cy - collarHalfY / 2, cz - collarHalfZ],
      to: [cx + collarHalfX, cy + collarHalfY * 1.2, cz + collarHalfZ],
    })
  }
  return out
}
