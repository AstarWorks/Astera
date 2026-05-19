<script setup lang="ts">
/**
 * Schema-driven param editor. Renders one control per `ParamSpec` from the
 * node's schema entry, picking the right widget by `type`:
 *
 *   number   → slider + linked number input (min/max/step honored)
 *   integer  → slider + linked integer input
 *   color    → color picker + hex text input
 *   boolean  → checkbox
 *   select   → dropdown (options labels JP-aware)
 *   tuple2   → two number inputs side-by-side
 *   string   → text input
 *
 * If a node type lacks a schema entry, we fall back to raw type-inferred
 * inputs (the previous heuristic path) so adding a node before authoring its
 * schema still works.
 */
import { computed, ref, nextTick } from 'vue'
import type { FxNode, ParamValue, NodeType, GradientStop, CurveStop } from '../../effects/types'
import { SCHEMA, type ParamSpec } from '../../effects/schema'
import { api } from '../../api'
import { ensureLoaded, keyForAiImage } from '../../effects/image-cache'

const props = defineProps<{ node: FxNode }>()
const emit = defineEmits<{
  (e: 'update:node', n: FxNode): void
}>()

const spec = computed(() => SCHEMA[props.node.type as NodeType])

type Entry = {
  k: string
  v: ParamValue
  spec: ParamSpec | null
}

const entries = computed<Entry[]>(() => {
  const s = spec.value
  // If we have a schema, render params in schema order (more readable than
  // arbitrary insertion order from the JSON file).
  if (s) {
    const ordered: Entry[] = []
    for (const k of Object.keys(s.params)) {
      ordered.push({
        k,
        v: props.node.params[k] ?? (s.params[k].default as ParamValue),
        spec: s.params[k],
      })
    }
    // Plus any "extra" params not in the schema (e.g. AI-added experimental keys)
    for (const k of Object.keys(props.node.params)) {
      if (!(k in s.params)) {
        ordered.push({ k, v: props.node.params[k], spec: null })
      }
    }
    return ordered
  }
  // No schema entry — fall back to insertion order.
  return Object.entries(props.node.params).map(([k, v]) => ({
    k,
    v,
    spec: null,
  }))
})

function setParam(k: string, v: ParamValue) {
  emit('update:node', {
    ...props.node,
    params: { ...props.node.params, [k]: v },
  })
}

const aiBusy = ref(false)
const aiError = ref<string | null>(null)

async function generateAiImage() {
  if (aiBusy.value) return
  const prompt = String(props.node.params.prompt ?? '').trim()
  if (!prompt) {
    aiError.value = 'prompt が空です'
    return
  }
  const seed = Number(props.node.params.seed ?? 1)
  aiBusy.value = true
  aiError.value = null
  try {
    const res = await api.effectAiImage({ prompt, seed })
    // Preload into the cache so the next render picks it up immediately.
    await ensureLoaded(keyForAiImage(res.generated_path), res.generated_path)
    setParam('generated_path', res.generated_path)
  } catch (e) {
    aiError.value = String(e)
  } finally {
    aiBusy.value = false
  }
}

function onNumber(k: string, e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  if (Number.isFinite(v)) setParam(k, v)
}
function onInteger(k: string, e: Event) {
  const v = Math.round(Number((e.target as HTMLInputElement).value))
  if (Number.isFinite(v)) setParam(k, v)
}
function onColor(k: string, e: Event) {
  setParam(k, (e.target as HTMLInputElement).value)
}
function onBool(k: string, e: Event) {
  setParam(k, (e.target as HTMLInputElement).checked)
}
function onString(k: string, e: Event) {
  setParam(k, (e.target as HTMLInputElement).value)
}
function onSelect(k: string, e: Event) {
  setParam(k, (e.target as HTMLSelectElement).value)
}
function onTupleField(k: string, idx: 0 | 1, e: Event) {
  const v = Number((e.target as HTMLInputElement).value)
  if (!Number.isFinite(v)) return
  const cur = Array.isArray(props.node.params[k]) ? (props.node.params[k] as number[]) : [0, 0]
  const next = [...cur] as number[]
  next[idx] = v
  setParam(k, next)
}

