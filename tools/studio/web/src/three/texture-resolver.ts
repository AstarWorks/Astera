import * as THREE from 'three'
import type { McModel } from '../types'

const loader = new THREE.TextureLoader()
const cache = new Map<string, THREE.Texture>()

export type ResolveContext = {
  vanillaVersion: string | null
}

function configureTexture(t: THREE.Texture): THREE.Texture {
  t.magFilter = THREE.NearestFilter
  t.minFilter = THREE.NearestFilter
  t.generateMipmaps = false
  t.colorSpace = THREE.SRGBColorSpace
  // Keep Three.js default flipY:true so the texture is sampled with the
  // image's pixel (0,0) at UV (0,1). Our setFaceUv() flips MC's V-down
  // convention to Three's V-up via `1 - v/16`, so both halves of the
  // transform line up. (Previously we tried flipY:false here, which
  // double-flipped and showed amethyst upside-down on every face.)
  t.wrapS = THREE.ClampToEdgeWrapping
  t.wrapT = THREE.ClampToEdgeWrapping
  return t
}

export function slugToUrl(
  slug: string,
  ctx: ResolveContext,
): string | null {
  if (slug.startsWith('astera:')) {
    const sub = slug.slice('astera:'.length)
    if (sub.startsWith('models/')) {
      return `/files/models-textures/${sub.slice('models/'.length)}.png`
    }
    return `/files/textures/${sub}.png`
  }
  if (slug.startsWith('minecraft:')) {
    if (!ctx.vanillaVersion) return null
    return `/files/vanilla/${ctx.vanillaVersion}/${slug.slice('minecraft:'.length)}.png`
  }
  return null
}

/**
 * Resolve a face's `texture` field (which may be a `#layer_name` reference
 * pointing into `model.textures`, or a direct slug) to a loadable URL.
 */
export function resolveFaceTexture(
  textureRef: string | undefined,
  model: McModel,
  ctx: ResolveContext,
): string | null {
  if (!textureRef) return null
  let slug: string | undefined
  if (textureRef.startsWith('#')) {
    const layer = textureRef.slice(1)
    slug = model.textures?.[layer]
  } else {
    slug = textureRef
  }
  if (!slug) return null
  return slugToUrl(slug, ctx)
}

// Render trigger registered by ThreeViewport — called after a texture finishes
// loading so the viewport re-renders the now-painted faces. Without this, the
// render-on-demand viewport would never repaint after the initial frame and
// late-arriving textures would stay invisible.
let renderTrigger: (() => void) | null = null
export function setRenderTrigger(fn: (() => void) | null) {
  renderTrigger = fn
}

export function loadTexture(
  url: string,
  onError?: (err: unknown) => void,
): THREE.Texture {
  const cached = cache.get(url)
  if (cached) return cached
  const tex = configureTexture(
    loader.load(
      url,
      () => {
        renderTrigger?.()
      },
      undefined,
      (err) => {
        onError?.(err)
        renderTrigger?.()
      },
    ),
  )
  cache.set(url, tex)
  return tex
}

export function clearTextureCache() {
  for (const t of cache.values()) t.dispose()
  cache.clear()
}
