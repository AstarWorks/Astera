/**
 * Template library — `tools/model-gen/templates/<category>/<name>.model.json`
 * are the "golden samples" the Skill loads as a starting point. Each
 * template is a fully-formed MC model.json (validates against the schema)
 * with display transforms already set, just no texture bindings.
 */
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import path from 'node:path'
import type { McModel } from '../src/types'
import { repoRoot } from './paths'

const TEMPLATES_DIR =
  process.env.STUDIO_MODEL_TEMPLATES_DIR ??
  path.join(repoRoot, 'tools/model-gen/templates')

export type TemplateEntry = {
  category: string
  name: string
  path: string
  /** Number of elements (cuboids) in this template. */
  cuboid_count: number
  /** Optional one-line description from the file's __meta key, if present. */
  description?: string
}

export function listTemplates(): TemplateEntry[] {
  if (!existsSync(TEMPLATES_DIR)) return []
  const out: TemplateEntry[] = []
  let categories: string[]
  try {
    categories = readdirSync(TEMPLATES_DIR)
  } catch {
    return []
  }
  for (const cat of categories) {
    const catDir = path.join(TEMPLATES_DIR, cat)
    try {
      if (!statSync(catDir).isDirectory()) continue
    } catch {
      continue
    }
    let files: string[]
    try {
      files = readdirSync(catDir)
    } catch {
      continue
    }
    for (const f of files) {
      if (!f.endsWith('.model.json')) continue
      const filePath = path.join(catDir, f)
      try {
        const raw = readFileSync(filePath, 'utf8')
        const model = JSON.parse(raw) as McModel & { __meta?: { description?: string } }
        out.push({
          category: cat,
          name: f.replace(/\.model\.json$/, ''),
          path: filePath,
          cuboid_count: model.elements?.length ?? 0,
          description: model.__meta?.description,
        })
      } catch {
        // skip malformed templates rather than crash the listing
      }
    }
  }
  return out
}

export function loadTemplateModel(category: string, name: string): McModel {
  const normCat = path.posix.normalize(category)
  const normName = path.posix.normalize(name)
  if (
    normCat.includes('/') ||
    normCat.startsWith('.') ||
    normName.includes('/') ||
    normName.startsWith('.')
  ) {
    throw new Error('invalid category or name')
  }
  const filePath = path.join(TEMPLATES_DIR, normCat, `${normName}.model.json`)
  if (!existsSync(filePath)) {
    throw new Error(`template not found: ${normCat}/${normName}`)
  }
  const raw = readFileSync(filePath, 'utf8')
  const model = JSON.parse(raw) as McModel & { __meta?: unknown }
  // Strip __meta (schema would reject the extra property otherwise).
  if ('__meta' in model) delete (model as { __meta?: unknown }).__meta
  return model
}
