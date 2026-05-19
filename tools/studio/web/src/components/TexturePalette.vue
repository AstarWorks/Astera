<script setup lang="ts">
import { ref, watch, computed, onMounted } from 'vue'
import { api } from '../api'
import type { TextureEntry, TextureScope, TextureScopesStatus } from '../api'
import type { SelectedFace } from '../types'

const props = defineProps<{
  selectedFace: SelectedFace | null
  armedTexture: TextureEntry | null
}>()

const emit = defineEmits<{
  apply: [{ entry: TextureEntry; target: SelectedFace | null }]
  'update:armedTexture': [entry: TextureEntry | null]
}>()

const activeScope = ref<TextureScope | 'all'>('vanilla')
const filterText = ref('')
const debounced = ref('')
const list = ref<TextureEntry[]>([])
const total = ref(0)
const truncated = ref(false)
const scopesStatus = ref<TextureScopesStatus | null>(null)
const loading = ref(false)
const error = ref<string | null>(null)

let filterTimer: ReturnType<typeof setTimeout> | null = null

watch(filterText, (v) => {
  if (filterTimer) clearTimeout(filterTimer)
  filterTimer = setTimeout(() => {
    debounced.value = v.trim()
  }, 200)
})

async function reload() {
  loading.value = true
  error.value = null
  try {
    const res = await api.textures({
      scope: activeScope.value,
      filter: debounced.value || undefined,
    })
    list.value = res.textures
    total.value = res.total
    truncated.value = res.truncated
  } catch (e) {
    error.value = String(e)
    list.value = []
    total.value = 0
    truncated.value = false
  } finally {
    loading.value = false
  }
}

watch([activeScope, debounced], reload)

// Default vanilla scope to block/ to filter out 16×32 entities / 16×96 animations
// etc. that look "squished" in square thumbs. User can clear the filter or pick
// another chip if they want the full vanilla set.
watch(
  activeScope,
  (s, prev) => {
    if (s === 'vanilla' && !filterText.value.trim()) {
      filterText.value = 'block/'
    }
    if (prev === 'vanilla' && s !== 'vanilla' && filterText.value === 'block/') {
      filterText.value = ''
    }
  },
  { immediate: true },
)

onMounted(async () => {
  try {
    scopesStatus.value = await api.textureScopes()
  } catch {
    // ignore — UI degrades gracefully
  }
  await reload()
})

const targetLabel = computed(() =>
  props.selectedFace
    ? `element[${props.selectedFace.elementIndex}].${props.selectedFace.face}`
    : '(面未選択)',
)

function onPick(entry: TextureEntry) {
  if (props.selectedFace) {
    emit('apply', {
      entry,
      target: { ...props.selectedFace },
    })
    return
  }
  // Texture-first: arm this texture; the next face click will apply it.
  const isSame =
    props.armedTexture &&
    props.armedTexture.scope === entry.scope &&
    props.armedTexture.slug === entry.slug
  emit('update:armedTexture', isSame ? null : entry)
}

const SCOPE_LABELS: Record<TextureScope | 'all', string> = {
  all: 'すべて',
  astera: 'Astera',
  vanilla: 'バニラ',
  content: 'モデル付随',
}

const QUICK_CHIPS: { label: string; filter: string }[] = [
  { label: 'ブロック', filter: 'block/*' },
  { label: '木材', filter: 'block/*planks' },
  { label: '鉱石', filter: 'block/*_block' },
  { label: '宝石', filter: 'block/{amethyst,diamond,emerald,lapis,redstone,gold,iron,copper,netherite}*' },
  { label: '石系', filter: 'block/{stone,deepslate,calcite,tuff,andesite,granite,diorite}*' },
  { label: 'アイテム', filter: 'item/*' },
  { label: '武器', filter: 'astera:items/swords*' },
]

function scopeShort(s: TextureScope): string {
  return s === 'astera' ? 'A' : s === 'vanilla' ? 'MC' : 'C'
}

function shortName(slug: string): string {
  // "minecraft:block/amethyst_block" → "amethyst_block"
  const afterColon = slug.includes(':') ? slug.slice(slug.indexOf(':') + 1) : slug
  return afterColon.split('/').slice(-1)[0] ?? slug
}
</script>

