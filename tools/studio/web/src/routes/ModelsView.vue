<script setup lang="ts">
import { ref, onMounted, computed, watch } from 'vue'
import { api } from '../api'
import type { ModelListEntry, TextureEntry } from '../api'
import type { SelectedFace } from '../types'
import { useStudioWs } from '../ws'
import ThreeViewport from '../components/ThreeViewport.vue'
import type { CameraSnapshot, ScreenshotPayload } from '../components/ThreeViewport.vue'
import TexturePalette from '../components/TexturePalette.vue'
import JsonInspector from '../components/JsonInspector.vue'
import SplitHandle from '../components/SplitHandle.vue'

const SIDEBAR_KEY = 'studio.models.sidebarW'
const PALETTE_KEY = 'studio.models.paletteW'
const JSON_KEY = 'studio.models.jsonW'

function loadW(key: string, fallback: number, min: number, max: number): number {
  const raw = localStorage.getItem(key)
  if (!raw) return fallback
  const v = Number(raw)
  if (!Number.isFinite(v)) return fallback
  return Math.max(min, Math.min(max, v))
}

const sidebarW = ref(loadW(SIDEBAR_KEY, 220, 160, 400))
const paletteW = ref(loadW(PALETTE_KEY, 320, 220, 600))
const jsonW = ref(loadW(JSON_KEY, 340, 200, 800))
watch(sidebarW, (v) => localStorage.setItem(SIDEBAR_KEY, String(v)))
watch(paletteW, (v) => localStorage.setItem(PALETTE_KEY, String(v)))
watch(jsonW, (v) => localStorage.setItem(JSON_KEY, String(v)))

const gridCols = computed(
  () => `${sidebarW.value}px 6px 1fr 6px ${paletteW.value}px 6px ${jsonW.value}px`,
)

const armedTexture = ref<TextureEntry | null>(null)

type CameraPreset = 'iso' | 'front' | 'top' | 'side' | 'item_gui'
const viewportRef = ref<{
  takeScreenshot: (p?: CameraPreset) => ScreenshotPayload
} | null>(null)

const list = ref<ModelListEntry[]>([])
const vanillaVersion = ref<string | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
const toast = ref<string | null>(null)
let toastTimer: ReturnType<typeof setTimeout> | null = null

function emptyCamera(view_id: string): CameraSnapshot {
  return {
    view_id,
    origin: [0, 0, 0],
    target: [0, 0, 0],
    up: [0, 1, 0],
    fov_deg: 45,
    width: 0,
    height: 0,
  }
}

const ws = useStudioWs({
  onScreenshotRequest(request_id, camera) {
    const preset = (camera ?? 'iso') as CameraPreset
    const result = viewportRef.value?.takeScreenshot(preset) ?? {
      png_base64: '',
      camera: emptyCamera(preset),
    }
    ws.send({
      type: 'screenshot_result',
      request_id,
      png_base64: result.png_base64,
      camera: result.camera,
    })
  },
})
const currentModel = ws.model
const currentSlug = ws.slug
const currentScope = ws.scope
const selectedFace = ws.selectedFace

async function loadList() {
  try {
    list.value = await api.models()
    // Give the WS snapshot a chance to land before deciding whether to
    // auto-load. Without this delay the snapshot may arrive a moment too late
    // and we clobber the server's current model with samples/sword.
    await new Promise<void>((resolve) => {
      if (currentModel.value) resolve()
      else {
        const t = setTimeout(resolve, 350)
        // Race with the first model_changed update.
        const stop = ws.connected.value
        if (stop) {
          // Already connected; snapshot may have arrived already. Short wait.
          setTimeout(resolve, 50)
          clearTimeout(t)
        }
      }
    })
    if (!currentModel.value && list.value[0]) {
      await pickModel(list.value[0])
    }
  } catch (e) {
    error.value = String(e)
  }
}

async function loadScopes() {
  try {
    const s = await api.textureScopes()
    vanillaVersion.value = s.vanilla_version
  } catch {
    vanillaVersion.value = null
  }
}

async function pickModel(entry: ModelListEntry) {
  loading.value = true
  error.value = null
  try {
    await api.stateLoad({ scope: entry.scope, slug: entry.slug })
    // The server pushes model_changed via WS; refs update reactively.
  } catch (e) {
    error.value = String(e)
  } finally {
    loading.value = false
  }
}

function showToast(msg: string) {
  toast.value = msg
  if (toastTimer) clearTimeout(toastTimer)
  toastTimer = setTimeout(() => {
    toast.value = null
  }, 2400)
}

async function applyPaint(entry: TextureEntry, face: SelectedFace) {
  try {
    await api.paint({
      element_index: face.elementIndex,
      face: face.face,
      slug: entry.slug,
    })
    showToast(
      `貼った: ${entry.slug.split('/').slice(-1)[0]} → ${face.face}`,
    )
  } catch (e) {
    showToast(`失敗: ${e}`)
  }
}

async function onApply(payload: {
  entry: TextureEntry
  target: SelectedFace | null
}) {
  // Face-first path: TexturePalette only emits 'apply' when a face is already
  // selected. The texture-first path (no face yet) sends 'update:armedTexture'
  // instead, which is handled below by armedTexture state + onFaceSelected.
  if (payload.target) {
    await applyPaint(payload.entry, payload.target)
  }
}

