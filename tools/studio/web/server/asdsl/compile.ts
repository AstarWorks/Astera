/**
 * ASDSL parts dict → MC model.json compiler.
 *
 * Day 13 MVP: cuboid + tapered_prism only (with rotated tip).
 * Day 14: chamfered_cuboid, cylinder, sphere, ring.
 */
import { resolve, type ResolvedPart } from './anchor'
import { resolveNum } from './util'
import type {
  Asdsl,
  Part,
  Vec3,
  McFace,
  TaperedPrismPart,
  CuboidPart,
  ChamferedCuboidPart,
  CylinderPart,
  SpherePart,
  RingPart,
} from './types'
import type { McModel, Element, Face } from '../../src/types'
import { generateCylinder } from '../composite/cylinder'
import { generateSphere } from '../composite/sphere'
import { generateRing } from '../composite/ring'
import { generateStairStepSphere } from '../composite/stair-step-sphere'

type Cuboid = {
  from: Vec3
  to: Vec3
  rotation?: { origin: Vec3; axis: 'x' | 'y' | 'z'; angle: -45 | -22.5 | 0 | 22.5 | 45 }
  /** Per-face slot override (slot name in asdsl.textures, e.g. "blade_body"). */
  faceSlots: Partial<Record<McFace, string>>
}

const DEFAULT_FACES: McFace[] = ['east', 'west', 'up', 'down', 'south', 'north']

/** Compile the ASDSL into a McModel ready for state.ts to ingest. */
export function compile(asdsl: Asdsl): {
  ok: true
  model: McModel
  per_part_indices: Record<string, number[]>
  resolved: Map<string, ResolvedPart>
} | { ok: false; error_code: string; hints: { text: string; code?: string }[] } {
  const r = resolve(asdsl)
  if (!r.ok) return { ok: false, error_code: r.error_code, hints: r.hints }

  const allCuboids: Cuboid[] = []
  const perPartIndices: Record<string, number[]> = {}

  for (const name of r.order) {
    const rp = r.resolved.get(name)!
    const cuboids = expandPart(rp, asdsl)
    // Apply per-part face_overrides on top of kind defaults.
    const overrides = asdsl.face_overrides[name]
    if (overrides) {
      for (const c of cuboids) {
        for (const [face, slot] of Object.entries(overrides)) {
          if (slot) c.faceSlots[face as McFace] = slot
        }
      }
    }
    perPartIndices[name] = []
    for (const c of cuboids) {
      const idx = allCuboids.length
      allCuboids.push(c)
      perPartIndices[name].push(idx)
    }
  }

  // Build model.textures map: slot name → slug.
  const textures: Record<string, string> = { ...asdsl.textures }
  // Sanity: only include slots that are actually referenced by a face.
  // (For an MVP, just include all slots the user declared.)

  // Build elements array.
  const availableSlots = Object.keys(textures)
  const elements: Element[] = allCuboids.map((c) => {
    const faces: Element['faces'] = {}
    for (const face of DEFAULT_FACES) {
      let slot = c.faceSlots[face]
      // Fallback chain: 1) requested slot, 2) first available, 3) skip the face
      if (!slot || !availableSlots.includes(slot)) {
        slot = availableSlots[0]
      }
      if (!slot) continue
      const uv = uvForFace(c, face)
      faces[face] = {
        uv,
        texture: `#${slot}`,
      } as Face
    }
    const el: Element = {
      from: c.from,
      to: c.to,
      faces,
    }
    if (c.rotation && c.rotation.angle !== 0) {
      el.rotation = c.rotation
    }
    return el
  })

  // Display transforms — default to handheld diagonal pose.
  const display = buildDisplay(asdsl.display)

  const model: McModel = {
    parent: 'item/handheld',
    textures,
    elements,
    display,
  }

  return { ok: true, model, per_part_indices: perPartIndices, resolved: r.resolved }
}

/** Detail tier defaults. Each part can still override via its own field. */
type QualityDefaults = {
  tapered_segments: number
  sphere_layers: 3 | 5 | 7
  cylinder_roundness: 1 | 2 | 3
  ring_resolution: number
  /** Whether `cross_section: "octagon"` actually emits the 45°-rotated
   *  overlay. `false` for low/medium because the overlay is invisible under
   *  textures and only adds drawcalls. */
  emit_octagon_overlay: boolean
  /** Whether chamfered_cuboid emits its 45°-rotated overlay cuboid. */
  emit_chamfer_overlay: boolean
  /** Whether sphere voxelizes (`true`) or uses the 5-cuboid stair-step
   *  approximation (`false`). */
  sphere_voxelize: boolean
}

