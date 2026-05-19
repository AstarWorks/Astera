/**
 * View × perspective projection (B9 / ADR-0062 §5).
 *
 * `crop_model_view` resolves a part's 3D world bbox to a 2D pixel AABB
 * inside a previously-rendered screenshot without round-tripping to the
 * Three.js renderer. The math here mirrors three.js's PerspectiveCamera:
 * a y-axis fov, aspect = width/height, view basis built from
 * (origin, target, up) — same convention as `camera.lookAt(target)`.
 *
 * Pixel coords use the canvas convention: x grows right from 0,
 * y grows down from 0 (PNG / DOM standard).
 *
 * Points with z <= 0 in view space (i.e. at or behind the camera plane)
 * return null; AABB projection returns null if *any* corner is behind
 * the camera so callers don't get a garbage half-bbox.
 */

export type Vec3 = [number, number, number]

export type CameraSnapshot = {
  view_id: string
  origin: Vec3
  target: Vec3
  up: Vec3
  /** Vertical fov in degrees (three.js PerspectiveCamera convention). */
  fov_deg: number
  width: number
  height: number
}

function sub(a: Vec3, b: Vec3): Vec3 {
  return [a[0] - b[0], a[1] - b[1], a[2] - b[2]]
}

function cross(a: Vec3, b: Vec3): Vec3 {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ]
}

function dot(a: Vec3, b: Vec3): number {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}

function norm(v: Vec3): Vec3 {
  const l = Math.hypot(v[0], v[1], v[2])
  if (l === 0) return [0, 0, 0]
  return [v[0] / l, v[1] / l, v[2] / l]
}

/**
 * Project a world point to pixel coords. Returns null if the point is at
 * or behind the camera plane (view-space z ≤ 0).
 */
export function projectToPixel(
  world: Vec3,
  cam: CameraSnapshot,
): { x: number; y: number } | null {
  // Right-handed view basis: forward = target - origin, right = forward × up,
  // recomputed up = right × forward (guarantees orthonormal even if input up
  // wasn't perpendicular).
  const f = norm(sub(cam.target, cam.origin))
  const r = norm(cross(f, cam.up))
  const u = cross(r, f)

  const d = sub(world, cam.origin)
  const xv = dot(d, r)
  const yv = dot(d, u)
  const zv = dot(d, f)

  if (zv <= 0) return null

  const halfFovY = (cam.fov_deg * Math.PI) / 180 / 2
  const tanY = Math.tan(halfFovY)
  const aspect = cam.width / cam.height
  const tanX = tanY * aspect

  // NDC in [-1, 1].
  const xn = xv / (zv * tanX)
  const yn = yv / (zv * tanY)

  const px = (xn + 1) * 0.5 * cam.width
  // Flip y so top of image = 0.
  const py = (1 - (yn + 1) * 0.5) * cam.height
  return { x: px, y: py }
}

/**
 * Project a world-space AABB to a pixel-space AABB by projecting all 8
 * corners. Returns null if any corner is behind the camera.
 */
export function projectBboxToPixelAabb(
  min: Vec3,
  max: Vec3,
  cam: CameraSnapshot,
): { x0: number; y0: number; x1: number; y1: number } | null {
  const corners: Vec3[] = [
    [min[0], min[1], min[2]],
    [max[0], min[1], min[2]],
    [min[0], max[1], min[2]],
    [max[0], max[1], min[2]],
    [min[0], min[1], max[2]],
    [max[0], min[1], max[2]],
    [min[0], max[1], max[2]],
    [max[0], max[1], max[2]],
  ]
  let x0 = +Infinity
  let y0 = +Infinity
  let x1 = -Infinity
  let y1 = -Infinity
  for (const c of corners) {
    const p = projectToPixel(c, cam)
    if (!p) return null
    if (p.x < x0) x0 = p.x
    if (p.y < y0) y0 = p.y
    if (p.x > x1) x1 = p.x
    if (p.y > y1) y1 = p.y
  }
  return { x0, y0, x1, y1 }
}
