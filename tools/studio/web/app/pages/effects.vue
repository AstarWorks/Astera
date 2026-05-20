<script setup lang="ts">
/**
 * Effects pane — preview-first layout.
 *
 *   ┌──────────┬─────────────────────────────────────────────────┐
 *   │ sidebar  │ toolbar [📐3D ref] [🔧編集] [⬇出力] [✨Claude]    │
 *   │  (effect ├─────────────────────────────────────────────────┤
 *   │   list)  │ minimap graph (read-only, ~80px)                │
 *   │          ├─────────────────────────────────────────────────┤
 *   │          │                                                 │
 *   │          │       PREVIEW (2D or 3D, big)                  │
 *   │          │                                                 │
 *   │          │       timeline + speed                         │
 *   │          └─────────────────────────────────────────────────┘
 *   └──────────┘
 *                                       ┌────────────────────────┐
 *                                       │ Inspect / Chat drawer  │
 *                                       │ (slides in from right) │
 *                                       └────────────────────────┘
 */
import { ref, onMounted, onBeforeUnmount, computed } from 'vue'
import { api } from '~/utils/api'
import type { EffectListEntry } from '~/utils/api'
import type { FxGraph, FxNode } from '#shared/effects/types'
import NodeGraph from '../components/effects/NodeGraph.vue'
import PreviewPanel from '../components/effects/PreviewPanel.vue'
import NodeInspector from '../components/effects/NodeInspector.vue'
import ExportDialog from '../components/effects/ExportDialog.vue'
import ChatPanel from '../components/effects/ChatPanel.vue'
import EditOverlay from '../components/effects/EditOverlay.vue'
import SaveDialog from '../components/effects/SaveDialog.vue'

const list = ref<EffectListEntry[]>([])
const selectedSlug = ref<string | null>(null)
const selectedScope = ref<'samples' | 'content'>('samples')
const graph = ref<FxGraph | null>(null)
const selectedNodeId = ref<string | null>(null)
const error = ref<string | null>(null)
const loading = ref(false)
const exportOpen = ref(false)
const exportToast = ref<{
  files: string[]
  summon?: string
} | null>(null)

// Drawer state: 'inspect' opens when a node is selected; 'chat' opens via
// the ✨ Claude button. null = drawer closed.
const drawerMode = ref<'inspect' | 'chat' | null>(null)
const editOverlayOpen = ref(false)
const showHelpers = ref(false)
const saveOpen = ref(false)

// ── Undo / Redo (Day 1) ───────────────────────────────────────────────
const HISTORY_MAX = 30
const historyPast = ref<FxGraph[]>([])
const historyFuture = ref<FxGraph[]>([])
let historyDebounceTimer: number | null = null

/** Vue reactive refs / proxies aren't `structuredClone`-friendly (non-cloneable
 *  internal slots). The fxgraph is plain JSON data, so JSON round-trip is the
 *  cheapest reliable deep clone. */
function deepCloneGraph(g: FxGraph): FxGraph {
  return JSON.parse(JSON.stringify(g))
}

/** Push current state into history. Debounced — fast-fire param tweaks
 *  shouldn't create 50 history entries; cluster them into one. */
function pushHistory(current: FxGraph) {
  if (historyDebounceTimer !== null) {
    window.clearTimeout(historyDebounceTimer)
  }
  historyDebounceTimer = window.setTimeout(() => {
    historyDebounceTimer = null
    historyPast.value.push(deepCloneGraph(current))
    if (historyPast.value.length > HISTORY_MAX) historyPast.value.shift()
    // Any new edit invalidates the redo stack.
    historyFuture.value = []
  }, 250) as unknown as number
}

function undo() {
  if (historyPast.value.length === 0 || !graph.value) return
  const prev = historyPast.value.pop()!
  historyFuture.value.push(deepCloneGraph(graph.value))
  if (historyFuture.value.length > HISTORY_MAX) historyFuture.value.shift()
  graph.value = prev
}

