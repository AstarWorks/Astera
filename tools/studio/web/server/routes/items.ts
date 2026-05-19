/**
 * Astera Studio `/items` backend (Wave _items-1, ADR-0073; Wave _infra-5, ADR-0070).
 *
 * Successor to the legacy `/weapons` route. Items are now the first-class
 * authoring unit (ADR-0073) — each `content/items/<id>.yaml` carries the
 * vendor template, inline i18n, PvP attributes, and skill bindings. Studio
 * exposes read-only views over them.
 *
 *  - GET /api/items                    — list items from content/items/<id>.yaml
 *  - GET /api/items/load/:slug         — parse a single item YAML; resolves
 *                                        `item.model` (ADR-0070) against
 *                                        content/models/ then samples/ before
 *                                        falling back to the archetype map.
 *  - GET /api/items/model/:archetype   — passthrough of an archetype sample
 *                                        model (sword/gun/wand). Phase 1: the
 *                                        "archetype" concept on Item is implicit
 *                                        (read from the first skill binding's
 *                                        archetype via cross-ref, or "sword" by
 *                                        default). Real archetypes will live on
 *                                        the Skill spec in Phase 2.
 *  - GET /api/items/model/by-slug/:slug — resolve a model slug (ADR-0070) to
 *                                        the parsed `.model.json`. Tries
 *                                        content/models/ first, then
 *                                        tools/model-gen/samples/.
 */
import { Hono } from 'hono'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import yaml from 'js-yaml'
import {
  itemsContentDir as itemsDir,
  modelSamplesDir as samplesDir,
  modelsContentDir,
  repoRoot,
} from '../paths'

export const items = new Hono()

/**
 * Archetype → sample model file. Currently every archetype falls back to
 * `sword.model.json` because that's the only sample model that exists; the
 * `_assets` track will generate pistol / wand variants and this map will be
 * updated. ADR-0061 §Consequences documents the gap.
 */
const ARCHETYPE_MODEL: Record<string, string> = {
  sword: 'sword.model.json',
  gun: 'sword.model.json',
  wand: 'sword.model.json',
}

type ItemListEntry = {
  slug: string
  path: string
  rarity: string | null
  material: string | null
  skillCount: number
}

type ItemSkillBindingYaml = {
  trigger: string
  skill_id: string
  cooldown_override_ticks?: number | null
}

type ItemYamlConfig = {
  id: string
  material?: string
  external_key?: string
  custom_model_data?: number | null
  /**
   * ADR-0070: opaque model slug (no `.model.json` suffix). When set, Studio
   * resolves `${modelsContentDir}/${model}.model.json` first, then
   * `${modelSamplesDir}/${model}.model.json`, before falling back to the
   * material-derived archetype lookup.
   */
  model?: string
  name?: Record<string, string>
  lore?: Record<string, string[]>
  rarity?: string
  level_requirement?: number
  attributes?: {
    attack_damage?: number | null
    attack_speed?: number | null
    armor?: number | null
    armor_toughness?: number | null
    knockback_resistance?: number | null
    movement_speed_multiplier?: number | null
    max_health_bonus?: number | null
  }
  equip_slot?: string
  max_durability?: number | null
  unbreakable?: boolean
  drop_on_death?: boolean
  keep_on_death?: boolean
  enchantable?: boolean
  glint?: boolean | null
  skills?: ItemSkillBindingYaml[]
  pdc_tags?: Record<string, string>
}

type LoadedItem = {
  slug: string
  spec: ItemYamlConfig
  archetypeHint: string
  /**
   * Kept for backwards compat with the current frontend caller — it still
   * reflects the archetype-keyed pick from ARCHETYPE_MODEL. When a slug-based
   * resolution (ADR-0070) succeeds, `modelSource` / `modelPathRelative`
   * carry the override; callers that consume the new fields should prefer
   * those.
   */
  modelArchetype: string
  /** Where the resolved model came from (ADR-0070). */
  modelSource: 'content' | 'samples' | 'archetype'
  /**
   * Resolved model path relative to `repoRoot`, or null when the archetype
   * fallback path is used (the frontend resolves that via `/model/:archetype`).
   */
  modelPathRelative: string | null
  modelFallback: boolean
}

function isFileSafe(p: string): boolean {
  try {
    return statSync(p).isFile()
  } catch {
    return false
  }
}

function parseYamlSafe<T>(filePath: string): T | { __error: string } {
  try {
    const raw = readFileSync(filePath, 'utf8')
    const parsed = yaml.load(raw)
    return parsed as T
  } catch (e) {
    return { __error: `${(e as Error).message}` }
  }
}

function hasError<T>(v: T | { __error: string }): v is { __error: string } {
  return typeof v === 'object' && v !== null && '__error' in v
}

