/**
 * Texture catalog — walks astera / vanilla / model side-car directories and
 * caches the listing for the process lifetime (dev-only tool; no live
 * invalidation needed). Cuts /api/textures from ~500ms to ~5ms after warmup.
 */
import { existsSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import {
  latestVanillaVersion,
  repoRoot,
  texturesDir,
  vanillaCacheDir,
} from '../paths'

export type TextureScope = 'astera' | 'vanilla' | 'content'

export type TextureEntry = {
  slug: string
  scope: TextureScope
  path: string
  size: number
  preview_url: string
}

function globToRegex(glob: string): RegExp {
  // Unanchored substring-glob. * matches any chars except /, ** matches any.
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
  const pattern = escaped.replace(/\*\*/g, '.+').replace(/\*/g, '[^/]*')
  return new RegExp(pattern, 'i')
}

export function matchesFilter(slug: string, filter: string | undefined): boolean {
  if (!filter) return true
  if (!filter.includes('*') && !filter.includes('?')) {
    return slug.toLowerCase().includes(filter.toLowerCase())
  }
  try {
    return globToRegex(filter).test(slug)
  } catch {
    return false
  }
}

function walkPng(
  rootDir: string,
  rel = '',
): { relPath: string; absPath: string; size: number }[] {
  if (!existsSync(rootDir)) return []
  const out: { relPath: string; absPath: string; size: number }[] = []
  const walk = (cur: string, r: string) => {
    let entries: { name: string; isFile(): boolean; isDirectory(): boolean }[]
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(cur, e.name)
      const next = r ? `${r}/${e.name}` : e.name
      if (e.isDirectory()) walk(full, next)
      else if (e.isFile() && e.name.endsWith('.png')) {
        try {
          const s = statSync(full)
          out.push({ relPath: next, absPath: full, size: s.size })
        } catch {}
      }
    }
  }
  walk(rootDir, rel)
  return out
}

let cachedAstera: TextureEntry[] | null = null
let cachedVanilla: TextureEntry[] | null = null
let cachedContent: TextureEntry[] | null = null

function buildAstera(): TextureEntry[] {
  return walkPng(texturesDir).map((f) => ({
    slug: `astera:${f.relPath.replace(/\.png$/, '')}`,
    scope: 'astera' as TextureScope,
    path: f.absPath,
    size: f.size,
    preview_url: `/files/textures/${f.relPath}`,
  }))
}

function buildVanilla(): TextureEntry[] {
  const version = latestVanillaVersion()
  if (!version) return []
  const root = path.join(vanillaCacheDir, version, 'assets', 'minecraft', 'textures')
  return walkPng(root).map((f) => ({
    slug: `minecraft:${f.relPath.replace(/\.png$/, '')}`,
    scope: 'vanilla' as TextureScope,
    path: f.absPath,
    size: f.size,
    preview_url: `/files/vanilla/${version}/${f.relPath}`,
  }))
}

function buildContent(): TextureEntry[] {
  const modelsDir = path.join(repoRoot, 'content', 'models')
  if (!existsSync(modelsDir)) return []
  const out: TextureEntry[] = []
  const walk = (cur: string, rel: string) => {
    let entries: { name: string; isFile(): boolean; isDirectory(): boolean }[]
    try {
      entries = readdirSync(cur, { withFileTypes: true })
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(cur, e.name)
      const next = rel ? `${rel}/${e.name}` : e.name
      if (e.isDirectory()) walk(full, next)
      else if (e.isFile() && e.name.endsWith('.png') && next.includes('/textures/')) {
        try {
          const s = statSync(full)
          out.push({
            slug: `astera:models/${next.replace(/\.png$/, '')}`,
            scope: 'content',
            path: full,
            size: s.size,
            preview_url: `/files/models-textures/${next}`,
          })
        } catch {}
      }
    }
  }
  walk(modelsDir, '')
  return out
}

export function listAstera(filter: string | undefined): TextureEntry[] {
  if (!cachedAstera) cachedAstera = buildAstera()
  return cachedAstera.filter((e) => matchesFilter(e.slug, filter))
}

export function listVanilla(filter: string | undefined): TextureEntry[] {
  if (!cachedVanilla) cachedVanilla = buildVanilla()
  return cachedVanilla.filter((e) => matchesFilter(e.slug, filter))
}

export function listContent(filter: string | undefined): TextureEntry[] {
  if (!cachedContent) cachedContent = buildContent()
  return cachedContent.filter((e) => matchesFilter(e.slug, filter))
}

export const DEFAULT_LIMIT = 60
export const MAX_LIMIT = 5000
