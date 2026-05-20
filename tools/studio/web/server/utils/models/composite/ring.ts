/**
 * Voxelized ring / annulus / torus approximation. Used for hilts, handle
 * wraps, decorative bands. Width is the outer-minus-inner radius difference;
 * height is the depth along the ring axis.
 *
 * Implementation: voxelize a 2D annulus in the plane perpendicular to `axis`,
 * extrude by `height` along that axis. Each voxel becomes one cuboid.
 */
import type { CuboidSpec } from '../model-state'

export type RingOptions = {
  center: [number, number, number]
  axis: 'x' | 'y' | 'z'
  /** Outer radius. */
  outer: number
  /** Inner radius (hole). */
  inner: number
  /** Extrude length along axis. */
  height: number
  /** Voxel resolution (voxels per radius). Default 5. */
  resolution?: number
}

export function generateRing(opts: RingOptions): CuboidSpec[] {
  const res = Math.max(3, Math.floor(opts.resolution ?? 5))
  const voxel = opts.outer / res
  const half = res
  const [cx, cy, cz] = opts.center
  const out: CuboidSpec[] = []
  const innerSq = opts.inner * opts.inner
  const outerSq = opts.outer * opts.outer
  for (let a = -half; a <= half; a++) {
    for (let b = -half; b <= half; b++) {
      const distSq = (a * voxel) ** 2 + (b * voxel) ** 2
      if (distSq < innerSq || distSq > outerSq) continue
      const u = a * voxel
      const v = b * voxel
      // Map (u, v) onto the plane perpendicular to axis.
      let from: [number, number, number]
      let to: [number, number, number]
      const halfV = voxel / 2
      const halfH = opts.height / 2
      if (opts.axis === 'y') {
        from = [cx + u - halfV, cy - halfH, cz + v - halfV]
        to = [cx + u + halfV, cy + halfH, cz + v + halfV]
      } else if (opts.axis === 'x') {
        from = [cx - halfH, cy + u - halfV, cz + v - halfV]
        to = [cx + halfH, cy + u + halfV, cz + v + halfV]
      } else {
        from = [cx + u - halfV, cy + v - halfV, cz - halfH]
        to = [cx + u + halfV, cy + v + halfV, cz + halfH]
      }
      out.push({ from, to })
    }
  }
  return out
}
