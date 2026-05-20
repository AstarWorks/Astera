/**
 * Convert an MC Java `model.json` (our SoT) to a BlockBench `.bbmodel`
 * (format_version 5.0). One-way only — `.bbmodel` is for round-tripping
 * through the BB GUI when a contributor wants to hand-tweak.
 *
 * Maps:
 *   - elements[].from/to                        → bbmodel cube from/to
 *   - elements[].rotation {axis, angle, origin} → bbmodel rotation [rx, ry, rz] + origin
 *   - elements[].faces.<dir>                    → bbmodel faces.<dir>
 *   - model.textures map (#layer → slug)        → bbmodel textures[] (with base64-embedded PNG)
 *   - face.texture "#layer0"                    → integer index into textures[]
 */
import { readFileSync } from 'node:fs'
import type { McModel, FaceName, Element, Face } from '#shared/types'
import { slugToPath } from './texture-paths'

type BbCube = {
  uuid: string
  name: string
  from: [number, number, number]
  to: [number, number, number]
  origin: [number, number, number]
  rotation: [number, number, number]
  faces: Partial<Record<FaceName, { uv: [number, number, number, number]; texture: number | null }>>
}

type BbTexture = {
  path: string
  name: string
  folder: string
  namespace: string
  id: string
  particle: boolean
  source: string
}

export type BbModel = {
  meta: {
    format_version: '5.0'
    model_format: 'java_block'
  }
  name: string
  parent?: string
  resolution: { width: number; height: number }
  elements: BbCube[]
  outliner: string[]
  textures: BbTexture[]
}

function uuidv4(): string {
  // RFC 4122 v4 — Bun has crypto.randomUUID
  return crypto.randomUUID()
}

function loadTextureAsDataUrl(slug: string): string {
  const filePath = slugToPath(slug)
  if (!filePath) {
    console.warn(`[bbmodel] cannot resolve slug to path: ${slug}`)
    return ''
  }
  try {
    const buf = readFileSync(filePath)
    return `data:image/png;base64,${buf.toString('base64')}`
  } catch (e) {
    console.warn(`[bbmodel] read failed for ${slug}: ${e}`)
    return ''
  }
}

export function mcModelToBbmodel(
  model: McModel,
  options: { name: string },
): BbModel {
  // Build a texture index — layers in insertion order.
  const layerNames = Object.keys(model.textures ?? {})
  const layerToIndex = new Map<string, number>()
  layerNames.forEach((name, i) => layerToIndex.set(name, i))

  const bbTextures: BbTexture[] = layerNames.map((layer, i) => {
    const slug = model.textures![layer]!
    const [namespace, rest] = slug.includes(':')
      ? [slug.split(':', 1)[0]!, slug.slice(slug.indexOf(':') + 1)]
      : ['astera', slug]
    const folder = rest.includes('/') ? rest.slice(0, rest.lastIndexOf('/')) : ''
    const fileName = rest.split('/').pop() ?? rest
    return {
      path: '',
      name: layer,
      folder,
      namespace,
      id: String(i),
      particle: i === 0,
      source: loadTextureAsDataUrl(slug),
      // BB also tracks `width`/`height`; left to BB to infer on load
    } as BbTexture
  })

  const cubes: BbCube[] = (model.elements ?? []).map((el: Element, idx) => {
    const uuid = uuidv4()
    const center: [number, number, number] = [
      (el.from[0] + el.to[0]) / 2,
      (el.from[1] + el.to[1]) / 2,
      (el.from[2] + el.to[2]) / 2,
    ]
    const origin: [number, number, number] = el.rotation?.origin ?? center
    const rotation: [number, number, number] = el.rotation
      ? [
          el.rotation.axis === 'x' ? el.rotation.angle : 0,
          el.rotation.axis === 'y' ? el.rotation.angle : 0,
          el.rotation.axis === 'z' ? el.rotation.angle : 0,
        ]
      : [0, 0, 0]

    const facesOut: BbCube['faces'] = {}
    if (el.faces) {
      for (const [dir, face] of Object.entries(el.faces) as [FaceName, Face | undefined][]) {
        const ref = face?.texture
        let texIndex: number | null = null
        if (ref && ref.startsWith('#')) {
          const layer = ref.slice(1)
          texIndex = layerToIndex.get(layer) ?? null
        }
        facesOut[dir] = {
          uv: face?.uv ?? [0, 0, 16, 16],
          texture: texIndex,
        }
      }
    }

    return {
      uuid,
      name: `cube_${idx}`,
      from: [...el.from],
      to: [...el.to],
      origin,
      rotation,
      faces: facesOut,
    }
  })

  return {
    meta: {
      format_version: '5.0',
      model_format: 'java_block',
    },
    name: options.name,
    parent: model.parent,
    resolution: { width: 16, height: 16 },
    elements: cubes,
    outliner: cubes.map((c) => c.uuid),
    textures: bbTextures,
  }
}