function gradientStops(p: ParamValue): GradientStop[] {
  if (!Array.isArray(p)) return []
  const out: GradientStop[] = []
  for (const s of p) {
    if (s && typeof s === 'object' && 't' in s && 'color' in s) {
      const t = Number((s as { t: unknown }).t)
      const color = String((s as { color: unknown }).color)
      if (Number.isFinite(t) && color) out.push({ t, color })
    }
  }
  return out.sort((a, b) => a.t - b.t)
}

function gradientCssBg(stops: GradientStop[]): string {
  if (stops.length === 0) return 'linear-gradient(90deg, #444, #444)'
  const parts = stops.map((s) => `${s.color} ${(s.t * 100).toFixed(2)}%`)
  return `linear-gradient(90deg, ${parts.join(', ')})`
}

function curveStops(p: ParamValue): CurveStop[] {
  if (!Array.isArray(p)) return []
  const out: CurveStop[] = []
  for (const s of p) {
    if (s && typeof s === 'object' && 't' in s && 'v' in s) {
      const t = Number((s as { t: unknown }).t)
      const v = Number((s as { v: unknown }).v)
      if (Number.isFinite(t) && Number.isFinite(v)) out.push({ t, v })
    }
  }
  return out.sort((a, b) => a.t - b.t)
}

function curveSvgPath(stops: CurveStop[], vmin: number, vmax: number, w: number, h: number): string {
  if (stops.length === 0) return ''
  const span = vmax - vmin || 1
  const toX = (t: number) => t * w
  const toY = (v: number) => h - ((v - vmin) / span) * h
  let d = `M ${toX(stops[0].t).toFixed(2)} ${toY(stops[0].v).toFixed(2)}`
  for (let i = 1; i < stops.length; i++) {
    d += ` L ${toX(stops[i].t).toFixed(2)} ${toY(stops[i].v).toFixed(2)}`
  }
  return d
}

function onCurveJson(k: string, e: Event) {
  const raw = (e.target as HTMLTextAreaElement).value
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return
    const cleaned: CurveStop[] = []
    for (const s of parsed) {
      if (s && typeof s === 'object' && 't' in s && 'v' in s) {
        const t = Number(s.t)
        const v = Number(s.v)
        if (Number.isFinite(t) && Number.isFinite(v)) cleaned.push({ t, v })
      }
    }
    if (cleaned.length > 0) setParam(k, cleaned)
  } catch {
    // malformed JSON — leave param alone; user is mid-typing
  }
}

function onGradientJson(k: string, e: Event) {
  const raw = (e.target as HTMLTextAreaElement).value
  try {
    const parsed = JSON.parse(raw)
    if (!Array.isArray(parsed)) return
    const cleaned: GradientStop[] = []
    for (const s of parsed) {
      if (s && typeof s === 'object' && 't' in s && 'color' in s) {
        const t = Number(s.t)
        const color = String(s.color)
        if (Number.isFinite(t) && color) cleaned.push({ t, color })
      }
    }
    if (cleaned.length > 0) setParam(k, cleaned)
  } catch {
    // malformed JSON — leave param alone; user is mid-typing
  }
}

// ---------- Drag-stops editors ----------------------------------------------

/**
 * Per-param selected stop index. Keyed by param name so opening another node
 * resets cleanly when the param key changes.
 */
const selectedStop = ref<Record<string, number>>({})

/**
 * Active drag state. While a pointer is dragging a stop, we store the param
 * key, the index, and the bounding rect of the editor surface so pointermove
 * can compute the new t (and v, for curves) without re-querying the DOM.
 */
type DragState = {
  paramKey: string
  index: number
  rect: DOMRect
  axis: 'gradient' | 'curve'
  vmin: number
  vmax: number
  pointerId: number
  target: HTMLElement
}
const drag = ref<DragState | null>(null)

function clamp01(n: number): number {
  if (n < 0) return 0
  if (n > 1) return 1
  return n
}

function clampRange(n: number, lo: number, hi: number): number {
  if (n < lo) return lo
  if (n > hi) return hi
  return n
}

