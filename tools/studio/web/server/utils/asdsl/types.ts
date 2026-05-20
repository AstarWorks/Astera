/**
 * Astera Shape DSL — types.
 *
 * Design intent at this layer:
 *  - LLM never emits raw MC voxel coordinates. Instead it names parts and
 *    relates them via anchor handles ("relative_to: 'blade', align: 'base_center'").
 *  - The compiler resolves anchors, voxelizes meso-primitives, applies rotations,
 *    and emits MC model.json. The LLM only knows about parts dict and tools.
 *  - Each mutation returns a truth envelope ({ok, status, hints, changes}).
 *    Hints carry suggestions when something fails (Onshape MCP pattern).
 */

export type Vec2 = [number, number]
export type Vec3 = [number, number, number]

/** Anchors a part can attach to relative to itself or another part. */
export type AnchorSlot =
  | 'origin'
  | 'center'
  | 'top_center'
  | 'bottom_center'
  | 'front_center'
  | 'back_center'
  | 'left_center'
  | 'right_center'
  | 'base_center' // base of an oriented primitive (bottom in axis-major)
  | 'tip_center'  // tip of an oriented primitive

export type PartPlacement = {
  /** If set, this is the absolute anchor for the part's own origin. */
  anchor?: AnchorSlot
  /** If set, place relative to another named part's anchor slot.
   *  Accepts either bare name (`"blade"`) or `@cad[name]` handle syntax
   *  (`"@cad[blade]"`). The handle form is self-documenting and signals
   *  cross-iteration intent; both resolve identically via unwrapHandle(). */
  relative_to?: string
  align?: AnchorSlot
  delta_x?: number
  delta_y?: number
  delta_z?: number
  /** Optional declarative assertions evaluated after compile.
   *  Failed assertions surface as WARNING + diagnostics in the truth envelope
   *  (do not block compile). See assertions.ts. */
  assertions?: Assertion[]
}

// Meso-primitive specifications. Each kind has a different "what does it look
// like" parameter set. Compiler maps each kind to N cuboids w/ rotations.

export type CuboidPart = PartPlacement & {
  kind: 'cuboid'
  /** [x, y, z] extents in voxel units. */
  size: Vec3
}

export type TaperedPrismPart = PartPlacement & {
  kind: 'tapered_prism'
  /** Major axis the prism extends along. */
  axis: 'x' | 'y' | 'z'
  /** Cross-section size at base [u, v] (perpendicular to axis). */
  base_size: Vec2
  /** Cross-section size at tip. */
  tip_size: Vec2
  /** Length along axis. */
  length: number
  /** Segment count for the taper (more = smoother, more polygons). */
  segments?: number
  /** Tip styling. "blunt" = last segment is just narrow.
   *  "pointed_rot" = last segment is single cuboid with rotation -45° around
   *  axis perpendicular to the primary axis (a real "blade point"). */
  tip_style?: 'blunt' | 'pointed_rot'
  /** Cross-section shape. "square" is axis-aligned only. "octagon" adds 45°
   *  rotated cuboids per segment to fake octagonal section (4 extra cuboids
   *  per segment). */
  cross_section?: 'square' | 'octagon'
}

export type ChamferedCuboidPart = PartPlacement & {
  kind: 'chamfered_cuboid'
  size: Vec3
  /** How much to chamfer the corners along the major axis. 0..min(size)/2. */
  chamfer: number
  /** Which axis the chamfered "ridge" runs along. The 4 cuboids that fake the
   *  chamfer are placed perpendicular to this axis. */
  axis?: 'x' | 'y' | 'z'
}

export type CylinderPart = PartPlacement & {
  kind: 'cylinder'
  axis: 'x' | 'y' | 'z'
  length: number
  diameter: number
  segments?: number
  /** 1 = single square prism; 2 = adds a 45° rotated inner square (octagonal
   *  silhouette); 3 = additional chamfers. */
  roundness?: 1 | 2 | 3
}

export type SpherePart = PartPlacement & {
  kind: 'sphere'
  diameter: number
  layers?: 3 | 5 | 7
}

