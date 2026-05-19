<script setup lang="ts">
/**
 * Power-user node-graph editor overlay.
 *
 * The primary `/effects` view is preview-driven (humans tweak params via
 * Inspector + ask Claude). Drag-and-drop node editing lives here, behind an
 * explicit 「🔧 編集」 button, so the default surface stays uncluttered.
 *
 *   ┌─────────────────────────────────────────────────────────┐
 *   │ ノード編集                                       [完了 ×] │
 *   ├──────────┬──────────────────────────┬────────────────────┤
 *   │ palette  │   interactive node graph │  inspect           │
 *   │ (drag    │   (drag, connect, delete)│  (selected node)   │
 *   │  source) │                          │                    │
 *   └──────────┴──────────────────────────┴────────────────────┘
 */
import { onMounted, onBeforeUnmount, ref, computed } from 'vue'
import type { FxGraph, FxNode } from '../../effects/types'
import NodeGraph from './NodeGraph.vue'
import NodePalette from './NodePalette.vue'
import NodeInspector from './NodeInspector.vue'

const props = defineProps<{
  graph: FxGraph
  initialSelectedId?: string | null
}>()

const emit = defineEmits<{
  (e: 'update:graph', g: FxGraph): void
  (e: 'close'): void
}>()

const selectedId = ref<string | null>(props.initialSelectedId ?? null)

const selectedNode = computed(() => {
  if (!selectedId.value) return null
  return props.graph.nodes.find((n) => n.id === selectedId.value) ?? null
})

function onNodeUpdate(updated: FxNode) {
  emit('update:graph', {
    ...props.graph,
    nodes: props.graph.nodes.map((n) =>
      n.id === updated.id ? updated : n,
    ),
  })
}

function onEsc(e: KeyboardEvent) {
  if (e.key === 'Escape') emit('close')
}

onMounted(() => {
  document.addEventListener('keydown', onEsc)
})
onBeforeUnmount(() => {
  document.removeEventListener('keydown', onEsc)
})
</script>

<template>
  <div class="overlay-backdrop" @click.self="emit('close')">
    <div class="overlay-card">
      <header class="overlay-head">
        <span class="title">🔧 ノード編集 — {{ graph.name }}</span>
        <button class="close-btn" @click="emit('close')" title="ESC でも閉じる">
          完了 ×
        </button>
      </header>
      <div class="overlay-body">
        <aside class="palette-col">
          <NodePalette />
        </aside>
        <div class="graph-col">
          <NodeGraph
            :graph="graph"
            :interactive="true"
            @update:graph="emit('update:graph', $event)"
            @select-node="(id) => (selectedId = id)"
          />
        </div>
        <aside class="inspect-col">
          <NodeInspector
            v-if="selectedNode"
            :node="selectedNode"
            @update:node="onNodeUpdate"
          />
          <div v-else class="empty-inspect">
            ノードを選択してパラメータ編集
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<style scoped>
.overlay-backdrop {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.6);
  display: grid;
  place-items: center;
  z-index: 50;
  padding: 24px;
}
.overlay-card {
  background: #0e0e10;
  border: 1px solid #2a2a2f;
  border-radius: 8px;
  width: 100%;
  height: 100%;
  max-width: 1600px;
  max-height: 900px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 16px 48px rgba(0, 0, 0, 0.7);
  overflow: hidden;
}
.overlay-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 14px;
  background: #18181b;
  border-bottom: 1px solid #2a2a2f;
  flex: 0 0 auto;
}
.title {
  font-family: ui-monospace, monospace;
  font-size: 13px;
  color: #eee;
}
.close-btn {
  background: #3b82f6;
  border: 1px solid #3b82f6;
  color: white;
  padding: 5px 12px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
}
.close-btn:hover {
  background: #2563eb;
}
.overlay-body {
  flex: 1;
  display: grid;
  grid-template-columns: 240px 1fr 320px;
  min-height: 0;
}
.palette-col {
  background: #18181b;
  border-right: 1px solid #2a2a2f;
  overflow-y: auto;
}
.graph-col {
  background: #0e0e10;
  min-height: 0;
  position: relative;
}
.inspect-col {
  background: #18181b;
  border-left: 1px solid #2a2a2f;
  overflow-y: auto;
}
.empty-inspect {
  padding: 24px 16px;
  color: #666;
  font-size: 12px;
  font-style: italic;
  text-align: center;
}
</style>
