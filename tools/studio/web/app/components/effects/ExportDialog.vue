<script setup lang="ts">
/**
 * Modal: pick which artifacts to export, then trigger the client-side packer
 * and server write. Emits `done` with the server response or `cancel`.
 */
import { ref, computed } from 'vue'
import type { ExportFormat, FxGraph } from '#shared/effects/types'
import { packExport } from '~/utils/effects/export'

const props = defineProps<{
  graph: FxGraph
  initialBasename?: string
}>()

const emit = defineEmits<{
  (e: 'done', payload: { written: string[]; summon_command?: string }): void
  (e: 'cancel'): void
}>()

const basename = ref(props.initialBasename || props.graph.name || 'effect')

const vertical = ref(true)
const horizontal = ref(false)
const perFrame = ref(false)
const displayModel = ref(true)

const formats = computed<ExportFormat[]>(() => {
  const f: ExportFormat[] = []
  if (vertical.value) f.push('vertical_sheet')
  if (horizontal.value) f.push('horizontal_sheet')
  if (perFrame.value) f.push('frames')
  if (displayModel.value) f.push('item_display_model')
  return f
})

const busy = ref(false)
const error = ref<string | null>(null)

const slugInvalid = computed(() => !/^[A-Za-z0-9_\-/]+$/.test(basename.value))

async function onConfirm() {
  if (slugInvalid.value || formats.value.length === 0) return
  busy.value = true
  error.value = null
  try {
    const packed = await packExport(props.graph, basename.value, formats.value)
    const res = await fetch('/api/effects/export', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(packed),
    })
    if (!res.ok) {
      throw new Error(`${res.status}: ${await res.text()}`)
    }
    const result = (await res.json()) as {
      written: string[]
      summon_command?: string
    }
    emit('done', { written: result.written, summon_command: result.summon_command })
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
      <header>エクスポート</header>
      <div class="body">
        <label class="row">
          <span class="lbl">スラッグ</span>
          <input
            v-model="basename"
            type="text"
            placeholder="例: sparkle_ring"
            :class="{ invalid: slugInvalid }"
          />
        </label>
        <p class="hint">
          ファイル名: <code>{{ basename }}.png</code> / <code>{{ basename }}.fxgraph.json</code>
          など。スラッシュ <code>/</code> で階層可。
        </p>

        <fieldset>
          <legend>形式</legend>
          <label class="ck">
            <input type="checkbox" v-model="vertical" />
            <span>縦並びシート + .mcmeta (Vanilla MC flipbook)</span>
            <code class="path">content/textures/animations/{{ basename }}.png</code>
          </label>
          <label class="ck">
            <input type="checkbox" v-model="horizontal" />
            <span>横並びシート</span>
            <code class="path">content/textures/animations/{{ basename }}_horiz.png</code>
          </label>
          <label class="ck">
            <input type="checkbox" v-model="perFrame" />
            <span>フレーム別 PNG ({{ graph.frames }} 個)</span>
            <code class="path">content/textures/animations/{{ basename }}_NN.png</code>
          </label>
          <label class="ck">
            <input type="checkbox" v-model="displayModel" />
            <span>item_display モデル</span>
            <code class="path">content/models/effects/{{ basename }}.model.json</code>
          </label>
        </fieldset>

        <div v-if="error" class="error">⚠️ {{ error }}</div>
      </div>
      <footer>
        <button class="btn" @click="emit('cancel')" :disabled="busy">キャンセル</button>
        <button
          class="btn primary"
          @click="onConfirm"
          :disabled="busy || slugInvalid || formats.length === 0"
        >
          {{ busy ? '書き出し中…' : '保存' }}
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
  min-width: 480px;
  max-width: 560px;
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
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.body {
  padding: 16px 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
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
input[type='text'].invalid {
  border-color: #ef4444;
}
.hint {
  margin: 0;
  font-size: 11px;
  color: #888;
}
.hint code {
  font-family: ui-monospace, monospace;
}
fieldset {
  border: 1px solid #2a2a2f;
  border-radius: 6px;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
legend {
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  padding: 0 6px;
}
.ck {
  display: grid;
  grid-template-columns: 18px 1fr;
  gap: 8px;
  align-items: baseline;
  font-size: 12px;
  color: #ddd;
}
.ck input[type='checkbox'] {
  accent-color: #3b82f6;
}
.ck .path {
  grid-column: 2;
  font-family: ui-monospace, monospace;
  font-size: 10px;
  color: #666;
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
.btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn.primary {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
}
.btn.primary:hover:not(:disabled) { background: #2563eb; }
</style>
