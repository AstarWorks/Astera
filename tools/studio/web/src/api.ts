import type {
  Job,
  JobDetail,
  Manifest,
  ManifestEntry,
  CategoryEntry,
  Tile,
  McModel,
} from './types'
import type { FxGraph } from './effects/types'

export type ModelListEntry = {
  slug: string
  path: string
  scope: 'samples' | 'content'
}

export type LoadedModel = {
  slug: string
  scope: 'samples' | 'content'
  model: McModel
}

export type EffectListEntry = {
  slug: string
  path: string
  scope: 'samples' | 'content'
}

export type LoadedEffect = {
  slug: string
  scope: 'samples' | 'content'
  graph: FxGraph
}

// Wave _infra-3 (ADR-0061): read-only weapon preview types. Field names
// mirror WeaponYamlConfig.kt + ItemYamlConfig.kt (kaml decodes from
// snake_case YAML).
export type WeaponListEntry = {
  slug: string
  path: string
  archetype: string | null
  rarity: string | null
}

export type WeaponYamlConfig = {
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

export type ItemYamlConfig = {
  id: string
  material?: string
  external_key?: string
  custom_model_data?: number | null
  display_name_key?: string
  lore_keys?: string[]
  pdc_tags?: Record<string, string>
}

export type LoadedWeapon = {
  slug: string
  spec: WeaponYamlConfig
  item: ItemYamlConfig | null
  itemError: string | null
  archetype: string
  modelArchetype: string
  modelFallback: boolean
}

export type ChatTurn = { role: 'user' | 'assistant'; content: string }

export type JsonPatchOp = {
  op: 'add' | 'replace' | 'remove' | 'move' | 'copy' | 'test'
  path: string
  value?: unknown
  from?: string
}

export type ChatResponse = {
  raw: string
  explanation: string
  patch: JsonPatchOp[] | null
  question: string | null
  usage: {
    input: number
    cache_read: number
    cache_creation: number
    output: number
  }
  cost_usd: number
  model: string
}

export type TextureScope = 'astera' | 'vanilla' | 'content'

export type TextureEntry = {
  slug: string
  scope: TextureScope
  path: string
  size: number
  preview_url: string
}

export type TextureScopesStatus = {
  astera: boolean
  vanilla: boolean
  vanilla_version: string | null
  content: boolean
}

export type TextureListResponse = {
  count: number
  total: number
  truncated: boolean
  vanilla_version: string | null
  textures: TextureEntry[]
}

async function j<T>(path: string, init?: RequestInit): Promise<T> {
  const r = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...init,
  })
  if (!r.ok) throw new Error(`${r.status}: ${await r.text()}`)
  return (await r.json()) as T
}

export const api = {
  jobs: () => j<Job[]>('/api/jobs'),
  job: (id: string) => j<JobDetail>(`/api/jobs/${id}`),
  accept: (
    jobId: string,
    tileName: string,
    body: { slug: string; targetPath: string },
  ) =>
    j<ManifestEntry>(`/api/jobs/${jobId}/tiles/${tileName}/accept`, {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  reject: (jobId: string, tileName: string) =>
    j<{ ok: true }>(`/api/jobs/${jobId}/tiles/${tileName}/reject`, {
      method: 'POST',
    }),
  regenerate: (jobId: string, tileName: string, refinedPrompt: string) =>
    j<{ tile: Tile }>(
      `/api/jobs/${jobId}/tiles/${tileName}/regenerate`,
      {
        method: 'POST',
        body: JSON.stringify({ refinedPrompt }),
      },
    ),
  generate: (body: { category: string; prompt: string; grid: string }) =>
    j<{ jobId: string }>('/api/generate', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  manifest: () => j<Manifest>('/api/manifest'),
  categories: () => j<CategoryEntry[]>('/api/categories'),
  models: () => j<ModelListEntry[]>('/api/models'),
  loadModel: (scope: 'samples' | 'content', slug: string) =>
    j<LoadedModel>(`/api/models/load/${scope}/${slug}`),
  // Wave _infra-3 (ADR-0061): read-only weapons preview.
  weapons: () => j<WeaponListEntry[]>('/api/weapons'),
  loadWeapon: (slug: string) => j<LoadedWeapon>(`/api/weapons/load/${slug}`),
  weaponModel: (archetype: string) =>
    j<McModel>(`/api/weapons/model/${archetype}`),
  // Language files for i18n resolution in /weapons (ADR-0073 Tier I).
  languages: () => j<string[]>('/api/languages'),
  loadLocale: (locale: string) =>
    j<{ locale: string; entries: Record<string, string | string[]> }>(
      `/api/languages/${locale}`,
    ),
  effects: () => j<EffectListEntry[]>('/api/effects'),
  loadEffect: (scope: 'samples' | 'content', slug: string) =>
    j<LoadedEffect>(`/api/effects/load/${scope}/${slug}`),
  effectSave: (slug: string, graph: FxGraph) =>
    j<{ ok: true; slug: string; path: string }>(
      `/api/effects/save/${slug}`,
      { method: 'PUT', body: JSON.stringify({ graph }) },
    ),
  effectOutputPaths: () =>
    j<{
      repo_root: string
      paths: { recipes: string; textures: string; models: string }
      absolute: { recipes: string; textures: string; models: string }
    }>('/api/effects/output-paths'),
  effectChat: (body: {
    user_input: string
    graph: FxGraph
    history: ChatTurn[]
    model: 'sonnet' | 'opus'
  }) =>
    j<ChatResponse>('/api/effects/chat', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  effectAiImage: (body: { prompt: string; seed?: number }) =>
    j<{
      ok: true
      generated_path: string
      jobId: string
      prompt: string
      seed: number
    }>('/api/effects/ai-image', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
  textureScopes: () => j<TextureScopesStatus>('/api/textures/scopes'),
  textures: (params: { scope?: TextureScope | 'all'; filter?: string } = {}) => {
    const u = new URLSearchParams()
    if (params.scope) u.set('scope', params.scope)
    if (params.filter) u.set('filter', params.filter)
    const q = u.toString()
    return j<TextureListResponse>(`/api/textures${q ? `?${q}` : ''}`)
  },
  stateLoad: (body: { scope: 'samples' | 'content'; slug: string }) =>
    j<{ model: McModel; slug: string; scope: 'samples' | 'content' }>(
      '/api/state/load',
      { method: 'POST', body: JSON.stringify(body) },
    ),
  stateSelection: (
    selectedFace: { elementIndex: number; face: string } | null,
  ) =>
    j<{ ok: true }>('/api/state/selection', {
      method: 'POST',
      body: JSON.stringify({ selectedFace }),
    }),
  paint: (body: {
    element_index: number
    face?: string
    faces?: string[]
    slug: string
  }) =>
    j<{ ok: true; model: McModel }>('/api/paint', {
      method: 'POST',
      body: JSON.stringify(body),
    }),
}