function redo() {
  if (historyFuture.value.length === 0 || !graph.value) return
  const next = historyFuture.value.pop()!
  historyPast.value.push(deepCloneGraph(graph.value))
  if (historyPast.value.length > HISTORY_MAX) historyPast.value.shift()
  graph.value = next
}

function onKeyDown(e: KeyboardEvent) {
  const cmd = e.metaKey || e.ctrlKey
  if (!cmd) return
  if (e.key === 'z' && !e.shiftKey) {
    e.preventDefault()
    undo()
  } else if ((e.key === 'z' && e.shiftKey) || e.key === 'y') {
    e.preventDefault()
    redo()
  }
}

async function loadList() {
  try {
    list.value = await api.effects()
    const first = list.value[0]
    if (first) await pickEffect(first)
  } catch (e) {
    error.value = String(e)
  }
}

async function pickEffect(entry: EffectListEntry) {
  selectedSlug.value = entry.slug
  selectedScope.value = entry.scope
  loading.value = true
  error.value = null
  selectedNodeId.value = null
  drawerMode.value = null
  // Loading a new graph resets history — past edits aren't undoable across
  // graph switches.
  historyPast.value = []
  historyFuture.value = []
  try {
    const res = await api.loadEffect(entry.scope, entry.slug)
    graph.value = res.graph
  } catch (e) {
    error.value = String(e)
    graph.value = null
  } finally {
    loading.value = false
  }
}

function onGraphUpdate(g: FxGraph) {
  if (graph.value) pushHistory(graph.value)
  graph.value = g
}

function onNodeUpdate(updated: FxNode) {
  if (!graph.value) return
  pushHistory(graph.value)
  graph.value = {
    ...graph.value,
    nodes: graph.value.nodes.map((n) =>
      n.id === updated.id ? updated : n,
    ),
  }
}

const selectedNode = computed(() => {
  if (!graph.value || !selectedNodeId.value) return null
  return graph.value.nodes.find((n) => n.id === selectedNodeId.value) ?? null
})

// Sidebar grouping (Track A-3): split samples vs content for readability.
const samplesList = computed(() => list.value.filter((e) => e.scope === 'samples'))
const contentList = computed(() => list.value.filter((e) => e.scope === 'content'))
const contentSlugs = computed(() => contentList.value.map((e) => e.slug))

function onSaveDone(savedSlug: string) {
  saveOpen.value = false
  exportToast.value = {
    files: [`content/effects/${savedSlug}.fxgraph.json`],
    summon: undefined,
  }
  void loadList()
}

function onMinimapSelect(id: string | null) {
  selectedNodeId.value = id
  if (id) drawerMode.value = 'inspect'
  // If clicking blank pane (id === null), don't auto-close the drawer —
  // the user explicitly toggling × is more predictable.
}

function openChat() {
  drawerMode.value = 'chat'
}

function closeDrawer() {
  drawerMode.value = null
}

function onExportDone(payload: { written: string[]; summon_command?: string }) {
  exportOpen.value = false
  exportToast.value = { files: payload.written, summon: payload.summon_command }
  void loadList()
}

function copySummon() {
  if (!exportToast.value?.summon) return
  void navigator.clipboard.writeText(exportToast.value.summon)
}

function dismissToast() {
  exportToast.value = null
}

