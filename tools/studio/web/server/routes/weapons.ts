/**
 * Astera Studio `/weapons` backend (Wave _infra-3, ADR-0061).
 *
 * Three read-only endpoints for the weapons preview route:
 *  - GET /api/weapons             — list weapons from content/weapons/*.yaml
 *  - GET /api/weapons/load/:slug  — parse the YAML + cross-ref the item YAML
 *  - GET /api/weapons/model/:archetype — passthrough of an archetype's sample
 *                                       model.json (fallback to sword for
 *                                       archetypes without a dedicated model)
 *
 * Phase 1 only — no live editing, no schema changes. The chain
 * weapon→item→model is currently broken (item YAML has `material:` but no
 * `model:` field); see ADR-0061 for the long-term Phase 2 fix.
 */
import { Hono } from 'hono'
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import path from 'node:path'
import yaml from 'js-yaml'
import { repoRoot } from '../paths'

export const weapons = new Hono()

const weaponsDir = path.join(repoRoot, 'content', 'weapons')
const itemsDir = path.join(repoRoot, 'content', 'items')
const samplesDir = path.join(repoRoot, 'tools', 'model-gen', 'samples')

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

type WeaponListEntry = {
  slug: string
  path: string
  archetype: string | null
  rarity: string | null
}

type WeaponYamlConfig = {
  id: string
  display_name_key: string
  lore_key: string
  archetype: string
  rarity: string
  level_requirement: number
  item: string
  damage: { base: number; attribute: string }
  cooldown_ticks: number
}

type ItemYamlConfig = {
  id: string
  material?: string
  external_key?: string
  custom_model_data?: number | null
  display_name_key?: string
  lore_keys?: string[]
  pdc_tags?: Record<string, string>
}

type LoadedWeapon = {
  slug: string
  spec: WeaponYamlConfig
  item: ItemYamlConfig | null
  itemError: string | null
  archetype: string
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

/** Light list: just slug + path, plus archetype/rarity hints for sidebar UI. */
weapons.get('/', (c) => {
  if (!existsSync(weaponsDir)) return c.json([])
  const out: WeaponListEntry[] = []
  for (const entry of readdirSync(weaponsDir, { withFileTypes: true })) {
    if (!entry.isFile()) continue
    if (!entry.name.endsWith('.yaml') && !entry.name.endsWith('.yml')) continue
    const slug = entry.name.replace(/\.(yaml|yml)$/, '')
    const full = path.join(weaponsDir, entry.name)
    const parsed = parseYamlSafe<WeaponYamlConfig>(full)
    const archetype = hasError(parsed) ? null : (parsed.archetype ?? null)
    const rarity = hasError(parsed) ? null : (parsed.rarity ?? null)
    out.push({ slug, path: full, archetype, rarity })
  }
  out.sort((a, b) => a.slug.localeCompare(b.slug))
  return c.json(out)
})

/** Detailed: parse weapon YAML, cross-ref item YAML, report fallback model. */
weapons.get('/load/:slug', (c) => {
  const slug = c.req.param('slug')
  if (!/^[\w./_-]+$/.test(slug)) {
    return c.json({ error: 'invalid slug' }, 400)
  }
  const weaponPath = path.join(weaponsDir, `${slug}.yaml`)
  try {
    if (!statSync(weaponPath).isFile()) return c.json({ error: 'not found' }, 404)
  } catch {
    return c.json({ error: 'not found' }, 404)
  }
  const spec = parseYamlSafe<WeaponYamlConfig>(weaponPath)
  if (hasError(spec)) {
    return c.json({ error: `weapon YAML parse failed: ${spec.__error}` }, 500)
  }

  // Cross-reference the item YAML. Soft-fail: a missing item file isn't fatal,
  // we still want to render the weapon stats.
  let item: ItemYamlConfig | null = null
  let itemError: string | null = null
  if (spec.item) {
    const itemPath = path.join(itemsDir, `${spec.item}.yaml`)
    if (existsSync(itemPath)) {
      const parsedItem = parseYamlSafe<ItemYamlConfig>(itemPath)
      if (hasError(parsedItem)) {
        itemError = `item YAML parse failed: ${parsedItem.__error}`
      } else {
        item = parsedItem
      }
    } else {
      itemError = `item file not found: content/items/${spec.item}.yaml`
    }
  }

  const archetype = spec.archetype ?? 'sword'
  const modelArchetype = archetype in ARCHETYPE_MODEL ? archetype : 'sword'
  const modelFallback = !(archetype in ARCHETYPE_MODEL) || ARCHETYPE_MODEL[archetype] === 'sword.model.json' && archetype !== 'sword'

  const result: LoadedWeapon = {
    slug,
    spec,
    item,
    itemError,
    archetype,
    modelArchetype,
    modelFallback,
  }
  return c.json(result)
})

/** Passthrough: the McModel JSON for an archetype's sample model. */
weapons.get('/model/:archetype', (c) => {
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
