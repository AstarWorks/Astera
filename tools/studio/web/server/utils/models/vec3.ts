/**
 * Shared 3-vector type + guard. Used by cuboids, composite shapes, project
 * (screenshot bbox math), and ASDSL state. Consolidated here to avoid
 * Nitro auto-import collisions on the type name.
 */
export type Vec3 = [number, number, number]

export function isVec3(v: unknown): v is Vec3 {
  return (
    Array.isArray(v) &&
    v.length === 3 &&
    v.every((n) => typeof n === 'number' && Number.isFinite(n))
  )
}
