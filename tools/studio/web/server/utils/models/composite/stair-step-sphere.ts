/**
 * "Stair-step sphere" — a 5-cuboid cross that suggests a spherical pommel
 * without the chunky stair-step look of a true voxel sphere. Looks better
 * than a single cuboid and ~7× cheaper than a voxelized one (5 vs 33+).
 *
 * Layout when viewed from any axis: a central cuboid with 4 thinner cuboids
 * jutting out at the cardinal directions, forming a "+". The 3D union forms
 * a 6-pointed star.
 */
import type { CuboidSpec } from '../model-state'

export type StairStepSphereOptions = {
  center: [number, number, number]
  diameter: number
}

export function generateStairStepSphere(opts: StairStepSphereOptions): CuboidSpec[] {
  const r = opts.diameter / 2
  const [cx, cy, cz] = opts.center
  // Central cuboid: 60% of diameter on all axes.
  const inner = r * 0.6
  // Arm cuboids: thinner along their cross-axes, full radius along their axis.
  const armT = r * 0.4
  const out: CuboidSpec[] = []
  // Center
  out.push({
    from: [cx - inner, cy - inner, cz - inner],
    to: [cx + inner, cy + inner, cz + inner],
  })
  // +X arm
  out.push({
    from: [cx + inner * 0.8, cy - armT, cz - armT],
    to: [cx + r, cy + armT, cz + armT],
  })
  // -X arm
  out.push({
    from: [cx - r, cy - armT, cz - armT],
    to: [cx - inner * 0.8, cy + armT, cz + armT],
  })
  // +Z arm
  out.push({
    from: [cx - armT, cy - armT, cz + inner * 0.8],
    to: [cx + armT, cy + armT, cz + r],
  })
  // -Z arm
  out.push({
    from: [cx - armT, cy - armT, cz - r],
    to: [cx + armT, cy + armT, cz - inner * 0.8],
  })
  // (Skip Y arms — bottom faces the grip, top is mostly invisible. 5 cuboids
  // gives the silhouette without paying for invisible faces.)
  return out
}
