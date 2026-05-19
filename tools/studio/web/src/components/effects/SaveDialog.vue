<script setup lang="ts">
/**
 * Save-as-preset dialog. Writes the current fxgraph to
 * content/effects/<slug>.fxgraph.json via PUT /api/effects/save/:slug.
 * After success the parent refreshes the sidebar list so the new
 * [CONTENT] entry appears.
 */
import { ref, computed } from 'vue'
import { api } from '../../api'
import type { FxGraph } from '../../effects/types'

const props = defineProps<{
  graph: FxGraph
  initialSlug?: string
  /** Slugs that already exist in `content/` — used to warn on overwrite. */
  existingContentSlugs: string[]
}>()
const emit = defineEmits<{
  (e: 'done', slug: string): void
  (e: 'cancel'): void
}>()

const slug = ref(props.initialSlug ?? props.graph.name ?? 'my_effect')
const busy = ref(false)
const error = ref<string | null>(null)
const confirmOverwrite = ref(false)

const slugInvalid = computed(() => !/^[A-Za-z0-9_\-/]+$/.test(slug.value))
const willOverwrite = computed(
  () => props.existingContentSlugs.includes(slug.value),
)

async function onSave() {
  if (slugInvalid.value) {
    error.value = 'slug は英数字 / _ / - / / のみ'
    return
  }
  if (willOverwrite.value && !confirmOverwrite.value) {
    error.value = `${slug.value} は既に存在します。上書きするにはチェックを入れてください。`
    return
  }
  busy.value = true
  error.value = null
  try {
    // Send a graph with the user's chosen name baked in.
    const toWrite: FxGraph = { ...props.graph, name: slug.value }
    await api.effectSave(slug.value, toWrite)
    emit('done', slug.value)
  } catch (e) {
    error.value = String(e)
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="backdrop" @click.self="emit('cancel')">
    <div class="dialog" role="dialog" aria-modal="true">
      <header>💾 保存 (Save as preset)</header>
      <div class="body">
        <label class="row">
          <span class="lbl">スラッグ</span>
          <input
            v-model="slug"
            type="text"
            placeholder="例: my_glow"
            :class="{ invalid: slugInvalid }"
          />
        </label>
        <p class="hint">
          書き出し先: <code>content/effects/{{ slug }}.fxgraph.json</code><br />
          サイドバーに <span class="badge content">CONTENT</span> として表示されます。
        </p>

        <label v-if="willOverwrite" class="overwrite">
          <input type="checkbox" v-model="confirmOverwrite" />
          <span>
            ⚠️ <code>{{ slug }}</code> は既存 — 上書きを許可
          </span>
        </label>

        <div v-if="error" class="error">⚠️ {{ error }}</div>
      </div>
      <footer>
        <button class="btn" @click="emit('cancel')" :disabled="busy">キャンセル</button>
        <button
          class="btn primary"
          @click="onSave"
          :disabled="busy || slugInvalid"
        >
          {{ busy ? '保存中…' : '保存' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: grid;
  place-items: center;
  z-index: 100;
}
.dialog {
  background: #18181b;
  border: 1px solid #2a2a2f;
  border-radius: 8px;
  min-width: 420px;
  max-width: 520px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.6);
}
header {
  padding: 14px 18px;
  border-bottom: 1px solid #2a2a2f;
  font-size: 13px;
  font-weight: 600;
  color: #eee;
}
.body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.row {
  display: grid;
  grid-template-columns: 100px 1fr;
  align-items: center;
  gap: 8px;
}
.lbl {
  font-size: 12px;
  color: #aaa;
}
input[type='text'] {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 6px 10px;
  border-radius: 4px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
}
input[type='text'].invalid { border-color: #ef4444; }
.hint {
  margin: 0;
  font-size: 11px;
  color: #888;
  line-height: 1.6;
}
.hint code {
  font-family: ui-monospace, monospace;
  color: #ddd;
  background: #0e0e10;
  padding: 1px 5px;
  border-radius: 2px;
}
.badge.content {
  background: #064e3b;
  color: #6ee7b7;
  font-size: 9px;
  padding: 1px 6px;
  border-radius: 999px;
}
.overwrite {
  display: flex;
  gap: 8px;
  align-items: baseline;
  font-size: 11px;
  color: #fbbf24;
  padding: 8px 10px;
  background: #422006;
  border-radius: 4px;
}
.overwrite input[type='checkbox'] { accent-color: #fbbf24; }
.overwrite code {
  font-family: ui-monospace, monospace;
  background: #18181b;
  padding: 1px 4px;
  border-radius: 2px;
  color: #fde68a;
}
.error {
  background: #7f1d1d;
  color: #fee;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
}
footer {
  padding: 12px 18px;
  border-top: 1px solid #2a2a2f;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.btn {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 6px 14px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.btn:hover { background: #26262b; }
.btn:disabled { opacity: 0.5; cursor: not-allowed; }
.btn.primary {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
}
.btn.primary:hover:not(:disabled) { background: #2563eb; }
</style>