/** Light list: slug + path, plus rarity/material/skillCount for sidebar UI. */
items.get('/', (c) => {
  if (!existsSync(itemsDir)) return c.json([])
  const out: ItemListEntry[] = []
  for (const entry of readdirSync(itemsDir, { withFileTypes: true })) {
    if (!entry.isFile()) continue
    if (!entry.name.endsWith('.yaml') && !entry.name.endsWith('.yml')) continue
    const slug = entry.name.replace(/\.(yaml|yml)$/, '')
    const full = path.join(itemsDir, entry.name)
    const parsed = parseYamlSafe<ItemYamlConfig>(full)
    const rarity = hasError(parsed) ? null : (parsed.rarity ?? null)
    const material = hasError(parsed) ? null : (parsed.material ?? null)
    const skillCount = hasError(parsed) ? 0 : (parsed.skills?.length ?? 0)
    out.push({ slug, path: full, rarity, material, skillCount })
  }
  out.sort((a, b) => a.slug.localeCompare(b.slug))
  return c.json(out)
})

/** Detailed: parse item YAML, infer archetype from material. */
items.get('/load/:slug', (c) => {
  const slug = c.req.param('slug')
  if (!/^[\w./_-]+$/.test(slug)) {
    return c.json({ error: 'invalid slug' }, 400)
  }
  const itemPath = path.join(itemsDir, `${slug}.yaml`)
  try {
    if (!statSync(itemPath).isFile()) return c.json({ error: 'not found' }, 404)
  } catch {
    return c.json({ error: 'not found' }, 404)
  }
  const spec = parseYamlSafe<ItemYamlConfig>(itemPath)
  if (hasError(spec)) {
    return c.json({ error: `item YAML parse failed: ${spec.__error}` }, 500)
  }

  // Archetype hint from material — pragmatic best-guess so the 3D preview
  // picks a reasonable sample model. CROSSBOW → gun, BLAZE_ROD → wand,
  // anything else → sword.
  const material = spec.material ?? ''
  const archetypeHint =
    material === 'CROSSBOW' || material === 'BOW' ? 'gun'
    : material === 'BLAZE_ROD' || material === 'STICK' ? 'wand'
    : 'sword'
  const modelArchetype = archetypeHint in ARCHETYPE_MODEL ? archetypeHint : 'sword'

  // ADR-0070: prefer the YAML's explicit `model:` slug. Try content/models/
  // first, then tools/model-gen/samples/, before falling back to the
  // archetype-keyed sample lookup.
  let modelSource: LoadedItem['modelSource'] = 'archetype'
  let modelPathRelative: string | null = null
  let modelFallback: boolean

  if (typeof spec.model === 'string' && spec.model.length > 0) {
    const fromContent = path.join(modelsContentDir, `${spec.model}.model.json`)
    const fromSamples = path.join(samplesDir, `${spec.model}.model.json`)
    if (isFileSafe(fromContent)) {
      modelSource = 'content'
      modelPathRelative = path.relative(repoRoot, fromContent)
      modelFallback = false
    } else if (isFileSafe(fromSamples)) {
      modelSource = 'samples'
      modelPathRelative = path.relative(repoRoot, fromSamples)
      modelFallback = false
    } else {
      // Slug declared but unresolvable — silently fall through to archetype
      // path. Frontend can still spot this via modelSource='archetype' while
      // spec.model is set.
      modelFallback = ARCHETYPE_MODEL[archetypeHint] !== `${archetypeHint}.model.json`
    }
  } else {
    modelFallback = ARCHETYPE_MODEL[archetypeHint] !== `${archetypeHint}.model.json`
  }

  const result: LoadedItem = {
    slug,
    spec,
    archetypeHint,
    modelArchetype,
    modelSource,
    modelPathRelative,
    modelFallback,
  }
  return c.json(result)
})

/**
 * ADR-0070: passthrough of an arbitrary model slug. Resolves
 * `${modelsContentDir}/${slug}.model.json` first, then
 * `${modelSamplesDir}/${slug}.model.json`, else 404. Returns the parsed
 * `McModel` JSON, same shape as `/model/:archetype`.
 *
 * Declared before `/model/:archetype` so Hono routes `/model/by-slug/foo`
 * here instead of treating `by-slug` as the `:archetype` param.
 */
items.get('/model/by-slug/:slug', (c) => {
  const slug = c.req.param('slug')
  if (!/^[\w./_-]+$/.test(slug)) {
    return c.json({ error: 'invalid slug' }, 400)
  }
  const candidates = [
    path.join(modelsContentDir, `${slug}.model.json`),
    path.join(samplesDir, `${slug}.model.json`),
  ]
  const resolved = candidates.find(isFileSafe)
  if (!resolved) {
    return c.json({ error: 'model not found' }, 404)
  }
  try {
    const raw = readFileSync(resolved, 'utf8')
    return c.json(JSON.parse(raw))
  } catch (e) {
    return c.json({ error: `parse failed: ${(e as Error).message}` }, 500)
  }
})

/** Passthrough: the McModel JSON for an archetype's sample model. */
items.get('/model/:archetype', (c) => {
  const archetype = c.req.param('archetype')
  const file = ARCHETYPE_MODEL[archetype] ?? 'sword.model.json'
  const filePath = path.join(samplesDir, file)
  try {
    if (!statSync(filePath).isFile()) return c.json({ error: 'sample model not found' }, 404)
  } catch {
    return c.json({ error: 'sample model not found' }, 404)
  }
  try {
    const raw = readFileSync(filePath, 'utf8')
    return c.json(JSON.parse(raw))
  } catch (e) {
    return c.json({ error: `parse failed: ${(e as Error).message}` }, 500)
  }
})
