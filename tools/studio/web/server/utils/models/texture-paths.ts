/**
 * Resolve a texture slug to an absolute filesystem path.
 *
 *   - astera:items/swords/foo  →  <repo>/content/textures/items/swords/foo.png
 *   - minecraft:block/amethyst →  <repo>/tools/studio/.vanilla-cache/<ver>/assets/minecraft/textures/block/amethyst.png
 *   - astera:models/bar        →  <repo>/content/models/bar.png  (side-car textures, Day 2 spec)
 *
 * Returns null if the slug is unparseable or the file does not exist.
 */
import { existsSync } from 'node:fs'
import path from 'node:path'
import {
  modelsContentDir,
  texturesDir,
  vanillaCacheDir,
  latestVanillaVersion,
} from '../paths'

export function slugToPath(slug: string): string | null {
  if (slug.startsWith('astera:')) {
    const sub = slug.slice('astera:'.length)
    if (sub.startsWith('models/')) {
      return path.join(modelsContentDir, `${sub.slice('models/'.length)}.png`)
    }
    return path.join(texturesDir, `${sub}.png`)
  }
  if (slug.startsWith('minecraft:')) {
    const v = latestVanillaVersion()
    if (!v) return null
    return path.join(
      vanillaCacheDir,
      v,
      'assets',
      'minecraft',
      'textures',
      `${slug.slice('minecraft:'.length)}.png`,
    )
  }
  return null
}

export function slugExists(slug: string): boolean {
  const p = slugToPath(slug)
  return p !== null && existsSync(p)
}
