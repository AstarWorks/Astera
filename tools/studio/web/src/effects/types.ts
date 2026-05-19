// Astera fxgraph — the "Pixel Composer .pxc analog" text recipe for an
// animated pixel-art effect. JSON shape; one DAG per effect; each node is a
// pure function over canvases parameterized by a frame index in [0, frames).
//
// Committed under content/effects/<category>/<name>.fxgraph.json.
// Rendered outputs land under content/textures/animations/ (sprite sheets +
// .mcmeta) and optionally content/models/effects/ (item_display models).

export type NodeId = string

/** Allowed v1 node type identifiers. Update Day 2/5 as the renderer grows. */
export type NodeType =
  // generators
  | 'shape.circle'
  | 'shape.ring'
  | 'shape.polygon'
  | 'shape.line'
  | 'noise.perlin'
  | 'noise.value'
  | 'image.import'
  | 'generate.scatter'
  | 'generate.gradient_radial'
  // transforms
  | 'xform.translate'
  | 'xform.rotate'
  | 'xform.scale'
  | 'xform.mirror'
  | 'xform.polar'
  // filters
  | 'filter.blur'
  | 'filter.bloom'
  | 'filter.dilate'
  | 'filter.palette'
  | 'filter.outline'
  | 'filter.trail'
  | 'filter.dither'
  // composites
  | 'composite.over'
  | 'composite.add'
  | 'composite.screen'
  | 'composite.multiply'
  // animation drivers
  | 'anim.time'
  | 'anim.rotate'
  | 'anim.scale_pulse'
  | 'anim.scale_ramp'
  | 'anim.curve'
  | 'anim.wiggler'
  | 'anim.stagger'
  // AI keyframe
  | 'ai.image'
  // particle system
  | 'particle.emitter'
  | 'particle.effector'
  // values (scalar / vector emitters that flow through scalar edges)
  | 'values.number'
  | 'values.vec2'
  // sink
  | 'output'

/** Color/position gradient stop, used by the `gradient` param type. */
export type GradientStop = { t: number; color: string }

/** Numeric keyframe for the `curve` param type — t ∈ [0,1], v unbounded. */
export type CurveStop = { t: number; v: number }

export type ParamValue =
  | string
  | number
  | boolean
  | number[]
  | string[]
  | GradientStop[]
  | CurveStop[]

export type FxNode = {
  id: NodeId
  type: NodeType
  params: Record<string, ParamValue>
  /** Optional UI position for the Vue Flow editor. Persisted so the graph
   *  reopens with the layout the author left it in. Vue Flow x,y coordinates. */
  pos?: { x: number; y: number }
}

/** A directed edge: `from` node's primary output → `to` node's named input.
 *  v1 uses a single input port name per node ("image"); compositors that take
 *  two inputs use "a" and "b". The renderer treats absent edges as no-input. */
export type FxEdge = {
  from: NodeId
  to: NodeId
  in: string
}

export type FxGraph = {
  format_version: '1.0'
  /** Slug name; matches the file basename. */
  name: string
  /** Output sprite-sheet dimensions in pixels. Default 32×32. */
  size: [number, number]
  /** Frame count (typically 4–32). */
  frames: number
  /** Per-frame duration in MC ticks (20 ticks = 1 s). Mojang convention. */
  frame_time_ticks: number
  /** Whether the animation loops; mapped to mcmeta `interpolate=false, loop`. */
  loop: boolean
  nodes: FxNode[]
  edges: FxEdge[]
}

export type ExportFormat =
  | 'vertical_sheet'
  | 'horizontal_sheet'
  | 'frames'
  | 'item_display_model'

export type ExportRequest = {
  formats: ExportFormat[]
  /** Output basename — slug. Used for `<basename>.png`, `<basename>.mcmeta.json`. */
  basename: string
  /** Subdirectory under content/textures/animations/ (default "effects"). */
  textureSubdir?: string
}
