/**
 * Convert an MC model.json into a single Three.js Mesh (merged geometry,
 * material groups). One draw call per material — BlockBench-style.
 *
 * The legacy per-element approach lived here in Wave 1-3, but for the model
 * sizes we ship (50-150 cuboids), per-mesh rendering hit 280+ drawcalls and
 * stalled the viewport. R4 merges everything into one BufferGeometry.
 */
import * as THREE from 'three'
import type { McModel } from '../types'
import { mergeMcModelGeometry } from './merge-geometry'
import type { ResolveContext } from './texture-resolver'

const BOX_FACE_ORDER = ['east', 'west', 'up', 'down', 'south', 'north'] as const

export function mcModelToThree(
  model: McModel,
  ctx: ResolveContext = { vanillaVersion: null },
): THREE.Group {
  const root = new THREE.Group()
  root.name = 'mc-model'
  if (!model.elements || model.elements.length === 0) return root
  const { geometry, materials, faceMap } = mergeMcModelGeometry(model, ctx)
  const mesh = new THREE.Mesh(geometry, materials)
  mesh.userData.faceMap = faceMap
  mesh.userData.merged = true
  root.add(mesh)
  return root
}

export function disposeGroup(g: THREE.Object3D) {
  g.traverse((obj) => {
    if (obj instanceof THREE.Mesh) {
      obj.geometry?.dispose()
      // Materials are cached by merge-geometry's slug-keyed cache. Don't
      // dispose them here — they're reused on the next rebuild.
    }
  })
}

export { BOX_FACE_ORDER }