onMounted(() => {
  void loadList()
  document.addEventListener('keydown', onKeyDown)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeyDown)
})
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <h3>エフェクト</h3>
      <div v-if="list.length === 0" class="empty-list">
        エフェクトがまだありません。<br />
        <code>tools/studio/web/samples/effects/</code> または
        <code>content/effects/</code> に <code>.fxgraph.json</code> を置いてください。
      </div>

      <template v-if="contentList.length > 0">
        <h4 class="sb-group">📁 完成 ({{ contentList.length }})</h4>
        <ul class="effect-list">
          <li
            v-for="entry in contentList"
            :key="`${entry.scope}/${entry.slug}`"
            :class="{
              active:
                entry.slug === selectedSlug && entry.scope === selectedScope,
            }"
            @click="pickEffect(entry)"
          >
            <span class="scope-badge" data-scope="content">content</span>
            <span class="slug">{{ entry.slug }}</span>
          </li>
        </ul>
      </template>

      <h4 class="sb-group" :class="{ 'sb-group-mt': contentList.length > 0 }">
        🎨 サンプル ({{ samplesList.length }})
      </h4>
      <ul class="effect-list">
        <li
          v-for="entry in samplesList"
          :key="`${entry.scope}/${entry.slug}`"
          :class="{
            active:
              entry.slug === selectedSlug && entry.scope === selectedScope,
          }"
          @click="pickEffect(entry)"
        >
          <span class="scope-badge" data-scope="samples">sample</span>
          <span class="slug">{{ entry.slug }}</span>
        </li>
      </ul>
    </aside>

    <section class="main-pane">
      <div v-if="error" class="error">⚠️ {{ error }}</div>
      <div v-if="loading" class="busy">⏳ 読み込み中...</div>

      <div v-if="exportToast" class="toast-card">
        <div class="toast-head">
          <span class="toast-ok">✓ {{ exportToast.files.length }} ファイル保存</span>
          <button class="toast-x" @click="dismissToast">×</button>
        </div>
        <ul class="toast-files">
          <li v-for="f in exportToast.files.slice(0, 4)" :key="f"><code>{{ f }}</code></li>
          <li v-if="exportToast.files.length > 4" class="more">他 {{ exportToast.files.length - 4 }} ファイル…</li>
        </ul>
        <div v-if="exportToast.summon" class="toast-summon">
          <span class="lbl">召喚コマンド:</span>
          <code class="cmd">{{ exportToast.summon }}</code>
          <button class="copy-btn" @click="copySummon" title="クリップボードへコピー">📋 コピー</button>
        </div>
      </div>

      <div class="toolbar">
        <span class="tb-title">{{ graph ? graph.name : '(エフェクト未選択)' }}</span>
        <span class="tb-meta" v-if="graph">
          {{ graph.size[0] }}×{{ graph.size[1] }} · {{ graph.frames }}frames · {{ graph.frame_time_ticks }}t/frame
        </span>
        <span class="spacer"></span>
        <button
          class="tb-btn"
          :class="{ active: showHelpers }"
          :disabled="!graph"
          @click="showHelpers = !showHelpers"
          title="3D モード時のグリッド + プレイヤーシルエットを表示/非表示"
        >📐 3D ref</button>
        <button
          class="tb-btn"
          :disabled="!graph"
          @click="editOverlayOpen = true"
          title="フルノードグラフエディタを開く (ドラッグ+ドロップ+接続編集)"
        >🔧 編集</button>
        <button
          class="tb-btn"
          :disabled="!graph"
          @click="saveOpen = true"
          title="現在の fxgraph を content/effects/ に保存 (CONTENT として再利用)"
        >💾 保存</button>
        <button
          class="tb-btn primary"
          :disabled="!graph"
          @click="exportOpen = true"
        >⬇ エクスポート…</button>
        <button
          class="tb-btn"
          :class="{ active: drawerMode === 'chat' }"
          :disabled="!graph"
          @click="drawerMode === 'chat' ? closeDrawer() : openChat()"
          title="Claude チャットを開く"
        >✨ Claude</button>
      </div>

      <div class="minimap-wrap">
        <NodeGraph
          v-if="graph"
          :graph="graph"
          :interactive="false"
          @select-node="onMinimapSelect"
        />
        <div v-else class="placeholder small">
          エフェクトを選択してください
        </div>
      </div>

      <div class="preview-wrap">
        <PreviewPanel
          v-if="graph"
          :graph="graph"
          :show-helpers="showHelpers"
        />
        <div v-else class="placeholder">プレビュー待機中</div>
      </div>
    </section>

    <!-- Right drawer: Inspect (auto) or Chat (manual) -->
    <aside v-if="drawerMode" class="drawer">
      <header class="drawer-head">
        <div class="drawer-tabs">
          <button
            class="dt"
            :class="{ active: drawerMode === 'inspect' }"
            :disabled="!selectedNode"
            @click="drawerMode = 'inspect'"
          >
            Inspect{{ selectedNode ? `: ${selectedNode.id}` : '' }}
          </button>
          <button
            class="dt"
            :class="{ active: drawerMode === 'chat' }"
            @click="drawerMode = 'chat'"
          >
            ✨ Claude
          </button>
        </div>
        <button class="drawer-close" @click="closeDrawer" title="閉じる (ESC)">×</button>
      </header>
      <div class="drawer-body">
        <NodeInspector
          v-if="drawerMode === 'inspect' && selectedNode"
          :node="selectedNode"
          @update:node="onNodeUpdate"
        />
        <div v-else-if="drawerMode === 'inspect'" class="empty-drawer">
          minimap でノードをクリックして選択してください
        </div>
        <ChatPanel
          v-if="drawerMode === 'chat' && graph"
          :graph="graph"
          @update:graph="onGraphUpdate"
        />
      </div>
    </aside>

    <!-- Modal overlays -->
    <ExportDialog
      v-if="exportOpen && graph"
      :graph="graph"
      :initial-basename="selectedSlug || graph.name"
      @cancel="exportOpen = false"
      @done="onExportDone"
    />

    <EditOverlay
      v-if="editOverlayOpen && graph"
      :graph="graph"
      :initial-selected-id="selectedNodeId"
      @update:graph="onGraphUpdate"
      @close="editOverlayOpen = false"
    />

    <SaveDialog
      v-if="saveOpen && graph"
      :graph="graph"
      :initial-slug="selectedSlug || graph.name"
      :existing-content-slugs="contentSlugs"
      @cancel="saveOpen = false"
      @done="onSaveDone"
    />
  </div>
