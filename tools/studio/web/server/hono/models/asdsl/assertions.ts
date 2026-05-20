/**
 * ASDSL declarative assertion runner (ADR-0062 B7).
 *
 * Each Part may declare an `assertions[]` array. After compile, this runner
 * evaluates every assertion and returns AssertionResult[] (one per
 * part × assertion). Failed assertions are surfaced as WARNING + diagnostics
 * in the truth envelope; they never block compile.
 *
 * Inspired by cad-khana's diagnostics-first pattern (survey §10.1.1).
 */
import { anchorPoint, unwrapHandle, type ResolvedPart } from './anchor'
import type { Asdsl, Assertion, AssertionResult, AnchorSlot, Vec3 } from './types'
import type { McModel } from '#shared/types'

const ALIGNED_THRESHOLD_VOXELS = 0.5

const ANCHOR_SLOTS: AnchorSlot[] = [
  'origin',
  'center',
  'top_center',
  'bottom_center',
  'front_center',
  'back_center',
  'left_center',
  'right_center',
  'base_center',
  'tip_center',
]

type CompiledRef = { model: McModel; per_part_indices: Record<string, number[]> }

/** Evaluate all declared assertions across every part. */
export function runAssertions(
  resolved: Map<string, ResolvedPart>,
  asdsl: Asdsl,
  compiled?: CompiledRef,
): AssertionResult[] {
  const out: AssertionResult[] = []
  for (const [partName, part] of Object.entries(asdsl.parts)) {
    const assertions = part.assertions ?? []
    for (const a of assertions) {
      out.push(evaluate(a, partName, resolved, compiled))
    }
  }
  return out
}

function evaluate(
  a: Assertion,
  partName: string,
  resolved: Map<string, ResolvedPart>,
  compiled: CompiledRef | undefined,
): AssertionResult {
  switch (a.kind) {
    case 'aligned_with':
      return evalAlignedWith(a, partName, resolved)
    case 'no_self_intersection':
      return evalNoSelfIntersection(partName, compiled)
    case 'fits_within_voxel_envelope':
      return evalFitsEnvelope(a, partName, resolved)
    case 'clearance_min':
      return evalClearanceMin(a, partName, resolved)
  }
}

// --- aligned_with -----------------------------------------------------------

function evalAlignedWith(
  a: Extract<Assertion, { kind: 'aligned_with' }>,
  partName: string,
  resolved: Map<string, ResolvedPart>,
): AssertionResult {
  const left = parseAnchorRef(a.a, resolved)
  if (!left.ok) return fail(a.kind, partName, left.detail)
  const right = parseAnchorRef(a.b, resolved)
  if (!right.ok) return fail(a.kind, partName, right.detail)
  const dx = left.point[0] - right.point[0]
  const dy = left.point[1] - right.point[1]
  const dz = left.point[2] - right.point[2]
  const dist = Math.sqrt(dx * dx + dy * dy + dz * dz)
  if (dist <= ALIGNED_THRESHOLD_VOXELS) {
    return pass(a.kind, partName)
  }
  return fail(
    a.kind,
    partName,
    `'${a.a}' and '${a.b}' are ${dist.toFixed(2)} voxels apart (threshold ${ALIGNED_THRESHOLD_VOXELS}).`,
  )
}

type ParsedAnchorRef =
  | { ok: true; point: Vec3 }
  | { ok: false; detail: string }

/** Parse "part.slot" → resolve the part (handle-aware) → compute anchor world point. */
function parseAnchorRef(ref: string, resolved: Map<string, ResolvedPart>): ParsedAnchorRef {
  const dot = ref.lastIndexOf('.')
  if (dot < 0) {
    return { ok: false, detail: `'${ref}' must be 'part.slot' (e.g., 'blade.base_center').` }
  }
  const partRefRaw = ref.slice(0, dot)
  const slot = ref.slice(dot + 1) as AnchorSlot
  const partRef = unwrapHandle(partRefRaw)
  const rp = resolved.get(partRef)
  if (!rp) {
    return { ok: false, detail: `Part '${partRef}' not found in resolved set.` }
  }
  if (!ANCHOR_SLOTS.includes(slot)) {
    return { ok: false, detail: `Slot '${slot}' is not a valid AnchorSlot.` }
  }
  return { ok: true, point: anchorPoint(rp, slot) }
}