/** Sort + write a gradient stop list back to the node param. */
function commitGradient(k: string, stops: GradientStop[]) {
  const sorted = [...stops].sort((a, b) => a.t - b.t)
  setParam(k, sorted)
}

function commitCurve(k: string, stops: CurveStop[]) {
  const sorted = [...stops].sort((a, b) => a.t - b.t)
  setParam(k, sorted)
}

function selectStop(paramKey: string, index: number) {
  selectedStop.value = { ...selectedStop.value, [paramKey]: index }
}

function selectedIndex(paramKey: string): number {
  return selectedStop.value[paramKey] ?? -1
}

// ---- gradient ----

function gradientPointerDown(
  paramKey: string,
  index: number,
  e: PointerEvent,
) {
  // Right-click is handled by @contextmenu; ignore here.
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  const bar = (e.currentTarget as HTMLElement).parentElement
  if (!bar) return
  const rect = bar.getBoundingClientRect()
  selectStop(paramKey, index)
  const target = e.currentTarget as HTMLElement
  // Capture the pointer on the handle so we keep receiving move/up events even
  // when the cursor leaves the bar. We add window listeners as well because
  // pointer capture routes events to the capturing target, not the parent bar.
  target.setPointerCapture(e.pointerId)
  drag.value = {
    paramKey,
    index,
    rect,
    axis: 'gradient',
    vmin: 0,
    vmax: 1,
    pointerId: e.pointerId,
    target,
  }
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', onDragUp)
  window.addEventListener('pointercancel', onDragUp)
}

function onDragMove(e: PointerEvent) {
  const d = drag.value
  if (!d) return
  if (e.pointerId !== d.pointerId) return
  const tRaw = (e.clientX - d.rect.left) / d.rect.width
  const t = clamp01(tRaw)
  if (d.axis === 'gradient') {
    const cur = gradientStops(props.node.params[d.paramKey])
    if (d.index < 0 || d.index >= cur.length) return
    const next = cur.map((s, i) => (i === d.index ? { ...s, t } : s))
    // Track the moving stop's identity through re-sort so the selection stays
    // glued to the same color rather than to the slot index.
    const marker = next[d.index]
    const sorted = [...next].sort((a, b) => a.t - b.t)
    const newIndex = sorted.indexOf(marker)
    setParam(d.paramKey, sorted)
    drag.value = { ...d, index: newIndex }
    if (newIndex >= 0) selectStop(d.paramKey, newIndex)
  } else {
    const cur = curveStops(props.node.params[d.paramKey])
    if (d.index < 0 || d.index >= cur.length) return
    const yRaw = (e.clientY - d.rect.top) / d.rect.height
    const yClamped = clamp01(yRaw)
    // y = 0 (top) → vmax, y = 1 (bottom) → vmin.
    const v = d.vmax - yClamped * (d.vmax - d.vmin)
    const next = cur.map((s, i) =>
      i === d.index ? { t, v: clampRange(v, d.vmin, d.vmax) } : s,
    )
    const marker = next[d.index]
    const sorted = [...next].sort((a, b) => a.t - b.t)
    const newIndex = sorted.indexOf(marker)
    setParam(d.paramKey, sorted)
    drag.value = { ...d, index: newIndex }
    if (newIndex >= 0) selectStop(d.paramKey, newIndex)
  }
}

function onDragUp(e: PointerEvent) {
  const d = drag.value
  if (!d) return
  if (e.pointerId !== d.pointerId) return
  try {
    d.target.releasePointerCapture(d.pointerId)
  } catch {
    // already released
  }
  drag.value = null
  window.removeEventListener('pointermove', onDragMove)
  window.removeEventListener('pointerup', onDragUp)
  window.removeEventListener('pointercancel', onDragUp)
}

