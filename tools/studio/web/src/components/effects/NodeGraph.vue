<script setup lang="ts">
/**
 * Vue Flow wrapper that owns the visual representation of the fxgraph.
 * Source of truth = the v-model:graph prop (an FxGraph). We translate to
 * Vue Flow's flat `nodes` + `edges` shape on every change.
 *
 * Day 1: drag-from-palette → add node; reconnect edges; delete node/edge.
 * Day 2+: in-place param editing will live in FxNodeBox.
 */
import { computed, ref, watch } from 'vue'
import {
  VueFlow,
  type Connection,
  type Edge as VFEdge,
  type Node as VFNode,
  type NodeChange,
  type EdgeChange,
  type NodeMouseEvent,
  useVueFlow,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { MiniMap } from '@vue-flow/minimap'
import { markRaw } from 'vue'
import FxNodeBox from './FxNodeBox.vue'
import type { FxGraph, FxNode, NodeType } from '../../effects/types'
import { defaultParamsFor } from '../../effects/defaults'
import { SCHEMA } from '../../effects/schema'

const EDGE_COLOR: Record<string, string> = {
  canvas: '#6b7280',  // gray (default)
  number: '#fbbf24',  // yellow (scalar)
  vec2: '#fb923c',    // orange (vec2)
  effector: '#a78bfa', // violet (effector — particle force)
}

const nodeTypes = { fx: markRaw(FxNodeBox) }

const props = withDefaults(
  defineProps<{
    graph: FxGraph
    /** When false, the graph is a read-only minimap — no drag/connect/drop. */
    interactive?: boolean
  }>(),
  { interactive: true },
)

const emit = defineEmits<{
  (e: 'update:graph', g: FxGraph): void
  (e: 'select-node', id: string | null): void
}>()

const { project } = useVueFlow()

const flowNodes = computed<VFNode[]>(() =>
  props.graph.nodes.map((n) => ({
    id: n.id,
    type: 'fx',
    position: n.pos ?? { x: 0, y: 0 },
    data: n,
  })),
)

function edgeColorFor(fromNodeId: string): string {
  const src = props.graph.nodes.find((n) => n.id === fromNodeId)
  if (!src) return EDGE_COLOR.canvas
  const spec = SCHEMA[src.type as NodeType]
  const kind = spec?.outputKind ?? 'canvas'
  return EDGE_COLOR[kind] ?? EDGE_COLOR.canvas
}

const flowEdges = computed<VFEdge[]>(() =>
  props.graph.edges.map((e, i) => ({
    id: `e${i}-${e.from}-${e.to}-${e.in}`,
    source: e.from,
    target: e.to,
    targetHandle: e.in,
    sourceHandle: 'out',
    animated: false,
    style: { stroke: edgeColorFor(e.from), strokeWidth: 1.5 },
  })),
)

// --- mutation helpers ---

function newNodeId(type: NodeType, existing: FxNode[]): string {
  const stem = type.split('.').pop() || 'node'
  let i = 1
  let id = stem
  const set = new Set(existing.map((n) => n.id))
  while (set.has(id)) {
    i += 1
    id = `${stem}${i}`
  }
  return id
}

function onConnect(conn: Connection) {
  if (!conn.source || !conn.target || !conn.targetHandle) return
  // Replace any existing edge into the same target+input.
  const filtered = props.graph.edges.filter(
    (e) => !(e.to === conn.target && e.in === conn.targetHandle),
  )
  emit('update:graph', {
    ...props.graph,
    edges: [
      ...filtered,
      { from: conn.source, to: conn.target, in: conn.targetHandle },
    ],
  })
}

function onNodesChange(changes: NodeChange[]) {
  let nextNodes = props.graph.nodes
  let nextEdges = props.graph.edges
  let mutated = false
  for (const c of changes) {
    if (c.type === 'remove') {
      nextNodes = nextNodes.filter((n) => n.id !== c.id)
      nextEdges = nextEdges.filter((e) => e.from !== c.id && e.to !== c.id)
      mutated = true
    } else if (c.type === 'position' && c.position && !c.dragging) {
      const pos = c.position
      nextNodes = nextNodes.map((n) =>
        n.id === c.id ? { ...n, pos } : n,
      )
      mutated = true
    }
  }
  if (mutated) {
    emit('update:graph', {
      ...props.graph,
      nodes: nextNodes,
      edges: nextEdges,
    })
  }
}

function onEdgesChange(changes: EdgeChange[]) {
  let nextEdges = props.graph.edges
  let mutated = false
  for (const c of changes) {
    if (c.type === 'remove') {
      const m = c.id.match(/^e\d+-(.+)-(.+)-(.+)$/)
      if (m) {
        const [, from, to, inName] = m
        nextEdges = nextEdges.filter(
          (e) => !(e.from === from && e.to === to && e.in === inName),
        )
        mutated = true
      }
    }
  }
  if (mutated) {
    emit('update:graph', { ...props.graph, edges: nextEdges })
  }
}

const wrapperRef = ref<HTMLElement | null>(null)

function onDrop(e: DragEvent) {
  e.preventDefault()
  if (!props.interactive) return
  const type = e.dataTransfer?.getData('application/fx-node-type') as NodeType | undefined
  if (!type) return
  const bbox = wrapperRef.value?.getBoundingClientRect()
  if (!bbox) return
  const pos = project({
    x: e.clientX - bbox.left,
    y: e.clientY - bbox.top,
  })
  const id = newNodeId(type, props.graph.nodes)
  const fresh: FxNode = {
    id,
    type,
    params: defaultParamsFor(type),
    pos,
  }
  emit('update:graph', {
    ...props.graph,
    nodes: [...props.graph.nodes, fresh],
  })
}

function onDragOver(e: DragEvent) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'move'
}

