/**
 * fxgraph render engine.
 *
 * Each node is a pure function:
 *   compute(inputs, params, ctx) → NodeResult
 *
 * The executor:
 *   1. Topologically sorts the DAG by edges.
 *   2. Walks nodes in order, calls each one's compute(), memoizes the result.
 *   3. Reads the `output` node's result and returns its canvas.
 *
 * Results live in an OffscreenCanvas of `graph.size`. CPU canvas is plenty
 * fast for 32×32 × ~16 frames; no GPU needed for v1.
 *
 * Memoization is per-(frame, nodeId). The executor is re-entered for each
 * frame; nodes that have no time-dependent inputs are still recomputed per
 * frame for v1 simplicity — that's negligible at this scale.
 */
import type { FxGraph, FxNode, NodeType, ParamValue } from './types'
import { computeNode } from './nodes'

/**
 * Cross-frame state shared by stateful nodes (filter.trail, etc.). The
 * caller owns the Map and reuses it across frames within a single graph
 * playback. Clears on graph switch.
 */
export type RenderState = Map<string, unknown>

export type RenderContext = {
  size: [number, number]
  frame: number
  totalFrames: number
  /** ticks per frame; 20 ticks = 1 s in MC. */
  frameTimeTicks: number
  /** Current playhead in seconds. */
  t: number
  /** 0..1 progress through the loop. */
  t01: number
  loop: boolean
  /** Cross-frame mutable storage. Stateful nodes key by nodeId. */
  state: RenderState
  /** The current node's ID — stateful nodes use it to scope state keys. */
  nodeId: string
}

/** Effector config emitted by `particle.effector` and consumed by
 *  `particle.emitter` to apply per-particle forces. */
export type EffectorConfig = {
  mode: 'attract' | 'vortex' | 'wind' | 'repel'
  origin: [number, number]
  strength: number
  radius: number
  falloff: 'linear' | 'inverse_square' | 'none'
}

/**
 * Result kinds a node may produce. Most nodes emit `canvas` (image), but
 * scalar nodes (anim.curve, anim.wiggler, values.number) emit `number`, and
 * positional drivers emit `vec2`. The executor auto-merges scalar/vec2
 * results into the downstream node's params by port name, so the compute
 * fn never has to know whether a param came from a static slider or an edge.
 */
export type NodeResult =
  | { kind: 'canvas'; canvas: OffscreenCanvas }
  | { kind: 'number'; value: number }
  | { kind: 'vec2'; value: [number, number] }
  | { kind: 'effector'; config: EffectorConfig }

export type NodeCompute = (
  inputs: Record<string, NodeResult>,
  params: Record<string, ParamValue>,
  ctx: RenderContext,
) => NodeResult

/** Helper for nodes that only consume canvas inputs — pluck out the matching
 *  port and silently ignore non-canvas results. Cleaner than scattering
 *  `if (r.kind === 'canvas')` everywhere. */
export function canvasInput(
  inputs: Record<string, NodeResult>,
  port: string,
): { canvas: OffscreenCanvas } | null {
  const r = inputs[port]
  if (r && r.kind === 'canvas') return { canvas: r.canvas }
  return null
}

/** Build the per-frame RenderContext given a graph + frame number. */
export function contextFor(
  graph: FxGraph,
  frame: number,
  state: RenderState = new Map(),
): RenderContext {
  const t = (frame * graph.frame_time_ticks) / 20
  const total = (graph.frames * graph.frame_time_ticks) / 20
  return {
    size: graph.size,
    frame,
    totalFrames: graph.frames,
    frameTimeTicks: graph.frame_time_ticks,
    t,
    t01: total > 0 ? t / total : 0,
    loop: graph.loop,
    state,
    nodeId: '',
  }
}

/** Topologically sort node IDs by graph edges. Throws on cycles. */
export function topoSort(graph: FxGraph): string[] {
  const inDeg: Record<string, number> = {}
  const out: Record<string, string[]> = {}
  for (const n of graph.nodes) {
    inDeg[n.id] = 0
    out[n.id] = []
  }
  for (const e of graph.edges) {
    if (!(e.from in inDeg) || !(e.to in inDeg)) continue
    inDeg[e.to] += 1
    out[e.from].push(e.to)
  }
  const queue: string[] = []
  for (const id of Object.keys(inDeg)) {
    if (inDeg[id] === 0) queue.push(id)
  }
  const order: string[] = []
  while (queue.length > 0) {
    const id = queue.shift() as string
    order.push(id)
    for (const next of out[id]) {
      inDeg[next] -= 1
      if (inDeg[next] === 0) queue.push(next)
    }
  }
  if (order.length !== graph.nodes.length) {
    throw new Error('fxgraph: cycle detected')
  }
  return order
}

/** Render a single frame of the graph. Returns the output node's canvas.
 *  Pass `state` (a Map) to enable stateful nodes (filter.trail) — the caller
 *  must reuse the same Map across consecutive frames. */
export function renderFrame(
  graph: FxGraph,
  frame: number,
  state: RenderState = new Map(),
): OffscreenCanvas {
  const ctx = contextFor(graph, frame, state)
  const order = topoSort(graph)
  const byId: Record<string, FxNode> = {}
  for (const n of graph.nodes) byId[n.id] = n

  // Build a reverse-index of edges: for each (target, in) → source node id.
  const inputsByTarget: Record<string, Record<string, string>> = {}
  for (const e of graph.edges) {
    if (!inputsByTarget[e.to]) inputsByTarget[e.to] = {}
    inputsByTarget[e.to][e.in] = e.from
  }

  const results: Record<string, NodeResult> = {}
  let outputId: string | null = null
  for (const id of order) {
    const node = byId[id]
    const inputs: Record<string, NodeResult> = {}
    const incoming = inputsByTarget[id] || {}
    // Scalar / vec2 inputs whose port name matches a param key OVERRIDE that
    // param value for this compute. e.g. an anim.curve node feeding the
    // "speed_deg_per_sec" port of anim.rotate replaces the slider value.
    const overrideParams: Record<string, ParamValue> = { ...node.params }
    for (const [inName, fromId] of Object.entries(incoming)) {
      const r = results[fromId]
      if (!r) continue
      inputs[inName] = r
      if (r.kind === 'number') {
        overrideParams[inName] = r.value
      } else if (r.kind === 'vec2') {
        overrideParams[inName] = [r.value[0], r.value[1]]
      }
      // `effector` kind: don't merge into params — emitter reads it via `inputs.effector`.
    }
    ctx.nodeId = id
    results[id] = computeNode(node.type as NodeType, inputs, overrideParams, ctx)
    if (node.type === 'output') outputId = id
  }

  if (outputId) {
    const r = results[outputId]
    if (r && r.kind === 'canvas') return r.canvas
  }
  // No output node — return the last evaluated canvas-emitting result, else blank.
  for (let i = order.length - 1; i >= 0; i--) {
    const r = results[order[i]]
    if (r && r.kind === 'canvas') return r.canvas
  }
  return blankCanvas(ctx.size)
}

export function blankCanvas([w, h]: [number, number]): OffscreenCanvas {
  return new OffscreenCanvas(w, h)
}
