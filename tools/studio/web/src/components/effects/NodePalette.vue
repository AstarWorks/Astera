<script setup lang="ts">
/**
 * Drag-source list of node types, grouped + labeled from the central
 * schema. Search bar at top filters by label / type id / description.
 * Any new node added to `effects/schema.ts` appears here automatically.
 */
import { ref, computed } from 'vue'
import type { NodeType } from '../../effects/types'
import {
  nodesByCategory,
  CATEGORY_LABELS,
  type NodeCategory,
  type NodeSpec,
} from '../../effects/schema'

const grouped = nodesByCategory()
const ORDER: NodeCategory[] = [
  'generator',
  'transform',
  'filter',
  'composite',
  'animation',
  'values',
  'sink',
]

const query = ref('')

function matches(spec: NodeSpec, q: string): boolean {
  if (!q) return true
  const needle = q.toLowerCase()
  return (
    spec.label.toLowerCase().includes(needle) ||
    spec.type.toLowerCase().includes(needle) ||
    spec.description.toLowerCase().includes(needle)
  )
}

const filtered = computed(() => {
  const out: Record<NodeCategory, NodeSpec[]> = {
    generator: [],
    transform: [],
    filter: [],
    composite: [],
    animation: [],
    values: [],
    sink: [],
  }
  const q = query.value.trim()
  for (const cat of ORDER) {
    for (const spec of grouped[cat]) {
      if (matches(spec, q)) out[cat].push(spec)
    }
  }
  return out
})

const visibleCategories = computed(() => ORDER.filter((c) => filtered.value[c].length > 0))

function onDragStart(e: DragEvent, type: NodeType) {
  if (!e.dataTransfer) return
  e.dataTransfer.setData('application/fx-node-type', type)
  e.dataTransfer.effectAllowed = 'move'
}

function clearQuery() {
  query.value = ''
}

function onSearchKey(e: KeyboardEvent) {
  if (e.key === 'Escape') clearQuery()
}
</script>

<template>
  <div class="palette">
    <div class="search-wrap">
      <input
        v-model="query"
        type="search"
        placeholder="ノード検索…  (Esc で消去)"
        class="search-input"
        @keydown="onSearchKey"
      />
      <button v-if="query" class="search-clear" @click="clearQuery" title="検索クリア">×</button>
    </div>

    <div v-if="visibleCategories.length === 0" class="no-match">
      該当ノードなし
    </div>

    <div v-for="cat in visibleCategories" :key="cat" class="group">
      <h4 class="group-label">{{ CATEGORY_LABELS[cat] }}</h4>
      <ul>
        <li
          v-for="spec in filtered[cat]"
          :key="spec.type"
          class="item"
          :class="{ unimpl: spec.description.startsWith('（v1 未実装') }"
          draggable="true"
          @dragstart="onDragStart($event, spec.type)"
          :title="`${spec.description}\n\nノード ID: ${spec.type}`"
        >
          <span class="lbl">{{ spec.label }}</span>
          <code class="type-id">{{ spec.type }}</code>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.palette {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 12px;
  overflow-y: auto;
  height: 100%;
}
.search-wrap {
  position: relative;
  flex: 0 0 auto;
}
.search-input {
  width: 100%;
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  color: #ddd;
  padding: 6px 26px 6px 10px;
  border-radius: 4px;
  font-size: 11px;
  font-family: inherit;
}
.search-input:focus {
  outline: none;
  border-color: #3b82f6;
}
.search-input::-webkit-search-cancel-button {
  display: none;
}
.search-clear {
  position: absolute;
  right: 6px;
  top: 50%;
  transform: translateY(-50%);
  background: transparent;
  border: none;
  color: #888;
  cursor: pointer;
  font-size: 14px;
  line-height: 1;
  padding: 0 4px;
}
.search-clear:hover { color: #ddd; }
.no-match {
  color: #666;
  font-size: 11px;
  text-align: center;
  font-style: italic;
  padding: 12px 0;
}
.group {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.group-label {
  margin: 0;
  font-size: 10px;
  color: #888;
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.group ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.item {
  padding: 5px 8px;
  border-radius: 4px;
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  cursor: grab;
  display: flex;
  flex-direction: column;
  gap: 1px;
  transition: background 0.1s, border-color 0.1s;
}
.item:hover {
  background: #26262b;
  border-color: #3b82f6;
}
.item:active {
  cursor: grabbing;
}
.item.unimpl {
  opacity: 0.55;
}
.lbl {
  font-size: 11px;
  color: #ddd;
}
.type-id {
  font-family: ui-monospace, monospace;
  font-size: 9px;
  color: #666;
}
</style>
