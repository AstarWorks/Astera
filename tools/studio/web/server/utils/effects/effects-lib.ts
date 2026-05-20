/**
 * Shared helpers for the effects (/api/effects/*) endpoints — fxgraph slug
 * sanitization, atomic file writes, repo-relative path conversion, and
 * recursive fxgraph listing.
 */
import {
  existsSync,
  mkdirSync,
  readdirSync,
  renameSync,
  writeFileSync,
} from 'node:fs'
import path from 'node:path'
import { repoRoot } from '../paths'

export type EffectsScope = 'samples' | 'content'

export type EffectListEntry = {
  slug: string
  path: string
  scope: EffectsScope
}

export const animationsDir = path.join(
  repoRoot,
  'content',
  'textures',
  'animations',
)

export const modelsEffectsDir = path.join(repoRoot, 'content', 'models', 'effects')

export function listFxGraphs(dir: string, scope: EffectsScope): EffectListEntry[] {
  if (!existsSync(dir)) return []
  const out: EffectListEntry[] = []
  const walk = (subdir: string, rel: string) => {
    let entries: { name: string; isFile(): boolean; isDirectory(): boolean }[] = []
    try {
      entries = readdirSync(subdir, { withFileTypes: true }) as typeof entries
    } catch {
      return
    }
    for (const e of entries) {
      const full = path.join(subdir, e.name)
      const r = rel ? `${rel}/${e.name}` : e.name
      if (e.isFile() && e.name.endsWith('.fxgraph.json')) {
        const slug = r.replace(/\.fxgraph\.json$/, '')
        out.push({ slug, path: full, scope })
      } else if (e.isDirectory()) {
        walk(full, r)
      }
    }
  }
  walk(dir, '')
  return out
}

export function safeSlug(raw: string): string | null {
  const norm = path.posix.normalize(raw)
  if (
    norm.startsWith('../') ||
    norm.includes('/../') ||
    path.isAbsolute(norm) ||
    !/^[A-Za-z0-9_\-/]+$/.test(norm)
  ) {
    return null
  }
  return norm
}

export function atomicWrite(targetPath: string, bytes: Uint8Array | string) {
  mkdirSync(path.dirname(targetPath), { recursive: true })
  const tmp = `${targetPath}.tmp-${Date.now()}-${Math.random().toString(36).slice(2)}`
  if (typeof bytes === 'string') writeFileSync(tmp, bytes, 'utf8')
  else writeFileSync(tmp, bytes)
  renameSync(tmp, targetPath)
}

export function relToRepo(p: string): string {
  return path.relative(repoRoot, p).replaceAll(path.sep, '/')
}

export type ExportBody = {
  basename: string
  formats: string[]
  vertical_sheet_b64?: string
  horizontal_sheet_b64?: string
  frames_b64?: string[]
  mcmeta?: unknown
  model_json?: unknown
  fxgraph?: {
    frames?: number
    size?: [number, number]
    frame_time_ticks?: number
  }
  summon_help?: string
}
