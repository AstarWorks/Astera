/**
 * Merge all cuboid faces of an MC model into a single BufferGeometry with
 * material groups, BlockBench-style. The result renders in one draw call per
 * material (~5 for a typical sword) instead of one per cuboid × material slot
 * (~280 for a sword). Per-element rotation is baked into vertex positions so
 * there are no runtime matrix updates.
 *
 * Output:
 *  - merged: THREE.BufferGeometry with `position`, `normal`, `uv` attributes
 *    and `groups: [{start, count, materialIndex}]` carving the index buffer
 *    into per-material chunks.
 *  - materials: THREE.Material[] in materialIndex order.
 *  - faceMap: per-face metadata in triangle-index order. `faceMap[Math.floor(
 *    triIdx/2)]` gives `{elementIndex, face}` for raycast hits.
 */
import * as THREE from 'three'
import type { Element, Face, McModel, Vec4 } from '#shared/types'
import { resolveFaceTexture, loadTexture } from './texture-resolver'
import type { ResolveContext } from './texture-resolver'

const PALETTE = [
  0xef4444, 0xf59e0b, 0xeab308, 0x84cc16, 0x10b981,
  0x06b6d4, 0x3b82f6, 0x8b5cf6, 0xec4899, 0xf43f5e,
]

type McFace = 'east' | 'west' | 'up' | 'down' | 'south' | 'north'
const FACES: McFace[] = ['east', 'west', 'up', 'down', 'south', 'north']

export type FaceInfo = {
  elementIndex: number
  face: McFace
  materialIndex: number
}

export type MergeResult = {
  geometry: THREE.BufferGeometry
  materials: THREE.Material[]
  faceMap: FaceInfo[]
}

// Cache materials between renders so re-merging doesn't recompile shaders.
const materialCacheByUrl = new Map<string, THREE.Material>()
const materialCacheByFallback = new Map<number, THREE.Material>()

function getFallbackMaterial(color: number): THREE.Material {
  let m = materialCacheByFallback.get(color)
  if (m) return m
  // MeshBasicMaterial: no lighting math. MC textures have no baked AO/shading
  // worth lighting up; with Lambert + ambient + directional we were paying
  // for per-fragment normal·light dot products that produced near-flat output.
  m = new THREE.MeshBasicMaterial({ color })
  materialCacheByFallback.set(color, m)
  return m
}

function getTexturedMaterial(url: string, fallback: number): THREE.Material {
  let m = materialCacheByUrl.get(url)
  if (m) return m
  const mat: THREE.MeshBasicMaterial = new THREE.MeshBasicMaterial({
    map: loadTexture(url, () => {
      console.warn(`texture failed to load: ${url}`)
      mat.map = null
      mat.color = new THREE.Color(fallback)
      mat.transparent = false
      mat.needsUpdate = true
    }),
    transparent: true,
    alphaTest: 0.5,
  })
  materialCacheByUrl.set(url, mat)
  return mat
}

export function clearMergeMaterialCache() {
  for (const m of materialCacheByUrl.values()) m.dispose()
  for (const m of materialCacheByFallback.values()) m.dispose()
  materialCacheByUrl.clear()
  materialCacheByFallback.clear()
}

/** Compute the 4 corners of a face in element-local coords (relative to (0,0,0)),
 *  given the cuboid's from/to extents. CCW winding order viewed from outside.
 *  Vertex order: top-left, top-right, bottom-right, bottom-left. */
function faceCorners(
  from: [number, number, number],
  to: [number, number, number],
  face: McFace,
): [Vec3, Vec3, Vec3, Vec3] {
  const [x1, y1, z1] = from
  const [x2, y2, z2] = to
  switch (face) {
    case 'east': // +X face, viewed from +X
      return [
        [x2, y2, z2],
        [x2, y2, z1],
        [x2, y1, z1],
        [x2, y1, z2],
      ]
    case 'west': // -X
      return [
        [x1, y2, z1],
        [x1, y2, z2],
        [x1, y1, z2],
        [x1, y1, z1],
      ]
    case 'up': // +Y
      return [
        [x1, y2, z1],
        [x2, y2, z1],
        [x2, y2, z2],
        [x1, y2, z2],
      ]
    case 'down': // -Y
      return [
        [x1, y1, z2],
        [x2, y1, z2],
        [x2, y1, z1],
        [x1, y1, z1],
      ]
    case 'south': // +Z
      return [
        [x1, y2, z2],
        [x2, y2, z2],
        [x2, y1, z2],
        [x1, y1, z2],
      ]
    case 'north': // -Z
      return [
        [x2, y2, z1],
        [x1, y2, z1],
        [x1, y1, z1],
        [x2, y1, z1],
      ]
  }
}

type Vec3 = [number, number, number]

const FACE_NORMAL: Record<McFace, Vec3> = {
  east: [1, 0, 0],
  west: [-1, 0, 0],
  up: [0, 1, 0],
  down: [0, -1, 0],
  south: [0, 0, 1],
  north: [0, 0, -1],
}

function rotatePoint(p: Vec3, origin: Vec3, axis: 'x' | 'y' | 'z', angleDeg: number): Vec3 {
  if (angleDeg === 0) return p
  const a = (angleDeg * Math.PI) / 180
  const cos = Math.cos(a)
  const sin = Math.sin(a)
  const dx = p[0] - origin[0]
  const dy = p[1] - origin[1]
  const dz = p[2] - origin[2]
  let rx: number, ry: number, rz: number
  switch (axis) {
    case 'x':
      rx = dx
      ry = dy * cos - dz * sin
      rz = dy * sin + dz * cos
      break
    case 'y':
      rx = dx * cos + dz * sin
      ry = dy
      rz = -dx * sin + dz * cos
      break
    case 'z':
      rx = dx * cos - dy * sin
      ry = dx * sin + dy * cos
      rz = dz
      break
  }
  return [rx + origin[0], ry + origin[1], rz + origin[2]]
}