<template>
  <div class="palette">
    <header class="header">
      <div class="title">テクスチャ</div>
      <div class="target">
        <span class="tlabel">適用先:</span>
        <span class="tvalue" :data-empty="!selectedFace">{{ targetLabel }}</span>
      </div>
      <div v-if="armedTexture && !selectedFace" class="armed-row">
        <span class="armed-dot" />
        <span class="armed-label">
          装填: <code>{{ shortName(armedTexture.slug) }}</code>
          — 面をクリックで適用
        </span>
        <button class="armed-clear" @click="emit('update:armedTexture', null)">×</button>
      </div>
    </header>

    <nav class="scopes">
      <button
        v-for="s in (['astera', 'vanilla', 'content', 'all'] as const)"
        :key="s"
        :class="{ active: activeScope === s }"
        :disabled="
          !!(
            (s === 'vanilla' && scopesStatus && !scopesStatus.vanilla) ||
            (s === 'content' && scopesStatus && !scopesStatus.content)
          )
        "
        :title="
          s === 'vanilla' && scopesStatus && !scopesStatus.vanilla
            ? 'bun run fetch:vanilla を実行してください'
            : ''
        "
        @click="activeScope = s"
      >
        {{ SCOPE_LABELS[s] }}
      </button>
    </nav>

    <div class="filter">
      <input
        v-model="filterText"
        type="search"
        placeholder="block/amethyst* / sword / diamond..."
      />
      <div class="chips">
        <button
          v-for="chip in QUICK_CHIPS"
          :key="chip.label"
          class="chip"
          :title="chip.filter"
          @click="filterText = chip.filter"
        >{{ chip.label }}</button>
      </div>
    </div>

    <div v-if="loading" class="status">⏳ 読み込み中...</div>
    <div v-else-if="error" class="status error">⚠️ {{ error }}</div>
    <div v-else-if="list.length === 0" class="status muted">
      該当なし
      <template v-if="activeScope === 'vanilla' && scopesStatus && !scopesStatus.vanilla">
        <br />
        <code>bun run fetch:vanilla</code> でバニラ MC アセットを取得してください
      </template>
    </div>

    <div v-else class="grid">
      <button
        v-for="t in list"
        :key="`${t.scope}:${t.slug}`"
        class="tile"
        :class="{
          armed:
            armedTexture &&
            armedTexture.scope === t.scope &&
            armedTexture.slug === t.slug,
        }"
        :title="`${t.slug}\n${t.path}`"
        @click="onPick(t)"
      >
        <div class="thumb">
          <img
            :src="t.preview_url"
            :alt="t.slug"
            loading="lazy"
          />
        </div>
        <div class="label">
          <span class="ns" :data-scope="t.scope">{{ scopeShort(t.scope) }}</span>
          <span class="name">{{ shortName(t.slug) }}</span>
        </div>
      </button>
    </div>

    <footer class="footer">
      <span class="count">
        {{ list.length }}
        <template v-if="truncated"> / {{ total }} 件 (絞り込んでください)</template>
        <template v-else> 件</template>
      </span>
      <span v-if="scopesStatus?.vanilla_version" class="ver">
        vanilla {{ scopesStatus.vanilla_version }}
      </span>
    </footer>
  </div>
</template>

