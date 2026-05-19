/**
 * Anchor resolver — converts the LLM-friendly named-handle placement
 * (`relative_to: "blade", align: "base_center"`) into world-space coordinates
 * by topologically sorting the parts dict and walking dependencies.
 *
 * Convention: every part has a "placement origin" = the world position of its
 * bottom_center anchor. relative_to+align computes a target world point; the
 * part's bottom_center is placed at that point + (delta_x, delta_y, delta_z).
 *
 * "origin" anchor (the default base case) means placement_origin = [8, 0, 8]
 * — MC item models center on x=8, z=8 with y=0 being the ground plane.
 */
import { HINTS } from './envelope'
import { ResolveError, resolveNum } from './util'
import type { AnchorSlot, Asdsl, Hint, Part, Vec3 } from './types'

export type ResolvedPart = {
  name: string
  /** The part's placement origin = its bottom_center in world space. */
  origin: Vec3
  /** Local extents from placement origin. min/max of the part's bbox. */
  extents: { min: Vec3; max: Vec3 }
  /** World bbox = extents + origin. */
  bbox: { min: Vec3; max: Vec3 }
  /** Raw part spec from the dsl. */
  part: Part
}

export type ResolveResult =
  | { ok: true; resolved: Map<string, ResolvedPart>; order: string[] }
  | { ok: false; error_code: string; hints: Hint[] }

/**
 * Compute a part's local extents (min/max from its bottom_center) given the
 * part spec and resolved numeric params. This is geometry-only — no anchor.
 *
 * For each meso-primitive kind, the bbox is computed conservatively (the
 * convex hull of all the cuboids the compiler will emit). The actual cuboid
 * generation lives in `compile.ts`.
 */
export function localExtents(part: Part, params: Record<string, number>): { min: Vec3; max: Vec3 } {
  const num = (v: unknown) => resolveNum(v as number | string, params)
  switch (part.kind) {
    case 'cuboid': {
      const w = num(part.size[0])
      const h = num(part.size[1])
      const d = num(part.size[2])
      return { min: [-w / 2, 0, -d / 2], max: [w / 2, h, d / 2] }
    }
    case 'tapered_prism': {
      const bw = num(part.base_size[0])
      const bd = num(part.base_size[1])
      const tw = num(part.tip_size[0])
      const td = num(part.tip_size[1])
      const baseW = Math.max(bw, tw)
      const baseD = Math.max(bd, td)
      const len = num(part.length)
      switch (part.axis) {
        case 'y':
          return { min: [-baseW / 2, 0, -baseD / 2], max: [baseW / 2, len, baseD / 2] }
        case 'x':
          return { min: [0, -baseW / 2, -baseD / 2], max: [len, baseW / 2, baseD / 2] }
        case 'z':
          return { min: [-baseW / 2, -baseD / 2, 0], max: [baseW / 2, baseD / 2, len] }
      }
    }
    case 'chamfered_cuboid': {
      const w = num(part.size[0])
      const h = num(part.size[1])
      const d = num(part.size[2])
      return { min: [-w / 2, 0, -d / 2], max: [w / 2, h, d / 2] }
    }
    case 'cylinder': {
      const r = num(part.diameter) / 2
      const len = num(part.length)
      switch (part.axis) {
        case 'y':
          return { min: [-r, 0, -r], max: [r, len, r] }
        case 'x':
          return { min: [0, -r, -r], max: [len, r, r] }
        case 'z':
          return { min: [-r, -r, 0], max: [r, r, len] }
      }
    }
    case 'sphere': {
      const r = num(part.diameter) / 2
      return { min: [-r, 0, -r], max: [r, num(part.diameter), r] }
    }
    case 'ring': {
      const r = num(part.outer)
      const h = num(part.height)
      switch (part.axis) {
        case 'y':
          return { min: [-r, 0, -r], max: [r, h, r] }
        case 'x':
          return { min: [0, -r, -r], max: [h, r, r] }
        case 'z':
          return { min: [-r, -r, 0], max: [r, r, h] }
      }
    }
  }
}

/**
 * Unwrap `@cad[name]` cross-iteration handle syntax to a bare part name.
 * Bare names pass through unchanged. The handle form is self-documenting
 * (signals "this references an existing part, do not create a new one")
 * and resolves identically to the bare form via this util (ADR-0062 B11).
 */
export function unwrapHandle(ref: string): string {
  const m = ref.match(/^@cad\[([^\]]+)\]$/)
  return m ? m[1]! : ref
}

