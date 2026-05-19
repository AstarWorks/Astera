import * as THREE from 'three'
import { BOX_FACE_ORDER } from './mcmodel-to-three'
import type { FaceInfo } from './merge-geometry'

export type PickedFace = {
  elementIndex: number
  face: (typeof BOX_FACE_ORDER)[number]
  point: THREE.Vector3
  normal: THREE.Vector3 | null
}

/**
 * Raycast against the merged-geometry model and return the picked cuboid
 * face, or null. After R4 the model is rendered as a single Mesh with all
 * cuboid faces merged into one BufferGeometry; the `faceMap` carried on the
 * mesh's userData lets us map triangle index → (elementIndex, face).
 */
export function pickFace(
  event: MouseEvent,
  canvas: HTMLElement,
  camera: THREE.PerspectiveCamera,
  target: THREE.Object3D,
): PickedFace | null {
  const rect = canvas.getBoundingClientRect()
  const ndc = new THREE.Vector2(
    ((event.clientX - rect.left) / rect.width) * 2 - 1,
    -((event.clientY - rect.top) / rect.height) * 2 + 1,
  )
  const ray = new THREE.Raycaster()
  ray.setFromCamera(ndc, camera)
  const hits = ray.intersectObject(target, true)
  if (hits.length === 0) return null
  const hit = hits[0]!
  const mesh = hit.object as THREE.Mesh
  const faceMap = mesh.userData.faceMap as FaceInfo[] | undefined
  if (!faceMap || hit.faceIndex === undefined) {
    // Fall back to the legacy per-Mesh path (in case some old artifact is still
    // around).
    const legacyEl = (mesh.userData.elementIndex ?? mesh.parent?.userData.elementIndex) as
      | number
      | undefined
    if (legacyEl === undefined) return null
    const faceIdx = hit.face?.materialIndex
    if (faceIdx === undefined || faceIdx < 0 || faceIdx > 5) return null
    return {
      elementIndex: legacyEl,
      face: BOX_FACE_ORDER[faceIdx]!,
      point: hit.point.clone(),
      normal: hit.face?.normal ? hit.face.normal.clone() : null,
    }
  }
  const idx = hit.faceIndex
  if (idx === undefined || idx === null) return null
  const entry = faceMap[idx]
  if (!entry) return null
  return {
    elementIndex: entry.elementIndex,
    face: entry.face,
    point: hit.point.clone(),
    normal: hit.face?.normal ? hit.face.normal.clone() : null,
  }
}
