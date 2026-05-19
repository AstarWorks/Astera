<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../api'
import type { LoadedWeapon, WeaponListEntry } from '../api'
import WeaponPreview from '../components/WeaponPreview.vue'
import { useI18n } from '../lib/i18n'

const list = ref<WeaponListEntry[]>([])
const selectedSlug = ref<string | null>(null)
const loaded = ref<LoadedWeapon | null>(null)
const error = ref<string | null>(null)
const loadingList = ref(false)
const loadingDetail = ref(false)

const { locales, currentLocale, setLocale } = useI18n()

async function loadList() {
  loadingList.value = true
  error.value = null
  try {
    list.value = await api.weapons()
    if (!selectedSlug.value && list.value.length > 0) {
      await pickWeapon(list.value[0].slug)
    }
  } catch (e) {
    error.value = `list load failed: ${(e as Error).message}`
  } finally {
    loadingList.value = false
  }
}

async function pickWeapon(slug: string) {
  if (selectedSlug.value === slug && loaded.value) return
  selectedSlug.value = slug
  loadingDetail.value = true
  error.value = null
  try {
    loaded.value = await api.loadWeapon(slug)
  } catch (e) {
    error.value = `weapon load failed: ${(e as Error).message}`
    loaded.value = null
  } finally {
    loadingDetail.value = false
  }
}

function archetypeIcon(a: string | null): string {
  switch (a?.toLowerCase()) {
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

function rarityColor(r: string | null): string {
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

onMounted(() => {
  loadList()
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <header class="sidebar-header">
        <h2>ウェポン</h2>
        <div class="header-actions">
          <select
            v-if="locales.length > 0"
            class="locale-select"
            :value="currentLocale"
            @change="setLocale(($event.target as HTMLSelectElement).value)"
            title="Locale for display_name_key / lore_key resolution"
          >
            <option v-for="loc in locales" :key="loc" :value="loc">{{ loc }}</option>
          </select>
          <button
            class="refresh"
            @click="loadList"
            :disabled="loadingList"
            title="Reload weapon list from content/weapons/"
          >
            {{ loadingList ? '…' : '↻' }}
          </button>
        </div>
      </header>
      <div v-if="error" class="error-banner">{{ error }}</div>
      <ul class="weapon-list">
        <li
          v-for="w in list"
          :key="w.slug"
          :class="{ active: selectedSlug === w.slug }"
          @click="pickWeapon(w.slug)"
        >
          <span class="entry-icon">{{ archetypeIcon(w.archetype) }}</span>
          <span class="entry-slug">{{ w.slug }}</span>
          <span
            v-if="w.rarity"
            class="entry-rarity"
            :style="{ background: rarityColor(w.rarity) }"
          >{{ w.rarity }}</span>
        </li>
        <li v-if="!loadingList && list.length === 0" class="empty">
          No weapons found in <code>content/weapons/</code>
        </li>
      </ul>
      <footer class="sidebar-footer">
        <div class="hint">
          Read-only preview (ADR-0061). Use <code>/astera reload weapons</code>
          in-game to hot-swap after editing YAML.
        </div>
      </footer>
    </aside>
    <main class="main">
      <div v-if="loadingDetail" class="loading">Loading weapon…</div>
      <WeaponPreview v-else :loaded="loaded" />
    </main>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  height: 100%;
  min-height: 0;
}
.sidebar {
  display: grid;
  grid-template-rows: auto auto 1fr auto;
  border-right: 1px solid #2a2a2f;
  background: #15151a;
  min-height: 0;
}
.sidebar-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid #2a2a2f;
}
.sidebar-header h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: #eee;
}
.header-actions {
  display: flex;
  gap: 6px;
  align-items: center;
}
.locale-select {
  background: #26262b;
  color: #d1d5db;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  padding: 4px 8px;
  cursor: pointer;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.locale-select:hover {
  background: #3b82f6;
  color: #fff;
}
.refresh {
  background: #26262b;
  color: #d1d5db;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  padding: 4px 10px;
  cursor: pointer;
  font-size: 14px;
}
.refresh:hover:not(:disabled) {
  background: #3b82f6;
  color: #fff;
}
.refresh:disabled {
  opacity: 0.5;
  cursor: default;
}
.error-banner {
  background: #7f1d1d;
  color: #fee2e2;
  padding: 8px 14px;
  font-size: 12px;
  border-bottom: 1px solid #2a2a2f;
}
.weapon-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  overflow-y: auto;
  min-height: 0;
}
.weapon-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  cursor: pointer;
  border-left: 3px solid transparent;
  transition: background 0.1s;
}
.weapon-list li:hover {
  background: #1f2937;
}
.weapon-list li.active {
  background: #1e3a8a;
  border-left-color: #3b82f6;
}
.weapon-list li.empty {
  cursor: default;
  color: #6b7280;
  font-size: 12px;
  font-style: italic;
}
.weapon-list li.empty:hover {
  background: transparent;
}
.entry-icon {
  font-size: 16px;
  flex-shrink: 0;
}
.entry-slug {
  flex: 1;
  color: #eee;
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.entry-rarity {
  font-size: 9px;
  padding: 2px 6px;
  border-radius: 3px;
  color: #fff;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.4px;
}
.sidebar-footer {
  padding: 10px 14px;
  border-top: 1px solid #2a2a2f;
  background: #0e0e10;
}
.hint {
  font-size: 11px;
  color: #6b7280;
  line-height: 1.5;
}
.hint code {
  background: #18181b;
  padding: 1px 4px;
  border-radius: 2px;
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  color: #d1d5db;
  font-size: 10px;
}
.main {
  min-height: 0;
  overflow: hidden;
}
.loading {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #6b7280;
  font-size: 14px;
}
</style>
