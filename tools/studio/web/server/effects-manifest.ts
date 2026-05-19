/**
 * content/effects/manifest.json reader/writer. Slug → record map of every
 * fxgraph that has been exported, so other tools (model-gen / runtime) can
 * cross-reference an effect by stable id rather than file path.
 *
 * Atomic write: write to a sibling temp file, then rename. Survives a crash
 * mid-write without leaving a half-empty manifest.
 */
import { existsSync, readFileSync, mkdirSync, renameSync, writeFileSync } from 'node:fs'
import path from 'node:path'
import { repoRoot } from './paths'

export const effectsDir = path.join(repoRoot, 'content', 'effects')
export const effectsManifestPath = path.join(effectsDir, 'manifest.json')

export type EffectsManifestEntry = {
  /** Slug under content/effects/ (relative path without `.fxgraph.json`). */
  slug: string
  /** Path of the recipe file relative to repo root. */
  fxgraph_path: string
  /** Texture path(s) emitted, relative to repo root. */
  texture_paths: string[]
  /** Optional model.json path (item_display billboard model). */
  model_path?: string
  exported_at: string
  frames: number
  size: [number, number]
  frame_time_ticks: number
  formats: string[]
}

export type EffectsManifest = {
  version: 1
  assets: Record<string, EffectsManifestEntry>
}

export function readEffectsManifest(): EffectsManifest {
  if (!existsSync(effectsManifestPath)) return { version: 1, assets: {} }
  try {
    return JSON.parse(readFileSync(effectsManifestPath, 'utf8')) as EffectsManifest
  } catch {
    return { version: 1, assets: {} }
  }
}

export function writeEffectsManifestAtomic(m: EffectsManifest): void {
  mkdirSync(effectsDir, { recursive: true })
  const tmp = `${effectsManifestPath}.tmp-${Date.now()}-${Math.random().toString(36).slice(2)}`
  writeFileSync(tmp, JSON.stringify(m, null, 2) + '\n', 'utf8')
  renameSync(tmp, effectsManifestPath)
}

export function appendEffectsEntry(slug: string, entry: EffectsManifestEntry): void {
  const m = readEffectsManifest()
  m.assets[slug] = entry
  writeEffectsManifestAtomic(m)
}
