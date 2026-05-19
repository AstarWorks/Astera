<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { api } from '../api'
import type { LoadedItem, ItemListEntry } from '../api'
import ItemPreview from '../components/ItemPreview.vue'
import { useI18n } from '../lib/i18n'

const list = ref<ItemListEntry[]>([])
const selectedSlug = ref<string | null>(null)
const loaded = ref<LoadedItem | null>(null)
const error = ref<string | null>(null)
const loadingList = ref(false)
const loadingDetail = ref(false)

const { locales, currentLocale, setLocale } = useI18n()

async function loadList() {
  loadingList.value = true
  error.value = null
  try {
    list.value = await api.items()
    if (!selectedSlug.value && list.value.length > 0) {
      await pickItem(list.value[0].slug)
    }
  } catch (e) {
    error.value = `list load failed: ${(e as Error).message}`
  } finally {
    loadingList.value = false
  }
}

async function pickItem(slug: string) {
  if (selectedSlug.value === slug && loaded.value) return
  selectedSlug.value = slug
  loadingDetail.value = true
  error.value = null
  try {
    loaded.value = await api.loadItem(slug)
  } catch (e) {
    error.value = `item load failed: ${(e as Error).message}`
    loaded.value = null
  } finally {
    loadingDetail.value = false
  }
}

function materialIcon(m: string | null): string {
  const mat = m?.toUpperCase() ?? ''
  if (mat.includes('CROSSBOW') || mat.includes('BOW')) return '🔫'
  if (mat.includes('BLAZE_ROD') || mat.includes('STICK')) return '🪄'
  if (mat.includes('SWORD')) return '🗡️'
  if (mat.includes('AXE')) return '🪓'
  if (mat.includes('HELMET') || mat.includes('CHESTPLATE') ||
      mat.includes('LEGGINGS') || mat.includes('BOOTS')) return '🛡️'
  return '📦'
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
        <h2>アイテム</h2>
        <div class="header-actions">
          <select
            v-if="locales.length > 0"
            class="locale-select"
            :value="currentLocale"
            @change="setLocale(($event.target as HTMLSelectElement).value)"
            title="Locale for name / lore resolution"
          >
            <option v-for="loc in locales" :key="loc" :value="loc">{{ loc }}</option>
          </select>
          <button
            class="refresh"
            @click="loadList"
            :disabled="loadingList"
            title="Reload item list from content/items/"
          >
            {{ loadingList ? '…' : '↻' }}
          </button>
        </div>
      </header>
      <div v-if="error" class="error-banner">{{ error }}</div>
      <ul class="item-list">
        <li
          v-for="it in list"
          :key="it.slug"
          :class="{ active: selectedSlug === it.slug }"
          @click="pickItem(it.slug)"
        >
          <span class="entry-icon">{{ materialIcon(it.material) }}</span>
          <span class="entry-slug">{{ it.slug }}</span>
          <span
            v-if="it.rarity"
            class="entry-rarity"
            :style="{ background: rarityColor(it.rarity) }"
          >{{ it.rarity }}</span>
        </li>
        <li v-if="!loadingList && list.length === 0" class="empty">
          No items found in <code>content/items/</code>
        </li>
      </ul>
      <footer class="sidebar-footer">
        <div class="hint">
          Read-only Studio preview (ADR-0073). Edit
          <code>content/items/*.yaml</code> and refresh.
        </div>
      </footer>
    </aside>
    <main class="main">
      <div v-if="loadingDetail" class="loading">Loading item…</div>
      <ItemPreview v-else :loaded="loaded" />
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
.item-list {
  list-style: none;
  margin: 0;
  padding: 4px 0;
  overflow-y: auto;
  min-height: 0;
}
.item-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  cursor: pointer;
  border-left: 3px solid transparent;
  transition: background 0.1s;
}
.item-list li:hover {
  background: #1f2937;
}
.item-list li.active {
  background: #1e3a8a;
  border-left-color: #3b82f6;
}
.item-list li.empty {
  cursor: default;
  color: #6b7280;
  font-size: 12px;
  font-style: italic;
}
.item-list li.empty:hover {
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
