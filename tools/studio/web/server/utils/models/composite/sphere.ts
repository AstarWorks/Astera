/**
 * Voxelized sphere — a 3D cross of cuboids that approximates a sphere. With
 * layers=3 the result is ~7 cuboids ("plus shape" in 3D); layers=5 gives
 * ~31 cuboids and reads visually as round-ish.
 *
 * Each layer is a thin slab perpendicular to one axis. Their union forms a
 * voxelized blob.
 */
import type { CuboidSpec } from '../model-state'

export type SphereOptions = {
  center: [number, number, number]
  /** Outer diameter. */
  diameter: number
  /** Slab count per axis. Must be odd. 3 = blocky, 5 = good, 7 = expensive. */
  layers?: 3 | 5 | 7
}

export function generateSphere(opts: SphereOptions): CuboidSpec[] {
  const layers = (opts.layers ?? 5) as 3 | 5 | 7
  const r = opts.diameter / 2
  const [cx, cy, cz] = opts.center
  const out: CuboidSpec[] = []
  // For each voxel (lx, ly, lz) in [-half..half], include if it's inside the
  // sphere of radius (r). Voxel size = diameter / layers.
  const half = (layers - 1) / 2
  const voxel = opts.diameter / layers
  for (let lx = -half; lx <= half; lx++) {
    for (let ly = -half; ly <= half; ly++) {
      for (let lz = -half; lz <= half; lz++) {
        // Use voxel-center distance to sphere center.
        const distSq = lx * lx + ly * ly + lz * lz
        const maxSq = half * half
        if (distSq > maxSq) continue
        const x = cx + lx * voxel
        const y = cy + ly * voxel
        const z = cz + lz * voxel
        out.push({
          from: [x - voxel / 2, y - voxel / 2, z - voxel / 2],
          to: [x + voxel / 2, y + voxel / 2, z + voxel / 2],
        })
      }
    }
  }
  return out
}