const QUALITY_TIERS: Record<'low' | 'medium' | 'high', QualityDefaults> = {
  low: {
    tapered_segments: 4,
    sphere_layers: 3,
    cylinder_roundness: 1,
    ring_resolution: 4,
    emit_octagon_overlay: false,
    emit_chamfer_overlay: false,
    sphere_voxelize: false,
  },
  medium: {
    tapered_segments: 6,
    sphere_layers: 5,
    cylinder_roundness: 1,
    ring_resolution: 5,
    emit_octagon_overlay: false,
    emit_chamfer_overlay: false,
    sphere_voxelize: false,
  },
  high: {
    tapered_segments: 10,
    sphere_layers: 7,
    cylinder_roundness: 2,
    ring_resolution: 7,
    emit_octagon_overlay: true,
    emit_chamfer_overlay: true,
    sphere_voxelize: true,
  },
}

function expandPart(rp: ResolvedPart, asdsl: Asdsl): Cuboid[] {
  const q = QUALITY_TIERS[asdsl.quality ?? 'medium']
  switch (rp.part.kind) {
    case 'cuboid':
      return expandCuboid(rp, rp.part, asdsl.params)
    case 'tapered_prism':
      return expandTaperedPrism(rp, rp.part, asdsl.params, q)
    case 'chamfered_cuboid':
      return expandChamferedCuboid(rp, rp.part, asdsl.params, q)
    case 'cylinder':
      return expandCylinder(rp, rp.part, asdsl.params, q)
    case 'sphere':
      return expandSphere(rp, rp.part, asdsl.params, q)
    case 'ring':
      return expandRing(rp, rp.part, asdsl.params, q)
  }
}

function expandChamferedCuboid(rp: ResolvedPart, part: ChamferedCuboidPart, params: Record<string, number>, q?: QualityDefaults): Cuboid[] {
  const w = resolveNum(part.size[0], params)
  const h = resolveNum(part.size[1], params)
  const d = resolveNum(part.size[2], params)
  const chamferAmount = resolveNum(part.chamfer, params)
  const o = rp.origin
  const axis: 'x' | 'y' | 'z' = part.axis ?? 'y'
  const slots = defaultFaceSlots(part.kind)
  // Two overlapping cuboids: axis-aligned + 45° rotated around `axis`.
  // The rotated copy is slightly smaller so the union silhouette is octagonal.
  const inner = Math.max(0.6, 1 - chamferAmount / Math.min(w, h, d))
  const axisAligned: Cuboid = {
    from: [o[0] - w / 2, o[1], o[2] - d / 2],
    to: [o[0] + w / 2, o[1] + h, o[2] + d / 2],
    faceSlots: slots,
  }
  let rotated: Cuboid | null = null
  // Only emit the rotated overlay when quality tier wants it. For medium /
  // low, the overlay is invisible under textures (Z-fights with the main
  // cuboid) and just doubles the cuboid count for no visual gain.
  if (chamferAmount > 0 && (q?.emit_chamfer_overlay ?? false)) {
    const cx = o[0]
    const cy = o[1] + h / 2
    const cz = o[2]
    if (axis === 'y') {
      const halfW = (w * inner) / 2
      const halfD = (d * inner) / 2
      rotated = {
        from: [cx - halfW, o[1], cz - halfD],
        to: [cx + halfW, o[1] + h, cz + halfD],
        rotation: { origin: [cx, cy, cz], axis: 'y', angle: 45 },
        faceSlots: slots,
      }
    } else if (axis === 'x') {
      const halfH = (h * inner) / 2
      const halfD = (d * inner) / 2
      rotated = {
        from: [o[0] - w / 2, cy - halfH, cz - halfD],
        to: [o[0] + w / 2, cy + halfH, cz + halfD],
        rotation: { origin: [cx, cy, cz], axis: 'x', angle: 45 },
        faceSlots: slots,
      }
    } else {
      const halfW = (w * inner) / 2
      const halfH = (h * inner) / 2
      rotated = {
        from: [cx - halfW, cy - halfH, o[2] - d / 2],
        to: [cx + halfW, cy + halfH, o[2] + d / 2],
        rotation: { origin: [cx, cy, cz], axis: 'z', angle: 45 },
        faceSlots: slots,
      }
    }
  }
  return rotated ? [axisAligned, rotated] : [axisAligned]
}

