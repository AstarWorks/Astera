/**
 * Texture browser API — unified listing across three scopes:
 *
 *   - astera  → content/textures/**.png           (Astera-curated)
 *   - vanilla → tools/studio/.vanilla-cache/<ver>/assets/minecraft/textures/{block,item}/**.png
 *   - content → content/models/**.png             (model side-car textures, if any)
 *
 * Slug conventions match MC's resource location naming:
 *   - astera:items/swords/sword_v01
 *   - minecraft:block/amethyst_block
 *
 * Preview URLs use the existing /files/textures/* route for astera and a new
 * /files/vanilla/<ver>/* route for vanilla (registered in server/index.ts).
 */
import { Hono } from 'hono'
import { existsSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import {
  repoRoot,
  texturesDir,
  vanillaCacheDir,
  latestVanillaVersion,
} from '../paths'

export const textures = new Hono()

type Scope = 'astera' | 'vanilla' | 'content'

type TextureEntry = {
  slug: string
  scope: Scope
  path: string
  size: number
  preview_url: string
}

function globToRegex(glob: string): RegExp {
  // Unanchored substring-glob. * matches any chars except /, ** matches any chars.
  // So "block/amethyst*" matches anywhere in slug like "minecraft:block/amethyst_block".
  const escaped = glob.replace(/[.+^${}()|[\]\\]/g, '\\$&')
  const pattern = escaped.replace(/\*\*/g, '.+').replace(/\*/g, '[^/]*')
  return new RegExp(pattern, 'i')
}

function matchesFilter(slug: string, filter: string | undefined): boolean {
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

// In-memory cache for the texture listings. Built lazily and persists for
// the process lifetime — these are static files in a dev tool, no live
// invalidation needed. Reduces /api/textures from ~500ms (full readdir walk)
// to ~5ms (in-memory filter).
let cachedAstera: TextureEntry[] | null = null
let cachedVanilla: TextureEntry[] | null = null
let cachedContent: TextureEntry[] | null = null

function buildAstera(): TextureEntry[] {
  return walkPng(texturesDir).map((f) => {
    const noExt = f.relPath.replace(/\.png$/, '')
    return {
      slug: `astera:${noExt}`,
      scope: 'astera' as Scope,
      path: f.absPath,
      size: f.size,
      preview_url: `/files/textures/${f.relPath}`,
    }
  })
}

function buildVanilla(): TextureEntry[] {
  const version = latestVanillaVersion()
  if (!version) return []
  const root = path.join(vanillaCacheDir, version, 'assets', 'minecraft', 'textures')
  return walkPng(root).map((f) => {
    const noExt = f.relPath.replace(/\.png$/, '')
    return {
      slug: `minecraft:${noExt}`,
      scope: 'vanilla' as Scope,
      path: f.absPath,
      size: f.size,
      preview_url: `/files/vanilla/${version}/${f.relPath}`,
    }
  })
}

function listAstera(filter: string | undefined): TextureEntry[] {
  if (!cachedAstera) cachedAstera = buildAstera()
  return cachedAstera.filter((e) => matchesFilter(e.slug, filter))
}

function listVanilla(filter: string | undefined): TextureEntry[] {
  if (!cachedVanilla) cachedVanilla = buildVanilla()
  return cachedVanilla.filter((e) => matchesFilter(e.slug, filter))
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
      else if (
        e.isFile() &&
        e.name.endsWith('.png') &&
        next.includes('/textures/')
      ) {
        try {
          const s = statSync(full)
          const noExt = next.replace(/\.png$/, '')
          out.push({
            slug: `astera:models/${noExt}`,
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

function listContent(filter: string | undefined): TextureEntry[] {
  if (!cachedContent) cachedContent = buildContent()
  return cachedContent.filter((e) => matchesFilter(e.slug, filter))
}

const DEFAULT_LIMIT = 60
const MAX_LIMIT = 5000

textures.get('/', (c) => {
  const scopeRaw = c.req.query('scope') ?? 'all'
  const filter = c.req.query('filter') || undefined
  const limitRaw = c.req.query('limit')
  const limit = limitRaw
    ? Math.min(Math.max(1, Number(limitRaw) || DEFAULT_LIMIT), MAX_LIMIT)
    : DEFAULT_LIMIT

  const scopes: Scope[] =
    scopeRaw === 'all'
      ? ['astera', 'vanilla', 'content']
      : ([scopeRaw] as Scope[]).filter((s) =>
          ['astera', 'vanilla', 'content'].includes(s),
        )
  if (scopes.length === 0) {
    return c.json({ error: `invalid scope: ${scopeRaw}` }, 400)
  }

  const out: TextureEntry[] = []
  for (const s of scopes) {
    if (s === 'astera') out.push(...listAstera(filter))
    else if (s === 'vanilla') out.push(...listVanilla(filter))
    else if (s === 'content') out.push(...listContent(filter))
  }
  const total = out.length
  const sliced = out.slice(0, limit)
  return c.json({
    count: sliced.length,
    total,
    truncated: total > sliced.length,
    vanilla_version: latestVanillaVersion(),
    textures: sliced,
  })
})

textures.get('/scopes', (c) => {
  const v = latestVanillaVersion()
  return c.json({
    astera: existsSync(texturesDir),
    vanilla: v !== null,
    vanilla_version: v,
    content: existsSync(path.join(repoRoot, 'content', 'models')),
  })
})