async function onFaceSelected(face: SelectedFace | null) {
  // If a texture is armed (texture-first flow), apply it immediately to the
  // newly selected face and clear the arm.
  if (face && armedTexture.value) {
    const armed = armedTexture.value
    armedTexture.value = null
    await applyPaint(armed, face)
  }
  // Echo to server so the CLI can read state.selectedFace.
  // WS will push selection_changed back; selectedFace ref updates from there.
  try {
    await api.stateSelection(face)
  } catch {
    // best effort — keep local pick state usable even if server is unhappy
  }
}

const modelHeader = computed(() => {
  if (!currentSlug.value) return '(モデル未選択)'
  const elements = currentModel.value?.elements?.length ?? 0
  const layers = Object.keys(currentModel.value?.textures ?? {}).length
  return `${currentSlug.value} — ${elements} cuboids, ${layers} layers`
})

onMounted(async () => {
  await Promise.all([loadList(), loadScopes()])
})
</script>

<template>
  <div class="layout" :style="{ gridTemplateColumns: gridCols }">
    <aside class="sidebar">
      <h3>モデル</h3>
      <div class="conn-line">
        <span :class="['dot', ws.connected.value ? 'on' : 'off']" />
        <span>{{ ws.connected.value ? 'WS 接続中' : 'WS 切断' }}</span>
      </div>
      <div v-if="list.length === 0" class="empty-list">
        モデルがまだありません。<br />
        <code>tools/model-gen/samples/</code> または
        <code>content/models/</code> に .model.json を置いてください。
      </div>
      <ul class="model-list">
        <li
          v-for="entry in list"
          :key="`${entry.scope}/${entry.slug}`"
          :class="{
            active:
              entry.slug === currentSlug && entry.scope === currentScope,
          }"
          @click="pickModel(entry)"
        >
          <span class="scope-badge" :data-scope="entry.scope">{{
            entry.scope === 'samples' ? 'sample' : 'content'
          }}</span>
          <span class="slug">{{ entry.slug }}</span>
        </li>
      </ul>
    </aside>

    <SplitHandle
      v-model="sidebarW"
      :min="160"
      :max="400"
      direction="right"
    />

    <section class="viewport-pane">
      <div v-if="error" class="banner error">⚠️ {{ error }}</div>
      <div v-if="loading" class="banner busy">⏳ 読み込み中...</div>
      <div v-if="toast" class="banner toast">{{ toast }}</div>
      <div class="viewport-header">{{ modelHeader }}</div>
      <ThreeViewport
        ref="viewportRef"
        :model="currentModel"
        :vanilla-version="vanillaVersion"
        :selected-face="selectedFace"
        @update:selected-face="onFaceSelected"
      />
    </section>

    <SplitHandle
      v-model="paletteW"
      :min="220"
      :max="600"
      direction="left"
    />

    <TexturePalette
      :selected-face="selectedFace"
      :armed-texture="armedTexture"
      @apply="onApply"
      @update:armed-texture="armedTexture = $event"
    />

    <SplitHandle
      v-model="jsonW"
      :min="200"
      :max="800"
      direction="left"
    />

    <section class="json-pane">
      <div class="json-header">
        <span>JSON (読み取り専用)</span>
        <span class="hint">AI / クリック貼付の結果を表示</span>
      </div>
      <JsonInspector :value="currentModel" empty-message="(モデル未選択)" />
    </section>
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  /* grid-template-columns is set inline from `gridCols` so it stays reactive. */
  height: 100%;
  min-height: 0;
}
.sidebar {
  background: #18181b;
  border-right: 1px solid #2a2a2f;
  padding: 14px;
  overflow-y: auto;
}
.sidebar h3 {
  margin: 0 0 8px;
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.conn-line {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 10px;
  color: #888;
  margin-bottom: 10px;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #555;
}
.dot.on {
  background: #22c55e;
  box-shadow: 0 0 6px #22c55e;
}
.dot.off {
  background: #ef4444;
}
.model-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.model-list li {
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #ddd;
  display: flex;
  align-items: center;
  gap: 8px;
}
.model-list li:hover {
  background: #26262b;
}
.model-list li.active {
  background: #3b82f6;
  color: white;
}
.scope-badge {
  font-size: 9px;
  padding: 1px 5px;
  border-radius: 999px;
  background: #2a2a2f;
  color: #aaa;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  flex: 0 0 auto;
}
.scope-badge[data-scope='samples'] {
  background: #422006;
  color: #fbbf24;
}
.scope-badge[data-scope='content'] {
  background: #064e3b;
  color: #6ee7b7;
}
.slug {
  font-family: ui-monospace, monospace;
  word-break: break-all;
}
.empty-list {
  color: #666;
  font-size: 12px;
  line-height: 1.5;
}
.viewport-pane {
  position: relative;
  overflow: hidden;
  background: #0e0e10;
  display: flex;
  flex-direction: column;
}
.viewport-header {
  padding: 6px 12px;
  background: #14141a;
  border-bottom: 1px solid #2a2a2f;
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: #888;
}
.viewport-pane :deep(.viewport) {
  flex: 1;
  min-height: 0;
}
.banner {
  position: absolute;
  top: 36px;
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 12px;
  z-index: 10;
  pointer-events: none;
}
.banner.error {
  background: #7f1d1d;
  color: #fee;
}
.banner.busy {
  background: #facc15;
  color: #000;
}
.banner.toast {
  background: #1f2937;
  color: #e5e7eb;
  border: 1px solid #374151;
}
.json-pane {
  background: #18181b;
  border-left: 1px solid #2a2a2f;
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.json-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  border-bottom: 1px solid #2a2a2f;
  font-size: 11px;
  color: #aaa;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.json-header .hint {
  text-transform: none;
  letter-spacing: 0;
  color: #666;
  font-style: italic;
}
</style>
