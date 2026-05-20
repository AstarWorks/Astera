/**
import type { Vec3 } from '../vec3'
 * ASDSL state singleton — the LLM's "feature tree". Every tool call mutates
 * this dict; after each mutation the compile() runs, the result is pushed to
 * the existing model state (which the viewport renders), and the truth
 * envelope is returned to the caller.
 */
import { compile } from './compile'
import { resolve, unwrapHandle } from './anchor'
import { HINTS, ok, warn, err } from './envelope'
import { runAssertions } from './assertions'
import type { Asdsl, AssertionResult, Envelope, Hint, Part} from './types'
import { persistDebounced, restore } from '../persist'
import { _internals } from '../model-state'
import { flushImageCache } from '../image-cache'

const PERSIST_KEY = '__asdsl_state__'

type PersistedAsdsl = { asdsl: Asdsl }

function emptyAsdsl(name = ''): Asdsl {
  return {
    name,
    params: {},
    parts: {},
    textures: {},
    face_overrides: {},
    display: { gui_rotation_z: 45, gui_scale: 1.0 },
  }
}

const state: PersistedAsdsl = restore<PersistedAsdsl>() ?? { asdsl: emptyAsdsl() }

export function getAsdsl(): Asdsl {
  return state.asdsl
}

export function resetAsdsl(name = ''): Envelope<Asdsl> {
  state.asdsl = emptyAsdsl(name)
  persist()
  // B9 / ADR-0062 §5: image cache lifetime = ASDSL session lifetime.
  flushImageCache()
  return ok(state.asdsl, { parts_removed: [] })
}

function persist() {
  persistDebounced(state)
}

const PART_KINDS = [
  'cuboid',
  'tapered_prism',
  'chamfered_cuboid',
  'cylinder',
  'sphere',
  'ring',
] as const

/**
 * Validate a part spec against its kind's schema. Returns hints for any
 * problem found. Conservative: catches missing required fields and obvious
 * out-of-range values. The compiler's Ajv runs the full MC schema after.
 */
function validatePart(name: string, part: Partial<Part>, available_parts: string[]): { ok: boolean; hints: Hint[] } {
  const hints: Hint[] = []
  if (!part.kind) {
    return { ok: false, hints: [{ code: 'MISSING_KIND', text: `Part '${name}' missing 'kind'. Available: ${PART_KINDS.join(', ')}` }] }
  }
  if (!PART_KINDS.includes(part.kind as (typeof PART_KINDS)[number])) {
    hints.push(HINTS.UNKNOWN_KIND(part.kind, [...PART_KINDS]))
    return { ok: false, hints }
  }
  if (part.relative_to) {
    const ref = unwrapHandle(part.relative_to)
    if (!available_parts.includes(ref)) {
      hints.push(HINTS.UNKNOWN_PART(ref, available_parts))
      return { ok: false, hints }
    }
  }
  return { ok: true, hints }
}

// --- Mutating operations -----------------------------------------------------

export function placePart(name: string, part: Part): Envelope<{ asdsl: Asdsl; model_compiled?: boolean }> {
  // ADR-0062 B6: place_part rejects existing names. Use patch_part to modify.
  if (name in state.asdsl.parts) {
    return err('ALREADY_EXISTS', [HINTS.ALREADY_EXISTS(name)])
  }
  const validation = validatePart(name, part, Object.keys(state.asdsl.parts))
  if (!validation.ok) {
    return err('PLACE_PART_INVALID', validation.hints)
  }
  state.asdsl.parts[name] = part as Part
  return finalize('place_part', { parts_added: [name] })
}

export function patchPart(name: string, patch: Partial<Part>): Envelope<{ asdsl: Asdsl }> {
  const existing = state.asdsl.parts[name]
  if (!existing) {
    return err('UNKNOWN_PART', [HINTS.UNKNOWN_PART(name, Object.keys(state.asdsl.parts))])
  }
  // Merge: only placement fields (relative_to, align, delta_*, anchor) and
  // sizing fields (size, length, base_size, tip_size). Type is preserved.
  state.asdsl.parts[name] = { ...existing, ...patch, kind: existing.kind } as Part
  return finalize('patch_part', { parts_modified: [name] })
}

export function removePart(name: string): Envelope<{ asdsl: Asdsl }> {
  if (!(name in state.asdsl.parts)) {
    return err('UNKNOWN_PART', [HINTS.UNKNOWN_PART(name, Object.keys(state.asdsl.parts))])
  }
  delete state.asdsl.parts[name]
  return finalize('remove_part', { parts_removed: [name] })
}

export function setVariable(name: string, value: number): Envelope<{ asdsl: Asdsl }> {
  state.asdsl.params[name] = value
  return finalize('set_variable', {})
}

export function setQuality(q: 'low' | 'medium' | 'high'): Envelope<{ asdsl: Asdsl }> {
  if (q !== 'low' && q !== 'medium' && q !== 'high') {
    return err('INVALID_QUALITY', [
      { code: 'INVALID_QUALITY', text: `quality must be low|medium|high, got "${q}"` },
    ])
  }
  state.asdsl.quality = q
  return finalize('set_quality', {})
}

export function setTexture(slot: string, slug: string): Envelope<{ asdsl: Asdsl }> {
  const had = slot in state.asdsl.textures
  state.asdsl.textures[slot] = slug
  return finalize('set_texture', had ? {} : { texture_refs_added: [slot] })
}