function expandCylinder(
  rp: ResolvedPart,
  part: CylinderPart,
  params: Record<string, number>,
  q: QualityDefaults,
): Cuboid[] {
  const slots = defaultFaceSlots(part.kind)
  const o = rp.origin
  const specs = generateCylinder({
    base: o,
    length: resolveNum(part.length, params),
    diameter: resolveNum(part.diameter, params),
    axis: part.axis,
    segments: part.segments,
    roundness: (part.roundness ?? q.cylinder_roundness) as 1 | 2 | 3,
  })
  return specs.map((s) => ({ from: s.from, to: s.to, faceSlots: slots }))
}

function expandSphere(
  rp: ResolvedPart,
  part: SpherePart,
  params: Record<string, number>,
  q: QualityDefaults,
): Cuboid[] {
  const slots = defaultFaceSlots(part.kind)
  const o = rp.origin
  const diameter = resolveNum(part.diameter, params)
  const center: Vec3 = [o[0], o[1] + diameter / 2, o[2]]
  // Medium / low quality use the 5-cuboid "stair-step cross" approximation —
  // looks better than a voxelized ball (which is just chunky stairs) and
  // costs ~7× less. High quality opts into the full voxel sphere.
  const useVoxel = part.layers !== undefined || q.sphere_voxelize
  const specs = useVoxel
    ? generateSphere({
        center,
        diameter,
        layers: (part.layers ?? q.sphere_layers) as 3 | 5 | 7,
      })
    : generateStairStepSphere({ center, diameter })
  return specs.map((s) => ({ from: s.from, to: s.to, faceSlots: slots }))
}

function expandRing(
  rp: ResolvedPart,
  part: RingPart,
  params: Record<string, number>,
  q: QualityDefaults,
): Cuboid[] {
  const slots = defaultFaceSlots(part.kind)
  const o = rp.origin
  const height = resolveNum(part.height, params)
  const center: Vec3 =
    part.axis === 'y'
      ? [o[0], o[1] + height / 2, o[2]]
      : part.axis === 'x'
        ? [o[0] + height / 2, o[1], o[2]]
        : [o[0], o[1], o[2] + height / 2]
  const specs = generateRing({
    center,
    axis: part.axis,
    outer: resolveNum(part.outer, params),
    inner: resolveNum(part.inner, params),
    height,
    resolution: part.resolution ?? q.ring_resolution,
  })
  return specs.map((s) => ({ from: s.from, to: s.to, faceSlots: slots }))
}

function expandCuboid(_rp: ResolvedPart, part: CuboidPart, _params: Record<string, number>): Cuboid[] {
  // Use the resolved bbox (anchor.ts already resolved @vars for size).
  return [
    {
      from: _rp.bbox.min,
      to: _rp.bbox.max,
      faceSlots: defaultFaceSlots(part.kind),
    },
  ]
}

function expandTaperedPrism(
  rp: ResolvedPart,
  part: TaperedPrismPart,
  params: Record<string, number>,
  q: QualityDefaults,
): Cuboid[] {
  const segments = Math.max(1, part.segments ?? q.tapered_segments)
  const tipStyle = part.tip_style ?? 'blunt'
  // `cross_section: "octagon"` becomes a no-op unless quality tier permits the
  // overlay. Medium / low ignore the request — they keep the blade as plain
  // axis-aligned segments (which look fine under textures).
  const crossSection =
    (part.cross_section ?? 'square') === 'octagon' && q.emit_octagon_overlay ? 'octagon' : 'square'
  const out: Cuboid[] = []

  // Resolve @var references for length and per-section size.
  const len = resolveNum(part.length, params)
  const baseSize: [number, number] = [
    resolveNum(part.base_size[0], params),
    resolveNum(part.base_size[1], params),
  ]
  const tipSize: [number, number] = [
    resolveNum(part.tip_size[0], params),
    resolveNum(part.tip_size[1], params),
  ]
  const segLen = len / segments
  const tipSegments = tipStyle === 'pointed_rot' ? segments - 1 : segments

  for (let i = 0; i < tipSegments; i++) {
    const t = segments === 1 ? 0.5 : (i + 0.5) / segments
    const [su, sv] = lerpSize(baseSize, tipSize, t)
    const main = taperedSegment(rp, part, i * segLen, (i + 1) * segLen, su, sv)
    out.push(main)
    if (crossSection === 'octagon') {
      const overlay = octagonalOverlay(main, part.axis, 0.85)
      if (overlay) out.push(overlay)
    }
  }
  if (tipStyle === 'pointed_rot' && tipSegments < segments) {
    const i = segments - 1
    const t = (i + 0.5) / segments
    const [su, sv] = lerpSize(baseSize, tipSize, t)
    const seg = taperedSegment(rp, part, i * segLen, (i + 1) * segLen, su, sv)
    const pivotAxis: 'x' | 'y' | 'z' = part.axis === 'y' ? 'x' : 'y'
    const origin: Vec3 = [
      (seg.from[0] + seg.to[0]) / 2,
      seg.from[1],
      (seg.from[2] + seg.to[2]) / 2,
    ]
    seg.rotation = { origin, axis: pivotAxis, angle: -45 }
    out.push(seg)
  }
  return out
}