function addGradientStop(paramKey: string, current: GradientStop[]) {
  // Insert at the midpoint between the two largest-gap neighbors, copying the
  // left neighbor's color so the change is non-destructive visually.
  if (current.length === 0) {
    commitGradient(paramKey, [
      { t: 0, color: '#ffffff' },
      { t: 1, color: '#000000' },
    ])
    return
  }
  if (current.length === 1) {
    const only = current[0]
    commitGradient(paramKey, [only, { t: clamp01(only.t + 0.5), color: only.color }])
    return
  }
  let bestGap = -1
  let bestIdx = 0
  for (let i = 1; i < current.length; i++) {
    const gap = current[i].t - current[i - 1].t
    if (gap > bestGap) {
      bestGap = gap
      bestIdx = i
    }
  }
  const left = current[bestIdx - 1]
  const right = current[bestIdx]
  const inserted: GradientStop = {
    t: (left.t + right.t) / 2,
    color: left.color,
  }
  const next = [...current.slice(0, bestIdx), inserted, ...current.slice(bestIdx)]
  commitGradient(paramKey, next)
  selectStop(paramKey, bestIdx)
}

function removeGradientStop(paramKey: string, current: GradientStop[], index: number) {
  if (current.length <= 2) return
  const next = current.filter((_, i) => i !== index)
  commitGradient(paramKey, next)
  selectStop(paramKey, Math.max(0, index - 1))
}

function setGradientStopColor(
  paramKey: string,
  current: GradientStop[],
  index: number,
  color: string,
) {
  if (index < 0 || index >= current.length) return
  const next = current.map((s, i) => (i === index ? { ...s, color } : s))
  commitGradient(paramKey, next)
}

// Refs to color inputs so we can programmatically click them on stop double-click.
const colorInputs = ref<Record<string, HTMLInputElement | null>>({})
function registerColorInput(key: string, el: HTMLInputElement | null) {
  colorInputs.value[key] = el
}
async function openColorPicker(paramKey: string, index: number) {
  selectStop(paramKey, index)
  await nextTick()
  const el = colorInputs.value[`${paramKey}:${index}`]
  el?.click()
}

// ---- curve ----

function curvePointerDown(
  paramKey: string,
  index: number,
  vmin: number,
  vmax: number,
  e: PointerEvent,
) {
  if (e.button !== 0) return
  e.preventDefault()
  e.stopPropagation()
  const surface = (e.currentTarget as SVGElement).ownerSVGElement
  if (!surface) return
  const rect = surface.getBoundingClientRect()
  selectStop(paramKey, index)
  const target = e.currentTarget as unknown as HTMLElement
  target.setPointerCapture(e.pointerId)
  drag.value = {
    paramKey,
    index,
    rect,
    axis: 'curve',
    vmin,
    vmax,
    pointerId: e.pointerId,
    target,
  }
  window.addEventListener('pointermove', onDragMove)
  window.addEventListener('pointerup', onDragUp)
  window.addEventListener('pointercancel', onDragUp)
}

function addCurveStop(paramKey: string, current: CurveStop[]) {
  if (current.length === 0) {
    commitCurve(paramKey, [
      { t: 0, v: 0 },
      { t: 1, v: 0 },
    ])
    return
  }
  if (current.length === 1) {
    const only = current[0]
    commitCurve(paramKey, [only, { t: clamp01(only.t + 0.5), v: only.v }])
    return
  }
  let bestGap = -1
  let bestIdx = 0
  for (let i = 1; i < current.length; i++) {
    const gap = current[i].t - current[i - 1].t
    if (gap > bestGap) {
      bestGap = gap
      bestIdx = i
    }
  }
  const left = current[bestIdx - 1]
  const right = current[bestIdx]
  const inserted: CurveStop = {
    t: (left.t + right.t) / 2,
    v: (left.v + right.v) / 2,
  }
  const next = [...current.slice(0, bestIdx), inserted, ...current.slice(bestIdx)]
  commitCurve(paramKey, next)
  selectStop(paramKey, bestIdx)
}

function removeCurveStop(paramKey: string, current: CurveStop[], index: number) {
  if (current.length <= 2) return
  const next = current.filter((_, i) => i !== index)
  commitCurve(paramKey, next)
  selectStop(paramKey, Math.max(0, index - 1))
}