<style scoped>
.palette {
  display: flex;
  flex-direction: column;
  background: #18181b;
  border-left: 1px solid #2a2a2f;
  height: 100%;
  min-height: 0;
}
.header {
  padding: 10px 12px 6px;
  border-bottom: 1px solid #2a2a2f;
}
.title {
  font-size: 11px;
  color: #aaa;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  margin-bottom: 4px;
}
.target {
  font-size: 11px;
  color: #888;
  display: flex;
  gap: 6px;
  align-items: center;
}
.tlabel {
  color: #666;
}
.tvalue {
  color: #facc15;
  font-family: ui-monospace, monospace;
}
.tvalue[data-empty='true'] {
  color: #555;
  font-style: italic;
}
.armed-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  padding: 4px 6px;
  background: #422006;
  border: 1px solid #facc1555;
  border-radius: 3px;
  font-size: 10px;
  color: #fde68a;
}
.armed-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #facc15;
  box-shadow: 0 0 6px #facc15;
  flex: 0 0 auto;
}
.armed-label {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.armed-label code {
  background: rgba(0, 0, 0, 0.3);
  padding: 0 4px;
  border-radius: 2px;
  font-family: ui-monospace, monospace;
  color: #fef3c7;
}
.armed-clear {
  flex: 0 0 auto;
  background: transparent;
  border: 0;
  color: #fde68a;
  cursor: pointer;
  font-size: 12px;
  padding: 0 4px;
  line-height: 1;
}
.armed-clear:hover {
  color: white;
}
.scopes {
  display: flex;
  padding: 6px;
  gap: 2px;
  background: #111;
  border-bottom: 1px solid #2a2a2f;
}
.scopes button {
  flex: 1;
  padding: 4px 6px;
  border: 0;
  background: #222;
  color: #aaa;
  font-size: 11px;
  border-radius: 3px;
  cursor: pointer;
}
.scopes button:hover:not(:disabled) {
  background: #26262b;
  color: #eee;
}
.scopes button.active {
  background: #3b82f6;
  color: white;
  font-weight: 600;
}
.scopes button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.filter {
  padding: 6px 8px;
  border-bottom: 1px solid #2a2a2f;
}
.filter input {
  width: 100%;
  background: #111;
  color: #eee;
  border: 1px solid #333;
  padding: 4px 6px;
  font-family: ui-monospace, monospace;
  font-size: 11px;
  border-radius: 3px;
}
.status {
  padding: 16px;
  text-align: center;
  font-size: 12px;
  color: #888;
}
.status.error {
  color: #fca5a5;
}
.status.muted {
  color: #666;
}
.status code {
  background: #111;
  padding: 1px 5px;
  border-radius: 3px;
  color: #aaa;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
}
.chip {
  background: #2a2a2f;
  color: #c7c7d1;
  border: 0;
  border-radius: 99px;
  padding: 2px 8px;
  font-size: 10px;
  cursor: pointer;
}
.chip:hover {
  background: #3b3b42;
  color: white;
}
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
  padding: 10px;
  overflow-y: auto;
  flex: 1;
  align-content: start;
}
.tile {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  background: #0e0e10;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  font: inherit;
  color: inherit;
  transition: border-color 0.1s, transform 0.05s;
}
.tile:hover {
  border-color: #3b82f6;
  transform: scale(1.04);
}
.tile:active {
  transform: scale(0.97);
}
.tile.armed {
  border-color: #facc15;
  box-shadow: 0 0 0 2px #facc1533, 0 0 10px #facc1555;
}
.tile .thumb {
  width: 100%;
  aspect-ratio: 1;
  background: repeating-conic-gradient(#16161a 0 25%, #0e0e10 0 50%) 50% / 8px 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.tile .thumb img {
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  object-fit: contain;
  display: block;
}
.tile .label {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 4px 6px;
  background: #161618;
  border-top: 1px solid #2a2a2f;
  font-size: 10px;
  font-family: ui-monospace, monospace;
  overflow: hidden;
}
.tile .label .ns {
  flex: 0 0 auto;
  font-size: 8px;
  padding: 1px 4px;
  border-radius: 3px;
  background: #2a2a2f;
  color: #aaa;
  font-weight: 700;
  letter-spacing: 0.04em;
}
.tile .label .ns[data-scope='astera'] {
  background: #422006;
  color: #fbbf24;
}
.tile .label .ns[data-scope='vanilla'] {
  background: #0c4a6e;
  color: #7dd3fc;
}
.tile .label .ns[data-scope='content'] {
  background: #064e3b;
  color: #6ee7b7;
}
.tile .label .name {
  color: #ddd;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1 1 auto;
  min-width: 0;
}
.footer {
  padding: 6px 10px;
  font-size: 10px;
  color: #666;
  border-top: 1px solid #2a2a2f;
  display: flex;
  justify-content: space-between;
}
</style>
