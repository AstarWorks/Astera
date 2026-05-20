<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import type { Tile } from '#shared/types'

const props = defineProps<{
  tile: Tile
  defaultCategory?: string
  busy?: boolean
}>()
const emit = defineEmits<{
  accept: [slug: string, targetPath: string]
  reject: []
  regen: [refinedPrompt: string]
}>()

const category = ref(props.defaultCategory ?? 'items')
const basename = ref('')
const variant = ref('v01')
const refinedPrompt = ref('')

watch(
  () => props.tile.name,
  (name) => {
    const stem = name.replace(/\.png$/, '')
    const cleaned = stem.replace(/_r\d+_c\d+$/, '').replace(/_v\d+$/, '')
    basename.value = cleaned || 'asset'
  },
  { immediate: true },
)

const targetPath = computed(
  () => `${category.value}/${basename.value}_${variant.value}.png`,
)
const slug = computed(() => {
  const dotted = category.value.replace(/\//g, '.').replace(/\.+/g, '.')
  return `astera:${dotted}.${basename.value}.${variant.value}`
})
const cacheBust = computed(() => `${props.tile.path}?b=${props.tile.bytes}`)
const downloadName = computed(() => `${basename.value}_${variant.value}.png`)
</script>

<template>
  <div class="card">
    <div class="previews">
      <div class="preview-wrap preview-main">
        <img :src="cacheBust" alt="大プレビュー" />
        <div class="preview-label">拡大</div>
      </div>
      <div class="preview-wrap preview-ref">
        <img :src="cacheBust" alt="原寸" />
        <div class="preview-label">原寸 32px</div>
      </div>
    </div>
    <div class="src">ソース: {{ tile.name }} ({{ tile.bytes }} B)</div>

    <div class="field">
      <label>カテゴリ</label>
      <input v-model="category" placeholder="items/swords" />
    </div>
    <div class="field">
      <label>ベース名</label>
      <input v-model="basename" placeholder="longsword" />
    </div>
    <div class="field">
      <label>バリアント</label>
      <input v-model="variant" placeholder="v01" />
    </div>
    <div class="computed">
      <div><span class="k">パス:</span> content/textures/{{ targetPath }}</div>
      <div><span class="k">スラグ:</span> {{ slug }}</div>
    </div>

    <div class="field">
      <label>プロンプトを修正して再生成</label>
      <textarea
        v-model="refinedPrompt"
        rows="3"
        placeholder="例: 刃を短く、ガードを真鍮色、宝石なし"
      ></textarea>
    </div>

    <div class="actions">
      <button class="accept" :disabled="busy" @click="emit('accept', slug, targetPath)">
        採用
      </button>
      <button
        class="regen"
        :disabled="busy || !refinedPrompt.trim()"
        @click="emit('regen', refinedPrompt)"
      >
        再生成
      </button>
      <a class="dl" :href="cacheBust" :download="downloadName" :title="`${downloadName} で保存`">
        保存
      </a>
      <button class="drop" :disabled="busy" @click="emit('reject')">破棄</button>
    </div>
  </div>
</template>

<style scoped>
.card {
  background: #18181b;
  padding: 14px;
  border-radius: 6px;
  border: 1px solid #2a2a2f;
}
.previews {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  margin-bottom: 24px;
}
.preview-wrap {
  position: relative;
  aspect-ratio: 1 / 1;
  flex-shrink: 0;
  /* checkered transparency indicator */
  background-image: linear-gradient(45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(-45deg, #1a1a1d 25%, transparent 25%),
    linear-gradient(45deg, transparent 75%, #1a1a1d 75%),
    linear-gradient(-45deg, transparent 75%, #1a1a1d 75%);
  background-size: 12px 12px;
  background-position: 0 0, 0 6px, 6px -6px, -6px 0;
  background-color: #2a2a2f;
  border: 1px solid #3a3a40;
  border-radius: 4px;
}
.preview-main {
  flex: 1 1 auto;
  width: auto;
  max-width: 100%;
  /* aspect-ratio: 1/1 already on .preview-wrap; with flex:1 takes column width */
}
.preview-ref {
  width: 32px;
  height: 32px;
}
.preview-wrap img {
  display: block;
  width: 100%;
  height: 100%;
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  object-fit: contain;
}
.preview-label {
  position: absolute;
  bottom: -16px;
  left: 0;
  right: 0;
  font-size: 9px;
  color: #888;
  text-align: center;
  font-family: ui-monospace, monospace;
}
.src {
  font-size: 10px;
  color: #777;
  margin-bottom: 12px;
  font-family: ui-monospace, monospace;
}
.field {
  display: flex;
  flex-direction: column;
  margin-bottom: 6px;
}
.field label {
  font-size: 11px;
  color: #aaa;
  margin-bottom: 2px;
}
.field input,
.field textarea {
  background: #0a0a0c;
  color: #eee;
  border: 1px solid #333;
  padding: 5px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  border-radius: 3px;
}
.computed {
  background: #0a0a0c;
  padding: 8px;
  border-radius: 4px;
  font-size: 11px;
  margin: 8px 0;
  font-family: ui-monospace, monospace;
}
.computed .k {
  color: #facc15;
}
.actions {
  display: flex;
  gap: 6px;
  margin-top: 8px;
}
.actions button,
.actions .dl {
  flex: 1;
  padding: 9px;
  border: 0;
  border-radius: 4px;
  font-weight: 600;
  cursor: pointer;
  font-size: 13px;
  text-align: center;
  text-decoration: none;
  font-family: inherit;
  display: inline-block;
}
.actions button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.accept {
  background: #10b981;
  color: white;
}
.regen {
  background: #f59e0b;
  color: #000;
}
.dl {
  background: #6366f1;
  color: white;
  flex: 0 0 auto !important;
  padding: 9px 12px !important;
}
.dl:hover {
  background: #4f46e5;
}
.drop {
  background: #dc2626;
  color: white;
}
</style>