function onNodeClick(evt: NodeMouseEvent) {
  emit('select-node', evt.node.id)
}

function onPaneClick() {
  emit('select-node', null)
}

watch(
  () => props.graph.name,
  () => emit('select-node', null),
)
</script>

<template>
  <div
    ref="wrapperRef"
    class="graph-wrap"
    @drop="onDrop"
    @dragover="onDragOver"
  >
    <VueFlow
      :nodes="flowNodes"
      :edges="flowEdges"
      :node-types="nodeTypes"
      :default-edge-options="{ type: 'smoothstep' }"
      :fit-view-on-init="true"
      :delete-key-code="interactive ? ['Delete', 'Backspace'] : []"
      :nodes-draggable="interactive"
      :nodes-connectable="interactive"
      :edges-updatable="interactive"
      :edges-focusable="interactive"
      :elements-selectable="true"
      :prevent-scrolling="interactive"
      :pan-on-drag="true"
      @connect="onConnect"
      @nodes-change="onNodesChange"
      @edges-change="onEdgesChange"
      @node-click="onNodeClick"
      @pane-click="onPaneClick"
    >
      <Background pattern-color="#2a2a2f" :gap="20" />
      <Controls v-if="interactive" />
      <MiniMap v-if="interactive" pannable zoomable />
    </VueFlow>
  </div>
</template>

<style scoped>
.graph-wrap {
  width: 100%;
  height: 100%;
  background: #0e0e10;
}
:deep(.vue-flow__background) {
  background: #0e0e10;
}
:deep(.vue-flow__minimap) {
  background: rgba(24, 24, 27, 0.85);
  border: 1px solid #2a2a2f;
  border-radius: 6px;
}
:deep(.vue-flow__controls) {
  background: #18181b;
  border: 1px solid #2a2a2f;
  border-radius: 6px;
}
:deep(.vue-flow__controls-button) {
  background: #1f1f24;
  border-bottom: 1px solid #2a2a2f;
  color: #ddd;
  fill: #ddd;
}
:deep(.vue-flow__controls-button:hover) {
  background: #26262b;
}
:deep(.vue-flow__handle) {
  width: 10px;
  height: 10px;
  border: 2px solid #18181b;
  background: #6b7280;
}
:deep(.vue-flow__handle-connecting) {
  background: #fbbf24;
}
:deep(.vue-flow__edge.selected .vue-flow__edge-path) {
  stroke: #3b82f6 !important;
}
</style>