export type RingPart = PartPlacement & {
  kind: 'ring'
  axis: 'x' | 'y' | 'z'
  outer: number
  inner: number
  height: number
  resolution?: number
}

export type Part =
  | CuboidPart
  | TaperedPrismPart
  | ChamferedCuboidPart
  | CylinderPart
  | SpherePart
  | RingPart

export type Quality = 'low' | 'medium' | 'high'

export type Asdsl = {
  /** Free-form id of the design (matches the slug for save). */
  name: string
  /** Numeric parameters referenced as `@<name>` in size/length fields. */
  params: Record<string, number>
  parts: Record<string, Part>
  /** Texture slot → MC texture slug. Slots are domain names ("blade_body",
   *  "blade_edge", "metal_warm", "wood", "gem"). */
  textures: Record<string, string>
  /** Per-part-per-face slot overrides (default per-kind defaults are used
   *  otherwise: e.g. blade north/south = blade_edge, up/down = blade_body). */
  face_overrides: Record<string, Partial<Record<McFace, string>>>
  /** Global detail tier — sets default segments/layers/roundness across all
   *  primitives. Individual parts can still override on a per-part basis. */
  quality?: Quality
  display?: {
    /** Z-axis rotation for the GUI camera, in degrees. MC convention is 45 for
     *  swords/axes. The compiler converts to MC's display.gui.rotation triplet. */
    gui_rotation_z?: number
    gui_scale?: number
  }
}

export type McFace = 'east' | 'west' | 'up' | 'down' | 'south' | 'north'

// --- Declarative assertions (ADR-0062 B7) -----------------------------------

/** Assertion kinds evaluated post-compile. New kinds are additive. */
export type Assertion =
  | { kind: 'aligned_with'; a: string; b: string }
  | { kind: 'no_self_intersection' }
  | { kind: 'fits_within_voxel_envelope'; width: number; height: number; depth: number }
  | { kind: 'clearance_min'; from: string; to: string; voxels: number }

/** Per-part-per-assertion evaluation outcome. Collected into Envelope.diagnostics. */
export type AssertionResult = {
  kind: string
  part_name: string
  passed: boolean
  detail?: string
}

// --- Truth envelope (every mutation returns this) ----------------------------

/** ADR-0062 B8 status enum.
 *  OK = mutation succeeded.
 *  INFO = mutation succeeded with mechanical auto-adjust (rotation snap,
 *    palette quantize, bbox clip). Agent may ignore.
 *  WARNING = mutation succeeded but soft constraint violated (assertion fail,
 *    overlap, unused variable). Agent should re-evaluate.
 *  ERROR = mutation failed.
 *  UNKNOWN = defensive default when status metadata is missing. */
export type EnvelopeStatus = 'OK' | 'INFO' | 'WARNING' | 'ERROR' | 'UNKNOWN'

export type Hint = {
  /** Short human/LLM-readable description of the suggestion. */
  text: string
  /** Programmatic identifier for the error pattern (e.g. ROTATION_OUT_OF_ENUM). */
  code?: string
  /** Suggested concrete fix, e.g. `{angle: 22.5}` if 30 was requested. */
  suggestion?: Record<string, unknown>
}

export type Envelope<T> = {
  ok: boolean
  status: EnvelopeStatus
  error_code?: string
  hints: Hint[]
  changes: {
    bbox_delta?: { min: Vec3; max: Vec3 }
    voxel_count_delta?: number
    parts_added?: string[]
    parts_modified?: string[]
    parts_removed?: string[]
    /** Texture slot keys newly bound by this mutation (B10). */
    texture_refs_added?: string[]
    /** Texture slot keys unbound by this mutation (B10). */
    texture_refs_removed?: string[]
    /** Human-readable one-liner summary of the change set (B10). */
    summary?: string
  }
  /** Assertion outcomes from B7 evaluator. Present only when the part schema
   *  declared `assertions[]`. A failed assertion downgrades status to WARNING. */
  diagnostics?: AssertionResult[]
  data?: T
}