function octagonalOverlay(
  main: Cuboid,
  axis: 'x' | 'y' | 'z',
  scale: number,
): Cuboid | null {
  // Place a copy of the main cuboid, scaled to `scale`, rotated 45° around
  // `axis`. The cuboid is centered on the main's centroid and shrunk slightly
  // so its 45°-rotated silhouette doesn't extend beyond the main's bbox.
  const cx = (main.from[0] + main.to[0]) / 2
  const cy = (main.from[1] + main.to[1]) / 2
  const cz = (main.from[2] + main.to[2]) / 2
  const w = (main.to[0] - main.from[0]) * scale
  const h = (main.to[1] - main.from[1]) * scale
  const d = (main.to[2] - main.from[2]) * scale
  let from: Vec3
  let to: Vec3
  // For the rotated overlay to "fake" the octagon, the dimensions perpendicular
  // to the rotation axis should be the rotated. The dimension along the
  // rotation axis matches the main (no change visible).
  if (axis === 'y') {
    from = [cx - w / 2, main.from[1], cz - d / 2]
    to = [cx + w / 2, main.to[1], cz + d / 2]
  } else if (axis === 'x') {
    from = [main.from[0], cy - h / 2, cz - d / 2]
    to = [main.to[0], cy + h / 2, cz + d / 2]
  } else {
    from = [cx - w / 2, cy - h / 2, main.from[2]]
    to = [cx + w / 2, cy + h / 2, main.to[2]]
  }
  return {
    from,
    to,
    rotation: { origin: [cx, cy, cz], axis, angle: 45 },
    faceSlots: main.faceSlots,
  }
}

function taperedSegment(
  rp: ResolvedPart,
  part: TaperedPrismPart,
  axisStart: number,
  axisEnd: number,
  perpU: number,
  perpV: number,
): Cuboid {
  // Place a cuboid centered on rp.origin's perpendicular plane, with
  // axis extent from origin[axisIdx]+axisStart to origin[axisIdx]+axisEnd.
  const axisIdx = axisIndex(part.axis)
  const halfU = perpU / 2
  const halfV = perpV / 2
  const o = rp.origin
  let from: Vec3, to: Vec3
  if (part.axis === 'y') {
    from = [o[0] - halfU, o[1] + axisStart, o[2] - halfV]
    to = [o[0] + halfU, o[1] + axisEnd, o[2] + halfV]
  } else if (part.axis === 'x') {
    from = [o[0] + axisStart, o[1] - halfU, o[2] - halfV]
    to = [o[0] + axisEnd, o[1] + halfU, o[2] + halfV]
  } else {
    from = [o[0] - halfU, o[1] - halfV, o[2] + axisStart]
    to = [o[0] + halfU, o[1] + halfV, o[2] + axisEnd]
  }
  return {
    from,
    to,
    faceSlots: bladeFaceSlots(part.axis),
  }
  void axisIdx
}

function lerpSize(base: [number, number], tip: [number, number], t: number): [number, number] {
  return [base[0] + (tip[0] - base[0]) * t, base[1] + (tip[1] - base[1]) * t]
}

function axisIndex(axis: 'x' | 'y' | 'z'): 0 | 1 | 2 {
  return axis === 'x' ? 0 : axis === 'y' ? 1 : 2
}

