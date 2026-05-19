<script setup lang="ts">
/**
 * Frame preview pane with 2D pixel canvas / 3D hologram billboard toggle.
 *
 *   [ 2D | 3D ]   ← view toggle
 *   ┌─────────────────────────────┐
 *   │ scaled-up canvas (×ZOOM)    │  ← v-show='2D'
 *   │ ‐or‐ Three.js scene         │  ← v-show='3D'
 *   ├─────────────────────────────┤
 *   │ ◀  ▶/⏸  ▶│  frame 7 / 16    │
 *   │ ─────●──────────────────── │  ← scrubber
 *   └─────────────────────────────┘
 *
 * Both modes render the SAME OffscreenCanvas every frame — 2D mode upscales
 * it with image-rendering:pixelated, 3D mode binds it as the CanvasTexture
 * on a face-camera quad (mocking Bukkit ItemDisplay/BillboardMode.CENTER).
 */
import { ref, computed, watchEffect, watch, onBeforeUnmount, onMounted } from 'vue'
import type { FxGraph } from '../../effects/types'
import { renderFrame } from '../../effects/render'
import { mountBillboard, type BillboardScene } from '../../effects/billboard'
import { warmupImages } from '../../effects/image-cache'

const props = defineProps<{
  graph: FxGraph
  /** Zoom factor for the visible preview canvas. Default 8 → 32×32 → 256×256. */
  zoom?: number
  /** Default view mode on mount. */
  initialView?: '2D' | '3D'
  /** When true (3D mode), show grid floor + wireframe player as scale reference. */
  showHelpers?: boolean
}>()

// Auto-scale: largest integer multiplier that keeps the upscaled canvas
// under ~256 px on its longer side. 32×32 → ×8 = 256, 64×64 → ×4 = 256,
// 128×128 → ×2 = 256. Override via the `zoom` prop if needed.
const zoom = computed(() => {
  if (props.zoom) return props.zoom
  const maxSide = Math.max(props.graph.size[0], props.graph.size[1])
  // Aim for a larger preview now that it's the dominant area — target ~400 px.
  return Math.max(1, Math.floor(400 / maxSide))
})
const frame = ref(0)
const playing = ref(false)
const renderError = ref<string | null>(null)
const viewMode = ref<'2D' | '3D'>(props.initialView ?? '2D')
const speed = ref<number>(1)
const SPEED_CHOICES = [0.25, 0.5, 1, 2, 4]

const visibleCanvas = ref<HTMLCanvasElement | null>(null)
const billboardHost = ref<HTMLElement | null>(null)

// Source OffscreenCanvas — the *real* fxgraph output. The 2D mode blits this
// to visibleCanvas with NEAREST upscale; the 3D mode binds it as a texture.
let sourceCanvas: OffscreenCanvas | null = null

// Persistent render state across frames — required for state-bearing nodes
// (filter.trail). Cleared when the graph identity changes.
const renderState = new Map<string, unknown>()

const totalFrames = computed(() => Math.max(1, props.graph.frames))
const fps = computed(() => 20 / props.graph.frame_time_ticks)

function clamp(n: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, n))
}

function doRender() {
  const [w, h] = props.graph.size
  try {
    sourceCanvas = renderFrame(props.graph, frame.value, renderState)
    renderError.value = null
  } catch (e) {
    renderError.value = String(e)
    return
  }

  // 2D mode — blit upscaled to the visible canvas
  const cvs = visibleCanvas.value
  if (cvs) {
    const z = zoom.value
    if (cvs.width !== w * z) cvs.width = w * z
    if (cvs.height !== h * z) cvs.height = h * z
    const g = cvs.getContext('2d')
    if (g) {
      g.imageSmoothingEnabled = false
      g.clearRect(0, 0, cvs.width, cvs.height)
      g.drawImage(sourceCanvas, 0, 0, w * z, h * z)
    }
  }

  // 3D mode — texture refresh + render
  if (scene && sourceCanvas) {
    scene.setSource(sourceCanvas)
    scene.render()
  }
}

watchEffect(
  () => {
    void props.graph
    void frame.value
    void zoom.value
    doRender()
  },
  { flush: 'post' },
)

// 3D scene (lazy mount — only when user flips to 3D)
let scene: BillboardScene | null = null
let resizeObserver: ResizeObserver | null = null

function mountSceneIfNeeded() {
  if (scene || !billboardHost.value || !sourceCanvas) return
  scene = mountBillboard(billboardHost.value, sourceCanvas)
  scene.setHelpers(!!props.showHelpers)
  resizeObserver = new ResizeObserver(() => {
    if (!billboardHost.value || !scene) return
    scene.resize(billboardHost.value.clientWidth, billboardHost.value.clientHeight)
    scene.render()
  })
  resizeObserver.observe(billboardHost.value)
  scene.render()
}

// Propagate helper-visibility changes from the parent.
watch(
  () => props.showHelpers,
  (v) => {
    if (scene) {
      scene.setHelpers(!!v)
      scene.render()
    }
  },
)

function setMode(m: '2D' | '3D') {
  viewMode.value = m
  if (m === '3D') {
    // Defer until the host is laid out — v-show keeps it in the DOM but width
    // can be 0 if it just unhid this tick.
    requestAnimationFrame(() => mountSceneIfNeeded())
  }
}

onMounted(() => {
  // Make sure the very first render produces sourceCanvas before user can
  // flip to 3D mode.
  doRender()
  // Kick off async image warmup; re-render once it resolves.
  void warmupImages(props.graph).then(() => doRender())
})