/** Heuristic for entries without a schema. */
function fallbackKind(k: string, v: ParamValue): 'number' | 'color' | 'boolean' | 'tuple' | 'string' {
  if (typeof v === 'boolean') return 'boolean'
  if (typeof v === 'number') return 'number'
  if (Array.isArray(v)) return 'tuple'
  if (typeof v === 'string') {
    if (k.toLowerCase().includes('color') && /^#[0-9a-fA-F]{3,8}$/.test(v)) return 'color'
    return 'string'
  }
  return 'string'
}
</script>

<template>
  <div class="inspector">
    <header class="hdr">
      <span class="id">{{ node.id }}</span>
      <span class="type">
        {{ spec?.label || node.type }}
        <span class="type-id">· {{ node.type }}</span>
      </span>
      <p v-if="spec?.description" class="node-desc">{{ spec.description }}</p>
    </header>
    <div v-if="node.type === 'ai.image'" class="ai-actions">
      <button
        class="ai-gen-btn"
        @click="generateAiImage"
        :disabled="aiBusy"
        :title="'Codex CLI を呼んで PNG を生成 (30〜60 秒)'"
      >
        {{ aiBusy ? '生成中… (30〜60秒)' : '🎨 生成' }}
      </button>
      <div v-if="aiError" class="ai-err">⚠️ {{ aiError }}</div>
      <div
        v-if="!aiError && node.params.generated_path"
        class="ai-ok"
      >
        ✓ <code>{{ node.params.generated_path }}</code>
      </div>
    </div>

    <div v-if="entries.length === 0" class="empty">
      この node にはパラメータがありません
    </div>
    <ul v-else class="params">
      <li v-for="p in entries" :key="p.k" class="param">
        <label class="lbl" :title="p.spec?.description">
          {{ p.spec?.label || p.k }}
        </label>

        <!-- schema: number with min/max → slider + number input -->
        <template v-if="p.spec && p.spec.type === 'number'">
          <div class="row-pair">
            <input
              type="range"
              :min="p.spec.min ?? -100"
              :max="p.spec.max ?? 100"
              :step="p.spec.step ?? 0.1"
              :value="p.v"
              @input="onNumber(p.k, $event)"
              class="slider"
            />
            <input
              type="number"
              :step="p.spec.step ?? 0.1"
              :value="p.v"
              @input="onNumber(p.k, $event)"
              class="num-box"
            />
          </div>
        </template>

        <!-- schema: integer → slider + int input -->
        <template v-else-if="p.spec && p.spec.type === 'integer'">
          <div class="row-pair">
            <input
              type="range"
              :min="p.spec.min ?? 0"
              :max="p.spec.max ?? 100"
              step="1"
              :value="p.v"
              @input="onInteger(p.k, $event)"
              class="slider"
            />
            <input
              type="number"
              step="1"
              :value="p.v"
              @input="onInteger(p.k, $event)"
              class="num-box"
            />
          </div>
        </template>

        <!-- schema: color -->
        <template v-else-if="p.spec && p.spec.type === 'color'">
          <div class="row-pair">
            <input
              type="color"
              :value="p.v"
              @input="onColor(p.k, $event)"
            />
            <input
              type="text"
              :value="p.v"
              @change="onString(p.k, $event)"
              class="hex-box"
            />
          </div>
        </template>

        <!-- schema: boolean -->
        <template v-else-if="p.spec && p.spec.type === 'boolean'">
          <input
            type="checkbox"
            :checked="!!p.v"
            @change="onBool(p.k, $event)"
          />
        </template>

        <!-- schema: select -->
        <template v-else-if="p.spec && p.spec.type === 'select'">
          <select :value="p.v" @change="onSelect(p.k, $event)">
            <option
              v-for="opt in p.spec.options"
              :key="opt.value"
              :value="opt.value"
            >
              {{ opt.label }}
            </option>
          </select>
        </template>

        <!-- schema: gradient -->
        <template v-else-if="p.spec && p.spec.type === 'gradient'">
          <div class="gradient-edit">
            <div
              class="gradient-bar"
              :style="{ background: gradientCssBg(gradientStops(p.v)) }"
              :title="'左=t0、右=t1 / ドラッグで位置、右クリックで削除、ダブルクリックで色変更'"
            >
              <div
                v-for="(s, i) in gradientStops(p.v)"
                :key="i"
                class="gradient-handle"
                :class="{ selected: selectedIndex(p.k) === i }"
                :style="{ left: (s.t * 100) + '%', background: s.color }"
                :title="`t=${s.t.toFixed(3)} ${s.color}`"
                @pointerdown="gradientPointerDown(p.k, i, $event)"
                @click.stop="selectStop(p.k, i)"
                @dblclick.stop="openColorPicker(p.k, i)"
                @contextmenu.prevent.stop="removeGradientStop(p.k, gradientStops(p.v), i)"
              ></div>
            </div>
            <div class="gradient-controls">
              <button
                type="button"
                class="stop-btn"
                @click="addGradientStop(p.k, gradientStops(p.v))"
              >＋ stop 追加</button>
              <button
                type="button"
                class="stop-btn"
                :disabled="gradientStops(p.v).length <= 2 || selectedIndex(p.k) < 0"
                @click="removeGradientStop(p.k, gradientStops(p.v), selectedIndex(p.k))"
              >− 選択を削除</button>
              <template v-for="(s, i) in gradientStops(p.v)" :key="`ci-${i}`">
                <input
                  type="color"
                  class="gradient-color-pick"
                  :value="s.color.length === 9 ? s.color.slice(0, 7) : s.color"
                  :ref="(el) => registerColorInput(`${p.k}:${i}`, el as HTMLInputElement | null)"
                  :style="{ display: selectedIndex(p.k) === i ? 'inline-block' : 'none' }"
                  @input="setGradientStopColor(p.k, gradientStops(p.v), i, ($event.target as HTMLInputElement).value)"
                />
              </template>
            </div>
            <details class="gradient-json-fold">
              <summary>JSON</summary>
              <textarea
                class="gradient-json"
                :value="JSON.stringify(gradientStops(p.v), null, 0)"
                @change="onGradientJson(p.k, $event)"
                spellcheck="false"
                rows="3"
              ></textarea>
              <p class="gradient-hint">
                JSON 配列で <code>{{ '{t:0..1, color:"#rrggbb[aa]"}' }}</code>。透明は <code>#00000000</code>。
              </p>
            </details>
          </div>
        </template>

        <!-- schema: curve -->
        <template v-else-if="p.spec && p.spec.type === 'curve'">
          <div class="curve-edit">
            <svg
              class="curve-preview"
              viewBox="0 0 200 80"
              preserveAspectRatio="none"
            >
              <rect x="0" y="0" width="200" height="80" fill="#0e0e10" />
              <!-- mid line at v=0 -->
              <line
                x1="0"
                :y1="80 - ((0 - (p.spec.vmin ?? -2)) / ((p.spec.vmax ?? 2) - (p.spec.vmin ?? -2))) * 80"
                x2="200"
                :y2="80 - ((0 - (p.spec.vmin ?? -2)) / ((p.spec.vmax ?? 2) - (p.spec.vmin ?? -2))) * 80"
                stroke="#2a2a2f"
                stroke-width="0.5"
                stroke-dasharray="2 2"
              />
              <path
                :d="curveSvgPath(curveStops(p.v), p.spec.vmin ?? -2, p.spec.vmax ?? 2, 200, 80)"
                stroke="#fbbf24"
                stroke-width="1.5"
                fill="none"
              />
              <!-- keyframe dots: draggable -->
              <circle
                v-for="(s, i) in curveStops(p.v)"
                :key="i"
                :cx="s.t * 200"
                :cy="80 - ((s.v - (p.spec.vmin ?? -2)) / ((p.spec.vmax ?? 2) - (p.spec.vmin ?? -2))) * 80"
                :r="selectedIndex(p.k) === i ? 5 : 4"
                :fill="selectedIndex(p.k) === i ? '#fde68a' : '#fbbf24'"
                :stroke="selectedIndex(p.k) === i ? '#fff' : 'none'"
                stroke-width="1"
                class="curve-handle"
                @pointerdown="curvePointerDown(p.k, i, p.spec.vmin ?? -2, p.spec.vmax ?? 2, $event)"
                @click.stop="selectStop(p.k, i)"
                @contextmenu.prevent.stop="removeCurveStop(p.k, curveStops(p.v), i)"
              />
              <!-- vmin/vmax labels -->
              <text x="3" y="10" class="curve-axis-label">{{ (p.spec.vmax ?? 2).toFixed(2) }}</text>
              <text x="3" y="76" class="curve-axis-label">{{ (p.spec.vmin ?? -2).toFixed(2) }}</text>
            </svg>
            <div class="gradient-controls">
              <button
                type="button"
                class="stop-btn"
                @click="addCurveStop(p.k, curveStops(p.v))"
              >＋ stop 追加</button>
              <button
                type="button"
                class="stop-btn"
                :disabled="curveStops(p.v).length <= 2 || selectedIndex(p.k) < 0"
                @click="removeCurveStop(p.k, curveStops(p.v), selectedIndex(p.k))"
              >− 選択を削除</button>
            </div>
            <details class="gradient-json-fold">
              <summary>JSON</summary>
              <textarea
                class="gradient-json"
                :value="JSON.stringify(curveStops(p.v), null, 0)"
                @change="onCurveJson(p.k, $event)"
                spellcheck="false"
                rows="3"
              ></textarea>
              <p class="gradient-hint">
                JSON 配列で <code>{{ '{t:0..1, v:number}' }}</code>。t は loop 進行、v は出力数値。
              </p>
            </details>
          </div>
        </template>

        <!-- schema: tuple2 -->
        <template v-else-if="p.spec && p.spec.type === 'tuple2'">
          <div class="row-pair">
            <input
              type="number"
              step="0.5"
              :min="p.spec.min"
              :max="p.spec.max"
              :value="Array.isArray(p.v) ? p.v[0] : 0"
              @input="onTupleField(p.k, 0, $event)"
              class="num-box"
            />
            <input
              type="number"
              step="0.5"
              :min="p.spec.min"
              :max="p.spec.max"
              :value="Array.isArray(p.v) ? p.v[1] : 0"
              @input="onTupleField(p.k, 1, $event)"
              class="num-box"
            />
          </div>
        </template>

        <!-- schema: string -->
        <template v-else-if="p.spec && p.spec.type === 'string'">
          <input
            type="text"
            :value="p.v"
            @change="onString(p.k, $event)"
          />
        </template>

        <!-- fallback: no schema; infer from value -->
        <template v-else>
          <input
            v-if="fallbackKind(p.k, p.v) === 'number'"
            type="number"
            step="any"
            :value="p.v"
            @input="onNumber(p.k, $event)"
          />
          <input
            v-else-if="fallbackKind(p.k, p.v) === 'color'"
            type="color"
            :value="p.v"
            @input="onColor(p.k, $event)"
          />
          <input
            v-else-if="fallbackKind(p.k, p.v) === 'boolean'"
            type="checkbox"
            :checked="!!p.v"
            @change="onBool(p.k, $event)"
          />
          <input
            v-else-if="fallbackKind(p.k, p.v) === 'tuple'"
            type="text"
            :value="Array.isArray(p.v) ? p.v.join(', ') : String(p.v)"
            @change="(e) => {
              const arr = ((e.target as HTMLInputElement).value).split(',').map((s) => Number(s.trim()))
              if (arr.every((n) => Number.isFinite(n))) setParam(p.k, arr)
            }"
          />
          <input
            v-else
            type="text"
            :value="p.v"
            @change="onString(p.k, $event)"
          />
        </template>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.inspector {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  height: 100%;
  overflow-y: auto;
}
.hdr {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding-bottom: 8px;
  border-bottom: 1px solid #2a2a2f;
}
.hdr .id {
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: #eee;
}
.hdr .type {
  font-size: 11px;
  color: #aaa;
}
.hdr .type-id {
  font-family: ui-monospace, monospace;
  color: #666;
  margin-left: 4px;
}
.node-desc {
  margin: 4px 0 0;
  font-size: 10px;
  color: #888;
  line-height: 1.5;
}
.empty {
  color: #666;
  font-size: 12px;
  font-style: italic;
}
.ai-actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 8px 10px;
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  border-radius: 4px;
}
.ai-gen-btn {
  background: #fb923c;
  border: 1px solid #fb923c;
  color: #0e0e10;
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
}
.ai-gen-btn:hover:not(:disabled) {
  background: #f97316;
}
.ai-gen-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.ai-err {
  font-size: 10px;
  color: #fee;
  background: #7f1d1d;
  padding: 4px 6px;
  border-radius: 3px;
}
.ai-ok {
  font-size: 10px;
  color: #6ee7b7;
}
.ai-ok code {
  font-family: ui-monospace, monospace;
  word-break: break-all;
}
.params {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.param {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.lbl {
  font-size: 10px;
  color: #aaa;
  text-transform: uppercase;
  letter-spacing: 0.05em;
  cursor: help;
}
.row-pair {
  display: flex;
  gap: 6px;
  align-items: center;
}
.slider {
  flex: 1;
  accent-color: #3b82f6;
}
.num-box {
  width: 70px;
}
.hex-box {
  width: 90px;
}
input[type='number'],
input[type='text'],
select {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 4px 6px;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  font-size: 11px;
  width: 100%;
}
input[type='number']:focus,
input[type='text']:focus,
select:focus {
  outline: none;
  border-color: #3b82f6;
}
input[type='color'] {
  width: 38px;
  height: 28px;
  border: 1px solid #2a2a2f;
  border-radius: 3px;
  background: #1f1f24;
  cursor: pointer;
  padding: 2px;
  flex: 0 0 auto;
}
input[type='checkbox'] {
  accent-color: #3b82f6;
  cursor: pointer;
}
.gradient-edit {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.gradient-bar {
  position: relative;
  height: 24px;
  border: 1px solid #2a2a2f;
  border-radius: 3px;
  touch-action: none;
  user-select: none;
}
.gradient-handle {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #0e0e10, 0 1px 3px rgba(0, 0, 0, 0.6);
  transform: translate(-50%, -50%);
  cursor: ew-resize;
  touch-action: none;
}
.gradient-handle.selected {
  box-shadow: 0 0 0 2px #3b82f6, 0 1px 3px rgba(0, 0, 0, 0.6);
  width: 16px;
  height: 16px;
}
.gradient-controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.stop-btn {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 3px 8px;
  border-radius: 3px;
  font-size: 10px;
  cursor: pointer;
}
.stop-btn:hover:not(:disabled) {
  background: #27272d;
  border-color: #3b82f6;
}
.stop-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.gradient-color-pick {
  width: 28px;
  height: 22px;
  padding: 1px;
}
.gradient-json-fold {
  font-size: 10px;
}
.gradient-json-fold summary {
  cursor: pointer;
  color: #888;
  padding: 2px 0;
  user-select: none;
}
.gradient-json-fold summary:hover {
  color: #ddd;
}
.gradient-json-fold[open] summary {
  margin-bottom: 4px;
}
.gradient-json {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 4px 6px;
  border-radius: 3px;
  font-family: ui-monospace, monospace;
  font-size: 10px;
  resize: vertical;
  min-height: 48px;
}
.gradient-json:focus {
  outline: none;
  border-color: #3b82f6;
}
.gradient-hint {
  margin: 0;
  font-size: 9px;
  color: #666;
  line-height: 1.4;
}
.gradient-hint code {
  background: #18181b;
  padding: 1px 4px;
  border-radius: 2px;
  color: #aaa;
}
.curve-edit {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.curve-preview {
  width: 100%;
  height: 80px;
  border: 1px solid #2a2a2f;
  border-radius: 3px;
  touch-action: none;
  user-select: none;
}
.curve-handle {
  cursor: move;
  touch-action: none;
}
.curve-axis-label {
  fill: #555;
  font-size: 8px;
  font-family: ui-monospace, monospace;
  pointer-events: none;
  user-select: none;
}
</style>
