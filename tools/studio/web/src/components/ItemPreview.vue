<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { api } from '../api'
import type { LoadedItem } from '../api'
import type { McModel } from '../types'
import ThreeViewport from './ThreeViewport.vue'
import { useI18n } from '../lib/i18n'

const props = defineProps<{
  loaded: LoadedItem | null
}>()

const { currentLocale } = useI18n()

const model = ref<McModel | null>(null)
const modelError = ref<string | null>(null)
const loadingModel = ref(false)

watch(
  () => [props.loaded?.spec?.model, props.loaded?.modelArchetype] as const,
  async ([modelSlug, archetype], prev) => {
    const prevModelSlug = prev?.[0]
    const prevArchetype = prev?.[1]
    if (modelSlug === prevModelSlug && archetype === prevArchetype) return
    if (!modelSlug && !archetype) {
      model.value = null
      return
    }
    loadingModel.value = true
    modelError.value = null
    try {
      if (modelSlug) {
        model.value = await api.itemModelBySlug(modelSlug)
      } else if (archetype) {
        model.value = await api.itemModelByArchetype(archetype)
      }
    } catch (e) {
      modelError.value = `model load failed: ${(e as Error).message}`
      model.value = null
    } finally {
      loadingModel.value = false
    }
  },
  { immediate: true },
)

function rarityColor(r: string | undefined | null): string {
  switch (r?.toLowerCase()) {
    case 'common':
      return '#9ca3af'
    case 'uncommon':
      return '#22c55e'
    case 'rare':
      return '#3b82f6'
    case 'epic':
      return '#a855f7'
    case 'legendary':
      return '#f59e0b'
    default:
      return '#6b7280'
  }
}

function archetypeIcon(a: string): string {
  switch (a.toLowerCase()) {
    case 'sword':
      return '🗡️'
    case 'gun':
      return '🔫'
    case 'wand':
      return '🪄'
    case 'axe':
      return '🪓'
    case 'armor':
      return '🛡️'
    default:
      return '📦'
  }
}

function pickLocalized<T>(map: Record<string, T> | undefined): T | null {
  if (!map) return null
  const locale = currentLocale.value
  if (map[locale] !== undefined) return map[locale]
  const keys = Object.keys(map)
  if (keys.length === 0) return null
  return map[keys[0]]
}

const displayName = computed<string | null>(() => {
  return pickLocalized<string>(props.loaded?.spec?.name)
})

const displayLore = computed<string | null>(() => {
  const lore = pickLocalized<string[]>(props.loaded?.spec?.lore)
  if (!lore) return null
  return lore.join('\n')
})

const attributesList = computed<Array<{ key: string; value: number }>>(() => {
  const attrs = props.loaded?.spec?.attributes
  if (!attrs) return []
  const out: Array<{ key: string; value: number }> = []
  for (const [key, value] of Object.entries(attrs)) {
    if (value !== null && value !== undefined) {
      out.push({ key, value: value as number })
    }
  }
  return out
})

type FlagRow = { key: string; value: string }
const behaviorFlags = computed<FlagRow[]>(() => {
  const spec = props.loaded?.spec
  if (!spec) return []
  const rows: FlagRow[] = []
  if (spec.max_durability != null) rows.push({ key: 'max_durability', value: String(spec.max_durability) })
  if (spec.unbreakable === true) rows.push({ key: 'unbreakable', value: 'true' })
  if (spec.drop_on_death === false) rows.push({ key: 'drop_on_death', value: 'false' })
  if (spec.keep_on_death === true) rows.push({ key: 'keep_on_death', value: 'true' })
  if (spec.enchantable === false) rows.push({ key: 'enchantable', value: 'false' })
  if (spec.glint != null) rows.push({ key: 'glint', value: String(spec.glint) })
  return rows
})

const pdcEntries = computed<Array<[string, string]>>(() => {
  const tags = props.loaded?.spec?.pdc_tags
  if (!tags) return []
  return Object.entries(tags)
})
</script>