export function setFaceTexture(part_name: string, face: string, slot: string): Envelope<{ asdsl: Asdsl }> {
  if (!(part_name in state.asdsl.parts)) {
    return err('UNKNOWN_PART', [HINTS.UNKNOWN_PART(part_name, Object.keys(state.asdsl.parts))])
  }
  if (!(state.asdsl.face_overrides[part_name])) {
    state.asdsl.face_overrides[part_name] = {}
  }
  state.asdsl.face_overrides[part_name]![face as 'east'] = slot
  return finalize('set_face_texture', { parts_modified: [part_name] })
}

// --- Finalize: compile, sync to model state, return envelope -----------------

function finalize(
  _op: string,
  changes: Envelope<unknown>['changes'],
): Envelope<{ asdsl: Asdsl; model_compiled: boolean }> {
  // Try to compile. On failure we still keep the dsl state but warn.
  const compiled = compile(state.asdsl)
  if (!compiled.ok) {
    persist()
    return {
      ok: false,
      status: 'ERROR',
      error_code: compiled.error_code,
      hints: compiled.hints.map((h) => ({ code: h.code, text: h.text })),
      changes: { ...changes, summary: buildSummary(changes) },
      data: { asdsl: state.asdsl, model_compiled: false },
    }
  }
  // Push to existing model state via internals (keeps Three.js viewport in sync).
  _internals.setModelDirect?.(compiled.model)
  persist()

  // ADR-0062 B7: evaluate declarative assertions after compile.
  const diagnostics: AssertionResult[] = runAssertions(
    compiled.resolved,
    state.asdsl,
    { model: compiled.model, per_part_indices: compiled.per_part_indices },
  )
  const failed = diagnostics.filter((d) => !d.passed)

  const fullChanges: Envelope<unknown>['changes'] = {
    ...changes,
    voxel_count_delta: compiled.model.elements?.length ?? 0,
  }
  fullChanges.summary = buildSummary(fullChanges)

  const data = { asdsl: state.asdsl, model_compiled: true }

  // Failed assertion(s) → WARNING (still ok=true; compile succeeded).
  if (failed.length > 0) {
    const hints: Hint[] = failed.map((d) =>
      HINTS.ASSERTION_FAILED(d.kind, d.part_name, d.detail),
    )
    const env = warn(data, hints, fullChanges)
    if (diagnostics.length > 0) env.diagnostics = diagnostics
    return env
  }

  const env = ok(data, fullChanges)
  if (diagnostics.length > 0) env.diagnostics = diagnostics
  return env
}

/** Build a human-readable one-liner from the changes dict (ADR-0062 B10). */
function buildSummary(c: Envelope<unknown>['changes']): string {
  const parts: string[] = []
  if (c.parts_added && c.parts_added.length > 0) {
    parts.push(`added ${c.parts_added.length} part${plural(c.parts_added.length)} (${c.parts_added.join(', ')})`)
  }
  if (c.parts_modified && c.parts_modified.length > 0) {
    parts.push(`modified ${c.parts_modified.length} (${c.parts_modified.join(', ')})`)
  }
  if (c.parts_removed && c.parts_removed.length > 0) {
    parts.push(`removed ${c.parts_removed.length} (${c.parts_removed.join(', ')})`)
  }
  if (c.texture_refs_added && c.texture_refs_added.length > 0) {
    parts.push(`bound texture${plural(c.texture_refs_added.length)} (${c.texture_refs_added.join(', ')})`)
  }
  if (c.texture_refs_removed && c.texture_refs_removed.length > 0) {
    parts.push(`unbound texture${plural(c.texture_refs_removed.length)} (${c.texture_refs_removed.join(', ')})`)
  }
  if (parts.length === 0) return 'no part changes'
  return parts.join('; ')
}

function plural(n: number): string {
  return n === 1 ? '' : 's'
}

// --- Describe (for the LLM) --------------------------------------------------

export type DescribeResult = {
  asdsl: Asdsl
  summary: string
  parts_summary: { name: string; kind: string; bbox: { min: Vec3; max: Vec3 }; element_count: number }[]
}

export function describe(): DescribeResult {
  const r = resolve(state.asdsl)
  if (!r.ok) {
    return {
      asdsl: state.asdsl,
      summary: 'unresolved (parts have anchor errors)',
      parts_summary: [],
    }
  }
  const compiled = compile(state.asdsl)
  const counts: Record<string, number> = {}
  if (compiled.ok) {
    for (const [name, idxs] of Object.entries(compiled.per_part_indices)) {
      counts[name] = idxs.length
    }
  }
  const parts_summary = r.order.map((name) => {
    const rp = r.resolved.get(name)!
    return {
      name,
      kind: rp.part.kind,
      bbox: rp.bbox,
      element_count: counts[name] ?? 0,
    }
  })
  const totalElements = parts_summary.reduce((s, p) => s + p.element_count, 0)
  const partsByKind = parts_summary.reduce<Record<string, number>>((acc, p) => {
    acc[p.kind] = (acc[p.kind] ?? 0) + 1
    return acc
  }, {})
  const summary = `${parts_summary.length} parts (${Object.entries(partsByKind)
    .map(([k, n]) => `${n} ${k}`)
    .join(', ')}) → ${totalElements} MC cuboids. Textures: ${Object.keys(state.asdsl.textures).length} slots.`
  return { asdsl: state.asdsl, summary, parts_summary }
}