/** Compute the world-space point of a slot on a resolved part. */
export function anchorPoint(rp: ResolvedPart, slot: AnchorSlot): Vec3 {
  const cx = (rp.bbox.min[0] + rp.bbox.max[0]) / 2
  const cy = (rp.bbox.min[1] + rp.bbox.max[1]) / 2
  const cz = (rp.bbox.min[2] + rp.bbox.max[2]) / 2
  switch (slot) {
    case 'origin':
    case 'bottom_center':
    case 'base_center':
      return [cx, rp.bbox.min[1], cz]
    case 'center':
      return [cx, cy, cz]
    case 'top_center':
    case 'tip_center':
      return [cx, rp.bbox.max[1], cz]
    case 'front_center':
      return [cx, cy, rp.bbox.max[2]]
    case 'back_center':
      return [cx, cy, rp.bbox.min[2]]
    case 'left_center':
      return [rp.bbox.min[0], cy, cz]
    case 'right_center':
      return [rp.bbox.max[0], cy, cz]
  }
}


/**
 * Resolve the whole parts dict, producing a topological ordering and world-
 * space bboxes for each part. Returns hints on cycle or unknown references.
 */
export function resolve(asdsl: Asdsl): ResolveResult {
  // Build dependency graph: part → list of refs to other parts.
  // relative_to may be a bare name or `@cad[name]` handle (ADR-0062 B11).
  const partNames = Object.keys(asdsl.parts)
  const deps = new Map<string, string[]>()
  for (const name of partNames) {
    const part = asdsl.parts[name]!
    const rawRef = part.relative_to
    if (rawRef) {
      const ref = unwrapHandle(rawRef)
      if (!partNames.includes(ref)) {
        return {
          ok: false,
          error_code: 'UNKNOWN_PART',
          hints: [HINTS.UNKNOWN_PART(ref, partNames)],
        }
      }
      deps.set(name, [ref])
    } else {
      deps.set(name, [])
    }
  }

  // Detect cycles + topological sort (Kahn's algorithm).
  const incoming = new Map<string, number>()
  for (const name of partNames) incoming.set(name, 0)
  for (const [, refs] of deps) {
    for (const r of refs) incoming.set(r, (incoming.get(r) ?? 0) + 1)
  }
  // Find roots (parts that nothing depends on).
  const order: string[] = []
  const queue: string[] = []
  for (const name of partNames) {
    if ((incoming.get(name) ?? 0) === 0) queue.push(name)
  }
  // Walk in reverse-topo: roots last (so dependents come first... we actually
  // want dependencies first). Use a different traversal:
  // — Start from leaves (parts with no relative_to), process them, then process
  //   parts that depend on them.
  // Restate: relative_to means "this part depends on that part being placed
  // first". So process anchor=origin parts first, then parts whose relative_to
  // target is already processed.
  const resolved = new Map<string, ResolvedPart>()
  const remaining = new Set(partNames)

  while (remaining.size > 0) {
    let progress = false
    for (const name of [...remaining]) {
      const part = asdsl.parts[name]!
      const ref = part.relative_to ? unwrapHandle(part.relative_to) : undefined
      if (ref && !resolved.has(ref)) continue // wait for dependency
      try {
        const rp = placePart(name, part, resolved, asdsl.params)
        resolved.set(name, rp)
        order.push(name)
        remaining.delete(name)
        progress = true
      } catch (e) {
        if (e instanceof ResolveError) {
          return { ok: false, error_code: e.hint.code ?? 'RESOLVE_ERROR', hints: [e.hint] }
        }
        return {
          ok: false,
          error_code: 'INTERNAL',
          hints: [{ text: String(e) }],
        }
      }
    }
    if (!progress) {
      // Remaining parts must form a cycle.
      const cycle = Array.from(remaining)
      return {
        ok: false,
        error_code: 'DEPENDENCY_CYCLE',
        hints: [HINTS.DEPENDENCY_CYCLE(cycle)],
      }
    }
  }

  return { ok: true, resolved, order }
}

const MC_ORIGIN: Vec3 = [8, 0, 8]

function placePart(
  name: string,
  part: Part,
  resolved: Map<string, ResolvedPart>,
  params: Record<string, number>,
): ResolvedPart {
  const extents = localExtents(part, params)
  let origin: Vec3
  if (!part.relative_to) {
    origin = [...MC_ORIGIN]
  } else {
    const target = resolved.get(unwrapHandle(part.relative_to))!
    const slot = part.align ?? 'top_center'
    const targetPoint = anchorPoint(target, slot)
    origin = [...targetPoint]
  }
  // Apply delta.
  if (part.delta_x) origin[0] += part.delta_x
  if (part.delta_y) origin[1] += part.delta_y
  if (part.delta_z) origin[2] += part.delta_z

  const bbox = {
    min: [origin[0] + extents.min[0], origin[1] + extents.min[1], origin[2] + extents.min[2]] as Vec3,
    max: [origin[0] + extents.max[0], origin[1] + extents.max[1], origin[2] + extents.max[2]] as Vec3,
  }
  return { name, origin, extents, bbox, part }
}