// --- no_self_intersection ---------------------------------------------------

function evalNoSelfIntersection(
  partName: string,
  compiled: CompiledRef | undefined,
): AssertionResult {
  if (!compiled) {
    return fail('no_self_intersection', partName, 'Compiled model unavailable.')
  }
  const indices = compiled.per_part_indices[partName] ?? []
  if (indices.length < 2) {
    return pass('no_self_intersection', partName)
  }
  const els = compiled.model.elements ?? []
  for (let i = 0; i < indices.length; i++) {
    const ai = indices[i]!
    const a = els[ai]
    if (!a) continue
    for (let j = i + 1; j < indices.length; j++) {
      const bj = indices[j]!
      const b = els[bj]
      if (!b) continue
      if (aabbVolumeOverlap(a.from, a.to, b.from, b.to)) {
        return fail(
          'no_self_intersection',
          partName,
          `cuboids #${ai} and #${bj} overlap by volume.`,
        )
      }
    }
  }
  return pass('no_self_intersection', partName)
}

/** Strict volumetric overlap of two AABBs. Edge-touching returns false. */
function aabbVolumeOverlap(aFrom: Vec3, aTo: Vec3, bFrom: Vec3, bTo: Vec3): boolean {
  for (let i = 0; i < 3; i++) {
    if (aTo[i]! <= bFrom[i]!) return false
    if (bTo[i]! <= aFrom[i]!) return false
  }
  return true
}

// --- fits_within_voxel_envelope ---------------------------------------------

function evalFitsEnvelope(
  a: Extract<Assertion, { kind: 'fits_within_voxel_envelope' }>,
  partName: string,
  resolved: Map<string, ResolvedPart>,
): AssertionResult {
  const rp = resolved.get(partName)
  if (!rp) return fail(a.kind, partName, 'Part not in resolved set.')
  const envMax: Vec3 = [a.width, a.height, a.depth]
  for (let i = 0; i < 3; i++) {
    if (rp.bbox.min[i]! < 0) {
      return fail(a.kind, partName, `bbox.min[${axisLabel(i)}]=${rp.bbox.min[i]} < 0.`)
    }
    if (rp.bbox.max[i]! > envMax[i]!) {
      return fail(
        a.kind,
        partName,
        `bbox.max[${axisLabel(i)}]=${rp.bbox.max[i]} > envelope ${envMax[i]}.`,
      )
    }
  }
  return pass(a.kind, partName)
}

// --- clearance_min ----------------------------------------------------------

function evalClearanceMin(
  a: Extract<Assertion, { kind: 'clearance_min' }>,
  partName: string,
  resolved: Map<string, ResolvedPart>,
): AssertionResult {
  const from = resolved.get(unwrapHandle(a.from))
  if (!from) return fail(a.kind, partName, `from-part '${a.from}' not resolved.`)
  const to = resolved.get(unwrapHandle(a.to))
  if (!to) return fail(a.kind, partName, `to-part '${a.to}' not resolved.`)
  const sep = chebyshevSeparation(from.bbox, to.bbox)
  if (sep >= a.voxels) {
    return pass(a.kind, partName)
  }
  return fail(
    a.kind,
    partName,
    `clearance ${sep.toFixed(2)} < required ${a.voxels}.`,
  )
}

/** L∞ separation between two AABBs (0 if they overlap on every axis). */
function chebyshevSeparation(
  a: { min: Vec3; max: Vec3 },
  b: { min: Vec3; max: Vec3 },
): number {
  let max = 0
  for (let i = 0; i < 3; i++) {
    const gap = Math.max(0, a.min[i]! - b.max[i]!, b.min[i]! - a.max[i]!)
    if (gap > max) max = gap
  }
  return max
}

// --- helpers ----------------------------------------------------------------

function pass(kind: string, partName: string): AssertionResult {
  return { kind, part_name: partName, passed: true }
}

function fail(kind: string, partName: string, detail: string): AssertionResult {
  return { kind, part_name: partName, passed: false, detail }
}

function axisLabel(i: number): 'x' | 'y' | 'z' {
  return i === 0 ? 'x' : i === 1 ? 'y' : 'z'
}