// Reset render state when the graph identity changes — trail buffers from
// the prior effect shouldn't bleed into a freshly-loaded one.
watch(
  () => props.graph.name,
  () => renderState.clear(),
)

// When the user edits a graph param that involves an image source (e.g.
// ai.image.generated_path after a "生成" click), warmup again.
watch(
  () => JSON.stringify(props.graph.nodes.filter((n) => n.type === 'ai.image' || n.type === 'image.import')),
  () => {
    void warmupImages(props.graph).then(() => doRender())
  },
)

// Playback loop
let rafId: number | null = null
let lastT = 0

function playStep(now: number) {
  if (!playing.value) return
  const msPerFrame = 1000 / (fps.value * speed.value)
  if (lastT === 0) lastT = now
  const delta = now - lastT
  if (delta >= msPerFrame) {
    const advance = Math.floor(delta / msPerFrame)
    frame.value = (frame.value + advance) % totalFrames.value
    lastT = now
  }
  rafId = requestAnimationFrame(playStep)
}

function togglePlay() {
  playing.value = !playing.value
  if (playing.value) {
    lastT = 0
    rafId = requestAnimationFrame(playStep)
  } else if (rafId !== null) {
    cancelAnimationFrame(rafId)
    rafId = null
  }
}

function stepBack() {
  frame.value = (frame.value - 1 + totalFrames.value) % totalFrames.value
}
function stepFwd() {
  frame.value = (frame.value + 1) % totalFrames.value
}
function onScrub(e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  frame.value = clamp(v, 0, totalFrames.value - 1)
}

onBeforeUnmount(() => {
  if (rafId !== null) cancelAnimationFrame(rafId)
  if (resizeObserver) resizeObserver.disconnect()
  if (scene) scene.dispose()
})
</script>

<template>
  <div class="preview">
    <div class="view-tabs">
      <button
        class="vt"
        :class="{ active: viewMode === '2D' }"
        @click="setMode('2D')"
      >2D</button>
      <button
        class="vt"
        :class="{ active: viewMode === '3D' }"
        @click="setMode('3D')"
        title="ホログラム (item_display billboard) のモック"
      >3D ホログラム</button>
    </div>
    <div class="canvas-wrap" v-show="viewMode === '2D'">
      <canvas ref="visibleCanvas" class="preview-canvas" />
    </div>
    <div class="billboard-wrap" v-show="viewMode === '3D'" ref="billboardHost"></div>
    <div v-if="renderError" class="render-error">⚠️ {{ renderError }}</div>
    <div class="timeline">
      <button class="tl-btn" @click="stepBack" title="前のフレーム">◀</button>
      <button class="tl-btn play" @click="togglePlay" :title="playing ? '一時停止' : '再生'">
        {{ playing ? '⏸' : '▶' }}
      </button>
      <button class="tl-btn" @click="stepFwd" title="次のフレーム">▶</button>
      <select
        v-model.number="speed"
        class="speed-sel"
        title="再生速度 (アニメーションのみ、frame_time_ticks は不変)"
      >
        <option v-for="s in SPEED_CHOICES" :key="s" :value="s">{{ s }}×</option>
      </select>
      <input
        type="range"
        :min="0"
        :max="totalFrames - 1"
        :value="frame"
        @input="onScrub"
        class="scrubber"
      />
      <span class="frame-label">
        {{ frame + 1 }} / {{ totalFrames }} ({{ (fps * speed).toFixed(1) }}fps)
      </span>
    </div>
  </div>
</template>

<style scoped>
.preview {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #0e0e10;
  min-height: 0;
}
.view-tabs {
  display: flex;
  gap: 2px;
  padding: 6px 10px;
  background: #18181b;
  border-bottom: 1px solid #2a2a2f;
  flex: 0 0 auto;
}
.vt {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #aaa;
  padding: 3px 10px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 11px;
}
.vt:hover { background: #26262b; color: #ddd; }
.vt.active {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
  font-weight: 600;
}
.canvas-wrap {
  flex: 1;
  display: grid;
  place-items: center;
  background:
    repeating-conic-gradient(#1a1a1d 0% 25%, #141416 0% 50%) 0 0 / 16px 16px;
  overflow: auto;
  padding: 16px;
  min-height: 0;
}
.billboard-wrap {
  flex: 1;
  min-height: 0;
  position: relative;
  background: #0e0e10;
}
.preview-canvas {
  image-rendering: pixelated;
  image-rendering: crisp-edges;
  background: transparent;
  border: 1px solid #2a2a2f;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
}
.render-error {
  position: absolute;
  background: #7f1d1d;
  color: #fee;
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 12px;
  margin-top: 16px;
  z-index: 5;
}
.timeline {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  background: #18181b;
  border-top: 1px solid #2a2a2f;
  flex: 0 0 auto;
}
.speed-sel {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 4px 6px;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  font-size: 11px;
  cursor: pointer;
  flex: 0 0 auto;
}
.speed-sel:focus {
  outline: none;
  border-color: #3b82f6;
}
.tl-btn {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 4px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 13px;
}
.tl-btn:hover { background: #26262b; }
.tl-btn.play {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
  min-width: 40px;
}
.tl-btn.play:hover { background: #2563eb; }
.scrubber {
  flex: 1;
  height: 4px;
  accent-color: #3b82f6;
}
.frame-label {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: #aaa;
  min-width: 130px;
  text-align: right;
}
</style>