function rotateNormal(n: Vec3, axis: 'x' | 'y' | 'z', angleDeg: number): Vec3 {
  if (angleDeg === 0) return n
  return rotatePoint(n, [0, 0, 0], axis, angleDeg)
}

/** Convert MC UV (top-left origin, 0..16) to Three.js UV (bottom-left origin,
 *  0..1). Mirrors the same flip we used in the previous per-Mesh path. */
function mcUVToThree(uv: Vec4 | undefined, defaultUV: Vec4): [number, number, number, number] {
  const u = uv ?? defaultUV
  return [u[0] / 16, 1 - u[1] / 16, u[2] / 16, 1 - u[3] / 16]
}

export function mergeMcModelGeometry(
  model: McModel,
  ctx: ResolveContext = { vanillaVersion: null },
): MergeResult {
  const positions: number[] = []
  const normals: number[] = []
  const uvs: number[] = []
  const indices: number[] = []
  const faceMap: FaceInfo[] = []
  const materials: THREE.Material[] = []
  const slugToMatIdx = new Map<string, number>()
  // Track ranges per material so we can emit groups in materialIndex order
  // (Three.js draws groups in array order regardless of materialIndex, but we
  // want predictable ordering).
  const groupsByMatIdx = new Map<number, { start: number; count: number }[]>()

  function resolveMaterialIndex(face: Face | undefined, fallbackColor: number): number {
    const url = resolveFaceTexture(face?.texture, model, ctx)
    const key = url ?? `__color:${fallbackColor}`
    let idx = slugToMatIdx.get(key)
    if (idx !== undefined) return idx
    idx = materials.length
    materials.push(url ? getTexturedMaterial(url, fallbackColor) : getFallbackMaterial(fallbackColor))
    slugToMatIdx.set(key, idx)
    return idx
  }

  function appendFace(
    el: Element,
    elementIndex: number,
    faceName: McFace,
    faceData: Face | undefined,
    fallbackColor: number,
  ) {
    const corners = faceCorners(el.from as Vec3, el.to as Vec3, faceName)
    const rotated: Vec3[] = el.rotation
      ? corners.map((c) =>
          rotatePoint(c, el.rotation!.origin as Vec3, el.rotation!.axis, el.rotation!.angle),
        )
      : corners
    const normal: Vec3 = el.rotation
      ? rotateNormal(FACE_NORMAL[faceName], el.rotation.axis, el.rotation.angle)
      : FACE_NORMAL[faceName]

    const baseIdx = positions.length / 3
    for (const v of rotated) {
      positions.push(v[0], v[1], v[2])
      normals.push(normal[0], normal[1], normal[2])
    }
    // UVs in 0..1 space; corner order is [top-left, top-right, bottom-right, bottom-left]
    const [u1, v1, u2, v2] = mcUVToThree(faceData?.uv as Vec4 | undefined, [0, 0, 16, 16])
    // top-left (u1, v1), top-right (u2, v1), bottom-right (u2, v2), bottom-left (u1, v2)
    uvs.push(u1, v1, u2, v2, u2, v2, u1, v2)

    // Two triangles per face: (0,1,2) and (0,2,3)
    const triStart = indices.length
    indices.push(baseIdx, baseIdx + 1, baseIdx + 2)
    indices.push(baseIdx, baseIdx + 2, baseIdx + 3)

    const matIdx = resolveMaterialIndex(faceData, fallbackColor)
    faceMap.push({ elementIndex, face: faceName, materialIndex: matIdx })
    faceMap.push({ elementIndex, face: faceName, materialIndex: matIdx })

    let arr = groupsByMatIdx.get(matIdx)
    if (!arr) {
      arr = []
      groupsByMatIdx.set(matIdx, arr)
    }
    const last = arr[arr.length - 1]
    if (last && last.start + last.count === triStart) {
      last.count += 6
    } else {
      arr.push({ start: triStart, count: 6 })
    }
  }

  ;(model.elements ?? []).forEach((el, i) => {
    const fallback = PALETTE[i % PALETTE.length]!
    for (const face of FACES) {
      const faceData = el.faces?.[face]
      // Skip faces with no data unless we want fallback color rendering. For
      // texture-free cuboids, we still render with palette color so the model
      // is visible — passes the face data as undefined and the fallback path
      // produces a colored material.
      appendFace(el, i, face, faceData, fallback)
    }
  })

  const geom = new THREE.BufferGeometry()
  geom.setAttribute('position', new THREE.Float32BufferAttribute(positions, 3))
  geom.setAttribute('normal', new THREE.Float32BufferAttribute(normals, 3))
  geom.setAttribute('uv', new THREE.Float32BufferAttribute(uvs, 2))
  geom.setIndex(indices)
  // Add a material group for each contiguous run.
  for (const [matIdx, runs] of groupsByMatIdx) {
    for (const r of runs) geom.addGroup(r.start, r.count, matIdx)
  }
  // Attach face map for raycast lookup. faceMap is one entry per *triangle*
  // (so 2 entries per quad face). triIdx → entry.
  geom.userData.faceMap = faceMap

  return { geometry: geom, materials, faceMap }
}