</template>

<style scoped>
.layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  height: 100%;
  min-height: 0;
  position: relative;
}

/* ── Sidebar (slim, effect list only) ─────────────────────────── */
.sidebar {
  background: #18181b;
  border-right: 1px solid #2a2a2f;
  overflow-y: auto;
  padding: 14px 0;
}
.sidebar h3 {
  margin: 0 14px 8px;
  font-size: 11px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.sb-group {
  margin: 0 14px 4px;
  font-size: 10px;
  color: #6ee7b7;
  font-weight: 600;
  letter-spacing: 0.05em;
}
.sb-group-mt {
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #2a2a2f;
  color: #fbbf24;
}
.effect-list + .sb-group { margin-top: 12px; }
.effect-list {
  list-style: none;
  margin: 0 14px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.effect-list li {
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  color: #ddd;
  display: flex;
  align-items: center;
  gap: 8px;
}
.effect-list li:hover { background: #26262b; }
.effect-list li.active { background: #3b82f6; color: white; }
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
.scope-badge[data-scope='samples'] { background: #422006; color: #fbbf24; }
.scope-badge[data-scope='content'] { background: #064e3b; color: #6ee7b7; }
.slug { font-family: ui-monospace, monospace; word-break: break-all; }
.empty-list {
  margin: 0 14px;
  color: #666;
  font-size: 12px;
  line-height: 1.5;
}

/* ── Main pane (preview-first vertical stack) ─────────────────── */
.main-pane {
  display: grid;
  grid-template-rows: auto 90px 1fr;
  background: #0e0e10;
  min-width: 0;
  min-height: 0;
  position: relative;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  background: #18181b;
  border-bottom: 1px solid #2a2a2f;
}
.tb-title {
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: #eee;
}
.tb-meta {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: #888;
}
.spacer { flex: 1; }
.tb-btn {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 5px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
}
.tb-btn:hover:not(:disabled) { background: #26262b; }
.tb-btn:disabled { opacity: 0.4; cursor: not-allowed; }
.tb-btn.active {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
}
.tb-btn.primary {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
  font-weight: 600;
}
.tb-btn.primary:hover:not(:disabled) { background: #2563eb; }

/* ── Minimap (compact read-only graph) ────────────────────────── */
.minimap-wrap {
  position: relative;
  background: #0a0a0c;
  border-bottom: 1px solid #2a2a2f;
  min-height: 0;
  overflow: hidden;
}

/* ── Preview (the dominant area) ──────────────────────────────── */
.preview-wrap {
  position: relative;
  min-height: 0;
}
.placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #555;
  font-size: 13px;
}
.placeholder.small { font-size: 11px; }

/* ── Right drawer ─────────────────────────────────────────────── */
.drawer {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 360px;
  background: #18181b;
  border-left: 1px solid #2a2a2f;
  display: flex;
  flex-direction: column;
  z-index: 30;
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.4);
}
.drawer-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 10px;
  border-bottom: 1px solid #2a2a2f;
  flex: 0 0 auto;
}
.drawer-tabs {
  display: flex;
  gap: 2px;
}
.dt {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #aaa;
  padding: 4px 10px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 11px;
}
.dt:hover:not(:disabled) { background: #26262b; color: #ddd; }
.dt:disabled { opacity: 0.4; cursor: not-allowed; }
.dt.active {
  background: #3b82f6;
  border-color: #3b82f6;
  color: white;
  font-weight: 600;
}
.drawer-close {
  background: transparent;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 16px;
  padding: 0 6px;
}
.drawer-close:hover { color: #ddd; }
.drawer-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
}
.empty-drawer {
  padding: 24px 16px;
  color: #666;
  font-size: 12px;
  font-style: italic;
  text-align: center;
}

/* ── Toast + status overlays (unchanged) ──────────────────────── */
.error,
.busy {
  position: absolute;
  top: 10px;
  left: 50%;
  transform: translateX(-50%);
  padding: 6px 14px;
  border-radius: 4px;
  font-size: 12px;
  z-index: 20;
}
.error { background: #7f1d1d; color: #fee; }
.busy { background: #facc15; color: #000; }
.toast-card {
  position: absolute;
  top: 56px;
  left: 50%;
  transform: translateX(-50%);
  min-width: 420px;
  max-width: 70%;
  background: #18181b;
  border: 1px solid #064e3b;
  border-radius: 6px;
  padding: 10px 14px;
  z-index: 25;
  box-shadow: 0 8px 28px rgba(0, 0, 0, 0.5);
  font-size: 11px;
}
.toast-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
}
.toast-ok { color: #6ee7b7; font-weight: 600; }
.toast-x {
  background: transparent;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 14px;
  padding: 0 4px;
}
.toast-x:hover { color: #ddd; }
.toast-files {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 1px;
}
.toast-files li {
  font-family: ui-monospace, monospace;
  font-size: 10px;
  color: #aaa;
}
.toast-files li.more { color: #666; font-style: italic; }
.toast-summon {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px solid #2a2a2f;
}
.toast-summon .lbl { color: #aaa; flex: 0 0 auto; }
.toast-summon .cmd {
  flex: 1;
  font-family: ui-monospace, monospace;
  font-size: 10px;
  color: #ddd;
  background: #0e0e10;
  padding: 4px 6px;
  border-radius: 3px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.copy-btn {
  background: #3b82f6;
  border: 1px solid #3b82f6;
  color: white;
  padding: 3px 8px;
  border-radius: 3px;
  cursor: pointer;
  font-size: 10px;
  flex: 0 0 auto;
}
.copy-btn:hover { background: #2563eb; }
</style>