/** Default face slot assignment per part kind. Domain-named slots; the
 *  compiler maps them to the user's asdsl.textures or falls back to the
 *  first declared slot. */
function defaultFaceSlots(kind: Part['kind']): Partial<Record<McFace, string>> {
  switch (kind) {
    case 'cuboid':
      return {
        east: 'metal_warm',
        west: 'metal_warm',
        up: 'metal_warm',
        down: 'metal_warm',
        south: 'metal_warm',
        north: 'metal_warm',
      }
    case 'tapered_prism':
      return bladeFaceSlots('y')
    case 'chamfered_cuboid':
      return {
        east: 'metal_warm',
        west: 'metal_warm',
        up: 'metal_warm',
        down: 'metal_warm',
        south: 'metal_warm',
        north: 'metal_warm',
      }
    case 'cylinder':
      return {
        east: 'wood',
        west: 'wood',
        up: 'wood',
        down: 'wood',
        south: 'wood',
        north: 'wood',
      }
    case 'sphere':
      return {
        east: 'gem',
        west: 'gem',
        up: 'gem',
        down: 'gem',
        south: 'gem',
        north: 'gem',
      }
    case 'ring':
      return {
        east: 'metal_warm',
        west: 'metal_warm',
        up: 'metal_warm',
        down: 'metal_warm',
        south: 'metal_warm',
        north: 'metal_warm',
      }
  }
}

/** Blade face slots — flat faces use blade_body, edges use blade_edge. */
function bladeFaceSlots(axis: 'x' | 'y' | 'z'): Partial<Record<McFace, string>> {
  // For axis=y (vertical blade), the "flat" sides are north/south (front/back
  // when blade is held in front of you) and the "edges" are east/west.
  if (axis === 'y') {
    return {
      north: 'blade_body',
      south: 'blade_body',
      east: 'blade_edge',
      west: 'blade_edge',
      up: 'blade_edge',
      down: 'blade_body',
    }
  }
  if (axis === 'x') {
    return {
      east: 'blade_body',
      west: 'blade_body',
      north: 'blade_edge',
      south: 'blade_edge',
      up: 'blade_edge',
      down: 'blade_edge',
    }
  }
  return {
    north: 'blade_body',
    south: 'blade_body',
    east: 'blade_edge',
    west: 'blade_edge',
    up: 'blade_edge',
    down: 'blade_edge',
  }
}

function uvForFace(c: Cuboid, face: McFace): [number, number, number, number] {
  const w = Math.abs(c.to[0] - c.from[0])
  const h = Math.abs(c.to[1] - c.from[1])
  const d = Math.abs(c.to[2] - c.from[2])
  let u = 0, v = 0
  if (face === 'east' || face === 'west') {
    u = d
    v = h
  } else if (face === 'up' || face === 'down') {
    u = w
    v = d
  } else {
    u = w
    v = h
  }
  return [0, 0, Math.min(u, 16), Math.min(v, 16)]
}

function buildDisplay(d?: Asdsl['display']): McModel['display'] {
  const guiZ = d?.gui_rotation_z ?? 45
  // Default scale 0.65 so a full-length sword (blade y 0..16 + hilt -8..0)
  // fits the GUI camera without cropping. User can override via gui_scale.
  const scale = d?.gui_scale ?? 0.65
  return {
    gui: {
      rotation: [30, 225, guiZ] as [number, number, number],
      translation: [0, -2, 0] as [number, number, number],
      scale: [scale, scale, scale] as [number, number, number],
    },
    ground: {
      translation: [0, 2, 0] as [number, number, number],
      scale: [0.5, 0.5, 0.5] as [number, number, number],
    },
    fixed: {
      rotation: [0, 180, 0] as [number, number, number],
      scale: [scale, scale, scale] as [number, number, number],
    },
    thirdperson_righthand: {
      rotation: [0, -90, 55] as [number, number, number],
      translation: [0, 4, 0.5] as [number, number, number],
      scale: [0.85, 0.85, 0.85] as [number, number, number],
    },
    firstperson_righthand: {
      rotation: [0, -90, 25] as [number, number, number],
      translation: [1.13, 3.2, 1.13] as [number, number, number],
      scale: [0.68, 0.68, 0.68] as [number, number, number],
    },
  }
}
