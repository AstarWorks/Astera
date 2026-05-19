<script setup lang="ts">
import { ref, watch } from 'vue'
import { api } from '../api'
import type { LoadedWeapon } from '../api'
import type { McModel } from '../types'
import ThreeViewport from './ThreeViewport.vue'
import I18nText from './I18nText.vue'

const props = defineProps<{
  loaded: LoadedWeapon | null
}>()

const model = ref<McModel | null>(null)
const modelError = ref<string | null>(null)
const loadingModel = ref(false)

watch(
  () => props.loaded?.modelArchetype,
  async (archetype, prev) => {
    if (!archetype) {
      model.value = null
      return
    }
    if (archetype === prev) return
    loadingModel.value = true
    modelError.value = null
    try {
      model.value = await api.weaponModel(archetype)
    } catch (e) {
      modelError.value = `model load failed: ${(e as Error).message}`
      model.value = null
    } finally {
      loadingModel.value = false
    }
  },
  { immediate: true },
)

function attributeColor(attr: string): string {
  switch (attr.toLowerCase()) {
    case 'physical':
      return '#9ca3af'
    case 'fire':
      return '#f97316'
    case 'electric':
      return '#a855f7'
    case 'wind':
      return '#22d3ee'
    case 'water':
      return '#3b82f6'
    case 'earth':
      return '#84cc16'
    default:
      return '#6b7280'
  }
}

function rarityColor(r: string): string {
  switch (r.toLowerCase()) {
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
    default:
      return '⚔️'
  }
}
</script>

<template>
  <div v-if="!loaded" class="empty">
    <p>Select a weapon from the list to preview.</p>
  </div>
  <div v-else class="preview">
    <header class="header">
      <div class="title">
        <span class="icon">{{ archetypeIcon(loaded.archetype) }}</span>
        <span class="slug">{{ loaded.slug }}</span>
      </div>
      <div class="chips">
        <span
          class="chip"
          :style="{ background: rarityColor(loaded.spec.rarity) }"
        >{{ loaded.spec.rarity }}</span>
        <span class="chip neutral">{{ loaded.spec.archetype }}</span>
        <span
          v-if="loaded.modelFallback"
          class="chip warn"
          title="No archetype-specific model yet — falling back to sword. Phase 2 fix."
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
      <div class="row">
        <span class="label">Display name</span>
        <span class="value">
          <I18nText :key-name="loaded.spec.display_name_key" />
        </span>
        <code class="muted-key">{{ loaded.spec.display_name_key }}</code>
      </div>
      <div class="row">
        <span class="label">Lore</span>
        <span class="value">
          <I18nText :key-name="loaded.spec.lore_key" />
        </span>
        <code class="muted-key">{{ loaded.spec.lore_key }}</code>
      </div>
      <div class="row">
        <span class="label">Damage</span>
        <span class="value">
          <strong>{{ loaded.spec.damage.base }}</strong>
          <span
            class="attr-chip"
            :style="{ background: attributeColor(loaded.spec.damage.attribute) }"
          >{{ loaded.spec.damage.attribute }}</span>
        </span>
      </div>
      <div class="row">
        <span class="label">Cooldown</span>
        <span class="value">
          {{ loaded.spec.cooldown_ticks }} ticks
          <span class="muted">({{ (loaded.spec.cooldown_ticks / 20).toFixed(2) }}s @ 20 TPS)</span>
        </span>
      </div>
      <div class="row">
        <span class="label">Level requirement</span>
        <span class="value">{{ loaded.spec.level_requirement }}</span>
      </div>
      <div class="row">
        <span class="label">Item id</span>
        <code>{{ loaded.spec.item }}</code>
      </div>
      <div v-if="loaded.item" class="row">
        <span class="label">Item material</span>
        <code>{{ loaded.item.material ?? loaded.item.external_key ?? '(none)' }}</code>
      </div>
      <div v-if="loaded.itemError" class="row error">
        <span class="label">Item cross-ref</span>
        <span class="value">{{ loaded.itemError }}</span>
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
  max-height: 35vh;
  overflow-y: auto;
}
.row {
  display: flex;
  flex-direction: column;
  gap: 2px;
  font-size: 12px;
}
.row.error .value {
  color: #fca5a5;
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
code {
  background: #0e0e10;
  padding: 2px 6px;
  border-radius: 3px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 11px;
  color: #d1d5db;
  display: inline-block;
}
.attr-chip {
  font-size: 10px;
  padding: 2px 6px;
  border-radius: 3px;
  color: #fff;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
code.muted-key {
  background: transparent;
  padding: 0;
  color: #4b5563;
  font-size: 10px;
}
</style>