<template>
  <div v-if="!loaded" class="empty">
    <p>Select an item from the list to preview.</p>
  </div>
  <div v-else class="preview">
    <header class="header">
      <div class="title">
        <span class="icon">{{ archetypeIcon(loaded.archetypeHint) }}</span>
        <span class="slug">{{ loaded.slug }}</span>
      </div>
      <div class="chips">
        <span
          v-if="loaded.spec.rarity"
          class="chip"
          :style="{ background: rarityColor(loaded.spec.rarity) }"
        >{{ loaded.spec.rarity }}</span>
        <span class="chip neutral">{{ loaded.archetypeHint }}</span>
        <span
          v-if="loaded.modelSource === 'archetype' && loaded.modelFallback"
          class="chip warn"
          title="No item-specific or archetype model — falling back."
        >fallback model</span>
      </div>
    </header>

    <section class="viewport">
      <ThreeViewport
        :model="model"
        :vanilla-version="null"
        :selected-face="null"
      />
      <div v-if="loadingModel" class="banner">Loading model…</div>
      <div v-if="modelError" class="banner error">{{ modelError }}</div>
    </section>

    <section class="stats">
      <div v-if="displayName" class="row">
        <span class="label">Display name</span>
        <span class="value">{{ displayName }}</span>
      </div>
      <div v-if="displayLore" class="row">
        <span class="label">Lore</span>
        <pre class="lore-value">{{ displayLore }}</pre>
      </div>
      <div v-if="loaded.spec.material || loaded.spec.external_key" class="row">
        <span class="label">{{ loaded.spec.material ? 'Material' : 'External key' }}</span>
        <code>{{ loaded.spec.material ?? loaded.spec.external_key }}</code>
      </div>
      <div v-if="loaded.spec.custom_model_data != null" class="row">
        <span class="label">custom_model_data</span>
        <code>{{ loaded.spec.custom_model_data }}</code>
      </div>
      <div v-if="loaded.spec.model" class="row">
        <span class="label">Model</span>
        <span class="value">
          <code>{{ loaded.spec.model }}</code>
          <span class="chip neutral source-chip">{{ loaded.modelSource }}</span>
        </span>
      </div>
      <div v-if="loaded.spec.rarity" class="row">
        <span class="label">Rarity</span>
        <span class="value">{{ loaded.spec.rarity }}</span>
      </div>
      <div v-if="loaded.spec.level_requirement != null" class="row">
        <span class="label">Level requirement</span>
        <span class="value">{{ loaded.spec.level_requirement }}</span>
      </div>
      <div v-if="loaded.spec.equip_slot" class="row">
        <span class="label">Equip slot</span>
        <code>{{ loaded.spec.equip_slot }}</code>
      </div>
      <div v-if="attributesList.length > 0" class="row span-2">
        <span class="label">Attributes</span>
        <div class="attr-grid">
          <div v-for="a in attributesList" :key="a.key" class="attr-row">
            <span class="attr-key">{{ a.key }}</span>
            <strong class="attr-val">{{ a.value }}</strong>
          </div>
        </div>
      </div>
      <div v-if="behaviorFlags.length > 0" class="row span-2">
        <span class="label">Behavior</span>
        <div class="attr-grid">
          <div v-for="f in behaviorFlags" :key="f.key" class="attr-row">
            <span class="attr-key">{{ f.key }}</span>
            <strong class="attr-val">{{ f.value }}</strong>
          </div>
        </div>
      </div>
      <div v-if="loaded.spec.skills && loaded.spec.skills.length > 0" class="row span-2">
        <span class="label">Skills</span>
        <table class="skills-table">
          <thead>
            <tr>
              <th>trigger</th>
              <th>skill_id</th>
              <th>cooldown override</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(s, i) in loaded.spec.skills" :key="i">
              <td><code>{{ s.trigger }}</code></td>
              <td><code>{{ s.skill_id }}</code></td>
              <td>
                <span v-if="s.cooldown_override_ticks != null">
                  {{ s.cooldown_override_ticks }} ticks
                </span>
                <span v-else class="muted">—</span>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="pdcEntries.length > 0" class="row span-2">
        <span class="label">PDC tags</span>
        <pre class="pdc-block"><template v-for="([k, v], i) in pdcEntries" :key="k">{{ k }}: {{ v }}<br v-if="i < pdcEntries.length - 1" /></template></pre>
      </div>
    </section>
  </div>
</template>

<style scoped>
.empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #6b7280;
  font-size: 14px;
}
.preview {
  display: grid;
  grid-template-rows: auto 1fr auto;
  height: 100%;
  min-height: 0;
}
.header {
  padding: 12px 16px;
  border-bottom: 1px solid #2a2a2f;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  background: #18181b;
}
.title {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 16px;
  font-weight: 600;
}
.icon {
  font-size: 20px;
}
.slug {
  color: #eee;
}
.chips {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.chip {
  font-size: 11px;
  padding: 3px 8px;
  border-radius: 4px;
  color: #fff;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.chip.neutral {
  background: #374151;
}
.chip.warn {
  background: #b45309;
}
.source-chip {
  font-size: 9px;
  padding: 2px 6px;
}
.viewport {
  position: relative;
  min-height: 0;
  overflow: hidden;
}
.banner {
  position: absolute;
  top: 12px;
  left: 12px;
  background: #1f2937;
  padding: 6px 10px;
  border-radius: 4px;
  font-size: 12px;
  color: #d1d5db;
}
.banner.error {
  background: #7f1d1d;
  color: #fee2e2;
}
.stats {
  padding: 12px 16px;
  border-top: 1px solid #2a2a2f;
  background: #15151a;
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 24px;
  max-height: 40vh;
  overflow-y: auto;
}
.row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.row.span-2 {
  grid-column: 1 / -1;
}
.label {
  color: #6b7280;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.value {
  color: #eee;
  display: flex;
  align-items: center;
  gap: 8px;
}
.value strong {
  font-size: 14px;
  color: #f3f4f6;
}
.muted {
  color: #6b7280;
}
.lore-value {
  margin: 0;
  white-space: pre-wrap;
  color: #d1d5db;
  font-family: inherit;
  font-size: 12px;
  background: transparent;
  padding: 0;
}
code {
  background: #0e0e10;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  color: #d1d5db;
  display: inline-block;
}
.attr-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 4px 16px;
  margin-top: 4px;
}
.attr-row {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 12px;
  padding: 2px 0;
  border-bottom: 1px solid #1f1f24;
}
.attr-key {
  color: #9ca3af;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
}
.attr-val {
  color: #f3f4f6;
}
.skills-table {
  margin-top: 4px;
  width: 100%;
  border-collapse: collapse;
  font-size: 11px;
}
.skills-table th {
  text-align: left;
  color: #6b7280;
  font-weight: 500;
  padding: 4px 8px;
  border-bottom: 1px solid #2a2a2f;
  text-transform: uppercase;
  font-size: 10px;
  letter-spacing: 0.4px;
}
.skills-table td {
  padding: 4px 8px;
  border-bottom: 1px solid #1f1f24;
  color: #d1d5db;
}
.pdc-block {
  margin: 4px 0 0 0;
  background: #0e0e10;
  padding: 8px 10px;
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  color: #d1d5db;
  white-space: pre-wrap;
}
</style>
