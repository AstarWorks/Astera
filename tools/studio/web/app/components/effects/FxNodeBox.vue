<script setup lang="ts">
/**
 * One node in the fxgraph editor. Renders the node type in the header and
 * its params as a compact key:value list. Day 1: read-only display.
 * Day 2 swaps the param list for inline editors (number/color/select).
 */
import { Handle, Position, type NodeProps } from '@vue-flow/core'
import { computed } from 'vue'
import type { FxNode, NodeType } from '#shared/effects/types'
import { SCHEMA } from '#shared/effects/schema'

// Vue Flow passes every standard NodeProps field (id, type, position, etc.)
// alongside `data`. We only use `data` and `selected`, but the type contract
// requires the rest to be declarable.
const props = defineProps<NodeProps<FxNode>>()

const spec = computed(() => SCHEMA[props.data.type as NodeType])

const isOutput = computed(() => props.data.type === 'output')
const isSource = computed(() => {
  // Generators don't take an image input.
  const t = props.data.type
  return (
    t.startsWith('shape.') ||
    t.startsWith('noise.') ||
    t === 'image.import' ||
    t === 'ai.image' ||
    t.startsWith('anim.')
  )
})
const isComposite = computed(
  () => props.data.type.startsWith('composite.'),
)

const category = computed(() => props.data.type.split('.')[0])

const paramEntries = computed(() =>
  Object.entries(props.data.params).map(([k, v]) => ({
    k,
    v: Array.isArray(v) ? `[${v.join(', ')}]` : String(v),
  })),
)
</script>

<template>
  <div class="fx-node" :class="[`cat-${category}`, { selected }]">
    <!-- Input handle (left). Generators don't show this. -->
    <Handle
      v-if="!isSource"
      type="target"
      :position="Position.Left"
      id="image"
    />
    <!-- Composites take a 2nd input "b". -->
    <Handle
      v-if="isComposite"
      type="target"
      :position="Position.Left"
      id="b"
      style="top: 70%"
    />

    <header class="fx-node-head">
      <span class="dot" :class="`cat-${category}`"></span>
      <span class="type" :title="data.type">
        {{ spec?.label || data.type }}
      </span>
    </header>
    <ul v-if="paramEntries.length > 0" class="fx-node-params">
      <li v-for="p in paramEntries" :key="p.k">
        <span class="pk">{{ spec?.params[p.k]?.label || p.k }}</span>
        <span class="pv">{{ p.v }}</span>
      </li>
    </ul>
    <div v-else class="fx-node-params empty">no params</div>

    <!-- Output handle (right). Output node has no downstream. -->
    <Handle
      v-if="!isOutput"
      type="source"
      :position="Position.Right"
      id="out"
    />
  </div>
</template>

<style scoped>
.fx-node {
  background: #1f1f24;
  border: 1px solid #2a2a2f;
  border-radius: 6px;
  min-width: 180px;
  font-size: 11px;
  color: #ddd;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.4);
}
.fx-node.selected {
  border-color: #3b82f6;
  box-shadow: 0 0 0 1px #3b82f6, 0 4px 12px rgba(59, 130, 246, 0.3);
}
.fx-node-head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 7px 10px;
  border-bottom: 1px solid #2a2a2f;
  background: #18181b;
  border-radius: 6px 6px 0 0;
}
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 auto;
}
.dot.cat-shape { background: #fbbf24; }
.dot.cat-noise { background: #a3a3a3; }
.dot.cat-image { background: #f87171; }
.dot.cat-xform { background: #34d399; }
.dot.cat-filter { background: #c084fc; }
.dot.cat-composite { background: #60a5fa; }
.dot.cat-anim { background: #f472b6; }
.dot.cat-ai { background: #fb923c; }
.dot.cat-output { background: #6ee7b7; }
.type {
  font-family: ui-monospace, monospace;
  font-size: 11px;
  color: #eee;
}
.fx-node-params {
  list-style: none;
  margin: 0;
  padding: 6px 10px 8px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.fx-node-params.empty {
  color: #666;
  font-style: italic;
  font-size: 10px;
}
.fx-node-params li {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-family: ui-monospace, monospace;
  font-size: 10px;
  line-height: 1.5;
}
.pk { color: #888; }
.pv {
  color: #ddd;
  max-width: 110px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
