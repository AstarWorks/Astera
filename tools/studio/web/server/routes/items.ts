/**
 * Astera Studio `/items` backend (Wave _items-1, ADR-0073).
 *
 * Successor to the legacy `/weapons` route. Items are now the first-class
 * authoring unit (ADR-0073) — each `content/items/<id>.yaml` carries the
 * vendor template, inline i18n, PvP attributes, and skill bindings. Studio
 * exposes read-only views over them.
 *
 *  - GET /api/items             — list items from content/items/<id>.yaml
 *  - GET /api/items/load/:slug  — parse a single item YAML
 *  - GET /api/items/model/:archetype — passthrough of an archetype sample
 *                                      model (sword/gun/wand). Phase 1: the
 *                                      "archetype" concept on Item is implicit
 *                                      (read from the first skill binding's
 *                                      archetype via cross-ref, or "sword" by
 *                                      default). Real archetypes will live on
 *                                      the Skill spec in Phase 2.
 */
import { Hono } from 'hono'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import yaml from 'js-yaml'
import { itemsContentDir as itemsDir, modelSamplesDir as samplesDir } from '../paths'

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
  modelArchetype: string
  modelFallback: boolean
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
  const modelFallback = ARCHETYPE_MODEL[archetypeHint] !== `${archetypeHint}.model.json`

  const result: LoadedItem = {
    slug,
    spec,
    archetypeHint,
    modelArchetype,
    modelFallback,
  }
  return c.json(result)
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
